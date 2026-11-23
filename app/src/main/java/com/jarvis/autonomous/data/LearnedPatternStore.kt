package com.jarvis.autonomous.data

import android.content.Context
import com.jarvis.autonomous.agent.LearnedPatternPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * v2 Gap 3 — on-device learned-pattern + instruction store
 * (release 1.9.0-PRO, vc 45).
 *
 * Storage (DESIGN §5.1): same I1 root, new record kind — NOT an
 * extension of `LearnedRoutineStore` (frozen schema) and NOT the doc
 * store. `filesDir/device-memory/patterns/<id>.json` +
 * `patterns-index.json`; `instructions/<id>.json` +
 * `instructions-index.json` (two indexes: patterns evict by use,
 * instructions never auto-evict). Reused from I1 verbatim: atomic
 * tmp+rename writes, fail-closed `fromJson`, safe ids, disk-read-
 * every-call (no in-memory cache), `synchronized(lock)`,
 * self-healing index rebuild (existence-only drift check).
 *
 * PRIVACY (hard boundary, reviewable in one grep): this file imports
 * NO networking — no OkHttp, no java.net.URL, no HttpURLConnection.
 * Nothing here can upload pattern/instruction content anywhere.
 * `maybeUploadLearnedPattern` never reads this store (enforced by a
 * CI grep step + a unit test that asserts the uploader's call sites
 * never reference this class).
 *
 * Caps (DESIGN §5.2): MAX_PATTERNS = 50 (LRU eviction by
 * lastUsedAtMs), MAX_INSTRUCTIONS = 30 (never auto-evicted — a
 * save past the cap is refused), MAX_PATTERN_STORE_BYTES = 512 KB
 * total (backstop; patterns are ~2 KB worst case).
 *
 * Every public store call reads from disk (no in-memory cache), so
 * the engine instance and any future UI instance see the same data.
 * All I/O must be called off the main thread (Dispatchers.IO).
 */
class LearnedPatternStore(private val storage: PatternStorage) {

    private val lock = Any()

    // ------------------------------------------------------------------
    // Patterns
    // ------------------------------------------------------------------

    /**
     * Save a pattern, or update the existing near-duplicate instead
     * of minting a new one (DESIGN §5.5: an enabled pattern scoring
     * ≥ 5 against the same goal is updated — useCount +1,
     * confidence per policy — never duplicated). Returns the id, or
     * null when any field trips the extraction filter (defence in
     * depth: a half-redacted pattern is worse than no pattern) or a
     * write fails.
     */
    fun saveOrUpdatePattern(pattern: LearnedPattern): String? {
        if (!isSaveablePattern(pattern)) return null
        val now = System.currentTimeMillis()
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadPatternIndexLocked()
            // Near-duplicate: update, don't mint.
            val queryKeywords = pattern.triggerKeywords
            val queryPhrase = pattern.triggerSignature.lowercase()
            var best: LearnedPatternMeta? = null
            var bestScore = -1
            for (meta in index.patterns.values) {
                if (!meta.enabled) continue
                val score = LearnedPatternPolicy.matchScore(
                    queryKeywords = queryKeywords,
                    patternKeywords = meta.triggerKeywords,
                    workKindMatches = meta.workKind == pattern.workKind,
                    phraseContainsSignature = queryPhrase.contains(meta.triggerSignature.lowercase())
                )
                if (score > bestScore) {
                    bestScore = score
                    best = meta
                }
            }
            if (best != null && bestScore >= LearnedPatternPolicy.DUPLICATE_MIN_SCORE) {
                val existing = getPatternLocked(best.id) ?: return null
                val updated = existing.copy(
                    useCount = existing.useCount + 1,
                    successCount = existing.successCount + 1,
                    consecutiveFailures = 0,
                    confidence = LearnedPatternPolicy.confidenceAfterSuccess(existing.confidence),
                    lastUsedAtMs = now
                )
                if (!storage.writePattern(updated.id, updated.toJson().toString())) return null
                index.patterns[updated.id] = metaOf(updated)
                if (!savePatternIndexLocked(index)) return null
                return updated.id
            }
            // Mint new (LRU eviction first, then byte-cap eviction).
            val evicted = ArrayList<String>()
            while (index.patterns.size >= MAX_PATTERNS) {
                val lru = index.patterns.values.minByOrNull { it.lastUsedAtMs } ?: break
                evictPatternLocked(index, lru.id)
                evicted.add(lru.id)
            }
            var totalBytes = index.patterns.values.sumOf { patternBytesLocked(it.id) }
            val newBytes = pattern.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong()
            while (index.patterns.isNotEmpty() && totalBytes + newBytes > MAX_PATTERN_STORE_BYTES) {
                val lru = index.patterns.values.minByOrNull { it.lastUsedAtMs } ?: break
                totalBytes -= patternBytesLocked(lru.id)
                evictPatternLocked(index, lru.id)
                evicted.add(lru.id)
            }
            val minted = pattern.copy(
                id = pattern.id.ifBlank { newPatternId() },
                createdAtMs = if (pattern.createdAtMs == 0L) now else pattern.createdAtMs,
                lastUsedAtMs = now
            )
            if (!storage.writePattern(minted.id, minted.toJson().toString())) return null
            index.patterns[minted.id] = metaOf(minted)
            if (!savePatternIndexLocked(index)) {
                storage.deletePattern(minted.id)
                return null
            }
            return minted.id
        }
    }

    /** Defence-in-depth filter: every field must pass before anything is saved. */
    fun isSaveablePattern(pattern: LearnedPattern): Boolean {
        if (pattern.triggerSignature.isBlank() || pattern.triggerSignature.length > 120) return false
        if (pattern.title.isBlank() || pattern.title.length > 120) return false
        if (pattern.steps.isEmpty() || pattern.steps.size > LearnedPatternPolicy.MAX_PATTERN_STEPS) return false
        for (step in pattern.steps) {
            if (!LearnedPatternPolicy.isLearnableStepLabel(step.label, step.kind)) return false
        }
        if (pattern.failure.isNotBlank() && !LearnedPatternPolicy.isLearnableNote(pattern.failure)) return false
        if (pattern.fix.isNotBlank() && !LearnedPatternPolicy.isLearnableNote(pattern.fix)) return false
        if (pattern.triggerKeywords.size > LearnedPatternPolicy.MAX_KEYWORDS) return false
        return true
    }

    /** Index-only lookup (DESIGN §5.3): never parses pattern files
     *  at dispatch — the ack budget cannot afford a full-corpus scan.
     *  Returns the best enabled pattern scoring ≥ 3 with confidence
     *  ≥ 0.5, or null. */
    fun findBestPattern(
        queryKeywords: List<String>,
        workKind: String,
        queryPhrase: String
    ): LearnedPatternMeta? {
        if (queryKeywords.isEmpty()) return null
        val phrase = queryPhrase.lowercase()
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadPatternIndexLocked()
            var best: LearnedPatternMeta? = null
            var bestScore = -1
            var bestConfidence = -1.0
            var bestLastUsed = -1L
            for (meta in index.patterns.values) {
                if (!meta.enabled) continue
                if (meta.confidence < LearnedPatternPolicy.APPLY_MIN_CONFIDENCE) continue
                val score = LearnedPatternPolicy.matchScore(
                    queryKeywords = queryKeywords,
                    patternKeywords = meta.triggerKeywords,
                    workKindMatches = meta.workKind == workKind,
                    phraseContainsSignature = phrase.contains(meta.triggerSignature.lowercase())
                )
                if (score < LearnedPatternPolicy.MATCH_MIN_SCORE) continue
                val better = score > bestScore ||
                    (score == bestScore && meta.confidence > bestConfidence) ||
                    (score == bestScore && meta.confidence == bestConfidence && meta.lastUsedAtMs > bestLastUsed)
                if (better) {
                    best = meta
                    bestScore = score
                    bestConfidence = meta.confidence
                    bestLastUsed = meta.lastUsedAtMs
                }
            }
            return best
        }
    }

    /** Index-only instruction lookup: up to 2 enabled instructions
     *  scoring ≥ 2, best first. */
    fun findInstructions(
        queryKeywords: List<String>,
        workKind: String,
        queryPhrase: String
    ): List<LearnedInstructionMeta> {
        if (queryKeywords.isEmpty()) return emptyList()
        val phrase = queryPhrase.lowercase()
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadInstructionIndexLocked()
            val scored = ArrayList<Pair<LearnedInstructionMeta, Int>>()
            for (meta in index.instructions.values) {
                if (!meta.enabled) continue
                if (workKind !in meta.appliesTo) continue
                val score = LearnedPatternPolicy.matchScore(
                    queryKeywords = queryKeywords,
                    patternKeywords = meta.scopeKeywords,
                    workKindMatches = true,
                    phraseContainsSignature = phrase.contains(meta.text.lowercase())
                )
                if (score < LearnedPatternPolicy.INSTRUCTION_MIN_SCORE) continue
                scored.add(meta to score)
            }
            return scored.sortedWith(
                compareByDescending<Pair<LearnedInstructionMeta, Int>> { it.second }
                    .thenByDescending { it.first.lastUsedAtMs }
            ).take(LearnedPatternPolicy.MAX_INSTRUCTIONS_PER_COMMAND).map { it.first }
        }
    }

    /** Verified reuse of an applied pattern: +0.1 confidence (cap
     *  0.9), counts up, consecutive failures reset. */
    fun recordReuse(patternId: String, success: Boolean): Boolean {
        synchronized(lock) {
            val pattern = getPatternLocked(patternId) ?: return false
            val now = System.currentTimeMillis()
            val updated = if (success) {
                pattern.copy(
                    useCount = pattern.useCount + 1,
                    successCount = pattern.successCount + 1,
                    consecutiveFailures = 0,
                    confidence = LearnedPatternPolicy.confidenceAfterSuccess(pattern.confidence),
                    lastUsedAtMs = now
                )
            } else {
                val (nextConfidence, autoDisable) = LearnedPatternPolicy.confidenceAfterFailure(
                    pattern.confidence, pattern.consecutiveFailures
                )
                pattern.copy(
                    useCount = pattern.useCount + 1,
                    consecutiveFailures = pattern.consecutiveFailures + 1,
                    confidence = nextConfidence,
                    enabled = pattern.enabled && !autoDisable,
                    lastUsedAtMs = now
                )
            }
            if (!storage.writePattern(updated.id, updated.toJson().toString())) return false
            val index = loadPatternIndexLocked()
            index.patterns[updated.id] = metaOf(updated)
            return savePatternIndexLocked(index)
        }
    }

    /** "ye tareeka mat use karo": failure-after-apply (−0.2, and
     *  auto-disable after 2 consecutive failures). */
    fun recordFailureAfterApply(patternId: String): Boolean =
        recordReuse(patternId, success = false)

    fun setPatternEnabled(id: String, enabled: Boolean): Boolean {
        synchronized(lock) {
            val pattern = getPatternLocked(id) ?: return false
            val updated = pattern.copy(enabled = enabled)
            if (!storage.writePattern(updated.id, updated.toJson().toString())) return false
            val index = loadPatternIndexLocked()
            index.patterns[updated.id] = metaOf(updated)
            return savePatternIndexLocked(index)
        }
    }

    /** Real delete: record file + index entry removed (DESIGN §5.8 —
     *  "bhool jao" must be real forgetting, not enabled=false). */
    fun deletePattern(id: String): Boolean {
        synchronized(lock) {
            val index = loadPatternIndexLocked()
            val existed = index.patterns.remove(id) != null
            index.order.remove(id)
            storage.deletePattern(id)
            savePatternIndexLocked(index)
            return existed
        }
    }

    fun getPattern(id: String): LearnedPattern? {
        synchronized(lock) {
            return getPatternLocked(id)
        }
    }

    fun listPatterns(): List<LearnedPatternMeta> {
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadPatternIndexLocked()
            return index.patterns.values.sortedByDescending { it.lastUsedAtMs }
        }
    }

    // ------------------------------------------------------------------
    // Instructions
    // ------------------------------------------------------------------

    /**
     * Save a user-taught instruction. Returns the record, or null
     * when the text trips the filter, the 30-cap is reached (never
     * auto-evict), or a write fails.
     */
    fun saveInstruction(
        rawText: String,
        scopeKeywords: List<String>,
        appliesTo: List<String>
    ): LearnedInstruction? {
        val text = rawText.trim().replace(Regex("\\s+"), " ")
        if (!LearnedPatternPolicy.isLearnableInstruction(text)) return null
        val now = System.currentTimeMillis()
        val id = "ins-$now-${UUID.randomUUID().toString().take(8).replace("-", "0")}"
        val record = LearnedInstruction(
            id = id,
            text = text.take(280),
            scopeKeywords = scopeKeywords.take(LearnedPatternPolicy.MAX_KEYWORDS),
            appliesTo = appliesTo.ifEmpty { listOf("PLAN", "LOOP") },
            createdAtMs = now,
            lastUsedAtMs = now
        )
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadInstructionIndexLocked()
            if (index.instructions.size >= MAX_INSTRUCTIONS) return null
            if (!storage.writeInstruction(id, record.toJson().toString())) return null
            index.instructions[id] = metaOf(record)
            index.order.add(id)
            if (!saveInstructionIndexLocked(index)) {
                storage.deleteInstruction(id)
                return null
            }
            return record
        }
    }

    /** Real delete: record file + index entry removed. */
    fun deleteInstruction(id: String): Boolean {
        synchronized(lock) {
            val index = loadInstructionIndexLocked()
            val existed = index.instructions.remove(id) != null
            index.order.remove(id)
            storage.deleteInstruction(id)
            saveInstructionIndexLocked(index)
            return existed
        }
    }

    fun getInstruction(id: String): LearnedInstruction? {
        synchronized(lock) {
            val raw = storage.readInstruction(id) ?: return null
            return LearnedInstruction.fromJsonString(raw)
        }
    }

    /** All enabled instructions, most recently used first (voice list caps at 10). */
    fun listInstructions(): List<LearnedInstructionMeta> {
        synchronized(lock) {
            rebuildIndexesIfNeededLocked()
            val index = loadInstructionIndexLocked()
            return index.instructions.values.sortedByDescending { it.lastUsedAtMs }
        }
    }

    fun recordInstructionUse(id: String): Boolean {
        synchronized(lock) {
            val record = getInstruction(id) ?: return false
            val updated = record.copy(
                useCount = record.useCount + 1,
                lastUsedAtMs = System.currentTimeMillis()
            )
            if (!storage.writeInstruction(updated.id, updated.toJson().toString())) return false
            val index = loadInstructionIndexLocked()
            index.instructions[updated.id] = metaOf(updated)
            return saveInstructionIndexLocked(index)
        }
    }

    fun stats(): Pair<Int, Int> {
        synchronized(lock) {
            val patterns = loadPatternIndexLocked().patterns.size
            val instructions = loadInstructionIndexLocked().instructions.size
            return patterns to instructions
        }
    }

    // ------------------------------------------------------------------
    // Index persistence (private — all callers hold [lock])
    // ------------------------------------------------------------------

    private class PatternIndexData(
        val patterns: MutableMap<String, LearnedPatternMeta> = HashMap(),
        val order: MutableList<String> = ArrayList()
    )

    private class InstructionIndexData(
        val instructions: MutableMap<String, LearnedInstructionMeta> = HashMap(),
        val order: MutableList<String> = ArrayList()
    )

    private fun metaOf(pattern: LearnedPattern): LearnedPatternMeta = LearnedPatternMeta(
        id = pattern.id,
        title = pattern.title,
        triggerSignature = pattern.triggerSignature,
        triggerKeywords = pattern.triggerKeywords,
        workKind = pattern.workKind,
        confidence = pattern.confidence,
        useCount = pattern.useCount,
        successCount = pattern.successCount,
        consecutiveFailures = pattern.consecutiveFailures,
        lastUsedAtMs = pattern.lastUsedAtMs,
        createdAtMs = pattern.createdAtMs,
        enabled = pattern.enabled
    )

    private fun metaOf(instruction: LearnedInstruction): LearnedInstructionMeta = LearnedInstructionMeta(
        id = instruction.id,
        text = instruction.text,
        scopeKeywords = instruction.scopeKeywords,
        appliesTo = instruction.appliesTo,
        createdAtMs = instruction.createdAtMs,
        useCount = instruction.useCount,
        lastUsedAtMs = instruction.lastUsedAtMs,
        enabled = instruction.enabled
    )

    private fun newPatternId(): String =
        "pat-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8).replace("-", "0")}"

    private fun loadPatternIndexLocked(): PatternIndexData {
        val raw = storage.readPatternIndex() ?: return PatternIndexData()
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("schemaVersion", -1) != 1) return PatternIndexData()
            val patterns = HashMap<String, LearnedPatternMeta>()
            val docsObj = obj.optJSONObject("patterns")
            if (docsObj != null) {
                for (key in docsObj.keys().asSequence().toList()) {
                    val metaObj = docsObj.optJSONObject(key) ?: continue
                    val meta = LearnedPatternMeta.fromJson(key, metaObj) ?: continue
                    patterns[key] = meta
                }
            }
            val order = ArrayList<String>()
            val orderArr = obj.optJSONArray("order")
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) {
                    val id = orderArr.optString(i, "")
                    if (id.isNotBlank() && patterns.containsKey(id)) order.add(id)
                }
            }
            for (id in patterns.keys) {
                if (id !in order) order.add(id)
            }
            PatternIndexData(patterns, order)
        }.getOrDefault(PatternIndexData())
    }

    private fun savePatternIndexLocked(index: PatternIndexData): Boolean {
        val obj = JSONObject()
        obj.put("schemaVersion", 1)
        val docsObj = JSONObject()
        for ((id, meta) in index.patterns) {
            docsObj.put(id, meta.toJson())
        }
        obj.put("patterns", docsObj)
        val orderArr = JSONArray()
        for (id in index.order) orderArr.put(id)
        obj.put("order", orderArr)
        return storage.writePatternIndex(obj.toString())
    }

    private fun loadInstructionIndexLocked(): InstructionIndexData {
        val raw = storage.readInstructionIndex() ?: return InstructionIndexData()
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("schemaVersion", -1) != 1) return InstructionIndexData()
            val instructions = HashMap<String, LearnedInstructionMeta>()
            val docsObj = obj.optJSONObject("instructions")
            if (docsObj != null) {
                for (key in docsObj.keys().asSequence().toList()) {
                    val metaObj = docsObj.optJSONObject(key) ?: continue
                    val meta = LearnedInstructionMeta.fromJson(key, metaObj) ?: continue
                    instructions[key] = meta
                }
            }
            val order = ArrayList<String>()
            val orderArr = obj.optJSONArray("order")
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) {
                    val id = orderArr.optString(i, "")
                    if (id.isNotBlank() && instructions.containsKey(id)) order.add(id)
                }
            }
            for (id in instructions.keys) {
                if (id !in order) order.add(id)
            }
            InstructionIndexData(instructions, order)
        }.getOrDefault(InstructionIndexData())
    }

    private fun saveInstructionIndexLocked(index: InstructionIndexData): Boolean {
        val obj = JSONObject()
        obj.put("schemaVersion", 1)
        val docsObj = JSONObject()
        for ((id, meta) in index.instructions) {
            docsObj.put(id, meta.toJson())
        }
        obj.put("instructions", docsObj)
        val orderArr = JSONArray()
        for (id in index.order) orderArr.put(id)
        obj.put("order", orderArr)
        return storage.writeInstructionIndex(obj.toString())
    }

    private fun evictPatternLocked(index: PatternIndexData, id: String) {
        index.patterns.remove(id)
        index.order.remove(id)
        storage.deletePattern(id)
    }

    private fun getPatternLocked(id: String): LearnedPattern? {
        val raw = storage.readPattern(id) ?: return null
        return LearnedPattern.fromJsonString(raw)
    }

    private fun patternBytesLocked(id: String): Long {
        val raw = storage.readPattern(id) ?: return 0L
        return raw.toByteArray(Charsets.UTF_8).size.toLong()
    }

    /**
     * Existence-only drift check (same shape as I1): an index entry
     * without a file, or a file without an index entry, triggers a
     * rebuild from the files on disk. Corrupt files are skipped +
     * pruned when actually read, never by a full parse here.
     */
    fun rebuildIfNeeded(): Boolean {
        synchronized(lock) {
            return rebuildIndexesIfNeededLocked()
        }
    }

    private fun rebuildIndexesIfNeededLocked(): Boolean {
        var rebuilt = false
        val filePatternIds = storage.listPatternIds().toSet()
        val patternIndex = loadPatternIndexLocked()
        if (filePatternIds != patternIndex.patterns.keys.toSet()) {
            val fresh = PatternIndexData()
            for (id in filePatternIds) {
                val record = getPatternLocked(id) ?: continue
                fresh.patterns[id] = metaOf(record)
                fresh.order.add(id)
            }
            fresh.order.sortBy { fresh.patterns[it]?.createdAtMs ?: 0L }
            savePatternIndexLocked(fresh)
            rebuilt = true
        }
        val fileInstructionIds = storage.listInstructionIds().toSet()
        val instructionIndex = loadInstructionIndexLocked()
        if (fileInstructionIds != instructionIndex.instructions.keys.toSet()) {
            val fresh = InstructionIndexData()
            for (id in fileInstructionIds) {
                val raw = storage.readInstruction(id) ?: continue
                val record = LearnedInstruction.fromJsonString(raw) ?: continue
                fresh.instructions[id] = metaOf(record)
                fresh.order.add(id)
            }
            fresh.order.sortBy { fresh.instructions[it]?.createdAtMs ?: 0L }
            saveInstructionIndexLocked(fresh)
            rebuilt = true
        }
        return rebuilt
    }

    companion object {
        const val MAX_PATTERNS = 50
        const val MAX_INSTRUCTIONS = 30
        const val MAX_PATTERN_STORE_BYTES = 512L * 1024L // 512 KB backstop
    }
}

/** Minimal storage seam so unit tests run without Android: the file
 *  implementation lives in [FilePatternStorage]; tests supply an
 *  in-memory one. Values are raw JSON strings; the store owns parsing. */
interface PatternStorage {
    fun listPatternIds(): List<String>
    fun readPattern(id: String): String?
    fun writePattern(id: String, json: String): Boolean
    fun deletePattern(id: String): Boolean
    fun readPatternIndex(): String?
    fun writePatternIndex(json: String): Boolean
    fun listInstructionIds(): List<String>
    fun readInstruction(id: String): String?
    fun writeInstruction(id: String, json: String): Boolean
    fun deleteInstruction(id: String): Boolean
    fun readInstructionIndex(): String?
    fun writeInstructionIndex(json: String): Boolean
    fun deleteAll()
}

/** File-backed storage: `filesDir/device-memory/patterns/<id>.json` +
 *  `patterns-index.json`, and the same shape for instructions.
 *  Writes are atomic (tmp file + rename); a failed write leaves the
 *  previous file untouched. */
class FilePatternStorage(context: Context) : PatternStorage {

    private val rootDir: File =
        File(context.applicationContext.filesDir, "device-memory").apply { mkdirs() }
    private val patternsDir: File = File(rootDir, "patterns").apply { mkdirs() }
    private val instructionsDir: File = File(rootDir, "instructions").apply { mkdirs() }
    private val patternIndexFile: File = File(rootDir, "patterns-index.json")
    private val instructionIndexFile: File = File(rootDir, "instructions-index.json")

    override fun listPatternIds(): List<String> = listIds(patternsDir)
    override fun listInstructionIds(): List<String> = listIds(instructionsDir)

    private fun listIds(dir: File): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .sorted()
    }

    override fun readPattern(id: String): String? = readFile(patternsDir, id)
    override fun readInstruction(id: String): String? = readFile(instructionsDir, id)

    private fun readFile(dir: File, id: String): String? {
        if (!isSafeId(id)) return null
        val file = File(dir, "$id.json")
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun writePattern(id: String, json: String): Boolean = writeFile(patternsDir, id, json)
    override fun writeInstruction(id: String, json: String): Boolean = writeFile(instructionsDir, id, json)

    private fun writeFile(dir: File, id: String, json: String): Boolean {
        if (!isSafeId(id)) return false
        return writeAtomic(File(dir, "$id.json"), json)
    }

    override fun deletePattern(id: String): Boolean = deleteFile(patternsDir, id)
    override fun deleteInstruction(id: String): Boolean = deleteFile(instructionsDir, id)

    private fun deleteFile(dir: File, id: String): Boolean {
        if (!isSafeId(id)) return false
        return runCatching { File(dir, "$id.json").delete() }.getOrDefault(false)
    }

    override fun readPatternIndex(): String? = readIndex(patternIndexFile)
    override fun readInstructionIndex(): String? = readIndex(instructionIndexFile)

    private fun readIndex(file: File): String? {
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun writePatternIndex(json: String): Boolean = writeAtomic(patternIndexFile, json)
    override fun writeInstructionIndex(json: String): Boolean = writeAtomic(instructionIndexFile, json)

    override fun deleteAll() {
        runCatching { patternsDir.deleteRecursively() }
        runCatching { instructionsDir.deleteRecursively() }
        runCatching { patternIndexFile.delete() }
        runCatching { instructionIndexFile.delete() }
        runCatching { patternsDir.mkdirs() }
        runCatching { instructionsDir.mkdirs() }
    }

    private fun writeAtomic(file: File, content: String): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** Ids are phone-generated (`pat-<millis>-<hex>` /
     *  `ins-<millis>-<hex>`); anything else (path separators, "..")
     *  is refused — an id must never escape its dir. */
    private fun isSafeId(id: String): Boolean =
        id.matches(Regex("(pat|ins)-[0-9]+-[0-9a-f]+"))
}

/**
 * R2.6 (1.17.0-PRO) — Store-backed [PatternStorage]: learned patterns
 * + instructions live in the Jarvis Store (sections
 * SEC_LEARNED_PATTERNS / SEC_INSTRUCTIONS) once migrated, instead of
 * device-memory/patterns/<id>.json + instructions/<id>.json. Same seam, so
 * LearnedPatternStore (and its unit test) work unchanged. The file
 * implementation stays as the legacy reader for the one-time
 * migration and for pre-migration launches.
 */
class JarvisStorePatternStorage : PatternStorage {

    private fun patternsSection(): JSONObject =
        (JarvisStore.getSection(JarvisStore.SEC_LEARNED_PATTERNS) as? JSONObject) ?: JSONObject()

    private fun instructionsSection(): JSONObject =
        (JarvisStore.getSection(JarvisStore.SEC_INSTRUCTIONS) as? JSONObject) ?: JSONObject()

    private fun savePatternsSection(obj: JSONObject): Boolean =
        JarvisStore.setSection(JarvisStore.SEC_LEARNED_PATTERNS, obj)

    private fun saveInstructionsSection(obj: JSONObject): Boolean =
        JarvisStore.setSection(JarvisStore.SEC_INSTRUCTIONS, obj)

    private fun mapOf(section: JSONObject, key: String): JSONObject =
        section.optJSONObject(key) ?: JSONObject().also { section.put(key, it) }

    override fun listPatternIds(): List<String> =
        mapOf(patternsSection(), "patterns").keys().asSequence().toList().sorted()

    override fun listInstructionIds(): List<String> =
        mapOf(instructionsSection(), "instructions").keys().asSequence().toList().sorted()

    override fun readPattern(id: String): String? =
        mapOf(patternsSection(), "patterns").optJSONObject(id)?.toString()

    override fun readInstruction(id: String): String? =
        mapOf(instructionsSection(), "instructions").optJSONObject(id)?.toString()

    override fun writePattern(id: String, json: String): Boolean {
        val section = patternsSection()
        return runCatching {
            mapOf(section, "patterns").put(id, JSONObject(json))
            savePatternsSection(section)
        }.getOrDefault(false)
    }

    override fun writeInstruction(id: String, json: String): Boolean {
        val section = instructionsSection()
        return runCatching {
            mapOf(section, "instructions").put(id, JSONObject(json))
            saveInstructionsSection(section)
        }.getOrDefault(false)
    }

    override fun deletePattern(id: String): Boolean {
        val section = patternsSection()
        val map = mapOf(section, "patterns")
        if (map.opt(id) == null) return false
        map.remove(id)
        return savePatternsSection(section)
    }

    override fun deleteInstruction(id: String): Boolean {
        val section = instructionsSection()
        val map = mapOf(section, "instructions")
        if (map.opt(id) == null) return false
        map.remove(id)
        return saveInstructionsSection(section)
    }

    override fun readPatternIndex(): String? =
        patternsSection().optJSONObject("pattern_index")?.toString()

    override fun readInstructionIndex(): String? =
        instructionsSection().optJSONObject("instruction_index")?.toString()

    override fun writePatternIndex(json: String): Boolean {
        val section = patternsSection()
        return runCatching {
            section.put("pattern_index", JSONObject(json))
            savePatternsSection(section)
        }.getOrDefault(false)
    }

    override fun writeInstructionIndex(json: String): Boolean {
        val section = instructionsSection()
        return runCatching {
            section.put("instruction_index", JSONObject(json))
            saveInstructionsSection(section)
        }.getOrDefault(false)
    }

    override fun deleteAll() {
        JarvisStore.setSection(JarvisStore.SEC_LEARNED_PATTERNS, null)
        JarvisStore.setSection(JarvisStore.SEC_INSTRUCTIONS, null)
    }
}

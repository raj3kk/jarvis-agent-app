package com.jarvis.autonomous.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * R2.6 (1.17.0-PRO) — Jarvis Store: the ONE durable on-device file.
 *
 * The user's order (2026-11-23 ~12:52 IST): "RAM me nahi, user ke
 * mobile me Jarvis file ho, usi me sara kuch rahe — Jarvis ka pura
 * storage, memory, aur jo jo hona chahiye, sara kuch."
 *
 * Layout: filesDir/jarvis-store.json — {"version":1,"sections":{...}}.
 * RAM is only a cache; this file is the source of truth. Five
 * persistence rules (MUSE_BACKEND_WORKFLOW.md §3.1) apply here:
 * write-through turant; files, never RAM-only; load-before-write on
 * startup; durable data never in cacheDir; sync/guaranteed-flush for
 * critical writes (atomic tmp + rename + fsync).
 *
 * ONE exception (security, user-told): secrets (licence keys, API
 * tokens, pairing secrets) stay in EncryptedSharedPreferences —
 * a plain file must never hold a token. Projects stay in external
 * storage (JarvisProjects/); only their registry/index lives here.
 *
 * Migration (one-time, first load after 1.17.0): read every legacy
 * source -> merge into the Store -> write Store -> READ-BACK verify
 * (re-read from disk, compare every section) -> only then rename
 * legacy files to *.bak and clear the migrated prefs keys. Any
 * failure -> run on the legacy path, retry next launch. Never lose
 * data, never crash.
 *
 * Corruption fallback: every successful write rotates the previous
 * good copy to jarvis-store.json.bak. A corrupt main file falls back
 * to the .bak; only a double failure starts empty (and the legacy
 * *.bak files stay on disk for manual recovery).
 */
object JarvisStore {
    const val STORE_FILE_NAME = "jarvis-store.json"
    private const val STORE_BAK_NAME = "jarvis-store.json.bak"
    private const val STORE_TMP_NAME = "jarvis-store.json.tmp"
    const val STORE_VERSION = 1

    // Section names (one per legacy source).
    const val SEC_CHAT = "chat" // JSONArray [{sender,message}] — cap 80
    const val SEC_FRIEND_MEMORIES = "friend_memories" // JSONArray [String]
    const val SEC_VOICE_ROUTINES = "voice_routines" // JSONArray [{id,trigger,prompt}]
    const val SEC_LEARNED_ROUTINES = "learned_routines" // JSONObject {routines:[...]}
    const val SEC_SKILL_MANIFESTS = "skill_manifests" // JSONObject {manifests:[...]}
    const val SEC_MEMORY_INDEX = "memory_index" // JSONObject {summaries:{},turnKeywords:{},index:{}}
    const val SEC_AGENT_PLAN = "agent_plan" // JSONObject (AgentPlan.toJson) or absent
    const val SEC_LEARNED_PATTERNS = "learned_patterns" // JSONObject {patterns:{id:obj},pattern_index:{...}}
    const val SEC_INSTRUCTIONS = "instructions" // JSONObject {instructions:{id:obj},instruction_index:{...}}
    const val SEC_DOCS_INDEX = "docs_index" // JSONObject (device-memory/index.json content)
    const val SEC_DIAGNOSTICS_CURSOR = "diagnostics_cursor" // JSONObject {lines,lastTsMs}
    const val SEC_EVENT_LOG_INDEX = "event_log_index" // JSONObject {count,firstTsMs,lastTsMs,lastEventId}
    const val SEC_WHITEBOARD = "whiteboard" // JSONObject (whiteboard state)
    const val SEC_PROJECTS_REGISTRY = "projects_registry" // JSONObject {projects:[{name,path}]}

    private val lock = Any()
    private var filesDir: File? = null
    private var sections: JSONObject? = null
    private var migrated = false
    private var loadAttempted = false
    private var dirty = false
    private var lastError: String? = null

    /** Load the Store (or migrate once). Call from MainActivity.onCreate
     *  and JarvisForegroundService.onCreate BEFORE the F1 chat restore.
     *  Idempotent — the second call is a no-op. Returns true when the
     *  Store is the live source of truth. */
    fun load(context: Context): Boolean {
        synchronized(lock) {
            if (loadAttempted) return migrated
            loadAttempted = true
            filesDir = context.filesDir
            val storeFile = File(context.filesDir, STORE_FILE_NAME)
            if (storeFile.exists()) {
                val mainParsed = readStoreFile(storeFile)
                val parsed = mainParsed
                    ?: readStoreFile(File(context.filesDir, STORE_BAK_NAME))
                if (parsed != null) {
                    sections = parsed.optJSONObject("sections") ?: JSONObject()
                    migrated = true
                    if (mainParsed == null) {
                        // Rescued from the .bak — promote it back to main.
                        runCatching { saveNowLocked() }
                    }
                    return true
                }
                lastError = "store_corrupt"
                sections = JSONObject()
                return false
            }
            // No Store yet — one-time migration from the legacy sources.
            return runCatching { migrateFromLegacy(context) }.getOrElse { e ->
                lastError = "migrate_failed:${e.javaClass.simpleName}"
                sections = JSONObject()
                false
            }
        }
    }

    /** True once load() has made the Store the source of truth. */
    fun isMigrated(): Boolean = synchronized(lock) { migrated }

    fun lastError(): String? = synchronized(lock) { lastError }

    /** Test-only: clear the loaded state so a test can re-run load()
     *  (migration, .bak rescue, double-corruption) in one JVM.
     *  Production code never calls this. */
    internal fun resetForTests() {
        synchronized(lock) {
            loadAttempted = false
            migrated = false
            sections = null
            filesDir = null
            dirty = false
            lastError = null
        }
    }

    /** Read one section (JSONObject or JSONArray), or null. */
    fun getSection(name: String): Any? = synchronized(lock) {
        sections?.opt(name)
    }

    /** Write one section (null = remove it) and flush to disk. Returns
     *  false when the Store is not loaded or the flush failed — the
     *  in-memory value still holds and the next setSection retries. */
    fun setSection(name: String, value: Any?): Boolean {
        synchronized(lock) {
            val secs = sections ?: return false
            if (value == null) secs.remove(name) else secs.put(name, value)
            dirty = true
            return saveNowLocked()
        }
    }

    /** Flush the in-memory sections to disk (atomic tmp + rename +
     *  fsync, previous good copy rotated to .bak). */
    fun saveNow(): Boolean = synchronized(lock) { saveNowLocked() }

    // ---- Internals (all called with lock held) ----

    private fun readStoreFile(file: File): JSONObject? {
        return runCatching {
            val raw = file.readText()
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != STORE_VERSION) return null
            if (!root.has("sections")) return null
            root
        }.getOrNull()
    }

    private fun saveNowLocked(): Boolean {
        val dir = filesDir ?: return false
        val secs = sections ?: return false
        return runCatching {
            val root = JSONObject().apply {
                put("version", STORE_VERSION)
                put("sections", secs)
            }
            val tmp = File(dir, STORE_TMP_NAME)
            FileOutputStream(tmp).use { out ->
                out.write(root.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            val main = File(dir, STORE_FILE_NAME)
            val bak = File(dir, STORE_BAK_NAME)
            if (main.exists()) {
                runCatching { bak.delete() }
                runCatching { main.renameTo(bak) }
            }
            if (!tmp.renameTo(main)) {
                // Rename failed — restore the .bak so we never end up
                // with neither file.
                runCatching { bak.renameTo(main) }
                return false
            }
            dirty = false
            true
        }.getOrElse { e ->
            lastError = "save_failed:${e.javaClass.simpleName}"
            false
        }
    }

    /**
     * One-time migration. Reads every legacy source, builds the
     * sections, writes the Store, READS IT BACK from disk and compares
     * every section — only then renames legacy files to *.bak and
     * clears the migrated prefs keys.
     */
    private fun migrateFromLegacy(context: Context): Boolean {
        val built = JSONObject()

        // --- prefs: jarvis_agent_prefs (chat, friend memories, routines) ---
        val agentPrefs = context.getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
        putJsonArraySection(built, SEC_CHAT, agentPrefs.getString("chat_history_json", null))
        putJsonArraySection(built, SEC_FRIEND_MEMORIES, agentPrefs.getString("friend_memories", null))
        putJsonArraySection(built, SEC_VOICE_ROUTINES, agentPrefs.getString("voice_routines", null))

        // --- prefs: jarvis_learned_routines (routines + skill manifests) ---
        val routinePrefs = context.getSharedPreferences("jarvis_learned_routines", Context.MODE_PRIVATE)
        val routinesRaw = routinePrefs.getString("learned_routines_json", null)
        if (routinesRaw != null) {
            runCatching {
                built.put(SEC_LEARNED_ROUTINES, JSONObject().put("routines", JSONArray(routinesRaw)))
            }
        }
        val skillsRaw = routinePrefs.getString("local_skills_json_v1", null)
        if (skillsRaw != null) {
            runCatching {
                built.put(SEC_SKILL_MANIFESTS, JSONObject().put("manifests", JSONArray(skillsRaw)))
            }
        }

        // --- prefs: jarvis_memory_index (three string maps) ---
        val indexPrefs = context.getSharedPreferences("jarvis_memory_index", Context.MODE_PRIVATE)
        val memIndex = JSONObject()
        putJsonObjectInto(memIndex, "summaries", indexPrefs.getString("turn_summaries_json", null))
        putJsonObjectInto(memIndex, "turnKeywords", indexPrefs.getString("turn_keywords_json", null))
        putJsonObjectInto(memIndex, "index", indexPrefs.getString("keyword_index_json", null))
        if (memIndex.length() > 0) built.put(SEC_MEMORY_INDEX, memIndex)

        // --- file: agent_plan.json ---
        val planFile = File(context.filesDir, "agent_plan.json")
        if (planFile.exists()) {
            runCatching { built.put(SEC_AGENT_PLAN, JSONObject(planFile.readText())) }
        }

        // --- files: learned patterns + instructions (via the same seam
        //     the live store uses) ---
        val patternStorage = FilePatternStorage(context)
        val patternsObj = JSONObject()
        val patternsMap = JSONObject()
        for (id in patternStorage.listPatternIds()) {
            val raw = patternStorage.readPattern(id) ?: continue
            runCatching { patternsMap.put(id, JSONObject(raw)) }
        }
        patternsObj.put("patterns", patternsMap)
        runCatching {
            patternStorage.readPatternIndex()?.let { patternsObj.put("pattern_index", JSONObject(it)) }
        }
        if (patternsMap.length() > 0 || patternsObj.has("pattern_index")) {
            built.put(SEC_LEARNED_PATTERNS, patternsObj)
        }
        val instructionsObj = JSONObject()
        val instructionsMap = JSONObject()
        for (id in patternStorage.listInstructionIds()) {
            val raw = patternStorage.readInstruction(id) ?: continue
            runCatching { instructionsMap.put(id, JSONObject(raw)) }
        }
        instructionsObj.put("instructions", instructionsMap)
        runCatching {
            patternStorage.readInstructionIndex()?.let { instructionsObj.put("instruction_index", JSONObject(it)) }
        }
        if (instructionsMap.length() > 0 || instructionsObj.has("instruction_index")) {
            built.put(SEC_INSTRUCTIONS, instructionsObj)
        }

        // --- file: device-memory/index.json (docs INDEX only; bodies stay) ---
        val docsIndexFile = File(context.filesDir, "device-memory/index.json")
        if (docsIndexFile.exists()) {
            runCatching { built.put(SEC_DOCS_INDEX, JSONObject(docsIndexFile.readText())) }
        }

        // --- file: diagnostics.jsonl (cursor only; log lines stay) ---
        val diagFile = File(context.filesDir, "diagnostics.jsonl")
        if (diagFile.exists()) {
            val lines = runCatching { diagFile.readLines().size }.getOrDefault(0)
            built.put(SEC_DIAGNOSTICS_CURSOR, JSONObject().apply {
                put("lines", lines)
                put("lastTsMs", System.currentTimeMillis())
            })
        }

        // --- file: whiteboard.json ---
        val whiteboardFile = File(context.filesDir, "whiteboard.json")
        if (whiteboardFile.exists()) {
            runCatching { built.put(SEC_WHITEBOARD, JSONObject(whiteboardFile.readText())) }
        }

        // --- external: JarvisProjects/ registry only (projects stay external) ---
        val registry = JSONObject()
        val projectsArr = JSONArray()
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val projectsDir = File(externalDir, "JarvisProjects")
            if (projectsDir.exists()) {
                projectsDir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    projectsArr.put(JSONObject().apply {
                        put("name", f.name)
                        put("path", f.absolutePath)
                        put("isDir", f.isDirectory)
                    })
                }
            }
        }
        registry.put("projects", projectsArr)
        built.put(SEC_PROJECTS_REGISTRY, registry)

        // --- event-log index starts empty; the log file itself is separate ---
        built.put(SEC_EVENT_LOG_INDEX, JSONObject().apply {
            put("count", 0)
            put("firstTsMs", 0)
            put("lastTsMs", 0)
            put("lastEventId", "")
        })

        // Write + READ-BACK verify before touching any legacy source.
        sections = built
        if (!saveNowLocked()) {
            sections = JSONObject()
            return false
        }
        val reRead = readStoreFile(File(context.filesDir, STORE_FILE_NAME))
            ?: run {
                sections = JSONObject()
                return false
            }
        val reSections = reRead.optJSONObject("sections") ?: run {
            sections = JSONObject()
            return false
        }
        val names = built.keys().asSequence().toList()
        for (name in names) {
            val want = built.opt(name)?.toString()
            val got = reSections.opt(name)?.toString()
            if (want != got) {
                lastError = "verify_mismatch:$name"
                sections = JSONObject()
                return false
            }
        }

        // Verified — only now retire the legacy sources.
        runCatching { if (planFile.exists()) planFile.renameTo(File(planFile.parentFile, "agent_plan.json.bak")) }
        runCatching { if (whiteboardFile.exists()) whiteboardFile.renameTo(File(whiteboardFile.parentFile, "whiteboard.json.bak")) }
        runCatching { if (docsIndexFile.exists()) docsIndexFile.renameTo(File(docsIndexFile.parentFile, "index.json.bak")) }
        val patternsDir = File(context.filesDir, "device-memory/patterns")
        val instructionsDir = File(context.filesDir, "device-memory/instructions")
        runCatching { if (patternsDir.exists()) patternsDir.renameTo(File(patternsDir.parentFile, "patterns.bak")) }
        runCatching { if (instructionsDir.exists()) instructionsDir.renameTo(File(instructionsDir.parentFile, "instructions.bak")) }
        agentPrefs.edit()
            .remove("chat_history_json")
            .remove("friend_memories")
            .remove("voice_routines")
            .commit()
        routinePrefs.edit()
            .remove("learned_routines_json")
            .remove("local_skills_json_v1")
            .commit()
        indexPrefs.edit()
            .remove("turn_summaries_json")
            .remove("turn_keywords_json")
            .remove("keyword_index_json")
            .commit()

        migrated = true
        return true
    }

    private fun putJsonArraySection(target: JSONObject, name: String, raw: String?) {
        if (raw == null) return
        runCatching { target.put(name, JSONArray(raw)) }
    }

    private fun putJsonObjectInto(target: JSONObject, name: String, raw: String?) {
        if (raw == null) return
        runCatching { target.put(name, JSONObject(raw)) }
    }
}

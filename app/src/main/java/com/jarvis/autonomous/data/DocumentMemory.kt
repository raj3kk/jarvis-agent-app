package com.jarvis.autonomous.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * I1 — On-device document memory (1.7.1).
 *
 * When the user shows Jarvis a document once, Jarvis saves it ON THE
 * PHONE, indexes it, and later recalls it from this store to do work.
 *
 * PRIVACY (hard boundary, reviewable in one grep): this file imports
 * NO networking — no OkHttp, no java.net.URL, no HttpURLConnection.
 * Nothing here can upload doc content anywhere. Server memory
 * (Phase 3d, D1 `memories`) stays learned-patterns-only; the only
 * uploader in the app (`maybeUploadLearnedPattern`) sends
 * action/target_kind pairs + template hash and never reads this store.
 *
 * Storage (see expansion/device-memory/DESIGN.md §2): app-private
 * `filesDir/device-memory/` — one JSON file per document + one small
 * `index.json` (metadata + per-doc keywords + order). Same atomic
 * tmp+rename writes and fail-closed parsing as `AgentPlanStore`;
 * same inverted-index search shape as `MemoryIndex` (keyword
 * extraction reused verbatim), without the SharedPreferences
 * whole-value rewrite that breaks at doc scale.
 *
 * Every public store call reads from disk (no in-memory cache), so
 * the engine instance and the UI instance always see the same data.
 * All I/O must be called off the main thread (Dispatchers.IO).
 */

/** Pure text helpers: chunking, snippets, LLM context. No Android. */
object DocumentText {

    const val MAX_CHUNK_CHARS = 800
    const val MAX_CHUNKS = 100
    const val MAX_SNIPPET_CHARS = 400
    const val MAX_CONTEXT_DOCS = 3
    const val TRUNCATION_MARKER = "… (aage ka hissa kata hua)"

    /** Split [text] into ≤800-char chunks at sentence boundaries first,
     *  then word boundaries; a single over-long word is hard-split.
     *  Never splits mid-word unless the word itself exceeds the cap.
     *  Returns at most [maxChunks] chunks. */
    fun chunkText(
        text: String,
        maxChunkChars: Int = MAX_CHUNK_CHARS,
        maxChunks: Int = MAX_CHUNKS
    ): List<String> {
        if (text.isBlank()) return emptyList()
        val sentences = text.split(Regex("(?<=[.!?\\n])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val chunks = ArrayList<String>()
        var current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                chunks.add(current.toString().trim())
                current = StringBuilder()
            }
        }
        for (sentence in sentences) {
            if (sentence.length > maxChunkChars) {
                flush()
                var wordChunk = StringBuilder()
                for (word in sentence.split(Regex("\\s+"))) {
                    if (word.isEmpty()) continue
                    if (word.length > maxChunkChars) {
                        if (wordChunk.isNotBlank()) {
                            chunks.add(wordChunk.toString().trim())
                            wordChunk = StringBuilder()
                        }
                        var rest = word
                        while (rest.length > maxChunkChars) {
                            chunks.add(rest.substring(0, maxChunkChars))
                            rest = rest.substring(maxChunkChars)
                        }
                        wordChunk.append(rest)
                    } else if (wordChunk.isNotEmpty() &&
                        wordChunk.length + 1 + word.length > maxChunkChars
                    ) {
                        chunks.add(wordChunk.toString().trim())
                        wordChunk = StringBuilder(word)
                    } else {
                        if (wordChunk.isNotEmpty()) wordChunk.append(' ')
                        wordChunk.append(word)
                    }
                }
                if (wordChunk.isNotBlank()) chunks.add(wordChunk.toString().trim())
                continue
            }
            if (current.isNotEmpty() && current.length + 1 + sentence.length > maxChunkChars) {
                flush()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
        flush()
        return chunks.take(maxChunks)
    }

    /** First chunk containing a matched keyword, windowed to ±200 chars
     *  around the first match, capped at [maxChars]. Falls back to the
     *  first chunk when no keyword occurs in any chunk. */
    fun extractSnippet(
        chunks: List<String>,
        matchedKeywords: List<String>,
        maxChars: Int = MAX_SNIPPET_CHARS
    ): String {
        if (chunks.isEmpty()) return ""
        val lowered = matchedKeywords.map { it.lowercase() }
        for (chunk in chunks) {
            val low = chunk.lowercase()
            var firstIdx = -1
            for (kw in lowered) {
                val idx = low.indexOf(kw)
                if (idx >= 0) {
                    firstIdx = idx
                    break
                }
            }
            if (firstIdx < 0) continue
            if (chunk.length <= maxChars) return chunk
            val start = (firstIdx - 200).coerceAtLeast(0)
            val end = (start + maxChars).coerceAtMost(chunk.length)
            val window = chunk.substring(start, end).trim()
            return (if (start > 0) "… " else "") + window +
                (if (end < chunk.length) " …" else "")
        }
        return chunks.first().take(maxChars)
    }

    /** Title cleanup: collapse whitespace, cap length, blank → fallback.
     *  The title is user-visible metadata (also shown in logs) — never
     *  a dump of the document text. */
    fun sanitizeTitle(raw: String): String {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isBlank()) return "Bina title ka document"
        return clean.take(120)
    }

    /** LLM prompt context for up to [maxDocs] hits: one labelled line
     *  per doc (title + snippet). "" when there is nothing to recall.
     *  This is context only — the model must never be told it is a
     *  verbatim answer. Snippets are extracts (≤400 chars each). */
    fun buildDocContext(hits: List<DocumentHit>, maxDocs: Int = MAX_CONTEXT_DOCS): String {
        if (hits.isEmpty()) return ""
        return hits.take(maxDocs).joinToString("\n") { hit ->
            "(Document '${hit.title}' se yaad: ${hit.snippet})"
        }
    }
}

/** Pure scoring + recall thresholds. No Android. */
object DocumentSearch {

    /** Recall: the hit is worth offering as context. */
    const val RECALL_MIN_SCORE = 2

    /** Strong hit: worth answering from directly, offline, with no LLM. */
    const val STRONG_MIN_SCORE = 3

    const val TITLE_BONUS_PER_KEYWORD = 2
    const val PHRASE_BONUS = 1

    /** Distinct-keyword overlap + title bonus + phrase bonus. */
    fun scoreHit(
        queryKeywords: List<String>,
        docKeywords: Set<String>,
        titleKeywords: Set<String>,
        queryPhraseInText: Boolean
    ): Int {
        var score = 0
        for (kw in queryKeywords) {
            if (kw in docKeywords) score += 1
            if (kw in titleKeywords) score += TITLE_BONUS_PER_KEYWORD
        }
        if (queryPhraseInText) score += PHRASE_BONUS
        return score
    }

    /** True when [score] (or a title-keyword match) makes the hit worth
     *  recalling. A single common-word overlap (score 1, no title
     *  match) never recalls — it would answer from the wrong doc. */
    fun isRecallHit(score: Int, titleMatched: Boolean): Boolean =
        score >= RECALL_MIN_SCORE || titleMatched

    /** True when the hit is strong enough for the offline direct
     *  answer (no LLM call at all). */
    fun isStrongHit(score: Int, titleMatched: Boolean): Boolean =
        score >= STRONG_MIN_SCORE || (titleMatched && score >= RECALL_MIN_SCORE)
}

data class DocumentRecord(
    val id: String,
    val title: String,
    val source: String, // "pasted" | "doc-confirm" | "voice"
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val text: String,
    val chunks: List<String>,
    val charCount: Int,
    val byteSize: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("title", title)
        put("source", source)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("text", text)
        val arr = JSONArray()
        for (chunk in chunks) arr.put(chunk)
        put("chunks", arr)
        put("charCount", charCount)
        put("byteSize", byteSize)
    }

    companion object {
        /** Fail-closed: unknown schema version or any parse problem
         *  returns null (the doc is skipped, never a crash). */
        fun fromJson(obj: JSONObject): DocumentRecord? {
            return try {
                if (obj.optInt("schemaVersion", -1) != 1) return null
                val id = obj.optString("id", "")
                val title = obj.optString("title", "")
                val text = obj.optString("text", "")
                if (id.isBlank() || title.isBlank() || text.isBlank()) return null
                val chunks = ArrayList<String>()
                val arr = obj.optJSONArray("chunks")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val chunk = arr.optString(i, "")
                        if (chunk.isNotBlank()) chunks.add(chunk)
                    }
                }
                DocumentRecord(
                    id = id,
                    title = title,
                    source = obj.optString("source", "pasted").ifBlank { "pasted" },
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    text = text,
                    chunks = chunks,
                    charCount = obj.optInt("charCount", text.length),
                    byteSize = obj.optLong("byteSize", 0L)
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonString(raw: String): DocumentRecord? {
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }
    }
}

/** Index metadata for one document (everything search needs without
 *  opening the doc file). */
data class DocumentMeta(
    val id: String,
    val title: String,
    val source: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val charCount: Int,
    val byteSize: Long,
    val keywords: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("title", title)
        put("source", source)
        put("createdAtMs", createdAtMs)
        put("updatedAtMs", updatedAtMs)
        put("charCount", charCount)
        put("byteSize", byteSize)
        val arr = JSONArray()
        for (kw in keywords) arr.put(kw)
        put("keywords", arr)
    }

    companion object {
        fun fromJson(id: String, obj: JSONObject): DocumentMeta? {
            return try {
                val keywords = ArrayList<String>()
                val arr = obj.optJSONArray("keywords")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val kw = arr.optString(i, "")
                        if (kw.isNotBlank()) keywords.add(kw)
                    }
                }
                DocumentMeta(
                    id = id,
                    title = obj.optString("title", ""),
                    source = obj.optString("source", "pasted"),
                    createdAtMs = obj.optLong("createdAtMs", 0L),
                    updatedAtMs = obj.optLong("updatedAtMs", 0L),
                    charCount = obj.optInt("charCount", 0),
                    byteSize = obj.optLong("byteSize", 0L),
                    keywords = keywords
                ).takeIf { it.title.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class DocumentHit(
    val docId: String,
    val title: String,
    val source: String,
    val snippet: String,
    val score: Int,
    val matchedKeywords: List<String>,
    val titleMatched: Boolean,
    val updatedAtMs: Long
)

data class SaveResult(
    val ok: Boolean,
    val id: String? = null,
    val title: String = "",
    val chunkCount: Int = 0,
    val charCount: Int = 0,
    val evictedIds: List<String> = emptyList(),
    val reason: String = ""
)

data class StoreStats(
    val docCount: Int,
    val totalBytes: Long,
    val totalChars: Int
)

/** Minimal storage seam so unit tests run without Android: the file
 *  implementation lives in [FileDocumentStorage]; tests supply an
 *  in-memory one. Values are raw JSON strings; the store owns parsing. */
interface DocumentStorage {
    fun listDocIds(): List<String>
    fun readDoc(id: String): String?
    fun writeDoc(id: String, json: String): Boolean
    fun deleteDoc(id: String): Boolean
    fun readIndex(): String?
    fun writeIndex(json: String): Boolean
    fun deleteAll()
}

/** File-backed storage: `filesDir/device-memory/docs/<id>.json` +
 *  `filesDir/device-memory/index.json`. Writes are atomic (tmp file +
 *  rename); a failed write leaves the previous file untouched. */
class FileDocumentStorage(context: Context) : DocumentStorage {

    private val rootDir: File =
        File(context.applicationContext.filesDir, "device-memory").apply { mkdirs() }
    private val docsDir: File = File(rootDir, "docs").apply { mkdirs() }
    private val indexFile: File = File(rootDir, "index.json")

    override fun listDocIds(): List<String> {
        val files = docsDir.listFiles() ?: return emptyList()
        return files.filter { it.isFile && it.name.endsWith(".json") }
            .map { it.name.removeSuffix(".json") }
            .sorted()
    }

    override fun readDoc(id: String): String? {
        if (!isSafeId(id)) return null
        val file = File(docsDir, "$id.json")
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun writeDoc(id: String, json: String): Boolean {
        if (!isSafeId(id)) return false
        return writeAtomic(File(docsDir, "$id.json"), json)
    }

    override fun deleteDoc(id: String): Boolean {
        if (!isSafeId(id)) return false
        return runCatching { File(docsDir, "$id.json").delete() }.getOrDefault(false)
    }

    override fun readIndex(): String? {
        if (!indexFile.isFile) return null
        return runCatching { indexFile.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun writeIndex(json: String): Boolean = writeAtomic(indexFile, json)

    override fun deleteAll() {
        runCatching { rootDir.deleteRecursively() }
        runCatching { rootDir.mkdirs() }
        runCatching { docsDir.mkdirs() }
    }

    private fun writeAtomic(file: File, content: String): Boolean {
        return runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // renameTo can fail across filesystems; fall back to a
                // direct overwrite only after the tmp write succeeded.
                file.writeText(content, Charsets.UTF_8)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /** Ids are phone-generated (`doc-<millis>-<hex>`); anything else
     *  (path separators, "..") is refused — a doc id must never
     *  escape the docs dir. */
    private fun isSafeId(id: String): Boolean =
        id.matches(Regex("doc-[0-9]+-[0-9a-f]+"))
}

class DocumentMemoryStore(private val storage: DocumentStorage) {

    private val lock = Any()

    /**
     * Saves [text] as a new document. Blank text is refused (never a
     * phantom doc); over-long text is truncated with a visible marker.
     * Evicts oldest docs first when the count cap or the total-bytes
     * cap would be exceeded. Returns the new id + evicted ids.
     */
    fun saveDocument(rawTitle: String, rawText: String, source: String): SaveResult {
        val text = rawText.trim()
        if (text.isBlank()) {
            return SaveResult(ok = false, reason = "empty_text")
        }
        val title = DocumentText.sanitizeTitle(rawTitle)
        val cleanSource = source.trim().ifBlank { "pasted" }.take(20)
        val storedText = if (text.length > MAX_TEXT_CHARS) {
            text.take(MAX_TEXT_CHARS) + " " + DocumentText.TRUNCATION_MARKER
        } else {
            text
        }
        val now = System.currentTimeMillis()
        val id = "doc-$now-${UUID.randomUUID().toString().take(8).replace("-", "0")}"
        val chunks = DocumentText.chunkText(storedText)
        val byteSize = storedText.toByteArray(Charsets.UTF_8).size.toLong() +
            title.toByteArray(Charsets.UTF_8).size.toLong()
        val keywords = MemoryKeywords.extract("$title $storedText")
            .take(MAX_KEYWORDS_PER_DOC)
        val record = DocumentRecord(
            id = id,
            title = title,
            source = cleanSource,
            createdAtMs = now,
            updatedAtMs = now,
            text = storedText,
            chunks = chunks,
            charCount = storedText.length,
            byteSize = byteSize
        )
        val meta = DocumentMeta(
            id = id,
            title = title,
            source = cleanSource,
            createdAtMs = now,
            updatedAtMs = now,
            charCount = storedText.length,
            byteSize = byteSize,
            keywords = keywords
        )
        synchronized(lock) {
            rebuildIndexIfNeededLocked()
            val index = loadIndexLocked()
            val evicted = ArrayList<String>()
            // Count cap (FIFO by createdAtMs, oldest first).
            while (index.docs.size >= MAX_DOCUMENTS) {
                val oldest = index.docs.values.minByOrNull { it.createdAtMs } ?: break
                evictLocked(index, oldest.id)
                evicted.add(oldest.id)
            }
            // Total-bytes cap (oldest first; a single doc always fits
            // because of the per-doc char cap).
            var totalBytes = index.docs.values.sumOf { it.byteSize }
            while (index.docs.isNotEmpty() && totalBytes + byteSize > MAX_TOTAL_BYTES) {
                val oldest = index.docs.values.minByOrNull { it.createdAtMs } ?: break
                totalBytes -= oldest.byteSize
                evictLocked(index, oldest.id)
                evicted.add(oldest.id)
            }
            if (!storage.writeDoc(id, record.toJson().toString())) {
                return SaveResult(ok = false, reason = "write_failed")
            }
            index.docs[id] = meta
            index.order.remove(id)
            index.order.add(id)
            if (!saveIndexLocked(index)) {
                // Doc file landed but the index did not: remove the
                // orphan so a later rebuild (not a stale index) owns it.
                storage.deleteDoc(id)
                return SaveResult(ok = false, reason = "index_write_failed")
            }
            return SaveResult(
                ok = true,
                id = id,
                title = title,
                chunkCount = chunks.size,
                charCount = storedText.length,
                evictedIds = evicted
            )
        }
    }

    /** Ranked hits for [query]: most score first, ties by recency.
     *  Score 0 (no keyword overlap) never returns. Empty / stopwords-
     *  only query returns empty, never a dump of all docs. Offline,
     *  pure Kotlin — no network. */
    fun search(query: String, topK: Int = TOP_K): List<DocumentHit> {
        val queryKeywords = MemoryKeywords.extract(query)
        if (queryKeywords.isEmpty()) return emptyList()
        val queryLower = query.lowercase().trim()
        synchronized(lock) {
            rebuildIndexIfNeededLocked()
            val index = loadIndexLocked()
            val hits = ArrayList<DocumentHit>()
            val corruptIds = ArrayList<String>()
            for (meta in index.docs.values) {
                val docKeywords = meta.keywords.toSet()
                val matched = queryKeywords.filter { it in docKeywords }
                if (matched.isEmpty()) continue
                val titleKeywords = MemoryKeywords.extract(meta.title).toSet()
                val titleMatched = queryKeywords.any { it in titleKeywords }
                // Phrase bonus needs the text: read the doc file for
                // candidates only (never a full-corpus scan). A null
                // record means the file is corrupt/unreadable — the
                // doc is skipped here and pruned from the index below
                // (never returned with an empty snippet).
                val record = getDocumentLocked(meta.id)
                if (record == null) {
                    corruptIds.add(meta.id)
                    continue
                }
                val phraseInText = queryLower.isNotBlank() &&
                    queryLower.length > 2 &&
                    record.text.lowercase().contains(queryLower)
                val score = DocumentSearch.scoreHit(
                    queryKeywords = queryKeywords,
                    docKeywords = docKeywords,
                    titleKeywords = titleKeywords,
                    queryPhraseInText = phraseInText
                )
                if (score <= 0) continue
                val snippet = DocumentText.extractSnippet(record.chunks, matched)
                hits.add(
                    DocumentHit(
                        docId = meta.id,
                        title = meta.title,
                        source = meta.source,
                        snippet = snippet,
                        score = score,
                        matchedKeywords = matched,
                        titleMatched = titleMatched,
                        updatedAtMs = meta.updatedAtMs
                    )
                )
            }
            // Self-heal: corrupt files found during this search are
            // pruned from the index now (no separate scan pass — the
            // per-call rebuild check below is existence-only, so a
            // search never pays O(total bytes) to re-parse all docs).
            if (corruptIds.isNotEmpty()) {
                for (id in corruptIds) {
                    index.docs.remove(id)
                    index.order.remove(id)
                }
                saveIndexLocked(index)
            }
            return hits.sortedWith(
                compareByDescending<DocumentHit> { it.score }
                    .thenByDescending { it.updatedAtMs }
            ).take(topK.coerceAtLeast(1))
        }
    }

    fun getDocument(id: String): DocumentRecord? {
        synchronized(lock) {
            return getDocumentLocked(id)
        }
    }

    /** Metadata for all docs, most recently updated first. */
    fun listDocuments(): List<DocumentMeta> {
        synchronized(lock) {
            rebuildIndexIfNeededLocked()
            val index = loadIndexLocked()
            return index.docs.values.sortedByDescending { it.updatedAtMs }
        }
    }

    fun deleteDocument(id: String): Boolean {
        synchronized(lock) {
            val index = loadIndexLocked()
            val existed = index.docs.remove(id) != null
            index.order.remove(id)
            storage.deleteDoc(id)
            saveIndexLocked(index)
            return existed
        }
    }

    fun deleteAll(): Boolean {
        synchronized(lock) {
            storage.deleteAll()
            return true
        }
    }

    fun stats(): StoreStats {
        synchronized(lock) {
            val index = loadIndexLocked()
            return StoreStats(
                docCount = index.docs.size,
                totalBytes = index.docs.values.sumOf { it.byteSize },
                totalChars = index.docs.values.sumOf { it.charCount }
            )
        }
    }

    /**
     * Integrity check + repair. Returns true only when a rebuild
     * actually happened (index entries without a readable doc file,
     * or doc files missing from the index). Healthy index: returns
     * false, no rewrite. Same shape as `MemoryIndex.rebuildIfNeeded()`.
     */
    fun rebuildIfNeeded(): Boolean {
        synchronized(lock) {
            return rebuildIndexIfNeededLocked()
        }
    }

    // ------------------------------------------------------------------
    // Index persistence (private — all callers hold [lock])
    // ------------------------------------------------------------------

    private class IndexData(
        val docs: MutableMap<String, DocumentMeta> = HashMap(),
        val order: MutableList<String> = ArrayList()
    )

    private fun loadIndexLocked(): IndexData {
        val raw = storage.readIndex() ?: return IndexData()
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.optInt("schemaVersion", -1) != 1) return IndexData()
            val docs = HashMap<String, DocumentMeta>()
            val docsObj = obj.optJSONObject("docs")
            if (docsObj != null) {
                for (key in docsObj.keys().asSequence().toList()) {
                    val metaObj = docsObj.optJSONObject(key) ?: continue
                    val meta = DocumentMeta.fromJson(key, metaObj) ?: continue
                    docs[key] = meta
                }
            }
            val order = ArrayList<String>()
            val orderArr = obj.optJSONArray("order")
            if (orderArr != null) {
                for (i in 0 until orderArr.length()) {
                    val id = orderArr.optString(i, "")
                    if (id.isNotBlank() && docs.containsKey(id)) order.add(id)
                }
            }
            // Order must cover every doc (a doc missing from order
            // still lists — it is appended, never dropped).
            for (id in docs.keys) {
                if (id !in order) order.add(id)
            }
            IndexData(docs, order)
        }.getOrDefault(IndexData())
    }

    private fun saveIndexLocked(index: IndexData): Boolean {
        val obj = JSONObject()
        obj.put("schemaVersion", 1)
        val docsObj = JSONObject()
        for ((id, meta) in index.docs) {
            docsObj.put(id, meta.toJson())
        }
        obj.put("docs", docsObj)
        val orderArr = JSONArray()
        for (id in index.order) orderArr.put(id)
        obj.put("order", orderArr)
        return storage.writeIndex(obj.toString())
    }

    private fun evictLocked(index: IndexData, id: String) {
        index.docs.remove(id)
        index.order.remove(id)
        storage.deleteDoc(id)
    }

    private fun getDocumentLocked(id: String): DocumentRecord? {
        val raw = storage.readDoc(id) ?: return null
        return DocumentRecord.fromJsonString(raw)
    }

    private fun rebuildIndexIfNeededLocked(): Boolean {
        val fileIds = storage.listDocIds().toSet()
        val index = loadIndexLocked()
        val indexIds = index.docs.keys.toSet()
        // Existence-only drift check (cheap): an index entry without
        // a file, or a file without an index entry. Corrupt files
        // are NOT detected here — parsing every doc on every call
        // would cost O(total bytes) per search; instead a corrupt
        // file is skipped + pruned when a search actually reads it.
        if (fileIds == indexIds) return false
        val rebuilt = IndexData()
        for (id in fileIds) {
            val record = getDocumentLocked(id) ?: continue
            val keywords = MemoryKeywords.extract("${record.title} ${record.text}")
                .take(MAX_KEYWORDS_PER_DOC)
            rebuilt.docs[id] = DocumentMeta(
                id = id,
                title = record.title,
                source = record.source,
                createdAtMs = record.createdAtMs,
                updatedAtMs = record.updatedAtMs,
                charCount = record.charCount,
                byteSize = record.byteSize,
                keywords = keywords
            )
            rebuilt.order.add(id)
        }
        rebuilt.order.sortBy { rebuilt.docs[it]?.createdAtMs ?: 0L }
        saveIndexLocked(rebuilt)
        return true
    }

    companion object {
        const val MAX_DOCUMENTS = 50
        const val MAX_TEXT_CHARS = 100_000
        const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L // 4 MB — reachable:
        // per-doc cap is 100k chars (~100 KB), so 50 full docs would be
        // ~5 MB; the byte cap must sit below that or it could never fire.
        const val MAX_KEYWORDS_PER_DOC = 400
        const val TOP_K = 5
    }
}

package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * I1 (1.7.1) — unit tests for on-device document memory.
 *
 * Runs on the JVM (JUnit4 + Robolectric, no emulator/device).
 * Pure logic (chunking / scoring / thresholds) needs no Android;
 * the store is tested twice — once against an in-memory storage
 * (fast, hermetic) and once against the real [FileDocumentStorage]
 * with a Robolectric application Context (proves the files actually
 * land in filesDir and a second store instance sees them, which is
 * how the engine instance and the UI instance share data).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocumentMemoryTest {

    /** In-memory [DocumentStorage] for hermetic store tests. */
    private class InMemoryDocumentStorage : DocumentStorage {
        private val docs = HashMap<String, String>()
        private var index: String? = null

        override fun listDocIds(): List<String> = docs.keys.sorted()
        override fun readDoc(id: String): String? = docs[id]
        override fun writeDoc(id: String, json: String): Boolean {
            docs[id] = json
            return true
        }

        override fun deleteDoc(id: String): Boolean = docs.remove(id) != null
        override fun readIndex(): String? = index
        override fun writeIndex(json: String): Boolean {
            index = json
            return true
        }

        override fun deleteAll() {
            docs.clear()
            index = null
        }
    }

    private fun newStore() = DocumentMemoryStore(InMemoryDocumentStorage())

    private fun save(
        store: DocumentMemoryStore,
        title: String,
        text: String,
        source: String = "pasted"
    ): SaveResult {
        val result = store.saveDocument(title, text, source)
        assertTrue("save must succeed: ${result.reason}", result.ok)
        assertNotNull(result.id)
        return result
    }

    // ------------------------------------------------------------------
    // DocumentText: chunking
    // ------------------------------------------------------------------

    @Test
    fun chunkText_splitsAtSentenceBoundaries() {
        val text = "Pehla jumla yahan hai. Doosra jumla yahan hai. Teesra jumla."
        val chunks = DocumentText.chunkText(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("Pehla jumla"))
        assertTrue(chunks[0].contains("Teesra jumla."))
    }

    @Test
    fun chunkText_respectsMaxChunkChars() {
        val sentence = "word ".repeat(300).trim() + "."
        val chunks = DocumentText.chunkText(sentence)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("chunk too long: ${chunk.length}", chunk.length <= 800)
        }
        // No content lost: rejoining recovers every word.
        val rejoined = chunks.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        assertEquals(sentence.replace(Regex("\\s+"), " ").trim(), rejoined)
    }

    @Test
    fun chunkText_hardSplitsSingleOverlongWord() {
        val word = "x".repeat(2000)
        val chunks = DocumentText.chunkText(word)
        assertTrue(chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue(chunk.length <= 800)
        }
        assertEquals(word, chunks.joinToString(""))
    }

    @Test
    fun chunkText_capsAtMaxChunks() {
        val text = (1..150).joinToString(" ") { "Sentence number $it ends here." }
        val long = text.repeat(20)
        val chunks = DocumentText.chunkText(long)
        assertTrue(chunks.size <= DocumentText.MAX_CHUNKS)
    }

    @Test
    fun chunkText_blankReturnsEmpty() {
        assertTrue(DocumentText.chunkText("   ").isEmpty())
        assertTrue(DocumentText.chunkText("").isEmpty())
    }

    // ------------------------------------------------------------------
    // DocumentText: snippet + title + context
    // ------------------------------------------------------------------

    @Test
    fun extractSnippet_containsMatchedKeywordAndCapsLength() {
        val chunks = listOf(
            "Ye pehla hissa hai jisme kuch nahi hai. ".repeat(10),
            "Bijli ka bill is mahine ka 1240 rupay hai, last date 25 November hai."
        )
        val snippet = DocumentText.extractSnippet(chunks, listOf("bijli"))
        assertTrue(snippet.contains("Bijli", ignoreCase = true))
        assertTrue(snippet.length <= DocumentText.MAX_SNIPPET_CHARS)
    }

    @Test
    fun extractSnippet_fallsBackToFirstChunk() {
        val chunks = listOf("Kuch bhi relevant nahi.", "Doosra hissa.")
        assertEquals("Kuch bhi relevant nahi.", DocumentText.extractSnippet(chunks, listOf("xyz")))
        assertEquals("", DocumentText.extractSnippet(emptyList(), listOf("xyz")))
    }

    @Test
    fun sanitizeTitle_blankFallsBackAndLongTruncates() {
        assertEquals("Bina title ka document", DocumentText.sanitizeTitle("   "))
        assertEquals("Aadhaar card", DocumentText.sanitizeTitle("  Aadhaar   card  "))
        assertEquals(120, DocumentText.sanitizeTitle("t".repeat(200)).length)
    }

    @Test
    fun buildDocContext_capsAtThreeDocsAndEmptyIsEmpty() {
        assertEquals("", DocumentText.buildDocContext(emptyList()))
        val hits = (1..5).map {
            DocumentHit("id$it", "Doc $it", "pasted", "snippet $it", 3, listOf("x"), false, it.toLong())
        }
        val ctx = DocumentText.buildDocContext(hits)
        assertTrue(ctx.contains("Doc 1"))
        assertTrue(ctx.contains("Doc 3"))
        assertFalse(ctx.contains("Doc 4"))
    }

    // ------------------------------------------------------------------
    // DocumentSearch: scoring + thresholds
    // ------------------------------------------------------------------

    @Test
    fun scoreHit_overlapTitleAndPhraseBonuses() {
        val base = DocumentSearch.scoreHit(
            queryKeywords = listOf("bijli", "bill"),
            docKeywords = setOf("bijli", "bill", "rupay"),
            titleKeywords = setOf("bijli"),
            queryPhraseInText = false
        )
        // overlap 2 + title bonus 2 = 4
        assertEquals(4, base)
        val withPhrase = DocumentSearch.scoreHit(
            queryKeywords = listOf("bijli", "bill"),
            docKeywords = setOf("bijli", "bill"),
            titleKeywords = emptySet(),
            queryPhraseInText = true
        )
        assertEquals(3, withPhrase)
    }

    @Test
    fun thresholds_recallNeedsTwoOrTitleStrongNeedsThree() {
        assertFalse(DocumentSearch.isRecallHit(1, false))
        assertTrue(DocumentSearch.isRecallHit(2, false))
        assertTrue(DocumentSearch.isRecallHit(1, true))
        assertFalse(DocumentSearch.isStrongHit(2, false))
        assertTrue(DocumentSearch.isStrongHit(3, false))
        assertTrue(DocumentSearch.isStrongHit(2, true))
    }

    // ------------------------------------------------------------------
    // Store: save / get / list / search
    // ------------------------------------------------------------------

    @Test
    fun saveAndGet_roundtripPreservesTextAndSource() {
        val store = newStore()
        val text = "Mera Aadhaar number 1234 5678 hai. Ye document test ke liye hai."
        val saved = save(store, "Aadhaar card", text, "pasted")
        val got = store.getDocument(saved.id!!)
        assertNotNull(got)
        assertEquals("Aadhaar card", got!!.title)
        assertEquals("pasted", got.source)
        assertEquals(text, got.text)
        assertTrue(got.chunks.isNotEmpty())
        assertEquals(text.length, got.charCount)
    }

    @Test
    fun save_blankTextRefused() {
        val store = newStore()
        val result = store.saveDocument("Khaali", "   ", "pasted")
        assertFalse(result.ok)
        assertEquals("empty_text", result.reason)
        assertEquals(0, store.stats().docCount)
    }

    @Test
    fun save_blankTitleFallsBack() {
        val store = newStore()
        val saved = save(store, "   ", "Kuch text yahan hai.")
        assertEquals("Bina title ka document", saved.title)
    }

    @Test
    fun search_ranksTitleMatchFirst() {
        val store = newStore()
        save(store, "Random notes", "Aadhaar card number ke baare me kuch notes yahan hain.")
        save(store, "Aadhaar card", "Is document me sirf ek line hai.")
        val hits = store.search("aadhaar card number kya hai")
        assertEquals(2, hits.size)
        assertEquals("Aadhaar card", hits[0].title)
        assertTrue(hits[0].titleMatched)
    }

    @Test
    fun search_phraseBonusRanksExactPhraseFirst() {
        val store = newStore()
        save(store, "Notes A", "Bijli ka alag se zikr hai aur bill ka bhi alag se.")
        save(store, "Notes B", "Is mahine ka bijli bill 1240 rupay hai.")
        // Exact-phrase query: B scores overlap 2 + phrase 1 = 3, A scores 2.
        val hits = store.search("bijli bill")
        assertEquals(2, hits.size)
        assertEquals("Notes B", hits[0].title)
        assertEquals(3, hits[0].score)
        assertEquals(2, hits[1].score)
    }

    @Test
    fun search_emptyAndStopwordsOnlyReturnEmpty() {
        val store = newStore()
        save(store, "Aadhaar card", "Aadhaar card number yahan likha hai.")
        assertTrue(store.search("").isEmpty())
        assertTrue(store.search("   ").isEmpty())
        // "kya hai" are stopwords in MemoryKeywords — no recall, never a dump.
        assertTrue(store.search("kya hai").isEmpty())
    }

    @Test
    fun search_snippetContainsMatchedKeyword() {
        val store = newStore()
        save(
            store, "Bijli bill",
            "Pehla hissa bekaar hai. ".repeat(5) +
                "Bijli bill 1240 rupay, last date 25 November."
        )
        val hits = store.search("bijli bill kitna")
        assertEquals(1, hits.size)
        assertTrue(hits[0].snippet.contains("Bijli", ignoreCase = true))
        assertTrue(hits[0].snippet.length <= DocumentText.MAX_SNIPPET_CHARS)
    }

    @Test
    fun listDocuments_mostRecentFirst() {
        val store = newStore()
        save(store, "Pehla", "Pehle document ka text.")
        Thread.sleep(5)
        save(store, "Doosra", "Doosre document ka text.")
        val listed = store.listDocuments()
        assertEquals(2, listed.size)
        assertEquals("Doosra", listed[0].title)
    }

    @Test
    fun delete_removesDocAndIndexEntry() {
        val store = newStore()
        val saved = save(store, "Delete me", "Delete hone wala text yahan hai.")
        assertTrue(store.deleteDocument(saved.id!!))
        assertNull(store.getDocument(saved.id!!))
        assertTrue(store.search("delete hone wala").isEmpty())
        assertEquals(0, store.stats().docCount)
        assertFalse(store.deleteDocument(saved.id!!))
    }

    @Test
    fun deleteAll_wipesEverything() {
        val store = newStore()
        save(store, "Ek", "Pehla text.")
        save(store, "Do", "Doosra text.")
        assertTrue(store.deleteAll())
        assertEquals(0, store.stats().docCount)
        assertTrue(store.listDocuments().isEmpty())
    }

    // ------------------------------------------------------------------
    // Store: caps / eviction / truncation / repair
    // ------------------------------------------------------------------

    @Test
    fun save_truncatesOverlongTextWithMarker() {
        val store = newStore()
        val long = "word ".repeat(30_000) // ~150k chars
        val saved = save(store, "Lamba", long)
        assertEquals(DocumentMemoryStore.MAX_TEXT_CHARS + 1 + DocumentText.TRUNCATION_MARKER.length, saved.charCount)
        val got = store.getDocument(saved.id!!)!!
        assertTrue(got.text.endsWith(DocumentText.TRUNCATION_MARKER))
        assertTrue(got.chunks.size <= DocumentText.MAX_CHUNKS)
    }

    @Test
    fun save_countCapEvictsOldestFirst() {
        val store = newStore()
        var firstId: String? = null
        for (i in 1..(DocumentMemoryStore.MAX_DOCUMENTS + 1)) {
            val saved = save(store, "Doc $i", "Document number $i ka unique text $i.")
            if (i == 1) firstId = saved.id
            Thread.sleep(2)
        }
        assertEquals(DocumentMemoryStore.MAX_DOCUMENTS, store.stats().docCount)
        assertNull(store.getDocument(firstId!!))
        assertEquals("Doc 2", store.listDocuments().last().title)
    }

    @Test
    fun save_totalBytesCapEvictsOldestFirst() {
        val store = newStore()
        // ~100 KB per doc: the 4 MB total cap fires long before the
        // 50-doc count cap (41 full docs ≈ 4.1 MB).
        val bigText = "z".repeat(100_000)
        var firstId: String? = null
        for (i in 1..45) {
            val saved = save(store, "Big $i", bigText)
            if (i == 1) firstId = saved.id
            Thread.sleep(2)
        }
        val stats = store.stats()
        assertTrue("total bytes must stay under cap: ${stats.totalBytes}", stats.totalBytes <= DocumentMemoryStore.MAX_TOTAL_BYTES)
        assertTrue("byte cap must evict before count cap", stats.docCount < 45)
        assertNull(store.getDocument(firstId!!))
    }

    @Test
    fun rebuildIfNeeded_repairsCorruptIndex() {
        val storage = InMemoryDocumentStorage()
        val store = DocumentMemoryStore(storage)
        save(store, "Ek", "Pehla document text.")
        save(store, "Do", "Doosra document text.")
        assertFalse(store.rebuildIfNeeded())
        storage.writeIndex("this is not json {{{")
        assertTrue(store.rebuildIfNeeded())
        assertEquals(2, store.listDocuments().size)
        // "pehla document": "Ek" overlaps both keywords + the exact
        // phrase is in its text (2 + 1 = 3); "Do" only "document" (1).
        val hits = store.search("pehla document")
        assertEquals(2, hits.size)
        assertEquals("Ek", hits[0].title)
        assertEquals(3, hits[0].score)
        assertEquals(1, hits[1].score)
    }

    @Test
    fun rebuildIfNeeded_dropsIndexEntryWithMissingFile() {
        val storage = InMemoryDocumentStorage()
        val store = DocumentMemoryStore(storage)
        val saved = save(store, "Ek", "Pehla document text.")
        save(store, "Do", "Doosra document text.")
        storage.deleteDoc(saved.id!!) // file gone, index still claims it
        assertTrue(store.rebuildIfNeeded())
        assertEquals(1, store.listDocuments().size)
        assertEquals("Do", store.listDocuments()[0].title)
    }

    // ------------------------------------------------------------------
    // Store: real file-backed storage (Robolectric Context)
    // ------------------------------------------------------------------

    @Test
    fun fileStorage_secondInstanceSeesSavedDoc() {
        val appContext = RuntimeEnvironment.getApplication().applicationContext
        val storage = FileDocumentStorage(appContext)
        storage.deleteAll()
        val storeA = DocumentMemoryStore(storage)
        val saved = save(storeA, "Bijli bill", "Bijli bill 1240 rupay, last date 25 November.")
        // A second instance over the same files (engine + UI shape):
        // no in-memory cache, so it must see the doc immediately.
        val storeB = DocumentMemoryStore(FileDocumentStorage(appContext))
        val hits = storeB.search("bijli bill kitna hai")
        assertEquals(1, hits.size)
        assertEquals(saved.id, hits[0].docId)
        assertEquals("Bijli bill", storeB.getDocument(saved.id!!)!!.title)
        assertTrue(storeB.deleteAll())
        assertEquals(0, DocumentMemoryStore(FileDocumentStorage(appContext)).stats().docCount)
    }
}

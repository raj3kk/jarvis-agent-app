package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 1.9.0 (v2) — unit tests for the on-device learned-pattern +
 * instruction store (DESIGN §5).
 *
 * Runs on the JVM (JUnit4 + Robolectric, no emulator/device). The
 * store is tested against an in-memory storage seam (fast,
 * hermetic), mirroring DocumentMemoryTest, plus a source grep that
 * enforces the privacy boundary: the store imports no networking,
 * and `maybeUploadLearnedPattern` never reads it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LearnedPatternStoreTest {

    /** In-memory [PatternStorage] for hermetic store tests. */
    private class InMemoryPatternStorage : PatternStorage {
        private val patterns = HashMap<String, String>()
        private val instructions = HashMap<String, String>()
        private var patternIndex: String? = null
        private var instructionIndex: String? = null

        override fun listPatternIds(): List<String> = patterns.keys.sorted()
        override fun readPattern(id: String): String? = patterns[id]
        override fun writePattern(id: String, json: String): Boolean {
            patterns[id] = json
            return true
        }

        override fun deletePattern(id: String): Boolean = patterns.remove(id) != null
        override fun readPatternIndex(): String? = patternIndex
        override fun writePatternIndex(json: String): Boolean {
            patternIndex = json
            return true
        }

        override fun listInstructionIds(): List<String> = instructions.keys.sorted()
        override fun readInstruction(id: String): String? = instructions[id]
        override fun writeInstruction(id: String, json: String): Boolean {
            instructions[id] = json
            return true
        }

        override fun deleteInstruction(id: String): Boolean = instructions.remove(id) != null
        override fun readInstructionIndex(): String? = instructionIndex
        override fun writeInstructionIndex(json: String): Boolean {
            instructionIndex = json
            return true
        }

        override fun deleteAll() {
            patterns.clear()
            instructions.clear()
            patternIndex = null
            instructionIndex = null
        }
    }

    private fun newStore(): Pair<LearnedPatternStore, InMemoryPatternStorage> {
        val storage = InMemoryPatternStorage()
        return LearnedPatternStore(storage) to storage
    }

    private fun pattern(
        signature: String = "website dukaan banao",
        keywords: List<String> = listOf("website", "dukaan", "banao"),
        workKind: String = "LOOP",
        title: String = "Dukaan website",
        confidence: Double = 0.5
    ) = LearnedPattern(
        id = "",
        triggerSignature = signature,
        triggerKeywords = keywords,
        workKind = workKind,
        title = title,
        steps = listOf(
            LearnedPatternStep("Website banao", "BUILD"),
            LearnedPatternStep("Preview check karo", "BUILD")
        ),
        confidence = confidence
    )

    // ------------------------------------------------------------------
    // Patterns: save / list / get / delete
    // ------------------------------------------------------------------

    @Test
    fun saveListGetDeletePatternRoundTrip() {
        val (store, _) = newStore()
        val id = store.saveOrUpdatePattern(pattern())
        assertNotNull(id)
        val got = store.getPattern(id!!)
        assertNotNull(got)
        assertEquals("Dukaan website", got!!.title)
        assertEquals(2, got.steps.size)
        assertEquals(1, store.listPatterns().size)
        assertEquals(1 to 0, store.stats())
        assertTrue(store.deletePattern(id))
        assertNull(store.getPattern(id))
        assertTrue(store.listPatterns().isEmpty())
        assertFalse(store.deletePattern(id))
    }

    @Test
    fun saveInstructionListGetDeleteRoundTrip() {
        val (store, _) = newStore()
        val saved = store.saveInstruction(
            "aage se pehle preview check karo",
            listOf("preview", "check"),
            listOf("PLAN", "LOOP")
        )
        assertNotNull(saved)
        val got = store.getInstruction(saved!!.id)
        assertNotNull(got)
        assertEquals("aage se pehle preview check karo", got!!.text)
        assertEquals(1, store.listInstructions().size)
        assertEquals(0 to 1, store.stats())
        assertTrue(store.deleteInstruction(saved.id))
        assertNull(store.getInstruction(saved.id))
        assertFalse(store.deleteInstruction(saved.id))
    }

    @Test
    fun unsaveablePatternIsRefusedNotRedacted() {
        val (store, _) = newStore()
        // A step label carrying an email must refuse the whole save.
        val bad = pattern().copy(
            steps = listOf(LearnedPatternStep("email priya@gmail.com bhejo", "LOOP"))
        )
        assertNull(store.saveOrUpdatePattern(bad))
        assertTrue(store.listPatterns().isEmpty())
        // Instruction with a sensitive value is refused too.
        assertNull(store.saveInstruction("otp 482910 daalo", listOf("otp"), listOf("LOOP")))
        assertTrue(store.listInstructions().isEmpty())
    }

    @Test
    fun nearDuplicateUpdatesInsteadOfMinting() {
        val (store, _) = newStore()
        val first = store.saveOrUpdatePattern(pattern())
        assertNotNull(first)
        val second = store.saveOrUpdatePattern(pattern(title = "Dukaan website v2"))
        assertEquals("near-duplicate must update, not mint", first, second)
        assertEquals(1, store.listPatterns().size)
        val got = store.getPattern(first!!)!!
        assertEquals(1, got.useCount)
        assertEquals(1, got.successCount)
        assertEquals(0.6, got.confidence, 0.001)
    }

    @Test
    fun findBestPatternRespectsScoreConfidenceAndEnabled() {
        val (store, _) = newStore()
        val id = store.saveOrUpdatePattern(pattern())!!
        // 2 keyword hits + work-kind match = 4 ≥ 3 → found.
        val found = store.findBestPattern(
            queryKeywords = listOf("website", "dukaan"),
            workKind = "LOOP",
            queryPhrase = "mere dukaan ke liye website banao"
        )
        assertNotNull(found)
        assertEquals(id, found!!.id)
        // No keyword hit and wrong work kind → below threshold.
        assertNull(
            store.findBestPattern(
                queryKeywords = listOf("zebra"),
                workKind = "PLAN",
                queryPhrase = "zebra dikhao"
            )
        )
        // Disabled patterns never apply.
        assertTrue(store.setPatternEnabled(id, false))
        assertNull(
            store.findBestPattern(
                queryKeywords = listOf("website", "dukaan"),
                workKind = "LOOP",
                queryPhrase = "mere dukaan ke liye website banao"
            )
        )
        // Low-confidence patterns never apply.
        val (lowStore, _) = newStore()
        lowStore.saveOrUpdatePattern(pattern(confidence = 0.4))
        assertNull(
            lowStore.findBestPattern(
                queryKeywords = listOf("website", "dukaan"),
                workKind = "LOOP",
                queryPhrase = "mere dukaan ke liye website banao"
            )
        )
    }

    @Test
    fun findInstructionsCapsAtTwoAndRespectsAppliesTo() {
        val (store, _) = newStore()
        store.saveInstruction("aage se pehle preview check karo", listOf("preview", "check"), listOf("PLAN", "LOOP"))
        store.saveInstruction("deploy se pehle build verify karo", listOf("build", "verify"), listOf("PLAN"))
        store.saveInstruction("message bhejne se pehle naam poochho", listOf("message", "naam"), listOf("LOOP"))
        val found = store.findInstructions(
            queryKeywords = listOf("preview", "check", "build", "verify", "message", "naam"),
            workKind = "LOOP",
            queryPhrase = "preview check karo aur message bhejo"
        )
        assertTrue("max 2 instructions per command", found.size <= 2)
        // The PLAN-only instruction must not apply to a LOOP command.
        assertTrue(found.none { it.text.contains("deploy se pehle") })
    }

    @Test
    fun reuseRaisesConfidenceAndFailureDisablesAfterTwo() {
        val (store, _) = newStore()
        val id = store.saveOrUpdatePattern(pattern())!!
        assertTrue(store.recordReuse(id, success = true))
        assertEquals(0.6, store.getPattern(id)!!.confidence, 0.001)
        assertEquals(1, store.getPattern(id)!!.successCount)
        // First failure: confidence drops, still enabled.
        assertTrue(store.recordFailureAfterApply(id))
        val afterOne = store.getPattern(id)!!
        assertEquals(0.4, afterOne.confidence, 0.001)
        assertEquals(1, afterOne.consecutiveFailures)
        assertTrue(afterOne.enabled)
        // Second consecutive failure: floored at 0.2 and auto-disabled.
        assertTrue(store.recordFailureAfterApply(id))
        val afterTwo = store.getPattern(id)!!
        assertEquals(0.2, afterTwo.confidence, 0.001)
        assertFalse(afterTwo.enabled)
    }

    @Test
    fun patternCapEvictsLruAtFifty() {
        val (store, _) = newStore()
        val ids = ArrayList<String>()
        for (i in 0 until 51) {
            val id = store.saveOrUpdatePattern(
                pattern(
                    signature = "task number $i karo",
                    keywords = listOf("kw$i", "task"),
                    title = "Task $i"
                )
            )
            assertNotNull("save $i must succeed", id)
            ids.add(id!!)
        }
        assertEquals(50, store.listPatterns().size)
        // The newest pattern is always present; the cap held.
        assertTrue(store.listPatterns().any { it.id == ids.last() })
        assertTrue(store.listPatterns().count { it.id in ids } == 50)
    }

    @Test
    fun instructionsAreNeverAutoEvicted() {
        val (store, _) = newStore()
        for (i in 0 until 30) {
            val saved = store.saveInstruction(
                "instruction number $i yaad rakhna",
                listOf("kw$i"),
                listOf("LOOP")
            )
            assertNotNull("instruction $i must save", saved)
        }
        assertEquals(30, store.listInstructions().size)
        // The 31st save is refused — never auto-evicted.
        assertNull(
            store.saveInstruction("instruction number 30 yaad rakhna", listOf("kw30"), listOf("LOOP"))
        )
        assertEquals(30, store.listInstructions().size)
    }

    @Test
    fun corruptFileFailsClosedAndIndexSelfHeals() {
        val (store, storage) = newStore()
        val id = store.saveOrUpdatePattern(pattern())!!
        // Corrupt the record file directly: get must fail closed.
        storage.writePattern(id, "{not json at all")
        assertNull(store.getPattern(id))
        // Delete the file directly (bypassing the store): the next
        // list must rebuild the index and drop the ghost entry.
        val (store2, storage2) = newStore()
        val id2 = store2.saveOrUpdatePattern(pattern())!!
        assertEquals(1, store2.listPatterns().size)
        storage2.deletePattern(id2)
        assertTrue(store2.listPatterns().isEmpty())
        assertEquals(0 to 0, store2.stats())
    }

    @Test
    fun safeIdRejectsTraversal() {
        val (store, storage) = newStore()
        // The store never mints ids outside the pat-/ins- shape.
        val id = store.saveOrUpdatePattern(pattern())!!
        assertTrue(id.startsWith("pat-"))
        assertFalse(id.contains("/") || id.contains(".."))
        val ins = store.saveInstruction("aage se preview check karo", listOf("preview"), listOf("LOOP"))!!
        assertTrue(ins.id.startsWith("ins-"))
        assertFalse(storage.readPattern("../evil") != null)
    }

    @Test
    fun fileStorageRejectsTraversalIds() {
        // Real FilePatternStorage under a Robolectric Context: a
        // traversal id must never create a file outside the root.
        val appContext = org.robolectric.RuntimeEnvironment.getApplication().applicationContext
        val fileStorage = FilePatternStorage(appContext)
        assertFalse(fileStorage.writePattern("../evil", "{}"))
        assertFalse(fileStorage.writePattern("pat-1-abc/../../evil", "{}"))
        assertNull(fileStorage.readPattern("../evil"))
        assertFalse(fileStorage.deletePattern("../evil"))
        // And a legitimate write round-trips through the real files.
        val store = LearnedPatternStore(fileStorage)
        val id = store.saveOrUpdatePattern(pattern())
        assertNotNull(id)
        assertNotNull(store.getPattern(id!!))
        assertEquals(1, store.listPatterns().size)
    }

    // ------------------------------------------------------------------
    // Privacy boundary — source greps (§5.7)
    // ------------------------------------------------------------------

    private fun readSource(relativePath: String): String {
        val candidates = listOf(
            File(relativePath),
            File("app/$relativePath"),
            File("../app/$relativePath"),
            File("jarvis-agent/$relativePath")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("source not found under any candidate path: $relativePath")
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun storeImportsNoNetworking() {
        for (path in listOf(
            "src/main/java/com/jarvis/autonomous/data/LearnedPatternStore.kt",
            "src/main/java/com/jarvis/autonomous/data/LearnedPattern.kt"
        )) {
            val source = readSource(path)
            val imports = source.lines()
                .filter { it.trim().startsWith("import ") }
                .map { it.trim() }
            for (imp in imports) {
                assertFalse("networking import in $path: $imp", imp.contains("java.net"))
                assertFalse("networking import in $path: $imp", imp.contains("okhttp"))
                assertFalse("networking import in $path: $imp", imp.contains("HttpURLConnection"))
            }
        }
    }

    @Test
    fun uploaderNeverReadsThePatternStore() {
        val engine = readSource("src/main/java/com/jarvis/autonomous/agent/JarvisAgentEngine.kt")
        val fnIdx = engine.indexOf("fun maybeUploadLearnedPattern")
        assertTrue("maybeUploadLearnedPattern must exist", fnIdx >= 0)
        val braceIdx = engine.indexOf('{', fnIdx)
        assertTrue(braceIdx >= 0)
        var depth = 0
        var end = braceIdx
        for (i in braceIdx until engine.length) {
            when (engine[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        val body = engine.substring(braceIdx, end)
        assertFalse(body.contains("LearnedPatternStore"))
        assertFalse(body.contains("PatternStorage"))
        assertFalse(body.contains("FilePatternStorage"))
        assertFalse(body.contains("LearnedInstruction"))
    }
}

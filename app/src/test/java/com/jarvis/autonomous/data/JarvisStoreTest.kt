package com.jarvis.autonomous.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * R2.6 (1.17.0-PRO) — unit tests for the Jarvis Store.
 *
 * Runs on the JVM (JUnit4 + Robolectric, no emulator/device). The
 * Store is a process singleton, so each test resets it
 * (resetForTests) and wipes its files first. Covers: one-time
 * migration (legacy -> Store -> read-back verify -> .bak + prefs
 * cleared), the chat preservation that the M-T1 phone test re-verifies,
 * set/get round-trip through the file on disk, .bak rescue after a
 * corrupt main file, and the double-corruption fail-closed path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JarvisStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        JarvisStore.resetForTests()
        wipeStoreAndLegacy()
    }

    @After
    fun tearDown() {
        JarvisStore.resetForTests()
        wipeStoreAndLegacy()
    }

    private fun wipeStoreAndLegacy() {
        val filesDir = context.filesDir
        listOf(
            "jarvis-store.json", "jarvis-store.json.bak", "jarvis-store.json.tmp",
            "agent_plan.json", "agent_plan.json.bak", "agent_plan.json.tmp",
            "whiteboard.json", "whiteboard.json.bak",
            "device-memory/index.json", "device-memory/index.json.bak"
        ).forEach { File(filesDir, it).delete() }
        File(filesDir, "device-memory/patterns").deleteRecursively()
        File(filesDir, "device-memory/patterns.bak").deleteRecursively()
        File(filesDir, "device-memory/instructions").deleteRecursively()
        File(filesDir, "device-memory/instructions.bak").deleteRecursively()
        for (prefsName in listOf("jarvis_agent_prefs", "jarvis_learned_routines", "jarvis_memory_index")) {
            context.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun migrationPreservesChatRoutinesAndPatterns() {
        // Seed legacy sources the way 1.16.0 left them.
        val chatJson = """[{"sender":"User","message":"hello"},{"sender":"Jarvis","message":"namaste"}]"""
        context.getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE).edit()
            .putString("chat_history_json", chatJson)
            .putString("friend_memories", """["note one"]""")
            .putString("voice_routines", """[{"id":"1","trigger":"good morning","prompt":"quote sunao"}]""")
            .commit()
        context.getSharedPreferences("jarvis_learned_routines", Context.MODE_PRIVATE).edit()
            .putString("learned_routines_json", """[{"id":"r1","trigger":"x","createdAtMs":1,"steps":[]}]""")
            .putString("local_skills_json_v1", """[]""")
            .commit()
        val patternJson = JSONObject().apply {
            put("schemaVersion", 1)
            put("id", "pat-123-abc")
            put("kind", "PATTERN")
            put("triggerSignature", "sig")
            put("triggerKeywords", JSONArray(listOf("test")))
            put("workKind", "LOOP")
            put("title", "Test pattern")
            put("steps", JSONArray())
        }.toString()
        val patternsDir = File(context.filesDir, "device-memory/patterns").apply { mkdirs() }
        File(patternsDir, "pat-123-abc.json").writeText(patternJson)
        File(context.filesDir, "device-memory/patterns-index.json").writeText(
            """{"schemaVersion":1,"patterns":{},"order":[]}"""
        )

        assertTrue(JarvisStore.load(context))
        assertTrue(JarvisStore.isMigrated())

        // M-T1 precondition: the chat survives the migration and is
        // read back through the same public API the F1 restore uses.
        val history = JarvisPreferences(context).getSavedChatHistory()
        assertEquals(2, history.size)
        assertEquals("hello", history[0].message)
        assertEquals("namaste", history[1].message)

        // Patterns landed in the Store section.
        val patternsSection = JarvisStore.getSection(JarvisStore.SEC_LEARNED_PATTERNS) as? JSONObject
        assertTrue(patternsSection != null)
        val patternsMap = patternsSection!!.optJSONObject("patterns")
        assertTrue(patternsMap != null)
        assertTrue(patternsMap!!.has("pat-123-abc"))

        // Legacy sources retired: files renamed to .bak, prefs keys cleared.
        assertFalse(File(patternsDir, "pat-123-abc.json").exists())
        assertTrue(File(context.filesDir, "device-memory/patterns.bak").exists())
        assertNull(
            context.getSharedPreferences("jarvis_agent_prefs", Context.MODE_PRIVATE)
                .getString("chat_history_json", null)
        )
    }

    @Test
    fun setSectionRoundTripsThroughTheFileOnDisk() {
        assertTrue(JarvisStore.load(context))
        val arr = JSONArray().apply {
            put(JSONObject().put("sender", "User").put("message", "round trip"))
        }
        assertTrue(JarvisStore.setSection(JarvisStore.SEC_CHAT, arr))
        val onDisk = JSONObject(File(context.filesDir, "jarvis-store.json").readText())
        assertEquals(1, onDisk.optInt("version", -1))
        val sections = onDisk.getJSONObject("sections")
        assertEquals(
            "round trip",
            sections.getJSONArray(JarvisStore.SEC_CHAT).getJSONObject(0).optString("message", "")
        )
        // And the in-memory read agrees.
        val back = JarvisStore.getSection(JarvisStore.SEC_CHAT) as? JSONArray
        assertEquals("round trip", back?.getJSONObject(0)?.optString("message", ""))
    }

    @Test
    fun corruptMainFileIsRescuedFromBak() {
        assertTrue(JarvisStore.load(context))
        JarvisStore.setSection(JarvisStore.SEC_CHAT, JSONArray().apply {
            put(JSONObject().put("sender", "User").put("message", "keep me"))
        })
        // Corrupt ONLY the main file; the .bak from the last save is good.
        File(context.filesDir, "jarvis-store.json").writeText("{corrupt!!!")
        JarvisStore.resetForTests()
        assertTrue(JarvisStore.load(context))
        val back = JarvisStore.getSection(JarvisStore.SEC_CHAT) as? JSONArray
        assertEquals("keep me", back?.getJSONObject(0)?.optString("message", ""))
    }

    @Test
    fun doubleCorruptionFailsClosedWithoutCrashing() {
        assertTrue(JarvisStore.load(context))
        File(context.filesDir, "jarvis-store.json").writeText("{corrupt!!!")
        File(context.filesDir, "jarvis-store.json.bak").writeText("{also corrupt!!!")
        JarvisStore.resetForTests()
        assertFalse(JarvisStore.load(context))
        assertTrue(JarvisStore.lastError()?.contains("corrupt") == true)
    }

    @Test
    fun freshUserMigratesToAnEmptyStore() {
        assertTrue(JarvisStore.load(context))
        assertTrue(JarvisStore.isMigrated())
        // The projects registry section always exists (possibly empty).
        val registry = JarvisStore.getSection(JarvisStore.SEC_PROJECTS_REGISTRY) as? JSONObject
        assertTrue(registry != null)
        assertTrue(registry!!.has("projects"))
    }
}

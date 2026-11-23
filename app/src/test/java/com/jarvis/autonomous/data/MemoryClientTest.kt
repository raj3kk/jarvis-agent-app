package com.jarvis.autonomous.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2.4 (1.17.0-PRO) — unit tests for the MemoryClient's pure parts:
 * the /api/memory/list response parser and the signed-payload builder.
 * No network, no pairing — the device-signed request itself is covered
 * by the live hub probes (R2 server notes), not by this test.
 */
class MemoryClientTest {

    @Test
    fun parseListJsonReadsMemoriesAndPatterns() {
        val json = JSONObject(
            """
            {
              "ok": true,
              "memories": [
                {"id": 4, "content": "User ko subah chai pasand hai", "kind": "fact", "created_at": 1700000000000},
                {"id": 5, "content": "[Doc: test]\npehla hissa", "kind": "document", "created_at": 1700000001000}
              ],
              "patterns": [
                {"id": "p1", "template": "Har subah report bhejo", "source": "admin", "use_count": 3}
              ],
              "since": 1700000000000
            }
            """.trimIndent()
        )
        val result = MemoryClient.parseListJson(json)
        assertEquals(2, result.memories.size)
        assertEquals(4L, result.memories[0].id)
        assertEquals("fact", result.memories[0].kind)
        assertEquals("document", result.memories[1].kind)
        assertEquals(1, result.patterns.size)
        assertEquals("admin", result.patterns[0].source)
        assertEquals(3, result.patterns[0].useCount)
        assertEquals(1700000000000L, result.since)
    }

    @Test
    fun parseListJsonToleratesMissingArrays() {
        val result = MemoryClient.parseListJson(JSONObject("""{"ok":true}"""))
        assertTrue(result.memories.isEmpty())
        assertTrue(result.patterns.isEmpty())
        assertEquals(0L, result.since)
    }

    @Test
    fun listPayloadRawMatchesTheServerShape() {
        // Must be byte-identical to hub.ts:
        // `memory-list:${tenantId}:${since}:${limit}`.
        assertEquals("memory-list::0:50", MemoryClient.listPayloadRaw("", 0L, 50))
        assertEquals(
            "memory-list:lic-abc:1700000000000:200",
            MemoryClient.listPayloadRaw("lic-abc", 1700000000000L, 200)
        )
    }
}

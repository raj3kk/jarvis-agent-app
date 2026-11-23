package com.jarvis.autonomous.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2.7 (1.17.0-PRO) — unit tests for the ToolRegistry: every Executor
 * step kind resolves, unknown kinds resolve to null (the executor
 * turns that into the T-NO-HANDLER failure), and the planner
 * description names every tool.
 */
class ToolRegistryTest {

    @Test
    fun allExecutorKindsResolve() {
        val kinds = listOf(
            "BUILD", "GITHUB_PUSH", "SUPABASE_TABLES", "VERCEL_DEPLOY",
            "HTTP_CHECK", "GMAIL_SEND", "GMAIL_READ", "DRIVE_READ", "BROWSER_JOB"
        )
        for (kind in kinds) {
            val entry = ToolRegistry.resolve(kind)
            assertNotNull("kind must resolve: $kind", entry)
            assertEquals(kind, entry!!.name)
            assertTrue(entry.description.isNotBlank())
            assertTrue(entry.args.isNotEmpty())
        }
        assertEquals(9, ToolRegistry.all().size)
    }

    @Test
    fun unknownKindResolvesToNull() {
        assertNull(ToolRegistry.resolve("NOPE"))
        assertNull(ToolRegistry.resolve(""))
        assertNull(ToolRegistry.resolve("build"))
    }

    @Test
    fun describeForPlannerNamesEveryTool() {
        val text = ToolRegistry.describeForPlanner()
        for (kind in listOf("BUILD", "GITHUB_PUSH", "BROWSER_JOB", "GMAIL_SEND")) {
            assertTrue(text.contains(kind))
        }
    }

    @Test
    fun sensitiveToolsSitBehindTheHardGates() {
        assertEquals(
            ToolRegistry.GateLevel.SENSITIVE,
            ToolRegistry.resolve("GMAIL_SEND")!!.gate
        )
        assertEquals(
            ToolRegistry.GateLevel.SENSITIVE,
            ToolRegistry.resolve("SUPABASE_TABLES")!!.gate
        )
        assertEquals(
            ToolRegistry.GateLevel.SAFE,
            ToolRegistry.resolve("HTTP_CHECK")!!.gate
        )
    }
}

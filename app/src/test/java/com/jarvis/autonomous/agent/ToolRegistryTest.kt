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
            "HTTP_CHECK", "GMAIL_SEND", "GMAIL_READ", "DRIVE_READ", "BROWSER_JOB",
            // 1.26.0 (power features): the five shell automation kinds.
            "SHELL_TAP", "SHELL_TYPE", "SHELL_SCREENSHOT", "SHELL_APP", "SHELL_SETTING"
        )
        for (kind in kinds) {
            val entry = ToolRegistry.resolve(kind)
            assertNotNull("kind must resolve: $kind", entry)
            assertEquals(kind, entry!!.name)
            assertTrue(entry.description.isNotBlank())
            assertTrue(entry.args.isNotEmpty())
        }
        assertEquals(14, ToolRegistry.all().size)
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

    // R3.6: live-mode tools — only the 3 registry SAFE tools may
    // run from a Gemini Live function call (executeLiveTool
    // gate-checks through here). CONFIRM / SENSITIVE tools are
    // REFUSED from live mode; the local device tools (torch /
    // volume / media) are not registry tools — the engine allowlists
    // them by name.
    //
    // 1.26.0: the SAFE shell kinds (TAP/TYPE/SCREENSHOT) join the
    // live-mode set (6 SAFE tools now); SHELL_APP/SHELL_SETTING are
    // CONFIRM and stay refused from live mode.
    @Test
    fun liveModeRegistryToolsAreSafeOnly() {
        for (kind in listOf("HTTP_CHECK", "GMAIL_READ", "DRIVE_READ", "SHELL_TAP", "SHELL_TYPE", "SHELL_SCREENSHOT")) {
            assertEquals(
                ToolRegistry.GateLevel.SAFE,
                ToolRegistry.resolve(kind)!!.gate
            )
        }
        for (kind in listOf("BUILD", "GITHUB_PUSH", "VERCEL_DEPLOY", "BROWSER_JOB", "SHELL_APP", "SHELL_SETTING")) {
            assertEquals(
                ToolRegistry.GateLevel.CONFIRM,
                ToolRegistry.resolve(kind)!!.gate
            )
        }
        for (kind in listOf("GMAIL_SEND", "SUPABASE_TABLES")) {
            assertEquals(
                ToolRegistry.GateLevel.SENSITIVE,
                ToolRegistry.resolve(kind)!!.gate
            )
        }
    }

    // 1.26.0: destructive app actions sit behind CONFIRM — the
    // executor hard-gates them again when needsGate is false.
    @Test
    fun shellAppAndSettingAreConfirmGated() {
        assertEquals(
            ToolRegistry.GateLevel.CONFIRM,
            ToolRegistry.resolve("SHELL_APP")!!.gate
        )
        assertEquals(
            ToolRegistry.GateLevel.CONFIRM,
            ToolRegistry.resolve("SHELL_SETTING")!!.gate
        )
        assertTrue(ToolRegistry.resolve("SHELL_APP")!!.args.contains("action"))
        assertTrue(ToolRegistry.resolve("SHELL_SETTING")!!.args.contains("setting"))
    }
}

package com.jarvis.autonomous.agent

import com.jarvis.autonomous.agent.TalkerDispatcher.TalkerDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.8.0 (I2) — Talker dispatcher tests.
 * Pure JVM tests: the dispatcher has zero Android imports.
 *
 * The agent-mode keyword list MUST stay in sync with
 * JarvisAgentEngine.isAgentModeCommand (frozen trigger: GitHub /
 * Supabase / Vercel / deploy / "agent mode") — these tests pin it.
 */
class TalkerDispatcherTest {

    @Test
    fun cancelWinsOverEverything() {
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("stop", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("ruk ja", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("ruko", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("cancel", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("bas kar", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("cancel karo ye kaam", false))
        assertEquals(TalkerDecision.CANCEL_TASK, TalkerDispatcher.classify("ruk ja, bas", true))
    }

    @Test
    fun statusQueryOnlyWhileWorkRuns() {
        assertEquals(TalkerDecision.STATUS_QUERY, TalkerDispatcher.classify("ho gaya?", true))
        assertEquals(TalkerDecision.STATUS_QUERY, TalkerDispatcher.classify("website ban gayi?", true))
        assertEquals(TalkerDecision.STATUS_QUERY, TalkerDispatcher.classify("kitna hua", true))
        assertEquals(TalkerDecision.STATUS_QUERY, TalkerDispatcher.classify("status kya hai", true))
        assertEquals(TalkerDecision.STATUS_QUERY, TalkerDispatcher.classify("kab tak hoga", true))
        // Same words with no running work are chat, not status.
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("ho gaya?", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("status kya hai", false))
    }

    @Test
    fun agentModeKeywordsRouteToPlan() {
        assertEquals(TalkerDecision.EXECUTE_PLAN, TalkerDispatcher.classify("github par push karo", false))
        assertEquals(TalkerDecision.EXECUTE_PLAN, TalkerDispatcher.classify("supabase tables banao", false))
        assertEquals(TalkerDecision.EXECUTE_PLAN, TalkerDispatcher.classify("vercel par deploy karo", false))
        assertEquals(TalkerDecision.EXECUTE_PLAN, TalkerDispatcher.classify("deploy kar do", false))
        assertEquals(TalkerDecision.EXECUTE_PLAN, TalkerDispatcher.classify("agent mode on karo", false))
    }

    @Test
    fun workVerbsRouteToLoop() {
        assertEquals(TalkerDecision.EXECUTE_LOOP, TalkerDispatcher.classify("ek website banao", false))
        assertEquals(TalkerDecision.EXECUTE_LOOP, TalkerDispatcher.classify("mere dukaan ke liye site banado", false))
        assertEquals(TalkerDecision.EXECUTE_LOOP, TalkerDispatcher.classify("youtube kholo", false))
        assertEquals(TalkerDecision.EXECUTE_LOOP, TalkerDispatcher.classify("kuch dhoondho", false))
        assertEquals(TalkerDecision.EXECUTE_LOOP, TalkerDispatcher.classify("ye file save karo", false))
    }

    @Test
    fun questionsStayTalkEvenWithVerbs() {
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("youtube kaise chalate hain", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("website kya hai", false))
        // NOTE: "deploy ka matlab kya hai" is EXECUTE_PLAN, not TALK —
        // the frozen agent-mode trigger ("deploy") is checked before
        // question markers, by design. A question without a trigger
        // word stays TALK:
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("website ka matlab kya hai", false))
    }

    @Test
    fun plainChatStaysTalk() {
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("namaste, kaisa hai", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("aaj mausam kaisa hai", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("tum kaun ho", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("", false))
        assertEquals(TalkerDecision.TALK, TalkerDispatcher.classify("   ", false))
    }

    @Test
    fun transliteratedMatchTextRoutesDevanagari() {
        // Devanagari "गीथब" with a transliterated match text routes
        // to PLAN; without it the Latin verb still lands on LOOP
        // (safe direction — work, not chat — just not the plan path).
        assertEquals(
            TalkerDecision.EXECUTE_PLAN,
            TalkerDispatcher.classify("गीथब पर push karo", false, "github par push karo")
        )
        assertEquals(
            TalkerDecision.EXECUTE_LOOP,
            TalkerDispatcher.classify("गीथब पर push karo", false)
        )
    }

    @Test
    fun isStatusQuestionDetectsPatterns() {
        assertTrue(TalkerDispatcher.isStatusQuestion("ho gaya?"))
        assertTrue(TalkerDispatcher.isStatusQuestion("kitna bacha hai"))
        assertFalse(TalkerDispatcher.isStatusQuestion("ek website banao"))
        assertFalse(TalkerDispatcher.isStatusQuestion("namaste"))
    }
}

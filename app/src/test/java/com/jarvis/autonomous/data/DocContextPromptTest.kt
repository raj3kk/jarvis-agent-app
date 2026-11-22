package com.jarvis.autonomous.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-I-2 (1.14.0): DocContextPrompt — the I1 → I2 wiring's only pure
 * seam. JVM unit test (JUnit 4, runs in CI `./gradlew
 * testDebugUnitTest`); no Android, no Robolectric needed.
 */
class DocContextPromptTest {

    private val docs = "(Document 'Aadhaar' se yaad: mera number 1234 hai)\n" +
        "(Document 'Bank' se yaad: account 9876 hai)"

    // ---- planGoalWithDocs ----

    @Test
    fun planGoal_noDocs_isByteIdenticalToPreI2() {
        // The pre-I2 inline buildString in startNewPlan, reproduced
        // exactly: a no-recall turn must not change the planner prompt.
        val expected = "github pe repo banao\n" +
            "Pehle seekha hua tareeka (hint — zaroori nahi): pehle build karo. Agar context badla ho to ignore karo.\n" +
            "Yaad rakha: hamesha main branch"
        assertEquals(
            expected,
            DocContextPrompt.planGoalWithDocs(
                goal = "github pe repo banao",
                learnedHint = "pehle build karo",
                instructionHint = "hamesha main branch",
                docContext = ""
            )
        )
    }

    @Test
    fun planGoal_blankHintsAreSkipped() {
        assertEquals(
            "deploy karo",
            DocContextPrompt.planGoalWithDocs("deploy karo", "", "  ", "")
        )
    }

    @Test
    fun planGoal_docsAppendedLabelledAfterHints() {
        val out = DocContextPrompt.planGoalWithDocs(
            goal = "mere documents me kya hai?",
            learnedHint = "",
            instructionHint = "",
            docContext = docs
        )
        assertTrue(out.startsWith("mere documents me kya hai?"))
        assertTrue(out.contains(DocContextPrompt.DOC_HEADER))
        assertTrue(out.contains("(Document 'Aadhaar' se yaad:"))
        // Docs come after the goal, never before it.
        assertTrue(out.indexOf("mere documents") < out.indexOf(DocContextPrompt.DOC_HEADER))
    }

    @Test
    fun planGoal_headerAppearsExactlyOnce() {
        // One header line even when the context holds several docs —
        // the per-doc labels come from buildDocContext itself.
        val three = (1..3).joinToString("\n") { "(Document 'D$it' se yaad: x)" }
        val out = DocContextPrompt.planGoalWithDocs("g", docContext = three)
        assertEquals(1, out.split(DocContextPrompt.DOC_HEADER).size - 1)
        assertEquals(3, out.split("(Document '").size - 1)
    }

    // ---- talkerSystemPrompt ----

    @Test
    fun talker_noDocs_returnsPersonaUntouched() {
        assertEquals(
            "Tum Jarvis ho.",
            DocContextPrompt.talkerSystemPrompt("Tum Jarvis ho.", "")
        )
    }

    @Test
    fun talker_docsAppendedWithHeader_userTextUntouched() {
        val out = DocContextPrompt.talkerSystemPrompt("Tum Jarvis ho.", docs)
        assertTrue(out.startsWith("Tum Jarvis ho."))
        assertTrue(out.contains(DocContextPrompt.DOC_HEADER))
        assertTrue(out.contains("(Document 'Bank' se yaad:"))
        // The doc lines are context — they never claim to be the answer.
        assertFalse(out.contains("verbatim"))
    }
}

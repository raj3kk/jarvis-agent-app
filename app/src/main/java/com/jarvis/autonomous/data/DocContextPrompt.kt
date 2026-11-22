package com.jarvis.autonomous.data

/**
 * M-I-2 (1.14.0) — pure prompt assembly for the I1 → I2 doc-context
 * wiring: recalled on-device document snippets reach the planner
 * (EXECUTE_PLAN) and the Talker's on-device local reply (TALK) as
 * labelled prompt context.
 *
 * Pure Kotlin, no Android — unit-tested by DocContextPromptTest.
 *
 * Contract (same as the existing recall path, I1 1.7.1):
 * - [docContext] is the output of [DocumentText.buildDocContext]:
 *   max 3 docs, one labelled line per doc, snippets ≤400 chars.
 * - It is CONTEXT ONLY, never a verbatim answer: the header line says
 *   so ("sirf context"). Nothing here changes recall scoring
 *   ([DocumentSearch]) or the offline direct-answer path
 *   (tryDocumentRecall), which still answers without any LLM call.
 * - Empty docContext changes NOTHING: planGoalWithDocs returns the
 *   exact pre-I2 string, talkerSystemPrompt returns personaPrompt
 *   untouched (pinned by tests — a no-recall turn must be
 *   byte-identical to before).
 * - Privacy: doc text stays on the phone for the Talker local reply
 *   (on-device Gemma). For EXECUTE_PLAN the snippets ride the planner
 *   prompt to Gemini — the same cloud the plan already uses; the user
 *   explicitly saved these documents for Jarvis to use, and only
 *   recalled snippets (≤3 × ≤400 chars) are sent, never a whole doc.
 */
object DocContextPrompt {

    /** Header line marking recalled snippets as context, not an answer. */
    const val DOC_HEADER = "Saved documents se yaad (sirf context):"

    /**
     * Planner goal for [AgentPlanExecutor.startNewPlan]: the user's goal
     * plus the 1.9.0 (v2) hint lines, plus recalled doc context when
     * there is any. Replaces the inline buildString in startNewPlan —
     * the no-doc output is byte-identical to the old inline version.
     */
    fun planGoalWithDocs(
        goal: String,
        learnedHint: String = "",
        instructionHint: String = "",
        docContext: String = ""
    ): String = buildString {
        append(goal)
        if (learnedHint.isNotBlank()) {
            append("\nPehle seekha hua tareeka (hint — zaroori nahi): $learnedHint. Agar context badla ho to ignore karo.")
        }
        if (instructionHint.isNotBlank()) {
            append("\nYaad rakha: $instructionHint")
        }
        if (docContext.isNotBlank()) {
            append("\n$DOC_HEADER\n$docContext")
        }
    }

    /**
     * System prompt for the Talker's on-device local reply: the persona
     * prompt plus recalled doc context when there is any. The doc lines
     * go in the system instruction (not the user text) so the user's
     * own words stay untouched and the model treats the snippets as
     * background, not as something the user said.
     */
    fun talkerSystemPrompt(personaPrompt: String, docContext: String = ""): String =
        if (docContext.isBlank()) personaPrompt
        else "$personaPrompt\n$DOC_HEADER\n$docContext"
}

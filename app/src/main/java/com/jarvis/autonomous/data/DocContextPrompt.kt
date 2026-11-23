package com.jarvis.autonomous.data

/**
 * M-I-2 (1.14.0) — pure prompt assembly for the I1 → I2 doc-context
 * wiring: recalled on-device document snippets reach the planner
 * (EXECUTE_PLAN) and the Talker's reply (TALK) as labelled prompt
 * context. (1.20.0: the Talker reply is the cloud loop now — Gemma
 * is gone — so the snippets ride the cloud prompt, not an
 * on-device model. See the Privacy bullet below.)
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
 * - Privacy (1.20.0: the on-device Gemma reply is gone, so this
 *   changed): recalled snippets now ride the cloud prompt to Gemini
 *   for TALK turns as well as EXECUTE_PLAN — the same cloud the
 *   chat already uses; the user explicitly saved these documents
 *   for Jarvis to use, and only recalled snippets (≤3 × ≤400 chars)
 *   are sent, never a whole doc. The offline direct-answer path
 *   (tryDocumentRecall) still answers without any LLM call and
 *   keeps doc text on the phone.
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
     * System prompt for the Talker's reply: the persona prompt plus
     * recalled doc context when there is any. The doc lines go in
     * the system instruction (not the user text) so the user's own
     * words stay untouched and the model treats the snippets as
     * background, not as something the user said. (1.20.0: no
     * production caller any more — the TALK turn goes through the
     * cloud loop, which carries doc context via buildMemoryContext.
     * Kept as a tested pure helper; delete in a later cleanup if
     * it stays uncalled.)
     */
    fun talkerSystemPrompt(personaPrompt: String, docContext: String = ""): String =
        if (docContext.isBlank()) personaPrompt
        else "$personaPrompt\n$DOC_HEADER\n$docContext"
}

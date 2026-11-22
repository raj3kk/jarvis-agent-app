package com.jarvis.autonomous.agent

/**
 * Talker reply wording + latency budget.
 *
 * DESIGN: ~/workspace/omnijarvis-implementation/expansion/talker-executor/DESIGN.md §2, §5, §6
 * Release 1.8.0-PRO (vc 44). Pure Kotlin — ZERO Android imports — so
 * this file unit-tests on a plain JVM.
 *
 * Every function here is a pure function of its inputs: no LLM, no
 * network, no disk, no clock reads except through the parameters the
 * caller passes. That is the whole latency guarantee — an ack or a
 * status answer cannot be slow, because there is nothing in it that
 * CAN be slow.
 *
 * Wording rules (from the existing engine voice): Hinglish, short,
 * one claim per spoken line. Completion ("ho gaya") is spoken ONLY for
 * a DONE result — never for an ack, never for a timeout, never for a
 * failure. Details go to chat, never to speech.
 */
object TalkerReplyPolicy {

    /** Ack must reach speak-start within this budget (templated, no I/O). */
    const val TALKER_ACK_TARGET_MS = 300L

    /** Status answers use the same budget as acks (bus state only). */
    const val TALKER_STATUS_TARGET_MS = 300L

    /** Local-Gemma first reply for a TALK turn: typical budget after
     * the model is warm. The hard bound is [TALKER_LOCAL_REPLY_TIMEOUT_MS]. */
    const val TALKER_REPLY_TARGET_MS = 1_500L

    /** Hard bound on the local-Gemma attempt: on expiry the Talker falls
     * through to today's cloud loop instead of stalling (DESIGN.md §6). */
    const val TALKER_LOCAL_REPLY_TIMEOUT_MS = 5_000L

    /**
     * Instant ack for a dispatched EXECUTE command. Templated — the
     * goal is echoed (trimmed) so the user hears their own work named
     * back, and the line promises presence ("main yahin hoon"), never
     * completion.
     */
    fun ackFor(command: ExecutorCommand): String {
        val goal = command.goal.trim().take(80).ifBlank { "ye kaam" }
        return when (command.workKind) {
            ExecutorWorkKind.PLAN ->
                "Ho raha hai — $goal. Plan bana kar peeche se karwa raha hoon, main yahin hoon."
            ExecutorWorkKind.LOOP ->
                "Ho raha hai — $goal. Main yahin hoon, ho jayega to bata dunga."
            ExecutorWorkKind.ROUTINE ->
                "Kar raha hoon — $goal."
        }
    }

    /**
     * Instant status answer while a command runs. Reads only bus state
     * passed in by the caller — no LLM, no Executor query.
     *
     * @param command the running command (null when nothing runs).
     * @param stepDescription the Executor's current step line (may be
     *   blank — the answer degrades to elapsed time only, never invents
     *   a step).
     * @param elapsedMs time since the command was issued.
     */
    fun statusLine(
        command: ExecutorCommand?,
        stepDescription: String?,
        elapsedMs: Long
    ): String {
        if (command == null) return "Abhi koi kaam chal nahi raha — kuch karwana ho to bolo."
        val minutes = elapsedMs / 60_000L
        val elapsed = when {
            minutes <= 0 -> "abhi-abhi shuru hua hai"
            minutes == 1L -> "1 minute se chal raha hai"
            else -> "$minutes minute se chal raha hai"
        }
        val step = stepDescription?.trim()?.take(100).orEmpty()
        val goal = command.goal.trim().take(80).ifBlank { "kaam" }
        return if (step.isBlank()) {
            "Abhi ban raha hai — $goal, $elapsed."
        } else {
            "Abhi chal raha hai — $goal, $elapsed. $step"
        }
    }

    /**
     * The Talker's spoken + chat line for a delivered [ExecutorResult].
     * One claim per line; DONE is the only status that may say "ho gaya".
     */
    fun resultLine(result: ExecutorResult): String {
        val summary = result.summary.trim()
            .take(TalkerExecutorProtocol.MAX_SUMMARY_CHARS)
        val prefix = if (result.isLate) "Der se hi sahi — " else ""
        return when (result.status) {
            ExecutorResultStatus.DONE ->
                prefix + if (summary.isBlank()) "Ho gaya bhai!" else "Ho gaya bhai! $summary"
            ExecutorResultStatus.FAILED ->
                prefix + "Bhai, kaam poora nahi ho paya — " +
                    if (summary.isBlank()) "dobara bolo to phir se koshish karunga."
                    else "$summary Dobara bolo to phir se koshish karunga."
            ExecutorResultStatus.BLOCKED ->
                prefix + "Kaam ruk gaya — " +
                    if (summary.isBlank()) "tumhare jawab ka intezaar hai. Batao to aage badhaun."
                    else "$summary Batao to aage badhaun."
            ExecutorResultStatus.DECLINED ->
                "Theek hai, rok diya."
            ExecutorResultStatus.TIMEOUT ->
                "Time zyada lag raha hai — kaam chalu hai, main peeche se karwa raha hoon. Ho jayega to bata dunga."
            ExecutorResultStatus.CANCELLED ->
                // A non-blank summary means the Talker cancelled this
                // command to supersede it with new work (the summary
                // names the new work); a blank summary is a plain stop.
                if (summary.isBlank()) "Theek hai bhai, ruk gaya." else summary
        }
    }

    /** Line spoken when a NEW command supersedes a running one. */
    fun supersededLine(newGoal: String): String {
        val goal = newGoal.trim().take(80).ifBlank { "naya kaam" }
        return "Pehla kaam rok diya, $goal shuru kar raha hoon."
    }

    /**
     * Line spoken when a chat turn cannot go to the cloud loop because
     * an Executor command is running — and the local-Gemma attempt
     * already failed. The Talker never cancels running work for chat.
     */
    fun busyLine(runningGoal: String): String {
        val goal = runningGoal.trim().take(80).ifBlank { "ek kaam" }
        return "Abhi $goal chal raha hai — uske baad tumhari baat ka jawab dunga."
    }

    /** Line spoken when a command is invalid and cannot be dispatched. */
    fun invalidCommandLine(errors: List<String>): String =
        "Ye kaam aise nahi bhej sakta — ${errors.firstOrNull() ?: "galat command"}. Dobara bolo."
}

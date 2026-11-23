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
     * @param currentActivity the Executor's current activity line
     *   (may be blank — the answer degrades to elapsed time only,
     *   never invents an activity).
     * @param elapsedMs time since the command was issued.
     */
    fun statusLine(
        command: ExecutorCommand?,
        currentActivity: String?,
        elapsedMs: Long
    ): String {
        if (command == null) return "Abhi koi kaam chal nahi raha — kuch karwana ho to bolo."
        val minutes = elapsedMs / 60_000L
        val elapsed = when {
            minutes <= 0 -> "abhi-abhi shuru hua hai"
            minutes == 1L -> "1 minute se chal raha hai"
            else -> "$minutes minute se chal raha hai"
        }
        val activity = currentActivity?.trim()?.take(100).orEmpty()
        val goal = command.goal.trim().take(80).ifBlank { "kaam" }
        return if (activity.isBlank()) {
            "Abhi ban raha hai — $goal, $elapsed."
        } else {
            "Abhi chal raha hai — $goal, $elapsed. $activity"
        }
    }

    /**
     * 1.9.0 (v2): status answer from the freshest structured
     * progress (DESIGN §3.5). Stale progress (older than
     * [TalkerProgressPolicy.PROGRESS_STALE_MS]) is never quoted as
     * current: the answer degrades to elapsed-only plus the stalled
     * line when the job looks stalled. ETA is spoken only when
     * `etaSeconds > 0` — loops never carry one.
     */
    fun statusLine(
        command: ExecutorCommand?,
        progress: ExecutorProgress?,
        elapsedMs: Long,
        stalled: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        if (command == null) return "Abhi koi kaam chal nahi raha — kuch karwana ho to bolo."
        val minutes = elapsedMs / 60_000L
        val elapsed = when {
            minutes <= 0 -> "abhi-abhi shuru hua hai"
            minutes == 1L -> "1 minute se chal raha hai"
            else -> "$minutes minute se chal raha hai"
        }
        val goal = command.goal.trim().take(80).ifBlank { "kaam" }
        val stalledSuffix = if (stalled) " ${TalkerProgressPolicy.stalledLine()}" else ""
        if (progress == null || TalkerProgressPolicy.isStale(progress, nowMs)) {
            return "Abhi ban raha hai — $goal, $elapsed.$stalledSuffix"
        }
        // R1 (no-step): plain-words status only — the label is the
        // current activity, never a "Step N/M" count.
        val label = progress.stepLabel.trim().take(80)
        val eta = TalkerProgressPolicy.etaClause(progress.etaSeconds)
        return "Abhi chal raha hai — $goal, $elapsed. Abhi $label chal raha hai.$eta$stalledSuffix"
    }

    /**
     * 1.9.0 (v2): one progress line, spoken and chat share the same
     * text (the chat rendering adds only the `⏳` marker — see
     * [progressChatLine]). R1 (no-step): the line names the current
     * activity in plain words ("X chal raha hai / ho gaya") and, for
     * plans, the next activity — never a count, never the word
     * "step". Loops never invent a next activity (DESIGN §3.3).
     * `phase` START = "shuru hua", DONE = "ho gaya" for that
     * activity (an activity "ho gaya" is fine — only a terminal
     * DONE may claim the whole task is done).
     */
    fun progressLine(progress: ExecutorProgress, nextLabel: String?): String {
        val label = progress.stepLabel.trim().take(80)
        val verb = when (progress.phase) {
            "DONE" -> "ho gaya"
            "START" -> "shuru hua"
            else -> "chal raha hai"
        }
        val next = if (progress.stepTotal > 0 && !nextLabel.isNullOrBlank()) {
            " — aage: ${nextLabel.trim().take(80)}"
        } else if (progress.stepTotal > 0 && progress.stepIndex >= progress.stepTotal) {
            // Last plan unit: no next activity to name.
            " — bas, khatm hone wala hai"
        } else {
            ""
        }
        return "$label $verb$next"
    }

    /** Chat rendering of a progress line: same text, `⏳` marker only. */
    fun progressChatLine(progress: ExecutorProgress, nextLabel: String?): String =
        "⏳ ${progressLine(progress, nextLabel)}"

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

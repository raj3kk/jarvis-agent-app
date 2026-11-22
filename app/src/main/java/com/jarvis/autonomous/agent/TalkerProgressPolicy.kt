package com.jarvis.autonomous.agent

/**
 * Talker progress throttling / dedupe / stall rules (v2 Gap 1).
 *
 * DESIGN.md: expansion/talker-executor-v2/DESIGN.md §3.3–§3.6.
 * Release 1.9.0-PRO (vc 45). Pure Kotlin — ZERO Android imports —
 * so this file unit-tests on a plain JVM. Every function is a pure
 * function of its inputs (same testability rule as TalkerReplyPolicy).
 *
 * The numbers are the annoyance bound: a milestone may arrive every
 * few seconds from a fast plan, but the user hears at most one
 * spoken progress line per 30 s, and the same step label is never
 * spoken twice within 60 s. Progress is a liveness signal for the
 * user, never a lease renewal for the job: it never resets the
 * 15-min watchdog and never satisfies the safety net.
 */
object TalkerProgressPolicy {

    /** Minimum gap between two *spoken* progress lines, same command. */
    const val MIN_SPOKEN_PROGRESS_INTERVAL_MS = 30_000L

    /** Minimum gap between two progress *chat* lines, same command. */
    const val MIN_CHAT_PROGRESS_INTERVAL_MS = 5_000L

    /** Same stepLabel within this window is never spoken twice. */
    const val PROGRESS_DEDUPE_WINDOW_MS = 60_000L

    /** A progress line delivered later than this is chat-only, never spoken. */
    const val PROGRESS_STALE_MS = 120_000L

    /** No progress *and* no state change for this long while RUNNING = stalled. */
    const val STALLED_AFTER_MS = 300_000L

    /**
     * Speak this milestone only if ALL hold: it is a milestone, the
     * spoken interval elapsed, the label is not a dedupe repeat, the
     * line is not stale, no gate question is outstanding, the engine
     * is in EXECUTING_STEP (never while SPEAKING a question/reply or
     * WAITING_USER_REPLY), and the announce level for this command
     * is FULL. `lastSpokenLabelAtMs` is when [lastSpokenLabel] was
     * last spoken (Long.MIN_VALUE / null label = never spoken).
     */
    fun shouldSpeak(
        progress: ExecutorProgress,
        nowMs: Long,
        lastSpokenAtMs: Long,
        lastSpokenLabel: String?,
        lastSpokenLabelAtMs: Long,
        gateQuestionOutstanding: Boolean,
        isExecutingStep: Boolean,
        announceLevelFull: Boolean
    ): Boolean {
        if (!progress.isMilestone) return false
        if (!announceLevelFull) return false
        if (gateQuestionOutstanding) return false
        if (!isExecutingStep) return false
        if (nowMs - lastSpokenAtMs < MIN_SPOKEN_PROGRESS_INTERVAL_MS) return false
        if (nowMs - progress.emittedAtMs > PROGRESS_STALE_MS) return false
        if (lastSpokenLabel != null && progress.stepLabel == lastSpokenLabel &&
            nowMs - lastSpokenLabelAtMs < PROGRESS_DEDUPE_WINDOW_MS
        ) {
            return false
        }
        return true
    }

    /**
     * Post a progress chat line when the chat interval elapsed AND
     * the label or fraction changed since the last posted line.
     * Faster arrivals update only `lastProgress` (status answers
     * still see them).
     */
    fun shouldPostToChat(
        progress: ExecutorProgress,
        nowMs: Long,
        lastPostedAtMs: Long,
        lastPostedLabel: String?,
        lastPostedFraction: Double
    ): Boolean {
        if (nowMs - lastPostedAtMs < MIN_CHAT_PROGRESS_INTERVAL_MS) return false
        if (progress.stepLabel == lastPostedLabel &&
            progress.progressFraction == lastPostedFraction
        ) {
            return false
        }
        return true
    }

    /** True when the progress is too old to quote as current. */
    fun isStale(progress: ExecutorProgress, nowMs: Long): Boolean =
        nowMs - progress.emittedAtMs > PROGRESS_STALE_MS

    /** True when no progress arrived for [STALLED_AFTER_MS] while running. */
    fun isStalled(lastProgressAtMs: Long, nowMs: Long): Boolean =
        nowMs - lastProgressAtMs >= STALLED_AFTER_MS

    /** Status-query appendix when the job looks stalled (DESIGN §3.6).
     *  The Talker does NOT cancel and does NOT speak unprompted — this
     *  line is only appended to a status answer the user asked for. */
    fun stalledLine(): String =
        "Pichle 5 minute se koi naya step nahi dikh raha — kaam chal raha hai, par slow lag raha hai."

    /** ETA clause for a spoken/status line. Loops never carry an ETA. */
    fun etaClause(etaSeconds: Long): String {
        if (etaSeconds <= 0) return ""
        return if (etaSeconds < 60) " — lagbhag $etaSeconds second aur."
        else " — lagbhag ${etaSeconds / 60} minute aur."
    }
}

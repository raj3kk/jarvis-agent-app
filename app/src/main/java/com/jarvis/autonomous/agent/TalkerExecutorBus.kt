package com.jarvis.autonomous.agent

/**
 * Talker/Executor command bus — the ONLY handoff object between layers.
 *
 * DESIGN: ~/workspace/omnijarvis-implementation/expansion/talker-executor/DESIGN.md §4
 * Release 1.8.0-PRO (vc 44). Pure Kotlin — ZERO Android imports, no
 * coroutines — so this file unit-tests on a plain JVM.
 *
 * The Talker submits [ExecutorCommand]s; the Executor completes them with
 * [ExecutorResult]s. Listeners are notified synchronously on complete, so
 * a listener must never block — the engine's listener only launches a
 * coroutine that posts the Talker result line to chat + speaks it.
 *
 * Thread-safety: all shared state is guarded by `synchronized`
 * (listeners are notified after the lock is released, from a
 * snapshot). The bus is in-memory only in v1: a process kill loses
 * pending commands (plan resume still covers persisted agent-mode
 * plans; loop tasks do not resume — see DESIGN.md §5).
 *
 * 1.9.0 (v2): progress + fix channels (DESIGN.md §2.4 of
 * expansion/talker-executor-v2). `reportProgress` delivers
 * STEP_PROGRESS milestones; the bus keeps only the LATEST progress
 * per command id (bounded by the pending set + completed history,
 * so a STATUS_QUERY can always read the freshest milestone).
 * `requestFix` / `completeFix` carry the FIX_REQUEST / FIX_RESULT
 * pair; a FIX_RESULT with no outstanding FIX_REQUEST is dropped.
 * Progress is never stored in `completedHistory` and never touches
 * the pending map — it cannot satisfy the safety net or reset the
 * watchdog. After a TIMEOUT was delivered, progress keeps flowing
 * (the job runs on): progress is accepted for pending ids AND for
 * ids already in completed history, but never for cancelled /
 * never-seen ids.
 */
class TalkerExecutorBus {

    enum class SubmitResult { ACCEPTED, INVALID, DUPLICATE_ID }

    /** Bounded completed history — the Talker's "what just finished" view. */
    private val completedHistory = ArrayDeque<ExecutorResult>()
    private val pending = linkedMapOf<String, ExecutorCommand>()
    private val listeners = mutableListOf<(ExecutorResult) -> Unit>()
    /** Latest progress per command id (v2). */
    private val lastProgressMap = linkedMapOf<String, ExecutorProgress>()
    private val progressListeners = mutableListOf<(ExecutorProgress) -> Unit>()
    /** Command ids with an outstanding FIX_REQUEST (v2). */
    private val outstandingFixRequests = mutableSetOf<String>()
    private val fixRequestListeners = mutableListOf<(ExecutorFixRequest) -> Unit>()
    private val fixListeners = mutableListOf<(ExecutorFixResult) -> Unit>()

    companion object {
        const val MAX_COMPLETED_HISTORY = 20
    }

    fun addListener(listener: (ExecutorResult) -> Unit) {
        synchronized(this) { listeners.add(listener) }
    }

    fun removeListener(listener: (ExecutorResult) -> Unit) {
        synchronized(this) { listeners.remove(listener) }
    }

    /** v2: progress listeners are notified synchronously, same
     *  never-block contract as result listeners. */
    fun addProgressListener(listener: (ExecutorProgress) -> Unit) {
        synchronized(this) { progressListeners.add(listener) }
    }

    fun removeProgressListener(listener: (ExecutorProgress) -> Unit) {
        synchronized(this) { progressListeners.remove(listener) }
    }

    /** v2: FIX_REQUEST listeners (the Executor's fix handler). */
    fun addFixRequestListener(listener: (ExecutorFixRequest) -> Unit) {
        synchronized(this) { fixRequestListeners.add(listener) }
    }

    /** v2: FIX_RESULT listeners (the Talker's verification gate). */
    fun addFixListener(listener: (ExecutorFixResult) -> Unit) {
        synchronized(this) { fixListeners.add(listener) }
    }

    /**
     * Submit a command. INVALID when [ExecutorCommand.validate] fails
     * (never submitted); DUPLICATE_ID when the id is already pending
     * (idempotency — never executed twice). A duplicate of an already
     * COMPLETED id is ACCEPTED: it is a new command that happens to
     * reuse an id only if the Talker generated it fresh (UUIDs), so in
     * practice this never fires — but a re-submit of a completed id must
     * not be silently dropped either; the engine treats it as new work.
     */
    @Synchronized
    fun submit(command: ExecutorCommand): SubmitResult {
        if (command.validate().isNotEmpty()) return SubmitResult.INVALID
        if (pending.containsKey(command.id)) return SubmitResult.DUPLICATE_ID
        pending[command.id] = command
        return SubmitResult.ACCEPTED
    }

    /**
     * Deliver a result. Returns false (dropped) for an unknown
     * commandId. A second terminal result for the same command — e.g.
     * a DONE that arrives after a TIMEOUT was already delivered — is
     * still notified, marked [ExecutorResult.isLate], so the Talker can
     * tell the user as a follow-up instead of swallowing it.
     * Listeners are notified AFTER the lock is released (snapshot
     * taken inside), so a listener can never deadlock the bus.
     */
    fun complete(result: ExecutorResult): Boolean {
        val delivered: ExecutorResult
        val snapshot: List<(ExecutorResult) -> Unit>
        synchronized(this) {
            if (result.validate().isNotEmpty()) return false
            val wasPending = pending.remove(result.commandId) != null
            val alreadyCompleted = completedHistory.any { it.commandId == result.commandId }
            if (!wasPending && !alreadyCompleted) return false
            delivered = if (!wasPending && alreadyCompleted) {
                result.copy(isLate = true)
            } else {
                result
            }
            completedHistory.addLast(delivered)
            while (completedHistory.size > MAX_COMPLETED_HISTORY) completedHistory.removeFirst()
            // v2: a terminal result drops the command's last progress
            // (status answers no longer apply). TIMEOUT is advisory —
            // the job runs on, so its progress stays readable and
            // keeps updating via reportProgress.
            if (result.status != ExecutorResultStatus.TIMEOUT) {
                lastProgressMap.remove(result.commandId)
            }
            snapshot = listeners.toList()
        }
        snapshot.forEach { listener ->
            runCatching { listener(delivered) }
        }
        return true
    }

    /** Remove a pending command; returns it, or null when not pending. */
    @Synchronized
    fun cancel(commandId: String): ExecutorCommand? {
        lastProgressMap.remove(commandId)
        outstandingFixRequests.remove(commandId)
        return pending.remove(commandId)
    }

    /**
     * v2: deliver a STEP_PROGRESS milestone. Returns false (dropped)
     * when the commandId is not pending AND has no completed-history
     * entry (stale progress after supersede/cancel is killed by this
     * guard, exactly like `complete()`). Progress does not touch the
     * pending map or completed history. The latest progress per id is
     * kept so STATUS_QUERY can read it without a listener.
     */
    fun reportProgress(progress: ExecutorProgress): Boolean {
        val snapshot: List<(ExecutorProgress) -> Unit>
        synchronized(this) {
            if (progress.validate().isNotEmpty()) return false
            val known = pending.containsKey(progress.commandId) ||
                completedHistory.any { it.commandId == progress.commandId }
            if (!known) return false
            lastProgressMap[progress.commandId] = progress
            snapshot = progressListeners.toList()
        }
        snapshot.forEach { listener ->
            runCatching { listener(progress) }
        }
        return true
    }

    /** v2: freshest progress for a command, or null when none arrived yet. */
    @Synchronized
    fun lastProgress(commandId: String): ExecutorProgress? = lastProgressMap[commandId]

    /**
     * v2: post a FIX_REQUEST. Returns false (dropped) when the
     * command is unknown (not pending, no completed-history entry)
     * or the request is invalid (attempt > maxAttempts). Marks the
     * command as having an outstanding fix so `completeFix` can
     * enforce the pairing.
     */
    fun requestFix(request: ExecutorFixRequest): Boolean {
        val snapshot: List<(ExecutorFixRequest) -> Unit>
        synchronized(this) {
            if (request.validate().isNotEmpty()) return false
            val known = pending.containsKey(request.commandId) ||
                completedHistory.any { it.commandId == request.commandId }
            if (!known) return false
            outstandingFixRequests.add(request.commandId)
            snapshot = fixRequestListeners.toList()
        }
        snapshot.forEach { listener ->
            runCatching { listener(request) }
        }
        return true
    }

    /**
     * v2: deliver a FIX_RESULT. Returns false (dropped) when there
     * is no outstanding FIX_REQUEST for the command (a late or
     * unsolicited fix answer must never restart verification).
     * Consumes the outstanding request.
     */
    fun completeFix(fixResult: ExecutorFixResult): Boolean {
        val snapshot: List<(ExecutorFixResult) -> Unit>
        synchronized(this) {
            if (fixResult.validate().isNotEmpty()) return false
            if (!outstandingFixRequests.remove(fixResult.commandId)) return false
            snapshot = fixListeners.toList()
        }
        snapshot.forEach { listener ->
            runCatching { listener(fixResult) }
        }
        return true
    }

    /** v2: true when a FIX_REQUEST is outstanding for this command. */
    @Synchronized
    fun hasOutstandingFix(commandId: String): Boolean = outstandingFixRequests.contains(commandId)

    @Synchronized
    fun pendingCommands(): List<ExecutorCommand> = pending.values.toList()

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun isPending(commandId: String): Boolean = pending.containsKey(commandId)

    @Synchronized
    fun pendingCommand(commandId: String): ExecutorCommand? = pending[commandId]

    @Synchronized
    fun lastCompleted(): ExecutorResult? = completedHistory.lastOrNull()

    @Synchronized
    fun completedHistory(): List<ExecutorResult> = completedHistory.toList()
}

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
 */
class TalkerExecutorBus {

    enum class SubmitResult { ACCEPTED, INVALID, DUPLICATE_ID }

    /** Bounded completed history — the Talker's "what just finished" view. */
    private val completedHistory = ArrayDeque<ExecutorResult>()
    private val pending = linkedMapOf<String, ExecutorCommand>()
    private val listeners = mutableListOf<(ExecutorResult) -> Unit>()

    companion object {
        const val MAX_COMPLETED_HISTORY = 20
    }

    fun addListener(listener: (ExecutorResult) -> Unit) {
        synchronized(this) { listeners.add(listener) }
    }

    fun removeListener(listener: (ExecutorResult) -> Unit) {
        synchronized(this) { listeners.remove(listener) }
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
            snapshot = listeners.toList()
        }
        snapshot.forEach { listener ->
            runCatching { listener(delivered) }
        }
        return true
    }

    /** Remove a pending command; returns it, or null when not pending. */
    @Synchronized
    fun cancel(commandId: String): ExecutorCommand? = pending.remove(commandId)

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

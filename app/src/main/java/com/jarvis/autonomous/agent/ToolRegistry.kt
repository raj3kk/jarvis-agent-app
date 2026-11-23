package com.jarvis.autonomous.agent

/**
 * R2.7 (1.17.0-PRO) — ToolRegistry: the Executor's tool table.
 *
 * Today the model does NOT pick tools (the planner emits step kinds
 * and the executor dispatches them). The registry is the single place
 * that names every tool the Executor can run, what it does, what args
 * it takes (schema = arg KEYS only, never values — values can be PII
 * and never go in a trajectory or a log), and which safety gate it
 * sits behind. AgentPlanExecutor.executeStep resolves through here
 * first: an unknown kind fails with the T-NO-HANDLER marker (the
 * self-extension detector keys on it) instead of silently doing
 * nothing.
 *
 * Trajectory (mini-swe shape): after every step, runPlan appends one
 * line to the plan's persisted trajectory ([PlanTrajectoryEntry] in
 * data/AgentPlanStore.kt) — tool name + outcome + verdict, no args,
 * no content. The LOOP path already carries the same shape in
 * runAutonomousLoop's previousActions (fed to the planner's next
 * decideNextStep call).
 */
object ToolRegistry {

    enum class GateLevel {
        /** No gate — read-only or harmless local work. */
        SAFE,
        /** Ask the user once (R1 no-step: PARK, never mid-task). */
        CONFIRM,
        /** One of the 5 hard gates: otp / login / payment / biometric /
         *  destructive_confirm. These stop the loop by design. */
        SENSITIVE
    }

    data class ToolEntry(
        val name: String,
        val description: String,
        /** Arg KEYS only (schema), never values. */
        val args: List<String>,
        val gate: GateLevel
    )

    private val TOOLS = listOf(
        ToolEntry(
            name = "BUILD",
            description = "In-app code builder: project file likho / badlo, preview server chalao.",
            args = listOf("project", "path", "content"),
            gate = GateLevel.CONFIRM
        ),
        ToolEntry(
            name = "GITHUB_PUSH",
            description = "Hub-proxy se GitHub par push karo (phone par kabhi token nahi).",
            args = listOf("repo", "branch", "files", "message"),
            gate = GateLevel.CONFIRM
        ),
        ToolEntry(
            name = "SUPABASE_TABLES",
            description = "Supabase project me tables banao (user ka apna project).",
            args = listOf("project_ref", "tables"),
            gate = GateLevel.SENSITIVE
        ),
        ToolEntry(
            name = "VERCEL_DEPLOY",
            description = "Vercel par deploy karo (user ka apna project).",
            args = listOf("project", "branch"),
            gate = GateLevel.CONFIRM
        ),
        ToolEntry(
            name = "HTTP_CHECK",
            description = "Kisi URL ko kholo aur status/content check karo.",
            args = listOf("url", "expect"),
            gate = GateLevel.SAFE
        ),
        ToolEntry(
            name = "GMAIL_SEND",
            description = "Gmail se email bhejo (hub OAuth, phone par token nahi).",
            args = listOf("to", "subject", "body"),
            gate = GateLevel.SENSITIVE
        ),
        ToolEntry(
            name = "GMAIL_READ",
            description = "Gmail inbox padho (hub OAuth, read-only).",
            args = listOf("query", "limit"),
            gate = GateLevel.SAFE
        ),
        ToolEntry(
            name = "DRIVE_READ",
            description = "Google Drive se file padho (hub OAuth, read-only).",
            args = listOf("file_id", "name"),
            gate = GateLevel.SAFE
        ),
        ToolEntry(
            name = "BROWSER_JOB",
            description = "WebView browser job: page kholo, tap/type karo, data nikalo (R0 JobEngine).",
            args = listOf("url", "steps"),
            gate = GateLevel.CONFIRM
        )
    )

    fun resolve(kind: String): ToolEntry? = TOOLS.firstOrNull { it.name == kind }

    fun all(): List<ToolEntry> = TOOLS

    /** One line per tool, for planner prompts (descriptions only). */
    fun describeForPlanner(): String =
        TOOLS.joinToString("\n") { "- ${it.name}: ${it.description}" }
}

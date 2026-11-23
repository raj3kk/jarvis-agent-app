package com.jarvis.autonomous.browser

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 1.15.0-PRO (redesign R0, browser requirement (c) refined 2026-11-23
 * ~13:00 IST) — per-step persistence for browser jobs.
 *
 * The problem: a browser job runs many steps (goto → click → type →
 * extract …) and the Vivo kills background work. Before this store, a
 * kill mid-job lost EVERYTHING — the plan resume re-ran the whole job
 * from step 0, re-clicking and re-typing on a fresh page.
 *
 * The fix: after EVERY completed step, the job's progress is written
 * to disk (atomic tmp+rename, best-effort, never throws): which steps
 * are done, the extracted values so far, and the last page URL. When
 * the foreground service + WorkManager backstop resume the plan after
 * a kill, the engine reloads this file and continues from the next
 * unfinished step — the user reopens the app and sees the result
 * (plan evidence) instead of a restart.
 *
 * Resume rule (pure, unit-tested — [resumeStartIndex]): replay from
 * the LAST completed `goto` (inclusive). A fresh WebView after a kill
 * has no page, so the last page load must re-run; steps before it
 * stay done (their extracts are already saved). Steps after the last
 * goto replay because their page state (typed text, clicked state)
 * died with the old WebView. A job whose steps are ALL complete
 * returns immediately with the saved result (killed between the last
 * step and the executor recording Done).
 *
 * Files live in filesDir/browser-jobs/<jobId>.json (jobId = the plan
 * step id, stable across resumes). Cleared on success; KEPT on
 * failure so partial extracts stay visible and resumable. Stale files
 * (>7 days) are deleted opportunistically on save.
 */
object JarvisBrowserJobStore {
    private const val TAG = "BrowserJobStore"
    private const val DIR_NAME = "browser-jobs"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    data class JobProgress(
        val jobId: String,
        val totalSteps: Int,
        val completed: Set<Int>,
        val extracted: JSONObject,
        val finalUrl: String,
        val updatedAtMs: Long
    )

    fun sanitizeJobId(jobId: String): String =
        jobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(64).ifBlank { "job" }

    fun progressFile(ctx: Context, jobId: String): File =
        File(File(ctx.filesDir, DIR_NAME), sanitizeJobId(jobId) + ".json")

    /** Atomic write (tmp + rename); never throws — persistence must never fail a job. */
    fun save(
        ctx: Context,
        jobId: String,
        totalSteps: Int,
        completed: Set<Int>,
        extracted: JSONObject,
        finalUrl: String
    ) {
        runCatching {
            val dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
            val obj = JSONObject().apply {
                put("jobId", jobId)
                put("totalSteps", totalSteps)
                put("completed", JSONArray(completed.sorted()))
                put("extracted", extracted)
                put("finalUrl", finalUrl)
                put("updatedAtMs", System.currentTimeMillis())
            }
            val target = File(dir, sanitizeJobId(jobId) + ".json")
            val tmp = File(dir, sanitizeJobId(jobId) + ".tmp")
            tmp.writeText(obj.toString())
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
            cleanupStale(ctx)
        }.onFailure { e -> Log.w(TAG, "progress save failed: ${e.message}") }
    }

    /** Null = no progress, corrupt file, or unreadable — the job then runs fresh. */
    fun load(ctx: Context, jobId: String): JobProgress? {
        return runCatching {
            val file = progressFile(ctx, jobId)
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val completed = mutableSetOf<Int>()
            val arr = obj.optJSONArray("completed")
            if (arr != null) for (i in 0 until arr.length()) completed.add(arr.optInt(i))
            JobProgress(
                jobId = obj.optString("jobId", jobId),
                totalSteps = obj.optInt("totalSteps", 0),
                completed = completed,
                extracted = obj.optJSONObject("extracted") ?: JSONObject(),
                finalUrl = obj.optString("finalUrl", ""),
                updatedAtMs = obj.optLong("updatedAtMs", 0L)
            )
        }.onFailure { e -> Log.w(TAG, "progress load failed: ${e.message}") }
            .getOrNull()
    }

    fun clear(ctx: Context, jobId: String) {
        runCatching { progressFile(ctx, jobId).delete() }
    }

    private fun cleanupStale(ctx: Context) {
        runCatching {
            val dir = File(ctx.filesDir, DIR_NAME)
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { f ->
                if (f.isFile && now - f.lastModified() > MAX_AGE_MS) f.delete()
            }
        }
    }

    /**
     * First step index to EXECUTE given the saved completed set.
     * Pure — no Android calls. `steps.length()` = nothing left to do
     * (all steps complete; the caller returns the saved result).
     */
    fun resumeStartIndex(steps: JSONArray, completed: Set<Int>): Int {
        if (steps.length() == 0) return 0
        var firstTodo = 0
        while (firstTodo < steps.length() && firstTodo in completed) firstTodo++
        if (firstTodo >= steps.length()) return steps.length()
        var lastGoto = -1
        for (i in 0 until firstTodo) {
            if (steps.optJSONObject(i)?.optString("action") == "goto") lastGoto = i
        }
        return if (lastGoto >= 0) lastGoto else firstTodo
    }
}

package com.jarvis.autonomous.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jarvis.autonomous.data.AgentPlanStore
import com.jarvis.autonomous.data.JarvisStateRepository
import com.jarvis.autonomous.data.PlanState
import java.util.concurrent.TimeUnit

/**
 * 1.15.0-PRO (redesign R0, parity spec §5 #10) — WorkManager backstop for
 * browser + executor background survival.
 *
 * The problem (spec §3.2 cause 3): the headless WebView and the Executor
 * live on the foreground service; the Vivo kills background services,
 * and there was NO WorkManager anywhere in the tree ("no WorkManager"
 * is written in AgentPlanExecutor.kt:36 and JarvisAgentEngine.kt:1782).
 * A long browser job or a mid-plan kill therefore died silently until
 * the user reopened the app.
 *
 * The backstop: a periodic worker (15 min — the WorkManager floor) that
 * checks for an unfinished persisted plan and, only then, re-starts the
 * foreground service. The service's onCreate auto-resumes the plan after
 * verify (existing path — the worker never resumes a plan itself, never
 * re-runs a step, never invents work). When nothing is pending the
 * worker is a cheap no-op that returns success.
 *
 * Honest limits (do not claim otherwise):
 * - WorkManager's minimum interval is 15 min — the backstop re-arms
 *   within ~15 min of a kill, not instantly.
 * - On Android 12+, starting a foreground service from the background
 *   needs the battery exemption (the app already prompts for it, A-M8).
 *   Without the exemption the OS denies the start; the worker records
 *   a BACKSTOP_DENIED diagnostic and the next tick retries. The phone
 *   test precondition is: battery exemption allowed once.
 * - 1.23.0: NO setForeground() here, ever. The worker is a PLAIN
 *   periodic worker (NOT expedited), so foreground priority is not
 *   required — and on WorkManager 2.9.0 it CRASHES the app on launch:
 *   setForeground() starts WorkManager's SystemForegroundService,
 *   which declares NO foregroundServiceType in the manifest, while
 *   the ForegroundInfo carried SPECIAL_USE (0x40000000) — the system
 *   rejects it (IllegalArgumentException, "not a subset", main
 *   thread) and runCatching around the setForegroundAsync call
 *   cannot catch it because the promotion happens asynchronously
 *   inside the service. Removing the call fixes the launch-bounce
 *   crash loop (1.21.0/1.22.0); the backstop check + FGS restart
 *   below are unchanged.
 */
class JarvisBackstopWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    private val TAG = "JarvisBackstop"

    companion object {
        const val UNIQUE_NAME = "jarvis-backstop"
    }

    override suspend fun doWork(): Result {
        return try {
            // 1.23.0: NO setForeground() — see the class docstring.
            // This is a plain periodic worker; it runs in the
            // background without foreground priority.
            val pending = hasPendingPlan()
            if (!pending) return Result.success()
            Log.i(TAG, "pending plan found — re-starting foreground service")
            JarvisStateRepository.addDiagnostic(
                "backstop", "BACKSTOP_FIRED",
                "Pending plan mila — foreground service dobara start ho rahi hai."
            )
            try {
                val intent = Intent(applicationContext, JarvisForegroundService::class.java)
                ContextCompat.startForegroundService(applicationContext, intent)
            } catch (e: Exception) {
                // Background FGS start denied (no battery exemption, OEM
                // restriction, or rate limit) — record it honestly and
                // let the next 15-min tick retry. Never crash the worker.
                Log.w(TAG, "backstop FGS start denied: ${e.message}")
                JarvisStateRepository.addDiagnostic(
                    "backstop", "BACKSTOP_DENIED",
                    "Foreground service start deny hua (${e.javaClass.simpleName}) — " +
                        "battery exemption allow karo, warna backstop kaam nahi karega."
                )
            }
            Result.success()
        } catch (e: Exception) {
            // A backstop must never crash-loop: the next tick retries.
            Log.w(TAG, "backstop error: ${e.message}")
            Result.success()
        }
    }

    /** True when a persisted plan exists with unfinished steps (same rule as resumePersistedPlan). */
    private fun hasPendingPlan(): Boolean {
        val plan = runCatching { AgentPlanStore.load(applicationContext) }.getOrNull()
            ?: return false
        if (plan.completed) return false
        return plan.steps.any {
            it.state == PlanState.PENDING || it.state == PlanState.RUNNING ||
                it.state == PlanState.BLOCKED || it.state == PlanState.AWAITING_USER
        }
    }
}

/** Scheduler for the backstop — enqueue once (KEEP), cancel never (except logout/uninstall paths, none today). */
object JarvisBackstop {
    fun enqueue(context: Context) {
        runCatching {
            val request = PeriodicWorkRequestBuilder<JarvisBackstopWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    JarvisBackstopWorker.UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }.onFailure { e ->
            Log.w("JarvisBackstop", "enqueue failed: ${e.message}")
        }
    }
}

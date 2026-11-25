package com.jarvis.autonomous.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.TelecomManager
import com.jarvis.autonomous.service.JarvisAccessibilityService
import com.jarvis.autonomous.shizuku.ShizukuManager
import java.io.File
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 1.26.0 (power features) — CallPower: the M0 probe (§5.5), the
 * Telecom companion setup (§1b/§5.2), dialling, WhatsApp control
 * (§5.2 M3), Jarvis's own call log, and the shared call state.
 *
 * SAFETY (§6 — enforced here, not just documented):
 * 1. Generic-only announce stays — this module NEVER reads caller
 *    identity. Incoming calls are identified by their RINGING state
 *    only; `Call.details.handle` is never read, and the system call
 *    log / contacts are never queried (not even through the shell —
 *    `content query content://call_log/calls` is explicitly excluded).
 * 2. No call without the user confirming the number — [dial] refuses
 *    empty numbers and emergency numbers.
 * 3. No auto-answer — the companion never answers on its own; the
 *    user taps Answer in the Calls tab, and only when the "Jarvis
 *    talks" toggle is ON (which itself unlocks only after the M0
 *    audio probe passes).
 * 4. No call recording without explicit per-call consent — default
 *    is listen-live, do not store. This module stores no audio.
 * 5. No other app's private data. 6. No silent destructive action.
 * 7. No exfiltration — files and call audio stay on the phone.
 *
 * The call-audio ENGINE (M2b — CallAudioService / CallAudioEngine)
 * is NOT in this release: it is built only if the M0 audio probe
 * (P2 + P3) passes, and P10 (the echo test) needs the engine to run
 * at all — so P10 is reported PENDING here, honestly, with the
 * reason in its detail line.
 */
object CallPower {

    const val JARVIS_PACKAGE = "com.jarvis.autonomous"

    // ---- Shared call state (companion service <-> Calls tab) ----

    enum class CallStatus { IDLE, DIALING, RINGING, ACTIVE, ENDED }
    enum class CallDirection { OUTGOING, INCOMING }
    enum class CallAudioMode { NONE, SPEAKERPHONE, CLEAN_TAP }

    data class JarvisCallState(
        val status: CallStatus = CallStatus.IDLE,
        val direction: CallDirection? = null,
        /**
         * OUTGOING: the number the user typed (user-entered, so it
         * may be shown). INCOMING: always the constant "Incoming
         * call" — the caller number is never read or shown.
         */
        val label: String = "",
        val audioMode: CallAudioMode = CallAudioMode.NONE,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val startedAtMs: Long = 0L
    )

    // ---- M0 probe (§5.5) ----

    enum class ProbeStatus { PASS, FAIL, UNKNOWN, PENDING }

    data class ProbeResult(
        val id: String,
        val title: String,
        val status: ProbeStatus,
        val detail: String,
        val raw: String
    )

    fun probeResultsToJson(results: List<ProbeResult>): String {
        val arr = JSONArray()
        for (r in results) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("status", r.status.name)
                put("detail", r.detail)
                put("raw", r.raw.take(2000))
            })
        }
        return arr.toString()
    }

    fun probeResultsFromJson(json: String): List<ProbeResult> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ProbeResult(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    status = runCatching { ProbeStatus.valueOf(o.optString("status")) }
                        .getOrDefault(ProbeStatus.UNKNOWN),
                    detail = o.optString("detail"),
                    raw = o.optString("raw")
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun pass(id: String, title: String, detail: String, raw: String) =
        ProbeResult(id, title, ProbeStatus.PASS, detail, raw.take(2000))

    private fun fail(id: String, title: String, detail: String, raw: String) =
        ProbeResult(id, title, ProbeStatus.FAIL, detail, raw.take(2000))

    private fun unknown(id: String, title: String, detail: String, raw: String) =
        ProbeResult(id, title, ProbeStatus.UNKNOWN, detail, raw.take(2000))

    /**
     * Runs P1–P9 through the 1.25.0 bridge; P10 is PENDING (it IS
     * the audio-engine test — it cannot run before the engine
     * exists). Each probe is independent: one failing never aborts
     * the rest. Results are published to [JarvisStateRepository] and
     * persisted by the caller (PhoneHealthCard) so the Calls tab can
     * read them without re-running.
     */
    suspend fun runProbes(): List<ProbeResult> {
        val results = ArrayList<ProbeResult>()

        // P1 — shell identity.
        val p1 = ShizukuManager.exec("id", 15_000)
        results += p1.fold(
            onSuccess = { out ->
                if (out.contains("uid=2000") || out.contains("uid=0")) {
                    pass("P1", "Shell identity", "Shell UID mil gaya (${out.trim().take(60)})", out)
                } else {
                    fail("P1", "Shell identity", "Unexpected UID: ${out.trim().take(120)}", out)
                }
            },
            onFailure = { e ->
                unknown("P1", "Shell identity", "Shizuku se 'id' nahi chala: ${e.message?.take(120)}", "")
            }
        )

        // P2 — audio-policy telephony routes (the M0 gate).
        val p2 = ShizukuManager.exec(
            "dumpsys media.audio_policy | grep -iE 'telephony|in-call|incall'",
            30_000
        )
        results += p2.fold(
            onSuccess = { out ->
                val lower = out.lowercase()
                val hasRx = lower.contains("telephony-rx")
                val hasCapture = lower.contains("in-call-capture")
                val hasPlayback = lower.contains("in-call-playback")
                val hasTx = lower.contains("telephony-tx")
                if (hasRx && hasCapture && hasPlayback && hasTx) {
                    pass("P2", "Audio-policy telephony routes", "telephony-rx → in-call-capture aur in-call-playback → telephony-tx — dono routes mile", out)
                } else {
                    fail(
                        "P2", "Audio-policy telephony routes",
                        "Routes poori nahi mili (rx=$hasRx capture=$hasCapture playback=$hasPlayback tx=$hasTx) — is phone par clean call-audio tap nahi banega",
                        out
                    )
                }
            },
            onFailure = { e ->
                unknown("P2", "Audio-policy telephony routes", "dumpsys nahi chala: ${e.message?.take(120)}", "")
            }
        )

        // P3 — isPstnCallAudioInterceptable() from the shell service.
        val p3 = ShizukuManager.isCallAudioInterceptable()
        results += p3.fold(
            onSuccess = { r ->
                when {
                    r.supported && r.interceptable ->
                        pass("P3", "Call-audio interceptable?", "isPstnCallAudioInterceptable() = true — system kehta hai tap possible hai", "")
                    r.supported && !r.interceptable ->
                        fail("P3", "Call-audio interceptable?", "isPstnCallAudioInterceptable() = false — system ne tap mana kiya", "")
                    else ->
                        unknown("P3", "Call-audio interceptable?", "Hidden API nahi chali: ${r.error.take(200)}", r.error)
                }
            },
            onFailure = { e ->
                unknown("P3", "Call-audio interceptable?", "Probe call fail hui: ${e.message?.take(120)}", "")
            }
        )

        // P4 — /Android/data readable.
        val p4 = ShizukuManager.exec("ls /sdcard/Android/data | head -5", 15_000)
        results += p4.fold(
            onSuccess = { out ->
                if (out.isNotBlank()) {
                    pass("P4", "/Android/data readable", "App folders dikh rahe hain (${out.lines().filter { it.isNotBlank() }.size} entries)", out)
                } else {
                    fail("P4", "/Android/data readable", "Khaali aaya — /Android/data khulta hai lekin koi folder nahi dikha", out)
                }
            },
            onFailure = { e ->
                fail("P4", "/Android/data readable", "ls fail hua: ${e.message?.take(150)}", "")
            }
        )

        // P5 — /Android/obb readable (exit 0 is the pass — it may be empty).
        val p5 = ShizukuManager.exec("ls /sdcard/Android/obb | head -5", 15_000)
        results += p5.fold(
            onSuccess = { out ->
                pass("P5", "/Android/obb readable", "ls chala (exit 0)${if (out.isBlank()) " — folder khaali hai" else ""}", out)
            },
            onFailure = { e ->
                fail("P5", "/Android/obb readable", "ls fail hua: ${e.message?.take(150)}", "")
            }
        )

        // P6 — /data/data blocked (EXPECTED — a PASS here is the
        // block behaving as documented). If it OPENS, that is
        // unexpected access and the verdict table must be updated.
        val p6 = ShizukuManager.exec("ls /data/data", 15_000)
        results += p6.fold(
            onSuccess = { out ->
                fail(
                    "P6", "/data/data blocked (expected)",
                    "EXPECTED 'Permission denied' tha, lekin folder KHUL GAYA (${out.lines().filter { it.isNotBlank() }.size} entries) — ye expected nahi tha; verdict table update karni hogi",
                    out
                )
            },
            onFailure = { e ->
                val msg = e.message.orEmpty()
                if (msg.contains("Permission denied", ignoreCase = true)) {
                    pass("P6", "/data/data blocked (expected)", "Permission denied — jaise document tha, dusre apps ka private data shell se bhi nahi khulta", msg)
                } else {
                    unknown("P6", "/data/data blocked (expected)", "ls fail hua, lekin 'Permission denied' nahi aaya: ${msg.take(150)}", msg)
                }
            }
        )

        // P7 — /data/app readable (APK backup source).
        val p7 = ShizukuManager.exec("ls /data/app | head -5", 15_000)
        results += p7.fold(
            onSuccess = { out ->
                if (out.isNotBlank()) {
                    pass("P7", "/data/app readable (APK backup)", "APK folders dikh rahe hain — backup possible hai", out)
                } else {
                    fail("P7", "/data/app readable (APK backup)", "Khaali aaya", out)
                }
            },
            onFailure = { e ->
                fail("P7", "/data/app readable (APK backup)", "ls fail hua: ${e.message?.take(150)}", "")
            }
        )

        // P8 — companion override works.
        val p8 = applyCompanionOverride()
        val p8Ok = p8.all { it.ok }
        results += if (p8Ok) {
            pass("P8", "Call companion override", "appops + telecom override lage, dumpsys me confirm hua", p8.joinToString("; ") { it.detail })
        } else {
            val failed = p8.firstOrNull { !it.ok }
            unknown("P8", "Call companion override", failed?.detail ?: "Override verify nahi hua", p8.joinToString("; ") { it.detail })
        }

        // P9 — uiautomator + screencap + screenrecord + input present.
        val p9 = ShizukuManager.exec("which uiautomator screencap screenrecord input", 15_000)
        results += p9.fold(
            onSuccess = { out ->
                val found = out.lines().filter { it.isNotBlank() }.size
                if (found >= 4) {
                    pass("P9", "Automation tools present", "uiautomator + screencap + screenrecord + input — charon mile", out)
                } else {
                    fail("P9", "Automation tools present", "Sirf $found/4 mile: ${out.trim().take(150)}", out)
                }
            },
            onFailure = { e ->
                fail("P9", "Automation tools present", "'which' fail hua: ${e.message?.take(150)}", "")
            }
        )

        // P10 — the echo test. It CANNOT run in 1.26.0: it feeds the
        // downlink tap straight back into the uplink, and the tap
        // only exists once the M2b audio engine is built — which
        // itself waits for P2+P3. Reporting PENDING is honest; the
        // gate for 1.27.0 is P2 AND P3 passing.
        val p2Pass = results.firstOrNull { it.id == "P2" }?.status == ProbeStatus.PASS
        val p3Pass = results.firstOrNull { it.id == "P3" }?.status == ProbeStatus.PASS
        results += ProbeResult(
            id = "P10",
            title = "Echo test (decisive audio proof)",
            status = ProbeStatus.PENDING,
            detail = if (p2Pass && p3Pass) {
                "P2+P3 PASS — audio engine (1.27.0) banega, phir echo test chalega (test call par downlink ko uplink me 10s feed karke caller se confirm karwana)"
            } else {
                "Audio engine ke liye P2+P3 dono PASS chahiye — abhi ${if (!p2Pass) "P2" else ""}${if (!p2Pass && !p3Pass) "+" else ""}${if (!p3Pass) "P3" else ""} pass nahi hai, isliye echo test abhi nahi chal sakta"
            },
            raw = ""
        )

        JarvisStateRepository.setProbeResults(results)
        return results
    }

    /** True when the M0 audio gate passed (P2 AND P3 PASS). */
    fun audioGatePassed(results: List<ProbeResult>): Boolean {
        val byId = results.associateBy { it.id }
        return byId["P2"]?.status == ProbeStatus.PASS &&
            byId["P3"]?.status == ProbeStatus.PASS
    }

    // ---- Telecom companion setup (§1b/§5.2) ----

    data class CompanionStep(val title: String, val ok: Boolean, val detail: String)

    /** Checks whether the companion override is currently present. */
    suspend fun checkCompanionOverride(): CompanionStep {
        val r = ShizukuManager.exec("dumpsys telecom | grep -i companion", 20_000)
        return r.fold(
            onSuccess = { out ->
                if (out.contains(JARVIS_PACKAGE)) {
                    CompanionStep("Companion override", true, "dumpsys telecom me $JARVIS_PACKAGE companion dikh raha hai")
                } else {
                    CompanionStep("Companion override", false, "dumpsys telecom me companion override nahi mila (reboot / Telecom restart par khatm ho jata hai)")
                }
            },
            onFailure = { e ->
                CompanionStep("Companion override", false, "dumpsys nahi chala: ${e.message?.take(120)}")
            }
        )
    }

    /**
     * Applies the companion setup: (1) appops MANAGE_ONGOING_CALLS,
     * (2) the test-only Telecom override, (3) verify by dumpsys.
     * Each step is verified by read-back, never assumed.
     */
    suspend fun applyCompanionOverride(): List<CompanionStep> {
        val steps = ArrayList<CompanionStep>()
        val appops = ShizukuManager.exec(
            "cmd appops set $JARVIS_PACKAGE MANAGE_ONGOING_CALLS allow", 15_000
        )
        steps += appops.fold(
            onSuccess = { CompanionStep("Appop MANAGE_ONGOING_CALLS", true, "allow lag gaya") },
            onFailure = { e -> CompanionStep("Appop MANAGE_ONGOING_CALLS", false, "fail: ${e.message?.take(150)}") }
        )
        val add = ShizukuManager.exec(
            "cmd telecom add-or-remove-call-companion-app $JARVIS_PACKAGE 1", 15_000
        )
        steps += add.fold(
            onSuccess = { CompanionStep("Telecom companion override", true, "add command chala (exit 0)") },
            onFailure = { e -> CompanionStep("Telecom companion override", false, "fail: ${e.message?.take(150)}") }
        )
        steps += checkCompanionOverride()
        return steps
    }

    /**
     * Cold-start re-apply (called from JarvisApplication.onCreate):
     * waits up to 10 s for Shizuku Ready, then re-applies the
     * override. Best-effort and silent — never blocks app start.
     */
    suspend fun reapplyCompanionOnStart() {
        // Wait up to 10 s for Shizuku Ready, then re-apply once if
        // the override is gone. Returns early as soon as it is Ready.
        repeat(20) {
            if (ShizukuManager.state.value is ShizukuManager.ShizukuState.Ready) {
                runCatching {
                    val check = checkCompanionOverride()
                    if (!check.ok) applyCompanionOverride()
                }
                return
            }
            delay(500)
        }
    }

    // ---- Dialling (§6 rule 2) ----

    /** Emergency short codes — never dialled by Jarvis. */
    private val EMERGENCY_CODES = setOf("112", "100", "101", "102", "108", "911")

    fun normalizeNumber(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }

    fun isEmergencyNumber(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        if (digits in EMERGENCY_CODES) return true
        if (digits.length < 3) return true
        return false
    }

    /** The number the user just dialled from the Calls tab (60 s window). */
    @Volatile
    private var lastOutgoingDial: Pair<String, Long>? = null

    fun noteOutgoingDial(number: String) {
        lastOutgoingDial = number to System.currentTimeMillis()
    }

    /** Returns the dialled number if a Jarvis dial happened < 60 s ago. */
    fun recentOutgoingDial(): String? {
        val (number, at) = lastOutgoingDial ?: return null
        return if (System.currentTimeMillis() - at < 60_000) number else null
    }

    /**
     * Dials [rawNumber] via TelecomManager.placeCall. The number
     * must come from the user (Calls tab field or a confirmed plan
     * step) — this function never dials an unresolved name, and
     * never dials an emergency number.
     */
    fun dial(context: Context, rawNumber: String): Result<Unit> = runCatching {
        val number = normalizeNumber(rawNumber)
        require(number.isNotBlank()) { "Number khaali hai — pehle number dalo." }
        require(!isEmergencyNumber(number)) { "Emergency number ($number) Jarvis kabhi nahi dial karta." }
        val telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        noteOutgoingDial(number)
        telecom.placeCall(Uri.parse("tel:$number"), null)
    }

    // ---- WhatsApp control (§5.2 M3) ----

    /**
     * WhatsApp call by UI automation: opens the chat via the wa.me
     * link, dumps the UI, finds the voice-call button by its label
     * and taps it. WhatsApp has no API — this is screen-tapping, so
     * it is brittle if WhatsApp changes its UI, and the voice is
     * speakerphone-only (acoustic, degraded — the UI says so).
     * Returns a human-readable result; never throws.
     */
    suspend fun whatsappCall(context: Context, rawNumber: String): Result<String> {
        val number = normalizeNumber(rawNumber)
        if (number.isBlank()) return Result.failure(IllegalArgumentException("Number khaali hai."))
        if (isEmergencyNumber(number)) {
            return Result.failure(IllegalArgumentException("Emergency number — WhatsApp call nahi karunga."))
        }
        val digits = number.trimStart('+')
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isFailure) {
            return Result.failure(IllegalStateException("WhatsApp khol nahi paya — WhatsApp installed hai?"))
        }
        delay(2500)
        val dump = ShizukuManager.exec(ShellAutomation.dumpUiCommand(), 20_000)
        val xml = dump.fold(
            onSuccess = { ShizukuManager.exec(ShellAutomation.readUiDumpCommand(), 15_000).getOrElse { "" } },
            onFailure = { "" }
        )
        if (xml.isBlank()) {
            return Result.failure(IllegalStateException("WhatsApp khula, lekin screen dump nahi mila — call button haath se dabao."))
        }
        val nodes = ShellAutomation.parseUiDump(xml)
        val button = ShellAutomation.findNodeByText(nodes, "Voice call")
            ?: ShellAutomation.findNodeByText(nodes, "Call")
        if (button == null || (button.centerX == 0 && button.centerY == 0)) {
            return Result.failure(IllegalStateException("WhatsApp chat khula, lekin call button nahi mila — UI badal gaya hoga; haath se dabao."))
        }
        val tap = ShizukuManager.exec(
            ShellAutomation.tapCommand(button.centerX, button.centerY), 15_000
        )
        return tap.fold(
            onSuccess = {
                noteOutgoingDial(number)
                Result.success("WhatsApp call button dabaya — awaaz speaker se jayegi (saaf nahi hogi, acoustic path hai).")
            },
            onFailure = { e ->
                // Fallback: Accessibility tap (works even if Shizuku died mid-run).
                val acc = JarvisAccessibilityService.instance
                if (acc != null && runCatching {
                        acc.tapCoordinates(button.centerX, button.centerY)
                    }.getOrDefault(false)) {
                    noteOutgoingDial(number)
                    Result.success("WhatsApp call button dabaya (Accessibility se) — awaaz speaker se jayegi.")
                } else {
                    Result.failure(IllegalStateException("Call button par tap nahi hua: ${e.message?.take(120)}"))
                }
            }
        )
    }

    // ---- Jarvis's own call log (never the system call log) ----

    data class CallLogEntry(
        val timeMs: Long,
        val direction: CallDirection,
        /** OUTGOING: user-entered number. INCOMING: "Incoming call". */
        val label: String,
        val mode: CallAudioMode,
        val durationSec: Long,
        val note: String = ""
    )

    private const val CALL_LOG_FILE = "call-log.json"
    private const val CALL_LOG_MAX = 100

    fun readCallLog(context: Context): List<CallLogEntry> {
        return runCatching {
            val file = File(context.filesDir, CALL_LOG_FILE)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CallLogEntry(
                    timeMs = o.optLong("timeMs"),
                    direction = runCatching { CallDirection.valueOf(o.optString("direction")) }
                        .getOrDefault(CallDirection.INCOMING),
                    label = o.optString("label"),
                    mode = runCatching { CallAudioMode.valueOf(o.optString("mode")) }
                        .getOrDefault(CallAudioMode.NONE),
                    durationSec = o.optLong("durationSec"),
                    note = o.optString("note")
                )
            }.sortedByDescending { it.timeMs }
        }.getOrDefault(emptyList())
    }

    fun appendCallLog(context: Context, entry: CallLogEntry) {
        runCatching {
            val file = File(context.filesDir, CALL_LOG_FILE)
            val existing = readCallLog(context).take(CALL_LOG_MAX - 1)
            val arr = JSONArray()
            arr.put(JSONObject().apply {
                put("timeMs", entry.timeMs)
                put("direction", entry.direction.name)
                put("label", entry.label)
                put("mode", entry.mode.name)
                put("durationSec", entry.durationSec)
                put("note", entry.note)
            })
            for (e in existing) {
                arr.put(JSONObject().apply {
                    put("timeMs", e.timeMs)
                    put("direction", e.direction.name)
                    put("label", e.label)
                    put("mode", e.mode.name)
                    put("durationSec", e.durationSec)
                    put("note", e.note)
                })
            }
            file.writeText(arr.toString())
        }
    }
}

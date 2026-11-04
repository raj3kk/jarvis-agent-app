package com.jarvis.autonomous.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.4.12: Test Voice live stage display (AI Brain Swara card,
 * Test Voice button ke neeche). User order 2026-11-04: test ke
 * neeche dikhe Swara bolne me KAHAN atak raha hai — aur
 * awaaz KYUN nahi aayi, iska kaaran bhi (stage trail + stuck
 * point + kaaran, plain Hinglish me).
 *
 * Design (koi naya speak path nahi, koi polling nahi, koi
 * arbitrary delay nahi):
 * - Display EXISTING diagnostic markers se derive hota hai.
 *   JarvisStateRepository.addDiagnostic har tts marker ko
 *   yahan bhejta hai (onDiagnostic). 1.4.12: Swara main
 *   process me chalta hai, isliye uske stage markers seedha
 *   addDiagnostic se aate hain — koi relay nahi hai).
 * - Ek test run TEST_VOICE_START par khulta hai aur agle
 *   TEST_VOICE_START tak terminal result FREEZE rehta hai
 *   (dusre speaks — greeting/reply — display ko chhoote
 *   nahi; terminal ke baad markers ignore hote hain).
 * - Arming precision: SPK_START speakMutex se PEHLE likha
 *   jaata hai, isliye sirf SPK_START par arm karna galat
 *   hota (kisi chalti speak ke peeche queued test ka
 *   SPK_START pehle aa jaata, aur us chalti speak ka SPK_END
 *   test ko galat terminalize kar deta). Isliye arming sirf
 *   onSpeakStarted() se hoti hai, jo speakWrapped ke
 *   post-mutex block se aati hai (origin == "test" par) —
 *   matlab test speak ne ASAL me bolna shuru kar diya hai.
 *   In-speak markers + SPK_END terminalization sirf armed
 *   run par lagte hain.
 * - Swara failure case: socket fail hone par bhi display
 *   main-process ke outcome se update hota hai — SWARA_FAILED
 *   + SPK_END ok=false. Terminal hamesha main-process markers
 *   se aata hai, isliye har test ka terminal outcome display
 *   par aata hi hai (speak/queue timeout paths samet — sab
 *   SPK_END likhte hain).
 * - Display me kabhi raw marker code, kabhi spoken text,
 *   kabhi key/token nahi dikhta — sirf plain stage labels
 *   aur kaaran. Unknown terminal par generic-cause fallback
 *   hai (kabhi blank/code nahi).
 */
data class TestVoiceStageDisplay(
    val line1: String,
    val line2: String
)

object TestVoiceStageTracker {

    private val lock = Any()
    private val _stage = MutableStateFlow<TestVoiceStageDisplay?>(null)
    val stage: StateFlow<TestVoiceStageDisplay?> = _stage.asStateFlow()

    /** Ek kaaran: stuck label + line1 override + kaaran text. */
    private data class Cause(
        val stuckLabel: String,
        val causeText: String,
        val line1Override: String? = null
    )

    private class Run {
        val trail = mutableListOf<String>()
        var lastStageKey = "prep"
        var pendingCause: Cause? = null
        var fallbackUsed = false
        var fallbackReason = "latch"
        var armed = false
        var terminal = false
    }

    private var run: Run? = null

    private fun runActive(): Run? = run?.takeIf { !it.terminal }

    private fun addTrail(r: Run, label: String) {
        if (r.trail.lastOrNull() != label) r.trail.add(label)
    }

    private fun trailText(r: Run): String =
        if (r.trail.isEmpty()) "—" else r.trail.joinToString(" → ")

    private fun publishLive(r: Run, liveText: String) {
        _stage.value = TestVoiceStageDisplay(
            liveText,
            "Pahuncha: ${trailText(r)}"
        )
    }

    /** Main-process tts markers (addDiagnostic hook se). */
    fun onDiagnostic(area: String, code: String, detail: String) {
        if (area != "tts") return
        synchronized(lock) {
            when (code) {
                "TEST_VOICE_START" -> {
                    val r = Run()
                    r.trail.add("Shuru")
                    run = r
                    publishLive(r, "Test shuru ho raha hai…")
                }
                "TEST_VOICE_SENT" -> {
                    val r = runActive() ?: return
                    publishLive(r, "Service ko test bheja gaya…")
                }
                "TEST_VOICE_START_FAILED" -> {
                    // Tap service tak pahuncha hi nahi — iska
                    // koi SPK_END nahi aayega, isliye yahin
                    // terminalize karna zaroori hai (warna
                    // display hamesha "chal raha hai" par atka
                    // rehta).
                    val r = runActive() ?: Run().also {
                        it.trail.add("Shuru")
                        run = it
                    }
                    r.terminal = true
                    _stage.value = TestVoiceStageDisplay(
                        "Service start nahi hua",
                        "Kaaran: test service tak pahuncha hi nahi, " +
                            "isliye awaaz nahi aayi. " +
                            "Pahuncha: ${trailText(r)}"
                    )
                }
                "SPK_START" -> {
                    val r = runActive() ?: return
                    if (detail.contains("origin=test")) {
                        // Pre-mutex marker: sirf live text +
                        // trail; arming onSpeakStarted() se hogi.
                        addTrail(r, "Taiyaari")
                        r.lastStageKey = "prep"
                        publishLive(r, "Bolne ki taiyaari ho rahi hai…")
                    } else if (r.armed && detail.contains("Speak failed at start")) {
                        r.pendingCause = Cause(
                            "Bolne ki shuruaat",
                            "bolne ki shuruaat me hi fail ho gaya, " +
                                "isliye awaaz nahi aayi"
                        )
                    }
                }
                "SPK_END" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.terminal = true
                    val ok = detail.contains("ok=true")
                    if (ok) {
                        if (r.fallbackUsed) {
                            val reasonText = if (r.fallbackReason == "off") {
                                "Swara OFF thi, isliye system voice boli."
                            } else {
                                "Swara unavailable tha (latch), " +
                                    "isliye system voice boli."
                            }
                            _stage.value = TestVoiceStageDisplay(
                                "System voice se bol diya — poori awaaz chali",
                                "Kaaran: $reasonText " +
                                    "Pahuncha: ${trailText(r)}"
                            )
                        } else {
                            _stage.value = TestVoiceStageDisplay(
                                "Bol diya — poori awaaz chali",
                                "Pahuncha: ${trailText(r)}"
                            )
                        }
                    } else {
                        _stage.value = terminalFailure(r)
                    }
                }
                // Neeche wale sab in-speak markers hain: sirf
                // armed run par lagte hain (kisi chalti speak
                // ke markers test run ko chhoo nahi sakte).
                "TTS_SPEAK_QUEUED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Intezar")
                    publishLive(
                        r,
                        "Intezar ho raha hai — pehle wali awaaz " +
                            "khatam ho rahi hai…"
                    )
                }
                "TTS_QUEUE_FULL" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Bolne ki line",
                        "bolne ki line bhari thi, isliye ye test " +
                            "refuse ho gaya aur awaaz nahi aayi"
                    )
                }
                "TTS_QUEUE_TIMEOUT" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Bolne ki line",
                        "bolne ki line me intezar karte karte " +
                            "timeout ho gaya, isliye awaaz nahi aayi"
                    )
                }
                "TTS_BUSY" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Doosri awaaz",
                        "doosri awaaz chal rahi thi, isliye ye " +
                            "test refuse ho gaya aur awaaz nahi aayi"
                    )
                }
                "SWARA_LATCHED_REFUSED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Swara latch",
                        "Swara unavailable hai (lagatar fail ke " +
                            "baad latch), isliye Swara se awaaz " +
                            "nahi aayi",
                        line1Override = "Swara unavailable hai (latch)"
                    )
                }
                "SWARA_UNAVAILABLE_LATCHED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    publishLive(
                        r,
                        "Swara unavailable hai (latch) — system " +
                            "voice ki taiyaari ho rahi hai…"
                    )
                }
                "SWARA_CONNECT" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Swara connect")
                    r.lastStageKey = "connect"
                    publishLive(r, "Swara se jud raha hai…")
                }
                "SWARA_FIRST_AUDIO" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Pehli awaaz")
                    r.lastStageKey = "firstAudio"
                    publishLive(r, "Pehli awaaz aa gayi — baj rahi hai…")
                }
                "SWARA_STREAMING" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Streaming")
                    r.lastStageKey = "stream"
                    publishLive(r, "Streaming chal rahi hai…")
                }
                "SWARA_END" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Awaaz poori")
                    publishLive(r, "Awaaz poori ho gayi…")
                }
                "SWARA_FAILED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        swaraStuckLabel(r),
                        swaraCauseText(detail)
                    )
                }
                "TTS_FALLBACK_INIT" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    publishLive(r, "System voice taiyaar ho rahi hai…")
                }
                "TTS_FALLBACK_USED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.fallbackUsed = true
                    r.fallbackReason = if (detail.contains("Swara OFF")) "off" else "latch"
                    addTrail(r, "System voice")
                    publishLive(
                        r,
                        "Swara ke bajay system voice bol rahi hai…"
                    )
                }
                "TTS_FALLBACK_FAILED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "System voice",
                        "Swara unavailable tha (latch/OFF) aur system " +
                            "voice bhi nahi bol payi, isliye awaaz " +
                            "nahi aayi"
                    )
                }
                else -> {
                    // Baaki tts markers (SWARA_FAIL_COUNT,
                    // SWARA_LATCH_CLEARED/EXPIRED, GREET_*, ...)
                    // display ko nahi chhoote.
                }
            }
        }
    }

    /** speakWrapped ke post-mutex block se (origin == "test"). */
    fun onSpeakStarted() {
        synchronized(lock) {
            val r = runActive() ?: return
            r.armed = true
        }
    }

    /** Swara stuck-point label + cause text from the
     * SWARA_FAILED detail (detail carries only cause + char
     * count — never text, codes, or keys — so the display
     * maps the cause word to a plain Hinglish phrase). */
    private fun swaraStuckLabel(r: Run): String = when (r.lastStageKey) {
        "connect" -> "Swara se judne"
        "firstAudio" -> "Pehli awaaz"
        "stream" -> "Streaming"
        else -> "Taiyaari"
    }

    private fun swaraCauseText(detail: String): String = when {
        detail.contains("cause=timeout") ->
            "Swara se jawab nahi aaya (timeout), isliye awaaz nahi aayi"
        detail.contains("cause=connect") ->
            "Swara se jud nahi paya, isliye awaaz nahi aayi"
        detail.contains("cause=no-audio") ->
            "Swara se awaaz aayi hi nahi, isliye awaaz nahi aayi"
        detail.contains("cause=cancelled") ->
            "bolna beech me roka gaya, isliye awaaz poori nahi aayi"
        else ->
            "Swara se awaaz ban nahi payi, isliye awaaz nahi aayi"
    }


    private fun lastStageLabel(r: Run): String = when (r.lastStageKey) {
        "connect" -> "Swara se judna"
        "firstAudio" -> "Pehli awaaz"
        "stream" -> "Streaming"
        else -> "Taiyaari"
    }

    private fun terminalFailure(r: Run): TestVoiceStageDisplay {
        val cause = r.pendingCause
        val line1: String
        val causeText: String
        when {
            cause == null -> {
                val label = lastStageLabel(r)
                line1 = "$label par atak gaya"
                causeText = "kaaran marker me nahi mila — $label " +
                    "par ruk gaya, isliye awaaz nahi aayi"
            }
            cause.line1Override != null -> {
                line1 = cause.line1Override
                causeText = cause.causeText
            }
            else -> {
                line1 = "${cause.stuckLabel} par atak gaya"
                causeText = cause.causeText
            }
        }
        return TestVoiceStageDisplay(
            line1,
            "Kaaran: $causeText. Pahuncha: ${trailText(r)}"
        )
    }
}

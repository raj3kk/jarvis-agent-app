package com.jarvis.autonomous.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 1.4.11: Test Voice live stage display (AI Brain Kokoro card,
 * Test Voice button ke neeche). User order 2026-11-04: test ke
 * neeche dikhe Kokoro bolne me KAHAN atak raha hai — aur
 * 2026-11-04 scope addition: awaaz KYUN nahi aayi, iska kaaran
 * bhi (stage trail + stuck point + kaaran, plain Hinglish me).
 *
 * Design (koi naya speak path nahi, koi polling nahi, koi
 * arbitrary delay nahi):
 * - Display EXISTING diagnostic markers se derive hota hai.
 *   JarvisStateRepository.addDiagnostic(Sync) har tts marker
 *   ko yahan bhejta hai (onDiagnostic), aur :tts process ke
 *   stage markers MSG_PROGRESS relay se aate hain (onTtsStage,
 *   TtsProcessClient ke SPEAK progress callback se).
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
 * - :tts death case: mara hua process kuch push nahi karta,
 *   isliye display main-process ke outcome se update hota
 *   hai — binder-death marker (NATIVE_TTS_PROCESS_DIED) +
 *   SPK_END ok=false. Relay stages sirf live trail ke liye
 *   hain; terminal hamesha main-process markers se aata hai,
 *   isliye har test ka terminal outcome display par aata hi
 *   hai (speak/bind/queue timeout paths samet — sab SPK_END
 *   likhte hain).
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
                            _stage.value = TestVoiceStageDisplay(
                                "System voice se bol diya — poori awaaz chali",
                                "Kaaran: Kokoro unavailable tha (latch), " +
                                    "isliye system voice boli. " +
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
                "TTS_WAIT_SETUP" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    addTrail(r, "Setup ka intezar")
                    r.lastStageKey = "setup"
                    publishLive(r, "Setup ka intezar ho raha hai…")
                }
                "TTS_BIND_FAILED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Service se judne",
                        "speech service se jud nahi paya, " +
                            "isliye awaaz nahi aayi"
                    )
                    publishLive(r, "Service se jud nahi paya…")
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
                "TTS_PROCESS_TIMEOUT" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Service se jawab ka intezar",
                        "speech service se jawab nahi aaya " +
                            "(timeout), isliye awaaz nahi aayi"
                    )
                }
                "KOKORO_OFF" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Kokoro OFF",
                        "Kokoro OFF hai, isliye awaaz nahi aayi",
                        line1Override = "Kokoro OFF hai"
                    )
                }
                "KOKORO_LATCHED_REFUSED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "Kokoro latch",
                        "Kokoro unavailable hai (lagatar fail ke " +
                            "baad latch), isliye Kokoro se awaaz " +
                            "nahi aayi",
                        line1Override = "Kokoro unavailable hai (latch)"
                    )
                }
                "KOKORO_UNAVAILABLE_LATCHED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    publishLive(
                        r,
                        "Kokoro unavailable hai (latch) — system " +
                            "voice ki taiyaari ho rahi hai…"
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
                    addTrail(r, "System voice")
                    publishLive(
                        r,
                        "Kokoro ke bajay system voice bol rahi hai…"
                    )
                }
                "TTS_FALLBACK_FAILED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    r.pendingCause = Cause(
                        "System voice",
                        "Kokoro unavailable tha (latch) aur system " +
                            "voice bhi nahi bol payi, isliye awaaz " +
                            "nahi aayi"
                    )
                }
                "NATIVE_TTS_PROCESS_DIED" -> {
                    val r = runActive() ?: return
                    if (!r.armed) return
                    // Death ka stuck point + kaaran SPK_END par
                    // lastStageKey se banta hai (neeche
                    // terminalFailure me) — yahan sirf flag.
                    r.pendingCause = Cause("DEATH", "")
                }
                else -> {
                    // Baaki tts markers (FAIL_COUNT, LATCH_CLEARED,
                    // FG markers, GREET_*, ...) display ko nahi
                    // chhoote.
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

    /** :tts process se relay huye stage markers (MSG_PROGRESS). */
    fun onTtsStage(code: String) {
        synchronized(lock) {
            val r = runActive() ?: return
            if (!r.armed) return
            when (code) {
                "TTS_ENSURE_FAILED" -> {
                    r.pendingCause = Cause(
                        "Voice files taiyaar karne",
                        "voice files taiyaar nahi hui, isliye " +
                            "awaaz nahi aayi"
                    )
                }
                "SPK_ENGINE_START" -> {
                    addTrail(r, "Engine load")
                    r.lastStageKey = "engine"
                    publishLive(r, "Engine load ho raha hai…")
                }
                "ENGINE_INIT_FAIL" -> {
                    r.pendingCause = Cause(
                        "Engine load",
                        "voice engine load nahi hua, isliye " +
                            "awaaz nahi aayi"
                    )
                }
                "SPK_ENGINE_END" -> {
                    r.lastStageKey = "engineDone"
                    publishLive(r, "Awaaz banne ki taiyaari ho rahi hai…")
                }
                "SPK_GENERATE_START" -> {
                    addTrail(r, "Generate")
                    r.lastStageKey = "generate"
                    publishLive(r, "Generate ho raha hai…")
                }
                "SPK_GENERATE_END" -> {
                    r.lastStageKey = "playbackPrep"
                    publishLive(r, "Playback ki taiyaari ho rahi hai…")
                }
                "SPK_PLAY_START" -> {
                    addTrail(r, "Playback")
                    r.lastStageKey = "playback"
                    publishLive(r, "Playback chal raha hai…")
                }
                "EMPTY_AUDIO" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani hi nahi — khaali audio bana, " +
                            "isliye awaaz nahi aayi"
                    )
                }
                "PLAYBACK_ERROR" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par baj nahi payi — playback " +
                            "me error aaya, isliye awaaz nahi aayi"
                    )
                }
                "TTS_MINBUF_ERROR" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par baj nahi payi — audio " +
                            "buffer nahi bana, isliye awaaz nahi aayi"
                    )
                }
                "TTS_TRACK_UNINITIALIZED" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par baj nahi payi — audio " +
                            "track taiyaar nahi hua, isliye awaaz " +
                            "nahi aayi"
                    )
                }
                "TTS_PLAY_NOT_STARTED" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par baj nahi payi — playback " +
                            "shuru nahi hua, isliye awaaz nahi aayi"
                    )
                }
                "WRITE_FAIL" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par poori baj nahi payi — " +
                            "audio likhne me fail hua, isliye " +
                            "awaaz nahi aayi"
                    )
                }
                "TTS_DRAIN_TIMEOUT" -> {
                    r.pendingCause = Cause(
                        "Playback",
                        "awaaz bani par poori baj nahi payi — " +
                            "playback beech me atak gaya, isliye " +
                            "awaaz nahi aayi"
                    )
                }
                else -> {
                    // FIRST_GENERATE, FG markers, ENGINE_STATE,
                    // ... — stage display ke liye zaroori nahi.
                }
            }
        }
    }

    private fun lastStageLabel(r: Run): String = when (r.lastStageKey) {
        "engine" -> "Engine load"
        "engineDone" -> "Generate ki taiyaari"
        "generate" -> "Generate"
        "playbackPrep" -> "Playback ki taiyaari"
        "playback" -> "Playback"
        "setup" -> "Setup ka intezar"
        else -> "Taiyaari"
    }

    private fun deathStagePhrase(r: Run): String = when (r.lastStageKey) {
        "engine" -> "engine load"
        "engineDone" -> "generate ki taiyaari"
        "generate" -> "generate"
        "playbackPrep" -> "playback ki taiyaari"
        "playback" -> "playback"
        else -> "bolne"
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
            cause.stuckLabel == "DEATH" -> {
                line1 = "${lastStageLabel(r)} par atak gaya"
                causeText = "${deathStagePhrase(r)} ke beech " +
                    "speech process band ho gaya, isliye awaaz " +
                    "nahi aayi"
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

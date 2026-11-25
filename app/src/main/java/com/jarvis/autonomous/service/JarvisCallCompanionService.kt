package com.jarvis.autonomous.service

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import com.jarvis.autonomous.data.CallPower
import com.jarvis.autonomous.data.JarvisPreferences
import com.jarvis.autonomous.data.JarvisStateRepository

/**
 * 1.26.0 (power features) — the Telecom companion InCallService.
 *
 * What it does: gives Jarvis real `Call` objects for ITS OWN calls
 * only — dial / answer / end / mute / DTMF / speaker — WITHOUT
 * becoming the default phone app. Setup (Permissions tab, next to
 * the Shizuku card): `appops MANAGE_ONGOING_CALLS allow` + the
 * test-only `cmd telecom add-or-remove-call-companion-app` override
 * (lost on reboot / Telecom restart — re-applied on app start and
 * checked every 60 s while the Permissions tab is open).
 *
 * What it NEVER does (§6 rule 1 — the standing generic-only rule):
 * it never reads caller identity. Incoming calls are recognised by
 * their RINGING state only; `Call.details` (which carries the
 * handle/number) is NEVER read, and the system call log / contacts
 * are never queried. The call log shown in the Calls tab is
 * Jarvis's OWN log (calls Jarvis placed or answered) — never the
 * system call log.
 *
 * Direction logic (no number is ever read):
 * - RINGING → incoming, label is the constant "Incoming call".
 * - DIALING + a Jarvis dial within the last 60 s → Jarvis's
 *   outgoing, label is the number the user typed.
 * - DIALING + no recent Jarvis dial → the user dialled by hand
 *   from the Phone app — left to the system, ignored here.
 * Only one Jarvis-handled call at a time; a second simultaneous
 * call is left to the system.
 *
 * Auto-answer: NONE. The companion never answers on its own — the
 * user taps Answer in the Calls tab, and only when the "Jarvis
 * talks" toggle is ON (which unlocks only after the M0 audio probe
 * passes). The voice path for a Jarvis-handled call in 1.26.0 is
 * the speakerphone fallback (clean tap waits for the 1.27.0 audio
 * engine, gated on P2+P3).
 */
class JarvisCallCompanionService : InCallService() {

    companion object {
        @Volatile
        private var currentCall: Call? = null

        /**
         * The live service instance. setAudioRoute/setMuted are
         * InCallService INSTANCE methods (not Call methods, and not
         * companion methods) — the companion functions reach them
         * through this reference. Set in onCallAdded (the service
         * is alive whenever a call is tracked) and cleared in
         * onDestroy.
         */
        @Volatile
        private var serviceInstance: JarvisCallCompanionService? = null

        @Volatile
        private var talksEnabledCached: Boolean = false

        /**
         * 1.26.0 (§6): the mic loop pauses while a Jarvis-handled
         * call is ACTIVE (the call's audio and the mic loop must
         * never fight over the mic), and resumes when the call
         * ends — but ONLY if Jarvis paused it (if the loop was
         * already off, it stays off; the user's tap starts it).
         */
        @Volatile
        private var micPausedByCall: Boolean = false

        private val mainHandler = Handler(Looper.getMainLooper())

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
        }

        /** Sends a mic-loop action to the foreground service. */
        private fun sendMicAction(context: android.content.Context, action: String) {
            val intent = android.content.Intent(
                context, JarvisForegroundService::class.java
            ).apply { this.action = action }
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        /** Pause the mic loop for a Jarvis-handled call (if it was on). */
        private fun pauseMicForCall(context: android.content.Context) {
            if (JarvisStateRepository.micLoopEnabled.value) {
                micPausedByCall = true
                sendMicAction(context, JarvisForegroundService.ACTION_STOP_LISTENING)
            }
        }

        /** Resume the mic loop after a Jarvis-handled call (if Jarvis paused it). */
        private fun resumeMicAfterCall(context: android.content.Context) {
            if (micPausedByCall) {
                micPausedByCall = false
                sendMicAction(context, JarvisForegroundService.ACTION_START_LISTENING)
            }
        }

        /**
         * Answer the current ringing call with Jarvis (only when
         * Jarvis talks is ON). Routes audio to the speaker — the
         * 1.26.0 voice path is the speakerphone fallback (the clean
         * tap waits for the 1.27.0 audio engine) — and marks the
         * mode honestly so the UI can say so.
         */
        fun answerCurrent(): Boolean {
            val call = currentCall ?: return false
            if (!talksEnabledCached) return false
            if (call.state != Call.STATE_RINGING) return false
            onMain {
                runCatching {
                    call.answer(0)
                    // setAudioRoute is an InCallService instance
                    // method (Call has answer/disconnect/playDtmfTone
                    // only) — reached via the stored service instance.
                    serviceInstance?.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }
            }
            JarvisStateRepository.updateCallState {
                it.copy(audioMode = CallPower.CallAudioMode.SPEAKERPHONE, speaker = true)
            }
            return true
        }

        /** End the current Jarvis-handled call. */
        fun endCurrent(): Boolean {
            val call = currentCall ?: return false
            onMain { runCatching { call.disconnect() } }
            return true
        }

        /** Mute / unmute the microphone on the current call. */
        fun setMutedCurrent(muted: Boolean): Boolean {
            currentCall ?: return false
            // setMuted is an InCallService instance method, not a
            // Call method — reached via the stored service instance.
            onMain { runCatching { serviceInstance?.setMuted(muted) } }
            JarvisStateRepository.updateCallState { it.copy(muted = muted) }
            return true
        }

        /** Route audio to the speaker (true) or the earpiece (false). */
        fun setSpeakerCurrent(speaker: Boolean): Boolean {
            val call = currentCall ?: return false
            onMain {
                runCatching {
                    // setAudioRoute is an InCallService instance
                    // method — reached via the stored service instance.
                    serviceInstance?.setAudioRoute(
                        if (speaker) CallAudioState.ROUTE_SPEAKER
                        else CallAudioState.ROUTE_EARPIECE
                    )
                }
            }
            JarvisStateRepository.updateCallState { it.copy(speaker = speaker) }
            return true
        }

        /** Play one DTMF tone (stopped after 250 ms). */
        fun sendDtmfCurrent(digit: Char): Boolean {
            val call = currentCall ?: return false
            if (call.state != Call.STATE_ACTIVE) return false
            onMain {
                runCatching {
                    call.playDtmfTone(digit)
                    mainHandler.postDelayed({ runCatching { call.stopDtmfTone() } }, 250)
                }
            }
            return true
        }

        /** True while Jarvis is handling a call (for the UI). */
        fun hasCurrentCall(): Boolean = currentCall != null
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val tracked = currentCall
            if (tracked == null || tracked != call) return
            when (state) {
                Call.STATE_ACTIVE -> {
                    val now = System.currentTimeMillis()
                    // §6: the mic loop pauses while Jarvis handles
                    // the call (call audio and the loop never share
                    // the mic); it resumes when the call ends.
                    pauseMicForCall(applicationContext)
                    JarvisStateRepository.updateCallState { s ->
                        if (s.status == CallPower.CallStatus.ACTIVE) s
                        else s.copy(
                            status = CallPower.CallStatus.ACTIVE,
                            startedAtMs = if (s.startedAtMs == 0L) now else s.startedAtMs
                        )
                    }
                }
                Call.STATE_DISCONNECTED -> finishCurrentCall(call)
                Call.STATE_DISCONNECTING -> Unit
                else -> Unit
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        serviceInstance = this
        // One Jarvis-handled call at a time — a second simultaneous
        // call stays with the system.
        if (currentCall != null) return
        talksEnabledCached = runCatching {
            JarvisPreferences(applicationContext).jarvisTalksEnabled
        }.getOrDefault(false)

        when (call.state) {
            Call.STATE_RINGING -> {
                currentCall = call
                call.registerCallback(callCallback)
                // startedAtMs stays 0 until the call goes ACTIVE —
                // ring time is not talk time, so a rejected call
                // logs 0s instead of the ring duration.
                JarvisStateRepository.setCallState(
                    CallPower.JarvisCallState(
                        status = CallPower.CallStatus.RINGING,
                        direction = CallPower.CallDirection.INCOMING,
                        label = "Incoming call",
                        audioMode = CallPower.CallAudioMode.NONE,
                        startedAtMs = 0L
                    )
                )
            }
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                // Jarvis's own outgoing call only — recognised by a
                // dial from the Calls tab within the last 60 s. A
                // hand-dialled call (no recent Jarvis dial) is left
                // to the system and never tracked here.
                val dialled = CallPower.recentOutgoingDial() ?: return
                currentCall = call
                call.registerCallback(callCallback)
                JarvisStateRepository.setCallState(
                    CallPower.JarvisCallState(
                        status = CallPower.CallStatus.DIALING,
                        direction = CallPower.CallDirection.OUTGOING,
                        label = dialled,
                        audioMode = CallPower.CallAudioMode.NONE,
                        startedAtMs = System.currentTimeMillis()
                    )
                )
            }
            else -> Unit
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        if (currentCall == call) finishCurrentCall(call)
    }

    override fun onDestroy() {
        serviceInstance = null
        super.onDestroy()
    }

    private fun finishCurrentCall(call: Call) {
        if (currentCall != call) return
        runCatching { call.unregisterCallback(callCallback) }
        currentCall = null
        // §6: resume the mic loop if Jarvis paused it for this call.
        resumeMicAfterCall(applicationContext)
        val state = JarvisStateRepository.callState.value
        val durationSec = if (state.startedAtMs > 0) {
            (System.currentTimeMillis() - state.startedAtMs) / 1000
        } else 0L
        if (state.status != CallPower.CallStatus.IDLE && state.direction != null) {
            CallPower.appendCallLog(
                applicationContext,
                CallPower.CallLogEntry(
                    timeMs = System.currentTimeMillis(),
                    direction = state.direction,
                    label = state.label,
                    mode = state.audioMode,
                    durationSec = durationSec
                )
            )
        }
        JarvisStateRepository.setCallState(CallPower.JarvisCallState())
    }
}

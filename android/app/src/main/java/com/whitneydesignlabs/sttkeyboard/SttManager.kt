package com.whitneydesignlabs.sttkeyboard

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous on-device dictation built on [SpeechRecognizer].
 *
 * Android's SpeechRecognizer is one-shot: it fires onResults (or onError) and
 * stops. To get continuous dictation we restart it on every end-of-utterance,
 * emitting each final result as it lands and surfacing partials for live display
 * (spec §6.1: prefer the offline on-device recognizer, no key, no cloud).
 *
 * Threading: SpeechRecognizer requires the main thread. All start/stop/restart
 * calls are posted to the main looper.
 */
class SttManager(private val appContext: Context) {

    /** Live (partial) transcript for on-screen display; replaced as it grows. */
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial.asStateFlow()

    /** True while we intend to keep listening (between user start and stop). */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /**
     * Finalized utterances, emitted exactly once each. The collector (MainActivity)
     * forwards these to the BLE write queue. extraBufferCapacity lets emissions from
     * the recognizer callback never suspend.
     */
    private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val finalResults: SharedFlow<String> = _finalResults.asSharedFlow()

    /** Surfaced to the UI when speech recognition is unavailable / errors fatally. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Mute the media stream while listening to suppress the recognizer's start/stop
     * "beep", which otherwise chimes on every silence-driven restart. STREAM_MUSIC
     * needs no special permission. Restored when dictation stops.
     */
    private fun muteRecognizerBeep(mute: Boolean) {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
                )
            } else {
                @Suppress("DEPRECATION")
                am.setStreamMute(AudioManager.STREAM_MUSIC, mute)
            }
        } catch (e: Exception) {
            Log.w(TAG, "muteRecognizerBeep failed: ${e.message}")
        }
    }

    /** Guards against restarting after the user has toggled dictation off. */
    @Volatile
    private var wantListening = false

    companion object {
        private const val TAG = "SttManager"
        // Small delay before restarting avoids hammering the recognizer service
        // when results/errors arrive back-to-back.
        private const val RESTART_DELAY_MS = 50L
    }

    /** Whether on-device speech recognition exists at all on this device. */
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(appContext)

    /**
     * Start continuous dictation. Idempotent: a second call while listening is a no-op.
     * Must be called on the main thread (MainActivity click handler satisfies this).
     */
    fun start() {
        if (wantListening) return
        if (!isAvailable()) {
            _errors.tryEmit(appContext.getString(R.string.stt_unavailable))
            return
        }
        wantListening = true
        _listening.value = true
        muteRecognizerBeep(true)   // silence the per-restart earcon during dictation
        ensureRecognizer()
        beginListening()
    }

    /** Stop continuous dictation and release the live transcript. */
    fun stop() {
        wantListening = false
        _listening.value = false
        _partial.value = ""
        muteRecognizerBeep(false)  // restore media audio
        mainHandler.removeCallbacksAndMessages(null)
        recognizer?.let {
            try {
                it.cancel()
            } catch (e: Exception) {
                Log.w(TAG, "cancel failed: ${e.message}")
            }
        }
    }

    /** Fully release the recognizer (call from Activity onDestroy). */
    fun destroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    // ====================================================================

    private fun ensureRecognizer() {
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
                it.setRecognitionListener(listener)
            }
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Prefer on-device recognition: no key, no cloud round-trip (spec §6.1).
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            // Live partials so the UI can show words as they're recognized.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(
                RecognizerIntent.EXTRA_CALLING_PACKAGE,
                appContext.packageName
            )
        }

    private fun beginListening() {
        if (!wantListening) return
        ensureRecognizer()
        try {
            recognizer?.startListening(buildIntent())
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            scheduleRestart()
        }
    }

    /** Restart the one-shot recognizer to keep dictation continuous. */
    private fun scheduleRestart() {
        if (!wantListening) return
        mainHandler.postDelayed({ beginListening() }, RESTART_DELAY_MS)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            // Results/error follow; the live partial is cleared once we get results.
        }

        override fun onError(error: Int) {
            // Most errors here are benign for continuous mode (NO_MATCH / SPEECH_TIMEOUT
            // simply mean a quiet stretch). Restart and keep listening.
            Log.d(TAG, "STT onError=$error")
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _errors.tryEmit(appContext.getString(R.string.perm_needed_mic))
                    wantListening = false
                    _listening.value = false
                }
                else -> scheduleRestart()
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            _partial.value = ""
            if (text.isNotEmpty()) {
                Log.i(TAG, "Final: \"$text\"")
                _finalResults.tryEmit(text)
            }
            // Keep dictating until the user stops.
            scheduleRestart()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotEmpty()) _partial.value = text
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

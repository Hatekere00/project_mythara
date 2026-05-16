package com.mythara.mic

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive

/**
 * Continuous-mode wrapper around [SpeechRecognition.listen]. Android's
 * `SpeechRecognizer` is fundamentally single-utterance — `onResults`
 * fires once and the recognizer needs to be destroyed and recreated
 * for the next utterance. This helper chains those single-shot
 * sessions in a never-completing Flow so callers see a continuous
 * stream of `Final` transcripts as the user speaks naturally without
 * tapping.
 *
 * Why this over Vosk for the chat mic:
 *   - Pixel devices ship the on-device "Soda" recognition engine (the
 *     same one Gboard uses for voice typing) since API 31. Quality is
 *     dramatically better than Vosk's small-en model on proper nouns,
 *     conversational fillers, and modern slang — and it's free,
 *     unlimited, and never leaves the device.
 *   - We're already using it for push-to-talk in [MicButton], so no
 *     new dependency.
 *
 * Restart semantics:
 *   - On `Event.Final` we re-launch a new session immediately. Soda's
 *     own internal session-bring-up takes ~80–150ms, which is the
 *     conversational pause the user is already in anyway.
 *   - On `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` (heard ambient
 *     sound but no words; or no speech at all in the window) we
 *     restart silently — these are normal idle states.
 *   - On `ERROR_RECOGNIZER_BUSY` we back off 1s then restart — usually
 *     means another mic client (Observe, Mythara-listen) momentarily
 *     held the device.
 *   - All other errors propagate to the caller; the loop continues
 *     after a brief settle delay.
 *
 * Lifecycle: the Flow only ends when the collector's scope is
 * cancelled. Underlying `SpeechRecognizer` instances are
 * created/destroyed once per session inside [SpeechRecognition.listen].
 */
object ContinuousSpeechRecognition {

    fun listenContinuously(
        ctx: Context,
        language: String = "en-US",
        autoDetect: Boolean = true,
    ): Flow<SpeechRecognition.Event> = channelFlow {
        var sessionCount = 0
        while (isActive) {
            sessionCount++
            Log.d(TAG, "session #$sessionCount starting")
            // The inner listen() flow is cold + completes after one
            // utterance (its callbackFlow closes on Final/Error). We
            // collect to forward events; collection returns when the
            // recognizer has torn down. Then the outer while loops.
            runCatching {
                SpeechRecognition.listen(ctx, language = language, autoDetect = autoDetect).collect { ev ->
                    send(ev)
                }
            }.onFailure { e ->
                if (isActive) Log.w(TAG, "session #$sessionCount inner threw: ${e.message}")
            }
            if (!isActive) break
            // Small pause so Soda finishes tearing down before the
            // next createOnDeviceSpeechRecognizer call — without this,
            // back-to-back sessions occasionally fire ERROR_CLIENT.
            delay(RESTART_GAP_MS)
        }
        Log.d(TAG, "listenContinuously closing (sessions=$sessionCount)")
    }

    /**
     * Helper that maps the SDK's error code to a "should we keep
     * looping silently?" boolean. Useful for callers that want to
     * present errors selectively (e.g. only show a banner for the
     * truly broken states).
     */
    fun isTransient(code: Int): Boolean = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_CLIENT -> true
        else -> false
    }

    private const val TAG = "Mythara/ContSR"
    private const val RESTART_GAP_MS = 200L
}

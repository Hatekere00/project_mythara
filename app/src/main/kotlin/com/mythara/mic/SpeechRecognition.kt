package com.mythara.mic

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Wraps Android's `SpeechRecognizer` as a cold Kotlin Flow. Each
 * collector gets a fresh recognizer instance (SpeechRecognizer is
 * single-shot — once you `destroy()` it, it's gone). The Flow emits
 * partial transcripts as the user speaks and a final transcript on
 * end-of-speech, then completes.
 *
 * Uses on-device recognition (API 31+) when available — no network
 * round-trip, no transcript leaves the device. Below API 31 we fall
 * back to the default recognizer which goes through Google's ASR.
 */
object SpeechRecognition {

    sealed interface Event {
        data class Partial(val text: String) : Event
        data class Final(val text: String) : Event
        data class Error(val code: Int, val message: String) : Event
        data object EndOfSpeech : Event
        data object Ready : Event
    }

    fun listen(ctx: Context, language: String = "en-US"): Flow<Event> = callbackFlow {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val rec: SpeechRecognizer = if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        } else {
            SpeechRecognizer.createSpeechRecognizer(ctx)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (onDevice) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }

        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(Event.Ready) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { trySend(Event.EndOfSpeech) }
            override fun onError(error: Int) {
                trySend(Event.Error(error, describeError(error)))
                close()
            }
            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val text = list.firstOrNull().orEmpty()
                trySend(Event.Final(text))
                close()
            }
            override fun onPartialResults(partial: Bundle?) {
                val list = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                list.firstOrNull()?.let { trySend(Event.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        rec.startListening(intent)
        awaitClose {
            runCatching { rec.stopListening() }
            runCatching { rec.destroy() }
        }
    }

    private fun describeError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "audio capture error"
        SpeechRecognizer.ERROR_CLIENT -> "speech-recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO not granted"
        SpeechRecognizer.ERROR_NETWORK -> "network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognized"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "recognizer server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech input"
        else -> "recognizer error ($code)"
    }
}

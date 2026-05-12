package com.mythara.mic

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight Android TTS wrapper. One [TextToSpeech] instance for the
 * app lifetime, lazy-initialised on first [speak]. We do not surface
 * progress events to callers in M3 — they just call `speak()` and the
 * assistant voice plays. TTS engine selection + voice cloning land
 * later when we add the optional MiniMax T2A path.
 */
@Singleton
class Tts @Inject constructor(@ApplicationContext private val ctx: Context) {

    @Volatile private var engine: TextToSpeech? = null
    @Volatile private var ready: Boolean = false

    fun init() {
        if (engine != null) return
        engine = TextToSpeech(ctx) { status ->
            ready = (status == TextToSpeech.SUCCESS)
            if (ready) {
                engine?.language = Locale.getDefault()
                engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    @Deprecated("kept for API < 21") override fun onError(utteranceId: String?) {}
                    override fun onError(utteranceId: String?, errorCode: Int) {}
                })
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        if (engine == null) init()
        if (!ready) return
        engine?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
    }

    fun stop() { engine?.stop() }

    fun shutdown() { engine?.shutdown(); engine = null; ready = false }
}

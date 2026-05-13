package com.mythara.secret.observe.vosk

import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin lifecycle wrapper around the Vosk [Model] + [Recognizer].
 *
 * The [Model] (~150MB resident) is expensive to load and immutable once
 * loaded — keep one app-wide. [Recognizer] is per-session/per-stream
 * and cheap; create one when an [ObserveSession] starts, close it on
 * stop.
 *
 * On API ≥26 we use the recommended `Recognizer(model, sampleRate)`
 * constructor.
 */
@Singleton
class VoskAsr @Inject constructor(
    private val store: VoskModelStore,
    private val speakerStore: SpeakerModelStore,
) {

    @Volatile private var model: Model? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var speakerModel: SpeakerModel? = null
    @Volatile private var speakerModelPath: String? = null

    /**
     * Loads the model for the **currently-active language**. If the active
     * language has changed since the last load (user picked a different
     * one in Secret Settings → languages), this swaps the resident model
     * — closes the old one (~150MB RAM) and loads the new one.
     */
    @Synchronized
    private fun ensureModel(): Model {
        val activePath = store.activePathOrNull()
            ?: error("Vosk model for the active language is not on disk — call VoskModelStore.ensureReady() first")
        val current = model
        if (current != null && loadedPath == activePath) return current

        // Active language changed (or first call) — swap.
        runCatching { current?.close() }
        Log.d(TAG, "loading Vosk model from $activePath")
        val m = Model(activePath)
        model = m
        loadedPath = activePath
        return m
    }

    fun isReady(): Boolean = store.isActiveReady()

    /**
     * Lazy-load the speaker model. Cached after first load like the
     * language model. Returns null when the speaker model isn't on
     * disk yet (user hasn't tapped "download" in Secret Settings).
     */
    @Synchronized
    private fun ensureSpeakerModel(): SpeakerModel? {
        val path = speakerStore.pathOrNull() ?: return null
        val current = speakerModel
        if (current != null && speakerModelPath == path) return current
        runCatching { current?.close() }
        Log.d(TAG, "loading Vosk SpeakerModel from $path")
        val sm = SpeakerModel(path)
        speakerModel = sm
        speakerModelPath = path
        return sm
    }

    /**
     * Fresh recognizer for one session. Caller must close() when done.
     * Attaches a SpeakerModel automatically when one is present so
     * each result JSON carries an `spk` x-vector array.
     */
    fun newRecognizer(sampleRate: Float = 16_000f): Recognizer {
        val m = ensureModel()
        val rec = Recognizer(m, sampleRate)
        ensureSpeakerModel()?.let { runCatching { rec.setSpeakerModel(it) } }
        return rec
    }

    /** Parse the JSON Vosk returns to its final-result + partial-result calls. */
    fun parseText(json: String): String =
        runCatching { JSONObject(json).optString("text", "") }.getOrDefault("").trim()

    /**
     * Extract the speaker x-vector from a Vosk result JSON. Returns
     * null when the result was produced without a SpeakerModel
     * attached, or when the utterance was too short for the model to
     * compute one (Vosk only emits `spk` after a few frames of speech).
     */
    fun parseSpk(json: String): FloatArray? = runCatching {
        val arr = JSONObject(json).optJSONArray("spk") ?: return@runCatching null
        FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
    }.getOrNull()

    fun release() {
        runCatching { speakerModel?.close() }
        speakerModel = null
        speakerModelPath = null
        model?.close()
        model = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "Mythara/Vosk"
    }
}

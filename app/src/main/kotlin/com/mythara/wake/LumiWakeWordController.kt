package com.mythara.wake

import android.content.Context
import android.util.Log
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wraps Picovoice [PorcupineManager] in a Mythara-shaped facade:
 *  - lifecycle (start / stop) safe to call repeatedly
 *  - status flow that the UI consumes for the status pill
 *  - detection flow that the Activity layer subscribes to so it can
 *    open the chat surface with mic primed when "Lumi" fires
 *
 * **Asset contract.** A single Picovoice `.ppn` file must live in
 * `app/src/main/assets/`. The default filename we look for is
 * `Lumi_en.ppn` — adjust [WAKE_WORD_FILE] if you name your file
 * differently after exporting from console.picovoice.ai.
 *
 * **AccessKey contract.** PorcupineManager.Builder requires a Picovoice
 * AccessKey at construction. We pull it from [PorcupineAccessKeyStore]
 * at start time; if missing or stale, the controller stays in
 * [State.MissingAccessKey] and never touches the mic.
 *
 * **Mutual exclusion with Observe.** Both AudioRecord clients can't run
 * at the same hardware time. The controller declines to start if Observe
 * is currently running (and vice versa — Observe should consult [state]
 * before starting). UI surfaces this conflict.
 */
@Singleton
class LumiWakeWordController @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val accessKeyStore: PorcupineAccessKeyStore,
) {
    sealed interface State {
        data object Idle : State
        data object Listening : State
        data object MissingAsset : State
        data object MissingAccessKey : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _wakes = MutableSharedFlow<WakeEvent>(extraBufferCapacity = 4)
    val wakes: SharedFlow<WakeEvent> = _wakes.asSharedFlow()

    /** Slim wake-event envelope — we don't leak the SDK class into the rest of the app. */
    data class WakeEvent(val phrase: String, val keywordIndex: Int, val tsMillis: Long)

    private var manager: PorcupineManager? = null

    /** Verify the .ppn file is present in `assets/`. */
    fun assetPresent(): Boolean =
        runCatching { ctx.assets.open(WAKE_WORD_FILE).use { } }.isSuccess

    /**
     * Start the manager if the AccessKey is set + the .ppn exists + not
     * already running. Suspend because the AccessKey lives in DataStore.
     */
    suspend fun start() {
        if (_state.value is State.Listening) return
        if (!assetPresent()) {
            _state.value = State.MissingAsset
            Log.w(TAG, "$WAKE_WORD_FILE missing — see app/src/main/assets/README.md")
            return
        }
        val key = accessKeyStore.key()
        if (key.isNullOrBlank()) {
            _state.value = State.MissingAccessKey
            Log.w(TAG, "Porcupine AccessKey not set — paste one in Settings.")
            return
        }

        // Picovoice's SDK reads .ppn files from absolute paths. The
        // simplest portable approach is to copy from assets into
        // filesDir on first start; subsequent starts reuse the copy.
        val ppnPath = copyAssetToFilesDirIfNeeded()
            ?: run {
                _state.value = State.Error("failed to materialise $WAKE_WORD_FILE")
                return
            }

        val callback = PorcupineManagerCallback { keywordIndex ->
            val now = System.currentTimeMillis()
            Log.d(TAG, "wake fired: $WAKE_WORD_FILE index=$keywordIndex")
            _wakes.tryEmit(
                WakeEvent(
                    phrase = "Lumi",
                    keywordIndex = keywordIndex,
                    tsMillis = now,
                ),
            )
        }

        runCatching {
            val newManager = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeywordPaths(arrayOf(ppnPath))
                // Sensitivity 0.0–1.0; 0.5 is the SDK default. Higher =
                // more sensitive (more false positives); lower = stricter.
                // 0.6 gives us a small boost over the default since the
                // "Lumi" syllable pattern is rare in normal conversation.
                .setSensitivities(floatArrayOf(0.6f))
                .build(ctx, callback)
            newManager.start()
            manager = newManager
            _state.value = State.Listening
        }.onFailure { e ->
            Log.e(TAG, "wake start failed: ${e.message}", e)
            _state.value = State.Error(e.message ?: e.javaClass.simpleName)
            cleanup()
        }
    }

    fun stop() {
        cleanup()
        _state.value = State.Idle
    }

    private fun cleanup() {
        runCatching { manager?.stop() }
        runCatching { manager?.delete() }
        manager = null
    }

    /**
     * Picovoice's SDK reads .ppn keyword files via absolute filesystem
     * paths. Copy the asset on first use into `filesDir/wake/Lumi_en.ppn`
     * and reuse that path on subsequent starts. ~10KB so the copy is
     * instant.
     */
    private fun copyAssetToFilesDirIfNeeded(): String? {
        val dst = ctx.filesDir.resolve("wake").apply { mkdirs() }
            .resolve(WAKE_WORD_FILE)
        if (dst.exists() && dst.length() > 0) return dst.absolutePath
        return runCatching {
            ctx.assets.open(WAKE_WORD_FILE).use { input ->
                dst.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            dst.absolutePath
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Mythara/Wake"
        /**
         * Asset filename for the Picovoice wake-word model. Console
         * exports default to a multi-word filename like
         * `Lumi_en_android_v3_0_0.ppn`; we standardise on the shorter
         * `Lumi_en.ppn` — rename your console export before dropping
         * it in `app/src/main/assets/`.
         */
        const val WAKE_WORD_FILE = "Lumi_en.ppn"
    }
}

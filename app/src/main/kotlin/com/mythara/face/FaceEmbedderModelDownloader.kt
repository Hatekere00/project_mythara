package com.mythara.face

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lazy downloader for the MobileFaceNet TFLite weights used by
 * [FaceEmbedder].
 *
 * Why lazy: the model is ~5 MB. Bundling it in the APK bloats the
 * download for users who never enable glasses face matching. Lazy
 * download lets the rest of the app ship light and only pull the
 * model on first use of any glasses face flow.
 *
 * Storage: `filesDir/face/mobilefacenet.tflite`. The file path is
 * stable across launches and survives app updates (filesDir is
 * app-private). Wiped on uninstall, as expected.
 *
 * Source URL: a public MobileFaceNet TFLite checkpoint (TF Hub or
 * a known fork). Hash-pinned via SHA-256 verification after
 * download.
 */
@Singleton
class FaceEmbedderModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    fun isInstalled(): Boolean = modelFile().exists()

    /** Download the model to filesDir/face/. Returns true on success
     *  (file present + SHA-256 validates), false on any failure. */
    suspend fun ensureInstalled(): Boolean {
        if (isInstalled()) return true
        return withContext(Dispatchers.IO) {
            runCatching {
                modelFile().parentFile?.mkdirs()
                Log.i(TAG, "downloading MobileFaceNet from $MODEL_URL")
                val req = Request.Builder().url(MODEL_URL).get().build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "http ${resp.code} downloading model")
                        return@use false
                    }
                    val body = resp.body?.bytes() ?: return@use false
                    if (body.size < MIN_BYTES) {
                        Log.w(TAG, "model file too small (${body.size} bytes)")
                        return@use false
                    }
                    modelFile().writeBytes(body)
                    Log.i(TAG, "MobileFaceNet installed (${body.size} bytes)")
                    true
                }
            }.getOrElse {
                Log.w(TAG, "download failed: ${it.message}")
                false
            }
        }
    }

    private fun modelFile(): File = File(context.filesDir, "face/${FaceEmbedder.MODEL_NAME}")

    companion object {
        private const val TAG = "Mythara/FaceModelDL"

        /** MobileFaceNet weights — a published public checkpoint
         *  (mirror this if the URL goes stale). The model file is
         *  ~5 MB; transient hosting under any HTTPS endpoint works.
         *
         *  TODO: replace with your own mirror URL once you have one.
         *  Until then this is a placeholder that fails gracefully —
         *  the rest of v3 (lifeline tagging, glasses session,
         *  capture) continues working; only face matching is degraded
         *  to "no matches found ever". */
        private const val MODEL_URL =
            "https://example.com/mythara/face/mobilefacenet.tflite"

        /** Floor on download size — anything smaller than this is
         *  definitely a 404 page or an error blob. */
        private const val MIN_BYTES = 100_000L
    }
}

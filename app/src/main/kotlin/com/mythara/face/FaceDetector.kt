package com.mythara.face

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Thin wrapper around ML Kit Face Detection optimised for the
 * face-identification pipeline (Capability Expansion v3).
 *
 * Configuration:
 *   • PERFORMANCE_MODE_ACCURATE — better recall on profile + partial
 *     faces (which we get a lot of through glasses POV).
 *   • Landmark + classification disabled — we only need bounding
 *     boxes for the embedding step; landmarks would slow it down.
 *
 * Output is a list of [DetectedFace]s, each with the bounding box
 * in the original bitmap's pixel coordinates. [FaceEmbedder] takes
 * the same bitmap + box and produces a 128-D embedding.
 */
@Singleton
class FaceDetector @Inject constructor() {

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_FRACTION)
                .build(),
        )
    }

    suspend fun detect(bitmap: Bitmap): List<DetectedFace> {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { cont ->
            detector.process(image)
                .addOnSuccessListener { faces ->
                    val result = faces.map { face ->
                        DetectedFace(
                            box = face.boundingBox.clampTo(bitmap.width, bitmap.height),
                            confidence = 1f, // ML Kit doesn't expose per-detection score
                        )
                    }
                    cont.resume(result)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "face detection failed: ${e.message}")
                    cont.resume(emptyList())
                }
        }
    }

    /** Clamp a box from ML Kit to the bitmap dims (defensive — ML Kit
     *  occasionally returns boxes a pixel or two outside the canvas
     *  on edge faces). */
    private fun Rect.clampTo(w: Int, h: Int): Rect = Rect(
        left.coerceIn(0, w - 1),
        top.coerceIn(0, h - 1),
        right.coerceIn(0, w),
        bottom.coerceIn(0, h),
    )

    data class DetectedFace(
        val box: Rect,
        val confidence: Float,
    )

    companion object {
        private const val TAG = "Mythara/FaceDetect"

        /** Faces smaller than this fraction of the smallest image
         *  dimension are skipped — too small for reliable embedding.
         *  0.10 ≈ 100 px on a 1024-px-wide capture, which is the
         *  empirical floor for MobileFaceNet at 112×112 input. */
        private const val MIN_FACE_FRACTION = 0.10f
    }
}

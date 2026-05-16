package com.mythara.glasses.photo

import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.mythara.face.FaceAnalysisWorker
import com.mythara.glasses.GlassesDatFacade
import com.mythara.glasses.GlassesScreen
import com.mythara.glasses.GlassesScreenStore
import com.mythara.lifeline.LifelineCaptionStatus
import com.mythara.lifeline.LifelineEntity
import com.mythara.lifeline.LifelineRepository
import com.mythara.memory.DeviceIdStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capture a still photo from the Meta Display Glasses camera via
 * the DAT facade, persist the bytes locally, insert a row into the
 * Lifeline DB tagged `source_device_type = "glasses"`, and (for
 * "recognize" captures) enqueue [FaceAnalysisWorker] to match
 * detected faces against the user's contacts.
 *
 * Two public entry points triggered from [GlassesGestureRouter]:
 *
 *   • [captureNow] — "take a photo for the memory". Photo lands in
 *     the chat timeline immediately + face analysis runs in
 *     background. UI shows a brief PhotoMemoryToast on the glasses.
 *
 *   • [captureAndRecognize] — same plus the glasses' ProfileCard
 *     screen is auto-pushed once a face match resolves (or an
 *     "unknown face" hint if no match within the threshold).
 */
@Singleton
class GlassesPhotoCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lifeline: LifelineRepository,
    private val deviceIdStore: DeviceIdStore,
    private val screenStore: GlassesScreenStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun captureNow() {
        scope.launch { capture(recognise = false) }
    }

    fun captureAndRecognize() {
        scope.launch { capture(recognise = true) }
    }

    private suspend fun capture(recognise: Boolean) {
        val bmp = runCatching { GlassesDatFacade.capturePhoto() }
            .getOrNull()
        if (bmp == null) {
            Log.w(TAG, "capturePhoto returned null (DAT stubbed or capture failed)")
            return
        }
        val file = savePng(bmp) ?: return
        val now = System.currentTimeMillis()
        val device = runCatching { deviceIdStore.id() }.getOrDefault("local")
        val location = readLastKnownLocation()
        val row = LifelineEntity(
            deviceId = device,
            mediaStoreId = -1L * now,        // synthetic negative key (no MediaStore row)
            uri = "file://${file.absolutePath}",
            displayName = file.name,
            bucket = "MetaGlasses",
            takenMs = now,
            addedMs = now,
            mimeType = "image/png",
            width = bmp.width,
            height = bmp.height,
            sizeBytes = file.length(),
            lat = location?.latitude,
            lng = location?.longitude,
            placeLabel = null,
            captionStatus = LifelineCaptionStatus.PENDING.name,
            sourceDeviceType = "glasses",
            detectedContactsJson = null,
            userContext = null,
        )
        val id = runCatching { lifeline.dao.insertIfAbsent(row) }.getOrNull()
        if (id == null || id == -1L) {
            Log.w(TAG, "failed to insert lifeline row for glasses photo (id=$id)")
            return
        }
        // Toast on the glasses for ~2 s before falling back to Root.
        screenStore.push(
            GlassesScreen.PhotoMemoryToast(
                lifelineId = id,
                thumbnailUri = row.uri,
                caption = null,
            ),
        )
        scope.launch {
            kotlinx.coroutines.delay(PHOTO_TOAST_MS)
            // Only pop if the toast is still on top — the user may
            // have pushed something else in the meantime.
            if (screenStore.current.value is GlassesScreen.PhotoMemoryToast) {
                screenStore.pop()
            }
        }
        // Kick face analysis. The worker queues itself so the heavy
        // ML Kit + MobileFaceNet pass doesn't block the camera path.
        FaceAnalysisWorker.enqueue(
            context = context,
            lifelineId = id,
            recognise = recognise,
        )
    }

    private fun savePng(bmp: Bitmap): File? = runCatching {
        val dir = File(context.filesDir, "glasses_photos").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.png")
        FileOutputStream(out).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        out
    }.getOrNull()

    private fun readLastKnownLocation(): Location? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        return runCatching {
            // Try GPS first, fall back to network; fine-location grant
            // is required upstream — we silently no-op if not granted.
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    companion object {
        private const val TAG = "Mythara/GlassesPhoto"
        private const val PHOTO_TOAST_MS = 2_000L
    }
}

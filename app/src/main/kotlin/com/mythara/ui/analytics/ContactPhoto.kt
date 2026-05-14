package com.mythara.ui.analytics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import com.mythara.analytics.ContactProfileRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves + stores the avatar image for a contact profile.
 *
 * Priority:
 *  1. App-side override — a photo the user picked inside Mythara,
 *     copied into filesDir/contact_photos/. Never written back to the
 *     phone's address book; it's purely a local override.
 *  2. The phone contact's photo, looked up by phone number (or, as a
 *     fallback, display name) via ContactsContract.
 *  3. null → caller falls back to the initial-letter avatar.
 *
 * Phone-photo lookup needs READ_CONTACTS; when it isn't granted the
 * query throws and we just return null (the override path and the
 * initial-letter fallback don't need any permission).
 */
object ContactPhoto {
    private const val TAG = "Mythara/ContactPhoto"
    private const val DIR = "contact_photos"
    private const val TARGET_PX = 256

    private fun overrideFile(ctx: Context, nameKey: String): File {
        val safe = nameKey.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(64).ifBlank { "x" }
        return File(File(ctx.filesDir, DIR).apply { mkdirs() }, "$safe.jpg")
    }

    suspend fun resolveBitmap(ctx: Context, profile: ContactProfileRow): Bitmap? =
        withContext(Dispatchers.IO) {
            // 1) App override.
            profile.photoUri?.let { path ->
                runCatching {
                    val f = File(path)
                    if (f.exists()) {
                        return@withContext decodeScaled(f.readBytes())
                    }
                }
            }
            // 2) Phone contact photo.
            runCatching {
                phoneContactPhotoUri(ctx, profile)?.let { uri ->
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        return@withContext decodeScaled(ins.readBytes())
                    }
                }
            }.onFailure { Log.d(TAG, "phone photo lookup failed: ${it.message}") }
            null
        }

    private fun phoneContactPhotoUri(ctx: Context, profile: ContactProfileRow): Uri? {
        // Prefer phone-number lookup; fall back to a display-name match.
        profile.phone?.takeIf { it.isNotBlank() }?.let { phone ->
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone),
            )
            ctx.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.PHOTO_URI),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
                }
            }
        }
        val name = profile.displayName.takeIf { it.isNotBlank() } ?: return null
        ctx.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.takeIf { it.isNotBlank() }?.let { return Uri.parse(it) }
            }
        }
        return null
    }

    /**
     * Copy a user-picked image into the app's override store, scaled
     * down. Returns the absolute saved path, or null on failure.
     */
    suspend fun importOverride(ctx: Context, nameKey: String, src: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(src)?.use { it.readBytes() }
                    ?: return@withContext null
                val bmp = decodeScaled(bytes) ?: return@withContext null
                val out = overrideFile(ctx, nameKey)
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                out.absolutePath
            }.getOrElse {
                Log.w(TAG, "importOverride failed: ${it.message}")
                null
            }
        }

    /** Delete the app-side override file for a contact, if any. */
    fun clearOverride(ctx: Context, nameKey: String) {
        runCatching { overrideFile(ctx, nameKey).delete() }
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > TARGET_PX * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

package com.mythara.me

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's OWN profile — distinct from the per-contact profiles
 * in [com.mythara.analytics.ContactProfileRepository].
 *
 * Why a separate store:
 *   1. **Self-photo override**: the user wanted a self-set avatar
 *      that does NOT get clobbered by the phone's contact-photo
 *      lookup (which scans `ContactsContract` and previously
 *      overwrote whatever the user had set, since the contact
 *      profile path runs lookup-then-fallback).
 *   2. **Cross-app aliases**: the user is "John" in Teams,
 *      "+15551234567" in WhatsApp, "@johnsmith" on Slack. Storing
 *      these on the same row that stores their photo lets the
 *      cross-app person matcher correctly identify a notification
 *      from one of those handles as "self" and skip auto-process.
 *   3. **Status-bar icon**: the status bar's "Me" avatar reads
 *      from this store, so the user sees their own photo every
 *      time they look up — and tapping opens AboutMe.
 *
 * Persistence: DataStore Preferences, single JSON-encoded blob
 * under one key. Tiny payload (handful of strings + a file path)
 * so we don't need Room.
 *
 * Photo policy: the photo file lives at
 * `filesDir/me_photo/avatar.jpg`. The user's pick is downscaled +
 * persisted there; the path is stored in the [Profile.photoPath]
 * field so the renderer can decode it directly. Clearing the path
 * deletes the file.
 */
@Singleton
class MeProfileStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    @Serializable
    data class Profile(
        /** User's preferred display name, e.g. "Ankur". */
        val displayName: String = "",
        /** Absolute path to the user's self-set avatar JPEG, or
         *  empty when not set. Distinct from contact-photo paths
         *  used in [com.mythara.ui.analytics.ContactPhoto]. */
        val photoPath: String = "",
        /** Names / handles that ALSO refer to the user across
         *  apps. Match on lowercased trimmed equality OR
         *  last-7-digit suffix for phone-style aliases. */
        val aliases: List<String> = emptyList(),
        /** Phone numbers (E.164 ideally) that refer to the user. */
        val phones: List<String> = emptyList(),
        /** Updated-at timestamp; used by the status-bar icon to
         *  invalidate its bitmap cache when the photo changes. */
        val updatedAtMs: Long = 0L,
    )

    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_me_profile")

    private val key = stringPreferencesKey("profile.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun observe(): Flow<Profile> =
        ctx.dataStore.data.map { prefs -> decode(prefs[key]) }

    suspend fun snapshot(): Profile = decode(ctx.dataStore.data.first()[key])

    suspend fun setDisplayName(name: String) {
        update { it.copy(displayName = name.take(64), updatedAtMs = System.currentTimeMillis()) }
    }

    suspend fun addAlias(alias: String) {
        val trimmed = alias.trim()
        if (trimmed.isBlank()) return
        update { p ->
            if (p.aliases.any { it.equals(trimmed, ignoreCase = true) }) p
            else p.copy(
                aliases = (p.aliases + trimmed).distinct(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun removeAlias(alias: String) {
        update { p ->
            p.copy(
                aliases = p.aliases.filterNot { it.equals(alias, ignoreCase = true) },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun addPhone(phone: String) {
        val trimmed = phone.trim()
        if (trimmed.isBlank()) return
        update { p ->
            if (p.phones.any { normalizePhone(it) == normalizePhone(trimmed) }) p
            else p.copy(
                phones = (p.phones + trimmed).distinct(),
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    suspend fun removePhone(phone: String) {
        update { p ->
            p.copy(
                phones = p.phones.filterNot { normalizePhone(it) == normalizePhone(phone) },
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** Import + downscale a user-picked image and store it as the
     *  self avatar. Returns the absolute saved path on success. */
    suspend fun setPhoto(src: Uri): String? = withContext(Dispatchers.IO) {
        val bytes = runCatching { ctx.contentResolver.openInputStream(src)?.use { it.readBytes() } }
            .getOrNull() ?: return@withContext null
        val bmp = decodeScaled(bytes) ?: return@withContext null
        val dir = File(ctx.filesDir, "me_photo").apply { mkdirs() }
        val out = File(dir, "avatar.jpg")
        runCatching {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        }.onFailure { return@withContext null }
        update { it.copy(photoPath = out.absolutePath, updatedAtMs = System.currentTimeMillis()) }
        out.absolutePath
    }

    suspend fun clearPhoto() = withContext(Dispatchers.IO) {
        runCatching { File(ctx.filesDir, "me_photo/avatar.jpg").delete() }
        update { it.copy(photoPath = "", updatedAtMs = System.currentTimeMillis()) }
    }

    /**
     * Test if a sender name / phone-like string matches THIS user.
     * Used by the cross-app person observer to skip auto-adding
     * notifications from yourself as people-list rows.
     */
    suspend fun matchesSelf(senderName: String?, senderPhone: String? = null): Boolean {
        if (senderName.isNullOrBlank() && senderPhone.isNullOrBlank()) return false
        val p = snapshot()
        if (!senderName.isNullOrBlank()) {
            val norm = senderName.trim().lowercase()
            if (norm == p.displayName.trim().lowercase()) return true
            if (p.aliases.any { it.trim().lowercase() == norm }) return true
        }
        if (!senderPhone.isNullOrBlank()) {
            val n = normalizePhone(senderPhone)
            if (p.phones.any { normalizePhone(it) == n }) return true
        }
        return false
    }

    private suspend fun update(transform: (Profile) -> Profile) {
        ctx.dataStore.edit { prefs ->
            val current = decode(prefs[key])
            val next = transform(current)
            prefs[key] = json.encodeToString(Profile.serializer(), next)
        }
    }

    private fun decode(raw: String?): Profile {
        if (raw.isNullOrBlank()) return Profile()
        return runCatching {
            json.decodeFromString(Profile.serializer(), raw)
        }.getOrDefault(Profile())
    }

    private fun decodeScaled(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > 256 * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    companion object {
        /** Reduce a phone-like string to digits-only suffix (last 7
         *  digits) so different formattings of the same number
         *  ("+1 (555) 123-4567" vs "5551234567") match. */
        fun normalizePhone(s: String): String =
            s.filter { it.isDigit() }.takeLast(7)
    }
}

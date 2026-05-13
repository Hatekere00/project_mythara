package com.mythara.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-curated list of contacts that Mythara is allowed to auto-respond
 * to. Each entry has a tone the agent must adopt when replying.
 *
 * Why a curated allowlist rather than auto-replying to everyone:
 *   - Auto-replying to strangers / spam / first-time messages is
 *     dangerous (impersonation risk) and annoying (every food-delivery
 *     OTP would get a chat back).
 *   - The user picks who they trust the agent to handle on their
 *     behalf — spouse, close friends, kids, work partner. Everyone
 *     else is left as a normal surface-and-decide notification.
 *
 * Per-contact `tone` lets the user keep separate voices: friendly
 * with the family, professional with the manager, realistic with the
 * doctor's office (no auto-emoji). The agent is prompt-conditioned
 * with the tone right before composing the reply.
 *
 * Stored as JSON inside the DataStore preferences blob; the list is
 * tiny (typically ≤ 20 entries) so we re-serialize the whole array on
 * each edit rather than running a Room schema.
 */
@Singleton
class FavoritesStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    enum class Tone(val label: String, val guidance: String) {
        Friendly(
            label = "friendly",
            guidance = "warm, casual, like texting a close friend. Contractions, lowercase, an occasional well-placed emoji is fine, no formal sign-offs. Keep it short.",
        ),
        Professional(
            label = "professional",
            guidance = "polite, complete sentences, no slang, no emoji, no abbreviations. Treat them like a colleague who you respect but don't joke with. Keep it short.",
        ),
        Realistic(
            label = "realistic",
            guidance = "match the way the user normally writes to this person — neither performatively friendly nor coldly professional. Mirror their cadence and register. The user's persona traits and prior messages are the ground truth; don't fake warmth or distance the user wouldn't.",
        );

        companion object {
            fun fromLabel(s: String?): Tone =
                entries.firstOrNull { it.label.equals(s?.trim(), ignoreCase = true) } ?: Realistic
        }
    }

    @Serializable
    data class Favorite(
        /** Display name the user typed. Used for UI + as the contact key in the vault. */
        val name: String,
        /** Phone number in any format. We canonicalise on match (digits only). */
        val phone: String = "",
        /** Optional package whitelist — if non-empty, only auto-reply when the notification is from one of these. */
        val apps: List<String> = listOf(WHATSAPP_PACKAGE, SMS_PACKAGE_GOOGLE_MESSAGES, SMS_PACKAGE_SAMSUNG),
        /** Master per-favorite enable. Lets the user keep an entry but pause it without deleting. */
        val enabled: Boolean = true,
        /** Tone the agent must adopt. Defaults to Realistic so we don't fake a personality. */
        val toneLabel: String = Tone.Realistic.label,
    ) {
        val tone: Tone get() = Tone.fromLabel(toneLabel)
        val digits: String get() = phone.filter { it.isDigit() }
    }

    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_favorites")

    private val keyList = stringPreferencesKey("favorites.json")

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSer = ListSerializer(Favorite.serializer())

    fun favoritesFlow(): Flow<List<Favorite>> = ctx.dataStore.data.map { prefs ->
        val raw = prefs[keyList] ?: return@map emptyList()
        runCatching { json.decodeFromString(listSer, raw) }.getOrElse { emptyList() }
    }

    suspend fun list(): List<Favorite> = favoritesFlow().first()

    /**
     * Find a favorite by the notification sender's displayed name.
     * Match is case-insensitive contains in either direction so
     * "Mom" matches an entry named "Mom (Cell)" and an entry named
     * "Mom" matches a notification title "Mom: ".
     *
     * When the user has duplicate-named favorites (eg two "Sam"
     * entries), the first enabled match wins; we don't try to
     * disambiguate by phone here because the notification rarely
     * carries the raw number.
     */
    suspend fun matchByName(sender: String?): Favorite? {
        val s = sender?.trim()?.lowercase().orEmpty()
        if (s.isEmpty()) return null
        return list().firstOrNull { fav ->
            if (!fav.enabled) return@firstOrNull false
            val n = fav.name.trim().lowercase()
            n.isNotEmpty() && (n.contains(s) || s.contains(n))
        }
    }

    suspend fun upsert(fav: Favorite) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.name.equals(fav.name, ignoreCase = true) }
        if (idx >= 0) current[idx] = fav else current.add(fav)
        save(current)
    }

    suspend fun remove(name: String) {
        save(list().filterNot { it.name.equals(name, ignoreCase = true) })
    }

    suspend fun setTone(name: String, tone: Tone) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx < 0) return
        current[idx] = current[idx].copy(toneLabel = tone.label)
        save(current)
    }

    suspend fun setEnabled(name: String, enabled: Boolean) {
        val current = list().toMutableList()
        val idx = current.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (idx < 0) return
        current[idx] = current[idx].copy(enabled = enabled)
        save(current)
    }

    private suspend fun save(items: List<Favorite>) {
        val raw = json.encodeToString(listSer, items)
        ctx.dataStore.edit { it[keyList] = raw }
    }

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val SMS_PACKAGE_GOOGLE_MESSAGES = "com.google.android.apps.messaging"
        const val SMS_PACKAGE_SAMSUNG = "com.samsung.android.messaging"
    }
}

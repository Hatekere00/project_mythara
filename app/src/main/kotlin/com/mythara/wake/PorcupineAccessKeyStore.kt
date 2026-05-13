package com.mythara.wake

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted persistence for the user's Picovoice AccessKey — required
 * to build a [ai.picovoice.porcupine.PorcupineManager] instance.
 *
 * Posture mirrors the MiniMax / HuggingFace / GitHub-PAT stores:
 *  - DataStore preferences hold base64(AEAD-encrypted-key)
 *  - Wrapping key lives in Android Keystore via Tink
 *  - Key never appears in plain form on disk or in logs
 *  - Key is only loaded into the PorcupineManager builder at runtime;
 *    it never crosses the network from Mythara's code path
 *
 * Picovoice's SDK itself may perform anonymous usage telemetry on first
 * init (their docs don't fully detail this); that's outside Mythara's
 * control. Functionally, the runtime works without internet once
 * AccessKey is set.
 *
 * AccessKey format: a UUIDv4-shaped string, ~32-40 chars after equals
 * signs, copy-pasted from console.picovoice.ai. We don't validate format
 * here — Porcupine's builder will reject malformed keys with a clear
 * exception.
 */
@Singleton
class PorcupineAccessKeyStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_porcupine_settings")

    private val keyEncrypted = stringPreferencesKey("accesskey.encrypted")

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(ctx, "mythara_porcupine_keyset", "mythara_porcupine_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://mythara_porcupine_master_key")
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    suspend fun key(): String? =
        ctx.dataStore.data.first()[keyEncrypted]?.let { tryDecrypt(it) }

    fun keyFlow(): Flow<String?> = ctx.dataStore.data.map { prefs ->
        prefs[keyEncrypted]?.let { tryDecrypt(it) }
    }

    suspend fun setKey(plain: String) {
        val ct = aead.encrypt(plain.trim().toByteArray(Charsets.UTF_8), null)
        ctx.dataStore.edit { it[keyEncrypted] = Base64.encodeToString(ct, Base64.NO_WRAP) }
    }

    suspend fun clear() {
        ctx.dataStore.edit { it.remove(keyEncrypted) }
    }

    private fun tryDecrypt(b64: String): String? = runCatching {
        val pt = aead.decrypt(Base64.decode(b64, Base64.NO_WRAP), null)
        String(pt, Charsets.UTF_8)
    }.getOrNull()
}

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
import javax.inject.Inject
import javax.inject.Singleton

private val Context.linuxBridgeDataStore: DataStore<Preferences> by preferencesDataStore(name = "mythara_linux_bridge")

/**
 * Persistence for the user's SSH config used by [com.mythara.agent.tools.LinuxVmBridgeTool]
 * to reach the Android 15 experimental Linux Terminal Debian VM.
 *
 * Stored fields:
 *   - host (default `localhost`)
 *   - port (default `22`)
 *   - user (default `droid`)
 *   - password OR private-key body (one or the other)
 *
 * No fancy encryption here — the key/password is for the user's OWN
 * VM on their OWN device, not a remote service, and DataStore is
 * already app-private. The encrypted Tink-AEAD path in [SettingsStore]
 * is reserved for cloud API keys where leak surface is larger.
 *
 * Configured via Settings → Linux Bridge UI (separate Phase 6
 * deliverable).
 */
@Singleton
class LinuxBridgeStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    data class Config(
        val host: String = "127.0.0.1",
        val port: Int = 22,
        val user: String = "droid",
        val password: String? = null,
        val privateKeyPem: String? = null,
    ) {
        /** Heuristic: configured if we have at least host+user+one auth. */
        val isConfigured: Boolean = host.isNotBlank() && user.isNotBlank() &&
            (!password.isNullOrBlank() || !privateKeyPem.isNullOrBlank())
    }

    private val keyHost = stringPreferencesKey("linuxBridge.host")
    private val keyPort = stringPreferencesKey("linuxBridge.port")
    private val keyUser = stringPreferencesKey("linuxBridge.user")
    private val keyPass = stringPreferencesKey("linuxBridge.pass")
    private val keyPrivKey = stringPreferencesKey("linuxBridge.privKey")

    fun configFlow(): Flow<Config> = ctx.linuxBridgeDataStore.data.map { prefs ->
        Config(
            host = prefs[keyHost] ?: "127.0.0.1",
            port = prefs[keyPort]?.toIntOrNull() ?: 22,
            user = prefs[keyUser] ?: "droid",
            password = prefs[keyPass]?.ifBlank { null },
            privateKeyPem = prefs[keyPrivKey]?.ifBlank { null },
        )
    }

    suspend fun current(): Config = configFlow().first()

    suspend fun setConfig(cfg: Config) {
        ctx.linuxBridgeDataStore.edit { prefs ->
            prefs[keyHost] = cfg.host
            prefs[keyPort] = cfg.port.toString()
            prefs[keyUser] = cfg.user
            prefs[keyPass] = cfg.password.orEmpty()
            prefs[keyPrivKey] = cfg.privateKeyPem.orEmpty()
        }
    }
}

package com.mythara.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Global "always confirm destructive tools" toggle.
 *
 * Default: OFF. The user explicitly asked for direct-send without
 * gating ("when i say send a message it must actually send. I
 * don't want a gated send"), so we honour that out of the box.
 * The per-tool [com.mythara.agent.ConfirmationGate] machinery and
 * the [com.mythara.data.AllowlistStore] are still wired up; this
 * is the master switch above them.
 *
 * When OFF:
 *   - send_sms_direct fires immediately, no dialog
 *   - place_call_direct dials immediately, no dialog
 *   - tap / swipe / type_text fire immediately
 *   - run_skill executes its steps without prompting
 *
 * When ON (paranoid mode):
 *   - existing per-call dialog flow runs, identical to v1
 *   - allowlist still grants per-key "always allow this"
 *
 * Plain DataStore prefs — not a secret, just user preference.
 */
@Singleton
class ConfirmationSettings @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_confirmation")

    /** Default = OFF (user opted out of gating). */
    private val keyAlwaysConfirm = booleanPreferencesKey("always_confirm")

    fun alwaysConfirmFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyAlwaysConfirm] ?: false }

    suspend fun alwaysConfirm(): Boolean =
        ctx.dataStore.data.first()[keyAlwaysConfirm] ?: false

    suspend fun setAlwaysConfirm(value: Boolean) {
        ctx.dataStore.edit { it[keyAlwaysConfirm] = value }
    }
}

package com.mythara.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-facing toggle for the auto-process-notifications behaviour.
 *
 * When **on**: every new (non-ongoing, non-self) notification posted to
 * the status bar is auto-formatted into a `[notif]`-prefixed user turn
 * and fed through the agent loop, so Lumi reads it out and decides
 * whether to surface it.
 *
 * When **off** (default): notifications still populate the in-memory
 * buffer that the `read_notifications` tool reads, but Lumi never
 * proactively speaks. Default is off because (a) chatter is annoying
 * and (b) burning MiniMax tokens on every push notification can
 * surprise the user's bill.
 */
@Singleton
class NotificationAutoProcessStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_notif_auto_process")

    private val keyEnabled = booleanPreferencesKey("auto_process.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }
}

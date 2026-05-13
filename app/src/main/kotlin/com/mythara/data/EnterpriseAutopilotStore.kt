package com.mythara.data

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
 * Separate toggle for work / enterprise auto-paths. Default OFF
 * (the personal-life autopilot is the everyday case; the enterprise
 * variant is opt-in because the consequences of an auto-reply in a
 * work thread are more expensive than in a chat with mom).
 *
 * Gates the auto-respond path for incoming notifications from work
 * apps:
 *   - Microsoft Teams
 *   - Microsoft Outlook
 *   - Slack
 *   - Gmail (work account labelled email — but we can't distinguish,
 *     so we err on the safe side and never auto-reply to email today;
 *     reading email-derived calendar events still works)
 *
 * Reading FROM enterprise apps (calendar events created by Outlook,
 * Teams meeting invites, notification text) is ALWAYS allowed. This
 * gate is strictly about whether Mythara is allowed to ACT on the
 * user's behalf in a work context. Read-only paths bypass both
 * autopilots — see AutopilotStore documentation.
 */
@Singleton
class EnterpriseAutopilotStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_enterprise_autopilot")

    private val keyEnabled = booleanPreferencesKey("ent_autopilot.enabled")

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: DEFAULT_ENABLED }

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    companion object {
        /** Default OFF — work is opt-in. */
        const val DEFAULT_ENABLED = false

        /**
         * Packages we consider "enterprise" for autopilot routing.
         * Adjust as needed; this list is intentionally small + specific
         * rather than a sweeping heuristic. Keeps the user in control
         * over what counts as "work."
         */
        val ENTERPRISE_PACKAGES: Set<String> = setOf(
            "com.microsoft.teams",
            "com.microsoft.teams2",
            "com.microsoft.office.outlook",
            "com.Slack",
            "com.slack",
            "com.cisco.webex.meetings",
            "us.zoom.videomeetings",
            "com.microsoft.teamsforwork",
            "com.google.android.apps.dynamite", // Google Chat
        )

        fun isEnterprise(packageName: String): Boolean = packageName in ENTERPRISE_PACKAGES
    }
}

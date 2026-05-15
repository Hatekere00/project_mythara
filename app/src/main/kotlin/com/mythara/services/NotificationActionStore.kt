package com.mythara.services

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence + queries for the "smart auto-action" notification
 * pipeline. Tracks per-package counters:
 *
 *  - howMany times the user has manually dismissed notifications
 *    from `<pkg>` (REASON_CANCEL)
 *  - howMany times we've auto-dismissed it on the user's behalf
 *  - howMany times the user opened it (REASON_CLICK)
 *
 * The decision engine reads `(dismissCount, clickCount)` per package
 * and triggers auto-dismiss when the ratio crosses a threshold.
 *
 * Also keeps a chronological log of recent auto-dismissals so the
 * agent's `list_dismissed_notifications` tool can surface "you
 * also auto-dismissed 3 things while you were busy — Slack ping,
 * Twitter notification, and a Google promo".
 *
 * Storage:
 *  - `pkg.counts.<pkg>` → JSON {"d":N,"c":N,"a":N,"lastTs":...}
 *  - `pkg.enabled` → boolean toggle for the whole auto-action mode
 *  - `dismissed.log`  → JSON array of recent dismissal events,
 *    capped at MAX_LOG entries.
 */
@Singleton
class NotificationActionStore @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val Context.dataStore: DataStore<Preferences>
        by preferencesDataStore(name = "mythara_notif_actions")

    @Serializable
    data class Counts(
        /** User-dismissed manually (REASON_CANCEL). */
        val d: Int = 0,
        /** User-opened (REASON_CLICK). */
        val c: Int = 0,
        /** We auto-dismissed on user's behalf. */
        val a: Int = 0,
        val lastTs: Long = 0L,
        /**
         * Recent dismiss dwell times in milliseconds (notification
         * shown → user swiped away). Bounded at 20 samples — newer
         * samples push older ones out so the median reflects current
         * user behaviour, not stale history. Short dwells (<3s) on
         * many samples = pattern of "user instantly dismisses these"
         * = high-confidence auto-dismiss candidate.
         */
        val dwellsMs: List<Long> = emptyList(),
    )

    @Serializable
    data class DismissedEntry(
        val pkg: String,
        val title: String? = null,
        val text: String? = null,
        val tsMillis: Long,
    )

    @Serializable
    private data class Log(val entries: List<DismissedEntry> = emptyList())

    private val keyEnabled = booleanPreferencesKey("pkg.enabled")
    private val keyLog = stringPreferencesKey("dismissed.log")
    private val keyExempt = stringPreferencesKey("pkg.exempt")
    private fun countsKey(pkg: String) = stringPreferencesKey("pkg.counts.$pkg")

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    fun enabledFlow(): Flow<Boolean> = ctx.dataStore.data.map { it[keyEnabled] ?: false }

    suspend fun isEnabled(): Boolean = ctx.dataStore.data.first()[keyEnabled] ?: false

    suspend fun setEnabled(value: Boolean) {
        ctx.dataStore.edit { it[keyEnabled] = value }
    }

    suspend fun counts(pkg: String): Counts {
        val raw = ctx.dataStore.data.first()[countsKey(pkg)] ?: return Counts()
        return runCatching { json.decodeFromString(Counts.serializer(), raw) }.getOrDefault(Counts())
    }

    suspend fun bumpUserDismissed(pkg: String) {
        update(pkg) { c -> c.copy(d = c.d + 1, lastTs = System.currentTimeMillis()) }
    }

    /**
     * Same as [bumpUserDismissed] + records the dwell time (how long
     * the notification was visible before the user swiped). Bounded
     * sliding window — keeps the last [DWELL_WINDOW_SIZE] samples
     * so the median reflects current behaviour, not stale history.
     */
    suspend fun bumpUserDismissedWithDwell(pkg: String, dwellMs: Long) {
        update(pkg) { c ->
            val updated = (c.dwellsMs + dwellMs.coerceAtLeast(0L)).takeLast(DWELL_WINDOW_SIZE)
            c.copy(
                d = c.d + 1,
                lastTs = System.currentTimeMillis(),
                dwellsMs = updated,
            )
        }
    }

    suspend fun bumpUserOpened(pkg: String) {
        update(pkg) { c -> c.copy(c = c.c + 1, lastTs = System.currentTimeMillis()) }
    }

    suspend fun bumpAutoDismissed(
        pkg: String,
        title: String?,
        text: String?,
    ) {
        update(pkg) { c -> c.copy(a = c.a + 1, lastTs = System.currentTimeMillis()) }
        appendDismissalLog(DismissedEntry(pkg = pkg, title = title, text = text, tsMillis = System.currentTimeMillis()))
    }

    /**
     * The decision rule. v1 is simple: auto-dismiss when the user
     * has manually dismissed ≥ DISMISS_THRESHOLD notifications from
     * this pkg AND the dismissal:click ratio is ≥ DISMISS_RATIO.
     * That way one accidental dismiss doesn't trigger; we need a
     * clear pattern.
     *
     * Hard exemption: the user can mark a package as "important —
     * never auto-dismiss" through the Triage screen. That overrides
     * any learned pattern below.
     */
    suspend fun shouldAutoDismiss(pkg: String): Boolean {
        if (isExempt(pkg)) return false
        val c = counts(pkg)
        // Rule A — explicit pattern: many manual dismisses, high
        // dismiss:click ratio.
        if (c.d >= DISMISS_THRESHOLD) {
            val total = (c.d + c.c).coerceAtLeast(1)
            if (c.d.toFloat() / total >= DISMISS_RATIO) return true
        }
        // Rule B — learned latency pattern: ≥ DWELL_CONFIDENCE_MIN
        // samples AND median dwell time < DWELL_LOW_VALUE_MS. Means
        // the user has consistently swiped these away within a few
        // seconds → low subjective value → safe to auto-dismiss.
        if (c.dwellsMs.size >= DWELL_CONFIDENCE_MIN) {
            val sorted = c.dwellsMs.sorted()
            val median = sorted[sorted.size / 2]
            if (median < DWELL_LOW_VALUE_MS) return true
        }
        return false
    }

    // ─── User-managed exemption set ──────────────────────────────────
    // Apps the user has explicitly tagged as "important — never
    // auto-dismiss" from the Triage UI. Persisted as a comma-
    // separated string of package names; the in-memory shape used
    // by the rest of the codebase is a Set<String>.

    fun exemptFlow(): Flow<Set<String>> =
        ctx.dataStore.data.map { decodeExempt(it[keyExempt]) }

    suspend fun exempt(): Set<String> =
        decodeExempt(ctx.dataStore.data.first()[keyExempt])

    suspend fun isExempt(pkg: String): Boolean = pkg in exempt()

    /** Add `pkg` to the "never auto-dismiss" set. Idempotent. */
    suspend fun markImportant(pkg: String) {
        ctx.dataStore.edit { prefs ->
            val cur = decodeExempt(prefs[keyExempt])
            if (pkg !in cur) {
                prefs[keyExempt] = encodeExempt(cur + pkg)
            }
        }
    }

    /** Remove `pkg` from the exempt set so the learned-pattern
     *  decision rules apply again. Idempotent. */
    suspend fun unmarkImportant(pkg: String) {
        ctx.dataStore.edit { prefs ->
            val cur = decodeExempt(prefs[keyExempt])
            if (pkg in cur) {
                prefs[keyExempt] = encodeExempt(cur - pkg)
            }
        }
    }

    private fun decodeExempt(raw: String?): Set<String> =
        raw?.split(',')?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun encodeExempt(set: Set<String>): String = set.joinToString(",")

    suspend fun recentDismissals(limit: Int = MAX_LOG): List<DismissedEntry> {
        val raw = ctx.dataStore.data.first()[keyLog] ?: return emptyList()
        val parsed = runCatching { json.decodeFromString(Log.serializer(), raw) }.getOrNull()
            ?: return emptyList()
        return parsed.entries.takeLast(limit).reversed() // newest first
    }

    private suspend fun update(pkg: String, transform: (Counts) -> Counts) {
        ctx.dataStore.edit { prefs ->
            val k = countsKey(pkg)
            val current = prefs[k]?.let { raw ->
                runCatching { json.decodeFromString(Counts.serializer(), raw) }.getOrNull()
            } ?: Counts()
            val next = transform(current)
            prefs[k] = json.encodeToString(Counts.serializer(), next)
        }
    }

    private suspend fun appendDismissalLog(entry: DismissedEntry) {
        ctx.dataStore.edit { prefs ->
            val current = prefs[keyLog]?.let { raw ->
                runCatching { json.decodeFromString(Log.serializer(), raw) }.getOrNull()
            } ?: Log()
            val merged = (current.entries + entry).takeLast(MAX_LOG)
            prefs[keyLog] = json.encodeToString(Log.serializer(), Log(merged))
        }
    }

    companion object {
        /** Need at least this many manual dismissals before we'll auto-dismiss. */
        const val DISMISS_THRESHOLD = 3

        /** Dismissal:click ratio that triggers auto-dismiss. 0.7 means the user
         *  dismissed 7 of the last 10 they saw from this pkg. */
        const val DISMISS_RATIO = 0.7f

        /** Cap on the recent-dismissals log. */
        const val MAX_LOG = 100

        /** Sliding window size for dwell-time samples per package. */
        const val DWELL_WINDOW_SIZE = 20

        /** Minimum dwell samples before the learned-latency rule fires. */
        const val DWELL_CONFIDENCE_MIN = 6

        /** Median dwell below this milliseconds = "user instantly dismisses these". */
        const val DWELL_LOW_VALUE_MS = 3_000L
    }
}

package com.mythara.analytics

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic refresh of every contact profile via
 * [ContactAnalyticsBuilder.rebuildAll]. Runs once a day so Gemma
 * keeps up with new conversation context the user has been
 * accumulating — without the user having to tap "refresh" manually.
 *
 * Scheduling shape mirrors PersonaWorker:
 *   - 24h periodic cadence
 *   - RequiresBatteryNotLow (not urgent)
 *   - 6h initial delay so fresh installs don't fire instantly
 *     before there's any meaningful vault to analyse
 *   - ExistingPeriodicWorkPolicy.UPDATE so re-scheduling on each
 *     cold boot is idempotent
 *
 * The builder itself self-gates re-inference per contact: it only
 * runs the Gemma passes when at least 24h has passed since the last
 * update OR the sample size grew by 50%. That means the daily
 * worker isn't always paying the LLM cost — most contacts skip the
 * expensive bit and only their aggregates (counts, topics, recency)
 * get refreshed.
 */
@HiltWorker
class ContactAnalyticsWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val builder: ContactAnalyticsBuilder,
    private val selfPersonaBuilder: com.mythara.persona.SelfPersonaBuilder,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val report = runCatching { builder.rebuildAll(force = false) }.getOrElse { e ->
            Log.w(TAG, "rebuildAll threw", e)
            return Result.retry()
        }
        Log.d(
            TAG,
            "daily contact-analytics rebuild done: contacts=${report.totalContacts} " +
                "rebuilt=${report.rebuilt} skipped=${report.skippedNoData} ms=${report.durationMs}",
        )
        // Daily self-profile refresh — same cadence as contact analysis.
        // Self-gates on data + freshness, so this is cheap most days.
        runCatching { selfPersonaBuilder.rebuild() }
            .onFailure { Log.w(TAG, "self-profile rebuild failed: ${it.message}") }
        return Result.success()
    }

    companion object {
        private const val TAG = "Mythara/AnalyticsWk"
        const val UNIQUE_PERIODIC = "mythara_contact_analytics_daily"
    }
}

@Singleton
class ContactAnalyticsScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<ContactAnalyticsWorker>(Duration.ofHours(24))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(Duration.ofHours(6))
            .build()
        wm.enqueueUniquePeriodicWork(
            ContactAnalyticsWorker.UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }

    fun stop() {
        wm.cancelUniqueWork(ContactAnalyticsWorker.UNIQUE_PERIODIC)
    }
}

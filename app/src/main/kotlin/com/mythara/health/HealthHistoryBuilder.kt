package com.mythara.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pulls the user's LONG-RANGE Health Connect history (the last
 * [WINDOW_DAYS] days of whatever has already synced from Samsung
 * Health / Fitbit / the watch) and folds it into one compact
 * `kind:health-history` vault row.
 *
 * Distinct from [HealthLearningWorker], which writes a rolling 24h
 * snapshot — this is the "all-time read" the About Me screen surfaces:
 * average steps/day, resting-HR trend across the window, typical
 * sleep, weight movement, etc.
 *
 * Uses Health Connect's aggregate API (not readRecords) so a
 * six-month query stays cheap — totals/averages come back without
 * loading every sample into memory. A first-half vs second-half
 * aggregate gives the trend insights.
 *
 * Self-gating: skips when a fresh history row already exists (<20h),
 * so wiring it into the 6-hourly [HealthLearningWorker] is safe — it
 * actually runs about once a day.
 */
@Singleton
class HealthHistoryBuilder @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val vault: LearningVault,
    private val deviceIdStore: DeviceIdStore,
) {

    suspend fun build(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE) {
                Log.d(TAG, "health connect unavailable; skipping history")
                return@withContext false
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.isEmpty()) {
                Log.d(TAG, "no health permissions granted; skipping history")
                return@withContext false
            }
            if (!force && hasRecentHistory()) {
                Log.d(TAG, "fresh health-history row exists; skipping")
                return@withContext false
            }

            val now = Instant.now()
            val since = now.minus(Duration.ofDays(WINDOW_DAYS))
            val mid = now.minus(Duration.ofDays(WINDOW_DAYS / 2))
            val full = TimeRangeFilter.between(since, now)
            val firstHalf = TimeRangeFilter.between(since, mid)
            val secondHalf = TimeRangeFilter.between(mid, now)

            fun has(record: kotlin.reflect.KClass<out androidx.health.connect.client.records.Record>) =
                HealthPermission.getReadPermission(record) in granted

            val metrics = buildSet<AggregateMetric<*>> {
                if (has(StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
                if (has(HeartRateRecord::class)) {
                    add(HeartRateRecord.BPM_AVG)
                    add(HeartRateRecord.BPM_MIN)
                    add(HeartRateRecord.BPM_MAX)
                }
                if (has(SleepSessionRecord::class)) add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
                if (has(TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            }
            if (metrics.isEmpty()) {
                Log.d(TAG, "no aggregatable permissions granted; skipping history")
                return@withContext false
            }

            val agg = client.aggregate(AggregateRequest(metrics, full))
            val days = WINDOW_DAYS.toDouble()

            val snapshot = buildJsonObject {
                put("ts_ms", System.currentTimeMillis())
                put("window_days", WINDOW_DAYS)
                put("device", deviceIdStore.id())

                if (StepsRecord.COUNT_TOTAL in metrics) {
                    val total = agg[StepsRecord.COUNT_TOTAL]
                    if (total != null) {
                        put("steps_total", total)
                        put("steps_per_day_avg", (total / days).toLong())
                    }
                }
                if (HeartRateRecord.BPM_AVG in metrics) {
                    agg[HeartRateRecord.BPM_AVG]?.let { put("hr_avg", it) }
                    agg[HeartRateRecord.BPM_MIN]?.let { put("hr_min", it) }
                    agg[HeartRateRecord.BPM_MAX]?.let { put("hr_max", it) }
                    // Trend: first-half vs second-half average resting HR.
                    val firstAvg = runCatching {
                        client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), firstHalf))[HeartRateRecord.BPM_AVG]
                    }.getOrNull()
                    val secondAvg = runCatching {
                        client.aggregate(AggregateRequest(setOf(HeartRateRecord.BPM_AVG), secondHalf))[HeartRateRecord.BPM_AVG]
                    }.getOrNull()
                    if (firstAvg != null && secondAvg != null) {
                        put("hr_avg_first_half", firstAvg)
                        put("hr_avg_second_half", secondAvg)
                        put("hr_trend_delta", secondAvg - firstAvg)
                    }
                }
                if (SleepSessionRecord.SLEEP_DURATION_TOTAL in metrics) {
                    agg[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.let { dur ->
                        put("sleep_total_minutes", dur.toMinutes())
                        put("sleep_per_night_minutes_avg", (dur.toMinutes() / days).toLong())
                    }
                }
                if (TotalCaloriesBurnedRecord.ENERGY_TOTAL in metrics) {
                    agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { e ->
                        put("kcal_total", e.inKilocalories.toLong())
                        put("kcal_per_day_avg", (e.inKilocalories / days).toLong())
                    }
                }
                // Weight trend — small dataset, read directly.
                if (has(WeightRecord::class)) {
                    val weights = runCatching {
                        client.readRecords(ReadRecordsRequest(WeightRecord::class, full)).records
                            .sortedBy { it.time }
                    }.getOrDefault(emptyList())
                    if (weights.isNotEmpty()) {
                        put("weight_kg_first", weights.first().weight.inKilograms)
                        put("weight_kg_latest", weights.last().weight.inKilograms)
                        put("weight_samples", weights.size)
                    }
                }
            }

            val content = snapshot.toString()
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "health:history",
                facets = listOf(
                    "kind:health-history",
                    "topic:health",
                    "device:${deviceIdStore.id()}",
                ),
                conf = 0.95,
            )
            Log.d(TAG, "health history persisted (${content.length}B, ${WINDOW_DAYS}d window)")
            true
        }.getOrElse {
            Log.w(TAG, "health history build failed: ${it.message}")
            false
        }
    }

    private suspend fun hasRecentHistory(): Boolean = runCatching {
        val now = System.currentTimeMillis()
        vault.listByTier(Tier.Semantic, limit = 250).any { e ->
            "kind:health-history" in vault.decodeFacets(e) &&
                now - e.tsMillis < FRESH_MS
        }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "Mythara/HealthHistory"
        private const val WINDOW_DAYS = 180L
        private const val FRESH_MS = 20L * 60 * 60 * 1000 // 20h
    }
}

package com.mythara.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mythara.memory.DeviceIdStore
import com.mythara.memory.Tier
import com.mythara.secret.observe.vault.LearningVault
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Periodic Health Connect snapshot → LearningVault.
 *
 * Pulls last-24h aggregates for every health record type the user has
 * granted permission for (steps, heart-rate, sleep, calories, weight,
 * etc.) and writes one compact vault row per snapshot with
 * topic:health + device:<id> facets. The vault's existing semantic-
 * tier sync ships these as semantic/health.jsonl so peer devices
 * + the persona pipeline can mine cross-device patterns ("user
 * averages 8.2k steps weekdays / 4.1k weekends", "resting HR has
 * climbed 5 bpm this month").
 *
 * Gating:
 *  - Off by default. The Health Connect SDK silently fails when
 *    the user hasn't granted any permissions — the worker simply
 *    writes nothing in that case, no error.
 *  - Health Connect itself is a separate APK on Android < 14;
 *    HealthConnectClient.getSdkStatus is checked first and the
 *    worker no-ops when unavailable.
 *
 * Cadence: every 6h on charging — bounded battery, frequent enough
 * to track meaningful patterns within a day.
 */
@HiltWorker
class HealthLearningWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val vault: LearningVault,
    private val deviceIdStore: DeviceIdStore,
    private val historyBuilder: HealthHistoryBuilder,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        // Long-range history pull — self-gates to ~once a day, so
        // calling it on every 6-hourly run is cheap.
        runCatching { historyBuilder.build() }
            .onFailure { Log.w(TAG, "health history build failed: ${it.message}") }
        return runCatching {
            val sdkStatus = HealthConnectClient.getSdkStatus(ctx)
            if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                Log.d(TAG, "health connect not available (status=$sdkStatus); skipping")
                return Result.success()
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.isEmpty()) {
                Log.d(TAG, "no health permissions granted; skipping")
                return Result.success()
            }
            val now = Instant.now()
            val since = now.minus(Duration.ofHours(24))
            val window = TimeRangeFilter.between(since, now)

            val snapshot = buildJsonObject {
                put("ts_ms", System.currentTimeMillis())
                put("window_h", 24)
                put("device", deviceIdStore.id())

                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                    val total = client.readRecords(ReadRecordsRequest(StepsRecord::class, window))
                        .records.sumOf { it.count }
                    put("steps_24h", total)
                }
                if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                    val samples = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, window)).records
                        .flatMap { rec -> rec.samples.map { it.beatsPerMinute } }
                    if (samples.isNotEmpty()) {
                        put("hr_24h_min", samples.min())
                        put("hr_24h_max", samples.max())
                        put("hr_24h_avg", samples.average())
                        put("hr_24h_samples", samples.size)
                    }
                }
                if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                    val sleep = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, window)).records
                    val totalMin = sleep.sumOf {
                        Duration.between(it.startTime, it.endTime).toMinutes()
                    }
                    put("sleep_24h_minutes", totalMin)
                    put(
                        "sleep_sessions",
                        buildJsonArray {
                            for (s in sleep.take(5)) {
                                add(
                                    buildJsonObject {
                                        put("start_ms", s.startTime.toEpochMilli())
                                        put("end_ms", s.endTime.toEpochMilli())
                                        put("title", s.title.orEmpty())
                                    },
                                )
                            }
                        },
                    )
                }
                if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) {
                    val cals = client.readRecords(ReadRecordsRequest(TotalCaloriesBurnedRecord::class, window)).records
                        .sumOf { it.energy.inKilocalories }
                    put("kcal_24h", cals)
                }
                if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
                    val latest = client.readRecords(
                        ReadRecordsRequest(WeightRecord::class, window),
                    ).records.maxByOrNull { it.time }
                    if (latest != null) {
                        put("weight_kg", latest.weight.inKilograms)
                        put("weight_ts_ms", latest.time.toEpochMilli())
                    }
                }
            }

            val content = snapshot.toString()
            vault.add(
                content = content,
                tier = Tier.Semantic,
                src = "health:periodic-snapshot",
                facets = listOf(
                    "kind:health-snapshot",
                    "topic:health",
                    "device:${deviceIdStore.id()}",
                ),
                conf = 0.95,
            )
            Log.d(TAG, "health snapshot persisted (${content.length}B)")
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "health snapshot failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Mythara/HealthLearn"
        const val UNIQUE_NAME = "mythara_health_learning"
    }
}

@Singleton
class HealthLearningScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<HealthLearningWorker>(Duration.ofHours(6))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(20))
            .build()
        wm.enqueueUniquePeriodicWork(
            HealthLearningWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}

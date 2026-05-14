package com.mythara.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
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
import com.mythara.audit.AuditRepository
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
import kotlin.math.sqrt

/**
 * Hourly correlation worker — looks for heart-rate spikes (typically
 * sourced from the user's paired Pixel Watch / Wear OS device, which
 * pipes HR samples into Health Connect on the phone) and attributes
 * each spike to whoever pinged the user in the preceding window.
 *
 * The output is one vault row per spike with topic:hr-correlation +
 * contact:<name> facets, e.g.:
 *
 *   {"ts_ms":..., "spike_bpm":118, "baseline_bpm":78,
 *    "z_score":2.4, "candidates":[{"contact":"Boss","pkg":"slack",
 *    "lag_ms":63000}]}
 *
 * The contact-analytics builder picks these up as relationship
 * signals — "every Slack ping from Boss correlates with a +30 bpm HR
 * spike" becomes a learned fact that flavors the agent's tone in
 * auto-replies + the persona insights.
 *
 * Conservative: spikes only fire when the sample is >= 1.5σ above
 * the rolling baseline AND the deltaBpm exceeds an absolute floor
 * (so a calm 60 → 75 in normal breathing doesn't get flagged).
 *
 * Privacy: stays entirely local to the device. Synced via the same
 * semantic-tier sync as everything else, so peer devices see the
 * pattern but the raw HR samples never leave the device that
 * generated them.
 */
@HiltWorker
class HrCorrelationWorker @AssistedInject constructor(
    @Assisted private val ctx: Context,
    @Assisted params: WorkerParameters,
    private val vault: LearningVault,
    private val auditRepo: AuditRepository,
    private val deviceIdStore: DeviceIdStore,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val sdkStatus = HealthConnectClient.getSdkStatus(ctx)
            if (sdkStatus != HealthConnectClient.SDK_AVAILABLE) {
                Log.d(TAG, "health connect not available; skipping")
                return Result.success()
            }
            val client = HealthConnectClient.getOrCreate(ctx)
            val granted = client.permissionController.getGrantedPermissions()
            if (HealthPermission.getReadPermission(HeartRateRecord::class) !in granted) {
                Log.d(TAG, "HR read permission not granted; skipping")
                return Result.success()
            }
            val now = Instant.now()
            val since = now.minus(Duration.ofHours(LOOKBACK_HOURS))
            val records = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(since, now)),
            ).records
            val samples = records.flatMap { rec ->
                rec.samples.map { s -> HrSample(s.time.toEpochMilli(), s.beatsPerMinute) }
            }.sortedBy { it.tsMs }
            if (samples.size < MIN_SAMPLES) {
                Log.d(TAG, "only ${samples.size} HR samples; skipping correlation")
                return Result.success()
            }

            val baseline = samples.map { it.bpm.toDouble() }.average()
            val stddev = stddev(samples.map { it.bpm.toDouble() }, baseline)
            val threshold = (baseline + Z_THRESHOLD * stddev).coerceAtLeast(baseline + ABS_DELTA_BPM)

            // Detect spikes — local maxima above threshold, debounced
            // so we don't fire on every adjacent sample of one episode.
            val spikes = detectSpikes(samples, threshold)
            if (spikes.isEmpty()) {
                Log.d(TAG, "no HR spikes detected (baseline=$baseline, threshold=$threshold)")
                return Result.success()
            }

            // For each spike, look back in the audit log for triage /
            // auto-reply entries — those carry the contact name + pkg.
            val auditEntries = runCatching { auditRepo.dao.listRecent(limit = 1000) }
                .getOrDefault(emptyList())
                .filter { it.note?.contains("enqueue") == true || it.toolName?.startsWith("send_") == true }

            for (spike in spikes) {
                val candidates = auditEntries
                    .filter { it.tsMillis in (spike.tsMs - LOOKBACK_MS)..spike.tsMs }
                    .mapNotNull { entry ->
                        val source = entry.note ?: entry.contactName ?: ""
                        extractContact(source)?.let {
                            Candidate(it.first, it.second, spike.tsMs - entry.tsMillis)
                        }
                    }
                if (candidates.isEmpty()) continue
                val payload = buildJsonObject {
                    put("ts_ms", spike.tsMs)
                    put("spike_bpm", spike.bpm)
                    put("baseline_bpm", baseline)
                    put("z_score", (spike.bpm - baseline) / stddev.coerceAtLeast(1.0))
                    put(
                        "candidates",
                        buildJsonArray {
                            for (c in candidates.distinctBy { it.contact }.take(5)) {
                                add(
                                    buildJsonObject {
                                        put("contact", c.contact)
                                        put("pkg", c.pkg)
                                        put("lag_ms", c.lagMs)
                                    },
                                )
                            }
                        },
                    )
                }
                vault.add(
                    content = payload.toString(),
                    tier = Tier.Semantic,
                    src = "hr:notification-correlation",
                    facets = buildList {
                        add("kind:hr-correlation")
                        add("topic:hr-correlation")
                        add("device:${deviceIdStore.id()}")
                        candidates.distinctBy { it.contact }.forEach { c ->
                            add("contact:${c.contact}")
                        }
                    },
                    conf = 0.7,
                )
                Log.d(TAG, "HR spike ${spike.bpm}bpm at ${spike.tsMs} → ${candidates.size} contact candidate(s)")
            }
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "hr correlation failed: ${e.message}")
            Result.retry()
        }
    }

    private fun stddev(values: List<Double>, mean: Double): Double {
        if (values.size < 2) return 0.0
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        return sqrt(variance)
    }

    private fun detectSpikes(samples: List<HrSample>, threshold: Double): List<HrSample> {
        val spikes = mutableListOf<HrSample>()
        var lastSpikeMs = 0L
        for (s in samples) {
            if (s.bpm < threshold) continue
            if (s.tsMs - lastSpikeMs < SPIKE_DEBOUNCE_MS) continue
            spikes.add(s)
            lastSpikeMs = s.tsMs
        }
        return spikes
    }

    /**
     * Audit log details look like:
     *   "auto-reply enqueue: Mom on com.whatsapp tone=warm image=false"
     *   "auto-triage enqueue: SomeSender on com.google.android.apps.messaging ..."
     * Pull the contact name and pkg out. Returns null if the line
     * doesn't match this shape.
     */
    private fun extractContact(detail: String): Pair<String, String>? {
        val re = Regex("""(?:auto-reply|auto-triage)\s+enqueue:\s+([^\s]+)\s+on\s+(\S+)""")
        val m = re.find(detail) ?: return null
        val name = m.groupValues[1].trim()
        val pkg = m.groupValues[2].trim()
        if (name.isBlank() || pkg.isBlank()) return null
        return name to pkg
    }

    private data class HrSample(val tsMs: Long, val bpm: Long)
    private data class Candidate(val contact: String, val pkg: String, val lagMs: Long)

    companion object {
        private const val TAG = "Mythara/HrCorr"
        const val UNIQUE_NAME = "mythara_hr_correlation"
        private const val LOOKBACK_HOURS = 4L
        private const val LOOKBACK_MS = 5L * 60_000 // notif → HR spike attribution window
        private const val MIN_SAMPLES = 30
        private const val Z_THRESHOLD = 1.5
        private const val ABS_DELTA_BPM = 15.0
        private const val SPIKE_DEBOUNCE_MS = 5L * 60_000
    }
}

@Singleton
class HrCorrelationScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val wm: WorkManager get() = WorkManager.getInstance(ctx)

    fun start() {
        val req = PeriodicWorkRequestBuilder<HrCorrelationWorker>(Duration.ofHours(1))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build(),
            )
            .setInitialDelay(Duration.ofMinutes(30))
            .build()
        wm.enqueueUniquePeriodicWork(
            HrCorrelationWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            req,
        )
    }
}

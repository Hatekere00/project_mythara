package com.mythara.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord

/**
 * Shared Health Connect access surface — the read-permission set
 * Mythara wants, plus availability / granted checks. The About Me
 * screen uses [PERMISSIONS] with
 * `PermissionController.createRequestPermissionResultContract()` to
 * launch the grant flow, since the workers themselves never prompt.
 *
 * Every permission here is also declared in the manifest.
 */
object HealthAccess {

    /** Read permissions Mythara's health builders make use of. */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    /** True when the Health Connect SDK is usable on this device. */
    fun isAvailable(ctx: Context): Boolean =
        runCatching {
            HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
        }.getOrDefault(false)

    /** How many of [PERMISSIONS] the user has granted (0 when none / unavailable). */
    suspend fun grantedCount(ctx: Context): Int = runCatching {
        if (!isAvailable(ctx)) return 0
        HealthConnectClient.getOrCreate(ctx)
            .permissionController.getGrantedPermissions()
            .count { it in PERMISSIONS }
    }.getOrDefault(0)
}

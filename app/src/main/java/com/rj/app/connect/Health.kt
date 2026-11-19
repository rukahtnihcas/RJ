package com.rj.app.connect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.rj.core.HealthSnapshot
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Samsung Health and Galaxy Watch data (sleep, steps, heart rate, workouts) through Health Connect.
 * In Samsung Health: Settings → Health Connect → allow it to share data, then grant RJ read access.
 */
class HealthReader(private val context: Context) {
    val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun available(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun granted(): Set<String> = if (!available()) emptySet()
    else runCatching { client.permissionController.getGrantedPermissions() }.getOrDefault(emptySet())

    /** Whatever we're allowed to read; each part is optional. */
    suspend fun snapshot(now: ZonedDateTime = ZonedDateTime.now()): HealthSnapshot? {
        val granted = granted()
        if (granted.isEmpty()) return null
        fun can(p: String) = p in granted
        val startOfDay = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        val nowI = now.toInstant()

        val steps = if (can(HealthPermission.getReadPermission(StepsRecord::class))) runCatching {
            client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), TimeRangeFilter.between(startOfDay, nowI)))[StepsRecord.COUNT_TOTAL]
        }.getOrNull() else null

        val kcal = if (can(HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class))) runCatching {
            client.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), TimeRangeFilter.between(startOfDay, nowI)))[
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories?.toInt()
        }.getOrNull() else null

        // Last night: sleep sessions that ended since 6pm yesterday.
        val sleep = if (can(HealthPermission.getReadPermission(SleepSessionRecord::class))) runCatching {
            val from = now.toLocalDate().minusDays(1).atTime(18, 0).atZone(now.zone).toInstant()
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(from, nowI)))
                .records.fold(Duration.ZERO) { acc, r -> acc + Duration.between(r.startTime, r.endTime) }
                .takeIf { !it.isZero }
        }.getOrNull() else null

        var latestHr: Pair<Int, ZonedDateTime>? = null
        if (can(HealthPermission.getReadPermission(HeartRateRecord::class))) runCatching {
            client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(nowI.minusSeconds(3600), nowI)))
                .records.flatMap { it.samples }.maxByOrNull { it.time }
                ?.let { latestHr = it.beatsPerMinute.toInt() to it.time.atZone(now.zone) }
        }

        val resting = if (can(HealthPermission.getReadPermission(RestingHeartRateRecord::class))) runCatching {
            client.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, TimeRangeFilter.between(nowI.minus(Duration.ofDays(3)), nowI)))
                .records.maxByOrNull { it.time }?.beatsPerMinute?.toInt()
        }.getOrNull() else null

        val workouts = if (can(HealthPermission.getReadPermission(ExerciseSessionRecord::class))) runCatching {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, TimeRangeFilter.between(startOfDay, nowI)))
                .records.map { r ->
                    val mins = Duration.between(r.startTime, r.endTime).toMinutes()
                    "${r.title?.takeIf { it.isNotBlank() } ?: "workout"} ($mins min)"
                }
        }.getOrNull().orEmpty() else emptyList()

        return HealthSnapshot(
            sleepLastNight = sleep,
            stepsToday = steps,
            restingHeartRate = resting,
            latestHeartRate = latestHr?.first,
            latestHeartRateAt = latestHr?.second,
            exerciseToday = workouts,
            activeCaloriesToday = kcal,
        )
    }
}

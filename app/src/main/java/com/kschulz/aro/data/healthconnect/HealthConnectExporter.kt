package com.kschulz.aro.data.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Pressure
import com.kschulz.aro.data.ble.AroDbg
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

/**
 * Opt-in, write-only exporter from Aro's local Room database to Google Health Connect.
 * Runs only when the user enables the toggle in Settings and grants Health Connect permissions.
 *
 * Uses deterministic `clientRecordId` values so re-exporting the same day updates in place
 * without duplicating records in Health Connect.
 */
@Singleton
class HealthConnectExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    /** True when the Health Connect SDK is installed and available on this device. */
    fun isAvailable(): Boolean =
        runCatching {
            HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
        }.getOrDefault(false)

    /** True when all required write permissions have been granted in Health Connect. */
    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            REQUIRED_PERMISSIONS.all { it in granted }
        }.getOrDefault(false)
    }

    /** Pure domain mapping used by unit tests. */
    suspend fun buildRecords(profileId: Long, days: Int): List<ExportRecord> {
        val toEpochDay = time.todayEpochDay()
        val fromEpochDay = toEpochDay - (days - 1).toLong()
        val fromMs = fromEpochDay * 86_400_000L
        val toMs = (toEpochDay + 1) * 86_400_000L

        val steps = health.stepsFor(profileId, fromEpochDay, toEpochDay)
        val hr = health.heartRateFor(profileId, fromMs, toMs)
        val sleep = health.sleepSessionsFor(profileId, fromMs, toMs)

        return buildList {
            steps.forEach { s ->
                add(ExportRecord.Steps(s.date, s.steps, s.distanceMeters, s.calories))
            }
            hr.forEach { h ->
                add(ExportRecord.HeartRate(h.epochMs, h.bpm))
            }
            sleep.forEach { sl ->
                add(ExportRecord.Sleep(sl.startEpoch, sl.endEpoch, sl.isNap))
            }
        }
    }

    /**
     * Export the active profile's recent health data to Google Health Connect.
     * Returns the number of Health Connect records written, or 0 if disabled/unavailable/unpermitted.
     */
    suspend fun write(profileId: Long, days: Int = 7): Int {
        if (settings.observeHealthConnectEnabled().firstOrNull() != true) return 0
        if (!isAvailable()) return 0

        val client = runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() ?: return 0
        val granted = runCatching { client.permissionController.getGrantedPermissions() }.getOrNull() ?: return 0

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(time.nowMillis())
        val version = time.nowMillis()
        val toEpochDay = time.todayEpochDay()
        val fromEpochDay = toEpochDay - (days - 1).toLong()
        val fromMs = fromEpochDay * 86_400_000L
        val toMs = (toEpochDay + 1) * 86_400_000L

        val records = mutableListOf<Record>()

        // 1. Steps, Distance, and Calories
        val stepDays = health.stepsFor(profileId, fromEpochDay, toEpochDay)
        val canWriteSteps = HealthPermission.getWritePermission(StepsRecord::class) in granted
        val canWriteDistance = HealthPermission.getWritePermission(DistanceRecord::class) in granted
        val canWriteCalories = HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class) in granted

        for (day in stepDays) {
            val localDate = LocalDate.ofEpochDay(day.date)
            val start = localDate.atStartOfDay(zone).toInstant()
            val endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant().minusSeconds(1)
            val end = if (endOfDay.isAfter(now)) now else endOfDay
            if (!end.isAfter(start)) continue
            val startOffset = zone.rules.getOffset(start)
            val endOffset = zone.rules.getOffset(end)

            if (canWriteSteps && day.steps > 0) {
                records.add(
                    StepsRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        count = day.steps.toLong(),
                        metadata = Metadata(
                            clientRecordId = "aro-steps-$profileId-${day.date}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
            if (canWriteDistance && day.distanceMeters > 0) {
                records.add(
                    DistanceRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        distance = Length.meters(day.distanceMeters.toDouble()),
                        metadata = Metadata(
                            clientRecordId = "aro-dist-$profileId-${day.date}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
            if (canWriteCalories && day.calories > 0) {
                records.add(
                    TotalCaloriesBurnedRecord(
                        startTime = start,
                        startZoneOffset = startOffset,
                        endTime = end,
                        endZoneOffset = endOffset,
                        energy = Energy.kilocalories(day.calories.toDouble()),
                        metadata = Metadata(
                            clientRecordId = "aro-cal-$profileId-${day.date}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
        }

        // 2. Heart Rate (grouped by day into a single HeartRateRecord series per day)
        if (HealthPermission.getWritePermission(HeartRateRecord::class) in granted) {
            val hrSamples = health.heartRateFor(profileId, fromMs, toMs).filter { it.bpm in 25..240 }
            val byDay = hrSamples.groupBy { Instant.ofEpochMilli(it.epochMs).atZone(zone).toLocalDate().toEpochDay() }
            for ((epochDay, list) in byDay) {
                val sorted = list.sortedBy { it.epochMs }
                val start = Instant.ofEpochMilli(sorted.first().epochMs)
                val lastInstant = Instant.ofEpochMilli(sorted.last().epochMs)
                val end = if (lastInstant.isAfter(start)) lastInstant else start.plusSeconds(1)
                records.add(
                    HeartRateRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = end,
                        endZoneOffset = zone.rules.getOffset(end),
                        samples = sorted.map {
                            HeartRateRecord.Sample(
                                time = Instant.ofEpochMilli(it.epochMs),
                                beatsPerMinute = it.bpm.toLong(),
                            )
                        },
                        metadata = Metadata(
                            clientRecordId = "aro-hr-$profileId-$epochDay",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
        }

        // 3. Sleep sessions + stage segments
        if (HealthPermission.getWritePermission(SleepSessionRecord::class) in granted) {
            val sessions = health.sleepSessionsFor(profileId, fromMs, toMs)
            for (session in sessions) {
                val start = Instant.ofEpochMilli(session.startEpoch)
                val end = Instant.ofEpochMilli(session.endEpoch)
                if (!end.isAfter(start)) continue
                val segments = health.sleepSegmentsFor(session.id)
                val stages = mutableListOf<SleepSessionRecord.Stage>()
                var cursor = start
                for (seg in segments) {
                    if (seg.durationMin <= 0) continue
                    val segEnd = cursor.plusSeconds(seg.durationMin * 60L)
                    val clampedEnd = if (segEnd.isAfter(end)) end else segEnd
                    if (!clampedEnd.isAfter(cursor)) break
                    val hcStage = when (seg.stage) {
                        1 -> SleepSessionRecord.STAGE_TYPE_DEEP
                        2 -> SleepSessionRecord.STAGE_TYPE_LIGHT
                        3 -> SleepSessionRecord.STAGE_TYPE_REM
                        else -> SleepSessionRecord.STAGE_TYPE_AWAKE
                    }
                    stages.add(SleepSessionRecord.Stage(cursor, clampedEnd, hcStage))
                    cursor = clampedEnd
                }
                records.add(
                    SleepSessionRecord(
                        startTime = start,
                        startZoneOffset = zone.rules.getOffset(start),
                        endTime = end,
                        endZoneOffset = zone.rules.getOffset(end),
                        stages = stages,
                        metadata = Metadata(
                            clientRecordId = "aro-sleep-$profileId-${session.startEpoch}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
        }

        // 4. Blood Pressure
        if (HealthPermission.getWritePermission(BloodPressureRecord::class) in granted) {
            val bps = health.bloodPressureFor(profileId, fromMs, toMs)
            for (bp in bps) {
                if (bp.sbp !in 60..260 || bp.dbp !in 30..180) continue
                val instant = Instant.ofEpochMilli(bp.epochMs)
                records.add(
                    BloodPressureRecord(
                        time = instant,
                        zoneOffset = zone.rules.getOffset(instant),
                        systolic = Pressure.millimetersOfMercury(bp.sbp.toDouble()),
                        diastolic = Pressure.millimetersOfMercury(bp.dbp.toDouble()),
                        metadata = Metadata(
                            clientRecordId = "aro-bp-$profileId-${bp.epochMs}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
        }

        // 5. Blood Oxygen (SpO2)
        if (HealthPermission.getWritePermission(OxygenSaturationRecord::class) in granted) {
            val spo2s = health.spo2For(profileId, fromMs, toMs)
            for (sample in spo2s) {
                if (sample.percent !in 50..100) continue
                val instant = Instant.ofEpochMilli(sample.epochMs)
                records.add(
                    OxygenSaturationRecord(
                        time = instant,
                        zoneOffset = zone.rules.getOffset(instant),
                        percentage = Percentage(sample.percent.toDouble()),
                        metadata = Metadata(
                            clientRecordId = "aro-spo2-$profileId-${sample.epochMs}",
                            clientRecordVersion = version,
                        ),
                    ),
                )
            }
        }

        if (records.isEmpty()) return 0

        var written = 0
        // Health Connect accepts up to ~1000 records per insertRecords call; chunk safely.
        for (chunk in records.chunked(200)) {
            runCatching {
                client.insertRecords(chunk)
                written += chunk.size
            }.onFailure { e ->
                AroDbg.d("HealthConnect insertRecords failed: ${e.message}")
            }
        }
        AroDbg.d("HealthConnect exported $written record(s)")
        return written
    }

    companion object {
        val REQUIRED_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getWritePermission(StepsRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(SleepSessionRecord::class),
            HealthPermission.getWritePermission(BloodPressureRecord::class),
            HealthPermission.getWritePermission(OxygenSaturationRecord::class),
        )
    }
}

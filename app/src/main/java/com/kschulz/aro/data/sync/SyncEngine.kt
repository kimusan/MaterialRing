package com.kschulz.aro.data.sync

import com.kschulz.aro.data.ble.ByteOrder
import com.kschulz.aro.data.ble.AroDbg
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingCommand
import com.kschulz.aro.data.ble.RingPayloads
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.HealthStore
import com.kschulz.aro.domain.BodyBattery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The resilient pull. Driven by the 0x46 distribution bitmap: query it, pull only the
 * days it marks unsynced, upsert by composite PK, re-query at the end to confirm the
 * bitmap cleared. A dropped connection mid-run is not a failure mode because the next run
 * simply re-queries 0x46 and re-pulls, and the upsert key makes any re-pull a no-op.
 *
 * Depends only on [RingCommand], [TimeSource], [HealthStore], and [BodyBattery], so the
 * whole loop is unit-testable with fakes (no GATT, no clock, no Room).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val command: RingCommand,
    private val health: HealthStore,
    private val bodyBattery: BodyBattery,
) {
    /**
     * @param profileId the profile to write into (resolved by the caller from the active
     * profile, so a sync always lands in the profile the user is on).
     * @param profile optional active [Profile]; when present, its anthropometric attributes
     * and activity/sleep goals are pushed to the ring via opcodes 0x0A and 0x21.
     */
    suspend fun sync(
        profileId: Long,
        time: TimeSource,
        profile: Profile? = null,
        useMetric: Boolean = true,
    ): SyncResult {
        if (!command.isConnected) return SyncResult(connected = false)

        // The vendor's post-bind init chain sends SET_DEVICE_TIME (0x01) first, before any
        // data query; the ring answers history pulls only once it has a clock. Do the same
        // here so the 0x46 distribution query below is not answered with nothing.
        sendTimeBaseline(time)
        if (profile != null) {
            syncProfileSettings(profile, time, useMetric)
        }

        val distribution = queryDistribution()
        val days = if (distribution != null) DistributionBitmap.daysToPull(distribution)
        else DistributionBitmap.recentWindow()
        com.kschulz.aro.data.ble.AroDbg.d("sync start: distribution=${distribution?.toString(16)} days=${days.sorted()}")

        val today = time.todayEpochDay()
        var steps = 0
        var bpReadings = 0
        var hrSamples = 0
        var sleepSessions = 0

        for (daysAgo in days) {
            val epochDay = time.epochDayForOffset(daysAgo)
            steps += pullStepsForDay(profileId, epochDay, daysAgo).size
            hrSamples += pullHeartRate(profileId, daysAgo).size
            sleepSessions += pullSleep(profileId, epochDay, daysAgo).size
        }

        // Blood pressure queries (0x0D auto-BP and 0x14 manual-BP) return buffered readings
        // across days in one stream rather than taking a dayOffset.
        bpReadings += pullAutoBp(profileId, time, profile).size
        bpReadings += pullManualBp(profileId).size

        // Body battery is derived from what we just stored; recompute for the window.
        val windowStartHour = time.epochDayForOffset(days.maxOfOrNull { it } ?: 0) * 24
        val windowEndHour = today * 24 + 23
        runCatching {
            bodyBattery.recompute(profileId, windowStartHour, windowEndHour)
        }

        // Confirm the bitmap cleared; report whether it did (best-effort).
        val finalDistribution = queryDistribution()
        val confirmed = finalDistribution == null || DistributionBitmap.isComplete(finalDistribution)
        com.kschulz.aro.data.ble.AroDbg.d(
            "sync done: steps=$steps bp=$bpReadings hr=$hrSamples sleep=$sleepSessions " +
                "finalDistribution=${finalDistribution?.toString(16)} cleared=$confirmed",
        )

        return SyncResult(
            connected = true,
            daysSynced = days.size,
            steps = steps,
            bpReadings = bpReadings,
            hrSamples = hrSamples,
            sleepSessions = sleepSessions,
            distributionCleared = confirmed,
        )
    }

    /**
     * Push user profile personalization (0x0A), target goals (0x21), and 24h auto-BP
     * monitoring schedule (0x0C) to the connected ring.
     */
    suspend fun syncProfileSettings(
        profile: Profile,
        time: TimeSource,
        useMetric: Boolean = true,
    ) {
        if (!command.isConnected) return
        val currentYear = time.localClock().year
        val timeSettingPayload = RingPayloads.buildTimeSettingPayload(
            gender = profile.gender,
            age = profile.age(currentYear),
            heightCm = profile.heightCm,
            weightKg = profile.weightKg,
            sbpBaseline = profile.sbpBaseline,
            dbpBaseline = profile.dbpBaseline,
            useMetric = useMetric,
        )
        command.request(Opcodes.GET_TIME_SETTING, timeSettingPayload)

        val targetPayload = RingPayloads.buildTargetSettingPayload(
            goalSteps = profile.goalSteps,
            goalCalories = profile.goalCalories,
            goalDistanceMeters = profile.goalDistanceMeters,
            goalSleepMinutes = profile.goalSleepMinutes,
        )
        command.request(Opcodes.TARGET_SETTING, targetPayload)

        val autoBpSwitch = RingPayloads.buildAutoBpSwitchPayload(enabled = true, intervalMinutes = 60)
        command.request(Opcodes.BP_TIMING_MONITOR_SWITCH, autoBpSwitch)
    }

    private suspend fun queryDistribution(): Int? {
        val rsp = command.request(Opcodes.QUERY_DATA_DISTRIBUTION) ?: return null
        return DistributionBitmap.parse(rsp.payload)
    }

    private suspend fun sendTimeBaseline(time: TimeSource) {
        val c = time.localClock()
        val payload = RingPayloads.buildSetTimePayload(
            c.year, c.month, c.day, c.hour, c.minute, c.second,
        )
        AroDbg.d(
            "set time baseline ${c.year}-${c.month}-${c.day} " +
                "${c.hour}:${c.minute}:${c.second} ${AroDbg.hex(payload)}",
        )
        command.request(Opcodes.SET_DEVICE_TIME, payload)
    }

    private suspend fun pullStepsForDay(profileId: Long, epochDay: Long, daysAgo: Int): List<StepDay> {
        val cmd = if (daysAgo == 0) Opcodes.GET_STEP_TODAY else Opcodes.GET_STEP_TOTAL_SOMEDAY
        val payload = if (daysAgo == 0) null else ByteOrder.toLeBytes(daysAgo, 4)
        val rsp = command.request(cmd, payload) ?: return emptyList()
        val day = RingPayloads.parseStepToday(rsp.payload, epochDay) ?: return emptyList()
        val stored = day.copy(profileId = profileId)
        health.upsertSteps(profileId, listOf(stored))
        return listOf(stored)
    }

    private suspend fun pullAutoBp(
        profileId: Long,
        time: TimeSource,
        profile: Profile?,
    ): List<BloodPressure> {
        var expectedSlots = -1
        var receivedValues = 0
        val packets = command.collectUntil(
            cmd = Opcodes.BP_TIMING_MONITOR_DATA,
            payload = null,
            shouldStop = { frame ->
                val p = frame.payload
                if (RingPayloads.isAutoBpEndPacket(p)) {
                    true
                } else {
                    val type = p[0].toInt() and 0xFF
                    if (type == 0x00 && p.size >= 11) {
                        var bits = 0
                        for (i in 5..10) {
                            bits += Integer.bitCount(p[i].toInt() and 0xFF)
                        }
                        expectedSlots = bits
                        expectedSlots <= 0
                    } else if (type == 0x01 && p.size >= 2) {
                        receivedValues += (p.size - 1)
                        expectedSlots > 0 && receivedValues >= expectedSlots
                    } else {
                        false
                    }
                }
            },
            maxPackets = 12,
        )
        if (packets.isEmpty()) return emptyList()

        val currentYear = time.localClock().year
        val parsed = RingPayloads.parseAutoBpStream(
            packets = packets.map { it.payload },
            dayStartEpochMsForDate = { y, m, d ->
                runCatching {
                    java.time.LocalDate.of(y, m, d).toEpochDay() * 86_400_000L
                }.getOrDefault(time.todayEpochDay() * 86_400_000L)
            },
            age = profile?.age(currentYear) ?: 30,
            sbpBaseline = profile?.sbpBaseline ?: Profile.DEFAULT_SBP_BASELINE,
            dbpBaseline = profile?.dbpBaseline ?: Profile.DEFAULT_DBP_BASELINE,
        )
        if (parsed.isEmpty()) return emptyList()

        val readings = parsed.map {
            BloodPressure(profileId, it.epochMs, it.sbp, it.dbp, BpSource.AUTO)
        }
        health.upsertBloodPressure(profileId, readings)
        // Acknowledge receipt (0x0E BpReadConformReq) so the ring clears its staging buffer.
        command.request(Opcodes.BP_TIMING_MONITOR_CONFIRM, RingPayloads.buildAutoBpConfirmPayload(true))
        return readings
    }

    private suspend fun pullManualBp(profileId: Long): List<BloodPressure> {
        // 0x14 request (`ReadPressureReq(0L)` in vendor BloodPressureRepository):
        // 4-byte LE timestamp = 0 | 0x00 | 0x32 (max 50 records).
        val req = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x32.toByte())

        val packets = command.collectUntil(
            cmd = Opcodes.GET_BAND_PRESSURE,
            payload = req,
            shouldStop = { RingPayloads.isManualBpSentinel(it.payload) },
            maxPackets = 50,
        )
        val readings = packets
            .mapNotNull { RingPayloads.parseManualBpPacket(it.payload) }
            .map { BloodPressure(profileId, it.epochMs, it.sbp, it.dbp, BpSource.MANUAL) }
        if (readings.isNotEmpty()) health.upsertBloodPressure(profileId, readings)
        return readings
    }

    private suspend fun pullHeartRate(profileId: Long, daysAgo: Int): List<HeartRateSample> {
        // 0x15 request carries a day offset; the body is a run of 13-byte records.
        val rsp = command.request(Opcodes.GET_HEART_RATE, ByteOrder.toLeBytes(daysAgo, 4)) ?: return emptyList()
        val payload = rsp.payload
        val count = payload.size / 13
        val samples = (0 until count).mapNotNull { i ->
            RingPayloads.parseHeartRateRecord(payload.copyOfRange(i * 13, i * 13 + 13))
                ?.let { HeartRateSample(profileId, it.epochMs, it.bpm) }
        }
        if (samples.isNotEmpty()) health.upsertHeartRate(profileId, samples)
        return samples
    }

    private suspend fun pullSleep(profileId: Long, epochDay: Long, daysAgo: Int): List<SleepSession> {
        // Request mirrors the vendor: [dayOffset, 0x0F, startIdx=0, endIdx=0x5F] (full slot
        // range in one shot). The device answers a 0x44 record; parseSleep decodes the
        // first (main) record. daysAgo is capped by the ring (it rejects offsets > 29).
        if (daysAgo > 29) return emptyList()
        val req = byteArrayOf(daysAgo.toByte(), 0x0F, 0x00, 0x5F.toByte())
        val rsp = command.request(Opcodes.GET_SLEEP, req) ?: return emptyList()
        val parsed = RingPayloads.parseSleep(rsp.payload, epochDay) ?: return emptyList()
        val session = SleepSession(
            profileId = profileId,
            startEpoch = parsed.startEpochMs,
            endEpoch = parsed.endEpochMs,
            isNap = parsed.isNap,
        )
        val segments = parsed.stages.map {
            SleepSegment(sessionId = 0, stage = it.stage, durationMin = it.durationMin)
        }
        health.upsertSleep(profileId, session, segments)
        return listOf(session)
    }
}

data class SyncResult(
    val connected: Boolean,
    val daysSynced: Int = 0,
    val steps: Int = 0,
    val bpReadings: Int = 0,
    val hrSamples: Int = 0,
    val sleepSessions: Int = 0,
    val distributionCleared: Boolean = false,
)

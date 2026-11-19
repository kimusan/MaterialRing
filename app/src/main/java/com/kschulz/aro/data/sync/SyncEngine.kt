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
        val caps = sendTimeBaseline(time)
        if (profile != null) {
            syncProfileSettings(profile, time, useMetric)
        }

        val distribution = queryDistribution()
        val days = if (distribution != null) DistributionBitmap.daysToPull(distribution)
        else DistributionBitmap.recentWindow()
        com.kschulz.aro.data.ble.AroDbg.d(
            "sync start: distribution=${distribution?.toString(16)} days=${days.sorted()} " +
                "newSleep=${caps.newSleepProtocol} manualBp=${caps.manualBpHistory}",
        )

        val today = time.todayEpochDay()
        var steps = 0
        var bpReadings = 0
        var hrSamples = 0
        var sleepSessions = 0

        for (daysAgo in days) {
            val epochDay = time.epochDayForOffset(daysAgo)
            steps += pullStepsForDay(profileId, epochDay, daysAgo).size
            hrSamples += pullHeartRate(profileId, epochDay, daysAgo, time).size
            if (!caps.newSleepProtocol) {
                sleepSessions += pullSleep(profileId, epochDay, daysAgo).size
            }
        }

        if (caps.newSleepProtocol) {
            sleepSessions += pullNewSleepProtocol(profileId, today).size
        }

        // Blood pressure queries (0x0D auto-BP and 0x14 manual-BP) return buffered readings
        // across days in one stream rather than taking a dayOffset.
        bpReadings += pullAutoBp(profileId, time, profile).size
        if (caps.manualBpHistory) {
            bpReadings += pullManualBp(profileId).size
        }

        // Body battery is derived from what we just stored; recompute for the window.
        val windowStartHour = time.epochDayForOffset(days.maxOfOrNull { it } ?: 0) * 24
        val windowEndHour = today * 24 + 23
        runCatching {
            bodyBattery.recompute(profileId, windowStartHour, windowEndHour)
        }

        // Confirm the bitmap cleared (only when 0x46 is supported by this ring).
        val finalDistribution = if (distribution != null) queryDistribution() else null
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

    /** Fast incremental refresh for today's steps (`0x48`), triggered on `0x73` dataType `0x04`. */
    suspend fun syncTodaySteps(profileId: Long, time: TimeSource): Int {
        if (!command.isConnected) return 0
        return pullStepsForDay(profileId, time.todayEpochDay(), 0).size
    }

    /** Fast incremental refresh for today's HR (`0x15`), triggered on `0x73` dataType `0x01`. */
    suspend fun syncTodayHeartRate(profileId: Long, time: TimeSource): Int {
        if (!command.isConnected) return 0
        return pullHeartRate(profileId, time.todayEpochDay(), 0, time).size
    }

    /** Fast incremental refresh for auto-BP (`0x0D`), triggered on `0x73` dataType `0x02`. */
    suspend fun syncAutoBp(profileId: Long, time: TimeSource, profile: Profile?): Int {
        if (!command.isConnected) return 0
        return pullAutoBp(profileId, time, profile).size
    }

    /**
     * Push user profile personalization (0x0A), target goals (0x21), 24h auto-HR schedule (0x16),
     * and 24h auto-BP monitoring schedule (0x0C) to the connected ring.
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

        val autoHrSwitch = RingPayloads.buildAutoHrSwitchPayload(enabled = true, intervalMinutes = 30)
        command.request(Opcodes.HR_TIMING_MONITOR_SWITCH, autoHrSwitch)

        val autoBpSwitch = RingPayloads.buildAutoBpSwitchPayload(enabled = true, intervalMinutes = 60)
        command.request(Opcodes.BP_TIMING_MONITOR_SWITCH, autoBpSwitch)
    }

    private suspend fun queryDistribution(): Int? {
        val rsp = command.request(Opcodes.QUERY_DATA_DISTRIBUTION) ?: return null
        if (rsp.error) return null
        return DistributionBitmap.parse(rsp.payload)
    }

    private data class RingCapabilities(
        val newSleepProtocol: Boolean = false,
        val manualBpHistory: Boolean = true,
    )

    private suspend fun sendTimeBaseline(time: TimeSource): RingCapabilities {
        val c = time.localClock()
        val payload = RingPayloads.buildSetTimePayload(
            c.year, c.month, c.day, c.hour, c.minute, c.second,
        )
        AroDbg.d(
            "set time baseline ${c.year}-${c.month}-${c.day} " +
                "${c.hour}:${c.minute}:${c.second} ${AroDbg.hex(payload)}",
        )
        val rsp = command.request(Opcodes.SET_DEVICE_TIME, payload) ?: return RingCapabilities()
        if (rsp.error) return RingCapabilities()
        return RingCapabilities(
            newSleepProtocol = RingPayloads.isNewSleepProtocolSupported(rsp.payload),
            manualBpHistory = RingPayloads.isManualBpHistorySupported(rsp.payload),
        )
    }

    private suspend fun pullStepsForDay(profileId: Long, epochDay: Long, daysAgo: Int): List<StepDay> {
        val day = if (daysAgo == 0) {
            val rsp = command.request(Opcodes.GET_STEP_TODAY, null) ?: return emptyList()
            if (rsp.error) return emptyList()
            RingPayloads.parseStepToday(rsp.payload, epochDay)
        } else {
            val detailReq = RingPayloads.buildDetailSportQueryPayload(daysAgo)
            val detailFrames = command.collectUntil(
                cmd = Opcodes.GET_STEP_SOMEDAY_DETAIL,
                payload = detailReq,
                shouldStop = { it.error || RingPayloads.isDetailSportEndPacket(it.payload) },
                maxPackets = 98,
                timeoutMs = STREAM_TIMEOUT_MS,
            )
            if (detailFrames.isNotEmpty() && detailFrames.none { it.error }) {
                RingPayloads.parseDetailSportPackets(detailFrames.map { it.payload }, epochDay)
            } else {
                val req = byteArrayOf(daysAgo.toByte())
                val totalFrames = command.collectUntil(
                    cmd = Opcodes.GET_STEP_TOTAL_SOMEDAY,
                    payload = req,
                    shouldStop = { it.error || RingPayloads.isTotalSportEndPacket(it.payload) },
                    maxPackets = 2,
                    timeoutMs = STREAM_TIMEOUT_MS,
                )
                val packets = if (totalFrames.isEmpty()) {
                    listOfNotNull(
                        command.request(Opcodes.GET_STEP_TOTAL_SOMEDAY, req, STREAM_TIMEOUT_MS)
                            ?.takeIf { !it.error }
                            ?.payload,
                    )
                } else {
                    totalFrames.filter { !it.error }.map { it.payload }
                }
                RingPayloads.parseTotalSportPackets(packets, epochDay)
            }
        } ?: return emptyList()
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
                if (frame.error) return@collectUntil true
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
            timeoutMs = STREAM_TIMEOUT_MS,
        ).filter { !it.error }
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
            shouldStop = { it.error || RingPayloads.isManualBpSentinel(it.payload) },
            maxPackets = 50,
            timeoutMs = 1_500L,
        ).filter { !it.error }
        val readings = packets
            .mapNotNull { RingPayloads.parseManualBpPacket(it.payload) }
            .map { BloodPressure(profileId, it.epochMs, it.sbp, it.dbp, BpSource.MANUAL) }
        if (readings.isNotEmpty()) health.upsertBloodPressure(profileId, readings)
        return readings
    }

    private suspend fun pullHeartRate(
        profileId: Long,
        epochDay: Long,
        daysAgo: Int,
        time: TimeSource,
    ): List<HeartRateSample> {
        val req = if (daysAgo == 0) {
            val nowMs = time.nowMillis()
            val tzOffsetMs = java.util.TimeZone.getDefault().getOffset(nowMs)
            RingPayloads.buildHeartRateQueryPayloadForToday(nowMs, tzOffsetMs)
        } else {
            RingPayloads.buildHeartRateQueryPayload(epochDay)
        }
        var expectedPackets = -1
        val frames = command.collectUntil(
            cmd = Opcodes.GET_HEART_RATE,
            payload = req,
            shouldStop = { frame ->
                if (frame.error) return@collectUntil true
                val p = frame.payload
                if (p.size >= 2 && (p[0].toInt() and 0xFF) == 0x00) {
                    expectedPackets = p[1].toInt() and 0xFF
                }
                RingPayloads.isHeartRateEndPacket(p, expectedPackets, isToday = daysAgo == 0)
            },
            maxPackets = 26,
            timeoutMs = STREAM_TIMEOUT_MS,
        )
        val packets = if (frames.isEmpty()) {
            listOfNotNull(
                command.request(Opcodes.GET_HEART_RATE, req, STREAM_TIMEOUT_MS)
                    ?.takeIf { !it.error }
                    ?.payload,
            )
        } else {
            frames.filter { !it.error }.map { it.payload }
        }
        if (packets.isEmpty()) return emptyList()
        val dayStartMs = epochDay * 86_400_000L
        val samples = RingPayloads.parseHeartRateStream(packets, dayStartMs)
            .map { HeartRateSample(profileId, it.epochMs, it.bpm) }
        if (samples.isNotEmpty()) health.upsertHeartRate(profileId, samples)
        return samples
    }

    private suspend fun pullNewSleepProtocol(
        profileId: Long,
        todayEpochDay: Long,
    ): List<SleepSession> {
        // `LargeDataHandler.syncSleepList(dayOffset = -1)` (`0x27`, payload `[0xFF, 0x01]`).
        val req = byteArrayOf(0xFF.toByte(), 0x01)
        val rsp = command.request(Opcodes.NEW_SLEEP_PROTOCOL, req, STREAM_TIMEOUT_MS) ?: return emptyList()
        if (rsp.error) return emptyList()
        val parsedList = RingPayloads.parseNewSleepProtocolPayload(rsp.payload, todayEpochDay)
        val sessions = mutableListOf<SleepSession>()
        for (parsed in parsedList) {
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
            sessions.add(session)
        }
        return sessions
    }

    private suspend fun pullSleep(
        profileId: Long,
        epochDay: Long,
        daysAgo: Int,
    ): List<SleepSession> {
        // Request mirrors the vendor `ReadSleepDetailsReq(daysAgo, 0, 0x5F)`:
        // `[dayOffset, 0x0F, startIdx=0, endIdx=0x5F]`.
        if (daysAgo > 29) return emptyList()
        val req = byteArrayOf(daysAgo.toByte(), 0x0F, 0x00, 0x5F.toByte())
        val frames = command.collectUntil(
            cmd = Opcodes.GET_SLEEP,
            payload = req,
            shouldStop = { it.error || RingPayloads.isSleepDetailsEndPacket(it.payload) },
            maxPackets = 98,
            timeoutMs = STREAM_TIMEOUT_MS,
        )
        val packets = if (frames.isEmpty()) {
            listOfNotNull(
                command.request(Opcodes.GET_SLEEP, req, STREAM_TIMEOUT_MS)
                    ?.takeIf { !it.error }
                    ?.payload,
            )
        } else {
            frames.filter { !it.error }.map { it.payload }
        }
        if (packets.isEmpty()) return emptyList()
        val parsed = RingPayloads.parseSleepDetailsStream(
            packets = packets,
            dayStartEpochMsForDate = { y, m, d ->
                runCatching {
                    java.time.LocalDate.of(y, m, d).toEpochDay() * 86_400_000L
                }.getOrDefault(epochDay * 86_400_000L)
            },
            fallbackEpochDay = epochDay,
        ) ?: return emptyList()
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

    private companion object {
        const val STREAM_TIMEOUT_MS = 2_500L
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

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
        hrIntervalMinutes: Int = 30,
        bpIntervalMinutes: Int = 60,
        spo2IntervalMinutes: Int = 60,
    ): SyncResult {
        if (!command.isConnected) return SyncResult(connected = false)
        if (command.activeProtocol == com.kschulz.aro.data.ble.RingProtocol.JRING) {
            return syncJRing(
                profileId = profileId,
                time = time,
                profile = profile,
                hrIntervalMinutes = hrIntervalMinutes,
            )
        }

        // The vendor's post-bind init chain sends SET_DEVICE_TIME (0x01) first, before any
        // data query; the ring answers history pulls only once it has a clock. Do the same
        // here so the 0x46 distribution query below is not answered with nothing.
        val caps = sendTimeBaseline(time)
        if (profile != null) {
            syncProfileSettings(
                profile = profile,
                time = time,
                useMetric = useMetric,
                hrIntervalMinutes = hrIntervalMinutes,
                bpIntervalMinutes = bpIntervalMinutes,
                spo2IntervalMinutes = spo2IntervalMinutes,
            )
        } else {
            applyMonitoringIntervals(hrIntervalMinutes, bpIntervalMinutes, spo2IntervalMinutes)
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

        // Blood oxygen (0xBC 0x2A auto-SpO2 big data across days).
        val spo2Samples = pullAutoSpo2(profileId, today).size

        // Body battery is derived from what we just stored; recompute up to the current hour.
        val windowStartHour = time.epochDayForOffset(days.maxOfOrNull { it } ?: 0) * 24
        val nowHour = time.nowMillis() / 3_600_000L
        val windowEndHour = minOf(today * 24 + 23, nowHour)
        runCatching {
            bodyBattery.recompute(profileId, windowStartHour, windowEndHour)
        }

        // Confirm the bitmap cleared (only when 0x46 is supported by this ring).
        val finalDistribution = if (distribution != null) queryDistribution() else null
        val confirmed = finalDistribution == null || DistributionBitmap.isComplete(finalDistribution)
        com.kschulz.aro.data.ble.AroDbg.d(
            "sync done: steps=$steps bp=$bpReadings hr=$hrSamples sleep=$sleepSessions spo2=$spo2Samples " +
                "finalDistribution=${finalDistribution?.toString(16)} cleared=$confirmed",
        )

        return SyncResult(
            connected = true,
            daysSynced = days.size,
            steps = steps,
            bpReadings = bpReadings,
            hrSamples = hrSamples,
            sleepSessions = sleepSessions,
            spo2Samples = spo2Samples,
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

    /** Fast incremental refresh for auto-SpO2 (`0xBC 0x2A`), triggered on `0x73` dataType `0x03`. */
    suspend fun syncAutoSpo2(profileId: Long, time: TimeSource): Int {
        if (!command.isConnected) return 0
        return pullAutoSpo2(profileId, time.todayEpochDay()).size
    }

    /**
     * Push user profile personalization (0x0A), target goals (0x21), 24h auto-HR schedule (0x16),
     * 24h auto-BP schedule (0x0C), and auto-SpO2 monitoring (0x2C) to the connected ring.
     */
    suspend fun syncProfileSettings(
        profile: Profile,
        time: TimeSource,
        useMetric: Boolean = true,
        hrIntervalMinutes: Int = 30,
        bpIntervalMinutes: Int = 60,
        spo2IntervalMinutes: Int = 60,
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

        applyMonitoringIntervals(hrIntervalMinutes, bpIntervalMinutes, spo2IntervalMinutes)
    }

    /**
     * Push continuous/automatic measurement intervals directly to the connected ring:
     *   - 0x16 HR timing monitor (0 = off, else 10/15/30/60 min)
     *   - 0x0C BP timing monitor (0 = off, else 30/60/120 min)
     *   - 0x2C SpO2 timing monitor (0 = off, else 30/60 min)
     */
    suspend fun applyMonitoringIntervals(
        hrIntervalMinutes: Int,
        bpIntervalMinutes: Int,
        spo2IntervalMinutes: Int,
    ) {
        if (!command.isConnected) return
        if (command.activeProtocol == com.kschulz.aro.data.ble.RingProtocol.JRING) {
            command.request(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_AUTO_HR_SCHEDULE,
                com.kschulz.aro.data.ble.JRingProtocol.buildAutoHrPayload(
                    enabled = hrIntervalMinutes > 0,
                    intervalMinutes = hrIntervalMinutes.coerceAtLeast(10),
                ),
                timeoutMs = STREAM_TIMEOUT_MS,
            )
            return
        }

        val autoHrSwitch = RingPayloads.buildAutoHrSwitchPayload(
            enabled = hrIntervalMinutes > 0,
            intervalMinutes = hrIntervalMinutes.coerceAtLeast(10),
        )
        command.request(Opcodes.HR_TIMING_MONITOR_SWITCH, autoHrSwitch)

        val autoBpSwitch = RingPayloads.buildAutoBpSwitchPayload(
            enabled = bpIntervalMinutes > 0,
            intervalMinutes = bpIntervalMinutes.coerceAtLeast(30),
        )
        command.request(Opcodes.BP_TIMING_MONITOR_SWITCH, autoBpSwitch)

        val autoSpo2Switch = RingPayloads.buildAutoSpo2SettingPayload(
            enabled = spo2IntervalMinutes > 0,
            intervalMinutes = spo2IntervalMinutes.coerceAtLeast(30),
        )
        command.request(Opcodes.AUTO_SPO2_SETTING, autoSpo2Switch)
    }

    /**
     * Sync path for JRing / Dialog `SMART_RING` (`0x56FF` / `0x33F3` / `0x33F4` 20-byte frames).
     */
    private suspend fun syncJRing(
        profileId: Long,
        time: TimeSource,
        profile: Profile?,
        hrIntervalMinutes: Int,
    ): SyncResult {
        val nowMs = time.nowMillis()
        val today = time.todayEpochDay()

        // 1. Set foreground state, device clock, user info, step goal, and auto-HR schedule.
        command.request(
            com.kschulz.aro.data.ble.JRingProtocol.CMD_APP_STATE,
            com.kschulz.aro.data.ble.JRingProtocol.buildAppStatePayload(foreground = true),
            timeoutMs = STREAM_TIMEOUT_MS,
        )
        command.request(
            com.kschulz.aro.data.ble.JRingProtocol.CMD_SET_TIME,
            com.kschulz.aro.data.ble.JRingProtocol.buildSetTimePayload(nowMs),
            timeoutMs = STREAM_TIMEOUT_MS,
        )
        if (profile != null) {
            command.request(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_SET_USER_INFO,
                com.kschulz.aro.data.ble.JRingProtocol.buildUserInfoPayload(
                    heightCm = profile.heightCm,
                    weightKg = profile.weightKg,
                    age = profile.age(time.localClock().year),
                    gender = profile.gender,
                ),
                timeoutMs = STREAM_TIMEOUT_MS,
            )
            command.request(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_SET_GOAL,
                com.kschulz.aro.data.ble.JRingProtocol.buildGoalPayload(profile.goalSteps),
                timeoutMs = STREAM_TIMEOUT_MS,
            )
        }
        command.request(
            com.kschulz.aro.data.ble.JRingProtocol.CMD_AUTO_HR_SCHEDULE,
            com.kschulz.aro.data.ble.JRingProtocol.buildAutoHrPayload(
                enabled = hrIntervalMinutes > 0,
                intervalMinutes = hrIntervalMinutes.coerceAtLeast(10),
            ),
            timeoutMs = STREAM_TIMEOUT_MS,
        )

        var stepsCount = 0
        var hrCount = 0
        val days = (0..6).toList()

        // 2. Pull recent HR history (0x16) and daily activity (0x10) for the last 7 days.
        for (daysAgo in days) {
            val hrFrames = command.collectUntil(
                cmd = com.kschulz.aro.data.ble.JRingProtocol.CMD_GET_HR_HISTORY,
                payload = com.kschulz.aro.data.ble.JRingProtocol.buildDayQueryPayload(daysAgo),
                shouldStop = { frame ->
                    val tag = frame.payload.getOrNull(0)?.toInt()?.and(0xFF)
                    tag == 0xFF
                },
                maxPackets = 32,
                timeoutMs = 1_500L,
            )
            val parsedHr = hrFrames.flatMap {
                com.kschulz.aro.data.ble.JRingProtocol.parseHrHistoryPayload(profileId, it.payload)
            }
            if (parsedHr.isNotEmpty()) {
                health.upsertHeartRate(profileId, parsedHr)
                health.upsertStress(profileId, deriveStressFromHr(profileId, parsedHr))
                hrCount += parsedHr.size
            }

            if (daysAgo == 0) {
                val sportRsp = command.request(
                    com.kschulz.aro.data.ble.JRingProtocol.CMD_GET_DATA_BY_DAY,
                    com.kschulz.aro.data.ble.JRingProtocol.buildDayQueryPayload(0),
                    timeoutMs = 1_500L,
                )
                sportRsp?.let {
                    com.kschulz.aro.data.ble.JRingProtocol.parseCurrentSportPayload(profileId, today, it.payload)
                        ?.let { stepDay ->
                            health.upsertSteps(profileId, listOf(stepDay))
                            stepsCount += 1
                        }
                }
            }
        }

        // 3. Recompute Body Battery up to current hour.
        val windowStartHour = time.epochDayForOffset(6) * 24
        val nowHour = nowMs / 3_600_000L
        val windowEndHour = minOf(today * 24 + 23, nowHour)
        runCatching { bodyBattery.recompute(profileId, windowStartHour, windowEndHour) }

        AroDbg.d("jring sync done: steps=$stepsCount hr=$hrCount")
        return SyncResult(
            connected = true,
            daysSynced = days.size,
            steps = stepsCount,
            hrSamples = hrCount,
            distributionCleared = true,
        )
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
            val todayTotal = command.request(Opcodes.GET_STEP_TODAY, null)
                ?.takeIf { !it.error }
                ?.let { RingPayloads.parseStepToday(it.payload, epochDay) }
            if (todayTotal != null && todayTotal.steps > 0) {
                todayTotal
            } else {
                // Mirror QRing StepDetailRepository: also check 0x43 (15-min step detail)
                // for today when 0x48 reports 0 steps, keeping whichever has more steps.
                val detailReq = RingPayloads.buildDetailSportQueryPayload(0)
                val detailFrames = command.collectUntil(
                    cmd = Opcodes.GET_STEP_SOMEDAY_DETAIL,
                    payload = detailReq,
                    shouldStop = { it.error || RingPayloads.isDetailSportEndPacket(it.payload) },
                    maxPackets = 98,
                    timeoutMs = STREAM_TIMEOUT_MS,
                )
                val detailDay = if (detailFrames.isNotEmpty() && detailFrames.none { it.error }) {
                    RingPayloads.parseDetailSportPackets(detailFrames.map { it.payload }, epochDay)
                } else {
                    null
                }
                if (detailDay != null && detailDay.steps > (todayTotal?.steps ?: 0)) {
                    detailDay
                } else {
                    todayTotal
                }
            }
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
        if (samples.isNotEmpty()) {
            health.upsertHeartRate(profileId, samples)
            health.upsertStress(profileId, deriveStressFromHr(profileId, samples))
        }
        return samples
    }

    /**
     * Estimate physiological stress (10..95) from heart-rate elevation above resting
     * baseline (60 bpm), grouped by hour. Works identically for both Oudmon/QRing and
     * JRing/SMART_RING devices.
     */
    private fun deriveStressFromHr(
        profileId: Long,
        samples: List<HeartRateSample>,
    ): List<com.kschulz.aro.data.model.StressSample> {
        val hourMs = 3_600_000L
        return samples
            .filter { it.bpm in 35..220 }
            .groupBy { it.epochMs / hourMs }
            .map { (epochHour, list) ->
                val avgBpm = list.map { it.bpm }.average()
                // Resting (~55-65 bpm) -> 18..28 (Relaxed); elevated (80-115 bpm) -> 45..85
                val score = (22.0 + (avgBpm - 60.0) * 1.15).toInt().coerceIn(10, 95)
                com.kschulz.aro.data.model.StressSample(
                    profileId = profileId,
                    epochMs = epochHour * hourMs,
                    score = score,
                )
            }
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

    private suspend fun pullAutoSpo2(
        profileId: Long,
        todayEpochDay: Long,
    ): List<com.kschulz.aro.data.model.Spo2Sample> {
        // Matches LargeDataHandler.syncBloodOxygen(0): 0xBC 0x2A with 1-byte payload [0x00].
        val rsp = command.request(
            cmd = Opcodes.AUTO_SPO2_DATA,
            payload = byteArrayOf(0x00),
            timeoutMs = STREAM_TIMEOUT_MS,
        ) ?: return emptyList()
        if (rsp.error) return emptyList()
        val samples = RingPayloads.parseAutoSpo2BigData(profileId, todayEpochDay, rsp.payload)
        if (samples.isNotEmpty()) health.upsertSpo2(profileId, samples)
        return samples
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
    val spo2Samples: Int = 0,
    val distributionCleared: Boolean = false,
)

package com.kschulz.aro.data.ble

import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay

/**
 * Pure byte parsers for the ring response payloads. No Android, no transport: ByteArray in,
 * model out. These are the byte-correct core of the protocol (verified against
 * re/PROTOCOL.md sections 6 and 7) and the part that is unit-tested on the JVM.
 */
object RingPayloads {

    // 0xFFFFFFFF read as a signed 4-byte big-endian Int is -1 (fromBeBytes returns Int).
    private const val MANUAL_BP_SENTINEL = 0xFFFFFFFF.toInt()

    const val MEASURE_TYPE_HEART_RATE = 0x01
    const val MEASURE_TYPE_BLOOD_PRESSURE = 0x02
    const val MEASURE_TYPE_SPO2 = 0x03

    data class ManualBpReading(val epochMs: Long, val sbp: Int, val dbp: Int)
    data class AutoBpReading(val epochMs: Long, val sbp: Int, val dbp: Int, val rawValue: Int)
    data class LiveMeasurementPacket(
        val type: Int,
        val errCode: Int,
        val value: Int,
        val sbp: Int = 0,
        val dbp: Int = 0,
    )
    data class HeartRateRecord(val epochMs: Long, val bpm: Int)
    data class SleepStage(val stage: Int, val durationMin: Int)
    data class SleepParse(
        val startEpochMs: Long,
        val endEpochMs: Long,
        val isNap: Boolean,
        val stages: List<SleepStage>,
    )

    private const val SECONDS_PER_DAY = 86_400L

    /**
     * 0x01 CMD_SET_DEVICE_TIME request payload (traced to `SetTimeReq` in the vendor smali).
     * Seven bytes, all BCD-packed (high nibble / low nibble per digit, `decimalToBCD`), from
     * the phone's LOCAL calendar, NOT UTC:
     *   [0] BCD(year % 2000)  [1] BCD(month, 1-12)  [2] BCD(day)
     *   [3] BCD(hour, 0-23)   [4] BCD(minute)       [5] BCD(second)  [6] language
     * `language` is the byte the vendor writes from its locale map (0 = zh, 1 = en). The ring
     * treats this as the time baseline: the vendor's post-bind init chain sends 0x01 first,
     * before any data query. We send it at the start of sync so the device has a clock before
     * the 0x46 distribution / history pulls.
     */
    fun buildSetTimePayload(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        language: Int = 1,
    ): ByteArray = byteArrayOf(
        bcd(year % 2000),
        bcd(month),
        bcd(day),
        bcd(hour),
        bcd(minute),
        bcd(second),
        language.toByte(),
    )

    /**
     * 0x0A GET_TIME_SETTING write payload (traced to `TimeFormatReq.getWriteInstance` in smali).
     * 11 bytes carrying unit/clock preferences, anthropometric attributes (used by the ring to
     * compute calories and walking distance), and systolic/diastolic BP calibration baseline:
     *   [0] 0x02 (write sub-command; 0x01 is read)
     *   [1] timeFormat (0 = 24-hour, 1 = 12-hour)
     *   [2] unitFormat (0 = Metric, 1 = Imperial)
     *   [3] sex (0 = Male, 1 = Female)
     *   [4] age (years)
     *   [5] heightCm (unsigned byte)
     *   [6] weightKg (unsigned byte)
     *   [7] sbpBaseline (systolic calibration baseline, default 120)
     *   [8] dbpBaseline (diastolic calibration baseline, default 80)
     *   [9] hrWarnBpm (heart rate warning threshold, default 160)
     *  [10] hrWarnOpen (0 = off, 1 = on)
     */
    fun buildTimeSettingPayload(
        gender: Int = Profile.GENDER_MALE,
        age: Int = 30,
        heightCm: Int = Profile.DEFAULT_HEIGHT_CM,
        weightKg: Int = Profile.DEFAULT_WEIGHT_KG,
        sbpBaseline: Int = Profile.DEFAULT_SBP_BASELINE,
        dbpBaseline: Int = Profile.DEFAULT_DBP_BASELINE,
        useMetric: Boolean = true,
        use24Hour: Boolean = true,
        hrWarnBpm: Int = 160,
        hrWarnEnabled: Boolean = false,
    ): ByteArray = byteArrayOf(
        0x02,
        if (use24Hour) 0x00 else 0x01,
        if (useMetric) 0x00 else 0x01,
        gender.coerceIn(0, 1).toByte(),
        age.coerceIn(10, 110).toByte(),
        heightCm.coerceIn(50, 250).toByte(),
        weightKg.coerceIn(20, 250).toByte(),
        sbpBaseline.coerceIn(80, 200).toByte(),
        dbpBaseline.coerceIn(40, 140).toByte(),
        hrWarnBpm.coerceIn(100, 220).toByte(),
        if (hrWarnEnabled) 0x01 else 0x00,
    )

    /**
     * 0x21 TARGET_SETTING write payload (traced to `TargetSettingReq$3.smali`).
     * 14 bytes:
     *   [0]      0x02 (write sub-command)
     *   [1..3]   goalSteps (3-byte little-endian)
     *   [4..6]   goalCalories * 1000 (3-byte little-endian)
     *   [7..9]   goalDistanceMeters (3-byte little-endian)
     *   [10..11] goalSportMinutes (2-byte little-endian)
     *   [12..13] goalSleepMinutes (2-byte little-endian)
     */
    fun buildTargetSettingPayload(
        goalSteps: Int = Profile.DEFAULT_GOAL_STEPS,
        goalCalories: Int = Profile.DEFAULT_GOAL_CALORIES,
        goalDistanceMeters: Int = Profile.DEFAULT_GOAL_DISTANCE_METERS,
        goalSportMinutes: Int = 30,
        goalSleepMinutes: Int = Profile.DEFAULT_GOAL_SLEEP_MINUTES,
    ): ByteArray {
        val out = ByteArray(14)
        out[0] = 0x02
        ByteOrder.toLeBytes(goalSteps.coerceAtLeast(0), 3).copyInto(out, 1)
        ByteOrder.toLeBytes(goalCalories.coerceAtLeast(0) * 1000, 3).copyInto(out, 4)
        ByteOrder.toLeBytes(goalDistanceMeters.coerceAtLeast(0), 3).copyInto(out, 7)
        ByteOrder.toLeBytes(goalSportMinutes.coerceAtLeast(0), 2).copyInto(out, 10)
        ByteOrder.toLeBytes(goalSleepMinutes.coerceAtLeast(0), 2).copyInto(out, 12)
        return out
    }

    /**
     * Packed BCD, byte-identical to the vendor `decimalToBCD` (`(n/10)<<4 | n%10` then
     * int-to-byte). Inputs are 0..99 date/time fields, so the result is well-formed BCD.
     */
    private fun bcd(value: Int): Byte = (((value / 10) shl 4) or (value % 10)).toByte()

    /** Unpacks a single BCD byte (`0x26` -> `26`), matching `BLEDataFormatUtils.BCDToDecimal`. */
    fun bcdToDecimal(byte: Byte): Int {
        val v = byte.toInt() and 0xFF
        return ((v ushr 4) * 10) + (v and 0x0F)
    }

    /**
     * Checks `SetTimeRsp.mNewSleepProtocol` (`SetTimeRsp.smali` line 332: `payload[8] == 1`).
     * When true, the ring uses `LargeDataHandler.syncSleepList` (`0x27` on `de5bf72a-...`)
     * rather than `0x44` (`ReadSleepDetailsReq`).
     */
    fun isNewSleepProtocolSupported(setTimeRspPayload: ByteArray): Boolean =
        setTimeRspPayload.size >= 9 && (setTimeRspPayload[8].toInt() and 0xFF) == 1

    /**
     * Checks `SetTimeRsp.mSupportBloodPressure` (`SetTimeRsp.smali` line 188: `(payload[3] & 4) != 0`).
     * Returns true when the response payload is empty (e.g. synthetic test fixtures).
     */
    fun isManualBpHistorySupported(setTimeRspPayload: ByteArray): Boolean =
        setTimeRspPayload.size < 4 || ((setTimeRspPayload[3].toInt() and 0x04) != 0)

    /**
     * 0x16 HR_TIMING_MONITOR_SWITCH (`HeartRateSettingReq.getWriteInstance` in smali).
     * Enables/disables 24-hour automatic heart-rate monitoring at [intervalMinutes].
     */
    /**
     * 0x16 HR_TIMING_MONITOR_SWITCH (`HeartRateSettingReq.getWriteInstance` in smali):
     *   [0] 0x02 (write sub-command)
     *   [1] 0x01 = enable, 0x02 = disable
     *   [2] intervalMinutes (e.g. 10, 15, 30, 60)
     *   [3] hrStart (0)
     *   [4] hrTooLow (0 = off)
     *   [5] hrTooHigh (0 = off)
     *   [6] hrTooSwitch (0 = alerts off)
     */
    fun buildAutoHrSwitchPayload(
        enabled: Boolean = true,
        intervalMinutes: Int = 30,
    ): ByteArray = byteArrayOf(
        0x02,
        if (enabled) 0x01 else 0x02,
        intervalMinutes.coerceIn(5, 60).toByte(),
        0x00,
        0x00,
        0x00,
        0x00,
    )

    /**
     * Parses a `0x27` (`LargeDataHandler.ACTION_New_Sleep_Protocol`, traced to
     * `LargeDataHandler$4.smali` and `LargeDataHandler.parseDaySleep` lines 653-898) big-data
     * payload (after the 6-byte `0xBC` header has been stripped by [RingProtocolCodec.decode]).
     *
     * Payload layout:
     *   [0] dayCount (`fullFrame[6]`)
     *   Followed by `dayCount` variable-length records starting at offset 1 (`fullFrame[7]`):
     *     rec[0]    daysAgo (0 = today, 1 = yesterday, ...)
     *     rec[1]    bodyLength (`recLen = bodyLength + 2`)
     *     rec[2..3] startMinutesSinceMidnight (2-byte LE)
     *     rec[4..5] endMinutesSinceMidnight (2-byte LE)
     *     rec[6..]  `[stageType, durationMinutes]` pairs (1 byte each)
     */
    fun parseNewSleepProtocolPayload(
        payload: ByteArray,
        todayEpochDay: Long,
    ): List<SleepParse> {
        if (payload.isEmpty()) return emptyList()
        val dayCount = payload[0].toInt() and 0xFF
        if (dayCount <= 0) return emptyList()

        val out = mutableListOf<SleepParse>()
        var offset = 1
        while (offset + 6 <= payload.size) {
            val daysAgo = payload[offset].toInt() and 0xFF
            val bodyLen = payload[offset + 1].toInt() and 0xFF
            val recLen = bodyLen + 2
            if (bodyLen < 4 || offset + recLen > payload.size) break

            val endMinutes =
                (payload[offset + 4].toInt() and 0xFF) or
                    ((payload[offset + 5].toInt() and 0xFF) shl 8)
            val stages = mutableListOf<SleepStage>()
            var totalMin = 0
            var i = offset + 6
            val recEnd = offset + recLen
            while (i + 1 < recEnd) {
                val rawType = payload[i].toInt() and 0xFF
                val durMin = payload[i + 1].toInt() and 0xFF
                if (durMin > 0) {
                    val stage = when (rawType) {
                        2 -> 1 // Deep sleep (SleepDetailRepository.b line 367)
                        3 -> 2 // Light sleep (line 360)
                        4 -> 3 // REM sleep (line 353)
                        5 -> 4 // Awake (line 346)
                        else -> rawType.coerceIn(1, 4)
                    }
                    stages.add(SleepStage(stage = stage, durationMin = durMin))
                    totalMin += durMin
                }
                i += 2
            }
            if (totalMin > 0 && stages.isNotEmpty()) {
                val dayEpoch = todayEpochDay - daysAgo
                val endEpochSec = dayEpoch * SECONDS_PER_DAY + endMinutes * 60L
                val startEpochSec = endEpochSec - totalMin * 60L
                out.add(
                    SleepParse(
                        startEpochMs = startEpochSec * 1000L,
                        endEpochMs = endEpochSec * 1000L,
                        isNap = false,
                        stages = stages,
                    ),
                )
            }
            offset += recLen
        }
        return out
    }

    /**
     * 0x44 GET_SLEEP main-sleep record (`LargeDataHandler.parseDaySleep` big-data format).
     * Kept for big-data sleep responses and unit tests:
     *   [0..2) record type
     *   [2..4) length
     *   [4..6) end minutes-since-midnight, 2-byte big-endian
     *   [6.. ) [stage, durationMin] pairs, one byte each
     */
    fun parseSleep(payload: ByteArray, epochDay: Long): SleepParse? {
        if (payload.size < 6) return null
        val endMinutes = ByteOrder.fromBeBytes(payload, 4, 2)
        val endEpochSec = epochDay * SECONDS_PER_DAY + endMinutes * 60L
        val stages = mutableListOf<SleepStage>()
        var totalMin = 0
        var i = 6
        while (i + 1 < payload.size) {
            stages.add(SleepStage(payload[i].toInt() and 0xFF, payload[i + 1].toInt() and 0xFF))
            totalMin += payload[i + 1].toInt() and 0xFF
            i += 2
        }
        val startEpochSec = endEpochSec - totalMin * 60L
        return SleepParse(
            startEpochMs = startEpochSec * 1000L,
            endEpochMs = endEpochSec * 1000L,
            isNap = false,
            stages = stages,
        )
    }

    /**
     * True when a 0x44 (`ReadSleepDetailsRsp.smali`) 16-byte command notification marks the end
     * of the sleep detail stream:
     * - `payload[0] == 0xFF`: no sleep data for the requested day
     * - `payload[0] == 0xF0`: stream init header (more packets follow)
     * - otherwise: data packet where `payload[4]` is `curPacketIndex` and `payload[5]` is `totalPackets`;
     *   stream ends when `curPacketIndex == totalPackets - 1`.
     */
    fun isSleepDetailsEndPacket(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val b0 = payload[0].toInt() and 0xFF
        if (b0 == 0xFF) return true
        if (b0 == 0xF0) return false
        if (payload.size < 6) return true
        val cur = payload[4].toInt() and 0xFF
        val total = payload[5].toInt() and 0xFF
        return total <= 0 || cur >= total - 1
    }

    /**
     * Parses a multi-packet 0x44 (`ReadSleepDetailsRsp.smali` + `SleepDetailRepository.smali`)
     * sleep detail stream into a [SleepParse].
     *
     * Each 14-byte data payload contains:
     *   [0] BCD(year - 2000), [1] BCD(month), [2] BCD(day),
     *   [3] timeIndex (15-minute slot index 0..95),
     *   [4] curPacketIndex, [5] totalPackets,
     *   [6] sleepType (`1` = sleep, `2` = wake/end, `3` = awake interruption),
     *   [7] durationMinutes (1..15, typically 15),
     *   [8] motionScore (`<= 50` = deep sleep stage 1, `> 50` = light sleep stage 2).
     */
    fun parseSleepDetailsStream(
        packets: List<ByteArray>,
        dayStartEpochMsForDate: (year: Int, month: Int, day: Int) -> Long,
        fallbackEpochDay: Long,
    ): SleepParse? {
        val dataPackets = packets.filter { pkt ->
            pkt.size >= 9 &&
                (pkt[0].toInt() and 0xFF) != 0xFF &&
                (pkt[0].toInt() and 0xFF) != 0xF0
        }
        if (dataPackets.isEmpty()) {
            val single = packets.singleOrNull()
            if (single != null && single.size >= 6 && (single[0].toInt() and 0xFF) == 0x00) {
                return parseSleep(single, fallbackEpochDay)
            }
            return null
        }

        val first = dataPackets.first()
        val year = bcdToDecimal(first[0]) + 2000
        val month = bcdToDecimal(first[1])
        val day = bcdToDecimal(first[2])
        val baseDayStartMs = if (month in 1..12 && day in 1..31) {
            dayStartEpochMsForDate(year, month, day)
        } else {
            fallbackEpochDay * SECONDS_PER_DAY * 1000L
        }

        data class SlotEntry(val slotOrder: Int, val epochMs: Long, val stage: Int, val durationMin: Int)

        val rawIndices = dataPackets.map { it[3].toInt() and 0xFF }
        val hasMorning = rawIndices.any { it < 48 }
        val hasEvening = rawIndices.any { it >= 72 }

        val entries = mutableListOf<SlotEntry>()
        for (pkt in dataPackets) {
            val timeIdx = pkt[3].toInt() and 0xFF
            if (timeIdx > 95) continue
            val q1 = pkt[6].toInt() and 0xFF
            val q2 = (pkt[7].toInt() and 0xFF).takeIf { it in 1..15 } ?: 15
            val q3 = pkt[8].toInt() and 0xFF
            val stage = when {
                q1 == 3 -> 4 // Awake
                q3 <= 50 -> 1 // Deep sleep
                else -> 2 // Light sleep
            }
            val shiftedOrder = if (hasMorning && hasEvening && timeIdx >= 72) {
                timeIdx - 96
            } else {
                timeIdx
            }
            val slotEpochMs = baseDayStartMs + shiftedOrder * 15L * 60_000L
            entries.add(SlotEntry(shiftedOrder, slotEpochMs, stage, q2))
        }
        if (entries.isEmpty()) return null

        val sorted = entries.sortedBy { it.slotOrder }.distinctBy { it.slotOrder }
        val mergedStages = mutableListOf<SleepStage>()
        for (entry in sorted) {
            val last = mergedStages.lastOrNull()
            if (last != null && last.stage == entry.stage) {
                mergedStages[mergedStages.lastIndex] =
                    last.copy(durationMin = last.durationMin + entry.durationMin)
            } else {
                mergedStages.add(SleepStage(stage = entry.stage, durationMin = entry.durationMin))
            }
        }

        val startMs = sorted.first().epochMs
        val totalMin = mergedStages.sumOf { it.durationMin }
        val endMs = maxOf(
            sorted.last().epochMs + sorted.last().durationMin * 60_000L,
            startMs + totalMin * 60_000L,
        )
        return SleepParse(
            startEpochMs = startMs,
            endEpochMs = endMs,
            isNap = false,
            stages = mergedStages,
        )
    }

    /**
     * 0x48 GET_STEP_TODAY (`TodaySportDataRsp.smali` lines 30-163, big-endian):
     *   [0..2]   totalSteps (3-byte big-endian)
     *   [3..5]   runningSteps (3-byte big-endian)
     *   [6..8]   calorie in gram-calories (3-byte big-endian; divided by 1000 for kcal)
     *   [9..11]  walkDistance in meters (3-byte big-endian)
     *   [12..13] sportDuration in minutes (2-byte big-endian)
     */
    fun parseStepToday(payload: ByteArray, epochDay: Long): StepDay? {
        if (payload.size < 14) return null
        val rawCalorie = ByteOrder.fromBeBytes(payload, 6, 3)
        return StepDay(
            profileId = 0, // caller overwrites with the active profile before upsert
            date = epochDay,
            steps = ByteOrder.fromBeBytes(payload, 0, 3),
            distanceMeters = ByteOrder.fromBeBytes(payload, 9, 3),
            calories = rawCalorie / 1000,
            durationMin = ByteOrder.fromBeBytes(payload, 12, 2),
        )
    }

    /**
     * True when a 0x07 (`TotalSportDataRsp.smali`) packet terminates the 2-packet historical
     * step response:
     * - Packet 0 (`payload[0] == 0x00`) with `payload[2..4] == 0` means no stored data for that day.
     * - Packet 1 (`payload[0] >= 0x01`) is the second and final packet.
     */
    fun isTotalSportEndPacket(payload: ByteArray): Boolean {
        if (payload.size < 12) return true
        val idx = payload[0].toInt() and 0xFF
        if (idx == 0) {
            return payload[2].toInt() == 0 && payload[3].toInt() == 0 && payload[4].toInt() == 0
        }
        return idx >= 1
    }

    /**
     * 0x43 GET_STEP_SOMEDAY_DETAIL request payload (`ReadDetailSportDataReq.smali`):
     * `[dayOffset, 0x0F, startOffset=0x00, endOffset=0x5F, 0x01]`.
     */
    fun buildDetailSportQueryPayload(daysAgo: Int): ByteArray =
        byteArrayOf(daysAgo.toByte(), 0x0F, 0x00, 0x5F.toByte(), 0x01)

    /**
     * True when a 0x43 (`ReadDetailSportDataRsp.smali`) notification marks the end of the
     * 15-minute step detail stream:
     * - `payload[0] == 0xFF`: no step data for that day
     * - `payload[0] == 0xF0`: init header packet (`payload[2] == 1` enables `calorieNewProtocol`)
     * - otherwise: data packet where `payload[4]` is `curPacketIndex` and `payload[5]` is `totalPackets`.
     */
    fun isDetailSportEndPacket(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val b0 = payload[0].toInt() and 0xFF
        if (b0 == 0xFF) return true
        if (b0 == 0xF0) return false
        if (payload.size < 6) return true
        val cur = payload[4].toInt() and 0xFF
        val total = payload[5].toInt() and 0xFF
        return total <= 0 || cur >= total - 1
    }

    /**
     * Parses a multi-packet 0x43 (`ReadDetailSportDataRsp.smali` + `StepDetailRepository.smali`)
     * response into a daily [StepDay] total.
     */
    fun parseDetailSportPackets(packets: List<ByteArray>, epochDay: Long): StepDay? {
        if (packets.isEmpty()) return null
        val header = packets.firstOrNull { it.size >= 3 && (it[0].toInt() and 0xFF) == 0xF0 }
        val calorieMultiplier = if (header != null && (header[2].toInt() and 0xFF) == 1) 10 else 1

        val dataPackets = packets.filter { pkt ->
            pkt.size >= 12 &&
                (pkt[0].toInt() and 0xFF) != 0xFF &&
                (pkt[0].toInt() and 0xFF) != 0xF0
        }
        if (dataPackets.isEmpty()) return null

        var totalSteps = 0
        var totalDistance = 0
        var totalCalorieRaw = 0
        val seenSlots = HashSet<Int>()

        for (pkt in dataPackets) {
            val timeIndex = pkt[3].toInt() and 0xFF
            if (!seenSlots.add(timeIndex)) continue
            val slotCalorie =
                (((pkt[7].toInt() and 0xFF) shl 8) or (pkt[6].toInt() and 0xFF)) * calorieMultiplier
            val slotSteps = ((pkt[9].toInt() and 0xFF) shl 8) or (pkt[8].toInt() and 0xFF)
            val slotDistance = ((pkt[11].toInt() and 0xFF) shl 8) or (pkt[10].toInt() and 0xFF)
            totalCalorieRaw += slotCalorie
            totalSteps += slotSteps
            totalDistance += slotDistance
        }
        if (totalSteps <= 0 && totalCalorieRaw <= 0 && totalDistance <= 0) return null

        return StepDay(
            profileId = 0,
            date = epochDay,
            steps = totalSteps,
            distanceMeters = totalDistance,
            calories = totalCalorieRaw / 1000,
            durationMin = seenSlots.size * 15,
        )
    }

    /**
     * Parses the 2-packet 0x07 (`TotalSportDataRsp.smali` lines 33-190) response for a
     * historical day (`daysAgo >= 1`):
     * - Packet 0 (`payload[0] == 0x00`):
     *   [1] BCD(daysAgo), [2] BCD(year-2000), [3] BCD(month), [4] BCD(day),
     *   [5..7] totalSteps (3-byte BE), [8..10] runningSteps (3-byte BE),
     *   [11..13] calorie in gram-calories (3-byte BE; divided by 1000 for kcal).
     * - Packet 1 (`payload[0] == 0x01`):
     *   [5..7] walkDistance in meters (3-byte BE),
     *   [8..9] sportDuration in minutes (2-byte BE),
     *   [10..11] sleepDuration in minutes (2-byte BE).
     */
    fun parseTotalSportPackets(packets: List<ByteArray>, epochDay: Long): StepDay? {
        if (packets.isEmpty()) return null
        val pkt0 = packets.firstOrNull { it.size >= 14 && (it[0].toInt() and 0xFF) == 0 }
            ?: return parseStepToday(packets.first(), epochDay)
        if (pkt0[2].toInt() == 0 && pkt0[3].toInt() == 0 && pkt0[4].toInt() == 0) {
            // Fallback if a synthetic single-packet test fixture was passed
            val stepsIfTodayLayout = ByteOrder.fromBeBytes(pkt0, 0, 3)
            if (stepsIfTodayLayout > 0) return parseStepToday(pkt0, epochDay)
            return null
        }
        val pkt1 = packets.firstOrNull { it.size >= 12 && (it[0].toInt() and 0xFF) == 1 }
        val steps = ByteOrder.fromBeBytes(pkt0, 5, 3)
        val rawCalorie = ByteOrder.fromBeBytes(pkt0, 11, 3)
        val distanceMeters = pkt1?.let { ByteOrder.fromBeBytes(it, 5, 3) } ?: 0
        val durationMin = pkt1?.let { ByteOrder.fromBeBytes(it, 8, 2) } ?: 0
        return StepDay(
            profileId = 0,
            date = epochDay,
            steps = steps,
            distanceMeters = distanceMeters,
            calories = rawCalorie / 1000,
            durationMin = durationMin,
        )
    }

    /** True when the packet's timestamp field is the 0xFFFFFFFF end-of-stream sentinel. */
    fun isManualBpSentinel(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        return ByteOrder.fromBeBytes(payload, 0, 4) == MANUAL_BP_SENTINEL
    }

    /**
     * One 0x14 manual BP packet (traced to `ReadBlePressureRsp.smali`):
     * ts[0..3] big-endian seconds, followed by DBP and SBP in mmHg (`payload[4]` = dbp,
     * `payload[5]` = sbp in `ReadBlePressureRsp.smali` lines 169-185). We normalize
     * `sbp = maxOf(b4, b5)` and `dbp = minOf(b4, b5)` so systolic is always the higher
     * pressure regardless of firmware byte ordering.
     */
    fun parseManualBpPacket(payload: ByteArray): ManualBpReading? {
        if (payload.size < 6) return null
        if (isManualBpSentinel(payload)) return null
        val tsSeconds = ByteOrder.fromBeBytes(payload, 0, 4)
        if (tsSeconds == MANUAL_BP_SENTINEL || tsSeconds <= 0) return null
        val b4 = payload[4].toInt() and 0xFF
        val b5 = payload[5].toInt() and 0xFF
        val sbp = maxOf(b4, b5)
        val dbp = minOf(b4, b5)
        if (sbp <= 0 || dbp <= 0 || sbp == dbp) return null
        return ManualBpReading(
            epochMs = tsSeconds.toLong() * 1000L,
            sbp = sbp,
            dbp = dbp,
        )
    }

    /**
     * 0x0C BP_TIMING_MONITOR_SWITCH (`BpSettingReq.getWriteInstance` in smali).
     * Enables/disables 24-hour automatic blood pressure monitoring at [intervalMinutes].
     */
    fun buildAutoBpSwitchPayload(
        enabled: Boolean = true,
        startHour: Int = 0,
        startMinute: Int = 0,
        endHour: Int = 23,
        endMinute: Int = 59,
        intervalMinutes: Int = 60,
    ): ByteArray = byteArrayOf(
        0x02,
        if (enabled) 0x01 else 0x00,
        startHour.coerceIn(0, 23).toByte(),
        startMinute.coerceIn(0, 59).toByte(),
        endHour.coerceIn(0, 23).toByte(),
        endMinute.coerceIn(0, 59).toByte(),
        intervalMinutes.coerceIn(15, 120).toByte(),
    )

    /**
     * 0x0E BP_TIMING_MONITOR_CONFIRM (`BpReadConformReq` in smali).
     * Sent after reading 0x0D auto-BP data to acknowledge receipt (`0x00` = success).
     */
    fun buildAutoBpConfirmPayload(success: Boolean = true): ByteArray =
        byteArrayOf(if (success) 0x00 else 0x01)

    /**
     * True when a 0x0D (`BpDataRsp`) packet signals the end of the auto-BP stream.
     * In `BpDataRsp.acceptData`, `(payload[0] & 0xFF) == 0xFF` or a header with 0 set bits
     * terminates collection.
     */
    fun isAutoBpEndPacket(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        val type = payload[0].toInt() and 0xFF
        if (type == 0xFF) return true
        if (type == 0x00) {
            if (payload.size < 11) return true
            val month = payload[2].toInt() and 0xFF
            val day = payload[3].toInt() and 0xFF
            val delay = payload[4].toInt() and 0xFF
            if (month !in 1..12 || day !in 1..31 || delay <= 0) return true
            val hasAnyBit = (5..10).any { (payload[it].toInt() and 0xFF) != 0 }
            return !hasAnyBit
        }
        return false
    }

    /**
     * Parses a multi-packet 0x0D (`BpDataRsp`) auto-BP stream and converts each slot's raw
     * PPG value to `(sbp, dbp)` using the deterministic port of `CalcBloodPressureByHeart`
     * calibrated to the active profile's [age], [sbpBaseline], and [dbpBaseline].
     *
     * Header packet (`payload[0] == 0x00`, size >= 11):
     *   [1] year - 2000, [2] month (1..12), [3] day (1..31), [4] timeDelayMinutes,
     *   [5..10] 48-bit slot bitmap.
     * Value packets (`payload[0] == 0x01`, size >= 2):
     *   [1..n] up to 13 raw bytes per packet corresponding to the set bitmap slots.
     */
    fun parseAutoBpStream(
        packets: List<ByteArray>,
        dayStartEpochMsForDate: (year: Int, month: Int, day: Int) -> Long,
        age: Int = 30,
        sbpBaseline: Int = Profile.DEFAULT_SBP_BASELINE,
        dbpBaseline: Int = Profile.DEFAULT_DBP_BASELINE,
    ): List<AutoBpReading> {
        val header = packets.firstOrNull { it.size >= 11 && (it[0].toInt() and 0xFF) == 0x00 }
            ?: return emptyList()
        val year = (header[1].toInt() and 0xFF) + 2000
        val month = header[2].toInt() and 0xFF
        val day = header[3].toInt() and 0xFF
        if (month !in 1..12 || day !in 1..31) return emptyList()
        val timeDelay = (header[4].toInt() and 0xFF).takeIf { it > 0 } ?: 30

        val slotMinutes = mutableListOf<Int>()
        for (byteIdx in 0 until 6) {
            val mask = header[5 + byteIdx].toInt() and 0xFF
            for (bit in 0 until 8) {
                if (((mask ushr bit) and 1) == 1) {
                    val slotIdx = byteIdx * 8 + bit
                    slotMinutes.add(slotIdx * timeDelay)
                }
            }
        }
        if (slotMinutes.isEmpty()) return emptyList()

        val rawValues = mutableListOf<Int>()
        for (pkt in packets) {
            if (pkt.size >= 2 && (pkt[0].toInt() and 0xFF) == 0x01) {
                for (i in 1 until pkt.size) {
                    rawValues.add(pkt[i].toInt() and 0xFF)
                }
            }
        }

        val dayStartMs = dayStartEpochMsForDate(year, month, day)
        val out = mutableListOf<AutoBpReading>()
        val count = minOf(slotMinutes.size, rawValues.size)
        for (i in 0 until count) {
            val raw = rawValues[i]
            if (raw <= 0 || raw == 0xFF) continue
            val (sbp, dbp) = estimateBpFromPpgRaw(
                rawValue = raw,
                age = age,
                sbpBaseline = sbpBaseline,
                dbpBaseline = dbpBaseline,
            )
            val epochMs = dayStartMs + slotMinutes[i] * 60_000L
            out.add(AutoBpReading(epochMs = epochMs, sbp = sbp, dbp = dbp, rawValue = raw))
        }
        return out
    }

    /**
     * Deterministic port of the vendor's `CalcBloodPressureByHeart.smali` +
     * `BloodPressureRepository$syncAutoBp$1$1$1$1.smali`, anchored to the user's calibrated
     * [sbpBaseline] / [dbpBaseline] and [age].
     *
     * In the vendor DEX:
     * - Raw values <= 50 are mapped into the 70..74 resting range.
     * - SBP scales by `(hr - 65) * 0.45f` around the age/profile baseline and is clamped to
     *   a physiological window (`90..165` mmHg).
     * - DBP preserves the profile's pulse-pressure differential (`sbpBaseline - dbpBaseline`,
     *   default ~40 mmHg) with 55% of the systolic delta, clamped to `55..105` mmHg.
     */
    fun estimateBpFromPpgRaw(
        rawValue: Int,
        age: Int = 30,
        sbpBaseline: Int = Profile.DEFAULT_SBP_BASELINE,
        dbpBaseline: Int = Profile.DEFAULT_DBP_BASELINE,
    ): Pair<Int, Int> {
        val effectiveHr = if (rawValue <= 50) {
            70 + (rawValue.coerceAtLeast(0) % 5)
        } else {
            rawValue.coerceIn(51, 180)
        }
        val ageOffset = if (sbpBaseline == Profile.DEFAULT_SBP_BASELINE) {
            ((age.coerceIn(15, 95) - 35) * 0.25f).toInt().coerceIn(-6, 12)
        } else {
            0
        }
        val baseSbp = (sbpBaseline + ageOffset).coerceIn(90, 170)
        val sbpDelta = ((effectiveHr - 65) * 0.45f).toInt()
        val sbp = (baseSbp + sbpDelta).coerceIn(90, 165)

        val basePulsePressure = (sbpBaseline - dbpBaseline).coerceIn(28, 65)
        val dbpDelta = (sbpDelta * 0.55f).toInt()
        val dbp = (baseSbp - basePulsePressure + dbpDelta)
            .coerceIn(55, (sbp - 22).coerceAtMost(105))
        return sbp to dbp
    }

    /**
     * 0x69 START_HEART_RATE request payload (`StartHeartRateReq.getSimpleReq(type)` in smali).
     * [type] is [MEASURE_TYPE_HEART_RATE] (`0x01`) or [MEASURE_TYPE_BLOOD_PRESSURE] (`0x02`).
     */
    fun buildStartMeasurementPayload(type: Int): ByteArray =
        byteArrayOf(type.toByte(), 0x00)

    /**
     * 0x6A STOP_HEART_RATE request payload (`StopHeartRateReq` in smali).
     * For blood pressure ([MEASURE_TYPE_BLOOD_PRESSURE]), passes `[0x02, sbp, dbp]`.
     * For heart rate ([MEASURE_TYPE_HEART_RATE]), passes `[0x01, bpm, 0x00]`.
     */
    fun buildStopMeasurementPayload(
        type: Int,
        primaryValue: Int = 0,
        secondaryValue: Int = 0,
    ): ByteArray = byteArrayOf(
        type.toByte(),
        primaryValue.coerceIn(0, 255).toByte(),
        secondaryValue.coerceIn(0, 255).toByte(),
    )

    /**
     * 0x69 START_HEART_RATE notification packet (`StartHeartRateRsp.acceptData` in smali):
     *   [0] type (0x01 = HR, 0x02 = BP)
     *   [1] errCode (0x00 = ok, 0x01 = ring not worn properly / poor contact)
     *   [2] value (live pulse / BPM)
     *   [3] sbp (optional, present when payload.size >= 5)
     *   [4] dbp (optional, present when payload.size >= 5)
     */
    fun parseLiveMeasurementPacket(payload: ByteArray): LiveMeasurementPacket? {
        if (payload.size < 3) return null
        val type = payload[0].toInt() and 0xFF
        val errCode = payload[1].toInt() and 0xFF
        val value = payload[2].toInt() and 0xFF
        var sbp = 0
        var dbp = 0
        if (payload.size >= 5) {
            val b3 = payload[3].toInt() and 0xFF
            val b4 = payload[4].toInt() and 0xFF
            if (b3 > 0 && b4 > 0) {
                sbp = maxOf(b3, b4)
                dbp = minOf(b3, b4)
            }
        }
        return LiveMeasurementPacket(
            type = type,
            errCode = errCode,
            value = value,
            sbp = sbp,
            dbp = dbp,
        )
    }

    /**
     * 0x15 GET_HEART_RATE request payload (`ReadHeartRateReq.smali` +
     * `HeartRateDetailRepository.smali` lines 1050-1076):
     * 4-byte little-endian local-midnight-aligned timestamp (`epochDay * 86_400`).
     */
    fun buildHeartRateQueryPayload(epochDay: Long): ByteArray =
        ByteOrder.toLeBytes((epochDay * SECONDS_PER_DAY).toInt(), 4)

    /**
     * 0x15 GET_HEART_RATE request payload for today (`daysAgo == 0`, matching
     * `HealthyViewModel$syncTodayHeartRateDetail$1.smali` + `HeartRateDetailRepository.l`):
     * current unix timestamp shifted by the local timezone offset.
     */
    fun buildHeartRateQueryPayloadForToday(nowMillis: Long, tzOffsetMillis: Int): ByteArray =
        ByteOrder.toLeBytes(((nowMillis + tzOffsetMillis) / 1000L).toInt(), 4)

    /**
     * True when a 0x15 (`ReadHeartRateRsp.smali` lines 87-265) notification marks the end of
     * the heart-rate stream for a day:
     * - `payload[0] == 0xFF`: end sentinel
     * - `payload[0] == 0x00`: header packet where `payload[1]` is total `size`; stops immediately if `size == 0`
     * - `payload[0] >= size - 1` (or `isToday && payload[0] == 0x17`): final slot packet
     */
    fun isHeartRateEndPacket(
        payload: ByteArray,
        expectedPackets: Int,
        isToday: Boolean,
    ): Boolean {
        if (payload.isEmpty()) return true
        val idx = payload[0].toInt() and 0xFF
        if (idx == 0xFF) return true
        if (idx == 0x00) {
            return payload.size < 2 || (payload[1].toInt() and 0xFF) <= 0
        }
        if (isToday && idx >= 0x17) return true
        return expectedPackets > 0 && idx >= expectedPackets - 1
    }

    /**
     * Parses a multi-packet 0x15 (`ReadHeartRateRsp.smali` +
     * `HeartRateDetailRepository$saveHeartRate$1.smali`) stream into [HeartRateRecord]s.
     *
     * Packet layout:
     * - `payload[0] == 0x00`: `[0x00, size, rangeMinutes, ...]`
     * - `payload[0] == 0x01`: `[0x01, utc0, utc1, utc2, utc3, hr0..hr8]` (first 9 slots)
     * - `payload[0] == 2..size-1`: `[idx, hr_a..hr_m]` (13 slots per packet)
     */
    fun parseHeartRateStream(
        packets: List<ByteArray>,
        dayStartEpochMs: Long,
    ): List<HeartRateRecord> {
        if (packets.isEmpty()) return emptyList()
        val header = packets.firstOrNull { it.size >= 3 && (it[0].toInt() and 0xFF) == 0x00 }
        val pkt1 = packets.firstOrNull { it.size >= 6 && (it[0].toInt() and 0xFF) == 0x01 }
        if (header == null && pkt1 == null) {
            // Fallback for single-packet synthetic test records
            val out = mutableListOf<HeartRateRecord>()
            for (pkt in packets) {
                val count = pkt.size / 13
                for (i in 0 until count) {
                    parseHeartRateRecord(pkt.copyOfRange(i * 13, i * 13 + 13))?.let(out::add)
                }
            }
            return out
        }

        val rangeMinutes = header?.let { (it[2].toInt() and 0xFF).takeIf { r -> r > 0 } } ?: 5
        val maxSlots = (24 * 60) / rangeMinutes
        val slotBpms = IntArray(maxSlots)

        val sortedData = packets
            .filter { it.size >= 2 && (it[0].toInt() and 0xFF) in 1..0xFE }
            .sortedBy { it[0].toInt() and 0xFF }

        for (pkt in sortedData) {
            val idx = pkt[0].toInt() and 0xFF
            if (idx == 1) {
                val len = minOf(9, pkt.size - 5)
                for (i in 0 until len) {
                    if (i < slotBpms.size) {
                        slotBpms[i] = pkt[5 + i].toInt() and 0xFF
                    }
                }
            } else if (idx >= 2) {
                val baseSlot = 9 + (idx - 2) * 13
                val len = minOf(13, pkt.size - 1)
                for (i in 0 until len) {
                    val slot = baseSlot + i
                    if (slot in slotBpms.indices) {
                        slotBpms[slot] = pkt[1 + i].toInt() and 0xFF
                    }
                }
            }
        }

        val out = mutableListOf<HeartRateRecord>()
        for (slotIdx in slotBpms.indices) {
            val bpm = slotBpms[slotIdx]
            if (bpm in 30..240) {
                val epochMs = dayStartEpochMs + slotIdx.toLong() * rangeMinutes * 60_000L
                out.add(HeartRateRecord(epochMs = epochMs, bpm = bpm))
            }
        }
        return out
    }

    /**
     * Legacy single-chunk heart-rate helper (kept for synthetic test fixtures).
     */
    fun parseHeartRateRecord(record: ByteArray): HeartRateRecord? {
        if (record.size < 13) return null
        val tsSeconds = ByteOrder.fromBeBytes(record, 0, 4)
        val bpm = ByteOrder.fromBeBytes(record, 4, 2)
        if (tsSeconds == 0 || bpm == 0) return null
        return HeartRateRecord(epochMs = tsSeconds.toLong() * 1000L, bpm = bpm)
    }

    /**
     * 0x2C AUTO_SPO2_SETTING request payload (`BloodOxygenSettingReq.getWriteInstance(enabled, interval)` in smali):
     *   [0] 0x02 (write sub-command)
     *   [1] 0x01 = enable auto SpO2, 0x00 = disable
     *   [2] intervalMinutes (e.g. 30, 60)
     */
    fun buildAutoSpo2SettingPayload(
        enabled: Boolean,
        intervalMinutes: Int = 60,
    ): ByteArray = byteArrayOf(
        0x02,
        if (enabled) 0x01 else 0x00,
        intervalMinutes.coerceIn(15, 120).toByte(),
    )

    /**
     * 0xBC 0x2A auto-SpO2 big-data payload (`BloodOxygenRepository$syncAutoBloodOxygen` /
     * `healthy/a.smali` lines 191-260):
     *   Contains N consecutive 49-byte (`0x31`) day records:
     *     [0] daysAgo (0 = today, 1 = yesterday, ...)
     *     [1..48] 24 hourly pairs: odd index = minSpO2, even index = maxSpO2.
     */
    fun parseAutoSpo2BigData(
        profileId: Long,
        todayEpochDay: Long,
        payload: ByteArray,
    ): List<Spo2Sample> {
        val recordSize = 49
        val dayCount = payload.size / recordSize
        if (dayCount <= 0) return emptyList()
        val out = mutableListOf<Spo2Sample>()
        for (d in 0 until dayCount) {
            val base = d * recordSize
            val daysAgo = payload[base].toInt() and 0xFF
            if (daysAgo > 30) continue
            val dayStartMs = (todayEpochDay - daysAgo) * SECONDS_PER_DAY * 1000L
            for (hour in 0 until 24) {
                val rawMin = payload[base + 1 + hour * 2].toInt() and 0xFF
                val rawMax = payload[base + 2 + hour * 2].toInt() and 0xFF
                val lo = minOf(rawMin, rawMax)
                val hi = maxOf(rawMin, rawMax)
                if (hi in 50..100) {
                    val validMin = if (lo in 50..100) lo else hi
                    val avg = (validMin + hi) / 2
                    out.add(
                        Spo2Sample(
                            profileId = profileId,
                            epochMs = dayStartMs + hour * 3_600_000L,
                            percent = avg,
                            minPercent = validMin,
                            maxPercent = hi,
                        ),
                    )
                }
            }
        }
        return out
    }
}

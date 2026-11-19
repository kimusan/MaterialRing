package com.kschulz.aro.data.ble

import com.kschulz.aro.data.model.Profile
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

    /**
     * 0x44 GET_SLEEP main-sleep record (re/PROTOCOL.md 7.4, traced to
     * `LargeDataHandler.parseDaySleep`). Layout after the codec strips cmd + checksum:
     *   [0..2) record type (unused here)
     *   [2..4) length (vendor reads and discards it)
     *   [4..6) end minutes-since-midnight, 2-byte big-endian
     *   [6.. )  [stage, durationMin] pairs, one byte each
     * end = midnight(epochDay) + endMinutes*60; start = end - sum(durationMin)*60. The
     * duration byte is the one the vendor sums, so it is the second of each pair, not the
     * first (the doc's `d`/`t` labels have the two reversed).
     *
     * Only the first record is decoded: the device can stream several (main sleep + naps)
     * and `parseDaySleep` walks them recursively, but that framing is unverified against a
     * live capture, so it is a documented follow-up rather than a guess.
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
     * 0x48 GET_STEP_TODAY (re/PROTOCOL.md 6.2, big-endian):
     * steps[0..3], runningSteps[4..5], calorie[6..7], walkDistance[9..10], duration[0xC..0xD].
     * [epochDay] is supplied by the caller (the phone fills dates from its calendar).
     */
    fun parseStepToday(payload: ByteArray, epochDay: Long): StepDay? {
        if (payload.size < 14) return null
        return StepDay(
            profileId = 0, // caller overwrites with the active profile before upsert
            date = epochDay,
            steps = ByteOrder.fromBeBytes(payload, 0, 4),
            distanceMeters = ByteOrder.fromBeBytes(payload, 9, 2),
            calories = ByteOrder.fromBeBytes(payload, 6, 2),
            durationMin = ByteOrder.fromBeBytes(payload, 0x0C, 2),
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
        if (type == 0x00 && payload.size >= 11) {
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
     * 0x15 heart-rate history (re/PROTOCOL.md 7.5): the body is a run of 13-byte records.
     * The exact sample layout inside a 13-byte record is device-defined, so this reads a
     * best-effort (epoch, bpm) from a fixed offset and never throws on a short final chunk.
     */
    fun parseHeartRateRecord(record: ByteArray): HeartRateRecord? {
        if (record.size < 13) return null
        val tsSeconds = ByteOrder.fromBeBytes(record, 0, 4)
        val bpm = ByteOrder.fromBeBytes(record, 4, 2)
        if (tsSeconds == 0 || bpm == 0) return null
        return HeartRateRecord(epochMs = tsSeconds.toLong() * 1000L, bpm = bpm)
    }
}

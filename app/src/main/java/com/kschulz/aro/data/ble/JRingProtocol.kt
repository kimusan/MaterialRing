package com.kschulz.aro.data.ble

import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.StepDay
import java.util.TimeZone

/**
 * Pure 20-byte frame builders and parsers for `SMART_RING` (JRing / Dialog DA1458x/DA1469x,
 * GATT service `0x56FF`, TX `0x33F3`, RX `0x33F4`).
 *
 * Every TX/RX frame is 20 bytes: `[cmdByte, 19 zero-padded payload bytes]`, no checksum.
 */
object JRingProtocol {

    const val FRAME_LEN = 20

    // Verified opcodes from live SMART_RING BluetoothLeService capture
    const val CMD_SET_TIME = 0x01
    const val CMD_SET_USER_INFO = 0x02
    const val CMD_CUR_SPORT_WALK = 0x03
    const val CMD_DEVICE_INFO = 0x0C
    const val CMD_GET_DATA_BY_DAY = 0x10
    const val CMD_CUR_SPORT_RUN = 0x13
    const val CMD_HEART_RATE_MODE = 0x15
    const val CMD_GET_HR_HISTORY = 0x16
    const val CMD_AUTO_HR_SCHEDULE = 0x19
    const val CMD_SET_GOAL = 0x1A
    const val CMD_OPTICAL_MODE = 0x23
    const val CMD_LIVE_PULSE = 0x24
    const val CMD_OPTICAL_STOP_ACK = 0x28
    const val CMD_TEMP_MODE = 0x37
    const val CMD_TEMP_RESULT = 0x38
    const val CMD_GET_TEMP_HISTORY = 0x39
    const val CMD_GET_SPO2_HISTORY = 0x40
    const val CMD_APP_STATE = 0x52

    // Optical measurement sub-modes for CMD_OPTICAL_MODE (0x23)
    const val OPTICAL_STOP = 0x00
    const val OPTICAL_SPO2 = 0x02
    const val OPTICAL_BLOOD_SUGAR = 0x03
    const val OPTICAL_BP_STRESS = 0x04

    data class TemperatureReading(
        val surfaceCelsius: Double,
        val bodyCelsius: Double,
    )

    fun buildFrame(cmd: Int, payload: ByteArray? = null): Frame {
        val buf = ByteArray(FRAME_LEN)
        buf[0] = (cmd and 0xFF).toByte()
        if (payload != null && payload.isNotEmpty()) {
            val len = minOf(payload.size, FRAME_LEN - 1)
            System.arraycopy(payload, 0, buf, 1, len)
        }
        return Frame(buf)
    }

    /** `0x01`: 4-byte LE unix timestamp (seconds) + timezone offset in hours. */
    fun buildSetTimePayload(nowMillis: Long, tzOffsetMillis: Int = TimeZone.getDefault().getOffset(nowMillis)): ByteArray {
        val sec = (nowMillis / 1000L).toInt()
        val tzHours = (tzOffsetMillis / 3_600_000).coerceIn(-12, 14)
        return byteArrayOf(
            (sec and 0xFF).toByte(),
            ((sec ushr 8) and 0xFF).toByte(),
            ((sec ushr 16) and 0xFF).toByte(),
            ((sec ushr 24) and 0xFF).toByte(),
            tzHours.toByte(),
        )
    }

    /** `0x02`: heightCm + weightKg/age/gender user info (observed: `02 AE C1 79` for 174cm, male, 46y). */
    fun buildUserInfoPayload(heightCm: Int, weightKg: Int, age: Int, gender: Int): ByteArray =
        byteArrayOf(
            heightCm.coerceIn(100, 240).toByte(),
            weightKg.coerceIn(30, 200).toByte(),
            ((if (gender == 0) 0x80 else 0x00) or (age.coerceIn(10, 120) and 0x7F)).toByte(),
        )

    /** `0x1A`: 2-byte LE daily step goal (observed: `1A A0 0F` = 4,000 steps). */
    fun buildGoalPayload(goalSteps: Int): ByteArray {
        val g = goalSteps.coerceIn(1000, 65000)
        return byteArrayOf((g and 0xFF).toByte(), ((g ushr 8) and 0xFF).toByte())
    }

    /**
     * `0x19`: Auto-HR schedule (observed: `19 00 00 17 3B 01 1E 01` = 00:00..23:59, enabled, 30 min).
     */
    fun buildAutoHrPayload(enabled: Boolean, intervalMinutes: Int = 30): ByteArray =
        byteArrayOf(
            0x00, // startHour
            0x00, // startMin
            0x17, // endHour (23)
            0x3B, // endMin (59)
            if (enabled) 0x01 else 0x00,
            intervalMinutes.coerceIn(10, 60).toByte(),
            0x01,
        )

    /** `0x10` / `0x16` / `0x39` / `0x40`: single-byte `daysAgo` (0 = today, 1 = yesterday, ...). */
    fun buildDayQueryPayload(daysAgo: Int): ByteArray =
        byteArrayOf(daysAgo.coerceIn(0, 30).toByte())

    /** `0x23`: optical measurement mode (0x00=stop, 0x02=SpO2, 0x03=BloodSugar, 0x04=BP/Stress). */
    fun buildOpticalModePayload(mode: Int): ByteArray =
        byteArrayOf((mode and 0xFF).toByte())

    /** `0x37`: live temperature measurement (0x01=start, 0x00=stop). */
    fun buildTemperatureModePayload(enable: Boolean): ByteArray =
        byteArrayOf(if (enable) 0x01 else 0x00)

    /** `0x52`: app foreground (0x01) / background (0x02) state. */
    fun buildAppStatePayload(foreground: Boolean): ByteArray =
        byteArrayOf(0x00, 0x00, 0x00, 0x00, if (foreground) 0x01 else 0x02)

    /**
     * `0x24`: Live pulse packet (`24 43 00 ...` -> 67 bpm).
     * Returns null when `0x00` (acquiring signal).
     */
    fun parseLivePulse(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val bpm = payload[0].toInt() and 0xFF
        return bpm.takeIf { it in 30..240 }
    }

    /**
     * `0x38`: Live dual temperature result (`38 E4 0C 3C 0E ...`):
     *   payload[0..1] = 2-byte LE surfaceTemp * 100 (e.g. 0x0CE4 = 3300 -> 33.00 °C)
     *   payload[2..3] = 2-byte LE bodyTemp * 100    (e.g. 0x0E3C = 3644 -> 36.44 °C)
     */
    fun parseTemperatureResult(payload: ByteArray): TemperatureReading? {
        if (payload.size < 4) return null
        val rawSurface = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val rawBody = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
        if (rawSurface !in 1500..4500 && rawBody !in 3000..4500) return null
        val surface = rawSurface / 100.0
        val body = if (rawBody in 3000..4500) rawBody / 100.0 else surface
        return TemperatureReading(surfaceCelsius = surface, bodyCelsius = body)
    }

    /**
     * `0x16 A0` HR history data packet (observed: `16 A0 C0 7A FF 6A 00 00 49 49 49 49 49 41 41 49 49 49 49 49`):
     *   payload[0] == 0xA0 (data chunk; 0xF0/0xAA are headers, 0xFF is end/empty)
     *   payload[1..4] = 4-byte LE unix timestamp (seconds)
     *   payload[5..6] = reserved / slot offset
     *   payload[7..18] = up to 12 1-byte BPM readings spaced 5 minutes apart
     */
    fun parseHrHistoryPayload(profileId: Long, payload: ByteArray): List<HeartRateSample> {
        if (payload.size < 8) return emptyList()
        val tag = payload[0].toInt() and 0xFF
        if (tag != 0xA0) return emptyList()
        val tsSec = (payload[1].toInt() and 0xFF) or
            ((payload[2].toInt() and 0xFF) shl 8) or
            ((payload[3].toInt() and 0xFF) shl 16) or
            ((payload[4].toInt() and 0xFF) shl 24)
        if (tsSec <= 0) return emptyList()
        val baseMs = tsSec.toLong() * 1000L
        val out = mutableListOf<HeartRateSample>()
        for (i in 7 until payload.size) {
            val bpm = payload[i].toInt() and 0xFF
            if (bpm in 30..240) {
                val sampleMs = baseMs + (i - 7) * 5 * 60_000L
                out.add(HeartRateSample(profileId = profileId, epochMs = sampleMs, bpm = bpm))
            }
        }
        return out
    }

    /**
     * `0x03` / `0x13` current sport packet (`03 [4B LE ts] [4B LE steps] [4B LE distance] [4B LE cal]`):
     * Parses non-zero step totals for today.
     */
    fun parseCurrentSportPayload(profileId: Long, todayEpochDay: Long, payload: ByteArray): StepDay? {
        if (payload.size < 16) return null
        val steps = (payload[4].toInt() and 0xFF) or
            ((payload[5].toInt() and 0xFF) shl 8) or
            ((payload[6].toInt() and 0xFF) shl 16) or
            ((payload[7].toInt() and 0xFF) shl 24)
        val distance = (payload[8].toInt() and 0xFF) or
            ((payload[9].toInt() and 0xFF) shl 8) or
            ((payload[10].toInt() and 0xFF) shl 16) or
            ((payload[11].toInt() and 0xFF) shl 24)
        val calories = (payload[12].toInt() and 0xFF) or
            ((payload[13].toInt() and 0xFF) shl 8) or
            ((payload[14].toInt() and 0xFF) shl 16) or
            ((payload[15].toInt() and 0xFF) shl 24)
        if (steps <= 0 || steps > 200_000) return null
        return StepDay(
            profileId = profileId,
            date = todayEpochDay,
            steps = steps,
            distanceMeters = distance.coerceIn(0, 500_000),
            calories = calories.coerceIn(0, 50_000),
            durationMin = 0,
        )
    }
}

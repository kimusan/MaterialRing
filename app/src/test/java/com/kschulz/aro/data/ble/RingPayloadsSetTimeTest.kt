package com.kschulz.aro.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the 0x01 SET_DEVICE_TIME payload matches the vendor's BCD layout traced from
 * `SetTimeReq` / `BLEDataFormatUtils.decimalToBCD` (re/PROTOCOL.md section 5). Pure JVM.
 */
class RingPayloadsSetTimeTest {

    @Test
    fun `builds a 7-byte BCD clock block`() {
        // 2026-10-30 14:05:09, language en (1).
        val p = RingPayloads.buildSetTimePayload(2026, 10, 30, 14, 5, 9, 1)
        assertArrayEquals(
            byteArrayOf(0x26, 0x10, 0x30, 0x14, 0x05, 0x09, 0x01),
            p,
        )
    }

    @Test
    fun `year is reduced mod 2000 before BCD packing`() {
        // 2021 -> carries only 21 -> BCD 0x21 (the leading century is dropped).
        val p = RingPayloads.buildSetTimePayload(2021, 1, 1, 0, 0, 0, 0)
        assertArrayEquals(
            byteArrayOf(0x21, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00),
            p,
        )
    }

    @Test
    fun `each BCD byte is tens-nibble over units-nibble`() {
        // 90 -> 0x90, 9 -> 0x09, 0 -> 0x00: a single digit must not bleed into the tens nibble.
        val p = RingPayloads.buildSetTimePayload(2026, 9, 0, 9, 0, 0, 0)
        assertArrayEquals(
            byteArrayOf(0x26, 0x09, 0x00, 0x09, 0x00, 0x00, 0x00),
            p,
        )
    }

    @Test
    fun `payload is always seven bytes`() {
        assertEquals(7, RingPayloads.buildSetTimePayload(2026, 10, 30, 14, 5, 9).size)
    }

    @Test
    fun `0x0A time setting payload encodes profile attributes and BP calibration baseline`() {
        val p = RingPayloads.buildTimeSettingPayload(
            gender = 1,
            age = 34,
            heightCm = 168,
            weightKg = 62,
            sbpBaseline = 118,
            dbpBaseline = 76,
            useMetric = true,
            use24Hour = true,
            hrWarnBpm = 165,
            hrWarnEnabled = true,
        )
        assertEquals(11, p.size)
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x00,
                0x00,
                0x01,
                34.toByte(),
                168.toByte(),
                62.toByte(),
                118.toByte(),
                76.toByte(),
                165.toByte(),
                0x01,
            ),
            p,
        )
    }

    @Test
    fun `0x21 target setting payload encodes little-endian daily step, calorie, distance, and sleep goals`() {
        val p = RingPayloads.buildTargetSettingPayload(
            goalSteps = 10_000, // 0x002710 -> LE 10 27 00
            goalCalories = 300, // 300_000 = 0x0493E0 -> LE E0 93 04
            goalDistanceMeters = 5_000, // 0x001388 -> LE 88 13 00
            goalSportMinutes = 45, // 0x002D -> LE 2D 00
            goalSleepMinutes = 480, // 0x01E0 -> LE E0 01
        )
        assertEquals(14, p.size)
        assertArrayEquals(
            byteArrayOf(
                0x02,
                0x10, 0x27, 0x00,
                0xE0.toByte(), 0x93.toByte(), 0x04,
                0x88.toByte(), 0x13, 0x00,
                0x2D, 0x00,
                0xE0.toByte(), 0x01,
            ),
            p,
        )
    }
}

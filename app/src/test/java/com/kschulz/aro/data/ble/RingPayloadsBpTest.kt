package com.kschulz.aro.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies Blood Pressure protocol payloads:
 * - 0x14 manual BP packet decoding (`ReadBlePressureRsp.smali`)
 * - 0x0D auto-BP multi-packet stream & PPG-to-mmHg conversion (`BpDataRsp.smali` & `CalcBloodPressureByHeart.smali`)
 * - 0x69 / 0x6A live on-demand BP & HR measurement (`StartHeartRateReq/Rsp`, `StopHeartRateReq`)
 */
class RingPayloadsBpTest {

    @Test
    fun `0x14 manual BP parser normalizes dbp-first vendor byte order into sbp and dbp`() {
        // ReadBlePressureRsp.smali puts dbp at payload[4] and sbp at payload[5].
        val ts = 1_700_000_000L
        val packet = byteArrayOf(
            ((ts shr 24) and 0xFF).toByte(),
            ((ts shr 16) and 0xFF).toByte(),
            ((ts shr 8) and 0xFF).toByte(),
            (ts and 0xFF).toByte(),
            78.toByte(),  // payload[4] = dbp in vendor smali
            122.toByte(), // payload[5] = sbp in vendor smali
        )
        val parsed = RingPayloads.parseManualBpPacket(packet)
        assertNotNull(parsed)
        assertEquals(1_700_000_000_000L, parsed!!.epochMs)
        assertEquals(122, parsed.sbp)
        assertEquals(78, parsed.dbp)
    }

    @Test
    fun `0x14 manual BP parser rejects end sentinel`() {
        val sentinel = byteArrayOf(
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            80.toByte(), 120.toByte(),
        )
        assertTrue(RingPayloads.isManualBpSentinel(sentinel))
        assertNull(RingPayloads.parseManualBpPacket(sentinel))
    }

    @Test
    fun `0x0D auto-BP stream decodes header bitmap slots and converts raw PPG bytes`() {
        // Header packet: type=0x00, year=26 (2026), month=10, day=30, timeDelay=30 min,
        // bitmap bytes[5..10]: byte 5 = 0b00000101 (slots 0 and 2 -> 0 min and 60 min).
        val header = byteArrayOf(
            0x00,
            26, 10, 30,
            30,
            0x05, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        // Value chunk packet: type=0x01, raw values for slot 0 (65) and slot 2 (85).
        val values = byteArrayOf(
            0x01,
            65.toByte(),
            85.toByte(),
        )
        val dayStartMs = 1_761_782_400_000L
        val readings = RingPayloads.parseAutoBpStream(
            packets = listOf(header, values),
            dayStartEpochMsForDate = { y, m, d ->
                assertEquals(2026, y)
                assertEquals(10, m)
                assertEquals(30, d)
                dayStartMs
            },
            age = 35,
            sbpBaseline = 120,
            dbpBaseline = 80,
        )
        assertEquals(2, readings.size)
        // Slot 0 at +0 min, raw=65 (resting baseline -> 120/80)
        assertEquals(dayStartMs, readings[0].epochMs)
        assertEquals(120, readings[0].sbp)
        assertEquals(80, readings[0].dbp)
        // Slot 2 at +60 min, raw=85 (elevated pulse -> higher SBP/DBP)
        assertEquals(dayStartMs + 60 * 60_000L, readings[1].epochMs)
        assertTrue(readings[1].sbp > readings[0].sbp)
        assertTrue(readings[1].dbp > readings[0].dbp)
    }

    @Test
    fun `0x69 and 0x6A live measurement builders and parser handle BP and wearing errors`() {
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            RingPayloads.buildStartMeasurementPayload(RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE),
        )
        assertArrayEquals(
            byteArrayOf(0x02, 121.toByte(), 79.toByte()),
            RingPayloads.buildStopMeasurementPayload(RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE, 121, 79),
        )

        val liveOk = RingPayloads.parseLiveMeasurementPacket(
            byteArrayOf(0x02, 0x00, 74.toByte(), 121.toByte(), 79.toByte()),
        )
        assertNotNull(liveOk)
        assertEquals(RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE, liveOk!!.type)
        assertEquals(0, liveOk.errCode)
        assertEquals(74, liveOk.value)
        assertEquals(121, liveOk.sbp)
        assertEquals(79, liveOk.dbp)

        val wearingErr = RingPayloads.parseLiveMeasurementPacket(
            byteArrayOf(0x02, 0x01, 0x00),
        )
        assertNotNull(wearingErr)
        assertEquals(1, wearingErr!!.errCode)
    }
}

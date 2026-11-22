package com.kschulz.aro.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamsungRingProtocolTest {

    @Test
    fun `buildBatterySyncFrame wraps Channel 11 doubled envelope and MSG_ID 3`() {
        val frame = SamsungRingProtocol.buildBatterySyncFrame()
        assertArrayEquals(
            byteArrayOf(0x0B, 0x0B, 0x03),
            frame.bytes,
        )
    }

    @Test
    fun `parseBatteryInfo decodes live RFC wire trace 0b 0b 42 06 00 05 3f 07 ff 08 ff`() {
        // From RFC-SGR-001 §10.3: 63% battery, not charging, off-cradle (0xFF)
        val raw = byteArrayOf(
            0x0B, 0x0B, 0x42,
            0x06, 0x00,
            0x05, 0x3F,
            0x07, 0xFF.toByte(),
            0x08, 0xFF.toByte(),
        )
        val status = SamsungRingProtocol.parseBatteryInfo(raw)
        assertNotNull(status)
        assertEquals(63, status!!.level)
        assertFalse(status.charging)
        assertNull(status.cradleLevel)
        assertNull(status.cradleCharging)
    }

    @Test
    fun `parseWearingState decodes on-finger and removed frames from RFC Appendix B_9`() {
        val removed = byteArrayOf(0x0B, 0x0B, 0x34, 0x37, 0x00)
        val onFinger = byteArrayOf(0x0B, 0x0B, 0x34, 0x37, 0x01)
        assertEquals(false, SamsungRingProtocol.parseWearingState(removed))
        assertEquals(true, SamsungRingProtocol.parseWearingState(onFinger))
    }

    @Test
    fun `parseStandardHeartRateMeasurement decodes 0x2A37 notification from RFC Appendix B_3`() {
        // From RFC-SGR-001 Appendix B.3: 06 4a 00 00 00 00 -> 74 bpm, contact detected
        val raw = byteArrayOf(0x06, 0x4A, 0x00, 0x00, 0x00, 0x00)
        assertEquals(74, SamsungRingProtocol.parseStandardHeartRateMeasurement(raw))
        assertFalse(SamsungRingProtocol.isStandardHrOffFinger(raw))

        // Off-finger reports 0 bpm
        val offFinger = byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertEquals(0, SamsungRingProtocol.parseStandardHeartRateMeasurement(offFinger))
        assertTrue(SamsungRingProtocol.isStandardHrOffFinger(offFinger))
    }

    @Test
    fun `buildRtcSyncFrame encodes variable-length SAP message with epoch ms and tz offset`() {
        val epochMs = 1_763_820_000_000L
        val tzOffsetMs = 3_600_000
        val frame = SamsungRingProtocol.buildRtcSyncFrame(epochMs, tzOffsetMs)
        val pkt = SamsungRingProtocol.decodeSapPacket(frame.bytes)
        assertNotNull(pkt)
        assertEquals(SamsungRingProtocol.CHANNEL_SETTINGS, pkt!!.channel)
        assertTrue(pkt.isVariableLength)
        assertEquals(SamsungRingProtocol.SETTINGS_MSG_RTC_INFO_SET, pkt.msgId)
        assertEquals(epochMs, pkt.params[SamsungRingProtocol.PARAM_START_TIME])
        assertEquals(tzOffsetMs.toLong(), pkt.params[SamsungRingProtocol.PARAM_TIME_OFFSET])
    }

    @Test
    fun `parseHealthPacket decodes Channel 10 pedometer, HR, SpO2, skin temp, stress, and sleep`() {
        val startMs = 1_763_766_000_000L
        val stopMs = startMs + 7 * 3_600_000L // 7 hours sleep
        // Variable-length Channel 10 response (0x0A 0x0A, header = 0xC3 = Variable + Response + MSG 3)
        // 8 parameters: START_TIME(1), STOP_TIME(2), STEP_COUNT(16), DISTANCE(17), CALORIES(19),
        // HR_ACTUAL(32), SPO2(169), SKIN_TEMPERATURE(165), STRESS_SCORE(96), SLEEP_EFFICIENCY(80)
        val buf = java.io.ByteArrayOutputStream()
        buf.write(0x0A)
        buf.write(0x0A)
        buf.write(0xC3) // Variable-length (0x80) | Response (0x40) | MSG_ID=3
        buf.write(10) // 10 parameters

        fun writeLe32(v: Int) {
            buf.write(v and 0xFF)
            buf.write((v ushr 8) and 0xFF)
            buf.write((v ushr 16) and 0xFF)
            buf.write((v ushr 24) and 0xFF)
        }
        fun writeLe64(v: Long) {
            for (i in 0 until 8) buf.write(((v ushr (i * 8)) and 0xFFL).toInt())
        }

        buf.write(SamsungRingProtocol.PARAM_START_TIME); writeLe64(startMs)
        buf.write(SamsungRingProtocol.PARAM_STOP_TIME); writeLe64(stopMs)
        buf.write(SamsungRingProtocol.PARAM_STEP_COUNT); writeLe32(8420)
        buf.write(SamsungRingProtocol.PARAM_DISTANCE); writeLe32(6150)
        buf.write(SamsungRingProtocol.PARAM_CALORIES); writeLe32(340)
        buf.write(SamsungRingProtocol.PARAM_HR_ACTUAL); writeLe32(68)
        buf.write(SamsungRingProtocol.PARAM_SPO2_PERCENT); writeLe32(97)
        buf.write(SamsungRingProtocol.PARAM_SKIN_TEMPERATURE); writeLe32(3580) // 35.80 °C
        buf.write(SamsungRingProtocol.PARAM_STRESS_SCORE); writeLe32(31)
        buf.write(SamsungRingProtocol.PARAM_SLEEP_EFFICIENCY); writeLe32(88)

        val batch = SamsungRingProtocol.parseHealthPacket(
            profileId = 1L,
            todayEpochDay = 20_413L,
            nowEpochMs = stopMs,
            raw = buf.toByteArray(),
        )

        assertEquals(1, batch.steps.size)
        assertEquals(8420, batch.steps[0].steps)
        assertEquals(6150, batch.steps[0].distanceMeters)
        assertEquals(340, batch.steps[0].calories)

        assertEquals(1, batch.heartRates.size)
        assertEquals(68, batch.heartRates[0].bpm)

        assertEquals(1, batch.spo2Samples.size)
        assertEquals(97, batch.spo2Samples[0].percent)

        assertEquals(1, batch.temperatures.size)
        assertEquals(35.80, batch.temperatures[0].surfaceCelsius, 0.01)

        assertEquals(1, batch.stressSamples.size)
        assertEquals(31, batch.stressSamples[0].score)

        assertEquals(1, batch.sleepSessions.size)
        val (session, segments) = batch.sleepSessions[0]
        assertEquals(startMs, session.startEpoch)
        assertEquals(stopMs, session.endEpoch)
        assertTrue(segments.isNotEmpty())
    }
}

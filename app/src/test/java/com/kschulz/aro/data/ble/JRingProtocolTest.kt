package com.kschulz.aro.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class JRingProtocolTest {

    @Test
    fun parseTemperatureResultMatchesCapturedSmartRingPacket() {
        // Captured live from SMART_RING: 38 E4 0C 3C 0E 00 ...
        // surface = 33.00 °C, body = 36.44 °C
        val payload = byteArrayOf(0xE4.toByte(), 0x0C, 0x3C, 0x0E, 0x00, 0x00)
        val reading = JRingProtocol.parseTemperatureResult(payload)
        assertNotNull(reading)
        assertEquals(33.00, reading!!.surfaceCelsius, 0.01)
        assertEquals(36.44, reading.bodyCelsius, 0.01)
    }

    @Test
    fun parseLivePulseMatchesCapturedSmartRingPacket() {
        // Captured live: 24 43 00 ... -> 67 bpm
        assertEquals(67, JRingProtocol.parseLivePulse(byteArrayOf(0x43, 0x00)))
        assertEquals(null, JRingProtocol.parseLivePulse(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun parseHrHistoryPayloadMatchesCapturedChunk() {
        // Captured live: 16 A0 C0 7A FF 6A 00 00 49 49 41
        val payload = byteArrayOf(
            0xA0.toByte(),
            0xC0.toByte(), 0x7A, 0xFF.toByte(), 0x6A,
            0x00, 0x00,
            0x49, 0x49, 0x41,
        )
        val samples = JRingProtocol.parseHrHistoryPayload(profileId = 1L, payload = payload)
        assertEquals(3, samples.size)
        assertEquals(73, samples[0].bpm)
        assertEquals(73, samples[1].bpm)
        assertEquals(65, samples[2].bpm)
    }

    @Test
    fun buildFrameIsAlways20Bytes() {
        val frame = JRingProtocol.buildFrame(JRingProtocol.CMD_SET_GOAL, JRingProtocol.buildGoalPayload(4000))
        assertEquals(20, frame.bytes.size)
        assertEquals(0x1A.toByte(), frame.bytes[0])
        assertEquals(0xA0.toByte(), frame.bytes[1])
        assertEquals(0x0F.toByte(), frame.bytes[2])
    }
}

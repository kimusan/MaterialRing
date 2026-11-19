package com.kschulz.aro.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class Spo2PayloadsTest {

    @Test
    fun parseAutoSpo2BigDataDecodes49ByteDayRecord() {
        val payload = ByteArray(49)
        payload[0] = 0 // today (daysAgo = 0)
        // Hour 8: min=96, max=99
        payload[1 + 8 * 2] = 96.toByte()
        payload[2 + 8 * 2] = 99.toByte()
        // Hour 14: min=98, max=98
        payload[1 + 14 * 2] = 98.toByte()
        payload[2 + 14 * 2] = 98.toByte()

        val todayEpochDay = 20_000L
        val samples = RingPayloads.parseAutoSpo2BigData(profileId = 1L, todayEpochDay = todayEpochDay, payload = payload)

        assertEquals(2, samples.size)
        assertEquals(96, samples[0].minPercent)
        assertEquals(99, samples[0].maxPercent)
        assertEquals(97, samples[0].percent)
        assertEquals(todayEpochDay * 86_400_000L + 8 * 3_600_000L, samples[0].epochMs)
        assertEquals(98, samples[1].percent)
    }

    @Test
    fun parseLiveSpo2MeasurementPacket() {
        val pkt = RingPayloads.parseLiveMeasurementPacket(byteArrayOf(0x03, 0x00, 98.toByte(), 0x00, 0x00))
        assertNotNull(pkt)
        assertEquals(RingPayloads.MEASURE_TYPE_SPO2, pkt!!.type)
        assertEquals(0, pkt.errCode)
        assertEquals(98, pkt.value)
    }
}

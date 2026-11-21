package com.kschulz.aro.data.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the 0x44 sleep-record parser matches the vendor layout traced from
 * `LargeDataHandler.parseDaySleep` (re/PROTOCOL.md 7.4). Pure JVM.
 */
class RingPayloadsSleepTest {

    private val day = 1000L

    @Test
    fun `parses end time and stage-duration pairs`() {
        // type[0..2)=00 01, len[2..4)=00 04, endMin[4..6)=01 2C (=300 = 05:00),
        // segments[6..)=[stage 1, 180min][stage 2, 90min] -> total 270min.
        val payload = byteArrayOf(
            0x00, 0x01,
            0x00, 0x04,
            0x01, 0x2C,
            0x01, 180.toByte(),
            0x02, 90.toByte(),
        )
        val p = RingPayloads.parseSleep(payload, day)
        requireNotNull(p)
        // end = midnight + 300*60 = day*86400 + 18000
        assertEquals((day * 86_400 + 18_000) * 1000L, p.endEpochMs)
        // start = end - 270*60 = day*86400 + 1800
        assertEquals((day * 86_400 + 1_800) * 1000L, p.startEpochMs)
        assertEquals(2, p.stages.size)
        assertEquals(1, p.stages[0].stage)
        assertEquals(180, p.stages[0].durationMin)
        assertEquals(2, p.stages[1].stage)
        assertEquals(90, p.stages[1].durationMin)
        assertFalse(p.isNap)
    }

    @Test
    fun `duration byte is the unsigned second of each pair`() {
        // stage 0x80 (negative as signed), duration 0x10. Only the duration sums into start.
        val payload = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x00, 0x10, 0x80.toByte(), 0x10)
        val p = RingPayloads.parseSleep(payload, day)
        requireNotNull(p)
        assertEquals(0x80, p.stages.single().stage)
        assertEquals(0x10, p.stages.single().durationMin)
        // start = end - 16*60
        val end = (day * 86_400 + 0x10 * 60) * 1000L
        assertEquals(end - 16 * 60_000L, p.startEpochMs)
    }

    @Test
    fun `rejects a payload too short for the fixed header`() {
        assertNull(RingPayloads.parseSleep(byteArrayOf(0, 0, 0), day))
    }

    @Test
    fun `a record with no segments has a zero-length sleep`() {
        val payload = byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x02, 0x58) // endMin=600, no stages
        val p = RingPayloads.parseSleep(payload, day)
        requireNotNull(p)
        assertTrue(p.stages.isEmpty())
        assertEquals(p.endEpochMs, p.startEpochMs)
    }

    @Test
    fun `new sleep protocol converts endMinutes relative to local midnight`() {
        // 1 day record, daysAgo=0, bodyLen=6 (2B reserved + 2B endMin + 1 stage pair):
        // endMinutes = 7*60 + 58 = 478 (0x01DE LE -> 0xDE, 0x01), 120 min deep sleep (rawType=2).
        val payload = byteArrayOf(
            0x01, // dayCount
            0x00, // daysAgo = 0
            0x06, // bodyLen = 6
            0x00, 0x00, // reserved
            0xDE.toByte(), 0x01, // endMinutes = 478 (07:58 local)
            0x02, 120.toByte(), // Deep sleep, 120 min
        )
        val copenhagen = java.time.ZoneId.of("Europe/Copenhagen")
        val localDate = java.time.LocalDate.of(2026, 11, 21)
        val parsed = RingPayloads.parseNewSleepProtocolPayload(
            payload = payload,
            todayEpochDay = localDate.toEpochDay(),
            dayStartEpochMsForDay = { epochDay ->
                java.time.LocalDate.ofEpochDay(epochDay)
                    .atStartOfDay(copenhagen)
                    .toInstant()
                    .toEpochMilli()
            },
        )
        assertEquals(1, parsed.size)
        val endLocal = java.time.Instant.ofEpochMilli(parsed.first().endEpochMs).atZone(copenhagen)
        assertEquals(7, endLocal.hour)
        assertEquals(58, endLocal.minute)
    }
}

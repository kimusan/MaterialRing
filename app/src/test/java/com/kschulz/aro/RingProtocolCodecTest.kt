package com.kschulz.aro.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the codec matches the reverse-engineered ring framing in re/PROTOCOL.md.
 * Pure JVM; run with `./gradlew :app:testDebugUnitTest`.
 */
class RingProtocolCodecTest {

    private val codec = RingProtocolCodec()

    @Test
    fun `crc16 matches MODBUS check value`() {
        // init 0xFFFF, poly 0x8005 (reflected as 0xA001), refin/refout = CRC-16/MODBUS.
        // (Not ARC: ARC uses init 0x0000, check 0xBB3D. The smali initialises crc to 0xFFFF.)
        val input = "123456789".toByteArray()
        assertEquals(0x4B37, Crc16.calc(input))
    }

    @Test
    fun `crc16 of empty input is the init value`() {
        assertEquals(0xFFFF, Crc16.calc(ByteArray(0)))
    }

    @Test
    fun `sum check verifies a valid frame`() {
        // cmd 0x14, payload [0x00, 0x32], checksum 0x14+0x00+0x32 = 0x46
        val frame = byteArrayOf(0x14, 0x00, 0x32, 0x46)
        assertTrue(SumCheck.verify(frame))
    }

    @Test
    fun `sum check rejects a corrupt frame`() {
        val frame = byteArrayOf(0x14, 0x00, 0x32, 0x47) // wrong checksum
        assertFalse(SumCheck.verify(frame))
    }

    @Test
    fun `toLeBytes is little endian`() {
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), ByteOrder.toLeBytes(0x04030201))
    }

    @Test
    fun `fromBeBytes is big endian`() {
        assertEquals(0x04030201, ByteOrder.fromBeBytes(byteArrayOf(0x04, 0x03, 0x02, 0x01), 0, 4))
    }

    @Test
    fun `encode builds the documented TX layout`() {
        val payload = byteArrayOf(0x14, 0x00, 0x32)
        val frame = codec.encode(0x48, payload)
        // [0xC4][cmd][len_lo][len_hi][crc_lo][crc_hi][payload]
        assertEquals(0xC4.toByte(), frame.bytes[0])
        assertEquals(0x48.toByte(), frame.bytes[1])
        assertEquals(0x03.toByte(), frame.bytes[2])
        assertEquals(0x00.toByte(), frame.bytes[3])
        val crc = Crc16.calc(payload)
        assertEquals((crc and 0xFF).toByte(), frame.bytes[4])
        assertEquals(((crc shr 8) and 0xFF).toByte(), frame.bytes[5])
        assertArrayEquals(payload, frame.bytes.copyOfRange(6, frame.bytes.size))
    }

    @Test
    fun `encode empty payload is header only with FF FF crc slots`() {
        val frame = codec.encode(0x03)
        assertArrayEquals(
            byteArrayOf(0xC4.toByte(), 0x03, 0x00, 0x00, -1, -1),
            frame.bytes,
        )
    }

    @Test
    fun `decode strips cmd and checksum and extracts payload`() {
        // cmd 0x1E, payload [0x7B] (heart rate 123), checksum 0x1E+0x7B = 0x99
        val raw = byteArrayOf(0x1E, 0x7B, 0x99.toByte())
        val parsed = codec.decode(raw)
        assertEquals(0x1E, parsed?.cmd)
        assertFalse(parsed?.error ?: true)
        assertArrayEquals(byteArrayOf(0x7B), parsed?.payload)
    }

    @Test
    fun `decode sets the error flag when bit 7 is set`() {
        // cmd byte 0x9E = 0x1E | 0x80, payload [0x01], checksum 0x9E+0x01 = 0x9F
        val raw = byteArrayOf(0x9E.toByte(), 0x01, 0x9F.toByte())
        val parsed = codec.decode(raw)
        assertEquals(0x1E, parsed?.cmd)
        assertTrue(parsed?.error ?: false)
    }

    @Test
    fun `decode returns null on a bad checksum`() {
        val raw = byteArrayOf(0x1E, 0x7B, 0x98.toByte()) // checksum off by one
        assertNull(codec.decode(raw))
    }

    @Test
    fun `decode returns null on a too-short frame`() {
        assertNull(codec.decode(byteArrayOf(0x1E)))
    }
}

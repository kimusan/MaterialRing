package com.kschulz.qring.data.ble

/**
 * QRing-specific protocol codec. The swappable brain of the BLE layer: a different
 * ring brand provides a different [RingProtocolCodec] and nothing above `data/ble`
 * changes. Pure Kotlin, no Android dependency, so it is fully unit-testable against
 * captured frames.
 *
 * Framing is asymmetric (see re/PROTOCOL.md sections 2 and 3):
 *  - TX: [0xC4 | cmd | len_lo | len_hi | crc16_lo | crc16_hi | payload]
 *  - RX: [cmd | payload | additive_checksum]
 */
class RingProtocolCodec {

    companion object {
        const val SYNC_BYTE = 0xC4.toByte()
        const val HEADER_LEN = 6
        const val ERROR_FLAG = 0x80
    }

    /**
     * Build a TX frame.
     * @param cmd opcode (use [Opcodes]).
     * @param payload body bytes, or empty/null for a header-only frame.
     */
    fun encode(cmd: Int, payload: ByteArray? = null): Frame {
        val p = payload?.takeIf { it.isNotEmpty() }
        return if (p == null) {
            Frame(byteArrayOf(SYNC_BYTE, cmd.toByte(), 0, 0, -1, -1))
        } else {
            val frame = ByteArray(HEADER_LEN + p.size)
            frame[0] = SYNC_BYTE
            frame[1] = cmd.toByte()
            // length, little-endian
            frame[2] = (p.size and 0xFF).toByte()
            frame[3] = ((p.size shr 8) and 0xFF).toByte()
            // crc16 over payload, little-endian
            val crc = Crc16.calc(p)
            frame[4] = (crc and 0xFF).toByte()
            frame[5] = ((crc shr 8) and 0xFF).toByte()
            System.arraycopy(p, 0, frame, HEADER_LEN, p.size)
            Frame(frame)
        }
    }

    /**
     * Decode an RX notify frame.
     * @return the parsed frame, or null if the additive checksum fails.
     */
    fun decode(raw: ByteArray): ParsedFrame? {
        if (raw.size < 2) return null
        if (!SumCheck.verify(raw)) return null
        val first = raw[0].toInt() and 0xFF
        val cmd = first and ERROR_FLAG.inv()
        val error = (first and ERROR_FLAG) != 0
        val payload = raw.copyOfRange(1, raw.size - 1)
        return ParsedFrame(cmd = cmd, error = error, payload = payload)
    }
}

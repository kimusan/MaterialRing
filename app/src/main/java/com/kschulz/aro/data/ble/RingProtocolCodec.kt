package com.kschulz.aro.data.ble

/**
 * Ring-specific protocol codec. The swappable brain of the BLE layer: a different
 * ring brand provides a different [RingProtocolCodec] and nothing above `data/ble`
 * changes. Pure Kotlin, no Android dependency, so it is fully unit-testable against
 * captured frames.
 *
 * Standard commands (`BaseReqCmd.getData()` / `QCBluetoothCallbackReceiver` in vendor smali)
 * use symmetric 16-byte fixed frames (`Constants.CMD_DATA_LENGTH = 16`):
 *  - TX: `[cmd | payload (14 bytes, zero-padded) | sum(bytes[0..14]) & 0xFF]`
 *  - RX: `[cmd | payload (14 bytes) | sum(bytes[0..14]) & 0xFF]`
 *
 * Big-data transfers (`LargeDataHandler` on `de5bf728-...`) use variable-length header frames:
 *  - TX: `[0xBC | action | len_lo | len_hi | crc16_lo | crc16_hi | payload]`
 */
class RingProtocolCodec {

    companion object {
        const val CMD_FRAME_LEN = 16
        const val MAX_CMD_PAYLOAD_LEN = 14
        const val LARGE_DATA_SYNC_BYTE = 0xBC.toByte()
        const val LEGACY_SYNC_BYTE = 0xC4.toByte()
        const val HEADER_LEN = 6
        const val ERROR_FLAG = 0x80
    }

    /**
     * Build a 16-byte standard command TX frame (`BaseReqCmd.getData()`).
     * @param cmd opcode (use [Opcodes]).
     * @param payload up to 14 body bytes (zero-padded to 14 bytes).
     */
    fun encode(cmd: Int, payload: ByteArray? = null): Frame {
        if (cmd == Opcodes.NEW_SLEEP_PROTOCOL) {
            return encodeLargeData(cmd, payload)
        }
        val frame = ByteArray(CMD_FRAME_LEN)
        frame[0] = (cmd and 0xFF).toByte()
        if (payload != null && payload.isNotEmpty()) {
            val copyLen = minOf(payload.size, MAX_CMD_PAYLOAD_LEN)
            System.arraycopy(payload, 0, frame, 1, copyLen)
        }
        frame[CMD_FRAME_LEN - 1] = SumCheck.checksum(frame)
        return Frame(frame)
    }

    /**
     * Build a variable-length big-data TX frame (`LargeDataHandler.addHeader`).
     */
    fun encodeLargeData(
        cmd: Int,
        payload: ByteArray? = null,
        syncByte: Byte = LARGE_DATA_SYNC_BYTE,
    ): Frame {
        val p = payload?.takeIf { it.isNotEmpty() }
        return if (p == null) {
            Frame(byteArrayOf(syncByte, cmd.toByte(), 0, 0, -1, -1))
        } else {
            val frame = ByteArray(HEADER_LEN + p.size)
            frame[0] = syncByte
            frame[1] = cmd.toByte()
            frame[2] = (p.size and 0xFF).toByte()
            frame[3] = ((p.size shr 8) and 0xFF).toByte()
            val crc = Crc16.calc(p)
            frame[4] = (crc and 0xFF).toByte()
            frame[5] = ((crc shr 8) and 0xFF).toByte()
            System.arraycopy(p, 0, frame, HEADER_LEN, p.size)
            Frame(frame)
        }
    }

    /**
     * Decode an RX notify frame (either a 16-byte command frame or a 0xBC big-data frame).
     * @return the parsed frame, or null if the checksum/header fails.
     */
    fun decode(raw: ByteArray): ParsedFrame? {
        if (raw.size >= HEADER_LEN && raw[0] == LARGE_DATA_SYNC_BYTE) {
            val cmd = raw[1].toInt() and 0xFF
            val len = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            val end = minOf(raw.size, HEADER_LEN + len)
            val payload = raw.copyOfRange(HEADER_LEN, end)
            return ParsedFrame(cmd = cmd, error = false, payload = payload)
        }
        if (raw.size < 2) return null
        if (!SumCheck.verify(raw)) return null
        val first = raw[0].toInt() and 0xFF
        val cmd = first and ERROR_FLAG.inv()
        val error = (first and ERROR_FLAG) != 0
        val payload = raw.copyOfRange(1, raw.size - 1)
        return ParsedFrame(cmd = cmd, error = error, payload = payload)
    }
}

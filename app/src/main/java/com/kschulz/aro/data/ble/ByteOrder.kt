package com.kschulz.aro.data.ble

/**
 * Directional byte-order helpers. The ring protocol is deliberately asymmetric:
 * phone->device multi-byte integers are little-endian, device->phone are big-endian.
 * These two helpers must NOT be unified (see re/PROTOCOL.md section 3).
 */
object ByteOrder {
    /** Little-endian (LSB first). For phone->device payloads. Traced to `intToByteArray`. */
    fun toLeBytes(value: Int, length: Int = 4): ByteArray {
        require(length in 1..8)
        val out = ByteArray(length)
        var v = value
        for (i in 0 until length) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    /** Big-endian (MSB first). For device->phone values. Traced to `bytes2Int`. */
    fun fromBeBytes(data: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && offset + length <= data.size) { "offset/length out of bounds" }
        var result = 0
        for (i in 0 until length) {
            result = (result shl 8) or (data[offset + i].toInt() and 0xFF)
        }
        return result
    }

    /** Little-endian read, for completeness. */
    fun fromLeBytes(data: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && offset + length <= data.size) { "offset/length out of bounds" }
        var result = 0
        var shift = 0
        for (i in 0 until length) {
            result = result or ((data[offset + i].toInt() and 0xFF) shl shift)
            shift += 8
        }
        return result
    }
}

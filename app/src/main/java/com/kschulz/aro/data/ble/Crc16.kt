package com.kschulz.aro.data.ble

/**
 * CRC-16 used by the ring TX framing.
 * Init 0xFFFF, polynomial 0xA001, reflected bit-wise (LSB first).
 * Traced to smali `CRC16.calcCrc16([B)I`.
 */
object Crc16 {
    private const val POLYNOMIAL = 0xA001
    private const val INITIAL = 0xFFFF

    fun calc(payload: ByteArray): Int {
        var crc = INITIAL
        for (b in payload) {
            crc = crc.xor(b.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor POLYNOMIAL else crc ushr 1
            }
        }
        return crc and 0xFFFF
    }
}

package com.kschulz.aro.data.ble

/**
 * Additive checksum used by the QRing RX framing.
 * The last byte must equal the low 8 bits of the sum of all preceding bytes.
 * Traced to smali `QCDataParser.checkCrc`.
 */
object SumCheck {
    /** @return true if `data[data.size-1]` is a valid additive checksum of the rest. */
    fun verify(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        var sum = 0
        for (i in 0 until data.size - 1) {
            sum += data[i].toInt()
        }
        return (sum and 0xFF) == (data[data.size - 1].toInt() and 0xFF)
    }

    fun checksum(payloadWithCmd: ByteArray): Byte {
        var sum = 0
        for (b in payloadWithCmd) sum += b.toInt()
        return (sum and 0xFF).toByte()
    }
}

package com.kschulz.aro.data.ble

/**
 * A framed byte payload ready to be written to the device (TX).
 * See re/PROTOCOL.md section 2.1 for the layout.
 */
data class Frame(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is Frame && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex()

    fun toHex(): String = bytes.joinToString(" ") { "%02X".format(it) }
}

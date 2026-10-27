package com.kschulz.qring.data.ble

/**
 * A decoded device->phone frame (RX). The cmd byte and trailing checksum are stripped;
 * `payload` is the body between them. See re/PROTOCOL.md section 2.2.
 */
data class ParsedFrame(
    val cmd: Int,          // data[0] and 0x7F (the real opcode, error bit removed)
    val error: Boolean,    // (data[0] and 0x80) != 0
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ParsedFrame && cmd == other.cmd && error == other.error &&
            payload.contentEquals(other.payload)

    override fun hashCode(): Int = (cmd * 31 + error.hashCode()) * 31 + payload.contentHashCode()

    override fun toString(): String =
        "ParsedFrame(cmd=0x${"%02X".format(cmd)}, error=$error, payload=${payload.joinToString(" ") { "%02X".format(it) }})"
}

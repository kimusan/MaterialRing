package com.kschulz.aro.data.ble

/**
 * Temporary debug logging for the on-device connect/sync investigation.
 * Every line is prefixed so `adb logcat | Select-String AroDbg` (or grep) captures
 * the whole chain. Remove once the root cause is confirmed and fixed.
 */
object AroDbg {
    fun d(msg: String) {
        println("[AroDbg] $msg")
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}

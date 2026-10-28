package com.kschulz.qring.data.ble

/** A discovered ring during scanning. */
data class ScannedRing(
    val name: String,
    val macAddress: String,
    val rssi: Int,
)

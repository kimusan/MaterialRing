package com.kschulz.aro.data.model

import androidx.room.Entity

/** A bound ring device, keyed by its BLE MAC address (the stable identity). Revision
 *  strings are filled from the DEVICE_INFO service after first connect. */
@Entity(tableName = "rings", primaryKeys = ["macAddress"])
data class Ring(
    val macAddress: String,
    val displayName: String,
    val hwRevision: String = "",
    val fwRevision: String = "",
    val boundAtEpochMs: Long,
)

package com.kschulz.aro.data.model

import androidx.room.Entity

/** A single-row key/value setting. Booleans are stored as 1/0. Keeps the active profile
 *  and the Health Connect toggle reactive without a second persistence library. */
@Entity(tableName = "app_settings", primaryKeys = ["key"])
data class AppSetting(
    val key: String,
    val value: Long,
)

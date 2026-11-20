package com.kschulz.aro.data.model

import androidx.room.Entity

/**
 * Blood oxygen saturation (SpO2 %) measurement, either from live on-demand measurement
 * (0x69 type=0x03) or hourly auto-SpO2 sync (0xBC 0x2A).
 */
@Entity(tableName = "spo2_samples", primaryKeys = ["profileId", "epochMs"])
data class Spo2Sample(
    val profileId: Long,
    val epochMs: Long,
    val percent: Int,
    val minPercent: Int = percent,
    val maxPercent: Int = percent,
    val ringMac: String = "",
)

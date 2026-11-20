package com.kschulz.aro.data.model

import androidx.room.Entity

/**
 * Estimated physiological stress score (1..100), derived from heart-rate elevation
 * and variability patterns for both Oudmon/QRing and JRing/SMART_RING devices.
 */
@Entity(tableName = "stress_samples", primaryKeys = ["profileId", "epochMs"])
data class StressSample(
    val profileId: Long,
    val epochMs: Long,
    val score: Int,
    val ringMac: String = "",
)

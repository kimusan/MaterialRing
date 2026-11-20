package com.kschulz.aro.data.model

import androidx.room.Entity

/** A stored heart-rate sample. Composite PK (profileId, epochMs) makes sync idempotent. */
@Entity(tableName = "heart_rate_samples", primaryKeys = ["profileId", "epochMs"])
data class HeartRateSample(
    val profileId: Long,
    val epochMs: Long,
    val bpm: Int,
    val ringMac: String = "",
)

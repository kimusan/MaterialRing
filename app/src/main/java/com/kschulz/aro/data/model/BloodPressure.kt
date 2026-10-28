package com.kschulz.aro.data.model

import androidx.room.Entity

/** A blood-pressure reading in mmHg. Composite PK (profileId, epochMs, source) keeps a
 *  manual and an auto reading at the same instant distinct, and makes sync idempotent. */
@Entity(tableName = "blood_pressure", primaryKeys = ["profileId", "epochMs", "source"])
data class BloodPressure(
    val profileId: Long,
    val epochMs: Long,
    val sbp: Int,
    val dbp: Int,
    val source: BpSource,
)

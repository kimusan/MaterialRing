package com.kschulz.aro.data.model

import androidx.room.Entity

/** One day of step activity per ring. Composite PK (profileId, date, ringMac) makes sync
 *  idempotent and lets multiple rings worn on the same day be viewed individually or summed.
 *  [date] is a UTC epoch day. */
@Entity(tableName = "step_days", primaryKeys = ["profileId", "date", "ringMac"])
data class StepDay(
    val profileId: Long,
    val date: Long,
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,
    val durationMin: Int,
    val ringMac: String = "",
)

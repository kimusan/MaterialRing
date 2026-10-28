package com.kschulz.qring.data.model

import androidx.room.Entity

/** One day of step activity. Composite PK (profileId, date) makes sync idempotent:
 *  re-pulling the same day replaces in place. [date] is a UTC epoch day. */
@Entity(tableName = "step_days", primaryKeys = ["profileId", "date"])
data class StepDay(
    val profileId: Long,
    val date: Long,
    val steps: Int,
    val distanceMeters: Int,
    val calories: Int,
    val durationMin: Int,
)

package com.kschulz.aro.data.model

import androidx.room.Entity

/** A derived body-battery score for one hour. This is a wellness metric, not a clinical
 *  vital. Composite PK (profileId, epochHour) makes sync idempotent. [epochHour] is a
 *  UTC epoch hour (epochMs / 3_600_000). */
@Entity(tableName = "body_battery", primaryKeys = ["profileId", "epochHour"])
data class BodyBatteryPoint(
    val profileId: Long,
    val epochHour: Long,
    val score: Int,
)

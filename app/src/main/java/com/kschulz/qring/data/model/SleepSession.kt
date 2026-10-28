package com.kschulz.qring.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A sleep period (main sleep or nap). Surrogate PK so SleepSegment can reference it;
 *  the UNIQUE(profileId, startEpoch) index is what keeps re-syncs from creating
 *  duplicate sessions. */
@Entity(
    tableName = "sleep_sessions",
    indices = [Index(value = ["profileId", "startEpoch"], unique = true)],
)
data class SleepSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val startEpoch: Long,
    val endEpoch: Long,
    val isNap: Boolean = false,
)

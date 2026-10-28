package com.kschulz.qring.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** One stage segment (awake/light/deep/rem) within a sleep session. The device streams
 *  segments in order with no stable id, so a re-sync deletes and re-inserts per session. */
@Entity(
    tableName = "sleep_segments",
    foreignKeys = [
        ForeignKey(
            entity = SleepSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SleepSegment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val stage: Int,
    val durationMin: Int,
)

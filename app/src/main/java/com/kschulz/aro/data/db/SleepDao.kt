package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import kotlinx.coroutines.flow.Flow

@Dao
interface SleepDao {
    // REPLACE on the UNIQUE(profileId, startEpoch) index deletes the old row (and its
    // segments via FK CASCADE) then inserts, returning the fresh surrogate id. This is
    // what keeps a re-sync of the same night from duplicating.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SleepSession): Long

    @Query("DELETE FROM sleep_segments WHERE sessionId = :sessionId")
    suspend fun deleteSegments(sessionId: Long)

    @Insert
    suspend fun insertSegments(segments: List<SleepSegment>)

    @Query("SELECT * FROM sleep_sessions WHERE profileId = :profileId AND startEpoch BETWEEN :from AND :to ORDER BY startEpoch DESC")
    fun observeSessions(profileId: Long, from: Long, to: Long): Flow<List<SleepSession>>

    @Query("SELECT * FROM sleep_segments WHERE sessionId = :sessionId ORDER BY id ASC")
    fun segments(sessionId: Long): Flow<List<SleepSegment>>

    @Query("SELECT * FROM sleep_sessions WHERE profileId = :profileId AND isNap = 0 ORDER BY startEpoch DESC LIMIT 1")
    fun observeLatestMain(profileId: Long): Flow<SleepSession?>

    @Query("SELECT * FROM sleep_sessions WHERE profileId = :profileId AND startEpoch BETWEEN :from AND :to ORDER BY startEpoch DESC")
    suspend fun sessionsBetween(profileId: Long, from: Long, to: Long): List<SleepSession>

    @Query("SELECT id FROM sleep_sessions WHERE profileId = :profileId AND startEpoch BETWEEN :from AND :to")
    suspend fun sessionIdsBetween(profileId: Long, from: Long, to: Long): List<Long>

    @Query("DELETE FROM sleep_sessions WHERE profileId = :profileId AND startEpoch < :before")
    suspend fun deleteSessionsBefore(profileId: Long, before: Long)
}

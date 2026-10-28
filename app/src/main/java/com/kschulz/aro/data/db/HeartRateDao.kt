package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.HeartRateSample
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<HeartRateSample>)

    @Query("SELECT * FROM heart_rate_samples WHERE profileId = :profileId AND epochMs BETWEEN :from AND :to ORDER BY epochMs ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<HeartRateSample>>

    @Query("SELECT * FROM heart_rate_samples WHERE profileId = :profileId ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<HeartRateSample?>

    @Query("SELECT COALESCE(AVG(bpm), 0) FROM heart_rate_samples WHERE profileId = :profileId AND epochMs BETWEEN :from AND :to")
    fun observeAverageBetween(profileId: Long, from: Long, to: Long): Flow<Int>

    @Query("DELETE FROM heart_rate_samples WHERE profileId = :profileId AND epochMs < :before")
    suspend fun deleteBefore(profileId: Long, before: Long)
}

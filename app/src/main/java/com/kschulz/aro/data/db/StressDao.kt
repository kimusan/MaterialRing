package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.StressSample
import kotlinx.coroutines.flow.Flow

@Dao
interface StressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<StressSample>)

    @Query("SELECT * FROM stress_samples WHERE profileId = :profileId AND epochMs BETWEEN :from AND :to ORDER BY epochMs ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<StressSample>>

    @Query("SELECT * FROM stress_samples WHERE profileId = :profileId ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<StressSample?>
}

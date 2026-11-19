package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.TemperatureSample
import kotlinx.coroutines.flow.Flow

@Dao
interface TemperatureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<TemperatureSample>)

    @Query("SELECT * FROM temperature_samples WHERE profileId = :profileId AND epochMs BETWEEN :from AND :to ORDER BY epochMs ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<TemperatureSample>>

    @Query("SELECT * FROM temperature_samples WHERE profileId = :profileId ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<TemperatureSample?>
}

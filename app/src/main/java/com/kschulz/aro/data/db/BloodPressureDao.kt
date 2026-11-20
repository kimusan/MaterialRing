package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BpSource
import kotlinx.coroutines.flow.Flow

@Dao
interface BloodPressureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(readings: List<BloodPressure>)

    @Query(
        "SELECT * FROM blood_pressure WHERE profileId = :profileId " +
            "AND epochMs BETWEEN :from AND :to AND (:ringMac IS NULL OR ringMac = :ringMac) ORDER BY epochMs DESC",
    )
    fun observeBetween(profileId: Long, from: Long, to: Long, ringMac: String? = null): Flow<List<BloodPressure>>

    @Query("SELECT * FROM blood_pressure WHERE profileId = :profileId ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<BloodPressure?>

    @Query(
        "SELECT * FROM blood_pressure WHERE profileId = :profileId AND source = :source " +
            "ORDER BY epochMs DESC LIMIT 1",
    )
    fun observeLatestBySource(profileId: Long, source: BpSource): Flow<BloodPressure?>

    @Query("DELETE FROM blood_pressure WHERE profileId = :profileId AND epochMs < :before")
    suspend fun deleteBefore(profileId: Long, before: Long)
}

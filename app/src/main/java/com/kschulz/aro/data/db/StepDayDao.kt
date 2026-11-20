package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.StepDay
import kotlinx.coroutines.flow.Flow

@Dao
interface StepDayDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(day: StepDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(days: List<StepDay>)

    @Query(
        "SELECT * FROM step_days WHERE profileId = :profileId AND date BETWEEN :from AND :to " +
            "AND (:ringMac IS NULL OR ringMac = :ringMac) ORDER BY date ASC",
    )
    fun observeBetween(profileId: Long, from: Long, to: Long, ringMac: String? = null): Flow<List<StepDay>>

    @Query("SELECT * FROM step_days WHERE profileId = :profileId AND date = :date")
    fun observeForDate(profileId: Long, date: Long): Flow<List<StepDay>>

    @Query("DELETE FROM step_days WHERE profileId = :profileId AND date < :before")
    suspend fun deleteBefore(profileId: Long, before: Long)
}

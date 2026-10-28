package com.kschulz.qring.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.qring.data.model.BodyBatteryPoint
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyBatteryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(points: List<BodyBatteryPoint>)

    @Query("SELECT * FROM body_battery WHERE profileId = :profileId AND epochHour BETWEEN :from AND :to ORDER BY epochHour ASC")
    fun observeBetween(profileId: Long, from: Long, to: Long): Flow<List<BodyBatteryPoint>>

    @Query("DELETE FROM body_battery WHERE profileId = :profileId AND epochHour < :before")
    suspend fun deleteBefore(profileId: Long, before: Long)
}

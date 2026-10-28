package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.Ring
import kotlinx.coroutines.flow.Flow

@Dao
interface RingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ring: Ring)

    @Query("SELECT * FROM rings ORDER BY boundAtEpochMs DESC")
    fun observeAll(): Flow<List<Ring>>

    @Query("SELECT * FROM rings WHERE macAddress = :mac LIMIT 1")
    suspend fun getByMac(mac: String): Ring?

    @Query("SELECT * FROM rings WHERE macAddress = :mac LIMIT 1")
    fun observeByMac(mac: String): Flow<Ring?>

    @Query("DELETE FROM rings WHERE macAddress = :mac")
    suspend fun delete(mac: String)
}

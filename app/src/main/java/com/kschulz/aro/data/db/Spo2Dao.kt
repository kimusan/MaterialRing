package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.Spo2Sample
import kotlinx.coroutines.flow.Flow

@Dao
interface Spo2Dao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(samples: List<Spo2Sample>)

    @Query(
        "SELECT * FROM spo2_samples WHERE profileId = :profileId AND epochMs BETWEEN :from AND :to " +
            "AND (:ringMac IS NULL OR ringMac = :ringMac) ORDER BY epochMs ASC",
    )
    fun observeBetween(profileId: Long, from: Long, to: Long, ringMac: String? = null): Flow<List<Spo2Sample>>

    @Query("SELECT * FROM spo2_samples WHERE profileId = :profileId ORDER BY epochMs DESC LIMIT 1")
    fun observeLatest(profileId: Long): Flow<Spo2Sample?>
}

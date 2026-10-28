package com.kschulz.aro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kschulz.aro.data.model.AppSetting
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSetting)

    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): AppSetting?

    @Query("SELECT * FROM app_settings WHERE `key` = :key LIMIT 1")
    fun observe(key: String): Flow<AppSetting?>

    @Delete
    suspend fun delete(setting: AppSetting)
}

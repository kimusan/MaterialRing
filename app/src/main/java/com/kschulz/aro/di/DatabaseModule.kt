package com.kschulz.aro.di

import android.content.Context
import androidx.room.Room
import com.kschulz.aro.data.db.AroDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AroDatabase =
        Room.databaseBuilder(context, AroDatabase::class.java, AroDatabase.NAME)
            .build()

    @Provides
    fun provideProfileDao(db: AroDatabase) = db.profileDao()

    @Provides
    fun provideRingDao(db: AroDatabase) = db.ringDao()

    @Provides
    fun provideSettingsDao(db: AroDatabase) = db.settingsDao()

    @Provides
    fun provideStepDayDao(db: AroDatabase) = db.stepDayDao()

    @Provides
    fun provideSleepDao(db: AroDatabase) = db.sleepDao()

    @Provides
    fun provideHeartRateDao(db: AroDatabase) = db.heartRateDao()

    @Provides
    fun provideBloodPressureDao(db: AroDatabase) = db.bloodPressureDao()

    @Provides
    fun provideBodyBatteryDao(db: AroDatabase) = db.bodyBatteryDao()
}

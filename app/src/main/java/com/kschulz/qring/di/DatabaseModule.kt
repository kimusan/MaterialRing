package com.kschulz.qring.di

import android.content.Context
import androidx.room.Room
import com.kschulz.qring.data.db.QRingDatabase
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
    fun provideDatabase(@ApplicationContext context: Context): QRingDatabase =
        Room.databaseBuilder(context, QRingDatabase::class.java, QRingDatabase.NAME)
            .build()

    @Provides
    fun provideProfileDao(db: QRingDatabase) = db.profileDao()

    @Provides
    fun provideRingDao(db: QRingDatabase) = db.ringDao()

    @Provides
    fun provideSettingsDao(db: QRingDatabase) = db.settingsDao()

    @Provides
    fun provideStepDayDao(db: QRingDatabase) = db.stepDayDao()

    @Provides
    fun provideSleepDao(db: QRingDatabase) = db.sleepDao()

    @Provides
    fun provideHeartRateDao(db: QRingDatabase) = db.heartRateDao()

    @Provides
    fun provideBloodPressureDao(db: QRingDatabase) = db.bloodPressureDao()

    @Provides
    fun provideBodyBatteryDao(db: QRingDatabase) = db.bodyBatteryDao()
}

package com.kschulz.qring.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kschulz.qring.data.model.AppSetting
import com.kschulz.qring.data.model.BodyBatteryPoint
import com.kschulz.qring.data.model.BloodPressure
import com.kschulz.qring.data.model.HeartRateSample
import com.kschulz.qring.data.model.Profile
import com.kschulz.qring.data.model.Ring
import com.kschulz.qring.data.model.SleepSegment
import com.kschulz.qring.data.model.SleepSession
import com.kschulz.qring.data.model.StepDay

@Database(
    entities = [
        Profile::class,
        Ring::class,
        AppSetting::class,
        StepDay::class,
        SleepSession::class,
        SleepSegment::class,
        HeartRateSample::class,
        BloodPressure::class,
        BodyBatteryPoint::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(BpSourceConverter::class)
abstract class QRingDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ringDao(): RingDao
    abstract fun settingsDao(): SettingsDao
    abstract fun stepDayDao(): StepDayDao
    abstract fun sleepDao(): SleepDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun bloodPressureDao(): BloodPressureDao
    abstract fun bodyBatteryDao(): BodyBatteryDao

    companion object {
        const val NAME = "qring.db"
    }
}

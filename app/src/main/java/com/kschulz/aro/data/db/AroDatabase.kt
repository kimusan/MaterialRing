package com.kschulz.aro.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.kschulz.aro.data.model.AppSetting
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay

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
    version = 3,
    exportSchema = false,
)
@TypeConverters(BpSourceConverter::class)
abstract class AroDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun ringDao(): RingDao
    abstract fun settingsDao(): SettingsDao
    abstract fun stepDayDao(): StepDayDao
    abstract fun sleepDao(): SleepDao
    abstract fun heartRateDao(): HeartRateDao
    abstract fun bloodPressureDao(): BloodPressureDao
    abstract fun bodyBatteryDao(): BodyBatteryDao

    companion object {
        const val NAME = "aro.db"

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE profiles ADD COLUMN gender INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE profiles ADD COLUMN birthYear INTEGER NOT NULL DEFAULT 1990")
                db.execSQL("ALTER TABLE profiles ADD COLUMN heightCm INTEGER NOT NULL DEFAULT 175")
                db.execSQL("ALTER TABLE profiles ADD COLUMN weightKg INTEGER NOT NULL DEFAULT 70")
                db.execSQL("ALTER TABLE profiles ADD COLUMN sbpBaseline INTEGER NOT NULL DEFAULT 120")
                db.execSQL("ALTER TABLE profiles ADD COLUMN dbpBaseline INTEGER NOT NULL DEFAULT 80")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goalSteps INTEGER NOT NULL DEFAULT 8000")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goalCalories INTEGER NOT NULL DEFAULT 300")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goalDistanceMeters INTEGER NOT NULL DEFAULT 5000")
                db.execSQL("ALTER TABLE profiles ADD COLUMN goalSleepMinutes INTEGER NOT NULL DEFAULT 480")
                db.execSQL("ALTER TABLE profiles ADD COLUMN isConfigured INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rings ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE rings SET isActive = 1 WHERE macAddress = " +
                        "(SELECT macAddress FROM rings ORDER BY boundAtEpochMs DESC LIMIT 1)",
                )
            }
        }
    }
}

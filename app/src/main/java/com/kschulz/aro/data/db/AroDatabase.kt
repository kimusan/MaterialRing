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
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.model.StressSample
import com.kschulz.aro.data.model.TemperatureSample

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
        Spo2Sample::class,
        TemperatureSample::class,
        StressSample::class,
    ],
    version = 6,
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
    abstract fun spo2Dao(): Spo2Dao
    abstract fun temperatureDao(): TemperatureDao
    abstract fun stressDao(): StressDao

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

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `spo2_samples` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`epochMs` INTEGER NOT NULL, " +
                        "`percent` INTEGER NOT NULL, " +
                        "`minPercent` INTEGER NOT NULL, " +
                        "`maxPercent` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `epochMs`))",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `temperature_samples` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`epochMs` INTEGER NOT NULL, " +
                        "`surfaceCelsius` REAL NOT NULL, " +
                        "`bodyCelsius` REAL NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `epochMs`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `stress_samples` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`epochMs` INTEGER NOT NULL, " +
                        "`score` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `epochMs`))",
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Recreate step_days with ringMac in the composite primary key so multiple
                // rings worn on the same day are preserved individually and can be summed.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `step_days_new` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`date` INTEGER NOT NULL, " +
                        "`steps` INTEGER NOT NULL, " +
                        "`distanceMeters` INTEGER NOT NULL, " +
                        "`calories` INTEGER NOT NULL, " +
                        "`durationMin` INTEGER NOT NULL, " +
                        "`ringMac` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`profileId`, `date`, `ringMac`))",
                )
                db.execSQL(
                    "INSERT INTO `step_days_new` (`profileId`, `date`, `steps`, `distanceMeters`, `calories`, `durationMin`, `ringMac`) " +
                        "SELECT `profileId`, `date`, `steps`, `distanceMeters`, `calories`, `durationMin`, '' FROM `step_days`",
                )
                db.execSQL("DROP TABLE `step_days`")
                db.execSQL("ALTER TABLE `step_days_new` RENAME TO `step_days`")

                db.execSQL("ALTER TABLE `sleep_sessions` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `heart_rate_samples` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `blood_pressure` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `spo2_samples` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `temperature_samples` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `stress_samples` ADD COLUMN `ringMac` TEXT NOT NULL DEFAULT ''")
            }
        }
    }
}

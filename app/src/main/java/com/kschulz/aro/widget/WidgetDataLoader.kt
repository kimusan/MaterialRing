package com.kschulz.aro.widget

import android.content.Context
import com.kschulz.aro.data.db.AroDatabase
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.firstOrNull

data class WidgetSnapshot(
    val profileName: String,
    val goalSteps: Int,
    val todayEpochDay: Long,
    val todaySteps: Int,
    val weeklySteps: List<Pair<Long, Int>>,
    val monthlySteps: List<Pair<Long, Int>>,
    val latestHrBpm: Int?,
    val latestBp: BloodPressure?,
    val latestSpo2: Spo2Sample?,
    val latestBodyBattery: Int?,
    val latestSleep: SleepSession?,
    val latestSleepSegments: List<SleepSegment>,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun database(): AroDatabase
    fun settings(): SettingsRepository
}

object WidgetDataLoader {
    suspend fun load(context: Context): WidgetSnapshot {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            WidgetEntryPoint::class.java,
        )
        val db = ep.database()
        val settings = ep.settings()

        val activeProfileId = settings.observeActiveProfileId().firstOrNull()
            ?.takeIf { it != SettingsRepository.NO_PROFILE }
            ?: db.profileDao().observeAll().firstOrNull()?.firstOrNull()?.id

        val todayEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        if (activeProfileId == null) {
            return WidgetSnapshot(
                profileName = "Aro",
                goalSteps = Profile.DEFAULT_GOAL_STEPS,
                todayEpochDay = todayEpochDay,
                todaySteps = 0,
                weeklySteps = (6 downTo 0).map { (todayEpochDay - it) to 0 },
                monthlySteps = (29 downTo 0).map { (todayEpochDay - it) to 0 },
                latestHrBpm = null,
                latestBp = null,
                latestSpo2 = null,
                latestBodyBattery = null,
                latestSleep = null,
                latestSleepSegments = emptyList(),
            )
        }

        val profile = db.profileDao().getById(activeProfileId)
        val goalSteps = profile?.goalSteps ?: Profile.DEFAULT_GOAL_STEPS

        val monthStepsRaw = db.stepDayDao()
            .observeBetween(activeProfileId, todayEpochDay - 29, todayEpochDay)
            .firstOrNull()
            .orEmpty()
            .associateBy { it.date }

        val todaySteps = monthStepsRaw[todayEpochDay]?.steps ?: 0
        val weeklySteps = (6 downTo 0).map { offset ->
            val d = todayEpochDay - offset
            d to (monthStepsRaw[d]?.steps ?: 0)
        }
        val monthlySteps = (29 downTo 0).map { offset ->
            val d = todayEpochDay - offset
            d to (monthStepsRaw[d]?.steps ?: 0)
        }

        val latestHr = db.heartRateDao().observeLatest(activeProfileId).firstOrNull()?.bpm
        val latestBp = db.bloodPressureDao().observeLatest(activeProfileId).firstOrNull()
        val latestSpo2 = db.spo2Dao().observeLatest(activeProfileId).firstOrNull()
        val todayBattery = db.bodyBatteryDao()
            .observeBetween(activeProfileId, todayEpochDay * 24L, todayEpochDay * 24L + 23L)
            .firstOrNull()
            .orEmpty()
            .lastOrNull()
            ?.score

        val latestSleep = db.sleepDao().observeLatestMain(activeProfileId).firstOrNull()
        val sleepSegments = latestSleep?.let {
            db.sleepDao().segments(it.id).firstOrNull().orEmpty()
        }.orEmpty()

        return WidgetSnapshot(
            profileName = profile?.name ?: "Aro",
            goalSteps = goalSteps,
            todayEpochDay = todayEpochDay,
            todaySteps = todaySteps,
            weeklySteps = weeklySteps,
            monthlySteps = monthlySteps,
            latestHrBpm = latestHr,
            latestBp = latestBp,
            latestSpo2 = latestSpo2,
            latestBodyBattery = todayBattery,
            latestSleep = latestSleep,
            latestSleepSegments = sleepSegments,
        )
    }
}

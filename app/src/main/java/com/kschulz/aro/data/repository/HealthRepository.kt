package com.kschulz.aro.data.repository

import com.kschulz.aro.data.db.BodyBatteryDao
import com.kschulz.aro.data.db.BloodPressureDao
import com.kschulz.aro.data.db.HeartRateDao
import com.kschulz.aro.data.db.SleepDao
import com.kschulz.aro.data.db.StepDayDao
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf

/** All health metrics. Reads are scoped to the active profile automatically (so profile
 *  switching is instant); upserts take an explicit [profileId] because the sync engine
 *  writes to whichever profile is active at sync time. Composite-PK upserts make a
 *  re-pull a no-op. */
@Singleton
class HealthRepository @Inject constructor(
    private val steps: StepDayDao,
    private val sleep: SleepDao,
    private val heartRate: HeartRateDao,
    private val bloodPressure: BloodPressureDao,
    private val bodyBattery: BodyBatteryDao,
    private val settings: SettingsRepository,
) : HealthStore {
    // ----- Steps -----
    fun observeStepsBetween(from: Long, to: Long): Flow<List<StepDay>> =
        scoped(emptyList()) { pid -> steps.observeBetween(pid, from, to) }

    fun observeTodaySteps(todayEpochDay: Long): Flow<StepDay?> =
        scoped(null) { pid -> steps.observeForDate(pid, todayEpochDay) }

    override suspend fun upsertSteps(profileId: Long, days: List<StepDay>) {
        if (days.isNotEmpty()) steps.upsertAll(days)
    }

    // ----- Sleep -----
    fun observeSleepSessions(from: Long, to: Long): Flow<List<SleepSession>> =
        scoped(emptyList()) { pid -> sleep.observeSessions(pid, from, to) }

    fun observeSleepSegmentsBetween(from: Long, to: Long): Flow<List<SleepSegment>> =
        scoped(emptyList()) { pid -> sleep.observeSegmentsForWindow(pid, from, to) }

    fun observeLatestMainSleep(): Flow<SleepSession?> =
        scoped(null) { pid -> sleep.observeLatestMain(pid) }

    fun observeSegments(sessionId: Long): Flow<List<SleepSegment>> =
        sleep.segments(sessionId)

    suspend fun sleepSessionsFor(profileId: Long, from: Long, to: Long): List<SleepSession> =
        sleep.sessionsBetween(profileId, from, to)

    suspend fun heartRateFor(profileId: Long, from: Long, to: Long): List<HeartRateSample> =
        heartRate.observeBetween(profileId, from, to).firstOrNull() ?: emptyList()

    suspend fun stepsFor(profileId: Long, from: Long, to: Long): List<StepDay> =
        steps.observeBetween(profileId, from, to).firstOrNull() ?: emptyList()

    override suspend fun upsertSleep(profileId: Long, session: SleepSession, segments: List<SleepSegment>) {
        val id = sleep.upsertSession(session.copy(profileId = profileId))
        if (segments.isNotEmpty()) {
            sleep.deleteSegments(id)
            sleep.insertSegments(segments.map { it.copy(sessionId = id, id = 0) })
        }
    }

    // ----- Heart rate -----
    fun observeHeartRateBetween(from: Long, to: Long): Flow<List<HeartRateSample>> =
        scoped(emptyList()) { pid -> heartRate.observeBetween(pid, from, to) }

    fun observeLatestHeartRate(): Flow<HeartRateSample?> =
        scoped(null) { pid -> heartRate.observeLatest(pid) }

    fun observeAverageHeartRate(from: Long, to: Long): Flow<Int> =
        scoped(0) { pid -> heartRate.observeAverageBetween(pid, from, to) }

    override suspend fun upsertHeartRate(profileId: Long, samples: List<HeartRateSample>) {
        if (samples.isNotEmpty()) heartRate.upsertAll(samples)
    }

    // ----- Blood pressure -----
    fun observeBloodPressureBetween(from: Long, to: Long): Flow<List<BloodPressure>> =
        scoped(emptyList()) { pid -> bloodPressure.observeBetween(pid, from, to) }

    fun observeLatestBloodPressure(): Flow<BloodPressure?> =
        scoped(null) { pid -> bloodPressure.observeLatest(pid) }

    fun observeLatestManualBloodPressure(): Flow<BloodPressure?> =
        scoped(null) { pid -> bloodPressure.observeLatestBySource(pid, BpSource.MANUAL) }

    override suspend fun upsertBloodPressure(profileId: Long, readings: List<BloodPressure>) {
        if (readings.isNotEmpty()) bloodPressure.upsertAll(readings)
    }

    // ----- Body battery (derived wellness metric) -----
    fun observeBodyBatteryBetween(fromHour: Long, toHour: Long): Flow<List<BodyBatteryPoint>> =
        scoped(emptyList()) { pid -> bodyBattery.observeBetween(pid, fromHour, toHour) }

    suspend fun upsertBodyBattery(profileId: Long, points: List<BodyBatteryPoint>) {
        if (points.isNotEmpty()) bodyBattery.upsertAll(points)
    }

    /** Runs [read] against the active profile, emitting [empty] when there is none. */
    private fun <T> scoped(empty: T, read: (Long) -> Flow<T>): Flow<T> =
        settings.observeActiveProfileId()
            .flatMapLatest { pid ->
                if (pid == SettingsRepository.NO_PROFILE) flowOf(empty) else read(pid)
            }
            .catch { emitAll(flowOf(empty)) }
}

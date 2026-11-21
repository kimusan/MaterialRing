package com.kschulz.aro.data.repository

import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.db.BodyBatteryDao
import com.kschulz.aro.data.db.BloodPressureDao
import com.kschulz.aro.data.db.HeartRateDao
import com.kschulz.aro.data.db.SleepDao
import com.kschulz.aro.data.db.Spo2Dao
import com.kschulz.aro.data.db.StepDayDao
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.model.StressSample
import com.kschulz.aro.data.model.TemperatureSample
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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
    private val spo2: Spo2Dao,
    private val temperature: com.kschulz.aro.data.db.TemperatureDao,
    private val stress: com.kschulz.aro.data.db.StressDao,
    private val settings: SettingsRepository,
    private val transport: RingBleTransport,
) : HealthStore {
    private fun activeRingMac(): String = transport.activeMac.value.orEmpty()

    // ----- Steps -----
    fun observeStepsBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<StepDay>> =
        scoped(emptyList()) { pid ->
            steps.observeBetween(pid, from, to, ringMac).map { rows ->
                if (ringMac == null) combineStepDaysByDate(rows) else rows
            }
        }

    fun observeTodaySteps(todayEpochDay: Long): Flow<StepDay?> =
        scoped(null) { pid ->
            steps.observeForDate(pid, todayEpochDay).map { rows ->
                combineStepDaysByDate(rows).firstOrNull()
            }
        }

    override suspend fun upsertSteps(profileId: Long, days: List<StepDay>) {
        if (days.isNotEmpty()) {
            val mac = activeRingMac()
            steps.upsertAll(days.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) })
        }
    }

    // ----- Sleep -----
    fun observeSleepSessions(from: Long, to: Long, ringMac: String? = null): Flow<List<SleepSession>> =
        scoped(emptyList()) { pid -> sleep.observeSessions(pid, from, to, ringMac) }

    fun observeSleepSegmentsBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<SleepSegment>> =
        scoped(emptyList()) { pid -> sleep.observeSegmentsForWindow(pid, from, to, ringMac) }

    fun observeLatestMainSleep(): Flow<SleepSession?> =
        scoped(null) { pid -> sleep.observeLatestMain(pid) }

    fun observeSegments(sessionId: Long): Flow<List<SleepSegment>> =
        sleep.segments(sessionId)

    suspend fun sleepSessionsFor(profileId: Long, from: Long, to: Long): List<SleepSession> =
        sleep.sessionsBetween(profileId, from, to)

    suspend fun heartRateFor(profileId: Long, from: Long, to: Long): List<HeartRateSample> =
        heartRate.observeBetween(profileId, from, to).firstOrNull() ?: emptyList()

    suspend fun stepsFor(profileId: Long, from: Long, to: Long): List<StepDay> =
        combineStepDaysByDate(steps.observeBetween(profileId, from, to).firstOrNull() ?: emptyList())

    override suspend fun upsertSleep(profileId: Long, session: SleepSession, segments: List<SleepSegment>) {
        val mac = activeRingMac()
        sleep.deleteOverlappingSessions(
            profileId = profileId,
            isNap = session.isNap,
            startEpoch = session.startEpoch,
            endEpoch = session.endEpoch,
        )
        val id = sleep.upsertSession(
            session.copy(profileId = profileId, ringMac = session.ringMac.ifBlank { mac }),
        )
        if (segments.isNotEmpty()) {
            sleep.deleteSegments(id)
            sleep.insertSegments(segments.map { it.copy(sessionId = id, id = 0) })
        }
    }

    // ----- Heart rate -----
    fun observeHeartRateBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<HeartRateSample>> =
        scoped(emptyList()) { pid -> heartRate.observeBetween(pid, from, to, ringMac) }

    fun observeLatestHeartRate(): Flow<HeartRateSample?> =
        scoped(null) { pid -> heartRate.observeLatest(pid) }

    fun observeAverageHeartRate(from: Long, to: Long): Flow<Int> =
        scoped(0) { pid -> heartRate.observeAverageBetween(pid, from, to) }

    override suspend fun upsertHeartRate(profileId: Long, samples: List<HeartRateSample>) {
        if (samples.isNotEmpty()) {
            val mac = activeRingMac()
            heartRate.upsertAll(samples.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) })
        }
    }

    // ----- Blood pressure -----
    fun observeBloodPressureBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<BloodPressure>> =
        scoped(emptyList()) { pid -> bloodPressure.observeBetween(pid, from, to, ringMac) }

    fun observeLatestBloodPressure(): Flow<BloodPressure?> =
        scoped(null) { pid -> bloodPressure.observeLatest(pid) }

    fun observeLatestManualBloodPressure(): Flow<BloodPressure?> =
        scoped(null) { pid -> bloodPressure.observeLatestBySource(pid, BpSource.MANUAL) }

    override suspend fun upsertBloodPressure(profileId: Long, readings: List<BloodPressure>) {
        if (readings.isNotEmpty()) {
            val mac = activeRingMac()
            bloodPressure.upsertAll(
                readings.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) },
            )
        }
    }

    // ----- Body battery (derived wellness metric) -----
    fun observeBodyBatteryBetween(fromHour: Long, toHour: Long): Flow<List<BodyBatteryPoint>> =
        scoped(emptyList()) { pid -> bodyBattery.observeBetween(pid, fromHour, toHour) }

    suspend fun upsertBodyBattery(profileId: Long, points: List<BodyBatteryPoint>) {
        if (points.isNotEmpty()) bodyBattery.upsertAll(points)
    }

    suspend fun deleteBodyBatteryAfter(profileId: Long, afterHour: Long) {
        bodyBattery.deleteAfter(profileId, afterHour)
    }

    suspend fun bloodPressureFor(profileId: Long, from: Long, to: Long): List<BloodPressure> =
        bloodPressure.observeBetween(profileId, from, to).firstOrNull() ?: emptyList()

    suspend fun spo2For(profileId: Long, from: Long, to: Long): List<Spo2Sample> =
        spo2.observeBetween(profileId, from, to).firstOrNull() ?: emptyList()

    suspend fun sleepSegmentsFor(sessionId: Long): List<SleepSegment> =
        sleep.segments(sessionId).firstOrNull() ?: emptyList()

    // ----- Blood oxygen (SpO2) -----
    fun observeSpo2Between(from: Long, to: Long, ringMac: String? = null): Flow<List<Spo2Sample>> =
        scoped(emptyList()) { pid -> spo2.observeBetween(pid, from, to, ringMac) }

    fun observeLatestSpo2(): Flow<Spo2Sample?> =
        scoped(null) { pid -> spo2.observeLatest(pid) }

    override suspend fun upsertSpo2(profileId: Long, samples: List<Spo2Sample>) {
        if (samples.isNotEmpty()) {
            val mac = activeRingMac()
            spo2.upsertAll(samples.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) })
        }
    }

    // ----- Temperature -----
    fun observeTemperatureBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<TemperatureSample>> =
        scoped(emptyList()) { pid -> temperature.observeBetween(pid, from, to, ringMac) }

    fun observeLatestTemperature(): Flow<TemperatureSample?> =
        scoped(null) { pid -> temperature.observeLatest(pid) }

    override suspend fun upsertTemperature(
        profileId: Long,
        samples: List<TemperatureSample>,
    ) {
        if (samples.isNotEmpty()) {
            val mac = activeRingMac()
            temperature.upsertAll(
                samples.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) },
            )
        }
    }

    // ----- Stress -----
    fun observeStressBetween(from: Long, to: Long, ringMac: String? = null): Flow<List<StressSample>> =
        scoped(emptyList()) { pid -> stress.observeBetween(pid, from, to, ringMac) }

    fun observeLatestStress(): Flow<StressSample?> =
        scoped(null) { pid -> stress.observeLatest(pid) }

    override suspend fun upsertStress(
        profileId: Long,
        samples: List<StressSample>,
    ) {
        if (samples.isNotEmpty()) {
            val mac = activeRingMac()
            stress.upsertAll(
                samples.map { it.copy(profileId = profileId, ringMac = it.ringMac.ifBlank { mac }) },
            )
        }
    }

    /** Runs [read] against the active profile, emitting [empty] when there is none. */
    private fun <T> scoped(empty: T, read: (Long) -> Flow<T>): Flow<T> =
        settings.observeActiveProfileId()
            .flatMapLatest { pid ->
                if (pid == SettingsRepository.NO_PROFILE) flowOf(empty) else read(pid)
            }
            .catch { emitAll(flowOf(empty)) }

    companion object {
        /**
         * Combines per-ring [StepDay] rows into a single daily total per date.
         * When a date has one or more ring-tagged rows (non-blank [StepDay.ringMac]),
         * legacy untagged rows (blank `ringMac`) for that same date are excluded to
         * avoid double-counting migrated history after a fresh sync.
         */
        fun combineStepDaysByDate(rows: List<StepDay>): List<StepDay> {
            if (rows.isEmpty()) return emptyList()
            return rows
                .groupBy { it.date }
                .toSortedMap()
                .values
                .map { dayRows ->
                    val tagged = dayRows.filter { it.ringMac.isNotBlank() }
                    val active = if (tagged.isNotEmpty()) tagged else dayRows
                    val first = active.first()
                    StepDay(
                        profileId = first.profileId,
                        date = first.date,
                        steps = active.sumOf { it.steps },
                        distanceMeters = active.sumOf { it.distanceMeters },
                        calories = active.sumOf { it.calories },
                        durationMin = active.sumOf { it.durationMin },
                        ringMac = "",
                    )
                }
        }
    }
}

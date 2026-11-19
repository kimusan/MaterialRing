package com.kschulz.aro.data.repository

import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay

/**
 * The upsert surface the sync engine writes to. Kept as a narrow interface so
 * [com.kschulz.aro.data.sync.SyncEngine] is testable with a fake (and so a different
 * persistence strategy is a one-line DI change). All writes are by composite PK, so a
 * re-pull is a no-op.
 */
interface HealthStore {
    suspend fun upsertSteps(profileId: Long, days: List<StepDay>)
    suspend fun upsertBloodPressure(profileId: Long, readings: List<BloodPressure>)
    suspend fun upsertHeartRate(profileId: Long, samples: List<HeartRateSample>)
    suspend fun upsertSleep(profileId: Long, session: SleepSession, segments: List<SleepSegment>)
    suspend fun upsertSpo2(profileId: Long, samples: List<Spo2Sample>) {}
}

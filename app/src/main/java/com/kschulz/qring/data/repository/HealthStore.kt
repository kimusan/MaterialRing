package com.kschulz.qring.data.repository

import com.kschulz.qring.data.model.BloodPressure
import com.kschulz.qring.data.model.HeartRateSample
import com.kschulz.qring.data.model.StepDay

/**
 * The upsert surface the sync engine writes to. Kept as a narrow interface so
 * [com.kschulz.qring.data.sync.SyncEngine] is testable with a fake (and so a different
 * persistence strategy is a one-line DI change). All writes are by composite PK, so a
 * re-pull is a no-op.
 */
interface HealthStore {
    suspend fun upsertSteps(profileId: Long, days: List<StepDay>)
    suspend fun upsertBloodPressure(profileId: Long, readings: List<BloodPressure>)
    suspend fun upsertHeartRate(profileId: Long, samples: List<HeartRateSample>)
}

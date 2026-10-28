package com.kschulz.aro.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kschulz.aro.data.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.firstOrNull

/**
 * Periodic + on-demand sync. Runs [SyncEngine.sync] against the active profile. Because
 * the engine is idempotent and resumes from the 0x46 distribution, re-scheduling after a
 * drop simply re-queries and continues without duplicating.
 */
@HiltWorker
class SyncJob @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val engine: SyncEngine,
    private val time: TimeSource,
    private val settings: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val profileId = settings.observeActiveProfileId().firstOrNull() ?: return Result.success()
        if (profileId == SettingsRepository.NO_PROFILE) return Result.success()
        val outcome = engine.sync(profileId, time)
        return if (outcome.connected) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "aro-periodic-sync"
    }
}

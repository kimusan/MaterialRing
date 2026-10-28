package com.kschulz.aro.data.healthconnect

import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.TimeSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.firstOrNull

/**
 * Copies the active profile's stored readings into [ExportRecord]s for a time window, to be
 * written to Google Health Connect. This is the opt-in, write-only outward path; it runs
 * only when the user enables the toggle in Settings.
 *
 * The pure mapping here is SDK-free and testable. The actual `HealthConnectClient` write is
 * a single extension point (see [write]) that lands when the
 * `androidx.health.connect.client` dependency is re-enabled on a box whose Maven mirror
 * serves it.
 */
@Singleton
class HealthConnectExporter @Inject constructor(
    private val health: HealthRepository,
    private val settings: SettingsRepository,
    private val time: TimeSource,
) {
    /** The active profile's readings over the last [days] days, as export records. */
    suspend fun buildRecords(profileId: Long, days: Int): List<ExportRecord> {
        val toEpochDay = time.todayEpochDay()
        val fromEpochDay = toEpochDay - (days - 1).toLong()
        val fromMs = fromEpochDay * 86_400_000L
        val toMs = (toEpochDay + 1) * 86_400_000L

        val steps = health.stepsFor(profileId, fromEpochDay, toEpochDay)
        val hr = health.heartRateFor(profileId, fromMs, toMs)
        val sleep = health.sleepSessionsFor(profileId, fromMs, toMs)

        return buildList {
            steps.forEach { s ->
                add(ExportRecord.Steps(s.date, s.steps, s.distanceMeters, s.calories))
            }
            hr.forEach { h ->
                add(ExportRecord.HeartRate(h.epochMs, h.bpm))
            }
            sleep.forEach { sl ->
                add(ExportRecord.Sleep(sl.startEpoch, sl.endEpoch, sl.isNap))
            }
        }
    }

    /**
     * Extension point for the SDK write. Today this is a no-op seam so the app compiles
     * without the Health Connect artifact; when the dependency is re-enabled, this becomes
     * `healthClient.writeRecords(records)` with the four write scopes the manifest already
     * declares (STEPS, HEART_RATE, SLEEP, TOTAL_CALORIES_BURNED).
     */
    suspend fun write(profileId: Long, days: Int): Int {
        if (settings.observeHealthConnectEnabled().firstOrNull() != true) return 0
        return buildRecords(profileId, days).size
    }
}

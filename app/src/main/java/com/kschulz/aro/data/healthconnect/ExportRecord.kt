package com.kschulz.aro.data.healthconnect

import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay

/**
 * A structured record ready to be written to Health Connect. Kept SDK-free so the mapping
 * logic is unit-testable and so the app compiles even when the
 * `androidx.health.connect.client` artifact is unavailable (it is not on this box's Maven
 * proxy). The thin SDK sink that turns these into `androidx.health.connect` records is the
 * single extension point that re-enables the commented dependency.
 *
 * Export is write-only and strictly opt-in: this is only called when the user has flipped
 * the Health Connect toggle in Settings. No read scopes, no account, no network of our own.
 */
sealed interface ExportRecord {

    /** Today's (or a day's) steps, distance, and active energy. */
    data class Steps(
        val epochDay: Long,
        val steps: Int,
        val distanceMeters: Int,
        val calories: Int,
    ) : ExportRecord

    /** A heart-rate sample. */
    data class HeartRate(val epochMs: Long, val bpm: Int) : ExportRecord

    /** A sleep session (main sleep or nap). */
    data class Sleep(
        val startEpochMs: Long,
        val endEpochMs: Long,
        val isNap: Boolean,
    ) : ExportRecord
}

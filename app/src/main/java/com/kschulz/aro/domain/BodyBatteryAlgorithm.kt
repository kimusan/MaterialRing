package com.kschulz.aro.domain

import com.kschulz.aro.data.model.BodyBatteryPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hourly body-battery wellness model (5..100).
 *
 * Simulates energy recharge during sleep hours and drain during waking hours based on
 * heart-rate load above resting baseline (65 bpm) and step activity. This is a derived
 * wellness metric, not a clinical vital.
 */
object BodyBatteryAlgorithm {

    const val MAX_SCORE = 100
    const val MIN_SCORE = 5
    const val DEFAULT_INITIAL_SCORE = 60.0

    private const val RESTING_HR_BASELINE = 65.0
    private const val MAX_SLEEP_RECHARGE_PER_HOUR = 7.5
    private const val BASE_AWAKE_DRAIN_PER_HOUR = 1.4

    data class HourlyInput(
        val epochHour: Long,
        val sleepMinutes: Int = 0,
        val meanHr: Int? = null,
        val steps: Int = 0,
    )

    /**
     * Simulate an hour-by-hour charging/draining body-battery curve from ordered [inputs].
     */
    fun computeHourly(
        inputs: List<HourlyInput>,
        initialScore: Double = DEFAULT_INITIAL_SCORE,
    ): List<BodyBatteryPoint> {
        if (inputs.isEmpty()) return emptyList()
        val sorted = inputs.sortedBy { it.epochHour }
        var level = initialScore.coerceIn(MIN_SCORE.toDouble(), MAX_SCORE.toDouble())
        val out = ArrayList<BodyBatteryPoint>(sorted.size)

        for (h in sorted) {
            val sleepFrac = (h.sleepMinutes / 60.0).coerceIn(0.0, 1.0)
            val awakeFrac = 1.0 - sleepFrac

            // Sleep recharges energy; lower HR during sleep improves recovery efficiency.
            val hrRecoveryBonus = when {
                h.meanHr == null -> 1.0
                h.meanHr <= 58 -> 1.15
                h.meanHr <= 70 -> 1.0
                else -> (1.0 - ((h.meanHr - 70) / 80.0)).coerceIn(0.55, 1.0)
            }
            val recharge = MAX_SLEEP_RECHARGE_PER_HOUR * sleepFrac * hrRecoveryBonus

            // Waking hours drain from baseline metabolism, elevated HR, and step activity.
            val hrStrain = when {
                h.meanHr == null -> 0.0
                h.meanHr > RESTING_HR_BASELINE ->
                    ((h.meanHr - RESTING_HR_BASELINE) / 14.0).coerceIn(0.0, 5.5)
                h.meanHr < 58 -> -0.6 // restful sitting/lying down slows drain
                else -> 0.0
            }
            val stepStrain = (h.steps / 550.0).coerceIn(0.0, 4.5)
            val drain = (BASE_AWAKE_DRAIN_PER_HOUR + hrStrain + stepStrain).coerceAtLeast(0.2) * awakeFrac

            level = (level + recharge - drain).coerceIn(MIN_SCORE.toDouble(), MAX_SCORE.toDouble())
            out.add(
                BodyBatteryPoint(
                    profileId = 0,
                    epochHour = h.epochHour,
                    score = level.roundToInt().coerceIn(MIN_SCORE, MAX_SCORE),
                ),
            )
        }
        return out
    }

    /**
     * Aggregate fallback used when only window totals are available.
     * Produces a bounded 5..100 score (previously clamped to 100 due to an unnormalized 1.2 offset).
     */
    fun compute(
        fromHour: Long,
        toHour: Long,
        restMin: Int,
        restLowMin: Int,
        restHighMin: Int,
        meanHr: Int,
        meanStepsPerHour: Int,
    ): List<BodyBatteryPoint> {
        if (toHour < fromHour) return emptyList()

        val sleepScore = (restMin / 480.0).coerceIn(0.0, 1.0) * 55.0 +
            ((restLowMin + restHighMin) / 480.0).coerceIn(0.0, 1.0) * 20.0
        val hrPenalty = if (meanHr > 0) {
            (abs(meanHr - RESTING_HR_BASELINE) / 60.0).coerceIn(0.0, 1.0) * 25.0
        } else {
            0.0
        }
        val activityPenalty = (meanStepsPerHour / 2000.0).coerceIn(0.0, 1.0) * 20.0
        val score = (35.0 + sleepScore - hrPenalty - activityPenalty)
            .roundToInt()
            .coerceIn(MIN_SCORE, MAX_SCORE)

        val points = ArrayList<BodyBatteryPoint>()
        var hour = fromHour
        while (hour <= toHour) {
            points.add(BodyBatteryPoint(profileId = 0, epochHour = hour, score = score))
            hour += 1
        }
        return points
    }
}

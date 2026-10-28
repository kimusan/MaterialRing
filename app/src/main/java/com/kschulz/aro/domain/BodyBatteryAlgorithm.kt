package com.kschulz.aro.domain

import com.kschulz.aro.data.model.BodyBatteryPoint
import kotlin.math.abs

/**
 * Ported body-battery score (re/PROTOCOL.md section 8, weights from BodyBatteryAlgorithm).
 * Blends sleep projection, heart-rate load, and step activity into an hourly 0-100 score.
 *
 * This is a derived wellness metric, not a clinical vital, and it is labeled as such in the
 * UI. It only needs the three series the ring actually reports (sleep, HR, steps), so it is
 * fully computable on-device with no extra capture.
 */
object BodyBatteryAlgorithm {

    // Weights from the original BodyBatteryAlgorithm (re/PROTOCOL.md section 8).
    private const val W_RESTORE = 1.2
    private const val W_NIGHT_SLEEP = 0.85
    private const val W_DEEP_SLEEP = 0.75
    private const val W_EVE_SLEEP = 0.65
    private const val W_LOW_HR = 0.4
    private const val W_HIGH_HR = 0.35
    private const val W_ACTIVITY = 0.3
    private const val W_REST = 0.25
    private const val W_REC_LOW = 0.2
    private const val W_REC_MID = 0.15
    private const val W_REC_HIGH = 0.1
    private const val W_TREND = 0.08
    private const val W_FINAL = 0.17
    private const val DIVISOR = 120.0

    const val MAX_SCORE = 100
    const val MIN_SCORE = 5

    /**
     * Compute an hourly body-battery score across [fromHour]..[toHour].
     *
     * @param restMin total rest (sleep) minutes in the window.
     * @param restLowMin minutes of rest below the light band.
     * @param restHighMin minutes of rest above the deep band.
     * @param meanHr mean heart rate across the window (bpm).
     * @param meanStepsPerHour mean step activity across the window.
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

        val restFactor = W_RESTORE + W_NIGHT_SLEEP * (restMin / 600.0).coerceIn(0.0, 1.0)
        val quality = W_DEEP_SLEEP * (restLowMin / 300.0).coerceIn(0.0, 1.0) +
            W_EVE_SLEEP * (restHighMin / 300.0).coerceIn(0.0, 1.0)

        // Activity: a moderate HR is restful; very high or very low drains.
        val hrLoad = (W_LOW_HR + W_HIGH_HR) * (abs(meanHr - 65.0) / DIVISOR).coerceIn(0.0, 1.0)
        val activity = (W_ACTIVITY + W_REST) * (meanStepsPerHour / 3000.0).coerceIn(0.0, 1.0)

        // Recovery: how much capacity is left.
        val recovery = (W_REC_LOW + W_REC_MID + W_REC_HIGH - hrLoad - activity).coerceIn(0.0, 1.0)
        val trend = W_TREND * (1.0 - (meanStepsPerHour / 5000.0).coerceIn(0.0, 1.0))

        val raw = restFactor + quality + W_FINAL * recovery + trend
        val score = (raw * MAX_SCORE).toInt().coerceIn(MIN_SCORE, MAX_SCORE)

        val points = ArrayList<BodyBatteryPoint>()
        var hour = fromHour
        while (hour <= toHour) {
            points.add(BodyBatteryPoint(profileId = 0, epochHour = hour, score = score))
            hour += 1
        }
        return points
    }
}

package com.kschulz.aro.domain

import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.repository.HealthRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Computes the body-battery score for a profile over a window from its stored sleep,
 * heart-rate, and step series, then persists the hourly points.
 */
@Singleton
class BodyBatteryUseCase @Inject constructor(
    private val health: HealthRepository,
) : BodyBattery {
    override suspend fun recompute(profileId: Long, fromHour: Long, toHour: Long) {
        computeFor(profileId, fromHour, toHour)
    }

    suspend fun computeFor(profileId: Long, fromHour: Long, toHour: Long): List<BodyBatteryPoint> {
        val fromMs = fromHour * 3_600_000L
        val toMs = toHour * 3_600_000L + 3_599_000L

        val sessions = health.sleepSessionsFor(profileId, fromMs, toMs)
        val restMin = sessions.sumOf { ((it.endEpoch - it.startEpoch) / 60_000L).toInt() }
        // The ring reports coarse stage info; treat a third of rest as the low band and a
        // third as the high band for the quality term (best-effort, see PROTOCOL.md 7.4).
        val restLowMin = restMin / 3
        val restHighMin = restMin / 3

        val hr = health.heartRateFor(profileId, fromMs, toMs)
        val meanHr = if (hr.isEmpty()) 0 else hr.map { it.bpm }.average().toInt()

        val steps = health.stepsFor(profileId, fromHour / 24, toHour / 24)
        val spanHours = (toHour - fromHour + 1).coerceAtLeast(1)
        val meanStepsPerHour = (steps.sumOf { it.steps } / spanHours).toInt()

        val points = BodyBatteryAlgorithm.compute(
            fromHour = fromHour,
            toHour = toHour,
            restMin = restMin,
            restLowMin = restLowMin,
            restHighMin = restHighMin,
            meanHr = meanHr,
            meanStepsPerHour = meanStepsPerHour,
        ).map { it.copy(profileId = profileId) }

        if (points.isNotEmpty()) health.upsertBodyBattery(profileId, points)
        return points
    }
}

package com.kschulz.aro.domain

import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.repository.HealthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Computes the hourly body-battery curve for a profile over a window from its stored
 * sleep sessions, heart-rate samples, and daily steps, then persists the hourly points.
 */
@Singleton
class BodyBatteryUseCase @Inject constructor(
    private val health: HealthRepository,
) : BodyBattery {
    override suspend fun recompute(profileId: Long, fromHour: Long, toHour: Long) {
        computeFor(profileId, fromHour, toHour)
    }

    suspend fun computeFor(profileId: Long, fromHour: Long, toHour: Long): List<BodyBatteryPoint> {
        if (toHour < fromHour) return emptyList()
        val fromMs = fromHour * HOUR_MS
        val toMs = toHour * HOUR_MS + HOUR_MS - 1L

        val sessions = health.sleepSessionsFor(profileId, fromMs, toMs)
        val hrSamples = health.heartRateFor(profileId, fromMs, toMs)
        val stepDays = health.stepsFor(profileId, fromHour / 24L, toHour / 24L)

        val hasAnySignal = sessions.isNotEmpty() ||
            hrSamples.isNotEmpty() ||
            stepDays.any { it.steps > 0 }
        if (!hasAnySignal) return emptyList()

        val hrByHour: Map<Long, Int> = hrSamples
            .filter { it.bpm > 0 }
            .groupBy { it.epochMs / HOUR_MS }
            .mapValues { (_, list) -> list.map { it.bpm }.average().toInt() }

        val stepsByDay: Map<Long, Int> = stepDays.associate { it.date to it.steps }

        val inputs = ArrayList<BodyBatteryAlgorithm.HourlyInput>((toHour - fromHour + 1).toInt())
        var hour = fromHour
        while (hour <= toHour) {
            val hourStartMs = hour * HOUR_MS
            val hourEndMs = hourStartMs + HOUR_MS

            var sleepMin = 0
            for (s in sessions) {
                val overlapStart = max(hourStartMs, s.startEpoch)
                val overlapEnd = min(hourEndMs, s.endEpoch)
                if (overlapEnd > overlapStart) {
                    sleepMin += ((overlapEnd - overlapStart) / 60_000L).toInt()
                }
            }
            sleepMin = sleepMin.coerceIn(0, 60)

            val daySteps = stepsByDay[hour / 24L] ?: 0
            val hourOfDay = ((hour % 24L) + 24L) % 24L
            // Distribute daily steps across daytime waking hours (07:00..22:00) when not asleep.
            val hourlySteps = if (daySteps > 0 && sleepMin < 30 && hourOfDay in 7..22) {
                daySteps / 16
            } else {
                0
            }

            inputs.add(
                BodyBatteryAlgorithm.HourlyInput(
                    epochHour = hour,
                    sleepMinutes = sleepMin,
                    meanHr = hrByHour[hour],
                    steps = hourlySteps,
                ),
            )
            hour += 1
        }

        val points = BodyBatteryAlgorithm.computeHourly(inputs)
            .map { it.copy(profileId = profileId) }

        health.deleteBodyBatteryAfter(profileId, toHour)
        if (points.isNotEmpty()) health.upsertBodyBattery(profileId, points)
        return points
    }

    companion object {
        private const val HOUR_MS = 3_600_000L
    }
}

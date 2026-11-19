package com.kschulz.aro.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class BodyBatteryAlgorithmTest {

    @Test
    fun sleepRechargesAndActiveDayDrains() {
        val inputs = buildList {
            // 7 hours of sleep (hours 0..6) with low resting HR -> should recharge
            for (h in 0L..6L) {
                add(BodyBatteryAlgorithm.HourlyInput(epochHour = h, sleepMinutes = 60, meanHr = 56, steps = 0))
            }
            // 10 hours awake (hours 7..16) with activity and elevated HR -> should drain
            for (h in 7L..16L) {
                add(BodyBatteryAlgorithm.HourlyInput(epochHour = h, sleepMinutes = 0, meanHr = 90, steps = 900))
            }
        }

        val points = BodyBatteryAlgorithm.computeHourly(inputs, initialScore = 40.0)
        val afterSleep = points.first { it.epochHour == 6L }.score
        val endOfDay = points.last().score

        assertTrue("Expected sleep to recharge above initial 40, got $afterSleep", afterSleep > 75)
        assertTrue("Expected active day to drain below morning peak $afterSleep, got $endOfDay", endOfDay < afterSleep - 25)
        assertTrue("Score must not be pinned at 100 all day", points.any { it.score < 90 })
    }

    @Test
    fun aggregateComputeIsNotPinnedAtHundred() {
        val lowRestHighLoad = BodyBatteryAlgorithm.compute(
            fromHour = 0L,
            toHour = 5L,
            restMin = 180,
            restLowMin = 60,
            restHighMin = 60,
            meanHr = 95,
            meanStepsPerHour = 1200,
        )
        assertTrue(
            "Expected low-rest/high-load score < 70, got ${lowRestHighLoad.first().score}",
            lowRestHighLoad.first().score < 70,
        )
    }
}

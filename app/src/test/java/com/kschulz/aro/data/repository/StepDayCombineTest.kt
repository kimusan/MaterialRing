package com.kschulz.aro.data.repository

import com.kschulz.aro.data.model.StepDay
import org.junit.Assert.assertEquals
import org.junit.Test

class StepDayCombineTest {

    @Test
    fun sumsMultipleRingsOnSameDay() {
        val rows = listOf(
            StepDay(profileId = 1, date = 20_000L, steps = 3200, distanceMeters = 2400, calories = 140, durationMin = 30, ringMac = "AA:BB:CC:DD:EE:01"),
            StepDay(profileId = 1, date = 20_000L, steps = 4800, distanceMeters = 3600, calories = 210, durationMin = 45, ringMac = "AA:BB:CC:DD:EE:02"),
        )

        val combined = HealthRepository.combineStepDaysByDate(rows)

        assertEquals(1, combined.size)
        val day = combined.first()
        assertEquals(8000, day.steps)
        assertEquals(6000, day.distanceMeters)
        assertEquals(350, day.calories)
        assertEquals(75, day.durationMin)
        assertEquals("", day.ringMac)
    }

    @Test
    fun excludesLegacyUntaggedRowWhenRingTaggedRowExistsForSameDay() {
        val rows = listOf(
            StepDay(profileId = 1, date = 20_000L, steps = 5000, distanceMeters = 3800, calories = 200, durationMin = 40, ringMac = ""),
            StepDay(profileId = 1, date = 20_000L, steps = 5100, distanceMeters = 3900, calories = 205, durationMin = 42, ringMac = "AA:BB:CC:DD:EE:01"),
            StepDay(profileId = 1, date = 19_999L, steps = 7200, distanceMeters = 5400, calories = 290, durationMin = 60, ringMac = ""),
        )

        val combined = HealthRepository.combineStepDaysByDate(rows)

        assertEquals(2, combined.size)
        assertEquals(19_999L, combined[0].date)
        assertEquals(7200, combined[0].steps)
        assertEquals(20_000L, combined[1].date)
        assertEquals(5100, combined[1].steps)
    }
}

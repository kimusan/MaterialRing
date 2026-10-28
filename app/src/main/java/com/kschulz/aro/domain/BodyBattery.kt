package com.kschulz.aro.domain

/** Recomputes the derived body-battery series for a profile window. */
interface BodyBattery {
    suspend fun recompute(profileId: Long, fromHour: Long, toHour: Long)
}

package com.kschulz.aro.data.model

import androidx.room.Entity

/**
 * Dual skin-surface and estimated body temperature reading (in °C), measured via
 * SMART_RING / JRing thermistor (0x37/0x38).
 */
@Entity(tableName = "temperature_samples", primaryKeys = ["profileId", "epochMs"])
data class TemperatureSample(
    val profileId: Long,
    val epochMs: Long,
    val surfaceCelsius: Double,
    val bodyCelsius: Double,
)

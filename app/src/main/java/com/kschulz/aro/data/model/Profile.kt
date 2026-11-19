package com.kschulz.aro.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A local, on-device user profile. Switching profiles is a query filter, never a
 *  logout; there is no cloud account behind it. Physical attributes and goals are
 *  synced to the ring via opcodes 0x0A and 0x21 for on-device calorie/distance/BP math. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long,
    /** 0 = Male, 1 = Female (matches vendor TimeFormatReq sex byte). */
    val gender: Int = GENDER_MALE,
    val birthYear: Int = DEFAULT_BIRTH_YEAR,
    val heightCm: Int = DEFAULT_HEIGHT_CM,
    val weightKg: Int = DEFAULT_WEIGHT_KG,
    val sbpBaseline: Int = DEFAULT_SBP_BASELINE,
    val dbpBaseline: Int = DEFAULT_DBP_BASELINE,
    val goalSteps: Int = DEFAULT_GOAL_STEPS,
    val goalCalories: Int = DEFAULT_GOAL_CALORIES,
    val goalDistanceMeters: Int = DEFAULT_GOAL_DISTANCE_METERS,
    val goalSleepMinutes: Int = DEFAULT_GOAL_SLEEP_MINUTES,
    val isConfigured: Boolean = false,
) {
    fun age(currentYear: Int = 2026): Int = (currentYear - birthYear).coerceIn(10, 110)

    companion object {
        const val GENDER_MALE = 0
        const val GENDER_FEMALE = 1
        const val DEFAULT_BIRTH_YEAR = 1990
        const val DEFAULT_HEIGHT_CM = 175
        const val DEFAULT_WEIGHT_KG = 70
        const val DEFAULT_SBP_BASELINE = 120
        const val DEFAULT_DBP_BASELINE = 80
        const val DEFAULT_GOAL_STEPS = 8_000
        const val DEFAULT_GOAL_CALORIES = 300
        const val DEFAULT_GOAL_DISTANCE_METERS = 5_000
        const val DEFAULT_GOAL_SLEEP_MINUTES = 480
    }
}

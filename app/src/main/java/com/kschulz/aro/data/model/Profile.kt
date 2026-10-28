package com.kschulz.aro.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A local, on-device user profile. Switching profiles is a query filter, never a
 *  logout; there is no cloud account behind it. */
@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isDefault: Boolean = false,
    val createdAtEpochMs: Long,
)

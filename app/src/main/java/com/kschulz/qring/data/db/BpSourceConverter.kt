package com.kschulz.qring.data.db

import androidx.room.TypeConverter
import com.kschulz.qring.data.model.BpSource

class BpSourceConverter {
    @TypeConverter
    fun fromBpSource(value: BpSource): String = value.name

    @TypeConverter
    fun toBpSource(value: String): BpSource = BpSource.valueOf(value)
}

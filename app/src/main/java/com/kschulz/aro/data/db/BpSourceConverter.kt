package com.kschulz.aro.data.db

import androidx.room.TypeConverter
import com.kschulz.aro.data.model.BpSource

class BpSourceConverter {
    @TypeConverter
    fun fromBpSource(value: BpSource): String = value.name

    @TypeConverter
    fun toBpSource(value: String): BpSource = BpSource.valueOf(value)
}

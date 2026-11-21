package com.kschulz.aro.data.sync

import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

class ProductionTimeSource(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : TimeSource {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun todayEpochDay(): Long =
        LocalDate.now(zoneId).toEpochDay()

    override fun epochDayForOffset(daysAgo: Int): Long = todayEpochDay() - daysAgo.toLong()

    override fun dayStartEpochMs(epochDay: Long): Long =
        LocalDate.ofEpochDay(epochDay)
            .atStartOfDay(zoneId)
            .toInstant()
            .toEpochMilli()

    override fun localClock(): LocalClock = Calendar.getInstance().let { c ->
        LocalClock(
            year = c.get(Calendar.YEAR),
            month = c.get(Calendar.MONTH) + 1,
            day = c.get(Calendar.DAY_OF_MONTH),
            hour = c.get(Calendar.HOUR_OF_DAY),
            minute = c.get(Calendar.MINUTE),
            second = c.get(Calendar.SECOND),
        )
    }
}

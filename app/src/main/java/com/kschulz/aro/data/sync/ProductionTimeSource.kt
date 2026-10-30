package com.kschulz.aro.data.sync

import java.util.Calendar
import java.util.TimeZone

class ProductionTimeSource(
    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
) : TimeSource {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun todayEpochDay(): Long =
        Calendar.getInstance(timeZone).timeInMillis / 86_400_000L

    override fun epochDayForOffset(daysAgo: Int): Long = todayEpochDay() - daysAgo.toLong()

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

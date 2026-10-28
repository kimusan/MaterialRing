package com.kschulz.qring.data.sync

import java.util.Calendar
import java.util.TimeZone

class ProductionTimeSource(
    private val timeZone: TimeZone = TimeZone.getTimeZone("UTC"),
) : TimeSource {

    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun todayEpochDay(): Long =
        Calendar.getInstance(timeZone).timeInMillis / 86_400_000L

    override fun epochDayForOffset(daysAgo: Int): Long = todayEpochDay() - daysAgo.toLong()
}

package com.kschulz.aro.data.sync

import java.time.Instant
import java.time.ZoneOffset

/** Deterministic clock for tests. */
class FixedTimeSource(
    private val todayEpochDay: Long,
    private val nowMillis: Long,
) : TimeSource {
    override fun nowMillis(): Long = nowMillis
    override fun todayEpochDay(): Long = todayEpochDay
    override fun epochDayForOffset(daysAgo: Int): Long = todayEpochDay - daysAgo.toLong()

    // Derived from nowMillis in UTC so tests can compute the exact 0x01 BCD bytes.
    override fun localClock(): LocalClock {
        val t = Instant.ofEpochMilli(nowMillis).atZone(ZoneOffset.UTC)
        return LocalClock(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
    }
}

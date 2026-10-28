package com.kschulz.aro.data.sync

/** Deterministic clock for tests. */
class FixedTimeSource(
    private val todayEpochDay: Long,
    private val nowMillis: Long,
) : TimeSource {
    override fun nowMillis(): Long = nowMillis
    override fun todayEpochDay(): Long = todayEpochDay
    override fun epochDayForOffset(daysAgo: Int): Long = todayEpochDay - daysAgo.toLong()
}

package com.kschulz.aro.data.sync

/** Time seam so the sync window is deterministic in tests. */
interface TimeSource {
    fun nowMillis(): Long
    fun todayEpochDay(): Long
    fun epochDayForOffset(daysAgo: Int): Long
}

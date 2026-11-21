package com.kschulz.aro.data.sync

/** Time seam so the sync window is deterministic in tests. */
interface TimeSource {
    fun nowMillis(): Long
    fun todayEpochDay(): Long
    fun epochDayForOffset(daysAgo: Int): Long

    /**
     * The device's local wall clock. Used for the 0x01 SET_DEVICE_TIME baseline, which the
     * vendor sends in LOCAL time (not UTC), so this must be the phone's default zone.
     */
    fun localClock(): LocalClock

    /**
     * Epoch milliseconds for midnight (00:00:00) of [epochDay] in the ring's wall-clock zone.
     * Defaults to UTC midnight so deterministic unit tests using [FixedTimeSource] stay stable.
     */
    fun dayStartEpochMs(epochDay: Long): Long = epochDay * 86_400_000L
}

/** Local wall-clock fields, for building the 0x01 time payload. */
data class LocalClock(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)

package com.kschulz.aro.data.sync

import com.kschulz.aro.data.ble.ByteOrder

/**
 * Interprets the 0x46 QUERY_DATA_DISTRIBUTION response as the set of unsynced day offsets.
 *
 * IMPORTANT (re/PROTOCOL.md section 11): the exact bit-to-day mapping is a pending live
 * capture. The assumed layout here is: the payload is a big-endian integer whose bit n
 * (LSB = day 0 = today) marks the day n days ago as still holding unsynced data. The sync
 * engine treats an unusable payload as "pull the recent window", and because upserts are
 * idempotent on composite PKs, that fallback can never create duplicates.
 */
object DistributionBitmap {

    const val WINDOW_DAYS = 28

    /** Parse the 0x46 payload into a distribution integer, or null when it is unusable. */
    fun parse(payload: ByteArray): Int? {
        if (payload.isEmpty()) return null
        val len = minOf(4, payload.size)
        return ByteOrder.fromBeBytes(payload, 0, len)
    }

    /** Day offsets (0 = today) that the distribution marks as unsynced. */
    fun daysToPull(distribution: Int): Set<Int> {
        val out = HashSet<Int>()
        for (n in 0 until WINDOW_DAYS) {
            if (distribution and (1 shl n) != 0) out.add(n)
        }
        return out
    }

    /** True when no day in the window is marked unsynced (nothing left to pull). */
    fun isComplete(distribution: Int): Boolean = daysToPull(distribution).isEmpty()

    /** The recent-window fallback when the distribution payload is unusable. */
    fun recentWindow(days: Int = 7): Set<Int> = (0 until days).toSet()
}

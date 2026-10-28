package com.kschulz.qring.data.ble

import com.kschulz.qring.data.model.StepDay

/**
 * Pure byte parsers for QRing response payloads. No Android, no transport: ByteArray in,
 * model out. These are the byte-correct core of the protocol (verified against
 * re/PROTOCOL.md sections 6 and 7) and the part that is unit-tested on the JVM.
 */
object RingPayloads {

    // 0xFFFFFFFF read as a signed 4-byte big-endian Int is -1 (fromBeBytes returns Int).
    private const val MANUAL_BP_SENTINEL = 0xFFFFFFFF.toInt()

    data class ManualBpReading(val epochMs: Long, val sbp: Int, val dbp: Int)
    data class HeartRateRecord(val epochMs: Long, val bpm: Int)
    data class SleepStage(val stage: Int, val durationMin: Int)
    data class SleepParse(
        val startEpochMs: Long,
        val endEpochMs: Long,
        val isNap: Boolean,
        val stages: List<SleepStage>,
    )

    /**
     * 0x48 GET_STEP_TODAY (re/PROTOCOL.md 6.2, big-endian):
     * steps[0..3], runningSteps[4..5], calorie[6..7], walkDistance[9..10], duration[0xC..0xD].
     * [epochDay] is supplied by the caller (the phone fills dates from its calendar).
     */
    fun parseStepToday(payload: ByteArray, epochDay: Long): StepDay? {
        if (payload.size < 14) return null
        return StepDay(
            profileId = 0, // caller overwrites with the active profile before upsert
            date = epochDay,
            steps = ByteOrder.fromBeBytes(payload, 0, 4),
            distanceMeters = ByteOrder.fromBeBytes(payload, 9, 2),
            calories = ByteOrder.fromBeBytes(payload, 6, 2),
            durationMin = ByteOrder.fromBeBytes(payload, 0x0C, 2),
        )
    }

    /** True when the packet's timestamp field is the 0xFFFFFFFF end-of-stream sentinel. */
    fun isManualBpSentinel(payload: ByteArray): Boolean {
        if (payload.size < 4) return false
        return ByteOrder.fromBeBytes(payload, 0, 4) == MANUAL_BP_SENTINEL
    }

    /**
     * One 0x14 manual BP packet (re/PROTOCOL.md 7.2, big-endian ts, direct mmHg):
     * ts[0..3] seconds, sbp[4], dbp[5]. Returns null on the sentinel or a short packet.
     */
    fun parseManualBpPacket(payload: ByteArray): ManualBpReading? {
        if (payload.size < 6) return null
        if (isManualBpSentinel(payload)) return null
        val tsSeconds = ByteOrder.fromBeBytes(payload, 0, 4)
        if (tsSeconds == MANUAL_BP_SENTINEL) return null
        return ManualBpReading(
            epochMs = tsSeconds.toLong() * 1000L,
            sbp = payload[4].toInt() and 0xFF,
            dbp = payload[5].toInt() and 0xFF,
        )
    }

    /**
     * 0x15 heart-rate history (re/PROTOCOL.md 7.5): the body is a run of 13-byte records.
     * The exact sample layout inside a 13-byte record is device-defined, so this reads a
     * best-effort (epoch, bpm) from a fixed offset and never throws on a short final chunk.
     */
    fun parseHeartRateRecord(record: ByteArray): HeartRateRecord? {
        if (record.size < 13) return null
        val tsSeconds = ByteOrder.fromBeBytes(record, 0, 4)
        val bpm = ByteOrder.fromBeBytes(record, 4, 2)
        if (tsSeconds == 0 || bpm == 0) return null
        return HeartRateRecord(epochMs = tsSeconds.toLong() * 1000L, bpm = bpm)
    }
}

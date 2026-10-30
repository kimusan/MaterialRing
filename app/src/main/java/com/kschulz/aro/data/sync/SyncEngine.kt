package com.kschulz.aro.data.sync

import com.kschulz.aro.data.ble.ByteOrder
import com.kschulz.aro.data.ble.AroDbg
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingCommand
import com.kschulz.aro.data.ble.RingPayloads
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.HealthStore
import com.kschulz.aro.domain.BodyBattery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The resilient pull. Driven by the 0x46 distribution bitmap: query it, pull only the
 * days it marks unsynced, upsert by composite PK, re-query at the end to confirm the
 * bitmap cleared. A dropped connection mid-run is not a failure mode because the next run
 * simply re-queries 0x46 and re-pulls, and the upsert key makes any re-pull a no-op.
 *
 * Depends only on [RingCommand], [TimeSource], [HealthStore], and [BodyBattery], so the
 * whole loop is unit-testable with fakes (no GATT, no clock, no Room).
 */
@Singleton
class SyncEngine @Inject constructor(
    private val command: RingCommand,
    private val health: HealthStore,
    private val bodyBattery: BodyBattery,
) {
    /**
     * @param profileId the profile to write into (resolved by the caller from the active
     * profile, so a sync always lands in the profile the user is on).
     */
    suspend fun sync(profileId: Long, time: TimeSource): SyncResult {
        if (!command.isConnected) return SyncResult(connected = false)

        // The vendor's post-bind init chain sends SET_DEVICE_TIME (0x01) first, before any
        // data query; the ring answers history pulls only once it has a clock. Do the same
        // here so the 0x46 distribution query below is not answered with nothing.
        sendTimeBaseline(time)

        val distribution = queryDistribution()
        val days = if (distribution != null) DistributionBitmap.daysToPull(distribution)
        else DistributionBitmap.recentWindow()
        com.kschulz.aro.data.ble.AroDbg.d("sync start: distribution=${distribution?.toString(16)} days=${days.sorted()}")

        val today = time.todayEpochDay()
        var steps = 0
        var bpReadings = 0
        var hrSamples = 0
        var sleepSessions = 0

        for (daysAgo in days) {
            val epochDay = time.epochDayForOffset(daysAgo)
            steps += pullStepsForDay(profileId, epochDay, daysAgo).size
            bpReadings += pullManualBp(profileId).size
            hrSamples += pullHeartRate(profileId, daysAgo).size
            sleepSessions += pullSleep(profileId, epochDay, daysAgo).size
        }

        // Body battery is derived from what we just stored; recompute for the window.
        val windowStartHour = time.epochDayForOffset(days.maxOfOrNull { it } ?: 0) * 24
        val windowEndHour = today * 24 + 23
        runCatching {
            bodyBattery.recompute(profileId, windowStartHour, windowEndHour)
        }

        // Confirm the bitmap cleared; report whether it did (best-effort).
        val finalDistribution = queryDistribution()
        val confirmed = finalDistribution == null || DistributionBitmap.isComplete(finalDistribution)
        com.kschulz.aro.data.ble.AroDbg.d(
            "sync done: steps=$steps bp=$bpReadings hr=$hrSamples sleep=$sleepSessions " +
                "finalDistribution=${finalDistribution?.toString(16)} cleared=$confirmed",
        )

        return SyncResult(
            connected = true,
            daysSynced = days.size,
            steps = steps,
            bpReadings = bpReadings,
            hrSamples = hrSamples,
            sleepSessions = sleepSessions,
            distributionCleared = confirmed,
        )
    }

    private suspend fun queryDistribution(): Int? {
        val rsp = command.request(Opcodes.QUERY_DATA_DISTRIBUTION) ?: return null
        return DistributionBitmap.parse(rsp.payload)
    }

    private suspend fun sendTimeBaseline(time: TimeSource) {
        val c = time.localClock()
        val payload = RingPayloads.buildSetTimePayload(
            c.year, c.month, c.day, c.hour, c.minute, c.second,
        )
        AroDbg.d(
            "set time baseline ${c.year}-${c.month}-${c.day} " +
                "${c.hour}:${c.minute}:${c.second} ${AroDbg.hex(payload)}",
        )
        command.request(Opcodes.SET_DEVICE_TIME, payload)
    }

    private suspend fun pullStepsForDay(profileId: Long, epochDay: Long, daysAgo: Int): List<StepDay> {
        val cmd = if (daysAgo == 0) Opcodes.GET_STEP_TODAY else Opcodes.GET_STEP_TOTAL_SOMEDAY
        val payload = if (daysAgo == 0) null else ByteOrder.toLeBytes(daysAgo, 4)
        val rsp = command.request(cmd, payload) ?: return emptyList()
        val day = RingPayloads.parseStepToday(rsp.payload, epochDay) ?: return emptyList()
        val stored = day.copy(profileId = profileId)
        health.upsertSteps(profileId, listOf(stored))
        return listOf(stored)
    }

    private suspend fun pullManualBp(profileId: Long): List<BloodPressure> {
        // 0x14 request: utc_seconds(4, LE) | 0x00 | 0x32 (max 50 records).
        val nowUtc = (System.currentTimeMillis() / 1000L).toInt()
        val req = ByteArray(6)
        ByteOrder.toLeBytes(nowUtc, 4).copyInto(req, 0)
        req[4] = 0x00
        req[5] = 0x32.toByte()

        val packets = command.collectUntil(
            cmd = Opcodes.GET_BAND_PRESSURE,
            payload = req,
            shouldStop = { RingPayloads.isManualBpSentinel(it.payload) },
            maxPackets = 50,
        )
        val readings = packets
            .mapNotNull { RingPayloads.parseManualBpPacket(it.payload) }
            .map { BloodPressure(profileId, it.epochMs, it.sbp, it.dbp, BpSource.MANUAL) }
        if (readings.isNotEmpty()) health.upsertBloodPressure(profileId, readings)
        return readings
    }

    private suspend fun pullHeartRate(profileId: Long, daysAgo: Int): List<HeartRateSample> {
        // 0x15 request carries a day offset; the body is a run of 13-byte records.
        val rsp = command.request(Opcodes.GET_HEART_RATE, ByteOrder.toLeBytes(daysAgo, 4)) ?: return emptyList()
        val payload = rsp.payload
        val count = payload.size / 13
        val samples = (0 until count).mapNotNull { i ->
            RingPayloads.parseHeartRateRecord(payload.copyOfRange(i * 13, i * 13 + 13))
                ?.let { HeartRateSample(profileId, it.epochMs, it.bpm) }
        }
        if (samples.isNotEmpty()) health.upsertHeartRate(profileId, samples)
        return samples
    }

    private suspend fun pullSleep(profileId: Long, epochDay: Long, daysAgo: Int): List<SleepSession> {
        // Request mirrors the vendor: [dayOffset, 0x0F, startIdx=0, endIdx=0x5F] (full slot
        // range in one shot). The device answers a 0x44 record; parseSleep decodes the
        // first (main) record. daysAgo is capped by the ring (it rejects offsets > 29).
        if (daysAgo > 29) return emptyList()
        val req = byteArrayOf(daysAgo.toByte(), 0x0F, 0x00, 0x5F.toByte())
        val rsp = command.request(Opcodes.GET_SLEEP, req) ?: return emptyList()
        val parsed = RingPayloads.parseSleep(rsp.payload, epochDay) ?: return emptyList()
        val session = SleepSession(
            profileId = profileId,
            startEpoch = parsed.startEpochMs,
            endEpoch = parsed.endEpochMs,
            isNap = parsed.isNap,
        )
        val segments = parsed.stages.map {
            SleepSegment(sessionId = 0, stage = it.stage, durationMin = it.durationMin)
        }
        health.upsertSleep(profileId, session, segments)
        return listOf(session)
    }
}

data class SyncResult(
    val connected: Boolean,
    val daysSynced: Int = 0,
    val steps: Int = 0,
    val bpReadings: Int = 0,
    val hrSamples: Int = 0,
    val sleepSessions: Int = 0,
    val distributionCleared: Boolean = false,
)

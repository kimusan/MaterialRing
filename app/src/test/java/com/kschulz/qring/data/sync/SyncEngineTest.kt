package com.kschulz.qring.data.sync

import com.kschulz.qring.data.ble.ConnectionState
import com.kschulz.qring.data.ble.Opcodes
import com.kschulz.qring.data.ble.ParsedFrame
import com.kschulz.qring.data.ble.RingCommand
import com.kschulz.qring.data.model.BloodPressure
import com.kschulz.qring.data.model.HeartRateSample
import com.kschulz.qring.data.model.StepDay
import com.kschulz.qring.data.repository.HealthStore
import com.kschulz.qring.domain.BodyBattery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the sync loop is resumable and idempotent (a core requirement): re-running over the
 * same distribution never produces a new, distinct row. Pure JVM, no GATT/Room.
 */
class SyncEngineTest {

    private class FakeCommand(
        private val connected: Boolean = true,
        private val distribution: ByteArray? = ByteArray(4),
    ) : RingCommand {
        var stepsRequests = 0

        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(if (connected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED)

        override val isConnected: Boolean get() = connected

        override suspend fun request(cmd: Int, payload: ByteArray?, timeoutMs: Long): ParsedFrame? {
            if (!connected) return null
            return when (cmd) {
                Opcodes.QUERY_DATA_DISTRIBUTION -> distribution?.let { ParsedFrame(cmd, false, it) }
                Opcodes.GET_STEP_TODAY, Opcodes.GET_STEP_TOTAL_SOMEDAY -> {
                    stepsRequests++
                    ParsedFrame(cmd, false, stepsPayload(5000))
                }

                Opcodes.GET_HEART_RATE -> ParsedFrame(cmd, false, hrPayload(1))
                else -> null
            }
        }

        override suspend fun collectUntil(
            cmd: Int,
            payload: ByteArray?,
            shouldStop: (ParsedFrame) -> Boolean,
            maxPackets: Int,
            timeoutMs: Long,
        ): List<ParsedFrame> {
            if (!connected) return emptyList()
            return if (cmd == Opcodes.GET_BAND_PRESSURE) {
                // One reading, then the end sentinel. The engine's parser drops the sentinel.
                listOf(
                    ParsedFrame(cmd, false, bpPayload(1_700_000_000L, 120, 80)),
                    ParsedFrame(cmd, false, ByteArray(4) { 0xFF.toByte() }),
                )
            } else {
                emptyList()
            }
        }

        private fun stepsPayload(steps: Int): ByteArray {
            val p = ByteArray(14)
            p[0] = (steps shr 24).toByte()
            p[1] = (steps shr 16).toByte()
            p[2] = (steps shr 8).toByte()
            p[3] = steps.toByte()
            return p
        }

        private fun hrPayload(count: Int): ByteArray {
            val record = ByteArray(13)
            record[0] = 0x66; record[1] = 0x40; record[2] = 0x00; record[3] = 0x01
            record[4] = 0x00; record[5] = 0x5D // bpm 93
            return ByteArray(count * 13) { i -> record[i % 13] }
        }

        private fun bpPayload(tsSeconds: Long, sbp: Int, dbp: Int): ByteArray {
            val p = ByteArray(6)
            p[0] = ((tsSeconds shr 24) and 0xFF).toByte()
            p[1] = ((tsSeconds shr 16) and 0xFF).toByte()
            p[2] = ((tsSeconds shr 8) and 0xFF).toByte()
            p[3] = (tsSeconds and 0xFF).toByte()
            p[4] = sbp.toByte()
            p[5] = dbp.toByte()
            return p
        }
    }

    private class RecordingStore : HealthStore {
        val steps = mutableListOf<StepDay>()
        val bp = mutableListOf<BloodPressure>()
        val hr = mutableListOf<HeartRateSample>()

        override suspend fun upsertSteps(profileId: Long, days: List<StepDay>) { steps += days }
        override suspend fun upsertBloodPressure(profileId: Long, readings: List<BloodPressure>) { bp += readings }
        override suspend fun upsertHeartRate(profileId: Long, samples: List<HeartRateSample>) { hr += samples }
    }

    private object NoopBodyBattery : BodyBattery {
        override suspend fun recompute(profileId: Long, fromHour: Long, toHour: Long) {}
    }

    private val time = FixedTimeSource(todayEpochDay = 1000, nowMillis = 1000L * 86_400_000L)

    private fun distTodayUnsynced() = byteArrayOf(0, 0, 0, 1) // bit 0 = today

    @Test
    fun `sync pulls the days the distribution marks and writes the rows`() {
        val command = FakeCommand(distribution = distTodayUnsynced())
        val store = RecordingStore()
        val engine = SyncEngine(command, store, NoopBodyBattery)

        val result = runBlocking { engine.sync(profileId = 1, time = time) }

        assertTrue(result.connected)
        assertEquals(1, result.daysSynced)
        assertEquals(1, command.stepsRequests)
        assertEquals(1, store.steps.size)
        assertEquals(5000, store.steps.single().steps)
        assertEquals(1, store.hr.size)
        assertEquals(93, store.hr.single().bpm)
        assertEquals(1, store.bp.size)
        assertEquals(120, store.bp.single().sbp)
        assertEquals(80, store.bp.single().dbp)
    }

    @Test
    fun `re-running over the same distribution writes identical rows, never duplicates`() {
        val command = FakeCommand(distribution = distTodayUnsynced())
        val store = RecordingStore()
        val engine = SyncEngine(command, store, NoopBodyBattery)

        runBlocking { engine.sync(1, time) }
        runBlocking { engine.sync(1, time) }

        // Two runs re-pull the same day. Idempotency means the second run produces the same
        // composite-PK row, not a distinct one, so an INSERT OR REPLACE in Room is a no-op.
        assertEquals(2, store.steps.size)
        val first = store.steps.first()
        val second = store.steps.last()
        assertEquals(first.date, second.date)
        assertEquals(first.steps, second.steps)
        assertEquals(first.distanceMeters, second.distanceMeters)
        assertEquals(first.calories, second.calories)
        assertEquals(1000L, first.date) // todayEpochDay for daysAgo 0
    }

    @Test
    fun `sync reports disconnected when there is no link`() {
        val command = FakeCommand(connected = false)
        val engine = SyncEngine(command, RecordingStore(), NoopBodyBattery)

        val result = runBlocking { engine.sync(1, time) }

        assertFalse(result.connected)
        assertEquals(0, result.daysSynced)
    }
}

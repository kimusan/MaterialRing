package com.kschulz.aro.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.ble.RingCommandClient
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.model.StressSample
import com.kschulz.aro.data.model.TemperatureSample
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.SyncEngine
import com.kschulz.aro.data.sync.TimeSource
import com.kschulz.aro.domain.BodyBatteryAlgorithm
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class HistoryPeriod(val days: Int, val label: String) {
    DAY(1, "Day"),
    WEEK(7, "Week"),
    MONTH(30, "Month"),
}

private data class HistoryQuery(
    val fromDay: Long,
    val toDay: Long,
    val ringMac: String?,
)

/**
 * Per-metric history across Day / Week / Month views with date navigation and per-ring
 * or combined-rings filtering, read straight from Room and reactive to the active profile.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val health: HealthRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val devices: DeviceRepository,
    private val transport: RingBleTransport,
    private val command: RingCommandClient,
    private val engine: SyncEngine,
    private val time: TimeSource,
) : ViewModel() {

    private val _period = MutableStateFlow(HistoryPeriod.DAY)
    val period: StateFlow<HistoryPeriod> = _period.asStateFlow()

    private val _anchorEpochDay = MutableStateFlow(time.todayEpochDay())
    val anchorEpochDay: StateFlow<Long> = _anchorEpochDay.asStateFlow()

    /** `null` means All rings (Combined); a non-null MAC filters to that specific ring. */
    private val _selectedRingMac = MutableStateFlow<String?>(null)
    val selectedRingMac: StateFlow<String?> = _selectedRingMac.asStateFlow()

    val rings: StateFlow<List<Ring>> =
        devices.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayEpochDay: Long
        get() = time.todayEpochDay()

    val activeProfile: StateFlow<Profile?> =
        profiles.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val useMetric: StateFlow<Boolean> =
        settings.observeUseMetric()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    fun setPeriod(newPeriod: HistoryPeriod) {
        _period.value = newPeriod
    }

    fun selectRing(macAddress: String?) {
        _selectedRingMac.value = macAddress
    }

    fun setWindow(days: Int) {
        _period.value = when {
            days <= 1 -> HistoryPeriod.DAY
            days <= 7 -> HistoryPeriod.WEEK
            else -> HistoryPeriod.MONTH
        }
    }

    fun selectEpochDay(epochDay: Long) {
        _anchorEpochDay.value = epochDay.coerceAtMost(time.todayEpochDay())
    }

    fun previousPeriod() {
        val step = _period.value.days.toLong()
        _anchorEpochDay.value = (_anchorEpochDay.value - step).coerceAtLeast(0L)
    }

    fun nextPeriod() {
        val step = _period.value.days.toLong()
        val today = time.todayEpochDay()
        _anchorEpochDay.value = (_anchorEpochDay.value + step).coerceAtMost(today)
    }

    fun resetToToday() {
        _anchorEpochDay.value = time.todayEpochDay()
    }

    private fun rangeFor(period: HistoryPeriod, anchorDay: Long): Pair<Long, Long> {
        val to = anchorDay
        val from = (to - (period.days - 1).toLong()).coerceAtLeast(0L)
        return from to to
    }

    private val queryFlow = combine(_period, _anchorEpochDay, _selectedRingMac) { p, anchor, mac ->
        val (from, to) = rangeFor(p, anchor)
        HistoryQuery(from, to, mac)
    }

    val steps: StateFlow<List<StepDay>> = queryFlow
        .flatMapLatest { q ->
            health.observeStepsBetween(q.fromDay, q.toDay, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 7-day strip context around the current anchor day for the Daily week-progress strip. */
    val weekStripSteps: StateFlow<List<StepDay>> = combine(_anchorEpochDay, _selectedRingMac) { anchor, mac ->
        anchor to mac
    }
        .flatMapLatest { (anchor, mac) ->
            val from = (anchor - 6L).coerceAtLeast(0L)
            health.observeStepsBetween(from, anchor, mac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleep: StateFlow<List<SleepSession>> = queryFlow
        .flatMapLatest { q ->
            // Include the prior evening (6h before midnight) so nights crossing midnight are captured.
            val startMs = q.fromDay * 86_400_000L - 6 * 3_600_000L
            val endMs = (q.toDay + 1) * 86_400_000L
            health.observeSleepSessions(startMs, endMs, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleepSegments: StateFlow<List<SleepSegment>> = queryFlow
        .flatMapLatest { q ->
            val startMs = q.fromDay * 86_400_000L - 6 * 3_600_000L
            val endMs = (q.toDay + 1) * 86_400_000L
            health.observeSleepSegmentsBetween(startMs, endMs, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heartRate: StateFlow<List<HeartRateSample>> = queryFlow
        .flatMapLatest { q ->
            health.observeHeartRateBetween(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bloodPressure: StateFlow<List<BloodPressure>> = queryFlow
        .flatMapLatest { q ->
            health.observeBloodPressureBetween(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val spo2: StateFlow<List<Spo2Sample>> = queryFlow
        .flatMapLatest { q ->
            health.observeSpo2Between(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stress: StateFlow<List<StressSample>> = queryFlow
        .flatMapLatest { q ->
            health.observeStressBetween(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val temperature: StateFlow<List<TemperatureSample>> = queryFlow
        .flatMapLatest { q ->
            health.observeTemperatureBetween(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bodyBattery: StateFlow<List<BodyBatteryPoint>> = queryFlow
        .flatMapLatest { q ->
            val fromHour = q.fromDay * 24L
            val toHour = minOf((q.toDay + 1) * 24L - 1L, time.nowMillis() / 3_600_000L)
            if (q.ringMac == null) {
                health.observeBodyBatteryBetween(fromHour, (q.toDay + 1) * 24L - 1L)
            } else {
                combine(
                    health.observeSleepSessions(q.fromDay * 86_400_000L - 6 * 3_600_000L, (q.toDay + 1) * 86_400_000L, q.ringMac),
                    health.observeHeartRateBetween(q.fromDay * 86_400_000L, (q.toDay + 1) * 86_400_000L, q.ringMac),
                    health.observeStepsBetween(q.fromDay, q.toDay, q.ringMac),
                ) { sessions, hrSamples, stepDays ->
                    computeRingBodyBattery(fromHour, toHour, sessions, hrSamples, stepDays)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun computeRingBodyBattery(
        fromHour: Long,
        toHour: Long,
        sessions: List<SleepSession>,
        hrSamples: List<HeartRateSample>,
        stepDays: List<StepDay>,
    ): List<BodyBatteryPoint> {
        if (toHour < fromHour) return emptyList()
        val hasAnySignal = sessions.isNotEmpty() || hrSamples.isNotEmpty() || stepDays.any { it.steps > 0 }
        if (!hasAnySignal) return emptyList()

        val hrByHour = hrSamples
            .filter { it.bpm > 0 }
            .groupBy { it.epochMs / 3_600_000L }
            .mapValues { (_, list) -> list.map { it.bpm }.average().toInt() }
        val stepsByDay = stepDays.associate { it.date to it.steps }

        val inputs = ArrayList<BodyBatteryAlgorithm.HourlyInput>((toHour - fromHour + 1).toInt())
        var hour = fromHour
        while (hour <= toHour) {
            val hourStartMs = hour * 3_600_000L
            val hourEndMs = hourStartMs + 3_600_000L
            var sleepMin = 0
            for (s in sessions) {
                val overlapStart = max(hourStartMs, s.startEpoch)
                val overlapEnd = min(hourEndMs, s.endEpoch)
                if (overlapEnd > overlapStart) {
                    sleepMin += ((overlapEnd - overlapStart) / 60_000L).toInt()
                }
            }
            sleepMin = sleepMin.coerceIn(0, 60)
            val daySteps = stepsByDay[hour / 24L] ?: 0
            val hourOfDay = ((hour % 24L) + 24L) % 24L
            val hourlySteps = if (daySteps > 0 && sleepMin < 30 && hourOfDay in 7..22) {
                daySteps / 16
            } else {
                0
            }
            inputs.add(
                BodyBatteryAlgorithm.HourlyInput(
                    epochHour = hour,
                    sleepMinutes = sleepMin,
                    meanHr = hrByHour[hour],
                    steps = hourlySteps,
                ),
            )
            hour += 1
        }
        return BodyBatteryAlgorithm.computeHourly(inputs)
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val profileId = settings.observeActiveProfileId().first()
                if (profileId == SettingsRepository.NO_PROFILE) return@launch
                if (!command.isConnected) {
                    val allRings = devices.observeAll().first()
                    val preferredMac = _selectedRingMac.value
                    val ring = allRings.firstOrNull { it.macAddress == preferredMac }
                        ?: allRings.firstOrNull { it.isActive }
                        ?: allRings.firstOrNull()
                        ?: return@launch
                    if (transport.connectionState.value != ConnectionState.CONNECTING) {
                        if (!transport.connect(ring.macAddress)) return@launch
                    }
                    val connected = withTimeoutOrNull(10_000L) {
                        transport.connectionState.first { it != ConnectionState.CONNECTING }
                    } == ConnectionState.CONNECTED
                    if (!connected) {
                        transport.disconnect()
                        return@launch
                    }
                }
                command.refreshBattery()
                val profile = profiles.getById(profileId)
                val metric = settings.observeUseMetric().first()
                engine.sync(profileId, time, profile, metric)
            } finally {
                _syncing.value = false
            }
        }
    }
}

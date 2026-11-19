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
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.SyncEngine
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

/**
 * Per-metric history across Day / Week / Month views with date navigation, read straight
 * from Room and reactive to both the active profile and the selected period/anchor date.
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

    private val windowFlow = combine(_period, _anchorEpochDay) { p, anchor ->
        rangeFor(p, anchor)
    }

    val steps: StateFlow<List<StepDay>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeStepsBetween(from, to)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 7-day strip context around the current anchor day for the Daily week-progress strip. */
    val weekStripSteps: StateFlow<List<StepDay>> = _anchorEpochDay
        .flatMapLatest { anchor ->
            val from = (anchor - 6L).coerceAtLeast(0L)
            health.observeStepsBetween(from, anchor)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleep: StateFlow<List<SleepSession>> = windowFlow
        .flatMapLatest { (from, to) ->
            // Include the prior evening (6h before midnight) so nights crossing midnight are captured.
            val startMs = from * 86_400_000L - 6 * 3_600_000L
            val endMs = (to + 1) * 86_400_000L
            health.observeSleepSessions(startMs, endMs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sleepSegments: StateFlow<List<SleepSegment>> = windowFlow
        .flatMapLatest { (from, to) ->
            val startMs = from * 86_400_000L - 6 * 3_600_000L
            val endMs = (to + 1) * 86_400_000L
            health.observeSleepSegmentsBetween(startMs, endMs)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val heartRate: StateFlow<List<HeartRateSample>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeHeartRateBetween(from * 86_400_000L, (to + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bloodPressure: StateFlow<List<BloodPressure>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeBloodPressureBetween(from * 86_400_000L, (to + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val spo2: StateFlow<List<com.kschulz.aro.data.model.Spo2Sample>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeSpo2Between(from * 86_400_000L, (to + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val stress: StateFlow<List<com.kschulz.aro.data.model.StressSample>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeStressBetween(from * 86_400_000L, (to + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val temperature: StateFlow<List<com.kschulz.aro.data.model.TemperatureSample>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeTemperatureBetween(from * 86_400_000L, (to + 1) * 86_400_000L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val bodyBattery: StateFlow<List<BodyBatteryPoint>> = windowFlow
        .flatMapLatest { (from, to) ->
            health.observeBodyBatteryBetween(from * 24L, (to + 1) * 24L - 1L)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val profileId = settings.observeActiveProfileId().first()
                if (profileId == SettingsRepository.NO_PROFILE) return@launch
                if (!command.isConnected) {
                    val ring = devices.observeAll().first().firstOrNull() ?: return@launch
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
                command.request(Opcodes.GET_DEVICE_ELECTRICITY_VALUE)
                val profile = profiles.getById(profileId)
                val metric = settings.observeUseMetric().first()
                engine.sync(profileId, time, profile, metric)
            } finally {
                _syncing.value = false
            }
        }
    }
}

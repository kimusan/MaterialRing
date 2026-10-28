package com.kschulz.qring.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.qring.data.ble.ConnectionState
import com.kschulz.qring.data.ble.RingCommandClient
import com.kschulz.qring.data.model.BloodPressure
import com.kschulz.qring.data.model.BodyBatteryPoint
import com.kschulz.qring.data.model.SleepSession
import com.kschulz.qring.data.model.StepDay
import com.kschulz.qring.data.repository.HealthRepository
import com.kschulz.qring.data.repository.ProfileRepository
import com.kschulz.qring.data.repository.SettingsRepository
import com.kschulz.qring.data.sync.SyncEngine
import com.kschulz.qring.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The home dashboard: live vitals plus the latest stored readings for the active profile.
 *  Real-time values come straight from the command client; stored values are reactive Room
 *  flows scoped to the active profile (so switching profiles updates this screen instantly). */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val health: HealthRepository,
    private val command: RingCommandClient,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val engine: SyncEngine,
    private val time: TimeSource,
) : ViewModel() {

    val connected: StateFlow<Boolean> = command.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Live values (no persistence).
    val liveHeartRate: StateFlow<Int> = command.realTimeHeartRate
    val battery: StateFlow<Int> = command.batteryLevel

    private val todayEpochDay get() = time.todayEpochDay()

    val today: StateFlow<StepDay?> =
        health.observeTodaySteps(todayEpochDay)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestBp: StateFlow<BloodPressure?> =
        health.observeLatestBloodPressure()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestSleep: StateFlow<SleepSession?> =
        health.observeLatestMainSleep()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bodyBattery: StateFlow<List<BodyBatteryPoint>> =
        health.observeBodyBatteryBetween(todayEpochDay * 24 - 23, todayEpochDay * 24)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val profileName: StateFlow<String?> =
        profiles.observeActive()
            .map { it?.name }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSync = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = _lastSync.asStateFlow()

    private var profileSeeded = false

    /** First-run guard: create a default profile and activate it when none exist, so the
     *  profile-scoped reads on this screen have somewhere to point. */
    fun ensureDefaultProfile() {
        if (profileSeeded) return
        profileSeeded = true
        viewModelScope.launch {
            val existing = profiles.observeAll().first()
            if (existing.isEmpty()) {
                val id = profiles.add(DEFAULT_PROFILE_NAME, time.nowMillis())
                profiles.switchTo(id)
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val profileId = settings.observeActiveProfileId().first()
                if (profileId == SettingsRepository.NO_PROFILE) {
                    _lastSync.value = "No profile selected"
                    return@launch
                }
                val result = engine.sync(profileId, time)
                _lastSync.value = if (result.connected) {
                    "Synced ${result.daysSynced} day(s): ${result.steps} steps, " +
                        "${result.bpReadings} BP, ${result.hrSamples} HR"
                } else {
                    "Device not connected"
                }
            } catch (e: Exception) {
                _lastSync.value = "Sync failed: ${e.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    companion object {
        const val DEFAULT_PROFILE_NAME = "Me"
    }
}

package com.kschulz.aro.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.ble.RingCommandClient
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.SyncEngine
import com.kschulz.aro.data.sync.TimeSource
import com.kschulz.aro.ui.device.BlePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The home dashboard: live vitals plus the latest stored readings for the active profile.
 *  Real-time values come straight from the command client; stored values are reactive Room
 *  flows scoped to the active profile (so switching profiles updates this screen instantly). */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val transport: RingBleTransport,
    private val command: RingCommandClient,
    private val devices: DeviceRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val engine: SyncEngine,
    private val time: TimeSource,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = command.connectionState

    val connected: StateFlow<Boolean> = command.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val boundRing: StateFlow<Ring?> = devices.observeAll()
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    val activeProfile: StateFlow<com.kschulz.aro.data.model.Profile?> =
        profiles.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allProfiles: StateFlow<List<com.kschulz.aro.data.model.Profile>> =
        profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val useMetric: StateFlow<Boolean> =
        settings.observeUseMetric()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val profileName: StateFlow<String?> =
        profiles.observeActive()
            .map { it?.name }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSync = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = _lastSync.asStateFlow()

    private var profileSeeded = false

    init {
        // Automatically connect to the bound ring on startup if permissions are granted.
        viewModelScope.launch {
            devices.observeAll().collect { bound ->
                val ring = bound.firstOrNull() ?: return@collect
                if (
                    transport.connectionState.value == ConnectionState.DISCONNECTED &&
                    BlePermissions.hasAllPermissions(context)
                ) {
                    transport.connect(ring.macAddress)
                }
            }
        }
        // Fetch battery level whenever the ring reaches CONNECTED state.
        viewModelScope.launch {
            transport.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED && battery.value < 0) {
                    command.request(Opcodes.GET_DEVICE_ELECTRICITY_VALUE)
                }
            }
        }
    }

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

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            profiles.switchTo(id)
            profiles.getById(id)?.let {
                engine.syncProfileSettings(it, time, useMetric.value)
            }
        }
    }

    fun saveProfile(profile: com.kschulz.aro.data.model.Profile) {
        if (profile.name.isBlank()) return
        viewModelScope.launch {
            val isNew = profile.id == 0L
            val savedId = profiles.save(
                if (isNew) profile.copy(createdAtEpochMs = time.nowMillis()) else profile,
            )
            if (isNew || activeProfile.value == null) {
                profiles.switchTo(savedId)
            }
            profiles.getById(savedId)?.let {
                engine.syncProfileSettings(it, time, useMetric.value)
            }
            _lastSync.value = "Profile \"${profile.name}\" saved"
        }
    }

    fun reconnect() {
        viewModelScope.launch {
            val ring = devices.observeAll().first().firstOrNull()
            if (ring == null) {
                _lastSync.value = "No bound ring"
                return@launch
            }
            if (!BlePermissions.hasAllPermissions(context)) {
                _lastSync.value = "Bluetooth permission required"
                return@launch
            }
            _lastSync.value = "Connecting to ${ring.displayName}..."
            if (!transport.connect(ring.macAddress)) {
                _lastSync.value = "Bluetooth unavailable"
                return@launch
            }
            val finalState = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                transport.connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (finalState == ConnectionState.CONNECTED) {
                _lastSync.value = "Connected to ${ring.displayName}"
                syncNow()
            } else {
                transport.disconnect()
                _lastSync.value = "Could not connect to ${ring.displayName}"
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
                if (!command.isConnected) {
                    val ring = devices.observeAll().first().firstOrNull()
                    if (ring == null) {
                        _lastSync.value = "No ring bound"
                        return@launch
                    }
                    _lastSync.value = "Connecting to ${ring.displayName}..."
                    if (transport.connectionState.value != ConnectionState.CONNECTING) {
                        if (!transport.connect(ring.macAddress)) {
                            _lastSync.value = "Bluetooth unavailable"
                            return@launch
                        }
                    }
                    val finalState = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                        transport.connectionState.first { it != ConnectionState.CONNECTING }
                    }
                    if (finalState != ConnectionState.CONNECTED) {
                        transport.disconnect()
                        _lastSync.value = "Device not connected. Is the ring nearby?"
                        return@launch
                    }
                }
                command.request(Opcodes.GET_DEVICE_ELECTRICITY_VALUE)
                val currentProfile = profiles.getById(profileId)
                val metric = settings.observeUseMetric().first()
                val result = engine.sync(profileId, time, currentProfile, metric)
                _lastSync.value = if (result.connected) {
                    "Synced ${result.daysSynced} day(s): ${result.steps} steps, " +
                        "${result.bpReadings} BP, ${result.hrSamples} HR, ${result.sleepSessions} sleep"
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
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }
}

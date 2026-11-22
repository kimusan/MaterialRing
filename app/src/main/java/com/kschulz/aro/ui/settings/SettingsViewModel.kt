package com.kschulz.aro.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.healthconnect.HealthConnectExporter
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.SyncEngine
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Profiles (add / switch / edit / delete, instant), unit preferences, the opt-in Health
 *  Connect toggle, and bound-device info. No cloud: everything here is local. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val devices: DeviceRepository,
    private val transport: RingBleTransport,
    private val syncEngine: SyncEngine,
    private val healthConnect: HealthConnectExporter,
    private val time: TimeSource,
) : ViewModel() {

    val healthConnectAvailable: Boolean
        get() = healthConnect.isAvailable()

    val allProfiles: StateFlow<List<Profile>> =
        profiles.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> =
        settings.observeActiveProfileId().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.NO_PROFILE)

    val boundDevices: StateFlow<List<Ring>> =
        devices.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeRing: StateFlow<Ring?> =
        devices.observeActive().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeTransportMac: StateFlow<String?> = transport.activeMac

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    val healthConnectEnabled: StateFlow<Boolean> =
        settings.observeHealthConnectEnabled().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val useMetric: StateFlow<Boolean> =
        settings.observeUseMetric().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val autoHrInterval: StateFlow<Int> =
        settings.observeAutoHrInterval().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_HR_INTERVAL_MIN,
        )

    val autoBpInterval: StateFlow<Int> =
        settings.observeAutoBpInterval().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_BP_INTERVAL_MIN,
        )

    val autoSpo2Interval: StateFlow<Int> =
        settings.observeAutoSpo2Interval().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            SettingsRepository.DEFAULT_SPO2_INTERVAL_MIN,
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun setAutoHrInterval(minutes: Int) {
        viewModelScope.launch {
            settings.setAutoHrInterval(minutes)
            pushMonitoringIntervals(hr = minutes, bp = autoBpInterval.value, spo2 = autoSpo2Interval.value)
        }
    }

    fun setAutoBpInterval(minutes: Int) {
        viewModelScope.launch {
            settings.setAutoBpInterval(minutes)
            pushMonitoringIntervals(hr = autoHrInterval.value, bp = minutes, spo2 = autoSpo2Interval.value)
        }
    }

    fun setAutoSpo2Interval(minutes: Int) {
        viewModelScope.launch {
            settings.setAutoSpo2Interval(minutes)
            pushMonitoringIntervals(hr = autoHrInterval.value, bp = autoBpInterval.value, spo2 = minutes)
        }
    }

    private suspend fun pushMonitoringIntervals(hr: Int, bp: Int, spo2: Int) {
        if (transport.isConnected) {
            syncEngine.applyMonitoringIntervals(hr, bp, spo2)
            if (bp > 0) {
                val pid = settings.observeActiveProfileId().first()
                if (pid != SettingsRepository.NO_PROFILE) {
                    syncEngine.syncAutoBp(
                        profileId = pid,
                        time = time,
                        profile = profiles.getById(pid),
                        bpIntervalMinutes = bp,
                    )
                }
            }
            _message.value = "Updated monitoring schedule on ring"
        } else {
            _message.value = "Saved — will apply to ring on next connect"
        }
    }

    private suspend fun pushProfileSettingsToRing(profile: Profile, metric: Boolean = useMetric.value) {
        syncEngine.syncProfileSettings(
            profile = profile,
            time = time,
            useMetric = metric,
            hrIntervalMinutes = autoHrInterval.value,
            bpIntervalMinutes = autoBpInterval.value,
            spo2IntervalMinutes = autoSpo2Interval.value,
        )
    }

    fun addProfile(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = profiles.add(name, time.nowMillis())
            profiles.switchTo(id)
            profiles.getById(id)?.let {
                pushProfileSettingsToRing(it)
            }
            _message.value = "Profile \"$name\" created"
        }
    }

    fun saveProfile(profile: Profile) {
        if (profile.name.isBlank()) return
        viewModelScope.launch {
            val isNew = profile.id == 0L
            val savedId = profiles.save(
                if (isNew) profile.copy(createdAtEpochMs = time.nowMillis()) else profile,
            )
            if (isNew || activeProfileId.value == SettingsRepository.NO_PROFILE) {
                profiles.switchTo(savedId)
            }
            if (activeProfileId.value == savedId || isNew) {
                profiles.getById(savedId)?.let {
                    pushProfileSettingsToRing(it)
                }
            }
            _message.value = if (isNew) {
                "Profile \"${profile.name}\" created"
            } else {
                "Profile \"${profile.name}\" updated"
            }
        }
    }

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            profiles.switchTo(id)
            profiles.getById(id)?.let {
                pushProfileSettingsToRing(it)
            }
            _message.value = null
        }
    }

    fun renameProfile(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            profiles.rename(id, name)
            _message.value = null
        }
    }

    fun deleteProfile(id: Long) {
        viewModelScope.launch {
            profiles.delete(id)
            _message.value = "Profile deleted"
        }
    }

    fun setHealthConnect(enabled: Boolean, onRequestPermissions: () -> Unit = {}) {
        viewModelScope.launch {
            if (!enabled) {
                settings.setHealthConnectEnabled(false)
                _message.value = "Google Health Connect disabled"
                return@launch
            }
            if (!healthConnect.isAvailable()) {
                settings.setHealthConnectEnabled(false)
                _message.value = "Google Health Connect is not available on this device"
                return@launch
            }
            if (healthConnect.hasAllPermissions()) {
                settings.setHealthConnectEnabled(true)
                syncHealthConnectNow()
            } else {
                onRequestPermissions()
            }
        }
    }

    fun onHealthConnectPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            if (granted.isEmpty()) {
                settings.setHealthConnectEnabled(false)
                _message.value = "Health Connect permissions were not granted"
            } else {
                settings.setHealthConnectEnabled(true)
                syncHealthConnectNow()
            }
        }
    }

    fun syncHealthConnectNow() {
        viewModelScope.launch {
            val pid = settings.observeActiveProfileId().first()
            if (pid == SettingsRepository.NO_PROFILE) {
                _message.value = "No active profile to export"
                return@launch
            }
            val written = healthConnect.write(pid, days = 7)
            _message.value = if (written > 0) {
                "Exported $written record(s) to Google Health Connect"
            } else {
                "Google Health Connect is up to date"
            }
        }
    }

    fun setUseMetric(metric: Boolean) {
        viewModelScope.launch {
            settings.setUseMetric(metric)
            profiles.getById(activeProfileId.value)?.let {
                pushProfileSettingsToRing(it, metric)
            }
        }
    }

    fun reconnect(mac: String) {
        viewModelScope.launch {
            _message.value = "Reconnecting..."
            if (!transport.connect(mac)) {
                _message.value = "Bluetooth unavailable"
                return@launch
            }
            val result = withTimeoutOrNull(10_000L) {
                transport.connectionState.first { it != ConnectionState.CONNECTING }
            }
            _message.value = if (result == ConnectionState.CONNECTED) {
                "Connected"
            } else {
                transport.disconnect()
                "Could not connect. Is the ring nearby?"
            }
        }
    }

    fun switchRing(mac: String) {
        viewModelScope.launch {
            val target = devices.getByMac(mac) ?: return@launch
            _message.value = "Switching to ${target.displayName}..."
            // Enforce single active ring at a time by disconnecting any previous ring first.
            transport.disconnect()
            devices.setActive(mac)
            if (!transport.connect(mac)) {
                _message.value = "${target.displayName} set active (Bluetooth unavailable)"
                return@launch
            }
            val result = withTimeoutOrNull(10_000L) {
                transport.connectionState.first { it != ConnectionState.CONNECTING }
            }
            _message.value = if (result == ConnectionState.CONNECTED) {
                "Switched to ${target.displayName}"
            } else {
                transport.disconnect()
                "${target.displayName} is now active (not nearby)"
            }
        }
    }

    fun unbind(mac: String) {
        viewModelScope.launch {
            val wasConnectedMac = transport.activeMac.value == mac
            if (wasConnectedMac) {
                // Tear down active BLE GATT session first so the ring resumes advertising
                // and any in-flight sync commands abort immediately.
                transport.disconnect()
            }
            devices.unbind(mac)
            val nextActive = devices.observeActive().first()
            if (wasConnectedMac && nextActive != null) {
                transport.connect(nextActive.macAddress)
                _message.value = "Device unbound. Switched to ${nextActive.displayName}."
            } else {
                _message.value = "Device unbound"
            }
        }
    }
}

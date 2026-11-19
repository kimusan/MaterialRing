package com.kschulz.aro.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
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

/** Profiles (add / switch / rename / delete, instant), the opt-in Health Connect toggle,
 *  and bound-device info. No cloud: everything here is local. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val devices: DeviceRepository,
    private val transport: RingBleTransport,
    private val time: TimeSource,
) : ViewModel() {

    val allProfiles: StateFlow<List<Profile>> =
        profiles.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> =
        settings.observeActiveProfileId().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.NO_PROFILE)

    val boundDevices: StateFlow<List<Ring>> =
        devices.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    val healthConnectEnabled: StateFlow<Boolean> =
        settings.observeHealthConnectEnabled().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addProfile(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = profiles.add(name, time.nowMillis())
            profiles.switchTo(id)
            _message.value = "Profile \"$name\" created"
        }
    }

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            profiles.switchTo(id)
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

    fun setHealthConnect(enabled: Boolean) {
        viewModelScope.launch {
            settings.setHealthConnectEnabled(enabled)
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

    fun unbind(mac: String) {
        viewModelScope.launch {
            // Tear down active BLE GATT session first so the ring resumes advertising
            // and any in-flight sync commands abort immediately.
            transport.disconnect()
            devices.unbind(mac)
            _message.value = "Device unbound"
        }
    }
}

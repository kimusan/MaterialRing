package com.kschulz.qring.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.qring.data.model.Profile
import com.kschulz.qring.data.model.Ring
import com.kschulz.qring.data.repository.DeviceRepository
import com.kschulz.qring.data.repository.ProfileRepository
import com.kschulz.qring.data.repository.SettingsRepository
import com.kschulz.qring.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Profiles (add / switch / rename / delete, instant), the opt-in Health Connect toggle,
 *  and bound-device info. No cloud: everything here is local. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val devices: DeviceRepository,
    private val time: TimeSource,
) : ViewModel() {

    val allProfiles: StateFlow<List<Profile>> =
        profiles.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfileId: StateFlow<Long> =
        settings.observeActiveProfileId().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsRepository.NO_PROFILE)

    val boundDevices: StateFlow<List<Ring>> =
        devices.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun unbind(mac: String) {
        viewModelScope.launch {
            devices.unbind(mac)
            _message.value = "Device unbound"
        }
    }
}

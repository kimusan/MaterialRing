package com.kschulz.aro.ui.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.ble.RingCommandClient
import com.kschulz.aro.data.ble.RingPayloads
import com.kschulz.aro.data.ble.RingScanner
import com.kschulz.aro.data.ble.ScannedRing
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Scans for rings, connects, runs the first-time baseline (7-byte BCD device time),
 *  reads revisions, and binds. Also exposes the bound-device list the app root uses to
 *  decide between the scan screen and the main tabs. */
@HiltViewModel
class DeviceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: RingScanner,
    private val transport: RingBleTransport,
    private val command: RingCommandClient,
    private val devices: DeviceRepository,
    private val time: TimeSource,
) : ViewModel() {

    val bluetoothAvailable: Boolean
        get() = scanner.isAvailable

    val isScanning: StateFlow<Boolean> = scanner.isScanning

    private val _discovered = MutableStateFlow<List<ScannedRing>>(emptyList())
    val discovered: StateFlow<List<ScannedRing>> = _discovered.asStateFlow()

    val boundDevices: StateFlow<List<Ring>> = devices.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var connectJob: Job? = null

    init {
        viewModelScope.launch {
            scanner.discoveries.collect { ring ->
                val updated = (_discovered.value.filterNot { it.macAddress == ring.macAddress } + ring)
                    .sortedByDescending { it.rssi }
                    .take(50)
                _discovered.value = updated
            }
        }
        // When all bound devices are removed (unbound), ensure any active connection or
        // stale status is cleared so ScanScreen starts fresh and can rebind immediately.
        viewModelScope.launch {
            devices.observeAll().collect { list ->
                if (list.isEmpty() && connectJob?.isActive != true) {
                    transport.disconnect()
                    _status.value = null
                }
            }
        }
    }

    fun onPermissionsGranted() {
        viewModelScope.launch {
            val active = devices.observeActive().first()
            if (active == null) {
                startScanning()
            } else if (!transport.isConnected && transport.connectionState.value == ConnectionState.DISCONNECTED) {
                transport.connect(active.macAddress)
            }
        }
    }

    fun startScanning() {
        if (!BlePermissions.hasAllPermissions(context)) {
            _status.value = "Bluetooth permission required to scan."
            return
        }
        _discovered.value = emptyList()
        _status.value = "Scanning..."
        scanner.start()
    }

    fun stopScanning() {
        scanner.stop()
        if (_status.value == "Scanning...") {
            _status.value = null
        }
    }

    fun connect(mac: String, name: String, onBound: () -> Unit = {}) {
        connectJob?.cancel()
        _status.value = "Connecting to $name..."
        connectJob = viewModelScope.launch {
            // Stop scanning before opening GATT and close any previously active ring link.
            scanner.stop()
            if (!transport.connect(mac)) {
                _status.value = "Bluetooth unavailable"
                return@launch
            }
            val final = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (final == ConnectionState.CONNECTED) {
                baseline(mac, name)
                onBound()
            } else {
                transport.disconnect()
                _status.value = "Connection failed. Is the ring on and nearby?"
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        transport.disconnect()
        _status.value = null
    }

    private suspend fun baseline(mac: String, name: String) {
        _status.value = "Setting up device..."
        // Bind first so the row exists before updating HW/FW revisions.
        devices.bind(mac, name, time.nowMillis())

        // Send 0x01 SET_DEVICE_TIME with 7-byte packed-BCD local calendar payload.
        val clock = time.localClock()
        val timePayload = RingPayloads.buildSetTimePayload(
            clock.year,
            clock.month,
            clock.day,
            clock.hour,
            clock.minute,
            clock.second,
        )
        command.request(Opcodes.SET_DEVICE_TIME, timePayload)

        // Query initial battery level (0x03) and DEVICE_INFO revisions.
        command.request(Opcodes.GET_DEVICE_ELECTRICITY_VALUE)
        transport.readDeviceInfo()?.let { (hw, fw) ->
            devices.updateRevisions(mac, hw, fw)
        }
        _status.value = "Bound to $name"
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 12_000L
    }
}

package com.kschulz.aro.ui.device

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ByteOrder
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.ble.RingCommandClient
import com.kschulz.aro.data.ble.RingScanner
import com.kschulz.aro.data.ble.ScannedRing
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.sync.TimeSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Scans for rings, connects, runs the first-time baseline (device time + phone OS),
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

    val bluetoothAvailable: Boolean = scanner.isAvailable

    val discovered: StateFlow<List<ScannedRing>> = scanner.discoveries
        .scan(emptyList<ScannedRing>()) { list, ring ->
            (list + ring).distinctBy { it.macAddress }.takeLast(50)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val boundDevices: StateFlow<List<Ring>> = devices.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val connectionState: StateFlow<ConnectionState> = transport.connectionState

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun startScanning() {
        _status.value = "Scanning..."
        scanner.start()
    }

    fun stopScanning() {
        scanner.stop()
    }

    fun connect(mac: String, name: String) {
        _status.value = "Connecting to $name..."
        viewModelScope.launch {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device = manager?.adapter?.getRemoteDevice(mac)
            if (device == null) {
                _status.value = "Bluetooth unavailable"
                return@launch
            }
            transport.connect(device)
            val final = connectionState.first { it != ConnectionState.CONNECTING }
            if (final == ConnectionState.CONNECTED) {
                baseline(mac, name)
            } else {
                _status.value = "Connection failed. Is the ring on and nearby?"
            }
        }
    }

    fun disconnect() {
        transport.disconnect()
        _status.value = null
    }

    private suspend fun baseline(mac: String, name: String) {
        _status.value = "Setting up device..."
        // First-time baseline (PROTOCOL.md section 5): set device time, then phone OS.
        val nowUtc = (System.currentTimeMillis() / 1000L).toInt()
        command.request(Opcodes.SET_DEVICE_TIME, ByteOrder.toLeBytes(nowUtc, 4))
        // 0x04 SET_PHONE_OS: 0x01 = Android.
        command.request(Opcodes.SET_PHONE_OS, byteArrayOf(0x01))

        // Fill revisions from the DEVICE_INFO service, then bind.
        transport.readDeviceInfo()?.let { (hw, fw) ->
            devices.updateRevisions(mac, hw, fw)
        }
        devices.bind(mac, name, time.nowMillis())
        _status.value = "Bound to $name"
    }
}

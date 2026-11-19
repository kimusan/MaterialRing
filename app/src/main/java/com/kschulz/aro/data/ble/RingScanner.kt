package com.kschulz.aro.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Discovers the ring by advertising-name model prefix. The ring advertises as
 * `<model>_<suffix>` (e.g. `R03_0202`); the model list is the set the original app
 * filters on (its DeviceBindActivity). Standard Android bonding handles pairing;
 * there is no custom handshake.
 */
@SuppressLint("MissingPermission")
@Singleton
class RingScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _discoveries = MutableSharedFlow<ScannedRing>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val discoveries: SharedFlow<ScannedRing> = _discoveries.asSharedFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var scanner: BluetoothLeScanner? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device?.name ?: return
            if (MODEL_PREFIXES.any { name.startsWith(it, ignoreCase = true) }) {
                val device = result.device
                _discoveries.tryEmit(
                    ScannedRing(
                        name = name,
                        macAddress = device.address,
                        rssi = result.rssi,
                    ),
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            AroDbg.d("onScanFailed errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    val isAvailable: Boolean
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.isEnabled == true
        }

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val leScanner = manager.adapter?.bluetoothLeScanner ?: return
        scanner = leScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scope.launch {
            if (_isScanning.value) {
                runCatching { leScanner.stopScan(callback) }
            }
            _isScanning.value = true
            runCatching { leScanner.startScan(null, settings, callback) }
                .onFailure { _isScanning.value = false }
        }
    }

    fun stop() {
        scope.launch {
            val activeScanner = scanner
            _isScanning.value = false
            if (activeScanner != null) {
                runCatching { activeScanner.stopScan(callback) }
            }
        }
    }

    companion object {
        // Model prefixes the ring advertises with (the original app's scan list).
        // The advertising name is "<model>_<suffix>", e.g. R03_0202, so the user's
        // ring matches "R03".
        val MODEL_PREFIXES = listOf(
            "R01", "R02", "R03", "R04", "R05", "R06", "R11",
            "VK-5098", "MERLIN", "Hello Ring", "RING1", "boAtring",
            "Y25", "H59", "DS1314", "DS520", "DS12",
        )
    }
}

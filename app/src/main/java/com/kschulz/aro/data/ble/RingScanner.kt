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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Discovers rings by advertising-name prefix (re/PROTOCOL.md sections 1 and 5: `QRing` /
 * `QRingBand`). Standard Android bonding handles pairing; there is no custom handshake.
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

    private var scanner: BluetoothLeScanner? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: return
            if (name.startsWith(NAME_PREFIX_1) || name.startsWith(NAME_PREFIX_2)) {
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
    }

    val isAvailable: Boolean
        get() {
            val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            return manager?.adapter?.isEnabled == true
        }

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        scanner = manager.adapter?.let { it.bluetoothLeScanner } ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scope.launch {
            runCatching { scanner?.startScan(null, settings, callback) }
        }
    }

    fun stop() {
        scope.launch {
            runCatching { scanner?.stopScan(callback) }
        }
    }

    companion object {
        const val NAME_PREFIX_1 = "QRing"
        const val NAME_PREFIX_2 = "QRingBand"
    }
}

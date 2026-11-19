package com.kschulz.aro.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android GATT wrapper for the ring's serial-port service. Owns connect, serial writes,
 * CCCD notification enable, and bounded auto-reconnect. Deliberately has no knowledge
 * of opcodes or payloads: [write] takes an already-framed [Frame] and [notifies] emits
 * raw device bytes. A different ring brand means a new transport, not a new app.
 */
@Singleton
class RingBleTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gattLock = Any()

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var secondaryWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var secondaryNotifyCharacteristic: BluetoothGattCharacteristic? = null
    private var secondaryNotifyEnabled = false
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private var pendingWrite: WriteRequest? = null
    private var pendingRead: ReadRequest? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _activeMac = MutableStateFlow<String?>(null)
    val activeMac: StateFlow<String?> = _activeMac.asStateFlow()

    // Raw device notifications. DROP_OLDEST keeps the GATT callback thread from ever
    // blocking on a slow consumer.
    private val _notifies = MutableSharedFlow<ByteArray>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notifies: SharedFlow<ByteArray> = _notifies.asSharedFlow()

    private class WriteRequest(val frame: Frame, val result: CompletableDeferred<Boolean>)
    private class ReadRequest(val result: CompletableDeferred<ByteArray>)

    private val writeChannel = Channel<WriteRequest>(Channel.UNLIMITED)

    init {
        // Single writer: Android rejects concurrent GATT writes, so all writes are
        // serialized through this coroutine and awaited until completion or timeout.
        scope.launch {
            for (req in writeChannel) {
                performWrite(req)
                val completed = withTimeoutOrNull(WRITE_TIMEOUT_MS) { req.result.await() }
                if (completed == null) {
                    synchronized(gattLock) {
                        if (pendingWrite === req) pendingWrite = null
                    }
                    req.result.complete(false)
                }
            }
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    /** Resolve a [BluetoothDevice] by MAC address and start a connection session. */
    fun connect(macAddress: String): Boolean {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val remote = runCatching { manager?.adapter?.getRemoteDevice(macAddress) }.getOrNull()
        if (remote == null) {
            AroDbg.d("connect($macAddress) failed: adapter or remote device unavailable")
            return false
        }
        connect(remote)
        return true
    }

    /** Connect and start a session. Closes any existing GATT handle first. */
    fun connect(device: BluetoothDevice) {
        synchronized(gattLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempts = 0
            openGattLocked(device)
        }
    }

    private fun openGattLocked(device: BluetoothDevice) {
        closeGattLocked()
        this.device = device
        _activeMac.value = device.address
        _connectionState.value = ConnectionState.CONNECTING
        AroDbg.d("connectGatt ${device.address} (attempt ${reconnectAttempts + 1})")
        val g = try {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            AroDbg.d("connectGatt SecurityException: ${e.message}")
            null
        }
        gatt = g
        if (g == null) {
            AroDbg.d("connectGatt returned null")
            _activeMac.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /** Close the session and disable auto-reconnect. */
    fun disconnect() {
        synchronized(gattLock) {
            reconnectJob?.cancel()
            reconnectJob = null
            device = null
            _activeMac.value = null
            reconnectAttempts = 0
            closeGattLocked()
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /** Write a framed payload. Returns false when not connected or the write failed. */
    suspend fun write(frame: Frame): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        val result = CompletableDeferred<Boolean>()
        writeChannel.send(WriteRequest(frame, result))
        return result.await()
    }

    /**
     * Read HW and FW revision strings from the DEVICE_INFO service.
     * @return (hw, fw), or null when not connected or either read fails.
     */
    suspend fun readDeviceInfo(): Pair<String, String>? {
        val g = synchronized(gattLock) { gatt } ?: return null
        val service = synchronized(gattLock) { g.getService(GattUuids.DEVICE_INFO_SERVICE) } ?: return null
        val fwChar = service.getCharacteristic(GattUuids.FIRMWARE_REVISION) ?: return null
        val hwChar = service.getCharacteristic(GattUuids.HARDWARE_REVISION) ?: return null
        val fw = readCharacteristic(fwChar) ?: return null
        val hw = readCharacteristic(hwChar) ?: return null
        return hw to fw
    }

    private suspend fun readCharacteristic(char: BluetoothGattCharacteristic): String? {
        val result = CompletableDeferred<ByteArray>()
        val started = synchronized(gattLock) {
            if (pendingRead != null) return null
            pendingRead = ReadRequest(result)
            try {
                gatt?.readCharacteristic(char) == true
            } catch (_: SecurityException) {
                false
            }
        }
        if (!started) {
            synchronized(gattLock) { pendingRead = null }
            return null
        }
        val value = try {
            withTimeoutOrNull(3000) { result.await() }
        } catch (_: Exception) {
            null
        }
        synchronized(gattLock) {
            if (pendingRead?.result === result) pendingRead = null
        }
        return value?.let { String(it, Charsets.ISO_8859_1).trim().ifEmpty { null } }
    }

    private fun performWrite(req: WriteRequest) {
        synchronized(gattLock) {
            val g = gatt
            val isLargeData = req.frame.bytes.isNotEmpty() &&
                req.frame.bytes[0] == RingProtocolCodec.LARGE_DATA_SYNC_BYTE
            val char = if (isLargeData) {
                secondaryWriteCharacteristic ?: writeCharacteristic
            } else {
                writeCharacteristic
            }
            if (g == null || char == null || _connectionState.value != ConnectionState.CONNECTED) {
                AroDbg.d("performWrite rejected: gatt=${g != null} char=${char != null} state=${_connectionState.value}")
                req.result.complete(false)
                return
            }
            val hasWriteWithRsp =
                char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val noResponse = !hasWriteWithRsp &&
                (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            val type = if (noResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }
            AroDbg.d("write ${req.frame.bytes.size}B type=$type ${AroDbg.hex(req.frame.bytes)}")
            if (!noResponse) {
                pendingWrite = req
            }
            val status = try {
                g.writeCharacteristic(char, req.frame.bytes, type)
            } catch (e: SecurityException) {
                AroDbg.d("writeCharacteristic SecurityException: ${e.message}")
                -1
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AroDbg.d("writeCharacteristic queued with status=$status")
                if (!noResponse) pendingWrite = null
                req.result.complete(false)
                return
            }
            if (noResponse) {
                req.result.complete(true)
            }
        }
    }

    private fun closeGattLocked() {
        gatt?.let { g ->
            notifyCharacteristic?.let {
                try { g.setCharacteristicNotification(it, false) } catch (_: SecurityException) {}
            }
            secondaryNotifyCharacteristic?.let {
                try { g.setCharacteristicNotification(it, false) } catch (_: SecurityException) {}
            }
            try { g.disconnect() } catch (_: SecurityException) {}
            try { g.close() } catch (_: SecurityException) {}
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        secondaryWriteCharacteristic = null
        secondaryNotifyCharacteristic = null
        secondaryNotifyEnabled = false
        pendingWrite?.let { it.result.complete(false) }
        pendingWrite = null
        pendingRead?.let { it.result.completeExceptionally(InterruptedException("gatt closed")) }
        pendingRead = null
    }

    private fun refreshDeviceCache(g: BluetoothGatt) {
        runCatching {
            val method = g.javaClass.getMethod("refresh")
            val ok = method.invoke(g) as? Boolean
            AroDbg.d("refreshDeviceCache -> $ok")
        }
    }

    private fun scheduleReconnect(): Boolean {
        val d = synchronized(gattLock) { device } ?: return false
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return false
        val attempt = ++reconnectAttempts
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1000L * attempt)
            synchronized(gattLock) {
                if (device === d && gatt == null) {
                    openGattLocked(d)
                }
            }
        }
        return true
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            synchronized(gattLock) {
                if (g !== gatt) return
            }
            AroDbg.d("onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTING
                    // Matches Oudmon BleBaseControl$ConnectionGattCallback.smali: wait 500ms
                    // after ACL connect before calling discoverServices() (and do not block
                    // on requestMtu()).
                    scope.launch {
                        delay(500L)
                        val started = synchronized(gattLock) {
                            if (g !== gatt) return@launch
                            AroDbg.d("discoverServices()")
                            try { g.discoverServices() } catch (_: SecurityException) { false }
                        }
                        if (!started) {
                            AroDbg.d("discoverServices() returned false, disconnecting")
                            disconnect()
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    val shouldRetry = synchronized(gattLock) {
                        if (g !== gatt) return
                        val hasTargetDevice = device != null
                        val isError = status != BluetoothGatt.GATT_SUCCESS
                        if (isError) refreshDeviceCache(g)
                        closeGattLocked()
                        hasTargetDevice && isError
                    }
                    if (shouldRetry && scheduleReconnect()) {
                        _connectionState.value = ConnectionState.CONNECTING
                    } else {
                        _connectionState.value = ConnectionState.DISCONNECTED
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            synchronized(gattLock) {
                if (g !== gatt) return
            }
            AroDbg.d("onMtuChanged mtu=$mtu status=$status")
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            synchronized(gattLock) {
                if (g !== gatt) return
            }
            AroDbg.d("onServicesDiscovered status=$status services=${g.services?.size}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                disconnect()
                return
            }
            val cmdService = g.getService(GattUuids.COMMAND_SERVICE)
            val serialService = g.getService(GattUuids.SERIAL_PORT_SERVICE)
            val wChar = cmdService?.getCharacteristic(GattUuids.COMMAND_WRITE_CHARACTERISTIC)
                ?: serialService?.getCharacteristic(GattUuids.WRITE_CHARACTERISTIC)
            val nChar = cmdService?.getCharacteristic(GattUuids.COMMAND_NOTIFY_CHARACTERISTIC)
                ?: serialService?.getCharacteristic(GattUuids.NOTIFICATION_CHARACTERISTIC)
            val secWrite = if (cmdService != null) {
                serialService?.getCharacteristic(GattUuids.WRITE_CHARACTERISTIC)
            } else {
                null
            }
            val secNotify = if (cmdService != null) {
                serialService?.getCharacteristic(GattUuids.NOTIFICATION_CHARACTERISTIC)
            } else {
                null
            }
            synchronized(gattLock) {
                writeCharacteristic = wChar
                notifyCharacteristic = nChar
                secondaryWriteCharacteristic = secWrite
                secondaryNotifyCharacteristic = secNotify
                secondaryNotifyEnabled = false
            }
            AroDbg.d(
                "cmdService=${cmdService != null} serialService=${serialService != null} " +
                    "writeChar=${wChar?.uuid} notifyChar=${nChar?.uuid} " +
                    "writeProps=${wChar?.properties}",
            )
            if (wChar == null || nChar == null) {
                disconnect()
                return
            }
            enableNotifications(g, nChar)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val req = synchronized(gattLock) {
                if (g !== gatt) return
                val current = pendingWrite
                pendingWrite = null
                current
            } ?: return
            AroDbg.d("onCharacteristicWrite uuid=${characteristic.uuid} status=$status")
            req.result.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val req = synchronized(gattLock) {
                if (g !== gatt) return
                val current = pendingRead
                pendingRead = null
                current
            } ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                req.result.complete(characteristic.value ?: ByteArray(0))
            } else {
                req.result.completeExceptionally(RuntimeException("gatt read failed: $status"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val nextNotify = synchronized(gattLock) {
                if (g !== gatt) return
                val sec = secondaryNotifyCharacteristic
                if (sec != null && !secondaryNotifyEnabled) {
                    secondaryNotifyEnabled = true
                    sec
                } else {
                    null
                }
            }
            AroDbg.d("onDescriptorWrite char=${descriptor.characteristic?.uuid} status=$status")
            if (nextNotify != null) {
                enableNotifications(g, nextNotify)
                return
            }
            // CCCD writes landed; the data channel is now ready for command traffic.
            if (_connectionState.value == ConnectionState.CONNECTING) markConnected()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            synchronized(gattLock) {
                if (g !== gatt) return
            }
            if (characteristic.uuid == GattUuids.COMMAND_NOTIFY_CHARACTERISTIC ||
                characteristic.uuid == GattUuids.NOTIFICATION_CHARACTERISTIC
            ) {
                AroDbg.d("notify ${value.size}B ${AroDbg.hex(value)}")
                _notifies.tryEmit(value)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt, notify: BluetoothGattCharacteristic) {
        try {
            g.setCharacteristicNotification(notify, true)
        } catch (_: SecurityException) {}
        val cccd = notify.getDescriptor(GattUuids.CCCD)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = synchronized(gattLock) {
                try { g.writeDescriptor(cccd) } catch (_: SecurityException) { false }
            }
            if (!started) {
                AroDbg.d("writeDescriptor(CCCD) returned false, marking connected immediately")
                markConnected()
            }
        } else {
            markConnected()
        }
    }

    private fun markConnected() {
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.CONNECTED
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
        // Matches the vendor SDK (Realsil BluetoothGattClientImpl requests 0x100).
        const val REQUESTED_MTU = 256
        const val WRITE_TIMEOUT_MS = 3_000L
    }
}

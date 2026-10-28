package com.kschulz.aro.data.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private var reconnectAttempts = 0
    private var pendingWrite: WriteRequest? = null
    private var pendingRead: ReadRequest? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

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
        // serialized through this coroutine.
        scope.launch {
            for (req in writeChannel) performWrite(req)
        }
    }

    val isConnected: Boolean
        get() = _connectionState.value == ConnectionState.CONNECTED

    /** Connect and start a session. Closes any existing GATT handle first. */
    fun connect(device: BluetoothDevice) {
        synchronized(gattLock) {
            closeGattLocked()
            this.device = device
            reconnectAttempts = 0
            _connectionState.value = ConnectionState.CONNECTING
            val g = try {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } catch (e: SecurityException) {
                null
            }
            gatt = g
            if (g == null) _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /** Close the session. Disables auto-reconnect. */
    fun disconnect() {
        synchronized(gattLock) {
            device = null
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
        val g = gatt ?: return null
        val service = synchronized(gattLock) { g.getService(GattUuids.DEVICE_INFO_SERVICE) } ?: return null
        val fwChar = service.getCharacteristic(GattUuids.FIRMWARE_REVISION) ?: return null
        val hwChar = service.getCharacteristic(GattUuids.HARDWARE_REVISION) ?: return null
        val fw = readCharacteristic(fwChar) ?: return null
        val hw = readCharacteristic(hwChar) ?: return null
        return hw to fw
    }

    private suspend fun readCharacteristic(char: BluetoothGattCharacteristic): String? {
        if (pendingRead != null) return null
        val result = CompletableDeferred<ByteArray>()
        pendingRead = ReadRequest(result)
        val started = synchronized(gattLock) { gatt?.readCharacteristic(char) == true }
        if (!started) {
            pendingRead = null
            return null
        }
        val value = try {
            withTimeoutOrNull(3000) { result.await() }
        } catch (e: Exception) {
            null
        }
        if (pendingRead != null) pendingRead = null
        return value?.let { String(it, Charsets.ISO_8859_1).trim().ifEmpty { null } }
    }

    private fun performWrite(req: WriteRequest) {
        val g = gatt
        val char = writeCharacteristic
        if (g == null || char == null || _connectionState.value != ConnectionState.CONNECTED) {
            req.result.complete(false)
            return
        }
        val noResponse =
            char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val type = if (noResponse) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        val status = synchronized(gattLock) { g.writeCharacteristic(char, req.frame.bytes, type) }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            req.result.complete(false)
            return
        }
        if (noResponse) {
            req.result.complete(true)
        } else {
            pendingWrite = req
        }
    }

    private fun closeGattLocked() {
        gatt?.let { g ->
            notifyCharacteristic?.let {
                try { g.setCharacteristicNotification(it, false) } catch (_: SecurityException) {}
            }
            g.close()
        }
        gatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        pendingWrite?.let { it.result.complete(false) }
        pendingWrite = null
        pendingRead?.let { it.result.completeExceptionally(InterruptedException("gatt closed")) }
        pendingRead = null
    }

    private fun scheduleReconnect() {
        val d = device ?: return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        val attempt = ++reconnectAttempts
        scope.launch {
            delay(1500L shl (attempt - 1))
            if (device === d && _connectionState.value == ConnectionState.DISCONNECTED) {
                connect(d)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTING
                    synchronized(gattLock) { g.discoverServices() }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    // GATT_SUCCESS means the peer dropped us cleanly or we asked; only
                    // retry on genuine failures.
                    if (status != BluetoothGatt.GATT_SUCCESS) scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = g.getService(GattUuids.SERIAL_PORT_SERVICE)
            writeCharacteristic = service?.getCharacteristic(GattUuids.WRITE_CHARACTERISTIC)
            notifyCharacteristic = service?.getCharacteristic(GattUuids.NOTIFICATION_CHARACTERISTIC)
            enableNotifications(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val req = pendingWrite ?: return
            pendingWrite = null
            req.result.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val req = pendingRead ?: return
            pendingRead = null
            if (status == BluetoothGatt.GATT_SUCCESS) {
                req.result.complete(characteristic.value ?: ByteArray(0))
            } else {
                req.result.completeExceptionally(RuntimeException("gatt read failed: $status"))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // CCCD write landed (either way); the data channel is usable now.
            if (_connectionState.value == ConnectionState.CONNECTING) markConnected()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == GattUuids.NOTIFICATION_CHARACTERISTIC) {
                _notifies.tryEmit(value)
            }
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val notify = notifyCharacteristic
        if (notify != null) {
            g.setCharacteristicNotification(notify, true)
            notify.getDescriptor(GattUuids.CCCD)?.let { cccd ->
                cccd.value = byteArrayOf(0x01, 0x00)
                synchronized(gattLock) { g.writeDescriptor(cccd) }
            }
        }
        markConnected()
    }

    private fun markConnected() {
        reconnectAttempts = 0
        _connectionState.value = ConnectionState.CONNECTED
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 3
    }
}

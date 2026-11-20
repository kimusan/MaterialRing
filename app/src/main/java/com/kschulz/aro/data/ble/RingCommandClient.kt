package com.kschulz.aro.data.ble

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Concrete [RingCommand] over [RingBleTransport]. Encodes requests with the codec, routes
 * every decoded response to all waiters registered for that cmd, and exposes the always-on
 * real-time values (0x1E heart rate, 0x03 battery, 0x69 live measurement packets) as Flows.
 */
@Singleton
class RingCommandClient @Inject constructor(
    private val transport: RingBleTransport,
    private val codec: RingProtocolCodec,
) : RingCommand {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val waiters = HashMap<Int, MutableList<Channel<ParsedFrame>>>()

    private val _realTimeHeartRate = MutableStateFlow(0)
    val realTimeHeartRate: StateFlow<Int> = _realTimeHeartRate.asStateFlow()

    private val _batteryLevel = MutableStateFlow(-1)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _liveMeasurements = MutableSharedFlow<RingPayloads.LiveMeasurementPacket>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveMeasurements: SharedFlow<RingPayloads.LiveMeasurementPacket> = _liveMeasurements.asSharedFlow()

    private val _liveTemperatures = MutableSharedFlow<JRingProtocol.TemperatureReading>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveTemperatures: SharedFlow<JRingProtocol.TemperatureReading> = _liveTemperatures.asSharedFlow()

    /**
     * Autonomous ring-to-app refresh notifications (`0x73` `CMD_DEVICE_NOTIFY`):
     * `0x01` = HR updated, `0x02` = BP updated, `0x03` = SpO2 updated, `0x04` = Steps updated.
     */
    private val _deviceNotifications = MutableSharedFlow<Int>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val deviceNotifications: SharedFlow<Int> = _deviceNotifications.asSharedFlow()

    override val connectionState: StateFlow<ConnectionState>
        get() = transport.connectionState

    override val isConnected: Boolean
        get() = transport.isConnected

    override val activeProtocol: RingProtocol
        get() = transport.activeProtocol.value

    val activeProtocolFlow: StateFlow<RingProtocol>
        get() = transport.activeProtocol

    private val _featureSupport = MutableStateFlow(RingFeatureSupport.defaultsFor(transport.activeProtocol.value))
    val featureSupport: StateFlow<RingFeatureSupport> = _featureSupport.asStateFlow()

    private var largeDataBuffer: ByteArray? = null
    private var largeDataExpectedLen: Int = 0

    init {
        scope.launch {
            transport.activeProtocol.collect { proto ->
                _featureSupport.value = RingFeatureSupport.defaultsFor(proto)
            }
        }
        scope.launch {
            transport.notifies.collect { raw ->
                if (activeProtocol == RingProtocol.JRING) {
                    if (raw.isEmpty()) return@collect
                    val parsed = ParsedFrame(
                        cmd = raw[0].toInt() and 0xFF,
                        error = false,
                        payload = if (raw.size > 1) raw.copyOfRange(1, raw.size) else ByteArray(0),
                    )
                    AroDbg.d("jring rx cmd=0x${parsed.cmd.toString(16)} ${AroDbg.hex(raw)}")
                    routeJRing(parsed)
                    return@collect
                }
                val fullFrame = reassembleIfLargeData(raw) ?: return@collect
                val parsed = codec.decode(fullFrame)
                if (parsed == null) {
                    AroDbg.d("decode FAILED ${fullFrame.size}B ${AroDbg.hex(fullFrame)}")
                    return@collect
                }
                AroDbg.d("decode ok $parsed")
                route(parsed)
            }
        }
        scope.launch {
            transport.connectionState.collect { state ->
                if (state != ConnectionState.CONNECTED) {
                    _realTimeHeartRate.value = 0
                    largeDataBuffer = null
                    largeDataExpectedLen = 0
                    if (state == ConnectionState.DISCONNECTED) {
                        _batteryLevel.value = -1
                    }
                    cancelAllWaiters()
                }
            }
        }
    }

    /**
     * Reassembles multi-chunk `0xBC` big-data packets (`LargeDataParser.smali` lines 99-196),
     * or returns 16-byte command frames directly.
     */
    private fun reassembleIfLargeData(raw: ByteArray): ByteArray? {
        if (raw.size >= RingProtocolCodec.HEADER_LEN &&
            raw[0] == RingProtocolCodec.LARGE_DATA_SYNC_BYTE &&
            largeDataBuffer == null
        ) {
            val len = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            return if (raw.size - RingProtocolCodec.HEADER_LEN >= len) {
                raw
            } else {
                largeDataBuffer = raw
                largeDataExpectedLen = len
                null
            }
        }
        val existing = largeDataBuffer
        if (existing != null && raw.size != RingProtocolCodec.CMD_FRAME_LEN) {
            val combined = existing + raw
            return if (combined.size - RingProtocolCodec.HEADER_LEN >= largeDataExpectedLen) {
                largeDataBuffer = null
                largeDataExpectedLen = 0
                combined
            } else {
                largeDataBuffer = combined
                null
            }
        }
        return raw
    }

    private fun encodeFrame(cmd: Int, payload: ByteArray?): Frame =
        if (activeProtocol == RingProtocol.JRING) {
            JRingProtocol.buildFrame(cmd, payload)
        } else {
            codec.encode(cmd, payload)
        }

    /** Refresh battery level for either protocol (0x03 on Oudmon, 0x2A19 on JRing). */
    suspend fun refreshBattery(): Int? {
        if (!transport.isConnected) return null
        return if (activeProtocol == RingProtocol.JRING) {
            val pct = transport.readStandardBatteryLevel()
            if (pct != null) _batteryLevel.value = pct
            pct
        } else {
            request(Opcodes.GET_DEVICE_ELECTRICITY_VALUE)
            _batteryLevel.value.takeIf { it in 0..100 }
        }
    }

    /** Write a command frame without waiting for a correlated response packet. */
    suspend fun send(cmd: Int, payload: ByteArray? = null, timeoutMs: Long = 3_000L): Boolean {
        if (!transport.isConnected) return false
        AroDbg.d("send 0x${cmd.toString(16)} payload=${payload?.size ?: 0}B protocol=$activeProtocol")
        return withTimeoutOrNull(timeoutMs) {
            transport.write(encodeFrame(cmd, payload))
        } == true
    }

    override suspend fun request(cmd: Int, payload: ByteArray?, timeoutMs: Long): ParsedFrame? {
        if (!transport.isConnected) {
            AroDbg.d("request 0x${cmd.toString(16)} skipped: not connected")
            return null
        }
        AroDbg.d("request 0x${cmd.toString(16)} payload=${payload?.size ?: 0}B timeout=${timeoutMs}ms")
        val channel = register(cmd)
        try {
            val written = withTimeoutOrNull(timeoutMs) { transport.write(encodeFrame(cmd, payload)) } ?: return null
            if (!written) {
                AroDbg.d("request 0x${cmd.toString(16)} write failed")
                return null
            }
            val rsp = withTimeoutOrNull(timeoutMs) {
                runCatching { channel.receive() }.getOrNull()
            }
            if (rsp == null) AroDbg.d("request 0x${cmd.toString(16)} TIMEOUT or disconnected")
            return rsp
        } finally {
            unregister(cmd, channel)
            channel.close()
        }
    }

    override suspend fun collectUntil(
        cmd: Int,
        payload: ByteArray?,
        shouldStop: (ParsedFrame) -> Boolean,
        maxPackets: Int,
        timeoutMs: Long,
    ): List<ParsedFrame> {
        if (!transport.isConnected) return emptyList()
        AroDbg.d("collectUntil 0x${cmd.toString(16)} max=$maxPackets timeout=${timeoutMs}ms")
        val channel = register(cmd)
        val out = mutableListOf<ParsedFrame>()
        try {
            val written = withTimeoutOrNull(timeoutMs) { transport.write(encodeFrame(cmd, payload)) } ?: return emptyList()
            if (!written) return emptyList()
            while (out.size < maxPackets) {
                val next = withTimeoutOrNull(timeoutMs) {
                    runCatching { channel.receive() }.getOrNull()
                }
                if (next == null) {
                    AroDbg.d("collectUntil 0x${cmd.toString(16)} TIMEOUT/closed after ${out.size} packets")
                    break
                }
                out.add(next)
                if (shouldStop(next)) break
            }
        } finally {
            unregister(cmd, channel)
            channel.close()
        }
        AroDbg.d("collectUntil 0x${cmd.toString(16)} collected ${out.size}")
        return out
    }

    private fun register(cmd: Int): Channel<ParsedFrame> {
        val channel = Channel<ParsedFrame>(Channel.UNLIMITED)
        synchronized(lock) {
            waiters.getOrPut(cmd) { mutableListOf() }.add(channel)
        }
        return channel
    }

    private fun unregister(cmd: Int, channel: Channel<ParsedFrame>) {
        synchronized(lock) { waiters[cmd]?.remove(channel) }
    }

    private fun cancelAllWaiters() {
        val toClose = synchronized(lock) {
            val all = waiters.values.flatten()
            waiters.clear()
            all
        }
        for (channel in toClose) {
            channel.close()
        }
    }

    private fun routeJRing(parsed: ParsedFrame) {
        when (parsed.cmd) {
            JRingProtocol.CMD_LIVE_PULSE -> {
                JRingProtocol.parseLivePulse(parsed.payload)?.let { bpm ->
                    _realTimeHeartRate.value = bpm
                    _liveMeasurements.tryEmit(
                        RingPayloads.LiveMeasurementPacket(
                            type = 0, // matches any active measurement collector
                            errCode = 0,
                            value = bpm,
                        ),
                    )
                }
            }

            JRingProtocol.CMD_TEMP_RESULT -> {
                JRingProtocol.parseTemperatureResult(parsed.payload)?.let { temp ->
                    _liveTemperatures.tryEmit(temp)
                }
            }
        }
        val list = synchronized(lock) { waiters[parsed.cmd]?.toList() } ?: return
        for (channel in list) channel.trySend(parsed)
    }

    private fun route(parsed: ParsedFrame) {
        when (parsed.cmd) {
            Opcodes.SET_DEVICE_TIME -> {
                if (!parsed.error && parsed.payload.size >= 4) {
                    val support = RingFeatureSupport.fromOudmonSetTimePayload(parsed.payload)
                    _featureSupport.value = support
                    AroDbg.d(
                        "oudmon capabilities: temp=${support.temperature} spo2=${support.spo2} bp=${support.bloodPressure}",
                    )
                }
            }

            Opcodes.REAL_TIME_HEART_RATE ->
                _realTimeHeartRate.value = parsed.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0

            Opcodes.GET_DEVICE_ELECTRICITY_VALUE ->
                _batteryLevel.value = parseBattery(parsed.payload)

            Opcodes.START_HEART_RATE -> {
                RingPayloads.parseLiveMeasurementPacket(parsed.payload)?.let { pkt ->
                    if (pkt.errCode == 0 && pkt.value > 0) {
                        _realTimeHeartRate.value = pkt.value
                    }
                    _liveMeasurements.tryEmit(pkt)
                }
            }

            Opcodes.DEVICE_NOTIFY -> {
                parsed.payload.getOrNull(0)?.toInt()?.and(0xFF)?.let { dataType ->
                    _deviceNotifications.tryEmit(dataType)
                }
            }
        }
        val list = synchronized(lock) { waiters[parsed.cmd]?.toList() } ?: return
        for (channel in list) channel.trySend(parsed)
    }

    /**
     * `0x03` (`BatteryRsp.smali` line 26): battery percentage is always `payload[0] & 0xFF` (`0..100`).
     */
    private fun parseBattery(payload: ByteArray): Int =
        if (payload.isNotEmpty()) (payload[0].toInt() and 0xFF).coerceIn(0, 100) else -1
}

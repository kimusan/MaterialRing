package com.kschulz.aro.data.ble

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Concrete [RingCommand] over [RingBleTransport]. Encodes requests with the codec, routes
 * every decoded response to all waiters registered for that cmd, and exposes the always-on
 * real-time values (0x1E heart rate, 0x03 battery) as StateFlows.
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

    override val connectionState: StateFlow<ConnectionState>
        get() = transport.connectionState

    override val isConnected: Boolean
        get() = transport.isConnected

    init {
        scope.launch {
            transport.notifies.collect { raw ->
                val parsed = codec.decode(raw) ?: return@collect
                route(parsed)
            }
        }
    }

    override suspend fun request(cmd: Int, payload: ByteArray?, timeoutMs: Long): ParsedFrame? {
        if (!transport.isConnected) return null
        val channel = register(cmd)
        try {
            val written = withTimeoutOrNull(timeoutMs) { transport.write(codec.encode(cmd, payload)) } ?: return null
            if (!written) return null
            return withTimeoutOrNull(timeoutMs) { channel.receive() }
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
        val channel = register(cmd)
        val out = mutableListOf<ParsedFrame>()
        try {
            val written = withTimeoutOrNull(timeoutMs) { transport.write(codec.encode(cmd, payload)) } ?: return emptyList()
            if (!written) return emptyList()
            while (out.size < maxPackets) {
                val next = withTimeoutOrNull(timeoutMs) { channel.receive() } ?: break
                out.add(next)
                if (shouldStop(next)) break
            }
        } finally {
            unregister(cmd, channel)
            channel.close()
        }
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

    private fun route(parsed: ParsedFrame) {
        when (parsed.cmd) {
            Opcodes.REAL_TIME_HEART_RATE ->
                _realTimeHeartRate.value = parsed.payload.getOrNull(0)?.toInt()?.and(0xFF) ?: 0

            Opcodes.GET_DEVICE_ELECTRICITY_VALUE ->
                _batteryLevel.value = parseBattery(parsed.payload)
        }
        val list = synchronized(lock) { waiters[parsed.cmd] } ?: return
        for (channel in list) channel.trySend(parsed)
    }

    private fun parseBattery(payload: ByteArray): Int = when {
        payload.size >= 4 -> ByteOrder.fromBeBytes(payload, 0, 4)
        payload.size == 2 -> ByteOrder.fromBeBytes(payload, 0, 2)
        payload.isNotEmpty() -> payload[0].toInt() and 0xFF
        else -> -1
    }
}

package com.kschulz.qring.data.ble

import kotlinx.coroutines.flow.StateFlow

/**
 * Opcode-aware command seam over the transport. [com.kschulz.qring.data.sync.SyncEngine]
 * and the UI depend on this interface, not the concrete client, so the sync loop is
 * testable with a fake.
 */
interface RingCommand {
    val connectionState: StateFlow<ConnectionState>
    val isConnected: Boolean

    /** Single-request round trip: write the frame, await the matching response. */
    suspend fun request(cmd: Int, payload: ByteArray? = null, timeoutMs: Long = 5000): ParsedFrame?

    /**
     * Streamed round trip: write the frame, then collect every response for [cmd] until
     * [shouldStop] returns true or [maxPackets] is reached. Used for multi-packet
     * commands (manual BP 0x14, HR history 0x15, auto BP 0x0D).
     */
    suspend fun collectUntil(
        cmd: Int,
        payload: ByteArray?,
        shouldStop: (ParsedFrame) -> Boolean,
        maxPackets: Int = 50,
        timeoutMs: Long = 15000,
    ): List<ParsedFrame>
}

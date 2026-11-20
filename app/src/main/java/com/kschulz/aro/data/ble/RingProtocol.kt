package com.kschulz.aro.data.ble

/**
 * Supported smart-ring BLE protocol families.
 * Auto-detected from discovered GATT services on connect:
 * - [OUDMON]: QRing / R03-series (`6e40fff0-...` + `de5bf728-...`, 16-byte + 0xBC big-data framing).
 * - [JRING]: Dialog/Renesas `SMART_RING` (`000056ff-...`, fixed 20-byte framing).
 */
enum class RingProtocol {
    OUDMON,
    JRING,
}

/**
 * Hardware/protocol feature availability for the currently active smart ring.
 *
 * For [RingProtocol.OUDMON], hardware capabilities are refined dynamically from the
 * `0x01` (`SetTimeRsp`) capability bytes (`payload[0] == 1` for temperature,
 * `payload[3] & 0x02` for SpO2, `payload[3] & 0x04` for BP).
 */
data class RingFeatureSupport(
    val heartRate: Boolean = true,
    val bloodPressure: Boolean = true,
    val spo2: Boolean = true,
    val steps: Boolean = true,
    val sleep: Boolean = true,
    val stress: Boolean = true,
    val temperature: Boolean = false,
) {
    companion object {
        fun defaultsFor(protocol: RingProtocol): RingFeatureSupport = when (protocol) {
            RingProtocol.OUDMON -> RingFeatureSupport(
                heartRate = true,
                bloodPressure = true,
                spo2 = true,
                steps = true,
                sleep = true,
                stress = true,
                temperature = false,
            )
            RingProtocol.JRING -> RingFeatureSupport(
                heartRate = true,
                bloodPressure = true,
                spo2 = true,
                steps = true,
                sleep = false,
                stress = true,
                temperature = true,
            )
        }

        fun fromOudmonSetTimePayload(payload: ByteArray): RingFeatureSupport {
            if (payload.size < 4) return defaultsFor(RingProtocol.OUDMON)
            val flags3 = payload[3].toInt() and 0xFF
            return RingFeatureSupport(
                heartRate = true,
                bloodPressure = (flags3 and 0x04) != 0,
                spo2 = (flags3 and 0x02) != 0,
                steps = true,
                sleep = true,
                stress = true,
                temperature = (payload[0].toInt() and 0xFF) == 1,
            )
        }
    }
}

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

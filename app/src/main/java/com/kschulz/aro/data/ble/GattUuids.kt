package com.kschulz.aro.data.ble

import java.util.UUID

/**
 * GATT UUIDs for the ring command and big-data channels.
 * Traced to `com/oudmon/ble/base/communication/Constants.smali`:
 * - Standard 16-byte commands (`CommandHandle` / `BaseReqCmd`) use [COMMAND_SERVICE] (`6e40fff0-...`),
 *   writing to [COMMAND_WRITE_CHARACTERISTIC] (`6e400002-...`) and receiving notifications on
 *   [COMMAND_NOTIFY_CHARACTERISTIC] (`6e400003-...`).
 * - Big-data transfers (`LargeDataHandler`) use [SERIAL_PORT_SERVICE] (`de5bf728-...`),
 *   writing to [WRITE_CHARACTERISTIC] (`de5bf72a-...`) and receiving notifications on
 *   [NOTIFICATION_CHARACTERISTIC] (`de5bf729-...`).
 */
object GattUuids {
    val COMMAND_SERVICE: UUID = UUID.fromString("6e40fff0-b5a3-f393-e0a9-e50e24dcca9e")
    val COMMAND_WRITE_CHARACTERISTIC: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    val COMMAND_NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val SERIAL_PORT_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
    val NOTIFICATION_CHARACTERISTIC: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
    val WRITE_CHARACTERISTIC: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER_NAME: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    // Standard BLE Battery Service (used by SMART_RING / JRing)
    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_CHARACTERISTIC: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    // Standard BLE Heart Rate Service (exposed by Samsung Galaxy Ring, RFC-SGR-001 Appendix B.3)
    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT_CHARACTERISTIC: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    // JRing / Dialog DA1458x SMART_RING 20-byte command channel
    val JRING_SERVICE: UUID = UUID.fromString("000056ff-0000-1000-8000-00805f9b34fb")
    val JRING_WRITE_CHARACTERISTIC: UUID = UUID.fromString("000033f3-0000-1000-8000-00805f9b34fb")
    val JRING_NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("000033f4-0000-1000-8000-00805f9b34fb")

    // Samsung Galaxy Ring (SM-Q500 / SM-Q508 / SM-Q509) SAP Data Service (RFC-SGR-001 §4.1–§4.2)
    val SAMSUNG_DATA_SERVICE: UUID = UUID.fromString("00001b1b-0000-1000-8000-00805f9b34fb")
    val SAMSUNG_FOTA_SERVICE: UUID = UUID.fromString("00001b1a-0000-1000-8000-00805f9b34fb")
    val SAMSUNG_WRITE_CHARACTERISTIC: UUID = UUID.fromString("63e30bad-4206-4596-839f-e47cbf7a4b5d")
    val SAMSUNG_NOTIFY_CHARACTERISTIC: UUID = UUID.fromString("797ae4e9-2e58-4fe8-b48d-b5c79599fb9b")
}

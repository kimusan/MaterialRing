package com.kschulz.qring.data.ble

import java.util.UUID

/** GATT UUIDs for the QRing serial-port (NUS-style) data channel.
 *  Source: re/PROTOCOL.md section 1 (traced to Oudmon Constants.smali). */
object GattUuids {
    val SERIAL_PORT_SERVICE: UUID = UUID.fromString("de5bf728-d711-4e47-af26-65e3012a5dc7")
    val NOTIFICATION_CHARACTERISTIC: UUID = UUID.fromString("de5bf729-d711-4e47-af26-65e3012a5dc7")
    val WRITE_CHARACTERISTIC: UUID = UUID.fromString("de5bf72a-d711-4e47-af26-65e3012a5dc7")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
}

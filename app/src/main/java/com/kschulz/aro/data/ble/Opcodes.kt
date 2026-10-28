package com.kschulz.aro.data.ble

/**
 * Command opcodes for the ring, extracted from the Oudmon SDK `Constants` class.
 * These are the byte values written as `cmd` (TX byte 1, RX byte 0 before the 0x80 mask).
 * Source: re/PROTOCOL.md section 4 (traced to smali).
 */
object Opcodes {
    // System / setup
    const val SET_DEVICE_TIME = 0x01
    const val GET_DEVICE_ELECTRICITY_VALUE = 0x03
    const val SET_PHONE_OS = 0x04
    const val GET_TIME_SETTING = 0x0A
    const val BIND_SUCCESS = 0x10
    const val REBOOT = 0x08

    // Steps / activity
    const val GET_STEP_TOTAL_SOMEDAY = 0x07
    const val GET_SPORT = 0x13
    const val GET_STEP_SOMEDAY_DETAIL = 0x43
    const val GET_SLEEP = 0x44
    const val QUERY_DATA_DISTRIBUTION = 0x46
    const val GET_STEP_TODAY = 0x48

    // Heart rate
    const val GET_HEART_RATE = 0x15
    const val HR_TIMING_MONITOR_SWITCH = 0x16
    const val REAL_TIME_HEART_RATE = 0x1E
    const val START_HEART_RATE = 0x69
    const val STOP_HEART_RATE = 0x6A

    // Blood pressure
    const val BP_TIMING_MONITOR_SWITCH = 0x0C
    const val BP_TIMING_MONITOR_DATA = 0x0D
    const val BP_TIMING_MONITOR_CONFIRM = 0x0E
    const val GET_BAND_PRESSURE = 0x14
    const val PRESSURE_SETTING = 0x36

    // Display / misc
    const val GET_PERSONALIZATION_SETTING = 0x17
    const val GET_BRIGHTNESS = 0x1B
    const val TARGET_SETTING = 0x21
    const val FIND_THE_PHONE = 0x22

    // Version
    const val MSG_GET_HW_AND_FW_VERSION = 0x93.toByte()
    const val DEVICE_REVISION = 0xA1.toByte()
}

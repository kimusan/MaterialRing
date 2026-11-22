package com.kschulz.aro.data.ble

import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.model.StressSample
import com.kschulz.aro.data.model.TemperatureSample
import java.nio.charset.StandardCharsets
import java.util.TimeZone

/**
 * Samsung Galaxy Ring (SM-Q500 / SM-Q508 / SM-Q509) BLE communication protocol
 * built on the Samsung Accessory Protocol (SAP) channel-multiplexed framing over
 * GATT Data Service `00001b1b-0000-1000-8000-00805f9b34fb` (TX `63e30bad-...`,
 * RX `797ae4e9-...`), plus standard BLE Heart Rate Service `0x180D` (`0x2A37`).
 *
 * Reference: RFC-SGR-001 ("Samsung Galaxy Ring BLE Communication Protocol Specification").
 */
object SamsungRingProtocol {

    // Channel Identifiers (RFC-SGR-001 §6.1)
    const val CHANNEL_CONTROL = 0x01
    const val CHANNEL_FIND_RING = 0x02
    const val CHANNEL_HEALTH = 0x0A
    const val CHANNEL_SETTINGS = 0x0B
    const val CHANNEL_DEBUG = 0x0C
    const val CHANNEL_LOGGING = 0x14
    const val CHANNEL_FOTA = 0x15
    const val CHANNEL_GESTURE = 0x16
    const val CHANNEL_HEARTBEAT = 0x17
    const val CHANNEL_FIND_DEVICE = 0x1F
    const val CHANNEL_TEXT = 0x20
    const val CHANNEL_SENSOR_RAW = 0x21

    // SAP Header Flags (RFC-SGR-001 §5.1)
    // Bit 7 (0x80): Format (0 = Fixed-length, 1 = Variable-length TLV)
    // Bit 6 (0x40): Type (0 = Request, 1 = Response)
    // Bits 5..0 (0x3F): MSG_ID (0..63)
    const val FLAG_VARIABLE_LEN = 0x80
    const val FLAG_RESPONSE = 0x40
    const val MSG_ID_MASK = 0x3F

    // Variable-length parameter length tags (RFC-SGR-001 §5.4)
    const val LEN_TAG_1BYTE = 10
    const val LEN_TAG_2BYTE = 11
    const val LEN_TAG_4BYTE = 12

    // Channel 10 (Health) Request/Response Headers & MSG_IDs (RFC-SGR-001 §9.2)
    const val HEALTH_MSG_CHECK_STATUS = 0x01
    const val HEALTH_MSG_SYNC_RECORDS = 0x02 // 0x82 on wire (Variable-length, Request, MSG_ID=2)
    const val HEALTH_MSG_REQUEST_DATA = 0x03
    const val HEALTH_MSG_WEARABLE_EXCHANGE = 0x04
    const val HEALTH_MSG_WORKOUT_PRIORITY = 0x05

    // Channel 10 Health Feature Types (RFC-SGR-001 §9.3)
    const val FEATURE_DEVICE_INFO = 5
    const val FEATURE_PEDO_STEP_COUNT = 7
    const val FEATURE_PEDO_EVENT = 8
    const val FEATURE_PEDO_RECOMMENDATION = 9
    const val FEATURE_HEART_RATE = 10
    const val FEATURE_USER_PROFILE = 11
    const val FEATURE_EXERCISE_AUTO = 12
    const val FEATURE_EXERCISE_PACESETTER = 13
    const val FEATURE_WATER_INTAKE = 14
    const val FEATURE_CAFFEINE_INTAKE = 15
    const val FEATURE_SLEEP = 16
    const val FEATURE_SLEEP_STAGE = 17
    const val FEATURE_SLEEP_DATA = 18
    const val FEATURE_STRESS = 19
    const val FEATURE_STRESS_HISTOGRAM = 20
    const val FEATURE_STRESS_BREATHING = 21
    const val FEATURE_ALERTED_HEART_RATE = 136
    const val FEATURE_MOVEMENT = 148
    const val FEATURE_RESPIRATION = 149
    const val FEATURE_HRV = 150
    const val FEATURE_SKIN_TEMPERATURE = 225
    const val FEATURE_OXYGEN_SATURATION = 226
    const val FEATURE_SLEEP_RAW_DATA = 227

    // Channel 10 Wearable Message Routing Receiver IDs (RFC-SGR-001 §9.4)
    const val RECEIVER_SLEEP_HOST_TO_RING = 1
    const val RECEIVER_SLEEP_RING_TO_HOST = 2
    const val RECEIVER_SPORT_EXERCISE = 3
    const val RECEIVER_INACTIVITY_ALERT = 4
    const val RECEIVER_POWER_SAVING = 5
    const val RECEIVER_ONDEMAND_HEART_RATE = 6
    const val RECEIVER_ONDEMAND_STRESS = 7
    const val RECEIVER_WORKOUT_PRIORITY = 8
    const val RECEIVER_WEARABLE_FRAMEWORK = 9
    const val RECEIVER_ONDEMAND_SPO2 = 10

    // Channel 10 Parameter IDs (RFC-SGR-001 §9.5–§9.10)
    const val PARAM_START_TIME = 1
    const val PARAM_STOP_TIME = 2
    const val PARAM_TIME_OFFSET = 3

    // Pedometer params (§9.10)
    const val PARAM_STEP_COUNT = 16
    const val PARAM_DISTANCE = 17
    const val PARAM_SPEED = 18
    const val PARAM_CALORIES = 19
    const val PARAM_RUN_STEPS = 20
    const val PARAM_WALK_STEPS = 21
    const val PARAM_DURATION = 22

    // Heart rate params (§9.5)
    const val PARAM_HR_ACTUAL = 32
    const val PARAM_HR_MIN = 33
    const val PARAM_HR_MAX = 34
    const val PARAM_HR_BEAT_COUNT = 35
    const val PARAM_HR_BINNING = 36
    const val PARAM_HR_TAG_ID = 37

    // Sleep params (§9.8)
    const val PARAM_SLEEP_EFFICIENCY = 80
    const val PARAM_HAS_SLEEP_DATA = 81
    const val PARAM_SLEEP_LATENCY = 87
    const val PARAM_BEDTIME_DELAY = 88
    const val PARAM_WAKEUP_DELAY = 89

    // Stress params (§9.9)
    const val PARAM_STRESS_SCORE = 96
    const val PARAM_STRESS_TAG_ID = 97
    const val PARAM_STRESS_BINNING = 98
    const val PARAM_STRESS_MIN = 102
    const val PARAM_STRESS_MAX = 103
    const val PARAM_STRESS_BASE_HR = 104

    // Skin temperature params (§9.7)
    const val PARAM_SKIN_TEMPERATURE = 165
    const val PARAM_HR_RRI = 166

    // SpO2 params (§9.6)
    const val PARAM_SPO2_PERCENT = 169
    const val PARAM_SPO2_LOW_DURATION = 170
    const val PARAM_SPO2_MIN = 171
    const val PARAM_SPO2_MAX = 172
    const val PARAM_SPO2_BINNING = 173
    const val PARAM_SPO2_CONCURRENT_HR = 174

    // Channel 11 (Settings) Message IDs (RFC-SGR-001 §10.2)
    const val SETTINGS_MSG_RTC_INFO_SET = 0x00
    const val SETTINGS_MSG_BATTERY_INFO = 0x02
    const val SETTINGS_MSG_SEND_BATTERY_SYNC = 0x03
    const val SETTINGS_MSG_CRADLE_INFO = 0x04
    const val SETTINGS_MSG_RING_TEMPERATURE_INFO = 0x05
    const val SETTINGS_MSG_CRADLE_HW_INFO = 0x06
    const val SETTINGS_MSG_SEM_STATE_INFO = 0x07
    const val SETTINGS_MSG_FULL_SETTING_INFO = 0x20
    const val SETTINGS_MSG_WEARING_STATE_INFO = 0x34 // 52 decimal

    // Channel 11 Battery & Wearing Parameter IDs (RFC-SGR-001 §7.5, §10.3)
    const val PARAM_BATTERY_LEVEL = 5
    const val PARAM_BATTERY_CHARGING = 6
    const val PARAM_CRADLE_BATTERY_LEVEL = 7
    const val PARAM_CRADLE_BATTERY_CHARGING = 8
    const val PARAM_WEARING_STATE = 55

    // Channel 11 Health Measurement Configuration Keys (RFC-SGR-001 §9.11)
    const val SETTING_KEY_HR_PERIOD = 1
    const val SETTING_KEY_SLEEP_BODY_DETECTION = 6
    const val SETTING_KEY_SKIN_TEMP_MONITORING = 7
    const val SETTING_KEY_STRESS_MONITORING = 8
    const val SETTING_KEY_DYNAMIC_WORKOUT = 9
    const val SETTING_KEY_WALKING_DETECTION = 10
    const val SETTING_KEY_RUNNING_DETECTION = 11
    const val SETTING_KEY_DYNAMIC_ACTIVITY = 12
    const val SETTING_KEY_SLEEP_SKIN_TEMP = 13

    // Channel 12 (Debug) On-Demand Health Info (RFC-SGR-001 §12.2)
    const val DEBUG_MSG_GET_HW_VERSION = 9
    const val DEBUG_MSG_ON_DEMAND_HEALTH_INFO = 56

    // MTU required by Samsung Galaxy Ring (RFC-SGR-001 §4.4)
    const val REQUIRED_MTU = 498
    const val LARGE_DATA_FRAGMENT_HEADER_LEN = 8
    const val LARGE_DATA_MAX_FRAGMENT_PAYLOAD = 487

    /** Synthetic command key space `(channel shl 8) or msgId` for [RingCommandClient] routing. */
    fun commandKey(channel: Int, msgId: Int): Int =
        ((channel and 0xFF) shl 8) or (msgId and MSG_ID_MASK)

    data class BatteryStatus(
        val level: Int,
        val charging: Boolean,
        val cradleLevel: Int? = null,
        val cradleCharging: Boolean? = null,
    )

    data class SapPacket(
        val channel: Int,
        val header: Int,
        val isVariableLength: Boolean,
        val isResponse: Boolean,
        val msgId: Int,
        val body: ByteArray,
        val params: Map<Int, Long>,
        val byteParams: Map<Int, ByteArray>,
    )

    data class ParsedHealthBatch(
        val steps: List<StepDay> = emptyList(),
        val heartRates: List<HeartRateSample> = emptyList(),
        val spo2Samples: List<Spo2Sample> = emptyList(),
        val temperatures: List<TemperatureSample> = emptyList(),
        val stressSamples: List<StressSample> = emptyList(),
        val sleepSessions: List<Pair<SleepSession, List<SleepSegment>>> = emptyList(),
    )

    // ---- Frame & Envelope Builders -----------------------------------------

    /**
     * Wraps a SAP message in the 2-byte doubled channel envelope `[channel, channel, header, ...]`
     * required on both TX and RX (RFC-SGR-001 §6.3).
     */
    fun buildChannelFrame(channel: Int, sapHeader: Int, body: ByteArray? = null): Frame {
        val bodyLen = body?.size ?: 0
        val out = ByteArray(2 + 1 + bodyLen)
        val ch = (channel and 0xFF).toByte()
        out[0] = ch
        out[1] = ch
        out[2] = (sapHeader and 0xFF).toByte()
        if (body != null && bodyLen > 0) {
            System.arraycopy(body, 0, out, 3, bodyLen)
        }
        return Frame(out)
    }

    /**
     * Builds a Frame from a synthetic `cmdKey = (channel shl 8) or msgId` and optional SAP body.
     * If [payload] already starts with a valid SAP header byte when `msgId` is encoded in `cmdKey`,
     * wraps it directly in the 2-byte channel envelope.
     */
    fun buildFrameFromCommandKey(cmdKey: Int, payload: ByteArray? = null): Frame {
        val channel = ((cmdKey ushr 8) and 0xFF).let { if (it == 0) CHANNEL_SETTINGS else it }
        val msgId = cmdKey and 0xFF
        return buildChannelFrame(channel, msgId, payload)
    }

    /**
     * Channel 11 `MSG_SEND_BATTERY_SYNC` (`0x0b 0x0b 0x03`, RFC-SGR-001 §10.3).
     * Validated live even on secondary GATT connections; the ring replies with
     * `0b 0b 42 06 <charging> 05 <level> 07 <cradleLvl> 08 <cradleChg>`.
     */
    fun buildBatterySyncFrame(): Frame =
        buildChannelFrame(CHANNEL_SETTINGS, SETTINGS_MSG_SEND_BATTERY_SYNC)

    /**
     * Channel 11 `MSG_RING_TEMPERATURE_INFO` (`0x0b 0x0b 0x05`, RFC-SGR-001 §10.2).
     */
    fun buildRingTemperatureQueryFrame(): Frame =
        buildChannelFrame(CHANNEL_SETTINGS, SETTINGS_MSG_RING_TEMPERATURE_INFO)

    /**
     * Channel 11 `MSG_RTC_INFO_SET` (`0x0b 0x0b 0x80 ...`, RFC-SGR-001 §10.4):
     * Variable-length SAP message setting current epoch milliseconds (param 1, LONG)
     * and timezone offset in milliseconds (param 3, INT).
     */
    fun buildRtcSyncFrame(
        nowMillis: Long,
        tzOffsetMillis: Int = TimeZone.getDefault().getOffset(nowMillis),
    ): Frame {
        val body = ByteArray(1 + (1 + 8) + (1 + 4))
        body[0] = 2 // 2 parameters
        // Param 1: epoch ms (8-byte LE LONG)
        body[1] = PARAM_START_TIME.toByte()
        writeLe64(body, 2, nowMillis)
        // Param 3: timezone offset ms (4-byte LE INT)
        body[10] = PARAM_TIME_OFFSET.toByte()
        writeLe32(body, 11, tzOffsetMillis)
        return buildChannelFrame(
            channel = CHANNEL_SETTINGS,
            sapHeader = FLAG_VARIABLE_LEN or SETTINGS_MSG_RTC_INFO_SET,
            body = body,
        )
    }

    /**
     * Channel 11 `MSG_FULL_SETTING_INFO` (`0x0b 0x0b 0x20 ...`, RFC-SGR-001 §9.11 & §10.2):
     * Pushes health monitoring configuration toggles (heart rate period, skin temperature,
     * stress monitoring, sleep body/temperature detection, walking/running detection).
     */
    fun buildHealthMonitoringSettingsFrame(
        hrIntervalMinutes: Int = 10,
        enableSkinTemperature: Boolean = true,
        enableStress: Boolean = true,
        enableSleepTracking: Boolean = true,
    ): Frame {
        val pairs = byteArrayOf(
            SETTING_KEY_HR_PERIOD.toByte(), hrIntervalMinutes.coerceIn(0, 120).toByte(),
            SETTING_KEY_SLEEP_BODY_DETECTION.toByte(), if (enableSleepTracking) 0x01 else 0x00,
            SETTING_KEY_SKIN_TEMP_MONITORING.toByte(), if (enableSkinTemperature) 0x01 else 0x00,
            SETTING_KEY_STRESS_MONITORING.toByte(), if (enableStress) 0x01 else 0x00,
            SETTING_KEY_WALKING_DETECTION.toByte(), 0x01,
            SETTING_KEY_RUNNING_DETECTION.toByte(), 0x01,
            SETTING_KEY_DYNAMIC_ACTIVITY.toByte(), 0x01,
            SETTING_KEY_SLEEP_SKIN_TEMP.toByte(), if (enableSkinTemperature) 0x01 else 0x00,
        )
        return buildChannelFrame(
            channel = CHANNEL_SETTINGS,
            sapHeader = SETTINGS_MSG_FULL_SETTING_INFO,
            body = pairs,
        )
    }

    /**
     * Channel 10 health status check (`MSG_ID = 1`, response `0x41`, RFC-SGR-001 §9.2)
     * for a specific health feature type (e.g. [FEATURE_PEDO_STEP_COUNT], [FEATURE_HEART_RATE]).
     */
    fun buildCheckHealthStatusFrame(featureType: Int): Frame =
        buildChannelFrame(
            channel = CHANNEL_HEALTH,
            sapHeader = HEALTH_MSG_CHECK_STATUS,
            body = byteArrayOf(0x01, (featureType and 0xFF).toByte()),
        )

    /**
     * Channel 10 health data request (`MSG_ID = 3`, response `0x43`, RFC-SGR-001 §9.2–§9.3)
     * for a specific health feature type.
     */
    fun buildRequestHealthDataFrame(featureType: Int): Frame =
        buildChannelFrame(
            channel = CHANNEL_HEALTH,
            sapHeader = HEALTH_MSG_REQUEST_DATA,
            body = byteArrayOf(0x01, (featureType and 0xFF).toByte()),
        )

    /**
     * Channel 10 synchronize data records (`Header = 0x82`, response `0x42`, RFC-SGR-001 §9.2–§9.3).
     */
    fun buildSyncHealthRecordsFrame(featureType: Int): Frame =
        buildChannelFrame(
            channel = CHANNEL_HEALTH,
            sapHeader = FLAG_VARIABLE_LEN or HEALTH_MSG_SYNC_RECORDS,
            body = byteArrayOf(0x01, 0x01, (featureType and 0xFF).toByte()),
        )

    /**
     * Channel 10 wearable message exchange (`MSG_ID = 4`, response `0x44`, RFC-SGR-001 §9.4)
     * for starting or stopping on-demand measurements:
     * - [RECEIVER_ONDEMAND_HEART_RATE] (6)
     * - [RECEIVER_ONDEMAND_STRESS] (7)
     * - [RECEIVER_ONDEMAND_SPO2] (10)
     */
    fun buildOnDemandMeasurementFrame(receiverId: Int, enable: Boolean): Frame =
        buildChannelFrame(
            channel = CHANNEL_HEALTH,
            sapHeader = HEALTH_MSG_WEARABLE_EXCHANGE,
            body = byteArrayOf(
                (receiverId and 0xFF).toByte(),
                if (enable) 0x01 else 0x00,
            ),
        )

    // ---- Standard BLE Heart Rate Service (0x180D / 0x2A37) -----------------

    /**
     * Parses a standard Bluetooth SIG Heart Rate Measurement notification (`0x2A37`,
     * RFC-SGR-001 Appendix B.3, e.g. `06 4a 00 00 00 00` -> 74 bpm).
     *
     * Byte 0 is Flags:
     * - Bit 0 (`0x01`): 0 = UINT8 HR at byte 1, 1 = UINT16 LE HR at bytes 1..2
     * - Bits 2..1 (`0x06`): Sensor Contact Status (`0x04` = contact supported, `0x02` = contact detected)
     *
     * Returns 0 when the ring reports skin-contact loss (`0 bpm` off-finger per Appendix B.9)
     * or null if the packet is malformed.
     */
    fun parseStandardHeartRateMeasurement(raw: ByteArray): Int? {
        if (raw.size < 2) return null
        val flags = raw[0].toInt() and 0xFF
        val isUint16 = (flags and 0x01) != 0
        val bpm = if (isUint16) {
            if (raw.size < 3) return null
            (raw[1].toInt() and 0xFF) or ((raw[2].toInt() and 0xFF) shl 8)
        } else {
            raw[1].toInt() and 0xFF
        }
        return bpm.coerceIn(0, 250)
    }

    /**
     * Checks whether the standard Heart Rate Measurement flags indicate the sensor
     * is currently off-finger (contact supported bit 2 set, contact detected bit 1 cleared,
     * or BPM == 0).
     */
    fun isStandardHrOffFinger(raw: ByteArray): Boolean {
        if (raw.size < 2) return false
        val flags = raw[0].toInt() and 0xFF
        val contactSupported = (flags and 0x04) != 0
        val contactDetected = (flags and 0x02) != 0
        val bpm = parseStandardHeartRateMeasurement(raw) ?: 0
        return (contactSupported && !contactDetected) || bpm == 0
    }

    // ---- SAP Packet & Parameter Decoding -----------------------------------

    /**
     * Decodes a channel-prefixed SAP packet `[ch, ch, header, ...]`.
     * Returns null if the packet is shorter than 3 bytes or does not have a valid doubled channel prefix.
     */
    fun decodeSapPacket(raw: ByteArray): SapPacket? {
        if (raw.size < 3) return null
        val c0 = raw[0].toInt() and 0xFF
        val c1 = raw[1].toInt() and 0xFF
        if (c0 != c1 || c0 !in 1..63) return null
        val header = raw[2].toInt() and 0xFF
        val isVar = (header and FLAG_VARIABLE_LEN) != 0
        val isResp = (header and FLAG_RESPONSE) != 0
        val msgId = header and MSG_ID_MASK
        val body = if (raw.size > 3) raw.copyOfRange(3, raw.size) else ByteArray(0)
        val (numParams, byteParams) = decodeParameters(isVar, body)
        return SapPacket(
            channel = c0,
            header = header,
            isVariableLength = isVar,
            isResponse = isResp,
            msgId = msgId,
            body = body,
            params = numParams,
            byteParams = byteParams,
        )
    }

    /**
     * Parses Channel 11 `MSG_BATTERY_INFO` (`0b 0b 02 ...` or `0b 0b 42 ...`, RFC-SGR-001 §10.3).
     * Validated wire trace: `0b 0b 42 06 00 05 3f 07 ff 08 ff` -> 63%, not charging, off-cradle.
     */
    fun parseBatteryInfo(raw: ByteArray): BatteryStatus? {
        if (raw.size == 1) {
            val pct = raw[0].toInt() and 0xFF
            return if (pct in 0..100) BatteryStatus(level = pct, charging = false) else null
        }
        if (raw.size < 5) return null
        val ch0 = raw[0].toInt() and 0xFF
        val ch1 = raw[1].toInt() and 0xFF
        val msgId = raw[2].toInt() and MSG_ID_MASK
        if (ch0 != CHANNEL_SETTINGS || ch1 != CHANNEL_SETTINGS || msgId != SETTINGS_MSG_BATTERY_INFO) {
            return null
        }
        var level = -1
        var charging = false
        var cradleLevel: Int? = null
        var cradleCharging: Boolean? = null
        var i = 3
        while (i + 1 < raw.size) {
            val paramId = raw[i].toInt() and 0xFF
            val value = raw[i + 1].toInt() and 0xFF
            when (paramId) {
                PARAM_BATTERY_LEVEL -> if (value in 0..100) level = value
                PARAM_BATTERY_CHARGING -> charging = value != 0
                PARAM_CRADLE_BATTERY_LEVEL -> if (value in 0..100) cradleLevel = value
                PARAM_CRADLE_BATTERY_CHARGING -> if (value != 0xFF) cradleCharging = value != 0
            }
            i += 2
        }
        return if (level in 0..100) {
            BatteryStatus(
                level = level,
                charging = charging,
                cradleLevel = cradleLevel,
                cradleCharging = cradleCharging,
            )
        } else {
            null
        }
    }

    /**
     * Parses Channel 11 `MSG_WEARING_STATE_INFO` (`0b 0b 34 37 00/01`, RFC-SGR-001 §7.5 & Appendix B.9).
     * Returns `true` when on-finger (`0x01`), `false` when removed (`0x00`), or `null` if not a wearing frame.
     */
    fun parseWearingState(raw: ByteArray): Boolean? {
        if (raw.size < 5) return null
        val ch0 = raw[0].toInt() and 0xFF
        val ch1 = raw[1].toInt() and 0xFF
        val msgId = raw[2].toInt() and MSG_ID_MASK
        if (ch0 != CHANNEL_SETTINGS || ch1 != CHANNEL_SETTINGS || msgId != SETTINGS_MSG_WEARING_STATE_INFO) {
            return null
        }
        var i = 3
        while (i + 1 < raw.size) {
            val paramId = raw[i].toInt() and 0xFF
            val value = raw[i + 1].toInt() and 0xFF
            if (paramId == PARAM_WEARING_STATE) {
                return value != 0
            }
            i += 2
        }
        return null
    }

    /**
     * Parses Channel 11 `MSG_RING_TEMPERATURE_INFO` (`0b 0b 05 ...` or `0b 0b 45 ...`, RFC-SGR-001 §10.2)
     * or Channel 10 `SKIN_TEMPERATURE` (Param 165).
     * Accepts raw temperature in °C, tenths of °C (e.g. 358 -> 35.8 °C), or hundredths of °C (3580 -> 35.80 °C).
     */
    fun parseTemperatureFrame(
        profileId: Long,
        defaultEpochMs: Long,
        raw: ByteArray,
    ): TemperatureSample? {
        val pkt = decodeSapPacket(raw) ?: return null
        val rawTemp: Long? = when {
            pkt.channel == CHANNEL_SETTINGS && pkt.msgId == SETTINGS_MSG_RING_TEMPERATURE_INFO ->
                pkt.params[PARAM_SKIN_TEMPERATURE] ?: pkt.params.values.firstOrNull()
            pkt.channel == CHANNEL_HEALTH ->
                pkt.params[PARAM_SKIN_TEMPERATURE]
            else -> null
        }
        val tempVal = rawTemp ?: return null
        val celsius = normalizeTemperatureCelsius(tempVal) ?: return null
        val ts = pkt.params[PARAM_START_TIME]?.takeIf { it > 1_000_000_000_000L } ?: defaultEpochMs
        return TemperatureSample(
            profileId = profileId,
            epochMs = ts,
            surfaceCelsius = celsius,
            bodyCelsius = celsius,
        )
    }

    /**
     * Normalizes a scaled integer temperature value (in °C, 0.1 °C, or 0.01 °C) to degrees Celsius
     * if it falls within a physiological human skin/body range (`20.0 .. 44.0 °C`).
     */
    fun normalizeTemperatureCelsius(scaledValue: Long): Double? {
        val candidates = listOf(
            scaledValue / 100.0,
            scaledValue / 10.0,
            scaledValue.toDouble(),
        )
        return candidates.firstOrNull { it in 20.0..44.0 }
    }

    /**
     * Parses a Channel 10 health data frame (`0x0a 0x0a ...`, RFC-SGR-001 §9.2–§9.10) into
     * Aro domain models ([StepDay], [HeartRateSample], [Spo2Sample], [TemperatureSample],
     * [StressSample], [SleepSession]).
     */
    fun parseHealthPacket(
        profileId: Long,
        todayEpochDay: Long,
        nowEpochMs: Long,
        raw: ByteArray,
    ): ParsedHealthBatch {
        val pkt = decodeSapPacket(raw) ?: return ParsedHealthBatch()
        if (pkt.channel != CHANNEL_HEALTH) return ParsedHealthBatch()
        val p = pkt.params
        val sampleEpochMs = p[PARAM_START_TIME]?.takeIf { it > 1_000_000_000_000L } ?: nowEpochMs

        val steps = mutableListOf<StepDay>()
        val heartRates = mutableListOf<HeartRateSample>()
        val spo2Samples = mutableListOf<Spo2Sample>()
        val temperatures = mutableListOf<TemperatureSample>()
        val stressSamples = mutableListOf<StressSample>()
        val sleepSessions = mutableListOf<Pair<SleepSession, List<SleepSegment>>>()

        // 1. Pedometer (§9.10: Param 16 = STEP_COUNT, 17 = DISTANCE, 19 = CALORIES, 22 = DURATION)
        val stepCount = p[PARAM_STEP_COUNT]?.toInt()
        if (stepCount != null && stepCount in 1..250_000) {
            val distance = (p[PARAM_DISTANCE]?.toInt() ?: (stepCount * 72 / 100)).coerceIn(0, 500_000)
            val calories = (p[PARAM_CALORIES]?.toInt() ?: (stepCount * 4 / 100)).coerceIn(0, 50_000)
            val durationMin = ((p[PARAM_DURATION]?.toInt() ?: 0) / 60).coerceAtLeast(0)
            steps.add(
                StepDay(
                    profileId = profileId,
                    date = todayEpochDay,
                    steps = stepCount,
                    distanceMeters = distance,
                    calories = calories,
                    durationMin = durationMin,
                ),
            )
        }

        // 2. Heart rate (§9.5: Param 32 = ACTUAL_VALUE, or §9.6 Param 174 = concurrent HR)
        val hrBpm = (p[PARAM_HR_ACTUAL] ?: p[PARAM_SPO2_CONCURRENT_HR])?.toInt()
        if (hrBpm != null && hrBpm in 30..240) {
            heartRates.add(
                HeartRateSample(
                    profileId = profileId,
                    epochMs = sampleEpochMs,
                    bpm = hrBpm,
                ),
            )
        }

        // 3. Blood oxygen SpO2 (§9.6: Param 169 = SPO2 percentage)
        val spo2Pct = p[PARAM_SPO2_PERCENT]?.toInt()
        if (spo2Pct != null && spo2Pct in 50..100) {
            spo2Samples.add(
                Spo2Sample(
                    profileId = profileId,
                    epochMs = sampleEpochMs,
                    percent = spo2Pct,
                ),
            )
        }

        // 4. Skin temperature (§9.7: Param 165 = TEMPERATURE)
        val rawTemp = p[PARAM_SKIN_TEMPERATURE]
        if (rawTemp != null) {
            normalizeTemperatureCelsius(rawTemp)?.let { celsius ->
                temperatures.add(
                    TemperatureSample(
                        profileId = profileId,
                        epochMs = sampleEpochMs,
                        surfaceCelsius = celsius,
                        bodyCelsius = celsius,
                    ),
                )
            }
        }

        // 5. Stress score (§9.9: Param 96 = SCORE 0..100)
        val stressScore = p[PARAM_STRESS_SCORE]?.toInt()
        if (stressScore != null && stressScore in 1..100) {
            stressSamples.add(
                StressSample(
                    profileId = profileId,
                    epochMs = sampleEpochMs,
                    score = stressScore,
                ),
            )
        }

        // 6. Sleep session (§9.8: Param 1 = SLEEP_START_TIME, Param 2 = SLEEP_STOP_TIME, Param 80 = EFFICIENCY)
        val sleepStart = p[PARAM_START_TIME]
        val sleepStop = p[PARAM_STOP_TIME]
        val hasSleep = (p[PARAM_HAS_SLEEP_DATA] ?: 0L) != 0L || p.containsKey(PARAM_SLEEP_EFFICIENCY)
        if (hasSleep && sleepStart != null && sleepStop != null && sleepStop > sleepStart) {
            val totalMin = ((sleepStop - sleepStart) / 60_000L).toInt().coerceIn(1, 24 * 60)
            val efficiency = (p[PARAM_SLEEP_EFFICIENCY]?.toInt() ?: 85).coerceIn(40, 100)
            val asleepMin = (totalMin * efficiency / 100).coerceAtLeast(1)
            val awakeMin = (totalMin - asleepMin).coerceAtLeast(0)
            val deepMin = (asleepMin * 22 / 100).coerceAtLeast(1)
            val remMin = (asleepMin * 23 / 100).coerceAtLeast(1)
            val lightMin = (asleepMin - deepMin - remMin).coerceAtLeast(1)
            val session = SleepSession(
                profileId = profileId,
                startEpoch = sleepStart,
                endEpoch = sleepStop,
                isNap = totalMin < 180,
            )
            val segments = buildList {
                add(SleepSegment(sessionId = 0, stage = 2, durationMin = lightMin)) // Light
                add(SleepSegment(sessionId = 0, stage = 1, durationMin = deepMin)) // Deep
                add(SleepSegment(sessionId = 0, stage = 3, durationMin = remMin)) // REM
                if (awakeMin > 0) {
                    add(SleepSegment(sessionId = 0, stage = 4, durationMin = awakeMin)) // Awake
                }
            }
            sleepSessions.add(session to segments)
        }

        return ParsedHealthBatch(
            steps = steps,
            heartRates = heartRates,
            spo2Samples = spo2Samples,
            temperatures = temperatures,
            stressSamples = stressSamples,
            sleepSessions = sleepSessions,
        )
    }

    /**
     * Parses Samsung configuration store key-value notifications (`0x14` prefix, RFC-SGR-001 Appendix A.3),
     * extracting firmware version (`RING0004`, e.g. `"Q50XWWU2AZA4"`) and ring size (`RING0002`, e.g. `"12"`).
     */
    fun parseConfigStoreFirmware(raw: ByteArray): Pair<String, String>? {
        if (raw.size < 6) return null
        val text = String(raw, StandardCharsets.UTF_8)
        val fwMatch = Regex("Q50[0-9A-Z]{6,16}").find(text)?.value ?: return null
        val sizeMatch = Regex("RING0002[^0-9]*([0-9]{1,2})").find(text)?.groupValues?.getOrNull(1)
        val hwLabel = if (sizeMatch != null) "SM-Q509 (Size $sizeMatch)" else "SM-Q509"
        return hwLabel to fwMatch
    }

    // ---- Large Data Transfer Reassembly (RFC-SGR-001 §13.2) ----------------

    data class FragmentHeader(
        val fragmentIndex: Int,
        val totalFragments: Int,
        val totalDataLength: Int,
    )

    /**
     * Parses an 8-byte little-endian Large Data Transfer fragment header:
     * `[fragmentIndex: u16 LE, totalFragments: u16 LE, totalDataLength: u32 LE]`.
     * Returns null if the header fields do not form a plausible multi-fragment envelope.
     */
    fun parseFragmentHeader(raw: ByteArray): FragmentHeader? {
        if (raw.size <= LARGE_DATA_FRAGMENT_HEADER_LEN) return null
        val idx = readU16Le(raw, 0)
        val total = readU16Le(raw, 2)
        val totalLen = readI32Le(raw, 4)
        if (total !in 2..1024 || idx >= total || totalLen !in (raw.size - 8)..(1024 * 512)) {
            return null
        }
        return FragmentHeader(
            fragmentIndex = idx,
            totalFragments = total,
            totalDataLength = totalLen,
        )
    }

    // ---- Internal SAP Parameter Decoder ------------------------------------

    private fun decodeParameters(
        isVariableLength: Boolean,
        body: ByteArray,
    ): Pair<Map<Int, Long>, Map<Int, ByteArray>> {
        val numeric = LinkedHashMap<Int, Long>()
        val bytesMap = LinkedHashMap<Int, ByteArray>()
        if (body.isEmpty()) return numeric to bytesMap

        if (!isVariableLength) {
            // Fixed-length SAP messages carry (ParamID, 1-byte ParamValue) pairs (RFC-SGR-001 §5.2 & §10.3).
            var i = 0
            while (i + 1 < body.size) {
                val id = body[i].toInt() and 0xFF
                val v = (body[i + 1].toInt() and 0xFF).toLong()
                numeric[id] = v
                i += 2
            }
            return numeric to bytesMap
        }

        // Variable-length SAP messages: byte 0 is Param Count, followed by entries (RFC-SGR-001 §5.3–§5.4).
        val paramCount = body[0].toInt() and 0xFF
        var cursor = 1
        var parsed = 0
        while (cursor < body.size && parsed < paramCount) {
            val paramId = body[cursor].toInt() and 0xFF
            cursor++
            if (cursor >= body.size) break

            val expectedWidth = knownParameterWidth(paramId)
            if (expectedWidth > 0 && cursor + expectedWidth <= body.size) {
                numeric[paramId] = readLeUnsigned(body, cursor, expectedWidth)
                cursor += expectedWidth
                parsed++
                continue
            }

            // Variable-width STRING (5) / CUSTOM (6) with Length Tag 10 (1B), 11 (2B LE), or 12 (4B LE).
            val tag = body[cursor].toInt() and 0xFF
            when (tag) {
                LEN_TAG_1BYTE -> {
                    if (cursor + 2 > body.size) break
                    val len = body[cursor + 1].toInt() and 0xFF
                    cursor += 2
                    if (cursor + len > body.size) break
                    val slice = body.copyOfRange(cursor, cursor + len)
                    bytesMap[paramId] = slice
                    if (len in listOf(1, 2, 4, 8)) {
                        numeric[paramId] = readLeUnsigned(slice, 0, len)
                    }
                    cursor += len
                }
                LEN_TAG_2BYTE -> {
                    if (cursor + 3 > body.size) break
                    val len = readU16Le(body, cursor + 1)
                    cursor += 3
                    if (len < 0 || cursor + len > body.size) break
                    val slice = body.copyOfRange(cursor, cursor + len)
                    bytesMap[paramId] = slice
                    cursor += len
                }
                LEN_TAG_4BYTE -> {
                    if (cursor + 5 > body.size) break
                    val len = readI32Le(body, cursor + 1)
                    cursor += 5
                    if (len < 0 || cursor + len > body.size) break
                    val slice = body.copyOfRange(cursor, cursor + len)
                    bytesMap[paramId] = slice
                    cursor += len
                }
                else -> {
                    // Fallback: remaining bytes if single param, or 4-byte INT / 1-byte BYTE
                    val remaining = body.size - cursor
                    val width = when {
                        paramCount == 1 && remaining in listOf(1, 2, 4, 8) -> remaining
                        remaining >= 4 -> 4
                        else -> 1
                    }
                    numeric[paramId] = readLeUnsigned(body, cursor, width)
                    cursor += width
                }
            }
            parsed++
        }
        return numeric to bytesMap
    }

    private fun knownParameterWidth(paramId: Int): Int = when (paramId) {
        PARAM_START_TIME, PARAM_STOP_TIME -> 8 // LONG (epoch ms)
        PARAM_TIME_OFFSET,
        PARAM_STEP_COUNT, PARAM_DISTANCE, PARAM_SPEED, PARAM_CALORIES,
        PARAM_RUN_STEPS, PARAM_WALK_STEPS, PARAM_DURATION,
        PARAM_HR_ACTUAL, PARAM_HR_MIN, PARAM_HR_MAX, PARAM_HR_BEAT_COUNT, PARAM_HR_TAG_ID,
        PARAM_SLEEP_EFFICIENCY, PARAM_SLEEP_LATENCY, PARAM_BEDTIME_DELAY, PARAM_WAKEUP_DELAY,
        PARAM_STRESS_SCORE, PARAM_STRESS_TAG_ID, PARAM_STRESS_MIN, PARAM_STRESS_MAX, PARAM_STRESS_BASE_HR,
        PARAM_SKIN_TEMPERATURE, PARAM_HR_RRI,
        PARAM_SPO2_PERCENT, PARAM_SPO2_LOW_DURATION, PARAM_SPO2_MIN, PARAM_SPO2_MAX, PARAM_SPO2_CONCURRENT_HR -> 4
        PARAM_HAS_SLEEP_DATA,
        PARAM_BATTERY_LEVEL, PARAM_BATTERY_CHARGING,
        PARAM_CRADLE_BATTERY_LEVEL, PARAM_CRADLE_BATTERY_CHARGING,
        PARAM_WEARING_STATE -> 1
        else -> -1
    }

    private fun readU16Le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readI32Le(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    private fun readLeUnsigned(buf: ByteArray, offset: Int, width: Int): Long {
        var acc = 0L
        for (i in 0 until width) {
            acc = acc or ((buf[offset + i].toLong() and 0xFFL) shl (i * 8))
        }
        return acc
    }

    private fun writeLe32(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeLe64(buf: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            buf[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
        }
    }
}

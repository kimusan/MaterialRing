package com.kschulz.aro.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.Opcodes
import com.kschulz.aro.data.ble.RingBleTransport
import com.kschulz.aro.data.ble.RingCommandClient
import com.kschulz.aro.data.ble.RingPayloads
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.Spo2Sample
import com.kschulz.aro.data.model.StepDay
import com.kschulz.aro.data.repository.DeviceRepository
import com.kschulz.aro.data.repository.HealthRepository
import com.kschulz.aro.data.repository.ProfileRepository
import com.kschulz.aro.data.repository.SettingsRepository
import com.kschulz.aro.data.sync.SyncEngine
import com.kschulz.aro.data.sync.TimeSource
import com.kschulz.aro.ui.device.BlePermissions
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class LiveMeasurementState(
    val active: Boolean = false,
    val type: Int = RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE,
    val secondsRemaining: Int = 0,
    val livePulse: Int = 0,
    val liveSbp: Int = 0,
    val liveDbp: Int = 0,
    val liveSpo2: Int = 0,
    val liveSamples: List<Int> = emptyList(),
    val statusMessage: String? = null,
    val wearingError: Boolean = false,
)

/** The home dashboard: live vitals plus the latest stored readings for the active profile.
 *  Real-time values come straight from the command client; stored values are reactive Room
 *  flows scoped to the active profile (so switching profiles updates this screen instantly). */
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val health: HealthRepository,
    private val transport: RingBleTransport,
    private val command: RingCommandClient,
    private val devices: DeviceRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val engine: SyncEngine,
    private val healthConnect: com.kschulz.aro.data.healthconnect.HealthConnectExporter,
    private val time: TimeSource,
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = command.connectionState

    val connected: StateFlow<Boolean> = command.connectionState
        .map { it == ConnectionState.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val boundRing: StateFlow<Ring?> = devices.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Live values (no persistence).
    val liveHeartRate: StateFlow<Int> = command.realTimeHeartRate
    val battery: StateFlow<Int> = command.batteryLevel

    private val todayEpochDay get() = time.todayEpochDay()

    val today: StateFlow<StepDay?> =
        health.observeTodaySteps(todayEpochDay)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestHr: StateFlow<HeartRateSample?> =
        health.observeLatestHeartRate()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestBp: StateFlow<BloodPressure?> =
        health.observeLatestBloodPressure()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestSpo2: StateFlow<Spo2Sample?> =
        health.observeLatestSpo2()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestTemperature: StateFlow<com.kschulz.aro.data.model.TemperatureSample?> =
        health.observeLatestTemperature()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val latestStress: StateFlow<com.kschulz.aro.data.model.StressSample?> =
        health.observeLatestStress()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activeProtocol: StateFlow<com.kschulz.aro.data.ble.RingProtocol> =
        command.activeProtocolFlow

    val featureSupport: StateFlow<com.kschulz.aro.data.ble.RingFeatureSupport> =
        command.featureSupport

    val latestSleep: StateFlow<SleepSession?> =
        health.observeLatestMainSleep()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bodyBattery: StateFlow<List<BodyBatteryPoint>> =
        health.observeBodyBatteryBetween(todayEpochDay * 24, todayEpochDay * 24 + 23)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeProfile: StateFlow<Profile?> =
        profiles.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val allProfiles: StateFlow<List<Profile>> =
        profiles.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val useMetric: StateFlow<Boolean> =
        settings.observeUseMetric()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val profileName: StateFlow<String?> =
        profiles.observeActive()
            .map { it?.name }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _lastSync = MutableStateFlow<String?>(null)
    val lastSync: StateFlow<String?> = _lastSync.asStateFlow()

    private val _measurement = MutableStateFlow(LiveMeasurementState())
    val measurement: StateFlow<LiveMeasurementState> = _measurement.asStateFlow()

    private var measurementJob: Job? = null
    private var profileSeeded = false

    init {
        // Automatically connect to the single active ring on startup if permissions are granted.
        viewModelScope.launch {
            devices.observeActive().collect { ring ->
                if (ring == null) return@collect
                if (
                    transport.connectionState.value == ConnectionState.DISCONNECTED &&
                    BlePermissions.hasAllPermissions(context)
                ) {
                    transport.connect(ring.macAddress)
                }
            }
        }
        // Fetch battery level and perform an initial sync when the ring reaches CONNECTED state.
        viewModelScope.launch {
            transport.connectionState.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    if (battery.value < 0) {
                        command.refreshBattery()
                    }
                    if (_lastSync.value == null && !_syncing.value) {
                        syncNow()
                    }
                }
            }
        }
        // React to autonomous ring notifications (0x73 CMD_DEVICE_NOTIFY) for live updates
        // while connected: 0x04 = steps, 0x01 = heart rate, 0x02 = auto-BP.
        viewModelScope.launch {
            command.deviceNotifications.collect { dataType ->
                if (_syncing.value || _measurement.value.active) return@collect
                val profileId = settings.observeActiveProfileId().first()
                if (profileId == SettingsRepository.NO_PROFILE) return@collect
                when (dataType) {
                    0x04 -> engine.syncTodaySteps(profileId, time)
                    0x01 -> engine.syncTodayHeartRate(profileId, time)
                    0x02 -> engine.syncAutoBp(profileId, time, activeProfile.value)
                    0x03 -> engine.syncAutoSpo2(profileId, time)
                }
                com.kschulz.aro.widget.AroWidgets.updateAll(context)
            }
        }
    }

    /** First-run guard: create a default profile and activate it when none exist, so the
     *  profile-scoped reads on this screen have somewhere to point. */
    fun ensureDefaultProfile() {
        if (profileSeeded) return
        profileSeeded = true
        viewModelScope.launch {
            val existing = profiles.observeAll().first()
            if (existing.isEmpty()) {
                val id = profiles.add(DEFAULT_PROFILE_NAME, time.nowMillis())
                profiles.switchTo(id)
            }
        }
    }

    fun switchProfile(id: Long) {
        viewModelScope.launch {
            profiles.switchTo(id)
            profiles.getById(id)?.let {
                engine.syncProfileSettings(it, time, useMetric.value)
            }
        }
    }

    fun saveProfile(profile: Profile) {
        if (profile.name.isBlank()) return
        viewModelScope.launch {
            val isNew = profile.id == 0L
            val savedId = profiles.save(
                if (isNew) profile.copy(createdAtEpochMs = time.nowMillis()) else profile,
            )
            if (isNew || activeProfile.value == null) {
                profiles.switchTo(savedId)
            }
            profiles.getById(savedId)?.let {
                engine.syncProfileSettings(it, time, useMetric.value)
            }
            _lastSync.value = "Profile \"${profile.name}\" saved"
        }
    }

    fun startBpMeasurement() {
        startLiveMeasurement(RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE)
    }

    fun startHrMeasurement() {
        startLiveMeasurement(RingPayloads.MEASURE_TYPE_HEART_RATE)
    }

    fun startSpo2Measurement() {
        startLiveMeasurement(RingPayloads.MEASURE_TYPE_SPO2)
    }

    /** Live dual-thermistor temperature measurement for SMART_RING / JRing (0x37 start/stop, 0x38 result). */
    fun startTemperatureMeasurement() {
        measurementJob?.cancel()
        measurementJob = viewModelScope.launch {
            val profileId = settings.observeActiveProfileId().first()
            if (profileId == SettingsRepository.NO_PROFILE) return@launch
            if (!ensureConnected()) {
                _lastSync.value = "Ring not connected. Place ring nearby and try again."
                return@launch
            }
            if (!command.featureSupport.value.temperature ||
                command.activeProtocol != com.kschulz.aro.data.ble.RingProtocol.JRING
            ) {
                val ringLabel = boundRing.value?.displayName?.ifBlank { "this ring" } ?: "this ring"
                _lastSync.value = "Temperature measurement is unavailable for $ringLabel."
                return@launch
            }
            _lastSync.value = "Measuring temperature..."
            command.send(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_TEMP_MODE,
                com.kschulz.aro.data.ble.JRingProtocol.buildTemperatureModePayload(true),
            )
            val reading = withTimeoutOrNull(10_000L) {
                command.liveTemperatures.first()
            }
            command.send(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_TEMP_MODE,
                com.kschulz.aro.data.ble.JRingProtocol.buildTemperatureModePayload(false),
            )
            if (reading != null) {
                val nowMs = time.nowMillis()
                health.upsertTemperature(
                    profileId,
                    listOf(
                        com.kschulz.aro.data.model.TemperatureSample(
                            profileId = profileId,
                            epochMs = nowMs,
                            surfaceCelsius = reading.surfaceCelsius,
                            bodyCelsius = reading.bodyCelsius,
                        ),
                    ),
                )
                _lastSync.value = "Temperature: %.2f °C body (%.1f °C skin)"
                    .format(reading.bodyCelsius, reading.surfaceCelsius)
            } else {
                _lastSync.value = "Temperature measurement timed out. Ensure ring is worn snugly."
            }
        }
    }

    fun stopMeasurement() {
        val current = _measurement.value
        measurementJob?.cancel()
        measurementJob = null
        if (current.active) {
            viewModelScope.launch {
                sendStopLiveMeasurement(current.type, 0, 0)
            }
        }
        _measurement.value = LiveMeasurementState()
    }

    private suspend fun sendStopLiveMeasurement(type: Int, value1: Int, value2: Int) {
        if (command.activeProtocol == com.kschulz.aro.data.ble.RingProtocol.JRING) {
            command.send(
                com.kschulz.aro.data.ble.JRingProtocol.CMD_OPTICAL_MODE,
                com.kschulz.aro.data.ble.JRingProtocol.buildOpticalModePayload(
                    com.kschulz.aro.data.ble.JRingProtocol.OPTICAL_STOP,
                ),
            )
        } else {
            command.send(
                Opcodes.STOP_HEART_RATE,
                RingPayloads.buildStopMeasurementPayload(type, value1, value2),
            )
        }
    }

    private fun startLiveMeasurement(type: Int) {
        measurementJob?.cancel()
        measurementJob = viewModelScope.launch {
            val profileId = settings.observeActiveProfileId().first()
            if (profileId == SettingsRepository.NO_PROFILE) {
                _lastSync.value = "No profile selected"
                return@launch
            }
            if (!ensureConnected()) {
                _measurement.value = LiveMeasurementState(
                    active = false,
                    type = type,
                    statusMessage = "Ring not connected. Place ring nearby and try again.",
                )
                return@launch
            }

            val currentProfile = profiles.getById(profileId)
            val currentYear = time.localClock().year
            val age = currentProfile?.age(currentYear) ?: 30
            val sbpBase = currentProfile?.sbpBaseline ?: Profile.DEFAULT_SBP_BASELINE
            val dbpBase = currentProfile?.dbpBaseline ?: Profile.DEFAULT_DBP_BASELINE

            _measurement.value = LiveMeasurementState(
                active = true,
                type = type,
                secondsRemaining = MEASURE_DURATION_SECONDS,
                statusMessage = when (type) {
                    RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE ->
                        "Measuring blood pressure... Keep your hand still at heart level."
                    RingPayloads.MEASURE_TYPE_SPO2 ->
                        "Measuring blood oxygen (SpO2)... Keep your hand still."
                    else ->
                        "Measuring heart rate... Keep your hand still."
                },
            )

            // Push user calibration baseline to the ring before starting BP measurement.
            if (currentProfile != null && type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE) {
                engine.syncProfileSettings(currentProfile, time, useMetric.value)
            }

            var streamAbortError = false
            val collectJob = launch {
                command.liveMeasurements.collect { pkt ->
                    if (pkt.type != type && pkt.type != 0) return@collect
                    if (pkt.errCode != 0) {
                        streamAbortError = true
                        _measurement.value = _measurement.value.copy(
                            active = false,
                            wearingError = true,
                            statusMessage = "Wearing error: make sure the ring sensors are snug against your finger.",
                        )
                        return@collect
                    }
                    if (type == RingPayloads.MEASURE_TYPE_SPO2) {
                        val spo2 = if (pkt.value in 50..100) pkt.value else _measurement.value.liveSpo2
                        val samples = if (pkt.value in 50..100) {
                            (_measurement.value.liveSamples + pkt.value).takeLast(30)
                        } else {
                            _measurement.value.liveSamples
                        }
                        _measurement.value = _measurement.value.copy(
                            liveSpo2 = spo2,
                            liveSamples = samples,
                        )
                        return@collect
                    }
                    val pulse = if (pkt.value > 0) pkt.value else _measurement.value.livePulse
                    var sbp = _measurement.value.liveSbp
                    var dbp = _measurement.value.liveDbp
                    if (pkt.sbp > 0 && pkt.dbp > 0) {
                        sbp = pkt.sbp
                        dbp = pkt.dbp
                    } else if (type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE && pulse > 0) {
                        val (estSbp, estDbp) = RingPayloads.estimateBpFromPpgRaw(
                            rawValue = pulse,
                            age = age,
                            sbpBaseline = sbpBase,
                            dbpBaseline = dbpBase,
                        )
                        sbp = estSbp
                        dbp = estDbp
                    }
                    val sampleVal = when {
                        type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE && sbp > 0 -> sbp
                        pulse > 0 -> pulse
                        else -> 0
                    }
                    val samples = if (sampleVal > 0) {
                        (_measurement.value.liveSamples + sampleVal).takeLast(30)
                    } else {
                        _measurement.value.liveSamples
                    }
                    _measurement.value = _measurement.value.copy(
                        livePulse = pulse,
                        liveSbp = sbp,
                        liveDbp = dbp,
                        liveSamples = samples,
                    )
                }
            }

            if (command.activeProtocol == com.kschulz.aro.data.ble.RingProtocol.JRING) {
                val mode = when (type) {
                    RingPayloads.MEASURE_TYPE_SPO2 -> com.kschulz.aro.data.ble.JRingProtocol.OPTICAL_SPO2
                    else -> com.kschulz.aro.data.ble.JRingProtocol.OPTICAL_BP_STRESS
                }
                command.send(
                    com.kschulz.aro.data.ble.JRingProtocol.CMD_OPTICAL_MODE,
                    com.kschulz.aro.data.ble.JRingProtocol.buildOpticalModePayload(mode),
                )
            } else {
                command.send(
                    Opcodes.START_HEART_RATE,
                    RingPayloads.buildStartMeasurementPayload(type),
                )
            }

            try {
                for (sec in MEASURE_DURATION_SECONDS downTo 1) {
                    if (streamAbortError || !command.isConnected) break
                    val liveHrNow = liveHeartRate.value
                    val curr = _measurement.value
                    val updatedPulse = if (liveHrNow > 0) liveHrNow else curr.livePulse
                    var updatedSbp = curr.liveSbp
                    var updatedDbp = curr.liveDbp
                    if (type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE && updatedPulse > 0) {
                        if (updatedSbp == 0 || (liveHrNow > 0 && liveHrNow != curr.livePulse)) {
                            val (estSbp, estDbp) = RingPayloads.estimateBpFromPpgRaw(
                                rawValue = updatedPulse,
                                age = age,
                                sbpBaseline = sbpBase,
                                dbpBaseline = dbpBase,
                            )
                            updatedSbp = estSbp
                            updatedDbp = estDbp
                        }
                    }
                    val tickSample = when {
                        type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE && updatedSbp > 0 -> updatedSbp
                        type == RingPayloads.MEASURE_TYPE_SPO2 && curr.liveSpo2 > 0 -> curr.liveSpo2
                        type == RingPayloads.MEASURE_TYPE_HEART_RATE && updatedPulse > 0 -> updatedPulse
                        else -> 0
                    }
                    val updatedSamples = if (tickSample > 0 && curr.liveSamples.size < (MEASURE_DURATION_SECONDS - sec + 1)) {
                        (curr.liveSamples + tickSample).takeLast(30)
                    } else {
                        curr.liveSamples
                    }
                    _measurement.value = curr.copy(
                        secondsRemaining = sec,
                        livePulse = updatedPulse,
                        liveSbp = updatedSbp,
                        liveDbp = updatedDbp,
                        liveSamples = updatedSamples,
                    )
                    delay(1_000L)
                }

                if (streamAbortError) {
                    sendStopLiveMeasurement(type, 0, 0)
                    return@launch
                }

                val nowMs = time.nowMillis()
                val finalState = _measurement.value
                val finalPulse = finalState.livePulse.takeIf { it > 0 } ?: liveHeartRate.value

                when (type) {
                    RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE -> {
                        val (finalSbp, finalDbp) = when {
                            finalState.liveSbp > 0 && finalState.liveDbp > 0 ->
                                finalState.liveSbp to finalState.liveDbp
                            finalPulse > 0 ->
                                RingPayloads.estimateBpFromPpgRaw(finalPulse, age, sbpBase, dbpBase)
                            else ->
                                sbpBase to dbpBase
                        }
                        sendStopLiveMeasurement(type, finalSbp, finalDbp)
                        health.upsertBloodPressure(
                            profileId,
                            listOf(BloodPressure(profileId, nowMs, finalSbp, finalDbp, BpSource.MANUAL)),
                        )
                        if (finalPulse > 0) {
                            health.upsertHeartRate(
                                profileId,
                                listOf(HeartRateSample(profileId, nowMs, finalPulse)),
                            )
                        }
                        _measurement.value = finalState.copy(
                            active = false,
                            secondsRemaining = 0,
                            liveSbp = finalSbp,
                            liveDbp = finalDbp,
                            livePulse = finalPulse,
                            statusMessage = "Recorded manual BP: $finalSbp/$finalDbp mmHg" +
                                (if (finalPulse > 0) " ($finalPulse bpm)" else ""),
                        )
                    }
                    RingPayloads.MEASURE_TYPE_SPO2 -> {
                        val finalSpo2 = finalState.liveSpo2
                        sendStopLiveMeasurement(type, finalSpo2, 0)
                        if (finalSpo2 in 50..100) {
                            health.upsertSpo2(
                                profileId,
                                listOf(Spo2Sample(profileId, nowMs, finalSpo2)),
                            )
                            _measurement.value = finalState.copy(
                                active = false,
                                secondsRemaining = 0,
                                liveSpo2 = finalSpo2,
                                statusMessage = "Recorded blood oxygen: $finalSpo2%",
                            )
                        } else {
                            _measurement.value = finalState.copy(
                                active = false,
                                secondsRemaining = 0,
                                statusMessage = "No SpO2 signal detected. Check ring placement.",
                            )
                        }
                    }
                    else -> {
                        sendStopLiveMeasurement(type, finalPulse, 0)
                        if (finalPulse > 0) {
                            health.upsertHeartRate(
                                profileId,
                                listOf(HeartRateSample(profileId, nowMs, finalPulse)),
                            )
                            _measurement.value = finalState.copy(
                                active = false,
                                secondsRemaining = 0,
                                livePulse = finalPulse,
                                statusMessage = "Recorded heart rate: $finalPulse bpm",
                            )
                        } else {
                            _measurement.value = finalState.copy(
                                active = false,
                                secondsRemaining = 0,
                                statusMessage = "No heart rate signal detected. Check ring placement.",
                            )
                        }
                    }
                }
            } finally {
                collectJob.cancel()
            }
        }
    }

    private suspend fun ensureConnected(): Boolean {
        if (command.isConnected) return true
        val ring = devices.observeActive().first() ?: return false
        if (transport.connectionState.value != ConnectionState.CONNECTING) {
            if (!transport.connect(ring.macAddress)) return false
        }
        val finalState = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            transport.connectionState.first { it != ConnectionState.CONNECTING }
        }
        if (finalState != ConnectionState.CONNECTED) {
            transport.disconnect()
            return false
        }
        return true
    }

    fun reconnect() {
        viewModelScope.launch {
            val ring = devices.observeActive().first()
            if (ring == null) {
                _lastSync.value = "No bound ring"
                return@launch
            }
            if (!BlePermissions.hasAllPermissions(context)) {
                _lastSync.value = "Bluetooth permission required"
                return@launch
            }
            _lastSync.value = "Connecting to ${ring.displayName}..."
            if (!transport.connect(ring.macAddress)) {
                _lastSync.value = "Bluetooth unavailable"
                return@launch
            }
            val finalState = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                transport.connectionState.first { it != ConnectionState.CONNECTING }
            }
            if (finalState == ConnectionState.CONNECTED) {
                _lastSync.value = "Connected to ${ring.displayName}"
                syncNow()
            } else {
                transport.disconnect()
                _lastSync.value = "Could not connect to ${ring.displayName}"
            }
        }
    }

    fun syncNow() {
        if (_syncing.value) return
        viewModelScope.launch {
            _syncing.value = true
            try {
                val profileId = settings.observeActiveProfileId().first()
                if (profileId == SettingsRepository.NO_PROFILE) {
                    _lastSync.value = "No profile selected"
                    return@launch
                }
                if (!command.isConnected) {
                    val ring = devices.observeActive().first()
                    if (ring == null) {
                        _lastSync.value = "No ring bound"
                        return@launch
                    }
                    _lastSync.value = "Connecting to ${ring.displayName}..."
                    if (!ensureConnected()) {
                        _lastSync.value = "Device not connected. Is the ring nearby?"
                        return@launch
                    }
                }
                command.refreshBattery()
                val currentProfile = profiles.getById(profileId)
                val metric = settings.observeUseMetric().first()
                val hrInterval = settings.observeAutoHrInterval().first()
                val bpInterval = settings.observeAutoBpInterval().first()
                val spo2Interval = settings.observeAutoSpo2Interval().first()
                val result = engine.sync(
                    profileId = profileId,
                    time = time,
                    profile = currentProfile,
                    useMetric = metric,
                    hrIntervalMinutes = hrInterval,
                    bpIntervalMinutes = bpInterval,
                    spo2IntervalMinutes = spo2Interval,
                )
                if (result.connected) {
                    runCatching { healthConnect.write(profileId, days = 7) }
                    com.kschulz.aro.widget.AroWidgets.updateAll(context)
                }
                _lastSync.value = if (result.connected) {
                    val clock = time.localClock()
                    val hhmm = "%02d:%02d".format(clock.hour, clock.minute)
                    val parts = buildList {
                        if (result.steps > 0) add("${result.steps} activity day" + if (result.steps == 1) "" else "s")
                        if (result.hrSamples > 0) add("${result.hrSamples} HR")
                        if (result.spo2Samples > 0) add("${result.spo2Samples} SpO2")
                        if (result.bpReadings > 0) add("${result.bpReadings} BP")
                        if (result.sleepSessions > 0) add("${result.sleepSessions} sleep")
                    }
                    if (parts.isEmpty()) "Synced at $hhmm" else "Synced at $hhmm • ${parts.joinToString(", ")}"
                } else {
                    "Device not connected"
                }
            } catch (e: Exception) {
                _lastSync.value = "Sync failed: ${e.message}"
            } finally {
                _syncing.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMeasurement()
    }

    companion object {
        const val DEFAULT_PROFILE_NAME = "Me"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val MEASURE_DURATION_SECONDS = 30
    }
}

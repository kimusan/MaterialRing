package com.kschulz.aro.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.ble.RingPayloads
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.ui.profile.ProfileEditorDialog
import com.kschulz.aro.ui.profile.ProfileSwitcherDialog
import com.kschulz.aro.ui.profile.profileSummaryLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onOpenHistory: (com.kschulz.aro.ui.history.HistoryMetric) -> Unit = {},
) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val boundRing by viewModel.boundRing.collectAsStateWithLifecycle()
    val liveHr by viewModel.liveHeartRate.collectAsStateWithLifecycle()
    val latestHr by viewModel.latestHr.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val bp by viewModel.latestBp.collectAsStateWithLifecycle()
    val spo2 by viewModel.latestSpo2.collectAsStateWithLifecycle()
    val sleep by viewModel.latestSleep.collectAsStateWithLifecycle()
    val batteryPoints by viewModel.bodyBattery.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val allProfiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val profile by viewModel.profileName.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()
    val measurement by viewModel.measurement.collectAsStateWithLifecycle()

    var showProfileSwitcher by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var isOnboardingEditor by remember { mutableStateOf(false) }

    val pullRefreshState = rememberPullToRefreshState()

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            if (!syncing) {
                viewModel.syncNow()
            }
        }
    }

    LaunchedEffect(syncing) {
        if (syncing) {
            pullRefreshState.startRefresh()
        } else {
            pullRefreshState.endRefresh()
        }
    }

    LaunchedEffect(Unit) { viewModel.ensureDefaultProfile() }

    if (showProfileSwitcher) {
        ProfileSwitcherDialog(
            profiles = allProfiles,
            activeProfileId = activeProfile?.id ?: -1L,
            onSwitch = { viewModel.switchProfile(it) },
            onEdit = { p ->
                showProfileSwitcher = false
                isOnboardingEditor = false
                editingProfile = p
            },
            onAddNew = {
                showProfileSwitcher = false
                isOnboardingEditor = false
                editingProfile = Profile(id = 0, name = "", createdAtEpochMs = System.currentTimeMillis())
            },
            onDismiss = { showProfileSwitcher = false },
        )
    }

    editingProfile?.let { target ->
        ProfileEditorDialog(
            initial = target,
            isOnboarding = isOnboardingEditor,
            onDismiss = { editingProfile = null },
            onSave = { saved ->
                viewModel.saveProfile(saved)
                editingProfile = null
            },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AssistChip(
                            onClick = { showProfileSwitcher = true },
                            label = {
                                Text(
                                    text = profile ?: "No profile",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Edit,
                                    contentDescription = "Switch or edit profile",
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                    val ringLabel = boundRing?.displayName?.let { "$it  •  " } ?: ""
                    val stateText = when (connectionState) {
                        ConnectionState.CONNECTED -> "${ringLabel}Connected"
                        ConnectionState.CONNECTING -> "${ringLabel}Connecting..."
                        ConnectionState.DISCONNECTED -> "${ringLabel}Not connected"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.BluetoothDisabled,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp).padding(end = 4.dp),
                            tint = when (connectionState) {
                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                            },
                        )
                        Text(
                            text = stateText,
                            style = MaterialTheme.typography.bodySmall,
                            color = when (connectionState) {
                                ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
                if (battery in 0..100) {
                    Text(
                        "${battery}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Filled.BatteryFull,
                        contentDescription = "Ring battery",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            activeProfile?.let { currentProfile ->
                if (!currentProfile.isConfigured) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Complete your user profile",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = "Set height, weight, BP baseline & daily goals to calibrate your ring.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            TextButton(
                                onClick = {
                                    isOnboardingEditor = true
                                    editingProfile = currentProfile
                                },
                            ) {
                                Text("Set up")
                            }
                        }
                    }
                } else {
                    Text(
                        text = profileSummaryLine(currentProfile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = { viewModel.syncNow() }, enabled = !syncing) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp).padding(end = 6.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp).padding(end = 6.dp),
                        )
                    }
                    Text(if (syncing) "Syncing..." else "Sync now")
                }

                if (connectionState == ConnectionState.DISCONNECTED) {
                    OutlinedButton(
                        onClick = { viewModel.reconnect() },
                        enabled = !syncing,
                    ) {
                        Text("Reconnect")
                    }
                }
            }

            lastSync?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (measurement.active || measurement.statusMessage != null) {
                LiveMeasurementCard(
                    state = measurement,
                    onStop = { viewModel.stopMeasurement() },
                    onRetry = {
                        if (measurement.type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE) {
                            viewModel.startBpMeasurement()
                        } else if (measurement.type == RingPayloads.MEASURE_TYPE_SPO2) {
                            viewModel.startSpo2Measurement()
                        } else {
                            viewModel.startHrMeasurement()
                        }
                    },
                )
            }

            // Hero Activity Ring Card
            val stepGoal = activeProfile?.goalSteps ?: Profile.DEFAULT_GOAL_STEPS
            val calGoal = activeProfile?.goalCalories ?: Profile.DEFAULT_GOAL_CALORIES
            val distGoal = activeProfile?.goalDistanceMeters ?: Profile.DEFAULT_GOAL_DISTANCE_METERS
            val stepsToday = today?.steps ?: 0
            val calToday = today?.calories ?: 0
            val distToday = today?.distanceMeters ?: 0

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.STEPS) },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    com.kschulz.aro.ui.history.ConcentricActivityRings(
                        stepsProgress = (stepsToday.toFloat() / stepGoal.coerceAtLeast(1)).coerceIn(0f, 1f),
                        caloriesProgress = (calToday.toFloat() / calGoal.coerceAtLeast(1)).coerceIn(0f, 1f),
                        distanceProgress = (distToday.toFloat() / distGoal.coerceAtLeast(1)).coerceIn(0f, 1f),
                        modifier = Modifier.size(96.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("Today's activity", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "$stepsToday / $stepGoal steps",
                            style = MaterialTheme.typography.bodyMedium,
                            color = com.kschulz.aro.ui.history.StepCyan,
                        )
                        Text(
                            text = "$calToday / $calGoal kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.kschulz.aro.ui.history.CalorieCrimson,
                        )
                        Text(
                            text = "$distToday / $distGoal m",
                            style = MaterialTheme.typography.bodySmall,
                            color = com.kschulz.aro.ui.history.DistanceGreen,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val hrDisplay = when {
                    liveHr > 0 -> "$liveHr bpm"
                    latestHr != null -> "${latestHr!!.bpm} bpm"
                    else -> "No reading"
                }
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Favorite,
                    title = "Heart rate",
                    value = hrDisplay,
                    subtitle = if (liveHr > 0) "Live sensor" else latestHr?.let { "Last recorded" },
                    live = liveHr > 0,
                    actionLabel = if (measurement.active) null else "Measure",
                    onAction = { viewModel.startHrMeasurement() },
                    onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.HEART_RATE) },
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Bloodtype,
                    title = "Blood pressure",
                    value = bp?.let { "${it.sbp}/${it.dbp} mmHg" } ?: "None",
                    subtitle = bp?.let {
                        val src = if (it.source == BpSource.MANUAL) "Manual" else "Auto"
                        "$src • ${bpCategoryLabel(it.sbp, it.dbp)}"
                    },
                    actionLabel = if (measurement.active) null else "Measure",
                    onAction = { viewModel.startBpMeasurement() },
                    onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.BLOOD_PRESSURE) },
                )
            }

            MetricTile(
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Filled.Bloodtype,
                title = "Blood oxygen (SpO2)",
                value = spo2?.let { "${it.percent}%" } ?: "No reading",
                subtitle = spo2?.let {
                    when {
                        it.percent >= 95 -> "Normal"
                        it.percent >= 90 -> "Slightly low"
                        else -> "Low"
                    }
                } ?: "Tap Measure to read SpO2 from ring",
                actionLabel = if (measurement.active) null else "Measure",
                onAction = { viewModel.startSpo2Measurement() },
                onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.SPO2) },
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.FitnessCenter,
                    title = "Steps today",
                    value = today?.let { "${it.steps} steps" } ?: "No data",
                    subtitle = today?.let { "${it.calories} kcal • ${it.distanceMeters} m (goal $stepGoal)" },
                    onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.STEPS) },
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Bedtime,
                    title = "Last night",
                    value = sleep?.let {
                        val min = sleepDurationMin(it)
                        "${min / 60}h ${min % 60}m"
                    } ?: "No data",
                    subtitle = activeProfile?.let { "Goal ${it.goalSleepMinutes / 60}h" },
                    onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.SLEEP) },
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenHistory(com.kschulz.aro.ui.history.HistoryMetric.BODY_BATTERY) },
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text("Body battery", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Derived wellness score, not a vital.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (batteryPoints.isNotEmpty()) {
                            val latest = batteryPoints.last()
                            Text(
                                "${latest.score}/100",
                                style = MaterialTheme.typography.headlineMedium,
                                color = com.kschulz.aro.ui.history.BodyBatteryBlue,
                            )
                        }
                    }
                    if (batteryPoints.isEmpty()) {
                        Text(
                            "No data yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        val latest = batteryPoints.last()
                        LinearProgressIndicator(
                            progress = { latest.score / 100f },
                            color = com.kschulz.aro.ui.history.BodyBatteryBlue,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun LiveMeasurementCard(
    state: LiveMeasurementState,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val isBp = state.type == RingPayloads.MEASURE_TYPE_BLOOD_PRESSURE
    val isSpo2 = state.type == RingPayloads.MEASURE_TYPE_SPO2
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (state.wearingError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when {
                        isBp -> "Blood Pressure Measurement"
                        isSpo2 -> "Blood Oxygen (SpO2) Measurement"
                        else -> "Heart Rate Measurement"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.active) {
                    Text(
                        text = "${state.secondsRemaining}s",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            if (state.active) {
                LinearProgressIndicator(
                    progress = { (30 - state.secondsRemaining).coerceIn(0, 30) / 30f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val liveReadout = when {
                isBp -> {
                    if (state.liveSbp > 0 && state.liveDbp > 0) {
                        "${state.liveSbp}/${state.liveDbp} mmHg" +
                            (if (state.livePulse > 0) "  •  ${state.livePulse} bpm" else "")
                    } else if (state.livePulse > 0) {
                        "Pulse ${state.livePulse} bpm — estimating BP..."
                    } else {
                        "Acquiring PPG signal..."
                    }
                }
                isSpo2 -> {
                    if (state.liveSpo2 > 0) "${state.liveSpo2}%" else "Acquiring SpO2 signal..."
                }
                else -> {
                    if (state.livePulse > 0) "${state.livePulse} bpm" else "Acquiring PPG signal..."
                }
            }

            if (state.active || state.liveSbp > 0 || state.livePulse > 0 || state.liveSpo2 > 0) {
                Text(
                    text = liveReadout,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            state.statusMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.active) {
                    TextButton(onClick = onStop) {
                        Text("Cancel")
                    }
                } else {
                    TextButton(onClick = onRetry) {
                        Text("Measure again")
                    }
                    TextButton(onClick = onStop) {
                        Text("Dismiss")
                    }
                }
            }
        }
    }
}

private fun bpCategoryLabel(sbp: Int, dbp: Int): String = when {
    sbp >= 140 || dbp >= 90 -> "High (Stage 2)"
    sbp >= 130 || dbp >= 80 -> "High (Stage 1)"
    sbp in 120..129 && dbp < 80 -> "Elevated"
    sbp < 90 || dbp < 60 -> "Low"
    else -> "Normal"
}

private fun sleepDurationMin(s: SleepSession): Int =
    ((s.endEpoch - s.startEpoch) / 60_000L).toInt()

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String? = null,
    live: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    Card(modifier = modifier, onClick = onClick) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    if (live) {
                        Icon(
                            Icons.Filled.FiberManualRecord,
                            contentDescription = "Live",
                            modifier = Modifier.padding(start = 4.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

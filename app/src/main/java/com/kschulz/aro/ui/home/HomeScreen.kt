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
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.ui.profile.ProfileEditorDialog
import com.kschulz.aro.ui.profile.ProfileSwitcherDialog
import com.kschulz.aro.ui.profile.profileSummaryLine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val boundRing by viewModel.boundRing.collectAsStateWithLifecycle()
    val liveHr by viewModel.liveHeartRate.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val bp by viewModel.latestBp.collectAsStateWithLifecycle()
    val sleep by viewModel.latestSleep.collectAsStateWithLifecycle()
    val batteryPoints by viewModel.bodyBattery.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val allProfiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val profile by viewModel.profileName.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()

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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Favorite,
                    title = "Heart rate",
                    value = if (liveHr > 0) "$liveHr bpm" else "No reading",
                    live = liveHr > 0,
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Bloodtype,
                    title = "Blood pressure",
                    value = bp?.let { "${it.sbp}/${it.dbp} mmHg" } ?: "None",
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.FitnessCenter,
                    title = "Steps today",
                    value = today?.let { "${it.steps} steps" } ?: "No data",
                )
                MetricTile(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Filled.Bedtime,
                    title = "Last night",
                    value = sleep?.let { "${sleepDurationMin(it)} min" } ?: "No data",
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Body battery", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Derived wellness score, not a vital.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (batteryPoints.isEmpty()) {
                        Text("No data yet.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val latest = batteryPoints.last()
                        Text(
                            "${latest.score}/100",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        LinearProgressIndicator(
                            progress = { latest.score / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
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

private fun sleepDurationMin(s: SleepSession): Int =
    ((s.endEpoch - s.startEpoch) / 60_000L).toInt()

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    live: Boolean = false,
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(16.dp)) {
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
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

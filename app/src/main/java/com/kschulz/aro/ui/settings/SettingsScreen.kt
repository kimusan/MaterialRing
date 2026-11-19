package com.kschulz.aro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.data.ble.ConnectionState
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.ui.profile.ProfileEditorDialog
import com.kschulz.aro.ui.profile.profileSummaryLine

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    onAddRing: () -> Unit = {},
) {
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val devices by viewModel.boundDevices.collectAsStateWithLifecycle()
    val activeRing by viewModel.activeRing.collectAsStateWithLifecycle()
    val activeTransportMac by viewModel.activeTransportMac.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val hcEnabled by viewModel.healthConnectEnabled.collectAsStateWithLifecycle()
    val useMetric by viewModel.useMetric.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var editingProfile by remember { mutableStateOf<Profile?>(null) }
    var showProfileDialog by remember { mutableStateOf(false) }

    if (showProfileDialog) {
        ProfileEditorDialog(
            initial = editingProfile,
            onDismiss = {
                showProfileDialog = false
                editingProfile = null
            },
            onSave = { updated ->
                viewModel.saveProfile(updated)
                showProfileDialog = false
                editingProfile = null
            },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("User profiles", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Local only. Switching is instant; physical stats and goals sync to your ring.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        editingProfile = Profile(
                            id = 0,
                            name = "",
                            createdAtEpochMs = System.currentTimeMillis(),
                        )
                        showProfileDialog = true
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("New")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { p ->
                    val active = p.id == activeId
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${p.name}${if (active) "  •  Active" else ""}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = profileSummaryLine(p),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "Goals: ${p.goalSteps} steps • ${p.goalCalories} kcal • ${p.goalDistanceMeters / 1000f} km • ${p.goalSleepMinutes / 60}h sleep",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (!active) {
                                    FilledTonalButton(onClick = { viewModel.switchProfile(p.id) }) {
                                        Text("Switch")
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        editingProfile = p
                                        showProfileDialog = true
                                    },
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit ${p.name}")
                                }
                                if (profiles.size > 1) {
                                    IconButton(onClick = { viewModel.deleteProfile(p.id) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${p.name}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Text("Units & preferences", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Metric units (cm, kg, km)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (useMetric) "Using metric units on phone and ring" else "Using imperial units (ft/in, lbs, mi)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = useMetric, onCheckedChange = viewModel::setUseMetric)
                }
            }
        }

        item {
            Text("Data sharing", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Health Connect", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Opt-in, write-only. Off by default; nothing leaves the device until you turn this on.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = hcEnabled, onCheckedChange = viewModel::setHealthConnect)
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Devices", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "Multiple rings supported (one active at a time)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalButton(onClick = onAddRing) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    Text("Add ring")
                }
            }
            if (devices.isEmpty()) {
                Text(
                    "No bound devices.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            devices.forEach { d ->
                val isActiveRing = d.macAddress == activeRing?.macAddress
                val ringConnectionState = if (isActiveRing && activeTransportMac == d.macAddress) {
                    connectionState
                } else {
                    ConnectionState.DISCONNECTED
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = if (isActiveRing) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    } else {
                        CardDefaults.cardColors()
                    },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "${d.displayName}${if (isActiveRing) "  •  Active" else ""}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${d.macAddress}  •  HW ${d.hwRevision.ifEmpty { "—" }} / FW ${d.fwRevision.ifEmpty { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = if (!isActiveRing) {
                                    "Standby"
                                } else {
                                    when (ringConnectionState) {
                                        ConnectionState.CONNECTED -> "Connected"
                                        ConnectionState.CONNECTING -> "Connecting..."
                                        ConnectionState.DISCONNECTED -> "Disconnected"
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (!isActiveRing) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    when (ringConnectionState) {
                                        ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                        ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                                    }
                                },
                            )
                        }
                        if (!isActiveRing) {
                            FilledTonalButton(onClick = { viewModel.switchRing(d.macAddress) }) {
                                Text("Switch")
                            }
                        } else if (ringConnectionState == ConnectionState.DISCONNECTED) {
                            TextButton(onClick = { viewModel.reconnect(d.macAddress) }) {
                                Text("Reconnect")
                            }
                        }
                        TextButton(onClick = { viewModel.unbind(d.macAddress) }) {
                            Text("Unbind")
                        }
                    }
                }
            }
        }

        item {
            Text("About", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Aro is a local-first companion app. All health data stays on this device " +
                    "in a private database. There is no cloud backend and no analytics.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        item {
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

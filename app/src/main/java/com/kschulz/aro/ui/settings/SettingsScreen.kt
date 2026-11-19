package com.kschulz.aro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val profiles by viewModel.allProfiles.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val devices by viewModel.boundDevices.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val hcEnabled by viewModel.healthConnectEnabled.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var newProfile by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Profiles", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Local only. Switching is instant; there is no account or logout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProfile,
                        onValueChange = { newProfile = it },
                        label = { Text("Profile name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        viewModel.addProfile(newProfile)
                        newProfile = ""
                    }) {
                        Text("Add profile")
                    }
                }
            }
        }

        item {
            profiles.forEach { p ->
                val active = p.id == activeId
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        "${p.name}${if (active) "  (active)" else ""}",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    if (!active) {
                        TextButton(onClick = { viewModel.switchProfile(p.id) }) { Text("Switch") }
                    }
                    TextButton(onClick = { viewModel.deleteProfile(p.id) }) { Text("Delete") }
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
            Text("Devices", style = MaterialTheme.typography.headlineSmall)
            if (devices.isEmpty()) {
                Text("No bound devices.", style = MaterialTheme.typography.bodyMedium)
            }
            devices.forEach { d ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(d.displayName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${d.macAddress}  •  HW ${d.hwRevision.ifEmpty { "—" }} / FW ${d.fwRevision.ifEmpty { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                when (connectionState) {
                                    com.kschulz.aro.data.ble.ConnectionState.CONNECTED -> "Connected"
                                    com.kschulz.aro.data.ble.ConnectionState.CONNECTING -> "Connecting..."
                                    com.kschulz.aro.data.ble.ConnectionState.DISCONNECTED -> "Disconnected"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = when (connectionState) {
                                    com.kschulz.aro.data.ble.ConnectionState.CONNECTED -> MaterialTheme.colorScheme.primary
                                    com.kschulz.aro.data.ble.ConnectionState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                                    com.kschulz.aro.data.ble.ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        if (connectionState == com.kschulz.aro.data.ble.ConnectionState.DISCONNECTED) {
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

package com.kschulz.aro.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.R
import com.kschulz.aro.data.ble.ConnectionState

@Composable
fun ScanScreen(
    viewModel: DeviceViewModel = hiltViewModel(),
    hasPermissions: Boolean = true,
    onRequestPermissions: () -> Unit = {},
    canCancel: Boolean = false,
    onCancel: () -> Unit = {},
    alreadyBoundMacs: Set<String> = emptySet(),
    onBound: () -> Unit = {},
) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()

    LaunchedEffect(hasPermissions) {
        if (hasPermissions) {
            viewModel.startScanning()
        }
    }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopScanning() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Extra clearance below the status bar / front-camera punch-hole area.
            Spacer(modifier = Modifier.height(36.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.BluetoothSearching,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (canCancel) "Add another ring" else "Scan for your ring",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (canCancel) {
                    "Connecting to a new ring will make it the active ring."
                } else {
                    "Keep your ring nearby and charged. It advertises with its model prefix (e.g. R03)."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )

            if (!hasPermissions) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "Bluetooth permissions are required to discover and connect to your smart ring.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onRequestPermissions) {
                            Text("Grant permissions")
                        }
                    }
                }
            } else if (!viewModel.bluetoothAvailable) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Text(
                        text = "Bluetooth is turned off. Enable Bluetooth to scan for your ring.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            if (state == ConnectionState.CONNECTING || status == "Setting up device...") {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 12.dp))
            }

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            if (discovered.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (hasPermissions && viewModel.bluetoothAvailable) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(32.dp)
                                    .padding(bottom = 12.dp),
                                strokeWidth = 2.5.dp,
                            )
                        }
                        Text(
                            text = if (isScanning) "Searching for nearby rings..." else "No rings found yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(discovered, key = { it.macAddress }) { ring ->
                        val isSaved = ring.macAddress in alreadyBoundMacs
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                viewModel.stopScanning()
                                viewModel.connect(ring.macAddress, ring.name, onBound)
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (isSaved) "${ring.name}  •  Saved" else ring.name,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        text = "${ring.macAddress}  •  ${ring.rssi} dBm",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                FilledTonalButton(
                                    enabled = state != ConnectionState.CONNECTING,
                                    onClick = {
                                        viewModel.stopScanning()
                                        viewModel.connect(ring.macAddress, ring.name, onBound)
                                    },
                                ) {
                                    Text(if (isSaved) "Activate" else "Connect")
                                }
                            }
                        }
                    }
                }
            }

            if (hasPermissions || canCancel) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                ) {
                    if (canCancel) {
                        OutlinedButton(
                            onClick = {
                                viewModel.stopScanning()
                                onCancel()
                            },
                        ) {
                            Text("Cancel")
                        }
                    }
                    if (hasPermissions) {
                        if (isScanning) {
                            OutlinedButton(onClick = { viewModel.stopScanning() }) {
                                Text("Stop scanning")
                            }
                        } else {
                            Button(onClick = { viewModel.startScanning() }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                                Text("Scan again")
                            }
                        }
                    }
                }
            }
        }
    }
}

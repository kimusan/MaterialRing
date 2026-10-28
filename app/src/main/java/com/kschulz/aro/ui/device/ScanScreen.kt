package com.kschulz.aro.ui.device

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.R
import com.kschulz.aro.data.ble.ConnectionState

@Composable
fun ScanScreen(viewModel: DeviceViewModel = hiltViewModel()) {
    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val state by viewModel.connectionState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startScanning() }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Text(
            "Scan for your ring",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Put the ring in range. It advertises with a fixed name prefix.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!viewModel.bluetoothAvailable) {
            Text(
                "Bluetooth is off. Turn it on to scan.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (state == ConnectionState.CONNECTING || status == "Setting up device...") {
            CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (discovered.isEmpty()) {
            if (viewModel.bluetoothAvailable) {
                Text(
                    "No rings found yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(discovered, key = { it.macAddress }) { ring ->
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            viewModel.stopScanning()
                            viewModel.connect(ring.macAddress, ring.name)
                        },
                    ) {
                        Column {
                            Text(ring.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "${ring.macAddress}  (${ring.rssi} dBm)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            OutlinedButton(onClick = { viewModel.stopScanning() }) {
                Text("Stop scanning")
            }
        }
    }
}

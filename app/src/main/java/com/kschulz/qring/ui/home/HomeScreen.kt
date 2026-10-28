package com.kschulz.qring.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.qring.data.model.SleepSession

@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val liveHr by viewModel.liveHeartRate.collectAsStateWithLifecycle()
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val bp by viewModel.latestBp.collectAsStateWithLifecycle()
    val sleep by viewModel.latestSleep.collectAsStateWithLifecycle()
    val batteryPoints by viewModel.bodyBattery.collectAsStateWithLifecycle()
    val profile by viewModel.profileName.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()
    val lastSync by viewModel.lastSync.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.ensureDefaultProfile() }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(profile ?: "No profile", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
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

        TextButton(onClick = { viewModel.syncNow() }, enabled = !syncing) {
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Filled.Sync, contentDescription = null)
            }
            Text(if (syncing) "Syncing..." else "Sync now")
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

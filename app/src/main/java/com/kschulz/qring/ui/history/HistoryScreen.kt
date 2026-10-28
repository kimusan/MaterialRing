package com.kschulz.qring.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel(), modifier: Modifier = Modifier) {
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val hr by viewModel.heartRate.collectAsStateWithLifecycle()
    val bp by viewModel.bloodPressure.collectAsStateWithLifecycle()
    val window by viewModel.windowDays.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                WindowPicker(window = window, onSelect = viewModel::setWindow)
            }
        }

        item {
            Text("Steps", style = MaterialTheme.typography.titleMedium)
            if (steps.isEmpty()) {
                Text("No data in this range.", style = MaterialTheme.typography.bodyMedium)
            }
            steps.forEach { d ->
                RowText(dateLabel(d.date), "${d.steps} steps  ·  ${(d.distanceMeters / 1000.0)} km  ·  ${d.calories} kcal")
            }
        }

        item {
            Text("Sleep", style = MaterialTheme.typography.titleMedium)
            if (sleep.isEmpty()) {
                Text("No data in this range.", style = MaterialTheme.typography.bodyMedium)
            }
            sleep.forEach { s ->
                val mins = ((s.endEpoch - s.startEpoch) / 60_000L).toInt()
                RowText(dateLabel(s.startEpoch / 1000L), "${mins} min" + if (s.isNap) " (nap)" else "")
            }
        }

        item {
            Text("Heart rate (avg per day)", style = MaterialTheme.typography.titleMedium)
            if (hr.isEmpty()) {
                Text("No data in this range.", style = MaterialTheme.typography.bodyMedium)
            }
            hr.groupBy { it.epochMs / 86_400_000L }
                .toSortedMap(reverseOrder())
                .forEach { (day, samples) ->
                    val avg = samples.map { it.bpm }.average().toInt()
                    val hi = samples.maxOf { it.bpm }
                    val lo = samples.minOf { it.bpm }
                    RowText(dateLabel(day), "avg $avg  (${lo} to $hi)")
                }
        }

        item {
            Text("Blood pressure", style = MaterialTheme.typography.titleMedium)
            if (bp.isEmpty()) {
                Text("No data in this range.", style = MaterialTheme.typography.bodyMedium)
            }
            bp.forEach { r ->
                val src = if (r.source.name == "MANUAL") "manual" else "auto"
                RowText(timestampLabel(r.epochMs), "${r.sbp}/${r.dbp} mmHg  ($src)")
            }
        }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneOffset.UTC)
private val tsFmt = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneOffset.UTC)

private fun dateLabel(epochDay: Long): String =
    dayFmt.format(Instant.ofEpochSecond(epochDay * 86_400L))

private fun timestampLabel(epochMs: Long): String =
    tsFmt.format(Instant.ofEpochMilli(epochMs))

@Composable
private fun RowText(label: String, value: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindowPicker(window: Int, onSelect: (Int) -> Unit) {
    val options = listOf(7 to "7d", 30 to "30d", 90 to "90d")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.widthIn(max = 200.dp)) {
        options.forEachIndexed { index, (days, label) ->
            SegmentedButton(
                selected = window == days,
                onClick = { onSelect(days) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) { Text(label) }
        }
    }
}

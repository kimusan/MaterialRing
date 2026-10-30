package com.kschulz.aro.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
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

        item { StepsChartCard(steps) }
        item { SleepPatternCard(sleep) }
        item { HeartRateChartCard(hr) }
        item { BloodPressureChartCard(bp) }
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

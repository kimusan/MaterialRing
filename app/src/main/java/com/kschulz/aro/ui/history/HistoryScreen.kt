package com.kschulz.aro.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.Ring
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FullDateFmt = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)
private val RangeDateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)

enum class HistoryMetric(val listIndex: Int) {
    STEPS(2),
    SLEEP(3),
    HEART_RATE(4),
    BLOOD_PRESSURE(5),
    SPO2(6),
    STRESS(7),
    TEMPERATURE(8),
    BODY_BATTERY(9),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
    focusMetric: HistoryMetric? = null,
    onFocusMetricConsumed: () -> Unit = {},
) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val anchorDay by viewModel.anchorEpochDay.collectAsStateWithLifecycle()
    val rings by viewModel.rings.collectAsStateWithLifecycle()
    val selectedRingMac by viewModel.selectedRingMac.collectAsStateWithLifecycle()
    val steps by viewModel.steps.collectAsStateWithLifecycle()
    val weekStripSteps by viewModel.weekStripSteps.collectAsStateWithLifecycle()
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val sleepSegments by viewModel.sleepSegments.collectAsStateWithLifecycle()
    val hr by viewModel.heartRate.collectAsStateWithLifecycle()
    val bp by viewModel.bloodPressure.collectAsStateWithLifecycle()
    val spo2 by viewModel.spo2.collectAsStateWithLifecycle()
    val stress by viewModel.stress.collectAsStateWithLifecycle()
    val temperature by viewModel.temperature.collectAsStateWithLifecycle()
    val bodyBattery by viewModel.bodyBattery.collectAsStateWithLifecycle()
    val profile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val useMetric by viewModel.useMetric.collectAsStateWithLifecycle()
    val syncing by viewModel.syncing.collectAsStateWithLifecycle()

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val pullRefreshState = rememberPullToRefreshState()

    LaunchedEffect(focusMetric) {
        if (focusMetric != null) {
            listState.animateScrollToItem(focusMetric.listIndex)
            onFocusMetricConsumed()
        }
    }

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

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(pullRefreshState.nestedScrollConnection),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RingFilterDropdown(
                        rings = rings,
                        selectedRingMac = selectedRingMac,
                        onSelectRing = viewModel::selectRing,
                    )
                    PeriodPicker(
                        selected = period,
                        onSelect = viewModel::setPeriod,
                    )
                    DateNavigatorBar(
                        period = period,
                        anchorEpochDay = anchorDay,
                        todayEpochDay = viewModel.todayEpochDay,
                        onPrevious = viewModel::previousPeriod,
                        onNext = viewModel::nextPeriod,
                        onResetToday = viewModel::resetToToday,
                    )
                }
            }

            item {
                WeekProgressStripCard(
                    anchorEpochDay = anchorDay,
                    weekSteps = weekStripSteps,
                    goalSteps = profile?.goalSteps ?: Profile.DEFAULT_GOAL_STEPS,
                    onSelectDay = viewModel::selectEpochDay,
                )
            }

            item {
                StepsChartCard(
                    days = steps,
                    period = period,
                    anchorEpochDay = anchorDay,
                    weekStripSteps = weekStripSteps,
                    profile = profile,
                    useMetric = useMetric,
                )
            }

            item {
                SleepPatternCard(
                    sessions = sleep,
                    segments = sleepSegments,
                    period = period,
                    anchorEpochDay = anchorDay,
                    goalSleepMinutes = profile?.goalSleepMinutes ?: Profile.DEFAULT_GOAL_SLEEP_MINUTES,
                )
            }

            item {
                HeartRateChartCard(
                    samples = hr,
                    period = period,
                    anchorEpochDay = anchorDay,
                    profile = profile,
                )
            }

            item {
                BloodPressureChartCard(
                    readings = bp,
                    period = period,
                    anchorEpochDay = anchorDay,
                )
            }

            item {
                Spo2ChartCard(
                    samples = spo2,
                    period = period,
                    anchorEpochDay = anchorDay,
                )
            }

            item {
                StressChartCard(
                    samples = stress,
                    period = period,
                    anchorEpochDay = anchorDay,
                )
            }

            item {
                TemperatureChartCard(
                    samples = temperature,
                    period = period,
                    anchorEpochDay = anchorDay,
                )
            }

            item {
                BodyBatteryChartCard(
                    points = bodyBattery,
                    period = period,
                    anchorEpochDay = anchorDay,
                )
            }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RingFilterDropdown(
    rings: List<Ring>,
    selectedRingMac: String?,
    onSelectRing: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedRing = rings.firstOrNull { it.macAddress == selectedRingMac }
    val displayLabel = when {
        selectedRingMac == null -> "All rings (Combined)"
        selectedRing != null -> selectedRing.displayName
        else -> selectedRingMac
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = displayLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("Ring source") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(
                            text = "All rings (Combined)",
                            fontWeight = if (selectedRingMac == null) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        Text(
                            text = "Combined totals across all rings",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                trailingIcon = if (selectedRingMac == null) {
                    { Icon(Icons.Default.Check, contentDescription = "Selected") }
                } else {
                    null
                },
                onClick = {
                    onSelectRing(null)
                    expanded = false
                },
            )
            if (rings.isNotEmpty()) {
                HorizontalDivider()
                rings.forEach { ring ->
                    val isSelected = selectedRingMac == ring.macAddress
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = if (ring.isActive) "${ring.displayName} (Active)" else ring.displayName,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Text(
                                    text = ring.macAddress,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = "Selected") }
                        } else {
                            null
                        },
                        onClick = {
                            onSelectRing(ring.macAddress)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeriodPicker(
    selected: HistoryPeriod,
    onSelect: (HistoryPeriod) -> Unit,
) {
    val entries = HistoryPeriod.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        entries.forEachIndexed { index, option ->
            SegmentedButton(
                selected = selected == option,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = entries.size),
            ) {
                Text(option.label)
            }
        }
    }
}

@Composable
private fun DateNavigatorBar(
    period: HistoryPeriod,
    anchorEpochDay: Long,
    todayEpochDay: Long,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onResetToday: () -> Unit,
) {
    val isAtToday = anchorEpochDay >= todayEpochDay
    val titleText = runCatching {
        val end = LocalDate.ofEpochDay(anchorEpochDay)
        if (period == HistoryPeriod.DAY) {
            if (anchorEpochDay == todayEpochDay) "Today, ${end.format(RangeDateFmt)}"
            else end.format(FullDateFmt)
        } else {
            val start = LocalDate.ofEpochDay((anchorEpochDay - (period.days - 1)).coerceAtLeast(0L))
            "${start.format(RangeDateFmt)} – ${end.format(RangeDateFmt)}, ${end.year}"
        }
    }.getOrDefault("")

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous period")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = titleText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!isAtToday) {
                    AssistChip(
                        onClick = onResetToday,
                        label = { Text("Today", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            IconButton(onClick = onNext, enabled = !isAtToday) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next period")
            }
        }
    }
}

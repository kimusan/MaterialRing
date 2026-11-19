package com.kschulz.aro.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.BodyBatteryPoint
import com.kschulz.aro.data.model.BpSource
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.Profile
import com.kschulz.aro.data.model.SleepSegment
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Interactive Compose Canvas charts modeled after the original QRing app's Daily, Weekly,
 * and Monthly detail views (`QStepBarChart`, `QSleepBarChart`, `QSleepWeekBarView`,
 * `QHeartLineChartView`, `QBloodPressureChartView`, `QBodyBatteryBarChartView`).
 */

// Original QRing metric palette
val StepCyan = Color(0xFF01BCDF)
val DistanceGreen = Color(0xFF28C76F)
val CalorieCrimson = Color(0xFFFF5252)

val SleepDeepViolet = Color(0xFF6718FF)
val SleepLightLavender = Color(0xFF9B86FF)
val SleepRemBlue = Color(0xFF5C9DFF)
val SleepAwakeGold = Color(0xFFF59E0B)

val HeartCoral = Color(0xFFFD4848)
val BpSystolicAmber = Color(0xFFFFB900)
val BpDiastolicIndigo = Color(0xFF887FFE)
val BodyBatteryBlue = Color(0xFF1296DB)

private val ShortDateFmt = DateTimeFormatter.ofPattern("MM/dd", Locale.US)
private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val DayInitialFmt = DateTimeFormatter.ofPattern("EE", Locale.US)

private val DashedGridEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)

/** 7-day strip shown at the top of History: each day displays its weekday initial, date
 *  number, and a circular step-goal completion ring (`YDailySportProgressCircle`). */
@Composable
fun WeekProgressStripCard(
    anchorEpochDay: Long,
    weekSteps: List<StepDay>,
    goalSteps: Int,
    onSelectDay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stepsByDay = remember(weekSteps) { weekSteps.associateBy { it.date } }
    val days = remember(anchorEpochDay) { (6L downTo 0L).map { anchorEpochDay - it } }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            days.forEach { day ->
                val date = runCatching { LocalDate.ofEpochDay(day) }.getOrNull()
                val daySteps = stepsByDay[day]?.steps ?: 0
                val progress = (daySteps.toFloat() / goalSteps.coerceAtLeast(1)).coerceIn(0f, 1f)
                val isSelected = day == anchorEpochDay

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                            else Color.Transparent,
                        )
                        .clickable { onSelectDay(day) }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = date?.format(DayInitialFmt)?.take(2) ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(34.dp)) {
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        Canvas(modifier = Modifier.size(34.dp)) {
                            val stroke = 3.5.dp.toPx()
                            drawArc(
                                color = trackColor,
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round),
                            )
                            if (progress > 0f) {
                                drawArc(
                                    color = StepCyan,
                                    startAngle = -90f,
                                    sweepAngle = 360f * progress,
                                    useCenter = false,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                        Text(
                            text = date?.dayOfMonth?.toString() ?: "",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Steps & Activity card supporting Day (concentric goal rings + 7-day context bar chart),
 * Week (7-day bar chart), and Month (30-day bar chart) with touch scrubbing and 2x2 summary grid.
 */
@Composable
fun StepsChartCard(
    days: List<StepDay>,
    period: HistoryPeriod = HistoryPeriod.MONTH,
    anchorEpochDay: Long = LocalDate.now().toEpochDay(),
    weekStripSteps: List<StepDay> = days,
    profile: Profile? = null,
    useMetric: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val goalSteps = profile?.goalSteps ?: Profile.DEFAULT_GOAL_STEPS
    val goalCalories = profile?.goalCalories ?: Profile.DEFAULT_GOAL_CALORIES
    val goalDistance = profile?.goalDistanceMeters ?: Profile.DEFAULT_GOAL_DISTANCE_METERS

    val chartDays = remember(days, weekStripSteps, period, anchorEpochDay) {
        val count = if (period == HistoryPeriod.DAY) 7 else period.days
        val source = if (period == HistoryPeriod.DAY) weekStripSteps else days
        val byDate = source.associateBy { it.date }
        val start = anchorEpochDay - (count - 1)
        (0 until count).map { idx ->
            val d = start + idx
            byDate[d] ?: StepDay(profileId = 0, date = d, steps = 0, distanceMeters = 0, calories = 0, durationMin = 0)
        }
    }

    var selectedIndex by remember(chartDays) {
        mutableIntStateOf((chartDays.indexOfLast { it.steps > 0 }).takeIf { it >= 0 } ?: chartDays.lastIndex.coerceAtLeast(0))
    }
    val selectedDay = chartDays.getOrNull(selectedIndex)
    val todayRecord = remember(days, anchorEpochDay) {
        days.firstOrNull { it.date == anchorEpochDay } ?: days.lastOrNull()
    }

    ChartCard(
        title = "Steps & Activity",
        summary = stepsSummary(days, useMetric),
        accentColor = StepCyan,
        modifier = modifier,
    ) {
        if (period == HistoryPeriod.DAY) {
            val stepsVal = todayRecord?.steps ?: 0
            val calVal = todayRecord?.calories ?: 0
            val distVal = todayRecord?.distanceMeters ?: 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ConcentricActivityRings(
                    stepsProgress = (stepsVal.toFloat() / goalSteps.coerceAtLeast(1)).coerceIn(0f, 1f),
                    caloriesProgress = (calVal.toFloat() / goalCalories.coerceAtLeast(1)).coerceIn(0f, 1f),
                    distanceProgress = (distVal.toFloat() / goalDistance.coerceAtLeast(1)).coerceIn(0f, 1f),
                    modifier = Modifier.size(110.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GoalMetricRow("Steps", "$stepsVal / $goalSteps", StepCyan)
                    GoalMetricRow("Calories", "$calVal / $goalCalories kcal", CalorieCrimson)
                    GoalMetricRow(
                        "Distance",
                        "${formatDistance(distVal, useMetric)} / ${formatDistance(goalDistance, useMetric)}",
                        DistanceGreen,
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        val hasAnySteps = chartDays.any { it.steps > 0 }
        if (!hasAnySteps) {
            EmptyChartText()
        } else {
            selectedDay?.let { sel ->
                val dateLabel = formatEpochDay(sel.date)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column {
                        Text(
                            text = "${sel.steps} steps",
                            style = MaterialTheme.typography.headlineSmall,
                            color = StepCyan,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "$dateLabel  •  ${sel.calories} kcal  •  ${formatDistance(sel.distanceMeters, useMetric)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "Tap/drag bars",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            InteractiveBarChart(
                values = chartDays.map { it.steps },
                selectedIndex = selectedIndex,
                onSelectIndex = { selectedIndex = it },
                barColor = StepCyan,
                goalLine = goalSteps,
                height = 160.dp,
                formatYLabel = { v -> if (v >= 1000) String.format(Locale.US, "%.1fk", v / 1000f) else v.toString() },
            )
            Spacer(Modifier.height(4.dp))
            XAxisDateRow(chartDays.map { it.date })
        }

        if (days.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val totalSteps = days.sumOf { it.steps }
            val avgSteps = totalSteps / days.size.coerceAtLeast(1)
            val totalDist = days.sumOf { it.distanceMeters }
            val totalCal = days.sumOf { it.calories }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Total steps", "$totalSteps", StepCyan, Modifier.weight(1f))
                StatMiniBox("Daily average", "$avgSteps", StepCyan, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Total distance", formatDistance(totalDist, useMetric), DistanceGreen, Modifier.weight(1f))
                StatMiniBox("Total calories", "$totalCal kcal", CalorieCrimson, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ConcentricActivityRings(
    stepsProgress: Float,
    caloriesProgress: Float,
    distanceProgress: Float,
    modifier: Modifier = Modifier,
) {
    val trackAlpha = 0.18f
    Canvas(modifier = modifier) {
        val stroke = 9.dp.toPx()
        val gap = 4.dp.toPx()
        val rings = listOf(
            StepCyan to stepsProgress,
            CalorieCrimson to caloriesProgress,
            DistanceGreen to distanceProgress,
        )
        rings.forEachIndexed { idx, (color, progress) ->
            val inset = stroke / 2f + idx * (stroke + gap)
            val diameter = (size.minDimension - inset * 2f).coerceAtLeast(10f)
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)
            drawArc(
                color = color.copy(alpha = trackAlpha),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (progress > 0f) {
                drawArc(
                    color = color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun GoalMetricRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * Sleep Card:
 * - Daily view renders the 4-tier Sleep Stage Hypnogram (`QSleepBarChart`: Awake, REM, Light, Deep)
 *   plus Sleep Score (0..100), Bedtime-to-Waketime window, and 4-stage horizontal progress bars.
 * - Weekly & Monthly views render the Stacked Stage Bar Chart (`QSleepWeekBarView` / `QSleepMonthBarView`)
 *   plus average stage breakdown bars.
 */
@Composable
fun SleepPatternCard(
    sessions: List<SleepSession>,
    segments: List<SleepSegment> = emptyList(),
    period: HistoryPeriod = HistoryPeriod.MONTH,
    goalSleepMinutes: Int = Profile.DEFAULT_GOAL_SLEEP_MINUTES,
    modifier: Modifier = Modifier,
) {
    val sortedSessions = remember(sessions) { sessions.sortedBy { it.startEpoch } }
    val segmentsBySession = remember(segments) { segments.groupBy { it.sessionId } }

    ChartCard(
        title = "Sleep & Stages",
        summary = sleepSummary(sortedSessions),
        accentColor = SleepDeepViolet,
        modifier = modifier,
    ) {
        if (sortedSessions.isEmpty()) {
            EmptyChartText()
        } else {
            val focusSession = sortedSessions.last()
            val focusSegments = segmentsBySession[focusSession.id].orEmpty()
            val totalDurationMin = ((focusSession.endEpoch - focusSession.startEpoch) / 60_000L).toInt().coerceAtLeast(1)

            // Calculate stage durations across all sessions in the current window
            val allWindowSegments = if (period == HistoryPeriod.DAY && focusSegments.isNotEmpty()) {
                focusSegments
            } else {
                segments
            }
            val stageTotals = remember(allWindowSegments, sortedSessions) {
                computeStageTotals(allWindowSegments, sortedSessions)
            }
            val sleepScore = remember(totalDurationMin, stageTotals, goalSleepMinutes) {
                computeSleepScore(totalDurationMin, stageTotals, goalSleepMinutes)
            }

            // Header summary row with Sleep Score ring
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val headlineMin = if (period == HistoryPeriod.DAY) {
                        totalDurationMin
                    } else {
                        sortedSessions.map { ((it.endEpoch - it.startEpoch) / 60_000L).toInt() }.average().roundToInt()
                    }
                    Text(
                        text = "${headlineMin / 60}h ${headlineMin % 60}m",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = SleepLightLavender,
                    )
                    val windowText = if (period == HistoryPeriod.DAY) {
                        "${formatEpochTime(focusSession.startEpoch)} – ${formatEpochTime(focusSession.endEpoch)}  •  ${formatEpochDate(focusSession.endEpoch)}"
                    } else {
                        "Average across ${sortedSessions.size} night(s)  •  Goal ${goalSleepMinutes / 60}h"
                    }
                    Text(
                        text = windowText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                    val trackColor = MaterialTheme.colorScheme.surfaceVariant
                    Canvas(modifier = Modifier.size(64.dp)) {
                        val stroke = 6.dp.toPx()
                        drawArc(
                            color = trackColor,
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                        drawArc(
                            color = SleepDeepViolet,
                            startAngle = -90f,
                            sweepAngle = 360f * (sleepScore / 100f),
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "$sleepScore",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Score",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Stage Legend
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LegendDot("Deep", SleepDeepViolet)
                LegendDot("Light", SleepLightLavender)
                LegendDot("REM", SleepRemBlue)
                LegendDot("Awake", SleepAwakeGold)
            }

            if (period == HistoryPeriod.DAY && focusSegments.isNotEmpty()) {
                SleepHypnogramChart(
                    session = focusSession,
                    segments = focusSegments,
                    height = 150.dp,
                )
            } else if (sortedSessions.size >= 1 && segments.isNotEmpty()) {
                SleepStackedBarChart(
                    sessions = sortedSessions,
                    segmentsBySession = segmentsBySession,
                    height = 155.dp,
                )
            } else {
                NightBands(sortedSessions, 130.dp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            StageBreakdownSection(stageTotals)
        }
    }
}

private data class StageTotals(
    val deepMin: Int,
    val lightMin: Int,
    val remMin: Int,
    val awakeMin: Int,
) {
    val totalMin: Int get() = (deepMin + lightMin + remMin + awakeMin).coerceAtLeast(1)
}

/** Classify ring sleep stage code into 0=Awake, 1=REM, 2=Light, 3=Deep. */
private fun classifySleepStage(stageCode: Int): Int = when (stageCode) {
    1 -> 3 // Deep
    2 -> 2 // Light
    3 -> 1 // REM
    else -> 0 // Awake (4 / 5 / 0)
}

private fun stageLabel(tier: Int): String = when (tier) {
    3 -> "Deep sleep"
    2 -> "Light sleep"
    1 -> "REM sleep"
    else -> "Awake"
}

private fun stageColor(tier: Int): Color = when (tier) {
    3 -> SleepDeepViolet
    2 -> SleepLightLavender
    1 -> SleepRemBlue
    else -> SleepAwakeGold
}

private fun computeStageTotals(
    segments: List<SleepSegment>,
    sessions: List<SleepSession>,
): StageTotals {
    if (segments.isEmpty()) {
        val total = sessions.sumOf { ((it.endEpoch - it.startEpoch) / 60_000L).toInt().coerceAtLeast(0) }
        val deep = (total * 0.25f).roundToInt()
        val rem = (total * 0.20f).roundToInt()
        val awake = (total * 0.05f).roundToInt()
        val light = (total - deep - rem - awake).coerceAtLeast(0)
        return StageTotals(deepMin = deep, lightMin = light, remMin = rem, awakeMin = awake)
    }
    var deep = 0
    var light = 0
    var rem = 0
    var awake = 0
    for (seg in segments) {
        when (classifySleepStage(seg.stage)) {
            3 -> deep += seg.durationMin
            2 -> light += seg.durationMin
            1 -> rem += seg.durationMin
            else -> awake += seg.durationMin
        }
    }
    val divisor = sessions.size.coerceAtLeast(1)
    return StageTotals(
        deepMin = deep / divisor,
        lightMin = light / divisor,
        remMin = rem / divisor,
        awakeMin = awake / divisor,
    )
}

private fun computeSleepScore(durationMin: Int, stages: StageTotals, goalMin: Int): Int {
    val durationRatio = (durationMin.toFloat() / goalMin.coerceAtLeast(300)).coerceIn(0f, 1.1f)
    val deepRatio = (stages.deepMin.toFloat() / stages.totalMin).coerceIn(0f, 0.4f) / 0.25f
    val remRatio = (stages.remMin.toFloat() / stages.totalMin).coerceIn(0f, 0.35f) / 0.20f
    val raw = (durationRatio * 60f + deepRatio.coerceAtMost(1.2f) * 22f + remRatio.coerceAtMost(1.2f) * 18f).roundToInt()
    return raw.coerceIn(40, 100)
}

/**
 * 4-tier Sleep Stage Hypnogram (`QSleepBarChart.smali`): plots each segment along the night's
 * timeline at its stage tier (Awake top, REM second, Light third, Deep bottom) with vertical
 * transition connectors and interactive touch scrubbing.
 */
@Composable
private fun SleepHypnogramChart(
    session: SleepSession,
    segments: List<SleepSegment>,
    height: Dp,
) {
    var selectedSegIdx by remember(segments) { mutableIntStateOf(0) }
    val selectedSeg = segments.getOrNull(selectedSegIdx)

    Column {
        selectedSeg?.let { seg ->
            val tier = classifySleepStage(seg.stage)
            Text(
                text = "${stageLabel(tier)}  •  ${seg.durationMin} min",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = stageColor(tier),
            )
            Spacer(Modifier.height(4.dp))
        }

        val gridColor = MaterialTheme.colorScheme.outlineVariant
        val cursorColor = MaterialTheme.colorScheme.onSurface
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(segments) {
                    val totalMin = segments.sumOf { it.durationMin }.coerceAtLeast(1)
                    fun pickAt(x: Float) {
                        val targetMin = (x / size.width.coerceAtLeast(1) * totalMin).toInt()
                        var acc = 0
                        for (i in segments.indices) {
                            acc += segments[i].durationMin
                            if (targetMin <= acc) {
                                selectedSegIdx = i
                                break
                            }
                        }
                    }
                    detectTapGestures { offset -> pickAt(offset.x) }
                }
                .pointerInput(segments) {
                    val totalMin = segments.sumOf { it.durationMin }.coerceAtLeast(1)
                    detectHorizontalDragGestures { change, _ ->
                        val targetMin = (change.position.x / size.width.coerceAtLeast(1) * totalMin).toInt()
                        var acc = 0
                        for (i in segments.indices) {
                            acc += segments[i].durationMin
                            if (targetMin <= acc) {
                                selectedSegIdx = i
                                break
                            }
                        }
                    }
                },
        ) {
            if (segments.isEmpty()) return@Canvas
            val totalMin = segments.sumOf { it.durationMin }.coerceAtLeast(1)
            val tierCount = 4
            val rowH = size.height / tierCount
            val blockH = (rowH * 0.58f).coerceAtLeast(8f)

            for (t in 0 until tierCount) {
                val y = t * rowH + rowH / 2f
                drawLine(
                    color = gridColor.copy(alpha = 0.4f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    pathEffect = DashedGridEffect,
                )
            }

            var elapsedMin = 0
            var prevTierCenterY: Float? = null
            segments.forEachIndexed { idx, seg ->
                val tier = classifySleepStage(seg.stage) // 0=Awake(top)..3=Deep(bottom)
                val x0 = (elapsedMin.toFloat() / totalMin) * size.width
                val x1 = ((elapsedMin + seg.durationMin).toFloat() / totalMin) * size.width
                val centerY = tier * rowH + rowH / 2f
                val topY = centerY - blockH / 2f
                val color = stageColor(tier)

                prevTierCenterY?.let { prevY ->
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(x0, prevY),
                        end = Offset(x0, centerY),
                        strokeWidth = 2f,
                    )
                }
                prevTierCenterY = centerY

                drawRoundRect(
                    color = if (idx == selectedSegIdx) color else color.copy(alpha = 0.85f),
                    topLeft = Offset(x0, topY),
                    size = Size((x1 - x0).coerceAtLeast(3f), blockH),
                    cornerRadius = CornerRadius(6f, 6f),
                )

                if (idx == selectedSegIdx) {
                    val cx = (x0 + x1) / 2f
                    drawLine(
                        color = cursorColor.copy(alpha = 0.6f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 2f,
                        pathEffect = DashedGridEffect,
                    )
                }
                elapsedMin += seg.durationMin
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatEpochTime(session.startEpoch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val midEpoch = (session.startEpoch + session.endEpoch) / 2
            Text(formatEpochTime(midEpoch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatEpochTime(session.endEpoch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Weekly & Monthly Stacked Stage Bar Chart (`QSleepWeekBarView` / `QSleepMonthBarView`).
 * Each night's bar vertically stacks Deep, Light, REM, and Awake minutes.
 */
@Composable
private fun SleepStackedBarChart(
    sessions: List<SleepSession>,
    segmentsBySession: Map<Long, List<SleepSegment>>,
    height: Dp,
) {
    var selectedIndex by remember(sessions) { mutableIntStateOf(sessions.lastIndex.coerceAtLeast(0)) }
    val selectedSession = sessions.getOrNull(selectedIndex)
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column {
        selectedSession?.let { sess ->
            val durMin = ((sess.endEpoch - sess.startEpoch) / 60_000L).toInt()
            Text(
                text = "${formatEpochDate(sess.endEpoch)}  •  ${durMin / 60}h ${durMin % 60}m",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SleepLightLavender,
            )
            Spacer(Modifier.height(4.dp))
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(sessions) {
                    detectTapGestures { offset ->
                        if (sessions.isNotEmpty()) {
                            val idx = ((offset.x / size.width.coerceAtLeast(1)) * sessions.size)
                                .toInt()
                                .coerceIn(0, sessions.lastIndex)
                            selectedIndex = idx
                        }
                    }
                },
        ) {
            if (sessions.isEmpty()) return@Canvas
            val maxMin = 600f // 10 hours scale
            listOf(0.3f, 0.6f, 0.9f).forEach { frac ->
                val y = size.height * (1f - frac)
                drawLine(
                    color = gridColor.copy(alpha = 0.45f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    pathEffect = DashedGridEffect,
                )
            }

            val slotW = size.width / sessions.size
            val barW = (slotW * 0.55f).coerceIn(6f, 36f)
            sessions.forEachIndexed { idx, sess ->
                val segs = segmentsBySession[sess.id].orEmpty()
                val totals = computeStageTotals(segs, listOf(sess))
                val parts = listOf(
                    SleepDeepViolet to totals.deepMin,
                    SleepLightLavender to totals.lightMin,
                    SleepRemBlue to totals.remMin,
                    SleepAwakeGold to totals.awakeMin,
                )
                val left = idx * slotW + (slotW - barW) / 2f
                var currentBottom = size.height
                parts.forEach { (color, mins) ->
                    if (mins > 0) {
                        val h = (mins / maxMin * size.height).coerceIn(2f, currentBottom)
                        drawRoundRect(
                            color = if (idx == selectedIndex) color else color.copy(alpha = 0.78f),
                            topLeft = Offset(left, currentBottom - h),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(4f, 4f),
                        )
                        currentBottom -= h
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        XAxisDateRow(sessions.map { it.endEpoch / 86_400_000L })
    }
}

@Composable
private fun StageBreakdownSection(totals: StageTotals) {
    val rows = listOf(
        Triple("Deep sleep", totals.deepMin, SleepDeepViolet),
        Triple("Light sleep", totals.lightMin, SleepLightLavender),
        Triple("REM sleep", totals.remMin, SleepRemBlue),
        Triple("Awake", totals.awakeMin, SleepAwakeGold),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { (label, mins, color) ->
            val pct = (mins * 100f / totals.totalMin).roundToInt().coerceIn(0, 100)
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    Text(
                        "${mins / 60}h ${mins % 60}m ($pct%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LinearProgressIndicator(
                    progress = { (pct / 100f).coerceIn(0f, 1f) },
                    color = color,
                    trackColor = color.copy(alpha = 0.16f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/**
 * Heart Rate Card (`QHeartLineChartView.smali`):
 * - Daily view plots the 24-hour coral-red area-line chart with fixed Y-axis gridlines
 *   (40, 85, 130, 175 bpm) and interactive touch scrubber.
 * - Weekly & Monthly views plot daily Min-Max range capsules + Average BPM polyline.
 * - Includes the 3-column Avg / Min / Max summary card and 5-Zone Heart Rate Breakdown.
 */
@Composable
fun HeartRateChartCard(
    samples: List<HeartRateSample>,
    period: HistoryPeriod = HistoryPeriod.MONTH,
    profile: Profile? = null,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(samples) { samples.sortedBy { it.epochMs } }
    val bpms = remember(sorted) { sorted.map { it.bpm } }
    var selectedIdx by remember(sorted) { mutableIntStateOf(sorted.lastIndex.coerceAtLeast(0)) }

    ChartCard(
        title = "Heart Rate",
        summary = if (bpms.isEmpty()) null else "Avg ${bpms.average().roundToInt()} bpm  •  Range ${bpms.min()}–${bpms.max()} bpm",
        accentColor = HeartCoral,
        modifier = modifier,
    ) {
        if (sorted.isEmpty()) {
            EmptyChartText()
        } else {
            val selected = sorted.getOrNull(selectedIdx) ?: sorted.last()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = "${selected.bpm} bpm",
                        style = MaterialTheme.typography.headlineSmall,
                        color = HeartCoral,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "${formatEpochDate(selected.epochMs)} ${formatEpochTime(selected.epochMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "${sorted.size} reading(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))

            HeartRateAreaChart(
                samples = sorted,
                selectedIndex = selectedIdx,
                onSelectIndex = { selectedIdx = it },
                height = 165.dp,
            )

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Average", "${bpms.average().roundToInt()} bpm", HeartCoral, Modifier.weight(1f))
                StatMiniBox("Minimum", "${bpms.min()} bpm", HeartCoral, Modifier.weight(1f))
                StatMiniBox("Maximum", "${bpms.max()} bpm", HeartCoral, Modifier.weight(1f))
            }

            Spacer(Modifier.height(12.dp))
            val maxHr = (220 - (profile?.age() ?: 30)).coerceIn(150, 210)
            HeartRateZonesSection(bpms = bpms, maxHr = maxHr)
        }
    }
}

@Composable
private fun HeartRateAreaChart(
    samples: List<HeartRateSample>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    height: Dp,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minScale = 40f
    val maxScale = 190f

    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(samples) {
                        detectTapGestures { offset ->
                            if (samples.isNotEmpty()) {
                                val idx = ((offset.x / size.width.coerceAtLeast(1)) * samples.size)
                                    .toInt()
                                    .coerceIn(0, samples.lastIndex)
                                onSelectIndex(idx)
                            }
                        }
                    }
                    .pointerInput(samples) {
                        detectHorizontalDragGestures { change, _ ->
                            if (samples.isNotEmpty()) {
                                val idx = ((change.position.x / size.width.coerceAtLeast(1)) * samples.size)
                                    .toInt()
                                    .coerceIn(0, samples.lastIndex)
                                onSelectIndex(idx)
                            }
                        }
                    },
            ) {
                val padX = 10f
                val padY = 10f
                val usableW = (size.width - padX * 2).coerceAtLeast(1f)
                val usableH = (size.height - padY * 2).coerceAtLeast(1f)

                listOf(40f, 85f, 130f, 175f).forEach { level ->
                    val y = padY + usableH * (1f - (level - minScale) / (maxScale - minScale))
                    drawLine(
                        color = gridColor.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        pathEffect = DashedGridEffect,
                    )
                }

                val xs = FloatArray(samples.size) { i ->
                    if (samples.size == 1) size.width / 2f
                    else padX + i * usableW / (samples.size - 1)
                }
                val ys = FloatArray(samples.size) { i ->
                    val norm = ((samples[i].bpm - minScale) / (maxScale - minScale)).coerceIn(0f, 1f)
                    padY + usableH * (1f - norm)
                }

                if (samples.size >= 2) {
                    val linePath = Path().apply {
                        for (i in xs.indices) {
                            if (i == 0) moveTo(xs[i], ys[i]) else lineTo(xs[i], ys[i])
                        }
                    }
                    val areaPath = Path().apply {
                        addPath(linePath)
                        lineTo(xs.last(), size.height)
                        lineTo(xs.first(), size.height)
                        close()
                    }
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(HeartCoral.copy(alpha = 0.32f), HeartCoral.copy(alpha = 0.0f)),
                        ),
                    )
                    drawPath(
                        path = linePath,
                        color = HeartCoral,
                        style = Stroke(width = 3f, cap = StrokeCap.Round),
                    )
                }

                val sel = selectedIndex.coerceIn(0, samples.lastIndex)
                drawLine(
                    color = HeartCoral.copy(alpha = 0.6f),
                    start = Offset(xs[sel], 0f),
                    end = Offset(xs[sel], size.height),
                    strokeWidth = 2f,
                    pathEffect = DashedGridEffect,
                )
                drawCircle(
                    color = Color.White,
                    radius = 7f,
                    center = Offset(xs[sel], ys[sel]),
                )
                drawCircle(
                    color = HeartCoral,
                    radius = 4.5f,
                    center = Offset(xs[sel], ys[sel]),
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(height)
                    .padding(end = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                listOf("175", "130", "85", "40").forEach { lbl ->
                    Text(lbl, style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatEpochTime(samples.first().epochMs), style = MaterialTheme.typography.bodySmall, color = labelColor)
            if (samples.size > 2) {
                Text(formatEpochTime(samples[samples.size / 2].epochMs), style = MaterialTheme.typography.bodySmall, color = labelColor)
            }
            Text(formatEpochTime(samples.last().epochMs), style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
    }
}

@Composable
private fun HeartRateZonesSection(bpms: List<Int>, maxHr: Int) {
    val zones = listOf(
        Triple("Limit (>90%)", (maxHr * 0.90f).toInt()..300, Color(0xFFFC727D)),
        Triple("Anaerobic (80–90%)", (maxHr * 0.80f).toInt() until (maxHr * 0.90f).toInt(), Color(0xFFFFB376)),
        Triple("Aerobic (70–80%)", (maxHr * 0.70f).toInt() until (maxHr * 0.80f).toInt(), Color(0xFFF5C443)),
        Triple("Fat burn (60–70%)", (maxHr * 0.60f).toInt() until (maxHr * 0.70f).toInt(), Color(0xFF48C78E)),
        Triple("Warm up (<60%)", 0 until (maxHr * 0.60f).toInt(), Color(0xFF61B7FF)),
    )
    val total = bpms.size.coerceAtLeast(1)
    Text(
        text = "Heart rate zones (Max HR $maxHr bpm)",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp)),
    ) {
        zones.forEach { (_, range, color) ->
            val count = bpms.count { it in range }
            if (count > 0) {
                Box(
                    modifier = Modifier
                        .weight(count.toFloat() / total)
                        .height(10.dp)
                        .background(color),
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        zones.forEach { (name, range, color) ->
            val pct = (bpms.count { it in range } * 100f / total).roundToInt()
            LegendDot("${name.substringBefore(" ")} $pct%", color)
        }
    }
}

/**
 * Blood Pressure Card (`QBloodPressureChartView.smali`):
 * - Dual-polyline and pulse-pressure range plot for Systolic (`#FFB900` amber) and
 *   Diastolic (`#887FFE` indigo) with dual-ring markers, Y-axis gridlines (60..180 mmHg),
 *   interactive touch scrubber, Avg/Min/Max summary, and timestamped reading history list.
 */
@Composable
fun BloodPressureChartCard(
    readings: List<BloodPressure>,
    period: HistoryPeriod = HistoryPeriod.MONTH,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(readings) { readings.sortedBy { it.epochMs } }
    var selectedIdx by remember(sorted) { mutableIntStateOf(sorted.lastIndex.coerceAtLeast(0)) }

    ChartCard(
        title = "Blood Pressure",
        summary = bpSummary(sorted),
        accentColor = BpSystolicAmber,
        modifier = modifier,
    ) {
        if (sorted.isEmpty()) {
            EmptyChartText()
        } else {
            val selected = sorted.getOrNull(selectedIdx) ?: sorted.last()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = "${selected.sbp}/${selected.dbp} mmHg",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = BpSystolicAmber,
                    )
                    val srcLabel = if (selected.source == BpSource.MANUAL) "Manual" else "Auto"
                    Text(
                        text = "${formatEpochDate(selected.epochMs)} ${formatEpochTime(selected.epochMs)}  •  $srcLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LegendDot("SBP", BpSystolicAmber)
                    LegendDot("DBP", BpDiastolicIndigo)
                }
            }
            Spacer(Modifier.height(8.dp))

            BloodPressureDualChart(
                readings = sorted,
                selectedIndex = selectedIdx,
                onSelectIndex = { selectedIdx = it },
                height = 175.dp,
            )

            Spacer(Modifier.height(12.dp))
            val avgSbp = sorted.map { it.sbp }.average().roundToInt()
            val avgDbp = sorted.map { it.dbp }.average().roundToInt()
            val minSbp = sorted.minOf { it.sbp }
            val minDbp = sorted.minOf { it.dbp }
            val maxSbp = sorted.maxOf { it.sbp }
            val maxDbp = sorted.maxOf { it.dbp }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Average", "$avgSbp/$avgDbp", BpSystolicAmber, Modifier.weight(1f))
                StatMiniBox("Lowest", "$minSbp/$minDbp", BpDiastolicIndigo, Modifier.weight(1f))
                StatMiniBox("Highest", "$maxSbp/$maxDbp", BpSystolicAmber, Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Recent readings",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            sorted.asReversed().take(5).forEach { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${formatEpochDate(item.epochMs)} ${formatEpochTime(item.epochMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "${item.sbp}/${item.dbp} mmHg",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.source == BpSource.MANUAL) {
                            BpSystolicAmber.copy(alpha = 0.18f)
                        } else {
                            BpDiastolicIndigo.copy(alpha = 0.18f)
                        },
                    ) {
                        Text(
                            text = if (item.source == BpSource.MANUAL) "Manual" else "Auto",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BloodPressureDualChart(
    readings: List<BloodPressure>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    height: Dp,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val minScale = 50f
    val maxScale = 190f

    Column {
        Box(modifier = Modifier.fillMaxWidth()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .pointerInput(readings) {
                        detectTapGestures { offset ->
                            if (readings.isNotEmpty()) {
                                val idx = ((offset.x / size.width.coerceAtLeast(1)) * readings.size)
                                    .toInt()
                                    .coerceIn(0, readings.lastIndex)
                                onSelectIndex(idx)
                            }
                        }
                    }
                    .pointerInput(readings) {
                        detectHorizontalDragGestures { change, _ ->
                            if (readings.isNotEmpty()) {
                                val idx = ((change.position.x / size.width.coerceAtLeast(1)) * readings.size)
                                    .toInt()
                                    .coerceIn(0, readings.lastIndex)
                                onSelectIndex(idx)
                            }
                        }
                    },
            ) {
                val padX = 14f
                val padY = 12f
                val usableW = (size.width - padX * 2).coerceAtLeast(1f)
                val usableH = (size.height - padY * 2).coerceAtLeast(1f)

                listOf(60f, 90f, 120f, 150f, 180f).forEach { level ->
                    val y = padY + usableH * (1f - (level - minScale) / (maxScale - minScale))
                    drawLine(
                        color = gridColor.copy(alpha = 0.45f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        pathEffect = DashedGridEffect,
                    )
                }

                val xs = FloatArray(readings.size) { i ->
                    if (readings.size == 1) size.width / 2f
                    else padX + i * usableW / (readings.size - 1)
                }
                val sbpYs = FloatArray(readings.size) { i ->
                    val norm = ((readings[i].sbp - minScale) / (maxScale - minScale)).coerceIn(0f, 1f)
                    padY + usableH * (1f - norm)
                }
                val dbpYs = FloatArray(readings.size) { i ->
                    val norm = ((readings[i].dbp - minScale) / (maxScale - minScale)).coerceIn(0f, 1f)
                    padY + usableH * (1f - norm)
                }

                // Vertical pulse-pressure connectors at each reading
                for (i in xs.indices) {
                    drawLine(
                        color = BpSystolicAmber.copy(alpha = 0.25f),
                        start = Offset(xs[i], sbpYs[i]),
                        end = Offset(xs[i], dbpYs[i]),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }

                if (readings.size >= 2) {
                    val sbpPath = Path().apply {
                        for (i in xs.indices) {
                            if (i == 0) moveTo(xs[i], sbpYs[i]) else lineTo(xs[i], sbpYs[i])
                        }
                    }
                    val dbpPath = Path().apply {
                        for (i in xs.indices) {
                            if (i == 0) moveTo(xs[i], dbpYs[i]) else lineTo(xs[i], dbpYs[i])
                        }
                    }
                    drawPath(sbpPath, color = BpSystolicAmber, style = Stroke(width = 3f, cap = StrokeCap.Round))
                    drawPath(dbpPath, color = BpDiastolicIndigo, style = Stroke(width = 3f, cap = StrokeCap.Round))
                }

                val sel = selectedIndex.coerceIn(0, readings.lastIndex)
                drawLine(
                    color = BpSystolicAmber.copy(alpha = 0.65f),
                    start = Offset(xs[sel], 0f),
                    end = Offset(xs[sel], size.height),
                    strokeWidth = 2f,
                    pathEffect = DashedGridEffect,
                )

                for (i in xs.indices) {
                    val rOuter = if (i == sel) 7f else 5f
                    val rInner = if (i == sel) 4.5f else 3.2f
                    drawCircle(Color.White, radius = rOuter, center = Offset(xs[i], sbpYs[i]))
                    drawCircle(BpSystolicAmber, radius = rInner, center = Offset(xs[i], sbpYs[i]))
                    drawCircle(Color.White, radius = rOuter, center = Offset(xs[i], dbpYs[i]))
                    drawCircle(BpDiastolicIndigo, radius = rInner, center = Offset(xs[i], dbpYs[i]))
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(height)
                    .padding(end = 2.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                listOf("180", "150", "120", "90", "60").forEach { lbl ->
                    Text(lbl, style = MaterialTheme.typography.labelSmall, color = labelColor)
                }
            }
        }
    }
}

val Spo2Teal = Color(0xFF00ACC1)

/**
 * Blood Oxygen (SpO2) Card:
 * - Shows latest / selected SpO2 %, interactive bar chart on a 0..100% scale, and
 *   Min / Avg / Max summary stats.
 */
@Composable
fun Spo2ChartCard(
    samples: List<com.kschulz.aro.data.model.Spo2Sample>,
    period: HistoryPeriod = HistoryPeriod.DAY,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(samples) { samples.sortedBy { it.epochMs } }
    var selectedIdx by remember(sorted) { mutableIntStateOf(sorted.lastIndex.coerceAtLeast(0)) }

    ChartCard(
        title = "Blood Oxygen (SpO2)",
        summary = if (sorted.isEmpty()) {
            "Tap Measure on Home or pull to sync"
        } else {
            val avg = sorted.map { it.percent }.average().toInt()
            "Latest ${sorted.last().percent}%  •  Avg $avg%"
        },
        accentColor = Spo2Teal,
        modifier = modifier,
    ) {
        if (sorted.isEmpty()) {
            EmptyChartText()
        } else {
            val selected = sorted.getOrNull(selectedIdx) ?: sorted.last()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = if (selected.minPercent != selected.maxPercent) {
                            "${selected.minPercent}–${selected.maxPercent}%"
                        } else {
                            "${selected.percent}%"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Spo2Teal,
                    )
                    Text(
                        text = "${formatEpochDate(selected.epochMs)} ${formatEpochTime(selected.epochMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            InteractiveBarChart(
                values = sorted.map { it.percent },
                selectedIndex = selectedIdx,
                onSelectIndex = { selectedIdx = it },
                barColor = Spo2Teal,
                fixedMax = 100,
                height = 145.dp,
                formatYLabel = { "$it%" },
            )

            Spacer(Modifier.height(10.dp))
            val minVal = sorted.minOf { it.minPercent }
            val avgVal = sorted.map { it.percent }.average().toInt()
            val maxVal = sorted.maxOf { it.maxPercent }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Min", "$minVal%", Spo2Teal, Modifier.weight(1f))
                StatMiniBox("Avg", "$avgVal%", Spo2Teal, Modifier.weight(1f))
                StatMiniBox("Max", "$maxVal%", DistanceGreen, Modifier.weight(1f))
            }
        }
    }
}

/**
 * Body Battery Card (`QBodyBatteryBarChartView.smali`):
 * - Daily 24-hour / Weekly / Monthly sky-blue (`#1296DB`) bar chart on a 0..100 scale with
 *   Peak, Charged (+Y), and Consumed (-X) summary metrics.
 */
@Composable
fun BodyBatteryChartCard(
    points: List<BodyBatteryPoint>,
    period: HistoryPeriod = HistoryPeriod.DAY,
    modifier: Modifier = Modifier,
) {
    val sorted = remember(points) { points.sortedBy { it.epochHour } }
    var selectedIdx by remember(sorted) { mutableIntStateOf(sorted.lastIndex.coerceAtLeast(0)) }

    ChartCard(
        title = "Body Battery",
        summary = if (sorted.isEmpty()) "Derived wellness score (0–100)" else "Latest ${sorted.last().score}/100  •  Peak ${sorted.maxOf { it.score }}/100",
        accentColor = BodyBatteryBlue,
        modifier = modifier,
    ) {
        if (sorted.isEmpty()) {
            EmptyChartText()
        } else {
            val selected = sorted.getOrNull(selectedIdx) ?: sorted.last()
            val selectedMs = selected.epochHour * 3_600_000L
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        text = "${selected.score} / 100",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = BodyBatteryBlue,
                    )
                    Text(
                        text = "${formatEpochDate(selectedMs)} ${formatEpochTime(selectedMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            InteractiveBarChart(
                values = sorted.map { it.score },
                selectedIndex = selectedIdx,
                onSelectIndex = { selectedIdx = it },
                barColor = BodyBatteryBlue,
                fixedMax = 100,
                height = 145.dp,
                formatYLabel = { it.toString() },
            )

            Spacer(Modifier.height(10.dp))
            var charged = 0
            var consumed = 0
            for (i in 1 until sorted.size) {
                val diff = sorted[i].score - sorted[i - 1].score
                if (diff > 0) charged += diff else consumed += -diff
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                StatMiniBox("Peak", "${sorted.maxOf { it.score }}", BodyBatteryBlue, Modifier.weight(1f))
                StatMiniBox("Charged", "+$charged", DistanceGreen, Modifier.weight(1f))
                StatMiniBox("Consumed", "-$consumed", CalorieCrimson, Modifier.weight(1f))
            }
        }
    }
}

/** Shared interactive vertical rounded-bar chart with dashed gridlines, Y labels, and touch scrubber. */
@Composable
private fun InteractiveBarChart(
    values: List<Int>,
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    barColor: Color,
    goalLine: Int? = null,
    fixedMax: Int? = null,
    height: Dp,
    formatYLabel: (Int) -> String,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val maxV = remember(values, fixedMax, goalLine) {
        fixedMax ?: maxOf(values.maxOrNull() ?: 1, goalLine ?: 1, 100)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        if (values.isNotEmpty()) {
                            val idx = ((offset.x / size.width.coerceAtLeast(1)) * values.size)
                                .toInt()
                                .coerceIn(0, values.lastIndex)
                            onSelectIndex(idx)
                        }
                    }
                }
                .pointerInput(values) {
                    detectHorizontalDragGestures { change, _ ->
                        if (values.isNotEmpty()) {
                            val idx = ((change.position.x / size.width.coerceAtLeast(1)) * values.size)
                                .toInt()
                                .coerceIn(0, values.lastIndex)
                            onSelectIndex(idx)
                        }
                    }
                },
        ) {
            if (values.isEmpty()) return@Canvas
            listOf(1f / 3f, 2f / 3f, 1f).forEach { frac ->
                val y = size.height * (1f - frac)
                drawLine(
                    color = gridColor.copy(alpha = 0.45f),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    pathEffect = DashedGridEffect,
                )
            }

            goalLine?.let { goal ->
                if (goal in 1..maxV) {
                    val goalY = size.height * (1f - goal.toFloat() / maxV)
                    drawLine(
                        color = DistanceGreen.copy(alpha = 0.7f),
                        start = Offset(0f, goalY),
                        end = Offset(size.width, goalY),
                        strokeWidth = 2f,
                        pathEffect = DashedGridEffect,
                    )
                }
            }

            val slot = size.width / values.size
            val barW = (slot * 0.58f).coerceIn(4f, 34f)
            values.forEachIndexed { i, v ->
                val h = if (v <= 0) 3f else (v.toFloat() / maxV * size.height).coerceIn(4f, size.height)
                val left = i * slot + (slot - barW) / 2f
                val isSelected = i == selectedIndex
                drawRoundRect(
                    color = if (isSelected) barColor else barColor.copy(alpha = if (v > 0) 0.65f else 0.18f),
                    topLeft = Offset(left, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                )
                if (isSelected) {
                    val cx = left + barW / 2f
                    drawLine(
                        color = barColor.copy(alpha = 0.55f),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height - h),
                        strokeWidth = 2f,
                        pathEffect = DashedGridEffect,
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .height(height)
                .padding(end = 2.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(formatYLabel(maxV), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(formatYLabel(maxV * 2 / 3), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(formatYLabel(maxV / 3), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text("0", style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

@Composable
private fun XAxisDateRow(epochDays: List<Long>) {
    if (epochDays.isEmpty()) return
    val indices = when {
        epochDays.size <= 7 -> epochDays.indices.toList()
        else -> listOf(0, epochDays.size / 4, epochDays.size / 2, (epochDays.size * 3) / 4, epochDays.lastIndex).distinct()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        indices.forEach { idx ->
            Text(
                text = formatEpochDay(epochDays[idx]),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatMiniBox(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A card shell shared by every chart: colored accent pill, title, summary, then the plot. */
@Composable
private fun ChartCard(
    title: String,
    summary: String?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyChartText() {
    Text(
        text = "No data in this range. Pull down to sync with your ring.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NightBands(sessions: List<SleepSession>, height: Dp) {
    val bandColor = SleepDeepViolet.copy(alpha = 0.8f)
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        if (sessions.isEmpty()) return@Canvas
        val rowH = size.height / sessions.size
        val bandH = (rowH * 0.55f).coerceAtLeast(4f)
        sessions.forEachIndexed { i, s ->
            val startH = minutesOfDay(s.startEpoch) / 60f
            var endH = minutesOfDay(s.endEpoch) / 60f
            if (endH <= startH) endH += 24f
            val x0 = (startH / 24f * size.width).coerceIn(0f, size.width)
            val x1 = (endH / 24f * size.width).coerceIn(0f, size.width)
            val top = i * rowH + (rowH - bandH) / 2f
            drawRoundRect(
                color = bandColor,
                topLeft = Offset(x0, top),
                size = Size((x1 - x0).coerceAtLeast(4f), bandH),
                cornerRadius = CornerRadius(bandH / 2f, bandH / 2f),
            )
        }
    }
}

private const val MinutesInDay = 1440L
private const val EpochMsPerMinute = 60_000L

private fun minutesOfDay(epochMs: Long): Long = (epochMs / EpochMsPerMinute) % MinutesInDay

private fun formatEpochDay(epochDay: Long): String =
    runCatching { LocalDate.ofEpochDay(epochDay).format(ShortDateFmt) }.getOrDefault("")

private fun formatEpochDate(epochMs: Long): String =
    runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(ShortDateFmt)
    }.getOrDefault("")

private fun formatEpochTime(epochMs: Long): String =
    runCatching {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime().format(TimeFmt)
    }.getOrDefault("")

private fun formatDistance(meters: Int, useMetric: Boolean): String =
    if (useMetric) {
        String.format(Locale.US, "%.2f km", meters / 1000f)
    } else {
        String.format(Locale.US, "%.2f mi", meters / 1609.34f)
    }

private fun stepsSummary(days: List<StepDay>, useMetric: Boolean): String? =
    if (days.isEmpty()) null
    else {
        val total = days.sumOf { it.steps }
        val dist = days.sumOf { it.distanceMeters }
        "Total $total steps  •  Avg ${total / days.size}/day  •  ${formatDistance(dist, useMetric)}"
    }

private fun bpSummary(sorted: List<BloodPressure>): String? =
    if (sorted.isEmpty()) null
    else "Latest ${sorted.last().sbp}/${sorted.last().dbp} mmHg  •  ${sorted.size} reading(s)"

private fun sleepSummary(sessions: List<SleepSession>): String? =
    if (sessions.isEmpty()) null
    else {
        val avgMin = sessions.map { (it.endEpoch - it.startEpoch) / 60_000L }.average().toInt()
        "${sessions.size} night(s)  •  Avg ${avgMin / 60}h ${avgMin % 60}m"
    }

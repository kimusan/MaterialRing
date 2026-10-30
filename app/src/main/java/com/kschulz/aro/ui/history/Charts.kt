package com.kschulz.aro.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kschulz.aro.data.model.BloodPressure
import com.kschulz.aro.data.model.HeartRateSample
import com.kschulz.aro.data.model.SleepSession
import com.kschulz.aro.data.model.StepDay

/**
 * Hand-rolled Compose Canvas charts for the History screen. No charting dependency:
 * the geometry here is simple enough to draw directly, and it keeps the app free of a
 * third-party surface (a sleep stage-hypnogram in particular has no first-class type in
 * the common chart libraries).
 */

private const val MinutesInDay = 1440L
private const val EpochMsPerMinute = 60_000L

/** A card shell shared by every chart: title, a one-line summary, then the plot. */
@Composable
private fun ChartCard(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun EmptyChartText() {
    Text("No data in this range.", style = MaterialTheme.typography.bodySmall)
}

/** Vertical bar chart of a per-day value series (steps). */
@Composable
private fun BarChart(values: List<Int>, height: Dp) {
    val barColor = MaterialTheme.colorScheme.primary
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val maxV = (values.maxOrNull() ?: 1).coerceAtLeast(1)
        val gap = 4f
        val slot = size.width / values.size
        val barW = (slot - gap).coerceAtLeast(1f)
        values.forEachIndexed { i, v ->
            val h = if (v <= 0) 0f else (v.toFloat() / maxV * size.height).coerceIn(0f, size.height)
            val left = i * slot + gap / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 3f, barW / 3f),
            )
        }
    }
}

/** A single polyline across evenly-spaced points, optional area fill. */
@Composable
private fun LineChart(values: List<Float>, height: Dp, color: androidx.compose.ui.graphics.Color, fill: Boolean = false) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        if (values.size < 2) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val pad = 6f
        val usableW = size.width - pad * 2
        val usableH = size.height - pad * 2
        val xs = FloatArray(values.size) { i -> pad + i * usableW / (values.size - 1) }
        val ys = FloatArray(values.size) { i -> pad + usableH - (values[i] - lo) / span * usableH }
        val line = Path()
        for (i in xs.indices) {
            if (i == 0) line.moveTo(xs[i], ys[i]) else line.lineTo(xs[i], ys[i])
        }
        if (fill) {
            val area = Path().apply {
                addPath(line)
                lineTo(xs.last(), size.height)
                lineTo(xs.first(), size.height)
                close()
            }
            drawPath(area, color.copy(alpha = 0.12f))
        }
        drawPath(line, color = color, style = Stroke(width = 2.5f))
    }
}

@Composable
fun StepsChartCard(days: List<StepDay>, modifier: Modifier = Modifier) {
    ChartCard("Steps", stepsSummary(days), modifier) {
        if (days.isEmpty()) EmptyChartText()
        else BarChart(days.sortedBy { it.date }.map { it.steps }, 140.dp)
    }
}

@Composable
fun HeartRateChartCard(samples: List<HeartRateSample>, modifier: Modifier = Modifier) {
    val byDay = samples.groupBy { it.epochMs / 86_400_000L }.toSortedMap()
    val avg = byDay.map { (_, list) -> list.map { it.bpm }.average().toFloat() }
    ChartCard("Heart rate (avg per day)", heartRateSummary(avg), modifier) {
        if (avg.size < 2) EmptyChartText()
        else LineChart(avg, 140.dp, MaterialTheme.colorScheme.tertiary, fill = true)
    }
}

@Composable
fun BloodPressureChartCard(readings: List<BloodPressure>, modifier: Modifier = Modifier) {
    val sorted = readings.sortedBy { it.epochMs }
    ChartCard("Blood pressure", bpSummary(sorted), modifier) {
        if (sorted.size < 2) {
            EmptyChartText()
        } else {
            Column {
                LineChart(sorted.map { it.sbp.toFloat() }, 110.dp, MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                LineChart(sorted.map { it.dbp.toFloat() }, 110.dp, MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

/**
 * Sleep pattern: one horizontal band per night, placed on a 24h time-of-day axis. The band
 * spans that night's start to end; a night that crosses midnight wraps past 24:00.
 */
@Composable
fun SleepPatternCard(sessions: List<SleepSession>, modifier: Modifier = Modifier) {
    ChartCard("Sleep (night window)", sleepSummary(sessions), modifier) {
        if (sessions.isEmpty()) {
            EmptyChartText()
        } else {
            Column {
                NightBands(sessions.sortedBy { it.startEpoch }, 160.dp)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("00:00", "06:00", "12:00", "18:00", "24:00")
                        .forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

@Composable
private fun NightBands(sessions: List<SleepSession>, height: Dp) {
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        if (sessions.isEmpty()) return@Canvas
        val rowH = size.height / sessions.size
        val bandH = (rowH * 0.55f).coerceAtLeast(4f)
        sessions.forEachIndexed { i, s ->
            val startH = minutesOfDay(s.startEpoch) / 60f
            var endH = minutesOfDay(s.endEpoch) / 60f
            if (endH <= startH) endH += 24f // night crosses midnight
            val x0 = (startH / 24f * size.width).coerceIn(0f, size.width)
            val x1 = (endH / 24f * size.width).coerceIn(0f, size.width)
            val top = i * rowH + (rowH - bandH) / 2f
            drawRoundRect(
                color = bandColor,
                topLeft = Offset(x0, top),
                size = Size((x1 - x0).coerceAtLeast(0f), bandH),
                cornerRadius = CornerRadius(bandH / 2f, bandH / 2f),
            )
        }
    }
}

private fun minutesOfDay(epochMs: Long): Long = (epochMs / EpochMsPerMinute) % MinutesInDay

private fun stepsSummary(days: List<StepDay>): String? =
    if (days.isEmpty()) null
    else {
        val total = days.sumOf { it.steps }
        "total $total · avg ${total / days.size}/day"
    }

private fun heartRateSummary(avg: List<Float>): String? =
    if (avg.isEmpty()) null else "${avg.min().toInt()}-${avg.max().toInt()} bpm"

private fun bpSummary(sorted: List<BloodPressure>): String? =
    if (sorted.isEmpty()) null
    else "last ${sorted.last().sbp}/${sorted.last().dbp} mmHg · ${sorted.size} readings"

private fun sleepSummary(sessions: List<SleepSession>): String? =
    if (sessions.isEmpty()) null
    else {
        val avgMin = sessions.map { (it.endEpoch - it.startEpoch) / 60_000L }.average().toInt()
        "${sessions.size} nights · avg ${avgMin / 60}h ${avgMin % 60}m"
    }

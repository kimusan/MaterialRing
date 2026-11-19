package com.kschulz.aro.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.kschulz.aro.R
import com.kschulz.aro.data.model.SleepSegment
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Renders crisp, system-theme-adaptive chart Bitmaps for Aro Android home screen widgets.
 * Colors are read dynamically from `widget_colors.xml` (Day / Night / Material You).
 */
object WidgetChartRenderer {

    fun renderWeeklyStepsChart(
        context: Context,
        days: List<Pair<Long, Int>>,
        goalSteps: Int,
        widthPx: Int = 640,
        heightPx: Int = 220,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val accentColor = ContextCompat.getColor(context, R.color.widget_accent_steps)
        val mutedBarColor = applyAlpha(accentColor, 0.42f)
        val textColor = ContextCompat.getColor(context, R.color.widget_text_secondary)
        val gridColor = ContextCompat.getColor(context, R.color.widget_grid)

        val padLeft = 16f
        val padRight = 16f
        val padTop = 18f
        val padBottom = 36f
        val chartW = widthPx - padLeft - padRight
        val chartH = heightPx - padTop - padBottom

        val maxVal = maxOf(goalSteps, days.maxOfOrNull { it.second } ?: 0, 1000).toFloat()

        // Horizontal baseline & goal dashed line
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(padLeft, padTop + chartH, padLeft + chartW, padTop + chartH, gridPaint)

        val goalY = padTop + chartH * (1f - (goalSteps / maxVal).coerceIn(0f, 1f))
        val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyAlpha(accentColor, 0.65f)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        }
        canvas.drawLine(padLeft, goalY, padLeft + chartW, goalY, goalPaint)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }

        val count = days.size.coerceAtLeast(1)
        val slotW = chartW / count
        val barW = (slotW * 0.52f).coerceAtMost(48f)
        val corner = barW * 0.3f

        days.forEachIndexed { idx, (epochDay, steps) ->
            val cx = padLeft + slotW * idx + slotW / 2f
            val frac = (steps / maxVal).coerceIn(0f, 1f)
            val barH = (chartH * frac).coerceAtLeast(if (steps > 0) 8f else 4f)
            val top = padTop + chartH - barH
            val rect = RectF(cx - barW / 2f, top, cx + barW / 2f, padTop + chartH)

            val isToday = idx == days.lastIndex
            barPaint.color = if (isToday || steps >= goalSteps) accentColor else mutedBarColor
            canvas.drawRoundRect(rect, corner, corner, barPaint)

            val dow = LocalDate.ofEpochDay(epochDay).dayOfWeek
                .getDisplayName(TextStyle.SHORT, Locale.getDefault())
                .take(2)
            labelPaint.isFakeBoldText = isToday
            canvas.drawText(dow, cx, heightPx - 8f, labelPaint)
        }

        return bmp
    }

    fun renderMonthlyStepsChart(
        context: Context,
        days: List<Pair<Long, Int>>,
        goalSteps: Int,
        widthPx: Int = 640,
        heightPx: Int = 220,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val accentColor = ContextCompat.getColor(context, R.color.widget_accent_steps)
        val mutedBarColor = applyAlpha(accentColor, 0.45f)
        val textColor = ContextCompat.getColor(context, R.color.widget_text_secondary)
        val gridColor = ContextCompat.getColor(context, R.color.widget_grid)

        val padLeft = 12f
        val padRight = 12f
        val padTop = 14f
        val padBottom = 34f
        val chartW = widthPx - padLeft - padRight
        val chartH = heightPx - padTop - padBottom

        val maxVal = maxOf(goalSteps, days.maxOfOrNull { it.second } ?: 0, 1000).toFloat()

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gridColor
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(padLeft, padTop + chartH, padLeft + chartW, padTop + chartH, gridPaint)

        val goalY = padTop + chartH * (1f - (goalSteps / maxVal).coerceIn(0f, 1f))
        val goalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = applyAlpha(accentColor, 0.55f)
            strokeWidth = 2f
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
        }
        canvas.drawLine(padLeft, goalY, padLeft + chartW, goalY, goalPaint)

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val count = days.size.coerceAtLeast(1)
        val slotW = chartW / count
        val barW = (slotW * 0.65f).coerceAtLeast(4f)
        val corner = barW * 0.35f

        days.forEachIndexed { idx, (_, steps) ->
            val cx = padLeft + slotW * idx + slotW / 2f
            val frac = (steps / maxVal).coerceIn(0f, 1f)
            val barH = (chartH * frac).coerceAtLeast(if (steps > 0) 6f else 3f)
            val rect = RectF(cx - barW / 2f, padTop + chartH - barH, cx + barW / 2f, padTop + chartH)
            barPaint.color = if (idx == days.lastIndex || steps >= goalSteps) accentColor else mutedBarColor
            canvas.drawRoundRect(rect, corner, corner, barPaint)
        }

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 20f
        }
        if (days.isNotEmpty()) {
            val firstDate = LocalDate.ofEpochDay(days.first().first)
            val midDate = LocalDate.ofEpochDay(days[days.size / 2].first)
            val lastDate = LocalDate.ofEpochDay(days.last().first)

            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${firstDate.dayOfMonth}/${firstDate.monthValue}", padLeft, heightPx - 8f, labelPaint)
            labelPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("${midDate.dayOfMonth}/${midDate.monthValue}", padLeft + chartW / 2f, heightPx - 8f, labelPaint)
            labelPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("${lastDate.dayOfMonth}/${lastDate.monthValue}", padLeft + chartW, heightPx - 8f, labelPaint)
        }

        return bmp
    }

    fun renderSleepStageBar(
        context: Context,
        segments: List<SleepSegment>,
        widthPx: Int = 600,
        heightPx: Int = 36,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        val totalMin = segments.sumOf { it.durationMin }.coerceAtLeast(0)
        if (totalMin <= 0) {
            paint.color = ContextCompat.getColor(context, R.color.widget_grid)
            canvas.drawRoundRect(RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()), 12f, 12f, paint)
            return bmp
        }

        var x = 0f
        for (seg in segments) {
            if (seg.durationMin <= 0) continue
            val w = (seg.durationMin.toFloat() / totalMin) * widthPx
            paint.color = when (seg.stage) {
                1 -> Color.parseColor("#3F51B5") // Deep
                2 -> Color.parseColor("#7E57C2") // Light
                3 -> Color.parseColor("#26C6DA") // REM
                else -> Color.parseColor("#FFA726") // Awake
            }
            canvas.drawRect(x, 0f, x + w, heightPx.toFloat(), paint)
            x += w
        }
        return bmp
    }

    private fun applyAlpha(color: Int, factor: Float): Int {
        val alpha = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}

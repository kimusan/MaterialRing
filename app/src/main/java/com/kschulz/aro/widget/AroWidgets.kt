package com.kschulz.aro.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.kschulz.aro.MainActivity
import com.kschulz.aro.R
import java.text.NumberFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Helper to refresh all active Aro home screen widgets after a ring sync or live measurement.
 */
object AroWidgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        scope.launch {
            val snapshot = runCatching { WidgetDataLoader.load(appContext) }.getOrNull() ?: return@launch
            updateProvider(appContext, manager, TodaySummaryWidgetProvider::class.java) {
                buildTodayViews(appContext, snapshot)
            }
            updateProvider(appContext, manager, WeeklyStepsWidgetProvider::class.java) {
                buildWeeklyViews(appContext, snapshot)
            }
            updateProvider(appContext, manager, VitalsSleepWidgetProvider::class.java) {
                buildVitalsSleepViews(appContext, snapshot)
            }
            updateProvider(appContext, manager, MonthlyTrendWidgetProvider::class.java) {
                buildMonthlyViews(appContext, snapshot)
            }
        }
    }

    internal fun launchAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        cls: Class<out AppWidgetProvider>,
        buildViews: () -> RemoteViews,
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, cls))
        if (ids.isEmpty()) return
        val views = buildViews()
        for (id in ids) {
            manager.updateAppWidget(id, views)
        }
    }

    internal fun buildTodayViews(context: Context, s: WidgetSnapshot): RemoteViews {
        val nf = NumberFormat.getIntegerInstance()
        return RemoteViews(context.packageName, R.layout.widget_today_summary).apply {
            setOnClickPendingIntent(R.id.widget_root, launchAppPendingIntent(context))
            setTextViewText(R.id.widget_title, "Aro • Today")
            val pct = ((s.todaySteps * 100) / s.goalSteps.coerceAtLeast(1)).coerceIn(0, 999)
            setTextViewText(R.id.widget_subtitle, "$pct% goal")
            setTextViewText(R.id.tv_steps, nf.format(s.todaySteps))
            setTextViewText(R.id.tv_hr, s.latestHrBpm?.let { "$it bpm" } ?: "—")
            setTextViewText(R.id.tv_spo2, s.latestSpo2?.let { "${it.percent}%" } ?: "—")
            setTextViewText(R.id.tv_battery, s.latestBodyBattery?.let { "$it/100" } ?: "—")
        }
    }

    internal fun buildWeeklyViews(context: Context, s: WidgetSnapshot): RemoteViews {
        val nf = NumberFormat.getIntegerInstance()
        val total = s.weeklySteps.sumOf { it.second }
        val avg = total / 7
        val chart = WidgetChartRenderer.renderWeeklyStepsChart(context, s.weeklySteps, s.goalSteps)
        return RemoteViews(context.packageName, R.layout.widget_weekly_steps).apply {
            setOnClickPendingIntent(R.id.widget_root, launchAppPendingIntent(context))
            setTextViewText(R.id.tv_weekly_summary, "${nf.format(total)} steps")
            setImageViewBitmap(R.id.iv_weekly_chart, chart)
            setTextViewText(
                R.id.tv_weekly_footer,
                "Daily avg ${nf.format(avg)}  •  Goal ${nf.format(s.goalSteps)}",
            )
        }
    }

    internal fun buildVitalsSleepViews(context: Context, s: WidgetSnapshot): RemoteViews {
        val sleepMin = s.latestSleep?.let { ((it.endEpoch - it.startEpoch) / 60_000L).toInt() } ?: 0
        val sleepText = if (sleepMin > 0) "${sleepMin / 60}h ${sleepMin % 60}m" else "No data"
        val deep = s.latestSleepSegments.filter { it.stage == 1 }.sumOf { it.durationMin }
        val light = s.latestSleepSegments.filter { it.stage == 2 }.sumOf { it.durationMin }
        val rem = s.latestSleepSegments.filter { it.stage == 3 }.sumOf { it.durationMin }
        val breakdown = if (sleepMin > 0) {
            "Deep ${deep}m  •  Light ${light}m  •  REM ${rem}m"
        } else {
            "Wear ring overnight to track sleep stages"
        }
        val bar = WidgetChartRenderer.renderSleepStageBar(context, s.latestSleepSegments)
        return RemoteViews(context.packageName, R.layout.widget_vitals_sleep).apply {
            setOnClickPendingIntent(R.id.widget_root, launchAppPendingIntent(context))
            setTextViewText(R.id.tv_sleep_duration, sleepText)
            setImageViewBitmap(R.id.iv_sleep_bar, bar)
            setTextViewText(R.id.tv_sleep_breakdown, breakdown)
            setTextViewText(R.id.tv_vitals_hr, s.latestHrBpm?.let { "$it bpm" } ?: "—")
            setTextViewText(R.id.tv_vitals_bp, s.latestBp?.let { "${it.sbp}/${it.dbp}" } ?: "—")
            setTextViewText(R.id.tv_vitals_spo2, s.latestSpo2?.let { "${it.percent}%" } ?: "—")
        }
    }

    internal fun buildMonthlyViews(context: Context, s: WidgetSnapshot): RemoteViews {
        val nf = NumberFormat.getIntegerInstance()
        val total = s.monthlySteps.sumOf { it.second }
        val activeDays = s.monthlySteps.count { it.second > 0 }.coerceAtLeast(1)
        val avg = total / activeDays
        val best = s.monthlySteps.maxOfOrNull { it.second } ?: 0
        val chart = WidgetChartRenderer.renderMonthlyStepsChart(context, s.monthlySteps, s.goalSteps)
        return RemoteViews(context.packageName, R.layout.widget_monthly_trend).apply {
            setOnClickPendingIntent(R.id.widget_root, launchAppPendingIntent(context))
            setTextViewText(R.id.tv_monthly_summary, "${nf.format(total)} steps")
            setImageViewBitmap(R.id.iv_monthly_chart, chart)
            setTextViewText(
                R.id.tv_monthly_footer,
                "Active avg ${nf.format(avg)}/day  •  Best ${nf.format(best)}",
            )
        }
    }
}

abstract class BaseAroWidgetProvider : AppWidgetProvider() {
    protected abstract fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val snapshot = WidgetDataLoader.load(context)
                val views = buildViews(context, snapshot)
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

class TodaySummaryWidgetProvider : BaseAroWidgetProvider() {
    override fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        AroWidgets.buildTodayViews(context, snapshot)
}

class WeeklyStepsWidgetProvider : BaseAroWidgetProvider() {
    override fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        AroWidgets.buildWeeklyViews(context, snapshot)
}

class VitalsSleepWidgetProvider : BaseAroWidgetProvider() {
    override fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        AroWidgets.buildVitalsSleepViews(context, snapshot)
}

class MonthlyTrendWidgetProvider : BaseAroWidgetProvider() {
    override fun buildViews(context: Context, snapshot: WidgetSnapshot): RemoteViews =
        AroWidgets.buildMonthlyViews(context, snapshot)
}

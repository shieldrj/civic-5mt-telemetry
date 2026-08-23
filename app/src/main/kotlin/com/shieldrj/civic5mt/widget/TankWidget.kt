package com.shieldrj.civic5mt.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import com.shieldrj.civic5mt.R
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.service.loadWidgetSnapshot
import com.shieldrj.civic5mt.service.saveWidgetSnapshot

/**
 * The home-screen widget, and why it reads like the HUD: same question, same answer.
 *
 * A widget lives on the launcher long after the engine is off, so it cannot read
 * [com.shieldrj.civic5mt.service.TelemetryState] - that only holds values while a drive is
 * live. Instead the service pushes figures here every thirty seconds while driving, and those
 * are also written to preferences so [TankWidgetProvider] has something honest to show when
 * the phone has rebooted or the app process is long gone. Stale-but-real beats fresh-but-fake;
 * there is deliberately no timer refreshing these into fabrication.
 */
object TankWidget {

    /** Called from the service's tick loop, throttled to once per [PUSH_INTERVAL_MS]. */
    fun update(context: Context, metrics: LiveMetrics) {
        saveWidgetSnapshot(context, metrics.tankMpg, metrics.fuelRangeMiles)
        push(context, metrics.tankMpg, metrics.fuelRangeMiles)
    }

    /** What the provider shows when Android asks for a redraw with nothing live. */
    fun refreshFromSaved(context: Context) {
        val (tankMpg, rangeMiles) = loadWidgetSnapshot(context)
        push(context, tankMpg, rangeMiles)
    }

    private fun push(context: Context, tankMpg: Double?, rangeMiles: Int?) {
        val views = RemoteViews(context.packageName, R.layout.tank_widget).apply {
            setTextViewText(
                R.id.widget_tank_mpg,
                tankMpg?.let { "%.1f".format(it) } ?: "—",
            )
            setTextViewText(
                R.id.widget_detail,
                when {
                    tankMpg == null -> "MPG · waiting for the car"
                    else -> "MPG · " + (rangeMiles?.toString() ?: "?") + " mi to empty"
                },
            )
        }
        AppWidgetManager.getInstance(context).updateAppWidget(
            ComponentName(context, TankWidgetProvider::class.java),
            views,
        )
    }
}

/** Redraws from the saved snapshot whenever the launcher asks. */
class TankWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        TankWidget.refreshFromSaved(context)
    }
}

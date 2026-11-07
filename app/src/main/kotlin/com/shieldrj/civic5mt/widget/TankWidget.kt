package com.shieldrj.civic5mt.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.shieldrj.civic5mt.R
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.WidgetSnapshot
import com.shieldrj.civic5mt.service.loadWidgetSnapshot
import com.shieldrj.civic5mt.service.saveWidgetSnapshot
import com.shieldrj.civic5mt.ui.MainActivity

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
        val snapshot = WidgetSnapshot(
            tankMpg = metrics.tankMpg,
            rangeMiles = metrics.fuelRangeMiles,
            rangeIsCeiling = metrics.tankBelowSenderZero,
            rangeToGaugeEmptyMiles = metrics.fuelRangeToSenderZeroMiles,
        )
        saveWidgetSnapshot(context, snapshot)
        push(context, snapshot)
    }

    /** What the provider shows when Android asks for a redraw with nothing live. */
    fun refreshFromSaved(context: Context) {
        push(context, loadWidgetSnapshot(context))
    }

    private fun push(context: Context, snapshot: WidgetSnapshot) {
        val tankMpg = snapshot.tankMpg
        val rangeMiles = snapshot.rangeMiles
        val toGauge = snapshot.rangeToGaugeEmptyMiles
        val views = RemoteViews(context.packageName, R.layout.tank_widget).apply {
            setTextViewText(
                R.id.widget_tank_mpg,
                tankMpg?.let { "%.1f".format(it) } ?: "—",
            )
            setTextViewText(
                R.id.widget_detail,
                when {
                    tankMpg == null -> "MPG · waiting for the car"
                    rangeMiles == null -> "MPG · ? mi to empty"
                    snapshot.rangeIsCeiling -> "MPG · about $rangeMiles mi dry"
                    toGauge != null && toGauge > 0 && toGauge != rangeMiles ->
                        "MPG · $toGauge mi to E · $rangeMiles dry"
                    toGauge != null && toGauge <= 0 ->
                        "MPG · on E · $rangeMiles mi dry"
                    else -> "MPG · $rangeMiles mi to empty"
                },
            )
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(TelemetryService.EXTRA_OPEN_SCREEN, TelemetryService.SCREEN_FUEL)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            setOnClickPendingIntent(R.id.widget_container, pendingIntent)
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

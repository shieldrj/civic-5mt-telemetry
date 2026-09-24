package com.shieldrj.civic5mt.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.shieldrj.civic5mt.core.ConnectionStatus

/** Google Maps, the one app the heads-up display is designed to sit over. */
internal const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

/**
 * Whether the heads-up display should be on screen.
 *
 * Kept apart from the service so every combination can be tested on the JVM. The first three
 * conditions are the old ones: the driver wants it, the drive has not ended, and something is
 * connected. The fourth is new - only while Google Maps is the app in front - and it only
 * applies when Android has let this app see which app that is. Without that access the card
 * behaves as it always did rather than disappearing for a reason nobody can see.
 */
internal fun shouldShowHud(
    enabled: Boolean,
    parked: Boolean,
    connection: ConnectionStatus,
    mapsOnly: Boolean,
    canSeeForeground: Boolean,
    foregroundPackage: String?,
): Boolean {
    if (!enabled || parked) return false
    if (connection != ConnectionStatus.CONNECTED && connection != ConnectionStatus.SIMULATING) return false
    if (!mapsOnly || !canSeeForeground) return true
    return foregroundPackage == GOOGLE_MAPS_PACKAGE
}

/**
 * Folds Android's activity events into the app now in front, oldest event first.
 *
 * A resume puts that app in front. A pause takes it out only if it is still the one in front,
 * because events from two apps interleave during a switch: the old one pausing can arrive
 * after the new one resuming. Maps shrinking to picture-in-picture pauses it, and so does the
 * screen going off, and both correctly take the card away.
 */
internal fun foldForeground(current: String?, events: List<Pair<Int, String>>): String? {
    var front = current
    for ((type, pkg) in events) {
        when (type) {
            UsageEvents.Event.ACTIVITY_RESUMED -> front = pkg
            UsageEvents.Event.ACTIVITY_PAUSED -> if (pkg == front) front = null
        }
    }
    return front
}

/**
 * Which app is in front, read from Android's usage events.
 *
 * Usage access rather than an accessibility service, because it is the narrower permission:
 * it tells this app which app is open and when, and nothing about what is on screen or typed.
 * It is granted on a Settings screen, like the overlay permission.
 */
internal class ForegroundAppWatcher(private val context: Context) {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private var queriedTo = 0L
    private var front: String? = null

    fun hasAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * The package in front now. Reads only the events since the last call, so polling it
     * every second costs next to nothing; the first call looks back far enough to catch a
     * Maps session that was opened before the car connected.
     */
    fun poll(nowMillis: Long = System.currentTimeMillis()): String? {
        val from = if (queriedTo == 0L) nowMillis - FIRST_LOOKBACK_MS else queriedTo
        val events = runCatching { usage?.queryEvents(from, nowMillis) }.getOrNull() ?: return front
        val batch = ArrayList<Pair<Int, String>>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            batch += e.eventType to e.packageName
        }
        front = foldForeground(front, batch)
        queriedTo = nowMillis
        return front
    }

    companion object {
        private const val FIRST_LOOKBACK_MS = 8 * 60 * 60 * 1000L
    }
}

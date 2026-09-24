package com.shieldrj.civic5mt.service

import android.app.usage.UsageEvents
import com.shieldrj.civic5mt.core.ConnectionStatus
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the heads-up display is on screen, and which app Android says is in front.
 *
 * The rule that matters most is the fallback: without usage access the bubble must behave as
 * it always did, not vanish for a reason the driver has no way to see.
 */
class HudVisibilityTest {

    private val maps = GOOGLE_MAPS_PACKAGE
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    private fun show(
        enabled: Boolean = true,
        parked: Boolean = false,
        connection: ConnectionStatus = ConnectionStatus.CONNECTED,
        mapsOnly: Boolean = true,
        canSee: Boolean = true,
        front: String? = maps,
    ) = shouldShowHud(enabled, parked, connection, mapsOnly, canSee, front)

    @Test
    @DisplayName("shows over Google Maps during a connected drive")
    fun overMaps() {
        assertTrue(show())
    }

    @Test
    @DisplayName("hides when anything else is in front")
    fun overAnythingElse() {
        assertFalse(show(front = "com.android.chrome"))
        assertFalse(show(front = "com.shieldrj.civic5mt.dev"))
        assertFalse(show(front = null), "screen off, or Maps shrunk to picture-in-picture")
    }

    @Test
    @DisplayName("shows over any app when Maps-only is switched off")
    fun mapsOnlyOff() {
        assertTrue(show(mapsOnly = false, front = "com.android.chrome"))
    }

    @Test
    @DisplayName("falls back to showing over any app without usage access")
    fun noAccessFallsBack() {
        assertTrue(show(canSee = false, front = null))
    }

    @Test
    @DisplayName("keeps the old rules: wanted, driving, connected")
    fun oldRulesStillApply() {
        assertFalse(show(enabled = false))
        assertFalse(show(parked = true))
        assertFalse(show(connection = ConnectionStatus.DISCONNECTED))
        assertTrue(show(connection = ConnectionStatus.SIMULATING))
    }

    @Test
    @DisplayName("follows a switch between apps, whichever order the events arrive in")
    fun foldsSwitches() {
        // Chrome to Maps, with Chrome's pause arriving after Maps' resume.
        assertEquals(maps, foldForeground("com.android.chrome", listOf(resumed to maps, paused to "com.android.chrome")))
        // Maps to Chrome, the ordinary order.
        assertEquals("com.android.chrome", foldForeground(maps, listOf(paused to maps, resumed to "com.android.chrome")))
    }

    @Test
    @DisplayName("treats Maps pausing with nothing after it as nothing in front")
    fun pauseClears() {
        assertNull(foldForeground(maps, listOf(paused to maps)))
    }

    @Test
    @DisplayName("keeps what it knew when no events arrived")
    fun noEventsKeepsState() {
        assertEquals(maps, foldForeground(maps, emptyList()))
    }

    @Test
    @DisplayName("ignores events that are not activity changes")
    fun ignoresOtherEvents() {
        val configChange = UsageEvents.Event.CONFIGURATION_CHANGE
        assertEquals(maps, foldForeground(maps, listOf(configChange to "android")))
    }
}

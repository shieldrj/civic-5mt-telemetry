package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Tying the oil record to the dashboard odometer")
class OilOdometerSyncTest {

    private fun engine() = OilLifeEngine(InMemoryOilProfileStore(), MutableClock(1_700_000_000_000))

    @Test
    fun `The odometer takes the car's figure and the interval is left alone`() {
        val engine = engine()
        val before = engine.getProfile()
        val milesBefore = before.currentOdometer - before.lastResetOdometer
        assertFalse(before.odometerSynced)

        engine.syncOdometer(197_214.0)

        val after = engine.getProfile()
        assertEquals(197_214.0, after.currentOdometer)
        assertTrue(after.odometerSynced)
        // Miles since the change are the watched ones. Counting the unwatched miles on top
        // would stretch the projection with wear nobody measured.
        assertEquals(milesBefore, after.currentOdometer - after.lastResetOdometer, 1e-9)
        assertEquals(before.estimatedMilesRemaining, after.estimatedMilesRemaining)
        assertEquals(before.oilLifePercent, after.oilLifePercent)
    }

    @Test
    fun `A reading that is not an odometer is ignored`() {
        val engine = engine()
        val before = engine.getProfile()
        engine.syncOdometer(Double.NaN)
        engine.syncOdometer(0.0)
        engine.syncOdometer(-5.0)
        engine.syncOdometer(Double.POSITIVE_INFINITY)
        assertEquals(before, engine.getProfile())
    }

    @Test
    fun `Resetting the oil keeps the odometer tied to the dashboard`() {
        val engine = engine()
        engine.syncOdometer(197_214.0)
        val reset = engine.resetOilLife()
        assertTrue(reset.odometerSynced)
        assertEquals(197_214.0, reset.currentOdometer)
        assertEquals(197_214.0, reset.lastResetOdometer)
    }
}

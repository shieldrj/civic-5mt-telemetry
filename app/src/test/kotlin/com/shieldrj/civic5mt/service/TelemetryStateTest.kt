package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.RawObdData
import com.shieldrj.civic5mt.core.ShiftMode
import com.shieldrj.civic5mt.core.TripAnalytics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The state everything reads, and the one rule inside it that is not a setter.
 *
 * Testable at all only because [TelemetryState] has no Android imports - it is a singleton of
 * StateFlows over `core` types, which is what lets a JVM test drive it directly. That is worth
 * noting rather than assuming: the moment something in here touches a Context, this file needs
 * an emulator.
 *
 * The rule worth pinning is [TelemetryState.reset]. It is called on every connect, including
 * the reconnect after a tunnel, and what it must NOT clear is the difference between one
 * commute and two.
 */
class TelemetryStateTest {

    /**
     * A singleton shared across tests in one JVM, so every test starts by putting it back.
     * Not fully - there is no public way to clear the DTC report or the connection - but the
     * fields each test reads are set explicitly by that test rather than inherited.
     */
    @BeforeEach
    fun resetSingleton() {
        TelemetryState.setConnection(ConnectionStatus.DISCONNECTED)
        TelemetryState.setData(RawObdData())
        TelemetryState.setMetrics(LiveMetrics())
        TelemetryState.setTrip(TripAnalytics())
        TelemetryState.setLifetime(LifetimeStats())
        TelemetryState.setFuelBlend(FuelBlendId.E10)
        TelemetryState.setHudTheme(HudTheme.SYSTEM)
        TelemetryState.setResolvedPids(ResolvedPids())
        if (TelemetryState.shiftMode.value != ShiftMode.ECO) TelemetryState.toggleShiftMode()
    }

    @Nested
    @DisplayName("reset(), which runs on every connect including a reconnect")
    inner class Reset {

        /**
         * The whole point of the method. A Bluetooth drop in a tunnel calls connect again, and
         * a reset that cleared the trip would turn one commute into two with the interesting
         * part missing from the gap between them.
         */
        @Test
        @DisplayName("leaves the trip, the lifetime record and the oil profile alone")
        fun keepsWhatTheDriveAccumulated() {
            val trip = TripAnalytics(distanceMiles = 23.4, totalFuelUsedGallons = 0.81)
            val lifetime = LifetimeStats(totalMiles = 14_000.0, totalFuelGallons = 480.0)
            TelemetryState.setTrip(trip)
            TelemetryState.setLifetime(lifetime)

            TelemetryState.reset()

            assertEquals(trip, TelemetryState.trip.value, "a tunnel does not end the drive")
            assertEquals(lifetime, TelemetryState.lifetime.value, "the record is permanent")
        }

        /**
         * The other half, and the reason it is not simply a no-op. Readings from the link that
         * just dropped must go: a gauge holding the speed the car was doing before the tunnel
         * is the failure that makes a driver stop trusting the whole app.
         */
        @Test
        @DisplayName("clears the last readings, so no gauge holds a stale value")
        fun clearsStaleReadings() {
            TelemetryState.setData(RawObdData(rpm = 3200.0, speedKmh = 104.0))
            TelemetryState.setMetrics(LiveMetrics(rpm = 3200.0, speedMph = 65.0))

            TelemetryState.reset()

            assertEquals(RawObdData(), TelemetryState.data.value)
            assertEquals(LiveMetrics(), TelemetryState.metrics.value)
        }

        /**
         * Which PID feeds which gauge is decided from the support bitmaps during the handshake,
         * so it belongs to a connection rather than to the car. Keeping the previous
         * connection's answers would have the discovery screen describing a link that is gone.
         */
        @Test
        @DisplayName("clears the resolved PIDs, which belong to one handshake")
        fun clearsResolvedPids() {
            TelemetryState.setResolvedPids(ResolvedPids(lambda = 0x34, outsideAir = 0x0F))

            TelemetryState.reset()

            assertEquals(ResolvedPids(), TelemetryState.resolvedPids.value)
        }
    }

    @Nested
    @DisplayName("The HUD theme")
    inner class HudThemeCycle {

        /**
         * Driven by a long-press on the card itself, which is the only control it has - so the
         * cycle has to return to where it started or a theme becomes unreachable by the only
         * gesture that can select it.
         */
        @Test
        @DisplayName("cycles light to dark to system and back, returning what it landed on")
        fun cyclesAndReturns() {
            TelemetryState.setHudTheme(HudTheme.LIGHT)

            assertEquals(HudTheme.DARK, TelemetryState.cycleHudTheme())
            assertEquals(HudTheme.SYSTEM, TelemetryState.cycleHudTheme())
            assertEquals(HudTheme.LIGHT, TelemetryState.cycleHudTheme())
        }

        /** The returned value is what the toast says; the flow is what the card draws. */
        @Test
        @DisplayName("returns the same theme it published to the flow")
        fun returnMatchesFlow() {
            TelemetryState.setHudTheme(HudTheme.LIGHT)

            val returned = TelemetryState.cycleHudTheme()

            assertEquals(returned, TelemetryState.hudTheme.value)
        }

        @Test
        @DisplayName("reaches every theme from every starting point")
        fun everyThemeReachable() {
            HudTheme.entries.forEach { start ->
                TelemetryState.setHudTheme(start)
                val seen = List(HudTheme.entries.size) { TelemetryState.cycleHudTheme() }.toSet()
                assertEquals(HudTheme.entries.toSet(), seen, "unreachable theme from $start")
            }
        }
    }

    @Nested
    @DisplayName("The shift mode")
    inner class Shift {

        @Test
        @DisplayName("toggles between eco and power and back")
        fun toggles() {
            assertEquals(ShiftMode.ECO, TelemetryState.shiftMode.value)

            TelemetryState.toggleShiftMode()
            assertEquals(ShiftMode.POWER, TelemetryState.shiftMode.value)

            TelemetryState.toggleShiftMode()
            assertEquals(ShiftMode.ECO, TelemetryState.shiftMode.value)
        }

        /**
         * The mode is a driver preference, not a reading, so a reconnect must not quietly put
         * the shift cues back to eco halfway through a spirited drive.
         */
        @Test
        @DisplayName("survives a reset")
        fun survivesReset() {
            TelemetryState.toggleShiftMode()
            val chosen = TelemetryState.shiftMode.value
            assertNotEquals(ShiftMode.ECO, chosen)

            TelemetryState.reset()

            assertEquals(chosen, TelemetryState.shiftMode.value)
        }
    }

    @Nested
    @DisplayName("The fuel blend")
    inner class Blend {

        /**
         * Set at a pump roughly once a year and read by the fuel model on the next tick. It is
         * the one preference the screen writes directly rather than routing through the
         * service, so the flow is the whole contract between them.
         */
        @Test
        @DisplayName("publishes what the screen selected")
        fun publishesSelection() {
            FuelBlendId.entries.forEach { blend ->
                TelemetryState.setFuelBlend(blend)
                assertEquals(blend, TelemetryState.fuelBlend.value)
            }
        }

        @Test
        @DisplayName("survives a reset, being a preference rather than a reading")
        fun survivesReset() {
            TelemetryState.setFuelBlend(FuelBlendId.E15)

            TelemetryState.reset()

            assertEquals(FuelBlendId.E15, TelemetryState.fuelBlend.value)
        }
    }
}

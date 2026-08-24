package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The charging system, on a car that spends much of its time deliberately not charging.
 *
 * The rule these replace was `batteryVoltage < 12.8 while running -> CHARGING SYSTEM LOW`,
 * and the case that matters most here is the one that used to fire it: a healthy 2013 Civic
 * at a steady cruise, where the ECM has backed the alternator off into the twelves on purpose
 * to stop it costing fuel. Every assertion below is really one question - does this
 * distinguish an alternator that is resting from one that is finished?
 */
class ChargingMonitorTest {

    /** Feeds one voltage for a stretch of driving, a tick at a time. */
    private fun hold(
        monitor: ChargingMonitor,
        volts: Double?,
        seconds: Double,
        rpm: Double = 2200.0,
        stepSec: Double = 1.0,
    ): ChargingVerdict {
        var verdict = monitor.verdict
        repeat((seconds / stepSec).toInt()) {
            verdict = monitor.observe(volts, rpm, stepSec)
        }
        return verdict
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("What the ECM does on purpose is not a fault")
    inner class LowOutputIsNormal {

        @Test
        fun `A cruise held in the twelves is not a warning`() {
            val monitor = ChargingMonitor()
            // Off a start that charged, then twenty minutes of the ECM holding low output.
            // This is the drive that used to sit under an amber banner the whole way.
            hold(monitor, 14.3, seconds = 90.0)
            val verdict = hold(monitor, 12.6, seconds = 1_200.0)

            assertEquals(ChargingVerdict.NORMAL, verdict)
            assertNull(chargingHealthStatus(verdict, monitor.volts, monitor.peakVolts))
        }

        @Test
        fun `The bottom of low output is still low output`() {
            // Honda's low-output mode reaches down to about 12.4. The drain threshold has to
            // sit below that with margin, or the warning is back.
            val monitor = ChargingMonitor()
            hold(monitor, 14.1, seconds = 60.0)
            assertEquals(ChargingVerdict.NORMAL, hold(monitor, 12.4, seconds = 900.0))
        }

        @Test
        fun `Switching between the two regimes stays quiet`() {
            val monitor = ChargingMonitor()
            repeat(6) {
                hold(monitor, 14.4, seconds = 60.0)
                assertEquals(ChargingVerdict.NORMAL, hold(monitor, 12.5, seconds = 300.0))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("What is actually worth saying")
    inner class RealFaults {

        @Test
        fun `Sustained below a rested battery is a drain`() {
            val monitor = ChargingMonitor()
            hold(monitor, 14.2, seconds = 60.0)
            // 12.0 with the engine running is under the battery's own rested voltage, so the
            // car is drawing it down rather than idling the alternator.
            val verdict = hold(monitor, 12.0, seconds = 60.0)

            assertEquals(ChargingVerdict.DRAINING, verdict)
            val status = assertNotNull(chargingHealthStatus(verdict, monitor.volts, monitor.peakVolts))
            assertEquals(HealthLevel.ADVISORY, status.level)
            assertTrue(status.summary.contains("12.00"), "reads the voltage: ${status.summary}")
        }

        @Test
        fun `A drive that never charges is caught even though every reading looks plausible`() {
            // The point of the whole class. Nothing here is below a rested battery, so no
            // instantaneous threshold sees anything wrong - but the alternator has never once
            // been commanded up, which is what a dead one looks like on this car.
            val monitor = ChargingMonitor()
            val verdict = hold(monitor, 12.5, seconds = 900.0)

            assertEquals(ChargingVerdict.NOT_CHARGING, verdict)
            val status = assertNotNull(chargingHealthStatus(verdict, monitor.volts, monitor.peakVolts))
            assertEquals(HealthLevel.ADVISORY, status.level)
            assertTrue(status.summary.contains("12.50"), "quotes the peak: ${status.summary}")
        }

        @Test
        fun `One commanded charge settles the question for the whole drive`() {
            val monitor = ChargingMonitor()
            hold(monitor, 13.4, seconds = 20.0)
            assertTrue(monitor.sawHighOutput)
            // Two hours of low output afterwards proves nothing new and says nothing.
            assertEquals(ChargingVerdict.NORMAL, hold(monitor, 12.5, seconds = 7_200.0))
        }

        @Test
        fun `Far under a running engine is critical`() {
            val monitor = ChargingMonitor()
            val verdict = hold(monitor, 11.4, seconds = 60.0)

            assertEquals(ChargingVerdict.CRITICAL, verdict)
            assertEquals(
                HealthLevel.CRITICAL,
                assertNotNull(chargingHealthStatus(verdict, monitor.volts, monitor.peakVolts)).level,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Readings that are not evidence")
    inner class NotEvidence {

        @Test
        fun `Key on, engine off is a rested battery, not a charging fault`() {
            // Sitting in the car with the radio on. The handshake needs the ignition on, so
            // every drive that gets connected early spends time here reading about 12.4.
            val monitor = ChargingMonitor()
            val verdict = hold(monitor, 12.4, seconds = 600.0, rpm = 0.0)

            assertEquals(ChargingVerdict.UNKNOWN, verdict)
            assertNull(chargingHealthStatus(verdict, monitor.volts, monitor.peakVolts))
        }

        @Test
        fun `A car that never answers PID 42 gets no verdict rather than a healthy one`() {
            // The seeded 14.2 this replaced was worse than useless: it reported a perfectly
            // healthy charging system on a car that had never measured one, and it handed the
            // never-charged check a peak it never had to earn.
            val monitor = ChargingMonitor()
            val verdict = hold(monitor, null, seconds = 1_800.0)

            assertEquals(ChargingVerdict.UNKNOWN, verdict)
            assertNull(monitor.peakVolts)
        }

        @Test
        fun `A momentary dip is not a drain`() {
            // The starter, a radiator fan cutting in, the AC clutch engaging. All of these
            // pull the rail down for a moment and none of them is a charging fault.
            val monitor = ChargingMonitor()
            hold(monitor, 14.3, seconds = 60.0)
            assertEquals(ChargingVerdict.NORMAL, hold(monitor, 11.2, seconds = 3.0))
            assertEquals(ChargingVerdict.NORMAL, hold(monitor, 14.3, seconds = 30.0))
        }

        @Test
        fun `A garbled frame is not a battery state`() {
            val monitor = ChargingMonitor()
            hold(monitor, 14.3, seconds = 60.0)
            assertEquals(ChargingVerdict.UNKNOWN, hold(monitor, 0.0, seconds = 120.0))
            // And it did not poison the peak on the way through.
            assertEquals(14.3, monitor.peakVolts)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Per drive, not per install")
    inner class PerDrive {

        @Test
        fun `Yesterday's charge does not vouch for today's alternator`() {
            val monitor = ChargingMonitor()
            hold(monitor, 14.4, seconds = 60.0)
            assertTrue(monitor.sawHighOutput)

            monitor.resetForDrive()
            assertNull(monitor.peakVolts)
            // The alternator failed overnight. Nothing carried over to hide it.
            assertEquals(ChargingVerdict.NOT_CHARGING, hold(monitor, 12.5, seconds = 900.0))
        }

        @Test
        fun `A new drive starts with nothing established`() {
            val monitor = ChargingMonitor()
            hold(monitor, 11.0, seconds = 120.0)
            monitor.resetForDrive()

            assertEquals(ChargingVerdict.UNKNOWN, monitor.verdict)
            assertNull(monitor.volts)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Through the manager, which is where the banner reads it")
    inner class ThroughTheManager {

        /**
         * A reading the manager will accept.
         *
         * The motion stamp is not decoration. The manager refuses to accumulate duration
         * against a reading it cannot date, which is the whole defence against a stalled loop
         * booking a stale voltage as sustained - so a test that leaves it null is testing the
         * refusal, not the rule.
         */
        private fun reading(volts: Double?, rpm: Double = 2200.0, coolantC: Double = 85.0) = RawObdData(
            rpm = rpm,
            speedKmh = 80.0,
            coolantC = coolantC,
            batteryVoltage = volts,
            motionSampledAtMillis = System.currentTimeMillis(),
        )

        private fun run(manager: TelemetryManager, volts: Double?, seconds: Int, rpm: Double = 2200.0) {
            repeat(seconds) {
                manager.tick(reading(volts, rpm), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
            }
        }

        @Test
        fun `A healthy cruise in the twelves reports all systems OK`() {
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            run(manager, 14.2, seconds = 60)
            run(manager, 12.6, seconds = 600)

            val health = manager.tick(reading(12.6), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
                .metrics.healthStatus

            assertEquals(HealthLevel.OK, health.level, "got: ${health.summary}")
        }

        @Test
        fun `A failing charging system outranks a warm coolant reading`() {
            // It used to be the other way round: the coolant advisory returned first, so the
            // more urgent banner was the one that never appeared.
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            // 218F is the coolant advisory band, well short of the overheating one.
            repeat(61) {
                manager.tick(reading(11.3, coolantC = 103.5), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
            }
            val health = manager.tick(reading(11.3, coolantC = 103.5), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
                .metrics.healthStatus

            assertEquals(HealthLevel.CRITICAL, health.level)
            assertTrue(health.summary.contains("CHARGING"), "got: ${health.summary}")
        }

        @Test
        fun `Actual overheating still outranks the charging system`() {
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            repeat(61) {
                manager.tick(reading(11.3, coolantC = 110.0), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
            }
            val health = manager.tick(reading(11.3, coolantC = 110.0), rawDtSec = 1.0, status = ConnectionStatus.CONNECTED)
                .metrics.healthStatus

            assertTrue(health.summary.contains("OVERHEATING"), "got: ${health.summary}")
        }

        @Test
        fun `A stalled loop cannot turn one stale reading into a sustained drain`() {
            // Park with the app open and let the phone lock. Each tick then arrives with a
            // twenty-minute gap and the last voltage still sitting in the snapshot. Without
            // the observed-time gate, one stale 11.0 would clear the twenty-second sustain
            // window on arrival and put a CRITICAL banner on a car that was switched off.
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            repeat(30) {
                manager.tick(reading(11.0), rawDtSec = 1_200.0, status = ConnectionStatus.CONNECTED)
            }
            val health = manager.tick(reading(11.0), rawDtSec = 1_200.0, status = ConnectionStatus.CONNECTED)
                .metrics.healthStatus

            assertEquals(HealthLevel.OK, health.level, "got: ${health.summary}")
        }

        @Test
        fun `A reading the manager cannot date is not evidence either`() {
            // The other half of the same defence. An adapter that stopped answering leaves
            // every field carrying its last value forward, and a snapshot with no motion
            // stamp is one nobody can date - so it buys no time towards a verdict.
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            repeat(60) {
                manager.tick(
                    RawObdData(rpm = 2200.0, speedKmh = 80.0, batteryVoltage = 11.0),
                    rawDtSec = 1.0,
                    status = ConnectionStatus.CONNECTED,
                )
            }
            val health = manager.tick(
                RawObdData(rpm = 2200.0, speedKmh = 80.0, batteryVoltage = 11.0),
                rawDtSec = 1.0,
                status = ConnectionStatus.CONNECTED,
            ).metrics.healthStatus

            assertEquals(HealthLevel.OK, health.level, "got: ${health.summary}")
        }

        @Test
        fun `Starting a trip starts the charging verdict over`() {
            val manager = TelemetryManager(lifetimeStore = InMemoryLifetimeStore())
            run(manager, 14.4, seconds = 60)
            assertTrue(manager.charging.sawHighOutput)

            manager.resetTrip()
            assertNull(manager.charging.peakVolts)
        }
    }
}

package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two decisions that let the poll loop stop working flat out.
 *
 * Both are pure functions of their inputs, deliberately, so they can be pinned without a
 * coroutine, a transport or a clock running - the same argument IntegrationRules makes for
 * itself. The loop that uses them is exercised in ProtocolTest against a fake adapter.
 */
class PollPacingTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Holding the cycle period")
    inner class CycleBudget {

        @Test
        fun `A cycle that finished early sleeps the rest of its budget`() {
            assertEquals(45L, cycleSleepMs(elapsedMs = 80, budgetMs = 125))
        }

        @Test
        fun `A cycle that took no time sleeps the whole budget`() {
            assertEquals(125L, cycleSleepMs(elapsedMs = 0, budgetMs = 125))
        }

        @Test
        fun `A cycle that used its budget exactly still yields`() {
            // The floor, not zero. The loop has to suspend every cycle or cancellation is
            // never observed - and under a test's virtual clock it would never advance.
            assertEquals(ObdPacing.MIN_CYCLE_IDLE_MS, cycleSleepMs(elapsedMs = 125, budgetMs = 125))
        }

        @Test
        fun `A cycle that overran its budget sleeps the floor rather than a negative`() {
            assertEquals(ObdPacing.MIN_CYCLE_IDLE_MS, cycleSleepMs(elapsedMs = 900, budgetMs = 125))
        }

        @Test
        fun `Overrunning does not accrue a debt to be repaid`() {
            // No catch-up on purpose: sleeping less on the next cycle to hit an average rate
            // turns a struggling bus into a busy-loop exactly when it is already struggling.
            // A slow bus is allowed to produce a slow loop, and nothing here remembers.
            val afterOverrun = cycleSleepMs(elapsedMs = 900, budgetMs = 125)
            val afterNormal = cycleSleepMs(elapsedMs = 80, budgetMs = 125)
            assertEquals(ObdPacing.MIN_CYCLE_IDLE_MS, afterOverrun)
            assertEquals(45L, afterNormal, "the next cycle is paced on its own elapsed time")
        }

        @Test
        fun `The engine-off budget is the same function, just a longer period`() {
            assertEquals(940L, cycleSleepMs(elapsedMs = 60, budgetMs = ObdPacing.ENGINE_OFF_CYCLE_MS))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Noticing the engine is off")
    inner class EngineOff {

        private val t0 = 1_700_000_000_000L

        private fun detector() = EngineOffDetector(confirmMs = 5_000)

        @Test
        fun `A car that is not answering is not evidence of anything`() {
            /*
             * The single most important case here. Every field on RawObdData carries forward
             * on a non-answer, so the snapshot cannot say whether the engine is idling or the
             * ECU fell asleep half a minute ago holding 750 rpm. A detector that treated a
             * null as "no rpm, so the engine is off" would back the loop off on a moving car
             * whenever the bus got busy - and, worse, the tier's safety for the permanent
             * record rests on entry requiring a genuine measurement.
             */
            val d = detector()
            repeat(100) { i ->
                assertFalse(
                    d.observe(freshRpm = null, speedKmh = 0.0, nowMillis = t0 + i * 1_000L),
                    "a silent adapter must never enter the tier",
                )
            }
            assertFalse(d.isEngineOff)
        }

        @Test
        fun `A silent adapter does not start the confirm timer either`() {
            // Not merely "does not enter" - the null must leave the clock untouched, so a
            // burst of timeouts followed by one real low reading does not arrive already
            // past the confirm window.
            val d = detector()
            repeat(10) { d.observe(null, 0.0, t0 + it * 1_000L) }
            assertFalse(d.observe(freshRpm = 0.0, speedKmh = 0.0, nowMillis = t0 + 10_000L))
            assertFalse(
                d.observe(freshRpm = 0.0, speedKmh = 0.0, nowMillis = t0 + 14_999L),
                "the window runs from the first real reading, not from the timeouts",
            )
            assertTrue(d.observe(freshRpm = 0.0, speedKmh = 0.0, nowMillis = t0 + 15_000L))
        }

        @Test
        fun `A standstill below the running threshold enters the tier once it is sustained`() {
            val d = detector()
            assertFalse(d.observe(0.0, 0.0, t0), "not on the first reading")
            assertFalse(d.observe(0.0, 0.0, t0 + 4_999), "not one millisecond early")
            assertTrue(d.observe(0.0, 0.0, t0 + 5_000), "and at the window, yes")
            assertTrue(d.isEngineOff)
        }

        @Test
        fun `An idling engine at a long red light never enters the tier`() {
            /*
             * There is no false positive available here, and it is the car that guarantees it:
             * the R18Z1 has no idle stop-start, so with the ignition on it idles around 750
             * and never approaches 400. Five minutes of it changes nothing. This is why
             * ENGINE_OFF_CONFIRM_MS is five seconds and not sixty - it is guarding against a
             * bad parse, not against traffic.
             */
            val d = detector()
            repeat(300) { i ->
                assertFalse(d.observe(freshRpm = 750.0, speedKmh = 0.0, nowMillis = t0 + i * 1_000L))
            }
        }

        @Test
        fun `Rolling with the engine off does not count as parked`() {
            // Coasting in neutral: the crank is below the threshold but the car is moving, so
            // speed is still worth reading at full rate.
            val d = detector()
            repeat(20) { i ->
                assertFalse(d.observe(freshRpm = 0.0, speedKmh = 30.0, nowMillis = t0 + i * 1_000L))
            }
        }

        @Test
        fun `One reading above the threshold resets the window`() {
            val d = detector()
            d.observe(0.0, 0.0, t0)
            d.observe(0.0, 0.0, t0 + 4_000)
            assertFalse(d.observe(freshRpm = 900.0, speedKmh = 0.0, nowMillis = t0 + 4_100), "caught")
            assertFalse(
                d.observe(freshRpm = 0.0, speedKmh = 0.0, nowMillis = t0 + 5_100),
                "and the window restarts from there rather than carrying the old four seconds",
            )
            assertTrue(d.observe(0.0, 0.0, t0 + 10_100))
        }

        @Test
        fun `The engine starting leaves the tier immediately, with no hysteresis`() {
            // Asymmetric on purpose. Getting into the tier is deliberate and slow; getting
            // out is instant, because the cost of being slow here is a driver watching a
            // dead gauge after turning the key.
            val d = detector()
            d.observe(0.0, 0.0, t0)
            assertTrue(d.observe(0.0, 0.0, t0 + 5_000))

            assertFalse(
                d.observe(freshRpm = CivicSpecs.ENGINE_RUNNING_RPM, speedKmh = 0.0, nowMillis = t0 + 5_100),
                "the threshold itself counts as running",
            )
            assertFalse(d.isEngineOff)
        }

        @Test
        fun `Reset clears both the state and the window`() {
            val d = detector()
            d.observe(0.0, 0.0, t0)
            assertTrue(d.observe(0.0, 0.0, t0 + 5_000))

            d.reset()
            assertFalse(d.isEngineOff)
            assertFalse(
                d.observe(freshRpm = 0.0, speedKmh = 0.0, nowMillis = t0 + 5_100),
                "a fresh connection starts the window again, so a reconnect does not inherit it",
            )
        }
    }
}

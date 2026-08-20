package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When to chase a dropped link and when to stop.
 *
 * Both ends of this are failure modes a driver notices. Give up too early and a tunnel ends
 * the drive and starts a second one on the far side; give up too late and the phone spends
 * the night waking its Bluetooth radio at a sleeping adapter.
 */
class ReconnectPolicyTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Backoff")
    inner class Backoff {

        @Test
        fun `The first retry is quick enough to catch a momentary drop`() {
            assertEquals(2_000, ReconnectPolicy.delayMs(1))
            assertTrue(ReconnectPolicy.delayMs(1) <= 2_000, "a tunnel is over in seconds")
        }

        @Test
        fun `It doubles, then holds`() {
            assertEquals(2_000, ReconnectPolicy.delayMs(1))
            assertEquals(4_000, ReconnectPolicy.delayMs(2))
            assertEquals(8_000, ReconnectPolicy.delayMs(3))
            assertEquals(16_000, ReconnectPolicy.delayMs(4))
            assertEquals(30_000, ReconnectPolicy.delayMs(5))
            assertEquals(30_000, ReconnectPolicy.delayMs(50))
        }

        @Test
        fun `An attempt number below one is still a real delay`() {
            // Defensive: a zero here would be a tight loop hammering the radio.
            assertEquals(2_000, ReconnectPolicy.delayMs(0))
            assertEquals(2_000, ReconnectPolicy.delayMs(-3))
        }

        @Test
        fun `Backing off keeps the whole window to a handful of attempts`() {
            // The point of the backoff, stated as the thing it is actually for. A flat
            // two-second retry across the same window is ninety wakeups.
            var elapsed = 0L
            var attempts = 0
            while (ReconnectPolicy.shouldRetry(elapsed)) {
                attempts++
                elapsed += ReconnectPolicy.delayMs(attempts)
            }
            assertTrue(attempts in 2..12, "made $attempts attempts across the window")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Giving up")
    inner class GivingUp {

        @Test
        fun `A tunnel is well inside the window`() {
            assertTrue(ReconnectPolicy.shouldRetry(0))
            assertTrue(ReconnectPolicy.shouldRetry(30_000), "half a minute underground")
            assertTrue(ReconnectPolicy.shouldRetry(2 * 60_000))
        }

        @Test
        fun `A parked car is outside it`() {
            // The failure this bounds is not a wrong reading, it is a flat battery in the
            // morning, so the window has to actually close.
            assertFalse(ReconnectPolicy.shouldRetry(ReconnectPolicy.GIVE_UP_AFTER_MS))
            assertFalse(ReconnectPolicy.shouldRetry(60 * 60_000))
        }

        @Test
        fun `Giving up says the drive was kept`() {
            // The drive is written as it happens, so a lost link never loses one - and the
            // message has to say so, or it reads as the drive having been thrown away.
            assertTrue(ReconnectPolicy.gaveUpMessage().contains("saved"))
        }
    }
}

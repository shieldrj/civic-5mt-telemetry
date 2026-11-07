package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fused level: burn counted between float readings, the float holding it to account.
 *
 * What it replaces is a smoothed float, which moves in steps of 0.4% and runs a minute behind
 * a falling tank. Every scenario here is a tank falling at a known rate with the truth known
 * exactly, so the error of each estimate can be measured rather than argued about.
 */
@DisplayName("Fused tank level")
class HybridLevelTest {

    private val gpp = CivicSpecs.NOMINAL_GALLONS_PER_SENDER_PERCENT

    /** The sender reports a byte, so its readings come in steps of a bit under half a percent. */
    private fun quantize(percent: Double): Double = (percent / 0.392).roundToInt() * 0.392

    private class Run(val tracker: TankTracker, val clock: MutableClock)

    private fun startRun(startPercent: Double = 80.0): Run {
        val clock = MutableClock(1_700_000_000_000)
        val tracker = TankTracker(InMemoryTankStore(), clock)
        // The first reading of a fresh install only takes the sender as it stands.
        tracker.record(startPercent, 0.0, 0.0, 1.0)
        return Run(tracker, clock)
    }

    /** One second of driving while the tank really falls by [rate] percent. */
    private fun Run.second(
        truth: Double,
        rate: Double,
        rawOffset: Double = 0.0,
        mafScale: Double = 1.0,
        moving: Boolean = true,
    ) {
        clock.advanceMillis(1_000)
        tracker.record(
            levelPercent = quantize(truth) + rawOffset,
            milesStep = if (moving) 0.01 else 0.0,
            gallonsStep = rate * gpp * mafScale,
            dtSec = 1.0,
        )
    }

    @Test
    fun `A falling tank is tracked without the float's lag`() {
        val run = startRun()
        val rate = 0.02 // percent per second, about 36 mpg at 60 mph
        var truth = 80.0
        repeat(1500) {
            truth -= rate
            run.second(truth, rate)
        }
        val state = run.tracker.get()
        val fusedError = abs(state.effectiveLevelPercent - truth)
        val floatError = abs(state.smoothedLevelPercent - truth)

        assertTrue(fusedError < 0.3, "fused is out by $fusedError")
        // The smoothed float is a minute behind: rate x 60 s = 1.2 points. That is the error
        // this exists to remove, so it has to actually be there to remove.
        assertTrue(floatError > 0.8, "the float's lag was only $floatError")
    }

    @Test
    fun `A MAF that reads five percent high does not drift away from the float`() {
        val run = startRun()
        val rate = 0.02
        var truth = 80.0
        var worst = 0.0
        repeat(3000) {
            truth -= rate
            run.second(truth, rate, mafScale = 1.05)
            if (it > 600) worst = max(worst, abs(run.tracker.get().effectiveLevelPercent - truth))
        }
        // Held to about rate x error x time constant = 0.6 points, not growing with the tank.
        assertTrue(worst < 1.0, "drifted by $worst")
    }

    @Test
    fun `Slosh on a hill is clamped instead of followed`() {
        val run = startRun()
        val rate = 0.02
        var truth = 80.0
        repeat(600) {
            truth -= rate
            run.second(truth, rate)
        }
        repeat(30) {
            truth -= rate
            run.second(truth, rate, rawOffset = 12.0)
        }
        val state = run.tracker.get()
        assertTrue(abs(state.effectiveLevelPercent - truth) < 0.5, "fused followed the slosh")
        assertTrue(
            abs(state.smoothedLevelPercent - truth) > 3.0,
            "the float should have been pulled by the slosh, which is the point of comparing",
        )
    }

    @Test
    fun `A stopped car never corrects the count`() {
        val run = startRun()
        val rate = 0.02
        var truth = 80.0
        repeat(600) {
            truth -= rate
            run.second(truth, rate)
        }
        val before = run.tracker.get().effectiveLevelPercent
        // Standing at a light after a hill: the float sits high for a minute while fuel settles.
        repeat(60) { run.second(truth, 0.0, rawOffset = 15.0, moving = false) }
        assertEquals(before, run.tracker.get().effectiveLevelPercent, 1e-9)
    }

    @Test
    fun `Fuel that left while nothing was watching is believed over the count`() {
        val run = startRun()
        val rate = 0.02
        var truth = 80.0
        repeat(600) {
            truth -= rate
            run.second(truth, rate)
        }
        // Twenty points of tank go with the phone at home: the float drops, the MAF saw nothing.
        var worstGap = 0.0
        repeat(600) {
            truth -= rate
            run.second(truth - 20.0, rate)
            val s = run.tracker.get()
            worstGap = max(worstGap, abs(s.effectiveLevelPercent - s.smoothedLevelPercent))
        }
        val s = run.tracker.get()
        assertTrue(worstGap <= TankRules.HYBRID_MAX_DIVERGENCE_PERCENT + 1e-9, "gap reached $worstGap")
        assertTrue(abs(s.effectiveLevelPercent - (truth - 20.0)) < 2.0, "ended at ${s.effectiveLevelPercent}")
    }

    @Test
    fun `A young run is the float and nothing else`() {
        val run = startRun()
        val rate = 0.02
        var truth = 80.0
        repeat(100) {
            truth -= rate
            run.second(truth, rate, mafScale = 1.3)
        }
        val s = run.tracker.get()
        assertEquals(s.smoothedLevelPercent, s.effectiveLevelPercent, 1e-9)
    }

    @Test
    fun `The fused level survives a restart`() {
        val clock = MutableClock(1_700_000_000_000)
        val store = InMemoryTankStore()
        val tracker = TankTracker(store, clock)
        tracker.record(80.0, 0.0, 0.0, 1.0)
        var truth = 80.0
        repeat(400) {
            truth -= 0.02
            clock.advanceMillis(1_000)
            tracker.record(quantize(truth), 0.01, 0.02 * gpp, 1.0)
        }
        tracker.flush()

        val reloaded = TankTracker(store, clock).get()
        assertNotNull(reloaded.fusedLevelPercent)
        assertEquals(tracker.get().fusedLevelPercent!!, reloaded.fusedLevelPercent!!, 1e-9)
    }

    @Test
    fun `Gallons come from the fused level once there is one`() {
        val state = TankState(
            fillTimestamp = 1L,
            smoothedLevelPercent = 50.0,
            fusedLevelPercent = 48.0,
        )
        val withoutFused = state.copy(fusedLevelPercent = null)
        assertEquals(
            state.gallonsPerPercent * 2.0,
            withoutFused.gallonsRemaining - state.gallonsRemaining,
            1e-9,
        )
    }
}

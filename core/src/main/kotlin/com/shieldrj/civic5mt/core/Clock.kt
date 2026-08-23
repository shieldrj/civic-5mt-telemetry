package com.shieldrj.civic5mt.core

/**
 * Wall-clock milliseconds, injected rather than read directly.
 *
 * The TypeScript called `Date.now()` inside the gear calculator and the oil model. That is
 * fine in a browser and awkward everywhere else: it makes a rate-of-change calculation
 * depend on how fast the test runner happens to be, and it is the reason clutch-slip
 * detection had no test at all. Passing the clock in costs one constructor argument and
 * makes those paths reachable from a unit test.
 */
fun interface MillisClock {
    fun nowMillis(): Long
}

val SystemMillisClock: MillisClock = MillisClock { System.currentTimeMillis() }

/** A clock a test drives by hand. */
class MutableClock(private var now: Long = 0L) : MillisClock {
    override fun nowMillis(): Long = now

    fun advanceMillis(delta: Long) {
        now += delta
    }

    fun setMillis(value: Long) {
        now = value
    }
}

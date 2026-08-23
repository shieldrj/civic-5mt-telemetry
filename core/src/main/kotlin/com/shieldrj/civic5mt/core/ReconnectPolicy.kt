package com.shieldrj.civic5mt.core

/**
 * How hard, and for how long, to chase a link that has gone.
 *
 * There are two completely different situations behind the same symptom, and the whole job
 * of this file is to serve both without a switch the driver has to think about:
 *
 * - **A tunnel, or a knock to the adapter.** The car is still running and the link comes back
 *   within seconds. Ending the drive here loses the rest of it and starts a second trip
 *   afterwards, so the right answer is to retry quickly and keep the trip open.
 * - **The ignition went off.** The adapter is deliberately asleep and is not coming back
 *   until the next drive. Retrying is a Bluetooth radio waking a sleeping phone every few
 *   seconds, all night, for nothing - which is the version of this that shows up as "the app
 *   drains my battery".
 *
 * Nothing distinguishes them at the moment the link drops; they only differ in how long the
 * silence lasts. So: retry hard at first and give up entirely at [GIVE_UP_AFTER_MS], which is
 * past any tunnel and well short of a night in a driveway.
 */
object ReconnectPolicy {

    /** First retry, close enough behind the drop to catch a momentary one. */
    const val FIRST_DELAY_MS: Long = 2_000

    /** Retries stop lengthening here rather than growing without bound. */
    const val MAX_DELAY_MS: Long = 30_000

    /**
     * Total time spent chasing before the drive is closed off.
     *
     * Three minutes. Longer than any tunnel on a commute, and short enough that parking and
     * walking away costs a handful of failed connects rather than an evening of them.
     */
    const val GIVE_UP_AFTER_MS: Long = 3 * 60_000

    /**
     * Gap before attempt [attempt], counting from 1.
     *
     * Doubles, then holds. Backing off matters more than it looks: each attempt wakes the
     * Bluetooth radio, and a fixed two-second retry for three minutes is ninety wakeups to
     * discover the same thing a dozen would have.
     */
    fun delayMs(attempt: Int): Long {
        if (attempt <= 1) return FIRST_DELAY_MS
        var delay = FIRST_DELAY_MS
        repeat(attempt - 1) {
            delay *= 2
            if (delay >= MAX_DELAY_MS) return MAX_DELAY_MS
        }
        return delay
    }

    /**
     * Whether to make another attempt, given how long the link has been gone.
     *
     * Measured from the drop rather than counted in attempts, because the attempts are spaced
     * unevenly - a count would mean something different depending on how far the backoff had
     * got, and the thing actually worth bounding is the radio being woken over a period of
     * time.
     */
    fun shouldRetry(elapsedSinceDropMs: Long): Boolean = elapsedSinceDropMs < GIVE_UP_AFTER_MS

    /** What to tell the driver once it stops trying. */
    fun gaveUpMessage(): String =
        "Lost the adapter and could not get it back after " +
            "${GIVE_UP_AFTER_MS / 60_000} minutes. The drive has been saved."
}

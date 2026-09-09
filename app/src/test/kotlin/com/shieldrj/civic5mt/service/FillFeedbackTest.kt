package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.FillOutcome
import com.shieldrj.civic5mt.core.FillRejection
import com.shieldrj.civic5mt.core.FillSample
import com.shieldrj.civic5mt.core.FuelCalibrationState
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the driver is told after logging a fill.
 *
 * Worth its own file, because the fault this closes was entirely in the telling. The fill was
 * recorded, the correction was right, and the reply went to a status line drawn on a different
 * screen - so tapping the button on the Fuel tab was indistinguishable from tapping a dead
 * one. A silent success and a silent refusal look identical, and the refusal is the one that
 * needs something done about it.
 */
class FillFeedbackTest {

    /** A tank measured five percent under the pump: ten gallons burned, ten and a half sold. */
    private fun accepted(fills: Int = 1): FillOutcome.Accepted {
        val samples = List(fills) {
            FillSample(
                timestampMillis = 1_700_000_000_000 + it,
                pumpGallons = 10.5,
                measuredGallons = 10.0,
                measuredMiles = 350.0,
                fuelFactorInEffect = 1.0,
                distanceFactorInEffect = 1.0,
            )
        }
        return FillOutcome.Accepted(
            sample = samples.last(),
            state = FuelCalibrationState(samples = samples, lastFillWasFull = true),
        )
    }

    private fun rejected(reason: FillRejection) =
        FillOutcome.Rejected(reason, FuelCalibrationState())

    @Test
    @DisplayName("reads the gallons back, so the tap is visibly the number that was typed")
    fun echoesThePump() {
        assertContains(fillFeedback(accepted(), pumpGallons = 11.42, levelPercent = 93.0), "11.42 gal")
    }

    @Test
    @DisplayName("says how far off the fuel figure is, and over how many fills")
    fun reportsTheCorrection() {
        val message = fillFeedback(accepted(fills = 2), pumpGallons = 10.5, levelPercent = 93.0)

        assertContains(message, "5.0% under the pump")
        assertContains(message, "over 2 fills")
    }

    @Test
    @DisplayName("counts a single fill in the singular")
    fun singularFill() {
        assertContains(fillFeedback(accepted(fills = 1), 10.5, 93.0), "over 1 fill.")
    }

    @Test
    @DisplayName("names where the new tank started when the car is reporting a level")
    fun saysWhereTheTankStarted() {
        assertContains(fillFeedback(accepted(), 11.42, 93.0), "New tank started at 93%")
    }

    @Test
    @DisplayName("says the level is still to come when the car is asleep at the pump")
    fun saysTheLevelIsPending() {
        // The ordinary case, and the one that used to produce no message at all: the receipt
        // is typed in with the ignition off, so there is no sender reading to report.
        val message = fillFeedback(accepted(), pumpGallons = 11.42, levelPercent = null)

        assertContains(message, "New tank started.")
        assertContains(message, "when the car next reports one")
    }

    @Test
    @DisplayName("gives each refusal its own reason, and tells the driver about the tank anyway")
    fun everyRefusalExplainsItself() {
        val messages = FillRejection.entries.associateWith {
            fillFeedback(rejected(it), pumpGallons = 11.42, levelPercent = null)
        }

        // Distinct, because two refusals that read alike are one refusal as far as the driver
        // is concerned - and these six want six different things done next.
        assertEquals(FillRejection.entries.size, messages.values.distinct().size)
        messages.forEach { (reason, message) ->
            assertTrue(message.length > 40, "$reason: $message")
            // The tank restarts whatever the calibration made of the receipt, and that is the
            // half that moves the range figure.
            assertContains(message, "New tank started", message = reason.toString())
        }
    }

    @Test
    @DisplayName("does not read a typo back as logged")
    fun doesNotClaimATypoWasLogged() {
        // 114.2 for 11.42, which is the slip this rejection exists for. It was not used, and
        // "logged" would tell the driver it was.
        val message = fillFeedback(rejected(FillRejection.IMPLAUSIBLE_PUMP_GALLONS), 114.2, null)

        assertContains(message, "114.20 gal")
        assertFalse(message.contains("logged"), message)
    }

    @Test
    @DisplayName("leaves no format specifier unfilled, in any outcome")
    fun noRawSpecifiersSurvive() {
        // The gallons are interpolated into a template that is then handed to format(), so a
        // specifier on the wrong side of that would reach a pump as "%.1f" or throw there.
        val outcomes = FillRejection.entries.map { rejected(it) as FillOutcome } +
            listOf(accepted(fills = 1), accepted(fills = 6))

        outcomes.forEach { outcome ->
            listOf(null, 93.0).forEach { level ->
                val message = fillFeedback(outcome, pumpGallons = 11.42, levelPercent = level)
                listOf("%.1f", "%.2f", "%s", "%d").forEach { specifier ->
                    assertFalse(message.contains(specifier), "$specifier in: $message")
                }
            }
        }
    }
}

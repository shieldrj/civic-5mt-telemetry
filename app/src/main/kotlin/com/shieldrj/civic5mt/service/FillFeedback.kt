package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.FillOutcome
import com.shieldrj.civic5mt.core.FillRejection
import com.shieldrj.civic5mt.core.FuelCalibrationRules
import kotlin.math.abs

/**
 * What to tell the driver about the fill they have just logged.
 *
 * A top-level function rather than a method on the service, because this text is the whole
 * visible result of the feature and it needs a test of its own. The fault it exists to close
 * was not in the arithmetic: the fill was recorded correctly and nobody was told, so a driver
 * standing at a pump had typed a number in and watched the app do nothing. Nothing in here
 * touches Android, so a JVM test can put every outcome through it.
 *
 * Two sentences answering two different questions. What the receipt taught, which is why the
 * number was typed in at all. And what became of the tank, which is why the range figure has
 * or has not moved yet.
 */
internal fun fillFeedback(
    outcome: FillOutcome,
    pumpGallons: Double,
    levelPercent: Double?,
    odometerGiven: Boolean = true,
): String {
    // Read back first, always. The receipt is the one figure here the driver knows
    // independently, so it is the one that proves the tap reached the right number.
    val pump = "%.2f gal".format(pumpGallons)

    val receipt = when (outcome) {
        is FillOutcome.Accepted -> {
            val state = outcome.state
            val off = (state.fuelCorrectionFactor - 1.0) * 100
            val direction = if (off >= 0) "under" else "over"
            val fills = state.samples.size
            "$pump logged. The app's fuel figure reads %.1f%% %s the pump, over %d fill%s."
                .format(abs(off), direction, fills, if (fills == 1) "" else "s")
        }

        // Each reason gets its own sentence rather than a shared "logged". A fill that taught
        // nothing must not read like one that did, or the correction never appears to improve.
        is FillOutcome.Rejected -> when (outcome.reason) {
            FillRejection.NOT_FILLED_TO_SHUTOFF ->
                "$pump logged. A part fill cannot be measured, but the next fill to the " +
                    "click will be measured from it."

            // Not a failure, and it must not read like one. This is what every driver sees the
            // first time they log a receipt: the fill was kept, as the start of the span the
            // next one measures. Reported 2026-09-23 as "the app wouldn't take 10.9 gallons".
            FillRejection.NO_FULL_FILL_BASELINE ->
                "$pump saved as your starting fill. Fill to the click next time and that " +
                    "fill calibrates the gauge."

            FillRejection.SPAN_TOO_SHORT ->
                "$pump logged. Too small to measure from: it takes about " +
                    "${FuelCalibrationRules.MIN_PUMP_GALLONS.toInt()} gallons and " +
                    "${FuelCalibrationRules.MIN_MILES.toInt()} miles."

            // The one outcome where the number itself is not believed, so it is not read back
            // as logged. It covers both ends - a tankful too many, or a figure at or below
            // nothing - which is why it does not name which.
            FillRejection.IMPLAUSIBLE_PUMP_GALLONS ->
                "$pump is not a fill this tank could have taken, so it was not used for " +
                    "the calibration."

            FillRejection.NO_MEASUREMENT ->
                "$pump logged. The app tracked nothing across that tank, so there was " +
                    "nothing to compare the receipt against."

            FillRejection.IMPLAUSIBLE_RATIO ->
                "$pump logged. The receipt and the sensors are too far apart to be a " +
                    "sensor error - a missed fill, most likely - so it was not used."
        }
    }

    // Worth saying either way. A driver told nothing about the tank goes looking for a fault
    // when the range figure has not moved, and the honest answer when the car is asleep is
    // that the level is not known yet rather than that nothing happened.
    val tank = if (levelPercent != null) {
        "New tank started at " + levelPercent.toInt() + "%."
    } else {
        "New tank started. Its level fills in when the car next reports one."
    }

    // The odometer is what turns the next receipt into a true miles-per-gallon, and it only
    // works when both ends of the tank have one. Asked for here, while the pump is in front of
    // the driver, because nothing else on the screen says the empty field cost anything.
    val odometer = if (!odometerGiven && fillWasTaken(outcome)) {
        " Add the odometer next time too - it makes the miles-to-empty exact."
    } else {
        ""
    }

    return "$receipt $tank$odometer"
}

/**
 * Whether the fill was kept, which decides the colour it is reported in.
 *
 * Wider than [FillOutcome.Accepted]. A first fill, a part fill and a short one teach the
 * calibration nothing, but each was stored exactly as it should be and each sets up the next
 * fill. Showing them in the warning colour told a driver who had done everything right that
 * the app had refused the receipt. Warning is kept for the outcomes where something needs
 * checking: a number the tank could not have taken, or a tank the app did not see.
 */
internal fun fillWasTaken(outcome: FillOutcome): Boolean = when (outcome) {
    is FillOutcome.Accepted -> true
    is FillOutcome.Rejected -> when (outcome.reason) {
        FillRejection.NO_FULL_FILL_BASELINE,
        FillRejection.NOT_FILLED_TO_SHUTOFF,
        FillRejection.SPAN_TOO_SHORT,
        -> true

        FillRejection.IMPLAUSIBLE_PUMP_GALLONS,
        FillRejection.NO_MEASUREMENT,
        FillRejection.IMPLAUSIBLE_RATIO,
        -> false
    }
}

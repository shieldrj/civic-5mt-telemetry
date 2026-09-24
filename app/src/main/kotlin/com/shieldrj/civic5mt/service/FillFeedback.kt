package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.FuelCalibrationRules
import com.shieldrj.civic5mt.core.FuelCalibrationState
import com.shieldrj.civic5mt.core.ReceiptOutcome

/**
 * What to tell the driver about the receipt they have just saved.
 *
 * A top-level function rather than a method on the service, because this text is the whole
 * visible result of the feature and it needs a test of its own. Nothing in here touches
 * Android, so a JVM test can put every outcome through it.
 *
 * Short, and in the order the driver cares about: the number was kept, then what it did. A
 * receipt is typed in at the end of a drive home, not studied.
 *
 * @param before the fill record as it stood before this receipt, so the message can say
 *   whether this one changed anything
 */
internal fun receiptFeedback(
    outcome: ReceiptOutcome,
    pumpGallons: Double,
    before: FuelCalibrationState,
    odometerGiven: Boolean,
): String {
    // Read back first, always. The receipt is the one figure here the driver knows
    // independently, so it is the one that proves the tap reached the right number.
    val pump = "%.2f gal".format(pumpGallons)

    val saved = when (outcome) {
        // The one refusal: a number the tank could not have taken. Not read back as saved.
        is ReceiptOutcome.Refused ->
            return "$pump is not a fill this tank could take, so it was not saved. " +
                "Check the number on the receipt."

        is ReceiptOutcome.Saved -> outcome
    }
    val record = saved.record
    val state = saved.state

    val gauge = when {
        // Logged at the pump before the engine was started: the rise is still to come.
        record.levelAfter == null ->
            "The gauge is measured from it once you start the car."

        record.gallonsPerPercent != null -> {
            val n = state.gaugeReceiptCount
            if (n == 1 && before.gaugeReceiptCount == 0) {
                "The fuel gauge is now measured by the pump."
            } else {
                "The fuel gauge is now measured from $n receipts."
            }
        }

        record.levelBefore == null ->
            "The car didn't see the gauge before this fill, so it couldn't measure it. " +
                "The next fill will."

        else ->
            "Too small a fill to measure the gauge from - it takes about " +
                "${FuelCalibrationRules.MIN_PUMP_GALLONS.toInt()} gallons."
    }

    // The odometer is what turns receipts into a true miles-per-gallon, and it only works
    // when two fills in a row have one. Asked for here because nothing else on the screen
    // says the empty field cost anything.
    val odometer = if (!odometerGiven && record.odometerAtFill == null) {
        " Add the odometer next time too - it makes miles to empty exact."
    } else {
        ""
    }

    return "$pump saved. $gauge$odometer"
}

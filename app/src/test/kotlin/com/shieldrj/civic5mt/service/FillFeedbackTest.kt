package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.FillRecord
import com.shieldrj.civic5mt.core.FuelCalibrationState
import com.shieldrj.civic5mt.core.ReceiptOutcome
import com.shieldrj.civic5mt.core.ReceiptRefusal
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the driver is told after saving a receipt.
 *
 * Worth its own file because this text is the whole visible result of typing a number in. The
 * first version answered a correctly stored receipt in the warning colour, and a driver who had
 * done everything right read it as a refusal.
 */
class FillFeedbackTest {

    /** A fill the car saw from 20% to 93%, with a receipt that measured the gauge. */
    private val measured = FillRecord(
        detectedAtMillis = 1_700_000_000_000,
        levelBefore = 20.0,
        levelAfter = 93.0,
        pumpGallons = 9.125,
    )

    private fun saved(record: FillRecord, others: List<FillRecord> = emptyList()) =
        ReceiptOutcome.Saved(record, FuelCalibrationState(fills = others + record))

    private fun message(
        outcome: ReceiptOutcome,
        pump: Double = 9.125,
        before: FuelCalibrationState = FuelCalibrationState(),
        odometerGiven: Boolean = true,
    ) = receiptFeedback(outcome, pump, before, odometerGiven)

    @Test
    @DisplayName("reads the gallons back, so the tap is visibly the number that was typed")
    fun echoesThePump() {
        assertContains(message(saved(measured), pump = 10.9), "10.90 gal saved")
    }

    @Test
    @DisplayName("says the gauge is now measured by the pump after the first receipt")
    fun firstReceiptMeasuresTheGauge() {
        assertContains(message(saved(measured)), "The fuel gauge is now measured by the pump.")
    }

    @Test
    @DisplayName("counts the receipts behind the gauge after that")
    fun countsReceipts() {
        val earlier = measured.copy(detectedAtMillis = 1)
        val msg = message(
            saved(measured, others = listOf(earlier)),
            before = FuelCalibrationState(fills = listOf(earlier)),
        )
        assertContains(msg, "measured from 2 receipts")
    }

    @Test
    @DisplayName("tells a driver at the pump that the gauge is measured once the car starts")
    fun waitingForTheRise() {
        val msg = message(saved(measured.copy(levelAfter = null)))
        assertContains(msg, "once you start the car")
    }

    @Test
    @DisplayName("explains a fill whose starting level the car never saw")
    fun noBeforeLevel() {
        val msg = message(saved(measured.copy(levelBefore = null)))
        assertContains(msg, "didn't see the gauge before this fill")
    }

    @Test
    @DisplayName("explains a fill too small to measure")
    fun tooSmall() {
        val msg = message(saved(measured.copy(levelBefore = 80.0, pumpGallons = 1.6)), pump = 1.6)
        assertContains(msg, "Too small a fill")
    }

    @Test
    @DisplayName("does not read a typo back as saved")
    fun typoIsNotSaved() {
        val msg = message(
            ReceiptOutcome.Refused(ReceiptRefusal.IMPLAUSIBLE_PUMP_GALLONS, FuelCalibrationState()),
            pump = 114.2,
        )
        assertContains(msg, "114.20 gal")
        assertFalse(msg.contains("gal saved"), msg)
        assertContains(msg, "not saved")
    }

    @Test
    @DisplayName("asks for the odometer only when the fill has none")
    fun asksForTheOdometer() {
        assertContains(message(saved(measured), odometerGiven = false), "Add the odometer")
        assertFalse(message(saved(measured), odometerGiven = true).contains("odometer"))
        val withOdometer = measured.copy(odometerAtFill = 100_000.0)
        assertFalse(message(saved(withOdometer), odometerGiven = false).contains("odometer"))
    }

    @Test
    @DisplayName("leaves no format specifier unfilled, in any outcome")
    fun noRawSpecifiersSurvive() {
        val outcomes = listOf(
            saved(measured),
            saved(measured.copy(levelAfter = null)),
            saved(measured.copy(levelBefore = null)),
            saved(measured.copy(levelBefore = 80.0)),
            ReceiptOutcome.Refused(ReceiptRefusal.IMPLAUSIBLE_PUMP_GALLONS, FuelCalibrationState()),
        )
        outcomes.forEach { outcome ->
            listOf(true, false).forEach { odo ->
                val msg = message(outcome, odometerGiven = odo)
                listOf("%.1f", "%.2f", "%s", "%d").forEach { spec ->
                    assertFalse(msg.contains(spec), "$spec in: $msg")
                }
                assertTrue(msg.length > 20, msg)
            }
        }
    }
}

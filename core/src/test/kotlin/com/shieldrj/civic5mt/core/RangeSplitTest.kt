package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two questions distance-to-empty can answer, and which economy figure it uses.
 *
 * The app read a hundred and thirty when the dashboard read fifty-four, and almost none of
 * that was a disagreement about fuel. Honda's figure counts down to zero with the reserve
 * still in the tank, deliberately; this one counted the reserve, because it was asked what is
 * actually there. Both are true. Presenting them as one number is what made the app look wrong
 * rather than different, so both are returned now.
 */
class RangeSplitTest {

    /** A tank with a measured sender scale and a real reserve underneath its zero. */
    private fun tank(
        senderPercent: Double,
        gallonsPerPercent: Double = 0.120,
        fullMark: Double = 93.0,
    ) = TankState(
        fillTimestamp = 1L,
        smoothedLevelPercent = senderPercent,
        gallonsPerPercent = gallonsPerPercent,
        fullMarkPercent = fullMark,
        calibrated = true,
    )

    @Nested
    @DisplayName("Splitting the range at the sender's zero")
    inner class Splitting {

        @Test
        fun `the halves add back up to the number on screen`() {
            val t = tank(senderPercent = 10.0)
            val split = splitRange(t, dampedTotalMiles = 130, mpgUsed = 39.0)
            assertEquals(130, split.totalMiles)
            assertEquals(130, split.toSenderZeroMiles + split.reserveMiles)
        }

        @Test
        fun `the reserve is a large share of a nearly empty tank, which is the whole problem`() {
            // 10% of sender at 0.12 gal per percent is 1.2 gallons the sender can see, plus a
            // reserve of 13.2 - (0.12 x 93) = 2.0 it cannot. Most of what is left is fuel no
            // sensor is watching go down.
            val t = tank(senderPercent = 10.0)
            val split = splitRange(t, dampedTotalMiles = 130, mpgUsed = 39.0)
            assertTrue(
                split.reserveMiles > split.toSenderZeroMiles,
                "reserve ${split.reserveMiles} vs usable ${split.toSenderZeroMiles}",
            )
        }

        @Test
        fun `and a rounding error on a full one`() {
            val t = tank(senderPercent = 93.0)
            val split = splitRange(t, dampedTotalMiles = 500, mpgUsed = 39.0)
            assertTrue(split.reserveMiles < 80, "reserve was ${split.reserveMiles}")
            assertEquals(500, split.toSenderZeroMiles + split.reserveMiles)
        }

        @Test
        fun `a car whose sender really does reach zero has no reserve to split off`() {
            // A full mark below the threshold means no full tank was ever seen, so no reserve
            // is claimed - and then the two figures are the same number, honestly.
            val t = tank(senderPercent = 40.0, fullMark = 50.0)
            val split = splitRange(t, dampedTotalMiles = 200, mpgUsed = 35.0)
            assertEquals(0, split.reserveMiles)
            assertEquals(200, split.toSenderZeroMiles)
        }

        @Test
        fun `an empty tank does not divide by zero`() {
            val t = TankState(fillTimestamp = 1L, smoothedLevelPercent = 0.0, fullMarkPercent = 0.0)
            val split = splitRange(t, dampedTotalMiles = 0, mpgUsed = 31.0)
            assertEquals(0, split.totalMiles)
            assertEquals(0, split.reserveMiles)
        }
    }

    @Nested
    @DisplayName("Which economy figure range is built on")
    inner class EconomyChoice {

        @Test
        fun `the verified figure outranks the lifetime one, being measured outside the app`() {
            val mpg = TankRules.mpgForRange(
                tankMpg = null,
                tankGallonsUsed = 0.0,
                lifetimeMpg = 34.0,
                lifetimeMiles = 5_000.0,
                verifiedMpg = 38.5,
            )
            assertEquals(38.5, mpg, 1e-9)
        }

        @Test
        fun `the lifetime one is used when no fills have been logged`() {
            val mpg = TankRules.mpgForRange(
                tankMpg = null,
                tankGallonsUsed = 0.0,
                lifetimeMpg = 34.0,
                lifetimeMiles = 5_000.0,
                verifiedMpg = null,
            )
            assertEquals(34.0, mpg, 1e-9)
        }

        @Test
        fun `and the EPA rating only when nothing at all has been measured`() {
            val mpg = TankRules.mpgForRange(
                tankMpg = null,
                tankGallonsUsed = 0.0,
                lifetimeMpg = 0.0,
                lifetimeMiles = 0.0,
                verifiedMpg = null,
            )
            assertEquals(CivicSpecs.EPA_COMBINED_MPG_DEFAULT, mpg, 1e-9)
        }

        @Test
        fun `the EPA fallback is the manual's rating, not the automatic's`() {
            // 28 city / 36 highway / 31 combined for the 5MT. The automatic's taller top gear
            // earns it 39 highway and a 32 combined, and that was the number sitting here.
            assertEquals(31.0, CivicSpecs.EPA_COMBINED_MPG_DEFAULT, 1e-9)
        }

        @Test
        fun `this tank still fades in on top of the verified baseline`() {
            // A highway tank running well above the verified average owns the answer once
            // enough fuel is behind it, and barely moves it in the first few miles.
            val early = TankRules.mpgForRange(
                tankMpg = 44.0,
                tankGallonsUsed = 0.1,
                lifetimeMpg = 0.0,
                lifetimeMiles = 0.0,
                verifiedMpg = 38.0,
            )
            val settled = TankRules.mpgForRange(
                tankMpg = 44.0,
                tankGallonsUsed = 2.5,
                lifetimeMpg = 0.0,
                lifetimeMiles = 0.0,
                verifiedMpg = 38.0,
            )
            assertTrue(early < 38.5, "a tenth of a gallon moved the baseline to $early")
            assertEquals(44.0, settled, 1e-9)
        }
    }

    @Nested
    @DisplayName("The corrections reaching the fuel model")
    inner class ModelWiring {

        @Test
        fun `a correction scales the gallons the MAF chain reports`() {
            val model = FuelModelEngine()
            val before = model.calculateFuelFlow(
                mafGramsPerSec = 5.0,
                airFuelRatio = 14.4,
                isDfcoActive = false,
            )
            model.setFuelCorrectionFactor(1.05)
            val after = model.calculateFuelFlow(
                mafGramsPerSec = 5.0,
                airFuelRatio = 14.4,
                isDfcoActive = false,
            )
            assertEquals(before.fuelFlowGalPerHour * 1.05, after.fuelFlowGalPerHour, 1e-9)
            assertEquals(before.fuelFlowGramsPerSec * 1.05, after.fuelFlowGramsPerSec, 1e-9)
            assertEquals(before.fuelFlowLitersPerHour * 1.05, after.fuelFlowLitersPerHour, 1e-9)
        }

        @Test
        fun `a nonsense correction is ignored rather than applied`() {
            val model = FuelModelEngine()
            model.setFuelCorrectionFactor(0.0)
            assertEquals(1.0, model.getFuelCorrectionFactor())
            model.setFuelCorrectionFactor(Double.NaN)
            assertEquals(1.0, model.getFuelCorrectionFactor())
        }

        @Test
        fun `cutting fuel still cuts it, correction or not`() {
            val model = FuelModelEngine()
            model.setFuelCorrectionFactor(1.2)
            val flow = model.calculateFuelFlow(
                mafGramsPerSec = 5.0,
                airFuelRatio = 14.4,
                isDfcoActive = true,
            )
            assertEquals(0.0, flow.fuelFlowGalPerHour)
        }
    }
}

package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ClutchHealthEngine
import com.shieldrj.civic5mt.core.ClutchProfileStore
import com.shieldrj.civic5mt.core.DtcReportStore
import com.shieldrj.civic5mt.core.DtcScanReport
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.FillRecord
import com.shieldrj.civic5mt.core.FuelCalibrationState
import com.shieldrj.civic5mt.core.FuelCalibrationStore
import com.shieldrj.civic5mt.core.InMemoryClutchProfileStore
import com.shieldrj.civic5mt.core.InMemoryDtcReportStore
import com.shieldrj.civic5mt.core.InMemoryFuelCalibrationStore
import com.shieldrj.civic5mt.core.InMemoryLifetimeStore
import com.shieldrj.civic5mt.core.InMemoryOilProfileStore
import com.shieldrj.civic5mt.core.InMemoryTankStore
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LifetimeStore
import com.shieldrj.civic5mt.core.OilProfileStore
import com.shieldrj.civic5mt.core.TankState
import com.shieldrj.civic5mt.core.TankStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What the app knows before anything is connected.
 *
 * The bug behind this file was an absent figure rather than a wrong one, which is the kind
 * nothing catches by accident: the clutch profile and the fill history were on disk and were
 * only ever read by [TelemetryService], so they existed and were invisible from the moment
 * the ignition went off until the moment it came back. The Clutch screen said "no record yet"
 * to a driver whose record was on the same phone.
 *
 * So these tests are mostly about the two directions that can go wrong. Publishing too little
 * is the bug that was there. Publishing too much - inventing a record for a car the app has
 * never been plugged into - is the bug that fixing it invites, because both engines write a
 * default profile out when they are constructed against an empty store.
 */
class StartupPublishTest {

    /**
     * The published state is a process-wide singleton, so each test puts back the fields it
     * reads. [TelemetryState.clutch] is the exception and cannot be cleared - `setClutch`
     * takes a non-null profile and there is no un-set - which is why the "invents nothing"
     * tests below assert against the store rather than against the flow. That turns out to be
     * the better assertion anyway: it catches the fabrication at the point it would be
     * written to disk, not merely at the point it would be shown.
     */
    @BeforeEach
    fun resetSingleton() {
        TelemetryState.setLifetime(LifetimeStats())
        TelemetryState.setCalibration(FuelCalibrationState())
        TelemetryState.setTank(TankState())
        TelemetryState.setDtcReport(null)
        TelemetryState.setFuelBlend(FuelBlendId.E10)
    }

    private fun publish(
        lifetime: LifetimeStore = InMemoryLifetimeStore(),
        oil: OilProfileStore = InMemoryOilProfileStore(),
        clutch: ClutchProfileStore = InMemoryClutchProfileStore(),
        calibration: FuelCalibrationStore = InMemoryFuelCalibrationStore(),
        blend: FuelBlendId = FuelBlendId.E10,
        tank: TankStore = InMemoryTankStore(),
        dtcReport: DtcReportStore = InMemoryDtcReportStore(),
    ) = publishPersistedRecords(lifetime, oil, clutch, calibration, blend, tank, dtcReport)

    /** A store holding a real profile, without spelling out eighteen fields nobody reads. */
    private fun seededClutchStore(engagements: Int): InMemoryClutchProfileStore {
        val store = InMemoryClutchProfileStore()
        val profile = ClutchHealthEngine(store).getProfile()
        store.save(profile.copy(totalEngagementsCount = engagements))
        return store
    }

    @Nested
    @DisplayName("with records already on disk")
    inner class WithRecords {

        /**
         * The whole point. This is the record that was on the phone and off the screen.
         */
        @Test
        @DisplayName("publishes the clutch profile, so the Clutch screen works parked")
        fun publishesClutch() {
            publish(clutch = seededClutchStore(engagements = 4321))

            val published = TelemetryState.clutch.value
            assertNotNull(published, "clutch profile was on disk and should have been published")
            assertEquals(
                4321,
                published.totalEngagementsCount,
                "published the wrong profile, or a freshly invented one",
            )
        }

        /**
         * Worth more per byte than anything else stored, by the Fuel screen's own reckoning,
         * and read standing at a pump with the ignition off - which is precisely when the
         * service that used to be its only reader is not running.
         */
        @Test
        @DisplayName("publishes the fill calibration, so corrections survive the ignition")
        fun publishesCalibration() {
            val stored = FuelCalibrationState(fills = listOf(FillRecord(detectedAtMillis = 1_700_000_000_000L, pumpGallons = 10.9)))

            publish(calibration = InMemoryFuelCalibrationStore(stored))

            assertEquals(stored, TelemetryState.calibration.value)
        }

        @Test
        @DisplayName("publishes the lifetime record and the chosen blend")
        fun publishesLifetimeAndBlend() {
            val stored = LifetimeStats(totalMiles = 132_004.2)

            publish(lifetime = InMemoryLifetimeStore(stored), blend = FuelBlendId.E15)

            assertEquals(132_004.2, TelemetryState.lifetime.value.totalMiles)
            assertEquals(FuelBlendId.E15, TelemetryState.fuelBlend.value)
        }

        @Test
        @DisplayName("publishes the persisted tank state with learned pump gallons per percent")
        fun publishesTank() {
            val cal = FuelCalibrationState(
                fills = listOf(
                    FillRecord(
                        detectedAtMillis = 1_700_000_000_000L,
                        levelBefore = 11.28,
                        levelAfter = 93.30,
                        pumpGallons = 11.50,
                    ),
                ),
            )
            val storedTank = TankState(
                smoothedLevelPercent = 93.2,
                fullMarkPercent = 93.3,
                milesSinceFill = 30.9,
                gallonsUsedSinceFill = 0.83,
            )

            publish(
                calibration = InMemoryFuelCalibrationStore(cal),
                tank = InMemoryTankStore(storedTank),
            )

            assertEquals(93.2, TelemetryState.tank.value.smoothedLevelPercent)
            assertEquals(30.9, TelemetryState.tank.value.milesSinceFill)
            assertEquals(true, TelemetryState.tank.value.calibrated)
        }

        @Test
        @DisplayName("publishes the last DTC scan report so Codes works parked")
        fun publishesDtcReport() {
            val report = DtcScanReport(timestamp = 1_700_000_000_000L, milOn = false)

            publish(dtcReport = InMemoryDtcReportStore(report))

            assertEquals(report, TelemetryState.dtcReport.value)
        }
    }

    @Nested
    @DisplayName("on a phone the app has never driven")
    inner class WithNothing {

        /**
         * [ClutchHealthEngine]'s constructor writes a default profile out when the store is
         * empty. Reading it without the guard would therefore not merely show invented
         * figures - 100% healthy, capacity as new - it would commit them to disk as though
         * the car had reported them. Deleting the guard makes this test fail.
         */
        @Test
        @DisplayName("invents no clutch record, and writes none")
        fun inventsNoClutchRecord() {
            val clutch = InMemoryClutchProfileStore()

            publish(clutch = clutch)

            assertNull(
                clutch.load(),
                "constructing the engine fabricated a clutch profile for a car it has " +
                    "never seen, and saved it",
            )
        }

        /** The same trap on the oil side, guarded the same way and for the same reason. */
        @Test
        @DisplayName("invents no oil record, and writes none")
        fun inventsNoOilRecord() {
            val oil = InMemoryOilProfileStore()

            publish(oil = oil)

            assertNull(oil.load(), "fabricated an oil profile on a fresh install")
        }

        /** Nothing on disk must leave the defaults alone rather than throwing. */
        @Test
        @DisplayName("leaves the lifetime record and calibration at their defaults")
        fun leavesDefaultsAlone() {
            publish()

            assertEquals(LifetimeStats(), TelemetryState.lifetime.value)
            assertEquals(FuelCalibrationState(), TelemetryState.calibration.value)
        }
    }
}

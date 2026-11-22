package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("Oil life by GM's published method (US 4,847,768)")
class GmOilLifeMethodTest {

    private val start = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun `The penalty factors are the patent's FIG 3`() {
        assertEquals(5.0, GmOilLifeMethod.penaltyFactor(-20.0))
        assertEquals(3.0, GmOilLifeMethod.penaltyFactor(20.0))
        assertEquals(2.0, GmOilLifeMethod.penaltyFactor(60.0))
        assertEquals(1.0, GmOilLifeMethod.penaltyFactor(80.0))
        assertEquals(1.0, GmOilLifeMethod.penaltyFactor(115.0))
        assertEquals(2.0, GmOilLifeMethod.penaltyFactor(140.0))
        assertEquals(8.0, GmOilLifeMethod.penaltyFactor(190.0))
    }

    @Test
    fun `Warm freeway cruising puts the oil in the slowest-ageing band`() {
        // 70 mph in fifth is about 3,000 rpm on this car, with coolant near 90°C.
        val oil = GmOilLifeMethod.equilibriumOilTempC(coolantC = 90.0, rpm = 3_000.0, speedMph = 70.0)
        assertTrue(oil in 100.0..125.0, "got $oil°C")
        assertEquals(1.0, GmOilLifeMethod.penaltyFactor(oil))
    }

    @Test
    fun `The longest possible interval comes from fifth gear`() {
        // 25M revolutions at about 2,520 revolutions a mile in fifth.
        assertEquals(2_520.0, GmOilLifeMethod.TOP_GEAR_REVS_PER_MILE, 5.0)
        assertEquals(9_920.0, GmOilLifeMethod.MAX_INTERVAL_MILES, 25.0)
    }

    @Test
    fun `A short stop leaves the oil cooler than the coolant`() {
        // Ten minutes off with the coolant at 85°C: the oil starts the next drive near 68°C.
        assertEquals(68.0, GmOilLifeMethod.referenceTempC(85.0, minutesOff = 10.0), 0.01)
        // More than an hour off, the two are the same.
        assertEquals(25.0, GmOilLifeMethod.referenceTempC(25.0, minutesOff = 600.0), 0.01)
        assertEquals(25.0, GmOilLifeMethod.referenceTempC(25.0, minutesOff = null), 0.01)
    }

    /**
     * One leg of Robert's commute: 40 miles, starting from cold, mostly freeway. Coolant climbs
     * from [startCoolantC] to 90°C over ten minutes of city driving, then thirty-five minutes
     * at 70 mph.
     */
    private fun commuteLeg(engine: OilLifeEngine, clock: MutableClock, startCoolantC: Double) {
        engine.registerEngineStart(startCoolantC)
        val dt = 1.0
        for (s in 0 until 600) {
            val coolant = startCoolantC + (90.0 - startCoolantC) * s / 600.0
            engine.recordTelemetryStep(2_200.0, coolant, 40.0, 30.0, dt)
            clock.advanceMillis(1_000)
        }
        for (s in 0 until 2_100) {
            engine.recordTelemetryStep(2_700.0, 90.0, 45.0, 64.0, dt)
            clock.advanceMillis(1_000)
        }
        engine.registerEngineStop()
    }

    @Test
    fun `An 80-mile freeway commute reaches 15% at about 6,000 miles, not 4,000`() {
        val clock = MutableClock(start)
        val engine = OilLifeEngine(InMemoryOilProfileStore(), clock)
        engine.resetOilLife(190_000.0)

        // Cool mornings, a car that sat in the sun all day for the drive home.
        var days = 0
        while (engine.getProfile().oilLifePercent > 15.0 && days < 400) {
            commuteLeg(engine, clock, startCoolantC = 15.0)
            clock.advanceMillis(9 * hour)
            commuteLeg(engine, clock, startCoolantC = 30.0)
            clock.advanceMillis(14 * hour)
            days++
        }
        val p = engine.getProfile()
        val miles = p.currentOdometer - p.lastResetOdometer

        // Honda's wrench comes on at 15%. The old model asked for a change at about 4,000
        // miles of this driving (Robert, 2026-11-21); this simulation reaches 15% near 6,200.
        assertTrue(miles in 5_500.0..9_000.0, "reached 15% at $miles miles after $days days")
        // Cold oil on the first few miles of every leg is a real part of the cost.
        assertTrue(p.degradationBreakdown.coldStartPenalty > 5.0, "cold ${p.degradationBreakdown}")
        assertEquals(0.0, p.degradationBreakdown.thermalShearPenalty)
    }

    @Test
    fun `A drive that never warms the oil is booked as a short trip`() {
        val clock = MutableClock(start)
        val engine = OilLifeEngine(InMemoryOilProfileStore(), clock)
        engine.resetOilLife(190_000.0)

        engine.registerEngineStart(15.0)
        repeat(300) {
            engine.recordTelemetryStep(1_800.0, 40.0, 30.0, 25.0, 1.0)
            clock.advanceMillis(1_000)
        }
        engine.registerEngineStop()

        val p = engine.getProfile()
        assertTrue(p.shortTripExtraRevolutions > 0.0)
        assertEquals(0.0, p.coldExtraRevolutions)
        assertTrue(p.weightedRevolutions > p.accumulatedRevolutions * 2.0)
    }

    @Test
    fun `Oil a year old is due whatever the count says`() {
        val clock = MutableClock(start)
        val engine = OilLifeEngine(InMemoryOilProfileStore(), clock)
        engine.resetOilLife(190_000.0)

        clock.advanceMillis(366L * 24 * hour)
        engine.recordTelemetryStep(800.0, 90.0, 20.0, 0.0, 1.0)

        assertEquals(0.0, engine.getProfile().oilLifePercent)
        assertEquals(0, engine.getProfile().estimatedMilesRemaining)
    }

    @Test
    fun `A record from the old model carries its revolutions over at factor 1`() {
        val old = OilLifeProfile(
            lastResetTimestamp = start - 30L * 24 * hour,
            lastResetOdometer = 190_000.0,
            currentOdometer = 192_000.0,
            oilLifePercent = 40.0,
            accumulatedRevolutions = 5_000_000.0,
            coldStartsCount = 40,
            timeBelowOperatingTempSec = 20_000.0,
            shortTripsCount = 3,
            highThermalStressSec = 0.0,
            estimatedMilesRemaining = 2_000,
            estimatedDaysRemaining = 30,
            oilConditionGrade = OilConditionGrade.GOOD,
            degradationBreakdown = DegradationBreakdown(0.0, 0.0, 0.0, 0.0),
        )
        val p = OilLifeEngine(InMemoryOilProfileStore(old), MutableClock(start)).getProfile()

        assertEquals(5_000_000.0, p.weightedRevolutions)
        assertEquals(80.0, p.oilLifePercent)
    }
}

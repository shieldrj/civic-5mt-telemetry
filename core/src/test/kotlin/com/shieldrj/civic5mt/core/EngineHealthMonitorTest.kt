package com.shieldrj.civic5mt.core

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineHealthMonitorTest {

    /** One warm cruising tick, with the trim reading stamped [trimAt] (null: never read). */
    private fun EngineHealthMonitor.cruise(stft: Double, ltft: Double, trimAt: Long?) = observe(
        rpm = 2000.0,
        speedKmh = 70.0,
        coolantF = 186,
        throttlePos = 22.0,
        stft = stft,
        ltft = ltft,
        fuelSystemStatus = 0x02,
        trimSampledAtMillis = trimAt,
        outsideAirC = 20.0,
        dtSec = 1.0,
    )

    @Test
    @DisplayName("ignores brief fuel-trim transients, flags sustained positive trim drift")
    fun sustainedLeanFuelTrim() {
        val monitor = EngineHealthMonitor()

        // A fresh +14% reading every fifteen seconds, as the slow rotation delivers them.
        // Thirty seconds and three readings in, that is still not forty-five seconds.
        for (sec in 0 until 31) monitor.cruise(stft = 2.0, ltft = 12.0, trimAt = sec / 15 * 15_000L)
        assertNull(monitor.activeIssue)

        for (sec in 31 until 50) monitor.cruise(stft = 2.0, ltft = 12.0, trimAt = sec / 15 * 15_000L)
        val issue = assertIs<EngineIssue.LeanFuelTrim>(monitor.activeIssue)
        val status = assertNotNull(engineIssueHealthStatus(issue))
        assertEquals(HealthLevel.ADVISORY, status.level)
        assertTrue(status.summary.startsWith("LEAN FUEL MIXTURE"))
    }

    @Test
    @DisplayName("one trim reading carried forward through a slow poll is one reading, not 45 seconds")
    fun carriedForwardTrimSnapshotIsNotSustained() {
        val monitor = EngineHealthMonitor()

        // 2026-10-26: one +16% total-trim snapshot on a tip-in, then nothing new for the
        // forty-two cycles until the trims came round again. Counting ticks made that a warning.
        repeat(60) { monitor.cruise(stft = 8.0, ltft = 8.0, trimAt = 1_000L) }
        assertNull(monitor.activeIssue)
    }

    @Test
    @DisplayName("lean readings broken up by in-between ones never add up across a drive")
    fun intermittentLeanReadingsDoNotAccumulate() {
        val monitor = EngineHealthMonitor()

        // Ten minutes of readings that alternate between +16% and +12% total trim. The old
        // counter held through the +12% ones and banked every +16% stretch until it fired.
        for (sec in 0 until 600) {
            val reading = sec / 15
            val stft = if (reading % 2 == 0) 8.0 else 4.0
            monitor.cruise(stft = stft, ltft = 8.0, trimAt = reading * 15_000L)
        }
        assertNull(monitor.activeIssue)
    }

    @Test
    @DisplayName("flags a thermostat stuck open when coolant stays below 162°F after 12 min of road driving")
    fun thermostatStuckOpen() {
        val monitor = EngineHealthMonitor()

        // 13 minutes (780 seconds) at 45 mph (72 km/h) with coolant stuck at 154°F
        repeat(780) {
            monitor.observe(
                rpm = 2100.0,
                speedKmh = 72.0,
                coolantF = 154,
                throttlePos = 20.0,
                stft = 0.0,
                ltft = 0.0,
                fuelSystemStatus = 0x02,
                trimSampledAtMillis = null,
                outsideAirC = 15.0,
                dtSec = 1.0,
            )
        }

        val issue = assertIs<EngineIssue.ThermostatCold>(monitor.activeIssue)
        assertEquals(154, issue.coolantF)
        val status = assertNotNull(engineIssueHealthStatus(issue))
        assertEquals(HealthLevel.ADVISORY, status.level)
        assertTrue(status.summary.startsWith("RUNNING TOO COLD"))
    }

    @Test
    @DisplayName("flags warm idle hunting when stationary RPM swings widely for 20+ seconds")
    fun roughWarmIdle() {
        val monitor = EngineHealthMonitor()

        // Alternate between 520 RPM and 830 RPM at warm idle for 25 seconds (3s settle + 22s hunting)
        repeat(25) { idx ->
            val rpm = if (idx % 2 == 0) 520.0 else 830.0
            monitor.observe(
                rpm = rpm,
                speedKmh = 0.0,
                coolantF = 186,
                throttlePos = 14.0,
                stft = 0.0,
                ltft = 0.0,
                fuelSystemStatus = 0x02,
                trimSampledAtMillis = null,
                outsideAirC = 20.0,
                dtSec = 1.0,
            )
        }

        val issue = assertIs<EngineIssue.RoughIdle>(monitor.activeIssue)
        assertEquals(520, issue.minRpm)
        assertEquals(830, issue.maxRpm)
        val status = assertNotNull(engineIssueHealthStatus(issue))
        assertEquals(HealthLevel.ADVISORY, status.level)
        assertTrue(status.summary.startsWith("ROUGH WARM IDLE"))
    }

    @Test
    @DisplayName("surfaces pending and confirmed DTCs in VehicleHealthStatus")
    fun dtcHealthBanner() {
        val p0171 = ScannedDtc(
            code = "P0171",
            type = DtcStatusType.PENDING,
            details = HONDA_DTC_DATABASE["P0171"]!!,
        )
        val pendingReport = DtcScanReport(
            timestamp = 1_700_000_000_000L,
            milOn = false,
            pendingCodes = listOf(p0171),
        )
        val status = assertNotNull(dtcHealthStatus(pendingReport))
        assertEquals(HealthLevel.ADVISORY, status.level)
        assertEquals("PENDING FAULT · P0171", status.summary)

        val p0300Confirmed = ScannedDtc(
            code = "P0300",
            type = DtcStatusType.CONFIRMED,
            details = HONDA_DTC_DATABASE["P0300"]!!,
        )
        val critReport = DtcScanReport(
            timestamp = 1_700_000_000_000L,
            milOn = true,
            confirmedCodes = listOf(p0300Confirmed),
        )
        val critStatus = assertNotNull(dtcHealthStatus(critReport))
        assertEquals(HealthLevel.CRITICAL, critStatus.level)
        assertEquals("FAULT CODE · P0300", critStatus.summary)
    }
}

package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Watches whether the engine is running right across a drive, and stays quiet while it is.
 *
 * The Drive screen has one health banner (`ALL SYSTEMS OK`). Putting five live sensor numbers
 * on a dashboard - fuel trims, coolant regulation, idle stability, readiness bits - asks the
 * driver to be the diagnostician while driving a manual car. On the other hand, waiting for a
 * check engine light waits until a fault has failed two full drive cycles in a row, and misses
 * the things that never light the dash at all (a thermostat stuck slightly open at 158°F, a
 * throttle body hunting at warm idle, or a +13% fuel trim from a cracked intake boot).
 *
 * Like [ChargingMonitor], every check here is a duration rather than a single sample:
 *
 *  - **Pending or confirmed fault codes** ([dtcHealthStatus]): a Mode 07 pending code is a
 *    fault the ECU has already spotted once before lighting the dashboard.
 *  - **Sustained fuel trim drift**: a single frame at +18% happens on a throttle tip-in and
 *    means nothing. Holding +12% long-term trim (or ±16% total trim) for forty-five seconds of
 *    warm closed-loop running means the ECU is continuously compensating for unmetered air or
 *    fuel delivery drift.
 *  - **Thermostat regulation**: the R18Z1 thermostat opens around 172–180°F and holds
 *    178–198°F. Failing to reach 162°F after twelve minutes at road speed, or dropping back
 *    under 160°F for ninety seconds on the highway after warming up, is a thermostat stuck
 *    open - which rarely sets a code and quietly costs MPG and oil life.
 *  - **Warm idle stability**: once warmed up and stopped in neutral for more than three
 *    seconds, the R18Z1 idles near 700 RPM within ±15 RPM. Hunting across a 180+ RPM band for
 *    twenty seconds points to throttle-body carbon buildup, a vacuum leak, or worn plugs.
 */
object EngineHealthRules {

    /** Long-term trim at or beyond ±12% in warm closed loop is a real mixture offset. */
    const val LTFT_WARN_PERCENT: Double = 12.0

    /** Combined short + long trim at or beyond ±16% in warm closed loop. */
    const val TOTAL_TRIM_WARN_PERCENT: Double = 16.0

    /** Seconds of warm closed-loop running before a trim offset becomes a warning. */
    const val TRIM_SUSTAIN_SEC: Double = 45.0

    /** Coolant temperature (°F) at which the R18Z1 is considered warmed up. */
    const val WARM_COOLANT_F: Int = 175

    /** Below this (°F) after twelve minutes of road-speed driving is a cold-running thermostat. */
    const val COLD_THERMOSTAT_F: Int = 162

    /** Below this (°F) for ninety seconds after already warming up means temperature collapsed. */
    const val DROPPED_THERMOSTAT_F: Int = 160

    /** Road-speed driving seconds before judging that the engine never reached operating temp. */
    const val WARMUP_ROAD_DRIVE_SEC: Double = 720.0

    /** Highway overcooling sustain seconds after the engine had already reached [WARM_COOLANT_F]. */
    const val OVERCOOL_SUSTAIN_SEC: Double = 90.0

    /** Seconds stopped at warm idle before measuring RPM hunting, so declutch settling is ignored. */
    const val IDLE_SETTLE_SEC: Double = 3.0

    /** Seconds of continuous hunting at warm idle before reporting rough idle. */
    const val IDLE_ROUGH_SUSTAIN_SEC: Double = 20.0

    /** Average sample-to-sample RPM swing during warm idle that counts as hunting. */
    const val IDLE_HUNT_AVG_DELTA_RPM: Double = 45.0

    /** Min-to-max RPM spread during warm idle that counts as hunting. */
    const val IDLE_HUNT_SPAN_RPM: Double = 180.0

    /** Oil life at or below this percentage surfaces a service advisory on the banner. */
    const val OIL_SERVICE_DUE_PERCENT: Double = 10.0
}

sealed interface EngineIssue {
    data class LeanFuelTrim(val trimPercent: Int) : EngineIssue
    data class RichFuelTrim(val trimPercent: Int) : EngineIssue
    data class ThermostatCold(val coolantF: Int) : EngineIssue
    data class RoughIdle(val minRpm: Int, val maxRpm: Int) : EngineIssue
}

class EngineHealthMonitor {
    private var leanSustainSec: Double = 0.0
    private var richSustainSec: Double = 0.0
    private var lastReportedTrimPct: Int = 0

    private var roadDriveSec: Double = 0.0
    private var reachedOperatingTemp: Boolean = false
    private var overcooledSec: Double = 0.0

    private var stationaryIdleSec: Double = 0.0
    private var huntingIdleSec: Double = 0.0
    private var lastIdleRpm: Double? = null
    private var idleDeltaEma: Double = 0.0
    private var idleMinRpm: Double = Double.MAX_VALUE
    private var idleMaxRpm: Double = 0.0

    var activeIssue: EngineIssue? = null
        private set

    fun observe(
        rpm: Double,
        speedKmh: Double,
        coolantF: Int,
        throttlePos: Double,
        stft: Double,
        ltft: Double,
        fuelSystemStatus: Int?,
        outsideAirC: Double?,
        dtSec: Double,
    ): EngineIssue? {
        if (rpm < CivicSpecs.ENGINE_RUNNING_RPM || dtSec <= 0.0) {
            stationaryIdleSec = 0.0
            lastIdleRpm = null
            return activeIssue
        }

        // 1. Thermostat & warm-up tracking
        if (coolantF >= EngineHealthRules.WARM_COOLANT_F) {
            reachedOperatingTemp = true
        }
        val movingOnRoad = speedKmh >= 40.0 && rpm >= 1200.0
        val notExtremeFreeze = outsideAirC == null || outsideAirC >= -9.0
        if (movingOnRoad && notExtremeFreeze) {
            roadDriveSec += dtSec
        }

        if (reachedOperatingTemp && movingOnRoad && notExtremeFreeze &&
            coolantF in 33..EngineHealthRules.DROPPED_THERMOSTAT_F
        ) {
            overcooledSec += dtSec
        } else if (coolantF >= EngineHealthRules.WARM_COOLANT_F - 5) {
            overcooledSec = 0.0
        }

        // 2. Sustained closed-loop fuel trim tracking
        val warmEnoughForTrim = coolantF >= 158
        val closedLoop = fuelSystemStatus == null || isClosedLoop(fuelSystemStatus)
        val totalTrim = stft + ltft
        if (warmEnoughForTrim && closedLoop) {
            val isLean = ltft >= EngineHealthRules.LTFT_WARN_PERCENT ||
                totalTrim >= EngineHealthRules.TOTAL_TRIM_WARN_PERCENT
            val isRich = ltft <= -EngineHealthRules.LTFT_WARN_PERCENT ||
                totalTrim <= -EngineHealthRules.TOTAL_TRIM_WARN_PERCENT

            if (isLean) {
                leanSustainSec += dtSec
                richSustainSec = 0.0
                lastReportedTrimPct = max(ltft, totalTrim).roundToInt()
            } else if (isRich) {
                richSustainSec += dtSec
                leanSustainSec = 0.0
                lastReportedTrimPct = min(ltft, totalTrim).roundToInt()
            } else if (abs(ltft) < 8.0 && abs(totalTrim) < 10.0) {
                leanSustainSec = max(0.0, leanSustainSec - dtSec * 0.5)
                richSustainSec = max(0.0, richSustainSec - dtSec * 0.5)
            }
        }

        // 3. Warm idle stability tracking
        val isWarmStationaryIdle = coolantF >= EngineHealthRules.WARM_COOLANT_F &&
            speedKmh < 1.0 &&
            throttlePos < 18.0 &&
            rpm in 420.0..1150.0

        if (isWarmStationaryIdle) {
            stationaryIdleSec += dtSec
            if (stationaryIdleSec >= EngineHealthRules.IDLE_SETTLE_SEC) {
                val prev = lastIdleRpm
                if (prev != null && dtSec <= 1.0) {
                    val delta = abs(rpm - prev)
                    idleDeltaEma = if (idleDeltaEma == 0.0) delta else idleDeltaEma * 0.8 + delta * 0.2
                }
                lastIdleRpm = rpm
                idleMinRpm = min(idleMinRpm, rpm)
                idleMaxRpm = max(idleMaxRpm, rpm)

                val span = if (idleMaxRpm >= idleMinRpm) idleMaxRpm - idleMinRpm else 0.0
                val isHunting = (idleDeltaEma >= EngineHealthRules.IDLE_HUNT_AVG_DELTA_RPM &&
                    span >= EngineHealthRules.IDLE_HUNT_SPAN_RPM) || rpm < 520.0

                if (isHunting) {
                    huntingIdleSec += dtSec
                } else if (idleDeltaEma < 20.0) {
                    huntingIdleSec = max(0.0, huntingIdleSec - dtSec * 0.5)
                }
            }
        } else {
            stationaryIdleSec = 0.0
            lastIdleRpm = null
            idleDeltaEma = 0.0
            idleMinRpm = Double.MAX_VALUE
            idleMaxRpm = 0.0
        }

        activeIssue = when {
            leanSustainSec >= EngineHealthRules.TRIM_SUSTAIN_SEC ->
                EngineIssue.LeanFuelTrim(lastReportedTrimPct)

            richSustainSec >= EngineHealthRules.TRIM_SUSTAIN_SEC ->
                EngineIssue.RichFuelTrim(lastReportedTrimPct)

            overcooledSec >= EngineHealthRules.OVERCOOL_SUSTAIN_SEC ||
                (!reachedOperatingTemp &&
                    roadDriveSec >= EngineHealthRules.WARMUP_ROAD_DRIVE_SEC &&
                    coolantF in 33..EngineHealthRules.COLD_THERMOSTAT_F) ->
                EngineIssue.ThermostatCold(coolantF)

            huntingIdleSec >= EngineHealthRules.IDLE_ROUGH_SUSTAIN_SEC &&
                idleMaxRpm > idleMinRpm ->
                EngineIssue.RoughIdle(idleMinRpm.roundToInt(), idleMaxRpm.roundToInt())

            else -> null
        }

        return activeIssue
    }

    fun resetForDrive() {
        leanSustainSec = 0.0
        richSustainSec = 0.0
        lastReportedTrimPct = 0
        roadDriveSec = 0.0
        reachedOperatingTemp = false
        overcooledSec = 0.0
        stationaryIdleSec = 0.0
        huntingIdleSec = 0.0
        lastIdleRpm = null
        idleDeltaEma = 0.0
        idleMinRpm = Double.MAX_VALUE
        idleMaxRpm = 0.0
        activeIssue = null
    }
}

/**
 * Converts a stored or newly scanned [DtcScanReport] into a banner status when a code is present.
 * Returns null when there are no confirmed or pending codes and the MIL is off.
 */
fun dtcHealthStatus(report: DtcScanReport?): VehicleHealthStatus? {
    if (report == null) return null

    val confirmed = report.confirmedCodes.firstOrNull()
    if (confirmed != null) {
        val level = if (confirmed.details.severity == DtcSeverity.CRITICAL || report.milOn) {
            HealthLevel.CRITICAL
        } else {
            HealthLevel.ADVISORY
        }
        val extraCount = report.confirmedCodes.size - 1
        val suffix = if (extraCount > 0) " (+$extraCount more)" else ""
        return VehicleHealthStatus(
            level = level,
            summary = "FAULT CODE · ${confirmed.code}$suffix",
            detail = "${confirmed.details.title}. " +
                (confirmed.details.civicSpecificNotes ?: confirmed.details.description),
        )
    }

    if (report.milOn) {
        return VehicleHealthStatus(
            level = HealthLevel.CRITICAL,
            summary = "CHECK ENGINE LIGHT ON",
            detail = "The ECU has lit the check engine light. Tap to view diagnostics.",
        )
    }

    val pending = report.pendingCodes.firstOrNull()
    if (pending != null) {
        val extraCount = report.pendingCodes.size - 1
        val suffix = if (extraCount > 0) " (+$extraCount more)" else ""
        return VehicleHealthStatus(
            level = HealthLevel.ADVISORY,
            summary = "PENDING FAULT · ${pending.code}$suffix",
            detail = "Early warning (no dash light yet): ${pending.details.title}. " +
                (pending.details.civicSpecificNotes ?: pending.details.description),
        )
    }

    return null
}

/**
 * Converts a sustained [EngineIssue] into a banner status and plain-English explanation.
 */
fun engineIssueHealthStatus(issue: EngineIssue?): VehicleHealthStatus? = when (issue) {
    is EngineIssue.LeanFuelTrim -> VehicleHealthStatus(
        level = HealthLevel.ADVISORY,
        summary = "LEAN FUEL MIXTURE · +${issue.trimPercent}%",
        detail = "ECU is consistently adding +${issue.trimPercent}% fuel. Check for a cracked " +
            "rubber intake boot, vacuum leak, or dirty MAF sensor.",
    )

    is EngineIssue.RichFuelTrim -> VehicleHealthStatus(
        level = HealthLevel.ADVISORY,
        summary = "RICH FUEL MIXTURE · ${issue.trimPercent}%",
        detail = "ECU is consistently pulling ${issue.trimPercent}% fuel back. Check for a " +
            "clogged air filter, leaking evap purge valve, or sticking fuel injector.",
    )

    is EngineIssue.ThermostatCold -> VehicleHealthStatus(
        level = HealthLevel.ADVISORY,
        summary = "RUNNING TOO COLD · ${issue.coolantF}°F",
        detail = "Coolant stayed at ${issue.coolantF}°F instead of 178–198°F after driving at " +
            "road speed. A thermostat stuck open reduces MPG and increases engine wear.",
    )

    is EngineIssue.RoughIdle -> VehicleHealthStatus(
        level = HealthLevel.ADVISORY,
        summary = "ROUGH WARM IDLE · ${issue.minRpm}–${issue.maxRpm} RPM",
        detail = "Warm idle is hunting between ${issue.minRpm} and ${issue.maxRpm} RPM instead " +
            "of holding a steady ~700 RPM. Check for throttle body carbon buildup or worn spark plugs.",
    )

    null -> null
}

/**
 * Persists the last [DtcScanReport] so the Health -> Codes screen remembers the last scan
 * after the ignition is switched off.
 */
interface DtcReportStore {
    fun load(): DtcScanReport?
    fun save(report: DtcScanReport)
}

class InMemoryDtcReportStore(private var stored: DtcScanReport? = null) : DtcReportStore {
    override fun load(): DtcScanReport? = stored
    override fun save(report: DtcScanReport) {
        stored = report
    }
}

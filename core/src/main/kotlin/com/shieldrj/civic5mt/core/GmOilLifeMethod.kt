package com.shieldrj.civic5mt.core

/**
 * The published oil-life method: GM's US patent 4,847,768 (1989), "Automatic engine oil
 * change indicator system", which builds on the Schwartz et al. revolution-counting patent.
 *
 * Honda never published how the Maintenance Minder works. GM did, with numbers, so this is
 * the method with a real source behind every constant. Every figure here was read from the
 * patent's scanned text or drawings; the drawings are cited by figure.
 *
 * The idea: oil ages per crank revolution, and how fast depends on oil temperature. Cold oil
 * collects fuel and water; very hot oil oxidises. Between about 80 and 130°C it ages slowest.
 * Each revolution is multiplied by a penalty factor for the oil temperature it happened at
 * (FIG. 3), and the oil is spent when the weighted total reaches the budget.
 *
 * This Civic does not report oil temperature (no PID 5C), so it is estimated from coolant
 * temperature, engine speed and road speed, the way the patent does when there is no sensor.
 */
object GmOilLifeMethod {

    /**
     * The life budget in penalty-weighted revolutions. Patent: "the engine oil was found to
     * have a maximum useful life of 25,000,000 engine revolutions, which corresponds to
     * 12,000 miles". Measured on 1980s oil; today's 0W-20 lasts at least as long, so this
     * errs early, not late. Used-oil analysis is the way to tune it for this engine.
     */
    const val BUDGET_REVOLUTIONS: Double = 25_000_000.0

    /** Patent: the change-oil lamp lights when the count is "within 10%" of its maximum. */
    const val CHANGE_SOON_PERCENT: Double = 10.0

    /** Patent: a year after the last change, the oil is due whatever the count says. */
    const val MAX_AGE_DAYS: Double = 365.0

    /** Oil at or above this is "warm", and the estimate switches to the equilibrium equation. */
    const val WARM_OIL_C: Double = 80.0

    /** Oil above this ages faster again (FIG. 3: factor 1 ends at about 130°C). */
    const val HOT_OIL_C: Double = 130.0

    // ── Warm-up: To = k1 + k2·Tr + k3·Ff ─────────────────────────────────────────────
    /** Patent: k1 varies "from 0 to -18 degrees C ... depending on the engine". Midpoint. */
    const val K1_C: Double = -9.0

    /** Patent: k3 = 0.00154 °C per firing "for all engines". k2 = 1. */
    const val K3_C_PER_FIRING: Double = 0.00154

    /** A four-cylinder four-stroke fires twice per crank revolution. */
    const val FIRINGS_PER_REV: Double = 2.0

    // ── Short time off: Tr = c1 + c2·Tc + c3·Dt ──────────────────────────────────────
    /** Patent: after more than an hour off, oil and coolant are about the same temperature. */
    const val SHORT_OFF_MINUTES: Double = 60.0

    /** Patent: c1 = -20 °C, c2 = 1. */
    const val C1_C: Double = -20.0

    /** Patent: c3 = 0.3 °C per minute off. */
    const val C3_C_PER_MIN: Double = 0.3

    // ── Equilibrium, vehicle-speed form: To = k4 + k5·Te + k6·Se + k7·Fc ─────────────
    /** Patent: k4 "variable depending on engine from -1 to -12 degrees C". Midpoint. */
    const val K4_C: Double = -6.5

    /** Patent: k6 = 0.0114 °C per rpm. k5 = 1. */
    const val K6_C_PER_RPM: Double = 0.0114

    /** Patent: k7 = -0.0213 °C per km/h - road speed cools the engine a little. */
    const val K7_C_PER_KPH: Double = -0.0213

    private const val KM_PER_MILE = 1.609344

    /**
     * Crank revolutions per mile in fifth gear. Revolutions per mile in a gear do not depend
     * on speed, and fifth is the tallest gear, so no mile can cost fewer revolutions than
     * this. Dividing it into the budget gives the longest interval the method can produce.
     */
    val TOP_GEAR_REVS_PER_MILE: Double =
        KM_PER_MILE / CivicSpecs.TIRE_CIRCUMFERENCE_KM *
            CivicSpecs.GEAR_RATIOS.getValue(5) * CivicSpecs.FINAL_DRIVE_RATIO

    /** The longest possible interval: the whole budget spent in fifth with warm oil. */
    val MAX_INTERVAL_MILES: Double = BUDGET_REVOLUTIONS / TOP_GEAR_REVS_PER_MILE

    /**
     * FIG. 3, trace 62, read off the patent drawing: how many revolutions' worth of life one
     * revolution costs at a given oil temperature.
     */
    fun penaltyFactor(oilTempC: Double): Double = when {
        oilTempC < -10.0 -> 5.0
        oilTempC < 0.0 -> 4.0
        oilTempC < 40.0 -> 3.0
        oilTempC < WARM_OIL_C -> 2.0
        oilTempC < HOT_OIL_C -> 1.0
        oilTempC < 150.0 -> 2.0
        oilTempC < 160.0 -> 3.0
        oilTempC < 165.0 -> 4.0
        oilTempC < 170.0 -> 5.0
        oilTempC < 175.0 -> 6.0
        oilTempC < 180.0 -> 7.0
        else -> 8.0
    }

    /**
     * The oil temperature the warm-up starts from. Coolant at engine start, corrected when
     * the engine was off less than an hour: the coolant holds its heat, the oil does not.
     */
    fun referenceTempC(startCoolantC: Double, minutesOff: Double?): Double =
        if (minutesOff != null && minutesOff in 0.0..SHORT_OFF_MINUTES) {
            C1_C + startCoolantC + C3_C_PER_MIN * minutesOff
        } else {
            startCoolantC
        }

    /** Warm-up: oil rises in proportion to the firings since the engine started. */
    fun warmupOilTempC(referenceTempC: Double, revsSinceStart: Double): Double =
        K1_C + referenceTempC + K3_C_PER_FIRING * FIRINGS_PER_REV * revsSinceStart

    /** Equilibrium: coolant temperature, plus engine speed, less a little for road speed. */
    fun equilibriumOilTempC(coolantC: Double, rpm: Double, speedMph: Double): Double =
        K4_C + coolantC + K6_C_PER_RPM * rpm + K7_C_PER_KPH * speedMph * KM_PER_MILE
}

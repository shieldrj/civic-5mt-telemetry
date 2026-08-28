package com.shieldrj.civic5mt.core

const val LITERS_PER_US_GALLON: Double = 3.785411784

/** US fluid ounces in a US gallon. Small fuel volumes read in ounces, not millilitres. */
const val OUNCES_PER_US_GALLON: Double = 128.0

/**
 * Pure-component fuel properties, at 15°C.
 *
 * Stoichiometric ratios here are the standard mass-basis figures: burning 1 kg of fuel
 * completely consumes this many kg of air. Ethanol carries its own oxygen, so it needs
 * far less air than gasoline - which is exactly why the blend matters to a MAF-based
 * fuel calculation. Gasoline is a mixture rather than one molecule, so 14.7 is the
 * conventional figure for pump-grade hydrocarbon rather than octane's ideal 15.1.
 */
object FuelComponents {
    const val GASOLINE_DENSITY_G_PER_L: Double = 745.0
    const val ETHANOL_DENSITY_G_PER_L: Double = 789.3
    const val GASOLINE_STOICH_AFR: Double = 14.7
    const val ETHANOL_STOICH_AFR: Double = 9.0
}

enum class FuelBlendId { E0, E10, E15 }

data class FuelBlendProperties(
    val id: FuelBlendId,
    val label: String,
    val ethanolByVolume: Double,
    val ethanolByMass: Double,
    val stoichAfr: Double,
    val densityGramsPerLiter: Double,
    val densityGramsPerGallon: Double,
)

/**
 * Derives a blend's density and stoichiometric AFR from its ethanol content.
 *
 * Blends are specified by VOLUME (E10 is 10% ethanol by volume) but combustion is a mass
 * relationship, so the volume fraction is converted to a mass fraction using each
 * component's density before the ratios are combined.
 *
 * The combination is a reciprocal (harmonic) sum, not a straight average. Air demand per
 * unit fuel mass is what adds linearly, and that is 1/AFR - so the blend's air demand is
 * w_gas/AFR_gas + w_eth/AFR_eth and its AFR is the reciprocal of that total. Averaging the
 * two AFRs directly is a common shortcut and gives roughly 14.1 for E10, but it is
 * dimensionally wrong; the mass-correct answer is closer to 13.8, and it is the number the
 * fuel mass calculation actually needs.
 */
fun deriveFuelBlend(ethanolByVolume: Double): DerivedBlend {
    val v = ethanolByVolume.coerceIn(0.0, 1.0)
    val gasolineMass = (1 - v) * FuelComponents.GASOLINE_DENSITY_G_PER_L
    val ethanolMass = v * FuelComponents.ETHANOL_DENSITY_G_PER_L
    val totalMass = gasolineMass + ethanolMass

    val ethanolByMass = ethanolMass / totalMass
    val gasolineByMass = gasolineMass / totalMass

    val airPerFuelMass =
        gasolineByMass / FuelComponents.GASOLINE_STOICH_AFR +
            ethanolByMass / FuelComponents.ETHANOL_STOICH_AFR

    return DerivedBlend(
        ethanolByMass = ethanolByMass,
        stoichAfr = 1 / airPerFuelMass,
        densityGramsPerLiter = totalMass,
        densityGramsPerGallon = totalMass * LITERS_PER_US_GALLON,
    )
}

data class DerivedBlend(
    val ethanolByMass: Double,
    val stoichAfr: Double,
    val densityGramsPerLiter: Double,
    val densityGramsPerGallon: Double,
)

private fun buildBlend(id: FuelBlendId, label: String, ethanolByVolume: Double): FuelBlendProperties {
    val d = deriveFuelBlend(ethanolByVolume)
    return FuelBlendProperties(
        id = id,
        label = label,
        ethanolByVolume = ethanolByVolume,
        ethanolByMass = d.ethanolByMass,
        stoichAfr = d.stoichAfr,
        densityGramsPerLiter = d.densityGramsPerLiter,
        densityGramsPerGallon = d.densityGramsPerGallon,
    )
}

val FUEL_BLENDS: Map<FuelBlendId, FuelBlendProperties> = mapOf(
    FuelBlendId.E0 to buildBlend(FuelBlendId.E0, "Ethanol-free (E0)", 0.0),
    FuelBlendId.E10 to buildBlend(FuelBlendId.E10, "Regular pump gas (E10)", 0.1),
    FuelBlendId.E15 to buildBlend(FuelBlendId.E15, "E15 / 88 octane", 0.15),
)

/** Every blend is in the map above, so this never returns null for a real enum value. */
fun fuelBlend(id: FuelBlendId): FuelBlendProperties = FUEL_BLENDS.getValue(id)

/** US retail pump gasoline is E10 almost everywhere, which is what this car runs. */
val DEFAULT_FUEL_BLEND: FuelBlendId = FuelBlendId.E10

/**
 * 2013 Honda Civic LX 5-Speed Manual (R18Z1 Engine) Specifications & Physical Constants
 */
object CivicSpecs {
    // Engine
    const val ENGINE_DISPLACEMENT_LITERS: Double = 1.798
    const val ENGINE_NAME: String = "1.8L SOHC 16-valve i-VTEC (R18Z1)"
    const val CYLINDERS: Int = 4
    const val REDLINE_RPM: Int = 6700
    const val REV_LIMITER_RPM: Int = 6800
    const val IDLE_RPM: Int = 750

    /**
     * Above this the engine is turning under its own power rather than cranking, or coasting
     * to a stop.
     *
     * The oil model uses it to decide whether a tick counts as engine running time at all,
     * and the manager uses it to decide when a coolant reading is real enough to judge a cold
     * start by. Both had the number written out separately, which is one figure in two places
     * waiting to disagree.
     */
    const val ENGINE_RUNNING_RPM: Double = 400.0
    const val VTEC_SWITCH_RPM: Int = 4800 // Dynamic i-VTEC economy-to-power cam switch

    // 5-Speed Manual Transmission Gear Ratios
    val GEAR_RATIOS: Map<Int, Double> = mapOf(
        1 to 3.143,
        2 to 1.870,
        3 to 1.235,
        4 to 0.949,
        5 to 0.727,
    )
    const val REVERSE_RATIO: Double = 3.307
    const val FINAL_DRIVE_RATIO: Double = 4.294

    // Tire & Wheel Specifications (Stock 195/65R15)
    const val TIRE_WIDTH_MM: Int = 195
    const val TIRE_ASPECT_RATIO: Int = 65
    const val RIM_DIAMETER_INCHES: Int = 15
    // Calculated tire diameter ~634.5 mm (0.6345 m), circumference = pi * D = ~1.9933 m.
    // Kept in kilometres only. The same figure in miles used to sit beside it as a second
    // literal, which is a rounding error waiting to disagree with this one - convert at the
    // point of use instead.
    const val TIRE_CIRCUMFERENCE_KM: Double = 0.0019933

    // Physical Vehicle Weights
    const val CURB_WEIGHT_KG: Int = 1247 // ~2,750 lbs
    const val DRAG_COEFFICIENT_CD: Double = 0.29
    const val FRONTAL_AREA_M2: Double = 2.1

    // Fuel Constants
    //
    // Fuel *properties* are deliberately not here. Density and stoichiometric ratio depend
    // on the blend in the tank, so they live in FUEL_BLENDS and nowhere else. This object
    // used to also carry a gasoline density of 736.5 g/L and a stoichiometric AFR of 14.7,
    // left over from before blends existed - both unused, and both disagreeing with the
    // blend model, which puts pure gasoline at 745.0 g/L. Two numbers for one physical
    // quantity is how the next person picks the wrong one.
    const val GAS_PRICE_DEFAULT_DOLLARS_PER_GALLON: Double = 3.45
    const val FUEL_TANK_CAPACITY_GALLONS: Double = 13.2 // 2013 Civic LX sedan factory tank spec

    /** Fallback multiplier for range-to-empty before a rolling MPG sample exists. */
    const val EPA_COMBINED_MPG_DEFAULT: Double = 32.0

    /**
     * The telemetry loop's period. A spec rather than a literal in the tick loop because
     * the rolling-MPG window is measured in samples, not seconds - it has to divide by
     * this to mean anything. They disagreed before: the buffer was sized 600 with a comment
     * claiming 30 seconds at 20Hz, while the loop has always run at 80ms, making the
     * "30 second" average a 48 second one.
     */
    const val TELEMETRY_TICK_MS: Int = 80

    // Shift Point Tuning
    val ECO_SHIFT_POINTS: Map<Int, Int> = mapOf(
        1 to 2200, // 1st -> 2nd
        2 to 2100, // 2nd -> 3rd
        3 to 2000, // 3rd -> 4th
        4 to 1950, // 4th -> 5th
    )
    const val POWER_SHIFT_POINT_RPM: Int = 6500 // Near peak horsepower (6,500 RPM @ 143 hp)

    // Clutch & Manual Transmission Physical Constants (2013 Civic 5MT / R18Z1)
    const val CLUTCH_DISC_DIAMETER_MM: Int = 212 // Stock Exedy / Honda OEM 212mm disc
    const val CLUTCH_MEAN_RADIUS_METERS: Double = 0.088 // Effective friction radius Rm ~88mm
    const val CLUTCH_NOMINAL_CLAMPING_FORCE_N: Double = 4500.0 // Diaphragm spring nominal clamp load
    const val CLUTCH_NOMINAL_FRICTION_COEFF: Double = 0.35 // Organic friction facing mu
    const val CLUTCH_NEW_TORQUE_CAPACITY_NM: Double = 277.0 // 2 * mu * Fn * Rm (~1.59x safety margin over peak engine torque)
    const val ENGINE_PEAK_TORQUE_NM: Double = 174.0 // R18Z1 peak engine torque @ 4300 RPM
    const val BASELINE_CLUTCH_LIFETIME_JOULES: Double = 42_000_000.0 // ~42 MJ nominal lifecycle friction energy
    const val CLUTCH_THERMAL_MASS_J_PER_K: Double = 4500.0 // Flywheel + pressure plate friction face thermal capacity
    const val CLUTCH_COOLING_COEFF_W_PER_K: Double = 18.0 // Convective bellhousing dissipation rate
    const val CLUTCH_NORMAL_TEMP_THRESHOLD_C: Double = 130.0 // Below this is standard wear
    const val CLUTCH_GLAZE_TEMP_THRESHOLD_C: Double = 200.0 // Above this accelerates thermal wear & glazing

    // Oil Life Baseline Constants
    const val OIL_CAPACITY_QUARTS: Double = 3.9
    const val BASELINE_OIL_LIFE_MILES: Double = 7500.0 // Full synthetic normal duty interval
    const val BASELINE_LIFETIME_REVOLUTIONS: Double = 14_500_000.0 // ~14.5M engine cycles
    const val OPERATING_TEMP_THRESHOLD_C: Double = 71.0 // 160°F (below this is a cold-start penalty)
    const val OPTIMAL_OPERATING_TEMP_C: Double = 85.0 // 185°F
    const val HIGH_THERMAL_THRESHOLD_RPM: Double = 4500.0
    const val HIGH_LOAD_THRESHOLD_PERCENT: Double = 75.0

    /** Honda DBW PID 0111 reads ~12-15% at foot-off idle. */
    const val CLOSED_THROTTLE_BASELINE_PERCENT: Double = 14.0
}

package com.shieldrj.civic5mt.core

const val LITERS_PER_US_GALLON: Double = 3.785411784

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
    // Calculated tire diameter ~634.5 mm (0.6345 m), circumference = pi * D = ~1.9933 m
    const val TIRE_CIRCUMFERENCE_KM: Double = 0.0019933
    const val TIRE_CIRCUMFERENCE_MILES: Double = 0.0012386

    // Physical Vehicle Weights
    const val CURB_WEIGHT_KG: Int = 1247 // ~2,750 lbs
    const val DRAG_COEFFICIENT_CD: Double = 0.29
    const val FRONTAL_AREA_M2: Double = 2.1

    // Fuel Constants
    const val GASOLINE_DENSITY_G_PER_GALLON: Double = 2788.0 // ~736.5 grams/liter
    const val GASOLINE_DENSITY_G_PER_LITER: Double = 736.5
    const val STOICHIOMETRIC_AFR: Double = 14.7 // Standard unleaded gasoline
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

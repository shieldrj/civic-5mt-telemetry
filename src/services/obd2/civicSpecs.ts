export const LITERS_PER_US_GALLON = 3.785411784;

/**
 * Pure-component fuel properties, at 15°C.
 *
 * Stoichiometric ratios here are the standard mass-basis figures: burning 1 kg of fuel
 * completely consumes this many kg of air. Ethanol carries its own oxygen, so it needs
 * far less air than gasoline - which is exactly why the blend matters to a MAF-based
 * fuel calculation. Gasoline is a mixture rather than one molecule, so 14.7 is the
 * conventional figure for pump-grade hydrocarbon rather than octane's ideal 15.1.
 */
export const FUEL_COMPONENTS = {
  gasolineDensityGramsPerLiter: 745.0,
  ethanolDensityGramsPerLiter: 789.3,
  gasolineStoichAfr: 14.7,
  ethanolStoichAfr: 9.0,
} as const;

export interface FuelBlendProperties {
  id: FuelBlendId;
  label: string;
  ethanolByVolume: number;
  ethanolByMass: number;
  stoichAfr: number;
  densityGramsPerLiter: number;
  densityGramsPerGallon: number;
}

export type FuelBlendId = 'E0' | 'E10' | 'E15';

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
export function deriveFuelBlend(
  ethanolByVolume: number
): Pick<FuelBlendProperties, 'ethanolByMass' | 'stoichAfr' | 'densityGramsPerLiter' | 'densityGramsPerGallon'> {
  const v = Math.max(0, Math.min(1, ethanolByVolume));
  const gasolineMass = (1 - v) * FUEL_COMPONENTS.gasolineDensityGramsPerLiter;
  const ethanolMass = v * FUEL_COMPONENTS.ethanolDensityGramsPerLiter;
  const totalMass = gasolineMass + ethanolMass;

  const ethanolByMass = ethanolMass / totalMass;
  const gasolineByMass = gasolineMass / totalMass;

  const airPerFuelMass =
    gasolineByMass / FUEL_COMPONENTS.gasolineStoichAfr +
    ethanolByMass / FUEL_COMPONENTS.ethanolStoichAfr;

  return {
    ethanolByMass,
    stoichAfr: 1 / airPerFuelMass,
    densityGramsPerLiter: totalMass,
    densityGramsPerGallon: totalMass * LITERS_PER_US_GALLON,
  };
}

function buildBlend(id: FuelBlendId, label: string, ethanolByVolume: number): FuelBlendProperties {
  return { id, label, ethanolByVolume, ...deriveFuelBlend(ethanolByVolume) };
}

export const FUEL_BLENDS: Record<FuelBlendId, FuelBlendProperties> = {
  E0: buildBlend('E0', 'Ethanol-free (E0)', 0),
  E10: buildBlend('E10', 'Regular pump gas (E10)', 0.1),
  E15: buildBlend('E15', 'E15 / 88 octane', 0.15),
};

/** US retail pump gasoline is E10 almost everywhere, which is what this car runs. */
export const DEFAULT_FUEL_BLEND: FuelBlendId = 'E10';

/**
 * 2013 Honda Civic LX 5-Speed Manual (R18Z1 Engine) Specifications & Physical Constants
 */
export const CIVIC_2013_SPECS = {
  // Engine
  engineDisplacementLiters: 1.798,
  engineName: '1.8L SOHC 16-valve i-VTEC (R18Z1)',
  cylinders: 4,
  redlineRpm: 6700,
  revLimiterRpm: 6800,
  idleRpm: 750,
  vtecSwitchRpm: 4800, // Dynamic i-VTEC economy-to-power cam switch
  
  // 5-Speed Manual Transmission Gear Ratios
  gearRatios: {
    1: 3.143,
    2: 1.870,
    3: 1.235,
    4: 0.949,
    5: 0.727,
    R: 3.307,
  } as const,
  finalDriveRatio: 4.294,
  
  // Tire & Wheel Specifications (Stock 195/65R15)
  tireWidthMm: 195,
  tireAspectRatio: 65,
  rimDiameterInches: 15,
  // Calculated tire diameter ~634.5 mm (0.6345 m), circumference = pi * D = ~1.9933 m (0.0019933 km)
  tireCircumferenceKm: 0.0019933,
  tireCircumferenceMiles: 0.0012386,
  
  // Physical Vehicle Weights
  curbWeightKg: 1247, // ~2,750 lbs
  dragCoefficientCd: 0.29,
  frontalAreaM2: 2.1,
  
  // Fuel Constants
  gasolineDensityGramsPerGallon: 2788, // ~736.5 grams/liter
  gasolineDensityGramsPerLiter: 736.5,
  stoichiometricAfr: 14.7, // Standard unleaded gasoline
  gasPriceDefaultDollarsPerGallon: 3.45,
  fuelTankCapacityGallons: 13.2, // 2013 Civic LX sedan factory tank spec
  epaCombinedMpgDefault: 32, // Fallback multiplier for range-to-empty before a rolling MPG sample exists

  // The telemetry loop's period. Exported as a spec rather than left as a literal in
  // telemetryManager because the rolling-MPG window is measured in samples, not seconds -
  // it has to divide by this to mean anything. They disagreed before: the buffer was sized
  // 600 with a comment claiming 30 seconds at 20Hz, while the loop has always run at 80ms,
  // making the "30 second" average a 48 second one.
  telemetryTickMs: 80,
  
  // Shift Point Tuning
  ecoShiftPoints: {
    1: 2200, // 1st -> 2nd
    2: 2100, // 2nd -> 3rd
    3: 2000, // 3rd -> 4th
    4: 1950, // 4th -> 5th
  },
  powerShiftPointRpm: 6500, // Near peak horsepower (6,500 RPM @ 143 hp)
  
  // Oil Life Baseline Constants
  oilCapacityQuarts: 3.9,
  baselineOilLifeMiles: 7500, // Recommended full synthetic normal duty interval
  baselineLifetimeRevolutions: 14500000, // ~14.5 million engine cycles baseline
  operatingTempThresholdC: 71, // 160°F (Below this is cold-start penalty)
  optimalOperatingTempC: 85,   // 185°F
  highThermalThresholdRpm: 4500,
  highLoadThresholdPercent: 75,
  closedThrottleBaselinePercent: 14.0, // Honda DBW PID 0111 reads ~12-15% at foot-off idle
} as const;

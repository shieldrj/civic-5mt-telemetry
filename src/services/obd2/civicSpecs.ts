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

import { CIVIC_2013_SPECS, FUEL_BLENDS, LITERS_PER_US_GALLON } from '../services/obd2/civicSpecs';
import { GearCalculatorEngine } from '../services/obd2/gearCalculator';
import { FuelModelEngine } from '../services/obd2/fuelModel';
import { OilLifeEngine } from '../services/obd2/oilLifeModel';
import { HONDA_DTC_DATABASE } from '../services/obd2/dtcSpecs';
import { OBDLinkBluetoothManager } from '../services/bluetooth/obdlinkBluetooth';
import { decodeReadinessMonitors, UNKNOWN_MONITORS } from '../services/obd2/dtcScanner';
import {
  choosePid,
  decodePidValue,
  pidsInUseFor,
  LAMBDA_PID_CANDIDATES,
  PRE_CAT_PID_CANDIDATES,
  OUTSIDE_AIR_PID_CANDIDATES,
} from '../services/obd2/pidCatalog';
import {
  resolveIntegrationStep,
  shouldRecordLifetime,
  MAX_INTEGRATION_STEP_SEC,
} from '../services/obd2/integrationRules';

// Polyfill localStorage for Node test runner
if (typeof globalThis.localStorage === 'undefined') {
  const store = new Map<string, string>();
  globalThis.localStorage = {
    getItem: (k: string) => store.get(k) || null,
    setItem: (k: string, v: string) => { store.set(k, v); },
    removeItem: (k: string) => { store.delete(k); },
    clear: () => { store.clear(); },
    length: 0,
    key: (i: number) => null,
  } as any;
}

let passed = 0;
let failed = 0;

function assert(condition: boolean, testName: string) {
  if (condition) {
    console.log(`  ✓ PASS: ${testName}`);
    passed++;
  } else {
    console.error(`  ✗ FAIL: ${testName}`);
    failed++;
  }
}

console.log('==================================================');
console.log('🧪 RUNNING 2013 CIVIC 5MT TELEMETRY ENGINE TESTS');
console.log('==================================================\n');

// 1. GEAR CALCULATOR & DYNAMICS
console.log('--- 1. Gear Deduction & Manual Transmission Dynamics ---');
const gearCalc = new GearCalculatorEngine();

// 1st Gear (13.50 overall ratio) -> 3000 RPM at 26.58 km/h (16.5 mph)
const g1 = gearCalc.analyzeGear(3000, 26.58, 20);
assert(g1.currentGear === 1, `1st Gear detected correctly (Got: ${g1.currentGear}, Ratio: ${g1.calculatedRatio.toFixed(2)})`);

// 2nd Gear (8.03 overall ratio) -> 3000 RPM at 44.67 km/h (27.8 mph)
const g2 = gearCalc.analyzeGear(3000, 44.67, 20);
assert(g2.currentGear === 2, `2nd Gear detected correctly (Got: ${g2.currentGear}, Ratio: ${g2.calculatedRatio.toFixed(2)})`);

// 3rd Gear (5.30 overall ratio) -> 3000 RPM at 67.66 km/h (42.0 mph)
const g3 = gearCalc.analyzeGear(3000, 67.66, 20);
assert(g3.currentGear === 3, `3rd Gear detected correctly (Got: ${g3.currentGear}, Ratio: ${g3.calculatedRatio.toFixed(2)})`);

// 4th Gear (4.07 overall ratio) -> 3000 RPM at 88.04 km/h (54.7 mph)
const g4 = gearCalc.analyzeGear(3000, 88.04, 20);
assert(g4.currentGear === 4, `4th Gear detected correctly (Got: ${g4.currentGear}, Ratio: ${g4.calculatedRatio.toFixed(2)})`);

// 5th Gear (3.12 overall ratio) -> 3000 RPM at 114.93 km/h (71.4 mph)
const g5 = gearCalc.analyzeGear(3000, 114.93, 20);
assert(g5.currentGear === 5, `5th Gear detected correctly (Got: ${g5.currentGear}, Ratio: ${g5.calculatedRatio.toFixed(2)})`);

// Neutral stationary
const gN = gearCalc.analyzeGear(750, 0, 0);
assert(gN.currentGear === 'N', `Neutral detected when stationary (Got: ${gN.currentGear})`);

// 2. FUEL & PHYSICS MODEL
console.log('\n--- 2. Physics Fuel Model & Deceleration Fuel Cut-Off (DFCO) ---');
const fuelModel = new FuelModelEngine();

// Air-Fuel Ratio computation. Stoichiometry is a property of the fuel, not a constant:
// the ECU targets lambda 1.0 for whatever is in the tank, so both directions are pinned.
fuelModel.setFuelBlend('E0');
const afrStoichE0 = fuelModel.calculateAirFuelRatio(1.0, 0, 0);
assert(Math.abs(afrStoichE0 - 14.7) < 0.05, `AFR at stoichiometry on E0 is 14.7:1 (Got: ${afrStoichE0.toFixed(2)})`);

fuelModel.setFuelBlend('E10'); // the default - US pump gas
const afrStoichE10 = fuelModel.calculateAirFuelRatio(1.0, 0, 0);
assert(Math.abs(afrStoichE10 - 13.78) < 0.05, `AFR at stoichiometry on E10 is 13.78:1 (Got: ${afrStoichE10.toFixed(2)})`);

const afrRich = fuelModel.calculateAirFuelRatio(0.85, 0, 0);
assert(afrRich < 13.0, `Rich AFR properly calculated for power pull (Got: ${afrRich.toFixed(2)})`);

/*
 * A car with no wideband PID must fall through to the fuel trims.
 *
 * This is the regression that mattered most. The lambda argument used to default to 1.0, so
 * a car that reports neither PID 24 nor 34 - which is this Civic - arrived here with an
 * apparently valid stoichiometric reading on every tick. It passed the validity range, took
 * the wideband branch, and returned bare stoichiometry forever while the real fuel trims
 * sitting in the next two arguments were discarded. Both halves are pinned below: null must
 * reach the trims, and a genuine 1.0 must still suppress them.
 */
fuelModel.setFuelBlend('E10');
const stoichE10 = FUEL_BLENDS.E10.stoichAfr;

const afrFromTrims = fuelModel.calculateAirFuelRatio(null, 3.91, 2.34);
assert(
  Math.abs(afrFromTrims - stoichE10) > 0.1,
  `No wideband PID falls through to fuel trims instead of reporting bare stoichiometry (Got: ${afrFromTrims.toFixed(2)} vs stoich ${stoichE10.toFixed(2)})`
);
assert(
  afrFromTrims < stoichE10,
  `Positive trims mean the ECU is adding fuel, so AFR lands below stoichiometry (Got: ${afrFromTrims.toFixed(2)})`
);

const afrTrimsLean = fuelModel.calculateAirFuelRatio(null, -4.0, -2.0);
assert(
  afrTrimsLean > stoichE10,
  `Negative trims mean the ECU is pulling fuel, so AFR lands above stoichiometry (Got: ${afrTrimsLean.toFixed(2)})`
);

// A real wideband reading of 1.0 already accounts for the trims; applying them again would
// double-count. So the same trims must be ignored when lambda was actually measured.
const afrMeasuredStoich = fuelModel.calculateAirFuelRatio(1.0, 3.91, 2.34);
assert(
  Math.abs(afrMeasuredStoich - stoichE10) < 0.001,
  `A measured lambda of 1.0 overrides the trims rather than compounding them (Got: ${afrMeasuredStoich.toFixed(3)})`
);
assert(
  afrMeasuredStoich !== afrFromTrims,
  'A measured stoichiometric reading and an inferred one are distinguishable, not identical'
);

// Fuel Flow at Idle (2.8 g/s MAF, 14.7 AFR)
const idleFlow = fuelModel.calculateFuelFlow(2.8, 14.7, false);
assert(idleFlow.fuelFlowLitersPerHour > 0.8 && idleFlow.fuelFlowLitersPerHour < 1.8, `Idle fuel burn rate realistic (${idleFlow.fuelFlowLitersPerHour.toFixed(2)} L/hr)`);

// Instantaneous MPG at 65 mph cruising (65 mph, 1.45 gal/hr)
const cruiseMpg = fuelModel.calculateInstantMpg(65, 1.45, false);
assert(cruiseMpg > 40 && cruiseMpg < 48, `Highway 5th gear cruising MPG realistic (${cruiseMpg.toFixed(1)} MPG)`);

// DFCO status check
const isDfco = fuelModel.checkDfco(0, 2400, 50, 4);
assert(isDfco === true, 'DFCO properly triggers when coasting in gear with closed throttle');

const dfcoFlow = fuelModel.calculateFuelFlow(2.0, 14.7, isDfco);
assert(dfcoFlow.fuelFlowGalPerHour === 0, 'DFCO cuts fuel burn rate to 0.00 GPH');

const dfcoMpg = fuelModel.calculateInstantMpg(45, dfcoFlow.fuelFlowGalPerHour, isDfco);
assert(dfcoMpg >= 99.9, 'DFCO returns 99.9+ MPG');

// 3. OIL LIFE & DEGRADATION MODEL
console.log('\n--- 3. 4-Factor Deep Oil Life Algorithm ---');
const oilEngine = new OilLifeEngine();
const initialProfile = oilEngine.getProfile();
assert(initialProfile.oilLifePercent >= 0 && initialProfile.oilLifePercent <= 100, `Initial oil life in valid range (${initialProfile.oilLifePercent}%)`);

// Register Cold Start (<71°C)
const prevColdStarts = initialProfile.coldStartsCount;
oilEngine.registerEngineStart(30); // 30°C cold start
const coldProfile = oilEngine.getProfile();
assert(coldProfile.coldStartsCount === prevColdStarts + 1, `Cold start counter incremented (${coldProfile.coldStartsCount} logged)`);

// Record 60 seconds of telemetry step at 3,500 RPM
const prevRevs = coldProfile.accumulatedRevolutions;
oilEngine.recordTelemetryStep(3500, 85, 45, 60, 60);
const stepProfile = oilEngine.getProfile();
assert(stepProfile.accumulatedRevolutions > prevRevs, `Mechanical revolutions accumulated (${(stepProfile.accumulatedRevolutions - prevRevs).toFixed(0)} new revs)`);

// Reset oil life to 100%
oilEngine.resetOilLife(115000);
const resetProfile = oilEngine.getProfile();
assert(resetProfile.oilLifePercent === 100, 'Oil life successfully reset to 100%');
assert(resetProfile.accumulatedRevolutions === 0, 'Accumulated revolutions reset to 0');

// 4. DTC DATABASE & LOOKUP TABLE
console.log('\n--- 4. Diagnostic Trouble Code (DTC) Database ---');
const dtcKeys = Object.keys(HONDA_DTC_DATABASE);
assert(dtcKeys.length >= 10, `Honda DTC database loaded (${dtcKeys.length} specific codes registered)`);
assert(HONDA_DTC_DATABASE['P0133'] !== undefined, 'P0133 Upstream O2 Sensor slow response code verified');
assert(HONDA_DTC_DATABASE['P0420'] !== undefined, 'P0420 Catalyst System Efficiency code verified');
assert(HONDA_DTC_DATABASE['P0301'] !== undefined, 'P0301 Cylinder 1 Misfire code verified');
assert(HONDA_DTC_DATABASE['P0171'] !== undefined, 'P0171 Fuel System Too Lean code verified');

// 5. NEW PID PLAUSIBILITY & FUEL RANGE CALC
console.log('\n--- 5. Battery / Fuel Level / O2 Sensor PIDs & Range-to-Empty ---');
const btManager = new OBDLinkBluetoothManager();
const defaults = btManager.latestData;

assert(defaults.batteryVoltage > 9 && defaults.batteryVoltage < 16, `Battery voltage default in plausible range (${defaults.batteryVoltage}V)`);
assert(defaults.fuelLevelPercent >= 0 && defaults.fuelLevelPercent <= 100, `Fuel level default in valid % range (${defaults.fuelLevelPercent}%)`);
// PID 0115 byte A is A/200, so full scale is 0 - 1.275V (not 1.0V - a narrowband sensor
// only *uses* roughly 0.1-0.9V of that range in practice). Every car answers 0115.
assert(defaults.o2Sensor2Voltage >= 0 && defaults.o2Sensor2Voltage <= 1.275, `O2 Sensor 2 (post-cat) default within PID full scale (${defaults.o2Sensor2Voltage}V)`);
assert(defaults.engineRuntimeSec >= 0, `Engine runtime default non-negative (${defaults.engineRuntimeSec}s)`);

/*
 * Readings that may not exist start as null - strictly null, not "a plausible number".
 *
 * These three were 1.0, 22 and 0.45. On a car lacking the PID behind them, that seed is
 * what the gauge displayed indefinitely, indistinguishable on screen from a measurement.
 * Written as `=== null` deliberately: the range checks these replace would both still pass
 * against null, since null coerces to 0, so a range check here is a check that has quietly
 * stopped running.
 */
assert(defaults.lambda === null, `Lambda starts as no-reading, not a stoichiometric-looking 1.0 (${defaults.lambda})`);
assert(defaults.ambientC === null, `Outside air starts as no-reading, not a room-temperature 22 (${defaults.ambientC})`);
assert(defaults.ambientSource === null, 'No outside-air reading means no source to attribute it to');
assert(defaults.o2Sensor1Voltage === null, `Pre-catalyst voltage starts as no-reading, not a switch-point 0.45 (${defaults.o2Sensor1Voltage})`);
assert(defaults.o2Sensor1Lambda === null, `Pre-catalyst lambda starts as no-reading (${defaults.o2Sensor1Lambda})`);
assert(defaults.o2Sensor1CurrentMa === null, `Wide-range sensor current starts as no-reading (${defaults.o2Sensor1CurrentMa})`);

// Fuel range: 50% of a 13.2gal tank at 30 MPG -> 6.6 gal * 30 mpg = 198 miles
const range = fuelModel.calculateFuelRange(50, CIVIC_2013_SPECS.fuelTankCapacityGallons, 30);
assert(Math.abs(range - 198) < 1, `Fuel range calculated correctly (Got: ${range.toFixed(1)} mi, expected ~198 mi)`);

// Fuel range falls back to the EPA combined default when no rolling MPG sample exists yet (0 MPG)
const rangeFallback = fuelModel.calculateFuelRange(100, CIVIC_2013_SPECS.fuelTankCapacityGallons, 0);
const expectedFallback = CIVIC_2013_SPECS.fuelTankCapacityGallons * CIVIC_2013_SPECS.epaCombinedMpgDefault;
assert(Math.abs(rangeFallback - expectedFallback) < 1, `Fuel range falls back to EPA combined MPG before a rolling sample exists (Got: ${rangeFallback.toFixed(1)} mi)`);

// 6. READINESS MONITOR DECODING (Mode 01 PID 01 bytes B/C/D)
console.log('\n--- 6. Emissions Readiness Monitor Decoding ---');

// Byte B: bits 0-2 supported (misfire, fuel, comprehensive), bits 4-6 incomplete.
// Byte C: supported spark-ignition monitors. Byte D: the matching incomplete bits.
// 0x07 = all three common tests supported, none incomplete -> all Ready.
const allReady = decodeReadinessMonitors(0x07, 0xe5, 0x00);
assert(allReady.misfire === 'Ready', `Misfire reads Ready when supported and complete (${allReady.misfire})`);
assert(allReady.fuelSystem === 'Ready', `Fuel system reads Ready when supported and complete (${allReady.fuelSystem})`);
assert(allReady.catalyst === 'Ready', `Catalyst reads Ready when supported and complete (${allReady.catalyst})`);

// Same supported set, but every spark-ignition test still incomplete.
const notReady = decodeReadinessMonitors(0x77, 0xe5, 0xe5);
assert(notReady.misfire === 'Not Ready', `Misfire reads Not Ready while its test is incomplete (${notReady.misfire})`);
assert(notReady.catalyst === 'Not Ready', `Catalyst reads Not Ready while its test is incomplete (${notReady.catalyst})`);
assert(notReady.o2Sensor === 'Not Ready', `O2 sensor reads Not Ready while its test is incomplete (${notReady.o2Sensor})`);

// An engine that does not support a monitor must report N/A, never Ready - the whole
// point of the fix. 0x00 supported means nothing is available to test.
const unsupported = decodeReadinessMonitors(0x00, 0x00, 0x00);
const unsupportedValues = Object.values(unsupported);
assert(
  unsupportedValues.every((v) => v === 'N/A'),
  `Unsupported monitors report N/A rather than Ready (${[...new Set(unsupportedValues)].join(', ')})`
);

// Secondary air is commonly absent on this engine: C bit3 clear -> N/A even though
// the neighbouring catalyst monitor is supported and complete.
const mixed = decodeReadinessMonitors(0x07, 0x05, 0x00);
assert(mixed.catalyst === 'Ready', `Supported catalyst still reads Ready in a mixed set (${mixed.catalyst})`);
assert(mixed.evap === 'Ready', `Supported evap still reads Ready in a mixed set (${mixed.evap})`);
assert(mixed.o2Sensor === 'N/A', `Unsupported O2 monitor reads N/A in a mixed set (${mixed.o2Sensor})`);

assert(
  Object.values(UNKNOWN_MONITORS).every((v) => v === 'N/A'),
  'Unreadable ECU reply falls back to all-N/A, never all-Ready'
);

// 7. FUEL BLEND CHEMISTRY & LIFETIME MPG
console.log('\n--- 7. Fuel Blend Chemistry (E0 / E10 / E15) ---');

// E0 must reproduce the pure-gasoline reference exactly.
const e0 = FUEL_BLENDS.E0;
assert(Math.abs(e0.stoichAfr - 14.7) < 0.001, `E0 stoichiometric AFR is the gasoline reference 14.7 (${e0.stoichAfr.toFixed(3)})`);
assert(Math.abs(e0.ethanolByMass) < 1e-9, `E0 contains no ethanol by mass (${e0.ethanolByMass})`);

// E10 is 10% ethanol BY VOLUME; ethanol is denser, so its mass share is slightly higher.
const e10 = FUEL_BLENDS.E10;
assert(
  e10.ethanolByMass > 0.10 && e10.ethanolByMass < 0.11,
  `E10 mass fraction exceeds its volume fraction because ethanol is denser (${(e10.ethanolByMass * 100).toFixed(2)}% by mass)`
);
assert(
  e10.stoichAfr > 13.6 && e10.stoichAfr < 13.9,
  `E10 stoichiometric AFR lands near 13.8 by mass-correct blending (${e10.stoichAfr.toFixed(3)})`
);
assert(e10.stoichAfr < e0.stoichAfr, `Adding ethanol lowers the stoichiometric ratio (${e10.stoichAfr.toFixed(2)} < ${e0.stoichAfr.toFixed(2)})`);
assert(
  e10.densityGramsPerLiter > e0.densityGramsPerLiter,
  `Adding ethanol raises blend density (${e10.densityGramsPerLiter.toFixed(1)} > ${e0.densityGramsPerLiter.toFixed(1)} g/L)`
);

// More ethanol keeps pushing the ratio down.
assert(
  FUEL_BLENDS.E15.stoichAfr < e10.stoichAfr,
  `E15 sits below E10 (${FUEL_BLENDS.E15.stoichAfr.toFixed(2)} < ${e10.stoichAfr.toFixed(2)})`
);

// Reciprocal blending is the point: a naive average of the two AFRs would give ~14.1 for
// E10, which is the figure that made the old pure-gasoline assumption look defensible.
const naiveAverage = 0.9 * 14.7 + 0.1 * 9.0;
assert(
  Math.abs(e10.stoichAfr - naiveAverage) > 0.2,
  `Mass-correct E10 AFR differs materially from a naive volume average (${e10.stoichAfr.toFixed(2)} vs ${naiveAverage.toFixed(2)})`
);

// Density round-trip: g/L -> g/gal must use the exact US gallon.
assert(
  Math.abs(e10.densityGramsPerGallon - e10.densityGramsPerLiter * LITERS_PER_US_GALLON) < 0.001,
  'Blend density converts between litres and US gallons consistently'
);

// The blend actually changes computed fuel flow: same air mass, more fuel on E10.
const blendModel = new FuelModelEngine();
blendModel.setFuelBlend('E0');
const flowE0 = blendModel.calculateFuelFlow(10, blendModel.getFuelBlend().stoichAfr, false);
blendModel.setFuelBlend('E10');
const flowE10 = blendModel.calculateFuelFlow(10, blendModel.getFuelBlend().stoichAfr, false);
assert(
  flowE10.fuelFlowGalPerHour > flowE0.fuelFlowGalPerHour,
  `E10 burns more volume than E0 for identical airflow (${flowE10.fuelFlowGalPerHour.toFixed(4)} > ${flowE0.fuelFlowGalPerHour.toFixed(4)} gal/hr)`
);

// At lambda 1.0 the AFR returned must be the blend's stoichiometric ratio, not 14.7.
blendModel.setFuelBlend('E10');
const afrAtStoichE10 = blendModel.calculateAirFuelRatio(1.0, 0, 0);
assert(
  Math.abs(afrAtStoichE10 - e10.stoichAfr) < 0.001,
  `Lambda 1.0 on E10 yields the blend's ratio rather than gasoline's (${afrAtStoichE10.toFixed(2)})`
);

// Lifetime MPG is total miles over total gallons - the definition, not an average of averages.
const lifetimeMpg = 1000 / 31.25;
assert(Math.abs(lifetimeMpg - 32) < 0.001, `Lifetime MPG is cumulative miles over cumulative gallons (${lifetimeMpg.toFixed(2)})`);

console.log('\n--- 8. Lifetime Record Integrity ---');

// Only a real adapter may write to the permanent record.
assert(shouldRecordLifetime('connected') === true, 'A connected adapter records to the lifetime figure');
assert(shouldRecordLifetime('simulating') === false, 'Simulated driving is refused by the lifetime figure');
assert(shouldRecordLifetime('disconnected') === false, 'Disconnected state records nothing');
assert(shouldRecordLifetime('connecting') === false, 'Mid-connection state records nothing');
assert(shouldRecordLifetime('error') === false, 'Error state records nothing');

// Normal 80ms ticks integrate; a stalled timer does not.
assert(resolveIntegrationStep(0.08) === 0.08, 'A normal 80ms tick integrates in full');
assert(resolveIntegrationStep(0.95) === 0.95, 'A slow but plausible tick still integrates');
assert(resolveIntegrationStep(MAX_INTEGRATION_STEP_SEC) === MAX_INTEGRATION_STEP_SEC, 'The boundary step integrates');
assert(resolveIntegrationStep(1200) === 0, 'A 20-minute stall from a locked phone is discarded, not integrated');
assert(resolveIntegrationStep(5) === 0, 'A 5-second gap already exceeds the trusted window');
assert(resolveIntegrationStep(0) === 0, 'A zero step contributes nothing');
assert(resolveIntegrationStep(-3) === 0, 'A negative step (clock adjustment) contributes nothing');
assert(resolveIntegrationStep(NaN) === 0, 'A non-finite step contributes nothing');

// 9. PID AVAILABILITY, SELECTION, AND THE READINGS THIS CAR ACTUALLY RETURNS
console.log('\n--- 9. PID Selection & Decoding (measured against a real scan) ---');

/*
 * The support set below is a real scan of the car this app is built for, taken at a warm
 * idle: 38 PIDs, and crucially none of 0x24, 0x46 or 0x14 - the three the gauges used to
 * ask for unconditionally. Hardcoding it here is the point. A synthetic set would have been
 * written to match whatever the code already did, which is exactly how the original bug
 * survived: nothing anywhere asserted that the PIDs being polled were PIDs this car has.
 */
const CIVIC_SUPPORTED_PIDS = new Set([
  0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x13, 0x15,
  0x1c, 0x1f, 0x21, 0x2c, 0x2d, 0x2e, 0x2f, 0x30, 0x31, 0x32, 0x33, 0x34, 0x3c, 0x41, 0x42,
  0x43, 0x44, 0x45, 0x47, 0x49, 0x4a, 0x4c, 0x51,
]);

assert(
  !CIVIC_SUPPORTED_PIDS.has(0x24) && !CIVIC_SUPPORTED_PIDS.has(0x46) && !CIVIC_SUPPORTED_PIDS.has(0x14),
  'The scanned car reports none of PIDs 24, 46 or 14 - the premise the rest of this section rests on'
);

assert(
  choosePid(LAMBDA_PID_CANDIDATES, CIVIC_SUPPORTED_PIDS) === 0x34,
  'Lambda resolves to PID 34 on a car with no PID 24'
);
assert(
  choosePid(PRE_CAT_PID_CANDIDATES, CIVIC_SUPPORTED_PIDS) === 0x34,
  'The pre-catalyst sensor resolves to PID 34 on a car with no narrowband PID 14'
);
assert(
  choosePid(OUTSIDE_AIR_PID_CANDIDATES, CIVIC_SUPPORTED_PIDS) === 0x0f,
  'Outside air falls back to intake air (PID 0F) on a car with no PID 46'
);

// A car that has the first choice must still get it.
const richCar = new Set([0x14, 0x24, 0x46, 0x34, 0x0f]);
assert(choosePid(LAMBDA_PID_CANDIDATES, richCar) === 0x24, 'PID 24 is preferred for lambda where it exists');
assert(choosePid(PRE_CAT_PID_CANDIDATES, richCar) === 0x14, 'The narrowband PID 14 is preferred for the pre-catalyst trace where it exists');
assert(choosePid(OUTSIDE_AIR_PID_CANDIDATES, richCar) === 0x46, 'Real outside air (PID 46) is preferred over intake air where it exists');

// A car that has neither candidate must yield null, not the first one anyway.
assert(choosePid(LAMBDA_PID_CANDIDATES, new Set([0x0c])) === null, 'A car with no wideband PID at all resolves to null rather than a guess');

// An empty set means the bitmaps could not be read - which is not the same as "the car has
// nothing", so it falls back to asking and letting the reply decide.
assert(
  choosePid(LAMBDA_PID_CANDIDATES, new Set()) === 0x24,
  'Unreadable support bitmaps fall back to the first candidate rather than giving up'
);

// The discovery screen's tick and the poll loop must agree, because they now share this.
const civicInUse = pidsInUseFor(CIVIC_SUPPORTED_PIDS);
assert(civicInUse.has(0x34), 'PID 34 is marked as driving a gauge on this car');
assert(civicInUse.has(0x0f), 'PID 0F is marked as driving a gauge on this car');
assert(
  !civicInUse.has(0x24) && !civicInUse.has(0x46) && !civicInUse.has(0x14),
  'PIDs this car does not have are never marked as driving a gauge'
);
assert(
  [...civicInUse].every((pid) => CIVIC_SUPPORTED_PIDS.has(pid)),
  'Every PID claimed to drive a gauge is one the car actually reports'
);

/*
 * The six replies this car gave that had no formula, decoded. These are the literal bytes
 * off the adapter, so each assertion is a round trip from a real reply to a real reading.
 */
assert(decodePidValue(0x2c, '00') === '0 %', `PID 2C commanded EGR decodes (Got: ${decodePidValue(0x2c, '00')})`);
assert(decodePidValue(0x2d, 'FF') === '99.22 %', `PID 2D EGR error decodes its saturated reading (Got: ${decodePidValue(0x2d, 'FF')})`);
// Signed: 0xFF96 is -106 quarter-pascals, a slight vacuum. Unsigned it would read +16742 Pa.
assert(decodePidValue(0x32, 'FF96') === '-26.5 Pa', `PID 32 evap pressure decodes as signed (Got: ${decodePidValue(0x32, 'FF96')})`);
assert(decodePidValue(0x51, '01') === 'Gasoline', `PID 51 fuel type decodes (Got: ${decodePidValue(0x51, '01')})`);

const wideRange = decodePidValue(0x34, '843D7FF5');
assert(
  wideRange !== null && wideRange.includes('1.033') && wideRange.includes('-0.04'),
  `PID 34 decodes to lambda and sensor current (Got: ${wideRange})`
);

// PID 41 is the same readiness bitmap as PID 01, scoped to this drive cycle. Byte A is
// reserved, so the decoder must read B, C and D - offset by one from the payload start.
const driveCycle = decodePidValue(0x41, '0005E000');
assert(
  driveCycle === '5 monitors, all complete',
  `PID 41 summarises this drive cycle's monitors (Got: ${driveCycle})`
);

// Same bytes, one monitor forced incomplete, to prove the summary is reading D and not
// simply reporting "all complete" for anything it is handed.
const driveCycleBusy = decodePidValue(0x41, '0005E020');
assert(
  driveCycleBusy !== null && driveCycleBusy.includes('1 of 5') && driveCycleBusy.includes('O2 sensor'),
  `PID 41 names the monitor still running rather than claiming completion (Got: ${driveCycleBusy})`
);

console.log('\n==================================================');
console.log(`🏁 TEST RESULTS: ${passed} PASSED, ${failed} FAILED`);
console.log('==================================================\n');

if (failed > 0) {
  process.exit(1);
}

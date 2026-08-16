import { CIVIC_2013_SPECS } from '../services/obd2/civicSpecs';
import { GearCalculatorEngine } from '../services/obd2/gearCalculator';
import { FuelModelEngine } from '../services/obd2/fuelModel';
import { OilLifeEngine } from '../services/obd2/oilLifeModel';
import { HONDA_DTC_DATABASE } from '../services/obd2/dtcSpecs';
import { OBDLinkBluetoothManager } from '../services/bluetooth/obdlinkBluetooth';
import { decodeReadinessMonitors, UNKNOWN_MONITORS } from '../services/obd2/dtcScanner';

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

// Air-Fuel Ratio computation
const afrStoich = fuelModel.calculateAirFuelRatio(1.0, 0, 0);
assert(Math.abs(afrStoich - 14.7) < 0.05, `AFR at Stoichiometry is 14.7:1 (Got: ${afrStoich.toFixed(2)})`);

const afrRich = fuelModel.calculateAirFuelRatio(0.85, 0, 0);
assert(afrRich < 13.0, `Rich AFR properly calculated for power pull (Got: ${afrRich.toFixed(2)})`);

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
// PID 0114/0115 byte A is A/200, so full scale is 0 - 1.275V (not 1.0V - a narrowband
// sensor only *uses* roughly 0.1-0.9V of that range in practice).
assert(defaults.o2Sensor1Voltage >= 0 && defaults.o2Sensor1Voltage <= 1.275, `O2 Sensor 1 (pre-cat) default within PID full scale (${defaults.o2Sensor1Voltage}V)`);
assert(defaults.o2Sensor2Voltage >= 0 && defaults.o2Sensor2Voltage <= 1.275, `O2 Sensor 2 (post-cat) default within PID full scale (${defaults.o2Sensor2Voltage}V)`);
assert(defaults.engineRuntimeSec >= 0, `Engine runtime default non-negative (${defaults.engineRuntimeSec}s)`);

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

console.log('\n==================================================');
console.log(`🏁 TEST RESULTS: ${passed} PASSED, ${failed} FAILED`);
console.log('==================================================\n');

if (failed > 0) {
  process.exit(1);
}

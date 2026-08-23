package com.shieldrj.civic5mt.core

enum class DtcCategory { POWERTRAIN, CHASSIS, BODY, NETWORK }

enum class DtcSeverity { CRITICAL, MODERATE, MINOR, INFO }

data class DtcDefinition(
    val code: String,
    val category: DtcCategory,
    val system: String,
    val title: String,
    val description: String,
    val severity: DtcSeverity,
    val symptoms: List<String>,
    val possibleCauses: List<String>,
    val civicSpecificNotes: String? = null,
)

/**
 * Honda-specific diagnostic trouble codes for the R18Z1.
 *
 * Generic OBD-II text says what a code means in the abstract; the entries here say what it
 * usually means on this car. The `civicSpecificNotes` are the reason the table exists at
 * all - the intake-duct crack behind P0171 and the oil-level link behind P2646 are both
 * things a generic lookup will not tell you.
 */
val HONDA_DTC_DATABASE: Map<String, DtcDefinition> = listOf(

    // ── Fuel & Air Metering ──────────────────────────────────────────────────────
    DtcDefinition(
        code = "P0171",
        category = DtcCategory.POWERTRAIN,
        system = "Fuel & Air Metering",
        title = "System Too Lean (Bank 1)",
        description = "The engine ECU has detected that the air-fuel mixture is too lean (too much air or not enough fuel), exceeding the positive fuel trim limit (+15% to +25%).",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf(
            "Rough idle",
            "Hesitation during acceleration",
            "Reduced fuel economy",
            "May be pending before CEL illuminates",
        ),
        possibleCauses = listOf(
            "Dirty or contaminated Mass Air Flow (MAF) sensor",
            "Vacuum leak downstream of MAF (cracked intake boot)",
            "Low fuel pressure / clogged fuel injector",
            "Exhaust leak before upstream O2 sensor",
        ),
        civicSpecificNotes = "Common on 2012-2015 Civics due to hairline cracks in the rubber intake air duct between the airbox and throttle body.",
    ),
    DtcDefinition(
        code = "P0172",
        category = DtcCategory.POWERTRAIN,
        system = "Fuel & Air Metering",
        title = "System Too Rich (Bank 1)",
        description = "The engine ECU detected excessive fuel or insufficient air, driving negative fuel trims past limits.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf(
            "Strong fuel smell from exhaust",
            "Black exhaust smoke under load",
            "Sluggish acceleration",
            "Lower MPG",
        ),
        possibleCauses = listOf(
            "Faulty or contaminated MAF sensor reading high",
            "Leaking fuel injector",
            "Excessive fuel pressure",
            "Stuck open EVAP purge valve",
        ),
    ),
    DtcDefinition(
        code = "P0101",
        category = DtcCategory.POWERTRAIN,
        system = "Fuel & Air Metering",
        title = "Mass Air Flow (MAF) Circuit Range/Performance",
        description = "MAF sensor signal is out of expected range compared to throttle position and engine RPM.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf(
            "Hesitation",
            "Stalling when coming to a stop in neutral",
            "Erratic idle",
        ),
        possibleCauses = listOf(
            "Dirty MAF sensor element",
            "Air intake leak",
            "Damaged MAF sensor wiring harness",
        ),
    ),
    DtcDefinition(
        code = "P0128",
        category = DtcCategory.POWERTRAIN,
        system = "Cooling System",
        title = "Coolant Thermostat (Coolant Temp Below Regulating Temp)",
        description = "Engine coolant temperature has not reached normal operating temperature (approx 160°F–180°F) within a calculated time after starting.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf(
            "Heater blows lukewarm air",
            "Decreased fuel economy (engine stays in warm-up enrichment)",
            "Oil takes longer to burn off condensation",
        ),
        possibleCauses = listOf(
            "Thermostat stuck open or opening prematurely",
            "Faulty Engine Coolant Temperature (ECT) sensor",
            "Low coolant level",
        ),
        civicSpecificNotes = "Directly impacts the Oil Life Calculator and fuel trim efficiency because the engine remains in open-loop/warmup mode.",
    ),
    DtcDefinition(
        code = "P0133",
        category = DtcCategory.POWERTRAIN,
        system = "Fuel & Air Metering",
        title = "O2 Sensor Circuit Slow Response (Bank 1 Sensor 1)",
        description = "Upstream wideband Air/Fuel sensor takes too long to switch between rich and lean signals during transient throttle.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf(
            "Slight hesitation",
            "Gradual degradation of MPG",
            "Often exists as a PENDING code for hundreds of miles before CEL triggers",
        ),
        possibleCauses = listOf(
            "Aging or contaminated primary A/F ratio sensor",
            "Exhaust manifold crack",
            "Fuel contamination",
        ),
    ),
    DtcDefinition(
        code = "P0135",
        category = DtcCategory.POWERTRAIN,
        system = "Fuel & Air Metering",
        title = "O2 Sensor Heater Circuit (Bank 1 Sensor 1)",
        description = "Electrical resistance fault in the internal heating element of the upstream air-fuel sensor.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf("Delayed entry into closed-loop fuel control after cold start"),
        possibleCauses = listOf(
            "Burned out heater element in primary O2 sensor",
            "Blown O2 heater fuse in under-dash fuse box",
        ),
    ),

    // ── Ignition & Misfires ──────────────────────────────────────────────────────
    DtcDefinition(
        code = "P0300",
        category = DtcCategory.POWERTRAIN,
        system = "Ignition & Misfire",
        title = "Random / Multiple Cylinder Misfire Detected",
        description = "ECU crankshaft speed fluctuations indicate multiple cylinders are failing to ignite consistently.",
        severity = DtcSeverity.CRITICAL,
        symptoms = listOf(
            "Jerking / stumbling under load",
            "Flashing Check Engine Light (signals catalytic converter damage risk)",
            "Sulfur smell",
        ),
        possibleCauses = listOf(
            "Worn spark plugs",
            "Low fuel delivery pressure",
            "Major vacuum leak",
            "Contaminated fuel",
        ),
    ),
    DtcDefinition(
        code = "P0301",
        category = DtcCategory.POWERTRAIN,
        system = "Ignition & Misfire",
        title = "Cylinder 1 Misfire Detected",
        description = "Crankshaft sensor detected rotational deceleration during Cylinder 1 power stroke.",
        severity = DtcSeverity.CRITICAL,
        symptoms = listOf(
            "Engine vibration",
            "Rough idle in neutral",
            "Loss of acceleration in 1st/2nd gear",
        ),
        possibleCauses = listOf(
            "Failing ignition coil on Cyl 1",
            "Worn NGK laser iridium spark plug",
            "Clogged injector on Cyl 1",
            "Valve adjustment required",
        ),
    ),
    DtcDefinition(
        code = "P0302",
        category = DtcCategory.POWERTRAIN,
        system = "Ignition & Misfire",
        title = "Cylinder 2 Misfire Detected",
        description = "Crankshaft sensor detected rotational deceleration during Cylinder 2 power stroke.",
        severity = DtcSeverity.CRITICAL,
        symptoms = listOf("Engine vibration", "Hesitation", "Power loss"),
        possibleCauses = listOf("Ignition coil 2", "Spark plug gap", "Fuel injector 2"),
    ),
    DtcDefinition(
        code = "P0303",
        category = DtcCategory.POWERTRAIN,
        system = "Ignition & Misfire",
        title = "Cylinder 3 Misfire Detected",
        description = "Crankshaft sensor detected rotational deceleration during Cylinder 3 power stroke.",
        severity = DtcSeverity.CRITICAL,
        symptoms = listOf("Engine vibration", "Hesitation", "Power loss"),
        possibleCauses = listOf("Ignition coil 3", "Spark plug gap", "Fuel injector 3"),
    ),
    DtcDefinition(
        code = "P0304",
        category = DtcCategory.POWERTRAIN,
        system = "Ignition & Misfire",
        title = "Cylinder 4 Misfire Detected",
        description = "Crankshaft sensor detected rotational deceleration during Cylinder 4 power stroke.",
        severity = DtcSeverity.CRITICAL,
        symptoms = listOf("Engine vibration", "Hesitation", "Power loss"),
        possibleCauses = listOf("Ignition coil 4", "Spark plug gap", "Fuel injector 4"),
    ),

    // ── Emissions & EVAP ─────────────────────────────────────────────────────────
    DtcDefinition(
        code = "P0420",
        category = DtcCategory.POWERTRAIN,
        system = "Emissions Control",
        title = "Catalyst System Efficiency Below Threshold (Bank 1)",
        description = "Downstream secondary O2 sensor switching frequency mirrors the upstream sensor, indicating the catalytic converter is not storing enough oxygen.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf(
            "No noticeable driveability issue in most cases",
            "Fails emissions inspection",
            "Often stays as a pending code during highway drive cycles",
        ),
        possibleCauses = listOf(
            "Degraded catalytic converter substrate",
            "Exhaust leak near secondary O2 sensor",
            "Contamination from oil blowby or unburnt fuel",
        ),
    ),
    DtcDefinition(
        code = "P0455",
        category = DtcCategory.POWERTRAIN,
        system = "Evaporative Emissions (EVAP)",
        title = "EVAP System Large Leak Detected",
        description = "The fuel tank vapor recovery system failed the engine vacuum pull-down test, indicating a large air leak.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf(
            "\"Check Fuel Cap\" warning on dashboard i-MID screen",
            "Faint fuel vapor odor near rear quarter panel",
        ),
        possibleCauses = listOf(
            "Loose, missing, or worn fuel filler cap gasket",
            "Disconnected EVAP canister purge line",
            "Defective EVAP canister vent valve",
        ),
    ),
    DtcDefinition(
        code = "P0456",
        category = DtcCategory.POWERTRAIN,
        system = "Evaporative Emissions (EVAP)",
        title = "EVAP System Very Small Leak Detected (0.020\")",
        description = "Minor pressure loss in fuel vapor system during overnight soak test.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf("No driveability symptoms", "Intermittent pending code"),
        possibleCauses = listOf(
            "Slightly dried fuel cap O-ring seal",
            "Micro-crack in plastic EVAP vapor lines",
        ),
    ),
    DtcDefinition(
        code = "P145C",
        category = DtcCategory.POWERTRAIN,
        system = "Honda Proprietary EVAP",
        title = "EVAP Purge Flow Malfunction (Honda Specific)",
        description = "Honda-specific diagnostic: MAP sensor did not register expected manifold pressure drop when EVAP purge solenoid opened.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf("Check engine light", "Can trigger after refueling"),
        possibleCauses = listOf(
            "Stuck closed EVAP purge control solenoid valve",
            "Clogged vacuum purge port on throttle body",
        ),
    ),

    // ── VTEC & Engine Controls ───────────────────────────────────────────────────
    DtcDefinition(
        code = "P2646",
        category = DtcCategory.POWERTRAIN,
        system = "Variable Valve Timing (i-VTEC)",
        title = "VTEC Rocker Arm Oil Pressure Switch Circuit Low Voltage",
        description = "When the ECU commanded i-VTEC high-lift cam transition at ~4,800 RPM, the VTEC oil pressure switch did not detect sufficient hydraulic oil pressure to lock the rocker arms.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf(
            "Engine stutters or hits a \"wall\" around 4,000–5,000 RPM (VTEC limp mode)",
            "Car drives normally below 3,500 RPM",
        ),
        possibleCauses = listOf(
            "Low engine oil level",
            "Clogged VTEC spool valve solenoid screen/filter gasket",
            "Dirty or degraded engine oil",
            "Faulty VTEC oil pressure switch",
        ),
        civicSpecificNotes = "Commonly triggered when engine oil is low or past its useful life, directly linking with your Oil Life Calculator!",
    ),
    DtcDefinition(
        code = "P0847",
        category = DtcCategory.POWERTRAIN,
        system = "Transmission",
        title = "Transmission Fluid Pressure Sensor/Switch (3rd Clutch)",
        description = "Circuit voltage low in transmission fluid pressure sensor.",
        severity = DtcSeverity.MINOR,
        symptoms = listOf("May appear in ECU memory on certain scan tools"),
        possibleCauses = listOf("Corroded connector pin", "Sensor replacement needed"),
    ),
    DtcDefinition(
        code = "U0100",
        category = DtcCategory.NETWORK,
        system = "CAN Communication",
        title = "Lost Communication With ECM/PCM \"A\"",
        description = "Data bus timeout between instrument cluster or ABS module and the Engine Control Module.",
        severity = DtcSeverity.MODERATE,
        symptoms = listOf("Intermittent gauge flicker", "ABS or VSA warning light"),
        possibleCauses = listOf(
            "Low 12V battery voltage during cold crank",
            "Loose OBD-II adapter connection",
        ),
    ),
).associateBy { it.code }

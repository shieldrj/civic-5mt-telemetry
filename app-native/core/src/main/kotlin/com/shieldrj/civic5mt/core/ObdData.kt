package com.shieldrj.civic5mt.core

/** Which PID an outside-air figure came from, so it can be labelled truthfully. */
enum class OutsideAirSource { AMBIENT, INTAKE }

/**
 * One snapshot of what the car is reporting.
 *
 * The nullable fields are the point of this type, not an inconvenience in it. Every one of
 * them is a reading the car may simply not produce, and each used to be seeded with a
 * plausible-looking constant instead - lambda at 1.0, outside air at 22°C, pre-catalyst
 * voltage at 0.45 V. On this Civic, which reports none of PIDs 24, 46 or 14, those seeds
 * were what the gauges displayed indefinitely, indistinguishable on screen from a
 * measurement. The 1.0 was the worst of the three because it also passed the fuel model's
 * validity test and so suppressed the fuel-trim fallback that had real data behind it.
 *
 * So: absent readings are null, and the UI renders them as absences. Anything that fills one
 * in with a default has reintroduced the bug.
 */
data class RawObdData(
    val rpm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val maf: Double = 2.8,
    val coolantC: Double = 85.0,
    val engineLoad: Double = 20.0,
    val throttlePos: Double = 14.0,
    val stft: Double = 0.0,
    val ltft: Double = 0.0,
    val timingAdvance: Double = 10.0,
    /** Wide-range lambda from PID 24 or 34, or null when the car has neither. */
    val lambda: Double? = null,
    val batteryVoltage: Double = 14.2,
    /**
     * PID 2F, tank level. Null when the car does not answer it.
     *
     * This was 65.0, and it is the same bug as the 22 degree outside air: a car with no
     * tank-level PID showed a five-eighths tank and a range to match, forever, with nothing
     * on screen to say it had never been measured. The doc above says a default here
     * reintroduces the bug; this one was the exception nobody noticed.
     */
    val fuelLevelPercent: Double? = null,
    /** Outside air, or null when neither PID 46 nor 0F answered. */
    val ambientC: Double? = null,
    /** Which PID the figure above came from, so it can be labelled truthfully. */
    val ambientSource: OutsideAirSource? = null,
    /** Pre-catalyst narrowband voltage from PID 14, null on a car that reports lambda instead. */
    val o2Sensor1Voltage: Double? = null,
    /** Pre-catalyst lambda from PID 34, null on a car with a narrowband there instead. */
    val o2Sensor1Lambda: Double? = null,
    /** Wide-range sensor current in mA from PID 34. Near zero means sitting at balance. */
    val o2Sensor1CurrentMa: Double? = null,
    val o2Sensor2Voltage: Double = 0.65,
    val engineRuntimeSec: Double = 0.0,

    /**
     * PID 03, fuel system 1 status. Null on a car that does not report it.
     *
     * Read [FUEL_SYSTEM_STATUS_LABELS] for what a value means. Closed loop is the state the
     * fuel model assumes; open loop means the mixture readings are following an enrichment
     * map rather than correcting towards stoichiometric.
     */
    val fuelSystemStatus: Int? = null,

    /** PID 45, throttle relative to its closed-throttle rest point. Null when unsupported. */
    val relativeThrottlePos: Double? = null,

    /**
     * When 010C or 010D last actually parsed. Null means never - not "long ago".
     *
     * Provenance, not a reading, which is why it does not violate the rule above: there is no
     * plausible-looking timestamp to seed it with. It exists because every field here carries
     * forward on a non-answer, so the values alone cannot say whether the car is idling or the
     * ECU went to sleep half a minute ago holding them. [IntegrationRules.isFreshEnoughToIntegrate]
     * is what reads it, and it is the only thing standing between a sleeping ECU and the
     * lifetime fuel total.
     */
    val motionSampledAtMillis: Long? = null,
)

/**
 * The permanent record: total miles over total gallons, since the app first saw real data.
 *
 * Only [IntegrationRules.shouldRecordLifetime] may let a sample in here, and
 * [lifetimeMpg] is recomputed on load rather than trusted from storage - miles and gallons
 * are the measurements, the ratio is a view of them.
 */
data class LifetimeStats(
    val totalMiles: Double = 0.0,
    val totalFuelGallons: Double = 0.0,
    val firstTrackedTimestamp: Long = 0L,
) {
    val lifetimeMpg: Double
        get() = if (totalFuelGallons > 0.01) totalMiles / totalFuelGallons else 0.0
}

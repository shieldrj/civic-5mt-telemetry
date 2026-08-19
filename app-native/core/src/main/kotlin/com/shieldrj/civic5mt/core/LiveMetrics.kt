package com.shieldrj.civic5mt.core

/**
 * Everything the gauges read, for one tick.
 *
 * The nullable fields are the same ones that are nullable on [RawObdData] and for the same
 * reason: this Civic reports none of PIDs 24, 46 or 14, and a seeded default here would be
 * indistinguishable on screen from a measurement.
 */
data class LiveMetrics(
    // Raw / base OBD-II PIDs
    val rpm: Double = 0.0,
    val speedKmh: Double = 0.0,
    val speedMph: Double = 0.0,
    val mafGramsPerSec: Double = 0.0,
    val coolantTempC: Double = 0.0,
    val coolantTempF: Int = 0,
    val engineLoadPercent: Double = 0.0,
    val throttlePosPercent: Double = 0.0,
    val shortTermFuelTrim: Double = 0.0,
    val longTermFuelTrim: Double = 0.0,
    val timingAdvanceDeg: Double = 0.0,
    /** PID 24 or 34. Null means the car has neither. */
    val equivalenceRatio: Double? = null,
    val batteryVoltage: Double = 0.0,
    /** Null when the car does not report a tank level. */
    val fuelLevelPercent: Double? = null,
    /** PID 46, else 0F. Null means neither exists. */
    val outsideAirTempC: Double? = null,
    val outsideAirTempF: Int? = null,
    /** Which of the two the figure above came from - 0F is not the same quantity as 46. */
    val outsideAirSource: OutsideAirSource? = null,
    val o2Sensor1Voltage: Double? = null,
    val o2Sensor1Lambda: Double? = null,
    val o2Sensor1CurrentMa: Double? = null,
    val o2Sensor2Voltage: Double = 0.0,
    val engineRuntimeSec: Double = 0.0,

    // Computed fuel physics
    val instantMpg: Double = 0.0,
    /** [instantMpg] damped for reading - see FuelModelEngine.updateDisplayMpg. */
    val displayMpg: Double = 0.0,
    /** Whether [displayMpg] is an economy figure at all, or idle / coasting. */
    val mpgDisplayState: MpgDisplayState = MpgDisplayState.IDLE,
    val isDfcoActive: Boolean = false,
    val fuelFlowGalPerHour: Double = 0.0,
    val fuelFlowLitersPerHour: Double = 0.0,
    val airFuelRatio: Double = 0.0,
    val rolling30sMpg: Double = 0.0,
    /** Persisted, and from real OBD data only - never from the simulator. */
    val lifetimeMpg: Double = 0.0,
    /** Real vehicle miles behind [lifetimeMpg]. Zero means never connected to a car. */
    val lifetimeMiles: Double = 0.0,
    /** Null when there is no tank level to compute it from. */
    val fuelRangeMiles: Int? = null,

    // Manual transmission dynamics
    val currentGear: GearSelection = GearSelection.Neutral,
    val gearRatio: Double = 0.0,
    val isClutchSlipping: Boolean = false,
    val optimalShiftRpm: Int = 0,
    val shouldShiftUp: Boolean = false,
    /** 0 to 5, progressive shift-light stages. */
    val shiftLightStage: Int = 0,

    val timestamp: Long = 0L,
)

data class TripAnalytics(
    val tripStartTime: Long = 0L,
    val tripDurationSec: Double = 0.0,
    val distanceMiles: Double = 0.0,
    val totalFuelUsedGallons: Double = 0.0,
    val avgMpg: Double = 0.0,
    val idleTimeSec: Double = 0.0,
    val idleFuelGallons: Double = 0.0,
    val idleCostDollars: Double = 0.0,
    val coastingDfcoTimeSec: Double = 0.0,
    val coastingFuelSavedGallons: Double = 0.0,
    val maxSpeedMph: Double = 0.0,
    val maxRpm: Double = 750.0,
    val avgSpeedMph: Double = 0.0,
    /** 0 - 100. */
    val ecoScore: Int = 92,
)

/** One tick's worth of everything, so a consumer gets a consistent set rather than three. */
data class TelemetrySnapshot(
    val metrics: LiveMetrics,
    val trip: TripAnalytics,
    val oil: OilLifeProfile,
    val lifetime: LifetimeStats,
)

/**
 * Where the permanent record is kept.
 *
 * Separate from [OilProfileStore] because they have different rules about what may write to
 * them: only a connected adapter may touch this one, and the values in it accumulated from
 * real driving and cannot be regenerated.
 */
interface LifetimeStore {
    fun load(): LifetimeStats?
    fun save(stats: LifetimeStats)
}

class InMemoryLifetimeStore(private var stored: LifetimeStats? = null) : LifetimeStore {
    override fun load(): LifetimeStats? = stored
    override fun save(stats: LifetimeStats) {
        stored = stats
    }
}

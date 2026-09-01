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
    /** Control module voltage. Null until the car has answered PID 42. */
    val batteryVoltage: Double? = null,
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
    /** PID 03 status code, or null on a car that does not report it. */
    val fuelSystemStatus: Int? = null,
    /** Decoded label for [fuelSystemStatus], null when there is no reading to decode. */
    val fuelSystemStatusLabel: String? = null,
    /** PID 45, throttle zeroed at its closed rest point. Null when unsupported. */
    val relativeThrottlePosPercent: Double? = null,

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
    /**
     * How much of a tankful is left, as a share of what the tank actually holds.
     *
     * Not [fuelLevelPercent], which is the sender talking and is wrong at both ends. See
     * TankState.fuelPercentRemaining for what the difference is and where it comes from.
     */
    val fuelPercentRemaining: Double? = null,

    /**
     * The figures for this tank of fuel.
     *
     * These are what a driver actually checks. Instant MPG is on the dashboard already and
     * changes every second, so it answers no question; miles per gallon over a whole tank
     * has one answer and moves slowly.
     */
    val tankMpg: Double? = null,
    val tankMilesSinceFill: Double? = null,
    val tankGallonsRemaining: Double? = null,
    /**
     * Whether gallons per percent has been measured on this car yet, or is still the nominal
     * figure from the tank capacity. Range is usable either way and better once measured, so
     * the screen says which it is.
     */
    val tankCalibrated: Boolean = false,
    /**
     * Whether the sender has bottomed out, leaving [fuelPercentRemaining] and
     * [fuelRangeMiles] as bounds rather than readings.
     *
     * See TankState.belowSenderZero. Both figures stop moving down there, and a screen that
     * goes on printing them plainly is claiming a measurement nothing took.
     */
    val tankBelowSenderZero: Boolean = false,

    // ── What the pump receipts have taught ──────────────────────────────────────────
    /**
     * Miles before the sender reads zero, which is the figure the dashboard is showing.
     *
     * Null when there is no range at all. Equal to [fuelRangeMiles] on a tank with no measured
     * reserve, which is the honest answer there: no reserve has been measured, so none is
     * being claimed.
     */
    val fuelRangeToSenderZeroMiles: Int? = null,
    /** Miles held below the sender's zero - real fuel, and the least certain fuel in the tank. */
    val fuelRangeReserveMiles: Int? = null,
    /** The economy figure the range was built on, so a screen can say where the number came from. */
    val rangeMpgUsed: Double? = null,
    /**
     * Odometer miles over pump gallons, across the logged fills. Null before there are enough.
     *
     * The only economy figure here that no sensor in this app contributed to.
     */
    val verifiedMpg: Double? = null,
    /** Pump gallons behind [verifiedMpg]. */
    val verifiedGallons: Double = 0.0,
    /** What the MAF chain's gallons are being multiplied by. 1.0 means nothing has corrected it. */
    val fuelCorrectionFactor: Double = 1.0,
    /** What integrated miles are being multiplied by. 1.0 means no odometer reading has been given. */
    val distanceCorrectionFactor: Double = 1.0,
    /** Fills behind the corrections. */
    val calibrationFillCount: Int = 0,
    /**
     * How far the individual fills disagree with the pooled correction, as a percentage.
     *
     * The honest width of the range figure: two hundred miles at three percent spread is two
     * hundred give or take six. Null before two fills, because one fill agrees with itself.
     */
    val calibrationSpreadPercent: Double? = null,

    // Manual transmission dynamics
    val currentGear: GearSelection = GearSelection.Neutral,
    val gearRatio: Double = 0.0,
    val isClutchSlipping: Boolean = false,
    val optimalShiftRpm: Int = 0,
    val shouldShiftUp: Boolean = false,
    /** 0 to 5, progressive shift-light stages. */
    val shiftLightStage: Int = 0,
    val clutchStatus: ClutchLiveStatus = ClutchLiveStatus(),

    // Vehicle Health Status
    val healthStatus: VehicleHealthStatus = VehicleHealthStatus(),

    val timestamp: Long = 0L,
)

enum class HealthLevel {
    OK,
    ADVISORY,
    CRITICAL,
}

data class VehicleHealthStatus(
    val level: HealthLevel = HealthLevel.OK,
    val summary: String = "ALL SYSTEMS OK",
    val detail: String? = null,
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
    val clutch: ClutchProfile,
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

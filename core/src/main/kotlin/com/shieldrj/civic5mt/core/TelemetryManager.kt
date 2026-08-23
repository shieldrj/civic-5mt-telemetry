package com.shieldrj.civic5mt.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val KM_PER_HOUR_TO_MPH = 0.621371

/**
 * Turns one snapshot of what the car is reporting into everything the gauges show.
 *
 * Deliberately has no timer of its own. The TypeScript version constructed a singleton on
 * import that started an interval, which is why the integration rules had to be moved into
 * their own file - importing this module to reach them started a timer and hung the test
 * runner outright. Here the caller drives the cadence and [tick] is a step function, so the
 * whole thing is exercisable from a unit test with no clock running anywhere.
 *
 * That also matters more in the native build than it did in the browser: the thing driving
 * the loop is now a foreground service, which is a considerably worse thing to start by
 * accident from a test than a `setInterval` was.
 */
class TelemetryManager(
    private val clock: MillisClock = SystemMillisClock,
    private val lifetimeStore: LifetimeStore = InMemoryLifetimeStore(),
    val oilLife: OilLifeEngine = OilLifeEngine(clock = clock),
    val fuelModel: FuelModelEngine = FuelModelEngine(),
    val tank: TankTracker = TankTracker(clock = clock),
    private val gearCalculator: GearCalculatorEngine = GearCalculatorEngine(clock),
) {
    var shiftMode: ShiftMode = ShiftMode.ECO
    var gasPricePerGallon: Double = CivicSpecs.GAS_PRICE_DEFAULT_DOLLARS_PER_GALLON

    private var lifetime: LifetimeStats = lifetimeStore.load() ?: LifetimeStats()
    private var lifetimeSaveTimestamp: Long = 0

    private var trip: TripAnalytics = TripAnalytics(tripStartTime = clock.nowMillis())

    /**
     * Whether this drive's engine start has been counted against the oil yet.
     *
     * It is not counted at connect time, which is what the TypeScript did: it read the
     * coolant temperature the instant the socket opened, before a single PID had been
     * answered, so the reading was the zero-initialised default. Below the cold threshold,
     * every time - which made every connection a cold start, including pulling over and
     * reconnecting to an engine that had been at temperature for an hour.
     */
    private var engineStartCounted = false

    /**
     * One step.
     *
     * @param rawDtSec wall-clock seconds since the previous tick. Two different step values
     *   come out of it: display smoothing uses the raw figure, the permanent record uses
     *   [IntegrationRules.resolveIntegrationStep], which discards a gap too long to have been
     *   observed driving.
     */
    fun tick(
        raw: RawObdData,
        rawDtSec: Double,
        status: ConnectionStatus,
    ): TelemetrySnapshot {
        val now = clock.nowMillis()

        // Display smoothing can use the raw step; the permanent record cannot.
        val dtSec = max(0.01, rawDtSec)

        // Two ways a step can fail to be real, and both have to be refused here rather than
        // in the three integrators downstream - they all key off integrationDtSec, so gating
        // it once is what stops them disagreeing. The gap can be unobserved (a stalled timer),
        // or the readings themselves can be stale: the car stopped answering and every field
        // is carrying forward its last value. Display keeps the carried-forward figure on
        // purpose - a frozen gauge is visible and honest - but nothing permanent may.
        val readingsAreFresh = IntegrationRules.isFreshEnoughToIntegrate(raw.motionSampledAtMillis, now)
        val integrationDtSec =
            if (readingsAreFresh) IntegrationRules.resolveIntegrationStep(rawDtSec) else 0.0

        // 1. Speeds. The unrounded figure is what gets integrated - rounding first would
        //    accumulate a bias over thousands of ticks.
        val speedMphRaw = raw.speedKmh * KM_PER_HOUR_TO_MPH
        val speedMph = roundTo(speedMphRaw, 1)
        val coolantF = ((raw.coolantC * 9) / 5 + 32).roundToInt()
        val outsideAirF = raw.ambientC?.let { ((it * 9) / 5 + 32).roundToInt() }

        // 2. Gear and transmission dynamics.
        val gear = gearCalculator.analyzeGear(raw.rpm, raw.speedKmh, raw.throttlePos, shiftMode)

        // 3. Air-fuel and fuel flow. Lambda is passed through as-is, null included: that
        //    nullability is the fix, and defaulting it here would undo it.
        val afr = fuelModel.calculateAirFuelRatio(raw.lambda, raw.stft, raw.ltft)
        val isDfco = fuelModel.checkDfco(raw.throttlePos, raw.rpm, raw.speedKmh, gear.currentGear)
        val flow = fuelModel.calculateFuelFlow(raw.maf, afr, isDfco)

        // 4. MPG, in its three forms - instantaneous, rolling, and damped for reading.
        val instantMpg = fuelModel.calculateInstantMpg(speedMph, flow.fuelFlowGalPerHour, isDfco)
        val rollingMpg = fuelModel.updateRollingMpg(instantMpg)
        val displayMpg = fuelModel.updateDisplayMpg(instantMpg, speedMph, isDfco, dtSec)
        // Range comes from the tank tracker now, further down, once this step has been added
        // to it. It used to be tank level times a 30-second average MPG, and that average is
        // what made it swing: it is a record of the last hill, not an economy figure.

        // 5. Integrate the trip, and the permanent record if this is a real adapter.
        updateTrip(integrationDtSec, speedMphRaw, flow.fuelFlowGalPerHour, isDfco, raw.rpm, status)

        // 6. The tank. Real driving only, for the same reason the lifetime record is: a bench
        //    run must not report that fuel was burned or that a tank was filled.
        if (IntegrationRules.shouldRecordLifetime(status) && integrationDtSec > 0 && raw.rpm >= 350) {
            tank.record(
                levelPercent = raw.fuelLevelPercent,
                milesStep = (speedMphRaw / 3600) * integrationDtSec,
                gallonsStep = (flow.fuelFlowGalPerHour / 3600) * integrationDtSec,
                dtSec = integrationDtSec,
            )
        }
        val tankState = tank.get()

        // Two ways to have no range, and both must read as absent rather than as zero.
        //
        // No tank level means the car does not report one, and a distance to empty derived
        // from an assumed tank is a number someone drives past a filling station on. No tank
        // record means nothing has been tracked yet - a bench run, or the first seconds of a
        // real drive - and "0 miles to empty" there is an alarm about nothing.
        val tankKnown = raw.fuelLevelPercent != null && tankState.fillTimestamp != 0L
        val fuelRange = if (tankKnown) {
            rangeMiles(tankState, lifetime.lifetimeMpg, lifetime.totalMiles)
        } else {
            null
        }

        // 7. Engine wear - real driving only.
        //
        // Gated by the same rule the lifetime record uses, and for the same reason. This
        // step was unconditional in the TypeScript, so a bench run added crank revolutions,
        // odometer miles and cold-running seconds to a maintenance record and wrote them to
        // disk thirty seconds later. Oil life is a figure someone changes their oil on.
        val isRealDrive = IntegrationRules.shouldRecordLifetime(status)
        val oilProfile = if (isRealDrive) {
            // The first tick with the engine actually turning is the first moment there is a
            // real coolant reading to judge a cold start by.
            if (!engineStartCounted && raw.rpm >= CivicSpecs.ENGINE_RUNNING_RPM) {
                engineStartCounted = true
                oilLife.registerEngineStart(raw.coolantC)
            }
            // The permanent record's step, not the display step. Oil life is a maintenance
            // figure, so a stalled timer has to be discarded here exactly as it is for
            // distance - it was taking the raw step, and booking a locked phone as running.
            oilLife.recordTelemetryStep(raw.rpm, raw.coolantC, raw.engineLoad, speedMph, integrationDtSec)
        } else {
            oilLife.getProfile()
        }

        val health = evaluateHealthStatus(
            rpm = raw.rpm,
            coolantF = coolantF,
            batteryVoltage = raw.batteryVoltage,
            isClutchSlipping = gear.isClutchSlipping,
            stft = raw.stft,
            ltft = raw.ltft,
        )

        val metrics = LiveMetrics(
            rpm = raw.rpm,
            speedKmh = raw.speedKmh,
            speedMph = speedMph,
            mafGramsPerSec = raw.maf,
            coolantTempC = raw.coolantC,
            coolantTempF = coolantF,
            engineLoadPercent = raw.engineLoad,
            throttlePosPercent = raw.throttlePos,
            shortTermFuelTrim = raw.stft,
            longTermFuelTrim = raw.ltft,
            timingAdvanceDeg = raw.timingAdvance,
            equivalenceRatio = raw.lambda,
            batteryVoltage = roundTo(raw.batteryVoltage, 2),
            fuelLevelPercent = raw.fuelLevelPercent?.let { roundTo(it, 1) },
            outsideAirTempC = raw.ambientC,
            outsideAirTempF = outsideAirF,
            outsideAirSource = raw.ambientSource,
            o2Sensor1Voltage = raw.o2Sensor1Voltage?.let { roundTo(it, 3) },
            o2Sensor1Lambda = raw.o2Sensor1Lambda,
            o2Sensor1CurrentMa = raw.o2Sensor1CurrentMa,
            o2Sensor2Voltage = roundTo(raw.o2Sensor2Voltage, 3),
            engineRuntimeSec = raw.engineRuntimeSec,
            fuelSystemStatus = raw.fuelSystemStatus,
            fuelSystemStatusLabel = raw.fuelSystemStatus?.let {
                FUEL_SYSTEM_STATUS_LABELS[it] ?: ("Unknown (0x" + it.toString(16) + ")")
            },
            relativeThrottlePosPercent = raw.relativeThrottlePos,
            instantMpg = roundTo(instantMpg, 1),
            displayMpg = roundTo(displayMpg.value, 1),
            mpgDisplayState = displayMpg.state,
            isDfcoActive = isDfco,
            fuelFlowGalPerHour = roundTo(flow.fuelFlowGalPerHour, 3),
            fuelFlowLitersPerHour = roundTo(flow.fuelFlowLitersPerHour, 2),
            airFuelRatio = roundTo(afr, 2),
            rolling30sMpg = roundTo(rollingMpg, 1),
            lifetimeMpg = roundTo(lifetime.lifetimeMpg, 1),
            lifetimeMiles = lifetime.totalMiles,
            fuelRangeMiles = fuelRange,
            tankMpg = tankState.tankMpg,
            tankMilesSinceFill = if (tankKnown) tankState.milesSinceFill else null,
            tankGallonsRemaining = if (tankKnown) tankState.gallonsRemaining else null,
            tankCalibrated = tankState.calibrated,
            currentGear = gear.currentGear,
            gearRatio = roundTo(gear.calculatedRatio, 2),
            isClutchSlipping = gear.isClutchSlipping,
            optimalShiftRpm = gear.optimalShiftRpm,
            shouldShiftUp = gear.shouldShiftUp,
            shiftLightStage = gear.shiftLightStage,
            healthStatus = health,
            timestamp = now,
        )

        return TelemetrySnapshot(metrics = metrics, trip = trip, oil = oilProfile, lifetime = lifetime)
    }

    private fun evaluateHealthStatus(
        rpm: Double,
        coolantF: Int,
        batteryVoltage: Double,
        isClutchSlipping: Boolean,
        stft: Double,
        ltft: Double,
    ): VehicleHealthStatus {
        if (coolantF >= 225) {
            return VehicleHealthStatus(
                level = HealthLevel.CRITICAL,
                summary = "ENGINE OVERHEATING · $coolantF°F",
                detail = "Coolant temperature critical. Pull over safely.",
            )
        }
        if (coolantF >= 215) {
            return VehicleHealthStatus(
                level = HealthLevel.ADVISORY,
                summary = "COOLANT TEMP HIGH · $coolantF°F",
                detail = "Coolant temperature elevated above normal operating range (175–205°F).",
            )
        }
        if (rpm >= CivicSpecs.ENGINE_RUNNING_RPM && batteryVoltage < 11.8 && batteryVoltage > 5.0) {
            return VehicleHealthStatus(
                level = HealthLevel.CRITICAL,
                summary = "BATTERY VOLTAGE CRITICAL · %.2fV".format(batteryVoltage),
                detail = "Severe electrical voltage drop under 11.8V.",
            )
        }
        if (rpm >= CivicSpecs.ENGINE_RUNNING_RPM && batteryVoltage < 12.8 && batteryVoltage > 5.0) {
            return VehicleHealthStatus(
                level = HealthLevel.ADVISORY,
                summary = "CHARGING SYSTEM LOW · %.2fV".format(batteryVoltage),
                detail = "Alternator output below 12.8V while engine is running.",
            )
        }
        if (isClutchSlipping) {
            return VehicleHealthStatus(
                level = HealthLevel.ADVISORY,
                summary = "CLUTCH SLIP DETECTED",
                detail = "Engine RPM rising without proportional vehicle speed gain in gear.",
            )
        }
        val totalTrim = stft + ltft
        if (rpm >= CivicSpecs.ENGINE_RUNNING_RPM && totalTrim > 18.0) {
            return VehicleHealthStatus(
                level = HealthLevel.ADVISORY,
                summary = "RUNNING LEAN · TRIMS +%.0f%%".format(totalTrim),
                detail = "ECU adding excess fuel. Check for vacuum leak or dirty MAF sensor.",
            )
        } else if (rpm >= CivicSpecs.ENGINE_RUNNING_RPM && totalTrim < -18.0) {
            return VehicleHealthStatus(
                level = HealthLevel.ADVISORY,
                summary = "RUNNING RICH · TRIMS %.0f%%".format(totalTrim),
                detail = "ECU removing excess fuel.",
            )
        }
        if (rpm >= CivicSpecs.ENGINE_RUNNING_RPM && coolantF in 33..159) {
            return VehicleHealthStatus(
                level = HealthLevel.OK,
                summary = "WARMING UP · $coolantF°F",
                detail = "Engine warming up to operating temperature (175–205°F).",
            )
        }
        return VehicleHealthStatus(
            level = HealthLevel.OK,
            summary = "ALL SYSTEMS OK",
            detail = "Engine temperature and electrical charging are nominal.",
        )
    }

    private fun updateTrip(
        dtSec: Double,
        speedMph: Double,
        fuelFlowGph: Double,
        isDfco: Boolean,
        rpm: Double,
        status: ConnectionStatus,
    ) {
        if (rpm < 350) return // Engine off
        if (dtSec <= 0) return // Unobserved gap - see MAX_INTEGRATION_STEP_SEC

        val stepMiles = (speedMph / 3600) * dtSec
        val stepFuelGal = (fuelFlowGph / 3600) * dtSec

        var next = trip.copy(
            tripDurationSec = trip.tripDurationSec + dtSec,
            distanceMiles = trip.distanceMiles + stepMiles,
            totalFuelUsedGallons = trip.totalFuelUsedGallons + stepFuelGal,
            maxSpeedMph = max(trip.maxSpeedMph, speedMph),
            maxRpm = max(trip.maxRpm, rpm),
        )

        // The permanent record takes REAL VEHICLE DATA ONLY. The simulator runs whenever
        // nothing is connected, so anything looser than this fills the lifetime figure with
        // invented driving - which is exactly what it used to do.
        if (IntegrationRules.shouldRecordLifetime(status)) {
            updateLifetime(stepMiles, stepFuelGal)
        }

        if (speedMph <= 1.0) {
            val idleFuel = next.idleFuelGallons + stepFuelGal
            next = next.copy(
                idleTimeSec = next.idleTimeSec + dtSec,
                idleFuelGallons = idleFuel,
                idleCostDollars = roundTo(idleFuel * gasPricePerGallon, 2),
            )
        }

        if (isDfco) {
            // Against a baseline of idling at 0.22 gal/hr while coasting.
            val baselineIdleBurnRate = 0.22
            next = next.copy(
                coastingDfcoTimeSec = next.coastingDfcoTimeSec + dtSec,
                coastingFuelSavedGallons = next.coastingFuelSavedGallons +
                    (baselineIdleBurnRate / 3600) * dtSec,
            )
        }

        if (next.totalFuelUsedGallons > 0.005) {
            next = next.copy(avgMpg = roundTo(next.distanceMiles / next.totalFuelUsedGallons, 1))
        }
        if (next.tripDurationSec > 5) {
            next = next.copy(
                avgSpeedMph = roundTo((next.distanceMiles / next.tripDurationSec) * 3600, 1),
            )
        }

        // Eco score, from average MPG and how much of the trip was spent stationary.
        val targetMpg = 34.0 // 2013 Civic 5MT is ~38 mpg highway / 32 combined
        val mpgRatio = if (next.avgMpg > 0) min(1.2, next.avgMpg / targetMpg) else 1.0
        val idlePenalty = min(25.0, (next.idleTimeSec / max(60.0, next.tripDurationSec)) * 40)
        next = next.copy(
            ecoScore = max(10.0, min(100.0, (mpgRatio * 100 - idlePenalty).roundToLong().toDouble())).toInt(),
        )

        trip = next
    }

    private fun updateLifetime(stepMiles: Double, stepFuelGal: Double) {
        if (stepMiles <= 0 && stepFuelGal <= 0) return

        lifetime = lifetime.copy(
            totalMiles = lifetime.totalMiles + stepMiles,
            totalFuelGallons = lifetime.totalFuelGallons + stepFuelGal,
            firstTrackedTimestamp = if (lifetime.firstTrackedTimestamp == 0L) {
                clock.nowMillis()
            } else {
                lifetime.firstTrackedTimestamp
            },
        )

        // Debounced to once per 30 seconds - this runs on every tick.
        val now = clock.nowMillis()
        if (now - lifetimeSaveTimestamp >= 30_000) {
            lifetimeSaveTimestamp = now
            lifetimeStore.save(lifetime)
        }
    }

    fun getLifetimeStats(): LifetimeStats = lifetime

    fun getTrip(): TripAnalytics = trip

    /**
     * Writes the permanent record out now rather than waiting for the debounce.
     *
     * Called when a drive ends or the service is going away. The WebView build needed this on
     * every backgrounding because it was about to be killed; a foreground service is not, but
     * losing up to thirty seconds of a record that cannot be regenerated is still worth one
     * write.
     */
    fun flush() {
        lifetimeStore.save(lifetime)
        oilLife.saveProfile()
        tank.flush()
    }

    fun resetTrip() {
        trip = TripAnalytics(tripStartTime = clock.nowMillis())
    }

    /**
     * Closes the drive off as far as the oil model is concerned.
     *
     * Separate from [flush], which just writes out what is already accumulated and may run
     * at any time. This one has a side effect: a short trip that never got the oil hot is
     * counted here, and counting it twice would be counting a drive that did not happen.
     *
     * Does nothing if no engine start was counted, so ending a simulated drive - or a
     * connection that never saw the engine turn - leaves the record alone.
     */
    fun endDrive() {
        if (!engineStartCounted) return
        engineStartCounted = false
        oilLife.registerEngineStop()
    }

    /**
     * Wipes the permanent record.
     *
     * Deliberately not reachable from anything that runs on its own - this is the figure that
     * accumulated over real driving and cannot be recovered once it is gone.
     */
    fun resetLifetimeStats() {
        lifetime = LifetimeStats(firstTrackedTimestamp = clock.nowMillis())
        lifetimeStore.save(lifetime)
    }

    /** Seeds the record from a migration, e.g. the values rescued out of WebView storage. */
    fun importLifetimeStats(stats: LifetimeStats) {
        lifetime = stats
        lifetimeStore.save(stats)
    }

    fun setFuelBlend(id: FuelBlendId) {
        fuelModel.setFuelBlend(id)
    }

    fun getFuelBlend(): FuelBlendProperties = fuelModel.getFuelBlend()
}

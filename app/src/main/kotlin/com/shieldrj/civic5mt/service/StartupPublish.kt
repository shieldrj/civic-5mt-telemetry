package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ClutchHealthEngine
import com.shieldrj.civic5mt.core.ClutchProfileStore
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.FuelCalibrationStore
import com.shieldrj.civic5mt.core.LifetimeStore
import com.shieldrj.civic5mt.core.OilLifeEngine
import com.shieldrj.civic5mt.core.OilProfileStore

/**
 * Puts every persisted record on screen before anything has connected.
 *
 * Split out of the Application and given stores rather than a Context so it can be tested at
 * all, which is the point: the bug this fixes was not a wrong figure but an absent one, and
 * absence is what nothing was checking. Half these records were published at process start
 * and half were left to [TelemetryService], which only runs while an adapter is answering.
 * The half left behind were unreadable in exactly the place they are worth most - the Clutch
 * screen told a driver standing in his own garage that there was no record yet, with the
 * record on the same phone, and the Fuel screen dropped the fill corrections at the pump.
 *
 * Every store here holds a persisted fact rather than a live reading, so every one of them
 * belongs on screen with the car parked, the adapter in a drawer and Bluetooth off.
 *
 * Three different treatments, for three different reasons:
 *
 * - **Lifetime and calibration** are read straight off the file. Every derived figure on
 *   [com.shieldrj.civic5mt.core.FuelCalibrationState] - both correction factors - is a
 *   computed property over the samples rather than a stored number, so there is nothing that
 *   can go stale behind a correction and nothing for an engine to recompute.
 * - **Oil and clutch** go through their engines, whose constructors recompute the derived
 *   view from the stored measurements. Reading the oil file directly published the derived
 *   figures exactly as they were last written, which is how a corrected interval estimate
 *   stayed invisible behind the old one.
 * - **Oil and clutch are also guarded** on the record existing first, and that guard is not
 *   defensive tidiness. Both engines write a default profile out when constructed against an
 *   empty store, so an unguarded read would manufacture a clutch record for a car the app has
 *   never been plugged into - the precise fabrication that `defaultProfile()` was written to
 *   refuse. A gauge must not show a number the car never supplied.
 */
internal fun publishPersistedRecords(
    lifetime: LifetimeStore,
    oil: OilProfileStore,
    clutch: ClutchProfileStore,
    calibration: FuelCalibrationStore,
    blend: FuelBlendId,
) {
    lifetime.load()?.let { TelemetryState.setLifetime(it) }

    if (oil.load() != null) {
        TelemetryState.setOil(OilLifeEngine(oil).getProfile())
    }

    if (clutch.load() != null) {
        TelemetryState.setClutch(ClutchHealthEngine(clutch).getProfile())
    }

    calibration.load()?.let { TelemetryState.setCalibration(it) }

    TelemetryState.setFuelBlend(blend)
}

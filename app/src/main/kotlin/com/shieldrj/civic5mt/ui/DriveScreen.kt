package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldrj.civic5mt.core.ChargingRules
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.HealthLevel
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.MpgDisplayState
import com.shieldrj.civic5mt.core.ShiftMode
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.core.VehicleHealthStatus

/**
 * The Drive tab: what you look at while the car is moving.
 *
 * Laid out around one decision - MPG is the hero, and everything else is smaller than it. The
 * gauge is the only large thing on the screen, the shift cue is a single line above it, and
 * the supporting figures are text rather than more dials. Five gauges of equal size is five
 * things competing to be read at a glance, which means none of them is.
 */
@Composable
fun DriveScreen(
    metrics: LiveMetrics,
    trip: TripAnalytics,
    lifetime: LifetimeStats,
    connection: ConnectionStatus,
    shiftMode: ShiftMode,
    onToggleShiftMode: () -> Unit,
    onOpen: (DetailScreen) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ShiftLightBar(
            rpm = metrics.rpm,
            stage = metrics.shiftLightStage,
            shiftMode = shiftMode,
            shouldShiftUp = metrics.shouldShiftUp,
            currentGear = metrics.currentGear,
            onToggleMode = onToggleShiftMode,
        )

        Spacer(Modifier.height(14.dp))

        // Vehicle Health Status Banner: Prominent, glanceable indicator that gives instant peace of mind.
        // Tapping opens the Diagnostics screen or Clutch screen if clutch alert is active.
        HealthStatusBanner(
            status = metrics.healthStatus,
            onClick = {
                if (metrics.healthStatus.summary.startsWith("CLUTCH")) {
                    onOpen(DetailScreen.Clutch)
                } else {
                    onOpen(DetailScreen.Codes)
                }
            },
        )

        // The gauge floats in the space above; the supporting figures anchor to the bottom.
        Spacer(Modifier.weight(1f))

        /*
         * The hero is the tank, not the moment.
         */
        RadialGauge(
            value = (metrics.tankMpg ?: 0.0).toFloat(),
            min = 0f,
            max = 50f,
            title = "This tank",
            unit = "MPG",
            overrideValue = if (metrics.tankMpg == null) "—" else null,
            subValue = metrics.tankMilesSinceFill?.let { "%.0f mi on this tank".format(it) },
            ticks = listOf(0f, 25f, 50f),
            size = 260.dp,
            isHero = true,
        )

        Spacer(Modifier.height(12.dp))

        // Range, directly under the tank figure: stable, non-swinging miles to empty.
        //
        // Stable is the point everywhere except at the bottom of the tank, where it stops
        // being a virtue: under the sender's zero the figure holds still because nothing is
        // measuring it any more, not because the fuel is lasting. It is labelled as a
        // ceiling there. See TankState.belowSenderZero.
        Row(verticalAlignment = Alignment.Bottom) {
            if (metrics.tankBelowSenderZero && metrics.fuelRangeMiles != null) {
                Text(
                    text = "under ",
                    color = CivicColors.Ink3,
                    fontSize = 13.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                text = metrics.fuelRangeMiles?.toString() ?: "—",
                color = CivicColors.Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = " miles to empty",
                color = CivicColors.Ink3,
                fontSize = 13.sp,
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.weight(1f))

        // Vitals: Coolant and Charging Voltage only with semantic health colors.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            val coolantColor = when {
                metrics.coolantTempF >= 225 -> CivicColors.Accent
                metrics.coolantTempF >= 212 -> CivicColors.Warn
                metrics.coolantTempF < 160 && metrics.coolantTempF > 32 -> CivicColors.Cold
                metrics.coolantTempF >= 160 -> CivicColors.Good
                else -> CivicColors.Ink
            }
            Stat(
                label = "Coolant",
                value = if (metrics.coolantTempF > 0) "${metrics.coolantTempF}" else "—",
                unit = if (metrics.coolantTempF > 0) "°F" else "",
                color = coolantColor,
            )

            // Colour follows the same rules as the banner, and for the same reason: on this
            // car the ECM parks the alternator in the twelves at cruise on purpose, so
            // painting every reading under 12.8 amber painted an ordinary drive amber. Only
            // a reading under a rested battery is worth a colour. See ChargingRules.
            val volts = metrics.batteryVoltage
            val voltageColor = when {
                volts == null -> CivicColors.Ink
                metrics.rpm >= CivicSpecs.ENGINE_RUNNING_RPM &&
                    volts < ChargingRules.CRITICAL_VOLTS &&
                    volts > ChargingRules.MIN_PLAUSIBLE_VOLTS -> CivicColors.Accent
                metrics.rpm >= CivicSpecs.ENGINE_RUNNING_RPM &&
                    volts < ChargingRules.DRAIN_VOLTS &&
                    volts > ChargingRules.MIN_PLAUSIBLE_VOLTS -> CivicColors.Warn
                volts >= ChargingRules.HIGH_OUTPUT_VOLTS -> CivicColors.Good
                else -> CivicColors.Ink
            }
            Stat(
                label = "Charging",
                value = volts?.let { "%.2f".format(it) } ?: "—",
                unit = if (volts != null) "V" else "",
                color = voltageColor,
            )
        }

        Spacer(Modifier.height(18.dp))
        Hairline()
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "%.1f mi · %.1f mpg trip".format(trip.distanceMiles, trip.avgMpg),
                color = CivicColors.Ink2,
                fontSize = 13.sp,
            )
            Text(
                text = if (lifetime.totalMiles > 0) {
                    "%.1f mpg lifetime".format(lifetime.lifetimeMpg)
                } else {
                    "No lifetime record yet"
                },
                color = CivicColors.Ink3,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        if (connection == ConnectionStatus.SIMULATING) {
            Text(
                text = "SIMULATED — NOT RECORDED",
                color = CivicColors.Warn,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            )
        }

        if (connection == ConnectionStatus.RECONNECTING) {
            Text(
                text = "ADAPTER LOST — RECONNECTING",
                color = CivicColors.Warn,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Readings below are the last ones received. The drive is still open.",
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavLink("Fuel") { onOpen(DetailScreen.Fuel) }
            NavLink("Clutch") { onOpen(DetailScreen.Clutch) }
            NavLink("Oil") { onOpen(DetailScreen.Oil) }
            NavLink("Codes") { onOpen(DetailScreen.Codes) }
            NavLink("Trips") { onOpen(DetailScreen.Trips) }
            NavLink("Stop", CivicColors.Accent, onStop)
        }
    }
}

@Composable
private fun HealthStatusBanner(
    status: VehicleHealthStatus,
    onClick: () -> Unit,
) {
    val (bgColor, textColor) = when (status.level) {
        HealthLevel.CRITICAL -> Pair(Color(0x33D8453B), CivicColors.Accent)
        HealthLevel.ADVISORY -> Pair(Color(0x33C8952E), CivicColors.Warn)
        HealthLevel.OK -> Pair(Color(0x1F38B26B), CivicColors.Good)
    }

    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(bgColor, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(7.dp)
                    .width(7.dp)
                    .background(textColor, androidx.compose.foundation.shape.CircleShape)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = status.summary,
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun NavLink(
    label: String,
    color: androidx.compose.ui.graphics.Color = CivicColors.Ink2,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

@Composable
private fun Stat(
    label: String,
    value: String,
    unit: String,
    color: androidx.compose.ui.graphics.Color = CivicColors.Ink,
    dim: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = if (dim) CivicColors.Ink4 else color,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            if (unit.isNotEmpty()) {
                Spacer(Modifier.height(0.dp))
                Text(
                    text = " $unit",
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label.uppercase(),
            color = CivicColors.Ink3,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CivicColors.Hairline)
    )
}

/** Redline as a fraction of the gauge, for any dial that shows engine speed. */
internal val redlineRatio: Float
    get() = CivicSpecs.REDLINE_RPM.toFloat() / CivicSpecs.REV_LIMITER_RPM.toFloat()

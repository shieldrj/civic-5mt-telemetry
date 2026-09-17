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
    onOpenHealth: (HealthSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
    ) {
        val isLandscape = maxWidth > maxHeight

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left Column: Shift light, Health banner, Hero Tank Gauge & Range
                Column(
                    modifier = Modifier
                        .weight(1.1f)
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    ShiftLightBar(
                        rpm = metrics.rpm,
                        stage = metrics.shiftLightStage,
                        shiftMode = shiftMode,
                        shouldShiftUp = metrics.shouldShiftUp,
                        currentGear = metrics.currentGear,
                        onToggleMode = onToggleShiftMode,
                    )

                    HealthStatusBanner(
                        status = metrics.healthStatus,
                        onClick = {
                            if (metrics.healthStatus.summary.startsWith("CLUTCH")) {
                                onOpenHealth(HealthSection.Clutch)
                            } else {
                                onOpenHealth(HealthSection.Codes)
                            }
                        },
                    )

                    RadialGauge(
                        value = (metrics.tankMpg ?: 0.0).toFloat(),
                        min = 0f,
                        max = 50f,
                        title = "This tank",
                        unit = "MPG",
                        overrideValue = if (metrics.tankMpg == null) "—" else null,
                        subValue = metrics.tankMilesSinceFill?.let { "%.0f mi on this tank".format(it) },
                        ticks = listOf(0f, 25f, 50f),
                        size = 200.dp,
                        isHero = true,
                    )

                    RangeToEmpty(metrics, valueSize = 24.sp, captionSize = 11.sp)
                }

                // Right Column: Vitals, Trip, and Navigation
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Secondary vitals row
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

                        Stat(
                            label = "Speed",
                            value = if (metrics.speedMph >= 0) "%.0f".format(metrics.speedMph) else "—",
                            unit = "MPH",
                            color = CivicColors.Ink,
                        )
                    }

                    Hairline()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "%.1f mi · %.1f mpg trip".format(trip.distanceMiles, trip.avgMpg),
                            color = CivicColors.Ink2,
                            fontSize = 12.sp,
                        )
                        Text(
                            text = if (lifetime.totalMiles > 0) {
                                "%.1f mpg lifetime".format(lifetime.lifetimeMpg)
                            } else {
                                "No lifetime record"
                            },
                            color = CivicColors.Ink3,
                            fontSize = 12.sp,
                        )
                    }

                    if (connection == ConnectionStatus.RECONNECTING) {
                        Text(
                            text = "These readings are the last ones received. " +
                                "The drive is still open.",
                            color = CivicColors.Ink3,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        } else {
            // Portrait layout
            Column(
                modifier = Modifier
                    .fillMaxSize()
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

                HealthStatusBanner(
                    status = metrics.healthStatus,
                    onClick = {
                        if (metrics.healthStatus.summary.startsWith("CLUTCH")) {
                            onOpenHealth(HealthSection.Clutch)
                        } else {
                            onOpenHealth(HealthSection.Codes)
                        }
                    },
                )

                Spacer(Modifier.weight(1f))

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

                RangeToEmpty(metrics, valueSize = 30.sp, captionSize = 12.sp)

                Spacer(Modifier.weight(1f))

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

                if (connection == ConnectionStatus.RECONNECTING) {
                    Text(
                        text = "These readings are the last ones received. " +
                            "The drive is still open.",
                        color = CivicColors.Ink3,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/**
 * Distance to dry, and where the gauge gives up on the way there.
 *
 * The headline counts every gallon in the tank, reserve included, because that is the question
 * this app was asked: not "when does the needle hit the peg" - the dashboard answers that
 * already - but "when does the car stop". Honda's figure deliberately reaches zero with about
 * 1.9 gallons still in the tank, so it is the more cautious of the two and the less useful one
 * to a driver who wants to know what is really left.
 *
 * Both are still drawn, which is the part that matters and the part that was missing. Printed
 * alone, the total is what made the app read 142 on the day the low fuel light came on against
 * a dashboard reading about 35 - not because it was wrong, but because there was nothing on
 * screen to say the two numbers were answering different questions. The second line is that
 * explanation, and it is what project rule 6 is really asking for: never one number where
 * there are two.
 *
 * Worth knowing about the headline: the reserve is the least certain fuel in the tank. It is
 * the only part no sensor watches going down, so the last stretch of this figure is arithmetic
 * rather than measurement.
 */
@Composable
private fun RangeToEmpty(
    metrics: LiveMetrics,
    valueSize: androidx.compose.ui.unit.TextUnit,
    captionSize: androidx.compose.ui.unit.TextUnit,
) {
    val headline = metrics.fuelRangeMiles
    val toSenderZero = metrics.fuelRangeToSenderZeroMiles
    val reserve = metrics.fuelRangeReserveMiles

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (metrics.tankBelowSenderZero && headline != null) {
                Text(
                    text = "under ",
                    color = CivicColors.Ink3,
                    fontSize = captionSize,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(
                text = headline?.toString() ?: "—",
                color = CivicColors.Ink,
                fontSize = valueSize,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = " miles until dry",
                color = CivicColors.Ink3,
                fontSize = captionSize,
                modifier = Modifier.alignByBaseline(),
            )
        }
        // Only when there is a reserve to separate out. On a tank with no measured full mark
        // the two figures are the same number, and saying so twice is noise.
        if (toSenderZero != null && reserve != null && reserve > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "gauge reads empty at $toSenderZero mi",
                color = CivicColors.Ink4,
                fontSize = captionSize,
            )
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

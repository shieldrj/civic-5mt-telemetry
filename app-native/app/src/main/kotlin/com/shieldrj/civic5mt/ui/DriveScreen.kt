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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.MpgDisplayState
import com.shieldrj.civic5mt.core.ShiftMode
import com.shieldrj.civic5mt.core.TripAnalytics

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

        // The gauge floats in the space above; the supporting figures anchor to the bottom.
        // Splitting the slack evenly above and below them left a void on both sides of the
        // one row you glance at after the dial.
        Spacer(Modifier.weight(1f))

        /*
         * The hero.
         *
         * Two of the three states this can be in are not economy figures at all, and neither
         * is drawn as a number: standing still the value is zero because the car is not
         * moving, and on a closed throttle it is the 99.9 cap because the injectors are off.
         * A driver who sees "99.9" learns to distrust the gauge; a driver who sees "coasting"
         * learns what the car is doing.
         */
        val isEconomyReading = metrics.mpgDisplayState == MpgDisplayState.DRIVING
        RadialGauge(
            value = metrics.displayMpg.toFloat(),
            min = 0f,
            max = 60f,
            title = "Economy",
            unit = "MPG",
            overrideValue = when (metrics.mpgDisplayState) {
                MpgDisplayState.IDLE -> "Stationary"
                MpgDisplayState.COASTING -> "Coasting"
                MpgDisplayState.DRIVING -> null
            },
            // Short enough to sit inside the dial. A longer line runs out across the bottom
            // of the arc, where the ring is narrowest - the trip average belongs here, but
            // spelled out it collides with the thing it is annotating.
            subValue = if (isEconomyReading) {
                "%.1f avg".format(trip.avgMpg)
            } else if (metrics.mpgDisplayState == MpgDisplayState.COASTING) {
                "Injectors off"
            } else {
                null
            },
            ticks = listOf(0f, 30f, 60f),
            size = 260.dp,
            isHero = true,
        )

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Stat("Speed", "${metrics.speedMph.toInt()}", "mph")
            Stat("Coolant", "${metrics.coolantTempC.toInt()}", "°C")
            Stat(
                label = "Mixture",
                // Absent rather than assumed. On a car with no wideband this is the reading
                // that used to be a constant 1.0 nobody had measured.
                value = metrics.equivalenceRatio?.let { "%.2f".format(it) } ?: "—",
                unit = if (metrics.equivalenceRatio != null) "λ" else "",
                dim = metrics.equivalenceRatio == null,
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
                text = "%.1f mi this trip".format(trip.distanceMiles),
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

        // Says plainly when the numbers are invented. The permanent record refuses simulated
        // driving, and a screen that looks identical either way is how someone ends up
        // trusting a figure the bench produced.
        if (connection == ConnectionStatus.SIMULATING) {
            Text(
                text = "SIMULATED — NOT RECORDED",
                color = CivicColors.Warn,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            )
        }

        // The screen stays up through a reconnect because the drive is still open - the
        // distance and fuel on it are intact and will carry on. What must not stay up is the
        // impression that these numbers are current: they are the last thing the adapter
        // said, and a driver who is not told that reads them as live.
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

        // One line, because this screen is read through a windscreen and everything on it
        // competes with the gauge. The detail screens are reachable while connected because
        // that is when their live half means anything - the mixture, the burn rate and the
        // idle cost are all readings, not records.
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            NavLink("Fuel") { onOpen(DetailScreen.Fuel) }
            NavLink("Oil") { onOpen(DetailScreen.Oil) }
            NavLink("Codes") { onOpen(DetailScreen.Codes) }
            NavLink("Trips") { onOpen(DetailScreen.Trips) }
            NavLink("Stop", CivicColors.Accent, onStop)
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
private fun Stat(label: String, value: String, unit: String, dim: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = if (dim) CivicColors.Ink4 else CivicColors.Ink,
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

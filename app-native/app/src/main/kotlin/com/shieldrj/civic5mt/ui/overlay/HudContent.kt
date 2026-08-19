package com.shieldrj.civic5mt.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.GearSelection
import com.shieldrj.civic5mt.core.MpgDisplayState
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.ui.CivicColors

/**
 * The heads-up display: what is worth seeing while something else owns the screen.
 *
 * Almost everything is left out. The Drive screen has room to explain itself; this has to be
 * readable in the half-second of peripheral attention left over from navigating, and it is
 * sitting on top of a map someone is relying on. So: the gear, the shift bar, and economy.
 * Coolant, trims, fuel level and the rest are things you go and look at, not things you
 * glance at, and each one added here costs map.
 *
 * The two rules from the full screen still hold, and matter more at this size. MPG is drawn
 * as a state rather than a numeral when it is not an economy figure, and the shift bar is one
 * colour at a time.
 */
@Composable
fun HudContent() {
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()

    val ratio by animateFloatAsState(
        targetValue = (metrics.rpm / CivicSpecs.REV_LIMITER_RPM).coerceIn(0.0, 1.0).toFloat(),
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "hudShiftFill",
    )
    val atLimiter = metrics.shiftLightStage >= 5
    val fillColor by animateColorAsState(
        targetValue = when {
            atLimiter -> CivicColors.Accent
            metrics.shouldShiftUp -> CivicColors.Warn
            else -> CivicColors.Ink
        },
        animationSpec = tween(durationMillis = 220),
        label = "hudShiftColour",
    )

    Column(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(14.dp))
            // Not the app's solid ground: this sits on a map, and an opaque panel is a hole
            // punched in the thing being navigated by. Dark enough to read white text on,
            // sheer enough to see the road under it.
            .background(Color(0xE00E1013))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = gearLabel(metrics.currentGear),
                color = CivicColors.Ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = "${metrics.rpm.toInt()}",
                color = CivicColors.Ink2,
                fontSize = 13.sp,
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.height(7.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(1.5.dp))
                .background(CivicColors.GaugeTrack),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .background(fillColor),
            )
        }

        Spacer(Modifier.height(9.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            when (metrics.mpgDisplayState) {
                MpgDisplayState.DRIVING -> {
                    Text(
                        text = "%.1f".format(metrics.displayMpg),
                        color = CivicColors.Ink,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "MPG",
                        color = CivicColors.Ink3,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                MpgDisplayState.COASTING -> HudState("COASTING")
                MpgDisplayState.IDLE -> HudState("STATIONARY")
            }
        }
    }
}

@Composable
private fun HudState(label: String) {
    Text(
        text = label,
        color = CivicColors.Ink3,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.4.sp,
        modifier = Modifier.padding(vertical = 7.dp),
    )
}

private fun gearLabel(gear: GearSelection): String = when (gear) {
    is GearSelection.Gear -> gear.number.toString()
    GearSelection.Neutral -> "N"
    GearSelection.Clutch -> "—"
}

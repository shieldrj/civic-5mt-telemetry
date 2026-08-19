package com.shieldrj.civic5mt.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.GearSelection
import com.shieldrj.civic5mt.core.ShiftMode

/**
 * The shift cue, as one line.
 *
 * This was sixteen discrete LEDs in four colours with a 0.12s strobe over the top at redline.
 * It was the loudest thing in the app and it was also worse at its own job: a shift light is
 * read in peripheral vision, where a moving edge registers and a pattern of coloured squares
 * does not, and a 12Hz flash at the exact moment the driver's attention is worth most makes
 * the bar harder to read rather than easier.
 *
 * One continuous fill, one colour at a time, and a word when it is time to shift. Colour is
 * state rather than decoration: ink while there is nothing to do, amber once the shift point
 * is reached, red at the limiter.
 */
@Composable
fun ShiftLightBar(
    rpm: Double,
    stage: Int,
    shiftMode: ShiftMode,
    shouldShiftUp: Boolean,
    currentGear: GearSelection,
    onToggleMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val targetRatio = (rpm / CivicSpecs.REV_LIMITER_RPM).coerceIn(0.0, 1.0).toFloat()
    val ratio by animateFloatAsState(
        targetValue = targetRatio,
        // Fast and linear. This is the readout where lag is worst: it exists to tell you to
        // do something now, and a bar easing into position is reporting the past.
        animationSpec = tween(durationMillis = 90, easing = LinearEasing),
        label = "shiftFill",
    )

    val atLimiter = stage >= 5
    val fillColor by animateColorAsState(
        targetValue = when {
            atLimiter -> CivicColors.Accent
            shouldShiftUp -> CivicColors.Warn
            else -> CivicColors.Ink
        },
        animationSpec = tween(durationMillis = 220),
        label = "shiftColour",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            // Gear, then the mode switch, both as plain text. The gear used to be a
            // red-bordered pill and the mode a filled green capsule, which between them put
            // two boxes and two colours around information that changes a few times a minute.
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = gearLabel(currentGear),
                    color = CivicColors.Ink,
                    fontSize = 17.sp,
                    modifier = Modifier.alignByBaseline(),
                )
                if (currentGear is GearSelection.Gear) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "GEAR",
                        color = CivicColors.Ink3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.6.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    text = if (shiftMode == ShiftMode.ECO) "ECO SHIFTS" else "POWER SHIFTS",
                    color = if (shiftMode == ShiftMode.POWER) CivicColors.Accent else CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier
                        .alignByBaseline()
                        .clickable(onClick = onToggleMode),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = rpm.toInt().toString(),
                    color = CivicColors.Ink,
                    fontSize = 17.sp,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "RPM",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(CivicColors.GaugeTrack),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .background(fillColor),
            )
        }

        // Reserved height, so the layout does not jump every time the cue appears.
        Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
            if (shouldShiftUp) {
                Text(
                    text = if (atLimiter) "REDLINE" else "SHIFT UP",
                    color = if (atLimiter) CivicColors.Accent else CivicColors.Warn,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.6.sp,
                )
            }
        }
    }
}

private fun gearLabel(gear: GearSelection): String = when (gear) {
    is GearSelection.Gear -> gear.number.toString()
    GearSelection.Neutral -> "Neutral"
    GearSelection.Clutch -> "Clutch"
}

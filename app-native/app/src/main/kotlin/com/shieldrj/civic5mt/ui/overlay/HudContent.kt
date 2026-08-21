package com.shieldrj.civic5mt.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.ui.CivicColors

/**
 * The heads-up display: three economy figures, and nothing else.
 *
 * It used to carry the gear, a shift bar and live MPG - the Drive screen in miniature. Those
 * are all fast-moving numbers, and a fast-moving number on top of a map someone is navigating
 * by pulls the eye away every second. These three move slowly. Two of them barely move at all
 * inside one drive.
 *
 * They are also the three that answer a question rather than describe a moment. Instant MPG
 * says what the last two seconds cost, which nobody can act on. Miles per gallon over this
 * tank, miles per gallon over the life of the car, and how far is left have one answer each.
 *
 * Absent readings render as a dash. Tank MPG and range are both null until the car has
 * reported a fuel level and a fill has been seen, and a fabricated number here is one someone
 * drives past a filling station on.
 */
@Composable
fun HudContent() {
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .width(132.dp)
            .clip(RoundedCornerShape(14.dp))
            // Not the app's solid ground: this sits on a map, and an opaque panel is a hole
            // punched in the thing being navigated by. Dark enough to read white text on,
            // sheer enough to see the road under it.
            .background(Color(0xE00E1013))
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        HudEconomy(
            label = "THIS TANK",
            value = metrics.tankMpg?.let { "%.1f".format(it) },
            unit = "MPG",
        )

        Spacer(Modifier.height(11.dp))

        HudEconomy(
            label = "LIFETIME",
            // Zero miles means no car has ever been connected, which is an absence rather
            // than an economy of nothing.
            value = metrics.lifetimeMiles
                .takeIf { it > 0 }
                ?.let { "%.1f".format(metrics.lifetimeMpg) },
            unit = "MPG",
        )

        Spacer(Modifier.height(11.dp))

        HudEconomy(
            label = "TO EMPTY",
            value = metrics.fuelRangeMiles?.toString(),
            unit = "MI",
        )
    }
}

/**
 * One figure, with its heading above it.
 *
 * The heading is small and the number is large on purpose: at a glance the eye finds the row
 * by position, and reads the heading only when it is not sure which row it landed on.
 */
@Composable
private fun HudEconomy(label: String, value: String?, unit: String) {
    Text(
        text = label,
        color = CivicColors.Ink3,
        fontSize = 9.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.3.sp,
    )
    Spacer(Modifier.height(1.dp))
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Start,
    ) {
        Text(
            text = value ?: "—",
            color = if (value != null) CivicColors.Ink else CivicColors.Ink4,
            fontSize = 23.sp,
            fontWeight = FontWeight.Light,
            modifier = Modifier.alignByBaseline(),
        )
        if (value != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = unit,
                color = CivicColors.Ink3,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.1.sp,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

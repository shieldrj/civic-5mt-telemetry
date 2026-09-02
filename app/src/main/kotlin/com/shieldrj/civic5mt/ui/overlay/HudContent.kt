package com.shieldrj.civic5mt.ui.overlay

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.service.HudTheme
import com.shieldrj.civic5mt.service.TelemetryState

/**
 * Google Maps' own surface palettes, scoped to the overlay and deliberately not merged into
 * [com.shieldrj.civic5mt.ui.CivicColors]. The in-app screens are a dark instrument; this card
 * floats over Google Maps, and the way it disappears into that UI is by borrowing Maps'
 * tokens exactly. Sharing an accent with the app would only make the card look like a foreign
 * object pasted on.
 *
 * There are two, because Maps itself has two. By day its surfaces are white with near-black
 * text; at night they flip dark - and a white card glowing over a night-time map is exactly
 * the kind of glare a windscreen does not need. Following the system setting means the card
 * flips when Maps does.
 */
private data class MapsTokens(
    val Surface: Color,
    val OnSurface: Color,
    val OnSurfaceVariant: Color,
    val Divider: Color,
) {
    companion object {
        /** Maps' day palette. */
        val Light = MapsTokens(
            Surface = Color(0xFFFFFFFF),
            OnSurface = Color(0xFF202124),
            OnSurfaceVariant = Color(0xFF5F6368),
            Divider = Color(0xFFE8EAED),
        )

        /** Maps' night palette: the same structure, dimmed rather than inverted. */
        val Dark = MapsTokens(
            Surface = Color(0xFF2D2F31),
            OnSurface = Color(0xFFE3E3E3),
            OnSurfaceVariant = Color(0xFF9AA0A6),
            Divider = Color(0xFF3C4043),
        )

        /** Tabular figures: a changing digit must not shuffle the ones beside it. */
        val Numeric = TextStyle(fontFeatureSettings = "tnum")
    }
}

/**
 * The heads-up display: how much fuel is left and how far it goes, on a card styled after
 * Google Maps' own surfaces, because this window's whole life is spent floating over that app
 * while someone navigates by it. A card that looks native to the map reads as part of the
 * navigation; one that looks like a different app reads as clutter.
 *
 * It has been cut down twice. It began as the gear, a shift bar and live MPG - the Drive
 * screen in miniature - and every one of those is a number that moves every second, which on
 * top of a map someone is navigating by pulls the eye away and gives nothing back. Then it
 * carried three economy figures. Now it carries two, and they are two halves of one question:
 * how much fuel is in the car, and how far that gets you.
 *
 * Economy is not that question. Miles per gallon over a tank is what a driver acts on at the
 * pump, standing still, with the app open - which is where it still is, on the Fuel screen. It
 * is not what anyone needs from a card floating over a route.
 *
 * The percentage is the hero, and it is deliberately not the number on the dashboard. That
 * gauge shows 0 with a usable amount of fuel still in the tank; this one is a share of what
 * the tank really holds, so it reads 100 at the pump and still reads several percent when the
 * dashboard has given up. See TankState.fuelPercentRemaining for how the difference is
 * measured.
 *
 * It does not go on to zero, and saying it did was wrong. The reserve is the last thing the
 * sender can tell anyone about, so the figure stops there - at the reserve's share of the
 * tank, some seven percent - and holds while the fuel goes on down. That last stretch is
 * shown as "under 7" rather than "7", which is the whole of what is honestly known about it.
 * See TankState.belowSenderZero.
 *
 * Absent readings render as a dash. Both figures are null until the car has reported a fuel
 * level and a fill has been seen, and a fabricated number here is one someone drives past a
 * filling station on.
 */
@Composable
fun HudContent() {
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val hudTheme by TelemetryState.hudTheme.collectAsStateWithLifecycle()
    val tokens = when (hudTheme) {
        HudTheme.LIGHT -> MapsTokens.Light
        HudTheme.DARK -> MapsTokens.Dark
        // Google Maps themes itself independently of the phone, so "system" is only ever a
        // guess at what Maps is doing - which is why long-pressing the card can override it.
        HudTheme.SYSTEM -> if (isSystemInDarkTheme()) MapsTokens.Dark else MapsTokens.Light
    }

    // The outer padding exists because the overlay window sizes itself to this content:
    // without slack around the card, the window edge would shear the shadow off.
    Column(
        modifier = Modifier.padding(8.dp),
    ) {
        Column(
            modifier = Modifier
                .width(128.dp)
                // The shadow is what sells "this belongs to the map": every surface Google Maps
                // draws over its tiles carries exactly this kind of soft elevation shadow.
                .shadow(elevation = 6.dp, shape = RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(tokens.Surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            val fuelPercent = metrics.fuelPercentRemaining

            // The close target lives in the heading row rather than floating over the card,
            // so it cannot land on top of a number. It is drawn here and *hit* in
            // OverlayHost.DragHandler, which owns the only touch listener on this window -
            // see CLOSE_TARGET_DP, which is what keeps the two in agreement.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Fuel left",
                    color = tokens.OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = "✕",
                    color = tokens.OnSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(1.dp))
            // Once the sender is on its stop, the percentage stops counting down - there is
            // fuel below there but nothing measuring it - so the card says "under 7" rather
            // than "7". A card read at 60 mph is the last place to print a number that has
            // quietly stopped meaning what it says. See TankState.belowSenderZero.
            val bounded = metrics.tankBelowSenderZero

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Start,
            ) {
                if (bounded && fuelPercent != null) {
                    Text(
                        text = "under",
                        color = tokens.OnSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    // Whole numbers. A tenth of a percent of a tank is two tenths of a
                    // gallon, which is below what any of this can honestly resolve, and a
                    // decimal place would only give the digit something to fidget with.
                    text = fuelPercent?.let { "%.0f".format(it) } ?: "—",
                    color = if (fuelPercent != null) tokens.OnSurface else tokens.OnSurfaceVariant,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Medium,
                    style = MapsTokens.Numeric,
                    modifier = Modifier.alignByBaseline(),
                )
                if (fuelPercent != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "%",
                        color = tokens.OnSurfaceVariant,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }

            HudDivider(tokens)
            HudDetailRow(
                tokens,
                label = "To empty",
                // Abbreviated here and spelled out above, for width: the row is a label and a
                // reading inside a 128dp card, and "under 30 mi" is wider than the two of them
                // have between them. The word is on the figure that carries the card.
                value = metrics.fuelRangeMiles
                    ?.let { if (bounded) "< $it mi" else "$it mi" }
                    ?: "—",
            )
        }
    }
}

/**
 * One secondary figure: heading left, reading right, the way Maps lays out the detail rows
 * under a place name. Small enough to stay subordinate to the tank figure above.
 */
@Composable
private fun HudDetailRow(tokens: MapsTokens, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = tokens.OnSurfaceVariant,
            fontSize = 11.sp,
        )
        Text(
            text = value,
            color = tokens.OnSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            style = MapsTokens.Numeric,
        )
    }
}

/** A hairline, matching the dividers Maps draws between rows of a place card. */
@Composable
private fun HudDivider(tokens: MapsTokens) {
    HorizontalDivider(color = tokens.Divider, thickness = 1.dp)
}

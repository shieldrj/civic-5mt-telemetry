package com.shieldrj.civic5mt.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
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
    /** Maps' route blue, for a tank with plenty in it. */
    val Ring: Color,
    /** Maps' traffic amber, for a quarter tank and below. */
    val RingLow: Color,
    /** Maps' traffic red, for the reserve under E. */
    val RingEmpty: Color,
) {
    companion object {
        /** Maps' day palette. */
        val Light = MapsTokens(
            Surface = Color(0xFFFFFFFF),
            OnSurface = Color(0xFF202124),
            OnSurfaceVariant = Color(0xFF5F6368),
            Divider = Color(0xFFE8EAED),
            Ring = Color(0xFF1A73E8),
            RingLow = Color(0xFFF29900),
            RingEmpty = Color(0xFFD93025),
        )

        /** Maps' night palette: the same structure, dimmed rather than inverted. */
        val Dark = MapsTokens(
            Surface = Color(0xFF2D2F31),
            OnSurface = Color(0xFFE3E3E3),
            OnSurfaceVariant = Color(0xFF9AA0A6),
            Divider = Color(0xFF3C4043),
            Ring = Color(0xFF8AB4F8),
            RingLow = Color(0xFFFDD663),
            RingEmpty = Color(0xFFF28B82),
        )

        /** Tabular figures: a changing digit must not shuffle the ones beside it. */
        val Numeric = TextStyle(fontFeatureSettings = "tnum")
    }
}

/**
 * The heads-up display: a round bubble in the style of Google Maps' own floating buttons,
 * because its whole life is spent over that app while someone navigates by it. White (or
 * Maps' night grey), soft shadow, one number - the same visual family as the speed bubble and
 * the re-centre button, so it reads as part of the map rather than as something pasted on.
 *
 * The number is miles until the tank is dry, reserve included, matching the Drive screen: the
 * one question worth a glance mid-route. The ring around it is how full the tank is, as a
 * share of what the tank really holds (see TankState.fuelPercentRemaining) - blue with plenty,
 * amber at a quarter, red once the gauge is on E and the reserve is being counted down.
 *
 * It used to be a card carrying the percentage as a numeral and the miles beneath. A circle
 * has room for one figure, and the ring carries the other without asking to be read.
 *
 * Below E the miles are counted down from fuel burned rather than read off the gauge, so they
 * carry a "~". Absent readings render as a dash: a fabricated number here is one someone
 * drives past a filling station on.
 *
 * Tap opens the Fuel screen, long-press cycles light and dark, and dragging it onto the cross
 * that appears at the bottom of the screen closes it - see OverlayHost.
 */
@Composable
fun HudContent() {
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val hudTheme by TelemetryState.hudTheme.collectAsStateWithLifecycle()
    val tokens = when (hudTheme) {
        HudTheme.LIGHT -> MapsTokens.Light
        HudTheme.DARK -> MapsTokens.Dark
        // Google Maps themes itself independently of the phone, so "system" is only ever a
        // guess at what Maps is doing - which is why long-pressing the bubble can override it.
        HudTheme.SYSTEM -> if (isSystemInDarkTheme()) MapsTokens.Dark else MapsTokens.Light
    }

    val miles = metrics.fuelRangeMiles
    val percent = metrics.fuelPercentRemaining
    val onE = metrics.tankBelowSenderZero
    val ringColor = when {
        onE -> tokens.RingEmpty
        percent != null && percent < LOW_PERCENT -> tokens.RingLow
        else -> tokens.Ring
    }

    // The outer padding exists because the overlay window sizes itself to this content:
    // without slack around the bubble, the window edge would shear the shadow off.
    Box(modifier = Modifier.padding(8.dp)) {
        Box(
            modifier = Modifier
                .size(DIAMETER)
                .shadow(elevation = 6.dp, shape = CircleShape)
                .clip(CircleShape)
                .background(tokens.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                val stroke = RING_WIDTH.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = tokens.Divider,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                if (percent != null && percent > 0) {
                    drawArc(
                        color = ringColor,
                        // From the top, clockwise, the way a gauge that empties reads.
                        startAngle = -90f,
                        sweepAngle = 360f * (percent / 100.0).coerceIn(0.0, 1.0).toFloat(),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = miles?.let { if (onE) "~$it" else "$it" } ?: "—",
                    color = if (miles != null) tokens.OnSurface else tokens.OnSurfaceVariant,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    style = MapsTokens.Numeric.copy(lineHeight = 20.sp),
                )
                Text(
                    text = "mi",
                    color = tokens.OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(lineHeight = 10.sp),
                )
            }
        }
    }
}

/** Maps' own floating buttons are 48-56dp; this carries a number, so a little larger. */
private val DIAMETER = 68.dp

/** The fuel ring: thick enough to read at a glance, thin enough to stay a frame. */
private val RING_WIDTH = 4.dp

/** A quarter tank, where the ring turns amber. Around a hundred miles left on this car. */
private const val LOW_PERCENT = 25.0

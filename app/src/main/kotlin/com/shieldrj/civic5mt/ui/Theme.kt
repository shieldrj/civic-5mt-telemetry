package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The instrument's palette, carried over from the web build's `index.css` rather than
 * reinvented, so the two do not drift while both exist.
 *
 * One accent colour, and it is spent on one thing at a time. Dividers are hairlines rather
 * than filled cards. There is no light theme: this is read at night through a windscreen,
 * and a white background at 2am is a mirror.
 */
object CivicColors {
    val Ground = Color(0xFF101215)
    val Accent = Color(0xFFD8453B)
    val Ink = Color(0xFFEEF0F2)
    val Ink2 = Color(0xFF9AA1A9)
    val Ink3 = Color(0xFF6B727A)
    val Ink4 = Color(0xFF464C53)
    val Hairline = Color(0x12FFFFFF)
    val HairlineStrong = Color(0x21FFFFFF)

    /**
     * Amber, and the only colour here that is not accent or ink.
     *
     * It earns its place by meaning one thing: you are past the shift point but not at the
     * limiter. Red already means "stop doing that", so the intermediate state needed its own
     * colour rather than a lighter red, which reads as a dimmer alarm rather than a different
     * one.
     */
    val Warn = Color(0xFFC8952E)
    val Good = Color(0xFF38B26B)
    val Cold = Color(0xFF4A90E2)

    /** The unfilled part of a gauge or meter. A hairline, never a filled band. */
    val GaugeTrack = Color(0x17FFFFFF)
    val GaugeTick = Color(0x33FFFFFF)
}

/**
 * One typeface, and it is the system's.
 *
 * The web build carried a note about this: webfonts were dropped because a font request fails
 * in a tunnel or an underground car park, and the fallback shifted every layout at exactly
 * the moment the screen mattered. Native has no such failure mode, but the reason to prefer
 * the system face survives it - it is the one already rasterised, it renders at any size, and
 * it is what the rest of the phone uses.
 *
 * Numerals are the exception worth caring about: readings are set with tabular figures so a
 * changing digit does not shuffle the ones beside it.
 */
private val CivicTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 72.sp,
        letterSpacing = (-2).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)

private val CivicColorScheme = darkColorScheme(
    primary = CivicColors.Accent,
    onPrimary = CivicColors.Ink,
    background = CivicColors.Ground,
    onBackground = CivicColors.Ink,
    surface = CivicColors.Ground,
    onSurface = CivicColors.Ink,
    onSurfaceVariant = CivicColors.Ink2,
    outline = CivicColors.HairlineStrong,
    error = CivicColors.Accent,
)

@Composable
fun Civic5MTTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Deliberately not following the system setting. See the note above.
    MaterialTheme(
        colorScheme = CivicColorScheme,
        typography = CivicTypography,
        content = content,
    )
}

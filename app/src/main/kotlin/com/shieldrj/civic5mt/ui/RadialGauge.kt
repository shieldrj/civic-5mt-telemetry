package com.shieldrj.civic5mt.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A dial, stripped back to the parts that carry a reading.
 *
 * What is deliberately not here, carried over from the web build where it was removed: a
 * Gaussian glow on the value arc, a two-stop gradient along it, an outer bezel, a white
 * pointer bead ringed in the accent colour, five tick labels, and a bordered chip around the
 * sub-value. None of it encoded anything. The angle of the arc was already the whole message,
 * and every added mark competed with the numeral in the middle, which is what a driver
 * actually reads.
 *
 * What is here: a hairline track, a solid arc, three unlabelled ticks, and a short radial
 * mark at the value. The mark crosses the track so it reads as a watch hand pointing at a
 * scale rather than a bead sitting on top of one.
 *
 * The one thing native adds is honest motion. The web build moved the arc with a CSS
 * transition on a value that updates 12.5 times a second; here the angle is interpolated per
 * frame, so the hand sweeps rather than steps.
 */
@Composable
fun RadialGauge(
    value: Float,
    min: Float,
    max: Float,
    title: String,
    unit: String,
    modifier: Modifier = Modifier,
    /** Sits under the numeral as plain text. No chip, no border. */
    subValue: String? = null,
    /** Replaces the numeral entirely, for states that are not a reading at all. */
    overrideValue: String? = null,
    /** Arc and hand colour. Ink by default: a normal value is not coloured. */
    accentColor: Color = CivicColors.Ink,
    /** Where the scale stops being normal. A hairline, not a filled zone. */
    redlineStart: Float? = null,
    ticks: List<Float> = emptyList(),
    size: Dp = 220.dp,
    isHero: Boolean = false,
) {
    val clamped = value.coerceIn(min, max)
    val targetRatio = if (max > min) ((clamped - min) / (max - min)).coerceIn(0f, 1f) else 0f

    // Just over one tick of the telemetry loop, linear. Long enough to smooth the step,
    // short enough that the hand is never showing a stale reading.
    val ratio by animateFloatAsState(
        targetValue = targetRatio,
        animationSpec = tween(durationMillis = 110, easing = LinearEasing),
        label = "gaugeSweep",
    )

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val cx = this.size.width / 2f
            val cy = this.size.height / 2f
            // The dial sits inside the box with room for the ticks, which sit outside the arc.
            val radius = min(cx, cy) - this.size.width * 0.09f
            val arcTopLeft = Offset(cx - radius, cy - radius)
            val arcDimensions = Size(radius * 2f, radius * 2f)

            val hairline = this.size.width * 0.0068f
            val valueWidth = this.size.width * (if (isHero) 0.0136f else 0.0114f)

            // Track.
            drawArc(
                color = CivicColors.GaugeTrack,
                startAngle = START_ANGLE,
                sweepAngle = TOTAL_SWEEP,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcDimensions,
                style = Stroke(width = hairline, cap = StrokeCap.Butt),
            )

            // Redline: a hairline in the alert colour. It marks where the scale changes
            // meaning without occupying the scale.
            if (redlineStart != null && redlineStart < max) {
                val redStart = START_ANGLE + ((redlineStart - min) / (max - min)) * TOTAL_SWEEP
                drawArc(
                    color = CivicColors.Accent,
                    startAngle = redStart,
                    sweepAngle = START_ANGLE + TOTAL_SWEEP - redStart,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcDimensions,
                    style = Stroke(width = hairline, cap = StrokeCap.Butt),
                )
            }

            // Value.
            if (ratio > 0.004f) {
                drawArc(
                    color = accentColor,
                    startAngle = START_ANGLE,
                    sweepAngle = TOTAL_SWEEP * ratio,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcDimensions,
                    style = Stroke(width = valueWidth, cap = StrokeCap.Butt),
                )
            }

            // Three ticks: the ends and the middle. Enough to read the scale and no more.
            // Unlabelled, because the numeral in the centre is the reading and five small
            // numbers around the rim were competing with it.
            val tickInnerR = radius + this.size.width * 0.027f
            val tickOuterR = radius + this.size.width * 0.055f
            val tickStroke = this.size.width * 0.0045f
            for (t in ticks) {
                val deg = START_ANGLE + ((t - min) / (max - min)) * TOTAL_SWEEP
                val rad = Math.toRadians(deg.toDouble())
                val cosR = cos(rad).toFloat()
                val sinR = sin(rad).toFloat()
                drawLine(
                    color = CivicColors.GaugeTick,
                    start = Offset(cx + tickInnerR * cosR, cy + tickInnerR * sinR),
                    end = Offset(cx + tickOuterR * cosR, cy + tickOuterR * sinR),
                    strokeWidth = tickStroke,
                )
            }

            // The hand. Crosses the track, so it points at the scale.
            if (overrideValue == null) {
                val deg = START_ANGLE + TOTAL_SWEEP * ratio
                val rad = Math.toRadians(deg.toDouble())
                val cosR = cos(rad).toFloat()
                val sinR = sin(rad).toFloat()
                val handInnerR = radius - this.size.width * 0.036f
                val handOuterR = radius + this.size.width * 0.009f
                drawLine(
                    color = accentColor,
                    start = Offset(cx + handInnerR * cosR, cy + handInnerR * sinR),
                    end = Offset(cx + handOuterR * cosR, cy + handOuterR * sinR),
                    strokeWidth = this.size.width * (if (isHero) 0.0102f else 0.0091f),
                    cap = StrokeCap.Butt,
                )
            }
        }

        // Centre readout. Sizes derive from the dial, because the screen fits the gauge to
        // the space it has - fixed type would be lost on a big dial and oversized on a small
        // one. The label and sub-value are clamped at both ends; only the numeral grows
        // without a ceiling, because it is the one thing being read.
        val px = size.value
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = size * 0.12f),
        ) {
            Text(
                text = title.uppercase(),
                color = CivicColors.Ink3,
                fontSize = min(12f, max(9f, px * 0.045f)).sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height((px * 0.03f).dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = overrideValue ?: formatReading(value, unit),
                    color = if (overrideValue != null) CivicColors.Ink3 else CivicColors.Ink,
                    fontSize = max(30f, px * (if (overrideValue != null) 0.13f else 0.24f)).sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-1).sp,
                    modifier = Modifier.alignByBaseline(),
                )
                if (overrideValue == null) {
                    Spacer(Modifier.width((px * 0.02f).dp))
                    Text(
                        text = unit,
                        color = CivicColors.Ink3,
                        fontSize = max(11f, px * 0.072f).sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }

            if (subValue != null) {
                Spacer(Modifier.height((px * 0.045f).dp))
                Text(
                    text = subValue,
                    color = CivicColors.Ink2,
                    fontSize = min(13f, max(10f, px * 0.05f)).sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** 250 degrees, from bottom-left round to bottom-right. */
private const val START_ANGLE = 145f
private const val TOTAL_SWEEP = 250f

private fun formatReading(value: Float, unit: String): String =
    if (unit == "%" || unit == "°F" || unit == "°C" || value >= 100f) {
        value.toInt().toString()
    } else {
        "%.1f".format(value)
    }

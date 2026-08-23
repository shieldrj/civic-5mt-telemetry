package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.OilLifeProfile
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import kotlin.math.roundToInt

/**
 * Oil life, and what used it up.
 *
 * Reads a persisted record rather than a live one, so it works with the car parked, the
 * adapter in a drawer and Bluetooth off - which is exactly when someone stands in a garage
 * wondering whether to change the oil. The profile is published by the Application at process
 * start for that reason, not by the service, which only exists while something is connected.
 *
 * The ring is a percentage of a fixed span, which is the one thing a ring is genuinely good
 * for. Everything under it is text: four figures in four boxes is mostly four boxes.
 */
@Composable
fun OilScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)

    val context = LocalContext.current
    val profile by TelemetryState.oil.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "OIL LIFE",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(18.dp))

        val p = profile
        if (p == null) {
            Text(
                text = "No oil record yet. It starts accumulating the first time the app is " +
                    "connected to the car with the engine running.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )
        } else {
            OilBody(p)

            Spacer(Modifier.height(24.dp))
            OilHairline()
            Spacer(Modifier.height(12.dp))

            if (!confirmingReset) {
                OilAction("Reset to 100%") { confirmingReset = true }
            } else {
                // What it costs, stated before the button - the same rule the code clearing
                // follows. Resetting is not a repair either: it throws away the only record
                // of how this oil was actually treated, and done without an oil change it
                // leaves a number on screen that reads exactly like a measurement.
                Text(
                    text = "Starts the interval again at 100% and throws away the crank " +
                        "revolutions, the cold starts and the short trips behind the current " +
                        "figure. Nothing can regenerate them. Do this after the oil and filter " +
                        "are changed, not before.",
                    color = CivicColors.Ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                OilAction("Reset anyway") {
                    confirmingReset = false
                    TelemetryService.resetOilLife(context)
                }
                OilAction("Cancel", CivicColors.Ink3) { confirmingReset = false }
            }
        }

        OilAction("Back", CivicColors.Accent, onBack)
    }
}

@Composable
private fun OilBody(p: OilLifeProfile) {
    val percent = p.oilLifePercent
    val tone = when {
        percent < 15 -> CivicColors.Accent
        percent < 40 -> CivicColors.Warn
        else -> CivicColors.Ink
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            OilRing(percent = percent, tone = tone)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "${percent.roundToInt()}",
                    color = tone,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.alignByBaseline(),
                )
                Text(
                    text = "%",
                    color = tone,
                    fontSize = 15.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }

        Spacer(Modifier.width(24.dp))

        Column {
            Text(
                text = "REMAINING",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "%,d".format(p.estimatedMilesRemaining),
                    color = CivicColors.Ink,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "mi",
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // No days figure until the model has watched long enough to have one.
                text = p.estimatedDaysRemaining
                    ?.let { "about $it days · ${p.oilConditionGrade.label}" }
                    ?: p.oilConditionGrade.label,
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    Factor(
        label = "Crank revolutions",
        value = "%.2fM".format(p.accumulatedRevolutions / 1_000_000),
        note = "${p.degradationBreakdown.revWearFactor}% of wear",
    )
    Factor(
        label = "Cold starts",
        value = p.coldStartsCount.toString(),
        note = "+${p.degradationBreakdown.coldStartPenalty}% dilution",
    )
    Factor(
        label = "Short trips",
        value = p.shortTripsCount.toString(),
        note = "+${p.degradationBreakdown.shortTripPenalty}%",
    )
    Factor(
        label = "High-rpm time",
        // Minutes past a minute. The web build printed raw seconds, so a season of enjoying
        // the car read as "54600s" - the same figure, unreadable at a glance.
        value = formatWearSeconds(p.highThermalStressSec),
        note = "+${p.degradationBreakdown.thermalShearPenalty}%",
    )

    Spacer(Modifier.height(20.dp))
    OilHairline()
    Spacer(Modifier.height(16.dp))

    Text(
        text = "WHAT USED IT UP",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(10.dp))
    DegradationMeter(p)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Revolutions · Cold starts · Short trips · Thermal",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
    )

    Spacer(Modifier.height(18.dp))
    Text(
        // Said plainly, because the mileage figure looks like it came from the car and did
        // not. There is no odometer PID on this Civic, so distance is integrated from vehicle
        // speed while the app is connected - miles driven with the phone at home are miles
        // this record never saw, and the interval estimate is long by exactly that much.
        text = "Mileage is counted from vehicle speed while the app is connected, not read " +
            "from the car's odometer. Drives made without it running are not in this figure.",
        color = CivicColors.Ink4,
        fontSize = 12.sp,
    )
}

/**
 * The four penalties on one bar, against the full 100% span.
 *
 * The unfilled remainder is the oil that is left, which is the whole point of drawing it on a
 * fixed span rather than normalising the four to fill the width. They run light to dark
 * rather than through four hues - four colours on one bar is a chart that needs a legend
 * nobody reads.
 */
@Composable
private fun DegradationMeter(p: OilLifeProfile) {
    val b = p.degradationBreakdown
    val parts = listOf(
        b.revWearFactor to CivicColors.Ink,
        b.coldStartPenalty to CivicColors.Ink2,
        b.shortTripPenalty to CivicColors.Ink3,
        b.thermalShearPenalty to CivicColors.Warn,
    ).map { (v, c) -> v.coerceAtLeast(0.0) to c }

    val total = parts.sumOf { it.first }
    // Past 100% the oil is spent and the bar is full; scaling keeps the proportions readable
    // rather than letting one segment run off the end and take the others with it.
    val scale = if (total > 100.0) 100.0 / total else 1.0
    val remainder = (100.0 - total * scale).toFloat()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(CivicColors.GaugeTrack),
    ) {
        parts.forEach { (value, color) ->
            val weight = (value * scale).toFloat()
            if (weight > 0f) {
                Box(
                    Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(color)
                )
            }
        }
        if (remainder > 0f) Spacer(Modifier.weight(remainder))
    }
}

@Composable
private fun OilRing(percent: Double, tone: Color) {
    Canvas(modifier = Modifier.size(132.dp)) {
        val stroke = 3.dp.toPx()
        val inset = stroke / 2 + 4.dp.toPx()
        val diameter = size.minDimension - inset * 2
        val topLeft = Offset(inset, inset)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = CivicColors.GaugeTrack,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        drawArc(
            color = tone,
            startAngle = -90f,
            sweepAngle = (percent.coerceIn(0.0, 100.0) / 100.0 * 360.0).toFloat(),
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            // Butt cap rather than round: a rounded cap adds half a stroke to each end, which
            // overstates a small remainder - and a small remainder is the reading on this
            // particular dial that someone acts on.
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun Factor(label: String, value: String, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = CivicColors.Ink2, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = CivicColors.Ink, fontSize = 14.sp)
            Spacer(Modifier.width(10.dp))
            Text(note, color = CivicColors.Ink3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OilAction(
    label: String,
    color: Color = CivicColors.Accent,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = color,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

@Composable
private fun OilHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
}

private fun formatWearSeconds(seconds: Double): String {
    val total = seconds.roundToInt()
    if (total < 60) return "${total}s"
    val minutes = total / 60
    if (minutes < 60) return "${minutes}m ${total % 60}s"
    return "%dh %02dm".format(minutes / 60, minutes % 60)
}

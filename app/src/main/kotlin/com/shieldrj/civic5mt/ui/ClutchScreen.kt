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
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ClutchLiveStatus
import com.shieldrj.civic5mt.core.ClutchProfile
import com.shieldrj.civic5mt.core.ClutchSlipIncident
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Dedicated Clutch Analysis & Remaining Useful Life (RUL) Prognostics Screen.
 *
 * Provides real-time slip detection, thermodynamic disc temperature modeling,
 * Archard physical wear law tracking, and remaining useful life projections.
 */
@Composable
fun ClutchScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.activity.compose.BackHandler(onBack = onBack)

    val context = LocalContext.current
    val profile by TelemetryState.clutch.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    var confirmingReset by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "CLUTCH HEALTH & PROGNOSTICS",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(18.dp))

        val p = profile
        if (p == null) {
            Text(
                text = "No clutch telemetry record yet. It starts calculating when the app is " +
                    "connected to the vehicle with the drivetrain engaged.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )
        } else {
            ClutchBody(p, metrics.clutchStatus)

            Spacer(Modifier.height(24.dp))
            ClutchHairline()
            Spacer(Modifier.height(12.dp))

            if (!confirmingReset) {
                ClutchAction("Reset to 100% (New Clutch)") { confirmingReset = true }
            } else {
                Text(
                    text = "Resets the baseline wear model to 100% and clears accumulated " +
                        "friction energy and incident logs. Do this only after physically " +
                        "replacing the clutch disc, pressure plate, and throwout bearing.",
                    color = CivicColors.Ink2,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                ClutchAction("Confirm Reset") {
                    confirmingReset = false
                    TelemetryService.resetClutch(context)
                }
                ClutchAction("Cancel", CivicColors.Ink3) { confirmingReset = false }
            }
        }

        ClutchAction("Back", CivicColors.Accent, onBack)
    }
}

@Composable
private fun ClutchBody(p: ClutchProfile, live: ClutchLiveStatus) {
    val percent = p.clutchHealthPercent
    val tone = when {
        percent < 20 -> CivicColors.Accent
        percent < 50 -> CivicColors.Warn
        else -> CivicColors.Ink
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            ClutchRing(percent = percent, tone = tone)
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
                text = "ESTIMATED LIFE",
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
                text = "%,d shifts left · %s".format(
                    p.estimatedShiftsRemaining,
                    p.conditionGrade.label,
                ),
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    ClutchHairline()
    Spacer(Modifier.height(16.dp))

    Text(
        text = "LIVE DRIVETRAIN TELEMETRY",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(12.dp))

    val slipTone = if (live.isMacroSlip) CivicColors.Accent else if (live.isSlipping) CivicColors.Warn else CivicColors.Ink
    ClutchMetricRow(
        label = "Slip speed",
        value = if (live.slipRpm > 30) "+${live.slipRpm.toInt()} RPM" else "0 RPM",
        note = live.classification.label,
        valueColor = slipTone,
    )
    ClutchMetricRow(
        label = "Disc temperature",
        value = "%.0f°C (%.0f°F)".format(live.discTempC, live.discTempC * 9 / 5 + 32),
        note = if (live.discTempC > 180) "Thermal glaze risk" else "Nominal",
        valueColor = if (live.discTempC > 180) CivicColors.Accent else CivicColors.Ink,
    )
    ClutchMetricRow(
        label = "Torque capacity",
        value = "%.0f N·m".format(p.estimatedTorqueCapacityNm),
        note = "Engine peak: %.0f N·m".format(CivicSpecs.ENGINE_PEAK_TORQUE_NM),
    )

    Spacer(Modifier.height(20.dp))
    ClutchHairline()
    Spacer(Modifier.height(16.dp))

    Text(
        text = "WEAR ACCUMULATION & STRESS",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(12.dp))

    ClutchMetricRow(
        label = "Total engagements",
        value = "%,d".format(p.totalEngagementsCount),
        note = "Shifts recorded",
    )
    ClutchMetricRow(
        label = "Friction energy work",
        value = "%.2f MJ".format(p.accumulatedFrictionEnergyJoules / 1_000_000.0),
        note = "of %.1f MJ budget".format(CivicSpecs.BASELINE_CLUTCH_LIFETIME_JOULES / 1_000_000.0),
    )
    ClutchMetricRow(
        label = "Abnormal slip events",
        value = p.abnormalSlipCount.toString(),
        note = if (p.abnormalSlipCount > 0) "${p.degradationBreakdown.slipWearPercent.roundToInt()}% life impact" else "None",
        valueColor = if (p.abnormalSlipCount > 0) CivicColors.Warn else CivicColors.Ink,
    )
    ClutchMetricRow(
        label = "Max disc temp seen",
        value = "%.0f°C".format(p.maxObservedTempC),
        note = if (p.maxObservedTempC > CivicSpecs.CLUTCH_GLAZE_TEMP_THRESHOLD_C) "Glazed penalty applied" else "Within limits",
    )

    Spacer(Modifier.height(20.dp))
    ClutchHairline()
    Spacer(Modifier.height(16.dp))

    Text(
        text = "DEGRADATION ATTRIBUTION",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(10.dp))
    ClutchDegradationMeter(p)
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Shift wear · Launch wear · Macro-slip · Thermal glaze",
        color = CivicColors.Ink3,
        fontSize = 11.sp,
    )

    if (p.recentIncidents.isNotEmpty()) {
        Spacer(Modifier.height(20.dp))
        ClutchHairline()
        Spacer(Modifier.height(16.dp))

        Text(
            text = "RECENT SLIP INCIDENTS",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(10.dp))

        p.recentIncidents.take(5).forEach { incident ->
            IncidentRow(incident)
        }
    }

    Spacer(Modifier.height(18.dp))
    Text(
        text = "Clutch holding capacity and RUL are computed via thermodynamic slip energy " +
            "integration and Archard friction material wear modeling calibrated for the " +
            "2013 Civic 5MT (212mm disc, 4500 N clamp load).",
        color = CivicColors.Ink4,
        fontSize = 12.sp,
    )
}

@Composable
private fun IncidentRow(incident: ClutchSlipIncident) {
    val dateStr = remember(incident.timestamp) {
        val sdf = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        sdf.format(Date(incident.timestamp))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Gear ${incident.gear} · +${incident.peakSlipRpm.toInt()} RPM flare",
                color = CivicColors.Warn,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "$dateStr · ${"%.1f".format(incident.durationSec)}s duration",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
            )
        }
        Text(
            text = "${incident.peakTorqueNm.toInt()} N·m",
            color = CivicColors.Ink2,
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ClutchDegradationMeter(p: ClutchProfile) {
    val b = p.degradationBreakdown
    val parts = listOf(
        b.shiftWearPercent to CivicColors.Ink,
        b.launchWearPercent to CivicColors.Ink2,
        b.slipWearPercent to CivicColors.Warn,
        b.thermalGlazePenaltyPercent to CivicColors.Accent,
    ).map { (v, c) -> v.coerceAtLeast(0.0) to c }

    val total = parts.sumOf { it.first }
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
private fun ClutchRing(percent: Double, tone: Color) {
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
            style = Stroke(width = stroke),
        )
    }
}

@Composable
private fun ClutchMetricRow(
    label: String,
    value: String,
    note: String,
    valueColor: Color = CivicColors.Ink,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = CivicColors.Ink2, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = valueColor, fontSize = 14.sp, fontWeight = FontWeight.Normal)
            Spacer(Modifier.width(10.dp))
            Text(note, color = CivicColors.Ink3, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ClutchAction(
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
private fun ClutchHairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
}

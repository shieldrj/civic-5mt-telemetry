package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.FUEL_BLENDS
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.OUNCES_PER_US_GALLON
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.core.fuelBlend
import com.shieldrj.civic5mt.core.isClosedLoop
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.saveFuelBlend
import kotlin.math.roundToInt

/**
 * What the engine is actually burning, and what it is burning.
 *
 * The live half is only drawn while something is connected - a mixture reading from a link
 * that dropped ten minutes ago is worse than none. The blend picker is the other half and is
 * always available, because it is set standing at a pump with the engine off.
 */
@Composable
fun FuelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val blendId by TelemetryState.fuelBlend.collectAsStateWithLifecycle()
    val blend = fuelBlend(blendId)

    val live = connection == ConnectionStatus.CONNECTED ||
        connection == ConnectionStatus.SIMULATING

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "FUEL",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(18.dp))

        if (live) {
            LiveFuel(metrics, trip, blend.stoichAfr)
            Spacer(Modifier.height(24.dp))
            IdleSection(trip)
            Spacer(Modifier.height(24.dp))
        } else {
            Text(
                text = "Nothing connected. Mixture, burn rate and idle cost are live readings " +
                    "and are not kept on screen after the link drops.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(24.dp))
        }

        TankSection(
            metrics = metrics,
            live = live,
            activeId = blendId,
            onSelect = { id ->
                // The screen owns this preference: it writes both the flow the service
                // observes and the file it survives in. See TelemetryState.fuelBlend.
                TelemetryState.setFuelBlend(id)
                saveFuelBlend(context, id)
            },
        )

        if (live && metrics.fuelLevelPercent != null) {
            Spacer(Modifier.height(24.dp))
            SectionHeading("Just filled up?", null)
            Text(
                text = "A fill is normally noticed on its own, from the level rising. Use this " +
                    "for a few gallons rather than a tankful, or if the count looks wrong. It " +
                    "restarts the miles and the fuel for this tank.",
                color = CivicColors.Ink3,
                fontSize = 12.5.sp,
            )
            Text(
                text = "Start a new tank",
                color = CivicColors.Accent,
                fontSize = 15.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { TelemetryService.markFilled(context) }
                    .padding(vertical = 12.dp),
            )
        }

        if (live) {
            Spacer(Modifier.height(24.dp))
            OxygenSection(metrics)
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "Back",
            color = CivicColors.Accent,
            fontSize = 15.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = 12.dp),
        )
    }
}

// ── The two live figures ────────────────────────────────────────────────────────

@Composable
private fun LiveFuel(metrics: LiveMetrics, trip: TripAnalytics, stoichAfr: Double) {
    val isDfco = metrics.isDfcoActive

    // This tank, and how much of it is left. Instant MPG used to be here and is gone: it is
    // on the dashboard already, and a figure that changes every second cannot be compared to
    // anything. Burn rate stays, further down, because gallons per hour at a standstill is a
    // different question and has a real answer.
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Label("THIS TANK")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = metrics.tankMpg?.let { "%.1f".format(it) } ?: "—",
                    color = if (metrics.tankMpg == null) CivicColors.Ink3 else CivicColors.Ink,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "mpg",
                    color = CivicColors.Ink3,
                    fontSize = 13.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                // Three different reasons for having no figure, and they are not the same
                // thing. The bench reports a tank level and is simply not allowed to spend
                // anyone's fuel; a car with no PID 2F has nothing to report at all.
                text = when {
                    metrics.tankMilesSinceFill != null ->
                        "%.0f mi since the fill".format(metrics.tankMilesSinceFill)
                    metrics.fuelLevelPercent == null -> "no tank level from this car"
                    else -> "not tracked on a simulated drive"
                },
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }

        Column(Modifier.weight(1f)) {
            Label("TO EMPTY")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = metrics.fuelRangeMiles?.toString() ?: "—",
                    color = CivicColors.Ink,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = "mi",
                    color = CivicColors.Ink3,
                    fontSize = 13.sp,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = metrics.tankGallonsRemaining
                    ?.let { "%.1f gal left".format(it) }
                    ?: "",
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
    }

    Spacer(Modifier.height(24.dp))
    SectionHeading("Combustion", if (isDfco) "Fuel cut" else "Closed loop")

    val afr = metrics.airFuelRatio
    // Judged against the blend rather than gasoline's 14.7. Stoichiometry moves with the
    // fuel, so measuring an E10 mixture against pure gasoline reads every normal cruise as
    // rich - which is how a driver learns to ignore the reading.
    val lambda = if (stoichAfr > 0) afr / stoichAfr else 1.0
    val offStoich = lambda < 0.97 || lambda > 1.03
    val afrStatus = when {
        lambda < 0.97 -> "Rich"
        lambda > 1.03 -> "Lean"
        else -> "Stoichiometric"
    }

    ValueRow(
        label = "Air to fuel",
        value = "%.2f:1".format(afr),
        // Whether the mixture was measured or inferred, said out loud. This car has no
        // narrowband front sensor, so the figure comes from the fuel trims - a legitimate
        // derivation, and not the same claim as a wideband reading.
        note = metrics.equivalenceRatio
            ?.let { "$afrStatus · λ %.3f".format(it) }
            ?: "$afrStatus · from fuel trims",
    )
    Spacer(Modifier.height(8.dp))
    Meter(
        fraction = (((afr - 10.0) / 10.0).coerceIn(0.0, 1.0)).toFloat(),
        markerFraction = (((stoichAfr - 10.0) / 10.0).coerceIn(0.0, 1.0)).toFloat(),
        color = if (offStoich) CivicColors.Warn else CivicColors.Ink,
    )
    Spacer(Modifier.height(12.dp))
    ValueRow(
        label = "Burn rate",
        value = "%.2f gal/hr".format(metrics.fuelFlowGalPerHour),
        // Ounces a minute rather than litres an hour. Gallons an hour is a small number at
        // idle, where this reading is most often looked at.
        note = "%.1f fl oz/min".format(metrics.fuelFlowGalPerHour * OUNCES_PER_US_GALLON / 60),
    )
    ValueRow(
        label = "ECU fuel trims",
        value = "Short %s%.1f%%   Long %s%.1f%%".format(
            if (metrics.shortTermFuelTrim > 0) "+" else "",
            metrics.shortTermFuelTrim,
            if (metrics.longTermFuelTrim > 0) "+" else "",
            metrics.longTermFuelTrim,
        ),
    )

    // Absent rather than assumed: a car with no PID 03 shows no row at all. It sits directly
    // under the trims because it is what says how to read them - in closed loop they are a
    // correction towards stoichiometric, and in open loop the ECU is following an enrichment
    // map instead and the air:fuel figure above is not what the trims describe.
    metrics.fuelSystemStatusLabel?.let { status ->
        ValueRow(
            label = "Fuel system",
            value = status,
            note = if (isClosedLoop(metrics.fuelSystemStatus)) null else "trims are not feedback here",
        )
    }
}

// ── Idling ──────────────────────────────────────────────────────────────────────

@Composable
private fun IdleSection(trip: TripAnalytics) {
    val total = trip.idleTimeSec.roundToInt()
    SectionHeading("Idling", "${total / 60}m ${total % 60}s this trip")

    // US fluid ounces. The web build printed gallons multiplied by a thousand and labelled
    // the result mL, which understated it by a factor of 3.79 - so a long wait at a level
    // crossing looked like a thimble of fuel. Ounces now, because that is what the rest of
    // this app measures in.
    val ounces = trip.idleFuelGallons * OUNCES_PER_US_GALLON
    ValueRow(
        label = "Burned at a standstill",
        value = "%.1f fl oz".format(ounces),
        note = "$%.2f".format(trip.idleCostDollars),
    )
}

// ── The tank, and what is in it ─────────────────────────────────────────────────

@Composable
private fun TankSection(
    metrics: LiveMetrics,
    live: Boolean,
    activeId: FuelBlendId,
    onSelect: (FuelBlendId) -> Unit,
) {
    val blend = fuelBlend(activeId)
    SectionHeading(
        title = "Fuel in the tank",
        aside = "%.2f:1 · %.0f g/L".format(blend.stoichAfr, blend.densityGramsPerLiter),
    )

    if (live) {
        // Both absent on a car with no PID 2F, and drawn as absences rather than as a
        // five-eighths tank. That default is what this port removed.
        ValueRow(
            label = "Tank level",
            value = metrics.fuelLevelPercent?.let { "%.0f%%".format(it) } ?: "—",
            note = if (metrics.fuelLevelPercent == null) "not reported by this car" else null,
        )
        ValueRow(
            label = "Range",
            value = metrics.fuelRangeMiles?.let { "$it mi" } ?: "—",
            note = if (metrics.fuelRangeMiles != null) "at the last 30s of economy" else null,
        )
        Spacer(Modifier.height(8.dp))
    }

    Text(
        text = "Sets the stoichiometric ratio and the density behind every fuel figure on " +
            "this screen.",
        color = CivicColors.Ink3,
        fontSize = 12.5.sp,
    )
    Spacer(Modifier.height(12.dp))

    // Selected is white text over an accent hairline, not a filled block. It is a setting
    // someone changes about once a year and it should not be the loudest thing on screen.
    Row(modifier = Modifier.fillMaxWidth()) {
        FUEL_BLENDS.keys.forEach { id ->
            val selected = id == activeId
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(id) }
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = id.name,
                    color = if (selected) CivicColors.Ink else CivicColors.Ink3,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(if (selected) CivicColors.Accent else CivicColors.Hairline)
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = fuelBlend(activeId).label,
        color = CivicColors.Ink4,
        fontSize = 11.5.sp,
    )
}

// ── Oxygen sensors ──────────────────────────────────────────────────────────────

/**
 * The front sensor is not the same device on every car, so this cannot assume a voltage.
 *
 * A narrowband answers PID 14 with a voltage that swings across 0.45 V; a wide-range sensor
 * answers PID 34 with lambda and a current, and does not swing at all. This Civic has the
 * latter, and the row used to print a fixed 0.45 V - the seeded default - directly above a
 * genuine post-catalyst reading, with nothing to tell them apart.
 */
@Composable
private fun OxygenSection(metrics: LiveMetrics) {
    val preCatLambda = metrics.o2Sensor1Lambda
    val preCatVolts = metrics.o2Sensor1Voltage

    SectionHeading("Oxygen sensors", null)

    when {
        // Voltage wins where both arrive, matching the order the poll loop prefers, so two
        // pre-catalyst rows can never appear at once.
        preCatVolts != null -> VoltageSensor("Pre-catalyst", preCatVolts)

        preCatLambda != null -> {
            ValueRow(
                label = "Pre-catalyst",
                value = "%.3f λ".format(preCatLambda),
                note = buildString {
                    append(
                        when {
                            preCatLambda < 0.98 -> "Rich"
                            preCatLambda > 1.02 -> "Lean"
                            else -> "At balance"
                        }
                    )
                    metrics.o2Sensor1CurrentMa?.let { append(" · %.2f mA".format(it)) }
                },
            )
            Spacer(Modifier.height(8.dp))
            // Scaled 0.8 - 1.2 lambda with the hairline at stoichiometry. A wideband holds
            // far tighter than a narrowband swings, so a 0 - 1.275 V style scale would draw
            // every reading as the same bar.
            Meter(
                fraction = (((preCatLambda - 0.8) / 0.4).coerceIn(0.0, 1.0)).toFloat(),
                markerFraction = 0.5f,
                color = CivicColors.Ink,
            )
            Spacer(Modifier.height(14.dp))
        }

        else -> {
            ValueRow(
                label = "Pre-catalyst",
                value = "—",
                note = "not reported by this car",
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    VoltageSensor("Post-catalyst", metrics.o2Sensor2Voltage)

    Text(
        text = if (preCatLambda != null) {
            "A wide-range front sensor reports lambda directly and holds close to 1.000 in " +
                "closed loop rather than swinging, with its current near zero. The " +
                "post-catalyst sensor should stay comparatively steady; one that starts " +
                "swinging actively is the live signature behind code P0420."
        } else {
            "A healthy pre-catalyst sensor swings actively across the 0.45 V line while the " +
                "post-catalyst one stays comparatively steady. A post-catalyst trace that " +
                "starts mirroring the pre-catalyst swing is the live signature behind code " +
                "P0420."
        },
        color = CivicColors.Ink3,
        fontSize = 12.5.sp,
    )
}

@Composable
private fun VoltageSensor(label: String, volts: Double) {
    ValueRow(
        label = label,
        value = "%.2f V".format(volts),
        note = when {
            volts >= 0.55 -> "Rich"
            volts <= 0.35 -> "Lean"
            else -> "Switching"
        },
    )
    Spacer(Modifier.height(8.dp))
    // Scaled 0 - 1.0 V: a narrowband only uses roughly 0.1 - 0.9 V of the PID's 1.275 V full
    // scale, so scaling to full scale would flatten the trace. The marker is the 0.45 V
    // stoichiometric switch point.
    Meter(
        fraction = volts.coerceIn(0.0, 1.0).toFloat(),
        markerFraction = 0.45f,
        color = CivicColors.Ink,
    )
    Spacer(Modifier.height(14.dp))
}

// ── Pieces ──────────────────────────────────────────────────────────────────────

/** A bar with a hairline where the meaningful value sits, so "rich" is read off a scale. */
@Composable
private fun Meter(fraction: Float, markerFraction: Float, color: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        drawRect(color = CivicColors.GaugeTrack, size = size)
        drawRect(
            color = color,
            size = Size(size.width * fraction.coerceIn(0f, 1f), size.height),
        )
        val x = size.width * markerFraction.coerceIn(0f, 1f)
        drawRect(
            color = CivicColors.GaugeTick,
            topLeft = Offset(x, 0f),
            size = Size(1.dp.toPx(), size.height),
        )
    }
}

@Composable
private fun SectionHeading(title: String, aside: String?) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, color = CivicColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        aside?.let { Text(it, color = CivicColors.Ink3, fontSize = 12.sp) }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ValueRow(label: String, value: String, note: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, color = CivicColors.Ink2, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = if (value == "—") CivicColors.Ink4 else CivicColors.Ink,
                fontSize = 14.sp,
            )
            note?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, color = CivicColors.Ink3, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
    )
}

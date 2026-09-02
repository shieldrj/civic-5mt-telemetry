package com.shieldrj.civic5mt.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.COSTCO_STATIONS
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.FUEL_BLENDS
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.GasPriceSnapshot
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.OUNCES_PER_US_GALLON
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.core.fuelBlend
import com.shieldrj.civic5mt.core.isClosedLoop
import com.shieldrj.civic5mt.data.GasPriceRepository
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.saveFuelBlend
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    androidx.activity.compose.BackHandler(onBack = onBack)

    val context = LocalContext.current
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val blendId by TelemetryState.fuelBlend.collectAsStateWithLifecycle()
    val blend = fuelBlend(blendId)
    val gasPrices by GasPriceRepository.snapshot.collectAsStateWithLifecycle()
    val gasRefreshing by GasPriceRepository.refreshing.collectAsStateWithLifecycle()
    val gasFetchFailed by GasPriceRepository.lastAttemptFailed.collectAsStateWithLifecycle()

    // Asked once per opening of the tab, and only when what is already held has aged out.
    // This tab gets opened at a pump - which is when the answer is wanted, and also where the
    // phone may have no signal - so the fetch updates what is drawn rather than gating it.
    LaunchedEffect(Unit) { GasPriceRepository.refreshIfStale(context) }

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

        CostcoSection(
            snapshot = gasPrices,
            refreshing = gasRefreshing,
            fetchFailed = gasFetchFailed,
            onRefresh = { GasPriceRepository.refresh(context) },
        )
        Spacer(Modifier.height(24.dp))

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
            FillLogSection(context)
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
                // "under" rather than a bare numeral once the sender has bottomed out. It is
                // the loudest number on the screen and it stops counting down there, so a
                // reader has to be told it is a ceiling. See TankState.belowSenderZero.
                if (metrics.tankBelowSenderZero && metrics.fuelRangeMiles != null) {
                    Text(
                        text = "under",
                        color = CivicColors.Ink3,
                        fontSize = 13.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(5.dp))
                }
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
                    ?.let {
                        if (metrics.tankBelowSenderZero) {
                            "under %.1f gal left".format(it)
                        } else {
                            "%.1f gal left".format(it)
                        }
                    }
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
        // What is in the tank, and what the car thinks is in the tank. They are different
        // numbers and both are worth showing here: the first is the honest one and is what
        // the overlay carries, the second is what the dashboard gauge is doing - which is
        // the thing being corrected, so hiding it would make the correction unreadable.
        // Under the sender's zero, neither figure is a reading any more - both sit still at
        // the reserve while the fuel goes on down - so both are printed as bounds. See
        // TankState.belowSenderZero for why nothing better is available down there.
        val bounded = metrics.tankBelowSenderZero

        ValueRow(
            label = "Fuel left",
            value = metrics.fuelPercentRemaining
                ?.let { if (bounded) "under %.0f%%".format(it) else "%.0f%%".format(it) }
                ?: "—",
            note = metrics.fuelLevelPercent
                ?.let { "the sender reads %.0f%%".format(it) }
                ?: "not reported by this car",
        )
        ValueRow(
            label = "Range",
            value = metrics.fuelRangeMiles
                ?.let { if (bounded) "under $it mi" else "$it mi" }
                ?: "—",
            note = when {
                metrics.fuelRangeMiles == null -> null
                bounded -> "the gauge is on E"
                else -> "at this tank's economy"
            },
        )
        // The split between fuel the sender can see and fuel underneath its zero.
        //
        // This row is the answer to "why does the dash say fifty-four when this says a hundred
        // and thirty". It is not a disagreement about fuel: Honda's figure stops at the
        // sender's zero on purpose and this one counts the reserve underneath it. Printing one
        // number made that look like an error rather than a different question.
        val reserveMiles = metrics.fuelRangeReserveMiles
        if (reserveMiles != null && reserveMiles > 0) {
            ValueRow(
                label = "Before the gauge reads E",
                value = "${metrics.fuelRangeToSenderZeroMiles} mi",
                note = "then $reserveMiles mi of reserve the sender cannot see",
            )
        }
        if (metrics.rangeMpgUsed != null) {
            ValueRow(
                label = "Range is built on",
                value = "%.1f mpg".format(metrics.rangeMpgUsed),
                note = when {
                    metrics.verifiedMpg != null -> "measured against %.1f gal of receipts"
                        .format(metrics.verifiedGallons)
                    metrics.lifetimeMiles >= 20 -> "this car's lifetime average"
                    else -> "the EPA rating, until this car has measured its own"
                },
            )
        }
        // What the pump receipts have taught, and how far apart they are. The spread is the
        // honest width of every figure above it: a range of two hundred miles from fills
        // scattered by three percent is two hundred give or take six, and a driver asking for
        // the last mile deserves to be told which mile the measurement can actually see.
        ValueRow(
            label = "Sensors checked",
            value = when {
                metrics.calibrationFillCount == 0 -> "not yet"
                else -> "%d fill%s".format(
                    metrics.calibrationFillCount,
                    if (metrics.calibrationFillCount == 1) "" else "s",
                )
            },
            note = when {
                metrics.calibrationFillCount == 0 ->
                    "log a fill-up below and the pump becomes the reference"
                else -> buildString {
                    val off = (metrics.fuelCorrectionFactor - 1.0) * 100
                    append("fuel %+.1f%%".format(off))
                    if (metrics.distanceCorrectionFactor != 1.0) {
                        append(", distance %+.1f%%".format((metrics.distanceCorrectionFactor - 1.0) * 100))
                    }
                    metrics.calibrationSpreadPercent?.let { append(", spread ±%.1f%%".format(it)) }
                }
            },
        )
        // Whether the gallons-per-percent figure behind all of this was measured on this car
        // or is still Honda's tank capacity divided by a hundred. It is the difference
        // between a percentage that describes this sender and one that is the dashboard
        // gauge plus a constant, and until now it was known only inside the app.
        ValueRow(
            label = "Tank measured",
            value = if (metrics.tankCalibrated) "yes" else "not yet",
            note = if (metrics.tankCalibrated) {
                "from fuel this car burned"
            } else {
                "using the factory %.1f gal".format(CivicSpecs.FUEL_TANK_CAPACITY_GALLONS)
            },
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

// ── What Costco is charging ─────────────────────────────────────────────────────

/**
 * The three warehouses, cheapest first.
 *
 * Sorted by price rather than held in a fixed order, because the question being asked at a
 * quarter tank is "which one", not "what is San Dimas". The cheapest regular is drawn in the
 * accent colour and the gap to the dearest is spelled out as a tankful: three cents a gallon
 * is not worth a detour and thirty is, and that is hard to see in three numbers that all end
 * in nine.
 *
 * Premium is shown small beside each. The R18Z1 is a regular-fuel engine, so premium here is
 * information rather than a choice.
 *
 * Prices come from Costco's own price service, cached on this phone. Nothing on this screen
 * needs the car: it is drawn whether or not anything is connected, because deciding where to
 * fill up happens before the drive as often as during it.
 */
@Composable
private fun CostcoSection(
    snapshot: GasPriceSnapshot,
    refreshing: Boolean,
    fetchFailed: Boolean,
    onRefresh: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()

    SectionHeading(
        title = "Costco gas",
        aside = when {
            refreshing -> "checking..."
            snapshot.isEmpty -> null
            else -> priceAge(snapshot.ageMillis(now))
        },
    )

    if (snapshot.isEmpty) {
        Text(
            text = if (fetchFailed) {
                "Could not reach Costco. Tap to try again."
            } else {
                "No prices yet. Tap to fetch them."
            },
            color = CivicColors.Ink3,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { scope.launch { onRefresh() } }
                .padding(vertical = 10.dp),
        )
        return
    }

    // A station whose pumps stopped reporting sorts to the end rather than disappearing: a
    // missing row reads as a station that closed, and it has not.
    val rows = COSTCO_STATIONS.sortedBy {
        snapshot.prices[it.warehouseId]?.regular ?: Double.MAX_VALUE
    }
    val quoted = rows.mapNotNull { snapshot.prices[it.warehouseId]?.regular }
    val cheapest = quoted.minOrNull()
    val spread = if (quoted.size > 1) quoted.max() - quoted.min() else 0.0

    rows.forEach { station ->
        val price = snapshot.prices[station.warehouseId]
        val regular = price?.regular
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(station.name, color = CivicColors.Ink2, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                price?.premium?.let {
                    Text("prem %.2f".format(it), color = CivicColors.Ink4, fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    text = regular?.let { "$%.3f".format(it) } ?: "-",
                    // The cheapest pump is the only thing this section has an opinion about.
                    color = when {
                        regular == null -> CivicColors.Ink4
                        regular == cheapest -> CivicColors.Accent
                        else -> CivicColors.Ink
                    },
                    fontSize = 17.sp,
                )
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        text = buildString {
            if (spread >= 0.01) {
                // A tankful is the unit the difference is actually felt in.
                append("%.0f cents a gallon between them".format(spread * 100))
                append(", $%.2f on a full tank. ".format(spread * CivicSpecs.FUEL_TANK_CAPACITY_GALLONS))
            } else if (quoted.size > 1) {
                append("All within a cent of each other. ")
            }
            if (fetchFailed) append("Last check failed, so these may have moved. ")
            append("Tap to refresh.")
        },
        color = CivicColors.Ink3,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { scope.launch { onRefresh() } }
            .padding(vertical = 6.dp),
    )
}

/** How old a price is, said the way a person would say it. */
private fun priceAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000L
    val hours = minutes / 60L
    return when {
        minutes < 2L -> "just now"
        minutes < 60L -> minutes.toString() + " min ago"
        minutes < 120L -> "an hour ago"
        hours < 24L -> hours.toString() + " hours ago"
        hours < 48L -> "yesterday"
        else -> (hours / 24L).toString() + " days ago"
    }
}

/**
 * Logging a fill-up, which is the only measurement in this app that comes from outside it.
 *
 * Everything else on this screen is the car talking to itself: a mass airflow reading divided
 * by an assumed density, a road speed integrated over time, a float in a tank. All of it is
 * good and none of it was ever checked. The two numbers asked for here - what the pump charged
 * for, and the odometer - are the check, and they are the reason the range figure can be
 * trusted to a few miles rather than to a few tens of miles.
 *
 * The "to the click" question is not a detail and is deliberately the one thing that cannot be
 * skipped. The whole method rests on the tank starting and finishing at the same level: fill
 * to the automatic shutoff twice and the gallons between them are the gallons burned, exactly.
 * Stop early and the receipt measures nothing except where the nozzle happened to stop, so a
 * part fill is logged and honestly refused rather than quietly averaged in.
 */
@Composable
private fun FillLogSection(context: android.content.Context) {
    var gallons by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }
    var toShutoff by remember { mutableStateOf(true) }

    val calibration by TelemetryState.calibration.collectAsStateWithLifecycle()
    val pumpGallons = gallons.trim().toDoubleOrNull()
    val odometerMiles = odometer.trim().toDoubleOrNull()

    SectionHeading("Just filled up?", null)
    Text(
        text = "The pump receipt is the reference every other fuel figure here is measured " +
            "against. Two fills to the automatic shutoff are what it takes: whatever the pump " +
            "put in the second time is what the engine burned in between, which is the one " +
            "fuel measurement in this app that no sensor guessed at.",
        color = CivicColors.Ink3,
        fontSize = 12.5.sp,
    )
    Spacer(Modifier.height(14.dp))

    EntryField(
        label = "GALLONS ON THE PUMP",
        value = gallons,
        onChange = { gallons = it },
        hint = "11.42",
    )
    Spacer(Modifier.height(12.dp))
    EntryField(
        label = "ODOMETER (OPTIONAL)",
        value = odometer,
        onChange = { odometer = it },
        // Optional and worth explaining, because it corrects a different error from the
        // gallons. The road-speed PID arrives in whole km/h and usually reads a little fast,
        // so the miles this app counts are not quite the miles the car counts. Two odometer
        // readings a tank apart measure that, and nothing else here can.
        hint = "142380",
    )
    Spacer(Modifier.height(12.dp))

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { toShutoff = !toShutoff }
            .padding(vertical = 10.dp),
    ) {
        Box(
            Modifier
                .width(18.dp)
                .height(18.dp)
                .background(
                    if (toShutoff) CivicColors.Accent else Color.Transparent,
                    RoundedCornerShape(3.dp),
                )
                .border(
                    1.dp,
                    if (toShutoff) CivicColors.Accent else CivicColors.Ink4,
                    RoundedCornerShape(3.dp),
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Filled to the automatic shutoff", color = CivicColors.Ink, fontSize = 14.sp)
            Text(
                text = if (toShutoff) {
                    "This one can be measured."
                } else {
                    "A part fill. It still starts a new tank, and the next fill to the click " +
                        "gets measured from it."
                },
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
    }

    Spacer(Modifier.height(6.dp))
    val ready = pumpGallons != null && pumpGallons > 0
    Text(
        text = if (ready) "Log this fill" else "Enter the gallons to log this fill",
        color = if (ready) CivicColors.Accent else CivicColors.Ink4,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (ready) {
                    Modifier.clickable {
                        TelemetryService.recordFill(
                            context = context,
                            pumpGallons = pumpGallons,
                            filledToShutoff = toShutoff,
                            odometerMiles = odometerMiles,
                        )
                        gallons = ""
                        odometer = ""
                    }
                } else {
                    Modifier
                }
            )
            .padding(vertical = 12.dp),
    )

    // The receipt-free path, kept because it is still the right answer sometimes: a fill
    // somebody else paid for, or one the app missed while the adapter was elsewhere. It
    // restarts the tank without claiming to have measured anything.
    Text(
        text = "Start a new tank without a receipt",
        color = CivicColors.Ink2,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { TelemetryService.markFilled(context) }
            .padding(vertical = 10.dp),
    )

    // What the tap did. The work happens in the service, which reported it to the dashboard's
    // status line and nowhere else, so from here the button looked dead whether it had started
    // a tank or refused to. A refusal is the more important of the two to be able to see: it
    // means the car is not reporting a level, which is a thing to go and fix rather than a
    // thing to tap again.
    val feedback by TelemetryState.actionFeedback.collectAsStateWithLifecycle()
    feedback?.let { result ->
        LaunchedEffect(result.sequence) {
            delay(6_000)
            TelemetryState.clearActionFeedback(result.sequence)
        }
        Text(
            text = result.message,
            color = if (result.worked) CivicColors.Good else CivicColors.Warn,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
    }

    if (calibration.samples.isNotEmpty()) {
        Text(
            text = "Clear the ${calibration.samples.size} logged fill-ups",
            color = CivicColors.Ink3,
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { TelemetryService.resetFuelCalibration(context) }
                .padding(vertical = 10.dp),
        )
        Text(
            text = "Only after a new MAF sensor or a tyre size change - either makes every " +
                "stored fill a measurement of a car that no longer exists.",
            color = CivicColors.Ink4,
            fontSize = 11.5.sp,
        )
    }
}

/** A plain number entry, hand-rolled to match the rest of the screen. */
@Composable
private fun EntryField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
) {
    Label(label)
    Spacer(Modifier.height(5.dp))
    Box(
        Modifier
            .fillMaxWidth()
            .background(CivicColors.Hairline, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) {
            Text(hint, color = CivicColors.Ink4, fontSize = 16.sp)
        }
        BasicTextField(
            value = value,
            // Filtered on the way in rather than validated on the way out. A stray character
            // in a gallons figure is not a thing to explain to someone standing at a pump.
            onValueChange = { entered ->
                onChange(entered.filter { it.isDigit() || it == '.' }.take(9))
            },
            singleLine = true,
            textStyle = TextStyle(color = CivicColors.Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(CivicColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
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

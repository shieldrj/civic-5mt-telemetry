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
import androidx.compose.ui.draw.clip
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
import com.shieldrj.civic5mt.core.FillRecord
import com.shieldrj.civic5mt.core.FuelBlendId
import com.shieldrj.civic5mt.core.FuelCalibrationState
import com.shieldrj.civic5mt.core.GasPriceSnapshot
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.OUNCES_PER_US_GALLON
import com.shieldrj.civic5mt.core.TankRules
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.core.fuelBlend
import com.shieldrj.civic5mt.core.rangeMiles
import com.shieldrj.civic5mt.core.splitRange
import com.shieldrj.civic5mt.data.GasPriceRepository
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.saveFuelBlend
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fuel economy, range estimation, pump receipts, and combustion telemetry.
 *
 * Structured into clean card containers with generous spacing so lines and explanatory
 * notes never crowd or collide on phone screens.
 */
@Composable
fun FuelScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val blendId by TelemetryState.fuelBlend.collectAsStateWithLifecycle()
    val blend = fuelBlend(blendId)
    val calibration by TelemetryState.calibration.collectAsStateWithLifecycle()
    val gasPrices by GasPriceRepository.snapshot.collectAsStateWithLifecycle()
    val gasRefreshing by GasPriceRepository.refreshing.collectAsStateWithLifecycle()
    val gasFetchFailed by GasPriceRepository.lastAttemptFailed.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { GasPriceRepository.refreshIfStale(context) }

    val live = connection == ConnectionStatus.CONNECTED ||
        connection == ConnectionStatus.SIMULATING

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        ReceiptsSection(context, calibration)
        Spacer(Modifier.height(16.dp))

        TankAndRangeCard(metrics = metrics, live = live)
        Spacer(Modifier.height(16.dp))

        if (live) {
            LiveCombustionCard(metrics = metrics, trip = trip, stoichAfr = blend.stoichAfr)
            Spacer(Modifier.height(16.dp))
        }

        CostcoSection(
            snapshot = gasPrices,
            refreshing = gasRefreshing,
            fetchFailed = gasFetchFailed,
            onRefresh = { GasPriceRepository.refresh(context) },
        )
        Spacer(Modifier.height(16.dp))

        FuelBlendCard(
            activeId = blendId,
            onSelect = { id ->
                TelemetryState.setFuelBlend(id)
                saveFuelBlend(context, id)
            },
        )

        if (live) {
            Spacer(Modifier.height(16.dp))
            OxygenSensorsCard(metrics)
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ── 1. Receipts & History ────────────────────────────────────────────────────────

@Composable
private fun ReceiptsSection(
    context: android.content.Context,
    calibration: FuelCalibrationState,
) {
    val pending = calibration.pendingReceipt(System.currentTimeMillis())
    val last = calibration.fills.lastOrNull()
    var handLog by remember { mutableStateOf(false) }
    var amending by remember { mutableStateOf(false) }

    when {
        pending != null -> {
            ReceiptCard(
                title = "Add your fill-up receipt",
                subtitle = buildString {
                    append("Filled up ").append(fillTime(pending.detectedAtMillis))
                    pending.levelAfter?.let { append(" · gauge rose to %.0f%%".format(it)) }
                },
                context = context,
                amendLast = false,
                onDone = {},
                onSkip = { TelemetryService.skipReceipt(context) },
            )
        }

        amending && last != null -> {
            ReceiptCard(
                title = "Correct the last receipt",
                subtitle = "Filled up " + fillTime(last.detectedAtMillis),
                context = context,
                amendLast = true,
                onDone = { amending = false },
                onSkip = null,
            )
        }

        handLog -> {
            ReceiptCard(
                title = "Log a fill-up",
                subtitle = "Only for a fill you just completed. This starts a new tank measurement now.",
                context = context,
                amendLast = false,
                onDone = { handLog = false },
                onSkip = null,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No receipt? Start a new tank without one",
                color = CivicColors.Ink3,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        TelemetryService.markFilled(context)
                        handLog = false
                    }
                    .padding(vertical = 8.dp),
            )
        }

        else -> {
            PanelCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CardHeader("FILL-UPS & RECEIPTS")
                    Text(
                        text = "Log by hand",
                        color = CivicColors.Accent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clickable { handLog = true }
                            .padding(vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (last != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Last fill-up",
                                color = CivicColors.Ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = fillTime(last.detectedAtMillis),
                                color = CivicColors.Ink3,
                                fontSize = 12.sp,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = last.pumpGallons?.let { "%.2f gal".format(it) } ?: "no receipt",
                                color = CivicColors.Ink,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (last.pumpGallons != null) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Change",
                                    color = CivicColors.Accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier
                                        .clickable { amending = true }
                                        .padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "After your next fill-up, a card appears here when you start the car. Enter the gallons from your receipt to calibrate the fuel gauge.",
                        color = CivicColors.Ink3,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }

                if (calibration.fills.size > 1) {
                    Spacer(Modifier.height(14.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
                    Spacer(Modifier.height(12.dp))

                    Text(
                        text = "RECENT FILL-UPS",
                        color = CivicColors.Ink3,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.4.sp,
                    )
                    Spacer(Modifier.height(6.dp))

                    calibration.fills.takeLast(5).asReversed().forEachIndexed { idx, fill ->
                        if (idx > 0) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
                        }
                        FillHistoryItem(fill, calibration.tankSpanSummary(fill))
                    }
                }
            }
        }
    }

    val feedback by TelemetryState.actionFeedback.collectAsStateWithLifecycle()
    feedback?.let { result ->
        LaunchedEffect(result.sequence) {
            delay(12_000)
            TelemetryState.clearActionFeedback(result.sequence)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = result.message,
            color = if (result.worked) CivicColors.Good else CivicColors.Warn,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun FillHistoryItem(fill: FillRecord, spanSummary: Pair<Double, Double>?) {
    val levelJump = if (fill.levelBefore != null && fill.levelAfter != null) {
        "%.0f%% → %.0f%%".format(fill.levelBefore, fill.levelAfter)
    } else {
        null
    }
    val tankSpan = spanSummary?.let { (miles, mpg) ->
        "%.0f mi · %.1f mpg".format(miles, mpg)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = fillDate(fill.detectedAtMillis),
                    color = CivicColors.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                levelJump?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        color = CivicColors.Ink3,
                        fontSize = 12.sp,
                    )
                }
            }
            Text(
                text = fill.pumpGallons?.let { "%.2f gal".format(it) } ?: "no receipt",
                color = CivicColors.Ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (tankSpan != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = tankSpan,
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
    }
}

// ── 2. This Tank & Range ────────────────────────────────────────────────────────

@Composable
private fun TankAndRangeCard(
    metrics: LiveMetrics,
    live: Boolean,
) {
    val calibration by TelemetryState.calibration.collectAsStateWithLifecycle()
    val savedTank by TelemetryState.tank.collectAsStateWithLifecycle()
    val lifetime by TelemetryState.lifetime.collectAsStateWithLifecycle()

    val hasSavedTank = savedTank.fillTimestamp != 0L
    if (!live && !hasSavedTank) return

    val bounded = if (live) metrics.tankBelowSenderZero else savedTank.belowSenderZero
    val fuelLeftPercent = if (live) metrics.fuelPercentRemaining else savedTank.fuelPercentRemaining
    val senderPercent = if (live) metrics.fuelLevelPercent else savedTank.smoothedLevelPercent

    val verifiedMpg = calibration.economyMpg
    val parkedMpgUsed = TankRules.mpgForRange(
        tankMpg = savedTank.tankMpg,
        tankGallonsUsed = savedTank.gallonsUsedSinceFill,
        lifetimeMpg = lifetime.lifetimeMpg,
        lifetimeMiles = lifetime.totalMiles,
        verifiedMpg = verifiedMpg,
    )
    val parkedRange = if (hasSavedTank) {
        val total = rangeMiles(savedTank, lifetime.lifetimeMpg, lifetime.totalMiles, verifiedMpg)
        splitRange(savedTank, total, parkedMpgUsed)
    } else {
        null
    }

    val displayMpg = if (live) metrics.tankMpg else savedTank.tankMpg
    val displayMiles = if (live) metrics.tankMilesSinceFill else savedTank.milesSinceFill
    val rangeTotal = if (live) metrics.fuelRangeMiles else parkedRange?.totalMiles
    val rangeToZero = if (live) metrics.fuelRangeToSenderZeroMiles else parkedRange?.toSenderZeroMiles
    val reserveMiles = if (live) metrics.fuelRangeReserveMiles else parkedRange?.reserveMiles
    val rangeMpgUsed = if (live) metrics.rangeMpgUsed else parkedRange?.mpgUsed
    val gallonsLeft = if (live) metrics.tankGallonsRemaining else savedTank.gallonsRemaining

    PanelCard {
        CardHeader("THIS TANK & RANGE")
        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "TANK ECONOMY",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = displayMpg?.let { "%.1f".format(it) } ?: "—",
                        color = if (displayMpg == null) CivicColors.Ink3 else CivicColors.Ink,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "mpg",
                        color = CivicColors.Ink3,
                        fontSize = 13.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = when {
                        displayMiles != null -> "%.1f mi since fill".format(displayMiles)
                        senderPercent == null -> "no tank level"
                        else -> "calibrating…"
                    },
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                )
            }

            Column(Modifier.weight(1f)) {
                Text(
                    text = "ESTIMATED RANGE",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    if (bounded && rangeTotal != null) {
                        Text(
                            text = "about",
                            color = CivicColors.Ink3,
                            fontSize = 13.sp,
                            modifier = Modifier.alignByBaseline(),
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        text = rangeTotal?.toString() ?: "—",
                        color = CivicColors.Ink,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "mi",
                        color = CivicColors.Ink3,
                        fontSize = 13.sp,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = gallonsLeft?.let {
                        if (bounded) "about %.1f gal left".format(it)
                        else "%.1f gal remaining".format(it)
                    } ?: "",
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
        Spacer(Modifier.height(6.dp))

        ValueRow(
            label = "Fuel in tank",
            value = fuelLeftPercent?.let { if (bounded) "about %.0f%%".format(it) else "%.0f%%".format(it) } ?: "—",
            note = senderPercent?.let {
                if (live) "sender reports %.0f%% · nominal 13.2 gal capacity".format(it)
                else "last recorded · sender was at %.0f%%".format(it)
            } ?: "not reported by this car",
        )

        if (reserveMiles != null && reserveMiles > 0 && rangeToZero != null) {
            ValueRow(
                label = "Gauge hits E in",
                value = "$rangeToZero mi",
                note = "then $reserveMiles mi of reserve below E",
            )
        }

        if (rangeMpgUsed != null) {
            val effVerifiedMpg = if (live) metrics.verifiedMpg else calibration.economyMpg
            val effVerifiedGal = if (live) metrics.verifiedGallons else calibration.receiptGallons
            val effLifetimeMiles = if (live) metrics.lifetimeMiles else lifetime.totalMiles
            ValueRow(
                label = "Range calculated from",
                value = "%.1f mpg".format(rangeMpgUsed),
                note = when {
                    effVerifiedMpg != null -> "checked against %.1f gal of pump receipts".format(effVerifiedGal)
                    effLifetimeMiles >= 20 -> "this car's lifetime average"
                    else -> "the EPA rating, until this car has measured its own"
                },
            )
        }

        val receipts = if (live) metrics.calibrationFillCount else calibration.gaugeReceiptCount
        val spread = if (live) metrics.calibrationSpreadPercent else calibration.spreadPercent
        ValueRow(
            label = "Gauge calibration",
            value = when (receipts) {
                0 -> "not yet calibrated"
                1 -> "calibrated by 1 receipt"
                else -> "calibrated by $receipts receipts"
            },
            note = when {
                receipts == 0 -> "add a receipt after your next fill to calibrate"
                spread != null -> "receipts agree within %.1f%%".format(spread)
                else -> "gallons pumped matched to gauge float rise"
            },
        )

        val fuelFactor = if (live) metrics.fuelCorrectionFactor else calibration.fuelCorrectionFactor
        val distFactor = if (live) metrics.distanceCorrectionFactor else calibration.distanceCorrectionFactor
        if (fuelFactor != 1.0 || distFactor != 1.0) {
            ValueRow(
                label = "Sensor corrections",
                value = buildString {
                    append("fuel %+.1f%%".format((fuelFactor - 1.0) * 100))
                    if (distFactor != 1.0) {
                        append(", miles %+.1f%%".format((distFactor - 1.0) * 100))
                    }
                },
            )
        }
    }
}

// ── 3. Live Combustion (Connected only) ─────────────────────────────────────────

@Composable
private fun LiveCombustionCard(
    metrics: LiveMetrics,
    trip: TripAnalytics,
    stoichAfr: Double,
) {
    val isDfco = metrics.isDfcoActive
    val afr = metrics.airFuelRatio
    val lambda = if (stoichAfr > 0) afr / stoichAfr else 1.0
    val offStoich = lambda < 0.97 || lambda > 1.03
    val afrStatus = when {
        lambda < 0.97 -> "Rich"
        lambda > 1.03 -> "Lean"
        else -> "Stoichiometric"
    }

    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardHeader("LIVE COMBUSTION")
            Text(
                text = if (isDfco) "Fuel cut (DFCO)" else "Closed loop",
                color = if (isDfco) CivicColors.Good else CivicColors.Ink3,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(14.dp))

        ValueRow(
            label = "Air to fuel",
            value = "%.2f:1".format(afr),
            note = metrics.equivalenceRatio
                ?.let { "$afrStatus · λ %.3f".format(it) }
                ?: "$afrStatus · from fuel trims",
            emphasizeValue = true,
        )
        Spacer(Modifier.height(4.dp))
        Meter(
            fraction = (((afr - 10.0) / 10.0).coerceIn(0.0, 1.0)).toFloat(),
            markerFraction = (((stoichAfr - 10.0) / 10.0).coerceIn(0.0, 1.0)).toFloat(),
            color = if (offStoich) CivicColors.Warn else CivicColors.Ink,
        )
        Spacer(Modifier.height(10.dp))

        ValueRow(
            label = "Burn rate",
            value = "%.2f gal/hr".format(metrics.fuelFlowGalPerHour),
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
            note = metrics.fuelSystemStatusLabel,
        )

        val idleOunces = trip.idleFuelGallons * OUNCES_PER_US_GALLON
        if (trip.idleTimeSec > 5) {
            val totalSec = trip.idleTimeSec.roundToInt()
            ValueRow(
                label = "Burned at idle",
                value = "%.1f fl oz".format(idleOunces),
                note = "$%.2f spent (${totalSec / 60}m ${totalSec % 60}s stopped this trip)".format(trip.idleCostDollars),
            )
        }
    }
}

// ── 4. Costco Gas Prices ────────────────────────────────────────────────────────

@Composable
private fun CostcoSection(
    snapshot: GasPriceSnapshot,
    refreshing: Boolean,
    fetchFailed: Boolean,
    onRefresh: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val now = System.currentTimeMillis()

    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardHeader("COSTCO GAS")
            val asideText = when {
                refreshing -> "Checking…"
                snapshot.isEmpty -> null
                else -> priceAge(snapshot.ageMillis(now))
            }
            asideText?.let {
                Text(it, color = CivicColors.Ink3, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

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
                    .padding(vertical = 6.dp),
            )
        } else {
            val rows = COSTCO_STATIONS.sortedBy {
                snapshot.prices[it.warehouseId]?.regular ?: Double.MAX_VALUE
            }
            val quoted = rows.mapNotNull { snapshot.prices[it.warehouseId]?.regular }
            val cheapest = quoted.minOrNull()
            val spread = if (quoted.size > 1) quoted.max() - quoted.min() else 0.0

            rows.forEachIndexed { idx, station ->
                if (idx > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(CivicColors.Hairline),
                    )
                }
                val price = snapshot.prices[station.warehouseId]
                val regular = price?.regular
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(station.name, color = CivicColors.Ink, fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        price?.premium?.let {
                            Text(
                                "prem $%.2f".format(it),
                                color = CivicColors.Ink4,
                                fontSize = 12.sp,
                            )
                            Spacer(Modifier.width(14.dp))
                        }
                        Text(
                            text = regular?.let { "$%.3f".format(it) } ?: "—",
                            color = when {
                                regular == null -> CivicColors.Ink4
                                regular == cheapest -> CivicColors.Accent
                                else -> CivicColors.Ink
                            },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            val summaryText = buildString {
                if (spread >= 0.01) {
                    append("%.0f¢/gal spread between warehouses ".format(spread * 100))
                    append("($%.2f on a full tank). ".format(spread * CivicSpecs.FUEL_TANK_CAPACITY_GALLONS))
                } else if (quoted.size > 1) {
                    append("All within a cent of each other. ")
                }
                if (fetchFailed) append("Last fetch failed. ")
                append("Tap to refresh.")
            }
            Text(
                text = summaryText,
                color = CivicColors.Ink3,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scope.launch { onRefresh() } }
                    .padding(top = 4.dp),
            )
        }
    }
}

// ── 5. Fuel Blend ───────────────────────────────────────────────────────────────

@Composable
private fun FuelBlendCard(
    activeId: FuelBlendId,
    onSelect: (FuelBlendId) -> Unit,
) {
    val blend = fuelBlend(activeId)

    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardHeader("FUEL BLEND")
            Text(
                text = "%.2f:1 stoich · %.0f g/L".format(blend.stoichAfr, blend.densityGramsPerLiter),
                color = CivicColors.Ink3,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            FUEL_BLENDS.keys.forEach { id ->
                val selected = id == activeId
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelect(id) }
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = id.name,
                        color = if (selected) CivicColors.Ink else CivicColors.Ink3,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (selected) CivicColors.Accent else CivicColors.Hairline),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = blend.label,
            color = CivicColors.Ink3,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

// ── 6. Oxygen Sensors (Connected only) ───────────────────────────────────────────

@Composable
private fun OxygenSensorsCard(metrics: LiveMetrics) {
    val preCatLambda = metrics.o2Sensor1Lambda
    val preCatVolts = metrics.o2Sensor1Voltage

    PanelCard {
        CardHeader("OXYGEN SENSORS")
        Spacer(Modifier.height(14.dp))

        when {
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
                Spacer(Modifier.height(4.dp))
                Meter(
                    fraction = (((preCatLambda - 0.8) / 0.4).coerceIn(0.0, 1.0)).toFloat(),
                    markerFraction = 0.5f,
                    color = CivicColors.Ink,
                )
                Spacer(Modifier.height(10.dp))
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
                "Wide-range front sensor reports lambda directly in closed loop. Post-catalyst sensor monitors catalyst efficiency (code P0420)."
            } else {
                "Pre-catalyst sensor swings across 0.45V in closed loop. Post-catalyst sensor monitors catalyst efficiency (code P0420)."
            },
            color = CivicColors.Ink3,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

// ── Shared UI Elements ──────────────────────────────────────────────────────────

@Composable
private fun CardHeader(title: String) {
    Text(
        text = title,
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun ValueRow(
    label: String,
    value: String,
    note: String? = null,
    modifier: Modifier = Modifier,
    emphasizeValue: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = CivicColors.Ink2,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = value,
                color = if (value == "—") CivicColors.Ink4 else CivicColors.Ink,
                fontSize = if (emphasizeValue) 15.sp else 14.sp,
                fontWeight = if (emphasizeValue) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                color = CivicColors.Ink3,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
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
    Spacer(Modifier.height(4.dp))
    Meter(
        fraction = volts.coerceIn(0.0, 1.0).toFloat(),
        markerFraction = 0.45f,
        color = CivicColors.Ink,
    )
    Spacer(Modifier.height(10.dp))
}

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
private fun ReceiptCard(
    title: String,
    subtitle: String,
    context: android.content.Context,
    amendLast: Boolean,
    onDone: () -> Unit,
    onSkip: (() -> Unit)?,
) {
    var gallons by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }
    var toShutoff by remember { mutableStateOf(true) }
    val pumpGallons = gallons.trim().toDoubleOrNull()
    val odometerMiles = odometer.trim().toDoubleOrNull()

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15181C))
            .border(1.dp, CivicColors.Accent, RoundedCornerShape(18.dp))
            .padding(18.dp),
    ) {
        Text(title, color = CivicColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = CivicColors.Ink3, fontSize = 13.sp)
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                EntryField(label = "GALLONS", value = gallons, onChange = { gallons = it }, hint = "10.90")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                EntryField(label = "ODOMETER NOW", value = odometer, onChange = { odometer = it }, hint = "optional")
            }
        }
        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toShutoff = !toShutoff }
                .padding(vertical = 8.dp),
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
            Spacer(Modifier.width(10.dp))
            Text("Filled until the pump clicked off", color = CivicColors.Ink2, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))

        val ready = pumpGallons != null && pumpGallons > 0
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(
                    if (ready) CivicColors.Accent else CivicColors.Hairline,
                    RoundedCornerShape(14.dp),
                )
                .then(
                    if (ready) {
                        Modifier.clickable {
                            TelemetryService.recordFill(
                                context = context,
                                pumpGallons = pumpGallons,
                                filledToShutoff = toShutoff,
                                odometerMiles = odometerMiles,
                                amendLast = amendLast,
                            )
                            gallons = ""
                            odometer = ""
                            onDone()
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ready) "Save" else "Enter the gallons",
                color = if (ready) CivicColors.Ink else CivicColors.Ink4,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        onSkip?.let { skip ->
            Text(
                text = "No receipt for this one",
                color = CivicColors.Ink3,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { skip() }
                    .padding(top = 12.dp, bottom = 2.dp),
            )
        }
    }
}

private fun fillTime(millis: Long): String =
    java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US).format(java.util.Date(millis))

private fun fillDate(millis: Long): String =
    java.text.SimpleDateFormat("MMM d", java.util.Locale.US).format(java.util.Date(millis))

private fun priceAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000L
    val hours = minutes / 60L
    return when {
        minutes < 2L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 120L -> "1 hour ago"
        hours < 24L -> "$hours hours ago"
        hours < 48L -> "yesterday"
        else -> "${hours / 24L} days ago"
    }
}

@Composable
private fun EntryField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String,
) {
    Text(
        text = label,
        color = CivicColors.Ink3,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
    )
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

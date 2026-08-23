package com.shieldrj.civic5mt.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.DtcSeverity
import com.shieldrj.civic5mt.core.MonitorState
import com.shieldrj.civic5mt.core.ScannedDtc
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState

/**
 * Fault codes, and the emissions self-tests.
 *
 * The screen leads with pending codes rather than confirmed ones, which is backwards from most
 * scan tools and deliberate. A confirmed code has already lit the dashboard - you know about
 * it. A pending code is the fault that has happened once and not yet lit anything, and it is
 * the only one on this screen you can still get ahead of.
 */
@Composable
fun CodesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val report by TelemetryState.dtcReport.collectAsStateWithLifecycle()
    val scanning by TelemetryState.scanning.collectAsStateWithLifecycle()
    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    var confirmingClear by remember { mutableStateOf(false) }

    val connected = connection == ConnectionStatus.CONNECTED

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "DIAGNOSTICS",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(18.dp))

        when {
            scanning -> Text("Scanning…", color = CivicColors.Ink2, fontSize = 14.sp)

            !connected -> Text(
                // The bench has no ECU to ask, and inventing a plausible fault would be a
                // scan tool that lies. It says so instead.
                text = "Connect to the adapter with the ignition on to scan. " +
                    "A simulated drive has no ECU to ask.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )

            report == null -> Text(
                "No scan yet.",
                color = CivicColors.Ink3,
                fontSize = 14.sp,
            )
        }

        report?.let { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = if (r.milOn) "Check engine light ON" else "No check engine light",
                    color = if (r.milOn) CivicColors.Accent else CivicColors.Ink,
                    fontSize = 16.sp,
                )
                Text(
                    text = "${r.totalDtcCount} code" + if (r.totalDtcCount == 1) "" else "s",
                    color = CivicColors.Ink2,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(20.dp))

            CodeSection("Pending", r.pendingCodes, CivicColors.Warn)
            CodeSection("Confirmed", r.confirmedCodes, CivicColors.Accent)
            CodeSection("Permanent", r.permanentCodes, CivicColors.Ink2)

            Spacer(Modifier.height(8.dp))
            Hairline()
            Spacer(Modifier.height(16.dp))

            Text(
                text = "EMISSIONS READINESS",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(10.dp))
            r.monitors.labelled().forEach { (label, state) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label.replaceFirstChar { it.uppercase() }, color = CivicColors.Ink2, fontSize = 13.sp)
                    Text(
                        text = state.label,
                        color = when (state) {
                            MonitorState.READY -> CivicColors.Ink
                            MonitorState.NOT_READY -> CivicColors.Warn
                            MonitorState.NOT_AVAILABLE -> CivicColors.Ink4
                        },
                        fontSize = 13.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))

        if (connected && !scanning) {
            ActionText(if (report == null) "Scan for codes" else "Scan again") {
                TelemetryService.scanForCodes(context)
            }

            if ((report?.totalDtcCount ?: 0) > 0) {
                if (!confirmingClear) {
                    ActionText("Clear codes") { confirmingClear = true }
                } else {
                    // Said before the button, not after. Clearing also wipes the readiness
                    // monitors, and a car with monitors reset fails an emissions test until it
                    // has driven a full drive cycle - which is days, not minutes.
                    Text(
                        text = "Clearing puts the light out and wipes the emissions self-tests " +
                            "with it. The car will fail a smog check until it has driven a full " +
                            "drive cycle. It does not fix the fault.",
                        color = CivicColors.Ink2,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    ActionText("Clear anyway") {
                        confirmingClear = false
                        TelemetryService.clearCodes(context)
                    }
                    ActionText("Cancel", CivicColors.Ink3) { confirmingClear = false }
                }
            }
        }

        ActionText("Back", CivicColors.Accent, onBack)
    }
}

@Composable
private fun CodeSection(title: String, codes: List<ScannedDtc>, accent: androidx.compose.ui.graphics.Color) {
    if (codes.isEmpty()) return

    Text(
        text = title.uppercase(),
        color = accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(8.dp))

    codes.forEach { dtc ->
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(dtc.code, color = CivicColors.Ink, fontSize = 18.sp)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  ${dtc.details.severity.name.lowercase()}",
                    color = when (dtc.details.severity) {
                        DtcSeverity.CRITICAL -> CivicColors.Accent
                        DtcSeverity.MODERATE -> CivicColors.Warn
                        else -> CivicColors.Ink3
                    },
                    fontSize = 11.sp,
                    letterSpacing = 1.2.sp,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(dtc.details.title, color = CivicColors.Ink2, fontSize = 14.sp)

            dtc.details.civicSpecificNotes?.let { note ->
                Spacer(Modifier.height(6.dp))
                // The reason this table exists rather than a generic lookup.
                Text(note, color = CivicColors.Ink3, fontSize = 13.sp)
            }

            dtc.freezeFrame?.let { f ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Stored at ${f.rpm} rpm · ${f.speedMph} mph · ${f.coolantTempF}°F · " +
                        "load ${f.calcLoad}% · trims ${f.fuelTrimSt}/${f.fuelTrimLt}%",
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ActionText(
    label: String,
    color: androidx.compose.ui.graphics.Color = CivicColors.Accent,
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
private fun Hairline() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(CivicColors.Hairline))
}

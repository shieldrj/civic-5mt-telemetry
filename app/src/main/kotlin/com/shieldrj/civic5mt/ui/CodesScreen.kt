package com.shieldrj.civic5mt.ui

import android.text.format.DateUtils
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
import com.shieldrj.civic5mt.core.HealthLevel
import com.shieldrj.civic5mt.core.MonitorState
import com.shieldrj.civic5mt.core.ScannedDtc
import com.shieldrj.civic5mt.core.dtcHealthStatus
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState

/**
 * Engine health verdict, fault codes, and emissions readiness.
 *
 * The app scans for codes automatically at the start of every drive and watches fuel trims,
 * thermostat warm-up, warm idle stability, charging voltage, oil life, and clutch slip in the
 * background. This screen states the plain-English verdict first and only expands individual
 * codes or incomplete emissions monitors when something needs attention.
 */
@Composable
fun CodesSection(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val report by TelemetryState.dtcReport.collectAsStateWithLifecycle()
    val scanning by TelemetryState.scanning.collectAsStateWithLifecycle()
    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    var confirmingClear by remember { mutableStateOf(false) }

    val connected = connection == ConnectionStatus.CONNECTED
    val live = connection == ConnectionStatus.CONNECTED || connection == ConnectionStatus.SIMULATING

    val activeIssue = when {
        live && metrics.healthStatus.level != HealthLevel.OK -> metrics.healthStatus
        else -> report?.let { dtcHealthStatus(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        when {
            activeIssue != null -> {
                val issueColor = if (activeIssue.level == HealthLevel.CRITICAL) {
                    CivicColors.Accent
                } else {
                    CivicColors.Warn
                }
                Text(
                    text = activeIssue.summary,
                    color = issueColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                activeIssue.detail?.let { detail ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        color = CivicColors.Ink2,
                        fontSize = 14.sp,
                    )
                }
            }

            report != null -> {
                Text(
                    text = "All systems healthy",
                    color = CivicColors.Good,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "No check engine light and 0 fault codes stored. Fuel mixture, " +
                        "engine temperature, warm idle, charging voltage, and clutch grip are " +
                        "watched automatically while you drive.",
                    color = CivicColors.Ink2,
                    fontSize = 14.sp,
                )
            }

            else -> {
                Text(
                    text = "Watched automatically when you drive",
                    color = CivicColors.Ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "The app scans for fault codes and checks fuel mixture, thermostat " +
                        "warm-up, warm idle, charging voltage, and clutch grip automatically " +
                        "every time the car connects. You will get a notification if a problem " +
                        "appears.",
                    color = CivicColors.Ink2,
                    fontSize = 14.sp,
                )
            }
        }

        if (scanning) {
            Spacer(Modifier.height(12.dp))
            Text("Scanning ECU…", color = CivicColors.Ink3, fontSize = 13.sp)
        } else {
            report?.let { r ->
                if (r.timestamp > 0L) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Last checked " +
                            DateUtils.getRelativeTimeSpanString(r.timestamp).toString(),
                        color = CivicColors.Ink3,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        report?.let { r ->
            if (r.totalDtcCount > 0) {
                Spacer(Modifier.height(20.dp))
                Hairline()
                Spacer(Modifier.height(16.dp))

                CodeSection("Pending", r.pendingCodes, CivicColors.Warn)
                CodeSection("Confirmed", r.confirmedCodes, CivicColors.Accent)
                CodeSection("Permanent", r.permanentCodes, CivicColors.Ink2)
            }

            val supported = r.monitors.labelled().filter { it.second != MonitorState.NOT_AVAILABLE }
            val incomplete = supported.filter { it.second == MonitorState.NOT_READY }

            if (supported.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Hairline()
                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Smog check self-tests",
                        color = CivicColors.Ink2,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (incomplete.isEmpty()) {
                            "Passed (${supported.size} of ${supported.size} ready)"
                        } else {
                            "${incomplete.size} of ${supported.size} still running"
                        },
                        color = if (incomplete.isEmpty()) CivicColors.Good else CivicColors.Warn,
                        fontSize = 14.sp,
                    )
                }

                if (incomplete.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    incomplete.forEach { (label, _) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = label.replaceFirstChar { it.uppercase() },
                                color = CivicColors.Ink3,
                                fontSize = 13.sp,
                            )
                            Text(
                                text = "Not ready yet",
                                color = CivicColors.Warn,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
        }

        if (connected && !scanning) {
            Spacer(Modifier.height(24.dp))
            Hairline()
            Spacer(Modifier.height(12.dp))

            ActionText(if (report == null) "Scan now" else "Check again now") {
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

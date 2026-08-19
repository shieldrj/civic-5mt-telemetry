package com.shieldrj.civic5mt.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.MpgDisplayState
import com.shieldrj.civic5mt.core.OutsideAirSource
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.service.ResolvedPids
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.transport.BluetoothClassicTransport
import com.shieldrj.civic5mt.transport.PairedDevice

/**
 * The shell, and enough of a screen to prove the whole chain works end to end: a Bluetooth
 * socket, the ELM327 handshake, the poll loop and the models in a service, and the figures
 * they produce on screen.
 *
 * This is not the Drive tab. It is a list of numbers rather than a gauge on purpose - what it
 * is for right now is being looked at in the car to see which readings arrived and which did
 * not, before any of it is dressed up. A gauge that is wrong is much harder to catch than a
 * row that says "—".
 *
 * It does already hold the two rules that survive into the real UI: MPG is the hero, and it
 * is only drawn as a number when it is actually an economy figure.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Draw behind the system bars, then pad the content back out of them. Without the
        // second half the header sits underneath the status bar, which is what the first
        // build on the phone did.
        enableEdgeToEdge()

        // The screen stays on while this is open. It is read at a glance while driving, and
        // a gauge that has blanked itself is worse than no gauge - you look down, see
        // nothing, and look again. It only applies while this Activity is in front; the
        // service keeps logging regardless of the screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            Civic5MTTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = CivicColors.Ground) {
                    ConnectionScreen()
                }
            }
        }
    }
}

@Composable
private fun ConnectionScreen() {
    val context = LocalContext.current

    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val status by TelemetryState.statusMessage.collectAsStateWithLifecycle()
    val resolved by TelemetryState.resolvedPids.collectAsStateWithLifecycle()

    var adapters by remember { mutableStateOf(emptyList<PairedDevice>()) }

    val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    val requestPermissions = rememberLauncher {
        adapters = BluetoothClassicTransport.pairedAdapters(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Text(
            text = "CIVIC 5MT",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(20.dp))

        // MPG is the hero, as in the web build - the one number worth reading at a glance.
        //
        // It is only ever drawn as a number while actually driving. Standing at a light the
        // figure is zero because the car is not moving, and on a closed throttle it is the
        // 99.9 cap because the injectors are off; neither is "your car is getting N mpg", so
        // neither gets drawn as one. That is what mpgDisplayState is for.
        val live = connection == ConnectionStatus.CONNECTED
        Reading(
            value = metrics.displayMpg
                .takeIf { live && metrics.mpgDisplayState == MpgDisplayState.DRIVING }
                ?.let { "%.1f".format(it) },
            unit = "MPG",
        )
        if (live) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = when (metrics.mpgDisplayState) {
                    MpgDisplayState.IDLE -> "Stationary"
                    MpgDisplayState.COASTING -> "Coasting · injectors off"
                    MpgDisplayState.DRIVING -> "Gear ${metrics.currentGear} · ${metrics.rpm.toInt()} rpm"
                },
                color = CivicColors.Ink3,
                fontSize = 13.sp,
            )
        }

        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))

        Text(status, color = CivicColors.Ink2, fontSize = 14.sp)
        Spacer(Modifier.height(16.dp))

        when (connection) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> {
                ActionRow("Disconnect") { TelemetryService.disconnect(context) }
            }
            else -> {
                ActionRow("Find paired adapters") {
                    requestPermissions.launch(permissions)
                }
                adapters.forEach { device ->
                    ActionRow("${device.name} · ${device.address}") {
                        TelemetryService.connect(context, device.address)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Hairline()
        Spacer(Modifier.height(16.dp))

        LiveReadings(metrics, trip, resolved, connection)

        // The failure messages tell you to check the adapter log, so the adapter log has to
        // be somewhere you can check. It was not, which made that sentence an instruction
        // pointing at nothing - noticed because the ignition-off message said it on the car.
        val log by TelemetryState.protocolLog.collectAsStateWithLifecycle()
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Hairline()
            Spacer(Modifier.height(16.dp))
            Text(
                text = "ADAPTER LOG",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(12.dp))
            // Newest first: the line that explains a failure is the last one written, and
            // scrolling to the bottom of a sixty-line log to find it is not a thing anyone
            // does in a car park.
            log.asReversed().forEach { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(
                        text = entry.cmd,
                        color = CivicColors.Ink3,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.width(96.dp),
                    )
                    Text(
                        text = entry.resp,
                        color = CivicColors.Ink2,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun rememberLauncher(onResult: () -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }

@Composable
private fun Reading(value: String?, unit: String) {
    // The unit sits on the numeral baseline rather than the row bottom edge, which is what
    // alignByBaseline is for. Alignment.Bottom lined it up with the descender box instead and
    // left it floating well below the digits.
    //
    // An absent reading is drawn small. At the hero size a dash is a wide rule across the
    // screen that reads as a divider or a rendering fault, not as "nothing has arrived yet".
    Row {
        Text(
            text = value ?: "—",
            color = if (value == null) CivicColors.Ink4 else CivicColors.Ink,
            fontSize = if (value == null) 40.sp else 72.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = if (value == null) 0.sp else (-2).sp,
            modifier = Modifier.alignByBaseline(),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = unit,
            color = CivicColors.Ink3,
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

@Composable
private fun LiveReadings(
    metrics: LiveMetrics,
    trip: TripAnalytics,
    resolved: ResolvedPids,
    connection: ConnectionStatus,
) {
    val live = connection == ConnectionStatus.CONNECTED

    // Nullable readings print a dash. This is the screen that used to show a fabricated
    // 22 °C outside temperature on a car that has no PID 46 to read it from.
    val rows = listOf(
        "Speed" to if (live) "${metrics.speedMph.toInt()} mph" else null,
        "Air:fuel" to if (live) "%.1f:1".format(metrics.airFuelRatio) else null,
        "Fuel flow" to if (live) "%.2f gal/hr".format(metrics.fuelFlowGalPerHour) else null,
        "Range" to if (live) "${metrics.fuelRangeMiles} mi" else null,
        "Coolant" to if (live) "${metrics.coolantTempC.toInt()} °C" else null,
        "Battery" to if (live) "%.2f V".format(metrics.batteryVoltage) else null,
        "Lambda" to metrics.equivalenceRatio?.let { "λ $it" },
        "Sensor current" to metrics.o2Sensor1CurrentMa?.let { "$it mA" },
        "Outside air" to metrics.outsideAirTempC?.let { c ->
            // 0F is intake air, which after a few minutes of idling reads engine-bay heat
            // rather than weather. It never appears under this heading unlabelled.
            val source = if (metrics.outsideAirSource == OutsideAirSource.INTAKE) " (intake)" else ""
            "${c.toInt()} °C$source"
        },
        "Trip" to if (live) "%.1f mi · %.1f mpg".format(trip.distanceMiles, trip.avgMpg) else null,
        "Lifetime" to metrics.lifetimeMiles
            .takeIf { it > 0 }
            ?.let { "%.1f mi · %.1f mpg".format(it, metrics.lifetimeMpg) },
    )

    rows.forEach { (label, value) -> ReadingRow(label, value) }

    if (resolved.lambda != null || resolved.outsideAir != null) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "PIDs in use — lambda ${hex(resolved.lambda)} · " +
                "pre-cat ${hex(resolved.preCat)} · outside air ${hex(resolved.outsideAir)}",
            color = CivicColors.Ink4,
            fontSize = 11.sp,
        )
    }
}

private fun hex(pid: Int?): String =
    pid?.toString(16)?.uppercase()?.padStart(2, '0') ?: "none"

@Composable
private fun ReadingRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = CivicColors.Ink2, fontSize = 14.sp)
        Text(
            text = value ?: "—",
            color = if (value == null) CivicColors.Ink4 else CivicColors.Ink,
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = label,
            color = CivicColors.Accent,
            fontSize = 15.sp,
            textAlign = TextAlign.Start,
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CivicColors.Hairline)
    )
}

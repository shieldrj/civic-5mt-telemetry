package com.shieldrj.civic5mt.ui

import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.shieldrj.civic5mt.data.BackupManager
import com.shieldrj.civic5mt.service.AutoStartReceiver
import com.shieldrj.civic5mt.service.loadAutoConnect
import com.shieldrj.civic5mt.service.loadBackupTreeUri
import com.shieldrj.civic5mt.service.loadCarBluetoothAddress
import com.shieldrj.civic5mt.service.loadCarBluetoothName
import com.shieldrj.civic5mt.service.loadLastBackupAt
import com.shieldrj.civic5mt.service.saveAutoConnect
import com.shieldrj.civic5mt.service.saveCarBluetooth
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.OilBarrel
import androidx.compose.material.icons.outlined.Science
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.MpgDisplayState
import com.shieldrj.civic5mt.core.OutsideAirSource
import com.shieldrj.civic5mt.core.ShiftMode
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.data.TripDatabase
import com.shieldrj.civic5mt.service.ResolvedPids
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.loadLastAdapter
import com.shieldrj.civic5mt.service.loadOverlayEnabled
import com.shieldrj.civic5mt.service.saveOverlayEnabled
import com.shieldrj.civic5mt.transport.BluetoothClassicTransport
import com.shieldrj.civic5mt.transport.PairedDevice
import com.shieldrj.civic5mt.ui.overlay.OverlayHost
import kotlin.math.roundToInt

/**
 * The screens that sit in front of whatever the connection implies.
 *
 * Lives here rather than in each screen file because it is the shape of the navigation, and
 * the navigation is one decision: exactly one of these is in front, or none of them is.
 */
enum class DetailScreen { Trips, Codes, Fuel, Oil, Clutch }

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

    /**
     * A screen requested from outside this Activity - a tap on the floating HUD, or on the
     * widget. Held as state rather than read once, because singleTask delivery can arrive
     * through [onNewIntent] while the Compose tree is already standing.
     */
    private val deepLink = androidx.compose.runtime.mutableStateOf<DetailScreen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)

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
                    ConnectionScreen(deepLink)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        when (intent?.getStringExtra(TelemetryService.EXTRA_OPEN_SCREEN)) {
            TelemetryService.SCREEN_FUEL -> deepLink.value = DetailScreen.Fuel
            TelemetryService.SCREEN_CLUTCH -> deepLink.value = DetailScreen.Clutch
        }
    }
}

@Composable
private fun ConnectionScreen(deepLink: androidx.compose.runtime.State<DetailScreen?>) {
    val context = LocalContext.current

    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val lifetime by TelemetryState.lifetime.collectAsStateWithLifecycle()
    val status by TelemetryState.statusMessage.collectAsStateWithLifecycle()
    val resolved by TelemetryState.resolvedPids.collectAsStateWithLifecycle()
    val shiftMode by TelemetryState.shiftMode.collectAsStateWithLifecycle()
    val overlayEnabled by TelemetryState.overlayEnabled.collectAsStateWithLifecycle()
    val oil by TelemetryState.oil.collectAsStateWithLifecycle()
    val clutch by TelemetryState.clutch.collectAsStateWithLifecycle()
    val lastAdapter = remember { loadLastAdapter(context) }

    // Which detail screen is in front, or null for whatever the connection implies - the
    // Drive screen while something is connected, the adapter list otherwise. Two booleans got
    // as far as two screens; four of them would allow a state where both are true.
    var detail by remember { mutableStateOf<DetailScreen?>(null) }

    // Overlay permission is granted on a Settings screen, not in a dialog, so the only way to
    // know it changed is to look again when the app comes back to the front.
    val tripCount by remember { TripDatabase.get(context).tripDao().observeRealTripCount() }
        .collectAsStateWithLifecycle(0)

    var autoConnect by remember { mutableStateOf(loadAutoConnect(context)) }
    var carBtAddress by remember { mutableStateOf(loadCarBluetoothAddress(context)) }
    var carBtName by remember { mutableStateOf(loadCarBluetoothName(context)) }
    var showAutoConnectDialog by remember { mutableStateOf(false) }

    // If no car Bluetooth device has been chosen yet, auto-detect from paired devices
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (carBtAddress == null) {
            val paired = BluetoothClassicTransport.pairedAdapters(context)
            val civic = paired.firstOrNull { AutoStartReceiver.isCivicBluetoothName(it.name) }
            if (civic != null) {
                carBtAddress = civic.address
                carBtName = civic.name
                saveCarBluetooth(context, civic.address, civic.name)
            }
        }
    }

    var permissionEpoch by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionEpoch++ }
    val canOverlay = remember(permissionEpoch) { OverlayHost.canDrawOverlays(context) }

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

    // The backup folder is picked once through the system picker; the grant is persisted so
    // every later backup is silent.
    val backupPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { BackupManager.onFolderPicked(context, uri) }.getOrDefault(false)
        Toast.makeText(
            context,
            if (ok) "Backup saved" else "Could not write a backup there",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val closeDetail = { detail = null }
    BackHandler(enabled = detail != null) {
        detail = null
    }

    // A request from outside - the HUD tap, the widget - lands here once and is consumed,
    // so rotating or re-composing does not drag the user back to the Fuel screen forever.
    androidx.compose.runtime.LaunchedEffect(deepLink.value) {
        deepLink.value?.let {
            detail = it
            (deepLink as? androidx.compose.runtime.MutableState<DetailScreen?>)?.value = null
        }
    }

    when (detail) {
        DetailScreen.Codes -> { CodesScreen(onBack = closeDetail); return }
        DetailScreen.Trips -> { TripsScreen(onBack = closeDetail); return }
        DetailScreen.Fuel -> { FuelScreen(onBack = closeDetail); return }
        DetailScreen.Oil -> { OilScreen(onBack = closeDetail); return }
        DetailScreen.Clutch -> { ClutchScreen(onBack = closeDetail); return }
        null -> {}
    }

    // RECONNECTING keeps this screen rather than dropping back to the adapter list. Being
    // thrown to a list of Bluetooth devices halfway through a drive, because of a tunnel, is
    // how a driver concludes the app crashed.
    if (connection == ConnectionStatus.CONNECTED ||
        connection == ConnectionStatus.SIMULATING ||
        connection == ConnectionStatus.RECONNECTING
    ) {
        DriveScreen(
            metrics = metrics,
            trip = trip,
            lifetime = lifetime,
            connection = connection,
            shiftMode = shiftMode,
            onToggleShiftMode = { TelemetryState.toggleShiftMode() },
            onOpen = { detail = it },
            onStop = { TelemetryService.disconnect(context) },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        CarHeader(connection)
        Spacer(Modifier.height(28.dp))

        // MPG is the hero, as in the web build - the one number worth reading at a glance.
        //
        // It is only ever drawn as a number while actually driving. Standing at a light the
        // figure is zero because the car is not moving, and on a closed throttle it is the
        // 99.9 cap because the injectors are off; neither is "your car is getting N mpg", so
        // neither gets drawn as one. That is what mpgDisplayState is for.
        val live = connection == ConnectionStatus.CONNECTED ||
            connection == ConnectionStatus.SIMULATING
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

        Spacer(Modifier.height(20.dp))

        Text(status, color = CivicColors.Ink2, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))

        when (connection) {
            ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING,
            ConnectionStatus.SIMULATING, ConnectionStatus.RECONNECTING -> {
                PrimaryButton("Disconnect") { TelemetryService.disconnect(context) }
            }
            else -> {
                // The adapter that answered last gets the one big button, because a phone is
                // bonded to headphones, a watch and a car stereo, and exactly one of them
                // speaks OBD-II. Starting a drive should be one obvious tap.
                lastAdapter?.let { address ->
                    PrimaryButton("Connect to your Civic") {
                        TelemetryService.connect(context, address)
                    }
                    Spacer(Modifier.height(12.dp))
                }

                SecondaryButton(
                    if (adapters.isEmpty()) "Browse paired adapters" else "Other paired adapters"
                ) { requestPermissions.launch(permissions) }

                adapters.forEach { device ->
                    Spacer(Modifier.height(8.dp))
                    AdapterRow(device.name) {
                        TelemetryService.connect(context, device.address)
                    }
                }

                Spacer(Modifier.height(28.dp))

                val hudSubtitle = when {
                    !canOverlay -> "Permission needed"
                    overlayEnabled -> "On"
                    else -> "Off"
                }
                val autoConnectSubtitle = when {
                    !autoConnect -> "Off"
                    carBtName != null -> "Starts with $carBtName"
                    carBtAddress != null -> "Starts with $carBtAddress"
                    else -> "Starts with Civic Bluetooth"
                }
                FeatureGrid(
                    listOf(
                        Feature(
                            "Trip history",
                            "$tripCount drives",
                            Icons.Outlined.History,
                        ) { detail = DetailScreen.Trips },
                        Feature(
                            "Fuel",
                            metrics.tankMpg?.let { "%.1f mpg".format(it) } ?: "Tank & fills",
                            Icons.Outlined.LocalGasStation,
                        ) { detail = DetailScreen.Fuel },
                        Feature(
                            "Oil life",
                            oil?.let { "${it.oilLifePercent.roundToInt()}%" } ?: "Interval",
                            Icons.Outlined.OilBarrel,
                        ) { detail = DetailScreen.Oil },
                        Feature(
                            "Clutch health",
                            clutch?.let { "${it.clutchHealthPercent.roundToInt()}%" } ?: "Wear & RUL",
                            Icons.Outlined.DonutLarge,
                        ) { detail = DetailScreen.Clutch },
                        Feature(
                            "Diagnostics",
                            "Read & clear codes",
                            Icons.Outlined.Build,
                        ) { detail = DetailScreen.Codes },
                        Feature(
                            "Heads-up display",
                            hudSubtitle,
                            Icons.Outlined.Layers,
                        ) {
                            if (!canOverlay) {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName),
                                    )
                                )
                            } else {
                                val next = !overlayEnabled
                                TelemetryState.setOverlayEnabled(next)
                                saveOverlayEnabled(context, next)
                            }
                        },
                        // The bench. Every screen can be built and looked at away from the car,
                        // which matters when the car is parked outside with the ignition off.
                        Feature(
                            "Simulated drive",
                            "Test bench",
                            Icons.Outlined.Science,
                        ) { TelemetryService.simulate(context) },
                        Feature(
                            "Backup",
                            backupSubtitle(context),
                            Icons.Outlined.Backup,
                        ) {
                            if (loadBackupTreeUri(context) == null) {
                                backupPicker.launch(null)
                            } else {
                                val result = BackupManager.restore(context)
                                Toast.makeText(
                                    context,
                                    result ?: "Nothing to restore - every record is already here",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        Feature(
                            "Auto-connect",
                            autoConnectSubtitle,
                            Icons.Outlined.Bluetooth,
                        ) {
                            if (!autoConnect) {
                                autoConnect = true
                                saveAutoConnect(context, true)
                                val paired = BluetoothClassicTransport.pairedAdapters(context)
                                if (adapters.isEmpty()) adapters = paired
                                val civic = paired.firstOrNull { AutoStartReceiver.isCivicBluetoothName(it.name) }
                                if (civic != null && carBtAddress == null) {
                                    carBtAddress = civic.address
                                    carBtName = civic.name
                                    saveCarBluetooth(context, civic.address, civic.name)
                                }
                                Toast.makeText(
                                    context,
                                    if (carBtName != null) "Auto-connect enabled (starts with $carBtName)"
                                    else "Auto-connect enabled (starts with Civic Bluetooth)",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                if (adapters.isEmpty()) {
                                    adapters = BluetoothClassicTransport.pairedAdapters(context)
                                }
                                showAutoConnectDialog = true
                            }
                        },
                    )
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        PanelCard {
            Text(
                text = "LIVE DATA",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            LiveReadings(metrics, trip, lifetime, resolved, connection)
        }

        // The failure messages tell you to check the adapter log, so the adapter log has to
        // be somewhere you can check. It was not, which made that sentence an instruction
        // pointing at nothing - noticed because the ignition-off message said it on the car.
        val log by TelemetryState.protocolLog.collectAsStateWithLifecycle()
        if (log.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PanelCard {
                Text(
                    text = "ADAPTER LOG",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(10.dp))
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
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAutoConnectDialog) {
        AutoConnectDialog(
            currentAddress = carBtAddress,
            pairedDevices = if (adapters.isNotEmpty()) adapters else BluetoothClassicTransport.pairedAdapters(context),
            onSelectDevice = { address, name ->
                carBtAddress = address
                carBtName = name
                saveCarBluetooth(context, address, name)
                showAutoConnectDialog = false
                Toast.makeText(
                    context,
                    if (name != null) "Auto-connect will start with $name"
                    else "Auto-connect will auto-detect Civic Bluetooth",
                    Toast.LENGTH_SHORT,
                ).show()
            },
            onTurnOff = {
                autoConnect = false
                saveAutoConnect(context, false)
                showAutoConnectDialog = false
                Toast.makeText(context, "Auto-connect turned off", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showAutoConnectDialog = false },
        )
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

/**
 * What to call the air temperature, decided by which PID answered.
 *
 * PID 46 is the real outside temperature. PID 0F is intake air, a different quantity: after a
 * few minutes of idling it reports engine-bay heat. This car has only 0F, so the row is always
 * intake air, and a heading saying "outside" would be a claim nothing measured.
 */
private fun airLabel(source: OutsideAirSource?): String =
    if (source == OutsideAirSource.AMBIENT) "Outside air" else "Intake air"

@Composable
private fun LiveReadings(
    metrics: LiveMetrics,
    trip: TripAnalytics,
    lifetime: LifetimeStats,
    resolved: ResolvedPids,
    connection: ConnectionStatus,
) {
    val live = connection == ConnectionStatus.CONNECTED ||
        connection == ConnectionStatus.SIMULATING

    val rows = listOf(
        "Vehicle health" to if (live) metrics.healthStatus.summary else null,
        "Coolant" to if (live && metrics.coolantTempF > 0) "${metrics.coolantTempF} °F" else null,
        "Battery / charging" to metrics.batteryVoltage?.takeIf { live }?.let { "%.2f V".format(it) },
        "Fuel level" to metrics.fuelLevelPercent?.let { "%.0f %%".format(it) },
        "Range" to metrics.fuelRangeMiles?.let {
            if (metrics.tankBelowSenderZero) "under $it mi to empty" else "$it mi to empty"
        },
        "Tank economy" to metrics.tankMpg?.let { "%.1f mpg".format(it) },
        "Trip" to if (live) "%.1f mi · %.1f mpg".format(trip.distanceMiles, trip.avgMpg) else null,
        "Lifetime" to lifetime.totalMiles
            .takeIf { it > 0 }
            ?.let { "%.1f mi · %.1f mpg".format(it, lifetime.lifetimeMpg) },
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

/** Where the Backup card stands today: unconfigured, configured, or last known save time. */
private fun backupSubtitle(context: android.content.Context): String {
    val lastBackupAt = loadLastBackupAt(context)
    return when {
        loadBackupTreeUri(context) == null -> "Choose a folder"
        lastBackupAt == 0L -> "Set up"
        else -> "Saved " + DateUtils.getRelativeTimeSpanString(lastBackupAt).toString()
    }
}

/** One tile of the feature grid: what it is, what it knows right now, and where it goes. */
private data class Feature(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

/**
 * The car, named, with its state on the right.
 *
 * A home screen that opens on a list of commands reads as a settings page. Opening on the
 * car itself - which car, is it awake - reads as an instrument for that car.
 */
@Composable
private fun CarHeader(connection: ConnectionStatus) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "2013 CIVIC LX",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "1.8L i-VTEC · 5-Speed Manual",
                color = CivicColors.Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        StatusChip(connection)
    }
}

@Composable
private fun StatusChip(connection: ConnectionStatus) {
    val (label, color) = when (connection) {
        ConnectionStatus.CONNECTED -> "Live" to CivicColors.Good
        ConnectionStatus.SIMULATING -> "Bench" to CivicColors.Warn
        ConnectionStatus.CONNECTING -> "Connecting" to CivicColors.Warn
        ConnectionStatus.RECONNECTING -> "Reconnecting" to CivicColors.Warn
        ConnectionStatus.ERROR -> "Error" to CivicColors.Accent
        else -> "Offline" to CivicColors.Ink3
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/** The one obvious action: starting - or ending - a drive. */
@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CivicColors.Accent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = CivicColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF171A1E))
            .border(1.dp, CivicColors.HairlineStrong, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = CivicColors.Ink2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AdapterRow(name: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF141619))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, color = CivicColors.Ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("Connect", color = CivicColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FeatureGrid(features: List<Feature>) {
    features.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            row.forEach { feature ->
                FeatureCard(feature, Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun FeatureCard(feature: Feature, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15181C))
            .border(1.dp, CivicColors.HairlineStrong, RoundedCornerShape(18.dp))
            .clickable(onClick = feature.onClick)
            .padding(16.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x14FFFFFF)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    tint = CivicColors.Ink,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(feature.title, color = CivicColors.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(feature.subtitle, color = CivicColors.Ink3, fontSize = 12.sp)
        }
    }
}

/** A quiet surface for secondary information, so the screen reads in layers. */
@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF15181C))
            .border(1.dp, CivicColors.HairlineStrong, RoundedCornerShape(18.dp))
            .padding(16.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun AutoConnectDialog(
    currentAddress: String?,
    pairedDevices: List<PairedDevice>,
    onSelectDevice: (address: String?, name: String?) -> Unit,
    onTurnOff: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15181C),
        title = {
            Text(
                "Auto-Connect Trigger",
                color = CivicColors.Ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Your Civic connects to your phone when you turn on the engine. Pick your car's Bluetooth to start logging automatically:",
                    color = CivicColors.Ink2,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(4.dp))

                Text(
                    "CHOOSE TRIGGER DEVICE",
                    color = CivicColors.Ink3,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.sp,
                )

                val isAutoSelected = currentAddress == null
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isAutoSelected) Color(0x223B82F6) else Color(0xFF1C2025))
                        .border(
                            1.dp,
                            if (isAutoSelected) CivicColors.Accent else CivicColors.HairlineStrong,
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onSelectDevice(null, null) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column {
                        Text(
                            "Auto-detect Civic (HandsFreeLink)",
                            color = if (isAutoSelected) CivicColors.Accent else CivicColors.Ink,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "Starts with any Honda or car audio device",
                            color = CivicColors.Ink3,
                            fontSize = 12.sp,
                        )
                    }
                }

                pairedDevices.forEach { device ->
                    val isSelected = device.address.equals(currentAddress, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0x223B82F6) else Color(0xFF1C2025))
                            .border(
                                1.dp,
                                if (isSelected) CivicColors.Accent else CivicColors.HairlineStrong,
                                RoundedCornerShape(10.dp),
                            )
                            .clickable { onSelectDevice(device.address, device.name) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Column {
                            Text(
                                device.name,
                                color = if (isSelected) CivicColors.Accent else CivicColors.Ink,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                device.address,
                                color = CivicColors.Ink3,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x18EF4444))
                        .border(1.dp, Color(0x44EF4444), RoundedCornerShape(10.dp))
                        .clickable { onTurnOff() }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Turn off auto-connect",
                        color = Color(0xFFEF4444),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        },
        confirmButton = {
            Text(
                "Done",
                color = CivicColors.Accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clickable(onClick = onDismiss)
                    .padding(8.dp),
            )
        },
    )
}

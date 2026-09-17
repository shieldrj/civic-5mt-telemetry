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
import androidx.compose.material.icons.outlined.Layers
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.OutsideAirSource
import com.shieldrj.civic5mt.core.TripAnalytics
import com.shieldrj.civic5mt.data.TripDatabase
import com.shieldrj.civic5mt.data.TripEntity
import com.shieldrj.civic5mt.service.ResolvedPids
import com.shieldrj.civic5mt.service.TelemetryService
import com.shieldrj.civic5mt.service.TelemetryState
import com.shieldrj.civic5mt.service.loadLastAdapter
import com.shieldrj.civic5mt.service.saveOverlayEnabled
import com.shieldrj.civic5mt.transport.BluetoothClassicTransport
import com.shieldrj.civic5mt.transport.PairedDevice
import com.shieldrj.civic5mt.ui.overlay.OverlayHost

/**
 * The shell, and enough of a screen to prove the whole chain works end to end: a Bluetooth
 * socket, the ELM327 handshake, the poll loop and the models in a service, and the figures
 * they produce on screen.
 *
 * The navigation used to be a row of six words at the foot of the Drive screen, each of which
 * replaced the whole screen; getting from Fuel to Oil meant going back and aiming again at a
 * 15sp target, and "Stop" sat in that row at the same size as the rest. It is four tabs now,
 * always on screen, with the link state and its one button above them. See [CivicShell].
 */
class MainActivity : ComponentActivity() {

    /**
     * A destination requested from outside this Activity - a tap on the floating HUD, or on
     * the widget. Held as state rather than read once, because singleTask delivery can arrive
     * through [onNewIntent] while the Compose tree is already standing.
     */
    private val deepLinkTab = mutableStateOf<Tab?>(null)
    private val deepLinkSection = mutableStateOf<HealthSection?>(null)

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
                    CivicApp(deepLinkTab, deepLinkSection)
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
            TelemetryService.SCREEN_FUEL -> {
                deepLinkTab.value = Tab.Fuel
            }
            // The clutch is a section of Health now, so a notification about it has to name
            // both the tab and the section or it lands on whichever section was last open.
            TelemetryService.SCREEN_CLUTCH -> {
                deepLinkTab.value = Tab.Health
                deepLinkSection.value = HealthSection.Clutch
            }
        }
    }
}

/**
 * Which tab, which section of Health, and whether Settings is in front - and nothing else.
 *
 * Every screen below owns its own state and collects what it needs for itself. What is held
 * here is only what more than one of them has to agree on.
 */
@Composable
private fun CivicApp(
    deepLinkTab: MutableState<Tab?>,
    deepLinkSection: MutableState<HealthSection?>,
) {
    val context = LocalContext.current

    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val lifetime by TelemetryState.lifetime.collectAsStateWithLifecycle()
    val status by TelemetryState.statusMessage.collectAsStateWithLifecycle()
    val shiftMode by TelemetryState.shiftMode.collectAsStateWithLifecycle()

    // The adapter that answered last. A phone is bonded to headphones, a watch and a car
    // stereo, and exactly one of them speaks OBD-II; knowing which means the connection bar
    // can offer one button rather than a list.
    //
    // Re-read when the app comes back to the front, not once at startup. It is written the
    // first time an adapter answers, and the bar's Connect button is now the main way in - so
    // holding the startup value would leave that button missing for the whole of the session
    // in which it was first earned.
    var adapterEpoch by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { adapterEpoch++ }
    val lastAdapter = remember(adapterEpoch) { loadLastAdapter(context) }

    var tab by remember { mutableStateOf(Tab.Drive) }
    var section by remember { mutableStateOf(HealthSection.Oil) }
    var settingsOpen by remember { mutableStateOf(false) }

    // Back closes Settings, then returns to Drive, then leaves. Drive is the home of this app
    // in the sense that matters: it is the one you are on when the car is moving.
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    BackHandler(enabled = !settingsOpen && tab != Tab.Drive) { tab = Tab.Drive }

    // A request from outside - the HUD tap, the widget - lands here once and is consumed, so
    // rotating or re-composing does not drag the user back to the Fuel tab forever.
    LaunchedEffect(deepLinkTab.value) {
        deepLinkTab.value?.let {
            tab = it
            settingsOpen = false
            deepLinkSection.value?.let { requested -> section = requested }
            deepLinkTab.value = null
            deepLinkSection.value = null
        }
    }

    // RECONNECTING counts as driving. Being dropped back to a connect button halfway through
    // a drive, because of a tunnel, is how a driver concludes the app crashed.
    val driving = connection == ConnectionStatus.CONNECTED ||
        connection == ConnectionStatus.SIMULATING ||
        connection == ConnectionStatus.RECONNECTING

    CivicShell(
        tab = tab,
        onTab = {
            tab = it
            settingsOpen = false
        },
        status = connection,
        detail = status,
        canConnect = lastAdapter != null,
        onConnectionAction = {
            when (connection) {
                ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR ->
                    lastAdapter?.let { TelemetryService.connect(context, it) }
                else -> TelemetryService.disconnect(context)
            }
        },
        onSettings = { settingsOpen = !settingsOpen },
    ) {
        when {
            settingsOpen -> SettingsPane()

            tab == Tab.Drive && driving -> DriveScreen(
                metrics = metrics,
                trip = trip,
                lifetime = lifetime,
                connection = connection,
                shiftMode = shiftMode,
                onToggleShiftMode = { TelemetryState.toggleShiftMode() },
                onOpenHealth = {
                    section = it
                    tab = Tab.Health
                },
            )

            tab == Tab.Drive -> ConnectPane(onOpenTrips = { tab = Tab.Trips })

            tab == Tab.Fuel -> FuelScreen()
            tab == Tab.Health -> HealthScreen(section = section, onSection = { section = it })
            tab == Tab.Trips -> TripsScreen()
        }
    }
}

/**
 * The Drive tab with nothing connected: how to start, and what the last drive came to.
 *
 * This is the same tab as the gauge, not a different screen - the shell above it is
 * identical, the tabs are in the same place, and the line saying which state the link is in
 * has not moved. That is the point. Before, connected and disconnected were two unrelated
 * layouts, so the only way to read the state was to recognise which page you were looking at.
 */
@Composable
private fun ConnectPane(onOpenTrips: () -> Unit) {
    val context = LocalContext.current
    val lastAdapter = remember { loadLastAdapter(context) }

    // The drive that just ended. The Drive screen and every figure on it goes when the link
    // does, which made the moment someone actually wants a number - standing beside a car
    // they have just parked - the one moment the app had nothing to say.
    val lastTrip by remember { TripDatabase.get(context).tripDao().observeLastFinishedTrip() }
        .collectAsStateWithLifecycle(null)

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        // Above the adapter list, because after a drive this is the thing being looked for
        // and the button is the thing being looked for before one. Absent on a phone that has
        // never finished a drive - an empty card teaches nothing.
        lastTrip?.let {
            LastDriveCard(it, onOpenTrips)
            Spacer(Modifier.height(24.dp))
        }

        // Starting a drive should be one obvious tap; the connection bar carries the same
        // action, and this is the same tap made big enough to find without looking.
        if (lastAdapter != null) {
            PrimaryButton("Connect to your Civic") {
                TelemetryService.connect(context, lastAdapter)
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
    }
}

/**
 * Settings: the four things that are switches or one-off actions rather than places to go.
 *
 * It used to be nine tiles on the disconnected page, five of which were navigation - and
 * being on that page meant it was unreachable while connected, so the heads-up display could
 * not be turned on during the drive it exists for. Those five are tabs now. What is left is
 * genuinely settings, and it is one tap from anywhere.
 */
@Composable
private fun SettingsPane() {
    val context = LocalContext.current

    val connection by TelemetryState.connection.collectAsStateWithLifecycle()
    val metrics by TelemetryState.metrics.collectAsStateWithLifecycle()
    val trip by TelemetryState.trip.collectAsStateWithLifecycle()
    val lifetime by TelemetryState.lifetime.collectAsStateWithLifecycle()
    val resolved by TelemetryState.resolvedPids.collectAsStateWithLifecycle()
    val overlayEnabled by TelemetryState.overlayEnabled.collectAsStateWithLifecycle()

    var autoConnect by remember { mutableStateOf(loadAutoConnect(context)) }
    var carBtAddress by remember { mutableStateOf(loadCarBluetoothAddress(context)) }
    var carBtName by remember { mutableStateOf(loadCarBluetoothName(context)) }
    var showAutoConnectDialog by remember { mutableStateOf(false) }
    var adapters by remember { mutableStateOf(emptyList<PairedDevice>()) }

    // If no car Bluetooth device has been chosen yet, auto-detect from paired devices.
    LaunchedEffect(Unit) {
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

    // Overlay permission is granted on a Settings screen, not in a dialog, so the only way to
    // know it changed is to look again when the app comes back to the front.
    var permissionEpoch by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permissionEpoch++ }
    val canOverlay = remember(permissionEpoch) { OverlayHost.canDrawOverlays(context) }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
    ) {
        Text(
            text = "SETTINGS",
            color = CivicColors.Ink3,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(16.dp))

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
                        val civic = paired.firstOrNull {
                            AutoStartReceiver.isCivicBluetoothName(it.name)
                        }
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
                // The bench. Every screen can be built and looked at away from the car, which
                // matters when the car is parked outside with the ignition off.
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
            )
        )

        Spacer(Modifier.height(16.dp))

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
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showAutoConnectDialog) {
        AutoConnectDialog(
            currentAddress = carBtAddress,
            pairedDevices = if (adapters.isNotEmpty()) {
                adapters
            } else {
                BluetoothClassicTransport.pairedAdapters(context)
            },
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

/**
 * What the drive that just ended came to, drawn with nothing connected.
 *
 * The Drive screen and every figure on it goes when the link does, which made the moment
 * someone actually wants a number - standing beside a car they have just parked - the one
 * moment the app had nothing to say. This reads the finished row back out of the trip log
 * rather than holding the live analytics open, so it survives the service stopping and the
 * process dying with it, which is exactly what the ignition going off causes.
 *
 * Stamped with when it ended, and titled as the last drive rather than drawn like a gauge,
 * because a figure with no time against it reads as a current one. This is a record of
 * something finished, and the stamp is what keeps it honest.
 */
@Composable
private fun LastDriveCard(trip: TripEntity, onOpen: () -> Unit) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "LAST DRIVE",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            trip.endedAt?.let {
                Text(
                    text = DateUtils.getRelativeTimeSpanString(it).toString(),
                    color = CivicColors.Ink3,
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "%.1f".format(trip.avgMpg),
                color = CivicColors.Ink,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                modifier = Modifier.alignByBaseline(),
            )
            Text(
                text = " MPG",
                color = CivicColors.Ink3,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.4.sp,
                modifier = Modifier.alignByBaseline(),
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "%.1f mi · %s · %d eco".format(
                trip.distanceMiles,
                formatDuration(trip.durationSec),
                trip.ecoScore,
            ),
            color = CivicColors.Ink2,
            fontSize = 13.sp,
        )

        Spacer(Modifier.height(14.dp))

        Text(
            text = "See all drives",
            color = CivicColors.Accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onOpen),
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

package com.shieldrj.civic5mt.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.shieldrj.civic5mt.R
import com.shieldrj.civic5mt.data.TripDatabase
import com.shieldrj.civic5mt.data.TripRecorder
import com.shieldrj.civic5mt.core.CivicSimulatorEngine
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.DtcScanner
import com.shieldrj.civic5mt.core.Elm327Client
import com.shieldrj.civic5mt.core.ObdTransportError
import com.shieldrj.civic5mt.core.ReconnectPolicy
import com.shieldrj.civic5mt.core.OilLifeEngine
import com.shieldrj.civic5mt.core.TankTracker
import com.shieldrj.civic5mt.core.TelemetryManager
import com.shieldrj.civic5mt.transport.BluetoothClassicTransport
import com.shieldrj.civic5mt.ui.overlay.HudContent
import com.shieldrj.civic5mt.ui.overlay.OverlayHost
import com.shieldrj.civic5mt.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.plus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import com.shieldrj.civic5mt.data.BackupManager
import com.shieldrj.civic5mt.widget.TankWidget

/**
 * Owns the adapter connection and the poll loop, for as long as the car is running.
 *
 * This is the structural reason for the native port. In the WebView the tick loop was
 * throttled the moment the app was backgrounded and killed shortly after - there is a commit
 * in this repo whose entire purpose is flushing accumulated oil wear on background, because
 * that was the only chance it got. A foreground service does not have that problem: the
 * screen can be off, the phone in a pocket, and the log keeps accruing.
 *
 * It is also what makes the rest of the plan possible. A HUD drawn over Google Maps has no
 * Activity behind it, and neither does a widget or an Android Auto surface, so the thing that
 * holds the connection cannot be a screen. Everything reads [TelemetryState]; only this
 * writes it.
 */
class TelemetryService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob) + kotlinx.coroutines.Dispatchers.Default

    private var client: Elm327Client? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var tickJob: Job? = null

    /**
     * The adapter this drive is attached to, kept so a dropped link can be chased.
     *
     * Also written to preferences, which is what lets the app offer the adapter it used last
     * rather than a list to hunt through - the phone is paired with more than one Bluetooth
     * device and only one of them is in the car.
     */
    private var currentAddress: String? = null

    /**
     * The models, and the trip and lifetime figures they accumulate into.
     *
     * Held by the service rather than rebuilt per connection, so a Bluetooth drop in a tunnel
     * does not end the trip or lose what the oil model has been accruing.
     */
    private lateinit var manager: TelemetryManager

    /**
     * The heads-up display, owned here rather than by an Activity.
     *
     * That is the whole point of it: the window it draws into sits on top of Google Maps, so
     * there is no Activity of ours on screen to own anything. It follows the connection - a
     * HUD showing the last reading from a link that dropped ten minutes ago is worse than no
     * HUD at all.
     */
    private var overlay: OverlayHost? = null

    /** Writes each drive to the database while it is happening. */
    private lateinit var recorder: TripRecorder

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // The migration runs in Civic5MTApp, not here: this service only starts once you
        // connect to an adapter, so a phone that had never managed a connection would have
        // left the rescued record un-imported.
        manager = TelemetryManager(
            lifetimeStore = PrefsLifetimeStore(applicationContext),
            oilLife = OilLifeEngine(PrefsOilProfileStore(applicationContext)),
            tank = TankTracker(PrefsTankStore(applicationContext)),
        )
        TelemetryState.setLifetime(manager.getLifetimeStats())
        TelemetryState.setOil(manager.oilLife.getProfile())

        recorder = TripRecorder(TripDatabase.get(applicationContext).tripDao())

        TelemetryState.setOverlayEnabled(loadOverlayEnabled(applicationContext))
        TelemetryState.setHudTheme(loadHudTheme(applicationContext))
        observeOverlay()
        observeFuelBlend()
        observeHudTheme()
        observeDrivingState()
        observeVoltageAlerts()
        observeWidgetPushes()
    }

    /** Persists whatever the long-press on the HUD cycled the card's look to. */
    private fun observeHudTheme() {
        scope.launch {
            TelemetryState.hudTheme.collect { saveHudTheme(applicationContext, it) }
        }
    }

    /**
     * Follows the blend the driver picked on the Fuel screen.
     *
     * The screen writes the preference and the flow; this only reads. One writer for a
     * setting that is stored on disk and also held in a running model, because the other
     * arrangement - screen writes prefs, service writes prefs, both hold a copy - is how a
     * setting ends up meaning two different things in the same process.
     */
    private fun observeFuelBlend() {
        scope.launch {
            TelemetryState.fuelBlend.collect { manager.setFuelBlend(it) }
        }
    }

    /**
     * Whether the car has been sitting still long enough that the HUD is in the way.
     *
     * A card floating over Google Maps is useful while driving and obstruction while parked -
     * once the trip is over, it is just something covering the map of where you are. Ten
     * minutes below a walking pace means errands and idling at lights do not blink the HUD
     * off, but a car left running in a driveway eventually does.
     */
    private val parkedTooLong = MutableStateFlow(false)

    /**
     * Watches for the drive being over, two ways.
     *
     * Parked too long just tucks the HUD away. Engine off is louder: once this connection has
     * seen the engine running, RPM at zero while stationary - held for a few seconds so one
     * missed PID read does not kill the app at a stop sign - means the ignition is off, and
     * the whole service shuts down then and there. The trip is saved first; the adapter has
     * no reason to stay powered a minute longer than the car.
     */
    private var sawEngineRunning = false
    private var engineOffSince: Long? = null

    private fun observeDrivingState() {
        scope.launch {
            var lastMovingAt = System.currentTimeMillis()
            TelemetryState.metrics.collect { m ->
                val now = System.currentTimeMillis()

                // HUD visibility: hide after long enough parked, show again on movement.
                if (m.speedMph >= MOVING_SPEED_MPH) {
                    lastMovingAt = now
                    parkedTooLong.value = false
                } else if (!parkedTooLong.value && now - lastMovingAt >= PARKED_HIDE_AFTER_MS) {
                    parkedTooLong.value = true
                }

                // Ignition watchdog: engine was running, now it is not.
                if (m.rpm >= ENGINE_RUNNING_RPM) {
                    sawEngineRunning = true
                    engineOffSince = null
                } else if (sawEngineRunning && m.speedMph < MOVING_SPEED_MPH) {
                    val since = engineOffSince
                    if (since == null) {
                        engineOffSince = now
                    } else if (now - since >= ENGINE_OFF_CONFIRM_MS) {
                        sawEngineRunning = false
                        engineOffSince = null
                        shutdownForIgnitionOff()
                    }
                } else {
                    engineOffSince = null
                }
            }
        }
    }

    /**
     * The engine is off: save the trip, drop the link, end the service.
     *
     * This is the path that keeps the OBDLink from sitting on the battery all afternoon,
     * which it will happily do - its radio stays alive whenever the OBD port has power.
     */
    private fun shutdownForIgnitionOff() {
        scope.launch {
            teardown("Engine off - trip saved")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Shows the HUD only while there is something to show.
     *
     * All three conditions matter. Without the preference it appears uninvited over whatever
     * is on screen; without the connection check it keeps displaying a frozen reading after
     * the adapter drops, which is the failure that makes a driver stop trusting a gauge; and
     * without the parked check it sits over the map after the drive has ended.
     */
    private fun observeOverlay() {
        scope.launch {
            combine(
                TelemetryState.overlayEnabled,
                TelemetryState.connection,
                parkedTooLong,
            ) { enabled, connection, parked ->
                enabled && !parked && (connection == ConnectionStatus.CONNECTED ||
                    connection == ConnectionStatus.SIMULATING)
            }.collect { shouldShow ->
                // WindowManager is main-thread only, and the service scope is Default.
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    // runCatching because of what the alternative costs. This collector is
                    // the only thing keeping the HUD in step with the connection, and an
                    // exception thrown through it does not surface anywhere - it cancels this
                    // coroutine and the card silently stops following the drive until the
                    // next ignition cycle. A window that failed to go up is worth a log line
                    // and one more attempt on the next emission, not the whole observer.
                    runCatching {
                        if (shouldShow) {
                            if (overlay == null) overlay = OverlayHost(
                                this@TelemetryService,
                                { HudContent() },
                                onTap = { openFuelScreen() },
                                onLongPress = { cycleHudThemeWithFeedback() },
                            )
                            overlay?.show()
                        } else {
                            overlay?.hide()
                        }
                    }.onFailure { Log.e(TAG, "Overlay show/hide failed", it) }
                }
            }
        }
    }

    /** Tapping the HUD opens the app on the Fuel screen - the fastest path to logging a fill. */
    private fun openFuelScreen() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_OPEN_SCREEN, SCREEN_FUEL)
        }
        startActivity(intent)
    }

    /**
     * Long-pressing the HUD cycles its look, with a toast to say which it landed on - because
     * a change you cannot see confirmed is a change you will long-press again to check.
     */
    private fun cycleHudThemeWithFeedback() {
        val theme = TelemetryState.cycleHudTheme()
        val label = when (theme) {
            HudTheme.LIGHT -> "HUD: light (Maps day)"
            HudTheme.DARK -> "HUD: dark (Maps night)"
            HudTheme.SYSTEM -> "HUD: follows phone"
        }
        android.widget.Toast.makeText(applicationContext, label, android.widget.Toast.LENGTH_SHORT).show()
    }

    /**
     * Warns once per drive when the battery is being pulled down.
     *
     * The adapter is powered by the car whether the engine is running or not, so a phone left
     * logging through a long park drains the starting battery - and nothing on the dash says
     * so until the car does not start. Below 12.0V for a sustained minute is past surface
     * charge and into real drain. Once per drive, because a repeating alarm while someone is
     * driving is noise, and noise gets ignored.
     */
    private fun observeVoltageAlerts() {
        scope.launch {
            // A new drive resets the one-shot flag.
            launch {
                TelemetryState.connection.collect { status ->
                    if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.SIMULATING) {
                        voltageAlerted = false
                    }
                }
            }
            var lowSince: Long? = null
            TelemetryState.metrics.collect { m ->
                // Null until the car has answered PID 42. No reading is not a low reading.
                val voltage = m.batteryVoltage ?: return@collect
                val now = System.currentTimeMillis()
                if (voltage > MIN_VALID_VOLTAGE && voltage < LOW_VOLTAGE) {
                    if (lowSince == null) {
                        lowSince = now
                    } else if (!voltageAlerted && now - lowSince!! >= LOW_VOLTAGE_SUSTAIN_MS) {
                        voltageAlerted = true
                        postVoltageAlert(voltage)
                    }
                } else {
                    lowSince = null
                }
            }
        }
    }

    private var voltageAlerted = false

    private fun postVoltageAlert(voltage: Double) {
        getSystemService(NotificationManager::class.java).notify(
            VOLTAGE_NOTIFICATION_ID,
            Notification.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("Battery at ${"%.1f".format(voltage)}V")
                .setContentText("The adapter may be draining the battery. Consider disconnecting.")
                .setSmallIcon(R.drawable.ic_stat_telemetry)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Pushes tank figures to the home-screen widget, at most every thirty seconds. */
    private fun observeWidgetPushes() {
        scope.launch {
            var lastPush = 0L
            TelemetryState.metrics.collect { m ->
                val now = System.currentTimeMillis()
                if (now - lastPush >= WIDGET_PUSH_INTERVAL_MS) {
                    lastPush = now
                    TankWidget.update(applicationContext, m)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) {
                    TelemetryState.setStatusMessage("No adapter chosen.")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForegroundWithStatus("Connecting…")
                connect(address)
            }

            ACTION_SIMULATE -> {
                startForegroundWithStatus("Simulating")
                simulate()
            }

            ACTION_SCAN_DTC -> scanForCodes()

            ACTION_MARK_FILLED -> {
                markFilled()
                stopIfIdle()
            }

            ACTION_RESET_OIL -> {
                resetOilLife()
                stopIfIdle()
            }

            ACTION_CLEAR_DTC -> clearCodes()

            ACTION_DISCONNECT -> {
                // Stopping on purpose is not a link to chase.
                currentAddress = null
                scope.launch { teardown("Disconnected") }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // Not sticky: an automatic restart would reconnect to the adapter with no ignition
        // and sit there failing. Reconnecting is a decision for the auto-connect logic, which
        // watches for the adapter appearing, rather than for the OS restarting a dead service.
        return START_NOT_STICKY
    }

    private fun connect(address: String) {
        // The loops outlive connectJob now - they are launched on the service scope so the
        // reconnect path can restart them - so a fresh connect has to stop them by name.
        // Cancelling them also marks the poll job cancelled, which is what stops the watcher
        // from treating this as a link that dropped.
        connectJob?.cancel()
        pollJob?.cancel()
        tickJob?.cancel()
        currentAddress = address
        saveLastAdapter(applicationContext, address)
        connectJob = scope.launch {
            TelemetryState.setConnection(ConnectionStatus.CONNECTING)
            TelemetryState.reset()

            val transport = BluetoothClassicTransport(applicationContext, address)
            val elm = Elm327Client(transport)
            client = elm

            // Mirror the client's own state out to anything watching.
            launch { elm.data.collect { TelemetryState.setData(it) } }
            launch { elm.protocolLog.collect { TelemetryState.setProtocolLog(it) } }

            try {
                elm.connect { message ->
                    TelemetryState.setStatusMessage(message)
                    updateNotification(message)
                }

                TelemetryState.setResolvedPids(
                    ResolvedPids(
                        lambda = elm.lambdaPid,
                        preCat = elm.preCatPid,
                        outsideAir = elm.outsideAirPid,
                    )
                )
                TelemetryState.setConnection(ConnectionStatus.CONNECTED)
                updateNotification("Logging")

                manager.resetTrip()
                recorder.start(System.currentTimeMillis(), simulated = false)

                startLoops(elm)
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: ObdTransportError) {
                // These messages are written for the driver - they name the ignition, or the
                // OBDLink app holding the link. Surfacing them verbatim is the point.
                Log.w(TAG, "Connect failed", e)
                teardown(e.message ?: "Could not connect to the adapter.")
                TelemetryState.setConnection(ConnectionStatus.ERROR)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected connect failure", e)
                teardown("Unexpected error: ${e.message}")
                TelemetryState.setConnection(ConnectionStatus.ERROR)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Starts polling and ticking, and watches for the link going away underneath them.
     *
     * That watch is the point. [Elm327Client.runPollLoop] returns when the transport reports
     * a disconnect - and until now nothing noticed. The tick loop carried on at 80ms against
     * the last values the adapter ever sent, so the gauges held a steady 65 mph and the
     * permanent record kept integrating distance and fuel from a car that was no longer
     * talking. A frozen gauge is at least visible; miles booked against a link that had
     * already gone are not.
     */
    private fun startLoops(elm: Elm327Client) {
        tickJob = scope.launch { runTelemetryLoop(elm) }

        val poll = scope.launch { elm.runPollLoop() }
        pollJob = poll

        // The watcher is a sibling of the poll job, not part of it. Doing this inside the
        // poll job meant the recovery path cancelled the very coroutine it was running in,
        // the moment it reached teardown.
        scope.launch {
            poll.join()
            // Completing rather than being cancelled means the transport went away.
            // Cancellation is the driver pressing Stop, and is not something to chase.
            if (!poll.isCancelled) onLinkLost()
        }
    }

    /**
     * Chases a link that disappeared mid-drive.
     *
     * The trip and the recorder are deliberately left open. A drive does not end because a
     * tunnel happened, and the alternative - closing it and opening another on the far side -
     * turns one commute into two trips with a gap in the middle where the interesting part
     * was.
     *
     * What must stop immediately is the tick loop, for the reason in [startLoops].
     */
    private suspend fun onLinkLost() {
        val address = currentAddress ?: return

        // Two different things end the poll loop and they do not read the same. A socket
        // that closed is a knock to the adapter or a phone that walked out of range; an
        // adapter that stopped answering with the socket still open is almost always the
        // ignition having gone off. Saying "lost the adapter" for the second one sends
        // someone out to check a connector that is fine.
        val silent = client?.wentSilent == true
        tickJob?.cancelAndJoin()
        tickJob = null
        runCatching { client?.disconnect() }
        client = null

        // An adapter that stops answering with the socket still open is almost always the
        // ignition having gone off - and an ignition-off is not a link to chase. The engine
        // watchdog usually gets there first with a clean save; this catches the case where
        // the socket died before RPM could be read falling to zero.
        if (silent) {
            teardown("Engine off - trip saved")
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }

        TelemetryState.setConnection(ConnectionStatus.RECONNECTING)

        val droppedAt = System.currentTimeMillis()
        var attempt = 0

        while (currentCoroutineContext().isActive) {
            val elapsed = System.currentTimeMillis() - droppedAt
            if (!ReconnectPolicy.shouldRetry(elapsed)) break

            attempt++
            val wait = ReconnectPolicy.delayMs(attempt)
            val message = (if (silent) "The adapter stopped answering" else "Lost the adapter") +
                " - trying again in " + (wait / 1000) + "s"
            TelemetryState.setStatusMessage(message)
            updateNotification(message)
            delay(wait)

            val transport = BluetoothClassicTransport(applicationContext, address)
            val elm = Elm327Client(transport)

            val reconnected = runCatching {
                elm.connect { TelemetryState.setStatusMessage(it) }
                true
            }.getOrElse {
                Log.w(TAG, "Reconnect attempt $attempt failed", it)
                runCatching { elm.disconnect() }
                false
            }

            if (reconnected) {
                client = elm
                TelemetryState.setResolvedPids(
                    ResolvedPids(elm.lambdaPid, elm.preCatPid, elm.outsideAirPid)
                )
                TelemetryState.setConnection(ConnectionStatus.CONNECTED)
                TelemetryState.setStatusMessage("Back on the adapter - the drive continued")
                updateNotification("Logging")

                scope.launch {
                    launch { elm.data.collect { TelemetryState.setData(it) } }
                    launch { elm.protocolLog.collect { TelemetryState.setProtocolLog(it) } }
                }
                // Not resetTrip: the drive never ended.
                startLoops(elm)
                return
            }
        }

        teardown(
            if (silent) {
                "The adapter stopped answering, which usually means the ignition is off. " +
                    "The drive has been saved."
            } else {
                ReconnectPolicy.gaveUpMessage()
            }
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Runs a diagnostic scan through the same command queue the gauges use.
     *
     * That sharing is the point: an ELM327 has one command in flight and no way to label
     * which reply belongs to which request, so a scan opening its own conversation beside
     * the poll loop is the collision that used to freeze every gauge on its last good
     * value. The gauges simply slow down while this runs.
     */
    private fun scanForCodes() {
        val elm = client
        if (elm == null) {
            TelemetryState.setStatusMessage("Connect to the adapter before scanning.")
            return
        }
        scope.launch {
            TelemetryState.setScanning(true)
            try {
                TelemetryState.setDtcReport(DtcScanner(elm).performFullScan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "DTC scan failed", e)
                TelemetryState.setStatusMessage("Scan failed: " + e.message)
            } finally {
                TelemetryState.setScanning(false)
            }
        }
    }

    /**
     * Mode 04.
     *
     * Also wipes the readiness monitors, so the car fails an emissions test until it has
     * driven a full drive cycle. The screen says so before offering the button; this just
     * carries it out and re-scans, so what is shown afterwards is what the ECU reports
     * rather than what the app assumed happened.
     */
    private fun clearCodes() {
        val elm = client ?: return
        scope.launch {
            TelemetryState.setScanning(true)
            try {
                val scanner = DtcScanner(elm)
                scanner.clearAllCodes()
                TelemetryState.setDtcReport(scanner.performFullScan())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Clearing codes failed", e)
                TelemetryState.setStatusMessage("Could not clear codes: " + e.message)
            } finally {
                TelemetryState.setScanning(false)
            }
        }
    }

    /**
     * Records a fill the app did not see.
     *
     * A fill is normally spotted on its own, from the level rising. This is for the one that
     * is too small to look like a fill, or that happened while something else was using the
     * adapter. It uses the level the car is reporting now, so it is worth doing with the
     * ignition on rather than from the driveway.
     */
    private fun markFilled() {
        val level = TelemetryState.metrics.value.fuelLevelPercent
        if (level == null) {
            TelemetryState.setStatusMessage("No tank level from the car, so there is nothing to reset.")
            return
        }
        manager.tank.markFilled(level)
        TelemetryState.setStatusMessage("Started a new tank at " + level.toInt() + "%")
    }

    /**
     * Starts the oil interval again at 100%.
     *
     * Routed through the service rather than written straight to preferences from the screen,
     * which is the opposite of how the fuel blend is handled and for a concrete reason: a
     * running OilLifeEngine holds the profile in memory and writes it back every thirty
     * seconds. A reset that only touched the file would be quietly overwritten by the engine's
     * next save, with the screen showing 100% until something reloaded it.
     */
    private fun resetOilLife() {
        TelemetryState.setOil(manager.oilLife.resetOilLife())
        TelemetryState.setStatusMessage("Oil life reset to 100%")
    }

    /**
     * Stops the service if it was only started to carry out a one-off.
     *
     * A reset arriving with no adapter connected starts this service purely to reach the
     * model that owns the record. Leaving it running afterwards would keep a started service
     * alive with no notification and nothing to do.
     */
    private fun stopIfIdle() {
        if (client == null && connectJob?.isActive != true) {
            stopSelf()
        }
    }

    /**
     * Drives the models from the bench instead of from the car.
     *
     * The whole stack downstream of the adapter is the same code the car drives: the same
     * manager, the same models, the same screen. What differs is the one thing that must -
     * the status is SIMULATING, and IntegrationRules refuses to let that near the permanent
     * record however long it runs.
     */
    private fun simulate() {
        connectJob?.cancel()
        connectJob = scope.launch {
            TelemetryState.reset()
            TelemetryState.setConnection(ConnectionStatus.SIMULATING)
            TelemetryState.setStatusMessage("Simulated drive — the lifetime record is not touched")

            val sim = CivicSimulatorEngine()
            manager.resetTrip()
            recorder.start(System.currentTimeMillis(), simulated = true)

            var last = System.currentTimeMillis()

            while (currentCoroutineContext().isActive) {
                delay(CivicSpecs.TELEMETRY_TICK_MS.toLong())

                val now = System.currentTimeMillis()
                val dtSec = (now - last) / 1000.0
                last = now

                val raw = sim.tick(dtSec)
                TelemetryState.setData(raw)

                val snapshot = manager.also { it.shiftMode = TelemetryState.shiftMode.value }.tick(raw, dtSec, ConnectionStatus.SIMULATING)
                TelemetryState.setMetrics(snapshot.metrics)
                TelemetryState.setTrip(snapshot.trip)
                TelemetryState.setOil(snapshot.oil)
                recorder.record(now, snapshot.metrics, snapshot.trip)
            }
        }
    }

    /**
     * The tick loop, separate from the poll loop on purpose.
     *
     * Polling is paced by how fast the adapter can answer; the models are integrated on a
     * fixed 80ms step because that is what the rolling-MPG window and the wear accumulators
     * are calibrated against. Tying them together would make the physics depend on Bluetooth
     * latency.
     *
     * It reads whatever the poll loop last wrote rather than waiting for fresh values, which
     * is also what makes an unanswered PID harmless - the previous reading is still there.
     */
    private suspend fun runTelemetryLoop(elm: Elm327Client) {
        var last = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
            delay(CivicSpecs.TELEMETRY_TICK_MS.toLong())

            val now = System.currentTimeMillis()
            val dtSec = (now - last) / 1000.0
            last = now

            val snapshot = manager.also { it.shiftMode = TelemetryState.shiftMode.value }.tick(elm.data.value, dtSec, TelemetryState.connection.value)
            TelemetryState.setMetrics(snapshot.metrics)
            TelemetryState.setTrip(snapshot.trip)
            TelemetryState.setOil(snapshot.oil)
            TelemetryState.setLifetime(snapshot.lifetime)
            recorder.record(now, snapshot.metrics, snapshot.trip)
        }
    }

    private suspend fun teardown(message: String) {
        tickJob?.cancelAndJoin()
        tickJob = null
        pollJob?.cancelAndJoin()
        pollJob = null
        runCatching { client?.disconnect() }
        client = null

        // Saves are debounced to once per thirty seconds, so the end of a drive is exactly
        // when the unwritten remainder is worth keeping.
        if (::manager.isInitialized) {
            // Before the flush: this is what counts a short cold trip, and the flush is what
            // writes the result out.
            runCatching { manager.endDrive() }
            runCatching { manager.flush() }
            TelemetryState.setLifetime(manager.getLifetimeStats())
            if (::recorder.isInitialized) {
                runCatching { recorder.finish(System.currentTimeMillis(), manager.getTrip()) }
            }
        }
        TelemetryState.setStatusMessage(message)
        if (TelemetryState.connection.value != ConnectionStatus.ERROR) {
            TelemetryState.setConnection(ConnectionStatus.DISCONNECTED)
        }
    }

    override fun onDestroy() {
        // destroy, not hide: this is the one place the host is genuinely finished with, and
        // the lifecycle it owns has to be closed so anything observing through it lets go.
        overlay?.destroy()
        overlay = null
        serviceJob.cancel()
        super.onDestroy()
    }

    // ── Notification ─────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Telemetry logging",
            // Low: this notification exists because a foreground service must have one, not
            // because it is news. It should sit silently in the shade while you drive.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the app is connected to the OBD-II adapter."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Alerts are news - a battery being pulled down is worth interrupting for.
        val alerts = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Warnings worth knowing about while the car is plugged in."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(alerts)
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // The fill-up quick action. A fill is normally spotted from the level rising, but a
        // small top-up or one that happened while the adapter was busy needs saying - and
        // doing it from the shade, standing at the pump, is the least friction there is.
        val markFilled = PendingIntent.getService(
            this,
            1,
            Intent(this, TelemetryService::class.java).apply { action = ACTION_MARK_FILLED },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Civic 5MT")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_telemetry)
            .setContentIntent(openApp)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Filled up",
                    markFilled,
                ).build()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundWithStatus(status: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(status),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(status))
        }
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    companion object {
        private const val TAG = "TelemetryService"
        private const val CHANNEL_ID = "telemetry"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.shieldrj.civic5mt.CONNECT"
        const val ACTION_SIMULATE = "com.shieldrj.civic5mt.SIMULATE"
        const val ACTION_SCAN_DTC = "com.shieldrj.civic5mt.SCAN_DTC"
        const val ACTION_CLEAR_DTC = "com.shieldrj.civic5mt.CLEAR_DTC"
        const val ACTION_RESET_OIL = "com.shieldrj.civic5mt.RESET_OIL"
        const val ACTION_MARK_FILLED = "com.shieldrj.civic5mt.MARK_FILLED"
        const val ACTION_DISCONNECT = "com.shieldrj.civic5mt.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "deviceAddress"

        /** Deep-link extras: which screen an outside tap should land on. */
        const val EXTRA_OPEN_SCREEN = "open_screen"
        const val SCREEN_FUEL = "fuel"

        private const val ALERT_CHANNEL_ID = "alerts"
        private const val VOLTAGE_NOTIFICATION_ID = 2

        /** Above a walking pace counts as driving, for HUD visibility. */
        private const val MOVING_SPEED_MPH = 3.0

        /** At or above this, the engine is running. Idle on the R18Z1 sits around 700. */
        private const val ENGINE_RUNNING_RPM = 250.0

        /** RPM at zero this long, while stationary, is an ignition-off rather than a misread. */
        private const val ENGINE_OFF_CONFIRM_MS = 5_000L

        /** Parked this long, the HUD stops covering the map. */
        private const val PARKED_HIDE_AFTER_MS = 10L * 60_000L

        /** Below this, sustained, the battery is genuinely draining rather than resting. */
        private const val LOW_VOLTAGE = 12.0

        /** A reading under this is noise or a dead sensor, not a battery state. */
        private const val MIN_VALID_VOLTAGE = 6.0

        private const val LOW_VOLTAGE_SUSTAIN_MS = 60_000L

        private const val WIDGET_PUSH_INTERVAL_MS = 30_000L

        fun connect(context: Context, address: String) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }
            context.startForegroundService(intent)
        }

        fun simulate(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_SIMULATE
            }
            context.startForegroundService(intent)
        }

        fun scanForCodes(context: Context) {
            context.startService(
                Intent(context, TelemetryService::class.java).apply { action = ACTION_SCAN_DTC }
            )
        }

        fun clearCodes(context: Context) {
            context.startService(
                Intent(context, TelemetryService::class.java).apply { action = ACTION_CLEAR_DTC }
            )
        }

        fun markFilled(context: Context) {
            context.startService(
                Intent(context, TelemetryService::class.java).apply { action = ACTION_MARK_FILLED }
            )
        }

        fun resetOilLife(context: Context) {
            context.startService(
                Intent(context, TelemetryService::class.java).apply { action = ACTION_RESET_OIL }
            )
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}

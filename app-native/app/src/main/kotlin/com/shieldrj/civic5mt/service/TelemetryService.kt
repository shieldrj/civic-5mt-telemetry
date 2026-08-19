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
import com.shieldrj.civic5mt.core.CivicSpecs
import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.Elm327Client
import com.shieldrj.civic5mt.core.ObdTransportError
import com.shieldrj.civic5mt.core.OilLifeEngine
import com.shieldrj.civic5mt.core.TelemetryManager
import com.shieldrj.civic5mt.transport.BluetoothClassicTransport
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
import kotlinx.coroutines.plus

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
     * The models, and the trip and lifetime figures they accumulate into.
     *
     * Held by the service rather than rebuilt per connection, so a Bluetooth drop in a tunnel
     * does not end the trip or lose what the oil model has been accruing.
     */
    private lateinit var manager: TelemetryManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Before anything can write to storage. The rescued records are the only copy of
        // figures that accumulated over real driving, and an import that runs after the first
        // write has nothing left to import into.
        importRescuedRecordsOnce(applicationContext)?.let { Log.i(TAG, it) }

        manager = TelemetryManager(
            lifetimeStore = PrefsLifetimeStore(applicationContext),
            oilLife = OilLifeEngine(PrefsOilProfileStore(applicationContext)),
        ).apply {
            setFuelBlend(loadFuelBlend(applicationContext))
        }
        TelemetryState.setLifetime(manager.getLifetimeStats())
        TelemetryState.setOil(manager.oilLife.getProfile())
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

            ACTION_DISCONNECT -> {
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
        connectJob?.cancel()
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

                pollJob = launch { elm.runPollLoop() }
                tickJob = launch { runTelemetryLoop(elm) }
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

            val snapshot = manager.tick(elm.data.value, dtSec, TelemetryState.connection.value)
            TelemetryState.setMetrics(snapshot.metrics)
            TelemetryState.setTrip(snapshot.trip)
            TelemetryState.setOil(snapshot.oil)
            TelemetryState.setLifetime(snapshot.lifetime)
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
            runCatching { manager.flush() }
            TelemetryState.setLifetime(manager.getLifetimeStats())
        }
        TelemetryState.setStatusMessage(message)
        if (TelemetryState.connection.value != ConnectionStatus.ERROR) {
            TelemetryState.setConnection(ConnectionStatus.DISCONNECTED)
        }
    }

    override fun onDestroy() {
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
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Civic 5MT")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_stat_telemetry)
            .setContentIntent(openApp)
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
        const val ACTION_DISCONNECT = "com.shieldrj.civic5mt.DISCONNECT"
        const val EXTRA_DEVICE_ADDRESS = "deviceAddress"

        fun connect(context: Context, address: String) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }
            context.startForegroundService(intent)
        }

        fun disconnect(context: Context) {
            val intent = Intent(context, TelemetryService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            context.startService(intent)
        }
    }
}

package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.LifetimeStats
import com.shieldrj.civic5mt.core.LiveMetrics
import com.shieldrj.civic5mt.core.OilLifeProfile
import com.shieldrj.civic5mt.core.ProtocolLogEntry
import com.shieldrj.civic5mt.core.RawObdData
import com.shieldrj.civic5mt.core.ShiftMode
import com.shieldrj.civic5mt.core.TripAnalytics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the car is currently saying, readable from anywhere in the process.
 *
 * A singleton rather than something handed out by a service binder, because of who the
 * readers are going to be. The Compose UI could bind. A floating HUD drawn over Google Maps
 * has no Activity behind it at all, and a home-screen widget or an Android Auto surface has
 * even less - so the readable surface cannot be tied to a screen being present. The service
 * is the only writer; everything else observes.
 *
 * This is also why the telemetry pipeline lives in the service rather than in a ViewModel.
 * Anything owned by the UI dies with the UI, and the entire reason for going native was that
 * logging has to continue with the screen off.
 */
object TelemetryState {

    private val _connection = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    /** What the adapter last said, before any physics is applied to it. */
    private val _data = MutableStateFlow(RawObdData())
    val data: StateFlow<RawObdData> = _data.asStateFlow()

    /** What the gauges read: the raw PIDs with the models applied. */
    private val _metrics = MutableStateFlow(LiveMetrics())
    val metrics: StateFlow<LiveMetrics> = _metrics.asStateFlow()

    private val _trip = MutableStateFlow(TripAnalytics())
    val trip: StateFlow<TripAnalytics> = _trip.asStateFlow()

    private val _oil = MutableStateFlow<OilLifeProfile?>(null)
    val oil: StateFlow<OilLifeProfile?> = _oil.asStateFlow()

    private val _lifetime = MutableStateFlow(LifetimeStats())
    val lifetime: StateFlow<LifetimeStats> = _lifetime.asStateFlow()

    /** Human-readable progress during the handshake, and the failure text if it fails. */
    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _protocolLog = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val protocolLog: StateFlow<List<ProtocolLogEntry>> = _protocolLog.asStateFlow()

    /**
     * Economy or power shift points.
     *
     * Lives here rather than in the service because it is a preference the driver sets, and
     * it has to survive the service being stopped and started between drives.
     */
    private val _shiftMode = MutableStateFlow(ShiftMode.ECO)
    val shiftMode: StateFlow<ShiftMode> = _shiftMode.asStateFlow()

    fun toggleShiftMode() {
        _shiftMode.value =
            if (_shiftMode.value == ShiftMode.ECO) ShiftMode.POWER else ShiftMode.ECO
    }

    /** Whether the driver wants the heads-up display over whatever else is on screen. */
    private val _overlayEnabled = MutableStateFlow(false)
    val overlayEnabled: StateFlow<Boolean> = _overlayEnabled.asStateFlow()

    fun setOverlayEnabled(enabled: Boolean) {
        _overlayEnabled.value = enabled
    }

    /** Which PID each ambiguous reading resolved to, once the bitmaps have been read. */
    private val _resolvedPids = MutableStateFlow(ResolvedPids())
    val resolvedPids: StateFlow<ResolvedPids> = _resolvedPids.asStateFlow()

    internal fun setConnection(status: ConnectionStatus) {
        _connection.value = status
    }

    internal fun setData(data: RawObdData) {
        _data.value = data
    }

    internal fun setMetrics(metrics: LiveMetrics) {
        _metrics.value = metrics
    }

    internal fun setTrip(trip: TripAnalytics) {
        _trip.value = trip
    }

    internal fun setOil(oil: OilLifeProfile) {
        _oil.value = oil
    }

    internal fun setLifetime(stats: LifetimeStats) {
        _lifetime.value = stats
    }

    internal fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }

    internal fun setProtocolLog(entries: List<ProtocolLogEntry>) {
        _protocolLog.value = entries
    }

    internal fun setResolvedPids(pids: ResolvedPids) {
        _resolvedPids.value = pids
    }

    internal fun reset() {
        _data.value = RawObdData()
        _metrics.value = LiveMetrics()
        _resolvedPids.value = ResolvedPids()
        // Trip, oil and the lifetime record deliberately survive a reconnect. A dropped
        // Bluetooth link in a tunnel is not the end of a drive.
    }
}

/**
 * Which PID supplies each reading that more than one PID can supply.
 *
 * Surfaced rather than kept private because it is the answer to "why is this gauge blank" -
 * on this car lambda comes from 34 and outside air falls back to intake air, and a screen
 * that cannot say so is the screen that used to show a fabricated 22 °C.
 */
data class ResolvedPids(
    val lambda: Int? = null,
    val preCat: Int? = null,
    val outsideAir: Int? = null,
)

package com.shieldrj.civic5mt.service

import com.shieldrj.civic5mt.core.ConnectionStatus
import com.shieldrj.civic5mt.core.ProtocolLogEntry
import com.shieldrj.civic5mt.core.RawObdData
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

    private val _data = MutableStateFlow(RawObdData())
    val data: StateFlow<RawObdData> = _data.asStateFlow()

    /** Human-readable progress during the handshake, and the failure text if it fails. */
    private val _statusMessage = MutableStateFlow("Not connected")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _protocolLog = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val protocolLog: StateFlow<List<ProtocolLogEntry>> = _protocolLog.asStateFlow()

    /** Which PID each ambiguous reading resolved to, once the bitmaps have been read. */
    private val _resolvedPids = MutableStateFlow(ResolvedPids())
    val resolvedPids: StateFlow<ResolvedPids> = _resolvedPids.asStateFlow()

    internal fun setConnection(status: ConnectionStatus) {
        _connection.value = status
    }

    internal fun setData(data: RawObdData) {
        _data.value = data
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
        _resolvedPids.value = ResolvedPids()
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

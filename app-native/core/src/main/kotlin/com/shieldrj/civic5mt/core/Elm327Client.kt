package com.shieldrj.civic5mt.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Command timeouts.
 *
 * These are not arbitrary. A reply that arrives after its own command has timed out is
 * indistinguishable, on a stream with no request IDs, from the next command's reply - so a
 * timeout that fires early does not merely lose one answer, it shifts every later answer one
 * command out of step and the parsers stop recognising anything. Generous is cheap; early is
 * silently fatal. See [Elm327Client.drainLine] for the other half of that defence.
 */
object ObdTimeouts {
    /** AT Z reboots the STN chip in the MX+, which takes appreciably longer than a clone. */
    const val RESET_MS = 6000L

    /** Configuration ATs answer from RAM and are quick, but not free over RFCOMM. */
    const val AT_INIT_MS = 2500L

    /** The first real request has to bring the CAN bus up, the slowest thing here. */
    const val BUS_PROBE_MS = 6000L

    /** Steady-state PID polling, once the bus is known good. */
    const val PID_MS = 1200L

    /**
     * How long every request may go unanswered before the adapter counts as gone.
     *
     * A socket that closes is easy - the transport says so. This is the other failure, and
     * the one that actually happens: the ignition goes off, the MX+ drops into low power,
     * and the socket stays open while every request times out. The poll loop had no opinion
     * about that and would sit there answering nothing indefinitely.
     *
     * Measured rather than guessed. Across two real drives - 2,733 and 3,460 samples over
     * 108 minutes - the longest gap between successful reads was 1.3 seconds. A third
     * session, left connected in a car park, ran two hours with gaps up to 216 seconds and
     * 23 of them over a minute. Thirty seconds is far outside anything a running engine
     * produces and well inside what a sleeping adapter does.
     */
    const val SILENT_ADAPTER_MS = 30_000L
}

/** One command and whatever came back, for the on-screen adapter log. */
data class ProtocolLogEntry(
    val atMillis: Long,
    val cmd: String,
    val resp: String,
)

/**
 * STN/ELM327 protocol client for the 2013 Civic's ISO 15765-4 CAN bus.
 *
 * Transport-agnostic on purpose: everything here is plain Kotlin, so the handshake, the
 * command queue and the poll loop can be driven by a fake transport in a unit test. In the
 * TypeScript build this whole layer was unreachable without a Bluetooth stack and had no
 * tests at all, which is unfortunate given that its two worst failure modes - a desynced
 * command stream and a bus that never came up - both present as a healthy connection with
 * gauges sitting on their defaults.
 */
class Elm327Client(
    private val transport: ObdTransport,
    private val clock: MillisClock = SystemMillisClock,
) {
    private val _data = MutableStateFlow(RawObdData())
    val data: StateFlow<RawObdData> = _data.asStateFlow()

    private val _log = MutableStateFlow<List<ProtocolLogEntry>>(emptyList())
    val protocolLog: StateFlow<List<ProtocolLogEntry>> = _log.asStateFlow()

    /**
     * One command in flight at a time.
     *
     * The ELM327 has no way to label which reply belongs to which request, so two callers
     * writing at once do not merely interleave - they permanently swap replies, and the
     * adapter aborts the half-written one with STOPPED. That is exactly what happened once:
     * the DTC scanner shares this client with the poll loop, its 0101 collided with a PID
     * request mid-reply, and every gauge froze on its last good value while the connection
     * still looked healthy. A mutex here rather than discipline in the callers means a
     * future third caller cannot reintroduce it by forgetting.
     */
    private val commandLock = Mutex()

    /** Guards everything the transport's reader thread touches. */
    private val incomingLock = Any()
    private val buffer = StringBuilder()
    private var pending: CompletableDeferred<String>? = null

    /**
     * Total characters seen, ever. [drainLine] watches this rather than the buffer length,
     * because the buffer is emptied on every '>' - including replies nobody is waiting for,
     * which are precisely the ones being drained.
     */
    @Volatile
    private var charsReceived: Long = 0

    /**
     * Set when a command times out. The next command must not go out until the line has
     * fallen quiet, or the late reply lands on it and every answer after that is off by one.
     */
    @Volatile
    private var needsDrain = false

    @Volatile
    var isPolling: Boolean = false

    /**
     * Whether polling stopped because nothing was answering, rather than because the socket
     * went away. Both end the drive the same way; they do not read the same to a driver.
     */
    var wentSilent: Boolean = false
        private set

    /** When the adapter last actually replied to anything. */
    private var lastReplyAt: Long = 0L
        private set

    /** PIDs the car reported via 0100/0120/... An empty set means "unknown, poll everything". */
    private val supportedPids = mutableSetOf<Int>()

    /**
     * Which PID supplies each reading that more than one PID can supply, decided once from
     * the support bitmaps and resolved through the shared candidate lists in PidCatalog - so
     * the discovery screen's "already drives a gauge" tick cannot disagree with what is
     * actually polled.
     */
    var lambdaPid: Int? = null
        private set
    var preCatPid: Int? = null
        private set
    var outsideAirPid: Int? = null
        private set

    /**
     * Verbose for the handshake and the first full poll cycle, then failures only. The first
     * cycle is the part worth seeing: it shows every PID's actual reply once, which is what
     * says whether a gauge is stuck because the car said nothing or because the reply was
     * not understood. Failures-only hid exactly that - a PID answering NO DATA is a
     * non-empty reply, so nothing was recorded at all.
     */
    private var verboseLog = true
    private val loggedOnce = mutableSetOf<String>()

    init {
        transport.setDataHandler(::handleIncoming)
        transport.setDisconnectHandler { isPolling = false }
    }

    // ── Incoming bytes ───────────────────────────────────────────────────────────

    /**
     * Called from the transport's reader thread with whatever arrived.
     *
     * ELM327/STN terminates every response with the '>' prompt, which is the only framing
     * there is. A reply arriving with nobody waiting is a late answer to a command that has
     * already timed out; dropping it here is the point, and [drainLine] is what stops the
     * next command going out until those have stopped arriving.
     */
    fun handleIncoming(chunk: String) {
        val toComplete: CompletableDeferred<String>?
        val response: String

        synchronized(incomingLock) {
            buffer.append(chunk)
            charsReceived += chunk.length
            if (buffer.indexOf(">") < 0) return

            response = buffer.toString().trim()
            buffer.setLength(0)
            toComplete = pending
            pending = null
        }

        if (toComplete != null) toComplete.complete(response) else log("(late)", response)
    }

    // ── Commands ─────────────────────────────────────────────────────────────────

    /** Queues a command behind every command already issued, and returns its reply. */
    suspend fun sendCommand(cmd: String, timeoutMs: Long = ObdTimeouts.PID_MS): String =
        commandLock.withLock { execute(cmd, timeoutMs) }

    private suspend fun execute(cmd: String, timeoutMs: Long): String {
        // A previous command timed out, so its reply may still be in flight. Let it land and
        // throw it away before putting a new command on the wire.
        if (needsDrain) drainLine()

        val deferred = CompletableDeferred<String>()
        synchronized(incomingLock) {
            buffer.setLength(0)
            pending = deferred
        }

        try {
            transport.write(cmd.trim() + "\r")
        } catch (e: Throwable) {
            synchronized(incomingLock) { pending = null }
            throw e
        }

        val response = withTimeoutOrNull(timeoutMs) { deferred.await() }

        if (response == null) {
            synchronized(incomingLock) { pending = null }
            needsDrain = true
            // Empty rather than an exception: one timed-out PID must not tear down the poll
            // loop, and the parsers treat "" as "no answer, keep the previous reading".
            log(cmd, "")
            return ""
        }

        lastReplyAt = clock.nowMillis()

        // Polling runs several commands a second, so logging all of it would bury the
        // handshake within seconds. Each PID is recorded once, the first time it is asked:
        // a complete picture of what this car answers, in about sixteen lines, that does not
        // grow. Timeouts are always recorded, whenever they happen.
        if (verboseLog || !loggedOnce.contains(cmd)) {
            loggedOnce.add(cmd)
            log(cmd, response)
        }
        return response
    }

    /**
     * Waits for the adapter to stop talking, then discards whatever it said.
     *
     * Without this, the reply the adapter was still composing arrives midway through the
     * next command and satisfies its waiter, leaving every subsequent response one command
     * behind - which parses as nothing at all, so the gauges sit on their defaults while the
     * app reports a healthy connection.
     *
     * Bounded by rounds rather than wall-clock so it behaves identically under a test's
     * virtual clock.
     */
    private suspend fun drainLine(quietMs: Long = 250, maxMs: Long = 4000) {
        val maxRounds = (maxMs / quietMs).toInt().coerceAtLeast(1)
        repeat(maxRounds) {
            val before = charsReceived
            delay(quietMs)
            if (charsReceived == before) {
                finishDrain()
                return
            }
        }
        finishDrain()
    }

    private fun finishDrain() {
        synchronized(incomingLock) { buffer.setLength(0) }
        needsDrain = false
    }

    private fun log(cmd: String, resp: String) {
        val entry = ProtocolLogEntry(clock.nowMillis(), cmd, resp.ifEmpty { "(no reply)" })
        _log.value = (_log.value + entry).takeLast(60)
    }

    // ── Connect & handshake ──────────────────────────────────────────────────────

    suspend fun connect(onStatus: ((String) -> Unit)? = null) {
        _log.value = emptyList()
        loggedOnce.clear()
        verboseLog = true
        needsDrain = false
        synchronized(incomingLock) { buffer.setLength(0) }

        transport.connect()

        onStatus?.invoke("Initializing ISO 15765-4 CAN protocol...")
        initializeElm327(onStatus)

        onStatus?.invoke("Connected & streaming telemetry")
        verboseLog = false
    }

    private suspend fun initializeElm327(onStatus: ((String) -> Unit)?) {
        val banner = sendCommand("AT Z", ObdTimeouts.RESET_MS)
        if (banner.isEmpty()) {
            throw ObdTransportError(
                "The adapter accepted a Bluetooth connection but never answered the reset " +
                    "command. That is usually another app still holding the link — close the " +
                    "OBDLink app completely (swipe it away, do not just background it) and try again."
            )
        }

        sendCommand("AT E0", ObdTimeouts.AT_INIT_MS) // Echo off
        sendCommand("AT L0", ObdTimeouts.AT_INIT_MS) // Linefeeds off
        sendCommand("AT S0", ObdTimeouts.AT_INIT_MS) // Spaces off
        sendCommand("AT H0", ObdTimeouts.AT_INIT_MS) // Headers off

        // Protocol 7 is ISO 15765-4 CAN with 29-bit IDs. This car answers on 7, not the 6
        // (11-bit) you would expect of a 2013 Civic - measured, via AT DPN reporting A7 after
        // an auto-detect. Protocol 6 returns NO DATA here, so 7 is tried first and 6 second.
        onStatus?.invoke("Waking the CAN bus...")
        for (proto in listOf("7", "6")) {
            sendCommand("AT SP $proto", ObdTimeouts.AT_INIT_MS)
            if (probeBus()) {
                loadSupportedPids()
                return
            }
        }

        // Neither fixed protocol answered. Auto-detect is slower, but if the car replies the
        // log says which protocol worked and the list above can be corrected properly.
        onStatus?.invoke("Fixed protocols got no answer — trying auto-detect...")
        sendCommand("AT SP 0", ObdTimeouts.AT_INIT_MS)
        if (probeBus()) {
            sendCommand("AT DPN", ObdTimeouts.AT_INIT_MS) // Logs the protocol that worked
            loadSupportedPids()
            return
        }

        throw ObdTransportError(
            "The adapter is connected and answering, but the car is not. Turn the ignition to " +
                "ON / II — the ECU powers down otherwise, and the adapter stays awake on its own, " +
                "which is why it still pairs. Check the adapter log for what it replied."
        )
    }

    /** Asks the ECU which PIDs it supports: the cheapest possible proof the bus is up. */
    private suspend fun probeBus(): Boolean {
        repeat(3) {
            if (PidParsers.isBusAlive(sendCommand("0100", ObdTimeouts.BUS_PROBE_MS))) return true
        }
        return false
    }

    /**
     * Reads the supported-PID bitmaps so polling can skip what this car does not have.
     *
     * Worth the extra commands: a 2013 Civic LX has no PID 14, and asking for it every cycle
     * costs a real round-trip to be told NO DATA. An empty set means the bitmaps could not be
     * read, and everything is polled as before rather than nothing.
     */
    suspend fun loadSupportedPids() {
        supportedPids.clear()
        val banks = listOf(
            0x00 to "0100", 0x20 to "0120", 0x40 to "0140", 0x60 to "0160",
            0x80 to "0180", 0xa0 to "01A0", 0xc0 to "01C0",
        )
        for ((base, cmd) in banks) {
            val resp = sendCommand(cmd, ObdTimeouts.AT_INIT_MS)
            val bank = PidParsers.supportBitmap(resp, base, "41" + cmd.substring(2)) ?: break
            supportedPids.addAll(bank.pids)
            if (!bank.hasNextBank) break
        }
        resolveOptionalPids()
    }

    fun supportedPidSnapshot(): Set<Int> = supportedPids.toSet()

    private fun resolveOptionalPids() {
        lambdaPid = choosePid(LAMBDA_PID_CANDIDATES, supportedPids)
        preCatPid = choosePid(PRE_CAT_PID_CANDIDATES, supportedPids)
        outsideAirPid = choosePid(OUTSIDE_AIR_PID_CANDIDATES, supportedPids)

        fun name(pid: Int?) = pid?.toString(16)?.uppercase()?.padStart(2, '0') ?: "none"
        log(
            "PID-SELECT",
            "lambda=${name(lambdaPid)} pre-cat=${name(preCatPid)} outside-air=${name(outsideAirPid)}",
        )
    }

    /** False only when the bitmaps were read and positively say the car lacks this PID. */
    private fun isPidSupported(cmd: String): Boolean {
        if (supportedPids.isEmpty()) return true
        val pid = cmd.substring(2).toIntOrNull(16) ?: return true
        return supportedPids.contains(pid)
    }

    /**
     * One PID request. Skips PIDs the car said it does not have and returns "" for them, so
     * the parsers leave the previous reading alone.
     */
    private suspend fun pollPid(cmd: String): String =
        if (isPidSupported(cmd)) sendCommand(cmd) else ""

    // ── Polling ──────────────────────────────────────────────────────────────────

    /**
     * The steady-state loop, tiered by how fast each reading actually changes.
     *
     * Runs until cancelled or [stopPolling]. Every reading goes through [update], which only
     * replaces a field when the car actually answered - a null parse leaves the previous
     * value in place rather than writing a default over it.
     */
    suspend fun runPollLoop(idleDelayMs: Long = 15) {
        isPolling = true
        wentSilent = false
        lastReplyAt = clock.nowMillis()
        var cycle = 0L

        while (isPolling) {
            // High-frequency: what the driver is watching move.
            update { it.copy(rpm = PidParsers.rpm(pollPid("010C")) ?: it.rpm) }
            update { it.copy(speedKmh = PidParsers.speedKmh(pollPid("010D")) ?: it.speedKmh) }
            update { it.copy(maf = PidParsers.maf(pollPid("0110")) ?: it.maf) }
            update { it.copy(throttlePos = PidParsers.throttlePercent(pollPid("0111")) ?: it.throttlePos) }

            // O2 readings oscillate rapidly - a slow poll aliases them into a flat line,
            // which defeats the point of showing a live trace. Which PID that is depends on
            // the car: a narrowband front sensor answers 14 with a voltage, a wide-range one
            // answers 34 with lambda and current. This Civic has only 34, and asking it for
            // 14 forever was why "Pre-catalyst" showed a constant 0.45 V from nowhere.
            when (preCatPid) {
                0x34 -> {
                    // One request covers both jobs: 34 is the pre-catalyst sensor *and* the
                    // wideband the fuel model needs, so this keeps lambda at this tier's rate.
                    val reading = PidParsers.wideRangeO2(pollPid("0134"))
                    if (reading != null) {
                        update {
                            it.copy(
                                o2Sensor1Lambda = reading.lambda,
                                lambda = reading.lambda,
                                o2Sensor1CurrentMa = reading.currentMa ?: it.o2Sensor1CurrentMa,
                            )
                        }
                    }
                }
                0x14 -> update {
                    it.copy(o2Sensor1Voltage = PidParsers.o2Sensor1Voltage(pollPid("0114")) ?: it.o2Sensor1Voltage)
                }
            }

            update { it.copy(o2Sensor2Voltage = PidParsers.o2Sensor2Voltage(pollPid("0115")) ?: it.o2Sensor2Voltage) }

            // Medium tier.
            if (cycle % 6 == 0L) {
                update { it.copy(coolantC = PidParsers.coolantC(pollPid("0105")) ?: it.coolantC) }
                update { it.copy(engineLoad = PidParsers.engineLoad(pollPid("0104")) ?: it.engineLoad) }
                update { it.copy(timingAdvance = PidParsers.timingAdvance(pollPid("010E")) ?: it.timingAdvance) }
                update { it.copy(batteryVoltage = PidParsers.batteryVoltage(pollPid("0142")) ?: it.batteryVoltage) }

                // 46 is outside air; 0F is intake air, a different quantity, and it gets
                // labelled as one. Neither existing leaves the reading null rather than 22 C.
                val airSource = when (outsideAirPid) {
                    0x46 -> OutsideAirSource.AMBIENT
                    0x0f -> OutsideAirSource.INTAKE
                    else -> null
                }
                if (airSource != null) {
                    val cmd = if (airSource == OutsideAirSource.AMBIENT) "0146" else "010F"
                    val value = PidParsers.outsideAirC(pollPid(cmd), airSource)
                    if (value != null) update { it.copy(ambientC = value, ambientSource = airSource) }
                }
            }

            // Slow tier: trims, fuel level, runtime.
            if (cycle % 12 == 0L) {
                update { it.copy(stft = PidParsers.shortTermFuelTrim(pollPid("0106")) ?: it.stft) }
                update { it.copy(ltft = PidParsers.longTermFuelTrim(pollPid("0107")) ?: it.ltft) }

                // Only when lambda is not already arriving with the pre-catalyst read above.
                // On this Civic the two are the same PID, so this does not run at all; a car
                // with a narrowband front sensor plus a separate wideband needs it to.
                if (lambdaPid != null && lambdaPid != preCatPid) {
                    when (lambdaPid) {
                        0x24 -> update { it.copy(lambda = PidParsers.lambdaFromPid24(pollPid("0124")) ?: it.lambda) }
                        0x34 -> {
                            val reading = PidParsers.wideRangeO2(pollPid("0134"))
                            if (reading != null) update { it.copy(lambda = reading.lambda) }
                        }
                    }
                }

                update { it.copy(fuelLevelPercent = PidParsers.fuelLevelPercent(pollPid("012F")) ?: it.fuelLevelPercent) }
                update { it.copy(engineRuntimeSec = PidParsers.engineRuntimeSec(pollPid("011F")) ?: it.engineRuntimeSec) }
            }

            cycle++

            // Checked once a cycle rather than per command: a single timed-out PID is
            // ordinary, and a car that answers eleven of twelve requests is a car that is
            // talking. What ends the loop is nothing answering at all, for long enough that
            // no running engine explains it.
            if (clock.nowMillis() - lastReplyAt >= ObdTimeouts.SILENT_ADAPTER_MS) {
                wentSilent = true
                isPolling = false
            }

            if (idleDelayMs > 0) delay(idleDelayMs)
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    private suspend fun update(transform: suspend (RawObdData) -> RawObdData) {
        _data.value = transform(_data.value)
    }

    suspend fun disconnect() {
        isPolling = false
        synchronized(incomingLock) {
            buffer.setLength(0)
            pending = null
        }
        transport.disconnect()
    }
}

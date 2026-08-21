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

    /** One cycle's readings, plus the fresh rpm the engine-off detector has to see. */
    private class CycleReadings(val snapshot: RawObdData, val freshRpm: Double?)

    private val engineOff = EngineOffDetector()

    /**
     * When 010C and 010D were each last answered, kept apart so the stamp can be the OLDER
     * of the two.
     *
     * Taking the newer would leave the unbounded hole open: on a marginal link 010C can keep
     * answering while 010D times out over and over, so rpm stays fresh and above the
     * integration gate while speed carries forward at a cruise - and distance accrues from a
     * speed nobody measured, for as long as the asymmetry lasts. Both readings gate the
     * permanent record, so the record is only as fresh as the staler of them.
     */
    private var lastRpmAt: Long? = null
    private var lastSpeedAt: Long? = null

    /** Null until both have been answered at least once - an absence, not an old value. */
    private fun motionStamp(): Long? {
        val rpmAt = lastRpmAt ?: return null
        val speedAt = lastSpeedAt ?: return null
        return minOf(rpmAt, speedAt)
    }

    /** Whether the loop has backed off to the engine-off tier. */
    val isEngineOffTier: Boolean get() = engineOff.isEngineOff

    /**
     * The steady-state loop: the fast set every cycle, plus exactly one slot from a rotation.
     *
     * The tiers used to be modulo counters - everything on `cycle % 6` fired together, and on
     * `cycle % 12` fifteen commands went out back to back. That bunching cost twice over: the
     * cycle time alternated between about 84ms and 210ms, and the worst-case gap between two
     * consecutive rpm reads was set by the heavy cycle rather than the ordinary one. Spreading
     * the slow readings one per cycle keeps every cycle the same length, and it is what makes
     * the freshness deadline in [IntegrationRules.MAX_READING_AGE_MS] honest.
     *
     * Every reading still goes through a null check that leaves the previous value in place
     * rather than writing a default over it.
     */
    suspend fun runPollLoop(budgetMs: Long = ObdPacing.CYCLE_BUDGET_MS) {
        isPolling = true
        wentSilent = false
        lastReplyAt = clock.nowMillis()
        engineOff.reset()
        lastRpmAt = null
        lastSpeedAt = null
        var cycle = 0L

        while (isPolling) {
            val cycleStart = clock.nowMillis()
            val wasEngineOff = engineOff.isEngineOff

            val readings: CycleReadings
            if (wasEngineOff) {
                readings = pollEngineOffCycle(_data.value)
            } else {
                readings = pollFullCycle(_data.value, cycle)
                cycle++
            }

            /*
             * One write per cycle, not one per PID.
             *
             * This used to publish on every single reading - about eighty-six emissions a
             * second into a StateFlow that a Compose UI collects, to drive gauges that repaint
             * twelve and a half times a second. It also meant rpm, speed, MAF and throttle in
             * any given snapshot could come from different moments, which the gear calculator
             * and the DFCO test both read together as though they did not.
             */
            _data.value = readings.snapshot

            val stillOff = engineOff.observe(
                freshRpm = readings.freshRpm,
                speedKmh = readings.snapshot.speedKmh,
                nowMillis = clock.nowMillis(),
            )

            // Checked once a cycle rather than per command: a single timed-out PID is
            // ordinary, and a car that answers eleven of twelve requests is a car that is
            // talking. What ends the loop is nothing answering at all, for long enough that
            // no running engine explains it.
            if (clock.nowMillis() - lastReplyAt >= ObdTimeouts.SILENT_ADAPTER_MS) {
                wentSilent = true
                isPolling = false
            }
            if (!isPolling) break

            // Just woke up. Skip what is left of the idle budget so the next cycle - a full
            // one - runs immediately, and the first snapshot the driver sees after turning
            // the key is complete rather than dribbling in a reading at a time.
            if (wasEngineOff && !stillOff) continue

            val budget = if (stillOff) ObdPacing.ENGINE_OFF_CYCLE_MS else budgetMs
            delay(cycleSleepMs(clock.nowMillis() - cycleStart, budget))
        }
    }

    /** The fast set, plus one rotation slot. */
    private suspend fun pollFullCycle(prev: RawObdData, cycle: Long): CycleReadings {
        var s = prev

        // High-frequency: what the driver is watching move, and what the permanent record
        // integrates. These two carry the freshness stamp because they are the two the
        // integrators gate on - a stale rpm reads as an idling engine, and a stale speed
        // books distance nobody drove.
        val freshRpm = PidParsers.rpm(pollPid("010C"))
        if (freshRpm != null) {
            s = s.copy(rpm = freshRpm)
            lastRpmAt = clock.nowMillis()
        }
        val freshSpeed = PidParsers.speedKmh(pollPid("010D"))
        if (freshSpeed != null) {
            s = s.copy(speedKmh = freshSpeed)
            lastSpeedAt = clock.nowMillis()
        }
        s = s.copy(motionSampledAtMillis = motionStamp())

        PidParsers.maf(pollPid("0110"))?.let { s = s.copy(maf = it) }
        PidParsers.throttlePercent(pollPid("0111"))?.let { s = s.copy(throttlePos = it) }

        // O2 readings oscillate rapidly - a slow poll aliases them into a flat line, which
        // defeats the point of showing a live trace. Which PID that is depends on the car: a
        // narrowband front sensor answers 14 with a voltage, a wide-range one answers 34 with
        // lambda and current. This Civic has only 34, and asking it for 14 forever was why
        // "Pre-catalyst" showed a constant 0.45 V from nowhere.
        when (preCatPid) {
            0x34 -> {
                // One request covers both jobs: 34 is the pre-catalyst sensor *and* the
                // wideband the fuel model needs, so this keeps lambda at this tier's rate.
                val reading = PidParsers.wideRangeO2(pollPid("0134"))
                if (reading != null) {
                    s = s.copy(
                        o2Sensor1Lambda = reading.lambda,
                        lambda = reading.lambda,
                        o2Sensor1CurrentMa = reading.currentMa ?: s.o2Sensor1CurrentMa,
                    )
                }
            }
            0x14 -> PidParsers.o2Sensor1Voltage(pollPid("0114"))?.let { s = s.copy(o2Sensor1Voltage = it) }
        }

        return CycleReadings(pollRotationSlot(s, cycle), freshRpm)
    }

    /**
     * The engine-off tier: one command, just enough to notice the engine starting.
     *
     * Nothing else is worth a round trip on a car that is switched off, and the gauges are not
     * frozen while this runs - the telemetry tick keeps going and shows a freshly measured
     * zero, which is the truth.
     */
    private suspend fun pollEngineOffCycle(prev: RawObdData): CycleReadings {
        val freshRpm = PidParsers.rpm(pollPid("010C"))
        if (freshRpm == null) return CycleReadings(prev, null)

        lastRpmAt = clock.nowMillis()
        // Speed is not read in this tier, so the stamp goes stale within a couple of seconds
        // and the integrators stop accepting the snapshot. That is correct rather than
        // unfortunate: nothing here is measuring whether the car is moving. The rpm of zero
        // gates them off anyway, so this is the same answer arrived at twice.
        return CycleReadings(
            prev.copy(rpm = freshRpm, motionSampledAtMillis = motionStamp()),
            freshRpm,
        )
    }

    private suspend fun pollRotationSlot(prev: RawObdData, cycle: Long): RawObdData {
        var s = prev
        when (ROTATION_SLOTS[(cycle % ROTATION_SLOTS.size).toInt()]) {
            "0105" -> PidParsers.coolantC(pollPid("0105"))?.let { s = s.copy(coolantC = it) }
            "0104" -> PidParsers.engineLoad(pollPid("0104"))?.let { s = s.copy(engineLoad = it) }
            "0142" -> PidParsers.batteryVoltage(pollPid("0142"))?.let { s = s.copy(batteryVoltage = it) }
            "0115" -> PidParsers.o2Sensor2Voltage(pollPid("0115"))?.let { s = s.copy(o2Sensor2Voltage = it) }
            "010E" -> PidParsers.timingAdvance(pollPid("010E"))?.let { s = s.copy(timingAdvance = it) }
            "012F" -> PidParsers.fuelLevelPercent(pollPid("012F"))?.let { s = s.copy(fuelLevelPercent = it) }
            "011F" -> PidParsers.engineRuntimeSec(pollPid("011F"))?.let { s = s.copy(engineRuntimeSec = it) }
            "0107" -> PidParsers.longTermFuelTrim(pollPid("0107"))?.let { s = s.copy(ltft = it) }
            "0103" -> PidParsers.fuelSystemStatus(pollPid("0103"))?.let { s = s.copy(fuelSystemStatus = it) }
            "0145" -> PidParsers.relativeThrottlePercent(pollPid("0145"))?.let { s = s.copy(relativeThrottlePos = it) }
            "0106" -> {
                PidParsers.shortTermFuelTrim(pollPid("0106"))?.let { s = s.copy(stft = it) }
                s = pollSeparateLambda(s)
            }
            AIR_SLOT -> s = pollOutsideAir(s)
        }
        return s
    }

    /**
     * Only when lambda is not already arriving with the pre-catalyst read.
     *
     * On this Civic the two are the same PID, so this does not run at all; a car with a
     * narrowband front sensor plus a separate wideband needs it to. It rides the 0106 slot
     * rather than having one of its own, so the rotation stays uniform on the car that exists.
     */
    private suspend fun pollSeparateLambda(prev: RawObdData): RawObdData {
        val pid = lambdaPid
        if (pid == null || pid == preCatPid) return prev
        return when (pid) {
            0x24 -> PidParsers.lambdaFromPid24(pollPid("0124"))?.let { prev.copy(lambda = it) } ?: prev
            0x34 -> PidParsers.wideRangeO2(pollPid("0134"))?.let { prev.copy(lambda = it.lambda) } ?: prev
            else -> prev
        }
    }

    // 46 is outside air; 0F is intake air, a different quantity, and it gets labelled as one.
    // Neither existing leaves the reading null rather than 22 C.
    private suspend fun pollOutsideAir(prev: RawObdData): RawObdData {
        val airSource = when (outsideAirPid) {
            0x46 -> OutsideAirSource.AMBIENT
            0x0f -> OutsideAirSource.INTAKE
            else -> return prev
        }
        val cmd = if (airSource == OutsideAirSource.AMBIENT) "0146" else "010F"
        val value = PidParsers.outsideAirC(pollPid(cmd), airSource) ?: return prev
        return prev.copy(ambientC = value, ambientSource = airSource)
    }

    companion object {
        /** Stands in for whichever of 46/0F this car answers, resolved at poll time. */
        const val AIR_SLOT = "AIR"

        /**
         * One slow reading per cycle, in seven blocks of six.
         *
         * The repeated entries are the point, not padding. Coolant, load, fuel-system status,
         * outside air and post-catalyst O2 come round every six cycles - the rate the old
         * `% 6` tier ran at - while the trims, fuel level, runtime, timing, volts and relative
         * throttle come round every forty-two. None of that second group feeds an integrator,
         * and all of them move on a multi-second timescale.
         *
         * Two changes worth knowing about:
         *
         * Post-catalyst O2 (0115) used to be in the fast set, polled as often as rpm, to fill
         * a single row on the Fuel screen. It was about a sixth of all traffic on the bus.
         *
         * Fuel-system status (0103) took the fast-ish slot that control module voltage (0142)
         * had. Volts is a number somebody glances at; the status says whether the engine is in
         * closed loop, and therefore whether the lambda and trim readings behind the AFR mean
         * what the fuel model takes them to mean. It is worth knowing that within a second.
         *
         * Keep this in step with [PidCatalog.ALWAYS_POLLED_PIDS] and
         * [PidCatalog.OPTIONAL_POLLED_PIDS], which are what the discovery screen reads to tick
         * a PID as already driving a gauge.
         */
        val ROTATION_SLOTS: List<String> = listOf(
            "0105", "0104", "0103", AIR_SLOT, "0115", "0106",
            "0105", "0104", "0103", AIR_SLOT, "0115", "0107",
            "0105", "0104", "0103", AIR_SLOT, "0115", "012F",
            "0105", "0104", "0103", AIR_SLOT, "0115", "011F",
            "0105", "0104", "0103", AIR_SLOT, "0115", "010E",
            "0105", "0104", "0103", AIR_SLOT, "0115", "0142",
            "0105", "0104", "0103", AIR_SLOT, "0115", "0145",
        )
    }

    fun stopPolling() {
        isPolling = false
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

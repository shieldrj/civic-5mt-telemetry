package com.shieldrj.civic5mt.core

enum class DtcStatusType(val label: String) {
    PENDING("Pending"),
    CONFIRMED("Confirmed"),
    PERMANENT("Permanent"),
    ;

    /** The mode this status comes back on, and the prefix its reply carries. */
    val responsePrefix: String
        get() = when (this) {
            CONFIRMED -> "43" // Mode 03
            PENDING -> "47" // Mode 07
            PERMANENT -> "4A" // Mode 0A
        }

    val command: String
        get() = when (this) {
            CONFIRMED -> "03"
            PENDING -> "07"
            PERMANENT -> "0A"
        }
}

/**
 * The running conditions the ECU captured at the moment a fault was stored.
 *
 * Only a confirmed code stores one, which is why a scan that finds only pending or permanent
 * codes correctly comes back without a frame rather than with a snapshot of zeroes.
 */
data class FreezeFrame(
    val rpm: Int,
    val speedMph: Int,
    val coolantTempF: Int,
    val calcLoad: Double,
    val fuelTrimSt: Double,
    val fuelTrimLt: Double,
)

data class ScannedDtc(
    val code: String,
    val type: DtcStatusType,
    val details: DtcDefinition,
    val freezeFrame: FreezeFrame? = null,
)

data class DtcScanReport(
    val timestamp: Long,
    val milOn: Boolean,
    val pendingCodes: List<ScannedDtc> = emptyList(),
    val confirmedCodes: List<ScannedDtc> = emptyList(),
    val permanentCodes: List<ScannedDtc> = emptyList(),
    val monitors: ReadinessMonitorStatus = UNKNOWN_MONITORS,
) {
    val totalDtcCount: Int
        get() = pendingCodes.size + confirmedCodes.size + permanentCodes.size
}

/**
 * Turning diagnostic replies into codes.
 *
 * Pure, and separate from the thing that talks to the adapter, so every one of these can be
 * checked against a literal reply. In the TypeScript they were private methods on a class that
 * held a Bluetooth manager, which meant none of them could be exercised without a car.
 */
object DtcCodec {

    /** Strips framing and normalises case, once, for all the parsers below. */
    private fun clean(resp: String): String {
        val out = StringBuilder(resp.length)
        for (ch in resp) {
            when (ch) {
                ' ', '\t', '\r', '\n', '>' -> {}
                else -> out.append(ch.uppercaseChar())
            }
        }
        return out.toString()
    }

    /** Bit 7 of byte A in the Mode 01 PID 01 reply: the check engine light itself. */
    fun parseMilStatus(resp: String): Boolean {
        val c = clean(resp)
        val idx = c.indexOf("4101")
        if (idx < 0 || c.length < idx + 6) return false
        val byteA = c.substring(idx + 4, idx + 6).toIntOrNull(16) ?: return false
        return (byteA and 0x80) != 0
    }

    /**
     * Readiness monitors, out of the same reply the MIL comes from.
     *
     * Falls back to all-N/A rather than all-Ready when the reply cannot be read. An
     * unanswered ECU must never render as a car that has passed every emissions self-test -
     * that is the reading someone takes to a smog check.
     */
    fun parseReadinessMonitors(resp: String): ReadinessMonitorStatus {
        val c = clean(resp)
        val idx = c.indexOf("4101")
        if (idx < 0 || c.length < idx + 12) return UNKNOWN_MONITORS

        val b = c.substring(idx + 6, idx + 8).toIntOrNull(16)
        val cc = c.substring(idx + 8, idx + 10).toIntOrNull(16)
        val d = c.substring(idx + 10, idx + 12).toIntOrNull(16)
        if (b == null || cc == null || d == null) return UNKNOWN_MONITORS

        return decodeReadinessMonitors(b, cc, d)
    }

    /**
     * Decodes a Mode 03 / 07 / 0A reply into SAE codes.
     *
     * Two bytes per code. The top two bits of the first byte pick the letter, the next two are
     * the second digit, the low nibble is the third, and the second byte is the last two. A
     * pair of zero bytes is padding, not a code.
     */
    fun decodeDtcResponse(resp: String, type: DtcStatusType): List<ScannedDtc> {
        val c = clean(resp)
        if (c.contains("NODATA") || c.length < 4) return emptyList()

        val prefixIdx = c.indexOf(type.responsePrefix)
        if (prefixIdx < 0) return emptyList()

        val data = c.substring(prefixIdx + 2)
        val results = mutableListOf<ScannedDtc>()

        var i = 0
        while (i + 4 <= data.length) {
            val byte1 = data.substring(i, i + 2).toIntOrNull(16)
            val byte2 = data.substring(i + 2, i + 4).toIntOrNull(16)
            i += 4
            if (byte1 == null || byte2 == null) break
            if (byte1 == 0 && byte2 == 0) continue // Padding

            val code = formatDtc(byte1, byte2)
            results += ScannedDtc(
                code = code,
                type = type,
                details = HONDA_DTC_DATABASE[code] ?: genericDefinition(code, type),
            )
        }
        return results
    }

    /** `P0133` and friends, from the two bytes the ECU sent. */
    fun formatDtc(byte1: Int, byte2: Int): String {
        val prefixChar = charArrayOf('P', 'C', 'B', 'U')[(byte1 shr 6) and 0x03]
        val codeTypeDigit = (byte1 shr 4) and 0x03
        val digit3 = (byte1 and 0x0F).toString(16).uppercase()
        val digit45 = byte2.toString(16).padStart(2, '0').uppercase()
        return "$prefixChar$codeTypeDigit$digit3$digit45"
    }

    /**
     * Something honest to show for a code the Honda table does not carry.
     *
     * Deliberately vague. A generic entry that invented plausible Civic-specific causes would
     * be indistinguishable on screen from the researched ones, and those are the entire reason
     * the table exists.
     */
    fun genericDefinition(code: String, type: DtcStatusType): DtcDefinition {
        val category = when (code.first()) {
            'P' -> DtcCategory.POWERTRAIN
            'C' -> DtcCategory.CHASSIS
            'B' -> DtcCategory.BODY
            else -> DtcCategory.NETWORK
        }
        return DtcDefinition(
            code = code,
            category = category,
            system = "General Diagnostic Code",
            title = "Generic fault code $code",
            description = "A standard OBD-II fault code this app has no Civic-specific notes " +
                "for. Look it up before acting on it.",
            severity = if (type == DtcStatusType.CONFIRMED) DtcSeverity.MODERATE else DtcSeverity.MINOR,
            symptoms = listOf("Potential driveability, emissions, or sensor communication anomaly"),
            possibleCauses = listOf(
                "Sensor reading outside normal operating parameters",
                "Intermittent wiring connection",
            ),
        )
    }

    /**
     * One Mode 02 freeze-frame PID.
     *
     * The reply echoes the mode and PID and then a frame number, so the data starts six
     * characters in rather than four.
     */
    fun parseFreezeFramePid(resp: String, pid: String): List<Int>? {
        val c = clean(resp)
        if (c.contains("NODATA")) return null
        val idx = c.indexOf("42$pid")
        if (idx < 0) return null

        val data = c.substring(idx + 4 + 2)
        if (data.length < 2) return null

        val bytes = mutableListOf<Int>()
        var i = 0
        while (i + 2 <= data.length && bytes.size < 2) {
            val v = data.substring(i, i + 2).toIntOrNull(16) ?: return null
            bytes += v
            i += 2
        }
        return bytes.ifEmpty { null }
    }

    /** Assembles a frame from the individual PID reads, or null if the ECU stored none. */
    fun buildFreezeFrame(
        rpm: List<Int>?,
        speed: List<Int>?,
        coolant: List<Int>?,
        load: List<Int>?,
        stft: List<Int>?,
        ltft: List<Int>?,
    ): FreezeFrame? {
        // Nothing at all means no frame stored, which is the normal answer when the only codes
        // present are pending or permanent. Reporting a snapshot of zeroes would read as a
        // fault that happened at 0 rpm and 0 mph.
        if (rpm == null && speed == null && coolant == null && load == null) return null

        return FreezeFrame(
            rpm = if (rpm != null && rpm.size >= 2) Math.round((rpm[0] * 256 + rpm[1]) / 4.0).toInt() else 0,
            speedMph = if (speed != null) Math.round(speed[0] * 0.621371).toInt() else 0,
            coolantTempF = if (coolant != null) Math.round(((coolant[0] - 40) * 9) / 5.0 + 32).toInt() else 0,
            calcLoad = if (load != null) roundTo((load[0] * 100.0) / 255.0, 1) else 0.0,
            fuelTrimSt = if (stft != null) roundTo(((stft[0] - 128) * 100.0) / 128.0, 1) else 0.0,
            fuelTrimLt = if (ltft != null) roundTo(((ltft[0] - 128) * 100.0) / 128.0, 1) else 0.0,
        )
    }
}

/**
 * Runs a diagnostic scan over the adapter.
 *
 * Shares the client with the poll loop rather than opening anything of its own, which is what
 * the command queue exists for: an ELM327 has one command in flight and no way to say which
 * reply belongs to which request. A scan issued while the gauges are polling is the exact
 * collision that used to freeze every reading on its last good value.
 */
class DtcScanner(
    private val client: Elm327Client,
    private val clock: MillisClock = SystemMillisClock,
) {

    suspend fun performFullScan(): DtcScanReport {
        // 1. The light itself, and the readiness monitors, from one reply.
        val milResp = client.sendCommand("0101", 1200)
        val milOn = DtcCodec.parseMilStatus(milResp)
        val monitors = DtcCodec.parseReadinessMonitors(milResp)

        // 2-4. Confirmed, pending and permanent. Pending matters most of the three: it is the
        // fault that has happened once and not yet lit the dashboard.
        val confirmed = DtcCodec.decodeDtcResponse(
            client.sendCommand(DtcStatusType.CONFIRMED.command, 1500),
            DtcStatusType.CONFIRMED,
        )
        val pending = DtcCodec.decodeDtcResponse(
            client.sendCommand(DtcStatusType.PENDING.command, 1500),
            DtcStatusType.PENDING,
        )
        val permanent = DtcCodec.decodeDtcResponse(
            client.sendCommand(DtcStatusType.PERMANENT.command, 1500),
            DtcStatusType.PERMANENT,
        )

        // 5. Freeze frame, only where there is a confirmed code to have stored one.
        val withFrame = if (confirmed.isNotEmpty()) {
            val frame = readFreezeFrame()
            if (frame != null) {
                confirmed.mapIndexed { i, dtc -> if (i == 0) dtc.copy(freezeFrame = frame) else dtc }
            } else {
                confirmed
            }
        } else {
            confirmed
        }

        return DtcScanReport(
            timestamp = clock.nowMillis(),
            milOn = milOn,
            pendingCodes = pending,
            confirmedCodes = withFrame,
            permanentCodes = permanent,
            monitors = monitors,
        )
    }

    /**
     * Reads frame 00 one PID at a time.
     *
     * Individually rather than as one multi-PID request, because that is not something every
     * adapter answers consistently, and sequentially rather than concurrently because they all
     * go through the same single-command queue anyway.
     */
    private suspend fun readFreezeFrame(): FreezeFrame? {
        suspend fun read(pid: String): List<Int>? =
            DtcCodec.parseFreezeFramePid(client.sendCommand("02${pid}00", 1200), pid)

        return DtcCodec.buildFreezeFrame(
            rpm = read("0C"),
            speed = read("0D"),
            coolant = read("05"),
            load = read("04"),
            stft = read("06"),
            ltft = read("07"),
        )
    }

    /**
     * Mode 04: clears stored codes and puts the light out.
     *
     * Worth knowing what this actually does before offering it: it also wipes the readiness
     * monitors, so the car will fail an emissions test until it has driven a full drive cycle.
     * Clearing a code is not the same as fixing it.
     */
    suspend fun clearAllCodes(): Boolean {
        val resp = client.sendCommand("04", 2000)
        val c = resp.replace(" ", "").uppercase()
        return c.contains("44") || c.contains("OK")
    }
}

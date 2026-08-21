package com.shieldrj.civic5mt.core

import kotlin.math.abs
import kotlin.math.floor

/**
 * Names and formulas for mode 01 PIDs, used by the discovery screen.
 *
 * This catalogue answers "what does my car expose, and what is it saying" without anyone
 * having to guess from the model year. Every entry here is the generic OBD-II definition -
 * which is only half the story, because whether a given ECU implements a PID varies by
 * manufacturer and trim. That half comes from the car itself, via the 0100/0120/... support
 * bitmaps. Nothing in this file asserts that the Civic has any particular PID.
 *
 * A PID with no `decode` still gets listed and still shows its raw bytes. Showing the hex
 * and saying "not decoded" is honest; inventing a plausible formula is how a dashboard ends
 * up confidently displaying a wrong number, which is the failure this whole exercise has
 * been chasing.
 */

data class PidDefinition(
    val name: String,
    val unit: String? = null,
    /** Bytes A, B, C, D... after the 41xx prefix. Returns null if the reply is too short. */
    val decode: ((List<Int>) -> Double?)? = null,
    /** Rendered instead of a number, for enumerations and bitmaps. */
    val describe: ((List<Int>) -> String?)? = null,
)

private val pct255: (List<Int>) -> Double? = { b -> if (b.isNotEmpty()) (b[0] * 100.0) / 255.0 else null }
private val tempA: (List<Int>) -> Double? = { b -> if (b.isNotEmpty()) b[0] - 40.0 else null }
private val trimPct: (List<Int>) -> Double? = { b -> if (b.isNotEmpty()) ((b[0] - 128.0) * 100.0) / 128.0 else null }
private val word: (List<Int>) -> Double? = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) else null }

/** Two's-complement 16-bit. Evap pressure is the one PID here that goes negative. */
private val signedWord: (List<Int>) -> Double? = { b ->
    word(b)?.let { raw -> if (raw >= 0x8000) raw - 0x10000 else raw }
}

/**
 * Whether PID 03 says the ECU is running O2 feedback - the state the fuel model assumes.
 *
 * Two values count: 2 is ordinary closed loop, and 16 is closed loop with one sensor faulted,
 * which is still feedback. Everything else is open loop, where lambda and the trims follow a
 * fixed enrichment map rather than correcting towards stoichiometric - so an air:fuel ratio
 * derived from them is not the ratio being burned.
 */
fun isClosedLoop(status: Int?): Boolean = status == 2 || status == 16

/** Shared with the live gauges, which show the same status the discovery screen decodes. */
val FUEL_SYSTEM_STATUS_LABELS: Map<Int, String> = mapOf(
    0 to "Off",
    1 to "Open loop — engine cold",
    2 to "Closed loop — using O2 feedback",
    4 to "Open loop — load or deceleration",
    8 to "Open loop — system fault",
    16 to "Closed loop — one O2 sensor faulted",
)

private val FUEL_TYPES: Map<Int, String> = mapOf(
    1 to "Gasoline",
    2 to "Methanol",
    3 to "Ethanol",
    4 to "Diesel",
    5 to "LPG",
    6 to "CNG",
    7 to "Propane",
    8 to "Electric",
    9 to "Bifuel — gasoline",
    10 to "Bifuel — methanol",
    11 to "Bifuel — ethanol",
    12 to "Bifuel — LPG",
    13 to "Bifuel — CNG",
    14 to "Bifuel — propane",
    15 to "Bifuel — electric",
    17 to "Hybrid gasoline",
    18 to "Hybrid ethanol",
    19 to "Hybrid diesel",
)

private val OBD_STANDARD: Map<Int, String> = mapOf(
    1 to "OBD-II (California ARB)",
    3 to "OBD and OBD-II",
    6 to "EOBD",
    9 to "EOBD and OBD-II",
)

val PID_CATALOG: Map<Int, PidDefinition> = mapOf(
    0x01 to PidDefinition(
        name = "Monitor status / MIL",
        describe = { b ->
            if (b.isNotEmpty()) {
                val codes = b[0] and 0x7f
                val mil = if ((b[0] and 0x80) != 0) "CHECK ENGINE ON" else "No MIL"
                "$mil · $codes stored code" + if (codes == 1) "" else "s"
            } else {
                null
            }
        },
    ),
    0x03 to PidDefinition(
        name = "Fuel system status",
        describe = { b ->
            if (b.isNotEmpty()) {
                FUEL_SYSTEM_STATUS_LABELS[b[0]] ?: ("Unknown (0x" + b[0].toString(16) + ")")
            } else {
                null
            }
        },
    ),
    0x04 to PidDefinition("Calculated engine load", "%", decode = pct255),
    0x05 to PidDefinition("Coolant temperature", "°C", decode = tempA),
    0x06 to PidDefinition("Short term fuel trim", "%", decode = trimPct),
    0x07 to PidDefinition("Long term fuel trim", "%", decode = trimPct),
    0x0a to PidDefinition("Fuel pressure", "kPa", decode = { b -> if (b.isNotEmpty()) b[0] * 3.0 else null }),
    0x0b to PidDefinition("Manifold pressure", "kPa", decode = { b -> if (b.isNotEmpty()) b[0].toDouble() else null }),
    0x0c to PidDefinition("Engine RPM", "rpm", decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 4.0 else null }),
    0x0d to PidDefinition("Vehicle speed", "km/h", decode = { b -> if (b.isNotEmpty()) b[0].toDouble() else null }),
    0x0e to PidDefinition("Timing advance", "°", decode = { b -> if (b.isNotEmpty()) b[0] / 2.0 - 64.0 else null }),
    0x0f to PidDefinition("Intake air temperature", "°C", decode = tempA),
    0x10 to PidDefinition("Mass air flow", "g/s", decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 100.0 else null }),
    0x11 to PidDefinition("Throttle position", "%", decode = pct255),
    0x13 to PidDefinition(
        name = "O2 sensors present",
        describe = { b ->
            if (b.isEmpty()) {
                null
            } else {
                val present = (0 until 8)
                    .filter { (b[0] and (1 shl it)) != 0 }
                    .map { "B" + (if (it < 4) 1 else 2) + "S" + ((it % 4) + 1) }
                if (present.isNotEmpty()) present.joinToString(", ") else "None reported"
            }
        },
    ),
    0x14 to PidDefinition("O2 sensor B1S1 voltage", "V", decode = { b -> if (b.isNotEmpty()) b[0] / 200.0 else null }),
    0x15 to PidDefinition("O2 sensor B1S2 voltage", "V", decode = { b -> if (b.isNotEmpty()) b[0] / 200.0 else null }),
    0x1c to PidDefinition(
        name = "OBD standard",
        describe = { b -> if (b.isNotEmpty()) (OBD_STANDARD[b[0]] ?: ("Type " + b[0])) else null },
    ),
    0x1f to PidDefinition("Engine run time", "s", decode = word),
    0x21 to PidDefinition("Distance with MIL on", "km", decode = word),
    0x24 to PidDefinition(
        name = "O2 S1 lambda (wide range)",
        unit = "ratio",
        decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 32768.0 else null },
    ),
    0x2c to PidDefinition("Commanded EGR", "%", decode = pct255),
    0x2d to PidDefinition("EGR error", "%", decode = trimPct),
    0x2e to PidDefinition("Commanded evap purge", "%", decode = pct255),
    0x2f to PidDefinition("Fuel tank level", "%", decode = pct255),
    0x30 to PidDefinition("Warm-ups since codes cleared", decode = { b -> if (b.isNotEmpty()) b[0].toDouble() else null }),
    0x31 to PidDefinition("Distance since codes cleared", "km", decode = word),
    0x32 to PidDefinition(
        name = "Evap system vapour pressure",
        unit = "Pa",
        decode = { b -> signedWord(b)?.let { it / 4.0 } },
    ),
    0x33 to PidDefinition("Barometric pressure", "kPa", decode = { b -> if (b.isNotEmpty()) b[0].toDouble() else null }),
    0x34 to PidDefinition(
        /*
         * The wide-range (air/fuel) sensor, reported as lambda plus sensor current. This is
         * the same lambda word as PID 24 - bytes A and B over 32768 - which is the whole
         * reason it matters here: a car that lacks 24 and has 34 still has a wideband, and
         * the fuel model reads it from whichever one answers. Current near zero means the
         * sensor is sitting at balance, which is what a working closed loop looks like.
         */
        name = "O2 S1 lambda + current (wide range)",
        describe = { b ->
            if (b.size < 2) {
                null
            } else {
                val lambda = (b[0] * 256.0 + b[1]) / 32768.0
                if (b.size < 4) {
                    "λ " + toFixed(lambda, 3)
                } else {
                    val currentMa = (b[2] * 256.0 + b[3]) / 256.0 - 128.0
                    "λ " + toFixed(lambda, 3) + " · " + toFixed(currentMa, 2) + " mA"
                }
            }
        },
    ),
    0x3c to PidDefinition(
        name = "Catalyst temperature B1S1",
        unit = "°C",
        decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 10.0 - 40.0 else null },
    ),
    0x41 to PidDefinition(
        /*
         * Byte A is reserved; B, C and D are the same readiness bitmap as PID 01, but scoped
         * to the current drive cycle rather than to everything since codes were cleared. The
         * decoder is shared with the diagnostics tab for exactly that reason.
         */
        name = "Monitor status this drive cycle",
        describe = { b ->
            if (b.size >= 4) summariseMonitors(decodeReadinessMonitors(b[1], b[2], b[3])) else null
        },
    ),
    0x42 to PidDefinition("Control module voltage", "V", decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 1000.0 else null }),
    0x43 to PidDefinition(
        name = "Absolute engine load",
        unit = "%",
        decode = { b -> if (b.size >= 2) ((b[0] * 256.0 + b[1]) * 100.0) / 255.0 else null },
    ),
    0x44 to PidDefinition(
        name = "Commanded equivalence ratio",
        unit = "ratio",
        decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 32768.0 else null },
    ),
    0x45 to PidDefinition("Relative throttle position", "%", decode = pct255),
    0x46 to PidDefinition("Ambient air temperature", "°C", decode = tempA),
    0x47 to PidDefinition("Absolute throttle B", "%", decode = pct255),
    0x49 to PidDefinition("Accelerator pedal D", "%", decode = pct255),
    0x4a to PidDefinition("Accelerator pedal E", "%", decode = pct255),
    0x4c to PidDefinition("Commanded throttle actuator", "%", decode = pct255),
    0x51 to PidDefinition(
        name = "Fuel type",
        describe = { b -> if (b.isNotEmpty()) (FUEL_TYPES[b[0]] ?: ("Type " + b[0])) else null },
    ),
    0x5c to PidDefinition("Engine oil temperature", "°C", decode = tempA),
    0x5e to PidDefinition("Engine fuel rate", "L/h", decode = { b -> if (b.size >= 2) (b[0] * 256.0 + b[1]) / 20.0 else null }),
)

/** PIDs at each bank boundary report which PIDs the *next* bank supports, not a reading. */
val BANK_MARKER_PIDS: Set<Int> = setOf(0x20, 0x40, 0x60, 0x80, 0xa0, 0xc0)

/** PIDs the gauges read on every car, because every OBD-II car has them. */
val ALWAYS_POLLED_PIDS: List<Int> = listOf(
    0x04, 0x05, 0x06, 0x07, 0x0c, 0x0d, 0x0e, 0x10, 0x11, 0x15, 0x1f, 0x2f, 0x42,
)

/**
 * Readings the gauges show where the car has them, and leave absent where it does not.
 *
 * Separate from [ALWAYS_POLLED_PIDS] because that list means "every OBD-II car answers this",
 * and these two are not that - a car without them must not have them ticked as driving a
 * gauge. This Civic reports both. It does not report 5C, engine oil temperature, which is why
 * the oil model still judges warm-up by coolant: a scan settled it rather than a datasheet.
 */
val OPTIONAL_POLLED_PIDS: List<Int> = listOf(
    0x03, 0x45,
)

/*
 * Three of the readings this app shows can come from more than one PID, and which one
 * exists varies by car. Naming a single PID per metric is what broke: the gauges asked for
 * 24, 46 and 14, this Civic has none of the three, and each reading silently kept the
 * plausible-looking number it was initialised with. A lambda pinned at exactly 1.0 was the
 * worst of them, because it also passed the fuel model's validity test and so suppressed
 * the fuel-trim fallback that would have used real data.
 *
 * So each metric is a preference list, resolved against what the car reports. The poll loop
 * and the discovery screen's "already drives a gauge" tick both resolve through choosePid()
 * below - one rule, so the screen cannot claim a PID the loop never reads.
 */

/** Wide-range lambda for the fuel model. 24 is lambda+voltage, 34 is lambda+current. */
val LAMBDA_PID_CANDIDATES: List<Int> = listOf(0x24, 0x34)

/**
 * The pre-catalyst sensor. 14 is a narrowband voltage that swings across 0.45 V; 34 is a
 * wideband reporting lambda directly. Narrowband first only because its live swing is the
 * more familiar trace, not because it is the better sensor.
 */
val PRE_CAT_PID_CANDIDATES: List<Int> = listOf(0x14, 0x34)

/**
 * Outside air. 46 is the real thing. 0F is intake air, which after a few minutes of idling
 * reads engine-bay heat rather than weather - so it is a fallback that has to be labelled
 * as what it is, never quietly shown under an "Outside" heading.
 */
val OUTSIDE_AIR_PID_CANDIDATES: List<Int> = listOf(0x46, 0x0f)

/**
 * First candidate the car actually supports, or null if it supports none of them.
 *
 * An empty support set means the bitmaps could not be read at all, which is not the same
 * as "the car has nothing". So it falls back to the first candidate and lets the reply
 * decide - the same choice the poll loop already makes for the fixed PIDs.
 */
fun choosePid(candidates: List<Int>, supported: Set<Int>): Int? {
    if (supported.isEmpty()) return candidates.firstOrNull()
    return candidates.firstOrNull { supported.contains(it) }
}

/** Every PID the gauges will actually poll on a car reporting this support set. */
fun pidsInUseFor(supported: Set<Int>): Set<Int> {
    val inUse = ALWAYS_POLLED_PIDS.toMutableSet()
    for (candidates in listOf(LAMBDA_PID_CANDIDATES, PRE_CAT_PID_CANDIDATES, OUTSIDE_AIR_PID_CANDIDATES)) {
        choosePid(candidates, supported)?.let { inUse.add(it) }
    }
    // Same rule choosePid uses: an unreadable bitmap means "ask, and let the reply decide",
    // which is what the poll loop does too - isPidSupported passes everything on an empty set.
    inUse.addAll(if (supported.isEmpty()) OPTIONAL_POLLED_PIDS else OPTIONAL_POLLED_PIDS.filter(supported::contains))
    return inUse
}

fun pidCommand(pid: Int): String = "01" + pid.toString(16).uppercase().padStart(2, '0')

fun pidLabel(pid: Int): String = PID_CATALOG[pid]?.name ?: ("PID " + pidCommand(pid).substring(2))

/**
 * Turns a raw adapter reply into something readable. Returns null when the car answered
 * but this catalogue has no formula - the caller shows the hex instead of a guess.
 */
fun decodePidValue(pid: Int, hexPayload: String): String? {
    val def = PID_CATALOG[pid] ?: return null

    val bytes = mutableListOf<Int>()
    var i = 0
    while (i + 1 < hexPayload.length) {
        // Stricter than the TypeScript's `parseInt`, which reads "1Z" as 1 and would feed a
        // half-parsed nibble downstream as though it were a real byte. A malformed pair
        // stops the parse here instead.
        val byte = hexPayload.substring(i, i + 2).toIntOrNull(16) ?: break
        bytes.add(byte)
        i += 2
    }
    if (bytes.isEmpty()) return null

    def.describe?.let { return it(bytes) }
    val decode = def.decode ?: return null

    val value = decode(bytes) ?: return null
    if (!value.isFinite()) return null

    // JavaScript's Math.round rounds halves toward +Infinity; kotlin.math.round rounds them
    // away from zero. They differ only on an exact .5 with a negative value, but this is a
    // number a driver reads off a gauge, so it matches rather than nearly matches.
    val rounded = if (abs(value) >= 100) floor(value + 0.5) else toFixed(value, 2).toDouble()
    val text = jsNumberToString(rounded)
    return if (def.unit != null) "$text ${def.unit}" else text
}

package com.shieldrj.civic5mt.core

/**
 * Emissions readiness monitor bitmaps, decoded.
 *
 * This lives on its own rather than inside the DTC scanner because two different screens
 * read the same bitmap from two different PIDs: the diagnostics tab reads PID 01 (since
 * codes were cleared) and the discovery tab reads PID 41 (this drive cycle). Both use the
 * byte layout below, and a second copy of a bit layout is how two readings of the same
 * bytes end up disagreeing.
 */

enum class MonitorState(val label: String) {
    READY("Ready"),
    NOT_READY("Not Ready"),
    NOT_AVAILABLE("N/A"),
    ;

    override fun toString(): String = label
}

data class ReadinessMonitorStatus(
    val misfire: MonitorState,
    val fuelSystem: MonitorState,
    val comprehensive: MonitorState,
    val catalyst: MonitorState,
    val evap: MonitorState,
    val o2Sensor: MonitorState,
    val o2Heater: MonitorState,
    val egrVvt: MonitorState,
) {
    /**
     * Monitors paired with their display names, in a fixed order.
     *
     * The TypeScript got this order from `Object.entries`, i.e. from the order the object
     * literal happened to be written in. Relying on that is fine until someone reorders the
     * fields and the summary line silently starts naming monitors in a different sequence,
     * so it is written down here instead.
     */
    fun labelled(): List<Pair<String, MonitorState>> = listOf(
        "misfire" to misfire,
        "fuel system" to fuelSystem,
        "components" to comprehensive,
        "catalyst" to catalyst,
        "evap" to evap,
        "O2 sensor" to o2Sensor,
        "O2 heater" to o2Heater,
        "EGR / VVT" to egrVvt,
    )
}

/**
 * Decodes the readiness monitor bits from bytes B, C and D.
 *
 * For every monitor there are two bits: one saying the ECU supports the test at all,
 * and one saying the test has not finished. A monitor the engine does not have reports
 * N/A rather than Ready - claiming a test passed when it was never run is the failure
 * mode this replaced, and it is the reading that matters when someone is deciding whether
 * the car is ready for a smog check.
 *
 * Byte B carries the three tests common to every engine, and its low nibble is the
 * "supported" half while bits 4-6 are the "incomplete" half. Bytes C and D are the
 * spark-ignition monitor set, split the same way: C supported, D incomplete.
 *
 * PID 01 and PID 41 share this layout exactly. They differ only in the window they
 * describe - PID 01 since codes were cleared, PID 41 this drive cycle - so the caller
 * supplies the bytes and says which question it is asking.
 */
fun decodeReadinessMonitors(b: Int, c: Int, d: Int): ReadinessMonitorStatus {
    fun read(supported: Boolean, incomplete: Boolean): MonitorState = when {
        !supported -> MonitorState.NOT_AVAILABLE
        incomplete -> MonitorState.NOT_READY
        else -> MonitorState.READY
    }

    fun bit(byte: Int, index: Int): Boolean = (byte and (1 shl index)) != 0

    return ReadinessMonitorStatus(
        misfire = read(bit(b, 0), bit(b, 4)),
        fuelSystem = read(bit(b, 1), bit(b, 5)),
        comprehensive = read(bit(b, 2), bit(b, 6)),
        catalyst = read(bit(c, 0), bit(d, 0)),
        evap = read(bit(c, 2), bit(d, 2)),
        o2Sensor = read(bit(c, 5), bit(d, 5)),
        o2Heater = read(bit(c, 6), bit(d, 6)),
        egrVvt = read(bit(c, 7), bit(d, 7)),
    )
}

/** Every monitor unknown - used when the ECU's reply cannot be read. */
val UNKNOWN_MONITORS: ReadinessMonitorStatus = ReadinessMonitorStatus(
    misfire = MonitorState.NOT_AVAILABLE,
    fuelSystem = MonitorState.NOT_AVAILABLE,
    comprehensive = MonitorState.NOT_AVAILABLE,
    catalyst = MonitorState.NOT_AVAILABLE,
    evap = MonitorState.NOT_AVAILABLE,
    o2Sensor = MonitorState.NOT_AVAILABLE,
    o2Heater = MonitorState.NOT_AVAILABLE,
    egrVvt = MonitorState.NOT_AVAILABLE,
)

/**
 * One line summarising a monitor set: what is still running, or that nothing is.
 * Returns null when the ECU supports no monitors at all, which is not a status worth
 * printing as "all complete".
 */
fun summariseMonitors(status: ReadinessMonitorStatus): String? {
    val supported = status.labelled().filter { it.second != MonitorState.NOT_AVAILABLE }
    if (supported.isEmpty()) return null

    val incomplete = supported.filter { it.second == MonitorState.NOT_READY }.map { it.first }
    if (incomplete.isEmpty()) return "${supported.size} monitors, all complete"
    return "${incomplete.size} of ${supported.size} still running — ${incomplete.joinToString(", ")}"
}

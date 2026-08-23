package com.shieldrj.civic5mt.core

/**
 * Turning an adapter reply into a reading.
 *
 * Every function here is pure: raw text in, a value or null out. Null means the car did not
 * answer, or answered something this cannot read - and the caller leaves the previous
 * reading alone rather than substituting a plausible number. That distinction is the one
 * this app keeps having to relearn.
 *
 * In the TypeScript these were private methods that assigned straight into a shared mutable
 * object, so there was no way to test a formula without a live adapter. Not one of them had
 * a test. They are pure functions here for that reason alone.
 */
object PidParsers {

    /**
     * The hex data bytes following a response prefix, or null if the prefix is absent.
     *
     * Single pass, no regex. It runs a dozen-plus times per poll cycle at roughly twelve
     * cycles a second, and the TypeScript's `replace(/[\s\r\n>]/g, '').toUpperCase()`
     * allocated two strings every time to do it.
     */
    fun hexPayload(raw: String, prefix: String): String? {
        val clean = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                ' ', '\t', '\r', '\n', '>' -> {}
                else -> clean.append(ch.uppercaseChar())
            }
        }
        val idx = clean.indexOf(prefix)
        if (idx < 0) return null
        return clean.substring(idx + prefix.length)
    }

    /** Byte n of a payload as an unsigned int, or null when the payload is too short. */
    private fun byteAt(hex: String, index: Int): Int? {
        val start = index * 2
        if (hex.length < start + 2) return null
        return hex.substring(start, start + 2).toIntOrNull(16)
    }

    private fun oneByte(raw: String, prefix: String): Int? =
        hexPayload(raw, prefix)?.let { byteAt(it, 0) }

    private fun twoBytes(raw: String, prefix: String): Int? {
        val hex = hexPayload(raw, prefix) ?: return null
        val a = byteAt(hex, 0) ?: return null
        val b = byteAt(hex, 1) ?: return null
        return a * 256 + b
    }

    /** PID 0C. Rounded to whole revolutions, as the gauge shows them. */
    fun rpm(raw: String): Double? = twoBytes(raw, "410C")?.let { Math.round(it / 4.0).toDouble() }

    /** PID 0D, km/h, a single byte. */
    fun speedKmh(raw: String): Double? = oneByte(raw, "410D")?.toDouble()

    /** PID 10, grams per second. */
    fun maf(raw: String): Double? = twoBytes(raw, "4110")?.let { roundTo(it / 100.0, 2) }

    /** PID 11, percent. */
    fun throttlePercent(raw: String): Double? =
        oneByte(raw, "4111")?.let { roundTo((it * 100.0) / 255.0, 1) }

    /** PID 05, Celsius, offset by 40. */
    fun coolantC(raw: String): Double? = oneByte(raw, "4105")?.let { it - 40.0 }

    /** PID 04, percent. */
    fun engineLoad(raw: String): Double? =
        oneByte(raw, "4104")?.let { roundTo((it * 100.0) / 255.0, 1) }

    /** PID 0E, degrees before top dead centre. */
    fun timingAdvance(raw: String): Double? = oneByte(raw, "410E")?.let { roundTo(it / 2.0 - 64.0, 1) }

    /** PID 06, short term fuel trim, percent either side of zero. */
    fun shortTermFuelTrim(raw: String): Double? =
        oneByte(raw, "4106")?.let { roundTo(((it - 128) * 100.0) / 128.0, 1) }

    /** PID 07, long term fuel trim. */
    fun longTermFuelTrim(raw: String): Double? =
        oneByte(raw, "4107")?.let { roundTo(((it - 128) * 100.0) / 128.0, 1) }

    /** PID 24, wide-range lambda where the car has it. */
    fun lambdaFromPid24(raw: String): Double? =
        twoBytes(raw, "4124")?.let { roundTo(it / 32768.0, 3) }

    /**
     * PID 34: the wide-range front sensor, as lambda plus sensor current.
     *
     * Bytes A and B are the same lambda word as PID 24, which is why a car with 34 and no 24
     * still has everything the fuel model needs. Bytes C and D are current in mA offset by
     * 128; near zero means the sensor is sitting at balance, which is a healthy closed loop.
     * Current is optional - a short reply still yields a usable lambda.
     */
    fun wideRangeO2(raw: String): WideRangeReading? {
        val hex = hexPayload(raw, "4134") ?: return null
        val a = byteAt(hex, 0) ?: return null
        val b = byteAt(hex, 1) ?: return null
        val lambda = roundTo((a * 256 + b) / 32768.0, 3)

        val c = byteAt(hex, 2)
        val d = byteAt(hex, 3)
        val currentMa = if (c != null && d != null) roundTo((c * 256 + d) / 256.0 - 128.0, 2) else null

        return WideRangeReading(lambda = lambda, currentMa = currentMa)
    }

    /** PID 14, pre-catalyst narrowband voltage. */
    fun o2Sensor1Voltage(raw: String): Double? = oneByte(raw, "4114")?.let { roundTo(it / 200.0, 3) }

    /** PID 15, post-catalyst narrowband voltage. */
    fun o2Sensor2Voltage(raw: String): Double? = oneByte(raw, "4115")?.let { roundTo(it / 200.0, 3) }

    /** PID 42, control module voltage. Reads low if the alternator is not charging. */
    fun batteryVoltage(raw: String): Double? = twoBytes(raw, "4142")?.let { roundTo(it / 1000.0, 2) }

    /** PID 2F, tank level as a percentage. */
    fun fuelLevelPercent(raw: String): Double? =
        oneByte(raw, "412F")?.let { roundTo((it * 100.0) / 255.0, 1) }

    /**
     * PID 03, fuel system 1 status, as the raw bitmask.
     *
     * Worth a slot because it says when the mixture readings mean what the fuel model thinks
     * they mean. In open loop - a cold engine, or wide-open throttle - the ECU ignores the O2
     * sensor and runs a fixed enrichment map, so lambda and the trims stop describing a
     * correction and the AFR derived from them is not the AFR being burned.
     */
    fun fuelSystemStatus(raw: String): Int? = oneByte(raw, "4103")

    /**
     * PID 45, throttle position relative to its own closed-throttle rest point.
     *
     * PID 11 on this car reads about 14 percent with the pedal up, which is where the magic
     * 14.0 in CivicSpecs.CLOSED_THROTTLE_BASELINE_PERCENT comes from. This one is zeroed at
     * rest by definition, so it needs no such constant.
     */
    fun relativeThrottlePercent(raw: String): Double? =
        oneByte(raw, "4145")?.let { roundTo((it * 100.0) / 255.0, 1) }

    /** PID 1F, seconds since the engine started. */
    fun engineRuntimeSec(raw: String): Double? = twoBytes(raw, "411F")?.toDouble()

    /**
     * Outside air, from whichever PID this car actually has.
     *
     * PID 46 is the real thing. PID 0F is intake air, which after a few minutes of idling
     * reads engine-bay heat rather than weather - so the source travels with the value and
     * the display has to say which it is. It must never appear under an "Outside" heading
     * unlabelled.
     */
    fun outsideAirC(raw: String, source: OutsideAirSource): Double? {
        val prefix = if (source == OutsideAirSource.AMBIENT) "4146" else "410F"
        return oneByte(raw, prefix)?.let { it - 40.0 }
    }

    /**
     * Whether a reply to 0100 shows the CAN bus is actually up.
     *
     * The cheapest possible proof, and the one thing the old handshake never did - it set a
     * protocol and went straight to polling, so a bus that never came up looked exactly like
     * a working one.
     */
    fun isBusAlive(raw: String): Boolean = hexPayload(raw, "4100") != null

    /**
     * Decodes one supported-PID bitmap into the PIDs it reports, plus whether the next bank
     * exists.
     *
     * Each bank's last bit says whether there is another bank after it, so a car that stops
     * at 0x20 costs exactly two commands rather than seven.
     */
    fun supportBitmap(raw: String, base: Int, prefix: String): SupportBank? {
        val hex = hexPayload(raw, prefix) ?: return null
        if (hex.length < 8) return null
        val mask = hex.substring(0, 8).toLongOrNull(16) ?: return null

        val pids = mutableSetOf<Int>()
        for (bit in 0 until 32) {
            if ((mask and (0x80000000L ushr bit)) != 0L) pids.add(base + bit + 1)
        }
        return SupportBank(pids = pids, hasNextBank = pids.contains(base + 0x20))
    }
}

data class WideRangeReading(
    val lambda: Double,
    /** Null when the adapter returned only the lambda word. */
    val currentMa: Double?,
)

data class SupportBank(
    val pids: Set<Int>,
    val hasNextBank: Boolean,
)

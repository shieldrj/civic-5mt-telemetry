package com.shieldrj.civic5mt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The protocol layer, which had no tests at all in the TypeScript build.
 *
 * Not for lack of care - it was unreachable without a Bluetooth stack and a car. That is
 * precisely why it is worth covering now: both of its worst failures present as a healthy
 * connection with gauges sitting on their defaults, so neither is visible from the driver's
 * seat until a reading is quietly wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProtocolTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("PID parsers, against bytes this car actually returned")
    inner class Parsers {

        @Test
        fun `RPM decodes from the two-byte quarter-count`() {
            // 0x1AF8 = 6904 quarter-revs = 1726 rpm
            assertEquals(1726.0, PidParsers.rpm("41 0C 1A F8"))
        }

        @Test
        fun `Speed is a single byte of km per hour`() {
            assertEquals(90.0, PidParsers.speedKmh("410D5A"))
        }

        @Test
        fun `MAF decodes to grams per second`() {
            assertEquals(28.4, PidParsers.maf("4110 0B18"))
        }

        @Test
        fun `Throttle decodes as a percentage of full scale`() {
            assertEquals(14.1, PidParsers.throttlePercent("41 11 24"))
        }

        @Test
        fun `Coolant carries the 40 degree offset`() {
            assertEquals(85.0, PidParsers.coolantC("4105 7D"))
        }

        @Test
        fun `Fuel trim is signed either side of 128`() {
            assertEquals(0.0, PidParsers.shortTermFuelTrim("410680"))
            assertEquals(-100.0, PidParsers.shortTermFuelTrim("410600"))
        }

        @Test
        fun `Control module voltage decodes in millivolts`() {
            // The 12.45 V seen at a warm idle - low for a running engine, which is a
            // charging-system question rather than a decoding one.
            assertEquals(12.45, PidParsers.batteryVoltage("4142 30A2"))
        }

        @Test
        fun `PID 34 yields lambda and sensor current together`() {
            val r = PidParsers.wideRangeO2("41 34 84 3D 7F F5")
            assertEquals(1.033, r?.lambda)
            assertEquals(-0.04, r?.currentMa)
        }

        @Test
        fun `PID 34 still yields lambda when the adapter sends only the first word`() {
            // Current is optional; a short reply must not throw away a usable lambda.
            val r = PidParsers.wideRangeO2("4134843D")
            assertEquals(1.033, r?.lambda)
            assertNull(r?.currentMa)
        }

        @Test
        fun `Outside air keeps the source it came from`() {
            assertEquals(25.0, PidParsers.outsideAirC("4146 41", OutsideAirSource.AMBIENT))
            assertEquals(25.0, PidParsers.outsideAirC("410F 41", OutsideAirSource.INTAKE))
        }

        @Test
        fun `Intake air is not accepted as an answer to the ambient question`() {
            // Different prefixes on purpose: 0F is engine-bay heat after a few minutes of
            // idling, not weather, and must never be read as though it were PID 46.
            assertNull(PidParsers.outsideAirC("410F41", OutsideAirSource.AMBIENT))
        }

        @Test
        fun `A reply with the wrong prefix yields null rather than a number`() {
            // The desync failure looks exactly like this: an answer to some other command.
            assertNull(PidParsers.rpm("410D5A"))
        }

        @Test
        fun `NO DATA yields null`() {
            assertNull(PidParsers.rpm("NO DATA"))
        }

        @Test
        fun `A truncated reply yields null rather than half a reading`() {
            assertNull(PidParsers.rpm("410C1A"))
        }

        @Test
        fun `The bus is only alive when the ECU actually answers 0100`() {
            assertTrue(PidParsers.isBusAlive("41 00 BE 3E B8 11"))
            assertFalse(PidParsers.isBusAlive("NO DATA"))
            assertFalse(PidParsers.isBusAlive("SEARCHING..."))
            assertFalse(PidParsers.isBusAlive(""))
        }

        @Test
        fun `A support bitmap reports its PIDs and whether another bank follows`() {
            val bank = PidParsers.supportBitmap("4100 80000001", 0x00, "4100")
            assertEquals(setOf(0x01, 0x20), bank?.pids)
            assertTrue(bank!!.hasNextBank, "the low bit is the continuation flag")

            val last = PidParsers.supportBitmap("4120 80000000", 0x20, "4120")
            assertEquals(setOf(0x21), last?.pids)
            assertFalse(last!!.hasNextBank)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Command queue, timeout and drain")
    inner class CommandDiscipline {

        @Test
        fun `A reply resolves the command that asked for it`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = { "41 0C 1A F8\r>" }
            val client = Elm327Client(t)

            // The '>' prompt rides along on the raw reply, as it did in the TypeScript -
            // it is the framing, and every parser strips it. Asserting through the parser
            // rather than on the raw string says the thing that actually matters.
            val reply = client.sendCommand("010C")
            assertEquals(1726.0, PidParsers.rpm(reply), "raw reply was: $reply")
            assertEquals(listOf("010C"), t.written)
        }

        @Test
        fun `A command that is never answered returns empty rather than throwing`() = runTest {
            // One timed-out PID must not tear down the poll loop.
            val t = FakeObdTransport()
            t.autoRespond = { null }
            val client = Elm327Client(t)

            assertEquals("", client.sendCommand("010C", timeoutMs = 100))
        }

        @Test
        fun `A late reply is discarded instead of answering the next command`() = runTest {
            // The failure this whole discipline exists to prevent. On a stream with no
            // request IDs, a reply that arrives after its command timed out is
            // indistinguishable from the next command's reply - and once one answer is
            // adopted by the wrong command, every answer after it is off by one, which
            // parses as nothing and leaves every gauge on its last good value.
            val t = FakeObdTransport()
            t.autoRespond = { null }
            val client = Elm327Client(t)

            assertEquals("", client.sendCommand("010C", timeoutMs = 100))

            // The adapter finally answers the command that already gave up.
            t.emit("41 0C 1A F8\r>")

            // Next command gets its own answer, not the stale one.
            t.autoRespond = { if (it == "010D") "41 0D 5A\r>" else null }
            val speed = client.sendCommand("010D", timeoutMs = 1000)

            assertEquals(90.0, PidParsers.speedKmh(speed), "raw reply was: $speed")
            assertNull(
                PidParsers.rpm(speed),
                "the speed request must not have been satisfied by the stale RPM reply",
            )
        }

        @Test
        fun `The late reply is recorded as late rather than silently dropped`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = { null }
            val client = Elm327Client(t)

            client.sendCommand("010C", timeoutMs = 100)
            t.emit("41 0C 1A F8\r>")

            assertTrue(
                client.protocolLog.value.any { it.cmd == "(late)" },
                "a discarded reply should still be visible in the adapter log",
            )
        }

        @Test
        fun `Commands issued concurrently are serialised, not interleaved`() = runTest {
            // Two callers share this client - the poll loop and the DTC scanner. Writing at
            // once does not interleave, it permanently swaps replies and the adapter aborts
            // the half-written one with STOPPED.
            val t = FakeObdTransport()
            val inFlight = mutableListOf<String>()
            var maxConcurrent = 0
            t.autoRespond = { cmd ->
                inFlight += cmd
                maxConcurrent = maxOf(maxConcurrent, inFlight.size)
                inFlight -= cmd
                "41${cmd.substring(2)} 00\r>"
            }
            val client = Elm327Client(t)

            val a = launch { client.sendCommand("010C") }
            val b = launch { client.sendCommand("0105") }
            a.join(); b.join()

            assertEquals(1, maxConcurrent, "only one command may be on the wire at a time")
            assertEquals(2, t.written.size)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Handshake and PID resolution")
    inner class Handshake {

        /**
         * Support bitmaps shaped like this car: PID 0F and PID 34 present, PIDs 24, 46 and
         * 14 absent. Bank 0 sets its continuation bit, bank 0x20 does not.
         */
        private fun civicLikeResponder(): (String) -> String? = { cmd ->
            when (cmd) {
                "AT Z" -> "ELM327 v1.5\r>"
                "0100" -> "41 00 00020001\r>"
                "0120" -> "41 20 00001000\r>"
                else -> "OK\r>"
            }
        }

        @Test
        fun `The handshake resolves lambda to 34 and outside air to 0F on this car`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = civicLikeResponder()
            val client = Elm327Client(t)

            client.connect()

            assertEquals(0x34, client.lambdaPid, "no PID 24, so lambda comes from 34")
            assertEquals(0x34, client.preCatPid, "no narrowband 14, so the pre-cat trace is 34 too")
            assertEquals(0x0f, client.outsideAirPid, "no PID 46, so outside air falls back to intake")
        }

        @Test
        fun `PID enumeration stops at the first bank with no continuation bit`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = civicLikeResponder()
            val client = Elm327Client(t)

            client.connect()

            assertFalse(t.written.contains("0140"), "bank 0x20 cleared its continuation bit")
            assertContains(client.supportedPidSnapshot(), 0x34)
            assertContains(client.supportedPidSnapshot(), 0x0f)
            assertFalse(client.supportedPidSnapshot().contains(0x24))
        }

        @Test
        fun `An adapter that never answers the reset says so, rather than proceeding`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = { null }
            val client = Elm327Client(t)

            val error = assertFailsWith<ObdTransportError> { client.connect() }
            assertTrue(
                error.message!!.contains("OBDLink app"),
                "the usual cause is another app holding the link, and the message should say so",
            )
        }

        @Test
        fun `A silent car is reported as ignition-off, not as a working connection`() = runTest {
            // The adapter is powered by the port and stays awake on its own, so it pairs and
            // answers ATs happily while the ECU is asleep. Setting a protocol and going
            // straight to polling made that look identical to a working connection.
            val t = FakeObdTransport()
            t.autoRespond = { cmd -> if (cmd == "0100") "NO DATA\r>" else "OK\r>" }
            val client = Elm327Client(t)

            val error = assertFailsWith<ObdTransportError> { client.connect() }
            assertTrue(error.message!!.contains("ignition"), "got: ${error.message}")
        }

        @Test
        fun `Protocol 7 is tried before 6, because this car answers on 7`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = civicLikeResponder()
            val client = Elm327Client(t)

            client.connect()

            val order = t.written.filter { it.startsWith("AT SP") }
            assertEquals(listOf("AT SP 7"), order, "7 answered, so 6 and auto-detect never ran")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Poll loop")
    inner class Polling {

        @Test
        fun `A PID the car does not answer leaves the previous reading alone`() = runTest {
            // And specifically does not write a plausible default over it.
            val t = FakeObdTransport()
            t.autoRespond = { cmd ->
                when (cmd) {
                    "AT Z" -> "ELM327 v1.5\r>"
                    // Bank 0 reports PIDs 05, 0C, 0F and the continuation bit; bank 0x20
                    // reports 34 and stops. Coolant (05) is deliberately *supported* here so
                    // that it is genuinely asked for and genuinely not answered - a PID the
                    // bitmaps exclude would never be sent, which tests something else.
                    "0100" -> "41 00 08120001\r>"
                    "0120" -> "41 20 00001000\r>"
                    "010C" -> "41 0C 1A F8\r>" // RPM answers
                    "0105" -> "NO DATA\r>" // Coolant is asked, and refuses
                    else -> "OK\r>"
                }
            }
            val client = Elm327Client(t)
            client.connect()

            val job = launch { client.runPollLoop(idleDelayMs = 1) }
            advanceTimeBy(200)
            client.stopPolling()
            job.cancelAndJoin()

            val data = client.data.value
            assertEquals(1726.0, data.rpm, "the PID that answered updated")
            assertEquals(85.0, data.coolantC, "the PID that did not kept its previous value")
            assertNull(data.lambda, "and a reading that has never arrived stays absent, not seeded")
            assertNull(data.ambientC)
            assertNull(data.ambientSource)
        }

        @Test
        fun `Polling gives up on an adapter that has gone quiet without dropping`() = runTest {
            // The failure that actually happens on this car. The ignition goes off, the MX+
            // drops into low power, and the Bluetooth socket stays open while every request
            // times out - so nothing reports a disconnect and the loop had no reason to stop.
            // A real session left connected in a car park ran two hours that way.
            val clock = MutableClock(1_700_000_000_000)
            val t = FakeObdTransport()
            var answering = true
            t.autoRespond = { cmd ->
                // Every command costs wall-clock time whether or not it is answered. Driving
                // the clock from here is what lets a thirty-second silence happen in a test
                // that finishes instantly.
                clock.advanceMillis(if (answering) 60 else ObdTimeouts.PID_MS)
                if (answering) "41 0C 1A F8\r>" else null
            }
            val client = Elm327Client(t, clock)

            val job = launch { client.runPollLoop(idleDelayMs = 1) }
            advanceTimeBy(200)
            assertTrue(client.isPolling, "still talking")

            answering = false
            advanceTimeBy(120_000)
            job.cancelAndJoin()

            assertFalse(client.isPolling, "gave up on a silent adapter")
            assertTrue(client.wentSilent, "and knows why, so the driver can be told")
        }

        @Test
        fun `An occasional timed-out PID is not a silent adapter`() = runTest {
            // A car that answers eleven of twelve requests is a car that is talking. Ending
            // the drive over one dropped reply would end it in a tunnel, at a red light, and
            // any time the bus was busy.
            val clock = MutableClock(1_700_000_000_000)
            val t = FakeObdTransport()
            var n = 0
            t.autoRespond = { _ ->
                n++
                clock.advanceMillis(if (n % 12 == 0) ObdTimeouts.PID_MS else 60)
                if (n % 12 == 0) null else "41 0C 1A F8\r>"
            }
            val client = Elm327Client(t, clock)

            val job = launch { client.runPollLoop(idleDelayMs = 1) }
            advanceTimeBy(120_000)

            assertTrue(client.isPolling, "kept polling through the odd timeout")
            assertFalse(client.wentSilent)
            client.stopPolling()
            job.cancelAndJoin()
        }

        @Test
        fun `Polling stops when the transport reports the link dropped`() = runTest {
            val t = FakeObdTransport()
            t.autoRespond = { "OK\r>" }
            val client = Elm327Client(t)

            val job = launch { client.runPollLoop(idleDelayMs = 1) }
            advanceTimeBy(50)
            assertTrue(client.isPolling)

            t.dropLink()
            advanceTimeBy(50)
            job.cancelAndJoin()

            assertFalse(client.isPolling)
        }
    }
}

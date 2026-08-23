package com.shieldrj.civic5mt.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Diagnostic scanning.
 *
 * The decoding is pure here, which is the point: in the TypeScript these were private methods
 * on a class holding a Bluetooth manager, so a fault code could only be decoded by driving to
 * a car with a fault. Reading a code wrong is a particularly expensive kind of wrong - it is
 * the number someone types into a parts website.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DtcScannerTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Decoding fault codes")
    inner class Decoding {

        @Test
        fun `A single confirmed code decodes to its SAE number`() {
            // 0x01 0x33 -> P0133, which is in the Honda table.
            val codes = DtcCodec.decodeDtcResponse("43 01 33", DtcStatusType.CONFIRMED)
            assertEquals(1, codes.size)
            assertEquals("P0133", codes[0].code)
            assertEquals(DtcStatusType.CONFIRMED, codes[0].type)
        }

        @Test
        fun `A known code carries its Civic-specific notes`() {
            val codes = DtcCodec.decodeDtcResponse("4301 71", DtcStatusType.CONFIRMED)
            assertEquals("P0171", codes[0].code)
            assertNotNull(
                codes[0].details.civicSpecificNotes,
                "P0171 is the intake-duct crack; that note is why the table exists",
            )
        }

        @Test
        fun `Several codes in one reply all decode`() {
            val codes = DtcCodec.decodeDtcResponse("4301330171 2646", DtcStatusType.CONFIRMED)
            assertEquals(listOf("P0133", "P0171", "P2646"), codes.map { it.code })
        }

        @Test
        fun `Padding pairs are not codes`() {
            val codes = DtcCodec.decodeDtcResponse("43 01 33 00 00 00 00", DtcStatusType.CONFIRMED)
            assertEquals(listOf("P0133"), codes.map { it.code })
        }

        @Test
        fun `Every letter prefix decodes from the top two bits`() {
            assertEquals("P0133", DtcCodec.formatDtc(0x01, 0x33))
            assertEquals("C0300", DtcCodec.formatDtc(0x43, 0x00))
            assertEquals("B1200", DtcCodec.formatDtc(0x92, 0x00))
            assertEquals("U0100", DtcCodec.formatDtc(0xC1, 0x00))
        }

        @Test
        fun `A Honda proprietary code with a letter in it round-trips`() {
            // P145C: 0x14 gives P, type 1, digit 4; 0x5C gives 5C.
            assertEquals("P145C", DtcCodec.formatDtc(0x14, 0x5C))
            assertTrue(HONDA_DTC_DATABASE.containsKey("P145C"))
        }

        @Test
        fun `NO DATA is no codes, not an unreadable one`() {
            assertTrue(DtcCodec.decodeDtcResponse("NO DATA", DtcStatusType.CONFIRMED).isEmpty())
            assertTrue(DtcCodec.decodeDtcResponse("", DtcStatusType.PENDING).isEmpty())
        }

        @Test
        fun `A reply for a different mode is not read as this one`() {
            // The pending reply carries 47; asking for confirmed codes must not find any.
            assertTrue(DtcCodec.decodeDtcResponse("47 01 33", DtcStatusType.CONFIRMED).isEmpty())
            assertEquals(1, DtcCodec.decodeDtcResponse("47 01 33", DtcStatusType.PENDING).size)
        }

        @Test
        fun `An unknown code still gets an entry, and it does not invent Civic notes`() {
            // The generic fallback must be visibly generic. A plausible-sounding invented
            // cause would be indistinguishable from the researched ones.
            val codes = DtcCodec.decodeDtcResponse("43 07 65", DtcStatusType.CONFIRMED)
            assertEquals("P0765", codes[0].code)
            assertNull(codes[0].details.civicSpecificNotes)
            assertTrue(codes[0].details.system.contains("General"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("The check engine light and readiness")
    inner class MilAndMonitors {

        @Test
        fun `The light reads from bit 7 of byte A`() {
            assertTrue(DtcCodec.parseMilStatus("41 01 81 07 65 00"), "0x81 has bit 7 set")
            assertFalse(DtcCodec.parseMilStatus("41 01 01 07 65 00"), "0x01 does not")
        }

        @Test
        fun `An unreadable reply is not a car with the light off`() {
            assertFalse(DtcCodec.parseMilStatus("NO DATA"))
        }

        @Test
        fun `Monitors decode from the same reply the light comes from`() {
            val monitors = DtcCodec.parseReadinessMonitors("41 01 00 07 E5 00")
            assertEquals(MonitorState.READY, monitors.misfire)
            assertEquals(MonitorState.READY, monitors.fuelSystem)
        }

        @Test
        fun `An unreadable reply reports N-A, never all-Ready`() {
            // This is the reading someone takes to a smog check. An unanswered ECU rendering
            // as "every self-test passed" is the worst possible way to be wrong here.
            assertEquals(UNKNOWN_MONITORS, DtcCodec.parseReadinessMonitors("NO DATA"))
            assertEquals(UNKNOWN_MONITORS, DtcCodec.parseReadinessMonitors("41 01 00"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Freeze frames")
    inner class Frames {

        @Test
        fun `A frame PID skips the mode echo and the frame number`() {
            // 42 0C 00 1A F8 -> mode+pid, frame 00, then the RPM word.
            assertEquals(listOf(0x1A, 0xF8), DtcCodec.parseFreezeFramePid("42 0C 00 1A F8", "0C"))
        }

        @Test
        fun `No stored frame yields null rather than zeroes`() {
            assertNull(DtcCodec.parseFreezeFramePid("NO DATA", "0C"))
            assertNull(
                DtcCodec.buildFreezeFrame(null, null, null, null, null, null),
                "a frame of zeroes reads as a fault that happened at 0 rpm and 0 mph",
            )
        }

        @Test
        fun `A frame decodes the conditions the fault was stored at`() {
            val frame = DtcCodec.buildFreezeFrame(
                rpm = listOf(0x1A, 0xF8), // 1726 rpm
                speed = listOf(0x42), // 66 km/h -> 41 mph
                coolant = listOf(0x7D), // 85 C -> 185 F
                load = listOf(0x60), // 37.6%
                stft = listOf(0x85), // +3.9%
                ltft = listOf(0x80), // 0%
            )
            assertNotNull(frame)
            assertEquals(1726, frame.rpm)
            assertEquals(41, frame.speedMph)
            assertEquals(185, frame.coolantTempF)
            assertEquals(37.6, frame.calcLoad)
            assertEquals(3.9, frame.fuelTrimSt)
            assertEquals(0.0, frame.fuelTrimLt)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("A whole scan")
    inner class FullScan {

        private fun scannerWith(responses: Map<String, String>): Pair<DtcScanner, FakeObdTransport> {
            val t = FakeObdTransport()
            t.autoRespond = { cmd -> (responses[cmd] ?: "NO DATA") + "\r>" }
            val client = Elm327Client(t)
            return DtcScanner(client, MutableClock(1_700_000_000_000)) to t
        }

        @Test
        fun `A clean car reports no codes and the light off`() {
            runTest {
                val (scanner, _) = scannerWith(
                    mapOf("0101" to "41 01 00 07 E5 00"),
                )
                val report = scanner.performFullScan()

                assertFalse(report.milOn)
                assertEquals(0, report.totalDtcCount)
                assertEquals(MonitorState.READY, report.monitors.misfire)
            }
        }

        @Test
        fun `A pending code is found even with the dashboard light off`() {
            // The whole reason Mode 07 is scanned: the fault that has happened once and not
            // yet lit the light is the one worth catching early.
            runTest {
                val (scanner, _) = scannerWith(
                    mapOf(
                        "0101" to "41 01 00 07 E5 00",
                        "07" to "47 01 33",
                    ),
                )
                val report = scanner.performFullScan()

                assertFalse(report.milOn, "no light")
                assertEquals(1, report.pendingCodes.size)
                assertEquals("P0133", report.pendingCodes[0].code)
                assertEquals(0, report.confirmedCodes.size)
            }
        }

        @Test
        fun `A confirmed code gets the freeze frame, and the others do not ask for one`() {
            runTest {
                val (scanner, transport) = scannerWith(
                    mapOf(
                        "0101" to "41 01 81 07 E5 00",
                        "03" to "43 01 71",
                        "020C00" to "42 0C 00 1A F8",
                        "020D00" to "42 0D 00 42",
                        "020500" to "42 05 00 7D",
                        "020400" to "42 04 00 60",
                    ),
                )
                val report = scanner.performFullScan()

                assertTrue(report.milOn)
                assertEquals("P0171", report.confirmedCodes[0].code)
                assertEquals(1726, report.confirmedCodes[0].freezeFrame?.rpm)
                assertTrue(transport.written.contains("020C00"), "the frame was requested")
            }
        }

        @Test
        fun `With no confirmed code the scan does not go looking for a frame`() {
            // Only a confirmed code stores one, so asking is six round trips spent to be told
            // NO DATA six times.
            runTest {
                val (scanner, transport) = scannerWith(
                    mapOf(
                        "0101" to "41 01 00 07 E5 00",
                        "0A" to "4A 04 56",
                    ),
                )
                val report = scanner.performFullScan()

                assertEquals(1, report.permanentCodes.size)
                assertEquals("P0456", report.permanentCodes[0].code)
                assertFalse(
                    transport.written.any { it.startsWith("02") },
                    "no freeze frame should have been requested",
                )
            }
        }

        @Test
        fun `Clearing codes is only reported as done when the ECU says so`() {
            runTest {
                val (ok, _) = scannerWith(mapOf("04" to "44"))
                assertTrue(ok.clearAllCodes())

                val (refused, _) = scannerWith(mapOf("04" to "NO DATA"))
                assertFalse(refused.clearAllCodes(), "a silent ECU has not cleared anything")
            }
        }
    }
}

package com.shieldrj.civic5mt.service

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoStartReceiverTest {

    private val obdAdapter = "00:04:3E:4A:12:34"
    private val carBluetooth = "00:26:E8:9B:56:78"
    private val headphones = "AA:BB:CC:DD:EE:FF"

    @Nested
    @DisplayName("Direct address matching")
    inner class AddressMatching {

        @Test
        @DisplayName("triggers when the OBD adapter itself connects")
        fun obdAdapterDirect() {
            assertTrue(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = obdAdapter,
                    connectedName = "OBDLink MX+",
                    deviceClass = null,
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = carBluetooth,
                )
            )
        }

        @Test
        @DisplayName("triggers when the explicitly configured car Bluetooth connects")
        fun explicitCarBluetooth() {
            assertTrue(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = carBluetooth,
                    connectedName = "HandsFreeLink",
                    deviceClass = null,
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = carBluetooth,
                )
            )
        }

        @Test
        @DisplayName("ignores unrelated devices when a car Bluetooth device is configured")
        fun ignoresOtherDevicesWhenCarConfigured() {
            assertFalse(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = headphones,
                    connectedName = "Pixel Buds Pro",
                    deviceClass = null,
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = carBluetooth,
                )
            )
        }
    }

    @Nested
    @DisplayName("Auto-detection when no car device is configured yet")
    inner class AutoDetection {

        @Test
        @DisplayName("detects Honda HandsFreeLink by name")
        fun detectsHandsFreeLink() {
            assertTrue(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = carBluetooth,
                    connectedName = "HandsFreeLink",
                    deviceClass = null,
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = null,
                )
            )
        }

        @Test
        @DisplayName("detects lowercase handsfreelink and civic names")
        fun detectsVariations() {
            val names = listOf(
                "handsfreelink",
                "Civic HandsFree",
                "Honda HFT",
                "Car Audio",
                "My CarKit",
                "CarBT Adapter",
            )
            for (name in names) {
                assertTrue(
                    AutoStartReceiver.isCarConnectionTrigger(
                        connectedAddress = carBluetooth,
                        connectedName = name,
                        deviceClass = null,
                        savedAdapterAddress = obdAdapter,
                        savedCarAddress = null,
                    ),
                    "Failed to detect car name: $name",
                )
            }
        }

        @Test
        @DisplayName("detects car audio by Bluetooth device class (0x0420)")
        fun detectsByBluetoothClass() {
            assertTrue(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = carBluetooth,
                    connectedName = "Custom BT Receiver",
                    deviceClass = 0x0420, // AUDIO_VIDEO_CAR_AUDIO
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = null,
                )
            )
        }

        @Test
        @DisplayName("ignores headphones and watches when auto-detecting")
        fun ignoresNonCarDevices() {
            val nonCarNames = listOf(
                "Pixel Buds Pro",
                "Galaxy Watch 6",
                "Sony WH-1000XM4",
                "Bose QC35",
                "iPad Pro",
                "Desktop PC",
            )
            for (name in nonCarNames) {
                assertFalse(
                    AutoStartReceiver.isCarConnectionTrigger(
                        connectedAddress = headphones,
                        connectedName = name,
                        deviceClass = 0x0404, // AUDIO_VIDEO_WEARABLE_HEADSET
                        savedAdapterAddress = obdAdapter,
                        savedCarAddress = null,
                    ),
                    "Should have ignored non-car device: $name",
                )
            }
        }

        @Test
        @DisplayName("ignores null or blank address")
        fun ignoresNullAddress() {
            assertFalse(
                AutoStartReceiver.isCarConnectionTrigger(
                    connectedAddress = null,
                    connectedName = "HandsFreeLink",
                    deviceClass = 0x0420,
                    savedAdapterAddress = obdAdapter,
                    savedCarAddress = null,
                )
            )
        }
    }

    @Nested
    @DisplayName("isCivicBluetoothName helper")
    inner class CivicNameCheck {

        @Test
        @DisplayName("identifies Civic and Honda names")
        fun validCivicNames() {
            assertTrue(AutoStartReceiver.isCivicBluetoothName("HandsFreeLink"))
            assertTrue(AutoStartReceiver.isCivicBluetoothName("HANDSFREELINK"))
            assertTrue(AutoStartReceiver.isCivicBluetoothName("2013 Civic"))
            assertTrue(AutoStartReceiver.isCivicBluetoothName("Honda Audio"))
            assertTrue(AutoStartReceiver.isCivicBluetoothName("Car Audio"))
        }

        @Test
        @DisplayName("rejects non-car names")
        fun invalidCivicNames() {
            assertFalse(AutoStartReceiver.isCivicBluetoothName("AirPods"))
            assertFalse(AutoStartReceiver.isCivicBluetoothName("JBL Flip 6"))
            assertFalse(AutoStartReceiver.isCivicBluetoothName(""))
            assertFalse(AutoStartReceiver.isCivicBluetoothName(null))
        }
    }
}

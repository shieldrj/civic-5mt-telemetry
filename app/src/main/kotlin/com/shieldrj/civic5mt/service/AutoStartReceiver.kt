package com.shieldrj.civic5mt.service

import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shieldrj.civic5mt.core.ConnectionStatus

/**
 * Starts logging the moment the car appears on Bluetooth.
 *
 * An OBD-II adapter operates strictly as a Bluetooth Serial Port Profile (SPP) peripheral;
 * it never initiates outgoing connections to a phone when powered on. Instead, when the driver
 * turns the ignition, the Civic's factory Bluetooth system (HandsFreeLink) or car stereo boots
 * up and connects to the phone automatically.
 *
 * This receiver catches that connection event (ACTION_ACL_CONNECTED). When the connected device
 * is identified as the Civic (either matching the chosen car Bluetooth, matching the OBD adapter,
 * or matching Honda HandsFreeLink / Car Audio signatures), the phone wakes up and launches
 * [TelemetryService] to connect to the OBDLink MX+.
 *
 * Starting a foreground service from the background is restricted on modern Android, and this
 * receiver is exactly that case. It works here because the app holds SYSTEM_ALERT_WINDOW for
 * the HUD, which Android accepts as evidence that a background service start is wanted.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!loadAutoConnect(context)) return

        val savedAdapter = loadLastAdapter(context) ?: return
        val savedCarAddress = loadCarBluetoothAddress(context)

        // Reading the device out of the intent needs BLUETOOTH_CONNECT; without it the extra
        // comes back null and there is no way to tell the car from headphones, so the
        // only honest move is to do nothing rather than connect to everything.
        val device: BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }

        val connectedAddress = try {
            device?.address
        } catch (e: SecurityException) {
            Log.w(TAG, "ACL_CONNECTED without BLUETOOTH_CONNECT; cannot identify device address")
            null
        }

        val connectedName = try {
            device?.name
        } catch (e: SecurityException) {
            null
        }

        val deviceClass = try {
            device?.bluetoothClass?.deviceClass
        } catch (e: SecurityException) {
            null
        }

        if (connectedAddress == null) return

        val isTrigger = isCarConnectionTrigger(
            connectedAddress = connectedAddress,
            connectedName = connectedName,
            deviceClass = deviceClass,
            savedAdapterAddress = savedAdapter,
            savedCarAddress = savedCarAddress,
        )

        if (!isTrigger) return

        // If matched via auto-detection and no specific car address was stored yet, remember it
        if (savedCarAddress == null && !connectedAddress.equals(savedAdapter, ignoreCase = true)) {
            Log.i(TAG, "Auto-detected Civic Bluetooth ($connectedName, $connectedAddress); saving as trigger")
            saveCarBluetooth(context, connectedAddress, connectedName ?: "Civic Bluetooth")
        }

        val current = TelemetryState.connection.value
        if (current == ConnectionStatus.CONNECTED ||
            current == ConnectionStatus.CONNECTING ||
            current == ConnectionStatus.SIMULATING ||
            current == ConnectionStatus.RECONNECTING
        ) {
            return
        }

        Log.i(TAG, "The Civic appeared on Bluetooth ($connectedName) - starting telemetry to $savedAdapter")

        // The exemption this relies on is a permission, and a permission can be absent. With
        // SYSTEM_ALERT_WINDOW not granted, startForegroundService from a broadcast throws
        // ForegroundServiceStartNotAllowedException - and an exception out of onReceive is a
        // crash. With the permission granted, it starts cleanly.
        runCatching { TelemetryService.connect(context.applicationContext, savedAdapter) }
            .onFailure {
                Log.w(
                    TAG,
                    "Not allowed to start the telemetry service from the background. Granting " +
                        "\"Draw over other apps\" is what lets auto-connect work without " +
                        "opening the app first.",
                    it,
                )
            }
    }

    companion object {
        private const val TAG = "AutoStartReceiver"

        /**
         * Checks whether a device name suggests a Honda Civic or car Bluetooth system.
         */
        fun isCivicBluetoothName(name: String?): Boolean {
            if (name.isNullOrBlank()) return false
            val lower = name.lowercase()
            return lower.contains("handsfreelink") ||
                lower.contains("handsfree") ||
                lower.contains("civic") ||
                lower.contains("honda") ||
                lower.contains("hft") ||
                lower.contains("car audio") ||
                lower.contains("caraudio") ||
                lower.contains("car kit") ||
                lower.contains("carkit") ||
                lower.contains("carbt")
        }

        /**
         * Decides whether the newly connected Bluetooth device represents the Civic starting up.
         */
        fun isCarConnectionTrigger(
            connectedAddress: String?,
            connectedName: String?,
            deviceClass: Int?,
            savedAdapterAddress: String?,
            savedCarAddress: String?,
        ): Boolean {
            if (connectedAddress.isNullOrBlank()) return false

            // 1. Direct connection to the OBD adapter itself (if an adapter or profile initiated it)
            if (savedAdapterAddress != null && connectedAddress.equals(savedAdapterAddress, ignoreCase = true)) {
                return true
            }

            // 2. Explicitly selected car Bluetooth device (e.g. HandsFreeLink)
            if (savedCarAddress != null) {
                return connectedAddress.equals(savedCarAddress, ignoreCase = true)
            }

            // 3. Auto-detection if no specific car Bluetooth device has been selected yet:
            // Matches Honda HandsFreeLink / Civic naming or the Car Audio Bluetooth class.
            if (isCivicBluetoothName(connectedName)) return true

            // BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO is 0x0420 (1056)
            if (deviceClass != null && deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) {
                return true
            }

            return false
        }
    }
}

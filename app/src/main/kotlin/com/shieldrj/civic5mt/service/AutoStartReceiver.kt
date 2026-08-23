package com.shieldrj.civic5mt.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.shieldrj.civic5mt.core.ConnectionStatus

/**
 * Starts logging the moment the car's adapter appears on Bluetooth.
 *
 * The OBDLink powers up with the ignition, and the phone - already bonded to it - connects
 * the way it connects to a car stereo. That connection is a broadcast any app may hear, which
 * makes it the whole cold-start story: no app opened, no button pressed, logging simply
 * running by the time the driver has found reverse.
 *
 * Starting a foreground service from the background is restricted on modern Android, and this
 * receiver is exactly that case. It works here because the app holds SYSTEM_ALERT_WINDOW for
 * the HUD, which Android accepts as evidence that a background service start is wanted.
 *
 * The address check matters: a phone is bonded to headphones, a watch and a stereo too, and
 * connecting to all of them would be worse than connecting to none.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        if (!loadAutoConnect(context)) return

        val saved = loadLastAdapter(context) ?: return

        // Reading the device out of the intent needs BLUETOOTH_CONNECT; without it the extra
        // comes back null and there is no way to tell the adapter from the stereo, so the
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
            Log.w(TAG, "ACL_CONNECTED without BLUETOOTH_CONNECT; cannot identify the device")
            null
        }

        if (connectedAddress == null || !connectedAddress.equals(saved, ignoreCase = true)) return

        val current = TelemetryState.connection.value
        if (current == ConnectionStatus.CONNECTED ||
            current == ConnectionStatus.CONNECTING ||
            current == ConnectionStatus.SIMULATING ||
            current == ConnectionStatus.RECONNECTING
        ) {
            return
        }

        Log.i(TAG, "The Civic's adapter appeared - starting telemetry")
        TelemetryService.connect(context.applicationContext, saved)
    }

    companion object {
        private const val TAG = "AutoStartReceiver"
    }
}

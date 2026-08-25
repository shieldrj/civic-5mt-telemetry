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
 * the HUD, which Android accepts as evidence that a background service start is wanted - so
 * the feature rests on a permission granted on a Settings screen, and revocable on the same
 * screen. See the start itself for what happens when it has been.
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

        // The exemption this relies on is a permission, and a permission can be absent. With
        // SYSTEM_ALERT_WINDOW not granted, startForegroundService from a broadcast throws
        // ForegroundServiceStartNotAllowedException - and an exception out of onReceive is a
        // crash. Which is the worst shape this could fail in: it happens on every ignition,
        // the driver never opened the app so the crash arrives unprompted, and it says nothing
        // about the HUD permission that would fix it.
        //
        // Caught rather than pre-checked against canDrawOverlays, because the exemption list
        // belongs to the platform and has changed between releases - whether the start was
        // allowed is the only honest test of it. Swallowed rather than surfaced, because there
        // is nothing on screen to surface it to. Connecting by hand still works, and the log
        // line says why it had to be by hand.
        runCatching { TelemetryService.connect(context.applicationContext, saved) }
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
    }
}

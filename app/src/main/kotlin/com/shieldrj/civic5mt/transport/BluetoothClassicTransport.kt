package com.shieldrj.civic5mt.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.shieldrj.civic5mt.core.ObdTransport
import com.shieldrj.civic5mt.core.ObdTransportError
import com.shieldrj.civic5mt.core.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Bluetooth Classic (RFCOMM / SPP) link to the OBD-II adapter.
 *
 * Lifted out of the Capacitor plugin it used to live in, which is all that changed: the
 * socket handling below, including the reflection fallback, is the code that has actually
 * worked against this adapter, so it was carried over rather than rewritten from the
 * documentation.
 *
 * It exists because the MX+ advertises Bluetooth Classic and not LE, and Web Bluetooth is
 * LE-only by specification - no browser can reach this adapter at all, which an LE scanner
 * finding every other device in the car but never this one confirmed.
 *
 * Deliberately thin: open a socket to an already-bonded device, pump bytes both ways, report
 * drops. Pairing stays in Android's own settings, where the OS wants the PIN exchange. The AT
 * handshake, the command queue and every parser are in `core`, with no Android anywhere near
 * them, so they can be tested against a fake.
 */
class BluetoothClassicTransport(
    private val context: Context,
    private val deviceAddress: String,
) : ObdTransport {

    override val kind = TransportKind.SPP
    override val label = "Bluetooth Classic (SPP)"

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var input: InputStream? = null
    private var readerThread: Thread? = null

    @Volatile
    private var running = false

    private var dataHandler: ((String) -> Unit)? = null
    private var disconnectHandler: (() -> Unit)? = null

    override fun setDataHandler(handler: (String) -> Unit) {
        dataHandler = handler
    }

    override fun setDisconnectHandler(handler: () -> Unit) {
        disconnectHandler = handler
    }

    @SuppressLint("MissingPermission") // Checked by hasConnectPermission below.
    override suspend fun connect() = withContext(Dispatchers.IO) {
        if (!hasConnectPermission()) {
            throw ObdTransportError(
                "Bluetooth permission has not been granted. Allow Nearby devices for this app " +
                    "in Android settings, then try again."
            )
        }

        val adapter = bluetoothAdapter()
            ?: throw ObdTransportError("This device has no Bluetooth hardware.")
        if (!adapter.isEnabled) {
            throw ObdTransportError("Bluetooth is switched off. Turn it on and try again.")
        }

        closeQuietly()

        val device = try {
            adapter.getRemoteDevice(deviceAddress)
        } catch (e: IllegalArgumentException) {
            throw ObdTransportError("Invalid Bluetooth address: $deviceAddress", e)
        }

        // Discovery is expensive and actively degrades an open RFCOMM link.
        runCatching { adapter.cancelDiscovery() }

        val opened = openSocket(device)
        socket = opened
        output = opened.outputStream
        input = opened.inputStream
        startReader()
    }

    /**
     * Opens the serial link, closing whatever it opened if it cannot use it.
     *
     * A socket that was created and then failed to connect still holds a file descriptor, and
     * `closeQuietly` cannot reach it - that only closes the fields, and nothing is assigned to
     * them until an attempt has succeeded. So each attempt closes its own socket on the way
     * out. This matters because failing here is the ordinary case, not the exceptional one:
     * every connect with the ignition off fails, and the reconnect loop tries repeatedly.
     * Leaking one descriptor per attempt is how a long stint of retries in a car park ends
     * with connects failing for a reason that has nothing to do with the car.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice): BluetoothSocket {
        var direct: BluetoothSocket? = null
        try {
            direct = device.createRfcommSocketToServiceRecord(SPP_UUID)
            direct.connect()
            return direct
        } catch (primaryFailure: IOException) {
            runCatching { direct?.close() }

            // Some adapters refuse the service-record route and only accept the
            // reflection-based channel-1 fallback. It is ugly and well known, and it is the
            // difference between working and not on a good number of clones.
            Log.w(TAG, "SPP service record failed, trying channel 1 fallback", primaryFailure)
            var fallback: BluetoothSocket? = null
            try {
                fallback = device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
                fallback.connect()
                return fallback
            } catch (fallbackFailure: Exception) {
                runCatching { fallback?.close() }
                throw ObdTransportError(
                    "Could not open a serial link to the adapter. Check the ignition is on and " +
                        "that no other app (including the OBDLink app) is holding the connection. " +
                        "(${primaryFailure.message})",
                    fallbackFailure,
                )
            }
        }
    }

    override suspend fun write(text: String) = withContext(Dispatchers.IO) {
        val stream = output ?: throw ObdTransportError("Not connected.")
        try {
            stream.write(text.toByteArray(Charsets.US_ASCII))
            stream.flush()
        } catch (e: IOException) {
            handleDropped()
            throw ObdTransportError("Write failed: ${e.message}", e)
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeQuietly()
    }

    /**
     * Reads continuously and forwards whatever arrives.
     *
     * Deliberately does not wait for the '>' prompt or assemble whole responses. That framing
     * lives in the protocol client, where it is covered by tests; doing it here as well would
     * be two implementations of the same rule, free to disagree.
     */
    private fun startReader() {
        running = true
        readerThread = thread(name = "obd-serial-reader", isDaemon = true) {
            val buffer = ByteArray(1024)
            while (running) {
                try {
                    val stream = input ?: break
                    val count = stream.read(buffer)
                    if (count < 0) break // End of stream: the adapter went away
                    if (count > 0) {
                        dataHandler?.invoke(String(buffer, 0, count, Charsets.US_ASCII))
                    }
                } catch (e: IOException) {
                    if (running) Log.w(TAG, "Read failed, link dropped", e)
                    break
                }
            }
            if (running) handleDropped()
        }
    }

    private fun handleDropped() {
        val wasRunning = running
        closeQuietly()
        if (wasRunning) disconnectHandler?.invoke()
    }

    private fun closeQuietly() {
        running = false
        readerThread?.interrupt()
        readerThread = null

        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }

    private fun bluetoothAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ObdSerial"

        /** The well-known Serial Port Profile UUID. Every ELM327-style adapter exposes this. */
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /**
         * Adapters Android has already been paired with.
         *
         * Bonded only: an unpaired adapter cannot be opened without a PIN exchange, and that
         * belongs in Android's settings rather than in this app.
         */
        @SuppressLint("MissingPermission")
        fun pairedAdapters(context: Context): List<PairedDevice> {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return emptyList()
            }
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
                ?.adapter ?: return emptyList()
            return runCatching {
                adapter.bondedDevices.map { PairedDevice(it.name ?: "(unnamed)", it.address) }
            }.getOrDefault(emptyList())
        }
    }
}

data class PairedDevice(val name: String, val address: String)

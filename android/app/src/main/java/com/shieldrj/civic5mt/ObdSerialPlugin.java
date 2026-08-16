package com.shieldrj.civic5mt;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import org.json.JSONArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * Bluetooth Classic (RFCOMM / SPP) bridge for OBD-II adapters.
 *
 * This exists because the OBDLink MX+ advertises Bluetooth Classic and not Bluetooth LE.
 * Web Bluetooth is LE-only by specification, so no browser can reach the adapter at all -
 * confirmed by an LE scanner finding plenty of other devices but never this one. RFCOMM is
 * the transport it actually speaks, and it is only reachable from native code.
 *
 * Deliberately thin: open a socket to an already-bonded device, pump bytes both ways, and
 * report drops. Pairing stays in Android's own settings, where the OS wants the PIN
 * exchange to happen. Everything above this - the AT handshake, PID polling, parsing - is
 * the shared TypeScript that already runs against the simulator.
 */
@CapacitorPlugin(
    name = "ObdSerial",
    permissions = {
        @Permission(alias = "bluetooth", strings = { Manifest.permission.BLUETOOTH_CONNECT })
    }
)
public class ObdSerialPlugin extends Plugin {

    private static final String TAG = "ObdSerial";

    /** The well-known Serial Port Profile UUID. Every ELM327-style adapter exposes this. */
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private OutputStream output;
    private InputStream input;
    private Thread readerThread;
    private volatile boolean running = false;

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject result = new JSObject();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter == null) {
            result.put("available", false);
            result.put("reason", "This device has no Bluetooth hardware.");
        } else if (!adapter.isEnabled()) {
            result.put("available", false);
            result.put("reason", "Bluetooth is switched off. Turn it on and try again.");
        } else if (!hasConnectPermission()) {
            result.put("available", false);
            result.put("reason", "Bluetooth permission not granted. Allow Nearby devices for this app in Android settings.");
        } else {
            result.put("available", true);
        }
        call.resolve(result);
    }

    /**
     * Bonded devices only. An unpaired adapter cannot be opened without a PIN exchange,
     * which belongs in Android's settings rather than in this app.
     */
    @PluginMethod
    public void listPairedDevices(PluginCall call) {
        if (!hasConnectPermission()) {
            requestPermissionForAlias("bluetooth", call, "listPermissionCallback");
            return;
        }
        doListPairedDevices(call);
    }

    @PermissionCallback
    private void listPermissionCallback(PluginCall call) {
        if (!hasConnectPermission()) {
            call.reject("Bluetooth permission is required to see paired adapters.");
            return;
        }
        doListPairedDevices(call);
    }

    private void doListPairedDevices(PluginCall call) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            call.reject("This device has no Bluetooth hardware.");
            return;
        }

        JSONArray devices = new JSONArray();
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice device : bonded) {
                JSObject entry = new JSObject();
                String name = device.getName();
                entry.put("name", name != null ? name : "(unnamed)");
                entry.put("address", device.getAddress());
                devices.put(entry);
            }
        } catch (SecurityException e) {
            call.reject("Bluetooth permission denied: " + e.getMessage());
            return;
        }

        JSObject result = new JSObject();
        result.put("devices", devices);
        call.resolve(result);
    }

    @PluginMethod
    public void connect(PluginCall call) {
        if (!hasConnectPermission()) {
            // Android 12+ will not hand over a bonded device without this, and the prompt
            // has to be raised from native code - the web layer cannot ask for it.
            requestPermissionForAlias("bluetooth", call, "connectPermissionCallback");
            return;
        }
        doConnect(call);
    }

    @PermissionCallback
    private void connectPermissionCallback(PluginCall call) {
        if (!hasConnectPermission()) {
            call.reject(
                "Bluetooth permission denied. Allow Nearby devices for this app in Android settings, then try again."
            );
            return;
        }
        doConnect(call);
    }

    private void doConnect(PluginCall call) {
        String address = call.getString("address");
        if (address == null || address.isEmpty()) {
            call.reject("No device address supplied.");
            return;
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            call.reject("Bluetooth is unavailable or switched off.");
            return;
        }

        closeQuietly();

        try {
            BluetoothDevice device = adapter.getRemoteDevice(address);

            // Discovery is expensive and actively degrades an open RFCOMM link.
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException ignored) {
                // Not fatal
            }

            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (IOException primaryFailure) {
                // Some adapters refuse the service-record route and only accept the
                // reflection-based channel-1 fallback. It is ugly and well known, and it
                // is the difference between working and not on a good number of clones.
                Log.w(TAG, "SPP service record failed, trying channel 1 fallback", primaryFailure);
                closeQuietly();
                try {
                    socket = (BluetoothSocket) device
                        .getClass()
                        .getMethod("createRfcommSocket", new Class[] { int.class })
                        .invoke(device, 1);
                    socket.connect();
                } catch (Exception fallbackFailure) {
                    closeQuietly();
                    call.reject(
                        "Could not open a serial link to the adapter. Check the ignition is on and that no other app "
                            + "(including the OBDLink app) is holding the connection. ("
                            + primaryFailure.getMessage() + ")"
                    );
                    return;
                }
            }

            output = socket.getOutputStream();
            input = socket.getInputStream();
            startReader();
            call.resolve();
        } catch (SecurityException e) {
            closeQuietly();
            call.reject("Bluetooth permission denied: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            closeQuietly();
            call.reject("Invalid Bluetooth address: " + address);
        } catch (IOException e) {
            closeQuietly();
            call.reject("Failed to open streams: " + e.getMessage());
        }
    }

    @PluginMethod
    public void write(PluginCall call) {
        String data = call.getString("data");
        if (data == null) {
            call.reject("No data supplied.");
            return;
        }
        OutputStream stream = output;
        if (stream == null) {
            call.reject("Not connected.");
            return;
        }
        try {
            stream.write(data.getBytes(StandardCharsets.US_ASCII));
            stream.flush();
            call.resolve();
        } catch (IOException e) {
            call.reject("Write failed: " + e.getMessage());
            handleDropped();
        }
    }

    @PluginMethod
    public void disconnect(PluginCall call) {
        closeQuietly();
        call.resolve();
    }

    /**
     * Reads continuously and forwards whatever arrives. Deliberately does NOT wait for the
     * '>' prompt or assemble whole responses - that framing already exists in the shared
     * TypeScript, and duplicating it here would give two implementations to keep in step.
     */
    private void startReader() {
        running = true;
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[1024];
            while (running) {
                try {
                    InputStream stream = input;
                    if (stream == null) break;
                    int count = stream.read(buffer);
                    if (count < 0) break; // End of stream: the adapter went away
                    if (count > 0) {
                        String chunk = new String(buffer, 0, count, StandardCharsets.US_ASCII);
                        JSObject event = new JSObject();
                        event.put("data", chunk);
                        notifyListeners("data", event);
                    }
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Read failed, link dropped", e);
                    break;
                }
            }
            if (running) handleDropped();
        }, "obd-serial-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleDropped() {
        boolean wasRunning = running;
        closeQuietly();
        if (wasRunning) notifyListeners("disconnected", new JSObject());
    }

    private boolean hasConnectPermission() {
        // BLUETOOTH_CONNECT only exists from Android 12. Earlier releases grant the old
        // BLUETOOTH permission at install time, so there is nothing to check there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED;
    }

    private void closeQuietly() {
        running = false;
        Thread reader = readerThread;
        readerThread = null;
        if (reader != null) reader.interrupt();

        try { if (input != null) input.close(); } catch (IOException ignored) {}
        try { if (output != null) output.close(); } catch (IOException ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        input = null;
        output = null;
        socket = null;
    }

    @Override
    protected void handleOnDestroy() {
        closeQuietly();
    }
}

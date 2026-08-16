# 2013 Honda Civic 5MT Telemetry & Performance Dashboard

An automotive-grade Progressive Web App (PWA) specifically built for the **2013 Honda Civic LX (5-Speed Manual, 1.8L SOHC i-VTEC R18Z1)** connecting to the **Vgate vLinker MC+** OBD-II adapter via Bluetooth LE / Web Bluetooth.

## Features
- **🔍 Full OBD-II Diagnostic Scanner (DTC)**:
  - **Mode 07 (Pending Codes)**: Catch early sensor anomalies and glitches *before* they turn on the Check Engine Light.
  - **Mode 03 (Confirmed Codes)**: Active CEL fault diagnostics.
  - **Mode 0A (Permanent/Historic Codes)**: Non-volatile ECU historical fault memory.
  - **Freeze Frame Data**: View snapshot of RPM, Speed, Coolant, Load, and Fuel Trims at the moment a fault was logged.
  - **Mode 04**: One-touch code clearing & MIL reset with safety verification.
- **Physics-Grade MPG Engine**: MAF + Wideband Lambda + Fuel Trims with Deceleration Fuel Cut-Off (DFCO) detection and idle fuel waste dollar counter.
- **Multi-Factor Oil Life Algorithm**: Tracks mechanical revolutions ($\int \text{RPM} \, dt$), cold starts (<160°F), short-trip dilution, and high-RPM thermal stress with persistent storage.
- **5-Speed Manual Dynamics**: Real-time gear detection (1st–5th, Neutral, Clutch), clutch slip warning, and Formula-1 / Eco progressive shift lights.
- **Virtual ECU Driving Bench**: Built-in interactive simulator for testing all gauges, shift lights, and diagnostic tools anywhere.

## Quick Start

### 1. Run the App
```bash
npm run dev
```

### 2. Open on Android Phone
Ensure your phone is connected to the same Wi-Fi network and open Chrome:
```
http://192.168.1.170:5173/
```

### 3. Install to Android Home Screen
In Chrome on Android, tap the 3-dot menu → **"Add to Home screen"** or **"Install app"** to run full-screen without browser bars.

### 4. Connect to Vgate vLinker MC+
Plug the vLinker into your OBD-II port, turn on the ignition, tap **CONNECT** in the top right of the dashboard, and select your vLinker from the Bluetooth scan prompt.

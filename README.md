# 2013 Honda Civic 5MT Telemetry & Diagnostics (Android)

A high-performance, native Android application built in **Kotlin** and **Jetpack Compose** specifically designed for the **2013 Honda Civic LX (5-Speed Manual, 1.8L SOHC i-VTEC R18Z1)** connecting to the **OBDLink MX+** OBD-II adapter via Bluetooth Classic (RFCOMM / SPP).

---

## Key Features

- **🏎️ Drive Dashboard & Shift Light System**:
  - Hero **Tank MPG** radial gauge with real-time range estimation and miles-since-fill tracking.
  - Progressive F1 & Eco shift cues with clutch-slip detection and dynamic gear calculation (1st–5th, Neutral, Coasting).
  - High-precision engine metrics (Coolant temperature, Air:Fuel ratio, Wide-range Lambda from PID 34, Battery voltage, Intake air).
  - Floating **Heads-Up Display (HUD) Overlay** option over navigation apps (e.g. Google Maps).

- **🔋 Foreground Telemetry Service**:
  - Uninterrupted telemetry logging with the screen off or app backgrounded via Android Foreground Service.
  - Automatic reconnection policy and link loss recovery with zero trip data loss.

- **🗄️ Trip History & Analytics**:
  - Full trip database powered by **Room DB** with live CSV export capabilities.
  - Permanent lifetime records and automatic localStorage data migration from previous builds.

- **🧪 Physics-Grade Fuel & Oil Engines**:
  - Multi-factor oil degradation algorithm tracking cumulative crank revolutions ($\int \text{RPM} \, dt$), cold starts (<160°F), short trips, and thermal stress.
  - Comprehensive fuel models supporting custom ethanol blends (E10/E15/E85), Deceleration Fuel Cut-Off (DFCO), and idle fuel waste dollar counters.
  - **Costco pump prices** at San Dimas, Chino Hills and Burbank on the Fuel tab, cheapest first, cached on the phone so the last figures are there with no signal.

- **🔍 Full OBD-II Diagnostic Scanner (DTC)**:
  - **Mode 07** (Pending Codes), **Mode 03** (Confirmed Codes), **Mode 0A** (Permanent Codes).
  - Freeze Frame snapshot capture and safety-verified **Mode 04** clearing with readiness monitor status.

- **🕹️ Built-in ECU Simulator**:
  - Virtual driving bench reproducing real 2013 Civic PID streams for offline development and testing.

---

## Architecture

The project is structured into two focused modules:

```
├── core/                # Pure Kotlin JVM module (Zero Android dependencies)
│   ├── src/main/kotlin  # Physics models, ELM327 protocol client, PID catalog, simulator
│   └── src/test/kotlin  # 97+ assertion test suite verified against real car byte captures
└── app/                 # Native Android Application (minSdk 29, compileSdk 36)
    ├── src/main/kotlin  # Jetpack Compose UI, Room Database, Telemetry Foreground Service, Bluetooth SPP
    └── src/main/res     # Vectors, themes, and rescued baseline datasets
```

---

## Building and Testing

### Prerequisites
- JDK 21 (Temurin or Adoptium recommended)
- Android SDK (`minSdk 29`, `compileSdk 36`)

### Run Tests
```bash
# Run physics models and core telemetry unit tests
./gradlew :core:test

# Run Android module unit tests
./gradlew :app:testDebugUnitTest
```

### Build Debug APK
```bash
./gradlew :app:assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Install to Device
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```


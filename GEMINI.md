# Civic 5MT Telemetry Project Guide

Android telemetry, diagnostics, and prognostics application for the **2013 Honda Civic LX 5MT (1.8L SOHC i-VTEC R18Z1)**.

## Build & Test Commands
- **Run all tests**: `./gradlew.bat test`
- **Run core physics tests only**: `./gradlew.bat :core:test`
- **Run app unit tests**: `./gradlew.bat :app:testDebugUnitTest`
- **Build debug APK**: `./gradlew.bat assembleDebug`
- **Connect phone wirelessly (1-click)**: Run `.\connect-phone.bat` or `.\connect-phone.ps1` (auto-discovers phone via mDNS and Wi-Fi scan).
- **Deploy and install to phone**: Run `powershell -ExecutionPolicy Bypass -File .\deploy.ps1`
- **Manual wireless connect**:
  ```powershell
  $env:ADB_MDNS_OPENSCREEN = "1"; adb connect <phone-ip>:<port>; adb -s <phone-ip>:<port> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

## Architecture & Code Structure
- **`core/`**: Pure Kotlin JVM module.
  - Zero Android framework dependencies (`android.*` imports are forbidden in `core/`).
  - Contains all physical models, kinematics, thermodynamic solvers, and predictive algorithms.
  - 100% deterministic test coverage with `MutableClock`.
- **`app/`**: Android application module.
  - Jetpack Compose UI, Material 3, dynamic Canvas rendering for gauges.
  - Background `TelemetryService` running as `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
  - Bluetooth SPP (Classic RFCOMM) link to OBDLink MX+.
  - Room database for trip analytics and SharedPreferences for profile persistence.

## Vehicle Physical Constants (2013 Civic 5MT)
- **Engine (R18Z1)**: 1.8L SOHC i-VTEC, 140 hp / 128 lb-ft (174 N·m). Hot idle 750 RPM; Cold/AC idle 1200–1400 RPM.
- **5MT Gearing**: 1st (3.143), 2nd (1.870), 3rd (1.235), 4th (0.949), 5th (0.727), Final Drive (4.294).
- **Tires**: 195/65R15 ($C_{\text{effective}} \approx 0.00193\text{ km}$).
- **Clutch (Exedy HCK1002)**: 215mm disc, 4500 N clamp force, $277\text{ N}\cdot\text{m}$ new clamp capacity, $500\text{ MJ}$ baseline lifetime friction budget.
- **Electrical**: Honda Dual-Mode Charging (ELD). 12.4–12.7V during steady warm cruise is normal alternator economy mode.

## Project Rules
1. **No Synthetic / Faked Metrics**: Unmeasured sensors remain null/unstated.
2. **Motion Gating**: Physical wear integrals must check `speed > 0`.
3. **Gear Latching on Slip**: Keep gear context engaged during throttle flaring.
4. **Minimal Overlay HUD**: `HudContent.kt` over Google Maps is kept clean and minimal; full metrics live on dedicated pages.

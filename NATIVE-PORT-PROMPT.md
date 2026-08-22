# Brief: port the Civic 5MT telemetry app from Capacitor to native Android

Paste this whole file, or point a session at it, to start the port. It is written for a
session with **no prior context** on this project. Read it before touching code.

---

## 1. What this is

`C:\Users\shiel\Projects\civic-5mt-telemetry` — remote `shieldrj/civic-5mt-telemetry`,
default branch `main`. A live OBD-II telemetry and diagnostics app for a **2013 Honda Civic LX,
5-speed manual, 1.8L R18Z1**, talking to an **OBDLink MX+** adapter. It is used in the actual
car, by the person asking for this work, on a Samsung SM-S948U1.

Today it is a **React + TypeScript + Vite PWA wrapped in Capacitor 8**, installed as an APK
(`com.shieldrj.civic5mt`). All telemetry logic is TypeScript running in a WebView. The Bluetooth
transport is already native Java.

**The goal is a real native Android app** so the app can do things the WebView makes hard or
impossible. Read §5 before assuming which of those things matter — that decision is the user's
and it drives the architecture.

## 2. Environment facts — do not rediscover these

They cost real time to find. All verified 2026-08-17.

**The repo has moved out of OneDrive** to `C:\Users\shiel\Projects\civic-5mt-telemetry`, which
should make the robocopy step below unnecessary — try building in place first. Everything from
here to the end of this subsection is kept as the reason it existed, and as the fallback if a
copy of the repo ever ends up back under a synced folder.

**Gradle could not build in place under OneDrive.** OneDrive turns files into cloud
reparse points. Gradle refuses to snapshot a reparse point and fails with
`Cannot snapshot <X>: not a regular file`. It is **not** limited to build output: 668 of 686
files under `android/`, all 366 under `node_modules/@capacitor/android`, and every file in
`dist/` were reparse points — including web assets written seconds earlier. They were already
pinned "always keep on this device" and were *still* reparse points, so pinning does not fix it.
Clearing one build directory just moves the failure to the next task.

Build outside OneDrive. From **PowerShell** (Git Bash rewrites `/MIR` into a path and robocopy
rejects it):

```powershell
robocopy "<repo>" "C:\Users\shiel\AppData\Local\Temp\hb" /MIR /MT:16 /NFL /NDL /NJH /NP /R:1 /W:1 `
  /XD "<repo>\.git" "<repo>\android\.gradle" "<repo>\android\app\build" `
      "<repo>\android\capacitor-cordova-android-plugins\build" `
      "<repo>\node_modules\@capacitor\android\capacitor\build"
```

~186 MB / 16k files, ~2.5 min. Robocopy exit 0–7 means success (1 = files copied). Then build in
the copy. A temp copy may already exist at that path with a warm Gradle cache.

**Toolchain is not on PATH and not in the environment:**

```
JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
ANDROID_HOME="/c/Users/shiel/AppData/Local/Android/Sdk"
adb: $ANDROID_HOME/platform-tools/adb.exe
```

Current Android config: `minSdk 24`, `compileSdk 36`, `targetSdk 36`, Gradle 8.14.3, Java only —
no Kotlin in the project yet.

**Installing to the phone.** Wireless debugging is used. `adb devices` is empty at first and
`adb mdns services` lists nothing, but adb auto-connects the already-paired phone a few seconds
later — wait and re-check rather than asking for an IP. Then `adb install -r <apk>`.

Two Git Bash hazards, both silent: MSYS rewrites device paths, so `adb shell screencap -p
/sdcard/x.png` fails — set `MSYS2_ARG_CONV_EXCL="*"`, but then the local `adb pull` destination
must be a Windows-style path. And `adb exec-out screencap -p > f.png` truncates over wireless,
producing a half-black PNG that looks like the screen is off; use `screencap` to `/sdcard` then
`pull`. Screenshots are 1440x3120 and need downscaling to ~800px before they can be read.

## 3. What already exists, and what is worth keeping

7,313 lines of TS/TSX. They are not equal in value.

**The domain logic is the asset.** Roughly 2,600 lines of pure TypeScript with no DOM
dependency, carrying real physics and real decisions, most of them commented with *why*:

| File | Lines | What it knows |
|---|---|---|
| `services/bluetooth/obdlinkBluetooth.ts` | 843 | ELM327/STN AT handshake, protocol negotiation, command queue, timeout-and-drain discipline, PID support bitmaps, polling tiers, every parser |
| `services/telemetryManager.ts` | 568 | The tick loop, trip analytics, lifetime record, persistence |
| `services/obd2/dtcScanner.ts` | 334 | Modes 03/07/0A, freeze frames, Mode 04 clearing |
| `services/obd2/pidCatalog.ts` | 296 | PID names, formulas, and per-car source resolution |
| `services/simulator/civicSimulator.ts` | 284 | A driving bench that mirrors this car's real PID set |
| `services/obd2/fuelModel.ts` | 269 | MAF+lambda MPG, DFCO, fuel blend chemistry, display damping |
| `services/obd2/oilLifeModel.ts` | 231 | 4-factor oil wear (revolutions, cold starts, short trips, thermal stress) |
| `services/obd2/dtcSpecs.ts` | 204 | Honda-specific DTC database |
| `services/obd2/gearCalculator.ts` | 170 | Gear deduction from ratio, clutch slip, shift points |
| `services/obd2/civicSpecs.ts` | 151 | Ratios, tyre circumference, tank size, fuel blends |
| `services/obd2/readinessMonitors.ts` | 95 | Emissions readiness bitmaps (shared by PID 01 and PID 41) |
| `services/obd2/integrationRules.ts` | 43 | Which time steps may be integrated into the permanent record |

**`src/tests/primetime_validation.ts` (451 lines, 97 assertions) is the specification.** It pins
gear ratios, fuel chemistry, DFCO, oil wear, readiness decoding, PID selection, and every PID
formula against literal bytes captured from the real car. Run it with `npm test`.

> **Port these assertions first, before porting the logic they describe.** They are the only
> thing that will tell you a Kotlin rewrite of the fuel model still computes what the
> TypeScript one did. A port that drops them converts tested physics into plausible physics.

**The Bluetooth transport is already native and survives nearly intact.**
`android/app/src/main/java/com/shieldrj/civic5mt/ObdSerialPlugin.java` (312 lines) is a
Bluetooth Classic RFCOMM/SPP bridge. It exists because the MX+ is Classic-only and Web Bluetooth
is LE-only by specification, so no browser can reach the adapter at all. It is deliberately thin
— open a socket to a bonded device, pump bytes, report drops — and includes the
reflection-based channel-1 fallback that some adapters require. Strip the `@CapacitorPlugin`
wrapper and the `JSObject`/`notifyListeners` plumbing and the logic is directly reusable.

`services/bluetooth/webBluetoothTransport.ts` (218 lines) is the browser LE path. **It can never
reach this adapter** and is dead weight in a native app.

**The UI is ~2,000 lines of React** across 11 components: a radial gauge, shift-light bar, and
the Drive / Fuel / Trip / Oil / Codes / Sim tabs. This is the part that must be genuinely
rewritten (Compose), and it is also the part most safely redesigned rather than transliterated.

## 4. Non-negotiable constraints

**4.1 — There is irreplaceable data in WebView localStorage.** A native app is a different
storage domain. If you do not migrate it, it is gone, and it cannot be regenerated because it
accumulated from real driving:

```
civic_2013_lifetime_stats_v2   lifetime MPG + lifetime miles  ← real OBD data only, permanent
civic_2013_oil_profile_v1      oil life: revolutions, cold starts, short trips, thermal stress
civic_2013_fuel_blend_v1       selected fuel blend
civic.pidDiscovery.lastScan.v1 last PID scan
```

Current values on the phone as of 2026-08-17: **lifetime 35.8 mpg over 65 mi, oil life 95%**.
Plan the migration path *before* the first native build lands on the phone — read the values out
of the WebView while the Capacitor app is still installed. Never `pm clear` this package.

**4.2 — This car's PID set is measured, not assumed, and the app was recently fixed to respect
that.** A real scan reports 38 PIDs. The car **does not have** PID 24 (wideband lambda),
PID 46 (ambient air) or PID 14 (pre-catalyst narrowband). It has **34** (wide-range lambda +
current) and **0F** (intake air) instead. The app previously polled 24/46/14 unconditionally and
each reading silently kept a plausible seeded default — lambda pinned at exactly `1.0`, which
also passed the fuel model's validity check and suppressed the fuel-trim fallback, so the
mixture readout was a constant derived from a PID the car does not implement.

The current design encodes this as preference lists resolved once against the support bitmaps,
with one shared resolver so the poll loop and the discovery screen cannot disagree. **Carry that
property into the port.** Do not reintroduce a fixed PID list, and do not seed a reading with a
plausible number — absent readings are nullable and the UI renders them as absences.

**4.3 — Do not end up with two implementations of the physics.** The app is also published as a
PWA to GitHub Pages by `.github/workflows/deploy.yml`. If the web version is kept alive
alongside a native one, the fuel model, oil model and PID catalogue exist twice and will drift.
This codebase's own comments repeatedly refuse that trade. Settle it in §5 — either the web
build is retired, or it is explicitly demoted to a simulator-only demo with no telemetry logic
of its own.

**4.4 — Shipping conventions.** Branch off `main`, never commit straight to it. Run `npm test`
(and the ported equivalent) before pushing. Open a PR with `gh pr create`; do not merge without
asking, and say plainly what is verified on the car versus only in a simulator.

## 5. Decide with the user before writing code

The right architecture depends entirely on which of these is actually wanted. Ask; do not assume
all of them.

**What native genuinely unlocks** (the honest list — some of these are merely *easier* in
native, not impossible in Capacitor):

- **A foreground service that keeps logging with the screen off.** Today the WebView is
  throttled and killed when backgrounded; there is already a commit whose entire purpose is
  flushing oil wear on background because of this. This is the single biggest structural win.
- **A real database (Room) instead of localStorage** — full trip history, time-series logging,
  queryable drive logs, proper export.
- **Auto-connect when the adapter appears**, rather than a manual connect tap each drive.
- **Sensor fusion**: GPS and accelerometer give 0–60 timing, a g-meter, road grade, and a
  speed cross-check against the ECU.
- **Notifications**: oil life due, a new DTC appearing, unusual coolant or charging voltage.
- **Android Auto**, home-screen widgets, Quick Settings tiles, Wear OS.
- **Real 60/120 fps gauge rendering** in Compose, no WebView compositing.
- The service-worker staleness class of bug disappears: today a new APK still renders the
  previous build until the app is force-stopped and relaunched twice, because
  `registerType: 'autoUpdate'` precaches the bundle inside the app's data directory.

**Questions to settle:**

1. Which of the above are actually wanted, and which are "someday"? Background logging and a
   real database change the data model; widgets and Android Auto change module layout.
2. **Kotlin + Jetpack Compose** is the default recommendation unless there is a reason
   otherwise. Confirm.
3. Fate of the web build (§4.3): retire, or demote to a simulator-only demo?
4. **Big bang or incremental?** A staged port keeps a working app in the car throughout — e.g.
   native shell and transport first, one screen at a time behind a toggle. A rewrite is cleaner
   but leaves the car without a working app mid-flight. This is a car diagnostic tool the user
   relies on; recommend accordingly.
5. Same repo or a new one? Same repo means the Capacitor app and the native app coexist for a
   while, which is fine, but `deploy.yml` and the Pages publish need a decision either way.
6. Does the existing UI design get transliterated or reconsidered? It has had a deliberate
   design pass — one accent colour, one typeface, MPG as the hero, hairline dividers rather
   than filled cards, no webfonts (chosen because font requests fail in tunnels and car parks).
   Compose can honour that, but it is a rebuild, not a port.

## 6. Suggested shape of the work

Offered as a starting point, not a decision. Confirm §5 first, then plan properly.

1. **Establish the test bed first.** Port `primetime_validation.ts` to JVM unit tests
   (JUnit/Kotest) as *failing* tests against empty Kotlin stubs. 97 assertions is a specification
   handed to you for free; make it the target rather than an afterthought.
2. **Port the pure domain layer** bottom-up, greenest first: `civicSpecs` → `integrationRules`
   → `readinessMonitors` → `pidCatalog` → `gearCalculator` → `fuelModel` → `oilLifeModel` →
   `dtcSpecs`. Each one turns its ported assertions green. No Android dependencies in this layer
   — keep it a plain JVM module so the tests stay fast.
3. **Lift `ObdSerialPlugin.java` out of Capacitor** into a real transport class, and port the
   ELM327/STN handshake and polling loop from `obdlinkBluetooth.ts`. Preserve the timeout and
   drain discipline exactly: the comments there explain that a late reply on a stream with no
   request IDs desynchronises every subsequent answer, and that failure is silent.
4. **Port the simulator.** It is what lets every screen be developed and demostrated away from
   the car, and it now mirrors the car's real PID set deliberately.
5. **Migrate the persisted data** (§4.1) before anything native writes to storage.
6. **Then the UI**, one tab at a time, starting with Drive.

## 7. Definition of done

- The ported test suite passes and covers at least what the 97 TypeScript assertions covered.
- The lifetime MPG and oil-life records survived, with their pre-port values verifiable.
- Verified against the real car with the MX+, not only against the simulator — and the report
  distinguishes the two.
- The mixture reading is derived from PID 34 on this car, absent readings render as absences,
  and no PID list is hardcoded.
- Exactly one implementation of the physics exists in the repo.
- Whatever `deploy.yml` does after the port is intentional.

## 8. Two loose threads from the previous session

- **`Control module voltage read 12.45 V at a warm idle, engine running.`** The alternator should
  hold ~13.8–14.4 V. This is a possible charging-system fault on the car, unrelated to the port,
  and the owner was advised to check it with a multimeter. If it is real, expect odd voltage
  readings during testing.
- The `λ` glyph in the Compose UI: in the current WebView build, lambda rendered at small sizes
  with wide letter-spacing reads more like `ʌ` on this phone's system font. Worth choosing a
  glyph that survives at 11 px.

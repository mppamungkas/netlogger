# NetLogger: Android Cellular / 5G NSA Drive Test Logger + Traffic Capture

**CLAUDE.md version 1.7.7** · last updated 2026-10-17
**License:** NetLogger is licensed under **GNU GPL v3** (see `LICENSE`).

### Project status
- Milestones 1–5 implemented and tested (milestone 3 screen-off test: 100 % ticks over 46 min unplugged incl. deep Doze on the moto; milestone 4 field walk on the A56 incl. kill recovery; milestone 5 export checked on the A56 against its database). Milestone 6 (map) implemented and checked on both phones (moving track seen on the moto; airplane mode still to check). G-NetTrack Pro cross-check done on both phones (2026-10-14, `docs/device-quirks.md`). Milestone 7 (EventDetector, LTE/WCDMA/GSM band mapping) implemented; field check pending. Milestone 8 (speed test) implemented and checked on the A56 against the user's LibreSpeed server and Cloudflare, also during a capture (2026-10-15). Milestone 9 (session charts, Compose Canvas, no chart library) implemented; to check on a phone. Milestone 10 (PCAPdroid bridge) implemented and checked on both phones, then removed in 1.7.0. Milestone 11 (native VpnService capture, `docs/decisions/002-native-capture.md`) implemented and checked on both phones (2026-10-15; open: IPv6 traffic through the capture). Settings, help and phone capabilities (`docs/decisions/003-settings-ux.md`) implemented and checked on both phones (2026-10-16). Live dashboard (`docs/decisions/004-live-dashboard.md`) implemented and checked on both phones (2026-10-16). Problem reports (`docs/decisions/005-problem-reports.md`) implemented and checked on both phones (2026-10-16). 1-hour screen-off test done: 1.5 h unplugged on the moto with a capture, 100 % ticks (2026-10-16, `docs/device-quirks.md`).
- Cell parsing approach: see `docs/decisions/001-cell-parsing.md` (approved decision is binding).

### Changelog
- **1.7.7:** Speed test on Live: three buttons, Private server (the user's server from Settings; a red note when none is set), Cloudflare (public comparison, always offered, never the default; the Settings on/off switch is gone) and Stop (finished phases are kept, the interrupted one is stored with `error` = `stopped`, or `session stopped` when the session ends during a test). Schema v3: `throughput_test.serverLocation`, Cloudflare's site code from the `CF-RAY` header (e.g. `SIN`), migration 2→3 tested.
- **1.7.6:** Release builds: signed with the release key from `local.properties` (`netlogger.signing.*`, never in the repo; unsigned without it), arm64-v8a + armeabi-v7a only, version 0.2.0. Debug builds have their own app ID `id.my.pamslab.netlogger.debug` ("NetLogger debug"), so debug and release install side by side, each with its own data; adb component names keep the class package (`id.my.pamslab.netlogger.debug/id.my.pamslab.netlogger.MainActivity`).
- **1.7.5:** Problem reports without the Play Store (`docs/decisions/005-problem-reports.md`, new module `core:diagnostics`): crashes (Java with stack trace, native with a tombstone summary), ANRs and Android ending NetLogger during a recording, from `ApplicationExitInfo` plus an uncaught-exception handler in both processes; a card on Live, Settings › Problem reports (view, share, public GitHub issue, delete; raw tombstone only if the user ticks it), Describe a problem. Nothing is sent automatically; reports contain no location, cell identities, SSID or capture content. Debug builds: `CrashTestReceiver` for tests.
- **1.7.4:** Live is a dashboard (`docs/decisions/004-live-dashboard.md`): status bar (recording time, ticks, Marker/Stop; scenario and Start when idle), operator and network type in words, one serving-cell table (NR merged from CellInfo and SignalStrength, "SS" marks the latter), GPS, data route (Wi-Fi SSID / cellular, `NetworkRouteSource`, Live only, not stored) with 5 s average rates, compact speed test and capture rows, the last events ("cell change", never "handover"), raw values under Details. Tapping the open Map tab no longer rebuilds (and crashed) the map.
- **1.7.3:** Map: "Live session" (the recording session, or while not logging an in-memory live preview of position and radio values, never stored, read only while the Map is visible) or "Recorded session"; session, metric and mode as dropdowns; the legend counts the ticks per colour. Sessions: estimated size on the phone and of the export ZIP per session (`SizeEstimate`, weights measured on both phones) plus the real database and capture sizes.
- **1.7.2:** Settings redesigned for teachers and students (decision 003): a list of pages (Logging, Speed test, Traffic capture; Phone capabilities, How to use, About), every option with a description. Logging interval (500/1000/2000/5000 ms) and default scenario are settings (`LoggingSettings`, core:data). Capture options (all or specific apps from an app picker, MTU) moved from the Live card to Settings (`CaptureSettings` now in core:data, same prefs); start/stop stays on Live. Phone capabilities: live check of the reported fields plus their share in recorded sessions (read-only queries, no schema change), Copy report. About: version and build commit, GPL text, privacy note, OSM attribution, third-party credits with full licence texts (`Licences.kt`; update with dependencies). English only, all texts in strings.xml.
- **1.7.1:** Speed test: the user's LibreSpeed server is set in Settings (plain HTTP allowed per host by a generated network security config from `netlogger.cleartextHosts` in local.properties; no server or host in the repo); optional, clearly labeled public secondary target (Cloudflare), never the default; redirects are never followed (an operator's captive portal is an error, not a measurement).
- **1.7.0:** Phase 3 capture is NetLogger's own VpnService only (milestone 11): the PCAPdroid bridge (Phase 3a, milestone 10) was removed at the user's request. Native capture: `libnetcapture` (C, NDK r27d, CMake) with zdtun (LGPL v3+, copied unchanged) in a separate `:vpn` process; nanosecond pcap whose timestamps are elapsedRealtimeNs + one fixed offset; `connections.csv` with each connection's app; MTU setting (1500 realistic / 10 000 fast). Old PCAPdroid sessions stay readable and exportable.
- **1.6.0:** Phase 3a stream mode is **TCP** ("UDP stream mode" replaced): the UDP exporter lost ~46 % of a 10 MB download. Packets keep PCAPdroid's timestamps; capture_sync.csv holds the wall-clock ↔ elapsedRealtimeNs offset at START and STOP; PCAPdroid's vs. NetLogger's packet count on the capture card and in CAPTURE_STOPPED.
- **1.5.9:** Phase 3a (milestone 10): PCAPdroid bridge in `feature:capture`, both modes; capture start/stop stored as `CAPTURE_STARTED` / `CAPTURE_STOPPED` events (no schema change); the stream mode writes `files/captures/<uuid>.pcap` + `_sync.csv`, exported as `capture.pcap` / `capture_sync.csv`. The Live screen gets the capture card through a slot from :app (features stay independent).
- **1.5.8:** Schema v2 (`throughput_test`, migration 1→2 tested; the A56's field data migrated on the phone). `throughput_test` also stores `startedElapsedNs` (timing rule), `streams` and `error`. Speed test (milestone 8): HTTP RTT ping, OkHttp download/upload over mobile data only, manual trigger, server URLs in Settings (LibreSpeed or any web server).
- **1.5.7:** Events (milestone 7) are detected per tick without debouncing, except a serving NR cell change (confirmed over 2 ticks) (definitions in `docs/columns.md`). GSM `band` is the frequency in MHz. A band the ARFCN settles (only one band contains it, or the country's known band) overrules a contradicting `getBands()`.
- **1.5.6:** Maps: MapLibre Native (13.6.1) replaces osmdroid (archived). SINR map colors: >= 20 / 13–20 / 0–13 / < 0 dB.
- **1.5.5:** Schema v1 frozen from the first real field session on. Data model: `cell.reportedServing`, `servingInferred` (was `isServing`), `servingSource`; `measurement.warningCount`; `bandSource` `MAPPED_MCC_FALLBACK`.
- **1.5.4:** Definition of done: instrumented database smoke test must pass on both phones before a release.
- **1.5.3:** Package / application ID renamed to `id.my.pamslab.netlogger` everywhere (root safety rules updated).
- **1.5.2:** Added Samsung Galaxy A56 (unrooted, serial `RRGYB06DN2T`) as the unrooted reference phone; always use `adb -s <serial>` with two phones attached.
- **1.5.1:** Package / application ID is `id.ac.pnp.netlogger` (root safety rules corrected).
- **1.5:** Project license set to GPL v3; reference reuse rules updated accordingly. Added pilot device (moto g45 5G, rooted, USB passthrough) and root safety rules. ADB over USB is now the primary connection. Added first device findings to Radio collection. Added `docs/samples/` (gitignored, contains location data) and optional modem-diag validation track. Added project status.
- **1.4:** Added PCAPdroid to `reference/` (study only, GPL). Phase 3a now uses PCAPdroid's app API, including UDP packet streaming to NetLogger. Capture file changed from `.pcapng` to `.pcap` (PCAPng is a paid PCAPdroid feature).
- **1.3:** Added development environment (Proxmox homelab, Ubuntu Server VM, Claude Code CLI, ADB over Wi-Fi). Added `reference/` folder with open-source projects for study and license rules for reuse. NetMonster Core evaluated as a parsing dependency in Milestone 2. Added device/ADB commands and `.gitignore` rules. Updated first prompt.

## Goal
Android app for research-grade drive tests:
1. **Phase 1–2:** log cellular radio measurements (LTE + 5G NSA, later SA), GPS, battery and data-usage context, show them live and on a map, and export clean datasets.
2. **Phase 3:** capture the phone's IP traffic (own VpnService, PCAPdroid-style) in sync with the radio log, so radio conditions can be correlated with application behavior (QoE, throughput, battery).

The data is used for research (LTE anchor quality vs. 5G NSA availability, ML on drive-test data, RAT vs. battery consumption). **Data quality and traceability matter more than UI polish.**

## Hard constraints
- Public Android APIs only. No root, no vendor diag/QXDM interfaces, no reflection or hidden APIs.
- Layer-3 / RRC / NAS signaling is **out of scope** (needs root or diag access).
- Never crash on missing data. Every radio field is nullable; OEMs hide or fake values.
- Treat `Int.MAX_VALUE`, `Long.MAX_VALUE` and `CellInfo.UNAVAILABLE` as `null`. Wrap all OEM-dependent parsing in try/catch and log parse failures (counted, not thrown).
- Core logging needs no network. Online dependencies: map tiles, throughput test server, optional upload.
- Battery: one location request stream, no wakelock beyond the service lifetime, no busy loops.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), single-activity, Navigation Compose
- minSdk 31 (Android 12), targetSdk 35, compileSdk 35
- Room (SQLite) with exported schemas + migrations, Kotlin Coroutines + Flow
- Hilt for DI
- Google Play Services Location (`FusedLocationProviderClient`)
- MapLibre Native Android for maps (BSD-2, no API key) with OpenStreetMap standard raster tiles; follow the OSM tile usage policy (visible attribution, app User-Agent, no bulk prefetch). osmdroid was the original choice but its repository is archived (last release Aug 2024).
- OkHttp for the throughput/ping module
- kotlinx.serialization for JSON fields and session metadata
- Candidate dependency: **NetMonster Core** (Apache 2.0) for cell parsing and OEM quirks. Evaluate in Milestone 2 before writing our own parser (see Reference code).
- Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`)
- Testing: JUnit5 (or JUnit4 if simpler with Android), Turbine for Flows, Robolectric only where needed
- Phase 3: Android NDK r27 LTS (C) via CMake 3.22.1, JNI (versions pinned in `gradle/libs.versions.toml`)

## Commands (run these to verify your own work)
```
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # unit tests (must pass before every commit; includes :feature:capture:nativeHostTest, the C tests with ASan/UBSan)
./gradlew lintDebug              # lint (no new errors)
./gradlew connectedDebugAndroidTest   # instrumented tests (needs device)
./gradlew :core:data:kspDebugKotlin   # regenerate Room schema

# device (pilot phone via USB passthrough; ADB over Wi-Fi as fallback)
adb devices                                   # confirm phone is connected
./gradlew installDebug                        # install the debug build (app ID id.my.pamslab.netlogger.debug)
./gradlew assembleRelease                     # signed release APK (needs netlogger.signing.* in local.properties)
adb logcat -s NetLogger:*                     # app logs (use tag "NetLogger")
adb shell dumpsys telephony.registry          # raw telephony state, for comparing with parser output
adb shell dumpsys telephony.registry > docs/samples/<device>_<operator>_<situation>_<date>.txt   # save a fixture
adb exec-out screencap -p > /tmp/screen.png    # screenshot of the app for review
```

## Development environment
- Host: Proxmox homelab. Guest: **Ubuntu Server LTS VM** (no desktop), min. 4 vCPU, 8–16 GB RAM, 60+ GB disk.
- Agent: **Claude Code CLI**, run inside `tmux` (session `netlogger`) so sessions survive disconnects. Access via Proxmox xterm.js console (campus blocks VPN); optionally Claude Code Remote Control from the Claude app.
- Run everything as user `pams`, never as root.
- Toolchain: JDK 17 or 21 (whichever is installed on the VM), Android SDK command-line tools (`platform-tools`, `platforms;android-35`, `build-tools`, `ndk;27.3.13750724`, `cmake;3.22.1`), Gradle wrapper from the repo, host `gcc` for the native host tests.
- Gradle: `org.gradle.jvmargs=-Xmx4g` (raise if the VM has more RAM), configuration cache and build cache enabled.
- No emulator: it has no real radio data. All radio testing happens on a **real phone**. Primary: pilot phone via **Proxmox USB port passthrough** (udev rule for vendor `22b8` + `plugdev`). Fallback: ADB over Wi-Fi (`adb pair` / `adb connect`), same network only.
- Editing by the user: VS Code Remote-SSH or Android Studio on a laptop via git. Do not assume a GUI exists on the server.
- Never store signing keys, keystore passwords or API tokens in the repo. Use `local.properties` or environment variables (both gitignored).
- Machine-specific build settings in `local.properties`: `netlogger.cleartextHosts=<host>[,<host>]` allows plain HTTP to those hosts only (e.g. a LibreSpeed server without TLS); unset means HTTPS only. Also as Gradle property or `NETLOGGER_CLEARTEXT_HOSTS`.

## Pilot device
- **Motorola moto g45 5G** (Snapdragon 6s Gen 3, SM6375), Android 15, ADB serial `ZP22222MQL`.
- **Rooted.** Root is for the developer's validation tools only; NetLogger must never require or use root.
- Dual SIM; slot 1 currently Tri / IOH (MCC 510, MNC 89), slot 2 empty.

### Unrooted reference phone
- **Samsung Galaxy A56** (SM-A566B, Exynos 1580 / Samsung modem), Android 16, ADB serial `RRGYB06DN2T`. **Unrooted.**
- SIM varies between tests (seen: Telkomsel 510-10, Tri 510-89); record the operator with every finding.
- Samsung redacts cell identity (`mCi`, `mPci`, `mTac`, `mEarfcn`) in `dumpsys telephony.registry`; compare those via NetLogger or `mPhysicalChannelConfigs`.
- Connected over USB like the pilot phone; ADB over Wi-Fi as fallback (keeps Wi-Fi on, so mobile data and NR stay idle).

With two phones attached, **always** use `adb -s <serial>` (and `ANDROID_SERIAL=<serial> ./gradlew installDebug` to install on one).

- Before marking a milestone done, test at least once on the **unrooted** phone as well (background, permissions and battery behavior can differ on rooted devices).
- Record everything device-specific in `docs/device-quirks.md`.

### Root safety rules (strict)
Allowed without asking: `adb install/uninstall` of NetLogger, `logcat`, `dumpsys`, `am`/`pm` commands for `id.my.pamslab.netlogger` and `id.my.pamslab.netlogger.debug`, `screencap`, `pull` of NetLogger's own files. Uninstalling deletes that app's sessions: back them up first.
**Never without explicit user approval:** `su` or any root command, flashing, `reboot bootloader/recovery`, modifying system settings or other apps, touching `/dev/diag` or modem interfaces, deleting files outside NetLogger's own storage.

### Optional validation track (outside the app)
On the rooted pilot phone, open-source modem-diag tools (e.g. QCSuper, SCAT) may capture LTE/NR RRC signaling as ground truth to validate NetLogger's public-API data. This is a separate research tool, run only by the user; Claude Code may help write analysis scripts for its output but must not integrate diag access into NetLogger.

## Module structure
Multi-module from day 1 so traffic capture can be added later without refactoring.

```
netlogger/
├── CLAUDE.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── app/                         # Application, MainActivity, NavGraph, DI wiring
├── core/
│   ├── model/                   # pure Kotlin data classes (no Android deps)
│   ├── telephony/               # radio collection behind interfaces
│   │   ├── TelephonySource.kt        # interface wrapping TelephonyManager (fakeable)
│   │   ├── AndroidTelephonySource.kt
│   │   ├── CellParser.kt             # CellInfo / SignalStrength -> CellSample (pure, unit-tested)
│   │   ├── NetworkStateListener.kt   # TelephonyCallback: DisplayInfo, ServiceState, SignalStrengths
│   │   ├── BandMapper.kt             # EARFCN / NR-ARFCN -> band (fallback when getBands() is empty)
│   │   └── EventDetector.kt          # RAT/PCI/NR-attach changes -> events
│   ├── location/                # LocationProvider: Flow<Location> ~1 Hz
│   ├── device/                  # battery, TrafficStats, thermal, screen state, device info
│   ├── data/                    # Room DB, DAOs, repositories, migrations, schemas/
│   ├── export/                  # CSV / GeoJSON / KML / ZIP bundle writers
│   ├── diagnostics/             # problem reports: crash handler, exit records, report text (decision 005)
│   └── ui/                      # theme, shared components, RsrpColor
├── feature/
│   ├── logger/                  # LoggingService (foreground), Live screen
│   ├── map/                     # Map screen
│   ├── sessions/                # Sessions list, detail, export, delete
│   ├── speedtest/               # ping + HTTP download/upload against own server (Phase 2)
│   ├── settings/                # Settings pages, phone capabilities, how to use, about + licences
│   └── capture/                 # Phase 3: native VpnService capture (libnetcapture + zdtun, :vpn process)
└── docs/
    ├── device-quirks.md         # update after every real-device test
    ├── field-test-checklist.md
    └── columns.md               # export column definitions and units
reference/                       # read-only open-source code for study (gitignored)
├── netmonster-core/
├── network-survey/
├── tower-collector/
└── pcapdroid/
```

Rules:
- `core:model` and parsers have **no Android dependencies** where possible, so they are unit-testable on the JVM.
- Never call `TelephonyManager` directly outside `AndroidTelephonySource`.
- Features depend on `core:*`, never on each other.

## Reference code
`reference/` holds cloned source repositories (not APKs) of open-source projects, for **study only**. It is gitignored.

Set up once:
```bash
mkdir -p reference && cd reference
git clone --depth 1 https://github.com/mroczis/netmonster-core
git clone --depth 1 https://github.com/christianrowlands/android-network-survey network-survey
git clone --depth 1 https://github.com/zamojski/TowerCollector tower-collector
git clone --depth 1 https://github.com/emanuele-f/PCAPdroid pcapdroid
# PCAPdroid native code lives in submodules; only needed if studying zdtun/nDPI:
# (cd pcapdroid && git submodule update --init --depth 1)
```

| Project | License | Use it for |
|---|---|---|
| netmonster-core | Apache 2.0 | Cell parsing, OEM workarounds, band mapping. Prefer as a Gradle dependency over reimplementing. |
| network-survey | Apache 2.0 | Foreground service architecture, logging pipeline, export formats |
| tower-collector | MPL 2.0 | Cell info throttling, background collection strategies |
| pcapdroid | GPL v3 (see `COPYING`) | Phase 3 only: VpnService lifecycle, capture architecture, how zdtun is wired in. Its zdtun submodule (LGPL v3+) is copied into `feature/capture/src/main/cpp/third_party/zdtun/` |

Rules:
- Read-only: never edit, build, or add `reference/` to the Gradle build.
- Always check the project's own `LICENSE` file before reuse; the table above is a starting point, not a guarantee.
- Default: **write our own implementation** after studying the approach.
- NetLogger is GPL v3, so code from GPL v3 (and "GPL v2 or later"), Apache 2.0, MIT and BSD projects may be copied or adapted, including PCAPdroid and zdtun.
- MPL 2.0 code (Tower Collector): reuse only if the file is not marked "Incompatible With Secondary Licenses"; otherwise study only. Ask if unsure.
- Code licensed "GPL v2 only" or under a proprietary/unknown license: **do not copy**.
- Before copying or adapting anything: tell the user the source file, project and license, and wait for approval. Then keep the original copyright/license header in the file, add our own modification note, and list the project in `docs/third-party.md`.
- Prefer using a library as a dependency over copying its source.
- Phase 3 may reuse PCAPdroid/zdtun code under the rules above.
- No decompiled code from any closed-source app (e.g. G-NetTrack Pro). Compare with it only as a black box using its exported logs.
- When a design choice is based on a reference project, mention it in the commit message (e.g. `inspired by network-survey: CellularController`).

## Radio collection: what works and what doesn't
Implement with these realities in mind:

| Data | Source | Notes |
|---|---|---|
| Serving + neighbor cells | `requestCellInfoUpdate()` → `List<CellInfo>` | Throttled by the OS and may return cached data, especially with screen off. Store each cell's age via `CellInfo.getTimestampMillis()` (elapsedRealtime base). |
| LTE signal | `CellSignalStrengthLte` | RSRP, RSRQ, RSSNR (SINR proxy; scaling varies by OEM), RSSI, CQI, TA |
| NR signal (NSA) | `CellInfoNr` **and** `SignalStrength.getCellSignalStrengths()` | Many phones don't report `CellInfoNr` in NSA. Use `CellSignalStrengthNr` from `SignalStrength` as a second source and record which source was used. |
| Bands | `CellIdentityLte/Nr.getBands()` | Often empty → fall back to `BandMapper`. Record `bandSource` (`API` / `MAPPED`). |
| 5G icon state | `TelephonyDisplayInfo.overrideNetworkType` | NR_NSA, NR_ADVANCED |
| Registration / NR state | `ServiceState`, `NetworkRegistrationInfo` | Cell bandwidths via `ServiceState.getCellBandwidths()` (best-effort CA hint) |
| Physical channel config | — | **Not available**: needs `READ_PRECISE_PHONE_STATE` (privileged). Do not implement. |
| Dual SIM | `SubscriptionManager`, `createForSubscriptionId()` | Default: active data SIM. Log `subscriptionId` and `simSlot`. |

Findings on the pilot phone (moto g45, Tri/IOH, Oct 2026):
- `ServiceState.getCellBandwidths()` reports CA (e.g. `[20000, 10000, 10000]`) and changes between samples → log every tick.
- `CellIdentityLte.getBands()` is populated → `bandSource = API` expected on this device.
- `nrState` is redacted even in dumpsys; EN-DC availability is not reliably readable via public API → use `TelephonyDisplayInfo` for NSA state.
- The empty SIM slot reports its own OUT_OF_SERVICE state → always follow the active data subscription, never "any" subscription.
- Raw dumps are kept in `docs/samples/` (gitignored) and can be turned into anonymized unit-test fixtures (replace CI/TAC/location before committing a fixture).

NSA detection: LTE serving cell registered **and** (NR cell or NR signal present **or** `overrideNetworkType` is NR_NSA / NR_ADVANCED). Store the raw inputs too, not just the conclusion.

## Data model (Room)
Normalized, long format. Keep a schema version and export the schema JSON for migrations.
Schema v1 is frozen from the first real field session on; after that, every schema change needs a version bump, migration and migration test.

**`session`**
`id, uuid, startedAt, endedAt, label, scenario (drive/walk/static/indoor), notes, intervalMs,`
`deviceManufacturer, deviceModel, androidVersion, basebandVersion, appVersion, schemaVersion,`
`subscriptionId, operatorName, mcc, mnc, captureFile (nullable, Phase 3)`

**`measurement`** (one row per tick)
`id, sessionId, seq, wallTimeMs, elapsedRealtimeNs,`
`lat, lon, altitude, speedMps, bearing, gpsAccuracy, locationAgeMs,`
`networkType (LTE/NR_NSA/NR_SA/WCDMA/GSM/NONE), overrideNetworkType, dataState, isRoaming,`
`cellBandwidthsJson,`
`batteryLevel, batteryCurrentUa, batteryVoltageMv, batteryTempC, isCharging,`
`rxBytesTotal, txBytesTotal, rxBytesApp, txBytesApp,`
`screenOn, thermalStatus, parseErrors (getter failures only), warningCount`

**`cell`** (many rows per measurement: serving + neighbors)
`id, measurementId, rat (LTE/NR/WCDMA/GSM), reportedServing (phone's label), servingInferred (derived), servingSource (NR: REPORTED/NSA_INFERRED/SIGNAL_STRENGTH_MATCH/STRONGEST/AMBIGUOUS), isRegistered, connectionStatus,`
`ci_or_nci, tac, pci, arfcn, band, bandSource (API/MAPPED/MAPPED_MCC_FALLBACK), bandwidthKhz,`
`rsrp, rsrq, sinr, rssi, cqi, ta,`
`csiRsrp, csiRsrq, csiSinr,`
`source (CELL_INFO/SIGNAL_STRENGTH), cellInfoAgeMs`

**`event`**
`id, sessionId, wallTimeMs, elapsedRealtimeNs, type (RAT_CHANGE, PCI_CHANGE_LTE, PCI_CHANGE_NR, NR_ADDED, NR_REMOVED, NO_SERVICE, SERVICE_RESTORED, GPS_LOST, GPS_RESTORED, USER_MARKER), fromValue, toValue, lat, lon`

**`throughput_test`** (Phase 2)
`id, sessionId, startedAtMs, startedElapsedNs, type (PING/DOWNLOAD/UPLOAD), serverUrl, durationMs, bytes, mbps, streams, rttMinMs, rttAvgMs, rttMaxMs, jitterMs, lossPct, captureActive, error, serverLocation (v3)`

Timing rules:
- Every row stores **both** `wallTimeMs` and `elapsedRealtimeNs`. `elapsedRealtimeNs` is the sync key with pcap files and is immune to clock changes.
- One tick = one coroutine loop driven by `elapsedRealtime`, not `Thread.sleep` drift.

## Logging service
1. `LoggingService` is a foreground service, type `location`, with an ongoing notification (Stop, Add Marker actions).
2. Every N ms (default 1000; options 500/1000/2000/5000): request a cell info update, take latest `NetworkState`, latest GPS fix, device context → build measurement + cells → insert in **one Room transaction**.
3. `EventDetector` compares with the previous tick and writes events.
4. Writes are batched if inserts become slow (measure first; don't optimize early).
5. Survive screen off for 1+ hour. If the service is killed, the session is closed cleanly on next app start (`endedAt` = last measurement time).

## Permissions
`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `READ_PHONE_STATE`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`, `INTERNET` (tiles, speed test).
- `ACCESS_BACKGROUND_LOCATION` is **not** needed: the service is started from the foreground.
- Rationale screen before requesting; app stays usable (read-only sessions, export) if denied.
- Phase 3 adds `FOREGROUND_SERVICE_SPECIAL_USE` (the VpnService's foreground type on Android 14+), `ACCESS_NETWORK_STATE` and the `VpnService` declaration (`BIND_VPN_SERVICE`, system-bound). The VPN consent dialog is the system's; the user answers it.

## Export
Per session, export a **ZIP bundle** via Storage Access Framework or `ACTION_SEND`:
- `session.json` (metadata)
- `measurements.csv`, `cells.csv`, `events.csv`, `throughput.csv`
- `flat.csv`: one row per tick with serving LTE + strongest NR columns (easy for Excel and quick plots)
- `track.geojson` (and optional `track.kml`)
- `capture.pcap`, `capture_sync.csv`, `connections.csv` if the session has a capture (Phase 3)

CSV rules: UTF-8, comma-separated, header row, ISO-8601 timestamps with offset, empty field for null (never `0` or `-999` for missing values), `.` as decimal separator regardless of device locale.

Optional: a "G-NetTrack-compatible" column layout for side-by-side validation. Implement only after the native format is stable.

## UI
- **Live** (`docs/decisions/004-live-dashboard.md`): one dashboard: status bar, operator and network type in words, serving LTE + NR table, neighbours (collapsed), GPS, data route and rates, speed test and capture rows while logging, last events, Details (raw values, battery, warnings; collapsed). Values older than 5 s are grayed out.
- **Map:** "Live session" (recording session, or a live preview while not logging) or "Recorded session"; track colored by selectable metric (LTE RSRP, NR SS-RSRP, SINR, network type) with tick counts per colour in the legend; event markers; tap point → values.
  RSRP colors: > -80 green, -80 to -95 yellow, -95 to -110 orange, < -110 red, null gray (integer dBm: -80 … -95 yellow, -96 … -110 orange).
  SINR colors (LTE RSSNR and NR SS-SINR): >= 20 green, 13 to < 20 yellow, 0 to < 13 orange, < 0 red, null gray.
- **Sessions:** list with estimated size on the phone and of the export, rename/label, notes, export, delete (with confirmation).
- **Settings** (`docs/decisions/003-settings-ux.md`): Logging (interval, default scenario, data SIM shown), Speed test (private server URLs; Cloudflare needs no setting), Traffic capture (all/specific apps, MTU); Phone capabilities (live check + recorded share, Copy report), How to use, Problem reports (decision 005), About (licences, privacy, credits). Users are teachers and students: every option explains itself; English only, texts in strings.xml.

## Phase 3: Traffic capture
NetLogger's own `VpnService` (milestone 11; plan and decisions in `docs/decisions/002-native-capture.md`):
- `NativeCaptureService` runs in a separate `:vpn` process, so a native crash cannot end radio logging; it writes no
  database rows and reports to the main process by package-limited broadcasts (counters every second, ENDED once).
- `libnetcapture` (C, NDK): one select() loop over the tun and zdtun's sockets (zdtun, LGPL v3+, copied unchanged),
  our own pcap writer (classic pcap, nanosecond timestamps, LINKTYPE_RAW); every packet crossing the tun is captured.
- Timing: packet timestamp = `elapsedRealtimeNs` (CLOCK_BOOTTIME) + one wall-clock offset taken at start, so the pcap
  aligns exactly with the radio log; `capture_sync.csv` has START (the offset), CHECK every 60 s, STOP.
- Per-app attribution via `ConnectivityManager.getConnectionOwnerUid()`, written to `connections.csv`.
- MTU setting: 1500 (realistic packet sizes, default) or 10 000 (much less throughput cost; zdtun forwards one
  segment per socket per loop round).
- One capture per session, stopped at the session end; refused while another VPN is active. A failed capture never
  crashes the app: it ends the capture, is stored as a CAPTURE_STOPPED reason, and logging continues.

Limits, shown in the UI:
- only IP traffic of apps, between each app and the VPN; no radio signaling, and packet sizes/timing are rebuilt by
  the forwarder, not the ones on the air;
- cannot run together with another VPN;
- adds overhead, so throughput tests during capture are flagged (`captureActive = true`).

History: milestone 10 bridged to PCAPdroid's app API (TCP stream into NetLogger, or PCAPdroid's own file). It worked
without loss but was removed in 1.7.0 in favour of the native capture; old sessions keep their files and events.

## Coding conventions
- Kotlin official style; no `!!` in production code.
- Parsers are pure functions returning nullable fields + a list of parse warnings.
- Log lines (`Log.*`, tag "NetLogger") never contain locations, cell identities (cell ID, TAC, PCI), SSIDs, IP
  addresses or capture content: problem reports include NetLogger's own log lines (decision 005).
- Domain units in names: `rsrpDbm`, `speedMps`, `bandwidthKhz` in code; CSV headers may drop suffixes but must be documented in `docs/columns.md`.
- Every Room schema change needs a migration and a migration test.
- No new dependency without saying why in the commit message.
- Small commits, one per milestone step, message format: `feat(telephony): ...`, `fix(export): ...`, `test(...)`.

## Milestones (build in order; build + tests + lint green before each commit)
**Phase 1 — MVP**
1. **Scaffold:** multi-module Gradle, Hilt, Compose, navigation, permission flow, CI (GitHub Actions: build, unit tests, lint).
2. **Telephony:** first, study `reference/` and write a short decision note in `docs/decisions/001-cell-parsing.md`: use NetMonster Core as a dependency, or write our own `CellParser`. Wait for user approval. Then implement `TelephonySource`, the parser (or NetMonster adapter), `NetworkStateListener`, NR-from-SignalStrength fallback, and a Live screen with raw values. Unit tests with fake CellInfo data.
3. **Location + Service:** `LocationProvider`, `LoggingService`, notification, device context (battery, TrafficStats, thermal).
4. **Persistence:** Room schema above, repositories, sessions list, crash-safe session closing.
5. **Export:** ZIP bundle, CSV writers, GeoJSON. Tests for escaping, nulls, locale.
6. **Map:** colored track, metric selector, event markers.

**Phase 2 — Research extras**
7. `EventDetector` + events table; `BandMapper` fallback with tests for all bands used by Indonesian operators.
8. Speed test module: ping, HTTP download/upload against configurable own server.
9. Session detail with charts (RSRP/SINR vs time, network type timeline).

**Phase 3 — Capture**
10. PCAPdroid bridge (3a) — done, removed in 1.7.0.
11. Native `VpnService` capture (3b) — done (`docs/decisions/002-native-capture.md`).

**Always**
12. Keep `docs/device-quirks.md` and `docs/field-test-checklist.md` updated.

## Definition of done (MVP = milestones 1–6)
- Start a session, drive/walk, stop; the ZIP contains session metadata, measurements, cells (serving + neighbors), GPS, LTE and NR RSRP/RSRQ/SINR, PCI, ARFCN, band (+ source), network type, both timestamps.
- 1 hour of logging with screen off: no crash, ≥ 95% of expected ticks recorded, stale samples identifiable.
- Works on at least one Android 12+ phone with 5G NSA; values cross-checked against G-NetTrack Pro on the same phone and differences documented.
- Unit tests pass for parsers, `BandMapper`, `EventDetector`, CSV writers.
- Before a release: the instrumented database smoke test passes on both phones (`ANDROID_SERIAL=<serial> ./gradlew :core:data:connectedDebugAndroidTest`, moto g45 and A56). Not run in CI.

## When working on this project, Claude Code should
- Implement only the milestone asked for; propose, don't silently expand scope.
- Run build, unit tests and lint before reporting a milestone as done.
- Report device-specific limitations and add them to `docs/device-quirks.md`.
- Ask before adding dependencies, changing the schema, or touching Phase 3 code.
- Follow the Reference code rules; never copy from `reference/` silently.
- Assume a headless server: no GUI tools, no emulator. Use `adb` (logcat, dumpsys, screencap) for on-device checks; ask the user for anything needing physical interaction (moving, toggling airplane mode, field tests).
- Follow the Root safety rules for the pilot phone.

## .gitignore (must include)
```
reference/
local.properties
*.jks
*.keystore
.gradle/
build/
.cxx/
captures/
*.pcap
*.pcapng
exports/
docs/samples/
```

## Next prompt to give Claude Code (milestone 2 validation)
> Read CLAUDE.md v1.5 (changed: GPL v3, pilot device, root safety rules, device findings). Update `.gitignore` and `docs/device-quirks.md` accordingly. Then validate milestone 2 on the pilot phone: with NetLogger's Live screen open, capture `adb shell dumpsys telephony.registry`, a screenshot, and NetLogger's logcat, and compare serving cell values (PCI, EARFCN, band, bandwidths/CA, TAC, CI, network type, RSRP/RSRQ/SINR). Report every mismatch or missing value in a table, propose fixes, and wait for my approval before changing code. Do not use root.

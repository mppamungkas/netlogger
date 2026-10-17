# Field test checklist

## Setup (every session)
- [ ] `adb devices` shows the phone (pilot: USB passthrough); `./gradlew installDebug`. No root commands.
- [ ] Note device, Android version, baseband (Settings → About → Software info), active data SIM and operator
- [ ] All permissions granted, location set to **Precise**
- [ ] `adb logcat -s NetLogger:*` running in a second terminal

## Milestone 2: Live screen vs. `dumpsys`
Do this on each phone, in LTE-only coverage and in 5G NSA coverage (5G icon visible).
- [ ] Open the Live tab, wait 10 s, take a screenshot
- [ ] Within the same few seconds: `adb shell dumpsys telephony.registry > docs/samples/<device>_<operator>_<situation>_<date>.txt` (gitignored)
- [ ] Compare serving LTE: PCI, EARFCN, band, TAC, CI, RSRP/RSRQ/RSSNR (`mCellInfo` against the `CELL_INFO` card, `mSignalStrength.mLte` against the `SIGNAL_STRENGTH` card; both are shown)
- [ ] Compare NR: does `dumpsys` list a `CellInfoNr`? Does the Live screen show NR as `CELL_INFO` or `SIGNAL_STRENGTH`?
      NSA NR is only added while data moves: keep a download running, and wait until the status bar shows 5G
      (`overrideNetwork=NR_NSA` in dumpsys) before capturing. Check the NR card's PCI and band against G-NetTrack.
- [ ] Compare network type with `mTelephonyDisplayInfo` (`overrideNetworkType`) and the status bar icon
- [ ] Note every parse warning shown, especially `SAMSUNG_*` and `OUT_OF_RANGE`
- [ ] Neighbors: count and PCIs roughly match `dumpsys`
- [ ] Turn the screen off for 30 s, back on: check cell ages and `Cell info: FRESH/CACHED`
- [ ] Repeat once on an unrooted phone (A56) before marking the milestone done
- [ ] Record findings in `docs/device-quirks.md`

## Milestone 3: logging service, screen off

Run on the moto g45 (pilot) and on the A56 (unrooted). **Unplug the phone**: on USB it charges and never enters
Doze, so a plugged-in test proves nothing about screen-off behavior.

- [ ] Live → **Start logging**: notification "NetLogger is logging" with Stop and Add marker appears
- [ ] `adb -s <serial> shell dumpsys power | grep NetLogger:logging` shows the partial wake lock while logging
- [ ] Add marker from the notification and from the Live screen: marker count goes up
- [ ] GPS card: status OK, accuracy and age plausible (age < 2 s while moving outdoors)
- [ ] Device card: battery %, current (note sign while charging vs. discharging), voltage, temperature, thermal, screen
- [x] Unplug, screen off, **1 hour** (walk or drive if possible). Then plug in and read
      `adb -s <serial> logcat -d -s NetLogger:I | grep ticks` (one summary per minute: ticks recorded/expected,
      cell info origin, max serving-cell age, max GPS age, screen-off ticks, NR ticks)
- [x] Pass: ≥ 95 % of expected ticks (Live: "Last session x / y"), no crash. **moto g45, 2026-10-16 17:29–19:03,
      unplugged, 98 % screen off, release 0.2.0 with a capture and the debug build logging in parallel: 5603 / 5603 and
      5584 / 5584 ticks (100 %), max tick spacing 1.01 s, no crash** (device-quirks)
- [x] Max serving-cell age with screen off: just under 10 s (median 5.0 s, 46 % of ticks > 5 s); NR (NSA) only 2 % of
      the time here, also with the screen on
- [ ] Stop from the notification: notification gone, wake lock released (`REL NetLogger:logging` in dumpsys power)
- [ ] Record results per phone in `docs/device-quirks.md`

## Milestone 4: persistence and crash-safe closing

- [ ] Live: pick a scenario chip, Start, Add marker (Live and notification), Stop
- [ ] Sessions: the session shows scenario, operator, device, duration, ticks and the marker count
- [ ] Detail: edit label and notes, change scenario, Save; values survive an app restart
- [ ] Kill test: start a session, `adb -s <serial> shell am force-stop id.my.pamslab.netlogger` (debug builds: `…netlogger.debug`), reopen the app.
      The session shows "closed after the app was killed (end = last tick)" and its end time equals the last tick
- [ ] Delete with confirmation removes the session (the recording session cannot be deleted)
- [ ] Pull the database (`adb -s <serial> exec-out run-as id.my.pamslab.netlogger.debug cat databases/netlogger.db`; run-as needs a debug build,
      also `-wal`) into `docs/samples/`; check that each tick has at most one `servingInferred` NR cell per NR-ARFCN
      and look at `servingSource`
- [ ] Release check: `ANDROID_SERIAL=<serial> ./gradlew :core:data:connectedDebugAndroidTest` passes on both phones

## Milestone 5: export

- [ ] Sessions → detail of a stopped session: "Save ZIP…" opens the document picker with the suggested name
      `netlogger_<model>_<start>_<label or scenario>_<uuid8>.zip`; the file appears where it was saved
- [ ] "Share ZIP" opens the share sheet with the ZIP attached (cache copy: `adb -s <serial> exec-out run-as
      id.my.pamslab.netlogger cat cache/exports/<file>.zip`); both buttons are disabled while the session records
- [ ] Unzip: session.json, measurements.csv, cells.csv, flat.csv, events.csv, warnings.csv, track.geojson
- [ ] Row counts match the database (`counts` in session.json, and the pulled `netlogger.db`); every CSV row has the
      header's width; empty fields for missing values, no `NaN`/`-999`/`2147483647`
- [ ] Times are ISO-8601 with the phone's offset; `timeZone` in session.json
- [ ] flat.csv: one row per tick, at most one NR cell (`nrServingCount` > 1 only with NR CA), every value has its
      `*Source`
- [ ] track.geojson opens in a GeoJSON viewer (e.g. geojson.io) at the right place; coordinates are [lon, lat]
- [ ] Open flat.csv in a spreadsheet with a comma-decimal locale (e.g. German): numbers still parse with "." when
      imported as CSV with "." decimal separator

## Milestone 6: map

- [ ] Map tab with no sessions: hint, no crash. With sessions: the newest session is selected and framed
- [ ] Each metric chip recolors the dots: LTE RSRP, NR SS-RSRP, LTE SINR, NR SS-SINR, Network; legend matches;
      ticks without a value are gray; ticks without a GPS fix are not drawn (count shown top right)
- [ ] Tap a dot: card with tick, time, LTE/NR identity, RSRP/SINR with "(SS)" when from SignalStrength, ages, GPS
- [ ] Markers appear as black dots with a white ring; detected events as small rings (gray handover, purple
      network type / NR, red service / GPS / SIM)
- [ ] Start logging, open Map: the session is marked "recording" and grows every tick; Follow keeps the newest
      point in view; turning Follow off lets you pan freely
- [ ] Session detail → "Show on map" opens that session on the Map tab
- [ ] "© OpenStreetMap contributors" is visible; tiles load on mobile data; no crash in airplane mode (gray map,
      track still drawn)
- [ ] Map open for 10+ minutes while logging: no slowdown or memory growth visible in Live's tick counter

## Milestone 7: events and bands

- [ ] Drive with handovers and 5G coming and going: events.csv has PCI_CHANGE_LTE, RAT_CHANGE, NR_ADDED / NR_REMOVED
      at plausible places on the map; A56 PCI_CHANGE_NR only for cells held 2+ ticks
- [ ] Compare the event list with G-NetTrack's events file (same phone, same drive)
- [ ] warnings.csv: `API_BAND_CONFLICT` on the moto right after handovers; bands in flat.csv match the EARFCN

## Milestone 8: speed test

- [x] Server reachable from mobile data (LibreSpeed backend or own web server); URLs saved in Settings (A56, 2026-10-15)
- [ ] **Wi-Fi off.** With Wi-Fi on, the test is refused ("default network is not mobile data")
- [ ] While logging: "Run speed test" on Live (and once from the notification with the screen off): ping, then
      download and upload for 10 s each; Live shows progress and results
- [ ] throughput.csv: 3 rows per test, plausible Mbps vs. G-NetTrack / another speed test app at the same spot;
      `startedElapsedNs` falls inside the session's ticks
- [x] Data used per test stays under 250 MB (200 MB down, 50 MB up caps) (A56: download stopped at 209.7 MB, 2026-10-15)
- [ ] Server down / wrong URL: the phases have `error` set, no crash, logging continues

## Milestone 9: session charts

- [ ] Session detail of a drive: RSRP and SINR charts (LTE blue, NR purple) over the map's color bands; timeline
      rows for network type, screen off and speed tests; times at start / middle / end match the session
- [ ] Gaps: no line across missing values or missed ticks; moto LTE SINR mostly empty with the screen off (stale rule)
- [ ] Tap and drag on any chart: cursor on all charts, readout with time, tick, network type, values, "(SS)" marks
- [ ] A 1-hour session: charts load in a few seconds, scrolling stays smooth
- [ ] A recording session: "Reload" shows newer ticks

## Milestone 10: PCAPdroid bridge (Phase 3a) — removed in CLAUDE.md 1.7.0 (native capture only)

Kept for the record; these checks no longer apply.


- [ ] PCAPdroid installed (F-Droid / Play); no other VPN active
- [ ] Without PCAPdroid: the capture card says so, no crash
- [ ] Logging → capture card → "Into NetLogger" (TCP) → Start capture: PCAPdroid asks for consent (first time) and for the
      VPN; deny once: the card shows the error, no CAPTURE_STARTED event, no file left behind
- [ ] Allow: packets and MB count up on the card; browse / run a speed test (flagged `captureActive`)
- [ ] Stop capture: card shows NetLogger's vs. PCAPdroid's packet count (missing ≈ 0); CAPTURE_STOPPED has both;
      export → capture.pcap opens in Wireshark (raw IP); capture_sync.csv has START and STOP rows
- [ ] Stop from PCAPdroid itself: NetLogger notices (CAPTURE_STOPPED reason PCAPDROID)
- [ ] Stop the session from the notification while capturing: CAPTURE_STOPPED SESSION_END; Live offers "Stop PCAPdroid capture"
- [ ] Screen off for 10 min while capturing: packets keep counting; missing ≈ 0 at the end
- [ ] "PCAPdroid file" mode: file in Download/PCAPdroid/netlogger_<uuid>.pcap, session.json captureFile names it
- [ ] App filter with one package: only that app's traffic in the PCAP

## Milestone 11: native capture (Phase 3b, own VpnService)

- [x] Logging → capture card → Start capture (since 1.7.0 the only mode): the system VPN consent dialog the first time only
      (moto g45, 2026-10-15); CAPTURE_STARTED NATIVE; key icon in the status bar; card counts packets, MB, connections
- [x] Deny the VPN consent: the card shows the error, no CAPTURE_STARTED, no file left behind (A56)
- [x] Stop capture on the card: CAPTURE_STOPPED USER, pcap packets = expected, dropped 0; capture_sync.csv START,
      CHECK (every 60 s), STOP (moto g45)
- [x] Stop the session while capturing: CAPTURE_STOPPED SESSION_END, VPN gone (moto g45)
- [x] Kill the capture process (`adb shell am crash <pid of :vpn>`): logging continues (130/130 ticks), CAPTURE_STOPPED
      PROCESS_DIED within ~6 s, pcap valid, connections.csv keeps the rows flushed before (moto g45)
- [x] Start another VPN (e.g. PCAPdroid) while capturing: CAPTURE_STOPPED VPN_REVOKED (A56)
- [x] Another VPN running (PCAPdroid): the native start is refused ("Another VPN is active"), no dialog (A56)
- [x] Throughput, curl downloads/uploads, capture off / native MTU 1500 / 10 000 (moto g45; A56 has no curl and no usable timer)
- [x] In-app speed test during a native capture against the LibreSpeed server: flagged `captureActive` (A56, 2026-10-15, session 23; pcap 0 dropped)
- [x] Speed test against the LibreSpeed server and the Cloudflare secondary target with working data (A56, 2026-10-15; radio changed NR NSA → LTE between runs, see device-quirks)
- [ ] Same on the moto once its data works again; results plausible vs. curl
- [x] Browser and a QUIC app (YouTube): connections.csv names the apps (A56, after the launcher-apps `<queries>` fix), pcap valid
- [x] Screen off 30 min while capturing and logging: 100 % ticks, pcap packets = expected (moto g45, USB powered)
- [ ] Same, unplugged (deep Doze)
- [ ] App filter with one package: only that app's connections
- [ ] Export: ZIP has capture.pcap, capture_sync.csv, connections.csv; session.json lists them
- [ ] Both phones (the A56 is the unrooted reference)

## Settings, help and phone capabilities (decision 003)

- [x] Settings list shows Logging and Speed test with their current values; pages open and "← Settings" returns (A56, 2026-10-15)
- [x] Logging interval 2000 ms: Live shows "2000 ms · change in Settings"; the next session logs every 2.000 s and stores `intervalMs` 2000 (A56, session 24)
- [x] Default scenario Walk: preselected on Live, also when changed while the app is open; stored with the session (A56, session 24)
- [x] SIM card names the data SIM (A56: 3SinyalKuatHemat 510-89, slot 1)
- [x] Speed test page: explainer with the limits from SpeedTestConfig (10 pings, 10 s, 4 connections, 200/50 MB), help per URL, saved server and Cloudflare switch kept (A56)
- [x] Traffic capture page: explainer, All / Specific apps, MTU 1500 / 10 000; the picker lists launcher apps with icon, name and package (A56, ~100 apps); a tick saves `appFilter` + `allApps` and does not move the row; apps selected earlier are listed first (A56)
- [x] Live capture card while logging: Start capture + "1 selected app · MTU 1500 · change in Settings"; the Settings list shows the same summary (A56, short test session)
- [x] Tapping the open tab again returns from a Settings page to the list (A56)
- [x] Phone capabilities (A56, Tri 510-89, Wi-Fi on, LTE B3 only): device, data SIM, right now (mobile data "Not connected" with Wi-Fi on, not the IMS network; battery optimised), LTE fields available, NR fields "needs 5G coverage", recorded shares over 25 sessions / 6328 rows (NR in the cell list 95 %, CSI-RSRP 0 %, neighbours 59 %)
- [x] Copy report: pasted back on the phone, a Markdown table with LTE/NR prefixes
- [x] How to use (6 sections) and About: icon, version + build commit, GPL text, privacy, OSM attribution, credits with licence texts (A56)
- [x] Phone capabilities with 5G NSA and mobile data on: moto (Tri, NR_NSA) NR values from CellInfo and SignalStrength, NR PCI missing, NR band mapped, mobile data "IPv4" (IMS network ignored); A56 (Telkomsel, LTE CA) "IPv4 + IPv6" (2026-10-16, docs/device-quirks.md)
- [x] A capture with Specific apps (A56, Telkomsel, session 26): only NetLogger selected, all 15 connections in connections.csv are NetLogger's, 0 dropped
- [x] Earlier `appFilter` typed on the old card reads as Specific apps: unit test (`CaptureSettingsTest`); not seen on a phone (neither phone had a list saved)
- [x] Same on the moto g45 (Tri + Tri, data on slot 1): Settings list, Logging (data SIM slot 1), Speed test, Traffic capture, Phone capabilities + Copy report, How to use, About; layout fine at 720 px
- [x] 500 ms interval (moto, screen on, session 17): ticks exactly 500 ms, but 57.5 % of rows carry cell info older than 500 ms (modem refreshes every 1–2 s)
- [ ] IPv6 traffic through the capture: the A56 on Telkomsel has IPv6, but the test traffic (map tiles) went over IPv4

## Map live preview and session sizes (CLAUDE.md 1.7.3)

- [x] Sessions list: size line per session and totals; A56 estimates sum to ~4.1 MB vs. a 4.8 MB database file incl. WAL, captures 159 MB exact; session 23 shows its 103 MB capture (2026-10-16)
- [x] Map "Live session" while not logging: position + trail, "live preview, not recorded", legend counts (moto 28, A56 26 ticks after 25 s); NR SS-RSRP on the moto: 75 in -96 … -110, 9 none
- [x] The preview stops with the Map: moto GPS request OFF ~5 s after switching to Sessions (shared location stream)
- [x] "Recorded session": session and metric dropdowns, legend counts (A56 session 26: LTE 81)
- [x] Map "Live session" while logging: switches from the preview to the recording session ("22 / 22 ticks · 2 events · recording", moto session 18) and back to a fresh preview after Stop
- [ ] Export a session and compare its ZIP size with the estimate

## Live dashboard (decision 004, CLAUDE.md 1.7.4)

- [x] One screen, moto g45 not logging: status bar, operator + "5G NSA · 3 carriers", serving table LTE B3 | NR n41
      (NR values marked SS, copied LTE PCI shown as "–"), Neighbours collapsed, GPS + Data, Events, Details collapsed
      (2026-10-16); on LTE only the table has one column
- [x] Recording: "REC h:mm:ss · ticks · scenario · markers", Marker and Stop; Speed test and Capture rows appear in
      the data card; Marker, "5G added" and "Network type change LTE → 5G NSA" listed in Events (moto)
- [x] Data rate: a 57 kbps ping (1400 B every 0.2 s) shows 57 kbps on cellular ("5 s average")
- [x] Details open survives a force-stop and restart (D7)
- [x] Capture refused (VPN consent cancelled): the capture row shows the reason, logging continues
- [x] Wi-Fi on: Data shows Wi-Fi "SSID" (A56, 2026-10-16; no ACCESS_WIFI_STATE needed); mobile data shows "Data disconnected"
- [ ] With location off, Data shows only "Wi-Fi"
- [ ] Capture running: row shows Capturing with packets / MB / connections; Data shows "via VPN"; rates not doubled
- [ ] Speed test row with a result ("↓ … ↑ … Mbps · … ms") and the Cloudflare link when enabled
- [ ] A cell change while moving: "LTE cell change PCI a → b  x → y dBm" (walk or drive)
- [x] A56 (Telkomsel, LTE B1): layout at its width, one LTE column, Wi-Fi SSID in the data row (2026-10-16)
- [ ] A56 while recording and on 5G (Samsung values)

## Problem reports (decision 005, CLAUDE.md 1.7.5)

Crash tests with the debug-only receiver (`adb shell am broadcast -n id.my.pamslab.netlogger.debug/id.my.pamslab.netlogger.CrashTestReceiver --es kind
java|native|anr --receiver-foreground`; `id.my.pamslab.netlogger.CrashTestVpnReceiver` for the :vpn process):

- [x] Java crash, main process: report with stack trace and log lines (moto, A56)
- [x] Native crash, main process: tombstone summary and log lines (moto, A56); the moto's real MapLibre crash imported
- [x] Crash in :vpn: report (moto: when the app comes to the front; A56/Android 16: at the next start of the app)
- [x] ANR ended with "Close app": report with the main thread of the dump (moto, A56)
- [x] Crash during a recording: the report names the session (moto, session 22)
- [x] Live card once per report; list, report page, Share sheet (raw dump attached only when ticked), GitHub warning,
      Describe a problem (moto); Live card and report page at the A56's size
- [x] GitHub issue: a 9.2 KB report opens the form (URL kept under 7000 encoded characters); two issues sent by the
      user (#9, #10)
- [x] "Was the report sent?" asked on return; Keep keeps it
- [ ] Android ending NetLogger during a recording (low memory / battery limits): needs a long field run (no way to
      cause it without root)

## Speed test buttons and Cloudflare location (CLAUDE.md 1.7.7)

- [x] Database migrated 2 → 3 on the moto's debug install with its field data: 30 sessions, 13 274 ticks and the
      earlier speed test rows kept (backup taken first) (2026-10-17)
- [x] Private server without a server set: red "No private server set: Settings › Speed test", nothing runs
- [x] Cloudflare: full test, "Cloudflare SIN" on Live, `serverLocation` = SIN in every row (moto, Tri)
- [x] Stop during the download: ping kept, DOWNLOAD stored with error `stopped`, upload not run
- [ ] Stop immediately on a slow link (unit test: 9.5 s before the fix, < 1.5 s after)
- [ ] The A56 (Telkomsel): Cloudflare site code and a stop while uploading
- [ ] The session stopped during a test: rows marked `session stopped`

## Before a release

- [x] Release APK 0.2.0 (versionCode 2, build 5349cb9, signed with the release key, arm64-v8a + armeabi-v7a, 49 MB)
      installed on the A56 after uninstalling the debug build (data backed up): permissions flow, Live, a 25-tick
      session with a capture, Sessions, Map (tiles after a few seconds on the empty cache), About, Problem reports;
      no crash; the debug-only CrashTestReceiver and IconPreviewActivity are not in the APK (2026-10-16)
- [x] Debug builds as `id.my.pamslab.netlogger.debug` next to the release app on both phones; the moto's data (23
      sessions, 3 captures, 11 problem reports) copied from the old debug install with run-as and checked row by row,
      then the old app replaced by release 0.2.0 (2026-10-16)

- [x] `ANDROID_SERIAL=ZP22222MQL ./gradlew :core:data:connectedDebugAndroidTest`: DatabaseSmokeTest 2/2 (moto g45,
      2026-10-15, build 2e86d38); also `:feature:capture:connectedDebugAndroidTest` 1/1
- [x] The same on the A56 (`RRGYB06DN2T`): DatabaseSmokeTest 2/2 (2026-10-15, build f973dc1)

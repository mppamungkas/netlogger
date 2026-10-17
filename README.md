<p align="center">
  <img src="docs/branding/netlogger-logo.png" alt="NetLogger logo" width="160">
</p>

# NetLogger

NetLogger is an Android drive-test logger for **LTE and 5G NSA** with its own **traffic capture**. It records
radio measurements, GPS and device context once per tick, shows them live and on a map, and exports clean,
documented datasets. Optionally it captures the phone's IP traffic into a pcap file that is time-aligned with the
radio log, so radio conditions can be compared with what the apps did.

It is built for research and teaching, for example:
- LTE anchor quality vs. 5G NSA availability;
- datasets for machine learning;
- network type vs. battery use.

The design follows three rules: data quality and traceability come before UI polish, it uses **public Android APIs
only** (no root, no vendor diagnostic interfaces), and it never crashes on missing data.

> **Status:** in development (version 0.1.0). No release APK is published yet; build it from source (below).
> Tested on a Motorola moto g45 5G (Android 15) and a Samsung Galaxy A56 (Android 16).

## Features

**Radio logging** (foreground service, works with the screen off)
- Interval of 500, 1000 (default), 2000 or 5000 ms, driven by `elapsedRealtime` (no drift).
- Serving and neighbour cells for LTE, NR, WCDMA and GSM: RSRP, RSRQ, SINR, RSSI, CQI, TA, PCI, ARFCN, band, cell
  ID and TAC, and how old each cell's data is.
- 5G NSA: NR values from `CellInfoNr` *and* from `SignalStrength`, because many phones report NSA cells only there.
  The source of each value is recorded.
- Bands from the API, or mapped from the ARFCN when the phone reports none. The band source is recorded.
- The 5G icon state (`overrideNetworkType`), carrier-aggregation bandwidths, data state, roaming.
- GPS (one location stream), battery level, current, voltage and temperature, traffic counters, screen state,
  thermal status.
- Events: RAT changes, LTE/NR PCI changes, NR added/removed, no service, GPS lost/restored, user markers.
- Dual SIM: follows the active data SIM.
- A session killed by the system is closed cleanly on the next app start.

**Screens**
- **Live:** one dashboard, in the spirit of G-NetTrack's:
  - a status bar with the recording time, Marker and Stop;
  - the operator and network type in words ("5G NSA · 3 carriers");
  - one serving-cell table with LTE and NR side by side, coloured like the map; neighbours collapsed;
  - GPS, the data route (mobile network, or Wi-Fi with its name) with download and upload rates;
  - the speed test and capture as one row each, and the last events ("LTE cell change PCI 123 → 211, -104 → -93 dBm").
  Raw values, battery and parse warnings are under Details. Values older than 5 s are greyed out.
- **Map** (MapLibre with OpenStreetMap tiles):
  - The recording session, or a live preview while not logging (in memory only, never stored), or any recorded session.
  - The track is coloured by LTE RSRP, NR SS-RSRP, SINR or network type; the legend counts the ticks per colour.
  - Event markers; tap a point to see its values.
- **Sessions:** list with the estimated size on the phone and of the export, label, notes, scenario, charts (RSRP and
  SINR over time, network type timeline), export, delete.
- **Speed test:** ping, download and upload over mobile data against a server you set (for example your own
  [LibreSpeed](https://github.com/librespeed/speedtest) server), or against Cloudflare for comparison, with Stop to end
  a test early; Cloudflare's answering site (e.g. SIN) is stored with the result. Redirects are never followed, so an operator's captive portal is reported as an error, not as a
  measurement.
- **Settings:** written for teachers and students; every option explains itself.
  - Logging, Speed test and Traffic capture options.
  - **Phone capabilities:** which radio fields this phone reports right now, and how often they appeared in your
    recorded sessions. Copy report gives a Markdown table.
  - How to use, About (licences, privacy, credits).
- **Problem reports:** if NetLogger crashes, freezes or is ended by Android during a recording, a card on Live says
  so and Settings › Problem reports keeps a report (what happened, a technical trace, NetLogger's own log lines; no
  location or cell identities). The user sends it by e-mail/chat or as a GitHub issue; nothing is sent automatically.

**Traffic capture** (NetLogger's own `VpnService`, no other app needed)
- Native C capture loop with [zdtun](https://github.com/emanuele-f/zdtun) as the user-space forwarder, in a separate
  process, so a capture failure cannot stop radio logging.
- Captures all apps or only the apps you pick.
- Classic pcap with **nanosecond timestamps** taken from the same clock as the radio log (`elapsedRealtimeNs` plus
  one fixed wall-clock offset), so packets and ticks line up exactly.
- `connections.csv` with the app that owns each connection.
- MTU 1500 (realistic packet sizes) or 10 000 (lower throughput cost).

Limits of the capture: it sees the IP traffic between each app and the VPN, not the radio. Packet sizes and timing on
the air are rebuilt by the forwarder. It cannot run while another VPN is active, and it adds overhead (speed tests
during a capture are flagged).

**Not possible with public APIs** (out of scope): Layer 3 / RRC / NAS signalling, physical channel configuration, and
anything that needs root or the modem's diagnostic port.

## Export

Each session exports as one ZIP (save to a folder or share):

| File | Content |
|---|---|
| `session.json` | Session metadata: device, Android version, baseband, operator, interval, time zone |
| `measurements.csv` | One row per tick |
| `cells.csv` | One row per cell per tick (serving and neighbours) |
| `flat.csv` | One row per tick with the serving LTE and NR cells side by side, for spreadsheets |
| `events.csv`, `warnings.csv`, `throughput.csv` | Events, parse warnings, speed test results |
| `track.geojson` | GPS track and events |
| `capture.pcap`, `capture_sync.csv`, `connections.csv` | With a traffic capture |

Every row has both `wallTimeMs` and `elapsedRealtimeNs` (the sync key with the pcap). CSV files are UTF-8 with `.`
as the decimal separator on every locale, and **an empty field means "not reported"**, never `0` or `-999`. All
columns and units are defined in [`docs/columns.md`](docs/columns.md).

## Requirements

- Android 12 (API 31) or newer, with a SIM and mobile data. A 5G NSA network is needed to see NR values.
- Permissions: location (while in use), phone state, notifications. Background location is not needed.
- What each phone reports differs a lot by manufacturer and modem; see Settings › Phone capabilities and
  [`docs/device-quirks.md`](docs/device-quirks.md).

## Build

Requirements: JDK 17 or 21, the Android SDK (platform 35), **NDK 27.3.13750724** and **CMake 3.22.1** (pinned in
`gradle/libs.versions.toml`), and a host `gcc` for the native unit tests.

```
./gradlew assembleDebug          # build the APK (app/build/outputs/apk/debug/)
./gradlew installDebug           # install on a connected phone
./gradlew testDebugUnitTest      # unit tests, including the C tests with ASan/UBSan
./gradlew lintDebug              # lint
```

Optional, in `local.properties` (not committed): to use a speed test server over plain HTTP, list its host:

```
netlogger.cleartextHosts=<host>[,<host>]
```

Without it, the app allows HTTPS only. The server URLs themselves are set in the app (Settings › Speed test).

There is no emulator support worth using: an emulator has no real radio. All testing happens on real phones.

## Project layout

```
app/              Application, activity, navigation, DI wiring
core/model        Plain Kotlin data classes
core/telephony    Radio collection, cell parser, band mapper, event detector
core/location     Location stream
core/device       Battery, traffic counters, thermal, device info
core/data         Room database, repositories, settings
core/export       CSV, GeoJSON and ZIP writers, size estimate
core/diagnostics  Problem reports: crash handler, exit records, report text
core/ui           Theme and shared components
feature/logger    Logging service and Live screen
feature/map       Map screen
feature/sessions  Sessions list, detail, charts, export
feature/speedtest Ping, download, upload
feature/settings  Settings pages, phone capabilities, help, about
feature/capture   VpnService capture (libnetcapture in C, zdtun)
docs/             Column definitions, device quirks, field test checklist, design decisions
```

Design decisions: [cell parsing](docs/decisions/001-cell-parsing.md),
[native capture](docs/decisions/002-native-capture.md), [settings](docs/decisions/003-settings-ux.md),
[Live dashboard](docs/decisions/004-live-dashboard.md), [problem reports](docs/decisions/005-problem-reports.md).

## Privacy

Sessions and exports contain your precise location, the cells you used and your phone model and operator. A traffic
capture contains the network traffic of the captured apps. Everything stays on the phone until you export it.
Problem reports also stay on the phone until you send one. NetLogger has no account, no analytics and no upload. It only contacts the OpenStreetMap tile server, the speed test
server you set or Cloudflare (only when you run a test), and Google Play services for the location.

## Licence

NetLogger is free software under the [GNU General Public License v3](LICENSE).

It includes code from other projects ([`docs/third-party.md`](docs/third-party.md)):
- zdtun (LGPL v3 or later);
- Samsung workarounds adapted from [NetMonster Core](https://github.com/mroczis/netmonster-core) (Apache 2.0).

The libraries it uses and their full licence texts are listed in the app under Settings › About.

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors.

# Export columns

One session exports as `netlogger_<model>_<start yyyyMMdd-HHmmss>_<label or scenario>_<uuid prefix>.zip`, which contains:

| File | Content |
|---|---|
| `session.json` | Session metadata (below) |
| `measurements.csv` | One row per tick |
| `cells.csv` | One row per cell per tick: serving and neighbor cells from CellInfo, plus one row per RAT from SignalStrength |
| `flat.csv` | One row per tick: the serving LTE and the serving NR cell side by side, for spreadsheets and quick plots |
| `events.csv` | Events: user markers and detected network, GPS and data-SIM events |
| `warnings.csv` | Parse warnings and flagged corrections, one row per (code, RAT, PCI, field) per session |
| `throughput.csv` | Speed test results, one row per phase (ping, download, upload) |
| `capture.pcap` | With a traffic capture: classic PCAP with nanosecond timestamps (magic `0xa1b23c4d`), LINKTYPE_RAW (IP packets, no link layer). Sessions from the PCAPdroid bridge (milestone 10, removed in CLAUDE.md 1.7.0) have microsecond timestamps from PCAPdroid |
| `capture_sync.csv` | With `capture.pcap`: time alignment, below |
| `connections.csv` | With a traffic capture: one row per connection with its app, below (not in PCAPdroid-bridge sessions) |
| `track.geojson` | GPS track (LineString) and events with a location (Points), RFC 7946 |

## Format rules (all CSV files)

- UTF-8 without BOM, comma separator, CRLF line ends, one header row (RFC 4180). Fields with a comma, quote, line
  break or leading/trailing space are quoted, quotes doubled.
- **Empty field = not reported.** Never `0`, `-999` or another sentinel for a missing value. Android's
  `UNAVAILABLE` / `Int.MAX_VALUE` are empty.
- Numbers use `.` as decimal separator, no thousands separator, no scientific notation, regardless of the phone's
  locale. Booleans are `true` / `false`. Enum values are their names (e.g. `NR_NSA`).
- **Time:** `time` and other ISO columns are ISO-8601 with milliseconds and UTC offset, e.g.
  `2026-10-14T05:55:32.434+07:00`, in the phone's time zone **at export** (stored in `session.json` as `timeZone`).
  Every file also has raw `wallTimeMs` (Unix epoch ms, phone clock) and `elapsedRealtimeNs` (ns since boot, immune to
  clock changes; the sync key with pcap files in Phase 3).
- `seq` is the tick number in the session, starting at 0. It joins `measurements.csv`, `cells.csv`, `flat.csv` and
  `warnings.csv` (`firstSeq`).
- Spreadsheet note: Excel may drop leading zeros of `mnc` (e.g. `01`) and show long IDs in scientific notation;
  import as text if needed.
- Sign convention: dBm / dB values are negative where the quantity is (RSRP, RSRQ, RSSI).

## measurements.csv

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number, 0-based |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `lat` | ° WGS84 | Latitude of the newest fix at or before the tick; empty without a fix |
| `lon` | ° WGS84 | Longitude |
| `altitude` | m | Altitude above the WGS84 ellipsoid |
| `speedMps` | m/s | GPS speed |
| `bearing` | ° | GPS bearing, 0 = north |
| `gpsAccuracy` | m | Horizontal accuracy (68 %) |
| `locationAgeMs` | ms | Tick time minus fix time; large values mean a stale fix |
| `locationStatus` | | `OK`, `WAITING`, `NO_PERMISSION`, `DISABLED`, `UNAVAILABLE`, `ERROR` |
| `networkType` | | NetLogger's classification: `LTE`, `NR_NSA`, `NR_SA`, `WCDMA`, `GSM`, `NONE` |
| `overrideNetworkType` | | `TelephonyDisplayInfo.getOverrideNetworkType()`: 0 none, 1 LTE_CA, 2 LTE_ADVANCED_PRO, 3 NR_NSA, 4 NR_NSA_MMWAVE, 5 NR_ADVANCED |
| `dataNetworkType` | | `TelephonyManager.NETWORK_TYPE_*` of the data connection (13 = LTE, 20 = NR) |
| `nsaLteServing` | | NSA input: an LTE serving cell is present (CellInfo) |
| `nsaNrCellInfo` | | NSA input: a serving NR cell is present in CellInfo (`servingInferred`) |
| `nsaNrSignal` | | NSA input: SignalStrength has a valid, current NR value (a stale one, see `NR_SIGNAL_STRENGTH_STALE`, does not count) |
| `serviceState` | | `ServiceState.getState()`: 0 in service, 1 out of service, 2 emergency only, 3 power off |
| `dataState` | | `TelephonyManager.getDataState()`: 0 disconnected, 1 connecting, 2 connected, 3 suspended |
| `isRoaming` | | Roaming flag from ServiceState |
| `operatorNumeric` | | MCC+MNC of the registered network (ServiceState), e.g. `51089`. Use this for the operator, not the cell's PLMN (network sharing) |
| `cellInfoOrigin` | | `FRESH` (cell info request completed), `CACHED`, `UNAVAILABLE`. Judge freshness by `cellInfoAgeMs` in cells.csv |
| `cellBandwidthsJson` | kHz | `ServiceState.getCellBandwidths()` as a JSON array, e.g. `[20000,10000]`; best-effort CA hint |
| `batteryLevel` | % | Battery level |
| `batteryCurrentUa` | µA | Instantaneous current; sign convention is OEM-specific |
| `batteryVoltageMv` | mV | Battery voltage |
| `batteryTempC` | °C | Battery temperature |
| `isCharging` | | Charging |
| `rxBytesTotal` | bytes | `TrafficStats.getTotalRxBytes()`: all interfaces incl. Wi-Fi, counter since boot; use differences between ticks |
| `txBytesTotal` | bytes | `TrafficStats.getTotalTxBytes()` |
| `rxBytesApp` | bytes | NetLogger's own received bytes (`getUidRxBytes`) |
| `txBytesApp` | bytes | NetLogger's own sent bytes |
| `screenOn` | | Screen interactive |
| `thermalStatus` | | `PowerManager.getCurrentThermalStatus()`: 0 none … 6 shutdown |
| `parseErrors` | count | Telephony getter calls that threw in this tick (real parse failures) |
| `warningCount` | count | Warnings raised in this tick, including flagged OEM corrections (see warnings.csv) |

## cells.csv

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `rat` | | `LTE`, `NR`, `WCDMA`, `GSM` |
| `source` | | `CELL_INFO` (`getAllCellInfo`, has identity) or `SIGNAL_STRENGTH` (`SignalStrength.getCellSignalStrengths()`, serving signal only, no identity) |
| `reportedServing` | | The phone's own label, never corrected: registered, or connection status PRIMARY/SECONDARY_SERVING. Empty for SIGNAL_STRENGTH rows |
| `servingInferred` | | NetLogger's serving flag after flagged corrections; use this one. SIGNAL_STRENGTH rows are `true`, except a stale entry: NR with no 5G indicator, no serving NR cell and older than 10 s (`NR_SIGNAL_STRENGTH_STALE`), or LTE older than 10 s (`LTE_SIGNAL_STRENGTH_STALE`) |
| `servingSource` | | NR CELL_INFO rows only: how `servingInferred` was decided. `REPORTED` (phone's label kept), `NSA_INFERRED` (phone gave no label; one NR cell under an NSA override), `SIGNAL_STRENGTH_MATCH` / `STRONGEST` (several candidates on one NR-ARFCN; the one matching SignalStrength within 1 dB, else the strongest), `AMBIGUOUS` (no basis; none serving). See `docs/decisions/001-cell-parsing.md` |
| `isRegistered` | | `CellInfo.isRegistered()` |
| `connectionStatus` | | `NONE`, `PRIMARY_SERVING`, `SECONDARY_SERVING` after corrections; the original is in `reportedServing` and warnings.csv |
| `ciOrNci` | | LTE ECI (28 bit), NR NCI (36 bit), WCDMA/GSM CID |
| `tac` | | TAC (LTE/NR) or LAC (WCDMA/GSM) |
| `pci` | | PCI (LTE 0–503, NR 0–1007), PSC (WCDMA) or BSIC (GSM) |
| `arfcn` | | EARFCN, NR-ARFCN, UARFCN or ARFCN |
| `band` | | 3GPP band number (NR without the `n`); GSM: band by frequency in MHz (850, 900, 1800, 1900) |
| `bandSource` | | `API` (`getBands()`), `MAPPED` (from the ARFCN: TS 36.101 / 38.101 / 25.101 / 45.005; also when `getBands()` contradicts a band the ARFCN settles, see `API_BAND_CONFLICT`), `MAPPED_MCC_FALLBACK` (mapped with the registered cell's MCC because the network MCC was not known yet) |
| `bandwidthKhz` | kHz | LTE cell bandwidth (`CellIdentityLte.getBandwidth()`) |
| `mcc` | | Cell's MCC |
| `mnc` | | Cell's MNC (may differ from the SIM operator under network sharing) |
| `rsrp` | dBm | LTE RSRP or NR SS-RSRP |
| `rsrq` | dB | LTE RSRQ or NR SS-RSRQ |
| `sinr` | dB | LTE RSSNR (scaling varies by OEM) or NR SS-SINR |
| `rssi` | dBm | LTE RSSI or GSM RSSI |
| `cqi` | | LTE CQI 0–15 |
| `ta` | | Timing advance as reported (LTE index 0–1282, GSM 0–219) |
| `csiRsrp` | dBm | NR CSI-RSRP |
| `csiRsrq` | dB | NR CSI-RSRQ |
| `csiSinr` | dB | NR CSI-SINR |
| `rscp` | dBm | WCDMA RSCP |
| `ecNo` | dB | WCDMA Ec/No |
| `cellInfoAgeMs` | ms | Tick time minus the cell's own timestamp (`CellInfo.getTimestampMillis()`; for SIGNAL_STRENGTH rows `SignalStrength.getTimestampMillis()`). ~10 s with the screen off is the modem refresh, not a NetLogger delay |

## flat.csv

One row per tick. Identity comes only from the CELL_INFO serving cell. Each signal value takes the CELL_INFO value,
else the SIGNAL_STRENGTH value (never a stale entry: `NR_SIGNAL_STRENGTH_STALE`, `LTE_SIGNAL_STRENGTH_STALE`); its `*Source` column says which (`CELL_INFO`, `SIGNAL_STRENGTH`, or empty when neither
had it). The two sources are sampled at different times; compare `*CellInfoAgeMs` and `*SignalStrengthAgeMs`.

- **LTE:** the registered `servingInferred` LTE CELL_INFO cell (else PRIMARY_SERVING, else the first serving one).
- **NR:** the `servingInferred` NR CELL_INFO cell; with several (NR carrier aggregation) the strongest SS-RSRP.

| Column | Unit | Description |
|---|---|---|
| `seq` | | Tick number |
| `time` | ISO-8601 | Tick wall time |
| `wallTimeMs` | ms | Tick wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Tick time since boot |
| `lat` | ° | As in measurements.csv |
| `lon` | ° | As in measurements.csv |
| `altitude` | m | As in measurements.csv |
| `speedMps` | m/s | As in measurements.csv |
| `gpsAccuracy` | m | As in measurements.csv |
| `locationAgeMs` | ms | As in measurements.csv |
| `networkType` | | As in measurements.csv |
| `overrideNetworkType` | | As in measurements.csv |
| `operatorNumeric` | | As in measurements.csv |
| `cellBandwidthsJson` | kHz | As in measurements.csv |
| `lteCi` | | Serving LTE ECI |
| `lteTac` | | Serving LTE TAC |
| `ltePci` | | Serving LTE PCI |
| `lteEarfcn` | | Serving LTE EARFCN |
| `lteBand` | | Serving LTE band |
| `lteBandSource` | | As `bandSource` in cells.csv |
| `lteBandwidthKhz` | kHz | Serving LTE bandwidth |
| `lteCellInfoAgeMs` | ms | Age of the CELL_INFO serving LTE cell |
| `lteRsrp` | dBm | LTE RSRP |
| `lteRsrpSource` | | Source of `lteRsrp` |
| `lteRsrq` | dB | LTE RSRQ |
| `lteRsrqSource` | | Source of `lteRsrq` |
| `lteSinr` | dB | LTE RSSNR (moto g45: only in SignalStrength, so empty while that entry is older than 10 s) |
| `lteSinrSource` | | Source of `lteSinr` |
| `lteRssi` | dBm | LTE RSSI |
| `lteRssiSource` | | Source of `lteRssi` |
| `lteCqi` | | LTE CQI |
| `lteCqiSource` | | Source of `lteCqi` |
| `lteTa` | | LTE timing advance |
| `lteTaSource` | | Source of `lteTa` |
| `lteSignalStrengthAgeMs` | ms | Age of the SIGNAL_STRENGTH LTE row |
| `nrServingCount` | count | Number of `servingInferred` NR CELL_INFO cells in the tick (> 1 = NR CA) |
| `nrServingSource` | | `servingSource` of the chosen NR cell |
| `nrNci` | | Serving NR NCI |
| `nrTac` | | Serving NR TAC (empty in NSA: NSA NR cells broadcast none) |
| `nrPci` | | Serving NR PCI |
| `nrArfcn` | | Serving NR-ARFCN |
| `nrBand` | | Serving NR band |
| `nrBandSource` | | As `bandSource` in cells.csv |
| `nrCellInfoAgeMs` | ms | Age of the CELL_INFO serving NR cell |
| `nrRsrp` | dBm | NR SS-RSRP |
| `nrRsrpSource` | | Source of `nrRsrp` |
| `nrRsrq` | dB | NR SS-RSRQ |
| `nrRsrqSource` | | Source of `nrRsrq` |
| `nrSinr` | dB | NR SS-SINR |
| `nrSinrSource` | | Source of `nrSinr` |
| `nrCsiRsrp` | dBm | NR CSI-RSRP |
| `nrCsiRsrpSource` | | Source of `nrCsiRsrp` |
| `nrCsiRsrq` | dB | NR CSI-RSRQ |
| `nrCsiRsrqSource` | | Source of `nrCsiRsrq` |
| `nrCsiSinr` | dB | NR CSI-SINR |
| `nrCsiSinrSource` | | Source of `nrCsiSinr` |
| `nrSignalStrengthAgeMs` | ms | Age of the SIGNAL_STRENGTH NR row |
| `batteryLevel` | % | As in measurements.csv |
| `batteryCurrentUa` | µA | As in measurements.csv |
| `isCharging` | | As in measurements.csv |
| `screenOn` | | As in measurements.csv |
| `thermalStatus` | | As in measurements.csv |
| `rxBytesTotal` | bytes | As in measurements.csv |
| `txBytesTotal` | bytes | As in measurements.csv |
| `parseErrors` | count | As in measurements.csv |
| `warningCount` | count | As in measurements.csv |

## events.csv

| Column | Unit | Description |
|---|---|---|
| `time` | ISO-8601 | Event wall time |
| `wallTimeMs` | ms | Event wall time, Unix epoch |
| `elapsedRealtimeNs` | ns | Event time since boot |
| `type` | | See the table below |
| `fromValue` | | Previous value; empty when there is none |
| `toValue` | | New value; empty when there is none |
| `lat` | ° | Location at the event; empty without a fix |
| `lon` | ° | Location at the event |

Detected events compare each tick with the previous one (`EventDetector`). There is **no debouncing** except for the
serving NR cell: every other change at tick resolution is an event, so ping-pong between LTE cells within a few ticks
stays visible; filter in analysis. A new serving NR cell must be seen in 2 ticks (NR still up, ticks without an NR
CellInfo cell in between do not count against it) before `PCI_CHANGE_NR` is written, stamped with the first tick's time
and position; a one-tick flip (A56: the serving pick among cells on one NR-ARFCN) writes nothing. A value
missing for one tick (no CellInfo cell, unknown service state) is not a change. The first tick of a session only sets
the state. The serving cells are chosen as in flat.csv.

| Type | fromValue → toValue | When |
|---|---|---|
| `USER_MARKER` | – | Live button or notification |
| `RAT_CHANGE` | `networkType` → `networkType` | `networkType` changed |
| `PCI_CHANGE_LTE` | `pci/earfcn` → `pci/earfcn` | Serving LTE cell changed (PCI or EARFCN) |
| `PCI_CHANGE_NR` | `pci/arfcn` → `pci/arfcn` | Serving NR cell changed while NR stayed up, confirmed over 2 ticks; only cells with a known PCI (none on the moto g45) |
| `NR_ADDED` | – → `pci/arfcn` or empty | A serving NR cell or a current NR SignalStrength appeared (`nsaNrCellInfo` or `nsaNrSignal`); the NR leg, not the 5G icon |
| `NR_REMOVED` | `pci/arfcn` or empty → – | Both went away |
| `NO_SERVICE` | `serviceState` → `serviceState` | ServiceState left IN_SERVICE (0) |
| `SERVICE_RESTORED` | `serviceState` → `serviceState` | Back to IN_SERVICE, also when the session started without service |
| `GPS_LOST` | – → reason | After a first fix: location status not OK (status name), no fix (`NO_FIX`) or fix older than 5 s (`STALE_FIX`) |
| `GPS_RESTORED` | reason → – | A fix no older than 5 s again |
| `DATA_SIM_CHANGED` | subId → subId | Default data SIM changed; the session keeps logging its own SIM |
| `CAPTURE_STARTED` | `NATIVE` → file name, `wallMinusElapsedNs`, `mtu` | NetLogger's VPN reported its first packet counters; at most one capture per session. Sessions from the removed PCAPdroid bridge: `TCP` / `FILE` → file name and `wallMinusElapsedNs` (PCAPdroid accepted the start request) |
| `CAPTURE_STOPPED` | reason → counts | Capture ended. Reason: `USER` (stop button), `SESSION_END`, `PCAPDROID` (old sessions: PCAPdroid stopped by itself), `VPN_REVOKED` (native: another VPN started or the VPN was turned off in Android's settings), `ERROR` (native capture loop failed), `PROCESS_DIED` (native: the capture process ended without reporting; the pcap is valid up to its last flush, at most ~1 s lost). Counts of old PCAPdroid-bridge sessions: `netlogger_packets`, `netlogger_bytes`, `pcapdroid_packets` (pkts_sent + pkts_rcvd), `pcapdroid_bytes_dumped`, `missing` (PCAPdroid's minus NetLogger's count; slightly negative is normal, PCAPdroid reads its counters just before its last packets are dumped; positive means loss), `not_drained` if PCAPdroid did not close the stream within 5 s, `error`. Native counts: `pcap_packets`, `pcap_bytes`, `expected_packets` (packets that crossed the VPN; equal to `pcap_packets` unless `dropped` > 0), `dropped` (pcap write errors), `tun_rx_packets` (from apps), `tun_tx_packets` (to apps), `tun_tx_errors`, `connections_tcp/udp/icmp`, `uid_unknown`, `conns_failed`, `unparsed` / `fragments` / `tcp_without_conn` (captured but not forwarded: e.g. IPv6 control packets, IP fragments, TCP segments of connections older than the capture), `exit`. Both: `wallMinusElapsedNs` |

## throughput.csv

One row per speed test phase (milestone 8): ping, download, upload, in that order per test. A test runs only over
mobile data (with Wi-Fi as the default network every phase gets `error` = "default network is not mobile data").
Started from the Live screen or the notification; server URLs are set in Settings (LibreSpeed backend or any web
server). Align with measurements.csv by `startedElapsedNs`.

| Column | Unit | Description |
|---|---|---|
| `startedAt` | ISO-8601 | Phase start, wall time |
| `startedAtMs` | ms | Phase start, Unix epoch |
| `startedElapsedNs` | ns | Phase start since boot (sync key) |
| `type` | | `PING`, `DOWNLOAD` or `UPLOAD` |
| `serverUrl` | | URL the phase used (each request adds a unique `nl` query parameter against caches): the user's private server set in Settings, or `https://speed.cloudflare.com/…` for the public Cloudflare test |
| `serverLocation` | | Where the server answered from, when it says so: Cloudflare's location code from the `CF-RAY` response header, an IATA airport code such as `SIN` (Singapore) or `CGK` (Jakarta). Empty for servers that do not report one and for rows recorded before schema v3 |
| `durationMs` | ms | Phase time actually measured |
| `bytes` | bytes | DOWNLOAD: bytes read; UPLOAD: bytes handed to the socket (the last socket buffer may still be in flight) |
| `mbps` | Mbit/s | bytes × 8 / phase time / 10^6, whole phase incl. TCP ramp-up; empty when the server refused (HTTP error) or nothing was transferred |
| `streams` | | Parallel connections (download/upload: 4; ping: 1) |
| `rttMinMs` | ms | PING: fastest HTTP round trip (request sent to response read, kept-alive connection; includes server time, unlike ICMP) |
| `rttAvgMs` | ms | PING: mean round trip of the counted requests (10; a first request opens the connection and is not counted) |
| `rttMaxMs` | ms | PING: slowest round trip |
| `jitterMs` | ms | PING: mean absolute difference between consecutive round trips |
| `lossPct` | % | PING: failed counted requests (3 s timeout each) |
| `captureActive` | | A traffic capture was running when the test started: its overhead may lower the result |
| `error` | | Why the phase failed or was refused; empty when it worked. A failed phase keeps what it measured. `stopped` / `session stopped`: the user pressed Stop, or the session ended, during this phase; phases finished before are kept. Redirects are never followed: `HTTP 302 (redirect to <host>)` usually means the operator's captive portal (e.g. `bimatri.ioh.co.id` when Tri's data quota is used up), not the server |

Limits per test: 10 s per throughput phase, at most 200 MB download and 50 MB upload.

## capture_sync.csv

Packets keep PCAPdroid's own timestamps (the phone's wall clock). This file pairs the wall clock with
`elapsedRealtimeNs` at capture START and STOP, so a packet maps onto the radio log's axis as
`elapsedRealtimeNs = pcapTsSec × 10^9 + pcapTsUsec × 10^3 - wallMinusElapsedNs`. If START and STOP differ, the wall
clock was changed (or synced) during the capture; interpolate or split there. Not checked by ColumnsDocTest.

**Native mode:** packet timestamps are `elapsedRealtimeNs + wallMinusElapsedNs` with the START row's offset, fixed
for the whole file (measured to within a few hundred ns, `clock_uncertainty_ns`), so
`elapsedRealtimeNs = pcapTsSec × 10^9 + pcapTsNsec - wallMinusElapsedNs(START)` exactly, even if the wall clock
changes. `CHECK` (every 60 s) and `STOP` read both clocks again: a different `wallMinusElapsedNs` there means the
phone's wall clock moved (NTP or user), which shifts `firstSeen`/wall times only, not the alignment.

| Column | Unit | Description |
|---|---|---|
| `point` | | `START` (PCAPdroid accepted / native capture running), `CHECK` (native: every 60 s) or `STOP` |
| `wallTimeMs` | ms | Wall clock at that moment, Unix epoch |
| `elapsedRealtimeNs` | ns | elapsedRealtimeNs at the same moment (midpoint of two reads around the wall-clock read) |
| `wallMinusElapsedNs` | ns | `wallTimeMs × 10^6 - elapsedRealtimeNs` |
| `counts` | | STOP: as in the CAPTURE_STOPPED event. Native START: `pcap_offset clock_uncertainty_ns=…` |

## connections.csv

Native capture only (milestone 11): one row per connection (flow) that crossed NetLogger's VPN, written when it
ends; connections still open when the capture stops are written then. "Out" is app → network, "in" network → app,
counted on the tun (the packets in `capture.pcap`). Times use the capture's own clock offset, so they match the pcap
timestamps exactly. Not checked by ColumnsDocTest.

| Column | Unit | Description |
|---|---|---|
| `protocol` | | `TCP`, `UDP` or `ICMP` (ICMPv6 included) |
| `ipVersion` | | 4 or 6 |
| `srcIp`, `srcPort` | | The app's side: NetLogger's tun address and the app's port (ICMP: the echo id) |
| `dstIp`, `dstPort` | | The remote side (ICMP: no port) |
| `uid` | | Owning app's Linux uid from `ConnectivityManager.getConnectionOwnerUid()`; empty when unknown (always for ICMP) |
| `app` | | Package name(s) of `uid`, joined by `\|` for a shared uid (e.g. `com.google.android.gms\|com.google.android.gsf`); a system uid's name (`android.uid.system:1000`); empty if the uid has none (e.g. 1051, the system DNS resolver) or the app has no launcher icon (Android hides such packages from NetLogger; `uid` is still filled) |
| `firstSeen`, `lastSeen` | ISO-8601 | First and last packet of the connection |
| `firstSeenElapsedNs`, `lastSeenElapsedNs` | ns | The same in elapsedRealtimeNs (the radio log's axis) |
| `durationMs` | ms | `lastSeen - firstSeen` |
| `packetsOut`, `bytesOut` | count, B | Packets and IP bytes from the app |
| `packetsIn`, `bytesIn` | count, B | Packets and IP bytes to the app |
| `status` | | zdtun's final state: `CLOSED`, `RESET`, `UNREACHABLE`, `ERROR`, `SOCKET_ERROR`, `CLIENT_ERROR` |
| `socketError` | errno | The forwarding socket's error, if any (32 = EPIPE: the remote side was still sending when the app closed, e.g. at the end of a curl download) |

## warnings.csv

Only anomalies and flagged corrections; sentinel values ("not reported") never raise a warning. Codes are documented
in `core/model/.../radio/Radio.kt` (`WarningCode`) and `docs/decisions/001-cell-parsing.md`.

| Column | Unit | Description |
|---|---|---|
| `code` | | Warning code, e.g. `SAMSUNG_NEIGHBOR_PLACEHOLDER`, `NR_SERVING_STRONGEST` |
| `rat` | | RAT of the affected cell |
| `pci` | | PCI of the affected cell |
| `field` | | Affected field, e.g. `tac`, `connectionStatus` |
| `rawValue` | | Original value at the first occurrence |
| `lastRawValue` | | Original value at the last occurrence |
| `occurrences` | count | Ticks in which it occurred (one per tick and key) |
| `firstSeen` | ISO-8601 | First occurrence |
| `lastSeen` | ISO-8601 | Last occurrence |
| `firstSeenMs` | ms | First occurrence, Unix epoch |
| `lastSeenMs` | ms | Last occurrence, Unix epoch |
| `firstSeq` | | Tick of the first occurrence |
| `firstElapsedRealtimeNs` | ns | First occurrence, time since boot |

## track.geojson

A FeatureCollection. Coordinates are `[lon, lat]` (RFC 7946 order), WGS84, 2D.

- Track: one `LineString` of all ticks with a fix, in order (a `Point` if there is only one fix; nothing if none).
  Properties: `kind` = `track`, `sessionUuid`, `points`, `start`, `end` (ISO-8601).
- Events: one `Point` per event with a location. Properties: `kind` = `event`, `type`, `time`, `wallTimeMs`,
  `elapsedRealtimeNs`, `fromValue`, `toValue`.

## session.json

| Field | Description |
|---|---|
| `format`, `formatVersion` | `netlogger-session`, 1 |
| `uuid` | Session UUID (also in the file name) |
| `label`, `scenario`, `notes` | As entered (`DRIVE`, `WALK`, `STATIC`, `INDOOR`) |
| `startedAt`, `startedAtMs`, `startedElapsedRealtimeNs` | Start, ISO / epoch ms / ns since boot |
| `endedAt`, `endedAtMs`, `endedElapsedRealtimeNs` | End; for `RECOVERED_AFTER_KILL` the last tick |
| `endReason` | `USER_STOP`, `RECOVERED_AFTER_KILL`, `ERROR` |
| `durationMs`, `intervalMs` | End minus start; tick interval |
| `timeZone`, `utcOffset` | Zone used for every ISO time in the bundle (phone zone at export) and its offset at session start |
| `exportedAt` | Export time |
| `appVersion`, `schemaVersion` | NetLogger version and database schema version |
| `device` | `manufacturer`, `model`, `androidVersion`, `basebandVersion` |
| `network` | `subscriptionId`, `simSlot`, `operatorName`, `mcc`, `mnc` of the logged SIM |
| `captureFile` | Traffic capture: `<uuid>.pcap` (in the ZIP as capture.pcap); old PCAPdroid FILE-mode sessions: `Download/PCAPdroid/<name>` (stays on the phone); empty without a capture |
| `counts` | Rows per file: `measurements`, `cells`, `events`, `warnings`, `throughputTests`, and `trackPoints` (ticks with a fix) |
| `files` | Files in the bundle |

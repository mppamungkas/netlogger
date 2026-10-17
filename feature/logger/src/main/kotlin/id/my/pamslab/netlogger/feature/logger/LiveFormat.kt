package id.my.pamslab.netlogger.feature.logger

import id.my.pamslab.netlogger.core.model.device.LinkCounters
import id.my.pamslab.netlogger.core.model.device.NetworkRoute
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.radio.CellSample
import id.my.pamslab.netlogger.core.model.radio.CellSource
import id.my.pamslab.netlogger.core.model.radio.ConnectionStatus
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.Rat
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import java.util.Locale

// Pure formatting for the Live dashboard (decision 004), unit-tested.

/** "h:mm:ss" from nanoseconds; negative counts as 0. */
internal fun formatElapsed(ns: Long): String {
    val s = (ns / 1_000_000_000).coerceAtLeast(0)
    return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s / 60 % 60, s % 60)
}

internal fun Scenario.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Network type in words, plus the carrier count when the phone reports more than one cell bandwidth. */
internal fun networkLabel(type: NetworkType, cellBandwidthsKhz: List<Int>): String {
    val base = when (type) {
        NetworkType.NR_SA -> "5G SA"
        NetworkType.NR_NSA -> "5G NSA"
        NetworkType.LTE -> "LTE"
        NetworkType.WCDMA -> "3G"
        NetworkType.GSM -> "2G"
        NetworkType.NONE -> return "No service"
    }
    return if (cellBandwidthsKhz.size > 1) "$base · ${cellBandwidthsKhz.size} carriers" else base
}

/** TelephonyManager data states other than connected, in words; null when connected or unknown. */
internal fun dataStateLabel(dataState: Int?): String? = when (dataState) {
    DATA_DISCONNECTED -> "Data disconnected"
    DATA_CONNECTING -> "Data connecting"
    DATA_SUSPENDED -> "Data suspended"
    else -> null
}

private const val DATA_DISCONNECTED = 0
private const val DATA_CONNECTING = 1
private const val DATA_SUSPENDED = 3

/** A value on the Live serving table and whether it came from SignalStrength rather than CellInfo. */
internal data class Shown<T>(val value: T?, val fromSignalStrength: Boolean = false)

/**
 * One serving RAT as a column of the Live table: the serving CellInfo cell (the primary one under carrier aggregation)
 * with every missing value filled from the SignalStrength row of the same RAT (decision 004).
 */
internal data class ServingColumn(
    val rat: Rat,
    val band: Int?,
    val pci: Int?,
    val arfcn: Int?,
    /** RSRP for LTE/NR, RSCP for WCDMA, RSSI for GSM. */
    val level: Shown<Int>,
    val rsrq: Shown<Int>,
    val sinr: Shown<Int>,
    val cellId: Long?,
    val tac: Int?,
    /** The CellInfo values are older than [STALE_AGE_MS]. */
    val stale: Boolean,
)

internal fun servingColumns(cells: List<CellSample>): List<ServingColumn> = Rat.entries.mapNotNull { rat ->
    val serving = cells.filter { it.rat == rat && it.servingInferred }
    val infos = serving.filter { it.source == CellSource.CELL_INFO }
    val info = infos.firstOrNull { it.connectionStatus == ConnectionStatus.PRIMARY_SERVING || it.isRegistered == true }
        ?: infos.firstOrNull()
    val ss = serving.firstOrNull { it.source == CellSource.SIGNAL_STRENGTH }
    if (info == null && ss == null) return@mapNotNull null
    fun <T> pick(get: (CellSample) -> T?): Shown<T> =
        info?.let(get)?.let { Shown(it) } ?: ss?.let(get)?.let { Shown(it, fromSignalStrength = true) } ?: Shown(null)
    ServingColumn(
        rat = rat,
        band = info?.band ?: ss?.band,
        pci = info?.pci ?: ss?.pci,
        arfcn = info?.arfcn ?: ss?.arfcn,
        level = pick { it.rsrpDbm ?: it.rscpDbm ?: it.rssiDbm },
        rsrq = pick { it.rsrqDb },
        sinr = pick { it.sinrDb },
        cellId = info?.cellId,
        tac = info?.tac,
        stale = info != null && (info.cellInfoAgeMs ?: 0) > STALE_AGE_MS,
    )
}

/** "LTE B3", "NR n41", "3G B1", "2G 900"; without a band only the RAT. */
internal fun ServingColumn.title(): String {
    val name = when (rat) {
        Rat.LTE -> "LTE"
        Rat.NR -> "NR"
        Rat.WCDMA -> "3G"
        Rat.GSM -> "2G"
    }
    val b = band ?: return name
    return when (rat) {
        Rat.LTE, Rat.WCDMA -> "$name B$b"
        Rat.NR -> "$name n$b"
        Rat.GSM -> "$name $b"
    }
}

/** Values older than this are grayed out. */
internal const val STALE_AGE_MS = 5_000L

/** Download and upload rate of the route's network between two counter readings, in bits per second. */
internal data class LinkRates(val downBps: Double, val upBps: Double)

/**
 * Cellular uses the mobile counters (they leave out a VPN's tun interface, so a running capture is not counted twice);
 * Wi-Fi and other networks use the total minus the mobile counters. Null without a previous reading, a route, or when a
 * counter went backwards.
 */
internal fun linkRates(prev: LinkCounters?, cur: LinkCounters, transport: NetworkRoute.Transport?): LinkRates? {
    prev ?: return null
    val dtNs = cur.elapsedRealtimeNs - prev.elapsedRealtimeNs
    if (dtNs <= 0) return null
    fun bytes(c: LinkCounters, rx: Boolean): Long? {
        val mobile = if (rx) c.mobileRxBytes else c.mobileTxBytes
        val total = if (rx) c.totalRxBytes else c.totalTxBytes
        return when (transport) {
            NetworkRoute.Transport.CELLULAR -> mobile
            NetworkRoute.Transport.WIFI, NetworkRoute.Transport.ETHERNET, NetworkRoute.Transport.OTHER ->
                total?.let { it - (mobile ?: 0) }
            NetworkRoute.Transport.NONE, null -> null
        }
    }
    fun rate(rx: Boolean): Double? {
        val delta = (bytes(cur, rx) ?: return null) - (bytes(prev, rx) ?: return null)
        return if (delta < 0) null else delta * 8 * 1e9 / dtNs
    }
    return LinkRates(rate(rx = true) ?: return null, rate(rx = false) ?: return null)
}

/**
 * Android updates the traffic counters only every few seconds (moto g45: flat for ~3 s, then a jump), so a rate
 * between two consecutive readings swings between 0 and many times the real rate. Live averages over at least
 * [RATE_WINDOW_NS]: the base is the newest reading at least that old, else the oldest one kept.
 */
internal fun rateBase(readings: List<LinkCounters>): LinkCounters? {
    val last = readings.lastOrNull() ?: return null
    return readings.lastOrNull { last.elapsedRealtimeNs - it.elapsedRealtimeNs >= RATE_WINDOW_NS }
        ?: readings.firstOrNull()?.takeIf { it !== last }
}

/** The readings [rateBase] can still need. */
internal fun keepRecent(readings: List<LinkCounters>): List<LinkCounters> {
    val last = readings.lastOrNull() ?: return readings
    val base = readings.indexOfLast { last.elapsedRealtimeNs - it.elapsedRealtimeNs >= RATE_WINDOW_NS }
    return if (base > 0) readings.drop(base) else readings
}

internal const val RATE_WINDOW_NS = 5_000_000_000L

/** "0 kbps", "85 kbps", "1.24 Mbps", "312 Mbps". */
internal fun formatRate(bps: Double): String = when {
    bps < 1e6 -> "%.0f kbps".format(Locale.ROOT, bps / 1e3)
    bps < 1e8 -> "%.2f Mbps".format(Locale.ROOT, bps / 1e6)
    else -> "%.0f Mbps".format(Locale.ROOT, bps / 1e6)
}

internal fun LocationStatus.label(): String = when (this) {
    LocationStatus.OK -> "Fixed"
    LocationStatus.WAITING -> "Searching"
    LocationStatus.NO_PERMISSION -> "No permission"
    LocationStatus.DISABLED -> "Location off"
    LocationStatus.UNAVAILABLE -> "Unavailable"
    LocationStatus.ERROR -> "Error"
}

/** "±10 m · 0.3 m/s · 30° · alt 28 m · 2 s ago"; missing parts are left out. */
internal fun fixLine(fix: LocationFix, ageMs: Long?): String = listOfNotNull(
    fix.accuracyM?.let { "±%.0f m".format(Locale.ROOT, it) },
    fix.speedMps?.let { "%.1f m/s".format(Locale.ROOT, it) },
    fix.bearingDeg?.let { "%.0f°".format(Locale.ROOT, it) },
    fix.altitudeM?.let { "alt %.0f m".format(Locale.ROOT, it) },
    ageMs?.let { "%d s ago".format(Locale.ROOT, it / 1000) },
).joinToString(" · ")

/** "↓ 42.1  ↑ 11.3 Mbps · 31 ms" from a finished test; phases that failed or did not run are left out. */
internal fun speedTestSummary(results: List<ThroughputResult>): String {
    fun mbps(type: ThroughputType) = results.firstOrNull { it.type == type && it.error == null }?.mbps
    val down = mbps(ThroughputType.DOWNLOAD)?.let { "↓ %.1f".format(Locale.ROOT, it) }
    val up = mbps(ThroughputType.UPLOAD)?.let { "↑ %.1f".format(Locale.ROOT, it) }
    val rates = listOfNotNull(down, up).joinToString("  ").takeIf { it.isNotEmpty() }?.let { "$it Mbps" }
    val ping = results.firstOrNull { it.type == ThroughputType.PING && it.error == null }?.rttAvgMs?.let { "%.0f ms".format(Locale.ROOT, it) }
    return listOfNotNull(rates, ping).joinToString(" · ").ifEmpty { "–" }
}

/** "Cloudflare SIN" / "Private server": which server a finished test used, with the location it reported. */
internal fun speedTestTarget(results: List<ThroughputResult>): String {
    val cloudflare = results.any { it.serverUrl.startsWith(SpeedTestConfig.CLOUDFLARE) }
    val location = results.firstNotNullOfOrNull { it.serverLocation }
    return listOfNotNull(if (cloudflare) "Cloudflare" else "Private server", location).joinToString(" ")
}

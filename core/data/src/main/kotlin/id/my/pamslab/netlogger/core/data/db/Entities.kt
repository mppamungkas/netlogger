package id.my.pamslab.netlogger.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema version 1 (CLAUDE.md data model plus approved additions). Enums are stored as their names (TEXT) so the
 * database stays readable without the app. Units: dBm / dB for signal, kHz for bandwidth, ms / ns for time,
 * µA / mV / °C for battery. Every radio column is nullable: null means "not reported".
 */

@Entity(tableName = "session", indices = [Index(value = ["uuid"], unique = true)])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val startedAt: Long,
    val startedElapsedNs: Long,
    val endedAt: Long? = null,
    val endedElapsedNs: Long? = null,
    /** EndReason name; null while the session is open. */
    val endReason: String? = null,
    val label: String? = null,
    /** Scenario name: DRIVE / WALK / STATIC / INDOOR. */
    val scenario: String? = null,
    val notes: String? = null,
    val intervalMs: Long,
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val basebandVersion: String?,
    val appVersion: String?,
    val schemaVersion: Int,
    val subscriptionId: Int? = null,
    val simSlot: Int? = null,
    val operatorName: String? = null,
    val mcc: String? = null,
    val mnc: String? = null,
    /** Phase 3. */
    val captureFile: String? = null,
)

@Entity(
    tableName = "measurement",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["sessionId", "seq"], unique = true)],
)
data class MeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val seq: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    val lat: Double?,
    val lon: Double?,
    val altitude: Double?,
    val speedMps: Float?,
    val bearing: Float?,
    val gpsAccuracy: Float?,
    val locationAgeMs: Long?,
    /** LocationStatus name. */
    val locationStatus: String,
    /** NetworkType name: LTE / NR_NSA / NR_SA / WCDMA / GSM / NONE. */
    val networkType: String,
    val overrideNetworkType: Int?,
    val dataNetworkType: Int?,
    val nsaLteServing: Boolean,
    val nsaNrCellInfo: Boolean,
    val nsaNrSignal: Boolean,
    val serviceState: Int?,
    val dataState: Int?,
    val isRoaming: Boolean?,
    val operatorNumeric: String?,
    /** CellInfoOrigin name: FRESH / CACHED / UNAVAILABLE. */
    val cellInfoOrigin: String,
    /** JSON array of ServiceState.getCellBandwidths() in kHz, e.g. "[20000,10000]". */
    val cellBandwidthsJson: String,
    val batteryLevel: Int?,
    val batteryCurrentUa: Int?,
    val batteryVoltageMv: Int?,
    val batteryTempC: Float?,
    val isCharging: Boolean?,
    val rxBytesTotal: Long?,
    val txBytesTotal: Long?,
    val rxBytesApp: Long?,
    val txBytesApp: Long?,
    val screenOn: Boolean?,
    val thermalStatus: Int?,
    /** Getter calls that threw while reading this tick (real parse failures). */
    val parseErrors: Int,
    /** Warnings raised by this tick, including flagged OEM corrections (before per-session deduplication). */
    val warningCount: Int,
)

@Entity(
    tableName = "cell",
    foreignKeys = [ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("measurementId")],
)
data class CellEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measurementId: Long,
    /** LTE / NR / WCDMA / GSM. */
    val rat: String,
    /** Derived serving flag (after flagged corrections); SIGNAL_STRENGTH rows are always serving. */
    val servingInferred: Boolean,
    /** The phone's own serving label, uncorrected; null for SIGNAL_STRENGTH rows. */
    val reportedServing: Boolean?,
    /** ServingSource name (NR CELL_INFO rows): REPORTED / NSA_INFERRED / SIGNAL_STRENGTH_MATCH / STRONGEST / AMBIGUOUS. */
    val servingSource: String?,
    val isRegistered: Boolean?,
    /** ConnectionStatus name. */
    val connectionStatus: String?,
    /** LTE ECI, NR NCI, WCDMA/GSM CID. */
    val ciOrNci: Long?,
    /** TAC (LTE/NR) or LAC (WCDMA/GSM). */
    val tac: Int?,
    /** PCI (LTE/NR), PSC (WCDMA), BSIC (GSM). */
    val pci: Int?,
    val arfcn: Int?,
    val band: Int?,
    /** API / MAPPED / MAPPED_MCC_FALLBACK. */
    val bandSource: String?,
    val bandwidthKhz: Int?,
    val mcc: String?,
    val mnc: String?,
    @ColumnInfo(name = "rsrp") val rsrpDbm: Int?,
    @ColumnInfo(name = "rsrq") val rsrqDb: Int?,
    @ColumnInfo(name = "sinr") val sinrDb: Int?,
    @ColumnInfo(name = "rssi") val rssiDbm: Int?,
    val cqi: Int?,
    @ColumnInfo(name = "ta") val timingAdvance: Int?,
    @ColumnInfo(name = "csiRsrp") val csiRsrpDbm: Int?,
    @ColumnInfo(name = "csiRsrq") val csiRsrqDb: Int?,
    @ColumnInfo(name = "csiSinr") val csiSinrDb: Int?,
    @ColumnInfo(name = "rscp") val rscpDbm: Int?,
    @ColumnInfo(name = "ecNo") val ecNoDb: Int?,
    /** CELL_INFO / SIGNAL_STRENGTH. */
    val source: String,
    val cellInfoAgeMs: Long?,
)

@Entity(
    tableName = "event",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val wallTimeMs: Long,
    val elapsedRealtimeNs: Long,
    /** EventType name. */
    val type: String,
    val fromValue: String? = null,
    val toValue: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** One speed test phase (milestone 8, schema v2). */
@Entity(
    tableName = "throughput_test",
    foreignKeys = [ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")],
)
data class ThroughputTestEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startedAtMs: Long,
    val startedElapsedNs: Long,
    /** ThroughputType name: PING / DOWNLOAD / UPLOAD. */
    val type: String,
    val serverUrl: String,
    val durationMs: Long?,
    val bytes: Long?,
    val mbps: Double?,
    val streams: Int?,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
    val jitterMs: Double?,
    val lossPct: Double?,
    val captureActive: Boolean,
    val error: String?,
    /** Schema v3: the server's location code if it reports one (Cloudflare's CF-RAY suffix, e.g. "SIN"). */
    val serverLocation: String? = null,
)

/**
 * One row per (session, code, rat, pci, field): the same anomaly is stored once with a counter instead of once per
 * tick. [rawValue] and [measurementId] / [elapsedRealtimeNs] are from the first occurrence.
 */
@Entity(
    tableName = "parse_warning",
    foreignKeys = [
        ForeignKey(SessionEntity::class, ["id"], ["sessionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(MeasurementEntity::class, ["id"], ["measurementId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId"), Index("measurementId")],
)
data class ParseWarningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val measurementId: Long,
    val elapsedRealtimeNs: Long,
    /** WarningCode name. */
    val code: String,
    val rat: String?,
    val pci: Int?,
    val field: String?,
    val rawValue: String?,
    val lastRawValue: String?,
    val occurrences: Long,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
)

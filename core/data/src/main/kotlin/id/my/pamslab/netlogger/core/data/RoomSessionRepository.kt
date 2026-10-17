package id.my.pamslab.netlogger.core.data

import android.util.Log
import id.my.pamslab.netlogger.core.data.db.EventEntity
import id.my.pamslab.netlogger.core.data.db.MeasurementDao
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import id.my.pamslab.netlogger.core.data.db.SessionDao
import id.my.pamslab.netlogger.core.data.db.SessionEntity
import id.my.pamslab.netlogger.core.data.db.SessionSummaryRow
import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.logging.UserMarker
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionStart
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomSessionRepository @Inject constructor(db: NetLoggerDatabase) : SessionRepository {

    private val sessions: SessionDao = db.sessionDao()
    private val measurements: MeasurementDao = db.measurementDao()

    /** Serializes start and recovery so recovery never closes the session being started. */
    private val lifecycle = Mutex()

    @Volatile private var activeSessionId: Long? = null

    override suspend fun startSession(start: SessionStart): SessionRecorder = lifecycle.withLock {
        closeAbandonedLocked()
        val id = sessions.insert(
            SessionEntity(
                uuid = start.uuid,
                startedAt = start.startedAtMs,
                startedElapsedNs = start.startedElapsedNs,
                scenario = start.scenario?.name,
                intervalMs = start.intervalMs,
                deviceManufacturer = start.device.manufacturer,
                deviceModel = start.device.model,
                androidVersion = start.device.androidVersion,
                basebandVersion = start.device.basebandVersion,
                appVersion = start.device.appVersion,
                schemaVersion = NetLoggerDatabase.VERSION,
            ),
        )
        activeSessionId = id
        Recorder(id)
    }

    override suspend fun closeAbandonedSessions(): Int = lifecycle.withLock { closeAbandonedLocked() }

    private suspend fun closeAbandonedLocked(): Int {
        val open = sessions.openSessions().filter { it.id != activeSessionId }
        open.forEach { s ->
            val last = sessions.lastMeasurement(s.id)
            sessions.close(
                id = s.id,
                endedAt = last?.wallTimeMs ?: s.startedAt,
                endedElapsedNs = last?.elapsedRealtimeNs ?: s.startedElapsedNs,
                endReason = EndReason.RECOVERED_AFTER_KILL.name,
            )
            Log.i(TAG, "closed abandoned session ${s.uuid} at ${last?.wallTimeMs ?: s.startedAt}")
        }
        return open.size
    }

    override fun observeSessions(): Flow<List<SessionSummary>> =
        sessions.observeSummaries().map { rows -> rows.map { it.toSummary() } }

    override fun observeSession(id: Long): Flow<SessionSummary?> =
        sessions.observeSummary(id).map { it?.toSummary() }

    override suspend fun updateDetails(id: Long, label: String?, notes: String?, scenario: Scenario?) =
        sessions.updateDetails(id, label?.takeIf { it.isNotBlank() }, notes?.takeIf { it.isNotBlank() }, scenario?.name)

    override suspend fun addEvent(sessionId: Long, event: SessionEvent) {
        measurements.insertEvent(event.toEntity(sessionId))
    }

    override suspend fun setCaptureFile(sessionId: Long, captureFile: String?) = sessions.setCaptureFile(sessionId, captureFile)

    override suspend fun delete(id: Long) {
        require(id != activeSessionId) { "cannot delete the session that is recording" }
        sessions.delete(id)
    }

    override suspend fun latestScenario(): Scenario? =
        sessions.latestScenario()?.let { name -> Scenario.entries.firstOrNull { it.name == name } }

    private inner class Recorder(override val sessionId: Long) : SessionRecorder {
        private val dedup = WarningDeduplicator(sessionId)
        private var networkKnown = false

        override suspend fun record(tick: Tick): Long {
            val started = System.nanoTime()
            val r = tick.radio
            val plan = dedup.plan(r.warnings, r.wallTimeMs, r.elapsedRealtimeNs)
            val (_, newIds) = measurements.insertTick(
                TickMapper.measurement(sessionId, tick),
                r.cells.map(TickMapper::cell),
                plan.newRows,
                plan.bumps,
            )
            dedup.committed(plan, newIds)
            val plmn = r.operatorNumeric
            if (!networkKnown && plmn != null) {
                networkKnown = true
                sessions.updateNetwork(
                    sessionId, r.subscriptionId, r.simSlot, r.operatorName,
                    plmn.take(3), plmn.drop(3).takeIf { it.isNotEmpty() },
                )
            }
            return (System.nanoTime() - started) / 1_000_000
        }

        override suspend fun addMarker(marker: UserMarker) {
            measurements.insertEvent(
                EventEntity(
                    sessionId = sessionId,
                    wallTimeMs = marker.wallTimeMs,
                    elapsedRealtimeNs = marker.elapsedRealtimeNs,
                    type = EventType.USER_MARKER.name,
                    lat = marker.latitude,
                    lon = marker.longitude,
                ),
            )
        }

        override suspend fun addEvent(event: SessionEvent) {
            measurements.insertEvent(event.toEntity(sessionId))
        }

        override suspend fun addThroughput(result: ThroughputResult) {
            measurements.insertThroughput(
                ThroughputTestEntity(
                    sessionId = sessionId,
                    startedAtMs = result.startedAtMs,
                    startedElapsedNs = result.startedElapsedNs,
                    type = result.type.name,
                    serverUrl = result.serverUrl,
                    durationMs = result.durationMs,
                    bytes = result.bytes,
                    mbps = result.mbps,
                    streams = result.streams,
                    rttMinMs = result.rttMinMs,
                    rttAvgMs = result.rttAvgMs,
                    rttMaxMs = result.rttMaxMs,
                    jitterMs = result.jitterMs,
                    lossPct = result.lossPct,
                    captureActive = result.captureActive,
                    error = result.error,
                    serverLocation = result.serverLocation,
                ),
            )
        }

        override suspend fun close(reason: EndReason, endedAtMs: Long, endedElapsedNs: Long) {
            lifecycle.withLock {
                sessions.close(sessionId, endedAtMs, endedElapsedNs, reason.name)
                if (activeSessionId == sessionId) activeSessionId = null
            }
        }
    }

    private fun SessionSummaryRow.toSummary() = SessionSummary(
        id = id,
        uuid = uuid,
        label = label,
        scenario = scenario?.let { name -> Scenario.entries.firstOrNull { it.name == name } },
        notes = notes,
        startedAtMs = startedAt,
        endedAtMs = endedAt,
        endReason = endReason?.let { name -> EndReason.entries.firstOrNull { it.name == name } },
        intervalMs = intervalMs,
        deviceModel = deviceModel,
        operatorName = operatorName,
        measurements = measurements,
        cells = cells,
        markers = markers,
        warnings = warnings,
    )

    private companion object {
        const val TAG = "NetLogger"
    }
}

private fun SessionEvent.toEntity(sessionId: Long) = EventEntity(
    sessionId = sessionId,
    wallTimeMs = wallTimeMs,
    elapsedRealtimeNs = elapsedRealtimeNs,
    type = type.name,
    fromValue = fromValue,
    toValue = toValue,
    lat = latitude,
    lon = longitude,
)

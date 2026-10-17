package id.my.pamslab.netlogger.feature.logger.service

import id.my.pamslab.netlogger.core.data.CaptureStateHolder
import id.my.pamslab.netlogger.core.data.SessionRecorder
import id.my.pamslab.netlogger.core.data.SessionRepository
import id.my.pamslab.netlogger.core.data.SpeedTestSettings
import id.my.pamslab.netlogger.core.device.DeviceContextSource
import id.my.pamslab.netlogger.core.location.LocationProvider
import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.capture.CaptureState
import id.my.pamslab.netlogger.core.model.device.BatterySample
import id.my.pamslab.netlogger.core.model.device.DeviceContext
import id.my.pamslab.netlogger.core.model.device.DeviceInfo
import id.my.pamslab.netlogger.core.model.device.TrafficSample
import id.my.pamslab.netlogger.core.model.location.LocationFix
import id.my.pamslab.netlogger.core.model.location.LocationState
import id.my.pamslab.netlogger.core.model.location.LocationStatus
import id.my.pamslab.netlogger.core.model.logging.SessionEvent
import id.my.pamslab.netlogger.core.model.logging.Tick
import id.my.pamslab.netlogger.core.model.logging.UserMarker
import id.my.pamslab.netlogger.core.model.radio.NetworkType
import id.my.pamslab.netlogger.core.model.radio.NsaInputs
import id.my.pamslab.netlogger.core.model.radio.RadioSnapshot
import id.my.pamslab.netlogger.core.model.raw.CellInfoOrigin
import id.my.pamslab.netlogger.core.model.session.EndReason
import id.my.pamslab.netlogger.core.model.session.EventType
import id.my.pamslab.netlogger.core.model.session.Scenario
import id.my.pamslab.netlogger.core.model.session.SessionStart
import id.my.pamslab.netlogger.core.model.session.SessionSummary
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestProgress
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestRunner
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestTarget
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoggingEngineTest {

    private class VirtualClock(private val scope: TestScope) : Clock {
        override fun wallTimeMs() = 1_700_000_000_000L + scope.currentTime
        override fun elapsedRealtimeNs() = (BOOT_MS + scope.currentTime) * 1_000_000
    }

    private class FakeLocation : LocationProvider {
        val flow = MutableStateFlow(LocationState(LocationStatus.WAITING))
        override val updates: Flow<LocationState> = flow
    }

    private object FakeDevice : DeviceContextSource {
        override fun snapshot() = DeviceContext(BatterySample(80, -300_000, 4_000, 30f, false), TrafficSample(1, 2, 3, 4), true, 0)
        override fun deviceInfo() = DeviceInfo("motorola", "moto g45 5G", "15", 35, "M6375", "0.1")
    }

    /** [hangAfterPing]: reports the ping, then waits in the download until cancelled (a test stopped half-way). */
    private class FakeRunner(private val hangAfterPing: Boolean = false) : SpeedTestRunner {
        var runs = 0
        var lastConfig: SpeedTestConfig? = null
        override suspend fun run(
            config: SpeedTestConfig,
            onResult: (ThroughputResult) -> Unit,
            onProgress: (SpeedTestProgress) -> Unit,
        ): List<ThroughputResult> {
            runs++
            lastConfig = config
            val results = ThroughputType.entries.map {
                ThroughputResult(it, 0, 0, config.pingUrl, 10_000, 1_000, 12.0, 4, null, null, null, null, null, serverLocation = "SIN")
            }
            onResult(results[0])
            onProgress(SpeedTestProgress(ThroughputType.DOWNLOAD, mbps = 12.0))
            if (hangAfterPing) awaitCancellation()
            results.drop(1).forEach(onResult)
            return results
        }
    }

    private class FakeSettings(config: SpeedTestConfig = SpeedTestConfig.libreSpeed("https://speed.example.org")) : SpeedTestSettings {
        override val config = MutableStateFlow(config)
        override fun save(pingUrl: String, downloadUrl: String, uploadUrl: String) = Unit
    }

    private class FakeRepository : SessionRepository {
        val starts = mutableListOf<SessionStart>()
        val recorded = mutableListOf<Tick>()
        val markers = mutableListOf<UserMarker>()
        val events = mutableListOf<SessionEvent>()
        val throughput = mutableListOf<ThroughputResult>()
        var closedWith: EndReason? = null
        var failWritesFrom: Long = Long.MAX_VALUE
        var abandonedClosed = 0

        override suspend fun startSession(start: SessionStart): SessionRecorder {
            starts += start
            return object : SessionRecorder {
                override val sessionId = 42L
                override suspend fun record(tick: Tick): Long {
                    if (tick.seq >= failWritesFrom) error("disk full")
                    recorded += tick
                    return 3
                }
                override suspend fun addMarker(marker: UserMarker) { markers += marker }
                override suspend fun addEvent(event: SessionEvent) { events += event }
                override suspend fun addThroughput(result: ThroughputResult) { throughput += result }
                override suspend fun close(reason: EndReason, endedAtMs: Long, endedElapsedNs: Long) { closedWith = reason }
            }
        }
        override suspend fun closeAbandonedSessions(): Int = (++abandonedClosed)
        override fun observeSessions() = emptyFlow<List<SessionSummary>>()
        override fun observeSession(id: Long) = emptyFlow<SessionSummary?>()
        override suspend fun updateDetails(id: Long, label: String?, notes: String?, scenario: Scenario?) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun latestScenario(): Scenario? = null
        override suspend fun addEvent(sessionId: Long, event: SessionEvent) { events += event }
        override suspend fun setCaptureFile(sessionId: Long, captureFile: String?) = Unit
    }

    /** One snapshot per interval, timestamped by the virtual clock; [skip] ticks are not emitted. */
    private fun radio(clock: Clock, skip: Set<Int> = emptySet()) = RadioSamples { intervalMs ->
        flow {
            var i = 0
            while (true) {
                if (i !in skip) emit(snapshot(clock.elapsedRealtimeNs()))
                i++
                delay(intervalMs)
            }
        }
    }

    private fun fix(atMs: Long, lat: Double = -0.95) =
        LocationFix(lat, 100.35, 10.0, 1f, 90f, 5f, (BOOT_MS + atMs) * 1_000_000, 0, "fused")

    @Test
    fun `counts ticks and missed ticks against the interval`() = runTest {
        val clock = VirtualClock(this)
        val engine = LoggingEngine(radio(clock, skip = setOf(3, 4)), FakeLocation(), FakeDevice, clock, FakeRepository(), FakeRunner(), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, Scenario.STATIC) }
        advanceTimeBy(9_500)
        runCurrent()

        val s = engine.state.value as LoggingState.Running
        assertEquals(8L, s.stats.recorded)
        assertEquals(10L, s.stats.expected)
        assertEquals(2L, s.stats.missed)
        assertEquals("seq is consecutive over recorded ticks", 7L, s.latest?.seq)
        assertEquals("moto g45 5G", s.deviceInfo.model)

        job.cancel()
        runCurrent()
        val idle = engine.state.value as LoggingState.Idle
        assertEquals(8L, idle.lastSession?.recorded)
    }

    @Test
    fun `tick uses the newest fix taken at or before the tick, and its age`() = runTest {
        val clock = VirtualClock(this)
        val location = FakeLocation()
        val engine = LoggingEngine(radio(clock), location, FakeDevice, clock, FakeRepository(), FakeRunner(), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, Scenario.STATIC) }
        runCurrent()
        assertNull("no fix yet", (engine.state.value as LoggingState.Running).latest?.location)

        advanceTimeBy(1_900)
        location.flow.value = LocationState(LocationStatus.OK, fix(1_800, lat = 1.0))
        runCurrent()
        // A fix stamped after the next tick's timestamp is already known when that tick is built (in the app: a
        // fix that arrives while the tick waits for requestCellInfoUpdate).
        location.flow.value = LocationState(LocationStatus.OK, fix(2_600, lat = 2.0))
        runCurrent()
        advanceTimeBy(200)
        runCurrent()

        val tick = (engine.state.value as LoggingState.Running).latest!!
        // Tick at 2000 ms: the fix taken at 1800 ms is used, not the one taken at 2600 ms.
        assertEquals(2L, tick.seq)
        assertEquals(1.0, tick.location!!.latitude, 0.0)
        assertEquals(200L, tick.locationAgeMs)
        assertEquals(LocationStatus.OK, tick.locationStatus)
        job.cancel()
    }

    @Test
    fun `markers carry the last position and are ignored when idle`() = runTest {
        val clock = VirtualClock(this)
        val location = FakeLocation()
        val engine = LoggingEngine(radio(clock), location, FakeDevice, clock, FakeRepository(), FakeRunner(), FakeSettings(), CaptureStateHolder())
        assertNull(engine.addMarker())

        val job = launch { engine.run(1_000, Scenario.STATIC) }
        location.flow.value = LocationState(LocationStatus.OK, fix(0, lat = -0.9))
        advanceTimeBy(1_500)
        val marker = engine.addMarker()
        assertEquals(-0.9, marker!!.latitude!!, 0.0)
        assertEquals(1, (engine.state.value as LoggingState.Running).markers.size)
        job.cancel()
    }

    @Test
    fun `session is started, every tick stored, markers persisted and closed with USER_STOP`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, FakeRunner(), FakeSettings(), CaptureStateHolder())
        assertEquals("recovery runs while idle", 1, engine.recoverAbandonedSessions())
        val job = launch { engine.run(1_000, Scenario.WALK) }
        advanceTimeBy(2_500)
        assertEquals(42L, (engine.state.value as LoggingState.Running).sessionId)
        assertEquals("recovery is skipped while recording", 0, engine.recoverAbandonedSessions())
        engine.addMarker()
        job.cancel()
        runCurrent()

        assertEquals(Scenario.WALK, repo.starts.single().scenario)
        assertEquals(listOf(0L, 1L, 2L), repo.recorded.map { it.seq })
        assertEquals(1, repo.markers.size)
        assertEquals(EndReason.USER_STOP, repo.closedWith)
        assertEquals(1, repo.abandonedClosed)
    }

    @Test
    fun `failed writes are counted and logging continues`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository().apply { failWritesFrom = 2 }
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, FakeRunner(), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(4_500)
        val s = engine.state.value as LoggingState.Running
        assertEquals(5L, s.stats.recorded)
        assertEquals(3L, s.writeErrors)
        job.cancel()
    }

    @Test
    fun `a data SIM switch and back writes one event each, at the tick it was seen`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val dataSubs = listOf(1, 1, 2, 2, 1, 1)
        val radio = RadioSamples { intervalMs ->
            flow {
                dataSubs.forEach { sub ->
                    emit(snapshot(clock.elapsedRealtimeNs()).copy(dataSubscriptionId = sub))
                    delay(intervalMs)
                }
            }
        }
        val engine = LoggingEngine(radio, FakeLocation(), FakeDevice, clock, repo, FakeRunner(), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(6_500)
        job.cancel()
        runCurrent()

        assertEquals(listOf("1" to "2", "2" to "1"), repo.events.map { it.fromValue to it.toValue })
        assertTrue(repo.events.all { it.type == EventType.DATA_SIM_CHANGED })
        assertEquals(listOf(2L, 4L), repo.events.map { e -> repo.recorded.single { it.radio.elapsedRealtimeNs == e.elapsedRealtimeNs }.seq })
    }

    @Test
    fun `speed test - stored in the running session, refused without a server or a session`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val runner = FakeRunner()
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, runner, FakeSettings(), CaptureStateHolder())
        assertFalse("not logging", engine.runSpeedTest())

        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(1_500)
        assertTrue(engine.runSpeedTest())
        assertEquals(listOf(ThroughputType.PING, ThroughputType.DOWNLOAD, ThroughputType.UPLOAD), repo.throughput.map { it.type })
        val done = (engine.state.value as LoggingState.Running).speedTest as SpeedTestState.Done
        assertEquals(3, done.results.size)
        job.cancel()
        runCurrent()

        val noServer = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, FakeRepository(), runner, FakeSettings(SpeedTestConfig("", "", "")), CaptureStateHolder())
        val job2 = launch { noServer.run(1_000, null) }
        advanceTimeBy(1_500)
        assertFalse(noServer.runSpeedTest())
        assertEquals(SpeedTestState.Refused(LoggingEngine.NO_PRIVATE_SERVER), (noServer.state.value as LoggingState.Running).speedTest)
        assertEquals("the runner was not called again", 1, runner.runs)
        job2.cancel()
    }

    @Test
    fun `public target - Cloudflare even without a server of the user's, the default stays the user's server`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val runner = FakeRunner()
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, runner, FakeSettings(SpeedTestConfig("", "", "")), CaptureStateHolder())
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(1_500)
        assertFalse("no server of the user's: the default target refuses", engine.runSpeedTest())
        assertTrue(engine.runSpeedTest(SpeedTestTarget.PUBLIC))
        assertEquals(SpeedTestConfig.cloudflare(), runner.lastConfig)
        assertTrue(repo.throughput.all { it.serverUrl.startsWith(SpeedTestConfig.CLOUDFLARE) })
        job.cancel()
    }

    @Test
    fun `Stop keeps the finished phases and stores the interrupted one as stopped`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, FakeRunner(hangAfterPing = true), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(1_500)
        val test = launch { engine.runSpeedTest(SpeedTestTarget.PUBLIC) }
        runCurrent()
        engine.stopSpeedTest()
        runCurrent()

        assertTrue(test.isCancelled)
        assertEquals(listOf(ThroughputType.PING, ThroughputType.DOWNLOAD), repo.throughput.map { it.type })
        assertEquals(listOf(null, LoggingEngine.STOPPED_BY_USER), repo.throughput.map { it.error })
        assertEquals("the stopped row keeps the server's location", listOf("SIN", "SIN"), repo.throughput.map { it.serverLocation })
        val done = (engine.state.value as LoggingState.Running).speedTest as SpeedTestState.Done
        assertEquals(2, done.results.size)
        job.cancel()
    }

    @Test
    fun `a session ending during a test keeps the finished phases as session stopped`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, FakeRunner(hangAfterPing = true), FakeSettings(), CaptureStateHolder())
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(1_500)
        val test = launch { engine.runSpeedTest() }
        runCurrent()
        test.cancel() // what the service does when the session stops
        runCurrent()

        assertEquals(listOf(null, LoggingEngine.STOPPED_WITH_SESSION), repo.throughput.map { it.error })
        job.cancel()
    }

    @Test
    fun `speed test during a traffic capture is flagged captureActive`() = runTest {
        val clock = VirtualClock(this)
        val repo = FakeRepository()
        val capture = CaptureStateHolder().apply { state.value = CaptureState(running = true, sessionId = 42) }
        val engine = LoggingEngine(radio(clock), FakeLocation(), FakeDevice, clock, repo, FakeRunner(), FakeSettings(), capture)
        val job = launch { engine.run(1_000, null) }
        advanceTimeBy(1_500)
        assertTrue(engine.runSpeedTest())
        assertTrue(repo.throughput.all { it.captureActive })
        job.cancel()
    }

    @Test
    fun `buildTick falls back to the oldest fix when all are newer than the tick`() {
        val tick = LoggingEngine.buildTick(0, snapshot(BOOT_MS * 1_000_000), LocationState(LocationStatus.OK), listOf(fix(300), fix(900)), FakeDevice.snapshot())
        assertEquals(-300L, tick.locationAgeMs)
        assertTrue(tick.location!!.elapsedRealtimeNs == fix(300).elapsedRealtimeNs)
    }

    private companion object {
        const val BOOT_MS = 5_000_000L

        fun snapshot(elapsedNs: Long) = RadioSnapshot(
            wallTimeMs = 0, elapsedRealtimeNs = elapsedNs, subscriptionId = 1, simSlot = 0, operatorName = "3",
            operatorNumeric = "51089", networkType = NetworkType.LTE, nsaInputs = NsaInputs(true, false, false, 0, 13),
            serviceState = 0, dataState = 2, isRoaming = false, cellBandwidthsKhz = emptyList(),
            cellInfoOrigin = CellInfoOrigin.FRESH, cells = emptyList(), warnings = emptyList(), listenerError = null,
        )
    }
}

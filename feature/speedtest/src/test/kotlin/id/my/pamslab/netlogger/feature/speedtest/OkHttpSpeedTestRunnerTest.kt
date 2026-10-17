package id.my.pamslab.netlogger.feature.speedtest

import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.cloudflareLocation
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestProgress
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Against a local JDK HTTP server: no network, no extra test dependency. */
class OkHttpSpeedTestRunnerTest {

    private lateinit var server: TinyHttpServer
    private val requests = AtomicInteger()
    private val pings = AtomicInteger()
    private val uploaded = AtomicLong()
    private val portal = AtomicInteger()
    private val base get() = "http://127.0.0.1:${server.port}"

    private val clock = object : Clock {
        override fun wallTimeMs() = System.currentTimeMillis()
        override fun elapsedRealtimeNs() = System.nanoTime()
    }

    @Before
    fun start() {
        server = TinyHttpServer { path, body, _ ->
            when (path) {
                "/ping" -> { requests.incrementAndGet(); pings.incrementAndGet(); 200 to 0L }
                "/down" -> { requests.incrementAndGet(); 200 to DOWNLOAD_BYTES }
                "/up" -> {
                    requests.incrementAndGet()
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        uploaded.addAndGet(n.toLong())
                    }
                    200 to 0L
                }
                "/redirect" -> { body.copyTo(java.io.OutputStream.nullOutputStream()); 302 to 0L }
                "/portal" -> { requests.incrementAndGet(); portal.incrementAndGet(); 200 to 100_000L }
                else -> { body.copyTo(java.io.OutputStream.nullOutputStream()); 404 to 0L }
            }
        }
    }

    @After
    fun stop() = server.stop()

    private fun config(ping: String = "$base/ping", down: String = "$base/down", up: String = "$base/up") = SpeedTestConfig(
        pingUrl = ping, downloadUrl = down, uploadUrl = up, pingCount = 5, durationMs = 1_000, streams = 2,
    )

    @Test
    fun `ping, download and upload against a working server`() = runBlocking {
        val progress = mutableListOf<SpeedTestProgress>()
        // No byte cap: on localhost 200 MB take well under a second.
        val uncapped = config().copy(maxDownloadBytes = Long.MAX_VALUE, maxUploadBytes = Long.MAX_VALUE)
        val results = OkHttpSpeedTestRunner(clock) { null }.run(uncapped) { synchronized(progress) { progress += it } }

        assertEquals(listOf(ThroughputType.PING, ThroughputType.DOWNLOAD, ThroughputType.UPLOAD), results.map { it.type })
        results.forEach { assertNull("${it.type}: ${it.error}", it.error) }

        val ping = results[0]
        assertEquals(0.0, ping.lossPct!!, 0.0)
        assertTrue(ping.rttMinMs!! <= ping.rttAvgMs!! && ping.rttAvgMs!! <= ping.rttMaxMs!!)
        assertNotNull(ping.jitterMs)
        assertEquals("warm-up request plus 5 counted", 6, pings.get())

        val down = results[1]
        assertTrue(down.bytes!! > 0)
        assertTrue(down.mbps!! > 0)
        assertEquals(2, down.streams)
        assertTrue("phase ends near its duration: ${down.durationMs}", down.durationMs!! in 900..1_100)

        val up = results[2]
        assertTrue(up.bytes!! > 0)
        assertTrue(up.mbps!! > 0)
        assertTrue(uploaded.get() > 0)
        assertTrue(progress.any { it.phase == ThroughputType.DOWNLOAD && it.mbps != null })
    }

    @Test
    fun `each phase is reported as it ends, with Cloudflare's location from CF-RAY`() = runBlocking {
        server.cfRay = "a4bb84683e5ffd90-SIN"
        val reported = mutableListOf<ThroughputResult>()
        val results = OkHttpSpeedTestRunner(clock) { null }.run(config(), onResult = { reported += it }) {}

        assertEquals("reported one by one, in order", results, reported)
        assertEquals(listOf("SIN", "SIN", "SIN"), results.map { it.serverLocation })
        server.cfRay = null
        assertNull("a server without CF-RAY has no location", OkHttpSpeedTestRunner(clock) { null }.run(config()) {}.first().serverLocation)
    }

    @Test
    fun `stopping during a slow download ends at once, not at the end of the phase`() = runBlocking {
        server.blockDelayMs = 200 // ~320 kB/s per stream: each request runs far longer than the stop takes
        val slow = config().copy(durationMs = 10_000, pingCount = 1)
        var downloading = false
        val test = launch(Dispatchers.Default) {
            OkHttpSpeedTestRunner(clock) { null }.run(slow) { if (it.phase == ThroughputType.DOWNLOAD) downloading = true }
        }
        withTimeout(5_000) { while (!downloading) delay(20) }
        val stopped = System.nanoTime()
        test.cancelAndJoin()
        val tookMs = (System.nanoTime() - stopped) / 1_000_000
        assertTrue("stopped in $tookMs ms", tookMs < 1_500)
        server.blockDelayMs = 0
    }

    @Test
    fun `location code only from a CF-RAY suffix`() {
        assertEquals("CGK", cloudflareLocation("8d1f0c2b3a4d5e6f-CGK"))
        assertNull(cloudflareLocation(null))
        assertNull(cloudflareLocation("8d1f0c2b3a4d5e6f"))
        assertNull(cloudflareLocation("8d1f-sin"))
        assertNull(cloudflareLocation("8d1f-SING"))
    }

    @Test
    fun `download stops at the byte cap`() = runBlocking {
        val capped = config().copy(maxDownloadBytes = 2L * 1024 * 1024, durationMs = 10_000)
        val down = OkHttpSpeedTestRunner(clock) { null }.run(capped) {}.single { it.type == ThroughputType.DOWNLOAD }
        assertTrue(down.bytes!! >= 2L * 1024 * 1024)
        assertTrue("stopped early: ${down.durationMs} ms", down.durationMs!! < 10_000)
    }

    @Test
    fun `not on mobile data - every phase fails without a request`() = runBlocking {
        val results = OkHttpSpeedTestRunner(clock) { CellularNetworkGuard.NOT_CELLULAR }.run(config()) {}
        assertEquals(3, results.size)
        results.forEach { assertEquals(CellularNetworkGuard.NOT_CELLULAR, it.error) }
        assertEquals(0, requests.get())
    }

    @Test
    fun `server errors and bad URLs are recorded, not thrown`() = runBlocking {
        val results = OkHttpSpeedTestRunner(clock) { null }.run(config(ping = "$base/missing", down = "not a url", up = "$base/missing")) {}
        assertEquals("connect: HTTP 404", results[0].error)
        assertEquals("invalid URL", results[1].error)
        assertEquals("HTTP 404", results[2].error)
        assertNull(results[2].mbps)
    }

    @Test
    fun `a redirect (operator captive portal) is an error, never followed or measured`() = runBlocking {
        val r = "$base/redirect"
        val results = OkHttpSpeedTestRunner(clock) { null }.run(config(ping = r, down = r, up = r)) {}
        assertEquals("connect: HTTP 302 (redirect to 127.0.0.1)", results[0].error)
        assertEquals("HTTP 302 (redirect to 127.0.0.1)", results[1].error)
        assertEquals("HTTP 302 (redirect to 127.0.0.1)", results[2].error)
        results.forEach { assertNull(it.type.name, it.mbps) }
        assertEquals("the portal was never requested", 0, portal.get())
    }

    @Test
    fun `unreachable server`() = runBlocking {
        val dead = "http://127.0.0.1:1"
        val results = OkHttpSpeedTestRunner(clock) { null }.run(config(ping = "$dead/p", down = "$dead/d", up = "$dead/u")) {}
        results.forEach { assertNotNull(it.type.name, it.error) }
    }

    private companion object {
        const val DOWNLOAD_BYTES = 8L * 1024 * 1024
    }
}

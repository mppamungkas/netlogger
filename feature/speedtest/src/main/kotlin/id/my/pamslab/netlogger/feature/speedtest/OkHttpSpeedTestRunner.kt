package id.my.pamslab.netlogger.feature.speedtest

import id.my.pamslab.netlogger.core.model.Clock
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestConfig
import id.my.pamslab.netlogger.core.model.speedtest.cloudflareLocation
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestProgress
import id.my.pamslab.netlogger.core.model.speedtest.SpeedTestRunner
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputResult
import id.my.pamslab.netlogger.core.model.speedtest.ThroughputType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlin.math.abs
import kotlin.random.Random

/**
 * Ping (HTTP round trips), then download and upload with [SpeedTestConfig.streams] parallel connections for
 * [SpeedTestConfig.durationMs] each. Every request carries a unique query parameter so no cache answers it.
 *
 * - Ping RTT: request sent to response body read, on a kept-alive connection; the first request opens the
 *   connection and is not counted. It includes the server's response time, unlike ICMP.
 * - Download: bytes read from the response bodies until the phase ends.
 * - Upload: bytes handed to the socket until the phase ends (the last socket buffer may not have arrived yet).
 * - Mbps = bytes × 8 / phase time / 10^6, over the whole phase (TCP ramp-up included, no grace period).
 * - Redirects are never followed: a 3xx (e.g. an operator's captive portal when the data quota is used up) is an
 *   error naming the redirect host, not a measurement of the portal.
 */
class OkHttpSpeedTestRunner @Inject constructor(
    private val clock: Clock,
    private val network: NetworkGuard,
) : SpeedTestRunner {

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .cache(null)
        .build()

    override suspend fun run(
        config: SpeedTestConfig,
        onResult: (ThroughputResult) -> Unit,
        onProgress: (SpeedTestProgress) -> Unit,
    ): List<ThroughputResult> {
        network.problem()?.let { problem ->
            return ThroughputType.entries.map { failed(it, url(config, it), problem).also(onResult) }
        }
        return withContext(Dispatchers.IO) {
            listOf(
                ping(config, onProgress).also(onResult),
                download(config, onProgress).also(onResult),
                upload(config, onProgress).also(onResult),
            )
        }
    }

    private fun url(config: SpeedTestConfig, type: ThroughputType) = when (type) {
        ThroughputType.PING -> config.pingUrl
        ThroughputType.DOWNLOAD -> config.downloadUrl
        ThroughputType.UPLOAD -> config.uploadUrl
    }

    private fun failed(type: ThroughputType, url: String, error: String) = ThroughputResult(
        type, clock.wallTimeMs(), clock.elapsedRealtimeNs(), url, null, null, null, null, null, null, null, null, null,
        error = error,
    )

    /** [base] with a unique `nl` query parameter, or null when [base] is not an http(s) URL. */
    private fun uncached(base: String) = base.toHttpUrlOrNull()?.newBuilder()
        ?.addQueryParameter("nl", "${System.nanoTime()}-${Random.nextInt(1_000_000)}")
        ?.build()

    private suspend fun ping(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit): ThroughputResult = coroutineScope {
        val startMs = clock.wallTimeMs()
        val startNs = clock.elapsedRealtimeNs()
        val t0 = System.nanoTime()
        val rtts = mutableListOf<Double>()
        var failures = 0
        var lastError: String? = null
        var location: String? = null
        repeat(config.pingCount + 1) { i ->
            ensureActive()
            val url = uncached(config.pingUrl) ?: return@coroutineScope failed(ThroughputType.PING, config.pingUrl, BAD_URL)
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            call.timeout().timeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val sent = System.nanoTime()
            try {
                call.execute().use { response ->
                    location = location ?: response.location()
                    response.body?.bytes()
                    if (!response.isSuccessful) throw HttpStatusException(response)
                }
                val rtt = (System.nanoTime() - sent) / 1e6
                if (i > 0) {
                    rtts += rtt
                    onProgress(SpeedTestProgress(ThroughputType.PING, rttMs = rtt))
                }
            } catch (e: IOException) {
                lastError = e.message ?: e.javaClass.simpleName
                if (i > 0) failures++ else return@coroutineScope failed(ThroughputType.PING, config.pingUrl, "connect: $lastError")
            }
            if (i < config.pingCount) delay(PING_GAP_MS)
        }
        val jitter = rtts.zipWithNext { a, b -> abs(b - a) }.takeIf { it.isNotEmpty() }?.average()
        ThroughputResult(
            ThroughputType.PING, startMs, startNs, config.pingUrl,
            durationMs = (System.nanoTime() - t0) / 1_000_000, bytes = null, mbps = null, streams = 1,
            rttMinMs = rtts.minOrNull(), rttAvgMs = rtts.takeIf { it.isNotEmpty() }?.average(), rttMaxMs = rtts.maxOrNull(),
            jitterMs = jitter, lossPct = failures * 100.0 / config.pingCount,
            error = if (rtts.isEmpty()) lastError ?: "no reply" else null,
            serverLocation = location,
        )
    }

    private suspend fun download(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit) =
        transfer(ThroughputType.DOWNLOAD, config.downloadUrl, config, config.maxDownloadBytes, onProgress) { url, bytes, limit, location ->
            val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build())
            call to {
                call.execute().use { response ->
                    response.location()?.let { location.compareAndSet(null, it) }
                    if (!response.isSuccessful) throw HttpStatusException(response)
                    val source = response.body?.source() ?: throw IOException("no body")
                    val buffer = ByteArray(CHUNK_BYTES)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (bytes.addAndGet(n.toLong()) >= limit) break
                    }
                }
            }
        }

    private suspend fun upload(config: SpeedTestConfig, onProgress: (SpeedTestProgress) -> Unit) =
        transfer(ThroughputType.UPLOAD, config.uploadUrl, config, config.maxUploadBytes, onProgress) { url, bytes, limit, location ->
            val body = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = UPLOAD_REQUEST_BYTES
                override fun writeTo(sink: BufferedSink) {
                    var left = UPLOAD_REQUEST_BYTES
                    while (left > 0) {
                        val n = minOf(left, RANDOM_BLOCK.size.toLong()).toInt()
                        sink.write(RANDOM_BLOCK, 0, n)
                        sink.flush()
                        left -= n
                        if (bytes.addAndGet(n.toLong()) >= limit) throw IOException(LIMIT_REACHED)
                    }
                }
            }
            val call = client.newCall(Request.Builder().url(url).post(body).build())
            call to {
                call.execute().use { response ->
                    response.location()?.let { location.compareAndSet(null, it) }
                    if (!response.isSuccessful) throw HttpStatusException(response)
                }
            }
        }

    /**
     * Runs [streams][SpeedTestConfig.streams] workers that repeat [request] until the phase time is up or [limit] bytes
     * are counted. All calls still open at the end are cancelled.
     */
    private suspend fun transfer(
        type: ThroughputType,
        base: String,
        config: SpeedTestConfig,
        limit: Long,
        onProgress: (SpeedTestProgress) -> Unit,
        request: (url: okhttp3.HttpUrl, bytes: AtomicLong, limit: Long, location: AtomicReference<String?>) -> Pair<Call, () -> Unit>,
    ): ThroughputResult {
        if (uncached(base) == null) return failed(type, base, BAD_URL)
        val startMs = clock.wallTimeMs()
        val startNs = clock.elapsedRealtimeNs()
        val bytes = AtomicLong()
        val firstError = AtomicReference<String?>(null)
        val rejected = AtomicReference<String?>(null)
        val location = AtomicReference<String?>(null)
        val open = Collections.synchronizedSet(mutableSetOf<Call>())
        val t0 = System.nanoTime()
        val deadline = t0 + config.durationMs * 1_000_000
        fun done() = System.nanoTime() >= deadline || bytes.get() >= limit
        coroutineScope {
            repeat(config.streams) {
                launch(Dispatchers.IO) {
                    while (isActive && !done()) {
                        val url = uncached(base) ?: break
                        val (call, execute) = request(url, bytes, limit, location)
                        call.timeout().timeout((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                        open += call
                        try {
                            execute()
                        } catch (e: HttpStatusException) {
                            // The server refused the request: the counted bytes do not show the link speed.
                            rejected.compareAndSet(null, e.message)
                            break
                        } catch (e: IOException) {
                            if (!done() && e.message != LIMIT_REACHED) {
                                firstError.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                                delay(RETRY_DELAY_MS)
                            }
                        } finally {
                            open -= call
                        }
                    }
                }
            }
            launch {
                try {
                    while (!done()) {
                        delay(PROGRESS_MS)
                        onProgress(SpeedTestProgress(type, mbps = mbps(bytes.get(), System.nanoTime() - t0)))
                    }
                } finally {
                    // Also when the test is stopped: the workers block in socket reads and writes, which a coroutine
                    // cancel does not interrupt; cancelling the calls does, at once.
                    synchronized(open) { open.forEach(Call::cancel) }
                }
            }
        }
        val elapsedNs = (minOf(System.nanoTime(), deadline) - t0).coerceAtLeast(1)
        val total = bytes.get()
        val refusal = rejected.get()
        return ThroughputResult(
            type, startMs, startNs, base,
            durationMs = elapsedNs / 1_000_000, bytes = total,
            mbps = if (total > 0 && refusal == null) mbps(total, elapsedNs) else null,
            streams = config.streams, rttMinMs = null, rttAvgMs = null, rttMaxMs = null, jitterMs = null, lossPct = null,
            error = refusal ?: if (total == 0L) firstError.get() ?: "no data" else null,
            serverLocation = location.get(),
        )
    }

    /** Where the server answered from: Cloudflare's CF-RAY location code; null for servers without one. */
    private fun Response.location(): String? = cloudflareLocation(header("CF-RAY"))

    /** A non-2xx answer: the server refused the request, or something on the way redirected it. */
    private class HttpStatusException(response: Response) : IOException(
        "HTTP ${response.code}" + (response.header("Location")?.toHttpUrlOrNull()?.host?.let { " (redirect to $it)" } ?: ""),
    )

    private fun mbps(bytes: Long, elapsedNs: Long) = bytes * 8 / (elapsedNs / 1e9) / 1e6

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val READ_TIMEOUT_MS = 5_000L
        const val PING_TIMEOUT_MS = 3_000L
        const val PING_GAP_MS = 100L
        const val PROGRESS_MS = 500L
        const val RETRY_DELAY_MS = 200L
        const val CHUNK_BYTES = 64 * 1024
        const val UPLOAD_REQUEST_BYTES = 4L * 1024 * 1024
        const val BAD_URL = "invalid URL"
        const val LIMIT_REACHED = "byte limit reached"
        val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Incompressible upload data. */
        val RANDOM_BLOCK = Random(42).nextBytes(64 * 1024)
    }
}

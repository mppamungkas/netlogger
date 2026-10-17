package id.my.pamslab.netlogger.core.model.speedtest

enum class ThroughputType { PING, DOWNLOAD, UPLOAD }

/**
 * Which server a test runs against. [PRIMARY]: the user's own server from Settings (the default, also for the
 * notification action). [PUBLIC]: [SpeedTestConfig.cloudflare], a secondary target the user opts in to.
 */
enum class SpeedTestTarget { PRIMARY, PUBLIC }

/**
 * Server endpoints. Works with a LibreSpeed backend ([libreSpeed]) or any web server: [pingUrl] answers small GETs,
 * [downloadUrl] serves a large body, [uploadUrl] accepts a POST and discards it.
 */
data class SpeedTestConfig(
    val pingUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    /** HTTP requests for the ping; the first one only opens the connection and is not counted. */
    val pingCount: Int = 10,
    /** Duration of each throughput phase. */
    val durationMs: Long = 10_000,
    /** Parallel connections for download and upload. */
    val streams: Int = 4,
    /** Stops a download early after this many bytes (all streams), to cap mobile data. */
    val maxDownloadBytes: Long = 200L * 1024 * 1024,
    /** Stops an upload early after this many bytes (all streams). */
    val maxUploadBytes: Long = 50L * 1024 * 1024,
) {
    val isComplete: Boolean get() = listOf(pingUrl, downloadUrl, uploadUrl).all { it.startsWith("http://") || it.startsWith("https://") }

    companion object {
        /** LibreSpeed backend endpoints under [baseUrl] (e.g. https://speed.example.org/). */
        fun libreSpeed(baseUrl: String): SpeedTestConfig {
            val base = baseUrl.trim().trimEnd('/')
            return SpeedTestConfig(
                pingUrl = "$base/backend/empty.php",
                downloadUrl = "$base/backend/garbage.php?ckSize=100",
                uploadUrl = "$base/backend/empty.php",
            )
        }

        /**
         * Cloudflare's public speed test endpoints, the secondary target ([SpeedTestTarget.PUBLIC]). Not our server:
         * a far-away or busy edge lowers the result, requests above ~100 MB answer HTTP 403 (hence 25 MB per
         * request, repeated by each stream), and many large downloads in a row answer HTTP 429 (rate limit).
         */
        fun cloudflare(): SpeedTestConfig = SpeedTestConfig(
            pingUrl = "$CLOUDFLARE/__down?bytes=0",
            downloadUrl = "$CLOUDFLARE/__down?bytes=25000000",
            uploadUrl = "$CLOUDFLARE/__up",
        )

        const val CLOUDFLARE = "https://speed.cloudflare.com"

    }
}

/** One phase of a speed test; a failed phase has [error] set and keeps what was measured until then. */
data class ThroughputResult(
    val type: ThroughputType,
    val startedAtMs: Long,
    val startedElapsedNs: Long,
    val serverUrl: String,
    val durationMs: Long?,
    val bytes: Long?,
    val mbps: Double?,
    val streams: Int?,
    val rttMinMs: Double?,
    val rttAvgMs: Double?,
    val rttMaxMs: Double?,
    /** Mean absolute difference between consecutive RTTs. */
    val jitterMs: Double?,
    val lossPct: Double?,
    /** Phase 3: traffic capture was running (adds overhead). */
    val captureActive: Boolean = false,
    val error: String? = null,
    /** Where the server answered from, if it says: Cloudflare's location code from CF-RAY (e.g. "SIN"); null otherwise. */
    val serverLocation: String? = null,
)

/** Progress for the UI. */
data class SpeedTestProgress(val phase: ThroughputType, val mbps: Double? = null, val rttMs: Double? = null)

/** Runs ping, download and upload in that order against [SpeedTestConfig]; implemented in feature:speedtest. */
interface SpeedTestRunner {
    /**
     * [onResult] gets each phase as soon as it is done, so a test stopped half-way keeps the finished phases. It comes
     * before [onProgress], so a trailing lambda stays the progress callback.
     */
    suspend fun run(
        config: SpeedTestConfig,
        onResult: (ThroughputResult) -> Unit = {},
        onProgress: (SpeedTestProgress) -> Unit,
    ): List<ThroughputResult>
}

/**
 * Cloudflare's location code from a CF-RAY header ("a4bb84683e5ffd90-SIN" -> "SIN": the edge's IATA airport code);
 * null for anything else.
 */
fun cloudflareLocation(cfRay: String?): String? =
    cfRay?.substringAfterLast('-', "")?.trim()?.takeIf { it.length == 3 && it.all(Char::isUpperCase) }

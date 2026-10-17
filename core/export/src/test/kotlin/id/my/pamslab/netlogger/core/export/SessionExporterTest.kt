package id.my.pamslab.netlogger.core.export

import id.my.pamslab.netlogger.core.data.CaptureFileStore
import id.my.pamslab.netlogger.core.data.db.ThroughputTestEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.util.zip.ZipInputStream

class SessionExporterTest {

    private val zone = ZoneId.of("Asia/Jakarta")

    private suspend fun exportZip(dao: FakeExportDao, captures: CaptureFileStore? = null): Pair<ExportResult, Map<String, String>> {
        val out = ByteArrayOutputStream()
        val result = SessionExporter(dao, captures).export(1, out, zone, nowMs = START_MS + 3_600_000)
        val entries = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        return result to entries
    }

    private fun lines(csv: String) = csv.split("\r\n").dropLast(1)

    @Test
    fun `bundle has every file, one header plus one row per record, across page boundaries`() = runTest {
        val ticks = (0L until 1_201).map { measurement(it, lat = if (it == 5L) null else -0.9, lon = if (it == 5L) null else 100.4) }
        val dao = FakeExportDao(
            measurements = ticks,
            events = listOf(marker(3)),
            warnings = listOf(warning("NR_SERVING_STRONGEST", "2"), warning("OUT_OF_RANGE", "a,\"b\"")),
            throughput = listOf(
                ThroughputTestEntity(
                    sessionId = 1, startedAtMs = START_MS + 5_000, startedElapsedNs = 5, type = "DOWNLOAD",
                    serverUrl = "https://speed.example.org/backend/garbage.php?ckSize=100", durationMs = 10_000,
                    bytes = 52_428_800, mbps = 41.94304, streams = 4, rttMinMs = null, rttAvgMs = null, rttMaxMs = null,
                    jitterMs = null, lossPct = null, captureActive = false, error = null,
                ),
            ),
        )
        val (result, files) = exportZip(dao)

        assertEquals(SessionExporter.FILES.toSet(), files.keys)
        assertEquals(1_202, lines(files.getValue("measurements.csv")).size)
        assertEquals(1_202, lines(files.getValue("flat.csv")).size)
        assertEquals(1 + 1_201 * a56Cells().size, lines(files.getValue("cells.csv")).size)
        assertEquals(2, lines(files.getValue("events.csv")).size)
        assertEquals(3, lines(files.getValue("warnings.csv")).size)
        assertTrue(files.getValue("warnings.csv").contains(",\"a,\"\"b\"\"\","))
        assertEquals(SessionJson.Counts(1_201, 1_201L * a56Cells().size, 1, 2, 1_200, 1), result.counts)
        val throughput = lines(files.getValue("throughput.csv"))
        assertEquals(2, throughput.size)
        assertTrue(throughput[1].contains(",DOWNLOAD,https://speed.example.org/backend/garbage.php?ckSize=100,,10000,52428800,41.94304,4,,,,,,false,"))
        assertTrue("paged, not one query", dao.measurementQueries >= 3)

        val seqs = lines(files.getValue("measurements.csv")).drop(1).map { it.substringBefore(',').toLong() }
        assertEquals("no tick lost or repeated at page edges", (0L until 1_201).toList(), seqs)
        val header = lines(files.getValue("measurements.csv")).first().split(',')
        val row5 = lines(files.getValue("measurements.csv"))[6].split(',')
        assertEquals("", row5[header.indexOf("lat")])
        assertEquals("WAITING", row5[header.indexOf("locationStatus")])
        assertTrue("JSON list with a comma is quoted", lines(files.getValue("measurements.csv"))[6].contains(",\"[20000,60000]\","))
    }

    @Test
    fun `a traffic capture is copied into the bundle and listed`() = runTest {
        val dir = Files.createTempDirectory("captures").toFile()
        val store = object : CaptureFileStore {
            override fun pcap(sessionUuid: String) = File(dir, "$sessionUuid.pcap")
            override fun sync(sessionUuid: String) = File(dir, "${sessionUuid}_sync.csv")
            override fun connections(sessionUuid: String) = File(dir, "${sessionUuid}_connections.csv")
        }
        val uuid = session().uuid
        store.pcap(uuid).writeText("pcap-bytes")
        store.sync(uuid).writeText("packet,arrivalElapsedNs\n")
        store.connections(uuid).writeText("protocol,ipVersion\r\nTCP,4\r\n")
        val (_, files) = exportZip(FakeExportDao(session = session().copy(captureFile = "$uuid.pcap")), store)
        assertEquals("pcap-bytes", files.getValue("capture.pcap"))
        assertEquals("packet,arrivalElapsedNs\n", files.getValue("capture_sync.csv"))
        assertEquals("protocol,ipVersion\r\nTCP,4\r\n", files.getValue("connections.csv"))
        val json = Json.parseToJsonElement(files.getValue("session.json")).jsonObject
        assertEquals("$uuid.pcap", json["captureFile"]?.jsonPrimitive?.content)
        assertTrue(json.getValue("files").toString().contains("capture.pcap"))
        assertTrue(json.getValue("files").toString().contains("connections.csv"))
        dir.deleteRecursively()

        val (_, without) = exportZip(FakeExportDao(), store)
        assertEquals("no capture: no entry", SessionExporter.FILES.toSet(), without.keys)
    }

    @Test
    fun `session json keeps metadata, zone and UTF-8 text`() = runTest {
        val (result, files) = exportZip(FakeExportDao())
        val json = Json.parseToJsonElement(files.getValue("session.json")).jsonObject
        assertEquals("netlogger-session", json["format"]?.jsonPrimitive?.content)
        assertEquals("cb1784a9-879e-4a6e-9959-31f7b7d9821c", json["uuid"]?.jsonPrimitive?.content)
        assertEquals("Pasar Raya, loop 1 \"A\"", json["label"]?.jsonPrimitive?.content)
        assertEquals("Ümlaut, comma\nsecond line", json["notes"]?.jsonPrimitive?.content)
        assertEquals("Asia/Jakarta", json["timeZone"]?.jsonPrimitive?.content)
        assertEquals("+07:00", json["utcOffset"]?.jsonPrimitive?.content)
        assertEquals("2026-10-14T05:40:00.000+07:00", json["startedAt"]?.jsonPrimitive?.content)
        assertEquals("60000", json["durationMs"]?.jsonPrimitive?.content)
        assertEquals("89", json.getValue("network").jsonObject["mnc"]?.jsonPrimitive?.content)
        assertEquals("netlogger_SM-A566B_20261014-054000_Pasar-Raya-loop-1-A_cb1784a9.zip", result.fileName)
    }

    @Test
    fun `flat csv in the bundle follows the source rule`() = runTest {
        val (_, files) = exportZip(FakeExportDao())
        val rows = lines(files.getValue("flat.csv"))
        val header = rows.first().split(',')
        val first = rows[1]
        // cellBandwidthsJson is quoted and contains a comma; split around it.
        val cells = first.replace("\"[20000,60000]\"", "BW").split(',')
        assertEquals("15", cells[header.indexOf("lteSinr")])
        assertEquals("SIGNAL_STRENGTH", cells[header.indexOf("lteSinrSource")])
        assertEquals("440", cells[header.indexOf("nrPci")])
    }

    @Test
    fun `progress goes through every file and ends at 1`() = runTest {
        val dir = Files.createTempDirectory("captures").toFile()
        val store = object : CaptureFileStore {
            override fun pcap(sessionUuid: String) = File(dir, "$sessionUuid.pcap")
            override fun sync(sessionUuid: String) = File(dir, "${sessionUuid}_sync.csv")
            override fun connections(sessionUuid: String) = File(dir, "${sessionUuid}_connections.csv")
        }
        store.pcap(session().uuid).writeBytes(ByteArray(200_000))
        val dao = FakeExportDao(measurements = (0L until 1_201).map { measurement(it) })
        val seen = mutableListOf<ExportProgress>()
        SessionExporter(dao, store).export(1, ByteArrayOutputStream(), zone, onProgress = { seen += it })
        dir.deleteRecursively()

        assertEquals(listOf("measurements.csv", "cells.csv", "flat.csv", "track.geojson", "capture.pcap"), seen.map { it.file }.distinct())
        assertTrue("never goes back", seen.zipWithNext().all { (a, b) -> b.fraction >= a.fraction })
        assertEquals(1.0, seen.last().fraction, 1e-9)
        // The four tick passes and the capture each take a fifth; every pass ends at its share.
        listOf("measurements.csv" to 0.2, "cells.csv" to 0.4, "flat.csv" to 0.6, "track.geojson" to 0.8).forEach { (file, end) ->
            assertEquals(file, end, seen.last { it.file == file }.fraction, 1e-9)
        }
        assertEquals("1201 ticks in pages of 500", 3, seen.count { it.file == "measurements.csv" })
    }

    @Test
    fun `a recording session is not exported`() = runTest {
        try {
            exportZip(FakeExportDao(session = session(endedAt = null)))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("session is still recording", e.message)
        }
    }

    @Test
    fun `file name falls back to the scenario and stays ASCII`() {
        assertEquals("netlogger_SM-A566B_20261014-054000_walk_cb1784a9.zip", SessionExporter.fileName(session(label = "  "), zone))
        assertEquals("netlogger_SM-A566B_20261014-054000_x_cb1784a9.zip", SessionExporter.fileName(session(label = "日本"), zone))
    }
}

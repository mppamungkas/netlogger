package id.my.pamslab.netlogger.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import id.my.pamslab.netlogger.core.data.db.ALL_MIGRATIONS
import id.my.pamslab.netlogger.core.data.db.MIGRATION_1_2
import id.my.pamslab.netlogger.core.data.db.MIGRATION_2_3
import id.my.pamslab.netlogger.core.data.db.NetLoggerDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Creates the database from the exported schema JSON, and tests every migration: create the old version with [helper],
 * insert rows, then `helper.runMigrationsAndValidate(name, N, true, MIGRATION_X_N)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SchemaTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NetLoggerDatabase::class.java)

    @Test
    fun `exported schema v1 creates and opens`() {
        helper.createDatabase(DB, 1).use { db ->
            db.execSQL(
                "INSERT INTO session (uuid, startedAt, startedElapsedNs, intervalMs, deviceManufacturer, deviceModel, " +
                    "androidVersion, schemaVersion) VALUES ('u', 1, 1, 1000, 'm', 'd', '15', 1)",
            )
        }
    }

    @Test
    fun `migration 1 to 2 keeps v1 data and adds throughput_test`() {
        helper.createDatabase(DB_MIGRATION, 1).use { db ->
            db.execSQL(
                "INSERT INTO session (uuid, startedAt, startedElapsedNs, intervalMs, deviceManufacturer, deviceModel, " +
                    "androidVersion, schemaVersion) VALUES ('u', 1, 1, 1000, 'm', 'd', '15', 1)",
            )
            db.execSQL("INSERT INTO event (sessionId, wallTimeMs, elapsedRealtimeNs, type) VALUES (1, 2, 2, 'USER_MARKER')")
        }
        helper.runMigrationsAndValidate(DB_MIGRATION, 2, true, MIGRATION_1_2).use { db ->
            db.query("SELECT uuid, schemaVersion FROM session").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("u", c.getString(0))
                assertEquals("rows keep the schema they were recorded with", 1, c.getInt(1))
            }
            db.query("SELECT COUNT(*) FROM event").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
            db.execSQL(
                "INSERT INTO throughput_test (sessionId, startedAtMs, startedElapsedNs, type, serverUrl, mbps, captureActive) " +
                    "VALUES (1, 3, 3, 'DOWNLOAD', 'https://example.org/backend/garbage.php', 42.5, 0)",
            )
            db.execSQL("PRAGMA foreign_keys = ON") // Room turns this on for app databases
            db.execSQL("DELETE FROM session")
            db.query("SELECT COUNT(*) FROM throughput_test").use { c ->
                c.moveToFirst(); assertEquals("deleted with its session", 0, c.getInt(0))
            }
        }
    }

    @Test
    fun `migration 2 to 3 keeps speed test rows and adds serverLocation`() {
        helper.createDatabase(DB_MIGRATION_3, 2).use { db ->
            db.execSQL(
                "INSERT INTO session (uuid, startedAt, startedElapsedNs, intervalMs, deviceManufacturer, deviceModel, " +
                    "androidVersion, schemaVersion) VALUES ('u', 1, 1, 1000, 'm', 'd', '15', 2)",
            )
            db.execSQL(
                "INSERT INTO throughput_test (sessionId, startedAtMs, startedElapsedNs, type, serverUrl, mbps, captureActive) " +
                    "VALUES (1, 3, 3, 'DOWNLOAD', 'https://speed.cloudflare.com/__down?bytes=25000000', 184.6, 0)",
            )
        }
        helper.runMigrationsAndValidate(DB_MIGRATION_3, 3, true, MIGRATION_2_3).use { db ->
            db.query("SELECT type, mbps, serverLocation FROM throughput_test").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("DOWNLOAD", c.getString(0))
                assertEquals(184.6, c.getDouble(1), 1e-9)
                assertTrue("old rows have no location", c.isNull(2))
            }
            db.execSQL(
                "INSERT INTO throughput_test (sessionId, startedAtMs, startedElapsedNs, type, serverUrl, captureActive, serverLocation) " +
                    "VALUES (1, 4, 4, 'PING', 'https://speed.cloudflare.com/__down?bytes=0', 0, 'SIN')",
            )
            db.query("SELECT serverLocation FROM throughput_test WHERE type = 'PING'").use { c ->
                assertTrue(c.moveToFirst()); assertEquals("SIN", c.getString(0))
            }
        }
    }

    @Test
    fun `every version has a migration`() {
        assertEquals((1 until NetLoggerDatabase.VERSION).toList(), ALL_MIGRATIONS.map { it.startVersion })
        ALL_MIGRATIONS.forEach { assertEquals(it.startVersion + 1, it.endVersion) }
    }

    private companion object {
        const val DB_MIGRATION = "migration-test.db"
        const val DB_MIGRATION_3 = "migration-test-3.db"
        const val DB = "schema-test.db"
    }
}

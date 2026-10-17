package id.my.pamslab.netlogger.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2: throughput_test table for the speed test (milestone 8). Existing tables are unchanged. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `throughput_test` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` INTEGER NOT NULL, `startedAtMs` INTEGER NOT NULL, `startedElapsedNs` INTEGER NOT NULL, " +
                "`type` TEXT NOT NULL, `serverUrl` TEXT NOT NULL, `durationMs` INTEGER, `bytes` INTEGER, `mbps` REAL, " +
                "`streams` INTEGER, `rttMinMs` REAL, `rttAvgMs` REAL, `rttMaxMs` REAL, `jitterMs` REAL, `lossPct` REAL, " +
                "`captureActive` INTEGER NOT NULL, `error` TEXT, FOREIGN KEY(`sessionId`) REFERENCES `session`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_throughput_test_sessionId` ON `throughput_test` (`sessionId`)")
    }
}

/** v2 → v3: throughput_test.serverLocation (Cloudflare's location code); existing rows get NULL. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `throughput_test` ADD COLUMN `serverLocation` TEXT")
    }
}

/** Every migration, in order; registered with the database builder. */
val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)

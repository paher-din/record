package com.paherdin.record.core.data.timeline

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tech PoC P6 — benchmark-scoped shadow Room projection. A file database whose
 * only table is `timeline_card`. Lives only in `:core:data`'s `benchmark`
 * source set and never enters `main`/`release`; schema export is disabled so
 * the committed production `core/data/schemas/` set stays untouched.
 */
@Entity(
    tableName = "timeline_card",
    indices = [Index(value = ["occurredAt"])],
)
data class TimelineCardEntity(
    @PrimaryKey val id: String,
    val type: String,
    val occurredAt: Long,
    val title: String,
    val amountMinor: Long?,
    val body: String?,
)

@Dao
interface TimelineCardDao {

    companion object {
        const val SELECTED_DAY_QUERY_SQL =
            "SELECT * FROM timeline_card " +
                "WHERE occurredAt >= :dayStart AND occurredAt < :nextDayStart " +
                "ORDER BY occurredAt DESC, id COLLATE BINARY ASC"
    }

    /** Seeding helper; REPLACE keeps a re-seed idempotent across launches. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<TimelineCardEntity>)

    @Query(SELECTED_DAY_QUERY_SQL)
    suspend fun querySelectedDay(dayStart: Long, nextDayStart: Long): List<TimelineCardEntity>

    /** Actual on-disk row count for the contract sentinel. */
    @Query("SELECT COUNT(*) FROM timeline_card")
    suspend fun rowCount(): Long
}

@Database(
    entities = [TimelineCardEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TimelineShadowDb : RoomDatabase() {
    abstract fun timelineCardDao(): TimelineCardDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_timeline_card_occurredAt " +
                        "ON timeline_card (occurredAt)",
                )
            }
        }
    }
}

/** Benchmark input DTO; UI callers never receive a Room entity or DAO. */
data class TimelineSeedCard(
    val id: String,
    val type: String,
    val occurredAt: Long,
    val title: String,
    val amountMinor: Long?,
    val body: String?,
)

data class ShadowMigrationContractResult(
    val beforeRowCount: Long,
    val afterRowCount: Long,
    val firstHashBefore: String,
    val firstHashAfter: String,
    val lastHashBefore: String,
    val lastHashAfter: String,
    val queryPlanDetail: String,
)

data class ShadowPreparationResult(
    val rowCount: Long,
    val seedWriteCount: Int,
    val firstHash: String,
    val lastHash: String,
    val migration: ShadowMigrationContractResult,
)

data class ShadowSelectedDayContractResult(
    val rowCount: Long,
    val selectedDate: String,
    val dayStart: Long,
    val nextDayStart: Long,
    val itemCount: Int,
    val hash: String,
    val queryPlanDetail: String,
    val seedWriteCount: Int,
)

enum class ShadowPreparationFailurePoint(val wire: String) {
    AFTER_OPEN("after-open"),
    AFTER_INSERT("after-insert"),
    AFTER_CHECK("after-check"),
    MIGRATION_AFTER_ROOM_OPEN("migration-after-room-open");

    companion object {
        fun fromWire(wire: String): ShadowPreparationFailurePoint =
            entries.singleOrNull { it.wire == wire }
                ?: throw IllegalArgumentException("unknown preparation failure point: $wire")
    }
}

data class ShadowResourceSnapshot(val opened: Int, val closed: Int, val seedWrites: Int) {
    fun openedSince(before: ShadowResourceSnapshot): Int = opened - before.opened
    fun closedSince(before: ShadowResourceSnapshot): Int = closed - before.closed
    fun seedWritesSince(before: ShadowResourceSnapshot): Int = seedWrites - before.seedWrites
}

/**
 * Pure-Kotlin facade over the shadow file database. `:app`'s benchmark
 * Activity calls this without ever naming a Room type, keeping the Room runtime
 * off `:app`'s compile classpath. Seeding/insert/read/hash all complete before
 * any (future) timing trace.
 */
object TimelineShadowSeeder {

    fun prepareSeedAndVerify(
        context: Context,
        cards: List<TimelineSeedCard>,
        failurePoint: ShadowPreparationFailurePoint? = null,
    ): ShadowPreparationResult = runBlocking {
        val before = resourceSnapshot()
        val entities = cards.map(TimelineSeedCard::toEntity)
        val db = open(context, DB_NAME)
        val mainPreparation = try {
            val dao = db.timelineCardDao()
            dao.rowCount() // Forces the real Room open before the open-failure contract point.
            failIfRequested(failurePoint, ShadowPreparationFailurePoint.AFTER_OPEN)
            dao.insertAll(entities)
            ShadowResourceTracker.seedWritten(entities.size)
            failIfRequested(failurePoint, ShadowPreparationFailurePoint.AFTER_INSERT)
            val rowCount = dao.rowCount()
            check(rowCount == EXPECTED_ROW_COUNT) {
                "prepared shadow DB has $rowCount rows, expected $EXPECTED_ROW_COUNT"
            }
            val first = TimelineTypedReader.readSelectedDay(dao, FIRST_DAY_START, FIRST_DAY_END)
            val last = TimelineTypedReader.readSelectedDay(dao, LAST_DAY_START, LAST_DAY_END)
            check(first.size == EXPECTED_DAY_ITEM_COUNT && last.size == EXPECTED_DAY_ITEM_COUNT) {
                "prepared shadow DB must retain 11-item first/last slices"
            }
            failIfRequested(failurePoint, ShadowPreparationFailurePoint.AFTER_CHECK)
            MainPreparationResult(
                rowCount = rowCount,
                seedWriteCount = resourceSnapshot().seedWritesSince(before),
                firstHash = hashItems(first),
                lastHash = hashItems(last),
            )
        } finally {
            close(db)
        }
        val migration = verifyMigration(context, cards, failurePoint)
        ShadowPreparationResult(
            rowCount = mainPreparation.rowCount,
            seedWriteCount = mainPreparation.seedWriteCount,
            firstHash = mainPreparation.firstHash,
            lastHash = mainPreparation.lastHash,
            migration = migration,
        )
    }

    fun readSelectedDayContract(
        context: Context,
        selectedDate: String,
        dayStart: Long,
        nextDayStart: Long,
    ): ShadowSelectedDayContractResult = runBlocking {
        val before = resourceSnapshot()
        val handle = openPrepared(context)
        try {
            val items = handle.readSelectedDay(dayStart, nextDayStart)
            ShadowSelectedDayContractResult(
                rowCount = handle.rowCount(),
                selectedDate = selectedDate,
                dayStart = dayStart,
                nextDayStart = nextDayStart,
                itemCount = items.size,
                hash = hashItems(items),
                queryPlanDetail = handle.selectedDayQueryPlan(dayStart, nextDayStart).details.joinToString(" | "),
                seedWriteCount = resourceSnapshot().seedWritesSince(before),
            )
        } finally {
            handle.close()
        }
    }

    /**
     * Task 4/6 render path: opens the already-prepared shadow file read-only,
     * verifies the frozen row count, and returns the bounded-reader handle.
     * No fixture insert occurs on this path.
     *
     * The caller owns the `TimelineQueryToRender` trace boundary and MUST begin
     * it on the main thread immediately before `readSelectedDay()` and end it on
     * the main thread at the first correct draw — `android.os.Trace`
     * begin/end pairs per-thread, so a cross-thread pairing leaves the slice
     * open and `TraceSectionMetric` cannot count it. Only `readSelectedDay()`
     * is inside the measured span; the DB type never escapes.
     */
    suspend fun openPrepared(context: Context): ShadowProjectionHandle {
        val db = open(context, DB_NAME)
        val handle = ShadowProjectionHandle(db)
        return try {
            val rowCount = handle.rowCount()
            check(rowCount == EXPECTED_ROW_COUNT) {
                "shadow DB is not prepared: $rowCount rows, expected $EXPECTED_ROW_COUNT"
            }
            handle
        } catch (t: Throwable) {
            handle.close()
            throw t
        }
    }

    fun resourceSnapshot(): ShadowResourceSnapshot = ShadowResourceTracker.snapshot()

    internal fun closePreparedDb(db: TimelineShadowDb) {
        close(db)
    }

    const val DB_NAME = "timeline-shadow.db"
    const val SELECTED_DAY_QUERY_SQL = TimelineCardDao.SELECTED_DAY_QUERY_SQL

    private const val FIRST_DAY_START = 1_672_502_400_000L
    private const val FIRST_DAY_END = 1_672_588_800_000L
    private const val LAST_DAY_START = 1_758_816_000_000L
    private const val LAST_DAY_END = 1_758_902_400_000L
    private const val EXPECTED_ROW_COUNT = 10_000L
    private const val EXPECTED_DAY_ITEM_COUNT = 11
    private const val MIGRATION_CONTRACT_DB_PREFIX = "timeline-shadow-migration-contract-"
    private const val DB_SUFFIX = ".db"
    private const val PRODUCTION_DB_NAME = "record.db"
    private const val GUARD_UUID = "123e4567-e89b-12d3-a456-426614174000"
    private const val UNRELATED_SENTINEL_NAME = "timeline-shadow-migration-cleanup-unrelated.sentinel"
    private fun interface ExactFileDeleter {
        fun delete(file: File): Boolean
    }
    private val FILE_DELETER = ExactFileDeleter { file -> file.delete() }
    private const val POSITIONAL_SELECTED_DAY_SQL =
        "SELECT * FROM timeline_card " +
            "WHERE occurredAt >= ? AND occurredAt < ? " +
            "ORDER BY occurredAt DESC, id COLLATE BINARY ASC"

    private fun open(context: Context, name: String): TimelineShadowDb {
        val db = Room.databaseBuilder(context.applicationContext, TimelineShadowDb::class.java, name)
            .addMigrations(TimelineShadowDb.MIGRATION_1_2)
            .build()
        ShadowResourceTracker.opened()
        return db
    }

    private fun close(db: TimelineShadowDb) {
        db.close()
        ShadowResourceTracker.closed()
    }

    private fun failIfRequested(
        requested: ShadowPreparationFailurePoint?,
        current: ShadowPreparationFailurePoint,
    ) {
        if (requested == current) error("injected preparation failure: ${current.wire}")
    }

    private suspend fun verifyMigration(
        context: Context,
        cards: List<TimelineSeedCard>,
        failurePoint: ShadowPreparationFailurePoint?,
    ): ShadowMigrationContractResult {
        val appContext = context.applicationContext
        val name = "$MIGRATION_CONTRACT_DB_PREFIX${UUID.randomUUID()}.db"
        val path = appContext.getDatabasePath(validateMigrationContractDatabaseName(name))
        val unrelatedSentinel = ensureUnrelatedSentinel(appContext)
        val guardEvidence = migrationCleanupGuardEvidence()
        var versionOneOpen = false
        var result: ShadowMigrationContractResult? = null
        var failure: Throwable? = null
        var cleanupEvidence = ""
        var cleanupAuthorized = false
        try {
            check(!path.exists()) { "fresh migration-contract database already exists: $name" }
            cleanupAuthorized = true
            check(path.parentFile?.mkdirs() != false || path.parentFile?.isDirectory == true) {
                "cannot create migration-contract database directory"
            }

            val versionOne = SQLiteDatabase.openOrCreateDatabase(path, null)
            versionOneOpen = true
            val beforeRowCount: Long
            val firstHashBefore: String
            val lastHashBefore: String
            try {
                versionOne.execSQL(V1_CREATE_TABLE_SQL)
                versionOne.beginTransaction()
                try {
                    versionOne.compileStatement(V1_INSERT_SQL).use { insert ->
                        for (card in cards) {
                            insert.clearBindings()
                            insert.bindString(1, card.id)
                            insert.bindString(2, card.type)
                            insert.bindLong(3, card.occurredAt)
                            insert.bindString(4, card.title)
                            if (card.amountMinor == null) insert.bindNull(5) else insert.bindLong(5, card.amountMinor)
                            if (card.body == null) insert.bindNull(6) else insert.bindString(6, card.body)
                            insert.executeInsert()
                        }
                    }
                    versionOne.setTransactionSuccessful()
                } finally {
                    versionOne.endTransaction()
                }
                versionOne.version = 1
                beforeRowCount = queryRowCount(versionOne)
                firstHashBefore = hashRows(
                    querySelectedDay(versionOne, FIRST_DAY_START, FIRST_DAY_END),
                    FIRST_DAY_START,
                    FIRST_DAY_END,
                )
                lastHashBefore = hashRows(
                    querySelectedDay(versionOne, LAST_DAY_START, LAST_DAY_END),
                    LAST_DAY_START,
                    LAST_DAY_END,
                )
            } finally {
                versionOne.close()
                versionOneOpen = false
            }

            val handle = ShadowProjectionHandle(open(appContext, name))
            try {
                handle.rowCount() // Force the migrated Room file open before failure injection.
                failIfRequested(
                    failurePoint,
                    ShadowPreparationFailurePoint.MIGRATION_AFTER_ROOM_OPEN,
                )
                val firstAfter = handle.readSelectedDay(FIRST_DAY_START, FIRST_DAY_END)
                val lastAfter = handle.readSelectedDay(LAST_DAY_START, LAST_DAY_END)
                result = ShadowMigrationContractResult(
                    beforeRowCount = beforeRowCount,
                    afterRowCount = handle.rowCount(),
                    firstHashBefore = firstHashBefore,
                    firstHashAfter = hashItems(firstAfter),
                    lastHashBefore = lastHashBefore,
                    lastHashAfter = hashItems(lastAfter),
                    queryPlanDetail = handle.selectedDayQueryPlan(LAST_DAY_START, LAST_DAY_END)
                        .details.joinToString(" | "),
                )
            } finally {
                handle.close()
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            if (cleanupAuthorized) {
                check(!versionOneOpen) { "migration cleanup reached with version-1 SQLite handle open" }
                cleanupEvidence = cleanupMigrationContractDatabase(
                    context = appContext,
                    validatedName = name,
                    unrelatedSentinel = unrelatedSentinel,
                )
            }
        }

        val evidence = "$guardEvidence;$cleanupEvidence"
        failure?.let { throw IllegalStateException("${it.message};$evidence", it) }
        val completed = checkNotNull(result) { "migration contract completed without result;$evidence" }
        return completed.copy(queryPlanDetail = "${completed.queryPlanDetail};$evidence")
    }

    private fun validateMigrationContractDatabaseName(candidate: String): String {
        require(candidate != DB_NAME && candidate != PRODUCTION_DB_NAME) {
            "database name is explicitly denied"
        }
        require('/' !in candidate && '\\' !in candidate && File(candidate).name == candidate) {
            "migration cleanup target must be a basename"
        }
        require(candidate.startsWith(MIGRATION_CONTRACT_DB_PREFIX) && candidate.endsWith(DB_SUFFIX)) {
            "migration cleanup target has the wrong prefix or suffix"
        }
        val uuidText = candidate.removePrefix(MIGRATION_CONTRACT_DB_PREFIX).removeSuffix(DB_SUFFIX)
        val uuid = runCatching { UUID.fromString(uuidText) }
            .getOrElse { throw IllegalArgumentException("migration cleanup target has invalid UUID", it) }
        require(uuid.toString() == uuidText) { "migration cleanup UUID must be canonical lowercase text" }
        return candidate
    }

    private fun cleanupMigrationContractDatabase(
        context: Context,
        validatedName: String,
        unrelatedSentinel: File,
        deleter: ExactFileDeleter = FILE_DELETER,
    ): String {
        val authorizedFiles = resolveMigrationCleanupTargets(context, validatedName)
        val resources = resourceSnapshot()
        check(resources.opened == resources.closed) {
            "migration cleanup reached before every tracked Room handle closed: $resources"
        }
        val deleteCalls = ArrayList<String>(authorizedFiles.size)
        authorizedFiles.forEach { file ->
            if (file.exists()) {
                deleteCalls.add(file.name)
                check(deleter.delete(file)) { "approved migration-contract file deletion failed: ${file.name}" }
            }
        }
        val residue = authorizedFiles.filter(File::exists)
        check(residue.isEmpty()) {
            "authorized migration-contract residue remains: ${residue.joinToString { it.name }}"
        }
        check(unrelatedSentinel.isFile) { "unrelated app-private sentinel was removed" }
        return "cleanupTargets=${authorizedFiles.joinToString("|") { it.name }};" +
            "cleanupDeleteCalls=${deleteCalls.joinToString("|")};cleanupApi=File.delete;" +
            "cleanupAfterHandlesClosed=true;cleanupResidue=0;unrelatedSentinelPresent=true"
    }

    private fun resolveMigrationCleanupTargets(context: Context, validatedName: String): List<File> {
        val name = validateMigrationContractDatabaseName(validatedName)
        val databasesDir = checkNotNull(context.getDatabasePath(DB_NAME).parentFile).canonicalFile
        val base = context.getDatabasePath(name).canonicalFile
        val expectedBase = File(databasesDir, name).canonicalFile
        check(base == expectedBase && base.parentFile == databasesDir && base.name == name) {
            "migration cleanup target is not the expected direct database child"
        }
        return listOf(
            base,
            File(databasesDir, name + "-wal").canonicalFile,
            File(databasesDir, name + "-shm").canonicalFile,
        ).also { targets ->
            check(targets.size == 3 && targets.distinct().size == 3)
            check(targets.all { it.parentFile == databasesDir }) {
                "migration cleanup sidecar is not a direct database child"
            }
            check(targets.map { it.name } == listOf(name, name + "-wal", name + "-shm")) {
                "migration cleanup resolved an unauthorized suffix"
            }
        }
    }

    private fun ensureUnrelatedSentinel(context: Context): File =
        File(context.filesDir, UNRELATED_SENTINEL_NAME).also { sentinel ->
            if (!sentinel.exists()) sentinel.writeText("unrelated migration cleanup sentinel\n")
            check(sentinel.isFile) { "unrelated app-private sentinel is unavailable" }
        }

    private fun migrationCleanupGuardEvidence(): String {
        val canonical = "$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID$DB_SUFFIX"
        check(validateMigrationContractDatabaseName(canonical) == canonical)
        val rejected = cleanupRejectedNames()
        rejected.forEach { (label, candidate) ->
            check(runCatching { validateMigrationContractDatabaseName(candidate) }.isFailure) {
                "cleanup guard accepted $label: $candidate"
            }
        }
        return buildString {
            append("cleanupGuard:canonical=ACCEPTED")
            rejected.keys.forEach { append(',').append(it).append("=REJECTED") }
        }
    }

    private fun cleanupRejectedNames(): Map<String, String> = linkedMapOf(
        "wrongPrefix" to "timeline-shadow-other-$GUARD_UUID.db",
        "missingUuid" to "$MIGRATION_CONTRACT_DB_PREFIX.db",
        "invalidUuid" to "${MIGRATION_CONTRACT_DB_PREFIX}not-a-uuid.db",
        "noncanonicalUuid" to "$MIGRATION_CONTRACT_DB_PREFIX${GUARD_UUID.uppercase()}.db",
        "suffixTrick" to "$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID.db.bak",
        "forwardSeparator" to "nested/$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID.db",
        "backSeparator" to "nested\\$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID.db",
        "absolutePath" to "/data/user/0/com.paherdin.record/databases/$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID.db",
        "mainShadow" to DB_NAME,
        "production" to PRODUCTION_DB_NAME,
        "generationLike" to "vault-generation-$GUARD_UUID.db",
        "snapshotLike" to "migration-snapshot-$GUARD_UUID.db",
        "nonBasename" to "../$MIGRATION_CONTRACT_DB_PREFIX$GUARD_UUID.db",
    )

    private fun queryRowCount(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT COUNT(*) FROM timeline_card", null).use { cursor ->
            check(cursor.moveToFirst()) { "version-1 row count query returned no row" }
            cursor.getLong(0)
        }

    private fun querySelectedDay(
        db: SQLiteDatabase,
        dayStart: Long,
        nextDayStart: Long,
    ): List<TimelineCardEntity> =
        db.rawQuery(
            POSITIONAL_SELECTED_DAY_SQL,
            arrayOf(dayStart.toString(), nextDayStart.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        TimelineCardEntity(
                            id = cursor.getString(0),
                            type = cursor.getString(1),
                            occurredAt = cursor.getLong(2),
                            title = cursor.getString(3),
                            amountMinor = if (cursor.isNull(4)) null else cursor.getLong(4),
                            body = if (cursor.isNull(5)) null else cursor.getString(5),
                        ),
                    )
                }
            }
        }

    private fun hashRows(rows: List<TimelineCardEntity>, dayStart: Long, nextDayStart: Long): String =
        hashItems(TimelineTypedReader.materializeSelectedDay(rows, dayStart, nextDayStart))

    private fun hashItems(items: List<TimelineProjectionItem>): String =
        TimelineTypedReader.sha256Hex(TimelineTypedReader.encodeCanonical(items))

    private data class MainPreparationResult(
        val rowCount: Long,
        val seedWriteCount: Int,
        val firstHash: String,
        val lastHash: String,
    )

    private const val V1_CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS timeline_card (" +
            "id TEXT NOT NULL PRIMARY KEY, " +
            "type TEXT NOT NULL, " +
            "occurredAt INTEGER NOT NULL, " +
            "title TEXT NOT NULL, " +
            "amountMinor INTEGER, " +
            "body TEXT)"
    private const val V1_INSERT_SQL =
        "INSERT OR REPLACE INTO timeline_card " +
            "(id, type, occurredAt, title, amountMinor, body) VALUES (?, ?, ?, ?, ?, ?)"
}

/**
 * Live handle over the open shadow DB returned by
 * [TimelineShadowSeeder.openPrepared]. The Room type never escapes.
 */
class ShadowProjectionHandle internal constructor(
    private val db: TimelineShadowDb,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    suspend fun readSelectedDay(dayStart: Long, nextDayStart: Long): List<TimelineProjectionItem> {
        require(dayStart < nextDayStart) { "dayStart must be before nextDayStart" }
        return TimelineTypedReader.readSelectedDay(db.timelineCardDao(), dayStart, nextDayStart)
    }

    suspend fun rowCount(): Long = db.timelineCardDao().rowCount()

    fun selectedDayQueryPlan(dayStart: Long, nextDayStart: Long): SelectedDayQueryPlan {
        require(dayStart < nextDayStart) { "dayStart must be before nextDayStart" }
        val positionalSql = TimelineCardDao.SELECTED_DAY_QUERY_SQL
            .replace(":dayStart", "?")
            .replace(":nextDayStart", "?")
        return db.query(
            SimpleSQLiteQuery("EXPLAIN QUERY PLAN $positionalSql", arrayOf(dayStart, nextDayStart)),
        ).use { cursor ->
            val detailIndex = cursor.getColumnIndexOrThrow("detail")
            SelectedDayQueryPlan(buildList {
                while (cursor.moveToNext()) add(cursor.getString(detailIndex))
            })
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                TimelineShadowSeeder.closePreparedDb(db)
            } catch (t: Throwable) {
                closed.set(false)
                throw t
            }
        }
    }
}

data class SelectedDayQueryPlan(val details: List<String>)

private fun TimelineSeedCard.toEntity(): TimelineCardEntity = TimelineCardEntity(
    id = id,
    type = type,
    occurredAt = occurredAt,
    title = title,
    amountMinor = amountMinor,
    body = body,
)

private object ShadowResourceTracker {
    private val opened = AtomicInteger()
    private val closed = AtomicInteger()
    private val seedWrites = AtomicInteger()

    fun opened() {
        opened.incrementAndGet()
    }

    fun closed() {
        closed.incrementAndGet()
    }

    fun seedWritten(count: Int) {
        seedWrites.addAndGet(count)
    }

    fun snapshot(): ShadowResourceSnapshot =
        ShadowResourceSnapshot(opened.get(), closed.get(), seedWrites.get())
}

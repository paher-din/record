package com.paherdin.record.core.data.search

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.SkipQueryVerification
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * Tech PoC P4 (plan Task 6) — the test-scoped candidate databases: a shared
 * `SearchDocument` Room shape plus the paged-LIKE (Candidate L) and
 * raw-DDL external-content FTS4 (Candidate F) query paths. Databases live in
 * `cacheDir/p4-search-benchmark/{runId}/` with WAL enabled; the directory is
 * new per explicit invocation carrying a `p4RunId` and retained as evidence
 * (P4 performs no deletion; a run without a `p4RunId` falls back to a shared
 * local directory).
 */
object SearchCandidateDb {

    const val FTS_DDL = "CREATE VIRTUAL TABLE SearchDocument_fts USING fts4(" +
        "normalizedText, content='SearchDocument', tokenize=unicode61)"
    const val FTS_TRIGGER_DDL = "CREATE TRIGGER search_document_ai AFTER INSERT ON SearchDocument " +
        "BEGIN INSERT INTO SearchDocument_fts(docid, normalizedText) VALUES (new.rowId, new.normalizedText); END"

    fun likeDatabase(context: Context, runDir: File): SearchCandidateDatabase =
        build(context, runDir, withFts = false)

    fun ftsDatabase(context: Context, runDir: File): SearchCandidateDatabase =
        build(context, runDir, withFts = true)

    private fun build(context: Context, runDir: File, withFts: Boolean): SearchCandidateDatabase {
        runDir.mkdirs()
        val dbName = if (withFts) "fts.db" else "like.db"
        return Room.databaseBuilder(context, SearchCandidateDatabase::class.java, File(runDir, dbName).absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    if (withFts) {
                        db.execSQL(FTS_DDL)
                        db.execSQL(FTS_TRIGGER_DDL)
                    }
                }
            })
            .build()
    }
}

@Entity(
    tableName = "SearchDocument",
    indices = [
        Index(value = ["entityRefId"], unique = true),
        Index(value = ["occurredAt", "entityRefId"]),
    ],
)
data class SearchDocumentEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val entityRefId: String,
    val type: String,
    val title: String?,
    val body: String?,
    val auxiliary: String,
    val normalizedText: String,
    val occurredAt: Long,
    val updatedAt: Long,
)

@Dao
interface SearchDao {
    @Query(
        "SELECT entityRefId FROM SearchDocument WHERE normalizedText LIKE '%' || :q || '%' " +
            "ESCAPE '\\' ORDER BY occurredAt DESC, entityRefId ASC LIMIT 50",
    )
    suspend fun likeFirstPage(q: String): List<String>

    // The FTS4 virtual table is created by raw DDL (SearchCandidateDb.FTS_DDL),
    // which Room cannot see at compile time; the query is verified by the smoke
    // test instead. This table exists only for the P4 FTS candidate.
    @SkipQueryVerification
    @Query(
        "SELECT entityRefId FROM SearchDocument WHERE rowId IN " +
            "(SELECT docid FROM SearchDocument_fts WHERE SearchDocument_fts MATCH :m) " +
            "ORDER BY occurredAt DESC, entityRefId ASC LIMIT 50",
    )
    suspend fun ftsFirstPage(m: String): List<String>

    @Query("SELECT entityRefId FROM SearchDocument ORDER BY rowId ASC")
    suspend fun allEntityRefIdsInFillOrder(): List<String>

    @Insert
    suspend fun insertAll(rows: List<SearchDocumentEntity>)
}

@Database(entities = [SearchDocumentEntity::class], version = 1, exportSchema = false)
abstract class SearchCandidateDatabase : RoomDatabase() {
    abstract fun dao(): SearchDao
}

/** Maps the frozen corpus records to the shared Room entity shape. */
fun List<SearchFixtureRecord>.toEntities(): List<SearchDocumentEntity> = map { r ->
    SearchDocumentEntity(
        entityRefId = r.entityRefId,
        type = r.type,
        title = r.title,
        body = r.body,
        auxiliary = r.auxiliary,
        normalizedText = r.normalizedText,
        occurredAt = r.occurredAtMs,
        updatedAt = r.updatedAtMs,
    )
}

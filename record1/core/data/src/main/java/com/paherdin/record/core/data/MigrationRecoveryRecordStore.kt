package com.paherdin.record.core.data

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import java.io.File
import java.io.InputStreamReader

/** The recovery-record state machine states (data and storage, plan Task 3). */
internal enum class MigrationRecoveryState {
    MIGRATION_HEALTHY,
    MIGRATION_RECOVERY_PENDING,
    MIGRATION_RECOVERED,
}

/** The pending-recovery stages (data and storage, plan Task 3). */
internal enum class MigrationStage {
    PREPARED,
    FAILED_SET_ISOLATED,
    RESTORE_COPY_VALIDATED,
    MAIN_REPLACED,
}

/**
 * The migration portion of the app-private activation/recovery record
 * (data and storage; plan "Recovery record v1"). All paths are normalized
 * app-data-root-relative strings; validation rejects absolute or escaping
 * paths before Room/SQLite opens.
 */
internal data class MigrationPayload(
    val snapshotId: String,
    val snapshotSha256: String,
    val liveDatabasePath: String,
    val workDatabasePath: String,
    val restoreTempPath: String,
    val quarantinePath: String,
    val stage: MigrationStage,
)

/** The complete recovery record (plan "Recovery record v1"). */
internal data class MigrationRecord(
    val recordVersion: Int,
    val state: MigrationRecoveryState,
    val migration: MigrationPayload?,
) {
    init {
        require(recordVersion == RECORD_VERSION) { "unsupported recordVersion $recordVersion" }
        require(state == MigrationRecoveryState.MIGRATION_HEALTHY || migration != null) {
            "state $state requires a migration payload"
        }
        require(!(state == MigrationRecoveryState.MIGRATION_HEALTHY && migration != null)) {
            "MIGRATION_HEALTHY must have migration = null"
        }
    }

    companion object {
        const val RECORD_VERSION = 1
    }
}

/**
 * Strict v1 recovery-record store, read and atomically written through
 * `android.util.AtomicFile` before Room/SQLite initialization (plan Task 3).
 * Parsing is fail-closed: unknown `recordVersion`, states, stages, missing
 * fields, duplicate keys, unknown fields, malformed JSON, and absolute or
 * escaping paths all reject the record; a pending record is never inferred
 * from file presence and never deleted.
 */
internal class MigrationRecoveryRecordStore(private val file: File) {

    /** Returns null only when no record file exists yet (first P3 startup). */
    fun read(): MigrationRecord? {
        if (!file.exists()) return null
        return try {
            AtomicFile(file).openRead().use { stream ->
                parse(InputStreamReader(stream, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("malformed recovery record at $file", e)
        }
    }

    fun write(record: MigrationRecord) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(toJson(record).toByteArray(Charsets.UTF_8))
            out.flush()
            atomic.finishWrite(out)
        } catch (e: Throwable) {
            atomic.failWrite(out)
            throw e
        }
    }

    // ---- strict parser ----

    private fun parse(reader: InputStreamReader): MigrationRecord {
        JsonReader(reader.buffered()).use { json ->
            val seen = mutableSetOf<String>()
            var recordVersion: Int? = null
            var state: MigrationRecoveryState? = null
            var migration: MigrationPayload? = null
            var migrationSeen = false

            json.beginObject()
            while (json.hasNext()) {
                val key = json.nextName()
                if (!seen.add(key)) throw IllegalArgumentException("duplicate key '$key'")
                when (key) {
                    "recordVersion" -> recordVersion = json.nextInt()
                    "migrationRecoveryState" -> {
                        val raw = json.nextString()
                        state = MigrationRecoveryState.entries.firstOrNull { it.name == raw }
                            ?: throw IllegalArgumentException("unknown state '$raw'")
                    }
                    "migration" -> {
                        migrationSeen = true
                        migration = if (json.peek() == JsonToken.NULL) {
                            json.nextNull()
                            null
                        } else {
                            parseMigration(json)
                        }
                    }
                    else -> throw IllegalArgumentException("unknown field '$key'")
                }
            }
            json.endObject()

            val version = recordVersion ?: throw IllegalArgumentException("missing recordVersion")
            if (version != MigrationRecord.RECORD_VERSION) {
                throw IllegalArgumentException("unsupported recordVersion $version")
            }
            val parsedState = state ?: throw IllegalArgumentException("missing migrationRecoveryState")
            if (!migrationSeen) throw IllegalArgumentException("missing migration")

            return MigrationRecord(version, parsedState, migration)
        }
    }

    private fun parseMigration(json: JsonReader): MigrationPayload {
        val seen = mutableSetOf<String>()
        var snapshotId: String? = null
        var snapshotSha256: String? = null
        var liveDatabasePath: String? = null
        var workDatabasePath: String? = null
        var restoreTempPath: String? = null
        var quarantinePath: String? = null
        var stage: MigrationStage? = null

        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            if (!seen.add(key)) throw IllegalArgumentException("duplicate key '$key'")
            when (key) {
                "snapshotId" -> snapshotId = json.nextString()
                "snapshotSha256" -> snapshotSha256 = validateSha256(json.nextString())
                "liveDatabasePath" -> liveDatabasePath = normalizeAndValidatePath(json.nextString())
                "workDatabasePath" -> workDatabasePath = normalizeAndValidatePath(json.nextString())
                "restoreTempPath" -> restoreTempPath = normalizeAndValidatePath(json.nextString())
                "quarantinePath" -> quarantinePath = normalizeAndValidatePath(json.nextString())
                "stage" -> {
                    val raw = json.nextString()
                    stage = MigrationStage.entries.firstOrNull { it.name == raw }
                        ?: throw IllegalArgumentException("unknown stage '$raw'")
                }
                else -> throw IllegalArgumentException("unknown migration field '$key'")
            }
        }
        json.endObject()

        val payload = MigrationPayload(
            snapshotId = snapshotId ?: throw IllegalArgumentException("missing snapshotId"),
            snapshotSha256 = snapshotSha256 ?: throw IllegalArgumentException("missing snapshotSha256"),
            liveDatabasePath = liveDatabasePath ?: throw IllegalArgumentException("missing liveDatabasePath"),
            workDatabasePath = workDatabasePath ?: throw IllegalArgumentException("missing workDatabasePath"),
            restoreTempPath = restoreTempPath ?: throw IllegalArgumentException("missing restoreTempPath"),
            quarantinePath = quarantinePath ?: throw IllegalArgumentException("missing quarantinePath"),
            stage = stage ?: throw IllegalArgumentException("missing stage"),
        )
        return payload
    }

    private fun validateSha256(raw: String): String {
        if (raw.length != 64 || !raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            throw IllegalArgumentException("invalid snapshotSha256")
        }
        return raw
    }

    /**
     * Normalize to a forward-slash relative path and reject any escape:
     * absolute (leading `/`, `\`, or a Windows drive letter), traversal
     * segments, or empty paths.
     */
    private fun normalizeAndValidatePath(raw: String): String {
        if (raw.isEmpty()) throw IllegalArgumentException("empty path")
        if (raw.startsWith("/") || raw.startsWith("\\")) throw IllegalArgumentException("absolute path '$raw'")
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) throw IllegalArgumentException("drive path '$raw'")
        val normalized = raw.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) throw IllegalArgumentException("traversal path '$raw'")
        return normalized
    }

    // ---- writer ----

    private fun toJson(record: MigrationRecord): String {
        val migration = record.migration
        val migrationJson = if (migration == null) {
            "null"
        } else {
            val sb = StringBuilder()
            sb.append("{")
            sb.append("\"snapshotId\":").append(jsonString(migration.snapshotId)).append(",")
            sb.append("\"snapshotSha256\":").append(jsonString(migration.snapshotSha256)).append(",")
            sb.append("\"liveDatabasePath\":").append(jsonString(migration.liveDatabasePath)).append(",")
            sb.append("\"workDatabasePath\":").append(jsonString(migration.workDatabasePath)).append(",")
            sb.append("\"restoreTempPath\":").append(jsonString(migration.restoreTempPath)).append(",")
            sb.append("\"quarantinePath\":").append(jsonString(migration.quarantinePath)).append(",")
            sb.append("\"stage\":").append(jsonString(migration.stage.name))
            sb.append("}")
            sb.toString()
        }
        return "{" +
            "\"recordVersion\":${record.recordVersion}," +
            "\"migrationRecoveryState\":" + jsonString(record.state.name) + "," +
            "\"migration\":$migrationJson" +
            "}"
    }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

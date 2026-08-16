package com.paherdin.record.core.data.restore

import android.util.AtomicFile
import android.util.JsonReader
import android.util.JsonToken
import com.paherdin.record.core.data.MigrationPayload
import com.paherdin.record.core.data.MigrationRecoveryRecordStore
import com.paherdin.record.core.data.MigrationRecoveryState
import com.paherdin.record.core.data.MigrationStage
import java.io.File
import java.io.InputStreamReader

/** The generation-activation state values (data and storage, ADR 0003). */
internal enum class GenerationState { HEALTHY, PENDING }

/**
 * The generation-activation portion of the v2 activation/recovery record
 * (spec 4.5, ADR 0010). Generation IDs are app-generated UUID strings.
 */
internal data class GenerationActivation(
    val activeGenerationId: String,
    val previousGenerationId: String?,
    val candidateGenerationId: String?,
    val retiringGenerationId: String?,
    val state: GenerationState,
) {
    init {
        listOfNotNull(activeGenerationId, previousGenerationId, candidateGenerationId, retiringGenerationId)
            .forEach { id -> require(GENERATION_ID.matches(id)) { "invalid generation ID '$id'" } }
        when (state) {
            GenerationState.PENDING -> {
                require(previousGenerationId != null) { "PENDING requires previousGenerationId" }
                require(candidateGenerationId == activeGenerationId) {
                    "PENDING candidateGenerationId must equal activeGenerationId"
                }
                require(previousGenerationId != activeGenerationId) {
                    "PENDING previous and active generations must differ"
                }
                require(retiringGenerationId == null) { "PENDING cannot retire a generation" }
            }
            GenerationState.HEALTHY -> {
                require(previousGenerationId == null) { "HEALTHY must clear previousGenerationId" }
                require(candidateGenerationId == null) { "HEALTHY must clear candidateGenerationId" }
                require(retiringGenerationId == null || retiringGenerationId != activeGenerationId) {
                    "HEALTHY cannot retire the active generation"
                }
            }
        }
    }

    companion object {
        private val GENERATION_ID = Regex(
            "^gen-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        )
    }
}

/**
 * The complete v2 activation/recovery record: the P3 migration portion is
 * carried unchanged in meaning, plus the generation-activation portion.
 * `migration` is non-null exactly when `migrationRecoveryState` is
 * `MIGRATION_RECOVERY_PENDING` or `MIGRATION_RECOVERED` (the P3 invariant).
 */
internal data class ActivationRecord(
    val migrationRecoveryState: MigrationRecoveryState,
    val migration: MigrationPayload?,
    val generationActivation: GenerationActivation,
) {
    companion object {
        const val RECORD_VERSION = 2
    }

    init {
        require(
            migrationRecoveryState == MigrationRecoveryState.MIGRATION_HEALTHY ||
                migration != null,
        ) { "state $migrationRecoveryState requires a migration payload" }
        require(!(migrationRecoveryState == MigrationRecoveryState.MIGRATION_HEALTHY && migration != null)) {
            "MIGRATION_HEALTHY must have migration = null"
        }
        require(!(migrationRecoveryState != MigrationRecoveryState.MIGRATION_HEALTHY && migration == null)) {
            "a non-healthy migration state requires a payload"
        }
    }
}

/**
 * Strict v2 activation/recovery-record store (spec 4.5, plan Task 3), read and
 * atomically written through `android.util.AtomicFile` before Room/SQLite
 * initialization. The normal-operation reader supports exactly
 * `recordVersion = 2`; the ADR-0010 bootstrap reader accepts the P3 v1 shape
 * separately. Parsing is fail-closed: unknown versions, states, stages,
 * missing fields, duplicate keys, unknown fields, and absolute or escaping
 * paths reject the record.
 */
internal class ActivationRecordStore(private val file: File) {

    /**
     * Reads only the top-level version for the one-time bootstrap dispatcher.
     * The selected v1/v2 store then parses the complete record strictly.
     */
    fun recordVersion(): Int? {
        if (!file.exists()) return null
        return try {
            AtomicFile(file).openRead().use { stream ->
                JsonReader(InputStreamReader(stream, Charsets.UTF_8).buffered()).use { json ->
                    val seen = mutableSetOf<String>()
                    var version: Int? = null
                    json.beginObject()
                    while (json.hasNext()) {
                        val key = json.nextName()
                        if (!seen.add(key)) throw IllegalArgumentException("duplicate key '$key'")
                        if (key == "recordVersion") {
                            version = json.nextInt()
                        } else {
                            json.skipValue()
                        }
                    }
                    json.endObject()
                    version ?: throw IllegalArgumentException("missing recordVersion")
                }
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("malformed activation record at $file", e)
        }
    }

    /** Returns null only when no record file exists (fresh install or v1-only). */
    fun read(): ActivationRecord? {
        if (!file.exists()) return null
        return try {
            AtomicFile(file).openRead().use { stream ->
                parse(InputStreamReader(stream, Charsets.UTF_8))
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("malformed activation record at $file", e)
        }
    }

    fun write(record: ActivationRecord) {
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

    private fun parse(reader: InputStreamReader): ActivationRecord {
        JsonReader(reader.buffered()).use { json ->
            val seen = mutableSetOf<String>()
            var recordVersion: Int? = null
            var state: MigrationRecoveryState? = null
            var migration: MigrationPayload? = null
            var migrationSeen = false
            var generation: GenerationActivation? = null

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
                    "generationActivation" -> generation = parseGeneration(json)
                    else -> throw IllegalArgumentException("unknown field '$key'")
                }
            }
            json.endObject()

            val version = recordVersion ?: throw IllegalArgumentException("missing recordVersion")
            if (version != ActivationRecord.RECORD_VERSION) {
                throw IllegalArgumentException("unsupported recordVersion $version")
            }
            val parsedState = state ?: throw IllegalArgumentException("missing migrationRecoveryState")
            if (!migrationSeen) throw IllegalArgumentException("missing migration")
            val parsedGeneration = generation ?: throw IllegalArgumentException("missing generationActivation")
            return ActivationRecord(parsedState, migration, parsedGeneration)
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

        return MigrationPayload(
            snapshotId = snapshotId ?: throw IllegalArgumentException("missing snapshotId"),
            snapshotSha256 = snapshotSha256 ?: throw IllegalArgumentException("missing snapshotSha256"),
            liveDatabasePath = liveDatabasePath ?: throw IllegalArgumentException("missing liveDatabasePath"),
            workDatabasePath = workDatabasePath ?: throw IllegalArgumentException("missing workDatabasePath"),
            restoreTempPath = restoreTempPath ?: throw IllegalArgumentException("missing restoreTempPath"),
            quarantinePath = quarantinePath ?: throw IllegalArgumentException("missing quarantinePath"),
            stage = stage ?: throw IllegalArgumentException("missing stage"),
        )
    }

    private fun parseGeneration(json: JsonReader): GenerationActivation {
        val seen = mutableSetOf<String>()
        var active: String? = null
        var previous: String? = null
        var candidate: String? = null
        var retiring: String? = null
        var state: GenerationState? = null

        json.beginObject()
        while (json.hasNext()) {
            val key = json.nextName()
            if (!seen.add(key)) throw IllegalArgumentException("duplicate key '$key'")
            when (key) {
                "activeGenerationId" -> active = json.nextString()
                "previousGenerationId" -> previous = nullableString(json)
                "candidateGenerationId" -> candidate = nullableString(json)
                "retiringGenerationId" -> retiring = nullableString(json)
                "state" -> {
                    val raw = json.nextString()
                    state = GenerationState.entries.firstOrNull { it.name == raw }
                        ?: throw IllegalArgumentException("unknown generation state '$raw'")
                }
                else -> throw IllegalArgumentException("unknown generationActivation field '$key'")
            }
        }
        json.endObject()

        return GenerationActivation(
            activeGenerationId = active ?: throw IllegalArgumentException("missing activeGenerationId"),
            previousGenerationId = previous,
            candidateGenerationId = candidate,
            retiringGenerationId = retiring,
            state = state ?: throw IllegalArgumentException("missing generation state"),
        )
    }

    private fun nullableString(json: JsonReader): String? =
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            null
        } else {
            json.nextString()
        }

    private fun validateSha256(raw: String): String {
        if (raw.length != 64 || !raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            throw IllegalArgumentException("invalid snapshotSha256")
        }
        return raw
    }

    private fun normalizeAndValidatePath(raw: String): String {
        if (raw.isEmpty()) throw IllegalArgumentException("empty path")
        if (raw.startsWith("/") || raw.startsWith("\\")) throw IllegalArgumentException("absolute path '$raw'")
        if (Regex("^[A-Za-z]:").containsMatchIn(raw)) throw IllegalArgumentException("drive path '$raw'")
        val normalized = raw.replace('\\', '/')
        if (normalized.split('/').any { it == ".." }) throw IllegalArgumentException("traversal path '$raw'")
        return normalized
    }

    // ---- writer ----

    private fun toJson(record: ActivationRecord): String {
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
        val g = record.generationActivation
        val generationJson = "{" +
            "\"activeGenerationId\":" + jsonString(g.activeGenerationId) + "," +
            "\"previousGenerationId\":" + nullableJson(g.previousGenerationId) + "," +
            "\"candidateGenerationId\":" + nullableJson(g.candidateGenerationId) + "," +
            "\"retiringGenerationId\":" + nullableJson(g.retiringGenerationId) + "," +
            "\"state\":" + jsonString(g.state.name) +
            "}"
        return "{" +
            "\"recordVersion\":${ActivationRecord.RECORD_VERSION}," +
            "\"migrationRecoveryState\":" + jsonString(record.migrationRecoveryState.name) + "," +
            "\"migration\":$migrationJson," +
            "\"generationActivation\":$generationJson" +
            "}"
    }

    private fun nullableJson(s: String?): String = if (s == null) "null" else jsonString(s)

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

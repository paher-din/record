package com.paherdin.record.core.data.backup

import com.paherdin.record.core.data.JsonValue
import com.paherdin.record.core.data.JsonValue.JArray
import com.paherdin.record.core.data.JsonValue.JNumber
import com.paherdin.record.core.data.JsonValue.JObject
import com.paherdin.record.core.data.JsonValue.JString
import com.paherdin.record.core.data.JsonlCodec
import com.paherdin.record.core.data.JsonParseException

/**
 * The manifest written last by the export pipeline (spec 4.2, plan Task 2).
 * Exactly one descriptor per payload entry except `manifest.json` itself; no
 * device identifier. The reader is strict: unknown fields, wrong types, a
 * missing required field, or a duplicate key fail closed.
 */
internal data class ManifestEntry(
    val logicalType: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val recordCount: Long?,
)

internal data class Manifest(
    val formatVersion: Int,
    val databaseSchemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val backupId: String,
    val hashAlgorithm: String,
    val catalogNameKeyVersions: List<Int>,
    val entries: List<ManifestEntry>,
)

internal object ManifestCodec {

    const val FORMAT_VERSION = 1
    const val HASH_ALGORITHM = "SHA-256"

    fun encode(m: Manifest): String = JsonlCodec.write(
        JObject(
            LinkedHashMap<String, JsonValue>().apply {
                put("formatVersion", JNumber(m.formatVersion.toLong()))
                put("databaseSchemaVersion", JNumber(m.databaseSchemaVersion.toLong()))
                put("appVersion", JString(m.appVersion))
                put("createdAt", JNumber(m.createdAt))
                put("backupId", JString(m.backupId))
                put("hashAlgorithm", JString(m.hashAlgorithm))
                put("catalogNameKeyVersions", JArray(m.catalogNameKeyVersions.map { JNumber(it.toLong()) }))
                put(
                    "entries",
                    JArray(m.entries.map { e ->
                        JObject(
                            LinkedHashMap<String, JsonValue>().apply {
                                put("logicalType", JString(e.logicalType))
                                put("path", JString(e.path))
                                put("size", JNumber(e.size))
                                put("sha256", JString(e.sha256))
                                e.recordCount?.let { put("recordCount", JNumber(it)) }
                            },
                        )
                    }),
                )
            },
        ),
    )

    fun decode(json: String): Manifest = decodeValue(JsonlCodec.parse(json))

    fun decodeValue(v: JsonValue): Manifest {
        val o = v as? JObject ?: throw JsonParseException("manifest is not an object")
        val entries = o.fields["entries"] as? JArray ?: throw JsonParseException("manifest missing 'entries'")
        return Manifest(
            formatVersion = int(o, "formatVersion"),
            databaseSchemaVersion = int(o, "databaseSchemaVersion"),
            appVersion = str(o, "appVersion"),
            createdAt = long(o, "createdAt"),
            backupId = str(o, "backupId"),
            hashAlgorithm = str(o, "hashAlgorithm"),
            catalogNameKeyVersions = (o.fields["catalogNameKeyVersions"] as? JArray)?.items
                ?.map { (it as? JNumber)?.value?.toInt() ?: throw JsonParseException("catalog version must be an integer") }
                ?: throw JsonParseException("manifest missing 'catalogNameKeyVersions'"),
            entries = entries.items.map { decodeEntry(it) },
        )
    }

    private fun decodeEntry(v: JsonValue): ManifestEntry {
        val o = v as? JObject ?: throw JsonParseException("manifest entry is not an object")
        val recordCount = when (val rc = o.fields["recordCount"]) {
            null -> null
            is JNumber -> rc.value
            else -> throw JsonParseException("manifest entry 'recordCount' must be an integer")
        }
        return ManifestEntry(
            logicalType = str(o, "logicalType"),
            path = str(o, "path"),
            size = long(o, "size"),
            sha256 = str(o, "sha256"),
            recordCount = recordCount,
        )
    }

    private fun int(o: JObject, key: String): Int = (o.fields[key] as? JNumber)?.value?.toInt()
        ?: throw JsonParseException("missing or non-integer field '$key'")

    private fun long(o: JObject, key: String): Long = (o.fields[key] as? JNumber)?.value
        ?: throw JsonParseException("missing or non-integer field '$key'")

    private fun str(o: JObject, key: String): String = (o.fields[key] as? JString)?.value
        ?: throw JsonParseException("missing or non-string field '$key'")
}

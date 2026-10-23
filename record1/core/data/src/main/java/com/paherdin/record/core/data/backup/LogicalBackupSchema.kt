package com.paherdin.record.core.data.backup

import com.paherdin.record.core.data.JsonValue
import com.paherdin.record.core.data.JsonValue.JArray
import com.paherdin.record.core.data.JsonValue.JBool
import com.paherdin.record.core.data.JsonValue.JNull
import com.paherdin.record.core.data.JsonValue.JNumber
import com.paherdin.record.core.data.JsonValue.JObject
import com.paherdin.record.core.data.JsonValue.JString
import com.paherdin.record.core.data.JsonlCodec
import com.paherdin.record.core.data.JsonParseException
import com.paherdin.record.core.data.room.Account
import com.paherdin.record.core.data.room.AccountEntry
import com.paherdin.record.core.data.room.Attachment
import com.paherdin.record.core.data.room.AttachmentLink
import com.paherdin.record.core.data.room.EntityRef
import com.paherdin.record.core.data.room.Note
import com.paherdin.record.core.data.room.Transaction
import com.paherdin.record.core.data.room.TransactionNoteRelation
import com.paherdin.record.model.EntityType
import com.paherdin.record.model.TransactionType

/**
 * The eight current-schema JSONL datasets and their record shapes (spec 4.2,
 * plan). `encode`/`decode` map room entities to/from strict `JsonValue`;
 * nullable fields (`deletedAt`) are omitted when absent and the reader admits
 * absent-or-null. Unknown fields, unknown enum names, and wrong types are
 * rejected. Pure Kotlin, no `android.*`.
 */
internal enum class LogicalType {
    EntityRef, Account, Transaction, AccountEntry, Note,
    TransactionNoteAssociation, Attachment, AttachmentLink,
}

internal object LogicalBackupSchema {

    /** Deterministic export order; the restore staging import order. */
    val DATASET_ORDER: List<LogicalType> = listOf(
        LogicalType.EntityRef,
        LogicalType.Account,
        LogicalType.Transaction,
        LogicalType.Note,
        LogicalType.AccountEntry,
        LogicalType.TransactionNoteAssociation,
        LogicalType.Attachment,
        LogicalType.AttachmentLink,
    )

    /** Every dataset is a singleton; a repeated singleton `logicalType` is invalid. */
    val SINGLETON_TYPES: Set<LogicalType> = DATASET_ORDER.toSet()

    fun encode(type: LogicalType, record: Any): String = JsonlCodec.write(encodeValue(type, record))

    fun encodeValue(type: LogicalType, record: Any): JsonValue = when (type) {
        LogicalType.EntityRef -> {
            val e = record as EntityRef
            obj(
                "entityType" to JString(e.entityType.name),
                "id" to JString(e.id),
                "createdAt" to JNumber(e.createdAt),
                "updatedAt" to JNumber(e.updatedAt),
                *nullable("deletedAt", e.deletedAt),
            )
        }
        LogicalType.Account -> {
            val a = record as Account
            obj("id" to JString(a.id), "name" to JString(a.name), "currency" to JString(a.currency))
        }
        LogicalType.Transaction -> {
            val t = record as Transaction
            obj("id" to JString(t.id), "type" to JString(t.type.name))
        }
        LogicalType.AccountEntry -> {
            val e = record as AccountEntry
            obj(
                "id" to JString(e.id),
                "transactionId" to JString(e.transactionId),
                "accountId" to JString(e.accountId),
                "amountMinor" to JNumber(e.amountMinor),
            )
        }
        LogicalType.Note -> {
            val n = record as Note
            obj("id" to JString(n.id))
        }
        LogicalType.TransactionNoteAssociation -> {
            val r = record as TransactionNoteRelation
            obj("transactionId" to JString(r.transactionId), "noteId" to JString(r.noteId))
        }
        LogicalType.Attachment -> {
            val a = record as Attachment
            obj(
                "id" to JString(a.id),
                "contentIdentity" to JString(a.contentIdentity),
                *nullable("deletedAt", a.deletedAt),
            )
        }
        LogicalType.AttachmentLink -> {
            val l = record as AttachmentLink
            obj("attachmentId" to JString(l.attachmentId), "ownerEntityRefId" to JString(l.ownerEntityRefId))
        }
    }

    fun decode(type: LogicalType, json: String): Any = decodeValue(type, JsonlCodec.parse(json))

    fun decodeValue(type: LogicalType, v: JsonValue): Any {
        val o = v as? JObject ?: throw JsonParseException("$type record is not an object")
        return when (type) {
            LogicalType.EntityRef -> {
                rejectUnknown(o, setOf("entityType", "id", "createdAt", "updatedAt", "deletedAt"))
                EntityRef(
                    id = str(o, "id"),
                    entityType = enum(o, "entityType", EntityType.entries, EntityType::name),
                    createdAt = long(o, "createdAt"),
                    updatedAt = long(o, "updatedAt"),
                    deletedAt = optionalLong(o, "deletedAt"),
                )
            }
            LogicalType.Account -> {
                rejectUnknown(o, setOf("id", "name", "currency"))
                Account(id = str(o, "id"), name = str(o, "name"), currency = str(o, "currency"))
            }
            LogicalType.Transaction -> {
                rejectUnknown(o, setOf("id", "type"))
                Transaction(id = str(o, "id"), type = enum(o, "type", TransactionType.entries, TransactionType::name))
            }
            LogicalType.AccountEntry -> {
                rejectUnknown(o, setOf("id", "transactionId", "accountId", "amountMinor"))
                AccountEntry(
                    id = str(o, "id"),
                    transactionId = str(o, "transactionId"),
                    accountId = str(o, "accountId"),
                    amountMinor = long(o, "amountMinor"),
                )
            }
            LogicalType.Note -> {
                rejectUnknown(o, setOf("id"))
                Note(id = str(o, "id"))
            }
            LogicalType.TransactionNoteAssociation -> {
                rejectUnknown(o, setOf("transactionId", "noteId"))
                TransactionNoteRelation(transactionId = str(o, "transactionId"), noteId = str(o, "noteId"))
            }
            LogicalType.Attachment -> {
                rejectUnknown(o, setOf("id", "contentIdentity", "deletedAt"))
                Attachment(id = str(o, "id"), contentIdentity = str(o, "contentIdentity"), deletedAt = optionalLong(o, "deletedAt"))
            }
            LogicalType.AttachmentLink -> {
                rejectUnknown(o, setOf("attachmentId", "ownerEntityRefId"))
                AttachmentLink(attachmentId = str(o, "attachmentId"), ownerEntityRefId = str(o, "ownerEntityRefId"))
            }
        }
    }

    private fun rejectUnknown(o: JObject, allowed: Set<String>) {
        for (key in o.fields.keys) {
            if (key !in allowed) throw JsonParseException("unknown field '$key'")
        }
    }

    private fun obj(vararg fields: Pair<String, JsonValue>): JsonValue =
        JObject(LinkedHashMap<String, JsonValue>().apply { fields.forEach { put(it.first, it.second) } })

    private fun nullable(name: String, value: Long?): Array<Pair<String, JsonValue>> =
        if (value == null) emptyArray() else arrayOf(name to JNumber(value))

    private fun str(o: JObject, key: String): String = (o.fields[key] as? JString)?.value
        ?: throw JsonParseException("missing or non-string field '$key'")

    private fun long(o: JObject, key: String): Long = (o.fields[key] as? JNumber)?.value
        ?: throw JsonParseException("missing or non-integer field '$key'")

    private fun optionalLong(o: JObject, key: String): Long? = when (val f = o.fields[key]) {
        null, is JNull -> null
        is JNumber -> f.value
        else -> throw JsonParseException("field '$key' must be an integer or absent")
    }

    private fun <E : Enum<E>> enum(o: JObject, key: String, values: List<E>, name: (E) -> String): E {
        val raw = str(o, key)
        return values.firstOrNull { name(it) == raw }
            ?: throw JsonParseException("unknown $key value '$raw'")
    }
}

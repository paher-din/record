package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Criterion 9(a): the committed v2 schema export declares `databaseVersion =
 * 2` with exactly the eight tables, the six v1 tables byte-compatible with
 * `1.json` (tables, columns, indexes, FK actions unchanged), `Attachment`
 * with a unique `contentIdentity`, and `AttachmentLink` with `NO ACTION` FKs
 * plus the `ownerEntityRefId` index. Same dependency-free JSON parsing as the
 * P1 contract test.
 */
class SchemaV2ContractTest {

    private fun load(name: String): Map<String, Any?> {
        val file = File("schemas/com.paherdin.record.core.data.room.RecordDatabase/$name")
        check(file.isFile) { "schema JSON not found at ${file.absolutePath}" }
        @Suppress("UNCHECKED_CAST")
        return parseJson(file.readText()) as Map<String, Any?>
    }

    private val v2: Map<String, Any?> by lazy { load("2.json") }
    private val v1: Map<String, Any?> by lazy { load("1.json") }

    private fun entities(schema: Map<String, Any?>): List<Map<String, Any?>> =
        (schema["database"] as Map<String, Any?>)["entities"] as List<Map<String, Any?>>

    private fun entity(schema: Map<String, Any?>, table: String): Map<String, Any?> =
        entities(schema).first { it["tableName"] == table }

    private fun columnNames(entity: Map<String, Any?>): Set<String> =
        (entity["fields"] as List<Map<String, Any?>>).map { it["columnName"] as String }.toSet()

    private fun fkActions(entity: Map<String, Any?>): List<Map<String, Any?>> =
        entity["foreignKeys"] as? List<Map<String, Any?>> ?: emptyList()

    private fun indexNames(entity: Map<String, Any?>): Set<String> =
        (entity["indices"] as? List<Map<String, Any?>>)?.map { it["name"] as String }?.toSet() ?: emptySet()

    @Test
    fun databaseVersionIsTwo() {
        assertEquals(2L, (v2["database"] as Map<String, Any?>)["version"])
    }

    @Test
    fun tableSetIsExactlyEightTables() {
        assertEquals(
            setOf(
                "EntityRef", "Transaction", "Account", "AccountEntry", "Note",
                "TransactionNoteRelation", "Attachment", "AttachmentLink",
            ),
            entities(v2).map { it["tableName"] as String }.toSet(),
        )
    }

    @Test
    fun v1TablesAreUnchanged() {
        for (table in listOf("EntityRef", "Transaction", "Account", "AccountEntry", "Note", "TransactionNoteRelation")) {
            val v1Entity = entity(v1, table)
            val v2Entity = entity(v2, table)
            assertEquals("$table columns changed", columnNames(v1Entity), columnNames(v2Entity))
            assertEquals("$table FK actions changed", fkActions(v1Entity), fkActions(v2Entity))
            assertEquals("$table indexes changed", indexNames(v1Entity), indexNames(v2Entity))
        }
    }

    @Test
    fun attachmentHasUniqueContentIdentityAndReliabilityDeletedAt() {
        val attachment = entity(v2, "Attachment")
        assertEquals(setOf("id", "contentIdentity", "deletedAt"), columnNames(attachment))
        val uniqueIndices = (attachment["indices"] as List<Map<String, Any?>>)
        assertTrue(uniqueIndices.any { it["unique"] == true && (it["columnNames"] as List<String>) == listOf("contentIdentity") })
    }

    @Test
    fun attachmentLinkHasNoActionForeignKeysAndOwnerIndex() {
        val link = entity(v2, "AttachmentLink")
        assertEquals(setOf("attachmentId", "ownerEntityRefId"), columnNames(link))
        val fks = fkActions(link)
        assertEquals(2, fks.size)
        for (fk in fks) {
            assertEquals("NO ACTION", fk["onDelete"])
            assertEquals("NO ACTION", fk["onUpdate"])
        }
        assertTrue(indexNames(link).contains("index_AttachmentLink_ownerEntityRefId"))
    }

    @Test
    fun v1JsonStillDeclaresVersionOne() {
        assertEquals(1L, (v1["database"] as Map<String, Any?>)["version"])
    }
}

package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Criterion 7a: the committed exported schema JSON is asserted to be exactly
 * the six spec-4.1 tables with the ADR 0006 contract (integer timestamps and
 * `amountMinor`, text enum columns, `NO ACTION` FKs, the pinned index set) —
 * and to contain no projection table. The test runs on the JVM, where the
 * Android `org.json` stubs throw, so the JSON is parsed with a tiny
 * dependency-free parser.
 */
class SchemaContractTest {

    private val schema: Map<String, Any?> by lazy {
        val file = File("schemas/com.paherdin.record.core.data.room.RecordDatabase/1.json")
        check(file.isFile) { "schema JSON not found at ${file.absolutePath}" }
        @Suppress("UNCHECKED_CAST")
        parseJson(file.readText()) as Map<String, Any?>
    }

    private fun entity(table: String): Map<String, Any?> = (schema["database"] as Map<String, Any?>)
        .let { it["entities"] as List<Map<String, Any?>> }
        .first { it["tableName"] == table }

    private fun columns(table: String): List<Map<String, Any?>> =
        (entity(table)["fields"] as List<Map<String, Any?>>)

    private fun columnNames(table: String): Set<String> = columns(table).map { it["columnName"] as String }.toSet()

    @Test
    fun tableSetIsExactlyTheSixSpecTables() {
        val tables = ((schema["database"] as Map<String, Any?>)["entities"] as List<Map<String, Any?>>)
            .map { it["tableName"] as String }
            .toSet()
        assertEquals(
            setOf("EntityRef", "Transaction", "Account", "AccountEntry", "Note", "TransactionNoteRelation"),
            tables,
        )
    }

    @Test
    fun databaseVersionIsOne() {
        assertEquals(1L, (schema["database"] as Map<String, Any?>)["version"])
    }

    @Test
    fun timestampsAndAmountMinorAreIntegerAffinity() {
        val entityRef = columns("EntityRef").associate { it["columnName"] as String to it["affinity"] }
        assertEquals("INTEGER", entityRef["createdAt"])
        assertEquals("INTEGER", entityRef["updatedAt"])
        assertEquals("INTEGER", entityRef["deletedAt"])
        assertEquals("INTEGER", columns("AccountEntry").first { it["columnName"] == "amountMinor" }["affinity"])
    }

    @Test
    fun enumColumnsAreTextAffinity() {
        assertEquals("TEXT", columns("EntityRef").first { it["columnName"] == "entityType" }["affinity"])
        assertEquals("TEXT", columns("Transaction").first { it["columnName"] == "type" }["affinity"])
    }

    @Test
    fun allForeignKeysAreNoAction() {
        for (table in listOf("Transaction", "Note", "AccountEntry", "TransactionNoteRelation")) {
            for (fk in entity(table)["foreignKeys"] as List<Map<String, Any?>>) {
                assertEquals("NO ACTION", fk["onDelete"])
                assertEquals("NO ACTION", fk["onUpdate"])
            }
        }
    }

    @Test
    fun indexSetMatchesAdr0006() {
        // Tables without indexes omit the key entirely in the schema JSON.
        val indexes = { table: String -> (entity(table)["indices"] as? List<Map<String, Any?>>) ?: emptyList() }
        assertEquals(emptyList<Map<String, Any?>>(), indexes("EntityRef"))
        assertEquals(emptyList<Map<String, Any?>>(), indexes("Transaction"))
        assertEquals(emptyList<Map<String, Any?>>(), indexes("Account"))
        assertEquals(
            setOf(setOf("transactionId"), setOf("accountId")),
            indexes("AccountEntry").map { (it["columnNames"] as List<*>).toSet() }.toSet(),
        )
        assertEquals(emptyList<Map<String, Any?>>(), indexes("Note"))
        assertEquals(
            setOf(setOf("noteId", "transactionId")),
            indexes("TransactionNoteRelation").map { (it["columnNames"] as List<*>).toSet() }.toSet(),
        )
    }

}

// ---- tiny dependency-free JSON parser (subset sufficient for schema JSON) ----
// Shared by the v1 and v2 contract tests (SchemaV2ContractTest).

internal fun parseJson(text: String): Any? {
    val p = Parser(text)
    val value = p.parseValue()
    p.skipWhitespace()
    check(p.peek() == null) { "trailing content at offset ${p.pos}" }
    return value
}

internal class Parser(private val text: String) {
        var pos = 0

        fun peek(): Char? = if (pos < text.length) text[pos] else null

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> { check(text.startsWith("null", pos)); pos += 4; null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            check(text[pos++] == '{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                check(text[pos++] == ':')
                result[key] = parseValue()
                skipWhitespace()
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> error("expected , or } at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            check(text[pos++] == '[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return result }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> error("expected , or ] at $pos")
                }
            }
        }

        private fun parseString(): String {
            check(text[pos++] == '"')
            val sb = StringBuilder()
            while (true) {
                val c = text[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(text[pos++])
                    else -> sb.append(c)
                }
            }
        }

        private fun parseBoolean(): Boolean {
            return if (text.startsWith("true", pos)) { pos += 4; true }
            else { check(text.startsWith("false", pos)); pos += 5; false }
        }

        private fun parseNumber(): Long {
            val start = pos
            while (pos < text.length && (text[pos].isDigit() || text[pos] == '-')) pos++
            return text.substring(start, pos).toLong()
        }
    }

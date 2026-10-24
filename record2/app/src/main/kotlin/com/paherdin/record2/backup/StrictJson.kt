package com.paherdin.record2.backup

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue {
    data class ObjectValue(val fields: LinkedHashMap<String, JsonValue>) : JsonValue
    data class ArrayValue(val items: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class IntegerValue(val value: Long, val raw: String) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

/**
 * Closed JSON reader for the backup container. It rejects duplicate keys, non-integer numbers,
 * raw NUL/CR bytes and nesting deeper than the frozen v1 limit before any row is interpreted.
 */
internal object StrictJson {
    fun decodeObject(
        bytes: ByteArray,
        maxBytes: Int = Int.MAX_VALUE,
        requireFinalLf: Boolean = false,
    ): JsonValue.ObjectValue {
        val value = decodeValue(bytes, maxBytes, requireFinalLf)
        if (value !is JsonValue.ObjectValue) {
            throw BackupException(BackupError.InvalidSource, "JSON root must be an object")
        }
        return value
    }

    fun decodeValue(
        bytes: ByteArray,
        maxBytes: Int = Int.MAX_VALUE,
        requireFinalLf: Boolean = false,
    ): JsonValue {
        if (bytes.size > maxBytes) {
            throw BackupException(BackupError.InvalidSource, "JSON value exceeds its byte limit")
        }
        var body = bytes
        if (requireFinalLf) {
            if (body.isEmpty() || body.last() != '\n'.code.toByte()) {
                throw BackupException(BackupError.InvalidSource, "JSON document must end with one LF")
            }
            body = body.copyOf(body.size - 1)
            if (body.isNotEmpty() && body.last() == '\n'.code.toByte()) {
                throw BackupException(BackupError.InvalidSource, "JSON document has more than one trailing LF")
            }
        }
        return Parser(decodeUtf8(body)).parse()
    }

    fun validateEmbedded(value: String, maxBytes: Int = BackupLimits.MAX_LINE_BYTES) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > maxBytes) {
            throw BackupException(BackupError.InvalidSource, "embedded JSON exceeds its byte limit")
        }
        Parser(decodeUtf8(bytes)).parse()
    }

    fun quote(value: String): String {
        requireValidUnicode(value, "JSON string")
        return buildString(value.length + 2) {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (Character.isISOControl(character)) {
                            append("\\u")
                            append(HEX[character.code shr 12 and 15])
                            append(HEX[character.code shr 8 and 15])
                            append(HEX[character.code shr 4 and 15])
                            append(HEX[character.code and 15])
                        } else {
                            append(character)
                        }
                    }
                }
            }
            append('"')
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            throw BackupException(BackupError.InvalidSource, "UTF-8 BOM is not allowed")
        }
        for (byte in bytes) {
            if (byte == 0x00.toByte() || byte == 0x0D.toByte()) {
                throw BackupException(BackupError.InvalidSource, "raw NUL/CR byte is not allowed")
            }
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw BackupException(BackupError.InvalidSource, "invalid UTF-8", error)
        }
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): JsonValue {
            skipWhitespace()
            val value = value(1)
            skipWhitespace()
            if (index != text.length) fail("trailing characters")
            return value
        }

        private fun value(depth: Int): JsonValue {
            if (index >= text.length) fail("unexpected end")
            return when (text[index]) {
                '{' -> objectValue(depth)
                '[' -> arrayValue(depth)
                '"' -> JsonValue.StringValue(string())
                't' -> {
                    literal("true")
                    JsonValue.BooleanValue(true)
                }
                'f' -> {
                    literal("false")
                    JsonValue.BooleanValue(false)
                }
                'n' -> {
                    literal("null")
                    JsonValue.NullValue
                }
                else -> number()
            }
        }

        private fun objectValue(depth: Int): JsonValue.ObjectValue {
            if (depth > BackupLimits.MAX_JSON_DEPTH) fail("JSON nesting exceeds 32")
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return JsonValue.ObjectValue(fields)
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("object key must be a string")
                val key = string()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val field = value(depth + 1)
                if (fields.putIfAbsent(key, field) != null) fail("duplicate key")
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return JsonValue.ObjectValue(fields)
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arrayValue(depth: Int): JsonValue.ArrayValue {
            if (depth > BackupLimits.MAX_JSON_DEPTH) fail("JSON nesting exceeds 32")
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return JsonValue.ArrayValue(items)
            }
            while (true) {
                skipWhitespace()
                items += value(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return JsonValue.ArrayValue(items)
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return out.toString()
                    character == '\\' -> escape(out)
                    character < ' ' -> fail("unescaped control character")
                    Character.isHighSurrogate(character) -> {
                        if (index >= text.length || !Character.isLowSurrogate(text[index])) {
                            fail("lone high surrogate")
                        }
                        out.append(character).append(text[index++])
                    }
                    Character.isLowSurrogate(character) -> fail("lone low surrogate")
                    else -> out.append(character)
                }
            }
            fail("unterminated string")
        }

        private fun escape(out: StringBuilder) {
            if (index >= text.length) fail("unterminated escape")
            when (val escaped = text[index++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> unicodeEscape(out)
                else -> fail("unsupported escape \\$escaped")
            }
        }

        private fun unicodeEscape(out: StringBuilder) {
            val first = hex4()
            when {
                Character.isHighSurrogate(first) -> {
                    if (index + 1 >= text.length || text[index] != '\\' || text[index + 1] != 'u') {
                        fail("lone high surrogate escape")
                    }
                    index += 2
                    val second = hex4()
                    if (!Character.isLowSurrogate(second)) fail("high surrogate without low surrogate")
                    out.append(first).append(second)
                }
                Character.isLowSurrogate(first) -> fail("lone low surrogate escape")
                else -> out.append(first)
            }
        }

        private fun hex4(): Char {
            if (index + 4 > text.length) fail("truncated unicode escape")
            var value = 0
            repeat(4) {
                val digit = text[index++]
                val nibble = when (digit) {
                    in '0'..'9' -> digit - '0'
                    in 'a'..'f' -> digit - 'a' + 10
                    in 'A'..'F' -> digit - 'A' + 10
                    else -> fail("invalid unicode escape")
                }
                value = value shl 4 or nibble
            }
            return value.toChar()
        }

        private fun number(): JsonValue.IntegerValue {
            val start = index
            if (peek() == '-') index++
            if (index >= text.length) fail("truncated number")
            if (text[index] == '0') {
                index++
                if (index < text.length && text[index] in '0'..'9') fail("leading zero")
            } else if (text[index] in '1'..'9') {
                while (index < text.length && text[index] in '0'..'9') index++
            } else {
                fail("invalid number")
            }
            if (index < text.length && (text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '+')) {
                fail("only integer numbers are allowed")
            }
            val raw = text.substring(start, index)
            val parsed = raw.toLongOrNull() ?: fail("integer outside Long range")
            return JsonValue.IntegerValue(parsed, raw)
        }

        private fun literal(value: String) {
            if (index + value.length > text.length || text.substring(index, index + value.length) != value) {
                fail("invalid literal")
            }
            index += value.length
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index] in JSON_WHITESPACE) index++
        }

        private fun peek(): Char {
            if (index >= text.length) fail("unexpected end")
            return text[index]
        }

        private fun expect(character: Char) {
            if (peek() != character) fail("expected '$character'")
            index++
        }

        private fun fail(message: String): Nothing =
            throw BackupException(BackupError.InvalidSource, "$message at offset $index")
    }

    private val HEX = "0123456789abcdef"
    private val JSON_WHITESPACE = charArrayOf(' ', '\t', '\n', '\r')
}

internal fun JsonValue.ObjectValue.exactFields(expected: List<String>, context: String) {
    val expectedSet = expected.toSet()
    val actual = fields.keys
    if (actual.size != expected.size || actual.toSet() != expectedSet) {
        val missing = expectedSet - actual
        val extra = actual - expectedSet
        throw BackupException(
            BackupError.InvalidSource,
            "$context fields mismatch (missing=$missing, extra=$extra)",
        )
    }
}

internal fun JsonValue.ObjectValue.string(field: String, context: String): String {
    val value = fields[field] ?: throw BackupException(BackupError.InvalidSource, "$context.$field missing")
    return (value as? JsonValue.StringValue)?.value
        ?: throw BackupException(BackupError.InvalidSource, "$context.$field must be a string")
}

internal fun JsonValue.ObjectValue.nullableString(field: String, context: String): String? =
    when (val value = fields[field] ?: throw BackupException(BackupError.InvalidSource, "$context.$field missing")) {
        is JsonValue.StringValue -> value.value
        JsonValue.NullValue -> null
        else -> throw BackupException(BackupError.InvalidSource, "$context.$field must be a string or null")
    }

internal fun JsonValue.ObjectValue.long(field: String, context: String): Long =
    (fields[field] as? JsonValue.IntegerValue)?.value
        ?: throw BackupException(BackupError.InvalidSource, "$context.$field must be an integer")

internal fun JsonValue.ObjectValue.nullableLong(field: String, context: String): Long? =
    when (val value = fields[field] ?: throw BackupException(BackupError.InvalidSource, "$context.$field missing")) {
        is JsonValue.IntegerValue -> value.value
        JsonValue.NullValue -> null
        else -> throw BackupException(BackupError.InvalidSource, "$context.$field must be an integer or null")
    }

internal fun JsonValue.ObjectValue.int(field: String, context: String): Int {
    val value = long(field, context)
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw BackupException(BackupError.InvalidSource, "$context.$field is outside Int range")
    }
    return value.toInt()
}

internal fun JsonValue.ObjectValue.nullableInt(field: String, context: String): Int? {
    val value = nullableLong(field, context) ?: return null
    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        throw BackupException(BackupError.InvalidSource, "$context.$field is outside Int range")
    }
    return value.toInt()
}

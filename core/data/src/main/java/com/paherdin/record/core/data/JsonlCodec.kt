package com.paherdin.record.core.data

/**
 * Strict, fail-closed JSON model and codec (spec 4.2, plan Task 2). The parser
 * rejects duplicate keys, unknown trailing content, malformed numbers, and any
 * input the fixed JSONL record shapes do not admit; `write` emits canonical
 * UTF-8 JSON. Pure Kotlin, no `android.*` dependency.
 */
internal sealed class JsonValue {
    internal data class JString(val value: String) : JsonValue()
    internal data class JNumber(val value: Long) : JsonValue()
    internal data class JBool(val value: Boolean) : JsonValue()
    internal data object JNull : JsonValue()
    internal data class JArray(val items: List<JsonValue>) : JsonValue()
    internal data class JObject(val fields: Map<String, JsonValue>) : JsonValue()
}

internal class JsonParseException(message: String) : IllegalArgumentException(message)

internal object JsonlCodec {

    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val value = p.parseValue()
        p.skipWhitespace()
        if (!p.atEnd()) throw JsonParseException("trailing content at offset ${p.pos}")
        return value
    }

    fun write(value: JsonValue): String = buildString { writeValue(value, this) }

    private fun writeValue(value: JsonValue, out: StringBuilder) {
        when (value) {
            is JsonValue.JString -> writeString(value.value, out)
            is JsonValue.JNumber -> out.append(value.value)
            is JsonValue.JBool -> out.append(value.value)
            is JsonValue.JNull -> out.append("null")
            is JsonValue.JArray -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    writeValue(item, out)
                }
                out.append(']')
            }
            is JsonValue.JObject -> {
                out.append('{')
                value.fields.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(',')
                    writeString(k, out)
                    out.append(':')
                    writeValue(v, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '' -> out.append("\\f")
                else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val c = text[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.JString(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseNumber()
                else -> throw JsonParseException("unexpected character '$c' at offset $pos")
            }
        }

        private fun parseObject(): JsonValue {
            expect('{')
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return JsonValue.JObject(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                if (fields.put(key, value) != null) throw JsonParseException("duplicate key '$key'")
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return JsonValue.JObject(fields)
                    }
                    else -> throw JsonParseException("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun parseArray(): JsonValue {
            expect('[')
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return JsonValue.JArray(items)
            }
            while (true) {
                items.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return JsonValue.JArray(items)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at offset $pos")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                val c = text[pos]
                when {
                    c == '"' -> {
                        pos++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos++
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        when (val e = text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= text.length) throw JsonParseException("short unicode escape")
                                val hex = text.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonParseException("invalid escape '\\$e'")
                        }
                        pos++
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun parseNumber(): JsonValue {
            val start = pos
            if (peek() == '-') pos++
            if (atEnd() || text[pos] !in '0'..'9') throw JsonParseException("invalid number at $start")
            if (text[pos] == '0') {
                pos++
                if (!atEnd() && text[pos] in '0'..'9') throw JsonParseException("leading zero at ${pos - 1}")
            } else {
                while (!atEnd() && text[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E')) {
                throw JsonParseException("non-integer number at $start")
            }
            val raw = text.substring(start, pos)
            val parsed = try {
                raw.toLong()
            } catch (e: NumberFormatException) {
                throw JsonParseException("number out of range at $start")
            }
            return JsonValue.JNumber(parsed)
        }

        private fun parseBool(): JsonValue {
            return when {
                text.startsWith("true", pos) -> {
                    pos += 4
                    JsonValue.JBool(true)
                }
                text.startsWith("false", pos) -> {
                    pos += 5
                    JsonValue.JBool(false)
                }
                else -> throw JsonParseException("invalid literal at offset $pos")
            }
        }

        private fun parseNull(): JsonValue {
            if (!text.startsWith("null", pos)) throw JsonParseException("invalid literal at offset $pos")
            pos += 4
            return JsonValue.JNull
        }

        private fun expect(c: Char) {
            if (atEnd() || text[pos] != c) throw JsonParseException("expected '$c' at offset $pos")
            pos++
        }

        private fun peek(): Char {
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return text[pos]
        }
    }
}

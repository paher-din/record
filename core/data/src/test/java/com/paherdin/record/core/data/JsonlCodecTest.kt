package com.paherdin.record.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonlCodecTest {

    private fun parse(s: String): JsonValue = JsonlCodec.parse(s)

    @Test
    fun objectRoundTrip() {
        val json = """{"a":1,"b":"x","c":[true,null,{"d":-5}]}"""
        assertEquals(json, JsonlCodec.write(parse(json)))
    }

    @Test
    fun emptyObjectAndArray() {
        assertEquals("{}", JsonlCodec.write(parse("{}")))
        assertEquals("[]", JsonlCodec.write(parse("[]")))
    }

    @Test
    fun stringEscapingRoundTrip() {
        val raw = "quote\" backslash\\ newline\n tab\t b\b form cr\r"
        val written = JsonlCodec.write(JsonValue.JString(raw))
        assertEquals(raw, (parse(written) as JsonValue.JString).value)
    }

    @Test
    fun unicodeIsPreserved() {
        val s = "中文/餐/art"
        assertEquals(s, (parse(JsonlCodec.write(JsonValue.JString(s))) as JsonValue.JString).value)
    }

    @Test
    fun unicodeEscapeIsParsed() {
        assertEquals("A", (parse("\"\\u0041\"") as JsonValue.JString).value)
    }

    @Test(expected = JsonParseException::class)
    fun duplicateKeyIsRejected() {
        parse("""{"a":1,"a":2}""")
    }

    @Test(expected = JsonParseException::class)
    fun trailingContentIsRejected() {
        parse("""{"a":1}x""")
    }

    @Test(expected = JsonParseException::class)
    fun leadingZeroNumberIsRejected() {
        parse("""{"a":01}""")
    }

    @Test(expected = JsonParseException::class)
    fun nonIntegerNumberIsRejected() {
        parse("""{"a":1.5}""")
    }

    @Test(expected = JsonParseException::class)
    fun invalidEscapeIsRejected() {
        parse("\"\\q\"")
    }

    @Test(expected = JsonParseException::class)
    fun unterminatedStringIsRejected() {
        parse("\"abc")
    }

    @Test(expected = JsonParseException::class)
    fun missingColonIsRejected() {
        parse("""{"a" 1}""")
    }

    @Test
    fun longValuesRoundTripExactly() {
        val v = 9_223_372_036_854_775_807L
        val n = parse("""{"n":$v}""") as JsonValue.JObject
        assertEquals(v, (n.fields["n"] as JsonValue.JNumber).value)
    }
}

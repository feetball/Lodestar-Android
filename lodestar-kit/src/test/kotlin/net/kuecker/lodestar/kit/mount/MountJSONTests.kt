package net.kuecker.lodestar.kit.mount

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MountJSONTests {
    @Test
    fun testLiteralsNumbersAndStrings() {
        assertEquals(MountJSON.Null, MountJSON.parse("null"))
        assertEquals(MountJSON.Bool(true), MountJSON.parse(" true "))
        assertEquals(MountJSON.Bool(false), MountJSON.parse("false"))
        assertEquals(MountJSON.Number(0.0), MountJSON.parse("0"))
        assertEquals(MountJSON.Number(-12.5), MountJSON.parse("-12.5"))
        assertEquals(MountJSON.Number(100.0), MountJSON.parse("1e2"))
        assertEquals(MountJSON.Number(0.01), MountJSON.parse("1E-2"))
        assertEquals(MountJSON.Number(12.0), MountJSON.parse("1.2e+1"))
        assertEquals(MountJSON.Text("hello"), MountJSON.parse("\"hello\""))
        assertEquals(MountJSON.Text(""), MountJSON.parse("\"\""))
    }

    @Test
    fun testStringEscapes() {
        assertEquals(MountJSON.Text("a\"b\\c/d\b\u000C\n\r\t"), MountJSON.parse("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""))
        assertEquals(MountJSON.Text("\u00E9"), MountJSON.parse("\"\\u00e9\""))
        assertEquals(MountJSON.Text("\uD83D\uDE00"), MountJSON.parse("\"\\ud83d\\ude00\""))
        assertEquals(MountJSON.Text("é😀"), MountJSON.parse("\"é😀\""))
    }

    @Test
    fun testArraysAndObjects() {
        assertEquals(MountJSON.Array(emptyList()), MountJSON.parse("[]"))
        assertEquals(MountJSON.Object(emptyMap()), MountJSON.parse(" { } "))
        val parsed = MountJSON.parse("{\"Value\": [1, 2.5, \"x\", null, true, {\"a\": []}], \"ErrorNumber\": 0}")
        val value = parsed.field("Value") as MountJSON.Array
        assertEquals(6, value.items.size)
        assertEquals(MountJSON.Number(2.5), value.items[1])
        assertEquals(MountJSON.Object(mapOf("a" to MountJSON.Array(emptyList()))), value.items[5])
        assertEquals(MountJSON.Number(0.0), parsed.field("ErrorNumber"))
        // Duplicate keys: the last one wins.
        assertEquals(MountJSON.Number(2.0), MountJSON.parse("{\"a\":1,\"a\":2}").field("a"))
    }

    @Test
    fun testFieldLookupIgnoresAsciiCase() {
        val parsed = MountJSON.parse("{\"clienttransactionid\": 7, \"ServerTransactionID\": 9}")
        assertEquals(MountJSON.Number(7.0), parsed.field("ClientTransactionID"))
        assertEquals(MountJSON.Number(9.0), parsed.field("servertransactionid"))
        assertNull(parsed.field("Value"))
        assertNull(MountJSON.Number(1.0).field("Value"))
    }

    @Test
    fun testMalformedDocumentsAreRejected() {
        val bad = listOf(
            "", " ", "nul", "tru", "True", "NaN", "Infinity", "+1", "01", "1.", ".5", "1e", "1e+", "-", "--1", "0x10", "1 2",
            "\"abc", "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "\"\\ud83d\"", "\"\\ude00\"", "\"\\ud83d\\u0041\"", "[1,]", "[1 2]", "[", "]",
            "{\"a\"}", "{\"a\":}", "{a:1}", "{\"a\":1,}", "{\"a\":1", "{1:2}", "'a'", "[1]x", "{} {}", "1e999",
        )
        for (text in bad) {
            assertFailsWith<MountError.MalformedReply>(text) { MountJSON.parse(text) }
        }
    }

    @Test
    fun testLimits() {
        val deepEnough = "[".repeat(MountJSON.maximumDepth) + "]".repeat(MountJSON.maximumDepth)
        MountJSON.parse(deepEnough)
        val tooDeep = "[".repeat(MountJSON.maximumDepth + 2) + "]".repeat(MountJSON.maximumDepth + 2)
        assertFailsWith<MountError.MalformedReply> { MountJSON.parse(tooDeep) }
        val long = "\"" + "a".repeat(MountJSON.maximumLength) + "\""
        assertFailsWith<MountError.MalformedReply> { MountJSON.parse(long) }
    }
}

package net.kuecker.lodestar.kit.comets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The strict JSON reader that the Small-Body Database parser uses (identical grammar in Kotlin and Swift). */
class CometsJSONTests {
    private fun read(text: String): JSONValue = JSONReader(text).readDocument()

    private fun failure(text: String): JSONFailure = assertFailsWith<JSONReaderException>(text) { read(text) }.failure

    @Test
    fun readsScalars() {
        assertEquals(JSONValue.NullValue, read("null"))
        assertEquals(JSONValue.BooleanValue(true), read("true"))
        assertEquals(JSONValue.BooleanValue(false), read(" \t\r\nfalse\n "))
        assertEquals(JSONValue.NumberValue(0.0), read("0"))
        assertEquals(JSONValue.NumberValue(12.0), read("12"))
        assertEquals(JSONValue.NumberValue(-1500.0), read("-1.5e3"))
        assertEquals(JSONValue.NumberValue(0.25), read("0.25"))
        assertEquals(JSONValue.NumberValue(100.0), read("1E2"))
        assertEquals(JSONValue.NumberValue(100.0), read("1e+2"))
        assertEquals(JSONValue.NumberValue(0.0025), read("2.5E-3"))
        assertEquals(JSONValue.NumberValue(2_461_599.841_466_614), read("2461599.841466614"))
        assertEquals(JSONValue.NumberValue(1e-400), read("1e-400")) // underflows to zero, which is finite
        assertEquals(JSONValue.StringValue(""), read("\"\""))
        assertEquals(JSONValue.StringValue("abc"), read("\"abc\""))
    }

    @Test
    fun readsStringEscapesAndUnicode() {
        assertEquals(JSONValue.StringValue("a\"b\\c/d\b\u000C\n\r\t"), read("\"a\\\"b\\\\c\\/d\\b\\f\\n\\r\\t\""))
        assertEquals(JSONValue.StringValue("A\u00E9"), read("\"\\u0041\\u00e9\""))
        assertEquals(JSONValue.StringValue("\uD83D\uDE00"), read("\"\\ud83d\\ude00\""))
        assertEquals(JSONValue.StringValue("\u00E9\uD83D\uDE00 x"), read("\"\u00E9\uD83D\uDE00 x\""))
        assertEquals(JSONValue.StringValue("\u0000"), read("\"\\u0000\""))
        assertEquals(JSONValue.StringValue("\u007F"), read("\"\u007F\""))
    }

    @Test
    fun readsStructures() {
        assertEquals(JSONValue.ObjectValue(emptyMap()), read("{}"))
        assertEquals(JSONValue.ArrayValue(emptyList()), read("[]"))
        assertEquals(JSONValue.ObjectValue(emptyMap()), read(" { } "))
        assertEquals(
            JSONValue.ArrayValue(listOf(JSONValue.NumberValue(1.0), JSONValue.StringValue("x"), JSONValue.NullValue)),
            read("[1, \"x\" ,null]"),
        )
        val nested = read("{\"a\": {\"b\": [true, {\"c\": 3}]}, \"d\": \"e\"}") as JSONValue.ObjectValue
        assertEquals(setOf("a", "d"), nested.members.keys)
        assertEquals(JSONValue.StringValue("e"), nested.members["d"])
        // A repeated name keeps the last value.
        val repeated = read("{\"k\": 1, \"k\": 2}") as JSONValue.ObjectValue
        assertEquals(JSONValue.NumberValue(2.0), repeated.members["k"])
        // Names may be empty and may contain escapes.
        val odd = read("{\"\": 1, \"a\\nb\": 2}") as JSONValue.ObjectValue
        assertEquals(JSONValue.NumberValue(1.0), odd.members[""])
        assertEquals(JSONValue.NumberValue(2.0), odd.members["a\nb"])
    }

    @Test
    fun refusesInvalidNumbers() {
        for (text in listOf("01", "1.", ".5", "+1", "-", "1e", "1e+", "--1", "0x10", "NaN", "Infinity", "-Infinity", "1e999", "-1e999", "1.5.2", "1,5", "0.", "-.5", "1 2")) {
            assertEquals(JSONFailure.INVALID, failure(text), text)
        }
    }

    @Test
    fun refusesInvalidStrings() {
        for (text in listOf(
            "\"abc", "\"a\nb\"", "\"a\tb\"", "\"\\x\"", "\"\\u12\"", "\"\\u12G4\"", "\"\\ud800\"", "\"\\ud800x\"", "\"\\ud800\\u0041\"",
            "\"\\udc00\"", "\"\\", "\"\\u", "\"\\ud83d\\", "'single'", "\"\\U0041\"", "\"\\u\u0662\u0662\u0662\u0662\"",
        )) {
            assertEquals(JSONFailure.INVALID, failure(text), text)
        }
        assertEquals(JSONFailure.INVALID, failure("\"\uD83D\""))
        assertEquals(JSONFailure.INVALID, failure("\"\uDE00\""))
    }

    @Test
    fun refusesInvalidStructures() {
        for (text in listOf(
            "", " ", "{", "}", "[", "]", "[1,]", "[,1]", "[1 2]", "{\"a\":1,}", "{\"a\" 1}", "{a: 1}", "{'a': 1}", "{\"a\":}",
            "{\"a\":1 \"b\":2}", "{} {}", "[] x", "1 2", "tru", "nul", "True", "falsey", "// c\n{}", "/* c */ {}", "{\"a\": 1} // c",
            "\uFEFF{}", "\u00A0{}", "\u000B{}", "[1;2]", "{\"a\":1;}", "{1: 2}", "{\"a\"}", "[", "{\"a\":[}",
        )) {
            assertEquals(JSONFailure.INVALID, failure(text), text)
        }
    }

    @Test
    fun limitsNesting() {
        assertEquals(32, JSONReader.maximumNesting)
        read("[".repeat(32) + "]".repeat(32))
        read("{\"a\":".repeat(32) + "1" + "}".repeat(32))
        assertEquals(JSONFailure.TOO_DEEP, failure("[".repeat(33) + "]".repeat(33)))
        assertEquals(JSONFailure.TOO_DEEP, failure("{\"a\":".repeat(33) + "1" + "}".repeat(33)))
        // The depth is reported even if the text ends early.
        assertEquals(JSONFailure.TOO_DEEP, failure("[".repeat(40)))
        // Brackets in strings are not nesting.
        read("[\"" + "[".repeat(100) + "\"]")
    }
}

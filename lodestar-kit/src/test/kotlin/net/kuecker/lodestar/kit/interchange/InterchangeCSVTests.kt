package net.kuecker.lodestar.kit.interchange

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The CSV reader and writer, against RFC 4180 (https://www.rfc-editor.org/rfc/rfc4180). */
class InterchangeCSVTests {
    private fun parse(text: String, maximumRecords: Int = 100): CsvTable = InterchangeCSV.parse(text, maximumRecords)

    private fun rows(text: String): List<List<String>> = parse(text).records.map { it.fields }

    @Test
    fun testPlainRecordsWithAnyLineBreak() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows("a,b,c\r\n1,2,3\r\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows("a,b\n1,2"))
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows("a,b\r1,2\r"))
    }

    @Test
    fun testQuotedFieldsKeepDelimitersQuotesAndLineBreaks() {
        val table = parse("name,note\r\n\"Flame, Horsehead\",\"say \"\"hi\"\"\"\r\n\"two\r\nlines\",x\r\n")
        assertEquals(
            listOf(
                listOf("name", "note"),
                listOf("Flame, Horsehead", "say \"hi\""),
                listOf("two\r\nlines", "x"),
            ),
            table.records.map { it.fields },
        )
        assertEquals(listOf(1, 2, 3), table.records.map { it.line })
    }

    @Test
    fun testLineNumbersFollowQuotedLineBreaks() {
        val table = parse("a\n\"x\ny\"\n\nb\n")
        assertEquals(listOf(listOf("a"), listOf("x\ny"), listOf("b")), table.records.map { it.fields })
        assertEquals(listOf(1, 2, 5), table.records.map { it.line })
    }

    @Test
    fun testEmptyLinesAreSkippedButEmptyQuotedFieldsAreNot() {
        assertEquals(listOf(listOf("a"), listOf("b")), rows("a\n\n\nb\n"))
        assertEquals(listOf(listOf("a", "b"), listOf("", ""), listOf("")), rows("a,b\n,\n\"\"\n"))
    }

    @Test
    fun testLenientReadingOfSloppyExports() {
        // A quotation mark inside an unquoted field is kept, as Telescopius writes arcseconds.
        assertEquals(listOf(listOf("a", "03h 46' 24\"\"", "x")), rows("a,03h 46' 24\"\",x\n"))
        // Text after a closing quotation mark is appended.
        assertEquals(listOf(listOf("abcd", "e")), rows("\"ab\"cd,e\n"))
        // A byte order mark is dropped.
        assertEquals(listOf(listOf("a", "b")), rows("﻿a,b\n"))
    }

    @Test
    fun testDelimiterIsDetectedFromTheFirstLine() {
        assertEquals(';', parse("a;b;c\n1;2;3\n").delimiter)
        assertEquals('\t', parse("a\tb\n1\t2\n").delimiter)
        assertEquals(',', parse("a,b;c\n").delimiter)
        assertEquals(',', parse("\"a;b;c\",d\n").delimiter)
        assertEquals(listOf(listOf("a", "b"), listOf("1,5", "2")), rows("a;b\n1,5;2\n"))
    }

    @Test
    fun testRecordLimitMarksTheTableTruncated() {
        val text = "h\n1\n2\n3\n4\n"
        val table = parse(text, maximumRecords = 3)
        assertEquals(3, table.records.size)
        assertTrue(table.isTruncated)
        assertFalse(parse(text, maximumRecords = 5).isTruncated)
        assertFalse(parse("h\n1\n2\n\n\n", maximumRecords = 3).isTruncated)
    }

    @Test
    fun testAnUnclosedQuoteIsAnError() {
        val error = assertFailsWith<InterchangeError.UnterminatedQuote> { parse("a,b\n1,\"oops\n2,3\n") }
        assertEquals(InterchangeError.UnterminatedQuote(2), error)
    }

    @Test
    fun testEmptyAndBlankInputGiveNoRecords() {
        assertEquals(emptyList(), rows(""))
        assertEquals(emptyList(), rows("\n\r\n"))
    }

    @Test
    fun testWritingQuotesOnlyWhatNeedsIt() {
        assertEquals("plain", InterchangeCSV.field("plain"))
        assertEquals("\"a,b\"", InterchangeCSV.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", InterchangeCSV.field("say \"hi\""))
        assertEquals("\"two\nlines\"", InterchangeCSV.field("two\nlines"))
        assertEquals("a,\"b,c\"\r\n1,\r\n", InterchangeCSV.encode(listOf(listOf("a", "b,c"), listOf("1", ""))))
    }

    @Test
    fun testWrittenRowsReadBackTheSame() {
        val original = listOf(
            listOf("Name", "Note"),
            listOf("M 31, \"Andromeda\"", "line 1\nline 2"),
            listOf("", "ünïcode ★"),
            listOf("x", ""),
        )
        assertEquals(original, rows(InterchangeCSV.encode(original)))
    }

    @Test
    fun testFormulaGuard() {
        for (text in listOf("=1+1", "+1", "-1", "@SUM(A1)", "\tx", "\rx")) {
            assertEquals("'$text", InterchangeCSV.guarded(text))
            assertEquals(text, InterchangeCSV.unguarded(InterchangeCSV.guarded(text)))
        }
        assertEquals("M 31", InterchangeCSV.guarded("M 31"))
        assertEquals("", InterchangeCSV.guarded(""))
        assertEquals("'plain", InterchangeCSV.unguarded("'plain"))
        assertEquals("'", InterchangeCSV.unguarded("'"))
    }
}

/** The number, date and text helpers behind the writers. */
class InterchangeTextTests {
    @Test
    fun testDecimalAndFixedNumbers() {
        assertEquals("-5.391", InterchangeText.decimal(-5.391, 5))
        assertEquals("12", InterchangeText.decimal(12.0, 3))
        assertEquals("0.5", InterchangeText.decimal(0.5, 1))
        assertEquals("0", InterchangeText.decimal(-0.0001, 2))
        assertEquals("0", InterchangeText.decimal(Double.NaN, 2))
        assertEquals("-5.39100", InterchangeText.fixed(-5.391, 5))
        assertEquals("83.82208", InterchangeText.fixed(83.822083, 5))
        assertEquals("0.00000", InterchangeText.fixed(-0.000001, 5))
        assertEquals("0.00", InterchangeText.fixed(Double.POSITIVE_INFINITY, 2))
    }

    @Test
    fun testDatesFromEpochDays() {
        assertEquals("1970-01-01", InterchangeText.dateText(0))
        assertEquals("1969-12-31", InterchangeText.dateText(-1))
        assertEquals("2000-02-29", InterchangeText.dateText(11_016))
        assertEquals("2026-11-18", InterchangeText.dateText(20_775))
        // 2026-11-18T22:15:30Z, the number Python's calendar.timegm gives.
        assertEquals("2026-11-18T22:15:30Z", InterchangeText.dateTimeText(1_795_040_130))
        assertEquals("1969-12-31T23:59:59Z", InterchangeText.dateTimeText(-1))
        assertEquals("2000-02-29T00:00:00Z", InterchangeText.dateTimeText(951_782_400))
    }

    @Test
    fun testXMLEscapingDropsWhatXMLCannotCarry() {
        assertEquals("a &amp; b &lt; c &gt; &quot;d&quot; &apos;e&apos;", InterchangeText.xml("a & b < c > \"d\" 'e'"))
        assertEquals("okx\ny\tz", InterchangeText.xml("ok\u0000\u0001\u000B￾x\ny\tz"))
        assertEquals("★ 🌟", InterchangeText.xml("★ 🌟"))
    }

    @Test
    fun testSingleLineAndMultiLineCleaning() {
        assertEquals("a b c", InterchangeText.singleLine("  a \t b\n\nc  ", 100))
        assertEquals("abc", InterchangeText.singleLine("abcdef", 3))
        assertEquals("ab", InterchangeText.singleLine("ab cd", 3))
        assertEquals("ab c", InterchangeText.singleLine("a​b\u0007c", 100))
        assertEquals("a\nb\nc\nd e", InterchangeText.multiLine("a\r\nb\rc\nd\u0007\te", 100))
        assertEquals("abcd", InterchangeText.multiLine("abcdef", 4))
    }
}

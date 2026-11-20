package net.kuecker.lodestar.kit.interchange

/**
 * One record of a CSV table.
 *
 * @property line The line the record starts on, from 1 (a quoted field may span lines).
 * @property fields The fields in order.
 */
data class CsvRecord(val line: Int, val fields: List<String>)

/**
 * The records of a CSV table.
 *
 * @property records The records, the header first, without the empty lines.
 * @property isTruncated `true` when more records followed the [InterchangeCSV.parse] limit.
 * @property delimiter The delimiter that was used.
 */
data class CsvTable(val records: List<CsvRecord>, val isTruncated: Boolean, val delimiter: Char)

/**
 * A reader and writer for comma separated values after RFC 4180 (https://www.rfc-editor.org/rfc/rfc4180): fields may be
 * quoted, a doubled quotation mark inside a quoted field is one quotation mark, and a quoted field may contain
 * delimiters and line breaks.
 *
 * The reader is lenient where exporters are sloppy: it accepts CRLF, LF and CR line breaks, a byte order mark, a
 * semicolon or tab as the delimiter, a quotation mark inside an unquoted field (kept as it is) and text after a
 * closing quotation mark (appended). It never traps; the only error is a quoted field that is never closed.
 */
internal object InterchangeCSV {
    private const val quote = '"'
    private const val byteOrderMark = '﻿'

    /** The delimiters [detectDelimiter] chooses between, the comma first. */
    private val delimiters = charArrayOf(',', ';', '\t')

    /**
     * The delimiter of a table: the one of comma, semicolon and tab that occurs most often outside quotation marks in the
     * first line, the comma on a tie or when none occurs.
     */
    fun detectDelimiter(text: String): Char {
        val counts = IntArray(delimiters.size)
        var inQuotes = false
        for (character in text) {
            if (character == quote) {
                inQuotes = !inQuotes
            } else if (!inQuotes) {
                if (character == '\n' || character == '\r') break
                val index = delimiters.indexOf(character)
                if (index >= 0) counts[index] += 1
            }
        }
        var best = 0
        for (index in 1 until delimiters.size) {
            if (counts[index] > counts[best]) best = index
        }
        return delimiters[best]
    }

    /**
     * Reads the records of [text] (the delimiter is detected).
     *
     * Empty lines are skipped. After [maximumRecords] records (the header counts) the rest is left unread and the table
     * is marked truncated.
     *
     * @throws InterchangeError.UnterminatedQuote when a quoted field is not closed before the end of the text.
     */
    @Throws(InterchangeError::class)
    fun parse(text: String, maximumRecords: Int): CsvTable {
        val delimiter = detectDelimiter(text)
        val records = ArrayList<CsvRecord>()
        var fields = ArrayList<String>()
        val field = StringBuilder()
        var line = 1
        var recordLine = 1
        var isQuoted = false
        var hasContent = false
        var isTruncated = false
        var index = 0
        val length = text.length
        if (length > 0 && text[0] == byteOrderMark) index = 1

        fun finishField() {
            fields.add(field.toString())
            field.setLength(0)
        }

        fun finishRecord() {
            finishField()
            val isEmpty = fields.size == 1 && fields[0].isEmpty() && !hasContent
            if (!isEmpty) records.add(CsvRecord(recordLine, fields))
            fields = ArrayList()
            hasContent = false
        }

        while (index < length) {
            val character = text[index]
            // Reaching the limit always happens between records, so any further text that is not a line break is a record.
            if (records.size >= maximumRecords && character != '\n' && character != '\r') {
                isTruncated = true
                break
            }
            if (isQuoted) {
                if (character == quote) {
                    if (index + 1 < length && text[index + 1] == quote) {
                        field.append(quote)
                        index += 1
                    } else {
                        isQuoted = false
                    }
                } else {
                    if (character == '\n') line += 1
                    field.append(character)
                }
            } else if (character == quote && field.isEmpty()) {
                isQuoted = true
                hasContent = true
            } else if (character == delimiter) {
                finishField()
                hasContent = true
            } else if (character == '\n' || character == '\r') {
                if (character == '\r' && index + 1 < length && text[index + 1] == '\n') index += 1
                finishRecord()
                line += 1
                recordLine = line
            } else {
                field.append(character)
                hasContent = true
            }
            index += 1
        }
        if (isQuoted) throw InterchangeError.UnterminatedQuote(recordLine)
        if (!isTruncated && (field.isNotEmpty() || fields.isNotEmpty() || hasContent)) finishRecord()
        return CsvTable(records, isTruncated, delimiter)
    }

    /** One field as CSV text: quoted when it has a delimiter, a quotation mark or a line break, with quotation marks doubled. */
    fun field(value: String): String {
        val needsQuotes = value.any { it == ',' || it == quote || it == '\n' || it == '\r' }
        return if (needsQuotes) quote + value.replace("\"", "\"\"") + quote else value
    }

    /** The rows as CSV text with CRLF line breaks, the last row ended too (RFC 4180 section 2). */
    fun encode(rows: List<List<String>>): String {
        val result = StringBuilder()
        for (row in rows) {
            row.forEachIndexed { column, value ->
                if (column > 0) result.append(',')
                result.append(field(value))
            }
            result.append("\r\n")
        }
        return result.toString()
    }

    /**
     * [value] made safe to open in a spreadsheet: text that starts with `=`, `+`, `-`, `@`, a tab or a carriage return would
     * be run as a formula, so it gets a leading apostrophe (OWASP "CSV injection"). [unguarded] undoes it.
     */
    fun guarded(value: String): String {
        val first = value.firstOrNull() ?: return value
        return if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r') "'$value" else value
    }

    /** [value] without the apostrophe [guarded] put in front of a formula character. */
    fun unguarded(value: String): String {
        if (value.length >= 2 && value[0] == '\'') {
            val second = value[1]
            if (second == '=' || second == '+' || second == '-' || second == '@' || second == '\t' || second == '\r') {
                return value.substring(1)
            }
        }
        return value
    }
}

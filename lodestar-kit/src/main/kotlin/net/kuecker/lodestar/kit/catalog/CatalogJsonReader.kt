package net.kuecker.lodestar.kit.catalog

// A strict, allocation-light JSON reader for the four catalog data files. Kotlin-only: Swift decodes with
// `JSONDecoder` and `Decodable` types; this reader gives the same results without building a tree.
//
// What it reproduces from `JSONDecoder`:
// - the grammar is RFC 8259 and nothing else: no NaN or Infinity, no leading zeros, no `+1`, `.5` or `1.`,
//   no comments, no trailing commas, no single quotes, no raw control characters in strings, no trailing
//   content after the top-level value; lone surrogate escapes and malformed UTF-8 are errors;
// - containers may be nested 512 levels deep, not more;
// - a syntax error anywhere makes the whole file unusable, even after valid records;
// - member types are checked like `decodeIfPresent`: absent and `null` are the same, a wrong type is an
//   error of the record; an `Int` accepts any number that is exactly an integer, a `Double` any number
//   that is finite; a repeated key is read again and the last occurrence counts;
// - unknown members are skipped (and still validated).

/** Thrown for anything that makes a data file unusable as a whole; the text says why. */
internal class CatalogJsonException(message: String) : Exception(message, null, false, false)

/**
 * The result of reading a data file: its `schema` member and its records.
 *
 * @property schema The `schema` member, `null` if absent or `null`.
 * @property records One entry per element of the records array; `null` for an element of the wrong shape
 * (Swift's `Lossy` elements).
 */
internal class DecodedFile<R>(val schema: Long?, val records: List<R?>)

/**
 * Reads one data file from its UTF-8 bytes. An instance reads one document, once, on one thread.
 */
internal class CatalogJsonReader(private val buffer: ByteArray) {
    private var position = 0
    private var depth = 0
    private val limit = buffer.size

    // The last number scanned by [scanNumber]: its bytes are buffer[numberStart, numberEnd), and its digits
    // were summed up on the way, so that converting it needs no second look at the bytes.
    private var numberStart = 0
    private var numberEnd = 0
    private var numberIsInteger = true
    private var numberNegative = false

    /** The first 18 significant digits of the number (integer and fraction digits, without leading zeros). */
    private var numberMantissa = 0L

    /** Whether the number has more significant digits than [numberMantissa] holds. */
    private var numberTooLong = false
    private var numberFractionDigits = 0
    private var numberExponent = 0

    // The outcome of the last typed read.
    private var status = OK

    /**
     * The fields of the current record whose last occurrence had the wrong type, one bit per field (the
     * record decoders define the bits). The record is of the wrong shape if any bit is set.
     */
    var failedMask: Int = 0

    // MARK: Documents

    /**
     * Reads a document of the form `{"schema": <integer>, "<recordsKey>": [<record>, ...], ...}`.
     *
     * @param recordsKey The member that holds the records.
     * @param readRecord Reads one array element completely (see [beginRecord]) and returns the record, or
     * `null` if the element has the wrong shape.
     * @throws CatalogJsonException for invalid JSON, a document that is not an object, a `schema` that is
     * not an integer, or a missing or mistyped records member.
     */
    fun <R> readFile(recordsKey: String, readRecord: (CatalogJsonReader) -> R?): DecodedFile<R> {
        if (limit - position >= 3 && buffer[0] == 0xEF.toByte() && buffer[1] == 0xBB.toByte() &&
            buffer[2] == 0xBF.toByte()
        ) {
            position = 3
        }
        skipWhitespace()
        if (position >= limit) fail("the file is empty")
        if (buffer[position] != OPEN_BRACE) fail("the top-level value is not an object")
        enter()
        position += 1
        var schema: Long? = null
        var schemaFailed = false
        var records: ArrayList<R?>? = null
        var recordsFailed = false
        var first = true
        while (true) {
            val key = nextKeyName(first)
            first = false
            if (key == null) break
            when (key) {
                "schema" -> {
                    status = OK
                    val value = readLongValue()
                    schemaFailed = status == MISMATCH
                    schema = if (status == OK) value else null
                }
                recordsKey -> {
                    skipWhitespace()
                    if (position < limit && buffer[position] == OPEN_BRACKET) {
                        records = readRecords(readRecord)
                        recordsFailed = false
                    } else {
                        records = null
                        recordsFailed = skipOrNull() == MISMATCH
                    }
                }
                else -> skipValue()
            }
        }
        skipWhitespace()
        if (position != limit) fail("unexpected data after the top-level value")
        if (schemaFailed) fail("schema: expected an integer")
        if (records == null) {
            fail(if (recordsFailed) "$recordsKey: expected an array" else "the key \"$recordsKey\" is missing")
        }
        return DecodedFile(schema, records)
    }

    private fun <R> readRecords(readRecord: (CatalogJsonReader) -> R?): ArrayList<R?> {
        enter()
        position += 1
        val records = ArrayList<R?>()
        skipWhitespace()
        if (position < limit && buffer[position] == CLOSE_BRACKET) {
            position += 1
            depth -= 1
            return records
        }
        while (true) {
            records.add(readRecord(this))
            skipWhitespace()
            if (position >= limit) fail("unexpected end of the data")
            val next = buffer[position]
            position += 1
            if (next == COMMA) continue
            if (next == CLOSE_BRACKET) break
            position -= 1
            fail("expected ',' or ']'")
        }
        depth -= 1
        return records
    }

    /** Skips the next value, returning [MISMATCH] for anything but `null` (which is [NULL]). */
    private fun skipOrNull(): Int {
        skipWhitespace()
        if (position < limit && buffer[position] == LETTER_N) {
            skipValue()
            return NULL
        }
        skipValue()
        return MISMATCH
    }

    // MARK: Records

    /**
     * Starts reading the next value as a record: when it is an object, consumes its `{`, clears
     * [failedMask] and returns `true`; otherwise (a number, a string, `null`, an array, ...) skips the
     * value, which makes the record of the wrong shape, and returns `false`.
     */
    fun beginRecord(): Boolean {
        skipWhitespace()
        if (position < limit && buffer[position] == OPEN_BRACE) {
            enter()
            position += 1
            failedMask = 0
            return true
        }
        skipValue()
        return false
    }

    /**
     * The next member of the object being read: its key as a [keyCode], or [END_OF_OBJECT] after the last
     * one (which consumes the closing brace). After a key the reader is positioned at the member's value,
     * which the caller has to read with one of the typed readers or [skipValue].
     *
     * @param first `true` for the first call on an object.
     */
    fun nextKey(first: Boolean): Long {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (first && buffer[position] == CLOSE_BRACE) {
            position += 1
            depth -= 1
            return END_OF_OBJECT
        }
        if (!first) {
            val next = buffer[position]
            position += 1
            if (next == CLOSE_BRACE) {
                depth -= 1
                return END_OF_OBJECT
            }
            if (next != COMMA) {
                position -= 1
                fail("expected ',' or '}'")
            }
            skipWhitespace()
            if (position >= limit) fail("unexpected end of the data")
        }
        if (buffer[position] != QUOTE) fail("expected a string as the name of a member")
        val code = readKeyCode()
        expectColon()
        return code
    }

    /** Like [nextKey] but with the key as a string, `null` after the last member. */
    private fun nextKeyName(first: Boolean): String? {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (first && buffer[position] == CLOSE_BRACE) {
            position += 1
            depth -= 1
            return null
        }
        if (!first) {
            val next = buffer[position]
            position += 1
            if (next == CLOSE_BRACE) {
                depth -= 1
                return null
            }
            if (next != COMMA) {
                position -= 1
                fail("expected ',' or '}'")
            }
            skipWhitespace()
            if (position >= limit) fail("unexpected end of the data")
        }
        if (buffer[position] != QUOTE) fail("expected a string as the name of a member")
        val name = scanString(wantValue = true)
        expectColon()
        return name
    }

    private fun expectColon() {
        skipWhitespace()
        if (position >= limit || buffer[position] != COLON) fail("expected ':' after the name of a member")
        position += 1
    }

    // MARK: Typed values

    /** Marks the field [bit] of the record as failed or good, according to the last typed read. */
    @Suppress("NOTHING_TO_INLINE") // a call costs more than the body in an interpreter, and this runs for every member
    private inline fun mark(bit: Int) {
        failedMask = if (status == MISMATCH) failedMask or bit else failedMask and bit.inv()
    }

    /**
     * Reads an `Int?` member: the integer, or `null` if the value is `null`. A value that is not a number
     * that is exactly an integer in the 64-bit range fails the field [bit].
     */
    fun long(bit: Int): Long? {
        val value = readLongValue()
        mark(bit)
        return if (status == OK) value else null
    }

    /** Reads a `Double?` member: the number, or `null` if the value is `null`; other values fail the field [bit]. */
    fun double(bit: Int): Double? {
        val value = readDoubleValue()
        mark(bit)
        return if (status == OK) value else null
    }

    /** Reads a `String?` member: the string, or `null` if the value is `null`; other values fail the field [bit]. */
    fun string(bit: Int): String? {
        val value = readStringValue()
        mark(bit)
        return if (status == OK) value else null
    }

    /**
     * Reads an `[Int]?` member. Anything but an array of integers fails the field [bit]; `null` is `null`.
     */
    fun longList(bit: Int): List<Long>? {
        var result: ArrayList<Long>? = null
        var bad = false
        status = NULL
        if (beginArrayOrNull()) {
            val list = ArrayList<Long>(4)
            status = OK
            var first = true
            while (nextElement(first)) {
                first = false
                val element = readLongValue()
                if (status == OK) list.add(element) else bad = true
                status = OK
            }
            result = list
        }
        if (bad) status = MISMATCH
        mark(bit)
        return if (status == OK) result else null
    }

    /**
     * Reads a `[String]?` member. Anything but an array of strings fails the field [bit]; `null` is `null`.
     */
    fun stringList(bit: Int): List<String>? {
        var result: ArrayList<String>? = null
        var bad = false
        status = NULL
        if (beginArrayOrNull()) {
            val list = ArrayList<String>(4)
            status = OK
            var first = true
            while (nextElement(first)) {
                first = false
                val element = readStringValue()
                if (status == OK) list.add(element) else bad = true
                status = OK
            }
            result = list
        }
        if (bad) status = MISMATCH
        mark(bit)
        return if (status == OK) result else null
    }

    /**
     * Reads a `[Double]?` member. Anything but an array of numbers fails the field [bit]; `null` is `null`.
     */
    fun doubleList(bit: Int): DoubleArray? {
        status = NULL
        var result: DoubleArray? = null
        if (beginArrayOrNull()) {
            val values = readDoubleElements()
            if (values == null) status = MISMATCH else {
                status = OK
                result = values
            }
        }
        mark(bit)
        return if (status == OK) result else null
    }

    /**
     * Reads a `[Lossy<[[Double]]>]?` member (the polylines and rings of the constellation and Milky Way
     * files): a list with one entry per element, `null` for an element that is not an array of arrays of
     * numbers. Anything but an array fails the field [bit]; `null` is `null`.
     */
    fun ringList(bit: Int): List<List<DoubleArray>?>? {
        status = NULL
        var result: ArrayList<List<DoubleArray>?>? = null
        if (beginArrayOrNull()) {
            val list = ArrayList<List<DoubleArray>?>()
            status = OK
            var first = true
            while (nextElement(first)) {
                first = false
                list.add(readRing())
            }
            result = list
        }
        mark(bit)
        return if (status == OK) result else null
    }

    /**
     * If the next value is an array, consumes its `[` and returns `true`; a `null` is consumed and gives
     * `false` with [status] `NULL`; anything else is skipped and gives `false` with [status] `MISMATCH`.
     */
    private fun beginArrayOrNull(): Boolean {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (buffer[position] == OPEN_BRACKET) {
            enter()
            position += 1
            return true
        }
        status = skipOrNull()
        return false
    }

    /**
     * Moves to the next element of the array being read: `true` if there is one (the reader is then
     * positioned at it), `false` after the last (which consumes the closing bracket).
     */
    private fun nextElement(first: Boolean): Boolean {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (first) {
            if (buffer[position] == CLOSE_BRACKET) {
                position += 1
                depth -= 1
                return false
            }
            return true
        }
        val next = buffer[position]
        position += 1
        if (next == CLOSE_BRACKET) {
            depth -= 1
            return false
        }
        if (next != COMMA) {
            position -= 1
            fail("expected ',' or ']'")
        }
        return true
    }

    /** The elements of the array being read (already begun) as numbers, `null` if any is not a finite number. */
    private fun readDoubleElements(): DoubleArray? {
        var values = DoubleArray(2)
        var count = 0
        var bad = false
        var first = true
        while (nextElement(first)) {
            first = false
            val value = readDoubleValue()
            if (status == OK) {
                if (count == values.size) values = values.copyOf(count * 2)
                values[count] = value
                count += 1
            } else {
                bad = true
            }
        }
        status = OK
        return if (bad) null else if (count == values.size) values else values.copyOf(count)
    }

    /** One `[[Double]]`: a list of vertices, `null` if the value is not an array of arrays of numbers. */
    private fun readRing(): List<DoubleArray>? {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (buffer[position] != OPEN_BRACKET) {
            skipValue()
            return null
        }
        enter()
        position += 1
        val vertices = ArrayList<DoubleArray>()
        var bad = false
        var first = true
        while (nextElement(first)) {
            first = false
            skipWhitespace()
            if (position < limit && buffer[position] == OPEN_BRACKET) {
                enter()
                position += 1
                val vertex = readDoubleElements()
                if (vertex == null) bad = true else vertices.add(vertex)
            } else {
                skipValue()
                bad = true
            }
        }
        return if (bad) null else vertices
    }

    // MARK: Scalar values

    /** Reads an `Int?`: [status] is `OK` with the value, `NULL`, or `MISMATCH` (the value is skipped). */
    private fun readLongValue(): Long {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        val c = buffer[position]
        if (c == MINUS || (c >= DIGIT_0 && c <= DIGIT_9)) {
            scanNumber()
            val value = numberAsLong()
            if (value == null) {
                status = MISMATCH
                return 0
            }
            status = OK
            return value
        }
        status = skipOrNull()
        return 0
    }

    /** Reads a `Double?`: [status] is `OK` with a finite value, `NULL`, or `MISMATCH` (the value is skipped). */
    private fun readDoubleValue(): Double {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        val c = buffer[position]
        if (c == MINUS || (c >= DIGIT_0 && c <= DIGIT_9)) {
            scanNumber()
            val value = numberAsDouble()
            if (!value.isFinite()) {
                status = MISMATCH
                return 0.0
            }
            status = OK
            return value
        }
        status = skipOrNull()
        return 0.0
    }

    /** Reads a `String?`: [status] is `OK` with the string, `NULL`, or `MISMATCH` (the value is skipped). */
    private fun readStringValue(): String {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        if (buffer[position] == QUOTE) {
            status = OK
            return scanString(wantValue = true)
        }
        status = skipOrNull()
        return ""
    }

    // MARK: Skipping

    /** Skips the next value, checking that it is valid JSON. */
    fun skipValue() {
        skipWhitespace()
        if (position >= limit) fail("unexpected end of the data")
        when (buffer[position]) {
            OPEN_BRACE -> skipObject()
            OPEN_BRACKET -> skipArray()
            QUOTE -> scanString(wantValue = false)
            LETTER_T -> literal(TRUE)
            LETTER_F -> literal(FALSE)
            LETTER_N -> literal(NULL_TEXT)
            else -> {
                val c = buffer[position]
                if (c == MINUS || (c >= DIGIT_0 && c <= DIGIT_9)) scanNumber() else fail("unexpected character")
            }
        }
    }

    private fun skipObject() {
        enter()
        position += 1
        skipWhitespace()
        if (position < limit && buffer[position] == CLOSE_BRACE) {
            position += 1
            depth -= 1
            return
        }
        while (true) {
            skipWhitespace()
            if (position >= limit || buffer[position] != QUOTE) fail("expected a string as the name of a member")
            scanString(wantValue = false)
            expectColon()
            skipValue()
            skipWhitespace()
            if (position >= limit) fail("unexpected end of the data")
            val next = buffer[position]
            position += 1
            if (next == COMMA) continue
            if (next == CLOSE_BRACE) break
            position -= 1
            fail("expected ',' or '}'")
        }
        depth -= 1
    }

    private fun skipArray() {
        enter()
        position += 1
        skipWhitespace()
        if (position < limit && buffer[position] == CLOSE_BRACKET) {
            position += 1
            depth -= 1
            return
        }
        while (true) {
            skipValue()
            skipWhitespace()
            if (position >= limit) fail("unexpected end of the data")
            val next = buffer[position]
            position += 1
            if (next == COMMA) continue
            if (next == CLOSE_BRACKET) break
            position -= 1
            fail("expected ',' or ']'")
        }
        depth -= 1
    }

    private fun literal(text: ByteArray) {
        if (limit - position < text.size) fail("invalid literal")
        for (index in text.indices) {
            if (buffer[position + index] != text[index]) fail("invalid literal")
        }
        position += text.size
    }

    private fun enter() {
        if (depth >= MAXIMUM_DEPTH) fail("the data is nested more than $MAXIMUM_DEPTH levels deep")
        depth += 1
    }

    @Suppress("NOTHING_TO_INLINE") // a call costs more than the body in an interpreter, and this runs for every token
    private inline fun skipWhitespace() {
        val buffer = buffer
        var p = position
        while (p < limit) {
            val c = buffer[p]
            if (c == SPACE || c == NEWLINE || c == CARRIAGE_RETURN || c == TAB) p += 1 else break
        }
        position = p
    }

    private fun fail(message: String): Nothing =
        throw CatalogJsonException("The data is not valid JSON: $message (at byte $position).")

    // MARK: Keys

    /**
     * Reads the name of a member (the reader is at its opening quote) as a [keyCode]: the packed bytes
     * when it is one to eight printable ASCII characters, [OTHER_KEY] for any other name.
     */
    private fun readKeyCode(): Long {
        val buffer = buffer
        var p = position + 1
        var code = 0L
        var length = 0
        while (p < limit) {
            val b = buffer[p].toInt()
            if (b == QUOTE.toInt()) {
                position = p + 1
                return if (length in 1..8) code else OTHER_KEY
            }
            if (b < 0x20 || b >= 0x7F || b == BACKSLASH.toInt()) break
            if (length < 8) code = (code shl 8) or b.toLong()
            length += 1
            p += 1
        }
        return keyCode(scanString(wantValue = true))
    }

    // MARK: Strings

    /**
     * Reads the string at the reader's position (an opening quote) and validates it; returns it only if
     * [wantValue], else the empty string.
     */
    private fun scanString(wantValue: Boolean): String {
        val buffer = buffer
        val limit = limit
        val classes = BYTE_CLASSES
        position += 1
        val start = position
        var p = start
        var nonAscii = false
        while (p < limit) {
            val kind = classes[buffer[p].toInt() and 0xFF].toInt()
            if (kind == PLAIN) {
                p += 1
                continue
            }
            when (kind) {
                QUOTE_BYTE -> {
                    position = p + 1
                    if (!nonAscii) {
                        return if (wantValue) String(buffer, start, p - start, Charsets.ISO_8859_1) else ""
                    }
                    if (!isValidUtf8(start, p)) {
                        position = start
                        fail("the string is not valid UTF-8")
                    }
                    return if (wantValue) String(buffer, start, p - start, Charsets.UTF_8) else ""
                }
                BACKSLASH_BYTE -> {
                    position = p
                    return scanEscapedString(start, wantValue)
                }
                CONTROL_BYTE -> {
                    position = p
                    fail("unescaped control character in a string")
                }
                else -> nonAscii = true
            }
            p += 1
        }
        position = limit
        fail("unterminated string")
    }

    /** The rest of a string that contains an escape: [position] is at the first backslash, [start] after the quote. */
    private fun scanEscapedString(start: Int, wantValue: Boolean): String {
        val buffer = buffer
        val builder = if (wantValue) StringBuilder() else null
        var segmentStart = start
        var p = position
        while (true) {
            if (p >= limit) {
                position = limit
                fail("unterminated string")
            }
            val b = buffer[p].toInt()
            if (b == QUOTE.toInt()) {
                appendSegment(builder, segmentStart, p)
                position = p + 1
                return builder?.toString() ?: ""
            }
            if (b == BACKSLASH.toInt()) {
                appendSegment(builder, segmentStart, p)
                p += 1
                if (p >= limit) {
                    position = limit
                    fail("unterminated string")
                }
                when (buffer[p].toInt().toChar()) {
                    '"' -> builder?.append('"')
                    '\\' -> builder?.append('\\')
                    '/' -> builder?.append('/')
                    'b' -> builder?.append('\b')
                    'f' -> builder?.append('\u000C')
                    'n' -> builder?.append('\n')
                    'r' -> builder?.append('\r')
                    't' -> builder?.append('\t')
                    'u' -> {
                        var unit = hexUnit(p + 1)
                        p += 4
                        if (unit in 0xD800..0xDBFF) {
                            if (p + 6 < limit && buffer[p + 1] == BACKSLASH && buffer[p + 2] == LETTER_U) {
                                val low = hexUnit(p + 3)
                                if (low in 0xDC00..0xDFFF) {
                                    builder?.append(unit.toChar())
                                    unit = low
                                    p += 6
                                } else {
                                    position = p
                                    fail("invalid unicode escape (unpaired surrogate)")
                                }
                            } else {
                                position = p
                                fail("invalid unicode escape (unpaired surrogate)")
                            }
                        } else if (unit in 0xDC00..0xDFFF) {
                            position = p
                            fail("invalid unicode escape (unpaired surrogate)")
                        }
                        builder?.append(unit.toChar())
                    }
                    else -> {
                        position = p
                        fail("invalid escape sequence in a string")
                    }
                }
                p += 1
                segmentStart = p
                continue
            }
            if (b in 0..0x1F) {
                position = p
                fail("unescaped control character in a string")
            }
            p += 1
        }
    }

    /** The value of the four hexadecimal digits at [from], which must exist. */
    private fun hexUnit(from: Int): Int {
        if (from + 4 > limit) {
            position = limit
            fail("unterminated string")
        }
        var value = 0
        for (index in 0 until 4) {
            val digit = hexDigit(buffer[from + index].toInt())
            if (digit < 0) {
                position = from + index
                fail("invalid unicode escape")
            }
            value = value * 16 + digit
        }
        return value
    }

    private fun hexDigit(b: Int): Int = when (b) {
        in 0x30..0x39 -> b - 0x30
        in 0x41..0x46 -> b - 0x41 + 10
        in 0x61..0x66 -> b - 0x61 + 10
        else -> -1
    }

    /** Appends the bytes `[from, to)` of the buffer, which are validated as UTF-8, to [builder] (if any). */
    private fun appendSegment(builder: StringBuilder?, from: Int, to: Int) {
        if (to <= from) return
        var ascii = true
        for (index in from until to) {
            if (buffer[index] < 0) {
                ascii = false
                break
            }
        }
        if (!ascii && !isValidUtf8(from, to)) {
            position = from
            fail("the string is not valid UTF-8")
        }
        if (builder == null) return
        if (ascii) {
            for (index in from until to) builder.append(buffer[index].toInt().toChar())
        } else {
            builder.append(String(buffer, from, to - from, Charsets.UTF_8))
        }
    }

    /** Whether the bytes `[from, to)` are well-formed UTF-8 (no overlong forms, no surrogates, nothing above U+10FFFF). */
    private fun isValidUtf8(from: Int, to: Int): Boolean {
        val buffer = buffer
        var p = from
        while (p < to) {
            val b = buffer[p].toInt() and 0xFF
            when {
                b < 0x80 -> p += 1
                b in 0xC2..0xDF -> {
                    if (p + 1 >= to || !isContinuation(buffer[p + 1])) return false
                    p += 2
                }
                b in 0xE0..0xEF -> {
                    if (p + 2 >= to) return false
                    val b1 = buffer[p + 1].toInt() and 0xFF
                    val low = if (b == 0xE0) 0xA0 else 0x80
                    val high = if (b == 0xED) 0x9F else 0xBF
                    if (b1 < low || b1 > high || !isContinuation(buffer[p + 2])) return false
                    p += 3
                }
                b in 0xF0..0xF4 -> {
                    if (p + 3 >= to) return false
                    val b1 = buffer[p + 1].toInt() and 0xFF
                    val low = if (b == 0xF0) 0x90 else 0x80
                    val high = if (b == 0xF4) 0x8F else 0xBF
                    if (b1 < low || b1 > high || !isContinuation(buffer[p + 2]) || !isContinuation(buffer[p + 3])) {
                        return false
                    }
                    p += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and 0xC0) == 0x80

    // MARK: Numbers

    /**
     * Validates the number at the reader's position against the JSON grammar and moves past it, summing up
     * its digits (see [numberMantissa]).
     */
    private fun scanNumber() {
        val buffer = buffer
        val limit = limit
        var p = position
        numberStart = p
        val negative = buffer[p] == MINUS
        if (negative) p += 1
        if (p >= limit) {
            position = p
            fail("invalid number")
        }
        var mantissa = 0L
        var significant = 0
        var tooLong = false
        val first = buffer[p]
        if (first == DIGIT_0) {
            p += 1
        } else if (first > DIGIT_0 && first <= DIGIT_9) {
            mantissa = (first - DIGIT_0).toLong()
            significant = 1
            p += 1
            while (p < limit) {
                val digit = buffer[p] - DIGIT_0
                if (digit < 0 || digit > 9) break
                if (significant >= MANTISSA_DIGITS) {
                    tooLong = true
                } else {
                    mantissa = mantissa * 10 + digit
                    significant += 1
                }
                p += 1
            }
        } else {
            position = p
            fail("invalid number")
        }
        var integer = true
        var fractionDigits = 0
        if (p < limit && buffer[p] == PERIOD) {
            p += 1
            val digitsStart = p
            while (p < limit) {
                val digit = buffer[p] - DIGIT_0
                if (digit < 0 || digit > 9) break
                if (mantissa != 0L || digit != 0) {
                    if (significant >= MANTISSA_DIGITS) {
                        tooLong = true
                    } else {
                        mantissa = mantissa * 10 + digit
                        significant += 1
                    }
                }
                p += 1
            }
            fractionDigits = p - digitsStart
            if (fractionDigits == 0) {
                position = p
                fail("invalid number (no digits after the decimal point)")
            }
            integer = false
        }
        var exponent = 0
        if (p < limit && (buffer[p] == LETTER_E || buffer[p] == LETTER_E_UPPER)) {
            p += 1
            var negativeExponent = false
            if (p < limit) {
                if (buffer[p] == PLUS) {
                    p += 1
                } else if (buffer[p] == MINUS) {
                    negativeExponent = true
                    p += 1
                }
            }
            val digitsStart = p
            while (p < limit) {
                val digit = buffer[p] - DIGIT_0
                if (digit < 0 || digit > 9) break
                if (exponent < 100_000) exponent = exponent * 10 + digit
                p += 1
            }
            if (p == digitsStart) {
                position = p
                fail("invalid number (no digits in the exponent)")
            }
            if (negativeExponent) exponent = -exponent
            integer = false
        }
        numberEnd = p
        numberIsInteger = integer
        numberNegative = negative
        numberMantissa = mantissa
        numberTooLong = tooLong
        numberFractionDigits = fractionDigits
        numberExponent = exponent
        position = p
    }

    /** The scanned number as a 64-bit integer, or `null` if it is not exactly an integer in that range. */
    private fun numberAsLong(): Long? {
        if (numberIsInteger) {
            if (!numberTooLong) return if (numberNegative) -numberMantissa else numberMantissa
            var p = numberStart
            val negative = buffer[p] == MINUS
            if (negative) p += 1
            // Accumulates negatively so that Long.MIN_VALUE fits.
            val bound = if (negative) Long.MIN_VALUE else -Long.MAX_VALUE
            val multiplicationBound = bound / 10
            var result = 0L
            while (p < numberEnd) {
                val digit = buffer[p] - DIGIT_0
                if (result < multiplicationBound) return null
                result *= 10
                if (result < bound + digit) return null
                result -= digit
                p += 1
            }
            return if (negative) result else -result
        }
        val value = numberAsDouble()
        if (!value.isFinite() || value != Math.rint(value)) return null
        // 2^63 is not representable as a Long.
        if (value >= -9.223372036854775808E18 && value < 9.223372036854775808E18) return value.toLong()
        return null
    }

    /** The scanned number as the closest `Double` (infinite if it is out of range). */
    private fun numberAsDouble(): Double {
        if (!numberTooLong) {
            val mantissa = numberMantissa
            if (mantissa == 0L) return if (numberNegative) -0.0 else 0.0
            val decimalExponent = numberExponent - numberFractionDigits
            if (mantissa <= EXACT_MANTISSA_LIMIT && decimalExponent >= -22 && decimalExponent <= 22) {
                // Both operands are exact doubles, so the one rounding makes the result the closest double.
                val magnitude = if (decimalExponent < 0) {
                    mantissa.toDouble() / POWERS_OF_TEN[-decimalExponent]
                } else {
                    mantissa.toDouble() * POWERS_OF_TEN[decimalExponent]
                }
                return if (numberNegative) -magnitude else magnitude
            }
        }
        return java.lang.Double.parseDouble(String(buffer, numberStart, numberEnd - numberStart, Charsets.ISO_8859_1))
    }

    companion object {
        /** Containers may be nested this deep (the limit of `JSONDecoder`). */
        const val MAXIMUM_DEPTH: Int = 512

        /** [nextKey] after the last member of an object. */
        const val END_OF_OBJECT: Long = -2L

        /** The key code of any name that is not one to eight printable ASCII characters. */
        const val OTHER_KEY: Long = -1L

        private const val OK = 0
        private const val NULL = 1
        private const val MISMATCH = 2

        /**
         * The code of a member name of one to eight printable ASCII characters: its bytes packed into a
         * `Long`, so that `when (code)` can dispatch on it. Any other name has the code [OTHER_KEY].
         */
        fun keyCode(name: String): Long {
            if (name.length !in 1..8) return OTHER_KEY
            var code = 0L
            for (character in name) {
                if (character.code < 0x20 || character.code >= 0x7F) return OTHER_KEY
                code = (code shl 8) or character.code.toLong()
            }
            return code
        }

        // How scanString treats a byte of a string: most are plain.
        private const val PLAIN = 0
        private const val QUOTE_BYTE = 1
        private const val BACKSLASH_BYTE = 2
        private const val CONTROL_BYTE = 3
        private const val NON_ASCII_BYTE = 4

        private val BYTE_CLASSES: ByteArray = ByteArray(256).also { classes ->
            for (byte in 0x00..0x1F) classes[byte] = CONTROL_BYTE.toByte()
            classes[0x22] = QUOTE_BYTE.toByte()
            classes[0x5C] = BACKSLASH_BYTE.toByte()
            for (byte in 0x80..0xFF) classes[byte] = NON_ASCII_BYTE.toByte()
        }

        private const val OPEN_BRACE: Byte = 0x7B
        private const val CLOSE_BRACE: Byte = 0x7D
        private const val OPEN_BRACKET: Byte = 0x5B
        private const val CLOSE_BRACKET: Byte = 0x5D
        private const val QUOTE: Byte = 0x22
        private const val BACKSLASH: Byte = 0x5C
        private const val COMMA: Byte = 0x2C
        private const val COLON: Byte = 0x3A
        private const val MINUS: Byte = 0x2D
        private const val PLUS: Byte = 0x2B
        private const val PERIOD: Byte = 0x2E
        private const val DIGIT_0: Byte = 0x30
        private const val DIGIT_9: Byte = 0x39
        private const val LETTER_E: Byte = 0x65
        private const val LETTER_E_UPPER: Byte = 0x45
        private const val LETTER_F: Byte = 0x66
        private const val LETTER_N: Byte = 0x6E
        private const val LETTER_T: Byte = 0x74
        private const val LETTER_U: Byte = 0x75
        private const val SPACE: Byte = 0x20
        private const val NEWLINE: Byte = 0x0A
        private const val CARRIAGE_RETURN: Byte = 0x0D
        private const val TAB: Byte = 0x09

        private val TRUE = "true".toByteArray(Charsets.US_ASCII)
        private val FALSE = "false".toByteArray(Charsets.US_ASCII)
        private val NULL_TEXT = "null".toByteArray(Charsets.US_ASCII)

        /** The number of significant digits that [numberMantissa] holds (it stays below 10^18). */
        private const val MANTISSA_DIGITS = 18

        /** The largest integer below which every integer is a `Double` (2^53). */
        private const val EXACT_MANTISSA_LIMIT = 9_007_199_254_740_992L

        /** 10^0 ... 10^22, all exactly representable. */
        private val POWERS_OF_TEN = doubleArrayOf(
            1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17,
            1e18, 1e19, 1e20, 1e21, 1e22,
        )
    }
}

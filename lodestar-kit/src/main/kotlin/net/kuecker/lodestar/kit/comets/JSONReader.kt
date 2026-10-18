package net.kuecker.lodestar.kit.comets

/** A value of a JSON document, as [JSONReader] reads it. */
internal sealed interface JSONValue {
    /** An object; a repeated name keeps its last value. */
    data class ObjectValue(val members: Map<String, JSONValue>) : JSONValue

    /** An array. */
    data class ArrayValue(val items: List<JSONValue>) : JSONValue

    /** A string. */
    data class StringValue(val value: String) : JSONValue

    /** A number, as the nearest `Double`. */
    data class NumberValue(val value: Double) : JSONValue

    /** `true` or `false`. */
    data class BooleanValue(val value: Boolean) : JSONValue

    /** `null`. */
    data object NullValue : JSONValue
}

/** Why [JSONReader] refused a document. */
internal enum class JSONFailure {
    /** The text is not JSON (RFC 8259) or contains a number that is not finite or a string with a lone surrogate. */
    INVALID,

    /** Objects and arrays are nested deeper than [JSONReader.maximumNesting]. */
    TOO_DEEP,
}

/** Thrown by [JSONReader] with the [failure] that stopped it. */
internal class JSONReaderException(val failure: JSONFailure) : Exception(failure.name, null, false, false)

/**
 * A strict JSON reader (RFC 8259) for the small answers of web services; the Swift twin reads the same grammar, so
 * both languages accept and refuse the same documents. No comments, no trailing commas, no single quotes, no `NaN`,
 * no leading zeros, no byte-order mark; only space, tab, line feed and carriage return are whitespace; raw control
 * characters are not allowed in strings; `\u` escapes must pair their surrogates.
 */
internal class JSONReader(private val text: String) {
    private var position = 0

    /** Reads the whole text as one JSON value. */
    fun readDocument(): JSONValue {
        skipWhitespace()
        val value = readValue(0)
        skipWhitespace()
        if (position != text.length) throw invalid()
        return value
    }

    private fun invalid(): JSONReaderException = JSONReaderException(JSONFailure.INVALID)

    private fun skipWhitespace() {
        while (position < text.length) {
            val character = text[position]
            if (character == ' ' || character == '\t' || character == '\n' || character == '\r') position++ else break
        }
    }

    private fun readValue(depth: Int): JSONValue {
        if (position >= text.length) throw invalid()
        return when (text[position]) {
            '{' -> readObject(depth + 1)
            '[' -> readArray(depth + 1)
            '"' -> JSONValue.StringValue(readString())
            't' -> readLiteral("true", JSONValue.BooleanValue(true))
            'f' -> readLiteral("false", JSONValue.BooleanValue(false))
            'n' -> readLiteral("null", JSONValue.NullValue)
            else -> readNumber()
        }
    }

    private fun readLiteral(word: String, value: JSONValue): JSONValue {
        if (!text.startsWith(word, position)) throw invalid()
        position += word.length
        return value
    }

    private fun readObject(depth: Int): JSONValue {
        if (depth > maximumNesting) throw JSONReaderException(JSONFailure.TOO_DEEP)
        position += 1
        val members = LinkedHashMap<String, JSONValue>()
        skipWhitespace()
        if (position < text.length && text[position] == '}') {
            position += 1
            return JSONValue.ObjectValue(members)
        }
        while (true) {
            skipWhitespace()
            if (position >= text.length || text[position] != '"') throw invalid()
            val key = readString()
            skipWhitespace()
            if (position >= text.length || text[position] != ':') throw invalid()
            position += 1
            skipWhitespace()
            members[key] = readValue(depth)
            skipWhitespace()
            if (position >= text.length) throw invalid()
            val separator = text[position]
            position += 1
            if (separator == '}') return JSONValue.ObjectValue(members)
            if (separator != ',') throw invalid()
        }
    }

    private fun readArray(depth: Int): JSONValue {
        if (depth > maximumNesting) throw JSONReaderException(JSONFailure.TOO_DEEP)
        position += 1
        val items = ArrayList<JSONValue>()
        skipWhitespace()
        if (position < text.length && text[position] == ']') {
            position += 1
            return JSONValue.ArrayValue(items)
        }
        while (true) {
            skipWhitespace()
            items.add(readValue(depth))
            skipWhitespace()
            if (position >= text.length) throw invalid()
            val separator = text[position]
            position += 1
            if (separator == ']') return JSONValue.ArrayValue(items)
            if (separator != ',') throw invalid()
        }
    }

    private fun readString(): String {
        position += 1
        val result = StringBuilder()
        while (true) {
            if (position >= text.length) throw invalid()
            val character = text[position]
            position += 1
            when {
                character == '"' -> return result.toString()
                character.code < 0x20 -> throw invalid()
                character != '\\' -> {
                    if (character.isHighSurrogate()) {
                        if (position >= text.length || !text[position].isLowSurrogate()) throw invalid()
                        result.append(character).append(text[position])
                        position += 1
                    } else if (character.isLowSurrogate()) {
                        throw invalid()
                    } else {
                        result.append(character)
                    }
                }
                else -> {
                    if (position >= text.length) throw invalid()
                    val escape = text[position]
                    position += 1
                    when (escape) {
                        '"' -> result.append('"')
                        '\\' -> result.append('\\')
                        '/' -> result.append('/')
                        'b' -> result.append('\b')
                        'f' -> result.append('\u000C')
                        'n' -> result.append('\n')
                        'r' -> result.append('\r')
                        't' -> result.append('\t')
                        'u' -> {
                            val unit = readHexUnit()
                            if (unit in 0xD800..0xDBFF) {
                                if (!text.startsWith("\\u", position)) throw invalid()
                                position += 2
                                val low = readHexUnit()
                                if (low !in 0xDC00..0xDFFF) throw invalid()
                                result.append(unit.toChar()).append(low.toChar())
                            } else if (unit in 0xDC00..0xDFFF) {
                                throw invalid()
                            } else {
                                result.append(unit.toChar())
                            }
                        }
                        else -> throw invalid()
                    }
                }
            }
        }
    }

    private fun readHexUnit(): Int {
        if (position + 4 > text.length) throw invalid()
        var unit = 0
        for (offset in 0 until 4) {
            val digit = Character.digit(text[position + offset], 16)
            if (digit < 0 || text[position + offset].code > 0x7F) throw invalid()
            unit = unit * 16 + digit
        }
        position += 4
        return unit
    }

    private fun readNumber(): JSONValue {
        val start = position
        if (position < text.length && text[position] == '-') position += 1
        if (position >= text.length) throw invalid()
        if (text[position] == '0') {
            position += 1
        } else if (text[position] in '1'..'9') {
            while (position < text.length && text[position] in '0'..'9') position += 1
        } else {
            throw invalid()
        }
        if (position < text.length && text[position] == '.') {
            position += 1
            val digitsStart = position
            while (position < text.length && text[position] in '0'..'9') position += 1
            if (position == digitsStart) throw invalid()
        }
        if (position < text.length && (text[position] == 'e' || text[position] == 'E')) {
            position += 1
            if (position < text.length && (text[position] == '+' || text[position] == '-')) position += 1
            val digitsStart = position
            while (position < text.length && text[position] in '0'..'9') position += 1
            if (position == digitsStart) throw invalid()
        }
        val value = text.substring(start, position).toDouble()
        if (!value.isFinite()) throw invalid()
        return JSONValue.NumberValue(value)
    }

    companion object {
        /** The deepest accepted nesting of objects and arrays, 32. */
        const val maximumNesting: Int = 32
    }
}

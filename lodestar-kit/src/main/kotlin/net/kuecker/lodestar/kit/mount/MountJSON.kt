package net.kuecker.lodestar.kit.mount

/**
 * A parsed JSON document. The engine has its own strict parser (RFC 8259 grammar, bounded size and depth) so that
 * Alpaca replies are validated identically in both languages.
 */
sealed interface MountJSON {
    /** `null`. */
    data object Null : MountJSON

    /** `true` or `false`. */
    data class Bool(val value: Boolean) : MountJSON

    /** A finite number. */
    data class Number(val value: Double) : MountJSON

    /** A string. */
    data class Text(val value: String) : MountJSON

    /** An array. */
    data class Array(val items: List<MountJSON>) : MountJSON

    /** An object; with duplicate keys the last one wins. */
    data class Object(val fields: Map<String, MountJSON>) : MountJSON

    /** The member [name] of an object (exact match first, then ignoring ASCII case), or null. */
    fun field(name: String): MountJSON? {
        val fields = (this as? Object)?.fields ?: return null
        fields[name]?.let { return it }
        val wanted = MountText.asciiLowercased(name)
        for ((key, value) in fields) if (MountText.asciiLowercased(key) == wanted) return value
        return null
    }

    companion object {
        /** Documents longer than this many UTF-16 units are rejected. */
        const val maximumLength: Int = 262_144

        /** Nesting deeper than this is rejected. */
        const val maximumDepth: Int = 32

        /** Parses [text]; throws [MountError.MalformedReply] for anything that is not exactly one JSON value. */
        @Throws(MountError::class)
        fun parse(text: String): MountJSON {
            if (text.length > maximumLength) throw MountError.MalformedReply("JSON document too long")
            return MountJSONReader(text).readDocument()
        }
    }
}

/** ASCII helpers shared by the protocol layers. */
internal object MountText {
    fun asciiLowercased(text: String): String {
        val result = StringBuilder(text.length)
        for (ch in text) result.append(if (ch in 'A'..'Z') (ch.code + 32).toChar() else ch)
        return result.toString()
    }

    /** Latin-1 view of bytes: every byte becomes the character with the same code. */
    fun latin1(bytes: ByteArray): String {
        val result = StringBuilder(bytes.size)
        for (b in bytes) result.append((b.toInt() and 0xFF).toChar())
        return result.toString()
    }

    /** One byte per character of [text] (character codes above 255 are cut to their low byte). */
    fun latin1Bytes(text: String): ByteArray = ByteArray(text.length) { index -> text[index].code.toByte() }

    /** The ASCII bytes of [text]; characters above 0x7E are replaced by '?'. */
    fun ascii(text: String): ByteArray = ByteArray(text.length) { index ->
        val code = text[index].code
        if (code in 0x20..0x7E) code.toByte() else '?'.code.toByte()
    }

    /** [text] without control characters, cut to [limit] characters. */
    fun sanitized(text: String, limit: Int): String {
        val result = StringBuilder()
        for (ch in text) {
            if (result.length >= limit) break
            if (ch.code >= 0x20 && ch.code != 0x7F) result.append(ch)
        }
        return result.toString().trim()
    }
}

private class MountJSONReader(private val text: String) {
    private var index = 0

    fun readDocument(): MountJSON {
        skipWhitespace()
        val value = readValue(0)
        skipWhitespace()
        if (index != text.length) fail("trailing characters")
        return value
    }

    private fun fail(reason: String): Nothing = throw MountError.MalformedReply("JSON: $reason")

    private fun skipWhitespace() {
        while (index < text.length) {
            val ch = text[index]
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') index++ else break
        }
    }

    private fun readValue(depth: Int): MountJSON {
        if (depth > MountJSON.maximumDepth) fail("nesting too deep")
        if (index >= text.length) fail("unexpected end")
        return when (val ch = text[index]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> MountJSON.Text(readString())
            't' -> readLiteral("true", MountJSON.Bool(true))
            'f' -> readLiteral("false", MountJSON.Bool(false))
            'n' -> readLiteral("null", MountJSON.Null)
            else -> if (ch == '-' || ch in '0'..'9') readNumber() else fail("unexpected character")
        }
    }

    private fun readLiteral(word: String, value: MountJSON): MountJSON {
        if (!text.startsWith(word, index)) fail("invalid literal")
        index += word.length
        return value
    }

    private fun readObject(depth: Int): MountJSON {
        index++
        val fields = LinkedHashMap<String, MountJSON>()
        skipWhitespace()
        if (index < text.length && text[index] == '}') {
            index++
            return MountJSON.Object(fields)
        }
        while (true) {
            skipWhitespace()
            if (index >= text.length || text[index] != '"') fail("object key expected")
            val key = readString()
            skipWhitespace()
            if (index >= text.length || text[index] != ':') fail("':' expected")
            index++
            skipWhitespace()
            fields[key] = readValue(depth + 1)
            skipWhitespace()
            if (index >= text.length) fail("unexpected end")
            val ch = text[index]
            index++
            if (ch == '}') return MountJSON.Object(fields)
            if (ch != ',') fail("',' or '}' expected")
        }
    }

    private fun readArray(depth: Int): MountJSON {
        index++
        val items = ArrayList<MountJSON>()
        skipWhitespace()
        if (index < text.length && text[index] == ']') {
            index++
            return MountJSON.Array(items)
        }
        while (true) {
            skipWhitespace()
            items.add(readValue(depth + 1))
            skipWhitespace()
            if (index >= text.length) fail("unexpected end")
            val ch = text[index]
            index++
            if (ch == ']') return MountJSON.Array(items)
            if (ch != ',') fail("',' or ']' expected")
        }
    }

    private fun readString(): String {
        index++
        val result = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            val ch = text[index]
            index++
            when {
                ch == '"' -> return result.toString()
                ch == '\\' -> result.append(readEscape())
                ch.code < 0x20 -> fail("control character in string")
                Character.isHighSurrogate(ch) -> {
                    if (index >= text.length || !Character.isLowSurrogate(text[index])) fail("invalid surrogate")
                    result.append(ch).append(text[index])
                    index++
                }
                Character.isLowSurrogate(ch) -> fail("invalid surrogate")
                else -> result.append(ch)
            }
        }
    }

    private fun readEscape(): String {
        if (index >= text.length) fail("unterminated escape")
        val ch = text[index]
        index++
        return when (ch) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\b"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> {
                val unit = readHex4()
                if (unit in 0xD800..0xDBFF) {
                    if (index + 1 < text.length && text[index] == '\\' && text[index + 1] == 'u') {
                        index += 2
                        val low = readHex4()
                        if (low !in 0xDC00..0xDFFF) fail("invalid surrogate pair")
                        String(charArrayOf(unit.toChar(), low.toChar()))
                    } else {
                        fail("invalid surrogate pair")
                    }
                } else if (unit in 0xDC00..0xDFFF) {
                    fail("invalid surrogate pair")
                } else {
                    unit.toChar().toString()
                }
            }
            else -> fail("invalid escape")
        }
    }

    private fun readHex4(): Int {
        if (index + 4 > text.length) fail("invalid \\u escape")
        var value = 0
        for (offset in 0 until 4) {
            val digit = Character.digit(text[index + offset], 16)
            if (digit < 0 || text[index + offset].code > 0x7F) fail("invalid \\u escape")
            value = value * 16 + digit
        }
        index += 4
        return value
    }

    private fun readNumber(): MountJSON {
        val start = index
        if (text[index] == '-') index++
        if (index >= text.length) fail("invalid number")
        if (text[index] == '0') {
            index++
        } else if (text[index] in '1'..'9') {
            while (index < text.length && text[index] in '0'..'9') index++
        } else {
            fail("invalid number")
        }
        if (index < text.length && text[index] == '.') {
            index++
            val digitsStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == digitsStart) fail("invalid number")
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            val digitsStart = index
            while (index < text.length && text[index] in '0'..'9') index++
            if (index == digitsStart) fail("invalid number")
        }
        val value = text.substring(start, index).toDoubleOrNull() ?: fail("invalid number")
        if (!value.isFinite()) fail("number out of range")
        return MountJSON.Number(value)
    }
}

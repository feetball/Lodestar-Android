package net.kuecker.lodestar.kit.catalog

import java.text.Normalizer

/**
 * Text normalization for catalog search.
 *
 * A search key is the text reduced to lower-case ASCII letters and digits:
 * - case, diacritics and character width are ignored ("Ｍ３１" is "m31", "Boötes" is "bootes");
 * - Greek letters become their English names ("α" is "alpha") and superscript or subscript digits
 *   become plain digits ("α¹" is "alpha1");
 * - punctuation and whitespace are dropped, apostrophes silently ("Barnard's" is "barnards") and
 *   everything else as a word boundary, which the key remembers in `wordStarts`;
 * - leading zeros of numbers are dropped ("NGC 0224" is "ngc224"), keeping a lone zero;
 * - scripts and symbols without a Latin reading (emoji, CJK, ...) count as punctuation.
 *
 * Queries and the texts they are matched against go through the same function, so every
 * spelling that reduces to the same key matches.
 */
internal object SearchText {
    /** The longest key in bytes; longer text is cut off. */
    const val maximumKeyLength: Int = 64

    /** The most Unicode scalars of a query that are considered. */
    const val maximumQueryScalars: Int = 64

    /**
     * A normalized text.
     *
     * @property bytes Lower-case ASCII letters and digits, at most [maximumKeyLength] bytes.
     * @property wordStarts Bit *i* (of the 64-bit pattern; Swift `UInt64`) is set when `bytes[i]` is the
     * first byte of a word.
     */
    class Key(val bytes: ByteArray, val wordStarts: Long) {
        override fun equals(other: Any?): Boolean =
            other is Key && wordStarts == other.wordStarts && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + wordStarts.hashCode()

        override fun toString(): String = "Key(\"${String(bytes, Charsets.ISO_8859_1)}\", wordStarts=$wordStarts)"
    }

    /** The key of [text], looking at no more than [maximumScalars] Unicode scalars. */
    fun key(text: String, maximumScalars: Int = Int.MAX_VALUE): Key {
        val builder = KeyBuilder()
        builder.readText(text, maximumScalars)
        return builder.toKey()
    }

    /**
     * Whether [lhs] sorts before [rhs] in natural order: byte by byte, but runs of digits compare
     * by value ("m2" before "m10"). Keys carry no leading zeros, so a longer run is a larger number.
     */
    fun naturalPrecedes(lhs: ByteArray, rhs: ByteArray): Boolean = naturalCompare(lhs, rhs) < 0

    /**
     * The three-way form of [naturalPrecedes]: negative when [lhs] precedes [rhs], positive when [rhs]
     * precedes [lhs], zero when neither does.
     */
    fun naturalCompare(lhs: ByteArray, rhs: ByteArray): Int {
        var i = 0
        var j = 0
        while (i < lhs.size && j < rhs.size) {
            val x = lhs[i].toInt() and 0xFF
            val y = rhs[j].toInt() and 0xFF
            if (isDigit(x) && isDigit(y)) {
                var iEnd = i
                var jEnd = j
                while (iEnd < lhs.size && isDigit(lhs[iEnd].toInt() and 0xFF)) iEnd += 1
                while (jEnd < rhs.size && isDigit(rhs[jEnd].toInt() and 0xFF)) jEnd += 1
                if (iEnd - i != jEnd - j) return if (iEnd - i < jEnd - j) -1 else 1
                for (offset in 0 until iEnd - i) {
                    val a = lhs[i + offset].toInt() and 0xFF
                    val b = rhs[j + offset].toInt() and 0xFF
                    if (a != b) return if (a < b) -1 else 1
                }
                i = iEnd
                j = jEnd
            } else {
                if (x != y) return if (x < y) -1 else 1
                i += 1
                j += 1
            }
        }
        val leftRemaining = lhs.size - i
        val rightRemaining = rhs.size - j
        return if (leftRemaining < rightRemaining) -1 else if (leftRemaining > rightRemaining) 1 else 0
    }

    private fun isDigit(byte: Int): Boolean = byte >= 0x30 && byte <= 0x39
}

/**
 * Accumulates the bytes and word boundaries of a search key, one Unicode scalar at a time.
 *
 * (Swift's is a private struct that is created for each key. This one can be [reset] and read again, so that
 * the index builder, which makes 50 000 keys, allocates no builder or key objects, and it sums up the
 * [symbolMask] of the bytes on the way.)
 */
internal class KeyBuilder {
    /** The stored bytes: `bytes[0, count)` once the text has been read and [complete] called. */
    val bytes = ByteArray(SearchText.maximumKeyLength)

    /** The number of stored bytes. */
    var count = 0
        private set

    /** Bit *i* is set when `bytes[i]` is the first byte of a word. */
    var wordStarts = 0L
        private set

    /** The characters among the stored bytes, see `SearchIndex.symbolMask`. */
    var symbolMask = 0L
        private set

    private var atWordStart = true
    private var inDigitRun = false

    /** Leading zeros of the current number that have been seen but not yet stored. */
    private var pendingZeros = 0

    val isFull: Boolean get() = count >= SearchText.maximumKeyLength

    /** Forgets everything that was read. */
    fun reset() {
        count = 0
        wordStarts = 0L
        symbolMask = 0L
        atWordStart = true
        inDigitRun = false
        pendingZeros = 0
    }

    /** Reads the Unicode scalars of [text], no more than [maximumScalars], until the key is full, then calls [complete]. */
    fun readText(text: String, maximumScalars: Int) {
        val length = text.length
        var index = 0
        var scalarCount = 0
        while (index < length) {
            if (scalarCount >= maximumScalars || count >= SearchText.maximumKeyLength) break
            scalarCount += 1
            val unit = text[index].code
            if (unit < 0x80) {
                index += 1
                when {
                    unit >= 0x61 && unit <= 0x7A -> letter(unit)
                    unit >= 0x30 && unit <= 0x39 -> digit(unit)
                    unit >= 0x41 && unit <= 0x5A -> letter(unit or 0x20)
                    unit == 0x27 || unit == 0x60 -> Unit
                    else -> separator()
                }
            } else {
                val scalar = text.codePointAt(index)
                index += Character.charCount(scalar)
                read(scalar, allowDecomposition = true)
            }
        }
        complete()
    }

    /** Ends the text: stores the zero of a number that consisted of zeros only. */
    fun complete() {
        flushZeros()
    }

    /** The key made so far (after [complete]). */
    fun toKey(): SearchText.Key = SearchText.Key(bytes.copyOf(count), wordStarts)

    fun read(scalar: Int, allowDecomposition: Boolean) {
        when (scalar) {
            in 0x30..0x39 -> digit(scalar)
            in 0x61..0x7A -> letter(scalar)
            in 0x41..0x5A -> letter(scalar or 0x20)
            0x27, 0x60, 0x2018, 0x2019, 0x201B, 0x02BC -> Unit
            in 0x00..0x7F -> separator()
            in 0xFF01..0xFF5E -> read(scalar - 0xFEE0, allowDecomposition = false)
            in 0x0391..0x03A1, in 0x03A3..0x03A9, in 0x03B1..0x03C9,
            0x03D1, 0x03D5, 0x03D6, 0x03F0, 0x03F1, 0x03F4, 0x03F5,
            -> {
                val index = GreekAlphabet.index(scalar)
                if (index != null) greek(index)
            }
            0x00B2 -> digit(0x32)
            0x00B3 -> digit(0x33)
            0x00B9 -> digit(0x31)
            0x2070 -> digit(0x30)
            in 0x2074..0x2079 -> digit(scalar - 0x2074 + 0x34)
            in 0x2080..0x2089 -> digit(scalar - 0x2080 + 0x30)
            0x00AD, in 0x0300..0x036F, in 0x1AB0..0x1AFF, in 0x1DC0..0x1DFF, in 0x200B..0x200F,
            in 0x20D0..0x20FF, 0x2060, in 0xFE00..0xFE0F, in 0xFE20..0xFE2F, 0xFEFF,
            -> Unit
            0x00D8, 0x00F8 -> letter(0x6F)
            0x00C6, 0x00E6 -> {
                letter(0x61)
                letter(0x65)
            }
            0x0152, 0x0153 -> {
                letter(0x6F)
                letter(0x65)
            }
            0x00DF -> {
                letter(0x73)
                letter(0x73)
            }
            0x00D0, 0x00F0, 0x0110, 0x0111 -> letter(0x64)
            0x00DE, 0x00FE -> {
                letter(0x74)
                letter(0x68)
            }
            0x0141, 0x0142 -> letter(0x6C)
            0x0126, 0x0127 -> letter(0x68)
            0x0131 -> letter(0x69)
            0x0166, 0x0167 -> letter(0x74)
            else -> decompose(scalar, allowDecomposition)
        }
    }

    /**
     * Reads a scalar through its compatibility decomposition (accented letters, ligatures,
     * circled digits, ...). A scalar that does not decompose counts as punctuation.
     */
    private fun decompose(scalar: Int, allowDecomposition: Boolean) {
        if (!allowDecomposition) {
            separator()
            return
        }
        val decomposed = Normalizer.normalize(String(Character.toChars(scalar)), Normalizer.Form.NFKD)
        var changed = false
        var index = 0
        while (index < decomposed.length) {
            val part = decomposed.codePointAt(index)
            if (part == scalar) break
            changed = true
            index += Character.charCount(part)
            read(part, allowDecomposition = false)
        }
        if (!changed) separator()
    }

    private fun greek(index: Int) {
        atWordStart = true
        for (byte in GreekAlphabet.nameBytes[index]) {
            letter(byte.toInt())
        }
    }

    private fun letter(byte: Int) {
        flushZeros()
        append(byte)
        inDigitRun = false
    }

    private fun digit(byte: Int) {
        if (byte == 0x30) {
            if (inDigitRun) {
                append(byte)
            } else {
                pendingZeros += 1
            }
        } else {
            pendingZeros = 0
            append(byte)
            inDigitRun = true
        }
    }

    private fun separator() {
        flushZeros()
        atWordStart = true
        inDigitRun = false
    }

    /** Stores one zero for a number that consisted of zeros only. */
    private fun flushZeros() {
        if (pendingZeros <= 0) return
        pendingZeros = 0
        append(0x30)
        inDigitRun = true
    }

    private fun append(byte: Int) {
        if (count >= SearchText.maximumKeyLength) return
        if (atWordStart) {
            wordStarts = wordStarts or (1L shl count)
            atWordStart = false
        }
        bytes[count] = byte.toByte()
        symbolMask = symbolMask or SearchIndex.symbolBit(byte.toByte())
        count += 1
    }
}

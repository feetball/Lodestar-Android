package net.kuecker.lodestar.kit.catalog

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

// Swift's `String` and `Unicode.Scalar` behaviour that the catalog relies on and the JVM lacks: the Unicode
// White_Space property, comparison in the canonical order of Swift strings, and `prefix(_:)` by characters
// (extended grapheme clusters). Kotlin-only; there is no Swift file for it.

/** Text helpers with Swift semantics, shared by the catalog's validation, naming and ordering code. */
internal object CatalogText {
    /**
     * The Unicode `White_Space` property of [scalar], Swift's `Unicode.Scalar.Properties.isWhitespace`:
     * U+0009 to U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F
     * and U+3000. (`Character.isWhitespace` is wrong for this: it excludes the no-break spaces and
     * includes U+001C to U+001F.)
     */
    fun isWhiteSpace(scalar: Int): Boolean = when (scalar) {
        in 0x09..0x0D, 0x20, 0x85, 0xA0, 0x1680, in 0x2000..0x200A, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000 -> true
        else -> false
    }

    /** Whether [scalar] is in Unicode's general category Cc (Swift's `generalCategory == .control`). */
    fun isControl(scalar: Int): Boolean = Character.getType(scalar) == Character.CONTROL.toInt()

    /**
     * Whether [scalar] is in Foundation's `CharacterSet.whitespaces`: general category Zs or the
     * character tabulation.
     */
    fun isSpaceOrTab(scalar: Int): Boolean =
        scalar == 0x09 || Character.getType(scalar) == Character.SPACE_SEPARATOR.toInt()

    /**
     * Swift's `trimmingCharacters(in: .whitespaces)`: removes spaces and tabs (general category Zs,
     * U+0009) from both ends.
     */
    fun trimmingSpacesAndTabs(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val scalar = text.codePointAt(start)
            if (!isSpaceOrTab(scalar)) break
            start += Character.charCount(scalar)
        }
        while (start < end) {
            val scalar = text.codePointBefore(end)
            if (!isSpaceOrTab(scalar)) break
            end -= Character.charCount(scalar)
        }
        return if (start == 0 && end == text.length) text else text.substring(start, end)
    }

    /**
     * Swift's `String.prefix(maxLength)`: the first [maxLength] characters, where a character is an
     * extended grapheme cluster (an accented letter written with a combining mark is one character,
     * however many UTF-16 units it takes).
     */
    fun prefixCharacters(text: String, maxLength: Int): String {
        // Every character takes at least one UTF-16 unit, so a short string cannot have too many of them.
        if (text.length <= maxLength) return text
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
        boundaries.setText(text)
        var end = 0
        var count = 0
        while (count < maxLength) {
            val next = boundaries.next()
            if (next == BreakIterator.DONE) return text
            end = next
            count += 1
        }
        return text.substring(0, end)
    }

    /** The number of bytes of the UTF-8 form of [text] (Swift's `text.utf8.count`), without encoding it. */
    fun utf8Length(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val unit = text[index]
            bytes += when {
                unit.code < 0x80 -> 1
                unit.code < 0x800 -> 2
                Character.isHighSurrogate(unit) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index += 1
                    4
                }
                else -> 3
            }
            index += 1
        }
        return bytes
    }

    /**
     * Compares two strings the way Swift's `<` does: by the Unicode scalars of their canonical
     * (NFC) forms, so canonically equivalent strings compare equal and the order of scalars outside
     * the BMP is not the order of their UTF-16 units. Returns a negative number, zero or a positive number.
     */
    fun compare(lhs: String, rhs: String): Int {
        if (lhs === rhs) return 0
        val left = canonical(lhs)
        val right = canonical(rhs)
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            val a = left.codePointAt(i)
            val b = right.codePointAt(j)
            if (a != b) return if (a < b) -1 else 1
            i += Character.charCount(a)
            j += Character.charCount(b)
        }
        val leftRemaining = left.length - i
        val rightRemaining = right.length - j
        return if (leftRemaining < rightRemaining) -1 else if (leftRemaining > rightRemaining) 1 else 0
    }

    private fun canonical(text: String): String {
        for (unit in text) {
            if (unit.code >= 0x80) return Normalizer.normalize(text, Normalizer.Form.NFC)
        }
        return text
    }
}

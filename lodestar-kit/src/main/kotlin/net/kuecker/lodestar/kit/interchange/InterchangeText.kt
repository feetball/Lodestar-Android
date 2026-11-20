package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.CatalogText
import net.kuecker.lodestar.kit.foundation.timeIntervalSince1970
import net.kuecker.lodestar.kit.formatting.NumberText
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/**
 * Locale-independent text for the numbers and dates of the interchange formats, built from integers like
 * `NumberText` (no `String.format`, no date formatter), so Swift and Kotlin write the same characters.
 */
internal object InterchangeText {
    /** A finite number with at most [maximumDecimals] places and no trailing zeros ("-5.391", "12", "0.5"); "0" when it is not finite. */
    fun decimal(value: Double, maximumDecimals: Int): String {
        if (!value.isFinite()) return "0"
        val magnitude = NumberText.trimmed(abs(value), maximumDecimals) ?: return "0"
        return if (value < 0 && magnitude != "0") "-$magnitude" else magnitude
    }

    /** A finite number with exactly [decimals] places ("-5.39100"); "0" when it is not finite or too large to render. */
    fun fixed(value: Double, decimals: Int): String {
        val point = NumberText.fixedPoint(if (value.isFinite()) abs(value) else 0.0, decimals) ?: return "0"
        return if (value.isFinite() && value < 0 && !point.isZero) "-${point.text}" else point.text
    }

    /** `a / b` rounded towards negative infinity, for a positive [b]. */
    fun floorDivide(a: Long, b: Long): Long = Math.floorDiv(a, b)

    /** The seconds since 1970-01-01T00:00:00Z of an instant, rounded down; 0 for an instant that is not representable. */
    fun wholeSeconds(date: Instant): Long {
        val seconds = floor(date.timeIntervalSince1970)
        if (!seconds.isFinite() || abs(seconds) >= 1e15) return 0
        return seconds.toLong()
    }

    /** The proleptic Gregorian date of a day count since 1970-01-01 (Howard Hinnant's `civil_from_days`) as year, month, day. */
    fun civil(daysSinceEpoch: Long): Triple<Long, Int, Int> {
        val shifted = daysSinceEpoch + 719_468
        val era = floorDivide(shifted, 146_097L)
        val dayOfEra = shifted - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
        val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        val year = yearOfEra + era * 400 + (if (month <= 2) 1 else 0)
        return Triple(year, month, day)
    }

    /** "2026-11-18" for a day count since 1970-01-01. */
    fun dateText(daysSinceEpoch: Long): String {
        val (year, month, day) = civil(daysSinceEpoch)
        return "${NumberText.padded(year, 4)}-${NumberText.padded(month.toLong(), 2)}-${NumberText.padded(day.toLong(), 2)}"
    }

    /** "2026-11-18T22:15:00Z" for whole seconds since 1970-01-01T00:00:00Z (an `xsd:dateTime` in UTC). */
    fun dateTimeText(epochSeconds: Long): String {
        val days = floorDivide(epochSeconds, 86_400L)
        val secondOfDay = epochSeconds - days * 86_400
        val hour = secondOfDay / 3_600
        val minute = secondOfDay % 3_600 / 60
        val second = secondOfDay % 60
        return "${dateText(days)}T${NumberText.padded(hour, 2)}:${NumberText.padded(minute, 2)}:${NumberText.padded(second, 2)}Z"
    }

    /** [text] with the characters XML 1.0 cannot carry removed and `& < > " '` escaped. */
    fun xml(text: String): String {
        val result = StringBuilder(text.length + 8)
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            when (scalar) {
                '&'.code -> result.append("&amp;")
                '<'.code -> result.append("&lt;")
                '>'.code -> result.append("&gt;")
                '"'.code -> result.append("&quot;")
                '\''.code -> result.append("&apos;")
                0x9, 0xA, 0xD -> result.appendCodePoint(scalar)
                in 0x20..0xD7FF, in 0xE000..0xFFFD, in 0x10000..0x10FFFF -> result.appendCodePoint(scalar)
                else -> Unit
            }
        }
        return result.toString()
    }

    /** Whether a scalar is dropped from user text: control, format, private-use, unassigned and surrogate code points. */
    private fun isDropped(scalar: Int): Boolean = when (Character.getType(scalar).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> true
        else -> false
    }

    /**
     * [text] on one line: control characters and runs of white space become single spaces, the ends are trimmed and the
     * result is cut to [limit] Unicode scalars.
     */
    fun singleLine(text: String, limit: Int): String {
        val result = StringBuilder()
        var kept = 0
        var pendingSpace = false
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            if (CatalogText.isWhiteSpace(scalar) || CatalogText.isControl(scalar)) {
                pendingSpace = kept > 0
                continue
            }
            if (isDropped(scalar)) continue
            val needed = if (pendingSpace) 2 else 1
            if (kept + needed > limit) break
            if (pendingSpace) {
                result.append(' ')
                pendingSpace = false
            }
            result.appendCodePoint(scalar)
            kept += needed
        }
        return result.toString()
    }

    /**
     * [text] with control characters other than line feeds removed, carriage returns (and CRLF) read as line feeds, tabs
     * read as spaces, and the result cut to [limit] Unicode scalars: for notes that may have several lines.
     */
    fun multiLine(text: String, limit: Int): String {
        val result = StringBuilder()
        var kept = 0
        var previousWasReturn = false
        var index = 0
        while (index < text.length) {
            if (kept >= limit) break
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            val wasReturn = previousWasReturn
            previousWasReturn = scalar == 0x0D
            if (scalar == 0x0D || scalar == 0x0A) {
                if (scalar == 0x0A && wasReturn) continue
                result.append('\n')
                kept += 1
                continue
            }
            if (scalar == 0x09) {
                result.append(' ')
                kept += 1
                continue
            }
            if (isDropped(scalar)) continue
            result.appendCodePoint(scalar)
            kept += 1
        }
        return result.toString()
    }
}

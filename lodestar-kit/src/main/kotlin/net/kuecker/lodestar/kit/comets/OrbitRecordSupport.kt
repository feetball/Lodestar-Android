package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*

/** A line of fixed-width text addressed by 1-based inclusive column ranges, as the MPC documents its formats. */
internal class FixedWidthLine(text: String) {
    private val scalars: IntArray = text.codePoints().toArray()

    /** The number of characters (Unicode scalars) in the line. */
    val length: Int get() = scalars.size

    /**
     * The characters of columns [first] to [last] without surrounding spaces and tabs; empty if the columns are
     * blank or beyond the end of the line.
     */
    fun text(first: Int, last: Int): String {
        var from = first - 1
        var to = if (last < scalars.size) last else scalars.size
        while (from < to && (scalars[from] == 0x20 || scalars[from] == 0x09)) from++
        while (to > from && (scalars[to - 1] == 0x20 || scalars[to - 1] == 0x09)) to--
        if (from >= to) return ""
        return String(scalars, from, to - from)
    }
}

/** Strict number and calendar helpers for the record parsers. */
internal object RecordFields {
    /** The longest field text kept in an error payload. */
    const val errorTextLength: Int = 40

    /** [text] cut to [errorTextLength] characters (Unicode scalars), for an error payload. */
    fun excerpt(text: String): String {
        val scalars = text.codePoints().toArray()
        return if (scalars.size > errorTextLength) String(scalars, 0, errorTextLength) else text
    }

    /**
     * Reads a plain decimal number: an optional sign, digits with an optional point (`12`, `-0.5`, `.5`, `5.`),
     * and, if [allowExponent], an exponent (`3.7E-6`). Nothing else is accepted (no NaN, no infinity, no hex, no
     * suffixes, no embedded spaces). Returns `null` for anything else or a value that is not finite.
     */
    fun decimal(text: String, allowExponent: Boolean = false): Double? {
        val count = text.length
        if (count == 0 || count > 40) return null
        var index = 0
        if (text[index] == '+' || text[index] == '-') index++
        var digits = 0
        while (index < count && text[index] in '0'..'9') {
            index++
            digits++
        }
        if (index < count && text[index] == '.') {
            index++
            while (index < count && text[index] in '0'..'9') {
                index++
                digits++
            }
        }
        if (digits == 0) return null
        if (allowExponent && index < count && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < count && (text[index] == '+' || text[index] == '-')) index++
            var exponentDigits = 0
            while (index < count && text[index] in '0'..'9') {
                index++
                exponentDigits++
            }
            if (exponentDigits == 0) return null
        }
        if (index != count) return null
        val value = text.toDouble()
        return if (value.isFinite()) value else null
    }

    /** Reads an integer of up to nine digits with an optional sign; `null` for anything else. */
    fun integer(text: String): Int? {
        val count = text.length
        if (count == 0) return null
        var index = 0
        if (text[index] == '+' || text[index] == '-') index++
        if (index == count || count - index > 9) return null
        for (position in index until count) {
            if (text[position] !in '0'..'9') return null
        }
        return text.toInt()
    }

    /** The number of days in a month of the Gregorian calendar. */
    fun daysInMonth(year: Int, month: Int): Int {
        if (month == 2) {
            val leap = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
            return if (leap) 29 else 28
        }
        return if (month == 4 || month == 6 || month == 9 || month == 11) 30 else 31
    }

    /** The first year of the Gregorian calendar that the parsers accept, 1583. */
    const val minimumYear: Int = 1583

    /** The last year that the parsers accept, 9999. */
    const val maximumYear: Int = 9999

    /** The Julian Day of 0h on a Gregorian calendar date. */
    fun julianDay(year: Int, month: Int, day: Int): Double = JulianDate.julianDay(year, month, day)
}

/** A calendar date unpacked from the MPC's five-character packed form. */
internal data class PackedDate(val year: Int, val month: Int, val day: Int)

/**
 * Unpacking of the Minor Planet Center's packed designations and dates
 * (https://www.minorplanetcenter.net/iau/info/PackedDes.html).
 */
internal object PackedDesignation {
    /** The value of a base-62 digit: 0–9, A–Z (10–35), a–z (36–61); `null` for any other character. */
    fun value(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'A'..'Z' -> character - 'A' + 10
        in 'a'..'z' -> character - 'a' + 36
        else -> null
    }

    private fun century(character: Char): Int? = when (character) {
        'I' -> 18
        'J' -> 19
        'K' -> 20
        else -> null
    }

    private fun digit(character: Char): Int? = if (character in '0'..'9') character - '0' else null

    private fun isHalfMonthLetter(character: Char): Boolean = character in 'A'..'Z' && character != 'I'

    /** The minor-planet number of a five-character packed number (`00433`, `A0001`, `~0000`), or `null`. */
    fun number(text: String): Int? {
        if (text.length != 5) return null
        val first = text[0]
        if (first == '~') {
            var total = 0
            for (position in 1..4) {
                val v = value(text[position]) ?: return null
                total = total * 62 + v
            }
            return 620_000 + total
        }
        val leading = value(first) ?: return null
        var rest = 0
        for (position in 1..4) {
            val v = digit(text[position]) ?: return null
            rest = rest * 10 + v
        }
        val number = leading * 10_000 + rest
        return if (number >= 1) number else null
    }

    /** The cycle count or comet order of the two characters at positions 4 and 5 of a packed provisional designation. */
    private fun order(high: Char, low: Char): Int? {
        val h = value(high) ?: return null
        val l = digit(low) ?: return null
        return h * 10 + l
    }

    /**
     * The readable provisional designation of a seven-character packed minor-planet designation: `J95X01L` is
     * `1995 XL1`, `K08Aa0A` is `2008 AA360`, and the survey designations `PLS2040` and `T1S3138` are `2040 P-L` and
     * `3138 T-1`. `null` if the text is not a valid packed designation.
     */
    fun minorPlanet(text: String): String? {
        if (text.length != 7) return null
        val prefix = text.substring(0, 3)
        val survey = when (prefix) {
            "PLS" -> "P-L"
            "T1S" -> "T-1"
            "T2S" -> "T-2"
            "T3S" -> "T-3"
            else -> null
        }
        if (survey != null) {
            var number = 0
            for (position in 3..6) {
                val v = digit(text[position]) ?: return null
                number = number * 10 + v
            }
            return if (number >= 1) "$number $survey" else null
        }
        val century = century(text[0]) ?: return null
        val tens = digit(text[1]) ?: return null
        val ones = digit(text[2]) ?: return null
        if (!isHalfMonthLetter(text[3])) return null
        val cycle = order(text[4], text[5]) ?: return null
        if (!isHalfMonthLetter(text[6])) return null
        val year = century * 100 + tens * 10 + ones
        return "$year ${text[3]}${text[6]}${if (cycle > 0) cycle.toString() else ""}"
    }

    /**
     * The readable provisional designation of a seven-character packed comet designation without the type letter:
     * `J95A010` is `1995 A1`, `K88AA30` is `2088 A103` and a split comet `J94P01b` is `1994 P1-B`. `null` if invalid.
     */
    fun cometProvisional(text: String): String? {
        if (text.length != 7) return null
        val century = century(text[0]) ?: return null
        val tens = digit(text[1]) ?: return null
        val ones = digit(text[2]) ?: return null
        if (!isHalfMonthLetter(text[3])) return null
        val order = order(text[4], text[5]) ?: return null
        if (order < 1) return null
        val fragment = text[6]
        val suffix = when {
            fragment == '0' -> ""
            fragment in 'a'..'z' -> "-${fragment.uppercaseChar()}"
            else -> return null
        }
        val year = century * 100 + tens * 10 + ones
        return "$year ${text[3]}$order$suffix"
    }

    /**
     * The date of a five-character packed date (`K2669` is 2026 June 9), or `null`: century letter, two year digits,
     * month `1`–`9`, `A`–`C`, day `1`–`9`, `A`–`V`. The day must exist in that month.
     */
    fun date(text: String): PackedDate? {
        if (text.length != 5) return null
        val century = century(text[0]) ?: return null
        val tens = digit(text[1]) ?: return null
        val ones = digit(text[2]) ?: return null
        val month = value(text[3]) ?: return null
        val day = value(text[4]) ?: return null
        if (month < 1 || month > 12 || day < 1 || day > 31) return null
        val year = century * 100 + tens * 10 + ones
        if (day > RecordFields.daysInMonth(year, month)) return null
        return PackedDate(year, month, day)
    }
}

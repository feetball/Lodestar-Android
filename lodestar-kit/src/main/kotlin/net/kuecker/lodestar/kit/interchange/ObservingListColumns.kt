package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.parseDeclination
import net.kuecker.lodestar.kit.formatting.parseRightAscension

/**
 * The columns of a CSV table that [ObservingListCodec] uses, found by their header names.
 *
 * Header names are compared without case, punctuation and spacing; "Right Ascension (j2000)", "RA_J2000" and "ra" all
 * name a right ascension. Columns of coordinates of the date (jNow) or of the B1950 equinox are ignored, because the codec
 * returns J2000 places.
 */
internal class ObservingListColumns private constructor() {
    /** What a coordinate column says about its unit. */
    enum class ColumnUnit {
        /** The header gives no unit: a plain number is read as degrees, sexagesimal text as the parser reads it. */
        UNSPECIFIED,
        DEGREES,
        HOURS,
    }

    var designation: Int? = null
        private set
    var name: Int? = null
        private set

    /** Telescopius' "Label", the custom name of a list entry. */
    var label: Int? = null
        private set
    var alternates: Int? = null
        private set
    var rightAscension: Int? = null
        private set
    var declination: Int? = null
        private set
    var rightAscensionUnit = ColumnUnit.UNSPECIFIED
        private set
    var isTelescopius = false
        private set

    companion object {
        private val designationHeaders = setOf(
            "designation", "catalogue entry", "catalog entry", "catalogue", "catalog", "catalogue number", "catalog number",
            "object", "object id", "target id", "id",
        )
        private val nameHeaders = setOf("name", "familiar name", "common name", "target", "target name", "object name")
        private val alternateHeaders = setOf("alternative entries", "alternate entries", "aliases", "alias")
        private val rightAscensionFirstTokens = setOf("ra", "ra2000", "raj2000")
        private val declinationFirstTokens = setOf("dec", "dec2000", "decj2000", "declination", "decl")
        private val otherEpochTokens = setOf("jnow", "b1950", "date")

        /** The header's words: lower case letters and digits, split at everything else. */
        fun tokens(header: String): List<String> {
            val result = ArrayList<String>()
            val current = StringBuilder()
            var index = 0
            while (index < header.length) {
                val scalar = header.codePointAt(index)
                index += Character.charCount(scalar)
                val type = Character.getType(scalar).toByte()
                val isWordScalar = Character.isLetterOrDigit(scalar) ||
                    type == Character.LETTER_NUMBER || type == Character.OTHER_NUMBER
                if (isWordScalar) {
                    current.appendCodePoint(scalar)
                } else if (current.isNotEmpty()) {
                    result.add(current.toString().lowercase())
                    current.setLength(0)
                }
            }
            if (current.isNotEmpty()) result.add(current.toString().lowercase())
            return result
        }

        private fun unit(words: List<String>): ColumnUnit {
            for (word in words) {
                if (word == "deg" || word == "degs" || word == "degree" || word == "degrees") return ColumnUnit.DEGREES
                if (word == "h" || word == "hr" || word == "hrs" || word == "hour" || word == "hours") return ColumnUnit.HOURS
            }
            return ColumnUnit.UNSPECIFIED
        }

        /** Finds the columns, or returns null when the header has no name or designation column and no pair of coordinates. */
        fun create(header: List<String>): ObservingListColumns? {
            val columns = ObservingListColumns()
            for ((index, text) in header.withIndex()) {
                val words = tokens(text)
                val first = words.firstOrNull() ?: continue
                val key = words.joinToString(" ")
                val isOtherEpoch = words.any { otherEpochTokens.contains(it) }
                if (rightAscensionFirstTokens.contains(first) || (first == "right" && words.drop(1).firstOrNull() == "ascension")) {
                    if (!isOtherEpoch && columns.rightAscension == null) {
                        columns.rightAscension = index
                        columns.rightAscensionUnit = unit(words)
                    }
                } else if (declinationFirstTokens.contains(first)) {
                    if (!isOtherEpoch && columns.declination == null) columns.declination = index
                } else if (key == "label") {
                    if (columns.label == null) columns.label = index
                } else if (alternateHeaders.contains(key)) {
                    if (columns.alternates == null) columns.alternates = index
                } else if (designationHeaders.contains(key)) {
                    if (columns.designation == null) columns.designation = index
                    if (key == "catalogue entry") columns.isTelescopius = true
                } else if (nameHeaders.contains(key)) {
                    if (columns.name == null) columns.name = index
                }
            }
            val hasCoordinates = columns.rightAscension != null && columns.declination != null
            if (columns.designation == null && columns.name == null && columns.label == null && !hasCoordinates) return null
            return columns
        }
    }
}

/** Reads the coordinates of import files with the shared angle parsers. */
internal object ObservingListCoordinates {
    /** Arcseconds that Telescopius writes as two quotation marks (`03h 46' 24""`) become one. */
    private fun normalized(text: String): String = text.replace("\"\"", "\"")

    /** The value of text that is only a number (optional "+", digits, one "." or "," decimal mark), or null. */
    private fun plainNumber(text: String): Double? {
        val integer = StringBuilder()
        val fraction = StringBuilder()
        var hasMark = false
        var isFirst = true
        for (character in text) {
            if (isFirst && character == '+') {
                isFirst = false
                continue
            }
            isFirst = false
            if (character in '0'..'9') {
                if (hasMark) fraction.append(character) else integer.append(character)
            } else if ((character == '.' || character == ',') && !hasMark) {
                hasMark = true
            } else {
                return null
            }
        }
        if (integer.isEmpty() && fraction.isEmpty()) return null
        return "${if (integer.isEmpty()) "0" else integer}.${if (fraction.isEmpty()) "0" else fraction}".toDoubleOrNull()
    }

    /**
     * A right ascension in degrees, [0, 360), or null.
     *
     * A plain number is degrees unless [unit] says hours; sexagesimal text is hours of time unless it is marked with `°`
     * or `d` (see [parseRightAscension]).
     */
    fun rightAscension(text: String, unit: ObservingListColumns.ColumnUnit): Double? {
        val cleaned = normalized(text)
        val number = plainNumber(cleaned)
        if (number != null) {
            val degrees = if (unit == ObservingListColumns.ColumnUnit.HOURS) number * 15 else number
            return if (degrees >= 0 && degrees < 360) degrees else null
        }
        val hours = AngleFormatter.parseRightAscension(cleaned) ?: return null
        return hours * 15
    }

    /** A declination in degrees, [-90, 90], or null. */
    fun declination(text: String): Double? = AngleFormatter.parseDeclination(normalized(text))
}

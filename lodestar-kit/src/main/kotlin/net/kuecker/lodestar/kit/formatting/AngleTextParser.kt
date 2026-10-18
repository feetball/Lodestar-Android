package net.kuecker.lodestar.kit.formatting

import kotlin.math.abs

/**
 * Parser for sexagesimal and decimal angle text typed or pasted by a person.
 *
 * The text is split into tokens (numbers, signs, unit marks, hemisphere letters and colons, see
 * `AngleTextParserLexing.kt`) and then matched against one grammar:
 *
 *     value  = [hemisphere] [sign] field { separator field } [hemisphere]
 *     field  = number [unit]
 *     unit   = h | d | ° | m | ′ | s | ″          (in that order, one per field)
 *
 * Anything the grammar does not cover, including every ambiguous input, is rejected with null.
 * The parser never throws: the input is length-capped, only ASCII digits are read, numbers are
 * converted from sanitised digit strings, and all arithmetic is on finite `Double`s.
 */
internal object AngleTextParser {
    /** The kind of value being read, which decides the letters, units and range that are valid. */
    enum class Quantity {
        /** Hours of right ascension in [0, 24); `n`, `e` and `w` are not accepted. */
        RIGHT_ASCENSION,

        /** Declination or latitude in [-90, 90]; hemisphere letters `n` and `s`. */
        DECLINATION,

        /** Longitude in (-180, 180]; hemisphere letters `e` and `w`. */
        LONGITUDE,
    }

    /** Longest accepted input, in Unicode scalars. */
    const val maximumLength = 64

    // MARK: Tokens

    /** What a unit mark stands for, by position within the value. */
    enum class Mark {
        /** `h`: hours of time. Only valid on the first field of a right ascension. */
        HOUR,

        /** `d`, `°`: degrees. On a right ascension they request the degrees form. */
        DEGREE,

        /** `m`, `′`: minutes of time or arc. */
        MINUTE,

        /** `s`, `″`: seconds of time or arc. */
        SECOND,
    }

    /** A unit mark and whether it was written as a letter (`d`, `m`, `s`) or as a symbol. */
    data class UnitMark(val kind: Mark, val isLetter: Boolean)

    /** A lexical token and whether whitespace came before it. */
    data class Token(
        val kind: Kind,
        /** Whether whitespace separated this token from the previous one. */
        val followsSpace: Boolean,
    ) {
        sealed interface Kind {
            /** An unsigned decimal number; [isWhole] is false when it had a decimal mark. */
            data class Number(val value: Double, val isWhole: Boolean) : Kind

            /** A plus or a minus (or any Unicode hyphen or dash used as one). */
            data class Sign(val isNegative: Boolean) : Kind

            /** The field separator ":". */
            data object Colon : Kind

            /** A unit mark (Swift `.mark(UnitMark)`, whose payload is unlabeled). */
            data class Mark(val unit: UnitMark) : Kind

            /** A hemisphere letter; [isNegative] is true for south and west. */
            data class Hemisphere(val isNegative: Boolean) : Kind

            /**
             * The letter `s` in a declination or latitude, where it is either the seconds unit
             * or the southern hemisphere.
             */
            data object SouthOrSeconds : Kind
        }
    }

    /** One parsed `number [unit]` field. */
    private data class Field(val value: Double, val isWhole: Boolean, val mark: UnitMark?)

    /** A cursor over the token list. */
    private class TokenReader(private val tokens: List<Token>) {
        private var position = 0

        val isAtEnd: Boolean
            get() = position >= tokens.size

        val upcoming: Token?
            get() = if (isAtEnd) null else tokens[position]

        val upcomingIsNumber: Boolean
            get() = upcoming?.kind is Token.Kind.Number

        /** Whether the next token is an "s" written directly against the token before it. */
        val upcomingIsAttachedSouthOrSeconds: Boolean
            get() {
                val token = upcoming ?: return false
                if (token.kind != Token.Kind.SouthOrSeconds) return false
                return !token.followsSpace
            }

        /** Consumes a hemisphere letter and returns whether it is the negative one (south or west). */
        fun takeHemisphere(): Boolean? {
            return when (val kind = upcoming?.kind) {
                is Token.Kind.Hemisphere -> {
                    position += 1
                    kind.isNegative
                }
                Token.Kind.SouthOrSeconds -> {
                    position += 1
                    true
                }
                else -> null
            }
        }

        /** Consumes a sign and returns whether it is a minus. */
        fun takeSign(): Boolean? {
            val kind = upcoming?.kind as? Token.Kind.Sign ?: return null
            position += 1
            return kind.isNegative
        }

        fun takeNumber(): Token.Kind.Number? {
            val kind = upcoming?.kind as? Token.Kind.Number ?: return null
            position += 1
            return kind
        }

        fun takeMark(): UnitMark? {
            val kind = upcoming?.kind as? Token.Kind.Mark ?: return null
            position += 1
            return kind.unit
        }

        fun takeColon(): Boolean {
            if (upcoming?.kind != Token.Kind.Colon) return false
            position += 1
            return true
        }

        /** Consumes the upcoming token, which the caller has already inspected. */
        fun skip() {
            position += 1
        }
    }

    // MARK: Parsing

    /**
     * Parses [text] as the given [quantity], returning hours for right ascension and degrees
     * otherwise, or null when the text is not valid.
     */
    fun parse(text: String, quantity: Quantity): Double? {
        val scalars = boundedScalars(text) ?: return null
        val tokens = tokenize(scalars, quantity) ?: return null

        val reader = TokenReader(tokens)
        val leadingHemisphere = reader.takeHemisphere()
        val explicitSign = reader.takeSign()
        val fields = readFields(reader) ?: return null
        val trailingHemisphere = reader.takeHemisphere()

        // A hemisphere letter may lead or trail the value, not both.
        if (!reader.isAtEnd) return null
        if (leadingHemisphere != null && trailingHemisphere != null) return null
        val magnitude = combinedMagnitude(fields) ?: return null
        val isNegative = mergedSign(leadingHemisphere ?: trailingHemisphere, explicitSign) ?: return null

        return value(magnitude, isNegative, fields[0].mark?.kind, quantity)
    }

    /**
     * The Unicode scalars of [text], or null when there are more than [maximumLength] of them.
     * Reads at most one scalar past the limit, so a huge input is rejected without being walked.
     */
    private fun boundedScalars(text: String): IntArray? {
        val scalars = IntArray(maximumLength)
        var count = 0
        var offset = 0
        while (offset < text.length) {
            if (count == maximumLength) return null
            val scalar = text.codePointAt(offset)
            scalars[count] = scalar
            count += 1
            offset += Character.charCount(scalar)
        }
        return if (count == maximumLength) scalars else scalars.copyOf(count)
    }

    /** Reads one to three `number [unit]` fields, separated by whitespace or colons. */
    private fun readFields(reader: TokenReader): List<Field>? {
        val fields = ArrayList<Field>(3)
        while (true) {
            val number = reader.takeNumber() ?: return null

            var mark = reader.takeMark()
            if (mark == null && fields.size == 2 && reader.upcomingIsAttachedSouthOrSeconds) {
                // "40d30m15s" reads the attached letter as the seconds unit. That is only
                // unambiguous when degrees and minutes were also written as letters; "40 30 15S"
                // could equally mean 40°30′15″ south, so it is rejected.
                if (fields[0].mark != UnitMark(Mark.DEGREE, isLetter = true) ||
                    fields[1].mark != UnitMark(Mark.MINUTE, isLetter = true)
                ) {
                    return null
                }
                reader.skip()
                mark = UnitMark(Mark.SECOND, isLetter = true)
            }
            if (!markFits(mark, fields.size)) return null
            fields.add(Field(number.value, number.isWhole, mark))

            // After a colon another field must follow; otherwise fields continue only while
            // numbers do.
            if (!(reader.takeColon() || reader.upcomingIsNumber)) break
        }
        return if (fields.size <= 3) fields else null
    }

    /**
     * Whether a unit mark is allowed on the field at [index]: hours or degrees first, then
     * minutes, then seconds.
     */
    private fun markFits(mark: UnitMark?, index: Int): Boolean {
        if (mark == null) return true
        return when (mark.kind) {
            Mark.HOUR, Mark.DEGREE -> index == 0
            Mark.MINUTE -> index == 1
            Mark.SECOND -> index == 2
        }
    }

    /**
     * Combines the fields into one value in the units of the first field, or returns null unless
     * only the last field is fractional and minutes and seconds are below 60.
     */
    private fun combinedMagnitude(fields: List<Field>): Double? {
        var total = 0.0
        var divisor = 1.0
        for ((index, field) in fields.withIndex()) {
            if (index < fields.size - 1 && !field.isWhole) return null
            if (index > 0 && field.value >= 60.0) return null
            total += field.value / divisor
            divisor *= 60.0
        }
        return total
    }

    /**
     * Merges a hemisphere letter and an explicit sign into one sign, returning whether the value is
     * negative. A sign next to a hemisphere letter is redundant at best and contradictory at worst,
     * so only the harmless "+40 N" is accepted.
     */
    private fun mergedSign(hemisphere: Boolean?, sign: Boolean?): Boolean? {
        if (hemisphere == null) return sign ?: false
        if (sign == null) return hemisphere
        return if (!hemisphere && !sign) false else null
    }

    /** Applies the unit and range rules of [quantity] to a parsed magnitude. */
    private fun value(magnitude: Double, isNegative: Boolean, firstMark: Mark?, quantity: Quantity): Double? {
        return when (quantity) {
            Quantity.RIGHT_ASCENSION -> {
                // A right ascension is hours unless degrees were explicitly requested with "°" or "d".
                if (isNegative || !(firstMark == null || firstMark == Mark.HOUR || firstMark == Mark.DEGREE)) {
                    return null
                }
                val hours = if (firstMark == Mark.DEGREE) magnitude / 15.0 else magnitude
                if (hours >= 0.0 && hours < 24.0) hours else null
            }

            Quantity.DECLINATION -> {
                if (!(firstMark == null || firstMark == Mark.DEGREE)) return null
                val degrees = if (isNegative) -magnitude else magnitude
                if (abs(degrees) <= 90.0) normalizingZero(degrees) else null
            }

            Quantity.LONGITUDE -> {
                if (!(firstMark == null || firstMark == Mark.DEGREE)) return null
                var degrees = if (isNegative) -magnitude else magnitude
                // West-negative values reach -180°; east-positive values may run to 360°.
                if (!(degrees >= -180.0 && degrees <= 360.0)) return null
                if (degrees > 180.0) {
                    degrees -= 360.0
                } else if (degrees <= -180.0) {
                    degrees += 360.0
                }
                normalizingZero(degrees)
            }
        }
    }

    /** Returns positive zero for negative zero, so "-0" never produces "-0.0". */
    private fun normalizingZero(value: Double): Double = if (value == 0.0) 0.0 else value
}

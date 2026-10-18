package net.kuecker.lodestar.kit.formatting

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Formats angles, sky and geographic coordinates, durations and exposure times for display, and
 * parses angles and coordinates from text.
 *
 * The duration and exposure formatters and the `parse…` functions are extension functions declared
 * in `AngleFormatterDurations.kt` and `AngleFormatterParsing.kt`; import them from this package
 * (`import net.kuecker.lodestar.kit.formatting.*`) to call them as `AngleFormatter.formatDuration(…)`.
 *
 * ## Formatting
 *
 * Output is independent of the user's locale: the decimal mark is always ".", and negative
 * values use U+2212 (or "-" in the [TypographyStyle.ASCII] style). Rounding is applied once at the
 * finest displayed unit, so carries behave as a reader expects: 59.96 s becomes the next minute
 * and 23h 59m 59.99s becomes 00h 00m 00.0s. Values that are not finite, or that cannot be
 * rendered, produce a placeholder (an em dash, or "--" in the ASCII style). The number of decimals
 * is clamped to 0...6.
 *
 * ## Parsing
 *
 * The `parse…` functions read text typed or pasted by a person. They return null for anything
 * they do not fully understand, and never throw. The rules shared by all of them:
 *
 * - Input longer than 64 characters (Unicode scalars) is rejected.
 * - Letters are case-insensitive, and whitespace around and between fields is ignored.
 * - A minus sign may be "-", U+2212 or any Unicode hyphen or dash used as one, such as the en
 *   dash. A degree mark is `°` (also `º` and `˚`) or the letter `d`; minutes and seconds are
 *   marked with `′` `″`, the apostrophe and quotation marks that smart punctuation produces
 *   instead (`'` `"` `’` `”`), two apostrophes for seconds, or the letters `m` and `s`.
 * - The decimal mark is "." or ",". Only ASCII digits are accepted.
 * - Fields are separated by whitespace, ":" or their unit marks, so `05:35:17`, `5 35 17`,
 *   `5h35m17s` and `5h 35m 17s` are equivalent. There are at most three fields, only the last may
 *   be fractional, and minutes and seconds must be below 60.
 * - A leading hemisphere letter or sign applies to the whole value, so `−00° 30′` is -0.5 even
 *   though its degrees are zero. A hemisphere letter together with a minus sign is rejected, as
 *   is a hemisphere letter at both ends.
 * - Text produced by the angle and coordinate formatters, in either [TypographyStyle], can be
 *   parsed back.
 */
object AngleFormatter {

    // MARK: Right ascension and declination

    /**
     * Formats a right ascension as hours, minutes and seconds, for example "05h 35m 17.3s".
     *
     * The value is wrapped into [0h, 24h) before formatting, so -1 h is "23h 00m 00.0s" and
     * 24 h is "00h 00m 00.0s".
     *
     * @param hours Right ascension in hours.
     * @param secondsDecimals Decimal places of the seconds field.
     * @param style The typography to use.
     */
    fun formatRightAscension(
        hours: Double,
        secondsDecimals: Int = 1,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        if (!hours.isFinite()) return style.placeholder
        var wrapped = hours % 24.0
        if (wrapped < 0.0) wrapped += 24.0
        val parts = NumberText.sexagesimal(wrapped, secondsDecimals) ?: return style.placeholder
        // Rounding can carry 23h 59m 59.99s up to 24h, which is 0h.
        val hoursText = NumberText.padded(parts.whole % 24L, 2)
        return "${hoursText}h ${NumberText.padded(parts.minutes, 2)}m ${parts.secondsText}s"
    }

    /**
     * Formats a declination with an explicit sign, for example "−05° 23′ 28″" or "+41° 16′ 09″".
     *
     * Zero, and values that round to zero, carry a plus sign. Values beyond ±90° are clamped.
     *
     * @param degrees Declination in degrees.
     * @param secondsDecimals Decimal places of the arcsecond field.
     * @param style The typography to use.
     */
    fun formatDeclination(
        degrees: Double,
        secondsDecimals: Int = 0,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        if (!degrees.isFinite()) return style.placeholder
        val clamped = min(max(degrees, -90.0), 90.0)
        val parts = NumberText.sexagesimal(abs(clamped), secondsDecimals) ?: return style.placeholder
        val sign = if (clamped < 0.0 && !parts.isZero) style.minus else "+"
        return degreesMinutesSeconds(parts, sign, 2, style)
    }

    // MARK: Decimal and sexagesimal degrees

    /**
     * Formats an angle in decimal degrees without wrapping, for example "123.4°".
     *
     * Negative values get a minus sign; a value that rounds to zero does not.
     */
    fun formatDegrees(
        degrees: Double,
        decimals: Int = 1,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        val value = NumberText.fixedPoint(abs(degrees), decimals) ?: return style.placeholder
        val sign = if (degrees < 0.0 && !value.isZero) style.minus else ""
        return sign + value.text + style.degree
    }

    /**
     * Formats an angle in decimal degrees with an explicit sign, for example "+45.3°" or "−5.2°".
     *
     * Zero, and values that round to zero, carry a plus sign.
     */
    fun formatSignedDegrees(
        degrees: Double,
        decimals: Int = 1,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        val value = NumberText.fixedPoint(abs(degrees), decimals) ?: return style.placeholder
        val sign = if (degrees < 0.0 && !value.isZero) style.minus else "+"
        return sign + value.text + style.degree
    }

    /**
     * Formats an azimuth wrapped into [0°, 360°), for example "123.4°".
     *
     * Wrapping happens after rounding, so 359.96° at one decimal reads "0.0°" and never "360.0°".
     */
    fun formatAzimuth(
        degrees: Double,
        decimals: Int = 1,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        if (!degrees.isFinite()) return style.placeholder
        var wrapped = degrees % 360.0
        if (wrapped < 0.0) wrapped += 360.0
        val value = NumberText.fixedPoint(wrapped, decimals) ?: return style.placeholder
        val turn = 360L * NumberText.powerOfTen(value.decimals)
        val carried = NumberText.FixedPoint(units = value.units % turn, decimals = value.decimals)
        return carried.text + style.degree
    }

    /**
     * Formats an angle as degrees, arcminutes and arcseconds, for example "123° 27′ 24″".
     *
     * Degrees are not padded; minutes and seconds always have two integer digits. Negative values
     * get a minus sign unless they round to zero.
     */
    fun formatDMS(
        degrees: Double,
        secondsDecimals: Int = 0,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        val parts = NumberText.sexagesimal(abs(degrees), secondsDecimals) ?: return style.placeholder
        val sign = if (degrees < 0.0 && !parts.isZero) style.minus else ""
        return degreesMinutesSeconds(parts, sign, 1, style)
    }

    // MARK: Geographic coordinates

    /**
     * Formats a latitude with a hemisphere letter, for example "40.0150° N".
     *
     * Values beyond ±90° are clamped. Zero, and values that round to zero, are "N".
     */
    fun formatLatitude(
        degrees: Double,
        decimals: Int = 4,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        if (!degrees.isFinite()) return style.placeholder
        val clamped = min(max(degrees, -90.0), 90.0)
        val value = NumberText.fixedPoint(abs(clamped), decimals) ?: return style.placeholder
        val hemisphere = if (clamped < 0.0 && !value.isZero) "S" else "N"
        return value.text + style.degree + " " + hemisphere
    }

    /**
     * Formats a longitude with a hemisphere letter, for example "105.2705° W".
     *
     * The longitude is wrapped into (-180°, 180°], so 190° is "170.0000° W" and -180° is
     * "180.0000° E". Zero, and values that round to zero, are "E".
     */
    fun formatLongitude(
        degrees: Double,
        decimals: Int = 4,
        style: TypographyStyle = TypographyStyle.UNICODE,
    ): String {
        if (!degrees.isFinite()) return style.placeholder
        var wrapped = degrees % 360.0
        if (wrapped > 180.0) {
            wrapped -= 360.0
        } else if (wrapped <= -180.0) {
            wrapped += 360.0
        }
        val value = NumberText.fixedPoint(abs(wrapped), decimals) ?: return style.placeholder
        // The antimeridian is written as east however it was reached.
        val isAntimeridian = value.units == 180L * NumberText.powerOfTen(value.decimals)
        val hemisphere = if (wrapped < 0.0 && !value.isZero && !isAntimeridian) "W" else "E"
        return value.text + style.degree + " " + hemisphere
    }

    // MARK: Compass

    /** The compass point whose sector contains an azimuth; see [CompassPoint.Companion.invoke]. */
    fun compassPoint(azimuth: Double): CompassPoint = CompassPoint(azimuth)

    // MARK: Helpers

    /** Assembles "±DD° MM′ SS″" from rounded parts. */
    private fun degreesMinutesSeconds(
        parts: NumberText.Sexagesimal,
        sign: String,
        degreesWidth: Int,
        style: TypographyStyle,
    ): String {
        val degrees = NumberText.padded(parts.whole, degreesWidth)
        val minutes = NumberText.padded(parts.minutes, 2)
        return "$sign$degrees${style.degree} $minutes${style.arcminute} ${parts.secondsText}${style.arcsecond}"
    }
}

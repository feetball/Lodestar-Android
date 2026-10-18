package net.kuecker.lodestar.kit.formatting

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Locale-independent number rendering built on integer arithmetic.
 *
 * Rounding happens once, on the finest displayed unit, and the result is split with integer
 * division. Rounding carries (59.96 s becoming the next minute, 359.96° becoming 0°) therefore
 * need no special cases, and output never depends on the user's locale.
 */
internal object NumberText {
    /** The largest number of decimal places the public formatters render; larger requests are clamped. */
    const val maximumDecimals = 6

    /** 2^53: every integer below this magnitude is exactly representable as a `Double`. */
    private const val exactIntegerLimit = 9_007_199_254_740_992.0

    /** 2^54: Swift writes a `Double` above this magnitude in exponential notation. */
    private const val exponentialLimit = 18_014_398_509_481_984.0

    /** Clamps a caller-supplied number of decimal places to `0...maximumDecimals`. */
    fun clampedDecimals(decimals: Int): Int = min(max(decimals, 0), maximumDecimals)

    /**
     * 10 raised to a small non-negative power. A negative exponent counts as zero. Like the Swift
     * original it traps (here: throws [ArithmeticException]) when the result does not fit 64 bits.
     */
    fun powerOfTen(exponent: Int): Long {
        var result = 1L
        repeat(max(exponent, 0)) { result = Math.multiplyExact(result, 10L) }
        return result
    }

    /** Left-pads the decimal digits of a non-negative integer with zeros. */
    fun padded(value: Long, width: Int): String {
        val digits = value.toString()
        return "0".repeat(max(0, width - digits.length)) + digits
    }

    // MARK: Fixed point

    /** A non-negative value scaled by 10^decimals and rounded half away from zero. */
    data class FixedPoint(
        /** The value multiplied by 10^[decimals]. */
        val units: Long,
        val decimals: Int,
    ) {
        /** Whether the value rounded to zero at the requested precision. */
        val isZero: Boolean
            get() = units == 0L

        /** The digits with a "." decimal mark and no sign, for example "40.0150". */
        val text: String
            get() {
                val scale = powerOfTen(decimals)
                val whole = (units / scale).toString()
                if (decimals <= 0) return whole
                return whole + "." + padded(units % scale, decimals)
            }
    }

    /**
     * Rounds a finite, non-negative value to [decimals] places, or returns null when the value is
     * negative, not finite or too large to be rendered exactly.
     */
    fun fixedPoint(magnitude: Double, decimals: Int): FixedPoint? {
        if (!magnitude.isFinite() || !(magnitude >= 0.0)) return null
        val places = clampedDecimals(decimals)
        val scaled = (magnitude * powerOfTen(places).toDouble()).roundedHalfAwayFromZero()
        if (!(scaled < exactIntegerLimit)) return null
        return FixedPoint(units = scaled.toLong(), decimals = places)
    }

    /**
     * Renders a non-negative value with at most [maxDecimals] places and without trailing zeros,
     * for example "2.5", "13" or "0.25".
     */
    fun trimmed(magnitude: Double, maxDecimals: Int): String? {
        val fixed = fixedPoint(magnitude, maxDecimals) ?: return null
        var text = fixed.text
        if (fixed.decimals > 0) {
            text = text.trimEnd('0').removeSuffix(".")
        }
        return text
    }

    /**
     * Renders a non-negative value with exactly [decimals] places for display text, falling back to
     * Swift's own description for values that cannot be scaled to an integer (huge or not finite).
     */
    fun fixedDescription(value: Double, decimals: Int): String =
        fixedPoint(value, decimals)?.text ?: swiftDescription(value)

    /**
     * Like [trimmed] for display text, with the same fallback as [fixedDescription].
     */
    fun trimmedDescription(value: Double, maxDecimals: Int): String =
        trimmed(value, maxDecimals) ?: swiftDescription(value)

    // MARK: Sexagesimal

    /** A non-negative quantity split into whole units, minutes and seconds. */
    data class Sexagesimal(
        /** Whole hours or degrees. */
        val whole: Long,
        /** Whole minutes, 0...59. */
        val minutes: Long,
        /** Seconds multiplied by 10^[decimals], below 60 × 10^[decimals]. */
        val secondsUnits: Long,
        val decimals: Int,
    ) {
        /** Whether every component rounded to zero at the requested precision. */
        val isZero: Boolean
            get() = whole == 0L && minutes == 0L && secondsUnits == 0L

        /** The seconds with at least two integer digits, for example "05" or "17.3". */
        val secondsText: String
            get() {
                val scale = powerOfTen(decimals)
                val integer = padded(secondsUnits / scale, 2)
                if (decimals <= 0) return integer
                return integer + "." + padded(secondsUnits % scale, decimals)
            }
    }

    /**
     * Splits a finite, non-negative quantity (hours or degrees) into whole units, minutes and
     * seconds, rounding at the requested seconds precision so that carries propagate upward.
     */
    fun sexagesimal(magnitude: Double, secondsDecimals: Int): Sexagesimal? {
        if (!magnitude.isFinite() || !(magnitude >= 0.0)) return null
        val places = clampedDecimals(secondsDecimals)
        val scale = powerOfTen(places)
        val unitsPerWhole = 3600L * scale
        val scaled = (magnitude * unitsPerWhole.toDouble()).roundedHalfAwayFromZero()
        if (!(scaled < exactIntegerLimit)) return null
        val total = scaled.toLong()
        val remainder = total % unitsPerWhole
        return Sexagesimal(
            whole = total / unitsPerWhole,
            minutes = remainder / (60L * scale),
            secondsUnits = remainder % (60L * scale),
            decimals = places,
        )
    }

    // MARK: Swift description

    /**
     * Swift's `description` of a `Double`, which string interpolation (`"\(value)"`) writes: the
     * shortest digit string that reads back as the same value, closest to the exact value, in plain
     * decimal notation ("36.0", "0.0001") or, for magnitudes below 0.0001 or above 2^54, in
     * exponential notation ("1e-05", "1.2345e+20"). Not a number is "nan", infinities are "inf" and
     * "-inf".
     *
     * This stands in for `Double.toString`, whose digits and notation differ between JVM versions
     * and Android releases; the result here depends only on the value.
     */
    fun swiftDescription(value: Double): String {
        if (value.isNaN()) return "nan"
        if (value.isInfinite()) return if (value > 0.0) "inf" else "-inf"
        val sign = if (value.toRawBits() < 0L) "-" else ""
        val magnitude = abs(value)
        if (magnitude == 0.0) return sign + "0.0"
        val shortest = shortestDigits(magnitude)
        val useExponent = shortest.pointPosition < -3 || magnitude > exponentialLimit
        return sign + if (useExponent) {
            exponentialNotation(shortest.digits, shortest.pointPosition)
        } else {
            decimalNotation(shortest.digits, shortest.pointPosition)
        }
    }

    /** Decimal digits without trailing zeros; the value is 0.[digits] × 10^[pointPosition]. */
    private class DecimalDigits(val digits: String, val pointPosition: Int)

    /** The rounding modes tried at each precision: nearest first, then the neighbours either side. */
    private val candidateRoundings = listOf(RoundingMode.HALF_EVEN, RoundingMode.FLOOR, RoundingMode.CEILING)

    /**
     * The shortest decimal that reads back as exactly [magnitude] (a finite, positive value). Among
     * decimals of that length the one closest to the exact value wins; the neighbours are tried
     * only when the nearest does not read back, which happens next to a power of two where the
     * interval of values that round to the same `Double` is lopsided.
     */
    private fun shortestDigits(magnitude: Double): DecimalDigits {
        val exact = BigDecimal(magnitude)
        for (precision in 1..17) {
            for (rounding in candidateRoundings) {
                val candidate = exact.round(MathContext(precision, rounding))
                if (candidate.toDouble() == magnitude) return decimalDigits(candidate)
            }
        }
        // Seventeen significant digits always read back, so the loop above has returned already.
        return decimalDigits(exact.round(MathContext(17, RoundingMode.HALF_EVEN)))
    }

    private fun decimalDigits(value: BigDecimal): DecimalDigits {
        val unscaled = value.unscaledValue().toString()
        return DecimalDigits(digits = unscaled.trimEnd('0'), pointPosition = unscaled.length - value.scale())
    }

    private fun decimalNotation(digits: String, pointPosition: Int): String = when {
        pointPosition <= 0 -> "0." + "0".repeat(-pointPosition) + digits
        pointPosition >= digits.length -> digits + "0".repeat(pointPosition - digits.length) + ".0"
        else -> digits.substring(0, pointPosition) + "." + digits.substring(pointPosition)
    }

    private fun exponentialNotation(digits: String, pointPosition: Int): String {
        val exponent = pointPosition - 1
        val mantissa = if (digits.length == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
        return mantissa + "e" + (if (exponent < 0) "-" else "+") + padded(abs(exponent).toLong(), 2)
    }
}

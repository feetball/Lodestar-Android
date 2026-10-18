package net.kuecker.lodestar.kit.foundation

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Swift `Double.rounded()` semantics: rounds half away from zero.
 *
 * `kotlin.math.round` rounds half to even and `Math.round` rounds half up, so neither matches Swift.
 */
fun Double.roundedHalfAwayFromZero(): Double {
    if (!isFinite()) return this
    val whole = truncate(this)
    return if (abs(this - whole) >= 0.5) whole + sign(this) else whole
}

/**
 * C `printf("%.<decimals>f")` semantics (what Swift's `String(format:)` produces): the exact binary
 * value rounded half-to-even, locale-independent, keeping the sign of negative values that round to
 * zero ("-0.0"). `String.format` differs on exact ties (it rounds the decimal expansion half-up).
 */
fun formatFixed(value: Double, decimals: Int): String {
    require(decimals in 0..20) { "decimals out of range: $decimals" }
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_EVEN).toPlainString()
    val negative = value < 0 || (value == 0.0 && 1.0 / value < 0)
    return if (negative && !text.startsWith("-")) "-$text" else text
}

/**
 * Swift's `min(x, y)` for `Double`: `y < x ? y : x`, so a NaN `y` is ignored while a NaN `x` is kept.
 *
 * `kotlin.math.min` returns NaN if either argument is NaN and orders `-0.0` below `0.0`; use this
 * wherever the Swift source calls `min` and a NaN or a signed zero can reach it.
 */
fun swiftMin(x: Double, y: Double): Double = if (y < x) y else x

/**
 * Swift's `max(x, y)` for `Double`: `y >= x ? y : x`, so a NaN `y` is ignored while a NaN `x` is kept.
 *
 * `kotlin.math.max` returns NaN if either argument is NaN and orders `-0.0` below `0.0`; use this
 * wherever the Swift source calls `max` and a NaN or a signed zero can reach it.
 */
fun swiftMax(x: Double, y: Double): Double = if (y >= x) y else x

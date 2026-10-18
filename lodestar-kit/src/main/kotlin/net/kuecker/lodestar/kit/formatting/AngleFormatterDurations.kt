package net.kuecker.lodestar.kit.formatting

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.abs
import kotlin.math.min

// Durations and camera exposure times are formatted by `AngleFormatter` as well, so that all
// display formatting shares one entry point, one `TypographyStyle` and one placeholder. Rounding
// carries propagate here too: 59.6 s is "1m 00s", never "0m 60s".

/** Durations beyond this many seconds (about 31,700 years) are treated as not renderable. */
private const val maximumDurationSeconds = 1e12

/**
 * Formats a duration with at most two units, for example "12s", "4m 30s" or "1h 05m".
 *
 * Below one minute the value is shown in whole seconds, below one hour in minutes and
 * seconds, and from one hour on in hours and whole minutes (seconds are dropped). Hours are
 * not folded into days. Negative durations get a minus sign unless they round to zero.
 */
fun AngleFormatter.formatDuration(seconds: Double, style: TypographyStyle = TypographyStyle.UNICODE): String {
    if (!seconds.isFinite() || !(abs(seconds) < maximumDurationSeconds)) return style.placeholder
    val magnitude = abs(seconds)

    val text: String
    val isZero: Boolean
    val wholeSeconds = magnitude.roundedHalfAwayFromZero().toLong()
    if (wholeSeconds < 60L) {
        text = "${wholeSeconds}s"
        isZero = wholeSeconds == 0L
    } else if (wholeSeconds < 3600L) {
        text = "${wholeSeconds / 60L}m ${NumberText.padded(wholeSeconds % 60L, 2)}s"
        isZero = false
    } else {
        // Round on the displayed unit so that 1h 59m 40s carries to "2h 00m".
        val wholeMinutes = (magnitude / 60.0).roundedHalfAwayFromZero().toLong()
        text = "${wholeMinutes / 60L}h ${NumberText.padded(wholeMinutes % 60L, 2)}m"
        isZero = false
    }
    return if (seconds < 0.0 && !isZero) style.minus + text else text
}

/**
 * Formats a camera exposure time the way a shutter-speed dial reads.
 *
 * - Below 0.25 s: a fraction, "1/250 s" (the denominator is the reciprocal rounded to an integer).
 * - 0.25 s up to 1 s: up to two decimals, "0.8 s" or "0.25 s".
 * - 1 s up to 10 s: up to one decimal, "2.5 s" or "4 s".
 * - 10 s up to a minute: whole seconds, "13 s".
 * - A minute or longer: as [formatDuration], "2m 30s".
 *
 * Zero, negative and non-finite exposures produce the placeholder.
 */
fun AngleFormatter.formatExposure(seconds: Double, style: TypographyStyle = TypographyStyle.UNICODE): String {
    if (!seconds.isFinite() || !(seconds > 0.0)) return style.placeholder

    if (seconds < 0.25) {
        // Capped so that absurdly short times cannot overflow the integer conversion.
        val denominator = min((1.0 / seconds).roundedHalfAwayFromZero(), 1e9)
        return "1/${denominator.toLong()} s"
    }
    if (seconds < 1.0) {
        return decimalSeconds(seconds, 2, style)
    }
    if (seconds < 10.0) {
        return decimalSeconds(seconds, 1, style)
    }
    // 59.5 s and above round up to a full minute and are shown as a duration.
    if (seconds < 59.5) {
        return "${seconds.roundedHalfAwayFromZero().toLong()} s"
    }
    return formatDuration(seconds, style)
}

private fun decimalSeconds(seconds: Double, maxDecimals: Int, style: TypographyStyle): String {
    val text = NumberText.trimmed(seconds, maxDecimals) ?: return style.placeholder
    return "$text s"
}

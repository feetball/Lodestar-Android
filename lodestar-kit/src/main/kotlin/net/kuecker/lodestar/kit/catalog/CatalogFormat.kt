package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.abs

/** Locale-independent number formatting for the catalog's display strings. */
internal object CatalogFormat {
    /** The minus sign used in display text (U+2212), as in the formatting module. */
    const val minus: String = "−"

    /**
     * A magnitude-style number with exactly one decimal, such as "3.4" or "−1.4".
     *
     * Values that round to zero print as "0.0" without a sign; non-finite values print as an em dash.
     */
    fun oneDecimal(value: Double): String {
        if (!value.isFinite()) return "—"
        val tenths = (minOf(abs(value), 1e9) * 10).roundedHalfAwayFromZero().toLong()
        val sign = if (value < 0 && tenths != 0L) minus else ""
        return "$sign${tenths / 10}.${tenths % 10}"
    }

    /** A non-negative number rounded to one decimal, without a trailing ".0" ("12", "8.4"). */
    fun trimmedOneDecimal(value: Double): String {
        if (!value.isFinite()) return "—"
        val tenths = (minOf(abs(value), 1e9) * 10).roundedHalfAwayFromZero().toLong()
        return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
    }
}

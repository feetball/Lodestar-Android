package net.kuecker.lodestar.kit.phototools

import kotlin.math.max
import kotlin.math.min

/** Limits numbers to a range, replacing values that are not numbers. */
internal object RangeLimiter {
    /** Returns [value] limited to [range], or [fallback] if [value] is not finite. */
    fun limit(value: Double, range: ClosedFloatingPointRange<Double>, fallback: Double): Double {
        if (!value.isFinite()) return fallback
        return min(max(value, range.start), range.endInclusive)
    }
}

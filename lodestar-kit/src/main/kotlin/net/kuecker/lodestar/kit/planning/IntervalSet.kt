package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * Arithmetic on sets of time intervals (Swift `NightPlanner.IntervalSet`).
 *
 * Every function accepts any intervals and returns intervals that are sorted, disjoint (touching
 * intervals are joined), longer than zero seconds and between finite dates. An `Instant` cannot be
 * NaN or infinite; [Instant.MIN] and [Instant.MAX], where the Foundation helpers saturate non-finite
 * dates, stand for "not finite" here.
 */
internal object IntervalSet {
    /** The union of [intervals] as sorted, disjoint intervals of positive length between finite dates. */
    fun normalized(intervals: List<DateInterval>): List<DateInterval> {
        val result = ArrayList<DateInterval>()
        for (interval in intervals.filter(::isUsable).sortedBy { it.start }) {
            val last = result.lastOrNull()
            if (last == null || interval.start > last.end) {
                result.add(interval)
                continue
            }
            if (interval.end > last.end) {
                result[result.size - 1] = DateInterval(last.start, interval.end)
            }
        }
        return result
    }

    /** Whether [interval] starts and ends at finite dates and is longer than zero seconds. */
    private fun isUsable(interval: DateInterval): Boolean =
        isFinite(interval.start) && isFinite(interval.end) && interval.duration > 0

    /** `false` for the instants that stand for infinite dates. */
    private fun isFinite(date: Instant): Boolean = date != Instant.MIN && date != Instant.MAX

    /** The time covered by both [first] and [second]. */
    fun intersection(first: List<DateInterval>, second: List<DateInterval>): List<DateInterval> {
        val left = normalized(first)
        val right = normalized(second)
        val result = ArrayList<DateInterval>()
        var i = 0
        var j = 0
        while (i < left.size && j < right.size) {
            val start = maxOf(left[i].start, right[j].start)
            val end = minOf(left[i].end, right[j].end)
            if (end > start) result.add(DateInterval(start, end))
            if (left[i].end < right[j].end) i += 1 else j += 1
        }
        return result
    }

    /** The time covered by [base] but not by [removed]. */
    fun subtracting(removed: List<DateInterval>, base: List<DateInterval>): List<DateInterval> {
        val cuts = normalized(removed)
        val result = ArrayList<DateInterval>()
        for (piece in normalized(base)) {
            var cursor = piece.start
            for (cut in cuts) {
                if (!(cut.end > cursor && cut.start < piece.end)) continue
                if (cut.start > cursor) result.add(DateInterval(cursor, cut.start))
                cursor = maxOf(cursor, cut.end)
            }
            if (cursor < piece.end) result.add(DateInterval(cursor, piece.end))
        }
        return result
    }

    /** The total length of [intervals] in seconds, counting overlapping time once. */
    fun totalDuration(intervals: List<DateInterval>): Double =
        normalized(intervals).fold(0.0) { total, interval -> total + interval.duration }
}

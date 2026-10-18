package net.kuecker.lodestar.kit.foundation

import java.time.Instant

/**
 * A closed span of time (Swift Foundation's `DateInterval`): a [start] and a non-negative
 * [duration] in seconds; [end] is `start + duration`.
 *
 * Time arithmetic follows [addingTimeInterval] / [timeIntervalSince], so intervals built from
 * `Double` seconds behave exactly like their Swift counterparts.
 *
 * @property start The first instant of the interval.
 * @property duration Length of the interval in seconds, never negative.
 */
data class DateInterval(val start: Instant, val duration: Double) : Comparable<DateInterval> {
    init {
        require(duration >= 0) { "Negative durations are not allowed" }
    }

    /** Creates the interval from [start] to [end]; [end] must not precede [start] (Swift `init(start:end:)`). */
    constructor(start: Instant, end: Instant) : this(start, durationBetween(start, end))

    /** The last instant of the interval, `start + duration`. */
    val end: Instant get() = start.addingTimeInterval(duration)

    /** `true` when [date] lies within the interval, endpoints included (also usable as `date in interval`). */
    operator fun contains(date: Instant): Boolean {
        val seconds = date.timeIntervalSinceReferenceDate
        return seconds >= start.timeIntervalSinceReferenceDate && seconds <= end.timeIntervalSinceReferenceDate
    }

    /** `true` when this interval and [dateInterval] share at least one instant. */
    fun intersects(dateInterval: DateInterval): Boolean =
        contains(dateInterval.start) || contains(dateInterval.end) ||
            dateInterval.contains(start) || dateInterval.contains(end)

    /** The interval where this one and [dateInterval] overlap, or `null` when they do not intersect. */
    fun intersection(dateInterval: DateInterval): DateInterval? {
        if (!intersects(dateInterval)) return null
        if (this == dateInterval) return this
        val resultStart = if (dateInterval.start.timeIntervalSinceReferenceDate >= start.timeIntervalSinceReferenceDate) {
            dateInterval.start
        } else {
            start
        }
        val resultEnd = if (dateInterval.end.timeIntervalSinceReferenceDate >= end.timeIntervalSinceReferenceDate) {
            end
        } else {
            dateInterval.end
        }
        return DateInterval(resultStart, resultEnd)
    }

    /** Orders by [start], then by [duration]. */
    override fun compareTo(other: DateInterval): Int {
        val byStart = start.compareTo(other.start)
        if (byStart != 0) return byStart
        return if (duration < other.duration) -1 else if (duration > other.duration) 1 else 0
    }

    private companion object {
        fun durationBetween(start: Instant, end: Instant): Double {
            require(end >= start) { "Reverse intervals are not allowed" }
            return end.timeIntervalSince(start)
        }
    }
}

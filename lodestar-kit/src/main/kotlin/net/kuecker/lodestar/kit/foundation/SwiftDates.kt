package net.kuecker.lodestar.kit.foundation

import java.time.Instant
import kotlin.math.floor
import kotlin.math.roundToLong

// Swift-compatible `Date` arithmetic on `java.time.Instant`.
//
// A Swift `Date` is a `Double` count of seconds since 2001-01-01T00:00:00Z (resolution about
// 1e-7 s in the 2020s). An `Instant` is exact to the nanosecond. These helpers convert through
// `Double` the way Swift does, so arithmetic on dates that came from Swift-style `Double` seconds
// (rise/set searches, sampling grids, test fixtures) reproduces Swift results bit for bit.

/** Seconds from 1970-01-01T00:00:00Z to the Foundation reference date 2001-01-01T00:00:00Z. */
private const val SECONDS_1970_TO_REFERENCE_DATE: Long = 978_307_200L

private const val NANOSECONDS_PER_SECOND: Double = 1.0e9

/**
 * Swift `Date.timeIntervalSince1970`: seconds since 1970-01-01T00:00:00Z as a `Double`, keeping
 * sub-second precision.
 */
val Instant.timeIntervalSince1970: Double
    get() = epochSecond.toDouble() + nano / NANOSECONDS_PER_SECOND

/**
 * Swift `Date.timeIntervalSinceReferenceDate`: seconds since 2001-01-01T00:00:00Z as a `Double`,
 * keeping sub-second precision.
 */
val Instant.timeIntervalSinceReferenceDate: Double
    get() = (epochSecond - SECONDS_1970_TO_REFERENCE_DATE).toDouble() + nano / NANOSECONDS_PER_SECOND

/**
 * Swift `Date(timeIntervalSince1970:)`.
 *
 * Values beyond the range of [Instant] (and infinities) saturate at [Instant.MIN] / [Instant.MAX], which
 * Swift's `Date` would hold; NaN gives [Instant.EPOCH].
 */
fun instantFromTimeIntervalSince1970(seconds: Double): Instant {
    if (seconds.isNaN()) return Instant.EPOCH
    val whole = floor(seconds)
    if (whole <= Instant.MIN.epochSecond.toDouble()) return Instant.MIN
    if (whole >= Instant.MAX.epochSecond.toDouble()) return Instant.MAX
    return Instant.ofEpochSecond(whole.toLong(), ((seconds - whole) * NANOSECONDS_PER_SECOND).roundToLong())
}

/**
 * Swift `Date(timeIntervalSinceReferenceDate:)`.
 *
 * Values beyond the range of [Instant] (and infinities) saturate at [Instant.MIN] / [Instant.MAX], which
 * Swift's `Date` would hold; NaN gives the reference date, 2001-01-01T00:00:00Z.
 */
fun instantFromTimeIntervalSinceReferenceDate(seconds: Double): Instant {
    if (seconds.isNaN()) return Instant.ofEpochSecond(SECONDS_1970_TO_REFERENCE_DATE)
    val whole = floor(seconds)
    val minimum = (Instant.MIN.epochSecond - SECONDS_1970_TO_REFERENCE_DATE).toDouble()
    val maximum = (Instant.MAX.epochSecond - SECONDS_1970_TO_REFERENCE_DATE).toDouble()
    if (whole <= minimum) return Instant.MIN
    if (whole >= maximum) return Instant.MAX
    return Instant.ofEpochSecond(
        whole.toLong() + SECONDS_1970_TO_REFERENCE_DATE,
        ((seconds - whole) * NANOSECONDS_PER_SECOND).roundToLong(),
    )
}

/** Swift `Date.addingTimeInterval(_:)`: this instant moved by [timeInterval] seconds (Double arithmetic, like Swift). */
fun Instant.addingTimeInterval(timeInterval: Double): Instant =
    instantFromTimeIntervalSinceReferenceDate(timeIntervalSinceReferenceDate + timeInterval)

/** Swift `Date.timeIntervalSince(_:)`: seconds from [date] to this instant (negative when [date] is later). */
fun Instant.timeIntervalSince(date: Instant): Double =
    timeIntervalSinceReferenceDate - date.timeIntervalSinceReferenceDate

/**
 * Swift `Date.distantPast`, `timeIntervalSinceReferenceDate` = -63114076800 (Foundation prints it as
 * 0001-01-01; in the proleptic Gregorian calendar it is 0000-12-30T00:00:00Z).
 */
val distantPast: Instant = instantFromTimeIntervalSinceReferenceDate(-63_114_076_800.0)

/** Swift `Date.distantFuture`, `timeIntervalSinceReferenceDate` = 63113904000 (4001-01-01T00:00:00Z). */
val distantFuture: Instant = instantFromTimeIntervalSinceReferenceDate(63_113_904_000.0)

package net.kuecker.lodestar.kit.foundation

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checks of the Swift-compatibility helpers (`SwiftNumerics.kt`, `SwiftDates.kt`, `DateInterval.kt`). */
class SwiftCompatTests {
    // MARK: Numerics

    @Test
    fun testRoundedHalfAwayFromZeroMatchesSwiftRounded() {
        assertEquals(3.0, 2.5.roundedHalfAwayFromZero())
        assertEquals(-3.0, (-2.5).roundedHalfAwayFromZero())
        assertEquals(2.0, 1.5.roundedHalfAwayFromZero())
        assertEquals(1.0, 0.5.roundedHalfAwayFromZero())
        assertEquals(0.0, 0.49999999999999994.roundedHalfAwayFromZero())
        assertEquals(4_503_599_627_370_497.0, 4_503_599_627_370_497.0.roundedHalfAwayFromZero())
        assertTrue((-0.3).roundedHalfAwayFromZero().let { it == 0.0 && 1.0 / it < 0 }, "-0.3 rounds to -0.0")
        assertTrue(Double.NaN.roundedHalfAwayFromZero().isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY.roundedHalfAwayFromZero())
    }

    @Test
    fun testFormatFixedMatchesCPrintf() {
        assertEquals("0.12", formatFixed(0.125, 2))
        assertEquals("0.38", formatFixed(0.375, 2))
        assertEquals("1.00", formatFixed(1.005, 2))
        assertEquals("2", formatFixed(2.5, 0))
        assertEquals("4", formatFixed(3.5, 0))
        assertEquals("-0.00", formatFixed(-0.0001, 2))
        assertEquals("-0.0", formatFixed(-0.0, 1))
        assertEquals("123.5", formatFixed(123.456, 1))
        assertEquals("nan", formatFixed(Double.NaN, 1))
        assertEquals("-inf", formatFixed(Double.NEGATIVE_INFINITY, 1))
    }

    @Test
    fun testSwiftMinMaxPropagateNaNLikeSwift() {
        // Swift: min(x, y) = y < x ? y : x and max(x, y) = y >= x ? y : x.
        assertEquals(90.0, swiftMin(90.0, Double.NaN))
        assertTrue(swiftMin(Double.NaN, 90.0).isNaN())
        assertEquals(0.0, swiftMax(0.0, Double.NaN))
        assertTrue(swiftMax(Double.NaN, 0.0).isNaN())
        assertEquals(1.0, swiftMin(2.0, 1.0))
        assertEquals(2.0, swiftMax(2.0, 1.0))
        assertEquals(2.0, swiftMax(1.0, 2.0))
        // The first argument wins a tie in min, the second in max.
        assertTrue(1.0 / swiftMin(-0.0, 0.0) < 0)
        assertTrue(1.0 / swiftMax(0.0, -0.0) < 0)
    }

    // MARK: Dates

    @Test
    fun testInstantTimeIntervalConversions() {
        val date = instantFromTimeIntervalSince1970(1_791_331_200.5)
        assertEquals(Instant.ofEpochSecond(1_791_331_200, 500_000_000), date)
        assertEquals(1_791_331_200.5, date.timeIntervalSince1970)
        assertEquals(1_791_331_200.5 - 978_307_200.0, date.timeIntervalSinceReferenceDate)
        assertEquals(Instant.parse("2001-01-01T00:00:00Z"), instantFromTimeIntervalSinceReferenceDate(0.0))
        assertEquals(Instant.parse("1969-12-31T23:59:59.250Z"), instantFromTimeIntervalSince1970(-0.75))
        assertEquals(-0.75, Instant.parse("1969-12-31T23:59:59.250Z").timeIntervalSince1970)
        // Instants are exact to the nanosecond, Doubles are not: a round trip is good to about 1e-7 s.
        val exact = Instant.parse("2026-10-17T12:34:56.789Z")
        val viaDouble = instantFromTimeIntervalSinceReferenceDate(exact.timeIntervalSinceReferenceDate)
        assertEquals(0.0, viaDouble.timeIntervalSince(exact), 1e-7)
    }

    @Test
    fun testInstantConversionKeepsSubMicrosecondResolutionOfADouble() {
        // A Double near 1.79e9 s resolves 2^-22 s ≈ 238 ns; the round trip must reproduce the Double.
        val seconds = 1_790_000_000.123456
        assertEquals(seconds, instantFromTimeIntervalSince1970(seconds).timeIntervalSince1970)
        val reference = 812_345_678.987654
        assertEquals(reference, instantFromTimeIntervalSinceReferenceDate(reference).timeIntervalSinceReferenceDate)
    }

    @Test
    fun testInstantConversionSaturatesInsteadOfThrowing() {
        assertEquals(Instant.MAX, instantFromTimeIntervalSince1970(Double.POSITIVE_INFINITY))
        assertEquals(Instant.MIN, instantFromTimeIntervalSince1970(Double.NEGATIVE_INFINITY))
        assertEquals(Instant.MAX, instantFromTimeIntervalSince1970(1e300))
        assertEquals(Instant.MIN, instantFromTimeIntervalSinceReferenceDate(-1e300))
        assertEquals(Instant.EPOCH, instantFromTimeIntervalSince1970(Double.NaN))
        assertEquals(Instant.parse("2001-01-01T00:00:00Z"), instantFromTimeIntervalSinceReferenceDate(Double.NaN))
        assertEquals(Instant.MAX, Instant.EPOCH.addingTimeInterval(Double.MAX_VALUE))
    }

    @Test
    fun testAddingTimeIntervalAndTimeIntervalSince() {
        val start = instantFromTimeIntervalSince1970(1_790_000_000.0)
        val later = start.addingTimeInterval(3600.5)
        assertEquals(1_790_003_600.5, later.timeIntervalSince1970)
        assertEquals(3600.5, later.timeIntervalSince(start))
        assertEquals(-3600.5, start.timeIntervalSince(later))
        assertEquals(start, later.addingTimeInterval(-3600.5))
        assertTrue(start < later)
    }

    @Test
    fun testDistantPastAndFutureMatchFoundation() {
        assertEquals(-63_114_076_800.0, distantPast.timeIntervalSinceReferenceDate)
        assertEquals(63_113_904_000.0, distantFuture.timeIntervalSinceReferenceDate)
        assertEquals(Instant.parse("4001-01-01T00:00:00Z"), distantFuture)
        assertEquals(Instant.parse("0000-12-30T00:00:00Z"), distantPast)
        assertTrue(distantPast < Instant.EPOCH && Instant.EPOCH < distantFuture)
    }

    // MARK: DateInterval

    private val noon = instantFromTimeIntervalSince1970(1_790_000_000.0)

    @Test
    fun testDateIntervalConstructionAndEnd() {
        val byEnd = DateInterval(noon, noon.addingTimeInterval(7200.0))
        val byDuration = DateInterval(noon, 7200.0)
        assertEquals(byDuration, byEnd)
        assertEquals(7200.0, byEnd.duration)
        assertEquals(noon.addingTimeInterval(7200.0), byEnd.end)
        assertEquals(DateInterval(noon, 0.0), DateInterval(noon, noon))
        assertFailsWith<IllegalArgumentException> { DateInterval(noon, noon.addingTimeInterval(-1.0)) }
        assertFailsWith<IllegalArgumentException> { DateInterval(noon, -1.0) }
        assertFailsWith<IllegalArgumentException> { DateInterval(noon, Double.NaN) }
    }

    @Test
    fun testDateIntervalContainsIncludesBothEndpoints() {
        val interval = DateInterval(noon, 600.0)
        assertTrue(interval.contains(noon))
        assertTrue(noon.addingTimeInterval(300.0) in interval)
        assertTrue(interval.contains(interval.end))
        assertFalse(interval.contains(noon.addingTimeInterval(-0.001)))
        assertFalse(noon.addingTimeInterval(600.001) in interval)
    }

    @Test
    fun testDateIntervalIntersection() {
        val a = DateInterval(noon, 600.0)
        val overlapping = DateInterval(noon.addingTimeInterval(300.0), 600.0)
        val touching = DateInterval(a.end, 60.0)
        val apart = DateInterval(noon.addingTimeInterval(1200.0), 60.0)
        val inside = DateInterval(noon.addingTimeInterval(100.0), 100.0)

        assertTrue(a.intersects(overlapping) && overlapping.intersects(a))
        assertEquals(DateInterval(noon.addingTimeInterval(300.0), noon.addingTimeInterval(600.0)), a.intersection(overlapping))
        assertEquals(a.intersection(overlapping), overlapping.intersection(a))
        assertTrue(a.intersects(touching))
        assertEquals(DateInterval(a.end, 0.0), a.intersection(touching))
        assertFalse(a.intersects(apart))
        assertNull(a.intersection(apart))
        assertEquals(inside, a.intersection(inside))
        assertEquals(inside, inside.intersection(a))
        assertEquals(a, a.intersection(a))
    }

    @Test
    fun testDateIntervalOrdersByStartThenDuration() {
        val early = DateInterval(noon, 600.0)
        val longer = DateInterval(noon, 900.0)
        val later = DateInterval(noon.addingTimeInterval(1.0), 1.0)
        assertEquals(listOf(early, longer, later), listOf(later, longer, early).sorted())
        assertEquals(0, early.compareTo(DateInterval(noon, 600.0)))
        assertNotEquals(early, longer)
        assertEquals(early.hashCode(), DateInterval(noon, 600.0).hashCode())
        assertNotNull(listOf(early, longer, later).maxOrNull())
    }
}

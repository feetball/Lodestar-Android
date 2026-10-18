package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private typealias Sets = IntervalSet

class PlanningIntervalSetTests {
    private val origin: Instant = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)

    private fun span(start: Double, end: Double): DateInterval =
        DateInterval(origin.addingTimeInterval(start), origin.addingTimeInterval(end))

    // MARK: Examples

    @Test
    fun testNormalizedSortsAndMergesOverlappingAndTouchingIntervals() {
        val messy = listOf(span(30.0, 40.0), span(0.0, 10.0), span(5.0, 12.0), span(12.0, 20.0), span(35.0, 36.0), span(50.0, 50.0))
        assertEquals(listOf(span(0.0, 20.0), span(30.0, 40.0)), Sets.normalized(messy))
        assertEquals(emptyList(), Sets.normalized(emptyList()))
    }

    @Test
    fun testNormalizedDropsIntervalsWithoutFiniteEnds() {
        val endless = DateInterval(origin, instantFromTimeIntervalSinceReferenceDate(Double.POSITIVE_INFINITY))
        val beginningless = DateInterval(instantFromTimeIntervalSinceReferenceDate(Double.NEGATIVE_INFINITY), 10.0)
        // Swift builds this one from a NaN start. An Instant cannot be NaN (the Foundation helper maps NaN to the
        // reference date, a perfectly finite instant), so the third kind of unusable interval starts at +infinity.
        val undefined = DateInterval(instantFromTimeIntervalSinceReferenceDate(Double.POSITIVE_INFINITY), 10.0)
        assertEquals(
            listOf(span(2.0, 3.0)),
            Sets.normalized(listOf(endless, beginningless, undefined, span(1.0, 1.0), span(2.0, 3.0))),
        )
        assertEquals(listOf(span(5.0, 10.0)), Sets.intersection(listOf(undefined, span(0.0, 10.0)), listOf(span(5.0, 20.0))))
        assertEquals(listOf(span(0.0, 10.0)), Sets.subtracting(listOf(undefined), listOf(span(0.0, 10.0))))
        assertEquals(4.0, Sets.totalDuration(listOf(undefined, endless, span(0.0, 4.0))))
    }

    @Test
    fun testIntersectionKeepsOnlyTimeCoveredByBothSets() {
        val first = listOf(span(0.0, 10.0), span(20.0, 30.0))
        val second = listOf(span(5.0, 25.0), span(28.0, 40.0))
        assertEquals(listOf(span(5.0, 10.0), span(20.0, 25.0), span(28.0, 30.0)), Sets.intersection(first, second))
        assertEquals(emptyList(), Sets.intersection(first, emptyList()))
        assertEquals(
            emptyList(),
            Sets.intersection(listOf(span(0.0, 10.0)), listOf(span(10.0, 20.0))),
            "intervals that only touch share no time",
        )
    }

    @Test
    fun testSubtractingRemovesCoveredTimeAndSplitsIntervals() {
        val base = listOf(span(0.0, 100.0))
        assertEquals(
            listOf(span(0.0, 10.0), span(20.0, 40.0), span(50.0, 100.0)),
            Sets.subtracting(listOf(span(10.0, 20.0), span(40.0, 50.0)), base),
        )
        assertEquals(emptyList(), Sets.subtracting(listOf(span(-5.0, 120.0)), base))
        assertEquals(base, Sets.subtracting(emptyList(), base))
        assertEquals(listOf(span(30.0, 100.0)), Sets.subtracting(listOf(span(0.0, 30.0)), base))
        assertEquals(listOf(span(0.0, 70.0)), Sets.subtracting(listOf(span(70.0, 200.0)), base))
    }

    @Test
    fun testTotalDurationCountsOverlapOnce() {
        assertEquals(21.0, Sets.totalDuration(listOf(span(0.0, 10.0), span(5.0, 20.0), span(30.0, 31.0))), 1e-9)
        assertEquals(0.0, Sets.totalDuration(emptyList()))
    }

    // MARK: Randomised comparison with membership tests

    private class Generator(private var state: ULong) {
        fun next(bound: Int): Int {
            state = state * 6_364_136_223_846_793_005uL + 1_442_695_040_888_963_407uL
            return ((state shr 33) % bound.toULong()).toInt()
        }
    }

    private fun randomSet(generator: Generator): List<DateInterval> =
        List(generator.next(6)) {
            val start = generator.next(95)
            span(start.toDouble(), (start + 1 + generator.next(30)).toDouble())
        }

    private fun contains(intervals: List<DateInterval>, seconds: Double): Boolean {
        val instant = origin.addingTimeInterval(seconds)
        return intervals.any { it.start <= instant && instant < it.end }
    }

    /** Endpoints are whole seconds, so probing at half seconds avoids every boundary. */
    @Test
    fun testSetOperationsAgreeWithPointwiseMembership() {
        val generator = Generator(2026uL)
        for (round in 0 until 300) {
            val first = randomSet(generator)
            val second = randomSet(generator)
            val union = Sets.normalized(first + second)
            val both = Sets.intersection(first, second)
            val onlyFirst = Sets.subtracting(second, first)
            for (tick in 0 until 130) {
                val probe = tick.toDouble() + 0.5
                val inFirst = contains(first, probe)
                val inSecond = contains(second, probe)
                assertEquals(inFirst || inSecond, contains(union, probe), "union, round $round at $probe")
                assertEquals(inFirst && inSecond, contains(both, probe), "intersection, round $round at $probe")
                assertEquals(inFirst && !inSecond, contains(onlyFirst, probe), "difference, round $round at $probe")
            }
            for (result in listOf(union, both, onlyFirst)) {
                assertTrue(result.all { it.duration > 0 }, "round $round")
                for ((earlier, later) in result.zip(result.drop(1))) {
                    assertTrue(earlier.end < later.start, "disjoint and not touching, round $round")
                }
            }
        }
    }
}

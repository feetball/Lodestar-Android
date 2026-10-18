package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `NightPlanner.altitudeCurve`: the sample grid and the altitudes and azimuths it reports. */
class PlanningAltitudeCurveTests {
    private val boulder = PlanningTestSupport.boulder
    private val orionNebula = EquatorialCoordinate(83.822_083, -5.391_111)

    private fun night(year: Int, month: Int, day: Int): DateInterval {
        val zone = boulder.timeZone()
        return DateInterval(
            PlanningTestSupport.localNoon(year, month, day, zone),
            PlanningTestSupport.localNoon(year, month, day + 1, zone),
        )
    }

    // MARK: The grid

    @Test
    fun testSamplesAreEvenlySpacedFromTheStartToTheEnd() {
        val interval = night(2026, 10, 25)
        val curve = NightPlanner.altitudeCurve(boulder.observer, interval) { HorizontalCoordinate(10.0, 20.0) }
        assertEquals(145, curve.size)
        assertEquals(interval.start, curve.firstOrNull()?.date)
        assertEquals(interval.end, curve.lastOrNull()?.date)
        for ((earlier, later) in curve.zip(curve.drop(1))) {
            assertEquals(600.0, later.date.timeIntervalSince(earlier.date), 1e-6)
        }
    }

    @Test
    fun testTheSpacingShrinksSoNoGapExceedsTheStep() {
        val interval = night(2026, 10, 25)
        // 24 h with at most 7 h between samples: four gaps of 6 h.
        val curve = NightPlanner.altitudeCurve(boulder.observer, interval, 7.0 * 3_600) { HorizontalCoordinate(0.0, 0.0) }
        assertEquals(5, curve.size)
        assertEquals(6.0 * 3_600, curve[1].date.timeIntervalSince(curve[0].date), 1e-6)
        assertEquals(interval.end, curve.lastOrNull()?.date)

        val coarse = NightPlanner.altitudeCurve(boulder.observer, interval, 1e9) { HorizontalCoordinate(0.0, 0.0) }
        assertEquals(listOf(interval.start, interval.end), coarse.map { it.date })
    }

    @Test
    fun testUnusableStepsAreReplaced() {
        val interval = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 6_000.0)
        fun count(step: Double): Int =
            NightPlanner.altitudeCurve(boulder.observer, interval, step) { HorizontalCoordinate(0.0, 0.0) }.size
        assertEquals(11, count(600.0))
        assertEquals(11, count(0.0), "not positive: 10 minutes")
        assertEquals(11, count(-30.0))
        assertEquals(11, count(Double.NaN))
        assertEquals(11, count(Double.POSITIVE_INFINITY))
        assertEquals(6_001, count(0.2), "below a second: one second")
        assertEquals(6_001, count(1.0))
    }

    @Test
    fun testAnEmptyIntervalGivesOneSample() {
        val instant = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)
        val curve = NightPlanner.altitudeCurve(boulder.observer, DateInterval(instant, 0.0)) {
            it.horizontal(SolarSystemBody.SUN, false)
        }
        assertEquals(listOf(instant), curve.map { it.date })
    }

    @Test
    fun testTheNumberOfSamplesIsCapped() {
        assertEquals(144, NightPlanner.curveGapCount(86_400.0, 600.0))
        assertEquals(86_400, NightPlanner.curveGapCount(86_400.0, 1.0))
        assertEquals(0, NightPlanner.curveGapCount(0.0, 600.0))
        assertEquals(NightPlanner.maximumCurveSampleCount - 1, NightPlanner.curveGapCount(1e12, 1.0))
        assertEquals(NightPlanner.maximumCurveSampleCount - 1, NightPlanner.curveGapCount(365.0 * 86_400, 0.001))
        assertEquals(NightPlanner.maximumCurveSampleCount - 1, NightPlanner.curveGapCount(1e300, 600.0))
    }

    @Test
    fun testAHugeRequestStillReturnsOnlyTheCappedNumberOfSamples() {
        val month = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 30.0 * 86_400)
        val curve = NightPlanner.altitudeCurve(boulder.observer, month, 1.0) { HorizontalCoordinate(0.0, 0.0) }
        assertEquals(NightPlanner.maximumCurveSampleCount, curve.size)
        assertEquals(month.start, curve.firstOrNull()?.date)
        assertEquals(month.end, curve.lastOrNull()?.date)
        assertEquals(month.duration / 99_999, curve[1].date.timeIntervalSince(curve[0].date), 1e-6)
    }

    // MARK: The values

    @Test
    fun testThePositionFunctionSeesAFrameForEachSampleInstant() {
        val interval = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 3_600.0)
        val seen = ArrayList<Instant>()
        val curve = NightPlanner.altitudeCurve(boulder.observer, interval, 900.0) { frame ->
            seen.add(frame.date)
            assertEquals(boulder.observer, frame.observer)
            HorizontalCoordinate(370.0, seen.size.toDouble())
        }
        assertEquals(5, seen.size)
        for ((sample, frameDate) in curve.zip(seen)) {
            assertEquals(0.0, frameDate.timeIntervalSince(sample.date), 1e-3)
        }
        assertEquals((0..4).map { interval.start.addingTimeInterval(it * 900.0) }, curve.map { it.date })
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0, 5.0), curve.map { it.altitude })
        assertEquals(List(5) { 10.0 }, curve.map { it.azimuth }, "azimuths come out in [0, 360)")
    }

    @Test
    fun testFixedObjectCurveMatchesDirectFrames() {
        val interval = night(2026, 10, 25)
        val motion = ProperMotion(1_000.0, -2_000.0)
        for (properMotion in listOf<ProperMotion?>(null, motion)) {
            val curve = NightPlanner.altitudeCurve(orionNebula, properMotion, boulder.observer, interval)
            assertEquals(145, curve.size)
            for (sample in curve) {
                val expected = SkyFrame(sample.date, boulder.observer).horizontal(orionNebula, properMotion, false)
                assertEquals(expected.altitude, sample.altitude, 1e-9)
                assertEquals(expected.azimuth, sample.azimuth, 1e-9)
            }
        }
        // Proper motion of two arcseconds a year moves the target by about a minute of arc by 2026.
        val still = NightPlanner.altitudeCurve(orionNebula, observer = boulder.observer, interval = interval)
        val moving = NightPlanner.altitudeCurve(orionNebula, motion, boulder.observer, interval)
        val largest = still.zip(moving).maxOfOrNull { (first, second) -> abs(first.altitude - second.altitude) } ?: 0.0
        assertTrue(largest > 0.01, "largest altitude difference $largest")
    }

    @Test
    fun testBodyCurveMatchesDirectFramesAndIsGeometric() {
        val interval = night(2026, 10, 25)
        for (body in listOf(SolarSystemBody.SUN, SolarSystemBody.MOON, SolarSystemBody.SATURN)) {
            val curve = NightPlanner.altitudeCurve(body, boulder.observer, interval, 1_800.0)
            assertEquals(49, curve.size)
            for (sample in curve) {
                val expected = SkyFrame(sample.date, boulder.observer).horizontal(body, false)
                assertEquals(expected.altitude, sample.altitude, 1e-9, body.displayName)
                assertEquals(expected.azimuth, sample.azimuth, 1e-9, body.displayName)
            }
        }
    }

    @Test
    fun testSunCurveShowsTheNightTheTwilightCalculatorFinds() {
        val zone = boulder.timeZone()
        val noon = PlanningTestSupport.localNoon(2026, 10, 25, zone)
        val twilight = TwilightCalculator.night(noon, boulder.observer, zone)
        val curve = NightPlanner.altitudeCurve(SolarSystemBody.SUN, boulder.observer, twilight.interval)
        val lowest = assertNotNull(curve.minByOrNull { it.altitude })
        assertEquals(0.0, lowest.date.timeIntervalSince(twilight.solarMidnight), 600.0)
        assertEquals(twilight.sunAltitudeAtMidnight, lowest.altitude, 0.05)
        assertTrue(assertNotNull(curve.firstOrNull()).altitude > 20, "the curve starts at local noon")
        // The curve is above −18° at every sample outside the astronomical night and below inside it.
        val dark = assertNotNull(twilight.astronomicalNight)
        for (sample in curve) {
            if (!(abs(sample.date.timeIntervalSince(dark.start)) > 600 && abs(sample.date.timeIntervalSince(dark.end)) > 600)) continue
            assertEquals(sample.date in dark, sample.altitude < TwilightCalculator.astronomicalAltitude)
        }
        assertTrue(curve.all { it.azimuth >= 0.0 && it.azimuth < 360.0 && it.altitude >= -90.0 && it.altitude <= 90.0 })
    }

    @Test
    fun testMoonCurveCrossesTheHorizonWhenTheSummarySaysItRisesAndSets() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        val curve = NightPlanner.altitudeCurve(SolarSystemBody.MOON, boulder.observer, summary.night, 60.0)
        val moonSet = assertNotNull(summary.moon.riseTransitSet.set)
        val before = assertNotNull(curve.lastOrNull { it.date < moonSet.addingTimeInterval(-120.0) })
        val after = assertNotNull(curve.firstOrNull { it.date > moonSet.addingTimeInterval(120.0) })
        assertTrue(before.altitude > -0.5, "altitude before the set: ${before.altitude}")
        assertTrue(after.altitude < -0.5, "altitude after the set: ${after.altitude}")
    }
}

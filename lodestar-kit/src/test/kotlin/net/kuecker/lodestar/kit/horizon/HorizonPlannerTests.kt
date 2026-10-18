package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import net.kuecker.lodestar.kit.planning.*
import net.kuecker.lodestar.kit.planning.PlanningTestSupport as Planning
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Tonight planner's calls with a `horizon`, against the calls without one. */
class HorizonPlannerTests {
    private val summary = Planning.summary(Planning.boulder, 2026, 12, 21)
    private val observer = summary.observer
    private val flat = HorizonProfile.flat

    private fun targets(count: Int, seed: ULong): List<EquatorialCoordinate> {
        val generator = Support.SeededGenerator(seed)
        return List(count) {
            EquatorialCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(2 * generator.nextDouble() - 1))
        }
    }

    private fun assertSameVisibility(expected: TargetVisibility, actual: TargetVisibility, message: String) {
        assertEquals(expected.minimumAltitude, actual.minimumAltitude, message)
        assertEquals(expected.riseTransitSet.circumstance, actual.riseTransitSet.circumstance, message)
        for ((name, pair) in listOf(
            "rise" to (expected.riseTransitSet.rise to actual.riseTransitSet.rise),
            "transit" to (expected.riseTransitSet.transit to actual.riseTransitSet.transit),
            "set" to (expected.riseTransitSet.set to actual.riseTransitSet.set),
        )) {
            val (wanted, found) = pair
            if (wanted == null) {
                assertNull(found, "$message $name")
            } else {
                assertEquals(0.0, assertNotNull(found, "$message $name").timeIntervalSince(wanted), 1.0, "$message $name")
            }
        }
        assertEquals(expected.intervalsAboveMinimum.size, actual.intervalsAboveMinimum.size, "$message $actual")
        for ((wanted, found) in expected.intervalsAboveMinimum.zip(actual.intervalsAboveMinimum)) {
            assertEquals(0.0, found.start.timeIntervalSince(wanted.start), 2.0, "$message start")
            assertEquals(0.0, found.end.timeIntervalSince(wanted.end), 2.0, "$message end")
        }
        assertEquals(expected.hoursAboveMinimum, actual.hoursAboveMinimum, 0.002, message)
        val wantedPeak = expected.maxAltitudeDuringDarkness
        val foundPeak = actual.maxAltitudeDuringDarkness
        if (wantedPeak == null) {
            assertNull(foundPeak, message)
            assertNull(actual.bestTime, message)
        } else {
            assertEquals(wantedPeak, assertNotNull(foundPeak, message), 0.01, message)
            assertNotNull(actual.bestTime, message)
        }
    }

    // MARK: A flat horizon changes nothing

    @Test
    fun testFlatHorizonGivesWhatThePlainPlannerGivesForManyTargets() {
        val list = targets(60, 11uL)
        val plain = NightPlanner.visibility(list, summary = summary)
        val withHorizon = NightPlanner.visibility(list, summary = summary, horizon = flat)
        assertEquals(list.size, withHorizon.size)
        for (index in list.indices) assertSameVisibility(plain[index], withHorizon[index], "target $index")
        assertTrue(withHorizon.count { it.hoursAboveMinimum > 0 } > 30)
        assertTrue(withHorizon.count { it.hoursAboveMinimum == 0.0 } > 5)
    }

    @Test
    fun testFlatHorizonGivesWhatThePlainPlannerGivesForMoonFreeTimeAndMinimumAltitudes() {
        val june = Planning.summary(Planning.boulder, 2026, 6, 20)
        val list = targets(40, 12uL)
        for (kind in DarknessKind.entries) {
            for (minimum in listOf(0.0, 35.0)) {
                val plain = NightPlanner.visibility(list, summary = june, darkness = kind, minimumAltitude = minimum)
                val withHorizon = NightPlanner.visibility(list, summary = june, darkness = kind, minimumAltitude = minimum, horizon = flat)
                for (index in list.indices) assertSameVisibility(plain[index], withHorizon[index], "$kind $minimum target $index")
            }
        }
    }

    @Test
    fun testFlatHorizonGivesWhatThePlainPlannerGivesForBodies() {
        for (day in listOf(listOf(2026, 12, 21), listOf(2026, 6, 20), listOf(2026, 1, 3))) {
            val night = Planning.summary(Planning.boulder, day[0], day[1], day[2])
            for (body in SolarSystemBody.entries) {
                val plain = NightPlanner.visibility(body, summary = night, darkness = DarknessKind.ASTRONOMICAL, minimumAltitude = 10.0)
                val withHorizon = NightPlanner.visibility(
                    body, summary = night, darkness = DarknessKind.ASTRONOMICAL, minimumAltitude = 10.0, horizon = flat,
                )
                assertSameVisibility(plain, withHorizon, "$body on $day")
            }
        }
    }

    @Test
    fun testFlatHorizonGivesWhatThePlainPlannerGivesForAPositionFunctionAndASpan() {
        val place = EquatorialCoordinate.fromRightAscensionHours(5.5883, -5.39)
        val position: (SkyFrame) -> HorizontalCoordinate = { it.horizontal(place, null, false) }
        assertSameVisibility(
            NightPlanner.visibility(position, summary),
            NightPlanner.visibility(position, summary, horizon = flat),
            "function",
        )
        val span = DateInterval(summary.night.start.addingTimeInterval(8 * 3_600.0), 12 * 3_600.0)
        assertSameVisibility(
            NightPlanner.visibility(position, observer, span),
            NightPlanner.visibility(position, observer, span, horizon = flat),
            "function in a span",
        )
        assertSameVisibility(
            NightPlanner.visibility(SolarSystemBody.JUPITER, observer, span),
            NightPlanner.visibility(SolarSystemBody.JUPITER, observer, span, horizon = flat),
            "body in a span",
        )
        val list = listOf(place)
        assertSameVisibility(
            NightPlanner.visibility(list, observer = observer, interval = span)[0],
            NightPlanner.visibility(list, observer = observer, interval = span, horizon = flat)[0],
            "batch in a span",
        )
    }

    // MARK: A skyline hides things

    @Test
    fun testSkylineRemovesWhatItHidesAndNothingElse() {
        val wall = flat.setting(45.0, AzimuthRange.between(100.0, 260.0)) // all of the south and part of the east and west
        val list = targets(120, 13uL)
        val plain = NightPlanner.visibility(list, summary = summary)
        val hidden = NightPlanner.visibility(list, summary = summary, horizon = wall)
        var shortened = 0
        for (index in list.indices) {
            assertTrue(hidden[index].hoursAboveMinimum <= plain[index].hoursAboveMinimum + 0.002, "target $index")
            for (window in hidden[index].intervalsAboveMinimum) {
                assertTrue(plain[index].intervalsAboveMinimum.any { it.start.timeIntervalSince(window.start) <= 2 && window.end.timeIntervalSince(it.end) <= 2 }, "target $index")
            }
            if (hidden[index].hoursAboveMinimum < plain[index].hoursAboveMinimum - 0.05) shortened += 1
            // The windows are exactly the times above both the planner's minimum and the skyline, found by scanning frames.
            val darkness = summary.darkness(DarknessKind.ASTRONOMICAL)
            for (step in 0 until 90) {
                val time = darkness.first().start.addingTimeInterval(step * 420.0 + 11)
                if (!darkness.any { it.start <= time && time < it.end }) continue
                val horizontal = SkyFrame(time, observer).horizontal(list[index], null, false)
                val expected = horizontal.altitude >= 20.0 &&
                    horizontal.altitude >= HorizonVisibility.thresholdAltitude(wall.altitude(horizontal.azimuth))
                val found = hidden[index].intervalsAboveMinimum.any { it.start <= time && time < it.end }
                if (expected != found) {
                    val distance = hidden[index].intervalsAboveMinimum.flatMap { listOf(it.start, it.end) }
                        .minOfOrNull { abs(it.timeIntervalSince(time)) } ?: Double.MAX_VALUE
                    assertTrue(distance < 30, "target $index step $step")
                }
            }
        }
        assertTrue(shortened > 10, "$shortened targets lose time")
    }

    @Test
    fun testBestTimeIsTheHighestPointWhileTheTargetCanBeSeen() {
        // A star on the celestial equator climbs to 50° in the south. Behind a 55° wall from azimuth 120° to 240° it is only
        // seen while it rises in the east below 31° and while it sinks in the west: two windows, separated by its transit.
        val wall = flat.setting(55.0, AzimuthRange.between(120.0, 240.0))
        val target = EquatorialCoordinate.fromRightAscensionHours(5.0, 0.0)
        val plain = NightPlanner.visibility(listOf(target), summary = summary)[0]
        val hidden = NightPlanner.visibility(listOf(target), summary = summary, horizon = wall)[0]
        assertTrue(hidden.hoursAboveMinimum < plain.hoursAboveMinimum)
        assertTrue(hidden.hoursAboveMinimum > 1)
        assertEquals(2, hidden.intervalsAboveMinimum.size, "split by the wall: ${hidden.intervalsAboveMinimum}")
        val best = assertNotNull(hidden.bestTime)
        assertTrue(hidden.intervalsAboveMinimum.any { it.start <= best && best <= it.end })
        val atBest = SkyFrame(best, observer).horizontal(target, null, false)
        assertEquals(atBest.altitude, assertNotNull(hidden.maxAltitudeDuringDarkness), 0.01)
        assertTrue(assertNotNull(hidden.maxAltitudeDuringDarkness) < assertNotNull(plain.maxAltitudeDuringDarkness) - 3)
        // It is the higher of the two ends of the gap the wall leaves, about 31° high.
        assertEquals(31.0, atBest.altitude, 1.0)
        val ends = hidden.intervalsAboveMinimum.flatMap { listOf(it.start, it.end) }
        val altitudes = ends.map { SkyFrame(it, observer).horizontal(target, null, false).altitude }
        assertEquals(altitudes.max(), atBest.altitude, 0.05)
    }

    @Test
    fun testATargetThatIsNeverClearKeepsItsPeakInTheDarkButHasNoWindow() {
        val everywhere = HorizonProfile.constant(45.0)
        val polaris = EquatorialCoordinate(37.954_515, 89.264_110)
        val plain = NightPlanner.visibility(listOf(polaris), summary = summary)[0]
        val hidden = NightPlanner.visibility(listOf(polaris), summary = summary, horizon = everywhere)[0]
        assertTrue(hidden.intervalsAboveMinimum.isEmpty())
        assertEquals(0.0, hidden.hoursAboveMinimum)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, hidden.riseTransitSet.circumstance)
        assertNull(hidden.riseTransitSet.rise)
        assertNull(hidden.riseTransitSet.set)
        // The values describe the highest point in the dark, as without the skyline.
        assertEquals(assertNotNull(plain.maxAltitudeDuringDarkness), assertNotNull(hidden.maxAltitudeDuringDarkness), 0.01)
        assertTrue(assertNotNull(hidden.maxAltitudeDuringDarkness) > 39)
        assertNotNull(hidden.bestTime)
    }

    @Test
    fun testSkylineOverridesTheMinimumAltitudeOnlyWhereItIsHigher() {
        // A 30° wall in the south with a 20° minimum: a target in the south needs 30°, one in the north 20°.
        val wall = flat.setting(30.0, AzimuthRange.between(135.0, 225.0))
        val list = targets(80, 14uL)
        val low = NightPlanner.visibility(list, summary = summary, horizon = wall)
        val high = NightPlanner.visibility(list, summary = summary, minimumAltitude = 30.0, horizon = wall)
        for (index in list.indices) {
            assertTrue(high[index].hoursAboveMinimum <= low[index].hoursAboveMinimum + 0.002, "$index")
            assertEquals(30.0, high[index].minimumAltitude)
        }
    }

    @Test
    fun testMoonRisingBehindARidgeIsLaterAndTheBodyWindowsFollow() {
        val night = Planning.summary(Planning.boulder, 2026, 6, 24)
        val ridge = HorizonProfile.constant(8.0)
        val plain = NightPlanner.visibility(SolarSystemBody.MOON, summary = night, minimumAltitude = 0.0)
        val behind = NightPlanner.visibility(SolarSystemBody.MOON, summary = night, minimumAltitude = 0.0, horizon = ridge)
        val flatRise = plain.riseTransitSet.rise
        val clear = behind.riseTransitSet.rise
        assertNotNull(flatRise)
        assertNotNull(clear)
        assertTrue(clear.timeIntervalSince(flatRise) > 20 * 60)
        assertTrue(behind.hoursAboveMinimum <= plain.hoursAboveMinimum)
        // The same call from the visibility object.
        val direct = HorizonVisibility(ridge).riseTransitSet(SolarSystemBody.MOON, observer, night.night)
        assertEquals(0.0, assertNotNull(direct.rise).timeIntervalSince(clear), 0.01)
    }

    // MARK: Edge cases

    @Test
    fun testNoDarknessGivesNoPeakButStillTheEvents() {
        val white = Planning.summary(Planning.tromso, 2026, 6, 21)
        assertFalse(white.hasAstronomicalDarkness)
        val target = EquatorialCoordinate.fromRightAscensionHours(18.6, 38.8)
        val result = NightPlanner.visibility(listOf(target), summary = white, horizon = Support.truthProfile)[0]
        assertNull(result.maxAltitudeDuringDarkness)
        assertNull(result.bestTime)
        assertTrue(result.intervalsAboveMinimum.isEmpty())
        val plain = NightPlanner.visibility(listOf(target), summary = white)[0]
        assertEquals(plain.riseTransitSet.circumstance, result.riseTransitSet.circumstance)
    }

    @Test
    fun testTargetsWithoutAUsablePositionNeverRise() {
        val bad = EquatorialCoordinate(Double.NaN, Double.NaN)
        val good = EquatorialCoordinate.fromRightAscensionHours(5.9, 7.4)
        val results = NightPlanner.visibility(listOf(bad, good), summary = summary, horizon = Support.truthProfile)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, results[0].riseTransitSet.circumstance)
        assertNull(results[0].maxAltitudeDuringDarkness)
        assertNull(results[0].bestTime)
        assertTrue(results[0].intervalsAboveMinimum.isEmpty())
        assertEquals(NightPlanner.defaultMinimumAltitude, results[0].minimumAltitude)
        assertTrue(results[1].hoursAboveMinimum > 0)
        assertEquals(30.0, NightPlanner.visibility(listOf(bad), summary = summary, minimumAltitude = 30.0, horizon = flat)[0].minimumAltitude)
        assertEquals(20.0, NightPlanner.visibility(listOf(good), summary = summary, minimumAltitude = Double.NaN, horizon = flat)[0].minimumAltitude)
        assertTrue(NightPlanner.visibility(emptyList(), summary = summary, horizon = flat).isEmpty())
    }

    @Test
    fun testProperMotionsAreUsed() {
        val barnard = EquatorialCoordinate(269.452_075, 4.693_391)
        val motion = ProperMotion(-798.58, 10_328.12)
        val with = NightPlanner.visibility(listOf(barnard), listOf(motion), summary = summary, horizon = flat)[0]
        val without = NightPlanner.visibility(listOf(barnard), summary = summary, horizon = flat)[0]
        // 26 years of 10″ per year is 4.5′ towards the north: at 40°N the rise of a star at +4.7° moves by 17 s.
        val shift = abs(assertNotNull(with.riseTransitSet.rise).timeIntervalSince(assertNotNull(without.riseTransitSet.rise)))
        assertTrue(shift > 10 && shift < 30, "$shift s")
        val plain = NightPlanner.visibility(listOf(barnard), listOf(motion), summary = summary)[0]
        assertEquals(0.0, assertNotNull(with.riseTransitSet.rise).timeIntervalSince(assertNotNull(plain.riseTransitSet.rise)), 1.0)
    }

    @Test
    fun testSeveralHundredTargetsThroughTheTonightPlannerAreFast() {
        val list = targets(700, 700uL)
        val skyline = Support.truthProfile
        NightPlanner.visibility(list, summary = summary, horizon = skyline) // warm up
        val start = System.nanoTime()
        val results = NightPlanner.visibility(list, summary = summary, horizon = skyline)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(700, results.size)
        // About 1 ms per target; the plain planner needs a few hundredths of a second for a thousand.
        assertTrue(elapsed < 3.0, "took $elapsed s")
    }
}

package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import net.kuecker.lodestar.kit.horizon.HorizonVisibility
import net.kuecker.lodestar.kit.planning.PlanningTestSupport as Planning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hours above the skyline: `TargetVisibility.unobstructed*`, the `horizon` argument of `NightPlanner.visibility`
 * and the catalog-sized `NightPlanner.unobstructedVisibility`.
 */
class PlanningUnobstructedTests {
    private val december = Planning.summary(Planning.boulder, 2026, 12, 21)
    private val october = Planning.summary(Planning.boulder, 2026, 10, 25)
    private val observer = december.observer

    private val andromeda = EquatorialCoordinate.fromRightAscensionHours(0 + 42.0 / 60 + 44.3 / 3600, 41 + 16.0 / 60 + 9.0 / 3600)
    private val orion = EquatorialCoordinate.fromRightAscensionHours(5 + 35.0 / 60 + 17.3 / 3600, -(5 + 23.0 / 60 + 28.0 / 3600))
    private val lagoon = EquatorialCoordinate.fromRightAscensionHours(18 + 3.0 / 60 + 37.0 / 3600, -(24 + 23.0 / 60 + 12.0 / 3600))
    private val polaris = EquatorialCoordinate(37.954_515, 89.264_110)

    private fun targets(count: Int, seed: ULong): List<EquatorialCoordinate> {
        val generator = Support.SeededGenerator(seed)
        return List(count) {
            EquatorialCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(2 * generator.nextDouble() - 1))
        }
    }

    // MARK: Without a skyline nothing changes

    @Test
    fun testWithoutASkylineThePlannerGivesTheNumbersItAlwaysGave() {
        // Recorded from the planner before it had a `horizon` argument.
        val expected = listOf(
            Triple(october, listOf(10.245325372583336, 5.437605496843656, 0.0), listOf(88.58000253152916, 44.6290463409263, 14.489015202981845)),
            Triple(december, listOf(6.96989415390624, 7.735069659385417, 0.0), listOf(88.5780985114627, 44.62649021789657, -17.74847228007255)),
        )
        for ((summary, hours, peaks) in expected) {
            val results = NightPlanner.visibility(listOf(andromeda, orion, lagoon), summary = summary)
            assertEquals(results, NightPlanner.visibility(listOf(andromeda, orion, lagoon), summary = summary, horizon = null))
            for (index in results.indices) {
                assertEquals(hours[index], results[index].hoursAboveMinimum, 1e-9, "hours $index")
                assertEquals(peaks[index], assertNotNull(results[index].maxAltitudeDuringDarkness), 1e-9, "peak $index")
            }
        }
    }

    @Test
    fun testWithoutASkylineUnobstructedIsAboveTheMinimumAltitude() {
        val list = targets(60, 21uL)
        for (result in NightPlanner.visibility(list, summary = december)) {
            assertEquals(result.intervalsAboveMinimum, result.unobstructedIntervals)
            assertEquals(result.hoursAboveMinimum, result.unobstructedHours)
            assertFalse(result.isBehindSkyline)
        }
    }

    @Test
    fun testEveryFormTakesANullSkylineAndGivesTheSameAsLeavingItOut() {
        val place = EquatorialCoordinate.fromRightAscensionHours(5.5883, -5.39)
        val position: (SkyFrame) -> HorizontalCoordinate = { it.horizontal(place, null, false) }
        val span = DateInterval(december.night.start.addingTimeInterval(8 * 3_600.0), 12 * 3_600.0)
        assertEquals(NightPlanner.visibility(position, december), NightPlanner.visibility(position, december, horizon = null))
        assertEquals(NightPlanner.visibility(position, observer, span), NightPlanner.visibility(position, observer, span, horizon = null))
        assertEquals(
            NightPlanner.visibility(SolarSystemBody.JUPITER, december),
            NightPlanner.visibility(SolarSystemBody.JUPITER, december, horizon = null),
        )
        assertEquals(
            NightPlanner.visibility(SolarSystemBody.MOON, observer, span),
            NightPlanner.visibility(SolarSystemBody.MOON, observer, span, horizon = null),
        )
        assertEquals(
            NightPlanner.visibility(listOf(place), observer = observer, interval = span),
            NightPlanner.visibility(listOf(place), observer = observer, interval = span, horizon = null),
        )
    }

    // MARK: A skyline hides things

    @Test
    fun testATargetRisingBehindABumpGetsFewerUnobstructedHours() {
        // Orion's Sword rises in the east; a 25° bump across the east hides it until it is 25° high, 20° above the
        // 5° minimum, which takes about two hours.
        val bump = HorizonProfile.flat.setting(25.0, AzimuthRange.between(50.0, 130.0))
        val open = NightPlanner.visibility(listOf(orion), summary = december, minimumAltitude = 5.0)[0]
        val hidden = NightPlanner.visibility(listOf(orion), summary = december, minimumAltitude = 5.0, horizon = bump)[0]
        assertEquals(1, hidden.unobstructedIntervals.size)
        assertEquals(1, open.unobstructedIntervals.size)
        assertTrue(hidden.unobstructedHours < open.unobstructedHours - 1.0, "${hidden.unobstructedHours} against ${open.unobstructedHours}")
        assertTrue(hidden.unobstructedHours > open.unobstructedHours - 3.0)
        assertEquals(hidden.hoursAboveMinimum, hidden.unobstructedHours)
        assertFalse(hidden.isBehindSkyline)

        // It clears the bump later and leaves the sky at the same moment as without it.
        val openWindow = open.unobstructedIntervals[0]
        val clearWindow = hidden.unobstructedIntervals[0]
        assertTrue(clearWindow.start.timeIntervalSince(openWindow.start) > 3_600)
        assertEquals(0.0, clearWindow.end.timeIntervalSince(openWindow.end), 2.0)

        // An independent count: the frames of the night every minute, above the minimum and above the bump.
        val dark = december.darkness(DarknessKind.ASTRONOMICAL).single()
        var counted = 0
        var time = dark.start.addingTimeInterval(30.0)
        while (time < dark.end) {
            val horizontal = SkyFrame(time, observer).horizontal(orion, null, false)
            if (horizontal.altitude >= 5.0 &&
                horizontal.altitude >= HorizonVisibility.thresholdAltitude(bump.altitude(horizontal.azimuth))
            ) {
                counted += 1
            }
            time = time.addingTimeInterval(60.0)
        }
        assertEquals(counted / 60.0, hidden.unobstructedHours, 0.05)
    }

    @Test
    fun testUnobstructedTimeNeverLeavesTheDarkOrTheOpenSky() {
        val wall = HorizonProfile.flat.setting(40.0, AzimuthRange.between(100.0, 260.0))
        val list = targets(120, 22uL)
        val summary = Planning.summary(Planning.boulder, 2026, 6, 20)
        for (kind in DarknessKind.entries) {
            val dark = summary.darkness(kind)
            val open = NightPlanner.visibility(list, summary = summary, darkness = kind)
            val hidden = NightPlanner.visibility(list, summary = summary, darkness = kind, horizon = wall)
            for (index in list.indices) {
                val intervals = hidden[index].unobstructedIntervals
                assertEquals(intervals, IntervalSet.intersection(intervals, dark), "$kind target $index stays in the dark")
                assertTrue(hidden[index].unobstructedHours <= open[index].unobstructedHours + 0.002, "$kind target $index")
            }
            assertTrue(hidden.indices.count { hidden[it].unobstructedHours < open[it].unobstructedHours - 0.05 } > 10)
        }
    }

    @Test
    fun testUnobstructedTimeOfTheSunMoonAndPlanetsAndOfAPositionFunctionFollowsTheSkyline() {
        val wall = HorizonProfile.constant(35.0)
        val place = EquatorialCoordinate.fromRightAscensionHours(5.5883, -5.39)
        val position: (SkyFrame) -> HorizontalCoordinate = { it.horizontal(place, null, false) }
        val open = NightPlanner.visibility(position, december)
        val hidden = NightPlanner.visibility(position, december, horizon = wall)
        assertTrue(open.unobstructedHours > 5)
        assertTrue(hidden.unobstructedHours < open.unobstructedHours - 1)
        for (body in listOf(SolarSystemBody.MOON, SolarSystemBody.JUPITER, SolarSystemBody.SATURN)) {
            val june = Planning.summary(Planning.boulder, 2026, 6, 20)
            val free = NightPlanner.visibility(body, june, minimumAltitude = 0.0)
            val behind = NightPlanner.visibility(body, june, minimumAltitude = 0.0, horizon = wall)
            assertTrue(behind.unobstructedHours <= free.unobstructedHours + 0.002, "$body")
        }
    }

    @Test
    fun testATargetThatIsUpButNeverClearIsBehindTheSkyline() {
        val ring = HorizonProfile.constant(45.0)
        val behind = NightPlanner.visibility(listOf(polaris), summary = december, horizon = ring)[0]
        assertTrue(behind.unobstructedIntervals.isEmpty())
        assertEquals(0.0, behind.unobstructedHours)
        assertTrue(behind.isBehindSkyline, "Polaris is 40° up but the skyline is at 45°")
        assertFalse(NightPlanner.visibility(listOf(polaris), summary = december)[0].isBehindSkyline)

        // Alpha Centauri never gets above the horizon at 40°N: nothing hides it, so it is not behind anything.
        val alphaCentauri = EquatorialCoordinate.fromRightAscensionHours(14 + 39.0 / 60 + 36.5 / 3600, -(60 + 50.0 / 60 + 2.0 / 3600))
        val down = NightPlanner.visibility(listOf(alphaCentauri), summary = december, horizon = ring)[0]
        assertTrue(down.unobstructedIntervals.isEmpty())
        assertFalse(down.isBehindSkyline)

        // A low skyline lets Polaris through.
        assertFalse(NightPlanner.visibility(listOf(polaris), summary = december, horizon = HorizonProfile.constant(10.0))[0].isBehindSkyline)
    }

    // MARK: Many targets

    @Test
    fun testTheCheapBatchFindsTheSameStretchesAsTheFullPlanner() {
        val list = targets(80, 23uL)
        val wall = HorizonProfile.flat.setting(30.0, AzimuthRange.between(100.0, 260.0))
        for (horizon in listOf<HorizonProfile?>(null, wall, Support.truthProfile)) {
            val full = NightPlanner.visibility(list, summary = december, horizon = horizon)
            val cheap = NightPlanner.unobstructedVisibility(list, summary = december, horizon = horizon)
            assertEquals(list.size, cheap.size)
            for (index in list.indices) {
                val message = "target $index over ${horizon?.maximumAltitude}"
                Planning.assertIntervals(cheap[index].intervals, full[index].unobstructedIntervals, 2.0, message)
                assertEquals(full[index].unobstructedHours, cheap[index].hours, 0.002, message)
                assertEquals(full[index].isBehindSkyline, cheap[index].isBehindSkyline, message)
            }
            if (horizon == wall) assertTrue(cheap.count { it.isBehindSkyline } > 3)
            if (horizon == null) assertTrue(cheap.none { it.isBehindSkyline })
        }
    }

    @Test
    fun testTheCheapBatchHonoursDarknessMinimumAltitudeAndProperMotion() {
        val june = Planning.summary(Planning.boulder, 2026, 6, 20)
        val barnard = EquatorialCoordinate(269.452_075, 4.693_391)
        val motion = ProperMotion(-798.58, 10_328.12)
        val list = targets(30, 24uL) + barnard
        val motions: List<ProperMotion?> = List(list.size - 1) { null } + motion
        val ridge = HorizonProfile.constant(12.0)
        for (kind in DarknessKind.entries) {
            for (minimum in listOf(0.0, 35.0, Double.NaN)) {
                val full = NightPlanner.visibility(list, motions, june, kind, minimum, ridge)
                val cheap = NightPlanner.unobstructedVisibility(list, motions, june, kind, minimum, ridge)
                for (index in list.indices) {
                    Planning.assertIntervals(cheap[index].intervals, full[index].unobstructedIntervals, 2.0, "$kind $minimum $index")
                }
            }
        }
    }

    @Test
    fun testTheCheapBatchInASpanAgreesWithTheFullPlannerInTheSpan() {
        val span = DateInterval(december.night.start.addingTimeInterval(8 * 3_600.0), 12 * 3_600.0)
        val list = targets(40, 25uL)
        val wall = HorizonProfile.flat.setting(30.0, AzimuthRange.between(60.0, 140.0))
        val full = NightPlanner.visibility(list, observer = observer, interval = span, horizon = wall)
        val cheap = NightPlanner.unobstructedVisibility(list, observer = observer, interval = span, horizon = wall)
        for (index in list.indices) {
            Planning.assertIntervals(cheap[index].intervals, full[index].unobstructedIntervals, 2.0, "target $index")
        }
    }

    @Test
    fun testTargetsWithoutAUsablePositionAreNeverClear() {
        val bad = EquatorialCoordinate(Double.NaN, Double.NaN)
        val good = EquatorialCoordinate.fromRightAscensionHours(5.9, 7.4)
        for (horizon in listOf<HorizonProfile?>(null, HorizonProfile.constant(5.0))) {
            val results = NightPlanner.unobstructedVisibility(listOf(bad, good), summary = december, horizon = horizon)
            assertTrue(results[0].intervals.isEmpty())
            assertEquals(0.0, results[0].hours)
            assertFalse(results[0].isBehindSkyline)
            assertTrue(results[1].hours > 3)
            assertTrue(NightPlanner.unobstructedVisibility(emptyList(), summary = december, horizon = horizon).isEmpty())
        }
    }

    @Test
    fun testWithoutDarknessNothingIsClear() {
        val white = Planning.summary(Planning.tromso, 2026, 6, 21)
        val results = NightPlanner.unobstructedVisibility(
            listOf(EquatorialCoordinate.fromRightAscensionHours(18.6, 38.8)), summary = white, horizon = Support.truthProfile,
        )
        assertTrue(results[0].intervals.isEmpty())
        assertFalse(results[0].isBehindSkyline)
    }

    @Test
    fun testAThousandTargetsThroughTheCheapBatchAreFast() {
        val list = targets(1_000, 26uL)
        NightPlanner.unobstructedVisibility(list, summary = december, horizon = Support.truthProfile) // warm up
        val start = System.nanoTime()
        val results = NightPlanner.unobstructedVisibility(list, summary = december, horizon = Support.truthProfile)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(1_000, results.size)
        // About 0.05 s on a desktop JVM; the bound only catches a computation that has gone badly wrong.
        assertTrue(elapsed < 3.0, "took $elapsed s")
        assertTrue(results.count { it.hours > 0 } > 200)
    }
}

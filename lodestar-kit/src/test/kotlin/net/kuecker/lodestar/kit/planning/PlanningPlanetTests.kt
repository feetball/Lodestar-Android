package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The planets in a night summary: which ones are up in the dark, how high they get and how bright they are. */
class PlanningPlanetTests {
    private val boulder = PlanningTestSupport.boulder

    private fun altitude(body: SolarSystemBody, date: Instant, observer: Observer): Double =
        SkyFrame(date, observer).horizontal(body, false).altitude

    // MARK: Contents

    @Test
    fun testPlanetsRunFromMercuryToNeptune() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        assertEquals(
            listOf(
                SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS, SolarSystemBody.JUPITER,
                SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
            ),
            summary.planets.map { it.body },
        )
        assertEquals(10.0, PlanetVisibility.minimumAltitude)
    }

    @Test
    fun testEachPlanetCarriesTheCalculatorsEventsAndTheEphemerisMagnitude() {
        for (site in listOf(boulder, PlanningTestSupport.sydney)) {
            val summary = PlanningTestSupport.summary(site, 2026, 12, 14)
            val reference = JulianDate(summary.referenceDate)
            for (planet in summary.planets) {
                val label = "${site.name} ${planet.body.displayName}"
                assertEquals(
                    RiseSetCalculator.riseTransitSet(planet.body, site.observer, summary.night),
                    planet.riseTransitSet, label,
                )
                assertEquals(Ephemeris.state(planet.body, reference).apparentMagnitude, planet.magnitude, label)
                assertNotNull(planet.magnitude, label)
            }
        }
    }

    @Test
    fun testMagnitudesAreInThePhysicalRanges() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        val limits: Map<SolarSystemBody, ClosedFloatingPointRange<Double>> = mapOf(
            SolarSystemBody.MERCURY to (-2.7)..6.0, SolarSystemBody.VENUS to (-4.95)..(-3.3),
            SolarSystemBody.MARS to (-3.0)..2.0, SolarSystemBody.JUPITER to (-3.0)..(-1.5),
            SolarSystemBody.SATURN to (-0.6)..1.6, SolarSystemBody.URANUS to 5.3..6.1,
            SolarSystemBody.NEPTUNE to 7.7..8.0,
        )
        for (planet in summary.planets) {
            val magnitude = assertNotNull(planet.magnitude, planet.body.displayName)
            assertTrue(assertNotNull(limits[planet.body]).contains(magnitude), "${planet.body.displayName} $magnitude")
        }
    }

    // MARK: A night with planets

    @Test
    fun testDecemberEveningSkyHoldsTheOuterPlanets() {
        // Boulder, 14 December 2026: Saturn, Uranus and Neptune are up in the evening; Mars and Jupiter climb
        // from the east before midnight; Venus is a morning object; Mercury is lost in the dawn.
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        val visible = summary.planets.associate { it.body to it.visibleDuringDarkness }
        assertEquals(
            mapOf(
                SolarSystemBody.MERCURY to false, SolarSystemBody.VENUS to true, SolarSystemBody.MARS to true,
                SolarSystemBody.JUPITER to true, SolarSystemBody.SATURN to true, SolarSystemBody.URANUS to true,
                SolarSystemBody.NEPTUNE to true,
            ),
            visible,
        )
        val night = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        val saturn = assertNotNull(summary.planets.firstOrNull { it.body == SolarSystemBody.SATURN })
        assertEquals(night.start, assertNotNull(saturn.visibleIntervals.firstOrNull()).start, "up already at dusk")
        assertTrue(assertNotNull(saturn.visibleIntervals.lastOrNull()).end < night.end, "sets before dawn")
        val jupiter = assertNotNull(summary.planets.firstOrNull { it.body == SolarSystemBody.JUPITER })
        assertTrue(assertNotNull(jupiter.visibleIntervals.firstOrNull()).start > night.start, "rises after dusk")
        assertEquals(night.end, assertNotNull(jupiter.visibleIntervals.lastOrNull()).end, "still up at dawn")
    }

    @Test
    fun testMercuryAndVenusStayOutOfTheDarkWhenTheyAreNearTheSun() {
        // Boulder, 14 June 2026: Mercury sets before astronomical dusk and Venus half an hour after it, too
        // low to count; the planner still reports when they go down.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 14)
        val dusk = assertNotNull(summary.twilight.astronomicalDusk)
        for (body in listOf(SolarSystemBody.MERCURY, SolarSystemBody.VENUS)) {
            val planet = assertNotNull(summary.planets.firstOrNull { it.body == body })
            assertFalse(planet.visibleDuringDarkness, body.displayName)
            assertTrue(planet.visibleIntervals.isEmpty(), body.displayName)
            assertTrue(assertNotNull(planet.maxAltitudeDuringDarkness) < PlanetVisibility.minimumAltitude, body.displayName)
            assertNotNull(planet.riseTransitSet.set, body.displayName)
            assertTrue(assertNotNull(planet.riseTransitSet.set) < dusk.addingTimeInterval(3 * 3_600.0), body.displayName)
        }
    }

    @Test
    fun testPolarNightJudgesTheWholeWindow() {
        // Ten kilometres from the North Pole on 21 December the Sun stays near −23°: the whole 24 hours are
        // astronomically dark, and a planet's altitude hardly changes with the hour, so each one's highest
        // point of the window counts. Planets north of the celestial equator are up all night.
        val pole = PlanningTestSupport.Site("North Pole", 89.9, 0.0)
        val summary = PlanningTestSupport.summary(pole, 2026, 12, 21)
        assertEquals(listOf(summary.night), summary.astronomicalDarknessIntervals)
        val observer = summary.observer
        for (planet in summary.planets) {
            val culminations = RiseSetCalculator.upperCulminations(planet.body, observer, summary.night)
            val expected = (
                listOf(
                    altitude(planet.body, summary.night.start, observer),
                    altitude(planet.body, summary.night.end, observer),
                ) + culminations.map { it.altitude }
                ).maxOrNull()
            assertEquals(
                assertNotNull(expected), assertNotNull(planet.maxAltitudeDuringDarkness), 0.002,
                planet.body.displayName,
            )
            // At the pole the altitude is the declination, give or take the 0.1° of latitude.
            val middle = summary.night.start.addingTimeInterval(summary.night.duration / 2)
            val declination = SkyFrame(middle, observer).topocentricEquatorial(planet.body).declination
            if (declination > 10.5) {
                PlanningTestSupport.assertIntervals(planet.visibleIntervals, listOf(summary.night), 1.0, planet.body.displayName)
            } else if (declination < 9.5) {
                assertTrue(planet.visibleIntervals.isEmpty(), planet.body.displayName)
            }
        }
        assertTrue(summary.planets.any { it.visibleDuringDarkness })
        assertTrue(summary.planets.any { !it.visibleDuringDarkness })
    }

    // MARK: Agreement with independent calculations

    /**
     * The sine-sampled answers against direct altitudes: the visible stretches are bounded by the dark
     * time and the 10° line, and the maximum is a culmination or an end of the dark time.
     */
    @Test
    fun testPlanetAnswersAreConsistentWithDirectAltitudes() {
        data class Case(val site: PlanningTestSupport.Site, val month: Int, val day: Int)
        val cases = listOf(
            Case(boulder, 3, 20), Case(boulder, 9, 22), Case(boulder, 12, 21),
            Case(PlanningTestSupport.sydney, 6, 21), Case(PlanningTestSupport.sydney, 12, 21),
            Case(PlanningTestSupport.quito, 3, 20), Case(PlanningTestSupport.paranal, 9, 22),
            Case(PlanningTestSupport.london, 12, 21),
        )
        var visibleRecords = 0
        var hiddenRecords = 0
        for ((site, month, day) in cases) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            val observer = site.observer
            val darkness = summary.astronomicalDarknessIntervals
            val darkEdges = darkness.flatMap { listOf(it.start, it.end) }
            for (planet in summary.planets) {
                val label = "${site.name} 2026-$month-$day ${planet.body.displayName}"
                val body = planet.body
                if (planet.visibleIntervals.isEmpty()) hiddenRecords += 1 else visibleRecords += 1

                assertEquals(planet.visibleIntervals, IntervalSet.intersection(planet.visibleIntervals, darkness), label)
                for (interval in planet.visibleIntervals) {
                    val middle = interval.start.addingTimeInterval(interval.duration / 2)
                    assertTrue(altitude(body, middle, observer) >= 10, label)
                    for (edge in listOf(interval.start, interval.end)) {
                        if (!darkEdges.all { abs(it.timeIntervalSince(edge)) > 1 }) continue
                        assertEquals(10.0, altitude(body, edge, observer), 0.01, "$label: an edge at 10°")
                    }
                }
                for (gap in IntervalSet.subtracting(planet.visibleIntervals, darkness)) {
                    val middle = gap.start.addingTimeInterval(gap.duration / 2)
                    assertTrue(altitude(body, middle, observer) < 10, label)
                }

                val maximum = planet.maxAltitudeDuringDarkness
                if (maximum == null) {
                    assertTrue(darkness.isEmpty(), label)
                    continue
                }
                val inDark = RiseSetCalculator.upperCulminations(body, observer, summary.night)
                    .filter { culmination -> darkness.any { culmination.time in it } }
                    .map { it.altitude }
                val ends = darkEdges.map { altitude(body, it, observer) }
                assertEquals(assertNotNull((inDark + ends).maxOrNull()), maximum, 0.002, label)
                if (maximum > 10.01) assertTrue(planet.visibleDuringDarkness, label)
                if (maximum < 9.99) assertFalse(planet.visibleDuringDarkness, label)
            }
        }
        assertTrue(visibleRecords > 20)
        assertTrue(hiddenRecords > 10)
    }

    @Test
    fun testVisibleStretchesMatchTheRootFinder() {
        // The root finder works on the same altitude function with a different method (bisection on the
        // true altitude instead of cubic interpolation of sines), so agreement to a second means the
        // interpolation is as good as claimed.
        for ((site, month, day) in listOf(Triple(boulder, 12, 14), Triple(PlanningTestSupport.sydney, 12, 21))) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            for (planet in summary.planets) {
                val expected = IntervalSet.intersection(
                    PlanningTestSupport.intervals(10.0, summary.night) { altitude(planet.body, it, summary.observer) },
                    summary.astronomicalDarknessIntervals,
                )
                PlanningTestSupport.assertIntervals(planet.visibleIntervals, expected, 1.0, "${site.name} ${planet.body.displayName}")
            }
        }
    }
}

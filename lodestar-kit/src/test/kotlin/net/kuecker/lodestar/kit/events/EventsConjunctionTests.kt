package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conjunctions and close approaches.
 *
 * References (JPL Horizons API, DE441, queried 2026-10-18; geocentric astrometric or apparent RA/Dec at 1–5 minute steps,
 * the minimum of the angular separation read off the table; Hipparcos places moved by their proper motion for the stars):
 * - Venus–Jupiter 2025-08-12 06:38 UT 0.8605°, 2026-06-09 19:48:45 UT 1.6081°.
 * - Moon–Venus 2026-06-17 20:30 UT 0.2697°, Moon–Jupiter 2026-10-06 10:24 UT 0.1582°, Moon–Mars 2026-11-02 13:40 UT 0.9661°,
 *   Moon–Mercury 2026-02-18 23:10 UT 0.1194°, Venus–Saturn 2026-03-08 13:15 UT 0.9114°, Mars–Jupiter 2026-11-16 02:15 UT 1.1935°.
 * - Moon–Regulus 2026-05-23 07:18 UT 0.0696°, Moon–Spica 2026-01-10 23:10 UT 1.5033°, Moon–Antares 2026-01-14 20:02 UT 0.5520°,
 *   Moon–Pleiades 2026-01-27 21:20 UT 1.0485°.
 * - Topocentric Moon–Venus on 2026-06-17 (SITE_COORD, apparent RA/Dec at 1 minute): Dallas 19:56 UT 0.1583°,
 *   Sydney 19:32 UT 0.7328°.
 */
class EventsConjunctionTests {
    private fun conjunctions(from: String, to: String, options: EventOptions = EventOptions(), observer: Observer? = null) =
        EventsTestSupport.conjunctions(ConjunctionFinder.events(utc(from), utc(to), observer, options))

    private fun find(
        found: List<Pair<AstronomicalEvent, Conjunction>>,
        first: ConjunctionTarget,
        second: ConjunctionTarget,
        near: String,
    ): Pair<AstronomicalEvent, Conjunction> {
        val moment = utc(near)
        val match = found.filter { it.second.first == first && it.second.second == second }
            .minByOrNull { kotlin.math.abs(EventsTestSupport.seconds(it.first.date, moment)) }
        return assertNotNull(match, "$first $second near $near")
    }

    private fun body(body: SolarSystemBody) = ConjunctionTarget.Body(body)

    private fun star(star: ReferenceStar) = ConjunctionTarget.Star(star)

    @Test
    fun testJupiterVenusConjunctionsOf2025And2026() {
        // The brief: Jupiter–Venus 2025-08-12 and 2026-06-09. Venus comes first in the planet order only after Mercury.
        val found = conjunctions("2025-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val august = find(found, body(SolarSystemBody.VENUS), body(SolarSystemBody.JUPITER), "2025-08-12T06:38:00Z")
        assertInstant("2025-08-12T06:38:40Z", august.first.date, 240.0, "2025")
        assertNear(0.8605, august.second.separation, 0.002, "2025 separation")
        assertTrue(august.second.solarElongation > 30 && august.second.solarElongation < 40)
        assertTrue(!august.second.involvesMoon && !august.second.occultationPossible)
        val june = find(found, body(SolarSystemBody.VENUS), body(SolarSystemBody.JUPITER), "2026-06-09T19:48:45Z")
        assertInstant("2026-06-09T19:48:45Z", june.first.date, 240.0, "2026")
        assertNear(1.6081, june.second.separation, 0.002, "2026 separation")
        // Both are within the default planet threshold of 3°; they are the only Venus–Jupiter conjunctions of the two years.
        assertEquals(2, found.count { it.second.first == body(SolarSystemBody.VENUS) && it.second.second == body(SolarSystemBody.JUPITER) })
    }

    @Test
    fun testMoonAndPlanetConjunctionsAgainstHorizons() {
        val found = conjunctions("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val moon = body(SolarSystemBody.MOON)
        val references = listOf(
            Triple(SolarSystemBody.VENUS, "2026-06-17T20:30:00Z", 0.2697),
            Triple(SolarSystemBody.JUPITER, "2026-10-06T10:24:00Z", 0.1582),
            Triple(SolarSystemBody.MARS, "2026-11-02T13:40:00Z", 0.9661),
            Triple(SolarSystemBody.MERCURY, "2026-02-18T23:10:00Z", 0.1194),
        )
        for ((planet, time, separation) in references) {
            val event = find(found, moon, body(planet), time)
            assertInstant(time, event.first.date, 180.0, "Moon–$planet")
            assertNear(separation, event.second.separation, 0.003, "Moon–$planet separation")
            assertTrue(event.second.involvesMoon)
        }
    }

    @Test
    fun testPlanetPairsAgainstHorizons() {
        val found = conjunctions("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val saturn = find(found, body(SolarSystemBody.VENUS), body(SolarSystemBody.SATURN), "2026-03-08T13:15:00Z")
        assertInstant("2026-03-08T13:15:00Z", saturn.first.date, 300.0, "Venus–Saturn")
        assertNear(0.9114, saturn.second.separation, 0.003, "Venus–Saturn separation")
        // Mars and Jupiter 1.19° apart in November (they move slowly relative to each other: ten minutes is nothing).
        val jupiter = find(found, body(SolarSystemBody.MARS), body(SolarSystemBody.JUPITER), "2026-11-16T02:15:00Z")
        assertInstant("2026-11-16T02:15:00Z", jupiter.first.date, 1_200.0, "Mars–Jupiter")
        assertNear(1.1935, jupiter.second.separation, 0.003, "Mars–Jupiter separation")
    }

    @Test
    fun testMoonWithBrightStarsAndThePleiades() {
        val found = conjunctions("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val moon = body(SolarSystemBody.MOON)
        val references = listOf(
            Triple(ReferenceStar.REGULUS, "2026-05-23T07:18:00Z", 0.0696),
            Triple(ReferenceStar.SPICA, "2026-01-10T23:10:00Z", 1.5033),
            Triple(ReferenceStar.ANTARES, "2026-01-14T20:02:00Z", 0.5520),
            Triple(ReferenceStar.PLEIADES, "2026-01-27T21:20:00Z", 1.0485),
        )
        for ((reference, time, separation) in references) {
            val event = find(found, moon, star(reference), time)
            assertInstant(time, event.first.date, 180.0, "Moon–${reference.displayName}")
            assertNear(separation, event.second.separation, 0.003, "Moon–${reference.displayName} separation")
        }
        // The Moon passes Regulus, Spica and Antares every month (the Moon is within 5° of the ecliptic and these stars
        // are within 2.1° of it), the Pleiades whenever the Moon is far enough north, and Aldebaran (5.5° south of the
        // ecliptic) not at all in 2026, when the ascending node is near the equinox.
        for (reference in listOf(ReferenceStar.REGULUS, ReferenceStar.SPICA, ReferenceStar.ANTARES)) {
            assertTrue(found.count { it.second.second == star(reference) } >= 12, reference.displayName)
        }
        assertEquals(0, found.count { it.second.second == star(ReferenceStar.ALDEBARAN) })
    }

    @Test
    fun testOccultationPossibleFlag() {
        val found = conjunctions("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        val regulus = find(found, body(SolarSystemBody.MOON), star(ReferenceStar.REGULUS), "2026-05-23T07:18:00Z")
        assertTrue(regulus.second.occultationPossible, "0.07° apart: the Moon's disc covers Regulus from somewhere")
        val spica = find(found, body(SolarSystemBody.MOON), star(ReferenceStar.SPICA), "2026-01-10T23:10:00Z")
        assertTrue(!spica.second.occultationPossible, "1.5° apart is beyond the Moon's radius and parallax")
        for ((_, conjunction) in found) {
            if (!conjunction.involvesMoon) assertTrue(!conjunction.occultationPossible)
        }
    }

    @Test
    fun testTopocentricApproachAgainstHorizons() {
        // 2026-06-17: the Moon's parallax moves it by up to a degree against Venus: Dallas sees them 0.158° apart at
        // 19:56 UT, Sydney 0.733° at 19:32 UT (Horizons), against 0.270° from the Earth's centre at 20:30 UT.
        val dallas = conjunctions("2026-06-15T00:00:00Z", "2026-06-19T00:00:00Z", observer = EventsTestSupport.dallas.observer)
        val venusFromDallas = find(dallas, body(SolarSystemBody.MOON), body(SolarSystemBody.VENUS), "2026-06-17T20:30:00Z")
        val topocentricDallas = assertNotNull(venusFromDallas.second.topocentric)
        assertInstant("2026-06-17T19:56:00Z", topocentricDallas.time, 180.0, "Dallas time")
        assertNear(0.1583, topocentricDallas.separation, 0.005, "Dallas separation")
        assertTrue(!topocentricDallas.isOccultation || topocentricDallas.separation < 0.4)
        val sydney = conjunctions("2026-06-15T00:00:00Z", "2026-06-19T00:00:00Z", observer = EventsTestSupport.sydney.observer)
        val venusFromSydney = find(sydney, body(SolarSystemBody.MOON), body(SolarSystemBody.VENUS), "2026-06-17T20:30:00Z")
        val topocentricSydney = assertNotNull(venusFromSydney.second.topocentric)
        assertInstant("2026-06-17T19:32:00Z", topocentricSydney.time, 180.0, "Sydney time")
        assertNear(0.7328, topocentricSydney.separation, 0.005, "Sydney separation")
        assertTrue(!topocentricSydney.isOccultation)
        assertNear(0.2697, venusFromDallas.second.separation, 0.003, "geocentric separation")
        // Without an observer there is no topocentric note, and planet pairs never have one.
        val geocentric = conjunctions("2026-06-15T00:00:00Z", "2026-06-19T00:00:00Z")
        assertTrue(geocentric.all { it.second.topocentric == null })
        assertTrue(dallas.filter { !it.second.involvesMoon }.all { it.second.topocentric == null })
    }

    @Test
    fun testTopocentricOccultationOfRegulusInTheSouthPacific() {
        // Scanning the Earth on a 4° grid, the Moon's disc covers Regulus on 2026-05-23 along a band through the South
        // Pacific (e.g. 36S 128W, 0.08° at closest). A site in the band reports an occultation, Dallas does not.
        val band = Observer(GeographicLocation(-36.0, -128.0, 0.0))
        val found = conjunctions("2026-05-22T00:00:00Z", "2026-05-24T00:00:00Z", observer = band)
        val regulus = find(found, body(SolarSystemBody.MOON), star(ReferenceStar.REGULUS), "2026-05-23T07:18:00Z")
        val approach = assertNotNull(regulus.second.topocentric)
        assertTrue(approach.separation < 0.15, "separation ${approach.separation}")
        assertTrue(approach.isOccultation)
        val dallas = conjunctions("2026-05-22T00:00:00Z", "2026-05-24T00:00:00Z", observer = EventsTestSupport.dallas.observer)
        val approachDallas = assertNotNull(find(dallas, body(SolarSystemBody.MOON), star(ReferenceStar.REGULUS), "2026-05-23T07:18:00Z").second.topocentric)
        assertTrue(!approachDallas.isOccultation && approachDallas.separation > 0.3)
    }

    @Test
    fun testThresholdsAndElongationFilterSelectConjunctions() {
        val from = "2026-01-01T00:00:00Z"
        val to = "2026-12-31T23:59:59Z"
        val defaults = conjunctions(from, to)
        for ((event, conjunction) in defaults) {
            val limit = if (conjunction.involvesMoon) 5.0 else 3.0
            assertTrue(conjunction.separation <= limit, "${event.date} ${conjunction.separation}")
            assertTrue(conjunction.solarElongation >= 10.0, "${event.date} elongation ${conjunction.solarElongation}")
        }
        // Tighter thresholds keep a subset; nothing farther apart than the threshold survives.
        val tight = conjunctions(from, to, EventOptions(planetConjunctionThreshold = 1.0, moonConjunctionThreshold = 1.0))
        assertTrue(tight.size < defaults.size)
        assertTrue(tight.all { it.second.separation <= 1.0 })
        val defaultTimes = defaults.map { it.first.date }.toSet()
        assertTrue(tight.all { it.first.date in defaultTimes })
        // Looser thresholds and no elongation limit find more, including conjunctions in the Sun's glare.
        val loose = conjunctions(from, to, EventOptions(planetConjunctionThreshold = 5.0, moonConjunctionThreshold = 8.0, minimumSolarElongation = 0.0))
        assertTrue(loose.size > defaults.size)
        assertTrue(loose.any { it.second.solarElongation < 10 })
        // The Moon passes close to something about every other day.
        assertTrue(defaults.count { it.second.involvesMoon } in 90..125)
    }

    @Test
    fun testConjunctionsAreSortedAndOrdered() {
        val found = ConjunctionFinder.events(utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"))
        for (index in 1 until found.size) assertTrue(found[index].date >= found[index - 1].date)
        for ((_, conjunction) in EventsTestSupport.conjunctions(found)) {
            if (conjunction.involvesMoon) {
                assertTrue(conjunction.second != body(SolarSystemBody.MOON))
            } else {
                val first = (conjunction.first as ConjunctionTarget.Body).body
                val second = (conjunction.second as ConjunctionTarget.Body).body
                assertTrue(SolarSystemBody.entries.indexOf(first) < SolarSystemBody.entries.indexOf(second))
            }
        }
    }

    @Test
    fun testConjunctionIsALocalMinimumOfTheSeparation() {
        for ((event, conjunction) in conjunctions("2026-03-01T00:00:00Z", "2026-06-30T00:00:00Z")) {
            fun separation(offsetDays: Double): Double {
                val jd = JulianDate(event.date).addingDays(offsetDays)
                fun vector(target: ConjunctionTarget): Vector3 =
                    when (target) {
                        is ConjunctionTarget.Body -> Ephemeris.geocentricJ2000(target.body, jd)
                        is ConjunctionTarget.Star -> target.star.position.unitVector
                    }
                return vector(conjunction.first).angle(vector(conjunction.second))
            }
            val step = if (conjunction.involvesMoon) 0.1 else 1.0
            assertTrue(separation(0.0) <= separation(-step) + 1e-6 && separation(0.0) <= separation(step) + 1e-6, "${event.date}")
            assertNear(conjunction.separation, separation(0.0), 0.01, "${event.date} separation")
        }
    }

    @Test
    fun testTargetsDescribeWhatToAimAt() {
        val venus = body(SolarSystemBody.VENUS)
        assertEquals("Venus", venus.displayName)
        assertEquals(AimTarget.Body(SolarSystemBody.VENUS), venus.aimTarget)
        val antares = star(ReferenceStar.ANTARES)
        assertEquals("Antares", antares.displayName)
        assertEquals(AimTarget.Fixed(ReferenceStar.ANTARES.position, ReferenceStar.ANTARES.properMotion), antares.aimTarget)
        assertEquals(5, ReferenceStar.entries.size)
        assertEquals(ReferenceStar.PLEIADES, ReferenceStar.fromRawValue("pleiades"))
        assertNull(ReferenceStar.fromRawValue("sirius"))
        // The kit's catalog gives the same J2000 places to the milliarcsecond.
        assertNear(152.092967, ReferenceStar.REGULUS.position.rightAscension, 1e-6, "Regulus RA")
        assertNear(-26.432002, ReferenceStar.ANTARES.position.declination, 1e-6, "Antares Dec")
    }

    @Test
    fun testEmptyRangesAndTheEdgesOfARange() {
        val moment = utc("2026-06-09T19:48:45Z")
        assertTrue(ConjunctionFinder.events(moment, moment.minusSeconds(1)).isEmpty())
        // A range that ends just before the Venus–Jupiter conjunction does not contain it; one that starts just after neither.
        assertTrue(conjunctions("2026-06-09T00:00:00Z", "2026-06-09T19:00:00Z").none { it.second.first == body(SolarSystemBody.VENUS) && it.second.second == body(SolarSystemBody.JUPITER) })
        assertTrue(conjunctions("2026-06-09T20:30:00Z", "2026-06-10T12:00:00Z").none { it.second.first == body(SolarSystemBody.VENUS) && it.second.second == body(SolarSystemBody.JUPITER) })
        assertEquals(1, conjunctions("2026-06-09T19:00:00Z", "2026-06-09T20:30:00Z").count { it.second.first == body(SolarSystemBody.VENUS) && it.second.second == body(SolarSystemBody.JUPITER) })
    }
}

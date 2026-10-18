package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Oppositions, greatest elongations, equinoxes and solstices, and the Earth's apsides.
 *
 * References:
 * - JPL Horizons API (https://ssd.jpl.nasa.gov/api/horizons.api, DE441), geocentric observer tables at 1 h steps queried
 *   2026-10-18: apparent ecliptic longitudes (quantity 31) of the planet and of the Sun, whose difference passes 180° at
 *   an opposition; and the Sun–observer–target angle (quantity 23), whose maximum is the greatest elongation (found as
 *   the zero of its central difference over 24 h).
 * - U.S. Naval Observatory, *Earth's Seasons* (https://aa.usno.navy.mil/api/seasons?year=2026): equinoxes, solstices,
 *   perihelion and aphelion to the minute, UT.
 */
class EventsPlanetaryTests {
    private fun events(finder: (java.time.Instant, java.time.Instant) -> List<AstronomicalEvent>, from: String, to: String) =
        finder(utc(from), utc(to))

    // MARK: Oppositions

    @Test
    fun testMarsOppositionsAgainstHorizons() {
        // Horizons: Mars opposition 2025-01-16 02:38:35 UT (Sun at 296.23°), 2027-02-19 15:50:55 UT.
        val found = EventsTestSupport.oppositions(events(OppositionFinder::events, "2025-01-01T00:00:00Z", "2027-12-31T23:59:59Z"))
        val mars = found.filter { it.second.planet == SolarSystemBody.MARS }
        assertEquals(2, mars.size)
        assertInstant("2025-01-16T02:38:35Z", mars[0].first.date, 180.0, "Mars 2025")
        assertInstant("2027-02-19T15:50:55Z", mars[1].first.date, 180.0, "Mars 2027")
        // Distance 0.6437 AU (closest approach 2025-01-12 0.6422 AU), magnitude -1.4, disc 14.5″.
        assertNear(0.6437, mars[0].second.distanceAU, 0.001, "Mars 2025 distance")
        assertNear(-1.44, mars[0].second.apparentMagnitude ?: 99.0, 0.05, "Mars 2025 magnitude")
        assertNear(14.5, mars[0].second.angularDiameterArcseconds, 0.1, "Mars 2025 diameter")
        assertNear(0.678, mars[1].second.distanceAU, 0.001, "Mars 2027 distance")
    }

    @Test
    fun testOuterPlanetOppositionsAgainstHorizons() {
        // Horizons: Jupiter 2026-01-10 08:42:10, Saturn 2025-09-21 05:45:39 and 2026-10-04 12:29:12,
        // Uranus 2025-11-21 12:25:25, Neptune 2025-09-23 12:53:35 UT.
        val found = EventsTestSupport.oppositions(events(OppositionFinder::events, "2025-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))
        fun date(planet: SolarSystemBody, year: Int) =
            found.first { it.second.planet == planet && it.first.date.toString().startsWith(year.toString()) }.first.date
        assertInstant("2026-01-10T08:42:10Z", date(SolarSystemBody.JUPITER, 2026), 240.0, "Jupiter")
        assertInstant("2025-09-21T05:45:39Z", date(SolarSystemBody.SATURN, 2025), 240.0, "Saturn 2025")
        assertInstant("2026-10-04T12:29:12Z", date(SolarSystemBody.SATURN, 2026), 240.0, "Saturn 2026")
        assertInstant("2025-11-21T12:25:25Z", date(SolarSystemBody.URANUS, 2025), 240.0, "Uranus")
        assertInstant("2025-09-23T12:53:35Z", date(SolarSystemBody.NEPTUNE, 2025), 240.0, "Neptune")
    }

    @Test
    fun testOppositionPutsThePlanetOppositeTheSun() {
        for ((event, opposition) in EventsTestSupport.oppositions(events(OppositionFinder::events, "2026-01-01T00:00:00Z", "2027-12-31T23:59:59Z"))) {
            val jd = JulianDate(event.date)
            val planet = Ephemeris.state(opposition.planet, jd)
            val sun = Ephemeris.state(SolarSystemBody.SUN, jd)
            val difference = AstroMath.normalizeSignedDegrees(planet.eclipticLongitudeOfDate - sun.eclipticLongitudeOfDate)
            assertTrue(kotlin.math.abs(kotlin.math.abs(difference) - 180) < 1e-4, "${opposition.planet}: $difference")
            assertNear(180.0, planet.elongation ?: 0.0, 5.0, "${opposition.planet} elongation")
        }
        // Mars, Jupiter, Saturn, Uranus and Neptune: one opposition per synodic period, 13 in 2025-2027.
        assertEquals(13, events(OppositionFinder::events, "2025-01-01T00:00:00Z", "2027-12-31T23:59:59Z").size)
    }

    // MARK: Greatest elongations

    @Test
    fun testVenusGreatestElongationsOf2025And2026() {
        // Horizons: Venus 2025-01-10 05:00 UT east 47.169°; 2025-06-01 03:30 west 45.883°; 2026-08-15 06:30 east 45.892°.
        val found = EventsTestSupport.elongations(events(GreatestElongationFinder::events, "2025-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))
        val venus = found.filter { it.second.planet == SolarSystemBody.VENUS }
        assertEquals(3, venus.size)
        assertInstant("2025-01-10T05:00:00Z", venus[0].first.date, 600.0, "Venus 2025-01")
        assertEquals(ElongationDirection.EAST, venus[0].second.direction)
        assertNear(47.169, venus[0].second.angle, 0.01, "Venus 2025-01 angle")
        assertInstant("2025-06-01T03:30:00Z", venus[1].first.date, 600.0, "Venus 2025-06")
        assertEquals(ElongationDirection.WEST, venus[1].second.direction)
        assertNear(45.883, venus[1].second.angle, 0.01, "Venus 2025-06 angle")
        assertInstant("2026-08-15T06:30:00Z", venus[2].first.date, 600.0, "Venus 2026-08")
        assertEquals(ElongationDirection.EAST, venus[2].second.direction)
        assertNear(45.892, venus[2].second.angle, 0.01, "Venus 2026-08 angle")
    }

    @Test
    fun testMercuryGreatestElongationsAlternateEastAndWest() {
        // Horizons: Mercury 2025-03-08 06:06 UT east 18.248°. Mercury has three evening and three morning elongations
        // a year (here 2025: Mar 8 E, Apr 21 W, Jul 4 E, Aug 19 W, Oct 29 E, Dec 7 W; angles 18.2, 27.4, 25.9, 18.6, 23.9, 20.7).
        val found = EventsTestSupport.elongations(events(GreatestElongationFinder::events, "2025-01-01T00:00:00Z", "2025-12-31T23:59:59Z"))
        val mercury = found.filter { it.second.planet == SolarSystemBody.MERCURY }
        assertEquals(6, mercury.size)
        assertInstant("2025-03-08T06:06:00Z", mercury[0].first.date, 600.0, "Mercury 2025-03")
        assertNear(18.248, mercury[0].second.angle, 0.01, "Mercury 2025-03 angle")
        val directions = mercury.map { it.second.direction }
        assertEquals(
            listOf(
                ElongationDirection.EAST, ElongationDirection.WEST, ElongationDirection.EAST, ElongationDirection.WEST,
                ElongationDirection.EAST, ElongationDirection.WEST,
            ),
            directions,
        )
        val angles = listOf(18.2, 27.4, 25.9, 18.6, 23.9, 20.7)
        for (index in angles.indices) {
            assertNear(angles[index], mercury[index].second.angle, 0.1, "Mercury elongation $index")
        }
    }

    @Test
    fun testElongationIsAMaximumOfTheSeparation() {
        for ((event, elongation) in EventsTestSupport.elongations(events(GreatestElongationFinder::events, "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))) {
            fun angle(offsetDays: Double): Double {
                val jd = JulianDate(event.date).addingDays(offsetDays)
                return Ephemeris.geocentricJ2000(elongation.planet, jd).angle(Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd))
            }
            assertTrue(angle(0.0) >= angle(-2.0) && angle(0.0) >= angle(2.0), "${elongation.planet} ${event.date}")
            assertNear(angle(0.0), elongation.angle, 1e-3, "${event.date} angle")
        }
    }

    // MARK: Equinoxes, solstices, apsides

    @Test
    fun testEquinoxesAndSolsticesOf2026AgainstUsno() {
        // USNO 2026 (UT): March equinox 20 Mar 14:46, June solstice 21 Jun 08:24, September equinox 23 Sep 00:05,
        // December solstice 21 Dec 20:50. The times are rounded to the minute: allow a minute and a half.
        val found = EventsTestSupport.seasons(events(SeasonFinder::events, "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))
        assertEquals(
            listOf(SeasonKind.MARCH_EQUINOX, SeasonKind.JUNE_SOLSTICE, SeasonKind.SEPTEMBER_EQUINOX, SeasonKind.DECEMBER_SOLSTICE),
            found.map { it.second.kind },
        )
        assertInstant("2026-03-20T14:46:00Z", found[0].first.date, 90.0, "March equinox")
        assertInstant("2026-06-21T08:24:00Z", found[1].first.date, 90.0, "June solstice")
        assertInstant("2026-09-23T00:05:00Z", found[2].first.date, 90.0, "September equinox")
        assertInstant("2026-12-21T20:50:00Z", found[3].first.date, 90.0, "December solstice")
    }

    @Test
    fun testSeasonsPutTheSunAtTheRightLongitude() {
        for ((event, season) in EventsTestSupport.seasons(events(SeasonFinder::events, "2026-06-01T00:00:00Z", "2030-06-01T00:00:00Z"))) {
            val longitude = Ephemeris.state(SolarSystemBody.SUN, JulianDate(event.date)).eclipticLongitudeOfDate
            assertTrue(kotlin.math.abs(AstroMath.normalizeSignedDegrees(longitude - season.kind.solarLongitude)) < 1e-4, "${event.date}")
        }
        assertEquals(16, events(SeasonFinder::events, "2026-06-01T00:00:00Z", "2030-06-01T00:00:00Z").size)
    }

    @Test
    fun testPerihelionAndAphelionOf2026AgainstUsno() {
        // USNO 2026: perihelion 3 Jan 17:15 UT, aphelion 6 Jul 17:30 UT. The distance is flat there: the aphelion is
        // only good to a quarter of an hour.
        val found = EventsTestSupport.apsides(events(EarthApsisFinder::events, "2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z"))
        assertEquals(listOf(EarthApsisKind.PERIHELION, EarthApsisKind.APHELION), found.map { it.second.kind })
        assertInstant("2026-01-03T17:15:00Z", found[0].first.date, 240.0, "perihelion")
        assertInstant("2026-07-06T17:30:00Z", found[1].first.date, 1_500.0, "aphelion")
        assertNear(0.98330, found[0].second.distanceAU, 1e-4, "perihelion distance")
        assertNear(1.01664, found[1].second.distanceAU, 1e-4, "aphelion distance")
    }

    @Test
    fun testApsidesAreOptionalByDefault() {
        assertTrue(EventKind.EARTH_APSIS !in EventOptions().kinds)
        assertTrue(EventKind.EARTH_APSIS in EventKind.entries)
        val events = EventCalculator.events(utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"))
        assertTrue(events.none { it.kind == EventKind.EARTH_APSIS })
        val withApsides = EventCalculator.events(
            utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"), null, EventOptions(kinds = setOf(EventKind.EARTH_APSIS)),
        )
        assertEquals(2, withApsides.size)
    }

    @Test
    fun testEmptyAndReversedRangesGiveNoEvents() {
        val moment = utc("2026-03-20T14:46:00Z")
        assertTrue(SeasonFinder.events(moment, moment.minusSeconds(1)).isEmpty())
        assertTrue(OppositionFinder.events(moment, moment.minusSeconds(1)).isEmpty())
        assertTrue(GreatestElongationFinder.events(moment, moment.minusSeconds(1)).isEmpty())
        assertTrue(EarthApsisFinder.events(moment, moment.minusSeconds(1)).isEmpty())
    }
}

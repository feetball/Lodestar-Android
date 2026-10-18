package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Supermoons, micromoons and blue moons.
 *
 * References: JPL Horizons API (DE441), Moon target 301, geocentric centre distance (quantity 20) at the full Moon of 2026:
 * Jan 3 10:03 UT 362 312.4 km, May 1 17:23 401 999.5 km, May 31 08:45 406 134.7 km, Jun 29 23:57 405 254.0 km, Nov 24 14:53
 * 360 766.1 km, Dec 24 01:28 356 738.3 km. Full Moon times from the kit's phase calculator (checked against the USNO
 * tables to the minute). Blue moons: the full Moons of 2018-01-02 and 2018-01-31, 2018-03-02 and 2018-03-31 and of
 * 2020-10-01 and 2020-10-31 (14:49 UT, which is already 1 November in eastern Australia) are well known.
 */
class EventsMoonSpecialTests {
    private fun specials(from: String, to: String, zone: ZoneId = ZoneId.of("UTC")) =
        EventsTestSupport.specials(FullMoonSpecialFinder.events(utc(from), utc(to), zone))

    @Test
    fun testSpecialFullMoonsOf2026() {
        val found = specials("2026-01-01T00:00:00Z", "2026-12-31T23:59:59Z")
        assertEquals(6, found.size)
        val expected = listOf(
            Triple("2026-01-03T10:03:00Z", listOf(FullMoonCategory.SUPERMOON), 362_312.4),
            Triple("2026-05-01T17:23:00Z", listOf(FullMoonCategory.MICROMOON), 401_999.5),
            Triple("2026-05-31T08:45:00Z", listOf(FullMoonCategory.MICROMOON, FullMoonCategory.BLUE_MOON), 406_134.7),
            Triple("2026-06-29T23:57:00Z", listOf(FullMoonCategory.MICROMOON), 405_254.0),
            Triple("2026-11-24T14:53:00Z", listOf(FullMoonCategory.SUPERMOON), 360_766.1),
            Triple("2026-12-24T01:28:00Z", listOf(FullMoonCategory.SUPERMOON), 356_738.3),
        )
        for (index in expected.indices) {
            assertInstant(expected[index].first, found[index].first.date, 120.0, "full Moon $index")
            assertEquals(expected[index].second, found[index].second.categories, "categories $index")
            assertNear(expected[index].third, found[index].second.distanceKilometers, 15.0, "distance $index")
        }
    }

    @Test
    fun testSupermoonOfJanuary2026() {
        // The brief asks for a supermoon in 2026: 3 January, 362 312 km, the Moon's disc 1978″ wide.
        val january = specials("2026-01-01T00:00:00Z", "2026-01-31T23:59:59Z")
        assertEquals(1, january.size)
        val supermoon = january[0].second
        assertEquals(listOf(FullMoonCategory.SUPERMOON), supermoon.categories)
        assertTrue(supermoon.distanceKilometers <= FullMoonSpecialFinder.supermoonMaximumDistanceKilometers)
        assertNear(1978.0, supermoon.angularDiameterArcseconds, 3.0, "angular diameter")
    }

    @Test
    fun testDefinitionsAreDocumentedConstants() {
        assertEquals(362_500.0, FullMoonSpecialFinder.supermoonMaximumDistanceKilometers, 0.0)
        assertEquals(400_600.0, FullMoonSpecialFinder.micromoonMinimumDistanceKilometers, 0.0)
        // Equal margins from the extreme perigee (356 371 km) and apogee (406 720 km) of the lunar orbit.
        assertNear(
            FullMoonSpecialFinder.supermoonMaximumDistanceKilometers - 356_371.0,
            406_720.0 - FullMoonSpecialFinder.micromoonMinimumDistanceKilometers, 100.0, "symmetry",
        )
        assertEquals(listOf("supermoon", "micromoon", "blueMoon"), FullMoonCategory.entries.map { it.rawValue })
        assertEquals(FullMoonCategory.BLUE_MOON, FullMoonCategory.fromRawValue("blueMoon"))
    }

    @Test
    fun testBlueMoonsOf2018() {
        val found = specials("2018-01-01T00:00:00Z", "2018-12-31T23:59:59Z")
        val blue = found.filter { FullMoonCategory.BLUE_MOON in it.second.categories }
        assertEquals(2, blue.size)
        assertInstant("2018-01-31T13:27:00Z", blue[0].first.date, 120.0, "January blue moon")
        assertInstant("2018-03-31T12:37:00Z", blue[1].first.date, 120.0, "March blue moon")
        // The January 31 full Moon was also a supermoon (the "super blue blood moon", total eclipse that day).
        assertTrue(FullMoonCategory.SUPERMOON in blue[0].second.categories)
    }

    @Test
    fun testBlueMoonDependsOnTheTimeZone() {
        // 2020: full Moons on October 1 21:05 UT and October 31 14:49 UT. In Sydney (UTC+11 in October) the second one
        // falls on 1 November at 01:49, so there is no blue moon there; in UTC and in New York there is.
        val utc = specials("2020-10-01T00:00:00Z", "2020-11-02T00:00:00Z", ZoneId.of("UTC"))
        assertEquals(1, utc.count { FullMoonCategory.BLUE_MOON in it.second.categories })
        val newYork = specials("2020-10-01T00:00:00Z", "2020-11-02T00:00:00Z", ZoneId.of("America/New_York"))
        assertEquals(1, newYork.count { FullMoonCategory.BLUE_MOON in it.second.categories })
        val sydney = specials("2020-10-01T00:00:00Z", "2020-11-02T00:00:00Z", ZoneId.of("Australia/Sydney"))
        assertEquals(0, sydney.count { FullMoonCategory.BLUE_MOON in it.second.categories })
    }

    @Test
    fun testBlueMoonNeedsTheFirstFullMoonOfTheMonthEvenBeforeTheRange() {
        // The range starts after the first full Moon of May 2026 (May 1): the second (May 31) is still blue.
        val found = specials("2026-05-15T00:00:00Z", "2026-06-15T00:00:00Z")
        assertEquals(1, found.size)
        assertEquals(listOf(FullMoonCategory.MICROMOON, FullMoonCategory.BLUE_MOON), found[0].second.categories)
        // And a range that holds only the first full Moon of the month does not make it blue.
        val first = specials("2026-04-25T00:00:00Z", "2026-05-10T00:00:00Z")
        assertEquals(1, first.size)
        assertEquals(listOf(FullMoonCategory.MICROMOON), first[0].second.categories)
    }

    @Test
    fun testEveryReportedMoonIsAFullMoon() {
        for ((event, _) in specials("2025-01-01T00:00:00Z", "2027-12-31T23:59:59Z")) {
            assertNear(180.0, Ephemeris.lunarElongation(JulianDate(event.date)), 1e-3, "${event.date}")
        }
    }

    @Test
    fun testSupermoonVisibilityAtMoonrise() {
        // The December 2026 supermoon (24 Dec 01:28 UT) at Boulder: 18:28 MST on December 23, with the Moon high in the
        // east after sunset; the window starts after sunset and holds the Moon above 10°.
        val options = EventOptions(kinds = setOf(EventKind.FULL_MOON_SPECIAL), timeZone = ZoneId.of("America/Denver"))
        val events = EventCalculator.events(
            utc("2026-12-20T00:00:00Z"), utc("2026-12-28T00:00:00Z"), EventsTestSupport.boulder.observer, options,
        )
        assertEquals(1, events.size)
        val visibility = assertNotNull(events[0].visibility)
        assertEquals(RiseSetCalculator.sunHorizonAltitude, visibility.darknessLimit, 0.0)
        assertTrue(visibility.altitude > 10, "the Moon at the instant of full: ${visibility.altitude}")
        val window = assertNotNull(visibility.bestWindow)
        val night = TwilightCalculator.night(utc("2026-12-23T19:00:00Z"), EventsTestSupport.boulder.observer, ZoneId.of("America/Denver"))
        val sunset = assertNotNull(night.sunset)
        assertTrue(window.start >= sunset.minusSeconds(1), "the window starts after sunset")
        assertTrue(window.end <= assertNotNull(night.sunrise).plusSeconds(1))
        assertEquals(listOf(SolarSystemBody.MOON), events[0].aimTargets.map { (it as AimTarget.Body).body })
    }
}

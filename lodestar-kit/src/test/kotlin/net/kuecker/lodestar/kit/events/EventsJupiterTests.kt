package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertInstant
import net.kuecker.lodestar.kit.events.EventsTestSupport.assertNear
import net.kuecker.lodestar.kit.events.EventsTestSupport.dallas
import net.kuecker.lodestar.kit.events.EventsTestSupport.utc
import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Planet stations, and the Great Red Spot and moon events in the calendar.
 *
 * References:
 * - Skyfield 1.55 with JPL DE440s: apparent geocentric ecliptic longitude of date of the planet, the instant of its
 *   extremum found on a 4001-point grid over the two days around the 6-hourly sign change of the rate and refined by a
 *   parabola through 101 points. Mars retrograde begins 2024-12-06 23:33:09 UT (126.171°) and direct motion resumes
 *   2025-02-24 01:59:50 UT (107.015°), Mars again 2027-01-10 12:59:11 UT; Mercury retrograde begins 2026-02-26
 *   06:48:10 UT and direct motion resumes 2026-03-20 19:32:50 UT; Jupiter retrograde begins 2026-12-13 00:56:36 UT.
 */
class EventsJupiterTests {
    // MARK: Stations

    @Test
    fun testMarsStationsAgainstDE440s() {
        val found = PlanetStations.find(SolarSystemBody.MARS, utc("2024-10-01T00:00:00Z"), utc("2025-04-01T00:00:00Z"))
        assertEquals(2, found.size)
        val first = found[0].details as EventDetails.PlanetStation
        val second = found[1].details as EventDetails.PlanetStation
        assertEquals(PlanetStationKind.RETROGRADE_BEGINS, first.station.kind)
        assertEquals(PlanetStationKind.DIRECT_MOTION_RESUMES, second.station.kind)
        assertEquals(SolarSystemBody.MARS, first.station.planet)
        assertInstant("2024-12-06T23:33:09Z", found[0].date, 3 * 3600.0, "Mars retrograde")
        assertInstant("2025-02-24T01:59:50Z", found[1].date, 3 * 3600.0, "Mars direct")
        assertNear(126.171, first.station.longitude, 0.05, "Mars longitude")
        assertNear(107.015, second.station.longitude, 0.05, "Mars longitude")
        assertTrue(found.all { it.visibility == null && it.kind == EventKind.PLANET_STATION })
    }

    @Test
    fun testMercuryAndJupiterStationsAgainstDE440s() {
        val mercury = PlanetStations.find(SolarSystemBody.MERCURY, utc("2026-01-01T00:00:00Z"), utc("2026-04-30T00:00:00Z"))
        assertEquals(2, mercury.size)
        assertInstant("2026-02-26T06:48:10Z", mercury[0].date, 3 * 3600.0, "Mercury retrograde")
        assertInstant("2026-03-20T19:32:50Z", mercury[1].date, 3 * 3600.0, "Mercury direct")
        val jupiter = PlanetStations.find(SolarSystemBody.JUPITER, utc("2026-11-01T00:00:00Z"), utc("2027-01-31T00:00:00Z"))
        assertEquals(1, jupiter.size)
        assertInstant("2026-12-13T00:56:36Z", jupiter[0].date, 12 * 3600.0, "Jupiter retrograde")
    }

    @Test
    fun testStationSearchLimits() {
        val from = utc("2024-10-01T00:00:00Z")
        assertTrue(PlanetStations.find(SolarSystemBody.MOON, from, utc("2025-04-01T00:00:00Z")).isEmpty())
        assertTrue(PlanetStations.find(SolarSystemBody.SUN, from, utc("2025-04-01T00:00:00Z")).isEmpty())
        assertTrue(PlanetStations.find(SolarSystemBody.MARS, utc("2025-04-01T00:00:00Z"), from).isEmpty())
        // Every planet from Mercury to Neptune has stations in a few years, alternating retrograde and direct.
        for (planet in listOf(
            SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS, SolarSystemBody.JUPITER,
            SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
        )) {
            val stations = PlanetStations.find(planet, utc("2025-01-01T00:00:00Z"), utc("2028-01-01T00:00:00Z"))
            assertTrue(stations.size >= 2, "$planet: ${stations.size}")
            val kinds = stations.map { (it.details as EventDetails.PlanetStation).station.kind }
            for ((previous, next) in kinds.zipWithNext()) assertTrue(previous != next, "$planet alternates")
        }
        val year = PlanetStations.events(utc("2026-01-01T00:00:00Z"), utc("2026-12-31T23:59:59Z"))
        assertEquals(16, year.size)
        assertEquals(year.sortedBy { it.date }, year)
        val event = PlanetStations.find(SolarSystemBody.MARS, from, utc("2025-04-01T00:00:00Z"))[0]
        assertEquals("Mars stationary: retrograde motion begins", event.title)
        assertEquals(listOf(AimTarget.Body(SolarSystemBody.MARS)), event.aimTargets)
    }

    // MARK: Calendar

    @Test
    fun testCalendarListsGreatRedSpotAndMoonEventsForTheFirstDays() {
        val from = utc("2026-11-19T00:00:00Z")
        val to = utc("2026-12-19T00:00:00Z")
        val options = EventOptions(kinds = setOf(EventKind.GREAT_RED_SPOT_TRANSIT, EventKind.JUPITER_MOON_EVENT))
        val events = EventCalculator.events(from, to, null, options)
        assertTrue(events.isNotEmpty())
        val limit = from.addingTimeInterval(2 * 86_400.0)
        assertTrue(events.all { it.date <= limit }, "only the first two days")
        val transits = events.filter { it.kind == EventKind.GREAT_RED_SPOT_TRANSIT }
        assertTrue(transits.size in 4..6, "transits: ${transits.size}")
        assertTrue(events.any { it.kind == EventKind.JUPITER_MOON_EVENT })
        assertTrue(transits.all { it.title == "Great Red Spot transit" && it.aimTargets == listOf(AimTarget.Body(SolarSystemBody.JUPITER)) })
        assertEquals(events.sortedBy { it.date }, events)
        // The window of the days can be changed, or switched off.
        val none = EventCalculator.events(from, to, null, EventOptions(kinds = options.kinds, jupiterEventDays = 0.0))
        assertTrue(none.isEmpty())
        val week = EventCalculator.events(from, to, null, EventOptions(kinds = options.kinds, jupiterEventDays = 7.0))
        assertTrue(week.size > events.size * 3)
        assertEquals(2.0, EventOptions(jupiterEventDays = Double.NaN).jupiterEventDays)
        assertEquals(30.0, EventOptions(jupiterEventDays = 100.0).jupiterEventDays)
    }

    @Test
    fun testCalendarUsesTheGreatRedSpotOfTheOptions() {
        val from = utc("2026-11-19T00:00:00Z")
        val to = utc("2026-11-20T00:00:00Z")
        val shifted = assertNotNull(GreatRedSpot.create(GreatRedSpot.reference.systemIILongitude(from) + 30, from, 0.0))
        val kinds = setOf(EventKind.GREAT_RED_SPOT_TRANSIT)
        val reference = EventCalculator.events(from, to, null, EventOptions(kinds = kinds))
        val other = EventCalculator.events(from, to, null, EventOptions(kinds = kinds, greatRedSpot = shifted))
        assertTrue(reference.size >= 2 && other.size >= 2)
        assertTrue(reference[0].date != other[0].date)
    }

    @Test
    fun testJupiterEventsAreObservableOnlyWhileJupiterIsUpInTheDark() {
        val from = utc("2026-12-01T00:00:00Z")
        val to = utc("2026-12-03T00:00:00Z")
        val kinds = setOf(EventKind.GREAT_RED_SPOT_TRANSIT, EventKind.JUPITER_MOON_EVENT)
        val options = EventOptions(kinds = kinds, timeZone = java.time.ZoneId.of("America/Chicago"))
        val events = EventCalculator.events(from, to, dallas.observer, options)
        assertTrue(events.size > 10)
        val visible = events.filter { it.visibility?.isObservable == true }
        assertTrue(visible.isNotEmpty() && visible.size < events.size, "some are, some are not: ${visible.size} of ${events.size}")
        for (event in events) {
            val visibility = assertNotNull(event.visibility)
            val window = visibility.bestWindow
            if (window != null) {
                assertTrue(window.start.timeIntervalSince(event.date) <= 3 * 3600.0)
                assertNotNull(visibility.bestTime)
            }
            if (visibility.isObservable) {
                assertTrue(visibility.altitude > 5, "${event.title}: altitude ${visibility.altitude}")
                assertTrue(visibility.sunAltitude < -3, "${event.title}: Sun at ${visibility.sunAltitude}")
            }
        }
        val onlyVisible = EventCalculator.events(from, to, dallas.observer, EventOptions(
            kinds = kinds, timeZone = java.time.ZoneId.of("America/Chicago"), visibleOnly = true))
        assertEquals(visible.size, onlyVisible.size)
    }

    @Test
    fun testStationsInTheCalendarCarryVisibility() {
        val options = EventOptions(kinds = setOf(EventKind.PLANET_STATION))
        val events = EventCalculator.events(utc("2026-06-01T00:00:00Z"), utc("2026-12-31T00:00:00Z"), dallas.observer, options)
        assertTrue(events.isNotEmpty())
        assertTrue(events.all { it.visibility != null && it.kind == EventKind.PLANET_STATION })
        assertEquals(EventKind.PLANET_STATION, EventKind.fromRawValue("planetStation"))
        assertEquals(EventKind.GREAT_RED_SPOT_TRANSIT, EventKind.fromRawValue("greatRedSpotTransit"))
        assertEquals(EventKind.JUPITER_MOON_EVENT, EventKind.fromRawValue("jupiterMoonEvent"))
        assertTrue(EventKind.calendarKinds.containsAll(listOf(EventKind.PLANET_STATION, EventKind.JUPITER_MOON_EVENT, EventKind.GREAT_RED_SPOT_TRANSIT)))
        assertTrue(EventKind.defaultKinds.none { it == EventKind.PLANET_STATION || it == EventKind.JUPITER_MOON_EVENT || it == EventKind.GREAT_RED_SPOT_TRANSIT })
    }
}

package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import net.kuecker.lodestar.kit.satellites.SatelliteTransitCentreline
import net.kuecker.lodestar.services.SatelliteGroup
import net.kuecker.lodestar.services.SatelliteLibrary
import net.kuecker.lodestar.services.issOmmJson
import net.kuecker.lodestar.services.ommArray
import net.kuecker.lodestar.services.poiskOmmJson
import net.kuecker.lodestar.services.tianheOmmJson
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EventsTransitsTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    private fun library(vararg records: String): SatelliteLibrary =
        SatelliteLibrary.EMPTY.with(SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(*records), now).value)

    private fun observer(latitude: Double, longitude: Double) = Observer(GeographicLocation(latitude, longitude, 100.0))

    // Availability

    @Test
    fun theSectionFollowsTheFeedAndTheFreshnessOfTheElementSets() {
        val full = library(issOmmJson, tianheOmmJson)
        assertEquals(EventsTransitAvailability.OFF, EventsTransits.availability(false, full, now))
        assertEquals(EventsTransitAvailability.NEEDS_DATA, EventsTransits.availability(true, SatelliteLibrary.EMPTY, now))
        assertEquals(EventsTransitAvailability.READY, EventsTransits.availability(true, full, now))
        // The stations group is good for three days.
        assertEquals(EventsTransitAvailability.READY, EventsTransits.availability(true, full, now.plus(Duration.ofDays(2))))
        assertEquals(EventsTransitAvailability.NEEDS_DATA, EventsTransits.availability(true, full, now.plus(Duration.ofDays(5))))
    }

    @Test
    fun onlyTheIssAndTiangongAreSearchedAndOnlyWithFreshData() {
        val library = library(poiskOmmJson, tianheOmmJson, issOmmJson)
        assertEquals(listOf(25544, 48274), EventsTransits.satellites(library, now).map { it.satellite.catalogNumber })
        assertTrue(EventsTransits.satellites(library, now.plus(Duration.ofDays(5))).isEmpty())
    }

    // Finding

    @Test
    fun transitsAreFoundForBothBodiesInTimeOrderWithTheNamesTheScreensShow() {
        val satellites = EventsTransits.satellites(library(issOmmJson, tianheOmmJson), now)
        val sites = listOf(
            observer(40.0, -105.3), observer(51.5, -0.1), observer(35.7, 139.7), observer(-33.9, 151.2),
            observer(48.9, 2.3), observer(34.0, -118.2), observer(-23.5, -46.6), observer(28.6, 77.2),
        )
        val found = sites.map { EventsTransits.compute(satellites, it, now) }
        for (items in found) {
            assertEquals(items.map { it.transit.date }.sorted(), items.map { it.transit.date })
            for (item in items) {
                assertTrue(item.transit.date >= now && item.transit.date <= now.plus(Duration.ofDays(EventsTransits.HORIZON_DAYS)))
                assertTrue(item.transit.isFromSite || item.transit.centreline != null)
                assertTrue((item.transit.centreline?.distanceMeters ?: 0.0) <= EventsTransits.MAX_GROUND_DISTANCE_METERS)
                assertEquals(if (item.transit.catalogNumber == 25544) "ISS" else "Tiangong", item.satelliteName)
            }
        }
        val all = found.flatten()
        assertTrue(all.isNotEmpty(), "No transit for any of ${sites.size} sites in 14 days")
        assertTrue(all.any { it.transit.body == SolarSystemBody.SUN } && all.any { it.transit.body == SolarSystemBody.MOON })
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun theListIsCutAtTheNowAndTheLimit() {
        val satellites = EventsTransits.satellites(library(issOmmJson, tianheOmmJson), now)
        val items = (0 until 12).flatMap { index ->
            EventsTransits.compute(satellites, observer(10.0 + index * 4.0, -105.0 + index * 25.0), now)
        }.sortedBy { it.transit.date }
        assertTrue(items.size > 2)
        val middle = items[items.size / 2].transit.date
        val upcoming = EventsTransits.upcoming(items, middle)
        assertTrue(upcoming.all { it.transit.date >= middle })
        assertEquals(items.count { it.transit.date >= middle }.coerceAtMost(EventsTransits.LIST_LIMIT), upcoming.size)
        assertTrue(EventsTransits.upcoming(items, now.plus(Duration.ofDays(60))).isEmpty())
    }

    // Cache key

    @Test
    fun theKeyIgnoresSmallMovesButNotNewElementSetsOrFarMoves() {
        val satellites = EventsTransits.satellites(library(issOmmJson, tianheOmmJson), now)
        val key = EventsTransitKey.of(satellites, observer(40.0000, -105.3000))
        assertEquals(key, EventsTransitKey.of(satellites, observer(40.0004, -105.2996)))
        assertNotEquals(key, EventsTransitKey.of(satellites, observer(40.0100, -105.3000)))
        assertNotEquals(key, EventsTransitKey.of(satellites, observer(40.0000, -105.2900)))
        val newer = EventsTransits.satellites(library(issOmmJson.replace("2026-10-18T11:12:47", "2026-10-18T15:12:47"), tianheOmmJson), now)
        assertNotEquals(key, EventsTransitKey.of(newer, observer(40.0000, -105.3000)))
        assertNotEquals(key, EventsTransitKey.of(satellites.take(1), observer(40.0000, -105.3000)))
    }

    // Texts

    @Test
    fun distancesAndDurationsAreReadable() {
        assertEquals("0 m", EventsTransitText.distance(0.0, Locale.US))
        assertEquals("800 m", EventsTransitText.distance(803.0, Locale.US))
        assertEquals("1.0 km", EventsTransitText.distance(960.0, Locale.US))
        assertEquals("2.3 km", EventsTransitText.distance(2_340.0, Locale.US))
        assertEquals("12 km", EventsTransitText.distance(12_345.0, Locale.US))
        assertEquals("50 km", EventsTransitText.distance(50_000.0, Locale.US))
        assertEquals("0.6 s", EventsTransitText.duration(0.62, Locale.US))
        assertEquals("1.3 s", EventsTransitText.duration(1.26, Locale.US))
        assertEquals("<0.1 s", EventsTransitText.duration(0.01, Locale.US))
        assertEquals("<0.1 s", EventsTransitText.duration(Double.NaN, Locale.US))
        assertEquals("612 km", EventsTransitText.range(611.6, Locale.US))
        assertEquals("109 m · 0.014° across", EventsTransitText.size(25544, 0.0136, Locale.US))
        assertEquals("55 m · 0.007° across", EventsTransitText.size(48274, 0.0071, Locale.US))
    }

    @Test
    fun coordinatesAndDirectionsMatchWhatTheMapsAppsTake() {
        assertEquals("40.12345, -105.23456", EventsTransitText.coordinates(GeographicLocation(40.123454, -105.234556, 10.0)))
        assertEquals("-33.86880, 151.20930", EventsTransitText.coordinates(GeographicLocation(-33.8688, 151.2093, 0.0)))
        assertTrue(EventsTransitText.bearing(45.0).startsWith("NE"))
        val centreline = SatelliteTransitCentreline(GeographicLocation(40.0, -105.0, 0.0), 12_300.0, 44.0, 0.0)
        assertEquals("centreline 12 km NE", EventsTransitText.centrelineSummary(centreline))
    }

    @Test
    fun theSkyAndTheMissAreDescribed() {
        assertEquals("Daylight", EventsTransitText.sky(isDaylight = true, isNight = false))
        assertEquals("Twilight", EventsTransitText.sky(isDaylight = false, isNight = false))
        assertEquals("Night", EventsTransitText.sky(isDaylight = false, isNight = true))
        assertEquals("Crosses the disc", EventsTransitText.fromHere(true, 0.1, 0.27))
        assertEquals("Misses the disc by 7′", EventsTransitText.fromHere(false, 0.39, 0.27))
    }
}

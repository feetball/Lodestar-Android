package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineScheduleTest {
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val t0 = 1_800_000_000_000L

    private fun state(
        resource: OnlineResource,
        attemptAgo: Long? = null,
        failed: Boolean = false,
        savedAgo: Long? = null,
        expiresIn: Long? = null,
        epochAgo: Long? = null,
        key: String? = null,
        now: Long = t0,
    ) = OnlineResourceState(
        lastAttemptMillis = attemptAgo?.let { now - it },
        lastAttemptFailed = failed,
        meta = savedAgo?.let {
            OnlineCacheMeta(now - it, expiresMillis = expiresIn?.let { delta -> now + delta }, dataEpochMillis = epochAgo?.let { ago -> now - ago }, key = key)
        },
    )

    @Test
    fun theFeedsAndTheirResources() {
        assertEquals(listOf(OnlineResource.STATIONS, OnlineResource.VISUAL), OnlineFeed.SATELLITE_PASSES.resources)
        assertEquals(listOf(OnlineResource.STARLINK), OnlineFeed.STARLINK.resources)
        assertEquals(listOf(OnlineResource.COMETS), OnlineFeed.COMETS.resources)
        assertEquals(listOf(OnlineResource.FORECAST), OnlineFeed.CLOUDS.resources)
        assertTrue(OnlineResource.STARLINK.requiresUnmetered)
        assertFalse(OnlineResource.STATIONS.requiresUnmetered)
    }

    @Test
    fun celesTrakGroupsNeverCloserThanTheEnginesMinimumInterval() {
        val minimum = (ElementSetGroup.minimumDownloadIntervalHours * hour).toLong()
        for (resource in listOf(OnlineResource.STATIONS, OnlineResource.VISUAL, OnlineResource.STARLINK)) {
            assertEquals(minimum, resource.minimumIntervalMillis)
            assertEquals(minimum, resource.retryIntervalMillis)
            assertFalse(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = minimum - 1, savedAgo = minimum - 1), t0))
            assertTrue(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = minimum, savedAgo = minimum), t0))
            // A refused request (403) waits as long.
            assertFalse(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = hour, failed = true), t0))
            assertTrue(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = 2 * hour + 1, failed = true), t0))
        }
    }

    @Test
    fun refreshIntervalsFollowTheEngineAndAreDueOnlyWhenOld() {
        for (group in ElementSetGroup.entries) {
            val resource = OnlineResource.entries.first { it.group == group }
            val interval = (group.refreshIntervalHours * hour).toLong()
            assertEquals(interval, resource.refreshIntervalMillis)
            assertFalse(OnlineSchedule.isDue(resource, state(resource, savedAgo = interval - 1, epochAgo = 0), t0))
            assertTrue(OnlineSchedule.isDue(resource, state(resource, savedAgo = interval, epochAgo = 0), t0))
        }
    }

    @Test
    fun noDataIsDueAtOnce() {
        for (resource in OnlineResource.entries) assertTrue(OnlineSchedule.isDue(resource, state(resource), t0))
        assertTrue(OnlineSchedule.shouldRefresh(OnlineResource.STATIONS, state(OnlineResource.STATIONS), t0))
    }

    @Test
    fun stationDataWhoseNewestElementIsOlderThanTwoDaysIsDueEarly() {
        val resource = OnlineResource.STATIONS
        assertFalse(OnlineSchedule.isDue(resource, state(resource, savedAgo = 3 * hour, epochAgo = 47 * hour), t0))
        assertTrue(OnlineSchedule.isDue(resource, state(resource, savedAgo = 3 * hour, epochAgo = 49 * hour), t0))
        // ...but never sooner than the request rules allow.
        assertFalse(OnlineSchedule.shouldRefresh(resource, state(resource, attemptAgo = hour, savedAgo = hour, epochAgo = 49 * hour), t0))
        assertTrue(OnlineSchedule.shouldRefresh(resource, state(resource, attemptAgo = 3 * hour, savedAgo = 3 * hour, epochAgo = 49 * hour), t0))
    }

    @Test
    fun cometsAreFetchedAtMostOnceADayAndRetriedAfterAnHour() {
        val resource = OnlineResource.COMETS
        assertFalse(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = day - 1, savedAgo = day - 1), t0))
        assertTrue(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = day, savedAgo = day), t0))
        // A failed attempt must not block a whole day.
        assertFalse(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = hour - 1, failed = true, savedAgo = 30 * hour), t0))
        assertTrue(OnlineSchedule.mayRequest(resource, state(resource, attemptAgo = hour, failed = true, savedAgo = 30 * hour), t0))
        assertFalse(OnlineSchedule.isDue(resource, state(resource, savedAgo = day - 1), t0))
        assertTrue(OnlineSchedule.isDue(resource, state(resource, savedAgo = day), t0))
    }

    @Test
    fun theForecastWaitsForExpiresOfTheSamePlace() {
        val resource = OnlineResource.FORECAST
        val key = "32.8,-96.8"
        // Expires in 20 minutes: not before, even though the minimum interval of ten minutes has passed.
        val waiting = state(resource, attemptAgo = 11 * 60_000L, savedAgo = 11 * 60_000L, expiresIn = 20 * 60_000L, key = key)
        assertFalse(OnlineSchedule.mayRequest(resource, waiting, t0, key))
        assertFalse(OnlineSchedule.isDue(resource, waiting, t0, key))
        assertTrue(OnlineSchedule.mayRequest(resource, waiting, t0 + 21 * 60_000L, key))
        assertTrue(OnlineSchedule.isDue(resource, waiting, t0 + 21 * 60_000L, key))
        // The jitter pushes the next request later.
        assertFalse(OnlineSchedule.mayRequest(resource, waiting, t0 + 21 * 60_000L, key, expiresJitterMillis = 120_000))
        assertTrue(OnlineSchedule.mayRequest(resource, waiting, t0 + 23 * 60_000L, key, expiresJitterMillis = 120_000))
    }

    @Test
    fun anotherPlaceIsDueAtOnceButStillWaitsTheMinimumInterval() {
        val resource = OnlineResource.FORECAST
        val here = state(resource, attemptAgo = 11 * 60_000L, savedAgo = 11 * 60_000L, expiresIn = 20 * 60_000L, key = "32.8,-96.8")
        // Another grid point has its own Expires.
        assertTrue(OnlineSchedule.isDue(resource, here, t0, "40.0,-75.1"))
        assertTrue(OnlineSchedule.mayRequest(resource, here, t0, "40.0,-75.1"))
        val justAsked = state(resource, attemptAgo = 5 * 60_000L, savedAgo = 5 * 60_000L, expiresIn = 20 * 60_000L, key = "32.8,-96.8")
        assertTrue(OnlineSchedule.isDue(resource, justAsked, t0, "40.0,-75.1"))
        assertFalse(OnlineSchedule.mayRequest(resource, justAsked, t0, "40.0,-75.1"))
    }

    @Test
    fun theUpdateNowTimeIsReportedAsTheEarliestRequest() {
        val resource = OnlineResource.STATIONS
        val s = state(resource, attemptAgo = hour, savedAgo = hour)
        assertEquals(t0 + hour, OnlineSchedule.earliestRequestMillis(resource, s))
        assertEquals(0L, OnlineSchedule.earliestRequestMillis(resource, state(resource)))
    }

    @Test
    fun coordinatesAreRoundedToATenthOfADegree() {
        assertEquals("32.8", OnlineCoordinates.rounded(32.7767))
        assertEquals("-96.8", OnlineCoordinates.rounded(-96.7970))
        assertEquals("0.0", OnlineCoordinates.rounded(-0.04))
        assertEquals("0.1", OnlineCoordinates.rounded(0.05))
        assertEquals("-33.9", OnlineCoordinates.rounded(-33.8688))
        assertEquals("lat=32.8&lon=-96.8", OnlineCoordinates.query(32.7767, -96.7970))
        assertEquals("32.8,-96.8", OnlineCoordinates.key(32.7767, -96.7970))
        // Places within the rounding share a request.
        assertEquals(OnlineCoordinates.key(32.76, -96.82), OnlineCoordinates.key(32.84, -96.78))
        assertTrue(OnlineCoordinates.key(32.76, -96.82) != OnlineCoordinates.key(32.86, -96.82))
    }

    @Test
    fun theUrlsAreSecureAndTheForecastCarriesTheRoundedPlace() {
        for (resource in OnlineResource.entries) {
            val url = resource.url(32.7767, -96.797)
            assertTrue(url.startsWith("https://"), url)
        }
        assertEquals("https://celestrak.org/NORAD/elements/gp.php?GROUP=stations&FORMAT=json", OnlineResource.STATIONS.url())
        assertEquals("https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt", OnlineResource.COMETS.url())
        assertEquals(
            "https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=32.8&lon=-96.8",
            OnlineResource.FORECAST.url(32.7767, -96.797),
        )
        assertNull(OnlineResource.COMETS.group)
    }
}

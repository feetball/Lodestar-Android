package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.satellites.ElementSetGroup
import net.kuecker.lodestar.kit.satellites.Satellite
import net.kuecker.lodestar.kit.satellites.SatellitePass
import net.kuecker.lodestar.kit.satellites.SatellitePassPoint
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SatelliteLibraryTest {
    private val now = Instant.parse("2026-10-18T20:00:00Z")
    private val dallas = Observer(GeographicLocation(32.78, -96.8, 150.0))

    private fun group(group: ElementSetGroup, vararg records: String) =
        SatelliteGroup.parse(group, ommArray(*records), now).value

    @Test
    fun aDownloadIsParsedIntoSatellitesWithTheNewestEpoch() {
        val parsed = SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(issOmmJson, tianheOmmJson), now)
        assertEquals(2, parsed.value.satellites.size)
        assertEquals(Instant.parse("2026-10-18T11:12:47.041Z").toEpochMilli(), parsed.value.newest?.epoch?.toEpochMilli())
        assertEquals(parsed.value.newest?.epoch?.toEpochMilli(), parsed.dataEpochMillis)
        assertEquals(0, parsed.value.skippedCount)
        assertTrue(parsed.value.isFresh(now))
        // Three days is the limit of the stations group.
        assertTrue(parsed.value.isFresh(now.plus(Duration.ofDays(2))))
        assertFalse(parsed.value.isFresh(now.plus(Duration.ofDays(4))))
    }

    @Test
    fun brokenRecordsAreSkippedButAnUnusableDownloadIsRejected() {
        val broken = """{"OBJECT_NAME":"BROKEN","EPOCH":"not a date"}"""
        val parsed = SatelliteGroup.parse(ElementSetGroup.VISUAL, ommArray(issOmmJson, broken), now)
        assertEquals(1, parsed.value.satellites.size)
        assertEquals(1, parsed.value.skippedCount)
        assertFailsWith<OnlineDataException> { SatelliteGroup.parse(ElementSetGroup.VISUAL, "[]".toByteArray(), now) }
        assertFailsWith<OnlineDataException> { SatelliteGroup.parse(ElementSetGroup.VISUAL, "<html>403</html>".toByteArray(), now) }
        assertFailsWith<OnlineDataException> { SatelliteGroup.parse(ElementSetGroup.VISUAL, ommArray(broken, broken), now) }
    }

    @Test
    fun elementSetsFromTheFutureAreDropped() {
        val future = issOmmJson.replace("2026-10-18T11:12:47.041632", "2026-12-18T11:12:47.041632")
        assertFailsWith<OnlineDataException> { SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(future), now) }
    }

    @Test
    fun theStationGroupKeepsOnlyTheIssAndTiangongAndTheIssIsFirst() {
        val library = SatelliteLibrary.EMPTY
            .with(group(ElementSetGroup.STATIONS, poiskOmmJson, tianheOmmJson, issOmmJson))
        val numbers = library.passSatellites.map { it.satellite.catalogNumber }
        assertEquals(listOf(25544, 48274), numbers)
        assertTrue(library.hasPassData)
    }

    @Test
    fun aSatelliteInBothGroupsAppearsOnceWithTheNewerElements() {
        val older = issOmmJson.replace("2026-10-18T11:12:47.041632", "2026-10-17T11:12:47.041632")
        val library = SatelliteLibrary.EMPTY
            .with(group(ElementSetGroup.STATIONS, older))
            .with(group(ElementSetGroup.VISUAL, issOmmJson))
        val iss = library.passSatellites.single()
        assertEquals(ElementSetGroup.VISUAL, iss.group)
        assertEquals(Instant.parse("2026-10-18T11:12:47.041Z").toEpochMilli(), iss.satellite.elements.epoch.toEpochMilli())
    }

    @Test
    fun theLibraryChangesWhenAGroupIsAddedOrRemoved() {
        val empty = SatelliteLibrary.EMPTY
        assertFalse(empty.hasPassData)
        val withStations = empty.with(group(ElementSetGroup.STATIONS, issOmmJson))
        val withStarlink = withStations.with(group(ElementSetGroup.STARLINK, issOmmJson.replace("25544", "44713")))
        assertTrue(withStations.generation > empty.generation)
        assertTrue(withStarlink.generation > withStations.generation)
        assertNotNull(withStarlink.starlink)
        val withoutPasses = withStarlink.without(OnlineFeed.SATELLITE_PASSES)
        assertFalse(withoutPasses.hasPassData)
        assertNotNull(withoutPasses.starlink)
        assertEquals(null, withoutPasses.without(OnlineFeed.STARLINK).starlink)
    }

    @Test
    fun staleDataIsFlagged() {
        val library = SatelliteLibrary.EMPTY.with(group(ElementSetGroup.STATIONS, issOmmJson))
        assertFalse(library.isPassDataStale(now))
        assertTrue(library.isPassDataStale(now.plus(Duration.ofDays(5))))
    }

    // Passes

    private fun item(
        satellite: Satellite,
        riseHour: Int,
        visibleMinutes: Int = 5,
        isVisible: Boolean = true,
        group: ElementSetGroup = ElementSetGroup.STATIONS,
    ): SatellitePassItem {
        val rise = Instant.parse("2026-10-18T00:00:00Z").plusSeconds(riseHour * 3600L)
        fun point(date: Instant) = SatellitePassPoint(date, 100.0, 40.0, 600.0, true, -3.0)
        val visible = if (isVisible) listOf(DateInterval(rise.plusSeconds(60), rise.plusSeconds(60L + visibleMinutes * 60))) else emptyList()
        val pass = SatellitePass(
            rise = point(rise), culmination = point(rise.plusSeconds(180)), set = point(rise.plusSeconds(600)),
            visibleIntervals = visible, brightest = point(rise.plusSeconds(180)), isRiseCut = false, isSetCut = false,
        )
        return SatellitePassItem(satellite, group, pass)
    }

    private val iss = Satellite(SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(issOmmJson), now).value.newest!!)
    private val tiangong = Satellite(SatelliteGroup.parse(ElementSetGroup.STATIONS, ommArray(tianheOmmJson), now).value.newest!!)

    @Test
    fun theIssIsNamedIssAndIdentified() {
        assertEquals("ISS", item(iss, 20).name)
        assertTrue(item(iss, 20).isIss)
        assertFalse(item(tiangong, 20).isIss)
        assertEquals("CSS (TIANHE)", item(tiangong, 20).name)
        assertEquals(Instant.parse("2026-10-18T20:01:00Z"), item(iss, 20).start)
    }

    @Test
    fun theFilterKeepsTheIssOrEverything() {
        val items = listOf(item(tiangong, 19), item(iss, 20), item(tiangong, 21))
        assertEquals(listOf(items[1]), SatellitePasses.filtered(items, SatellitePassFilter.ISS))
        assertEquals(items, SatellitePasses.filtered(items, SatellitePassFilter.BRIGHT))
    }

    @Test
    fun tonightListsTheIssFirstThenTheOthersInTimeOrder() {
        val items = listOf(item(tiangong, 19), item(iss, 22), item(tiangong, 20), item(iss, 21))
        val night = DateInterval(Instant.parse("2026-10-18T18:00:00Z"), Instant.parse("2026-10-19T06:00:00Z"))
        val tonight = SatellitePasses.tonight(items, night, now = Instant.parse("2026-10-18T18:30:00Z"), limit = 10)
        assertEquals(listOf(items[3], items[1], items[0], items[2]), tonight)
    }

    @Test
    fun tonightOmitsPassesOutsideTheNightOrAlreadyOverAndHonoursTheLimit() {
        val items = listOf(item(iss, 17), item(iss, 19), item(iss, 21), item(iss, 23), item(iss, 25), item(iss, 30))
        val night = DateInterval(Instant.parse("2026-10-18T18:00:00Z"), Instant.parse("2026-10-19T06:00:00Z"))
        val now = Instant.parse("2026-10-18T20:00:00Z")
        val tonight = SatellitePasses.tonight(items, night, now, limit = 2)
        // 17:00 is before the night, 19:00 is over, 30:00 (06:00 + 6 h) is after it; the limit keeps two of the three left.
        assertEquals(listOf(items[2], items[3]), tonight)
        assertEquals(3, SatellitePasses.tonight(items, night, now, limit = 10).size)
    }

    @Test
    fun upcomingDropsFinishedPasses() {
        val items = listOf(item(iss, 17), item(iss, 21))
        assertEquals(listOf(items[1]), SatellitePasses.upcoming(items, Instant.parse("2026-10-18T20:00:00Z")))
    }

    @Test
    fun aPassWithoutVisiblePartUsesTheRiseTimes() {
        val hidden = item(iss, 21, isVisible = false)
        assertEquals(Instant.parse("2026-10-18T21:00:00Z"), hidden.start)
        assertEquals(Instant.parse("2026-10-18T21:10:00Z"), hidden.end)
    }

    @Test
    fun theFoundPassesAreVisibleAndInTimeOrder() {
        val library = SatelliteLibrary.EMPTY.with(group(ElementSetGroup.STATIONS, issOmmJson, tianheOmmJson))
        // The ISS is visible from a site only on some evenings: look through a month of three-day windows.
        val found = (0 until 30 step 3).flatMap { day ->
            SatellitePasses.compute(library, dallas, Instant.parse("2026-10-18T12:00:00Z").plus(Duration.ofDays(day.toLong())))
        }
        assertTrue(found.isNotEmpty())
        for (pass in found) assertTrue(pass.pass.isVisible)
        for (window in found.groupBy { it.start.epochSecond / (3 * 86_400) }.values) {
            assertEquals(window.sortedBy { it.start }, window)
        }
        assertTrue(found.any { it.isIss })
    }
}

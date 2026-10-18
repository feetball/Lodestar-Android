package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonightSkylineTest {
    private val zone = ZoneId.of("America/Denver")
    private val observer = Observer(checkNotNull(GeographicLocation.validated(40.0, -105.3, 1650.0)))
    private val now = Instant.parse("2026-07-15T20:00:00Z")
    private val catalog: Catalog by lazy { Catalog.loadBundled() }

    private fun context(horizon: HorizonProfile?) =
        ObserverContext(observer, zone, "Ridge", LocationSource.Device, horizon = horizon)

    private fun report(horizon: HorizonProfile?) = TonightCalculator.report(
        TonightInput(TonightNightKey(context(horizon), TonightNightChoice.Tonight), now, catalog),
    )

    private fun hours(intervals: List<DateInterval>) = intervals.sumOf { it.duration } / 3600

    @Test
    fun anOpenOrFlatHorizonChangesNothing() {
        val open = report(null)
        val flat = report(HorizonProfile.flat)
        assertNull(open.skyline)
        assertNull(flat.skyline)
        assertEquals(open.summary, flat.summary)
        assertEquals(open.targets, flat.targets)
    }

    @Test
    fun aHighSkylineShortensEverythingThatNeedsToBeUp() {
        val open = report(null)
        val walled = report(HorizonProfile.constant(40.0))
        assertNotNull(walled.skyline)
        val openSummary = open.summary
        val summary = walled.summary
        assertTrue(hours(summary.milkyWayCore.visibleIntervals) < hours(openSummary.milkyWayCore.visibleIntervals))
        assertTrue(hours(summary.moon.upIntervals) <= hours(openSummary.moon.upIntervals))
        assertTrue(summary.planets.count { it.visibleDuringDarkness } <= openSummary.planets.count { it.visibleDuringDarkness })
        // The moon-free dark hours can only grow when the Moon is hidden.
        assertTrue(summary.moonFreeDarkHours >= openSummary.moonFreeDarkHours - 1e-9)
        // The Moon rises later and sets earlier behind a wall.
        val openMoon = openSummary.moon.riseTransitSet
        val moon = summary.moon.riseTransitSet
        if (openMoon.rise != null && moon.rise != null) assertTrue(moon.rise!! >= openMoon.rise!!)
        val targets = walled.targets as TonightTargetsOutcome.Ranked
        val openTargets = open.targets as TonightTargetsOutcome.Ranked
        // Everything listed clears the wall at its best time, and the hours above 20° shrink.
        for (target in targets.targets) {
            assertTrue(target.peakAltitude >= 40.0, "${target.obj.name} ${target.peakAltitude}")
            openTargets.targets.firstOrNull { it.id == target.id }?.let {
                assertTrue(target.hoursAboveMinimum <= it.hoursAboveMinimum + 1e-9)
            }
        }
    }

    @Test
    fun aWallAroundTheWholeHorizonHidesTheCore() {
        val walled = report(HorizonProfile.constant(60.0))
        val core = walled.summary.milkyWayCore
        assertTrue(core.visibleIntervals.isEmpty())
        assertNull(core.bestTime)
        assertNull(core.maxAltitude)
        assertTrue(walled.summary.planets.none { it.visibleDuringDarkness && (it.maxAltitudeDuringDarkness ?: 0.0) < 60.0 })
    }

    @Test
    fun theCoreWindowOfALowSkylineMatchesTheOpenHorizonWhereTheSkylineIsLow() {
        val open = report(null).summary.milkyWayCore
        val low = report(HorizonProfile.constant(2.0)).summary.milkyWayCore
        // The core is judged from 10° up, so a 2° skyline changes nothing.
        assertEquals(hours(open.visibleIntervals), hours(low.visibleIntervals), 0.01)
        assertEquals((open.bestTime?.epochSecond ?: 0L).toDouble(), (low.bestTime?.epochSecond ?: 0L).toDouble(), 120.0)
    }

    @Test
    fun subtractingIntervalsCutsTheirOverlap() {
        val start = Instant.parse("2026-07-16T00:00:00Z")
        fun span(from: Long, to: Long) = DateInterval(start.plusSeconds(from * 3600), start.plusSeconds(to * 3600))
        val result = TonightSkyline.subtract(listOf(span(0, 10)), listOf(span(2, 3), span(8, 12)))
        assertEquals(listOf(span(0, 2), span(3, 8)), result)
        assertEquals(listOf(span(0, 10)), TonightSkyline.subtract(listOf(span(0, 10)), listOf(span(11, 12))))
        assertTrue(TonightSkyline.subtract(listOf(span(0, 10)), listOf(span(-1, 11))).isEmpty())
    }
}

package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the core of the Milky Way can be photographed: June against December, a New Moon against a
 * Full Moon, and the planner's answers against the root finder and fresh sky frames.
 */
class PlanningMilkyWayCoreTests {
    private val boulder = PlanningTestSupport.boulder

    /** 33.9° S on the longitude of Sydney, a latitude where the core passes almost through the zenith. */
    private val southern = PlanningTestSupport.Site("33.9° S", -33.9, 151.2, timeZoneIdentifier = "Australia/Sydney")

    private fun coreAltitude(date: Instant, observer: Observer): Double =
        SkyFrame(date, observer).horizontal(MilkyWayCoreVisibility.galacticCentre, refraction = false).altitude

    // MARK: The target

    @Test
    fun testTheCoreIsSagittariusAStar() {
        val core = MilkyWayCoreVisibility.galacticCentre
        assertEquals(17 + 45.0 / 60 + 40.04 / 3_600, core.rightAscensionHours, 1e-9)
        assertEquals(-(29 + 28.1 / 3_600), core.declination, 1e-9)
        assertEquals(10.0, MilkyWayCoreVisibility.minimumAltitude)
    }

    // MARK: June against December

    @Test
    fun testJuneNightShowsTheCoreAllNightAtFortyNorth() {
        // New Moon at 20:54 MDT on 14 June 2026: no moonlight.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 14)
        val core = summary.milkyWayCore
        val night = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        assertEquals(1, summary.astronomicalDarknessIntervals.size)
        PlanningTestSupport.assertIntervals(core.visibleIntervals, listOf(night), 1.0, "the core stays above 10° all night")
        PlanningTestSupport.assertIntervals(core.moonFreeVisibleIntervals, listOf(night), 1.0)

        val transit = assertNotNull(core.riseTransitSet.transit)
        assertTrue(transit in night, "the core crosses the meridian in the dark")
        assertEquals(RiseTransitSet.Circumstance.NORMAL, core.riseTransitSet.circumstance)
        assertTrue(assertNotNull(core.riseTransitSet.rise) < night.start)
        assertTrue(assertNotNull(core.riseTransitSet.set) > night.end)
        assertEquals(0.0, assertNotNull(core.bestTime).timeIntervalSince(transit), 3.0)
        // Culmination altitude 90° − (40° − (−29.0°)) = 21.0° due south.
        assertEquals(21.0, assertNotNull(core.maxAltitude), 0.05)
        assertEquals(assertNotNull(core.riseTransitSet.transitAltitude), assertNotNull(core.maxAltitude), 1e-3)
        assertEquals(180.0, assertNotNull(core.bestAzimuth), 0.1)
    }

    @Test
    fun testDecemberNightHidesTheCoreAtFortyNorth() {
        // In December the Sun is in Sagittarius: the core is up only by day.
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        val core = summary.milkyWayCore
        assertTrue(summary.hasAstronomicalDarkness)
        assertTrue(core.visibleIntervals.isEmpty())
        assertTrue(core.moonFreeVisibleIntervals.isEmpty())
        assertNull(core.bestTime)
        assertNull(core.maxAltitude)
        assertNull(core.bestAzimuth)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, core.riseTransitSet.circumstance)
        assertTrue(assertNotNull(core.riseTransitSet.set) < assertNotNull(summary.twilight.astronomicalDusk))
        assertTrue(assertNotNull(core.riseTransitSet.rise) > assertNotNull(summary.twilight.astronomicalDawn))
    }

    @Test
    fun testJuneNightShowsTheCoreNearTheZenithAtThirtyFourSouth() {
        val summary = PlanningTestSupport.summary(southern, 2026, 6, 14) // New Moon on 15 June
        val core = summary.milkyWayCore
        val night = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        assertTrue(summary.astronomicalDarkHours > 11)
        PlanningTestSupport.assertIntervals(core.visibleIntervals, listOf(night), 1.0)
        PlanningTestSupport.assertIntervals(core.moonFreeVisibleIntervals, listOf(night), 1.0)

        val transit = assertNotNull(core.riseTransitSet.transit)
        assertTrue(transit in night)
        assertEquals(0.0, assertNotNull(core.bestTime).timeIntervalSince(transit), 3.0)
        // 90° − |−33.9° − (−29.0°)| = 85.1°, so far north of the zenith that the azimuth is almost exact.
        assertEquals(85.1, assertNotNull(core.maxAltitude), 0.05)
        assertEquals(assertNotNull(core.riseTransitSet.transitAltitude), assertNotNull(core.maxAltitude), 1e-3)
        val azimuth = assertNotNull(core.bestAzimuth)
        assertTrue(min(azimuth, 360 - azimuth) < 0.2, "due north of the zenith")
    }

    @Test
    fun testDecemberNightHidesTheCoreAtThirtyFourSouth() {
        val summary = PlanningTestSupport.summary(southern, 2026, 12, 14)
        val core = summary.milkyWayCore
        assertTrue(summary.hasAstronomicalDarkness)
        assertTrue(core.visibleIntervals.isEmpty())
        assertTrue(core.moonFreeVisibleIntervals.isEmpty())
        assertNull(core.bestTime)
        assertNull(core.maxAltitude)
        assertTrue(assertNotNull(core.riseTransitSet.set) < assertNotNull(summary.twilight.astronomicalDusk))
        assertTrue(assertNotNull(core.riseTransitSet.rise) > assertNotNull(summary.twilight.astronomicalDawn))
    }

    @Test
    fun testTheCoreNeverReachesTenDegreesFarNorth() {
        // Culminates at 90° − (60° + 29°) = 1° at 60° N.
        val helsinki = PlanningTestSupport.Site("60° N", 60.0, 25.0, timeZoneIdentifier = "Europe/Helsinki")
        val summary = PlanningTestSupport.summary(helsinki, 2026, 10, 1)
        assertTrue(summary.hasAstronomicalDarkness)
        assertTrue(summary.milkyWayCore.visibleIntervals.isEmpty())
        assertNull(summary.milkyWayCore.bestTime)
    }

    @Test
    fun testWhiteNightsLeaveNoDarknessForTheCore() {
        // London on the solstice: the Sun stays above −15.1°, so astronomical darkness never comes.
        val summary = PlanningTestSupport.summary(PlanningTestSupport.london, 2026, 6, 21)
        val core = summary.milkyWayCore
        assertFalse(summary.hasAstronomicalDarkness)
        assertTrue(core.visibleIntervals.isEmpty())
        assertTrue(core.moonFreeVisibleIntervals.isEmpty())
        assertNull(core.bestTime)
        assertNull(core.maxAltitude)
        assertNull(core.bestAzimuth)
        assertNotNull(core.riseTransitSet.transit)
    }

    // MARK: Moonlight

    @Test
    fun testBestTimeWaitsForMoonsetWhenTheMoonLightsTheMeridianPassage() {
        // 21 June 2026, a day after first quarter: the Moon sets at 00:58 MDT, after the core's passage
        // at 00:47 and 2.5 hours before dawn. The moon-free hours begin with the core already descending.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        val core = summary.milkyWayCore
        val night = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        val moonSet = assertNotNull(summary.moon.riseTransitSet.set)
        val transit = assertNotNull(core.riseTransitSet.transit)
        assertTrue(transit < moonSet)
        PlanningTestSupport.assertIntervals(core.visibleIntervals, listOf(night), 1.0)
        PlanningTestSupport.assertIntervals(core.moonFreeVisibleIntervals, listOf(DateInterval(moonSet, night.end)), 1.0)
        assertEquals(
            0.0, assertNotNull(core.bestTime).timeIntervalSince(moonSet), 3.0,
            "the highest the core gets once the sky is moon-free",
        )
        assertTrue(assertNotNull(core.maxAltitude) < assertNotNull(core.riseTransitSet.transitAltitude))
        assertEquals(20.93, assertNotNull(core.maxAltitude), 0.05)
    }

    @Test
    fun testFullMoonNightHasNoMoonFreeCoreButStillReportsTheBestTime() {
        // Full Moon at 17:57 MDT on 29 June 2026.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 29)
        val core = summary.milkyWayCore
        val night = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        assertEquals(MoonPhaseName.FULL_MOON, summary.moon.phase.name)
        assertTrue(summary.darknessIntervals.isEmpty())
        assertTrue(core.moonFreeVisibleIntervals.isEmpty())
        // The core is above 10° from dusk until a little before dawn.
        val window = assertNotNull(core.visibleIntervals.firstOrNull())
        assertEquals(1, core.visibleIntervals.size)
        assertEquals(0.0, window.start.timeIntervalSince(night.start), 1.0)
        assertTrue(window.end < night.end)
        assertEquals(10.0, coreAltitude(window.end, summary.observer), 0.01)
        val transit = assertNotNull(core.riseTransitSet.transit)
        assertEquals(
            0.0, assertNotNull(core.bestTime).timeIntervalSince(transit), 3.0,
            "without a moon-free window the best time comes from the whole dark time",
        )
        assertEquals(21.0, assertNotNull(core.maxAltitude), 0.05)
    }

    // MARK: Partial windows

    @Test
    fun testRisingCoreIsVisibleOnlyFromTenDegreesUntilDawn() {
        // 16 April 2026 (New Moon): the core rises in the small hours and clears 10° shortly before dawn.
        val summary = PlanningTestSupport.summary(boulder, 2026, 4, 16)
        val core = summary.milkyWayCore
        val dawn = assertNotNull(summary.twilight.astronomicalDawn)
        val window = assertNotNull(core.visibleIntervals.firstOrNull())
        assertEquals(1, core.visibleIntervals.size)
        assertEquals(0.0, window.end.timeIntervalSince(dawn), 1.0)
        assertTrue(window.start > assertNotNull(summary.twilight.astronomicalDusk))
        assertTrue(window.duration < 3 * 3_600)
        assertEquals(10.0, coreAltitude(window.start, summary.observer), 0.01)
        assertTrue(coreAltitude(window.start.addingTimeInterval(-60.0), summary.observer) < 10)
        assertTrue(coreAltitude(window.start.addingTimeInterval(60.0), summary.observer) > 10)
        val best = assertNotNull(core.bestTime)
        assertEquals(0.0, best.timeIntervalSince(dawn), 3.0, "still climbing when the sky brightens")
        assertEquals(assertNotNull(core.maxAltitude), coreAltitude(best, summary.observer), 1e-4)
    }

    @Test
    fun testSettingCoreIsVisibleFromDuskUntilItSinksBelowTenDegrees() {
        // 10 September 2026 (New Moon): the core is high in the south-west after dusk and sets before midnight.
        val summary = PlanningTestSupport.summary(boulder, 2026, 9, 10)
        val core = summary.milkyWayCore
        val dusk = assertNotNull(summary.twilight.astronomicalDusk)
        val window = assertNotNull(core.visibleIntervals.firstOrNull())
        assertEquals(1, core.visibleIntervals.size)
        assertEquals(0.0, window.start.timeIntervalSince(dusk), 1.0)
        assertTrue(window.end < assertNotNull(summary.twilight.astronomicalDawn))
        assertEquals(10.0, coreAltitude(window.end, summary.observer), 0.01)
        assertTrue(coreAltitude(window.end.addingTimeInterval(-60.0), summary.observer) > 10)
        assertTrue(coreAltitude(window.end.addingTimeInterval(60.0), summary.observer) < 10)
        assertEquals(
            0.0, assertNotNull(core.bestTime).timeIntervalSince(dusk), 3.0,
            "already past the meridian at dusk, so the best moment is the first",
        )
    }

    // MARK: Agreement with independent calculations

    /** The visible stretches equal the root finder's stretches above 10°, cut to the dark time. */
    @Test
    fun testVisibleIntervalsMatchTheRootFinderEverywhere() {
        val sites = listOf(
            boulder, southern, PlanningTestSupport.quito, PlanningTestSupport.paranal, PlanningTestSupport.london,
        )
        for (site in sites) {
            for ((month, day) in listOf(1 to 15, 3 to 20, 5 to 10, 7 to 25, 9 to 10, 11 to 5)) {
                val summary = PlanningTestSupport.summary(site, 2026, month, day)
                val label = "${site.name} 2026-$month-$day"
                val observer = site.observer
                val expected = IntervalSet.intersection(
                    PlanningTestSupport.intervals(MilkyWayCoreVisibility.minimumAltitude, summary.night) {
                        coreAltitude(it, observer)
                    },
                    summary.astronomicalDarknessIntervals,
                )
                PlanningTestSupport.assertIntervals(summary.milkyWayCore.visibleIntervals, expected, 1.0, label)
            }
        }
    }

    /**
     * Structural properties of the core's answer, checked at a grid of places and dates against fresh
     * frames: the dark time is split exactly into "core high enough" and "core too low", the moon-free
     * stretches are the part without moonlight, and the best moment is the highest one in the preferred
     * stretches.
     */
    @Test
    fun testAnswersAreConsistentWithDirectAltitudes() {
        val sites = listOf(
            boulder, southern, PlanningTestSupport.quito, PlanningTestSupport.paranal, PlanningTestSupport.london,
            PlanningTestSupport.tromso,
        )
        var nightsWithTheCore = 0
        var nightsWithoutIt = 0
        for (site in sites) {
            for ((month, day) in listOf(2 to 10, 4 to 16, 6 to 14, 8 to 20, 10 to 25, 12 to 14)) {
                val summary = PlanningTestSupport.summary(site, 2026, month, day)
                val label = "${site.name} 2026-$month-$day"
                val observer = site.observer
                val core = summary.milkyWayCore
                if (core.visibleIntervals.isEmpty()) nightsWithoutIt += 1 else nightsWithTheCore += 1

                assertEquals(
                    core.visibleIntervals,
                    IntervalSet.intersection(core.visibleIntervals, summary.astronomicalDarknessIntervals), label,
                )
                assertEquals(
                    IntervalSet.intersection(core.visibleIntervals, summary.darknessIntervals),
                    core.moonFreeVisibleIntervals, label,
                )
                val darkEdges = summary.astronomicalDarknessIntervals.flatMap { listOf(it.start, it.end) }
                for (interval in core.visibleIntervals) {
                    assertTrue(
                        coreAltitude(interval.start.addingTimeInterval(interval.duration / 2), observer) >= 10, label,
                    )
                    // Each end is where the dark time begins or ends, or else where the core crosses 10°.
                    for (edge in listOf(interval.start, interval.end)) {
                        if (!darkEdges.all { abs(it.timeIntervalSince(edge)) > 1 }) continue
                        assertEquals(10.0, coreAltitude(edge, observer), 0.01, "$label: an edge at 10°")
                    }
                }
                for (gap in IntervalSet.subtracting(core.visibleIntervals, summary.astronomicalDarknessIntervals)) {
                    assertTrue(coreAltitude(gap.start.addingTimeInterval(gap.duration / 2), observer) < 10, label)
                }

                val preferred = if (core.moonFreeVisibleIntervals.isEmpty()) core.visibleIntervals else core.moonFreeVisibleIntervals
                val best = core.bestTime
                val maxAltitude = core.maxAltitude
                if (best == null || maxAltitude == null) {
                    assertTrue(core.visibleIntervals.isEmpty(), label)
                    assertNull(core.bestTime, label)
                    assertNull(core.maxAltitude, label)
                    assertNull(core.bestAzimuth, label)
                    continue
                }
                assertTrue(preferred.any { it.start <= best && best <= it.end }, "$label: best time in the window")
                assertEquals(maxAltitude, coreAltitude(best, observer), 1e-4, label)
                val frame = SkyFrame(best, observer)
                assertEquals(
                    assertNotNull(core.bestAzimuth),
                    frame.horizontal(MilkyWayCoreVisibility.galacticCentre, refraction = false).azimuth, 1e-6, label,
                )
                for (interval in preferred) {
                    var date = interval.start
                    while (date <= interval.end) {
                        assertTrue(coreAltitude(date, observer) <= maxAltitude + 1e-3, label)
                        date = date.addingTimeInterval(300.0)
                    }
                }
            }
        }
        assertTrue(nightsWithTheCore > 12, "the grid must include many nights with a visible core")
        assertTrue(nightsWithoutIt > 8, "and many without")
    }

    @Test
    fun testRiseTransitSetIsTheRootFindersForTheNight() {
        for (site in listOf(boulder, southern, PlanningTestSupport.london)) {
            val summary = PlanningTestSupport.summary(site, 2026, 8, 12)
            assertEquals(
                RiseSetCalculator.riseTransitSet(MilkyWayCoreVisibility.galacticCentre, null, site.observer, summary.night),
                summary.milkyWayCore.riseTransitSet,
                site.name,
            )
        }
    }
}

package net.kuecker.lodestar.features.alignmentfinder

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.SkyWindow
import net.kuecker.lodestar.kit.planning.SkyWindowDarkness
import net.kuecker.lodestar.kit.planning.SkyWindowMoonRule
import net.kuecker.lodestar.kit.planning.SkyWindowTarget
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The inputs of the Alignment Finder, the searches they make and the words of the results. */
class AlignmentFinderModelTest {
    private val denver = ZoneId.of("America/Denver")
    private val boulder = Observer(GeographicLocation(40.0, -105.27, 1650.0))
    private val tromso = Observer(GeographicLocation(69.65, 18.96, 0.0))

    /** A ridge of 30° in the east on a 2° line of trees. */
    private val skyline = assertNotNull(
        HorizonProfile.create(List(360) { degree -> if (degree in 60..120) 30.0 - kotlin.math.abs(degree - 90) / 2.0 else 2.0 }),
    )

    private fun context(horizon: HorizonProfile? = null, observer: Observer = boulder, zone: ZoneId = denver) =
        ObserverContext(observer, zone, "Test site", LocationSource.Device, horizon = horizon)

    private val summerEvening = Instant.parse("2026-07-11T04:00:00Z") // 22:00 MDT on 10 July

    // MARK: Nights

    @Test
    fun testTheSearchStartsAtTheNoonThatOpenedTheCurrentNight() {
        val afternoon = Instant.parse("2026-11-19T20:00:00Z") // 13:00 MST
        val earlyMorning = Instant.parse("2026-11-19T10:00:00Z") // 03:00 MST
        assertEquals(Instant.parse("2026-11-19T19:00:00Z"), AlignmentFinderRequest.nightStart(afternoon, denver))
        assertEquals(Instant.parse("2026-11-18T19:00:00Z"), AlignmentFinderRequest.nightStart(earlyMorning, denver))
        // Across the change to daylight time the noon moves by an hour.
        assertEquals(Instant.parse("2026-03-07T19:00:00Z"), AlignmentFinderRequest.nightStart(Instant.parse("2026-03-08T05:00:00Z"), denver))
        assertEquals(Instant.parse("2026-03-08T18:00:00Z"), AlignmentFinderRequest.nightStart(Instant.parse("2026-03-09T03:00:00Z"), denver))
    }

    // MARK: Inputs

    @Test
    fun testTheSkylineNeedsARecordedSkyline() {
        val inputs = AlignmentFinderInputs()
        assertEquals(AlignmentFinderPlace.SKYLINE, inputs.place)
        assertNull(inputs.request(context(), summerEvening))
        assertNull(inputs.request(context(HorizonProfile.flat), summerEvening), "a flat skyline counts as none")
        val request = assertNotNull(inputs.request(context(skyline), summerEvening))
        assertTrue(request.isAtSkyline)
        assertEquals(skyline, request.horizon)
        assertEquals(SkyWindowTarget.MilkyWayCore, request.target)
        assertEquals(SkyWindowDarkness.ASTRONOMICAL, request.darkness)
        assertEquals(SkyWindowMoonRule.Unrestricted, request.moon)
        assertEquals(SkyWindow.skylinePoint(180.0, skyline, 3.0), request.window)
    }

    @Test
    fun testAWindowOfNumbersNeedsNoSkyline() {
        val inputs = AlignmentFinderInputs()
        inputs.place = AlignmentFinderPlace.WINDOW
        inputs.windowAzimuth = 225
        inputs.windowTolerance = 15
        inputs.minimumAltitude = 3
        inputs.maximumAltitude = 12
        val request = assertNotNull(inputs.request(context(), summerEvening))
        assertFalse(request.isAtSkyline)
        assertNull(request.horizon)
        assertEquals(SkyWindow.around(225.0, 15.0, 3.0, 12.0), request.window)
        inputs.skylineAzimuth = 90
        assertEquals(request.window, assertNotNull(inputs.request(context(), summerEvening)).window, "the skyline does not matter")
        // A window with the lowest altitude above the highest cannot be searched.
        inputs.minimumAltitude = 40
        assertNull(inputs.request(context(), summerEvening))
    }

    @Test
    fun testThePeriodRunsFromTheStartOfTheNight() {
        val inputs = AlignmentFinderInputs()
        inputs.place = AlignmentFinderPlace.WINDOW
        for (range in AlignmentFinderRange.entries) {
            inputs.range = range
            val request = assertNotNull(inputs.request(context(), summerEvening))
            assertEquals(Instant.parse("2026-07-10T18:00:00Z"), request.from)
            assertEquals(range.days * 86_400L, request.to.epochSecond - request.from.epochSecond)
        }
    }

    @Test
    fun testTheMoonSettingsBecomeRules() {
        val inputs = AlignmentFinderInputs()
        inputs.place = AlignmentFinderPlace.WINDOW
        fun rule(): SkyWindowMoonRule = assertNotNull(inputs.request(context(), summerEvening)).moon
        assertEquals(SkyWindowMoonRule.Unrestricted, rule())
        inputs.moon = AlignmentFinderMoon.BELOW_HORIZON
        assertEquals(SkyWindowMoonRule.BelowHorizon, rule())
        inputs.moon = AlignmentFinderMoon.DIM
        assertEquals(SkyWindowMoonRule.IlluminatedAtMost(0.25), rule())
        inputs.dimPercent = 40
        assertEquals(SkyWindowMoonRule.IlluminatedAtMost(0.4), rule())
    }

    @Test
    fun testTheSunAndTheMoonAreSearchedAtAnyTime() {
        val inputs = AlignmentFinderInputs()
        assertEquals(AlignmentFinderDarkness.ASTRONOMICAL, inputs.darkness)
        inputs.choose(AlignmentFinderWhat.SUN)
        assertEquals(AlignmentFinderDarkness.ANY_TIME, inputs.darkness)
        assertTrue(inputs.followsSun)
        inputs.choose(AlignmentFinderWhat.MILKY_WAY_CORE)
        assertEquals(AlignmentFinderDarkness.ASTRONOMICAL, inputs.darkness)
        inputs.choose(AlignmentFinderWhat.MOON)
        assertEquals(AlignmentFinderDarkness.ANY_TIME, inputs.darkness)
        assertFalse(inputs.followsSun)
    }

    @Test
    fun testChoosingAnObjectNeedsTheObject() {
        val inputs = AlignmentFinderInputs()
        inputs.choose(AlignmentFinderWhat.OBJECT)
        assertEquals(AlignmentFinderWhat.MILKY_WAY_CORE, inputs.what, "there is no object to choose yet")
        val custom = CelestialObject.custom("Ridge star", EquatorialCoordinate(120.0, 10.0))
        inputs.choose(custom)
        assertEquals(AlignmentFinderWhat.OBJECT, inputs.what)
        assertEquals(SkyWindowTarget.CatalogObject(custom), inputs.target())
        assertEquals(custom, inputs.aimObject { null })
        assertFalse(inputs.followsSun)
        inputs.choose(AlignmentFinderWhat.MOON)
        inputs.choose(AlignmentFinderWhat.OBJECT)
        assertEquals(AlignmentFinderWhat.OBJECT, inputs.what, "the object is kept for the way back")
        inputs.choose(AlignmentFinderWhat.MILKY_WAY_CORE)
        assertEquals("special.galacticCenter", assertNotNull(inputs.aimObject { id -> CelestialObject.custom(id, EquatorialCoordinate(0.0, 0.0)) }).name)
    }

    // MARK: Searching

    private fun windowRequest(
        zone: ZoneId = denver,
        observer: Observer = boulder,
        configure: AlignmentFinderInputs.() -> Unit = {},
    ): AlignmentFinderRequest {
        val inputs = AlignmentFinderInputs()
        inputs.place = AlignmentFinderPlace.WINDOW
        inputs.windowAzimuth = 180
        inputs.windowTolerance = 90
        inputs.minimumAltitude = 5
        inputs.maximumAltitude = 90
        inputs.configure()
        return assertNotNull(inputs.request(context(null, observer, zone), summerEvening))
    }

    @Test
    fun testASearchReturnsTheNightsInOrder() {
        val outcome = assertIs<AlignmentFinderOutcome.Found>(AlignmentFinderSearch.run(windowRequest()))
        assertFalse(outcome.isCutShort)
        assertTrue(outcome.matches.size >= 25)
        assertTrue(outcome.matches.zipWithNext().all { (a, b) -> a.interval.end <= b.interval.start })
        assertTrue(outcome.matches.all { it.altitude in 5.0..90.0 && it.azimuth in 90.0..270.0 })
    }

    @Test
    fun testAYearOfSunAtAnyTimeIsCutShort() {
        val request = windowRequest {
            choose(AlignmentFinderWhat.SUN)
            minimumAltitude = -10
            windowTolerance = 90
            windowAzimuth = 180
            range = AlignmentFinderRange.YEAR
        }
        val outcome = assertIs<AlignmentFinderOutcome.Found>(AlignmentFinderSearch.run(request))
        assertEquals(200, outcome.matches.size)
        assertTrue(outcome.isCutShort)
    }

    @Test
    fun testNoDarknessInAPolarSummerIsSaid() {
        val request = windowRequest(ZoneId.of("Europe/Oslo"), tromso)
        val outcome = assertIs<AlignmentFinderOutcome.Empty>(AlignmentFinderSearch.run(request.copy(
            from = Instant.parse("2026-06-10T10:00:00Z"), to = Instant.parse("2026-07-10T10:00:00Z"),
        )))
        assertEquals(AlignmentFinderEmptyReason.NEVER_DARK, outcome.reason)
    }

    @Test
    fun testTheMoonInTheWayIsSaid() {
        // Full Moon on 29 July 2026: it is up all through the dark hours for three nights.
        val base = windowRequest { moon = AlignmentFinderMoon.BELOW_HORIZON }
        val request = base.copy(from = Instant.parse("2026-07-29T18:00:00Z"), to = Instant.parse("2026-07-31T18:00:00Z"))
        val outcome = assertIs<AlignmentFinderOutcome.Empty>(AlignmentFinderSearch.run(request))
        assertEquals(AlignmentFinderEmptyReason.ONLY_WITH_MOON, outcome.reason)
        val anyMoon = assertIs<AlignmentFinderOutcome.Found>(AlignmentFinderSearch.run(request.copy(moon = SkyWindowMoonRule.Unrestricted)))
        assertTrue(anyMoon.matches.isNotEmpty())
    }

    @Test
    fun testDaylightOnlyIsSaid() {
        val request = windowRequest {
            choose(AlignmentFinderWhat.SUN)
            darkness = AlignmentFinderDarkness.ASTRONOMICAL
            minimumAltitude = 0
        }
        assertEquals(SkyWindowDarkness.ASTRONOMICAL, request.darkness)
        val outcome = assertIs<AlignmentFinderOutcome.Empty>(AlignmentFinderSearch.run(request))
        assertEquals(AlignmentFinderEmptyReason.ONLY_IN_LIGHT, outcome.reason)
    }

    @Test
    fun testAWindowTheTargetNeverReachesIsSaid() {
        // The core never gets above 21° at 40° N.
        val request = windowRequest { minimumAltitude = 80 }
        val outcome = assertIs<AlignmentFinderOutcome.Empty>(AlignmentFinderSearch.run(request))
        assertEquals(AlignmentFinderEmptyReason.NEVER_IN_WINDOW, outcome.reason)
        assertEquals("Never in this window", AlignmentFinderText.emptyTitle(outcome.reason, request))
        val onSkyline = request.copy(isAtSkyline = true)
        assertEquals("Never over this part of the skyline", AlignmentFinderText.emptyTitle(outcome.reason, onSkyline))
        assertTrue(AlignmentFinderText.emptyMessage(outcome.reason, onSkyline, 30).contains("30 days"))
    }

    @Test
    fun testARangeBeyondTheLimitFailsWithAMessage() {
        val request = windowRequest().let { it.copy(to = it.from.plusSeconds(401 * 86_400L)) }
        val outcome = assertIs<AlignmentFinderOutcome.Failed>(AlignmentFinderSearch.run(request))
        assertTrue(outcome.message.contains("too many nights"))
    }

    // MARK: Words

    @Test
    fun testTheWordsOfAResult() {
        val request = windowRequest { windowAzimuth = 225; windowTolerance = 15; minimumAltitude = 3; maximumAltitude = 10 }
        val match = assertIs<AlignmentFinderOutcome.Found>(AlignmentFinderSearch.run(request)).matches.first()
        val detail = AlignmentFinderText.rowDetail(match)
        assertTrue(detail.startsWith("Alt "), detail)
        assertTrue(detail.contains("Az ") && detail.contains("°"), detail)
        assertTrue(detail.endsWith("Moon down") || detail.contains("Moon up, "), detail)
        assertTrue(AlignmentFinderText.rowTitle(match, denver).contains("–"))
        assertTrue(AlignmentFinderText.rowDescription(match, denver).endsWith("."))
        assertEquals("225° SW", AlignmentFinderText.direction(225))
        assertEquals("0° N", AlignmentFinderText.direction(0))
        assertEquals("18°", AlignmentFinderText.altitude(17.6))
    }
}

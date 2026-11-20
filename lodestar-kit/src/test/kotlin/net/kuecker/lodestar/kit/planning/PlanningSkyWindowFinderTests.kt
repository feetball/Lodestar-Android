package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.catalog.*
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "When does the Milky Way core, the Moon or a star stand over that ridge?": the sky-window search against fresh
 * sky frames, a brute-force scan, the root finder and published geometry.
 */
class PlanningSkyWindowFinderTests {
    private val boulder = PlanningTestSupport.boulder
    private val tromso = PlanningTestSupport.tromso

    /** 47° N in the Alps, where the Milky Way core never gets above 14°. */
    private val innsbruck = PlanningTestSupport.Site("Innsbruck", 47.0, 11.4, 600.0, "Europe/Vienna")

    private val coreTarget = SkyWindowTarget.MilkyWayCore

    private fun window(azimuth: Double, tolerance: Double, minimum: Double, maximum: Double): SkyWindow =
        assertNotNull(SkyWindow.around(azimuth, tolerance, minimum, maximum))

    private fun noon(site: PlanningTestSupport.Site, year: Int, month: Int, day: Int): Instant =
        PlanningTestSupport.localNoon(year, month, day, site.timeZone())

    private fun matches(
        site: PlanningTestSupport.Site,
        target: SkyWindowTarget,
        window: SkyWindow,
        from: Instant,
        to: Instant,
        horizon: HorizonProfile? = null,
        darkness: SkyWindowDarkness = SkyWindowDarkness.ASTRONOMICAL,
        moon: SkyWindowMoonRule = SkyWindowMoonRule.Unrestricted,
    ): List<SkyWindowMatch> =
        SkyWindowFinder.matches(
            target, window, site.observer, horizon, from, to, darkness, moon, site.timeZone(),
        )

    private fun apparent(frame: SkyFrame, place: EquatorialCoordinate): HorizontalCoordinate =
        frame.horizontal(place, null, true)

    private fun sunAltitude(date: Instant, observer: Observer): Double = PlanningTestSupport.sunAltitude(date, observer)

    // MARK: Windows

    @Test
    fun testAWindowNeedsFiniteOrderedAltitudes() {
        val range = AzimuthRange(170.0, 20.0)
        val window = assertNotNull(SkyWindow.create(range, 5.0, 25.0))
        assertEquals(range, window.azimuth)
        assertEquals(5.0, window.minimumAltitude)
        assertEquals(25.0, window.maximumAltitude)
        assertNotNull(SkyWindow.create(range, -90.0, 90.0))
        assertNotNull(SkyWindow.create(range, 12.0, 12.0))
        assertNull(SkyWindow.create(range, 25.0, 5.0))
        assertNull(SkyWindow.create(range, -90.5, 5.0))
        assertNull(SkyWindow.create(range, 5.0, 90.5))
        assertNull(SkyWindow.create(range, Double.NaN, 5.0))
        assertNull(SkyWindow.create(range, 5.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testAWindowAroundAnAzimuthRunsThroughNorth() {
        val window = window(359.0, 3.0, 0.0, 10.0)
        assertEquals(356.0, window.azimuth.start, 1e-9)
        assertEquals(6.0, window.azimuth.length, 1e-9)
        assertTrue(window.azimuth.contains(0.0))
        assertTrue(window.azimuth.contains(2.0))
        assertFalse(window.azimuth.contains(3.5))
        assertEquals(360.0, window(10.0, 180.0, 0.0, 10.0).azimuth.length)
        assertNull(SkyWindow.around(10.0, -1.0, 0.0, 10.0))
        assertNull(SkyWindow.around(10.0, 181.0, 0.0, 10.0))
        assertNull(SkyWindow.around(Double.NaN, 3.0, 0.0, 10.0))
        assertNull(SkyWindow.around(10.0, Double.NaN, 0.0, 10.0))
    }

    @Test
    fun testASkylinePointWindowSitsJustAboveTheSkyline() {
        val skyline = HorizonTestSupport.truthProfile
        val ridge = assertNotNull(SkyWindow.skylinePoint(90.0, skyline))
        assertEquals(87.0, ridge.azimuth.start, 1e-9)
        assertEquals(6.0, ridge.azimuth.length, 1e-9)
        assertEquals(30.0, ridge.minimumAltitude, 1e-9)
        assertEquals(40.0, ridge.maximumAltitude, 1e-9)

        val building = assertNotNull(SkyWindow.skylinePoint(190.0, skyline, 5.0, 2.0..6.0))
        assertEquals(185.0, building.azimuth.start, 1e-9)
        assertEquals(10.0, building.azimuth.length, 1e-9)
        assertEquals(skyline.altitude(190.0) + 2.0, building.minimumAltitude, 1e-9)
        assertEquals(skyline.altitude(190.0) + 6.0, building.maximumAltitude, 1e-9)

        val flat = assertNotNull(SkyWindow.skylinePoint(45.0, HorizonProfile.flat))
        assertEquals(0.0, flat.minimumAltitude)
        assertEquals(10.0, flat.maximumAltitude)
        val high = assertNotNull(SkyWindow.skylinePoint(45.0, HorizonProfile.constant(60.0), 3.0, 0.0..40.0))
        assertEquals(60.0, high.minimumAltitude, 1e-9)
        assertEquals(90.0, high.maximumAltitude, 1e-9)

        assertNull(SkyWindow.skylinePoint(Double.NaN, skyline))
        assertNull(SkyWindow.skylinePoint(90.0, skyline, Double.NaN))
        assertNull(SkyWindow.skylinePoint(90.0, skyline, 181.0))
        assertNull(SkyWindow.skylinePoint(90.0, skyline, 3.0, 0.0..100.0))
        assertNull(SkyWindow.skylinePoint(90.0, skyline, 3.0, -100.0..10.0))
    }

    @Test
    fun testDarknessKindsNameTheSunAltitudeTheyNeed() {
        assertEquals(-18.0, SkyWindowDarkness.ASTRONOMICAL.sunAltitudeLimit)
        assertEquals(-12.0, SkyWindowDarkness.NAUTICAL.sunAltitudeLimit)
        assertEquals(-6.0, SkyWindowDarkness.CIVIL.sunAltitudeLimit)
        assertNull(SkyWindowDarkness.ANY_TIME.sunAltitudeLimit)
        for (kind in SkyWindowDarkness.entries) assertEquals(kind, SkyWindowDarkness.fromRawValue(kind.rawValue))
        assertNull(SkyWindowDarkness.fromRawValue("twilight"))
    }

    // MARK: A fixed star

    @Test
    fun testAFixedStarCrossesTheSouthWindowOnlyAtTransit() {
        // A star whose right ascension is the local sidereal time at midnight transits at midnight, in the middle of
        // each night. Declination +10° at 40° N: transit altitude 60°, azimuth rate there 29.6°/h, so a window of
        // 180° ± 2° is crossed in 4° / 29.6°/h = 8.1 minutes.
        val first = noon(boulder, 2026, 1, 10)
        val midnight = first.addingTimeInterval(12 * 3600.0)
        val star = EquatorialCoordinate(SkyFrame(midnight, boulder.observer).localApparentSiderealTime, 10.0)
        val found = matches(
            boulder, SkyWindowTarget.Fixed(star), window(180.0, 2.0, 5.0, 90.0), first, noon(boulder, 2026, 1, 20),
            darkness = SkyWindowDarkness.ANY_TIME,
        )
        assertEquals(10, found.size)
        for ((index, match) in found.withIndex()) {
            val night = RiseSetCalculator.riseTransitSet(
                star, null, boulder.observer, DateInterval(noon(boulder, 2026, 1, 10 + index), 86_400.0),
            )
            val transit = assertNotNull(night.transit)
            assertEquals(noon(boulder, 2026, 1, 10 + index), match.night.start)
            assertTrue(match.interval.start <= transit && transit <= match.interval.end)
            assertEquals(0.0, match.midpoint.timeIntervalSince(transit), 15.0, "night $index")
            assertEquals(180.0, match.azimuth, 0.05)
            assertEquals(60.0, match.altitude, 0.3)
            assertTrue(match.isAboveSkyline)

            // The root finder gives the moments the star is at 178° and 182°; the match is inside them, within the
            // 10 s of the bisection.
            val around = DateInterval(transit.addingTimeInterval(-1800.0), transit.addingTimeInterval(1800.0))
            val azimuth = { date: Instant -> SkyFrame(date, boulder.observer).horizontal(star, null, true).azimuth }
            val entry = assertNotNull(RiseSetCalculator.crossings(azimuth, 178.0, around).firstOrNull()).time
            val exit = assertNotNull(RiseSetCalculator.crossings(azimuth, 182.0, around).firstOrNull()).time
            assertEquals(486.0, exit.timeIntervalSince(entry), 10.0, "the window takes 8.1 minutes to cross")
            assertEquals(5.0, match.interval.start.timeIntervalSince(entry), 5.0, "night $index")
            assertEquals(5.0, exit.timeIntervalSince(match.interval.end), 5.0, "night $index")
        }
    }

    @Test
    fun testTheNightsOfASearchFollowTheTimeZone() {
        // Spring forward in Denver on 8 March 2026 makes the night that starts on the 7th 23 hours long.
        val first = noon(boulder, 2026, 3, 7)
        val star = EquatorialCoordinate(
            SkyFrame(first.addingTimeInterval(11.5 * 3600), boulder.observer).localApparentSiderealTime, 10.0,
        )
        val found = matches(
            boulder, SkyWindowTarget.Fixed(star), window(180.0, 2.0, 5.0, 90.0), first, noon(boulder, 2026, 3, 9),
            darkness = SkyWindowDarkness.ANY_TIME,
        )
        assertEquals(2, found.size)
        assertEquals(23 * 3600.0, found[0].night.duration, 1.0)
        assertEquals(24 * 3600.0, found[1].night.duration, 1.0)
        assertEquals(noon(boulder, 2026, 3, 8), found[1].night.start)
    }

    @Test
    fun testAMatchAgreesWithABruteForceScan() {
        val first = noon(boulder, 2026, 1, 10)
        val star = EquatorialCoordinate(
            SkyFrame(first.addingTimeInterval(12 * 3600.0), boulder.observer).localApparentSiderealTime + 40, -10.0,
        )
        val box = window(150.0, 30.0, 10.0, 40.0)
        val found = matches(
            boulder, SkyWindowTarget.Fixed(star), box, first, noon(boulder, 2026, 1, 13),
            darkness = SkyWindowDarkness.NAUTICAL,
        )
        val expected = ArrayList<DateInterval>()
        for (night in 0 until 3) {
            var start: Instant? = null
            var last: Instant? = null
            var time = noon(boulder, 2026, 1, 10 + night)
            val end = noon(boulder, 2026, 1, 11 + night)
            while (time < end) {
                val frame = SkyFrame(time, boulder.observer)
                val place = apparent(frame, star)
                val inside = frame.horizontal(SolarSystemBody.SUN, false).altitude < -12.0 &&
                    box.azimuth.contains(place.azimuth) &&
                    place.altitude >= box.minimumAltitude && place.altitude <= box.maximumAltitude
                if (inside) {
                    if (start == null) start = time
                    last = time
                } else if (start != null) {
                    expected.add(DateInterval(start, assertNotNull(last)))
                    start = null
                }
                time = time.addingTimeInterval(30.0)
            }
            val opened = start
            if (opened != null) expected.add(DateInterval(opened, assertNotNull(last)))
        }
        assertTrue(expected.isNotEmpty())
        PlanningTestSupport.assertIntervals(found.map { it.interval }, expected, 45.0, "against a 30 s scan")
    }

    // MARK: The Milky Way core

    @Test
    fun testTheCoreStandsInASouthWestWindowOnSummerNightsAtFortySevenNorth() {
        // At 47° N the core (declination −29°) culminates at 14° and sets at azimuth 225° (cos A = sin δ / cos φ).
        // Its setting runs through 210…240° below 10° from about mid-July, when it is still dark enough, to autumn.
        val box = window(225.0, 15.0, 3.0, 10.0)
        val found = matches(
            innsbruck, coreTarget, box, noon(innsbruck, 2026, 7, 1), noon(innsbruck, 2026, 10, 1),
        )
        assertTrue(found.size in 50..93, "found ${found.size}")
        val core = MilkyWayCoreVisibility.galacticCentre
        for (match in found) {
            val frame = SkyFrame(match.midpoint, innsbruck.observer)
            val place = apparent(frame, core)
            assertEquals(place.azimuth, match.azimuth, 0.02)
            assertEquals(place.altitude, match.altitude, 0.02)
            assertTrue(match.azimuth in 210.0..240.0 && match.altitude in 3.0..10.0)
            assertTrue(match.isAboveSkyline)
            assertTrue(match.night.start <= match.interval.start && match.interval.end <= match.night.end)
            for (date in listOf(match.interval.start, match.midpoint, match.interval.end)) {
                assertTrue(sunAltitude(date, innsbruck.observer) < -17.99, "dark at $date")
            }
        }
        // Even at the solstice the Sun sinks 19.6° at 47° N, so there are dark hours at the start of the range, with
        // the core setting at the end of them.
        assertTrue(found.first().interval.start > noon(innsbruck, 2026, 7, 1))
        assertTrue(found.zipWithNext().all { (a, b) -> a.interval.end <= b.interval.start }, "in time order")
        // The core sets about 4 minutes earlier each night, so the window is crossed every night in August.
        val august = found.filter { it.night.start >= noon(innsbruck, 2026, 8, 1) && it.night.start < noon(innsbruck, 2026, 9, 1) }
        assertEquals(31, august.map { it.night.start }.toSet().size)
    }

    @Test
    fun testTheCoreWindowMatchesTheNightPlannerWhenItIsAskedForHigherUp() {
        // Altitudes of 10° and more in the dark are what the planner calls the core visible. The planner's 10° is
        // geometric; the window's altitudes are apparent, so its lower edge is where refraction lifts 10° to.
        val summary = PlanningTestSupport.summary(boulder, 2026, 7, 10)
        val visible = summary.milkyWayCore.visibleIntervals
        assertTrue(visible.isNotEmpty())
        val found = matches(
            boulder, coreTarget, window(180.0, 180.0, Refraction.apparentAltitude(10.0), 90.0), summary.night.start,
            summary.night.end,
        )
        PlanningTestSupport.assertIntervals(found.map { it.interval }, visible, 25.0, "the planner's visible intervals")
        val best = assertNotNull(summary.milkyWayCore.bestTime)
        assertTrue(found.any { best in it.interval })
    }

    // MARK: Darkness

    @Test
    fun testDarknessKindsKeepTheStretchOfTheSunBelowTheirLimit() {
        // A circumpolar star is in the window all night, so the matches are exactly the dark stretches.
        val star = SkyWindowTarget.Fixed(EquatorialCoordinate(0.0, 80.0))
        val box = window(180.0, 180.0, 5.0, 90.0)
        val from = noon(innsbruck, 2026, 8, 20)
        val twilight = TwilightCalculator.night(from, innsbruck.observer, innsbruck.timeZone())
        fun hours(kind: SkyWindowDarkness): Double {
            val found = matches(innsbruck, star, box, from, from.addingTimeInterval(86_400.0), darkness = kind)
            assertEquals(1, found.size, kind.rawValue)
            return found.sumOf { it.interval.duration } / 3600
        }
        fun expected(dusk: Instant?, dawn: Instant?): Double =
            assertNotNull(dawn).timeIntervalSince(assertNotNull(dusk)) / 3600
        assertEquals(expected(twilight.astronomicalDusk, twilight.astronomicalDawn), hours(SkyWindowDarkness.ASTRONOMICAL), 0.012)
        assertEquals(expected(twilight.nauticalDusk, twilight.nauticalDawn), hours(SkyWindowDarkness.NAUTICAL), 0.012)
        assertEquals(expected(twilight.civilDusk, twilight.civilDawn), hours(SkyWindowDarkness.CIVIL), 0.012)
        assertEquals(24.0, hours(SkyWindowDarkness.ANY_TIME), 1e-6)
        assertTrue(
            hours(SkyWindowDarkness.ANY_TIME) > hours(SkyWindowDarkness.CIVIL) + 1 &&
                hours(SkyWindowDarkness.CIVIL) > hours(SkyWindowDarkness.NAUTICAL) + 0.5 &&
                hours(SkyWindowDarkness.NAUTICAL) > hours(SkyWindowDarkness.ASTRONOMICAL) + 0.5,
        )
    }

    @Test
    fun testThereIsNoAstronomicalDarknessInAPolarSummer() {
        val box = window(180.0, 180.0, 0.0, 90.0)
        val from = noon(tromso, 2026, 6, 20)
        val to = noon(tromso, 2026, 6, 24)
        val star = SkyWindowTarget.Fixed(EquatorialCoordinate(80.0, 30.0))
        for (kind in listOf(SkyWindowDarkness.ASTRONOMICAL, SkyWindowDarkness.NAUTICAL, SkyWindowDarkness.CIVIL)) {
            assertTrue(matches(tromso, star, box, from, to, darkness = kind).isEmpty(), kind.rawValue)
        }
        val anyTime = matches(tromso, SkyWindowTarget.Body(SolarSystemBody.SUN), box, from, to, darkness = SkyWindowDarkness.ANY_TIME)
        assertTrue(anyTime.isNotEmpty(), "the midnight sun stands in the window")
    }

    // MARK: The Moon

    private val julyFrom get() = noon(boulder, 2026, 7, 1)
    private val julyTo get() = noon(boulder, 2026, 8, 1)
    private val wholeSouth get() = window(180.0, 90.0, 5.0, 90.0)

    private fun nights(found: List<SkyWindowMatch>): Set<Instant> = found.map { it.night.start }.toSet()

    @Test
    fun testTheMoonBelowTheHorizonRuleRemovesNights() {
        val all = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo)
        val moonFree = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo, moon = SkyWindowMoonRule.BelowHorizon)
        assertTrue(nights(all).size >= 29, "the core is up in the dark every night of July")
        assertTrue(nights(moonFree).size <= nights(all).size - 4, "${nights(moonFree).size} of ${nights(all).size}")
        assertTrue(nights(moonFree).isNotEmpty())
        assertTrue(nights(all).containsAll(nights(moonFree)))
        for (match in moonFree) {
            assertFalse(match.moon.isUp)
            for (date in listOf(match.interval.start, match.midpoint, match.interval.end)) {
                val altitude = SkyFrame(date, boulder.observer).horizontal(SolarSystemBody.MOON, false).altitude
                assertTrue(altitude < -0.75, "the Moon is down at $date: $altitude")
            }
        }
        val removed = nights(all) - nights(moonFree)
        for (match in all.filter { it.night.start in removed }) {
            assertTrue(match.moon.isUp, "a removed night has the Moon up whenever the core is in the window")
        }
        val total = all.sumOf { it.interval.duration }
        assertTrue(moonFree.sumOf { it.interval.duration } < total - 4 * 3600)
    }

    @Test
    fun testTheIlluminationRuleKeepsTheDimMoonNights() {
        val all = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo)
        val dim = matches(
            boulder, coreTarget, wholeSouth, julyFrom, julyTo, moon = SkyWindowMoonRule.IlluminatedAtMost(0.25),
        )
        assertTrue(nights(dim).size in 6..16, "${nights(dim).size}")
        assertTrue(nights(all).containsAll(nights(dim)))
        for (match in dim) {
            for (date in listOf(match.interval.start, match.midpoint, match.interval.end)) {
                assertTrue(MoonPhaseCalculator.info(date).illuminatedFraction <= 0.252, "$date")
            }
        }
        // New Moon on 14 July 2026, Full Moon on 29 July.
        assertTrue(noon(boulder, 2026, 7, 14) in nights(dim))
        assertFalse(noon(boulder, 2026, 7, 29) in nights(dim))
        val none = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo, moon = SkyWindowMoonRule.IlluminatedAtMost(0.0))
        assertTrue(none.isEmpty())
        val every = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo, moon = SkyWindowMoonRule.IlluminatedAtMost(1.0))
        assertEquals(all, every)
    }

    @Test
    fun testTheMoonStateDescribesTheMoonAtTheMidpoint() {
        val all = matches(boulder, coreTarget, wholeSouth, julyFrom, julyTo)
        assertTrue(all.any { it.moon.isUp } && all.any { !it.moon.isUp })
        for (match in all) {
            val frame = SkyFrame(match.midpoint, boulder.observer)
            assertEquals(frame.horizontal(SolarSystemBody.MOON, true).altitude, match.moon.altitude, 0.02)
            assertEquals(MoonPhaseCalculator.info(match.midpoint).illuminatedFraction, match.moon.illuminatedFraction, 1e-9)
            if (match.moon.altitude > 1.0) assertTrue(match.moon.isUp)
            if (match.moon.altitude < -2.0) assertFalse(match.moon.isUp)
        }
        // The Moon itself: a rule that it be down can never hold for it.
        val moonWindow = window(180.0, 180.0, 0.0, 90.0)
        val moonSelf = matches(
            boulder, SkyWindowTarget.Body(SolarSystemBody.MOON), moonWindow, julyFrom, julyFrom.addingTimeInterval(5 * 86_400.0),
            darkness = SkyWindowDarkness.ANY_TIME,
        )
        assertTrue(moonSelf.isNotEmpty())
        assertTrue(moonSelf.all { it.moon.isUp })
        assertTrue(
            matches(
                boulder, SkyWindowTarget.Body(SolarSystemBody.MOON), moonWindow, julyFrom, julyFrom.addingTimeInterval(5 * 86_400.0),
                darkness = SkyWindowDarkness.ANY_TIME, moon = SkyWindowMoonRule.BelowHorizon,
            ).all { it.altitude < 0.5 },
        )
    }

    // MARK: Objects and the skyline

    @Test
    fun testEveryKindOfTargetFollowsTheSameSky() {
        val from = noon(boulder, 2026, 9, 1)
        val to = noon(boulder, 2026, 9, 3)
        val everywhere = window(180.0, 180.0, 0.0, 90.0)
        val catalog = CatalogSmallBodyFixture.catalog()
        fun check(target: SkyWindowTarget, expected: (SkyFrame) -> HorizontalCoordinate) {
            val found = matches(boulder, target, everywhere, from, to, darkness = SkyWindowDarkness.ANY_TIME)
            assertTrue(found.isNotEmpty(), "$target")
            for (match in found) {
                val horizontal = expected(SkyFrame(match.midpoint, boulder.observer))
                assertEquals(horizontal.azimuth, match.azimuth, 0.02, "$target")
                assertEquals(horizontal.altitude, match.altitude, 0.02, "$target")
            }
        }
        check(SkyWindowTarget.Body(SolarSystemBody.JUPITER)) { it.horizontal(SolarSystemBody.JUPITER, true) }
        check(SkyWindowTarget.Body(SolarSystemBody.MOON)) { it.horizontal(SolarSystemBody.MOON, true) }
        val place = EquatorialCoordinate(279.2347, 38.7837)
        val motion = ProperMotion(200.94, 286.23)
        check(SkyWindowTarget.Fixed(place, motion)) { it.horizontal(place, motion, true) }
        check(SkyWindowTarget.MilkyWayCore) { it.horizontal(MilkyWayCoreVisibility.galacticCentre, null, true) }
        for (id in listOf("planet.saturn", "dso.M31", "special.galacticCenter", "special.ncp", "asteroid.4", "comet.12P")) {
            val obj = assertNotNull(catalog.objectWithID(id), id)
            check(SkyWindowTarget.CatalogObject(obj)) { it.horizontal(obj, true) }
        }
        val custom = CelestialObject.custom("Test", EquatorialCoordinate(10.0, -20.0))
        check(SkyWindowTarget.CatalogObject(custom)) { it.horizontal(custom, true) }
    }

    @Test
    fun testTheSkylineDecidesWhetherAMatchIsAboveIt() {
        val star = SkyWindowTarget.Fixed(EquatorialCoordinate(30.0, 0.0))
        val from = noon(boulder, 2026, 11, 1)
        val to = noon(boulder, 2026, 11, 4)
        val wall = HorizonProfile.constant(25.0)
        val low = window(180.0, 180.0, 5.0, 15.0)
        val high = window(180.0, 180.0, 30.0, 40.0)
        val hidden = matches(boulder, star, low, from, to, horizon = wall, darkness = SkyWindowDarkness.ANY_TIME)
        assertTrue(hidden.isNotEmpty() && hidden.none { it.isAboveSkyline })
        val visible = matches(boulder, star, high, from, to, horizon = wall, darkness = SkyWindowDarkness.ANY_TIME)
        assertTrue(visible.isNotEmpty() && visible.all { it.isAboveSkyline })
        val flat = matches(boulder, star, low, from, to, darkness = SkyWindowDarkness.ANY_TIME)
        assertEquals(hidden.map { it.interval }, flat.map { it.interval })
        assertTrue(flat.all { it.isAboveSkyline })
        // A window cut from the skyline itself is by construction above it.
        val gap = assertNotNull(SkyWindow.skylinePoint(110.0, HorizonTestSupport.truthProfile, 5.0, 1.0..12.0))
        val over = matches(
            boulder, star, gap, from, to, horizon = HorizonTestSupport.truthProfile, darkness = SkyWindowDarkness.ANY_TIME,
        )
        assertTrue(over.all { it.isAboveSkyline })
    }

    // MARK: Ranges, limits and errors

    @Test
    fun testMatchesStayInsideTheRangeAndTheirNights() {
        // 03:00 on the 11th: inside the night of the 10th, with a star on the meridian at that moment.
        val from = noon(boulder, 2026, 1, 10).addingTimeInterval(15 * 3600.0)
        val star = SkyWindowTarget.Fixed(EquatorialCoordinate(SkyFrame(from, boulder.observer).localApparentSiderealTime, 10.0))
        val to = noon(boulder, 2026, 1, 14).addingTimeInterval(3 * 3600.0)
        val box = window(180.0, 180.0, 0.0, 90.0)
        val found = matches(boulder, star, box, from, to, darkness = SkyWindowDarkness.ANY_TIME)
        assertTrue(found.isNotEmpty())
        assertEquals(from, found.first().interval.start, "the star is up at the start of the range")
        assertEquals(noon(boulder, 2026, 1, 10), found.first().night.start)
        assertTrue(found.all { it.interval.start >= from && it.interval.end <= to })
        assertTrue(found.all { it.night.start <= it.interval.start && it.interval.end <= it.night.end })
        assertTrue(found.zipWithNext().all { (a, b) -> a.interval.end <= b.interval.start })
        assertTrue(matches(boulder, star, box, from, from, darkness = SkyWindowDarkness.ANY_TIME).isEmpty())
    }

    @Test
    fun testTheSearchRefusesWhatItCannotRun() {
        val box = window(180.0, 10.0, 0.0, 90.0)
        val from = noon(boulder, 2026, 1, 1)
        assertFailsWith<SkyWindowError.InvalidRange> {
            matches(boulder, coreTarget, box, from.addingTimeInterval(1.0), from)
        }
        assertFailsWith<SkyWindowError.RangeTooLong> {
            matches(boulder, coreTarget, box, from, from.addingTimeInterval(401 * 86_400.0))
        }
        assertFailsWith<SkyWindowError.InvalidTarget> {
            matches(boulder, SkyWindowTarget.Fixed(EquatorialCoordinate(Double.NaN, 10.0)), box, from, from.addingTimeInterval(86_400.0))
        }
        assertFailsWith<SkyWindowError.InvalidMoonRule> {
            matches(boulder, coreTarget, box, from, from.addingTimeInterval(86_400.0), moon = SkyWindowMoonRule.IlluminatedAtMost(1.5))
        }
        assertFailsWith<SkyWindowError.InvalidMoonRule> {
            matches(boulder, coreTarget, box, from, from.addingTimeInterval(86_400.0), moon = SkyWindowMoonRule.IlluminatedAtMost(Double.NaN))
        }
    }

    @Test
    fun testTheMatchLimitCutsALongResultShort() {
        // The Sun is in an all-sky window whenever you look: one match per night, cut at the limit.
        val everywhere = SkyWindow.create(AzimuthRange.full, -90.0, 90.0)
        val full = assertNotNull(everywhere)
        val from = noon(boulder, 2026, 1, 1)
        val found = matches(
            boulder, SkyWindowTarget.Body(SolarSystemBody.SUN), full, from, from.addingTimeInterval(300 * 86_400.0),
            darkness = SkyWindowDarkness.ANY_TIME,
        )
        assertEquals(SkyWindowFinder.maximumMatchCount, found.size)
        assertEquals(200, SkyWindowFinder.maximumMatchCount)
        assertEquals(from, found.first().interval.start)
        assertEquals(noon(boulder, 2026, 1, 200), found.last().night.start, "the 200th night, across the change to daylight time")
    }

    @Test
    fun testTheNightLimitHoldsForAYearAndAHalf() {
        assertEquals(400, SkyWindowFinder.maximumNightCount)
        val box = window(180.0, 5.0, 0.0, 90.0)
        val from = noon(boulder, 2026, 1, 1)
        // 400 nights are fine (this one has no match: the Sun is never below −18° while it stands due south); 401 are not.
        assertTrue(
            matches(
                boulder, SkyWindowTarget.Body(SolarSystemBody.SUN), box, from, from.addingTimeInterval(400 * 86_400.0),
                darkness = SkyWindowDarkness.ASTRONOMICAL,
            ).isEmpty(),
        )
        assertFailsWith<SkyWindowError.RangeTooLong> {
            matches(boulder, coreTarget, box, from, from.addingTimeInterval(401 * 86_400.0 + 3600))
        }
    }

    @Test
    fun testAYearOfSearchingIsFast() {
        val box = window(200.0, 20.0, 0.0, 40.0)
        val from = noon(boulder, 2026, 1, 1)
        val to = noon(boulder, 2027, 1, 1)
        matches(boulder, coreTarget, box, from, from.addingTimeInterval(5 * 86_400.0))
        val start = System.nanoTime()
        val found = matches(boulder, coreTarget, box, from, to)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertTrue(found.size in 100..200, "found ${found.size}")
        assertTrue(elapsed < yearLimit, "took $elapsed s")
        val startMoon = System.nanoTime()
        matches(boulder, coreTarget, box, from, to, moon = SkyWindowMoonRule.BelowHorizon)
        val elapsedMoon = (System.nanoTime() - startMoon) / 1e9
        assertTrue(elapsedMoon < yearLimit, "took $elapsedMoon s with the Moon")
    }

    private companion object {
        /** Seconds allowed for a year: the documented few hundred milliseconds, with ample room for a slow machine. */
        const val yearLimit: Double = 10.0
    }
}

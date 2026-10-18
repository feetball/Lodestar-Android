package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How high and for how long targets get in the dark: one target at a time, in batches, and for the Sun,
 * Moon and planets, checked against brute-force scans and the rise/set root finder.
 */
class PlanningVisibilityTests {
    private val boulder = PlanningTestSupport.boulder

    // MARK: Targets

    private val andromedaGalaxy = EquatorialCoordinate.fromRightAscensionHours(
        0.0 + 42.0 / 60 + 44.3 / 3_600,
        41.0 + 16.0 / 60 + 9.0 / 3_600,
    )
    private val orionNebula = EquatorialCoordinate.fromRightAscensionHours(
        5.0 + 35.0 / 60 + 17.3 / 3_600,
        -(5.0 + 23.0 / 60 + 28.0 / 3_600),
    )
    private val lagoonNebula = EquatorialCoordinate.fromRightAscensionHours(
        18.0 + 3.0 / 60 + 37.0 / 3_600,
        -(24.0 + 23.0 / 60 + 12.0 / 3_600),
    )
    private val polaris = EquatorialCoordinate(37.954_515, 89.264_110)
    private val alphaCentauri = EquatorialCoordinate.fromRightAscensionHours(
        14.0 + 39.0 / 60 + 36.5 / 3_600,
        -(60.0 + 50.0 / 60 + 2.0 / 3_600),
    )

    /** Barnard's Star, with the largest proper motion of any star: 10.3″ a year, almost due north. */
    private val barnardsStar = EquatorialCoordinate(269.452_075, 4.693_391)
    private val barnardsMotion = ProperMotion(-798.58, 10_328.12)

    private fun geometric(target: EquatorialCoordinate, motion: ProperMotion? = null): (SkyFrame) -> HorizontalCoordinate =
        { it.horizontal(target, motion, false) }

    /** A reproducible spread of targets over the whole sky. */
    private fun scatteredTargets(count: Int, seed: ULong): List<EquatorialCoordinate> {
        val generator = PlanningTestSupport.SeededGenerator(seed)
        return List(count) {
            EquatorialCoordinate(
                generator.nextDouble(0.0, 360.0),
                AstroMath.asinDeg(generator.nextDoubleInclusive(-1.0, 1.0)),
            )
        }
    }

    // MARK: Brute force

    private class Reading(val date: Instant, val altitude: Double)

    /** Geometric altitudes of a target every [step] seconds through the dark intervals, ends included. */
    private fun scan(darkness: List<DateInterval>, step: Double = 30.0, altitude: (Instant) -> Double): List<Reading> =
        darkness.flatMap { interval ->
            val dates = ArrayList<Instant>()
            var index = 0
            while (true) {
                val date = interval.start.addingTimeInterval(index.toDouble() * step)
                if (date >= interval.end) break
                dates.add(date)
                index += 1
            }
            dates.add(interval.end)
            dates.map { Reading(it, altitude(it)) }
        }

    private fun directAltitude(
        target: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        date: Instant,
        observer: Observer,
    ): Double = SkyFrame(date, observer).horizontal(target, properMotion, false).altitude

    // MARK: One target

    @Test
    fun testDefaultMinimumAltitudeIsTwentyDegrees() {
        assertEquals(20.0, NightPlanner.defaultMinimumAltitude)
    }

    @Test
    fun testSingleTargetAgreesWithABruteForceScan() {
        for ((site, month, day) in listOf(Triple(boulder, 10, 25), Triple(PlanningTestSupport.sydney, 6, 21))) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            val observer = site.observer
            for (target in listOf(andromedaGalaxy, orionNebula, lagoonNebula)) {
                val label = "${site.name} $target"
                val result = NightPlanner.visibility(geometric(target), summary)
                val samples = scan(summary.astronomicalDarknessIntervals) { directAltitude(target, null, it, observer) }
                val highest = assertNotNull(samples.maxByOrNull { it.altitude })

                assertEquals(20.0, result.minimumAltitude, label)
                assertEquals(highest.altitude, assertNotNull(result.maxAltitudeDuringDarkness), 2e-3, label)
                val best = assertNotNull(result.bestTime)
                assertEquals(0.0, best.timeIntervalSince(highest.date), 60.0, label)
                assertEquals(
                    assertNotNull(result.maxAltitudeDuringDarkness), directAltitude(target, null, best, observer), 2e-3, label,
                )

                val secondsAbove = samples.count { it.altitude >= 20 }.toDouble() * 30
                assertEquals(secondsAbove, result.hoursAboveMinimum * 3_600, 90.0, label)
                assertEquals(
                    result.intervalsAboveMinimum,
                    IntervalSet.intersection(result.intervalsAboveMinimum, summary.astronomicalDarknessIntervals), label,
                )

                val events = RiseSetCalculator.riseTransitSet(target, null, observer, summary.night)
                assertEquals(events.circumstance, result.riseTransitSet.circumstance, label)
                val pairs = listOf(
                    result.riseTransitSet.rise to events.rise,
                    result.riseTransitSet.transit to events.transit,
                    result.riseTransitSet.set to events.set,
                )
                for ((found, wanted) in pairs) {
                    assertEquals(wanted == null, found == null, label)
                    if (found != null && wanted != null) assertEquals(0.0, found.timeIntervalSince(wanted), 1.0, label)
                }
            }
        }
    }

    @Test
    fun testIntervalsAboveTheMinimumAreBoundedByTheMinimumAltitude() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val observer = boulder.observer
        val result = NightPlanner.visibility(geometric(andromedaGalaxy), summary, minimumAltitude = 45.0)
        assertEquals(45.0, result.minimumAltitude)
        assertFalse(result.intervalsAboveMinimum.isEmpty())
        val darkEdges = summary.astronomicalDarknessIntervals.flatMap { listOf(it.start, it.end) }
        for (interval in result.intervalsAboveMinimum) {
            val middle = interval.start.addingTimeInterval(interval.duration / 2)
            assertTrue(directAltitude(andromedaGalaxy, null, middle, observer) >= 45)
            for (edge in listOf(interval.start, interval.end)) {
                if (!darkEdges.all { abs(it.timeIntervalSince(edge)) > 1 }) continue
                assertEquals(45.0, directAltitude(andromedaGalaxy, null, edge, observer), 0.01)
            }
        }
        assertEquals(
            result.intervalsAboveMinimum.fold(0.0) { total, interval -> total + interval.duration } / 3_600,
            result.hoursAboveMinimum, 1e-9,
        )
    }

    @Test
    fun testHoursAboveShrinkAsTheMinimumAltitudeRises() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        var previous = Double.POSITIVE_INFINITY
        for (minimum in listOf(-90.0, 0.0, 10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 89.0, 90.0)) {
            val hours = NightPlanner.visibility(geometric(andromedaGalaxy), summary, minimumAltitude = minimum).hoursAboveMinimum
            assertTrue(hours <= previous, "minimum $minimum")
            previous = hours
        }
        val everything = NightPlanner.visibility(geometric(andromedaGalaxy), summary, minimumAltitude = -90.0)
        assertEquals(summary.astronomicalDarkHours, everything.hoursAboveMinimum, 1e-6)
        assertEquals(0.0, previous, "no target is 90° high for long")
    }

    @Test
    fun testInvalidMinimumAltitudesAreReplacedOrClamped() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val position = geometric(andromedaGalaxy)
        assertEquals(20.0, NightPlanner.visibility(position, summary, minimumAltitude = Double.NaN).minimumAltitude)
        assertEquals(20.0, NightPlanner.visibility(position, summary, minimumAltitude = Double.POSITIVE_INFINITY).minimumAltitude)
        assertEquals(90.0, NightPlanner.visibility(position, summary, minimumAltitude = 500.0).minimumAltitude)
        assertEquals(-90.0, NightPlanner.visibility(position, summary, minimumAltitude = -500.0).minimumAltitude)
    }

    @Test
    fun testCircumpolarTargetStaysUpAllNight() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val result = NightPlanner.visibility(geometric(polaris), summary)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, result.riseTransitSet.circumstance)
        assertNull(result.riseTransitSet.rise)
        assertNull(result.riseTransitSet.set)
        assertEquals(40.6, assertNotNull(result.maxAltitudeDuringDarkness), 0.1, "latitude plus 0.6°")
        assertEquals(summary.astronomicalDarkHours, result.hoursAboveMinimum, 1e-6)
        assertEquals(summary.astronomicalDarknessIntervals, result.intervalsAboveMinimum)
    }

    @Test
    fun testTargetThatNeverRisesHasNoUsableTime() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val result = NightPlanner.visibility(geometric(alphaCentauri), summary)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, result.riseTransitSet.circumstance)
        assertTrue(assertNotNull(result.maxAltitudeDuringDarkness) < -10)
        assertEquals(0.0, result.hoursAboveMinimum)
        assertTrue(result.intervalsAboveMinimum.isEmpty())
        assertNotNull(result.bestTime, "the best time is the least bad moment, still reported")
    }

    @Test
    fun testTargetCrossingNearTheZenithReachesItsCulminationAltitude() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        // Precession has carried this J2000 place about 0.13° north by 2026, so it passes 0.01° from the zenith.
        val nearZenith = EquatorialCoordinate(330.0, 39.87)
        val apparent = SkyFrame(summary.referenceDate, summary.observer).apparentPlace(nearZenith)
        val expected = 90 - abs(40 - apparent.declination)
        assertTrue(expected > 89.9, "the target does pass almost through the zenith")
        val result = NightPlanner.visibility(geometric(nearZenith), summary)
        val transit = assertNotNull(result.riseTransitSet.transit)
        assertEquals(
            expected, assertNotNull(result.maxAltitudeDuringDarkness), 0.03,
            "the sampled sines are least precise right at the zenith",
        )
        assertEquals(0.0, assertNotNull(result.bestTime).timeIntervalSince(transit), 20.0)
    }

    // MARK: Darkness

    @Test
    fun testNoAstronomicalDarknessLeavesNoAltitudeButKeepsTheEvents() {
        val summary = PlanningTestSupport.summary(PlanningTestSupport.tromso, 2026, 6, 21)
        assertFalse(summary.hasAstronomicalDarkness)
        val single = NightPlanner.visibility(geometric(andromedaGalaxy), summary)
        val batch = NightPlanner.visibility(listOf(andromedaGalaxy, orionNebula), summary = summary)
        val body = NightPlanner.visibility(SolarSystemBody.SATURN, summary)
        for (result in listOf(single, batch[0], batch[1], body)) {
            assertNull(result.maxAltitudeDuringDarkness)
            assertNull(result.bestTime)
            assertTrue(result.intervalsAboveMinimum.isEmpty())
            assertEquals(0.0, result.hoursAboveMinimum)
        }
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, single.riseTransitSet.circumstance, "the galaxy is circumpolar at 69.65° N")
        assertEquals(RiseTransitSet.Circumstance.NORMAL, batch[1].riseTransitSet.circumstance)
    }

    @Test
    fun testMoonFreeDarknessCountsOnlyTheHoursWithoutMoonlight() {
        // 21 June 2026: the Moon sets at 00:57 MDT, 2.5 hours before dawn, and the Pole Star region stays up.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        val moonSet = assertNotNull(summary.moon.riseTransitSet.set)
        val all = NightPlanner.visibility(geometric(polaris), summary, DarknessKind.ASTRONOMICAL)
        val free = NightPlanner.visibility(geometric(polaris), summary, DarknessKind.MOON_FREE)
        assertEquals(summary.astronomicalDarkHours, all.hoursAboveMinimum, 1e-6)
        assertEquals(summary.moonFreeDarkHours, free.hoursAboveMinimum, 1e-6)
        assertEquals(summary.darknessIntervals, free.intervalsAboveMinimum)
        assertTrue(assertNotNull(free.bestTime) >= moonSet)

        // Around Full Moon there is no moon-free darkness at all.
        val full = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val result = NightPlanner.visibility(geometric(andromedaGalaxy), full, DarknessKind.MOON_FREE)
        assertNull(result.maxAltitudeDuringDarkness)
        assertNull(result.bestTime)
        assertEquals(0.0, result.hoursAboveMinimum)
        assertNotNull(NightPlanner.visibility(geometric(andromedaGalaxy), full).maxAltitudeDuringDarkness)
    }

    @Test
    fun testDarknessKindRoundTripsThroughCodable() {
        for (kind in DarknessKind.entries) {
            val data = Json.encodeToString(kind)
            assertEquals(kind, Json.decodeFromString<DarknessKind>(data))
        }
        assertEquals(listOf(DarknessKind.ASTRONOMICAL, DarknessKind.MOON_FREE), DarknessKind.entries)
    }

    // MARK: Batches

    @Test
    fun testBatchMatchesTheSingleTargetPath() {
        for ((site, month, day) in listOf(
            Triple(boulder, 10, 25), Triple(PlanningTestSupport.sydney, 6, 21), Triple(PlanningTestSupport.london, 3, 20),
        )) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            val observer = site.observer
            val targets = scatteredTargets(80, (month * 100 + day).toULong())
            val batch = NightPlanner.visibility(targets, summary = summary)
            assertEquals(targets.size, batch.size)
            for ((target, found) in targets.zip(batch)) {
                val label = "${site.name} $target"
                val single = NightPlanner.visibility(geometric(target), summary)
                assertEquals(
                    AstroMath.sinDeg(assertNotNull(single.maxAltitudeDuringDarkness)),
                    AstroMath.sinDeg(assertNotNull(found.maxAltitudeDuringDarkness)), 5e-7, label,
                )
                assertEquals(single.hoursAboveMinimum, found.hoursAboveMinimum, 0.005, label)
                assertEquals(single.intervalsAboveMinimum.size, found.intervalsAboveMinimum.size, label)
                PlanningTestSupport.assertIntervals(found.intervalsAboveMinimum, single.intervalsAboveMinimum, 2.0, label)
                assertEquals(single.riseTransitSet.circumstance, found.riseTransitSet.circumstance, label)
                assertEquals(single.riseTransitSet.rise == null, found.riseTransitSet.rise == null, label)
                assertEquals(single.riseTransitSet.set == null, found.riseTransitSet.set == null, label)
                assertEquals(single.riseTransitSet.transit == null, found.riseTransitSet.transit == null, label)
                val times = listOf(
                    found.riseTransitSet.rise to single.riseTransitSet.rise,
                    found.riseTransitSet.transit to single.riseTransitSet.transit,
                    found.riseTransitSet.set to single.riseTransitSet.set,
                )
                for ((batchTime, singleTime) in times) {
                    if (batchTime != null && singleTime != null) {
                        assertEquals(0.0, batchTime.timeIntervalSince(singleTime), 1.0, label)
                    }
                }
                val best = found.bestTime
                val maximum = assertNotNull(found.maxAltitudeDuringDarkness)
                if (best != null) {
                    assertEquals(
                        AstroMath.sinDeg(maximum), AstroMath.sinDeg(directAltitude(target, null, best, observer)), 5e-7, label,
                    )
                }
            }
        }
    }

    @Test
    fun testBatchResultsFollowTheOrderOfTheTargets() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val targets = listOf(alphaCentauri, polaris, andromedaGalaxy, orionNebula)
        val results = NightPlanner.visibility(targets, summary = summary)
        assertEquals(
            listOf(
                RiseTransitSet.Circumstance.ALWAYS_DOWN, RiseTransitSet.Circumstance.ALWAYS_UP,
                RiseTransitSet.Circumstance.NORMAL, RiseTransitSet.Circumstance.NORMAL,
            ),
            results.map { it.riseTransitSet.circumstance },
        )
        assertEquals(
            listOf(
                RiseTransitSet.Circumstance.NORMAL, RiseTransitSet.Circumstance.NORMAL,
                RiseTransitSet.Circumstance.ALWAYS_UP, RiseTransitSet.Circumstance.ALWAYS_DOWN,
            ),
            NightPlanner.visibility(targets.reversed(), summary = summary).map { it.riseTransitSet.circumstance },
        )
        assertTrue(NightPlanner.visibility(emptyList(), summary = summary).isEmpty())
    }

    @Test
    fun testProperMotionMovesTheTargetByTheAmountItImplies() {
        // 14 June 2026: Barnard's Star crosses the meridian at about 00:20 solar time, in the dark. At the
        // meridian a shift north raises it by exactly that shift: 10.328″ a year for 26.5 years.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 14)
        val years = SkyFrame(summary.night.start.addingTimeInterval(summary.night.duration / 2), boulder.observer).yearsSinceJ2000
        val shift = 10.328_12 * years / 3_600
        val without = assertNotNull(NightPlanner.visibility(listOf(barnardsStar), summary = summary)[0].maxAltitudeDuringDarkness)
        val withMotion = assertNotNull(
            NightPlanner.visibility(listOf(barnardsStar), listOf(barnardsMotion), summary)[0].maxAltitudeDuringDarkness,
        )
        assertEquals(shift, withMotion - without, 0.002)
        assertTrue(shift > 0.07)
        val direct = assertNotNull(
            NightPlanner.visibility(geometric(barnardsStar, barnardsMotion), summary).maxAltitudeDuringDarkness,
        )
        assertEquals(direct, withMotion, 1e-4, "the same as moving the star in every frame")
    }

    @Test
    fun testProperMotionsApplyByPositionAndMissingEntriesMeanNone() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 14)
        val targets = listOf(barnardsStar, barnardsStar, barnardsStar)
        val altitudes = NightPlanner.visibility(targets, listOf(null, barnardsMotion), summary)
            .map { it.maxAltitudeDuringDarkness ?: Double.NaN }
        assertEquals(altitudes[2], altitudes[0], 1e-9, "a nil entry and a missing entry both mean no motion")
        assertTrue(altitudes[1] - altitudes[0] > 0.07)
        val longer = NightPlanner.visibility(listOf(barnardsStar), listOf(barnardsMotion, barnardsMotion, null), summary)
        assertEquals(1, longer.size, "extra motions are ignored")
        assertEquals(altitudes[1], longer[0].maxAltitudeDuringDarkness ?: Double.NaN, 1e-9)
    }

    @Test
    fun testTargetsWithoutFiniteCoordinatesNeverRise() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val broken = listOf(
            EquatorialCoordinate(Double.NaN, 10.0),
            EquatorialCoordinate(10.0, Double.NaN),
            EquatorialCoordinate(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY),
        )
        val results = NightPlanner.visibility(broken + andromedaGalaxy, summary = summary)
        assertEquals(4, results.size)
        for (result in results.take(3)) {
            assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, result.riseTransitSet.circumstance)
            assertNull(result.riseTransitSet.rise)
            assertNull(result.riseTransitSet.transit)
            assertNull(result.riseTransitSet.set)
            assertNull(result.maxAltitudeDuringDarkness)
            assertNull(result.bestTime)
            assertEquals(0.0, result.hoursAboveMinimum)
        }
        assertNotNull(results[3].maxAltitudeDuringDarkness, "the others are unaffected")
    }

    @Test
    fun testBatchOverAnIntervalUsesTheIntervalForEverything() {
        val observer = boulder.observer
        val start = PlanningTestSupport.localNoon(2026, 10, 25, boulder.timeZone()).addingTimeInterval(11 * 3_600.0)
        val window = DateInterval(start, 4 * 3_600.0) // 23:00 to 03:00 MDT
        val targets = listOf(andromedaGalaxy, orionNebula, polaris)
        val results = NightPlanner.visibility(targets, observer = observer, interval = window)
        for ((target, result) in targets.zip(results)) {
            val samples = scan(listOf(window)) { directAltitude(target, null, it, observer) }
            val highest = assertNotNull(samples.maxByOrNull { it.altitude })
            assertEquals(highest.altitude, assertNotNull(result.maxAltitudeDuringDarkness), 2e-3)
            assertEquals(samples.count { it.altitude >= 20 }.toDouble() * 30, result.hoursAboveMinimum * 3_600, 90.0)
            assertTrue(result.intervalsAboveMinimum.all { window.start <= it.start && it.end <= window.end })
            assertEquals(RiseSetCalculator.riseTransitSet(target, null, observer, window), result.riseTransitSet)
        }
        val single = NightPlanner.visibility(geometric(andromedaGalaxy), observer, window)
        assertEquals(
            results[0].maxAltitudeDuringDarkness ?: Double.POSITIVE_INFINITY,
            single.maxAltitudeDuringDarkness ?: Double.NaN, 2e-5,
        )
    }

    @Test
    fun testUnusableIntervalsGiveNoDarknessInsteadOfFailing() {
        val observer = boulder.observer
        // Swift builds the first window from a NaN start. An Instant cannot be NaN (the Foundation helper maps
        // NaN to the reference date, a finite instant), so the unusable window starts at +infinity instead.
        val undefined = DateInterval(instantFromTimeIntervalSinceReferenceDate(Double.POSITIVE_INFINITY), 3_600.0)
        val empty = DateInterval(instantFromTimeIntervalSinceReferenceDate(800_000_000.0), 0.0)
        for (window in listOf(undefined, empty)) {
            val single = NightPlanner.visibility(geometric(andromedaGalaxy), observer, window)
            val batch = NightPlanner.visibility(listOf(andromedaGalaxy, polaris), observer = observer, interval = window)
            val body = NightPlanner.visibility(SolarSystemBody.MARS, observer, window)
            assertEquals(2, batch.size)
            for (result in listOf(single, batch[0], batch[1], body)) {
                assertNull(result.maxAltitudeDuringDarkness)
                assertNull(result.bestTime)
                assertEquals(0.0, result.hoursAboveMinimum)
            }
        }
    }

    @Test
    fun testWindowsOfManyWeeksAreSampledMoreCoarselyButStayAccurate() {
        // Over 100 days the dark time is sampled every 864 s instead of every 600 s (10 000 frames at most).
        // Polaris stays above 20° throughout. The Andromeda Galaxy, at declination +41°, is above 20° for
        // the fraction acos((sin 20° − sin 40° sin 41.3°) / (cos 40° cos 41.3°)) / 180° = 0.5456 of every
        // sidereal day, give or take the part-day at the ends of the window; the root finder gives the exact total.
        val observer = boulder.observer
        val window = DateInterval(instantFromTimeIntervalSince1970(1_792_195_200.0), 100.0 * 86_400)
        val results = NightPlanner.visibility(listOf(polaris, andromedaGalaxy), observer = observer, interval = window)
        assertEquals(window.duration, results[0].hoursAboveMinimum * 3_600, 1.0)

        val declination = SkyFrame(window.start.addingTimeInterval(window.duration / 2), observer)
            .apparentPlace(andromedaGalaxy).declination
        val fraction = AstroMath.acosDeg(
            (AstroMath.sinDeg(20.0) - AstroMath.sinDeg(40.0) * AstroMath.sinDeg(declination)) /
                (AstroMath.cosDeg(40.0) * AstroMath.cosDeg(declination)),
        ) / 180
        val exact = IntervalSet.totalDuration(
            PlanningTestSupport.intervals(20.0, window) { directAltitude(andromedaGalaxy, null, it, observer) },
        ) / 3_600
        assertEquals(fraction * 100 * 24, exact, 5.0)
        assertEquals(exact, results[1].hoursAboveMinimum, 0.1)
        assertEquals(90 - abs(40 - declination), assertNotNull(results[1].maxAltitudeDuringDarkness), 0.05)
    }

    // MARK: Solar-system bodies

    @Test
    fun testBodyVisibilityMatchesTheSummary() {
        for ((site, month, day) in listOf(Triple(boulder, 12, 14), Triple(PlanningTestSupport.sydney, 6, 21))) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            for (planet in summary.planets) {
                val label = "${site.name} ${planet.body.displayName}"
                val result = NightPlanner.visibility(planet.body, summary, minimumAltitude = 10.0)
                assertEquals(planet.riseTransitSet, result.riseTransitSet, label)
                assertEquals(
                    planet.maxAltitudeDuringDarkness ?: Double.POSITIVE_INFINITY,
                    result.maxAltitudeDuringDarkness ?: Double.NaN, 1e-9, label,
                )
                assertEquals(planet.visibleIntervals, result.intervalsAboveMinimum, label)
            }
            val moon = NightPlanner.visibility(SolarSystemBody.MOON, summary)
            assertEquals(summary.moon.riseTransitSet, moon.riseTransitSet)
            assertNotNull(moon.maxAltitudeDuringDarkness)
            val sun = NightPlanner.visibility(SolarSystemBody.SUN, summary)
            assertTrue(
                assertNotNull(sun.maxAltitudeDuringDarkness) <= -17.99,
                "the Sun is at −18° at the ends of astronomical darkness and lower between",
            )
            assertEquals(0.0, sun.hoursAboveMinimum)
        }
    }

    @Test
    fun testMoonVisibilityUsesTheUpperLimbForItsEvents() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        val viaBody = NightPlanner.visibility(SolarSystemBody.MOON, summary)
        val viaPosition = NightPlanner.visibility({ it.horizontal(SolarSystemBody.MOON, false) }, summary)
        val set = assertNotNull(viaBody.riseTransitSet.set)
        val centreSet = assertNotNull(viaPosition.riseTransitSet.set)
        // The Moon's centre reaches the standard horizon about a minute before its upper limb leaves it.
        assertTrue(set.timeIntervalSince(centreSet) > 30)
        assertTrue(set.timeIntervalSince(centreSet) < 180)
        assertEquals(
            viaPosition.maxAltitudeDuringDarkness ?: Double.POSITIVE_INFINITY,
            viaBody.maxAltitudeDuringDarkness ?: Double.NaN, 1e-6,
        )
    }

    @Test
    fun testBodyOverAnIntervalMatchesTheNightSummaryOfThatInterval() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        val dark = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        val result = NightPlanner.visibility(SolarSystemBody.JUPITER, boulder.observer, dark)
        val planet = assertNotNull(summary.planets.firstOrNull { it.body == SolarSystemBody.JUPITER })
        assertEquals(
            planet.maxAltitudeDuringDarkness ?: Double.POSITIVE_INFINITY,
            result.maxAltitudeDuringDarkness ?: Double.NaN, 1e-9,
        )
        assertEquals(
            RiseSetCalculator.riseTransitSet(SolarSystemBody.JUPITER, boulder.observer, dark),
            result.riseTransitSet,
        )
    }
}

package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Observing sites and calendar helpers shared by the Planning tests. */
internal object PlanningTestSupport {
    class Site(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val elevation: Double = 0.0,
        val timeZoneIdentifier: String = "UTC",
    ) {
        val observer: Observer
            get() = Observer(GeographicLocation(latitude, longitude, elevation))

        fun timeZone(): ZoneId = ZoneId.of(timeZoneIdentifier)
    }

    val boulder = Site("Boulder", 40.0, -105.27, 1650.0, "America/Denver")
    val sydney = Site("Sydney", -33.87, 151.21, 0.0, "Australia/Sydney")
    val tromso = Site("Tromsø", 69.65, 18.96, 0.0, "Europe/Oslo")
    val london = Site("London", 51.48, 0.0, 0.0, "Europe/London")
    val quito = Site("Quito", 0.0, -78.5, 2800.0, "America/Guayaquil")
    val paranal = Site("Paranal", -24.63, -70.40, 2635.0, "America/Santiago")

    /** Noon on a local calendar day (days beyond the end of the month roll over, like Foundation's lenient `Calendar`). */
    fun localNoon(year: Int, month: Int, day: Int, zone: ZoneId): Instant =
        LocalDate.of(year, month, 1).plusDays((day - 1).toLong()).atTime(12, 0).atZone(zone).toInstant()

    /** The local calendar day whose noon-to-noon night contains [instant], as that day's noon. */
    fun startOfNight(instant: Instant, zone: ZoneId): Instant {
        val noon: ZonedDateTime = instant.atZone(zone).toLocalDate().atTime(12, 0).atZone(zone)
        return if (instant >= noon.toInstant()) noon.toInstant() else noon.minusDays(1).toInstant()
    }

    /** The summary of the night starting on a local calendar day. */
    fun summary(site: Site, year: Int, month: Int, day: Int): NightSummary {
        val zone = site.timeZone()
        return NightPlanner.summary(localNoon(year, month, day, zone), site.observer, zone)
    }

    fun contains(intervals: List<DateInterval>, date: Instant): Boolean =
        intervals.any { it.start <= date && date < it.end }

    /**
     * The parts of [window] in which [altitude] (degrees) is at or above [threshold], found with
     * `RiseSetCalculator`'s root finder: an independent route to what the planner derives from sampled sines.
     */
    fun intervals(threshold: Double, window: DateInterval, altitude: (Instant) -> Double): List<DateInterval> {
        var start: Instant? = if (altitude(window.start) >= threshold) window.start else null
        val result = ArrayList<DateInterval>()
        for (crossing in RiseSetCalculator.crossings(altitude, threshold, window)) {
            when (crossing.kind) {
                AltitudeCrossing.Kind.RISING -> start = crossing.time
                AltitudeCrossing.Kind.SETTING -> {
                    val opened = start
                    if (opened != null) result.add(DateInterval(opened, crossing.time))
                    start = null
                }
            }
        }
        val opened = start
        if (opened != null) result.add(DateInterval(opened, window.end))
        return result
    }

    /** Fails unless both lists hold the same number of intervals whose ends agree to [accuracy] seconds. */
    fun assertIntervals(
        actual: List<DateInterval>,
        expected: List<DateInterval>,
        accuracy: Double,
        message: String = "",
    ) {
        if (actual.size != expected.size) {
            fail("$message: $actual against $expected (expected ${expected.size} intervals, found ${actual.size})")
        }
        for ((found, wanted) in actual.zip(expected)) {
            assertEquals(0.0, found.start.timeIntervalSince(wanted.start), accuracy, "$message: start")
            assertEquals(0.0, found.end.timeIntervalSince(wanted.end), accuracy, "$message: end")
        }
    }

    /** The Sun's geometric altitude, from a frame built for the instant. */
    fun sunAltitude(date: Instant, observer: Observer): Double =
        SkyFrame(date, observer).horizontal(SolarSystemBody.SUN, false).altitude

    /**
     * A reproducible source of random numbers (SplitMix64) with the draws Swift's
     * `Double.random(in:using:)` makes from it, so the sequences match the Swift tests.
     */
    class SeededGenerator(seed: ULong) {
        private var state: ULong = seed

        fun next(): ULong {
            state += 0x9E37_79B9_7F4A_7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
            z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
            return z xor (z shr 31)
        }

        /** Swift `RandomNumberGenerator.next(upperBound:)` for `UInt64` (Lemire's multiply-and-shift). */
        private fun next(upperBound: ULong): ULong {
            var random = next()
            var low = random * upperBound
            var high = multiplyHigh(random, upperBound)
            if (low < upperBound) {
                val threshold = (0uL - upperBound) % upperBound
                while (low < threshold) {
                    random = next()
                    low = random * upperBound
                    high = multiplyHigh(random, upperBound)
                }
            }
            return high
        }

        private fun multiplyHigh(a: ULong, b: ULong): ULong {
            var high = Math.multiplyHigh(a.toLong(), b.toLong())
            if (a.toLong() < 0) high += b.toLong()
            if (b.toLong() < 0) high += a.toLong()
            return high.toULong()
        }

        /** Swift `Double.random(in: lower..<upper, using: &self)`. */
        fun nextDouble(lower: Double, upper: Double): Double {
            val delta = upper - lower
            while (true) {
                val significand = next() and ((1uL shl 53) - 1uL)
                val value = delta * (significand.toDouble() * UNIT) + lower
                if (value != upper) return value
            }
        }

        /** Swift `Double.random(in: lower...upper, using: &self)`. */
        fun nextDoubleInclusive(lower: Double, upper: Double): Double {
            val delta = upper - lower
            val significand = next((1uL shl 53) + 1uL)
            return delta * (significand.toDouble() * UNIT) + lower
        }

        private companion object {
            /** `Double.ulpOfOne / 2`. */
            const val UNIT: Double = 1.0 / 9_007_199_254_740_992.0
        }
    }
}

class PlanningNightSummaryTests {
    private val boulder = PlanningTestSupport.boulder

    // MARK: Structure

    @Test
    fun testSummaryCarriesTheTwilightOfTheNight() {
        val zone = boulder.timeZone()
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        val twilight = TwilightCalculator.night(PlanningTestSupport.localNoon(2026, 10, 25, zone), boulder.observer, zone)
        assertEquals(twilight, summary.twilight)
        assertEquals(twilight.interval, summary.night)
        assertEquals(boulder.observer, summary.observer)
        assertEquals(24.0 * 3_600, summary.night.duration, 0.001)
        assertEquals(listOf(assertNotNull(twilight.astronomicalNight)), summary.astronomicalDarknessIntervals)
        assertEquals(twilight.astronomicalDusk, summary.referenceDate)
        assertTrue(summary.hasAstronomicalDarkness)
        // Boulder, 25 October: dusk 19:38 and dawn 05:53 MDT, a little over ten hours of darkness.
        assertEquals(10.25, summary.astronomicalDarkHours, 0.05)
    }

    @Test
    fun testSummaryIsDeterministic() {
        assertEquals(PlanningTestSupport.summary(boulder, 2026, 6, 21), PlanningTestSupport.summary(boulder, 2026, 6, 21))
    }

    @Test
    fun testNightLengthFollowsDaylightSavingChanges() {
        val newYork = PlanningTestSupport.Site("New York", 40.71, -74.0, timeZoneIdentifier = "America/New_York")
        val zone = newYork.timeZone()
        for ((month, day, hours) in listOf(Triple(3, 7, 23.0), Triple(10, 31, 25.0), Triple(5, 5, 24.0))) {
            val summary = NightPlanner.summary(PlanningTestSupport.localNoon(2026, month, day, zone), newYork.observer, zone)
            assertEquals(hours, summary.night.duration / 3_600, 1e-6, "2026-$month-$day")
        }
    }

    // MARK: Darkness

    @Test
    fun testFullMoonNightHasNoMoonFreeDarkness() {
        // Full moon at 04:11 UTC on 26 October 2026: the Moon rises at 17:35 and sets at 08:06 the next
        // morning, covering the whole of the astronomical night (19:38 to 05:53).
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 25)
        assertEquals(MoonPhaseName.FULL_MOON, summary.moon.phase.name)
        assertTrue(summary.moon.illuminatedFraction > 0.99)
        assertEquals(1, summary.moon.upIntervals.size)
        assertTrue(summary.darknessIntervals.isEmpty())
        assertEquals(0.0, summary.moonFreeDarkHours)
        val astronomical = assertNotNull(summary.astronomicalDarknessIntervals.firstOrNull())
        val moonUp = assertNotNull(summary.moon.upIntervals.firstOrNull())
        assertTrue(moonUp.start <= astronomical.start)
        assertTrue(moonUp.end >= astronomical.end)
        assertEquals(moonUp.start, summary.moon.riseTransitSet.rise)
        assertEquals(moonUp.end, summary.moon.riseTransitSet.set)
    }

    @Test
    fun testNewMoonNightIsAsDarkAsTheAstronomicalNight() {
        // New moon at 15:50 UTC on 10 October 2026: the Moon sets with the Sun and is gone all night.
        val summary = PlanningTestSupport.summary(boulder, 2026, 10, 10)
        assertEquals(MoonPhaseName.NEW_MOON, summary.moon.phase.name)
        assertTrue(summary.moon.illuminatedFraction < 0.02)
        assertEquals(summary.astronomicalDarknessIntervals, summary.darknessIntervals)
        assertEquals(summary.astronomicalDarkHours, summary.moonFreeDarkHours, 1e-9)
        assertTrue(summary.moonFreeDarkHours > 9.5)
        // The Moon is above the horizon only before dusk and after dawn.
        val dusk = assertNotNull(summary.twilight.astronomicalDusk)
        val dawn = assertNotNull(summary.twilight.astronomicalDawn)
        for (interval in summary.moon.upIntervals) {
            assertTrue(interval.end <= dusk || interval.start >= dawn, "$interval")
        }
    }

    @Test
    fun testMoonSetBeginsTheMoonFreeHoursAndDawnEndsThem() {
        // 21 June 2026, a day after first quarter: the Moon sets at 00:58 MDT, 2.5 hours before dawn.
        val summary = PlanningTestSupport.summary(boulder, 2026, 6, 21)
        val dawn = assertNotNull(summary.twilight.astronomicalDawn)
        assertEquals(1, summary.darknessIntervals.size)
        val dark = assertNotNull(summary.darknessIntervals.firstOrNull())
        assertEquals(summary.moon.riseTransitSet.set, dark.start)
        assertEquals(dawn, dark.end)
        assertEquals(2.52, summary.moonFreeDarkHours, 0.02)
        assertEquals(4.87, summary.astronomicalDarkHours, 0.02)
        assertEquals(MoonPhaseName.FIRST_QUARTER, summary.moon.phase.name)
        assertEquals(0.53, summary.moon.illuminatedFraction, 0.03)
    }

    @Test
    fun testMoonRiseEndsTheMoonFreeHours() {
        // 7 July 2026, last quarter: the Moon rises after midnight, so the evening hours are dark.
        val summary = PlanningTestSupport.summary(boulder, 2026, 7, 7)
        val dusk = assertNotNull(summary.twilight.astronomicalDusk)
        val dark = assertNotNull(summary.darknessIntervals.firstOrNull())
        assertEquals(1, summary.darknessIntervals.size)
        assertEquals(dusk, dark.start)
        assertEquals(summary.moon.riseTransitSet.rise, dark.end)
        assertTrue(summary.moonFreeDarkHours > 1)
        assertTrue(summary.moonFreeDarkHours < summary.astronomicalDarkHours)
    }

    /**
     * Darkness is exactly "Sun below −18° and Moon's upper limb below the horizon", checked at random
     * instants against fresh `SkyFrame` calculations.
     */
    @Test
    fun testDarknessMatchesDirectSunAndMoonAltitudes() {
        data class Case(val site: PlanningTestSupport.Site, val year: Int, val month: Int, val day: Int)
        val cases = listOf(
            Case(boulder, 2026, 10, 25), Case(boulder, 2026, 6, 21), Case(boulder, 2026, 7, 7),
            Case(PlanningTestSupport.sydney, 2026, 6, 21), Case(PlanningTestSupport.sydney, 2026, 12, 21),
            Case(PlanningTestSupport.london, 2026, 9, 1), Case(PlanningTestSupport.quito, 2026, 3, 20),
            Case(PlanningTestSupport.paranal, 2026, 8, 12), Case(PlanningTestSupport.tromso, 2026, 12, 21),
        )
        val generator = PlanningTestSupport.SeededGenerator(20_261_017uL)
        for ((site, year, month, day) in cases) {
            val summary = PlanningTestSupport.summary(site, year, month, day)
            val boundaries = (summary.astronomicalDarknessIntervals + summary.moon.upIntervals + summary.darknessIntervals)
                .flatMap { listOf(it.start, it.end) }
            var checked = 0
            while (checked < 150) {
                val date = summary.night.start.addingTimeInterval(generator.nextDouble(0.0, summary.night.duration))
                if (!boundaries.all { abs(it.timeIntervalSince(date)) > 30 }) continue
                checked += 1
                val frame = SkyFrame(date, site.observer)
                val sunDark = frame.horizontal(SolarSystemBody.SUN, false).altitude < TwilightCalculator.astronomicalAltitude
                val moon = frame.state(SolarSystemBody.MOON)
                val semiDiameter = AstroMath.asinDeg(SolarSystemBody.MOON.meanRadiusKilometers / moon.distanceKilometers)
                val moonUp = frame.horizontal(SolarSystemBody.MOON, false).altitude + semiDiameter >
                    RiseSetCalculator.standardHorizonAltitude
                val label = "${site.name} $year-$month-$day at $date"
                assertEquals(sunDark, PlanningTestSupport.contains(summary.astronomicalDarknessIntervals, date), label)
                assertEquals(moonUp, PlanningTestSupport.contains(summary.moon.upIntervals, date), label)
                assertEquals(sunDark && !moonUp, PlanningTestSupport.contains(summary.darknessIntervals, date), label)
            }
        }
    }

    @Test
    fun testDarknessNestsInsideAstronomicalNightForEverySiteAndSeason() {
        val sites = listOf(
            boulder, PlanningTestSupport.sydney, PlanningTestSupport.london, PlanningTestSupport.quito,
            PlanningTestSupport.paranal, PlanningTestSupport.tromso,
        )
        for (site in sites) {
            for ((month, day) in listOf(1 to 15, 3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
                val summary = PlanningTestSupport.summary(site, 2026, month, day)
                val label = "${site.name} 2026-$month-$day"
                val astronomical = summary.twilight.astronomicalNight?.let { listOf(it) } ?: emptyList()
                assertEquals(astronomical, summary.astronomicalDarknessIntervals, label)
                assertEquals(summary.darknessIntervals, IntervalSet.intersection(summary.darknessIntervals, astronomical), label)
                assertTrue(IntervalSet.intersection(summary.darknessIntervals, summary.moon.upIntervals).isEmpty(), label)
                // Dark time plus moonlit astronomical night is the whole astronomical night.
                val moonlit = IntervalSet.intersection(astronomical, summary.moon.upIntervals)
                assertEquals(
                    IntervalSet.totalDuration(astronomical),
                    IntervalSet.totalDuration(summary.darknessIntervals) + IntervalSet.totalDuration(moonlit),
                    1e-6, label,
                )
                assertTrue(summary.moonFreeDarkHours <= summary.astronomicalDarkHours + 1e-9, label)
                assertEquals(!astronomical.isEmpty(), summary.hasAstronomicalDarkness, label)
                assertEquals(summary.astronomicalDarknessIntervals, summary.darkness(DarknessKind.ASTRONOMICAL))
                assertEquals(summary.darknessIntervals, summary.darkness(DarknessKind.MOON_FREE))
            }
        }
    }

    // MARK: High latitudes

    @Test
    fun testPolarSummerHasNoAstronomicalDarkness() {
        // Tromsø (69.65° N) on 21 June: the Sun never sets, let alone reaches −18°.
        val summary = PlanningTestSupport.summary(PlanningTestSupport.tromso, 2026, 6, 21)
        assertFalse(summary.hasAstronomicalDarkness)
        assertTrue(summary.astronomicalDarknessIntervals.isEmpty())
        assertTrue(summary.darknessIntervals.isEmpty())
        assertEquals(0.0, summary.astronomicalDarkHours)
        assertEquals(0.0, summary.moonFreeDarkHours)
        assertNull(summary.twilight.sunset)
        assertEquals(summary.twilight.solarMidnight, summary.referenceDate)
        // Everything that depends on darkness is empty, while the events still come out.
        val core = summary.milkyWayCore
        assertTrue(core.visibleIntervals.isEmpty())
        assertTrue(core.moonFreeVisibleIntervals.isEmpty())
        assertNull(core.bestTime)
        assertNull(core.maxAltitude)
        assertNull(core.bestAzimuth)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, core.riseTransitSet.circumstance, "Sagittarius A* never rises at 69.65° N")
        assertEquals(7, summary.planets.size)
        for (planet in summary.planets) {
            assertNull(planet.maxAltitudeDuringDarkness, planet.body.displayName)
            assertFalse(planet.visibleDuringDarkness, planet.body.displayName)
            assertNotNull(planet.magnitude, planet.body.displayName)
        }
        assertNotNull(summary.moon.riseTransitSet.transit)
    }

    @Test
    fun testPolarWinterNightsAreDarkButMoonlitWhenTheMoonStaysUp() {
        // Tromsø on 21 December 2026 (a day before full moon): astronomical darkness from 16:56 to 06:29
        // and a Moon that never sets.
        val summary = PlanningTestSupport.summary(PlanningTestSupport.tromso, 2026, 12, 21)
        assertTrue(summary.hasAstronomicalDarkness)
        assertEquals(13.55, summary.astronomicalDarkHours, 0.05)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, summary.moon.riseTransitSet.circumstance)
        assertEquals(listOf(summary.night), summary.moon.upIntervals)
        assertTrue(summary.darknessIntervals.isEmpty())
        assertEquals(0.0, summary.moonFreeDarkHours)
    }

    @Test
    fun testPolarNightFillsTheWholeWindowWithDarkness() {
        val pole = PlanningTestSupport.Site("Pole", 89.9, 0.0)
        val zone = pole.timeZone()
        val summary = NightPlanner.summary(PlanningTestSupport.localNoon(2026, 12, 21, zone), pole.observer, zone)
        assertEquals(listOf(summary.night), summary.astronomicalDarknessIntervals)
        assertEquals(24.0, summary.astronomicalDarkHours, 1e-6)
        assertEquals(summary.twilight.solarMidnight, summary.referenceDate)
        assertTrue(summary.planets.any { it.maxAltitudeDuringDarkness != null })
    }

    // MARK: Twilight

    @Test
    fun testTwilightEventsRunInOrderAtTheSunsAltitudes() {
        val sites = listOf(boulder, PlanningTestSupport.sydney, PlanningTestSupport.quito, PlanningTestSupport.paranal)
        for (site in sites) {
            for ((month, day) in listOf(1 to 15, 3 to 20, 6 to 21, 9 to 22, 12 to 21)) {
                val label = "${site.name} 2026-$month-$day"
                val summary = PlanningTestSupport.summary(site, 2026, month, day)
                val twilight = summary.twilight
                val events = listOf(
                    twilight.sunset, twilight.civilDusk, twilight.nauticalDusk, twilight.astronomicalDusk,
                    twilight.astronomicalDawn, twilight.nauticalDawn, twilight.civilDawn, twilight.sunrise,
                )
                val dates = events.map { assertNotNull(it, label) }
                assertEquals(dates.sorted(), dates, label)
                assertTrue(dates.all { it in summary.night }, label)
                for ((date, altitude) in dates.zip(listOf(-0.8333, -6.0, -12.0, -18.0, -18.0, -12.0, -6.0, -0.8333))) {
                    assertEquals(altitude, PlanningTestSupport.sunAltitude(date, site.observer), 0.01, label)
                }
                assertEquals(listOf(DateInterval(dates[3], dates[4])), summary.astronomicalDarknessIntervals, label)
                // Between the edges the Sun is below −18°, outside them above it.
                val middle = dates[3].addingTimeInterval(dates[4].timeIntervalSince(dates[3]) / 2)
                assertTrue(PlanningTestSupport.sunAltitude(middle, site.observer) < -18, label)
                assertTrue(PlanningTestSupport.sunAltitude(summary.night.start, site.observer) > -18, label)
                assertTrue(PlanningTestSupport.sunAltitude(summary.night.end, site.observer) > -18, label)
            }
        }
    }

    @Test
    fun testLondonInMidsummerHasNauticalButNoAstronomicalDarkness() {
        val summary = PlanningTestSupport.summary(PlanningTestSupport.london, 2026, 6, 21)
        assertFalse(summary.hasAstronomicalDarkness)
        assertNull(summary.twilight.astronomicalDusk)
        assertNull(summary.twilight.astronomicalDawn)
        assertNotNull(summary.twilight.nauticalDusk, "the Sun reaches −15° at midnight, past the nautical −12°")
        assertTrue(summary.darknessIntervals.isEmpty())
        assertEquals(summary.twilight.solarMidnight, summary.referenceDate)
        assertEquals(summary.twilight.solarMidnight, summary.moon.phase.date)
        assertEquals(0.0, summary.astronomicalDarkHours)
    }

    // MARK: The Moon

    @Test
    fun testMoonSummaryIsWhatTheMoonCalculatorsSayForTheNight() {
        for ((site, month, day) in listOf(Triple(boulder, 6, 21), Triple(PlanningTestSupport.sydney, 12, 21), Triple(PlanningTestSupport.tromso, 12, 21))) {
            val summary = PlanningTestSupport.summary(site, 2026, month, day)
            val label = "${site.name} 2026-$month-$day"
            assertEquals(MoonPhaseCalculator.info(summary.referenceDate), summary.moon.phase, label)
            assertEquals(summary.referenceDate, summary.moon.phase.date, label)
            assertEquals(summary.moon.phase.illuminatedFraction, summary.moon.illuminatedFraction, label)
            assertEquals(
                RiseSetCalculator.riseTransitSet(SolarSystemBody.MOON, site.observer, summary.night),
                summary.moon.riseTransitSet, label,
            )
            // The Moon is above the horizon exactly between its rises and sets.
            val crossings = RiseSetCalculator.crossings(SolarSystemBody.MOON, site.observer, summary.night)
            for (interval in summary.moon.upIntervals) {
                assertTrue(
                    interval.start == summary.night.start ||
                        crossings.any { it.time == interval.start && it.kind == AltitudeCrossing.Kind.RISING },
                    label,
                )
                assertTrue(
                    interval.end == summary.night.end ||
                        crossings.any { it.time == interval.end && it.kind == AltitudeCrossing.Kind.SETTING },
                    label,
                )
            }
        }
    }

    /**
     * At every New and Full Moon of 2026 the night that contains it is either all moonlit or all moon-free,
     * as seen from places where the sky gets dark every night of the year.
     */
    @Test
    fun testEveryNewAndFullMoonOf2026() {
        val start = instantFromTimeIntervalSince1970(1_767_225_600.0) // 2026-01-01T00:00:00Z
        val end = instantFromTimeIntervalSince1970(1_798_761_600.0) // 2027-01-01T00:00:00Z
        val syzygies = MoonPhaseCalculator.nextPhases(start, 60)
            .filter { it.date < end && (it.kind == PrincipalMoonPhase.NEW_MOON || it.kind == PrincipalMoonPhase.FULL_MOON) }
        assertEquals(12, syzygies.count { it.kind == PrincipalMoonPhase.NEW_MOON })
        assertEquals(13, syzygies.count { it.kind == PrincipalMoonPhase.FULL_MOON }, "2026 has Full Moons on 1 and 31 May")
        for (site in listOf(boulder, PlanningTestSupport.sydney)) {
            val zone = site.timeZone()
            for ((kind, date) in syzygies) {
                val summary = NightPlanner.summary(PlanningTestSupport.startOfNight(date, zone), site.observer, zone)
                val label = "${site.name} $kind $date"
                assertTrue(date in summary.night, label)
                assertTrue(summary.hasAstronomicalDarkness, label)
                if (kind == PrincipalMoonPhase.FULL_MOON) {
                    assertEquals(MoonPhaseName.FULL_MOON, summary.moon.phase.name, label)
                    assertTrue(summary.moon.illuminatedFraction > 0.99, label)
                    assertTrue(summary.darknessIntervals.isEmpty(), label)
                    assertEquals(0.0, summary.moonFreeDarkHours, label)
                } else {
                    assertEquals(MoonPhaseName.NEW_MOON, summary.moon.phase.name, label)
                    assertTrue(summary.moon.illuminatedFraction < 0.01, label)
                    assertEquals(summary.astronomicalDarknessIntervals, summary.darknessIntervals, label)
                    assertEquals(summary.astronomicalDarkHours, summary.moonFreeDarkHours, 1e-9, label)
                }
            }
        }
    }

    // MARK: Reference data

    /**
     * Sun and Moon events for Boulder from the U.S. Naval Observatory's Astronomical Applications API
     * (`rstt/oneday` for 2026-10-17, 40.00° N 105.27° W, UT), which gives times to the minute. The service
     * shares no code or data with LodestarKit.
     */
    @Test
    fun testSunAndMoonEventsAgreeWithTheNavalObservatory() {
        val midnightUT = instantFromTimeIntervalSince1970(1_792_195_200.0) // 2026-10-17T00:00:00Z
        fun universal(hour: Int, minute: Int): Instant = midnightUT.addingTimeInterval((hour * 3_600 + minute * 60).toDouble())

        // The night starting on 16 October holds the evening of the 16th (17 October in UT) and the morning after.
        val evening = PlanningTestSupport.summary(boulder, 2026, 10, 16)
        val twilight = evening.twilight
        assertEquals(0.0, assertNotNull(twilight.sunset).timeIntervalSince(universal(0, 20)), 60.0)
        assertEquals(0.0, assertNotNull(twilight.civilDusk).timeIntervalSince(universal(0, 47)), 60.0)
        assertEquals(0.0, assertNotNull(twilight.civilDawn).timeIntervalSince(universal(12, 47)), 60.0)
        assertEquals(0.0, assertNotNull(twilight.sunrise).timeIntervalSince(universal(13, 14)), 60.0)
        assertEquals(0.0, assertNotNull(evening.moon.riseTransitSet.set).timeIntervalSince(universal(4, 9)), 60.0)
        assertEquals(MoonPhaseName.WAXING_CRESCENT, evening.moon.phase.name, "USNO: Waxing Crescent, First Quarter on the 18th at 16:12 UT")

        // The Moon rises at 20:01 UT, in the afternoon of the 17th, so in the night starting on that date.
        val following = PlanningTestSupport.summary(boulder, 2026, 10, 17)
        assertEquals(0.0, assertNotNull(following.moon.riseTransitSet.rise).timeIntervalSince(universal(20, 1)), 60.0)
    }

    // MARK: Public initializers

    @Test
    fun testPublicInitializersKeepTheirValues() {
        val summary = PlanningTestSupport.summary(boulder, 2026, 12, 14)
        val moon = MoonSummary(summary.moon.phase, summary.moon.riseTransitSet, summary.moon.upIntervals)
        val core = summary.milkyWayCore
        val rebuiltCore = MilkyWayCoreVisibility(
            riseTransitSet = core.riseTransitSet, visibleIntervals = core.visibleIntervals,
            moonFreeVisibleIntervals = core.moonFreeVisibleIntervals, bestTime = core.bestTime,
            maxAltitude = core.maxAltitude, bestAzimuth = core.bestAzimuth,
        )
        val planets = summary.planets.map {
            PlanetVisibility(
                body = it.body, riseTransitSet = it.riseTransitSet, visibleIntervals = it.visibleIntervals,
                maxAltitudeDuringDarkness = it.maxAltitudeDuringDarkness, magnitude = it.magnitude,
            )
        }
        val rebuilt = NightSummary(
            observer = summary.observer, night = summary.night, twilight = summary.twilight,
            referenceDate = summary.referenceDate, moon = moon,
            astronomicalDarknessIntervals = summary.astronomicalDarknessIntervals,
            darknessIntervals = summary.darknessIntervals, milkyWayCore = rebuiltCore, planets = planets,
        )
        assertEquals(summary, rebuilt)
        assertEquals(summary.moon.phase.illuminatedFraction, moon.illuminatedFraction)
        assertEquals(summary.planets.map { it.visibleDuringDarkness }, planets.map { it.visibleDuringDarkness })

        val visibility = NightPlanner.visibility(SolarSystemBody.SATURN, summary)
        val rebuiltVisibility = TargetVisibility(
            riseTransitSet = visibility.riseTransitSet, minimumAltitude = visibility.minimumAltitude,
            maxAltitudeDuringDarkness = visibility.maxAltitudeDuringDarkness, bestTime = visibility.bestTime,
            intervalsAboveMinimum = visibility.intervalsAboveMinimum,
        )
        assertEquals(visibility, rebuiltVisibility)

        val instant = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)
        val sample = AltitudeSample(instant, 12.5, 270.0)
        assertEquals(instant, sample.date)
        assertEquals(12.5, sample.altitude)
        assertEquals(270.0, sample.azimuth)
        assertEquals(AltitudeSample(instant, 12.5, 270.0), sample)
        assertNotEquals(AltitudeSample(instant, 12.5, 271.0), sample)
    }

    // MARK: Odd input

    @Test
    fun testAnyInstantOfTheLocalDayGivesTheSameNight() {
        val zone = boulder.timeZone()
        val noon = PlanningTestSupport.localNoon(2026, 10, 25, zone)
        val reference = NightPlanner.summary(noon, boulder.observer, zone)
        for (hours in listOf(-11.5, -6.0, 0.0, 6.0, 11.5)) {
            val summary = NightPlanner.summary(noon.addingTimeInterval(hours * 3_600), boulder.observer, zone)
            assertEquals(reference, summary, "$hours h from noon")
        }
    }

    @Test
    fun testTheTimeZoneDecidesWhichNightIsMeant() {
        val utcZone = ZoneId.of("UTC")
        val instant = PlanningTestSupport.localNoon(2026, 10, 25, utcZone)
        val utc = NightPlanner.summary(instant, boulder.observer, utcZone)
        val denver = NightPlanner.summary(instant, boulder.observer, boulder.timeZone())
        assertEquals(instant, utc.night.start)
        assertNotEquals(denver.night, utc.night)
        assertEquals(24.0 * 3_600, denver.night.duration, 1e-6)
    }

    @Test
    fun testPlacesOnThePolesAndNonFiniteDatesStillGetASummary() {
        for (latitude in listOf(90.0, -90.0)) {
            val pole = PlanningTestSupport.Site("pole $latitude", latitude, 0.0)
            val summary = PlanningTestSupport.summary(pole, 2026, 12, 21)
            assertEquals(24.0 * 3_600, summary.night.duration, 1e-6)
            assertEquals(7, summary.planets.size)
            assertTrue(summary.astronomicalDarkHours == 0.0 || summary.astronomicalDarkHours == 24.0, "all or nothing at a pole")
            assertEquals(latitude > 0, summary.hasAstronomicalDarkness)
            assertTrue(summary.milkyWayCore.visibleIntervals.isEmpty() || latitude < 0)
        }
        // An Instant cannot be NaN: the Foundation helper turns the NaN date into the reference date.
        val broken = NightPlanner.summary(instantFromTimeIntervalSinceReferenceDate(Double.NaN), boulder.observer, boulder.timeZone())
        assertEquals(24.0 * 3_600, broken.night.duration, 3_601.0)
        val far = NightPlanner.summary(distantFuture, boulder.observer, boulder.timeZone())
        assertEquals(24.0 * 3_600, far.night.duration, 3_601.0)
    }

    // MARK: Moon intervals from horizon crossings

    @Test
    fun testUpIntervalsFollowTheHorizonCrossings() {
        val window = DateInterval(instantFromTimeIntervalSinceReferenceDate(0.0), 24.0 * 3_600)
        fun at(hours: Double): Instant = window.start.addingTimeInterval(hours * 3_600)
        fun span(from: Double, to: Double): DateInterval = DateInterval(at(from), at(to))
        fun rise(hours: Double): AltitudeCrossing = AltitudeCrossing(at(hours), AltitudeCrossing.Kind.RISING)
        fun set(hours: Double): AltitudeCrossing = AltitudeCrossing(at(hours), AltitudeCrossing.Kind.SETTING)
        fun up(crossings: List<AltitudeCrossing>, circumstance: RiseTransitSet.Circumstance, window: DateInterval) =
            NightPlanner.upIntervals(crossings, circumstance, window)

        assertEquals(listOf(window), up(emptyList(), RiseTransitSet.Circumstance.ALWAYS_UP, window))
        assertEquals(emptyList(), up(emptyList(), RiseTransitSet.Circumstance.ALWAYS_DOWN, window))
        assertEquals(listOf(span(5.0, 9.0)), up(listOf(rise(5.0), set(9.0)), RiseTransitSet.Circumstance.NORMAL, window))
        assertEquals(
            listOf(span(0.0, 3.0), span(10.0, 20.0)),
            up(listOf(set(3.0), rise(10.0), set(20.0)), RiseTransitSet.Circumstance.NORMAL, window),
        )
        assertEquals(listOf(span(15.0, 24.0)), up(listOf(rise(15.0)), RiseTransitSet.Circumstance.NORMAL, window))
        assertEquals(listOf(span(0.0, 7.0)), up(listOf(set(7.0)), RiseTransitSet.Circumstance.NORMAL, window))
        assertEquals(
            listOf(span(2.0, 4.0), span(10.0, 12.0)),
            up(listOf(rise(2.0), set(4.0), rise(10.0), set(12.0)), RiseTransitSet.Circumstance.NORMAL, window),
        )
    }
}

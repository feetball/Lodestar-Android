package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RiseSetCalculatorTests {
    private val origin = instantFromTimeIntervalSinceReferenceDate(800_000_000.0)

    private fun date(seconds: Double): Instant = origin.addingTimeInterval(seconds)

    /** 40 sin(2π t/day) + 10 degrees, a smooth day-long curve with known crossings. */
    private fun sinusoid(): (Instant) -> Double = { time ->
        40 * sin(2 * PI * time.timeIntervalSince(origin) / 86_400) + 10
    }

    // MARK: Generic crossings

    @Test
    fun testCrossingsOfASinusoidAreFoundExactly() {
        val threshold = RiseSetCalculator.standardHorizonAltitude
        val interval = DateInterval(origin, 2.0 * 86_400)
        val crossings = RiseSetCalculator.crossings(sinusoid(), threshold, interval, tolerance = 0.001)

        // sin θ = (h₀ − 10)/40. The curve starts at 10°, above the threshold, so it sets first (at
        // θ = π − θ₁) and rises again (at 2π + θ₁), twice in two days.
        val theta = asin((threshold - 10) / 40)
        val setting = (PI - theta) / (2 * PI) * 86_400
        val rising = (2 * PI + theta) / (2 * PI) * 86_400
        assertEquals(
            listOf(
                AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING,
                AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING,
            ),
            crossings.map { it.kind },
        )
        val times = crossings.map { it.time.timeIntervalSince(origin) }
        assertEquals(setting, times[0], 0.01)
        assertEquals(rising, times[1], 0.01)
        assertEquals(setting + 86_400, times[2], 0.01)
        assertEquals(rising + 86_400, times[3], 0.01)
    }

    @Test
    fun testCrossingsDependOnTheThresholdAndReportTheirKind() {
        val interval = DateInterval(origin, 86_400.0)
        val curve = sinusoid()
        // Above the maximum (50°) or below the minimum (−30°) nothing crosses.
        assertTrue(RiseSetCalculator.crossings(curve, 51.0, interval).isEmpty())
        assertTrue(RiseSetCalculator.crossings(curve, -31.0, interval).isEmpty())
        // The curve starts at 10° and rises first: a threshold of 30° is crossed up then down.
        val crossings = RiseSetCalculator.crossings(curve, 30.0, interval, tolerance = 0.001)
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            crossings.map { it.kind },
        )
        val theta = asin(0.5)
        assertEquals(theta / (2 * PI) * 86_400, crossings[0].time.timeIntervalSince(origin), 0.01)
        assertEquals((PI - theta) / (2 * PI) * 86_400, crossings[1].time.timeIntervalSince(origin), 0.01)
    }

    @Test
    fun testCulminationsAreTheLocalMaxima() {
        val interval = DateInterval(origin, 3.0 * 86_400)
        val culminations = RiseSetCalculator.upperCulminations(sinusoid(), interval, tolerance = 0.001)
        assertEquals(3, culminations.size)
        for ((index, culmination) in culminations.withIndex()) {
            assertEquals(21_600 + index.toDouble() * 86_400, culmination.time.timeIntervalSince(origin), 0.05)
            assertEquals(50.0, culmination.altitude, 1e-9)
        }
    }

    @Test
    fun testCulminationNearTheEndOfTheIntervalIsFound() {
        // The maximum lies 2 minutes before the end, between the last two samples; a window that
        // ends just after the peak, or starts just before it, must still report it.
        val peakTime = 21_600.0
        val curve = sinusoid()
        val endsJustAfter = DateInterval(date(peakTime - 4 * 3600), date(peakTime + 120))
        assertEquals(1, RiseSetCalculator.upperCulminations(curve, endsJustAfter).size)
        val startsJustBefore = DateInterval(date(peakTime - 120), date(peakTime + 4 * 3600))
        assertEquals(1, RiseSetCalculator.upperCulminations(curve, startsJustBefore).size)
        // A window that holds only the rising or only the falling part has no maximum inside.
        val rising = DateInterval(origin, date(peakTime - 60))
        assertTrue(RiseSetCalculator.upperCulminations(curve, rising).isEmpty())
        val falling = DateInterval(date(peakTime + 60), date(70_000.0))
        assertTrue(RiseSetCalculator.upperCulminations(curve, falling).isEmpty())
    }

    @Test
    fun testBriefExcursionBetweenSamplesIsStillFound() {
        // The curve peaks 0.02° above the threshold for about 8.6 minutes, centred between two samples
        // 10 minutes apart that both lie below it.
        val center = 1_800.0
        val bump: (Instant) -> Double = { current ->
            val offset = current.timeIntervalSince(origin) - center
            0.02 - 3e-7 * offset * offset
        }
        val interval = DateInterval(origin, 3.0 * 3600)
        val found = RiseSetCalculator.crossings(bump, 0.0, interval, tolerance = 0.001)
        val halfWidth = sqrt(0.02 / 3e-7)
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            found.map { it.kind },
        )
        assertEquals(center - halfWidth, found[0].time.timeIntervalSince(origin), 0.05)
        assertEquals(center + halfWidth, found[1].time.timeIntervalSince(origin), 0.05)

        // The mirror image: a dip of 0.02° below the threshold between samples that both lie above it.
        val dipFound = RiseSetCalculator.crossings({ -bump(it) }, 0.0, interval, tolerance = 0.001)
        assertEquals(
            listOf(AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING),
            dipFound.map { it.kind },
        )
        assertEquals(center - halfWidth, dipFound[0].time.timeIntervalSince(origin), 0.05)
        assertEquals(center + halfWidth, dipFound[1].time.timeIntervalSince(origin), 0.05)

        // A bump that stays 0.02° short of the threshold produces nothing.
        assertTrue(RiseSetCalculator.crossings({ bump(it) - 0.04 }, 0.0, interval).isEmpty())
    }

    // MARK: Generic rise/transit/set

    @Test
    fun testRiseTransitSetOfASinusoid() {
        val threshold = 0.0
        val interval = DateInterval(origin, 86_400.0)
        val result = RiseSetCalculator.riseTransitSet(sinusoid(), threshold, interval)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, result.circumstance)
        val theta = asin((threshold - 10) / 40)
        // The curve starts above the threshold, so it sets first and rises later in the window.
        val setting = (PI - theta) / (2 * PI) * 86_400
        val rising = (2 * PI + theta) / (2 * PI) * 86_400
        assertEquals(setting, assertNotNull(result.set).timeIntervalSince(origin), 0.5)
        assertEquals(rising, assertNotNull(result.rise).timeIntervalSince(origin), 0.5)
        assertEquals(21_600.0, assertNotNull(result.transit).timeIntervalSince(origin), 0.5)
        assertEquals(50.0, assertNotNull(result.transitAltitude), 1e-6)
    }

    @Test
    fun testAlwaysUpAndAlwaysDown() {
        val interval = DateInterval(origin, 86_400.0)
        val up = RiseSetCalculator.riseTransitSet(sinusoid(), -35.0, interval)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, up.circumstance)
        assertNull(up.rise)
        assertNull(up.set)
        assertEquals(50.0, assertNotNull(up.transitAltitude), 1e-6)
        val down = RiseSetCalculator.riseTransitSet(sinusoid(), 60.0, interval)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, down.circumstance)
        assertNull(down.rise)
        assertNull(down.set)
        // The culmination is reported even though the target never clears the horizon.
        assertEquals(50.0, assertNotNull(down.transitAltitude), 1e-6)
    }

    @Test
    fun testOnlyTheFirstOfRepeatedEventsIsReported() {
        val interval = DateInterval(origin, 3.0 * 86_400)
        val result = RiseSetCalculator.riseTransitSet(sinusoid(), 0.0, interval)
        val all = RiseSetCalculator.crossings(sinusoid(), 0.0, interval)
        assertEquals(6, all.size)
        assertEquals(all.firstOrNull { it.kind == AltitudeCrossing.Kind.SETTING }?.time, result.set)
        assertEquals(all.firstOrNull { it.kind == AltitudeCrossing.Kind.RISING }?.time, result.rise)
        assertEquals(21_600.0, assertNotNull(result.transit).timeIntervalSince(origin), 0.5)
    }

    // MARK: Degenerate input

    @Test
    fun testDegenerateIntervalsAndFunctions() {
        val curve = sinusoid()
        val empty = DateInterval(origin, 0.0)
        assertTrue(RiseSetCalculator.crossings(curve, 0.0, empty).isEmpty())
        assertTrue(RiseSetCalculator.upperCulminations(curve, empty).isEmpty())
        val point = RiseSetCalculator.riseTransitSet(curve, 0.0, empty)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, point.circumstance)
        assertNull(point.transit)

        // Values that are not finite are skipped rather than trapping or producing crossings.
        val broken: (Instant) -> Double = { Double.NaN }
        val interval = DateInterval(origin, 86_400.0)
        assertTrue(RiseSetCalculator.crossings(broken, 0.0, interval).isEmpty())
        assertTrue(RiseSetCalculator.upperCulminations(broken, interval).isEmpty())
        val partlyBroken: (Instant) -> Double = { current ->
            if (current.timeIntervalSince(origin) < 3_000) Double.NaN else curve(current)
        }
        val crossings = RiseSetCalculator.crossings(partlyBroken, 30.0, interval)
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            crossings.map { it.kind },
        )
    }

    @Test
    fun testSampleIntervalAndToleranceAreClamped() {
        val interval = DateInterval(origin, 3.0 * 3600)
        // Period 6000 s: rising at 192 s and 6192 s, setting at 2808 s and 8808 s.
        val curve: (Instant) -> Double = { current -> 25 * sin(2 * PI * current.timeIntervalSince(origin) / 6_000) }
        val reference = RiseSetCalculator.crossings(curve, 5.0, interval, tolerance = 0.001)
        assertEquals(
            listOf(
                AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING,
                AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING,
            ),
            reference.map { it.kind },
        )
        // Absurd arguments fall back to sensible values instead of failing.
        for (step in listOf(-5.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, 1e12)) {
            val result = RiseSetCalculator.crossings(curve, 5.0, interval, sampleInterval = step, tolerance = 0.001)
            assertEquals(reference.map { it.kind }, result.map { it.kind }, "step $step")
        }
        val loose = RiseSetCalculator.crossings(curve, 5.0, interval, tolerance = Double.NaN)
        assertEquals(4, loose.size)
        assertEquals(
            reference[0].time.timeIntervalSince(origin), loose[0].time.timeIntervalSince(origin), 0.5,
        )
    }

    @Test
    fun testVeryLongIntervalsAreSampledCoarsely() {
        // At most 100 000 samples whatever the interval: a thousand years completes quickly.
        val interval = DateInterval(origin, 1_000 * 365.25 * 86_400)
        val curve: (Instant) -> Double = { current -> sin(current.timeIntervalSince(origin) / 3e9) }
        val crossings = RiseSetCalculator.crossings(curve, 0.0, interval)
        assertEquals(
            listOf(AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            crossings.map { it.kind },
        )
        assertEquals(PI * 3e9, crossings[0].time.timeIntervalSince(origin), 1_000.0)
    }

    @Test
    fun testAbsurdDatesAreHandledWithoutTrapping() {
        val observer = Observer(GeographicLocation(45.0, 10.0))
        for (seconds in listOf(1e300, -1e300, 1e18, -1e18, 1e14)) {
            val interval = DateInterval(instantFromTimeIntervalSinceReferenceDate(seconds), 86_400.0)
            assertTrue(RiseSetCalculator.crossings(SolarSystemBody.SUN, observer, interval).isEmpty())
            assertTrue(RiseSetCalculator.upperCulminations(SolarSystemBody.MOON, observer, interval).isEmpty())
            val star = EquatorialCoordinate(10.0, 20.0)
            assertTrue(RiseSetCalculator.crossings(star, observer = observer, interval = interval).isEmpty())
            val summary = RiseSetCalculator.riseTransitSet(SolarSystemBody.JUPITER, observer, interval)
            assertNull(summary.rise)
            assertNull(summary.transit)
        }
        val night = TwilightCalculator.night(
            instantFromTimeIntervalSinceReferenceDate(1e300), observer, ZoneOffset.UTC,
        )
        assertEquals(86_400.0, night.interval.duration, 1.0)
        val nan = TwilightCalculator.night(
            instantFromTimeIntervalSinceReferenceDate(Double.NaN), observer, ZoneOffset.UTC,
        )
        assertEquals(86_400.0, nan.interval.duration, 1.0)
    }

    // MARK: Meeus example 15.a

    /**
     * Venus at Boston (φ = +42°20′, λ = 71°05′ W) on 1988 March 20: the book finds
     * rising 12h25m26s, transit 19h40m31s and setting 2h54m40s UT; DE440s gives 12:25:26,
     * 19:40:39 and 02:54:39, and a transit altitude of 66.424°.
     */
    @Test
    fun testVenusRiseTransitSetMeeusExample15a() {
        val boston = Observer(GeographicLocation(42.3333, -71.0833))
        val midnight = JulianDate(JulianDate.julianDay(1988, 3, 20))
        val start = midnight.date
        val day = DateInterval(start, 86_400.0)
        val result = RiseSetCalculator.riseTransitSet(SolarSystemBody.VENUS, boston, day)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, result.circumstance)
        val hours = { date: Instant? -> assertNotNull(date).timeIntervalSince(start) / 3600 }
        assertEquals(12 + 25.43 / 60, hours(result.rise), 10.0 / 3600)
        assertEquals(19 + 40.65 / 60, hours(result.transit), 15.0 / 3600)
        assertEquals(2 + 54.66 / 60, hours(result.set), 10.0 / 3600)
        assertEquals(66.424, assertNotNull(result.transitAltitude), 0.01)
    }

    // MARK: Fixed objects

    @Test
    fun testStarRisingTwiceInOneWindow() {
        // A star returns to the same place in a sidereal day, 3 min 56 s short of 24 hours, so a
        // window that starts just before it rises holds two risings.
        val observer = Observer(GeographicLocation(0.0, 0.0))
        val star = EquatorialCoordinate(101.28715539, -16.71611582)
        val probe = DateInterval(origin, 86_400.0)
        val firstRise = assertNotNull(
            RiseSetCalculator.riseTransitSet(star, observer = observer, interval = probe).rise,
        )
        val window = DateInterval(firstRise.addingTimeInterval(-60.0), 86_400.0)
        val crossings = RiseSetCalculator.crossings(star, observer = observer, interval = window)
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING),
            crossings.map { it.kind },
        )
        val siderealDay = 86_164.0905
        assertEquals(siderealDay, crossings[2].time.timeIntervalSince(crossings[0].time), 1.0)
        val culminations = RiseSetCalculator.upperCulminations(star, observer = observer, interval = window)
        assertEquals(1, culminations.size)
        val first = RiseSetCalculator.riseTransitSet(star, observer = observer, interval = window)
        assertEquals(crossings[0].time, first.rise)
        assertEquals(crossings[1].time, first.set)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, first.circumstance)
        // At the equator a star of declination −16.7° culminates at altitude 90° − 16.7°.
        assertEquals(90 - 16.716, assertNotNull(first.transitAltitude), 0.06)
    }

    @Test
    fun testCircumpolarAndNeverRisingStars() {
        val observer = Observer(GeographicLocation(50.0, 8.0))
        val window = DateInterval(instantFromTimeIntervalSince1970(1_790_000_000.0), 86_400.0)
        val polaris = EquatorialCoordinate(37.95451535, 89.26410951)
        val circumpolar = RiseSetCalculator.riseTransitSet(polaris, observer = observer, interval = window)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, circumpolar.circumstance)
        assertNull(circumpolar.rise)
        assertNull(circumpolar.set)
        // Its upper culmination is 90° − |φ − δ| above the horizon, with δ the apparent declination.
        val frame = SkyFrame(window.start, observer)
        val apparent = frame.apparentPlace(polaris, null)
        assertEquals(90 - (apparent.declination - 50), assertNotNull(circumpolar.transitAltitude), 0.005)

        val acrux = EquatorialCoordinate(186.64956584, -63.09909166)
        val invisible = RiseSetCalculator.riseTransitSet(acrux, observer = observer, interval = window)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, invisible.circumstance)
        assertNull(invisible.rise)
        assertNull(invisible.set)
        assertNotNull(invisible.transit)
    }

    @Test
    fun testProperMotionMovesTheEventTimes() {
        val observer = Observer(GeographicLocation(45.0, 0.0))
        val star = EquatorialCoordinate(213.9153, 19.1824)
        val window = DateInterval(instantFromTimeIntervalSince1970(1_790_000_000.0), 86_400.0)
        val still = assertNotNull(
            RiseSetCalculator.riseTransitSet(star, observer = observer, interval = window).transit,
        )
        // 100 arcseconds per year in right ascension over 26.7 years: 2670″ = 178 s of time.
        val motion = ProperMotion(100_000 * AstroMath.cosDeg(19.1824), 0.0)
        val movingStar = RiseSetCalculator.riseTransitSet(star, motion, observer, window)
        val moving = assertNotNull(movingStar.transit)
        assertTrue(moving.timeIntervalSince(still) > 170)
        assertTrue(moving.timeIntervalSince(still) < 186)
    }

    // MARK: Solar-system bodies

    @Test
    fun testPolarDayAndNightForTheSun() {
        val tromso = Observer(GeographicLocation(69.65, 18.96))
        // 2026-06-21
        val midsummer = DateInterval(instantFromTimeIntervalSince1970(1_782_000_000.0), 86_400.0)
        val sunUp = RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, tromso, midsummer)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, sunUp.circumstance)
        assertNull(sunUp.rise)
        assertNull(sunUp.set)
        // Culmination at 90° − (69.65° − 23.44°) = 43.8°; the lowest point is 3.0° above the horizon.
        assertEquals(43.8, assertNotNull(sunUp.transitAltitude), 0.2)

        // 2026-12-14
        val midwinter = DateInterval(instantFromTimeIntervalSince1970(1_797_000_000.0), 86_400.0)
        val sunDown = RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, tromso, midwinter)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, sunDown.circumstance)
        assertTrue(assertNotNull(sunDown.transitAltitude) < -0.8333)
    }

    @Test
    fun testMoonRisesBetweenTheTwoDefinitionsOfItsHorizon() {
        // The event is the upper limb at the horizon (centre at −0.5667° − semi-diameter, seen from the
        // surface). Meeus' threshold 0.7275π − 0.5667° applies to the geocentric altitude of the
        // centre and gives the same instants to within seconds.
        val observer = Observer(GeographicLocation(48.2, 16.4, 200.0))
        val interval = DateInterval(instantFromTimeIntervalSince1970(1_790_000_000.0), 2.0 * 86_400)
        val events = RiseSetCalculator.crossings(SolarSystemBody.MOON, observer, interval)
        assertTrue(events.size >= 3)

        val geocentricMargin: (Instant) -> Double = { date ->
            val frame = SkyFrame(date, observer)
            val state = frame.state(SolarSystemBody.MOON)
            val direction = frame.equatorialOfDateToHorizontal *
                (frame.precessionNutationMatrix * state.geocentricJ2000)
            val parallax = AstroMath.asinDeg(SkyFrame.earthEquatorialRadiusKilometers / state.distanceKilometers)
            HorizontalCoordinate(direction).altitude - (0.7275 * parallax - 0.5667)
        }
        val meeus = RiseSetCalculator.crossings(geocentricMargin, 0.0, interval)
        assertEquals(events.map { it.kind }, meeus.map { it.kind })
        for ((mine, theirs) in events.zip(meeus)) {
            assertEquals(0.0, mine.time.timeIntervalSince(theirs.time), 15.0)
        }
    }

    @Test
    fun testBodyEvaluatorMatchesADirectSkyFrameComputation() {
        // The searches use cached frames and interpolated ephemerides; they agree with a fresh
        // SkyFrame to a few hundredths of an arcsecond.
        val observer = Observer(GeographicLocation(-33.9, 151.2, 30.0))
        val start = instantFromTimeIntervalSince1970(1_790_000_000.0).timeIntervalSinceReferenceDate
        for (body in SolarSystemBody.entries) {
            val sampler = RiseSetSampler(RiseSetSampler.Target.Body(body), observer)
            for (step in 0 until 60) {
                val time = start + step.toDouble() * 1_777.7
                val direct = SkyFrame(instantFromTimeIntervalSinceReferenceDate(time), observer)
                    .horizontal(body, false).altitude
                assertEquals(direct, sampler.sample(time).centreAltitude, 2e-5, "$body at step $step")
            }
        }
        val star = EquatorialCoordinate(88.79293860, 7.40706274)
        val sampler = RiseSetSampler(RiseSetSampler.Target.Fixed(star, null), observer)
        for (step in 0 until 60) {
            val time = start + step.toDouble() * 1_777.7
            val direct = SkyFrame(instantFromTimeIntervalSinceReferenceDate(time), observer)
                .horizontal(star, null, false).altitude
            assertEquals(direct, sampler.sample(time).centreAltitude, 2e-5, "star at step $step")
        }
        assertTrue(sampler.sample(Double.NaN).centreAltitude.isNaN())
    }

    @Test
    fun testMoonLimbAllowanceIsItsApparentSemiDiameter() {
        val observer = Observer(GeographicLocation(10.0, 20.0))
        val time = instantFromTimeIntervalSince1970(1_790_000_000.0).timeIntervalSinceReferenceDate
        val sample = RiseSetSampler(RiseSetSampler.Target.Body(SolarSystemBody.MOON), observer).sample(time)
        // The Moon's semi-diameter is between 14.7′ and 16.7′ for any distance and viewpoint.
        assertTrue(sample.limbAllowance in 0.24..0.28, "semi-diameter ${sample.limbAllowance}°")
        assertEquals(sample.centreAltitude + sample.limbAllowance, sample.limbAltitude, 1e-12)
        val sun = RiseSetSampler(RiseSetSampler.Target.Body(SolarSystemBody.SUN), observer).sample(time)
        assertEquals(0.0, sun.limbAllowance)
    }
}

package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.HorizonTestSupport as Support
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The visibility searches against the flat-horizon [RiseSetCalculator], against closed-form geometry (a star at
 * the equator rises along a line of constant azimuth) and against a brute-force scan with full [SkyFrame]s.
 */
class HorizonVisibilityTests {
    private val flat = HorizonVisibility(HorizonProfile.flat)

    /** Degrees per hour at which the Earth turns relative to the stars. */
    private val siderealRate = 360.985_647_366_29 / 24.0

    private val stars: List<Pair<String, EquatorialCoordinate>> = listOf(
        "Betelgeuse" to EquatorialCoordinate.fromRightAscensionHours(5.9195, 7.407),
        "Sirius" to EquatorialCoordinate.fromRightAscensionHours(6.7525, -16.716),
        "Polaris" to EquatorialCoordinate.fromRightAscensionHours(2.5303, 89.264),
        "Vega" to EquatorialCoordinate.fromRightAscensionHours(18.6156, 38.784),
        "Sagittarius A*" to EquatorialCoordinate.fromRightAscensionHours(17.7611, -29.0078),
        "Alpha Centauri" to EquatorialCoordinate.fromRightAscensionHours(14.6600, -60.835),
    )

    private val day = DateInterval(Instant.parse("2026-06-20T00:00:00Z"), 86_400.0)

    private fun assertSameEvents(expected: RiseTransitSet, actual: RiseTransitSet, accuracy: Double, message: String) {
        assertEquals(expected.circumstance, actual.circumstance, message)
        for ((name, pair) in listOf(
            "rise" to (expected.rise to actual.rise),
            "transit" to (expected.transit to actual.transit),
            "set" to (expected.set to actual.set),
        )) {
            val (wanted, found) = pair
            if (wanted == null) {
                assertEquals(null, found, "$message $name")
            } else {
                assertNotNull(found, "$message $name")
                assertEquals(0.0, found.timeIntervalSince(wanted), accuracy, "$message $name")
            }
        }
        val wanted = expected.transitAltitude
        val found = actual.transitAltitude
        if (wanted == null) assertEquals(null, found) else assertEquals(wanted, assertNotNull(found), 1e-6, message)
    }

    // MARK: A flat profile is the flat horizon

    @Test
    fun testFlatProfileMatchesTheRiseSetCalculatorForStars() {
        for (observer in listOf(Support.boulder, Support.equator, Support.sydney, Support.tromso)) {
            for ((name, position) in stars) {
                val expected = RiseSetCalculator.riseTransitSet(position, null, observer, day)
                val actual = flat.riseTransitSet(position, null, observer, day)
                assertSameEvents(expected, actual, 0.5, "$name at ${observer.location}")
                val wantedCrossings = RiseSetCalculator.crossings(position, null, observer, day)
                val foundCrossings = flat.crossings(position, null, observer, day)
                assertEquals(wantedCrossings.map { it.kind }, foundCrossings.map { it.kind }, name)
                for ((wanted, found) in wantedCrossings.zip(foundCrossings)) {
                    assertEquals(0.0, found.time.timeIntervalSince(wanted.time), 0.5, name)
                }
            }
        }
    }

    @Test
    fun testFlatProfileMatchesTheRiseSetCalculatorForTheSunMoonAndPlanets() {
        val dates = listOf("2026-03-20T00:00:00Z", "2026-06-20T00:00:00Z", "2026-12-21T00:00:00Z")
        for (observer in listOf(Support.boulder, Support.tromso, Support.sydney)) {
            for (date in dates) {
                val interval = DateInterval(Instant.parse(date), 86_400.0)
                for (body in SolarSystemBody.entries) {
                    val message = "$body at ${observer.location.latitude} on $date"
                    assertSameEvents(
                        RiseSetCalculator.riseTransitSet(body, observer, interval),
                        flat.riseTransitSet(body, observer, interval),
                        0.5, message,
                    )
                    val wanted = RiseSetCalculator.crossings(body, observer, interval)
                    val found = flat.crossings(body, observer, interval)
                    assertEquals(wanted.map { it.kind }, found.map { it.kind }, message)
                    for ((expected, actual) in wanted.zip(found)) {
                        assertEquals(0.0, actual.time.timeIntervalSince(expected.time), 0.5, message)
                    }
                }
            }
        }
    }

    @Test
    fun testFlatProfileWindowsAreTheTimeAboveTheHorizon() {
        val observer = Support.boulder
        val (_, position) = stars[0]
        val windows = flat.visibleWindows(position, null, observer, day)
        val crossings = RiseSetCalculator.crossings(position, null, observer, day)
        // Betelgeuse at 40°N, 20 June: up at the start of the UTC day (evening in Boulder), sets, rises again.
        assertEquals(2, windows.size)
        assertEquals(day.start, windows[0].start)
        assertEquals(0.0, windows[0].end.timeIntervalSince(crossings[0].time), 0.5)
        assertEquals(0.0, windows[1].start.timeIntervalSince(crossings[1].time), 0.5)
        assertEquals(day.end, windows[1].end)
        // A star that never sets is one window, a star that never rises none.
        val polaris = stars[2].second
        assertEquals(listOf(day), flat.visibleWindows(polaris, null, observer, day))
        assertTrue(flat.visibleWindows(polaris, null, Support.sydney, day).isEmpty())
    }

    @Test
    fun testMinimumAltitudeRaisesTheEdgesOfTheWindows() {
        val observer = Support.boulder
        val (_, position) = stars[3]
        val altitude: (Instant) -> Double = { SkyFrame(it, observer).horizontal(position, null, false).altitude }
        val expected = RiseSetCalculator.crossings(altitude, 20.0, day, tolerance = 0.01)
        val windows = flat.visibleWindows(position, null, observer, day, minimumAltitude = 20.0)
        var starts = expected.filter { it.kind == AltitudeCrossing.Kind.RISING }.map { it.time }
        var ends = expected.filter { it.kind == AltitudeCrossing.Kind.SETTING }.map { it.time }
        if (altitude(day.start) >= 20.0) starts = listOf(day.start) + starts
        if (altitude(day.end) >= 20.0) ends = ends + listOf(day.end)
        assertEquals(starts.size, windows.size)
        assertEquals(ends.size, windows.size)
        for (index in windows.indices) {
            assertEquals(0.0, windows[index].start.timeIntervalSince(starts[index]), 0.5)
            assertEquals(0.0, windows[index].end.timeIntervalSince(ends[index]), 0.5)
        }
        // The window under a higher minimum is inside the window under the horizon.
        val lower = flat.visibleWindows(position, null, observer, day)
        for (window in windows) assertTrue(lower.any { it.start <= window.start && it.end >= window.end })
        // Under the horizon-only search the window is longer.
        assertTrue(lower.sumOf { it.duration } > windows.sumOf { it.duration } + 3_600)
    }

    @Test
    fun testFlatProfileIsVisibleExactlyWhileAboveTheStandardHorizon() {
        val observer = Support.boulder
        val position = stars[1].second
        for (step in 0 until 48) {
            val time = day.start.addingTimeInterval(step * 1_800.0 + 77)
            val geometric = SkyFrame(time, observer).horizontal(position, null, false).altitude
            assertEquals(geometric >= RiseSetCalculator.standardHorizonAltitude, flat.isVisible(position, null, observer, time), "$step")
        }
        assertTrue(flat.isVisible(0.0, 123.0))
        assertTrue(flat.isVisible(-0.5667, 10.0))
        assertFalse(flat.isVisible(-0.5668, 10.0))
        assertTrue(flat.isVisible(0.0, 0.0, refracted = true))
        assertFalse(flat.isVisible(-0.1, 0.0, refracted = true))
    }

    // MARK: Mountains

    /** The star of the closed-form tests: on the celestial equator at 6 h, which at the equator rises due east. */
    private val equatorStar = EquatorialCoordinate.fromRightAscensionHours(6.0, 0.0)

    @Test
    fun testAMountainToTheEastDelaysAStarsRiseByTheExpectedAmount() {
        val observer = Support.equator
        val mountain = HorizonVisibility(Support.wall(10.0, 45.0, 135.0))
        val flatRise = assertNotNull(RiseSetCalculator.riseTransitSet(equatorStar, null, observer, day).rise)
        val events = mountain.riseTransitSet(equatorStar, null, observer, day)
        val cleared = assertNotNull(events.rise)

        // At the equator a star of declination 0 climbs along its azimuth, 90°, at 15.041° per hour: altitude = 90° − |H|.
        // It must climb from the horizon (−0.5667°) to the geometric altitude at which refraction lifts it to 10°.
        val threshold = Refraction.trueAltitude(10.0)
        assertEquals(HorizonVisibility.thresholdAltitude(10.0), threshold, 0.005) // the same 10° skyline through the library's refraction
        val expected = (HorizonVisibility.thresholdAltitude(10.0) - RiseSetCalculator.standardHorizonAltitude) / siderealRate * 3_600
        assertEquals(2_508.0, expected, 1.0)
        assertEquals(expected, cleared.timeIntervalSince(flatRise), 2.0)

        // Independent checks with full frames: at that moment the star's refracted altitude is the skyline's, at 90°.
        val frame = SkyFrame(cleared, observer)
        val apparent = frame.horizontal(equatorStar, null, true)
        assertEquals(10.0, apparent.altitude, 0.01)
        assertEquals(90.0, apparent.azimuth, 0.1)
        val before = SkyFrame(cleared.addingTimeInterval(-5.0), observer).horizontal(equatorStar, null, true)
        assertTrue(before.altitude < 10.0)
        assertFalse(mountain.isVisible(equatorStar, null, observer, cleared.addingTimeInterval(-5.0)))
        assertTrue(mountain.isVisible(equatorStar, null, observer, cleared.addingTimeInterval(5.0)))

        // It sets in the west, where the skyline is flat: the set is the flat set, and the circumstance normal.
        val flatEvents = RiseSetCalculator.riseTransitSet(equatorStar, null, observer, day)
        assertEquals(AltitudeCrossing.Kind.RISING, mountain.crossings(equatorStar, null, observer, day).first().kind)
        assertEquals(0.0, assertNotNull(events.set).timeIntervalSince(assertNotNull(flatEvents.set)), 0.5)
        assertEquals(0.0, assertNotNull(events.transit).timeIntervalSince(assertNotNull(flatEvents.transit)), 0.5)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, events.circumstance)
    }

    @Test
    fun testAMountainToTheWestMakesAStarSinkBehindItEarlier() {
        val observer = Support.equator
        val mountain = HorizonVisibility(Support.wall(15.0, 225.0, 315.0))
        val flatEvents = RiseSetCalculator.riseTransitSet(equatorStar, null, observer, day)
        val events = mountain.riseTransitSet(equatorStar, null, observer, day)
        val lead = (HorizonVisibility.thresholdAltitude(15.0) - RiseSetCalculator.standardHorizonAltitude) / siderealRate * 3_600
        assertEquals(lead, assertNotNull(flatEvents.set).timeIntervalSince(assertNotNull(events.set)), 2.0)
        assertEquals(0.0, assertNotNull(events.rise).timeIntervalSince(assertNotNull(flatEvents.rise)), 0.5)
        // The windows are the time between.
        val windows = mountain.visibleWindows(equatorStar, null, observer, day)
        assertEquals(1, windows.size)
        assertEquals(0.0, windows[0].start.timeIntervalSince(assertNotNull(events.rise)), 0.5)
        assertEquals(0.0, windows[0].end.timeIntervalSince(assertNotNull(events.set)), 0.5)
    }

    @Test
    fun testADelayForAStarThatRisesNorthOfEast() {
        val observer = Support.equator
        // Declination +20° at the equator: it rises at azimuth 70° and keeps going to the north-east.
        val star = EquatorialCoordinate.fromRightAscensionHours(6.0, 20.0)
        val mountain = HorizonVisibility(Support.wall(10.0, 40.0, 100.0))
        val flatRise = assertNotNull(RiseSetCalculator.riseTransitSet(star, null, observer, day).rise)
        val cleared = assertNotNull(mountain.riseTransitSet(star, null, observer, day).rise)
        // sin(altitude) = cos(declination) · cos(hour angle) at the equator.
        fun hourAngleBefore(altitude: Double): Double =
            AstroMath.acosDeg(AstroMath.sinDeg(altitude) / AstroMath.cosDeg(20.0))
        val expected = (hourAngleBefore(RiseSetCalculator.standardHorizonAltitude) -
            hourAngleBefore(HorizonVisibility.thresholdAltitude(10.0))) / siderealRate * 3_600
        assertEquals(expected, cleared.timeIntervalSince(flatRise), 3.0)
        assertEquals(10.0, SkyFrame(cleared, observer).horizontal(star, null, true).altitude, 0.01)
        assertEquals(69.7, SkyFrame(cleared, observer).horizontal(star, null, true).azimuth, 0.3)
    }

    @Test
    fun testAMountainOffTheStarsPathChangesNothing() {
        val observer = Support.equator
        val star = EquatorialCoordinate.fromRightAscensionHours(6.0, 20.0)
        val mountain = HorizonVisibility(Support.wall(40.0, 100.0, 140.0))
        assertSameEvents(
            RiseSetCalculator.riseTransitSet(star, null, observer, day),
            mountain.riseTransitSet(star, null, observer, day),
            0.5, "behind the mountain's side",
        )
    }

    @Test
    fun testTheSunRisesLaterBehindATenDegreeSkyline() {
        val observer = Support.boulder
        val morning = DateInterval(Instant.parse("2026-03-20T08:00:00Z"), 8 * 3_600.0)
        val flatEvents = RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, observer, morning)
        val flatRise = assertNotNull(flatEvents.rise)
        val ridge = HorizonVisibility(Support.wall(10.0, 60.0, 120.0))
        val events = ridge.riseTransitSet(SolarSystemBody.SUN, observer, morning)
        val cleared = assertNotNull(events.rise)

        // Near the equinox the Sun's declination is 0 and sin(altitude) = cos(latitude) · cos(hour angle), at 15° per hour.
        // The upper limb must reach 10° apparent, i.e. the centre 0.2666° lower than a star would have to be.
        val sunThreshold = HorizonVisibility.thresholdAltitude(10.0) + (RiseSetCalculator.sunHorizonAltitude - RiseSetCalculator.standardHorizonAltitude)
        fun hourAngleBefore(altitude: Double): Double =
            AstroMath.acosDeg(AstroMath.sinDeg(altitude) / AstroMath.cosDeg(40.0))
        val expected = (hourAngleBefore(RiseSetCalculator.sunHorizonAltitude) - hourAngleBefore(sunThreshold)) / 15.0 * 3_600
        assertEquals(54.9 * 60, expected, 30.0)
        assertEquals(expected, cleared.timeIntervalSince(flatRise), 60.0)

        // A scan with full frames: the first second at which the refracted upper limb stands at 10° (the Sun is at 90° azimuth).
        var scan = flatRise
        val limb = { time: Instant ->
            val state = Ephemeris.state(SolarSystemBody.SUN, JulianDate(time))
            SkyFrame(time, observer).horizontal(SolarSystemBody.SUN, true).altitude + state.angularDiameterArcseconds / 7_200
        }
        while (limb(scan) < 10.0) scan = scan.addingTimeInterval(1.0)
        assertEquals(0.0, cleared.timeIntervalSince(scan), 3.0)
        // Climbing from due east it has turned 8° towards the south by then, still in front of the skyline.
        assertEquals(98.2, SkyFrame(cleared, observer).horizontal(SolarSystemBody.SUN, true).azimuth, 0.5)

        // The skyline is flat in the west: the Sun sets when it does without it.
        val evening = DateInterval(Instant.parse("2026-03-21T00:00:00Z"), 8 * 3_600.0)
        val flatSet = assertNotNull(RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, observer, evening).set)
        assertEquals(0.0, assertNotNull(ridge.riseTransitSet(SolarSystemBody.SUN, observer, evening).set).timeIntervalSince(flatSet), 0.5)
        // The day's window runs from the clearing to the sunset.
        val dayWindows = ridge.visibleWindows(SolarSystemBody.SUN, observer, DateInterval(morning.start, 20 * 3_600.0))
        assertEquals(1, dayWindows.size)
        assertEquals(0.0, dayWindows[0].start.timeIntervalSince(cleared), 0.5)
        assertEquals(0.0, dayWindows[0].end.timeIntervalSince(flatSet), 0.5)
    }

    @Test
    fun testTheMoonRisesBehindARidgeLaterThanOverTheFlatHorizon() {
        val observer = Support.boulder
        val night = DateInterval(Instant.parse("2026-06-24T12:00:00Z"), 24 * 3_600.0)
        val flatRise = assertNotNull(RiseSetCalculator.riseTransitSet(SolarSystemBody.MOON, observer, night).rise)
        val ridge = HorizonVisibility(HorizonProfile.constant(8.0))
        val events = ridge.riseTransitSet(SolarSystemBody.MOON, observer, night)
        val cleared = assertNotNull(events.rise)
        assertTrue(cleared.timeIntervalSince(flatRise) > 20 * 60, "the Moon needs more than 20 minutes to climb 8°")

        // The first second at which the refracted upper limb is at the skyline.
        var scan = flatRise
        val limb = { time: Instant ->
            val frame = SkyFrame(time, observer)
            frame.horizontal(SolarSystemBody.MOON, true).altitude +
                Ephemeris.state(SolarSystemBody.MOON, frame.julianDate).angularDiameterArcseconds / 7_200
        }
        while (limb(scan) < 8.0) scan = scan.addingTimeInterval(1.0)
        assertEquals(0.0, cleared.timeIntervalSince(scan), 10.0)
        assertEquals(AltitudeCrossing.Kind.RISING, ridge.crossings(SolarSystemBody.MOON, observer, night).first().kind)
        assertFalse(ridge.isVisible(SolarSystemBody.MOON, observer, cleared.addingTimeInterval(-60.0)))
        assertTrue(ridge.isVisible(SolarSystemBody.MOON, observer, cleared.addingTimeInterval(60.0)))
    }

    @Test
    fun testCircumstancesFollowTheSkylineNotTheHorizon() {
        val polaris = stars[2].second
        // Polaris is 40° high at 40°N: above any skyline lower than that, below a 45° wall all round.
        val low = HorizonVisibility(HorizonProfile.constant(30.0))
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, low.riseTransitSet(polaris, null, Support.boulder, day).circumstance)
        assertTrue(low.crossings(polaris, null, Support.boulder, day).isEmpty())
        val high = HorizonVisibility(HorizonProfile.constant(45.0))
        val hidden = high.riseTransitSet(polaris, null, Support.boulder, day)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, hidden.circumstance)
        assertEquals(null, hidden.rise)
        assertEquals(null, hidden.set)
        assertTrue(high.visibleWindows(polaris, null, Support.boulder, day).isEmpty())
        assertNotNull(hidden.transit) // the culmination is reported whether or not it is hidden
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_UP, flat.riseTransitSet(polaris, null, Support.boulder, day).circumstance)
    }

    @Test
    fun testTheMinimumAltitudeAndTheSkylineBothApply() {
        val observer = Support.equator
        val mountain = HorizonVisibility(Support.wall(40.0, 45.0, 135.0))
        // The star climbs through the 40° wall at azimuth 90°, so a 20° minimum changes nothing there; it sets over the
        // flat west, where the minimum ends the window when the star falls to 20° instead of at the horizon.
        val withoutMinimum = mountain.visibleWindows(equatorStar, null, observer, day)
        val withLowMinimum = mountain.visibleWindows(equatorStar, null, observer, day, minimumAltitude = 20.0)
        assertEquals(1, withoutMinimum.size)
        assertEquals(1, withLowMinimum.size)
        assertEquals(0.0, withLowMinimum[0].start.timeIntervalSince(withoutMinimum[0].start), 0.01)
        val earlier = (20.0 - RiseSetCalculator.standardHorizonAltitude) / siderealRate * 3_600
        assertEquals(earlier, withoutMinimum[0].end.timeIntervalSince(withLowMinimum[0].end), 1.0)
        // A 60° minimum is higher than the wall: it decides, and the star is up 30° of hour angle either side of transit.
        val high = mountain.visibleWindows(equatorStar, null, observer, day, minimumAltitude = 60.0)
        assertEquals(1, high.size)
        assertEquals(60.0 / siderealRate * 3_600, high[0].duration, 5.0)
        assertTrue(withoutMinimum[0].start < high[0].start)
    }

    // MARK: Generic position functions

    private val dayLength = 86_400.0
    private val origin = Instant.parse("2026-06-20T18:00:00Z")

    /** 40 sin(2π t / day) + 10 degrees at azimuth 90°: a smooth curve with known crossings. */
    private val sinusoid: (Instant) -> HorizontalCoordinate = { time ->
        HorizontalCoordinate(90.0, 40 * sin(2 * PI * time.timeIntervalSince(origin) / dayLength) + 10)
    }

    @Test
    fun testCrossingsOfAFunctionAreFoundExactly() {
        val skyline = HorizonVisibility(Support.wall(20.0, 80.0, 100.0))
        val interval = DateInterval(origin, 2 * dayLength)
        // Refracted altitudes are compared with the skyline as it is: 40 sin θ + 10 = 20.
        val crossings = skyline.crossings(sinusoid, interval, refracted = true, tolerance = 0.001)
        val theta = asin(0.25)
        val rising = theta / (2 * PI) * dayLength
        val setting = (PI - theta) / (2 * PI) * dayLength
        assertEquals(
            listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING, AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING),
            crossings.map { it.kind },
        )
        val times = crossings.map { it.time.timeIntervalSince(origin) }
        assertEquals(rising, times[0], 0.01)
        assertEquals(setting, times[1], 0.01)
        assertEquals(rising + dayLength, times[2], 0.01)
        assertEquals(setting + dayLength, times[3], 0.01)

        // Geometric altitudes must reach the threshold above the skyline instead.
        val threshold = HorizonVisibility.thresholdAltitude(20.0)
        val geometric = skyline.crossings(sinusoid, interval, tolerance = 0.001)
        assertEquals(asin((threshold - 10) / 40) / (2 * PI) * dayLength, geometric[0].time.timeIntervalSince(origin), 0.01)
        assertTrue(threshold < 20.0 && threshold > 19.9)

        val events = skyline.riseTransitSet(sinusoid, interval, refracted = true)
        assertEquals(rising, assertNotNull(events.rise).timeIntervalSince(origin), 0.3)
        assertEquals(setting, assertNotNull(events.set).timeIntervalSince(origin), 0.3)
        assertEquals(dayLength / 4, assertNotNull(events.transit).timeIntervalSince(origin), 0.3)
        assertEquals(50.0, assertNotNull(events.transitAltitude), 1e-6)
        val windows = skyline.visibleWindows(sinusoid, interval, refracted = true, tolerance = 0.001)
        assertEquals(2, windows.size)
        assertEquals(rising, windows[0].start.timeIntervalSince(origin), 0.01)
        assertEquals(setting, windows[0].end.timeIntervalSince(origin), 0.01)
    }

    @Test
    fun testIsVisibleForAFunctionFollowsTheMargin() {
        val skyline = HorizonVisibility(Support.wall(20.0, 80.0, 100.0))
        for (step in 0 until 96) {
            val time = origin.addingTimeInterval(step * 900.0 + 13)
            val altitude = sinusoid(time).altitude
            assertEquals(altitude >= 20.0, skyline.isVisible(sinusoid, time, refracted = true), "$step")
            assertEquals(altitude >= HorizonVisibility.thresholdAltitude(20.0), skyline.isVisible(sinusoid, time), "$step")
        }
        assertEquals(10.0, skyline.margin(30.0, 90.0, refracted = true), 1e-12)
        assertEquals(30.0 - HorizonVisibility.thresholdAltitude(20.0), skyline.margin(30.0, 90.0), 1e-12)
        assertEquals(30.0, skyline.margin(30.0, 180.0, refracted = true) , 1e-12) // flat there
        assertTrue(skyline.margin(Double.NaN, 90.0).isNaN())
    }

    @Test
    fun testATargetThatOnlyGrazesTheSkylineIsStillFound() {
        val skyline = HorizonVisibility(HorizonProfile.constant(10.0))
        // 10.01° at the top of a parabola that is 40 s wide at the skyline: narrower than a sample step (120 s), with the
        // nearest sample 30 s from the top, 0.0125° short of the skyline.
        val peak = origin.addingTimeInterval(1_050.0)
        val graze: (Instant) -> HorizontalCoordinate = { time ->
            val offset = time.timeIntervalSince(peak) / 20
            HorizontalCoordinate(45.0, 10.01 - 0.01 * offset * offset)
        }
        val interval = DateInterval(origin, 3_600.0)
        val windows = skyline.visibleWindows(graze, interval, refracted = true, tolerance = 0.001)
        assertEquals(1, windows.size)
        assertEquals(40.0, windows[0].duration, 0.05)
        assertEquals(0.0, windows[0].start.timeIntervalSince(peak.addingTimeInterval(-20.0)), 0.02)
        val crossings = skyline.crossings(graze, interval, refracted = true, tolerance = 0.001)
        assertEquals(listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING), crossings.map { it.kind })
        // A target that peaks just below the skyline never shows.
        val below: (Instant) -> HorizontalCoordinate = { time ->
            val offset = time.timeIntervalSince(peak) / 20
            HorizontalCoordinate(45.0, 9.99 - 0.01 * offset * offset)
        }
        assertTrue(skyline.visibleWindows(below, interval, refracted = true).isEmpty())
    }

    @Test
    fun testATargetCrossingANarrowSpireDisappearsBehindItAndReturns() {
        // A spire 20° high at azimuth 90° (a triangle over ±1°) in front of a target 10° high that moves east at 0.25° per minute.
        val spire = HorizonVisibility(HorizonProfile.flat.setting(20.0, AzimuthRange(90.0, 0.0)))
        val moving: (Instant) -> HorizontalCoordinate = { time ->
            HorizontalCoordinate(80.0 + time.timeIntervalSince(origin) * 0.25 / 60, 10.0)
        }
        val interval = DateInterval(origin, 5_000.0)
        val windows = spire.visibleWindows(moving, interval, refracted = true, tolerance = 0.001)
        // Hidden while 20 (1 − |d|) ≥ 10, i.e. for |azimuth − 90| < 0.5°: 240 s centred on 2400 s.
        assertEquals(2, windows.size)
        assertEquals(0.0, windows[0].start.timeIntervalSince(origin), 1e-9)
        assertEquals(2_280.0, windows[0].end.timeIntervalSince(origin), 0.05)
        assertEquals(2_520.0, windows[1].start.timeIntervalSince(origin), 0.05)
        assertEquals(5_000.0, windows[1].end.timeIntervalSince(origin), 1e-9)
        val events = spire.riseTransitSet(moving, interval, refracted = true)
        assertEquals(2_520.0, assertNotNull(events.rise).timeIntervalSince(origin), 0.05)
        assertEquals(2_280.0, assertNotNull(events.set).timeIntervalSince(origin), 0.05)
    }

    @Test
    fun testEmptyAndDegenerateIntervals() {
        val ridge = HorizonVisibility(HorizonProfile.constant(10.0))
        val position = equatorStar
        val empty = DateInterval(day.start, 0.0)
        assertTrue(ridge.crossings(position, null, Support.equator, empty).isEmpty())
        assertTrue(ridge.visibleWindows(position, null, Support.equator, empty).isEmpty())
        assertTrue(ridge.visibleWindows(position, null, Support.equator, emptyList<DateInterval>()).isEmpty())
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, ridge.riseTransitSet(position, null, Support.equator, empty).circumstance)
        // Overlapping and touching intervals are merged; the windows come back sorted without overlap.
        val first = DateInterval(day.start, 6 * 3_600.0)
        val second = DateInterval(day.start.addingTimeInterval(5 * 3_600.0), 6 * 3_600.0)
        val merged = ridge.visibleWindows(position, null, Support.equator, listOf(second, first))
        val whole = ridge.visibleWindows(position, null, Support.equator, DateInterval(day.start, 11 * 3_600.0))
        assertEquals(whole.size, merged.size)
        for ((a, b) in merged.zip(whole)) {
            assertEquals(0.0, a.start.timeIntervalSince(b.start), 0.01)
            assertEquals(0.0, a.end.timeIntervalSince(b.end), 0.01)
        }
    }

    // MARK: The threshold

    @Test
    fun testThresholdAltitudeScalesTheHorizonRefractionWithTheSkylineAltitude() {
        assertEquals(RiseSetCalculator.standardHorizonAltitude, HorizonVisibility.thresholdAltitude(0.0))
        // Bennett's refraction at the horizon and at 10°, 30° and 60° (arcminutes): 1 / tan(h + 7.31 / (h + 4.4)).
        fun bennett(h: Double): Double = 1 / AstroMath.tanDeg(h + 7.31 / (h + 4.4))
        for (h in listOf(1.0, 5.0, 10.0, 20.0, 30.0, 45.0, 60.0)) {
            val expected = h + RiseSetCalculator.standardHorizonAltitude * bennett(h) / bennett(0.0)
            assertEquals(expected, HorizonVisibility.thresholdAltitude(h), 1e-9, "$h")
        }
        assertEquals(9.9114, HorizonVisibility.thresholdAltitude(10.0), 1e-4)
        // Rising with the skyline, and never below the skyline itself minus the horizon allowance.
        var previous = HorizonVisibility.thresholdAltitude(-10.0)
        for (tenth in -99..600) {
            val value = HorizonVisibility.thresholdAltitude(tenth / 10.0)
            assertTrue(value > previous, "$tenth")
            assertTrue(value <= tenth / 10.0 + 1e-12 && value >= tenth / 10.0 - 0.5667 - 1e-12, "$tenth")
            previous = value
        }
        // A skyline below the horizon keeps the full allowance.
        assertEquals(-5.5667, HorizonVisibility.thresholdAltitude(-5.0), 1e-12)
        assertTrue(HorizonVisibility.thresholdAltitude(Double.NaN).isNaN())
        // The independent route: the refraction model of the library puts the star at the skyline.
        for (h in listOf(2.0, 10.0, 25.0)) {
            val apparent = Refraction.apparentAltitude(HorizonVisibility.thresholdAltitude(h))
            assertEquals(h, apparent, 0.01, "$h")
        }
    }

    // MARK: Batches

    @Test
    fun testBatchOfFixedObjectsGivesTheSameWindowsAsSingleCalls() {
        val generator = Support.SeededGenerator(31uL)
        val positions = List(40) {
            EquatorialCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(2 * generator.nextDouble() - 1))
        }
        val motions: List<ProperMotion?> = positions.indices.map { if (it % 3 == 0) ProperMotion(40.0, -25.0) else null }
        val skyline = HorizonVisibility(Support.truthProfile)
        val night = listOf(DateInterval(Instant.parse("2026-12-21T01:00:00Z"), 5 * 3_600.0), DateInterval(Instant.parse("2026-12-21T08:00:00Z"), 3 * 3_600.0))
        val batch = skyline.visibleWindows(positions, motions, Support.boulder, night, minimumAltitude = 15.0)
        assertEquals(positions.size, batch.size)
        for (index in positions.indices) {
            assertEquals(skyline.visibleWindows(positions[index], motions[index], Support.boulder, night, 15.0), batch[index], "$index")
        }
        assertTrue(batch.count { it.isNotEmpty() } in 10..35)
        // Windows lie inside the given intervals, in order, and without overlap.
        for (windows in batch) {
            for (window in windows) assertTrue(night.any { it.start <= window.start && it.end >= window.end })
            for ((a, b) in windows.zipWithNext()) assertTrue(a.end < b.start)
        }
    }

    @Test
    fun testWindowsAgreeWithAScanOfFullFramesForARoughSkyline() {
        val generator = Support.SeededGenerator(77uL)
        val skyline = HorizonVisibility(Support.truthProfile)
        val night = DateInterval(Instant.parse("2026-12-21T01:00:00Z"), 10 * 3_600.0)
        val observer = Support.boulder
        repeat(12) { index ->
            val position = EquatorialCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(2 * generator.nextDouble() - 1))
            val windows = skyline.visibleWindows(position, null, observer, night, minimumAltitude = 5.0)
            var disagreements = 0
            for (step in 0 until 600) {
                val time = night.start.addingTimeInterval(step * 60.0 + 17)
                val frame = SkyFrame(time, observer)
                val geometric = frame.horizontal(position, null, false)
                val expected = geometric.altitude >= 5.0 &&
                    geometric.altitude >= HorizonVisibility.thresholdAltitude(Support.truthProfile.altitude(geometric.azimuth))
                val found = windows.any { it.start <= time && time < it.end }
                if (expected != found) {
                    // Only samples within a few seconds of an edge may differ.
                    val distance = windows.flatMap { listOf(it.start, it.end) }.minOfOrNull { abs(it.timeIntervalSince(time)) } ?: Double.MAX_VALUE
                    assertTrue(distance < 30.0, "object $index at step $step: expected $expected")
                    disagreements += 1
                }
            }
            assertTrue(disagreements <= 3, "$disagreements disagreements for object $index")
        }
    }

    // MARK: Speed

    @Test
    fun testWindowsForSevenHundredObjectsThroughANightTakeUnderASecond() {
        val summary = net.kuecker.lodestar.kit.planning.PlanningTestSupport.summary(
            net.kuecker.lodestar.kit.planning.PlanningTestSupport.boulder, 2026, 12, 21,
        )
        val generator = Support.SeededGenerator(700uL)
        val positions = List(700) {
            EquatorialCoordinate(360 * generator.nextDouble(), AstroMath.asinDeg(2 * generator.nextDouble() - 1))
        }
        val skyline = HorizonVisibility(Support.truthProfile)
        val dark = summary.astronomicalDarknessIntervals
        assertTrue(dark.sumOf { it.duration } > 10 * 3_600)
        skyline.visibleWindows(positions, emptyList(), summary.observer, dark, minimumAltitude = 10.0) // warms up the JIT compiler
        val start = System.nanoTime()
        val windows = skyline.visibleWindows(positions, emptyList(), summary.observer, dark, minimumAltitude = 10.0)
        val elapsed = (System.nanoTime() - start) / 1e9
        assertEquals(700, windows.size)
        assertTrue(elapsed < 1.0, "took $elapsed s")
        assertTrue(windows.count { it.isNotEmpty() } > 200)
        assertTrue(windows.count { it.isEmpty() } > 100)
    }
}

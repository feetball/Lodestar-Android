package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Compares the rise, transit, set and twilight searches with `riseset.json`: for 5 sites and 6 dates
 * (windows from local solar noon to the next, including midnight Sun and polar night at Tromsø) the
 * instants at which Skyfield and JPL DE440s put the Sun, Moon, Venus, Jupiter, Sirius and Vega
 * through their horizons, and their upper culminations.
 */
class RiseSetFixtureTests {
    @Serializable
    private class Crossing(val kind: String, val unix: Double)

    @Serializable
    private class Horizon(val crossings: List<Crossing>, val alwaysUp: Boolean, val alwaysDown: Boolean)

    @Serializable
    private class Transit(val unix: Double, val altitudeDeg: Double)

    @Serializable
    private class ObjectRecord(
        val horizon: Horizon? = null,
        val horizonUpperLimb: Horizon? = null,
        val civil: Horizon? = null,
        val nautical: Horizon? = null,
        val astronomical: Horizon? = null,
        val transits: List<Transit>,
    )

    @Serializable
    private class DayCase(
        val site: String,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
        val date: String,
        val windowStartUtc: String,
        val windowEndUtc: String,
        val objects: Map<String, ObjectRecord>,
    ) {
        val observer: Observer get() = Observer(GeographicLocation(lat, lon, elevationM))

        val interval: DateInterval
            get() = DateInterval(Instant.parse(windowStartUtc), Instant.parse(windowEndUtc))
    }

    @Serializable
    private class File(val cases: List<DayCase>)

    private companion object {
        /** Sirius and Vega as in the fixture generator (Hipparcos, ICRS J2000.0). */
        val stars: Map<String, Pair<EquatorialCoordinate, ProperMotion>> = mapOf(
            "sirius" to Pair(
                EquatorialCoordinate(101.28715539, -16.71611582),
                ProperMotion(-546.01, -1223.08),
            ),
            "vega" to Pair(
                EquatorialCoordinate(279.23473511, 38.78369180),
                ProperMotion(201.02, 287.46),
            ),
        )
    }

    /**
     * Seconds by which event times may differ. Observed maxima over the fixture: Sun 0.08 s, stars
     * 0.04 s, Venus and Jupiter 0.4 s, Moon 0.6 s (a 8″ error in the lunar theory is half a second of time).
     */
    private fun timeTolerance(obj: String): Double =
        when (obj) {
            "sun", "sirius", "vega" -> 1.5
            "moon" -> 3.0
            else -> 2.0
        }

    private fun fixture(): List<DayCase> {
        val cases = EphemerisFixtureStore.load<File>("riseset.json").cases
        assertEquals(30, cases.size, "5 sites x 6 dates")
        return cases
    }

    private fun assertCrossings(found: List<AltitudeCrossing>, expected: Horizon, tolerance: Double, label: String) {
        assertEquals(expected.crossings.size, found.size, "$label: number of crossings")
        if (found.size != expected.crossings.size) return
        for ((mine, reference) in found.zip(expected.crossings)) {
            assertEquals(
                reference.kind, if (mine.kind == AltitudeCrossing.Kind.RISING) "rise" else "set", "$label: kind",
            )
            assertEquals(reference.unix, mine.time.timeIntervalSince1970, tolerance, "$label: time")
        }
    }

    private fun assertSummary(
        result: RiseTransitSet,
        expected: Horizon,
        transits: List<Transit>,
        tolerance: Double,
        label: String,
    ) {
        val rise = expected.crossings.firstOrNull { it.kind == "rise" }
        val set = expected.crossings.firstOrNull { it.kind == "set" }
        assertEquals(rise == null, result.rise?.timeIntervalSince1970 == null, "$label: rise present")
        assertEquals(set == null, result.set?.timeIntervalSince1970 == null, "$label: set present")
        val foundRise = result.rise?.timeIntervalSince1970
        val referenceRise = rise?.unix
        if (foundRise != null && referenceRise != null) {
            assertEquals(referenceRise, foundRise, tolerance, "$label: rise")
        }
        val foundSet = result.set?.timeIntervalSince1970
        val referenceSet = set?.unix
        if (foundSet != null && referenceSet != null) {
            assertEquals(referenceSet, foundSet, tolerance, "$label: set")
        }
        assertEquals(
            expected.alwaysUp, result.circumstance == RiseTransitSet.Circumstance.ALWAYS_UP, "$label: always up",
        )
        assertEquals(
            expected.alwaysDown, result.circumstance == RiseTransitSet.Circumstance.ALWAYS_DOWN, "$label: always down",
        )
        assertEquals(
            !expected.alwaysUp && !expected.alwaysDown, result.circumstance == RiseTransitSet.Circumstance.NORMAL,
            "$label: circumstance",
        )
        val first = transits.firstOrNull()
        if (first != null) {
            assertEquals(
                first.unix, result.transit?.timeIntervalSince1970 ?: Double.NaN, 2 * tolerance, "$label: transit",
            )
            assertEquals(
                first.altitudeDeg, result.transitAltitude ?: Double.NaN, 0.002, "$label: transit altitude",
            )
        } else {
            assertNull(result.transit, "$label: transit")
        }
    }

    private fun assertCulminations(
        found: List<AltitudeCulmination>,
        expected: List<Transit>,
        tolerance: Double,
        label: String,
    ) {
        assertEquals(expected.size, found.size, "$label: number of culminations")
        if (found.size != expected.size) return
        for ((mine, reference) in found.zip(expected)) {
            assertEquals(reference.unix, mine.time.timeIntervalSince1970, 2 * tolerance, "$label: time")
            assertEquals(reference.altitudeDeg, mine.altitude, 0.002, "$label: altitude")
        }
    }

    // MARK: Tests

    @Test
    fun testSunSetsAndRisesAndTwilightsMatch() {
        for (day in fixture()) {
            val label = "${day.site} ${day.date} sun"
            val record = assertNotNull(day.objects["sun"])
            val tolerance = timeTolerance("sun")
            val observer = day.observer

            val horizon = assertNotNull(record.horizon)
            assertCrossings(
                RiseSetCalculator.crossings(SolarSystemBody.SUN, observer, day.interval),
                horizon, tolerance, "$label horizon",
            )
            assertSummary(
                RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, observer, day.interval),
                horizon, record.transits, tolerance, "$label summary",
            )

            // The twilights go through the generic search with a caller-supplied altitude function.
            val altitude: (Instant) -> Double = {
                SkyFrame(it, observer).horizontal(SolarSystemBody.SUN, false).altitude
            }
            for ((name, threshold, expected) in listOf(
                Triple("civil", TwilightCalculator.civilAltitude, record.civil),
                Triple("nautical", TwilightCalculator.nauticalAltitude, record.nautical),
                Triple("astronomical", TwilightCalculator.astronomicalAltitude, record.astronomical),
            )) {
                assertCrossings(
                    RiseSetCalculator.crossings(altitude, threshold, day.interval),
                    assertNotNull(expected), tolerance, "$label $name",
                )
            }
            assertCulminations(
                RiseSetCalculator.upperCulminations(SolarSystemBody.SUN, observer, day.interval),
                record.transits, tolerance, "$label culmination",
            )
        }
    }

    @Test
    fun testMoonUpperLimbRiseSetAndTransitMatch() {
        for (day in fixture()) {
            val label = "${day.site} ${day.date} moon"
            val record = assertNotNull(day.objects["moon"])
            val tolerance = timeTolerance("moon")
            val limb = assertNotNull(record.horizonUpperLimb)
            assertCrossings(
                RiseSetCalculator.crossings(SolarSystemBody.MOON, day.observer, day.interval),
                limb, tolerance, "$label upper limb",
            )
            assertSummary(
                RiseSetCalculator.riseTransitSet(SolarSystemBody.MOON, day.observer, day.interval),
                limb, record.transits, tolerance, "$label summary",
            )
            assertCulminations(
                RiseSetCalculator.upperCulminations(SolarSystemBody.MOON, day.observer, day.interval),
                record.transits, tolerance, "$label culmination",
            )
        }
    }

    /**
     * The fixture also tabulates the DESIGN.md recipe taken literally: the topocentric altitude against
     * `0.7275·π − 0.5667°`, which is Meeus' threshold for the geocentric altitude. The generic search
     * reproduces that list, and shows that it puts every event about π too high: rises come 4 to 16
     * minutes late and sets as early (the fixture README measures 255 s at the equator to 949 s at
     * Tromsø), which is why `RiseSetCalculator` uses the upper limb instead.
     */
    @Test
    fun testLiteralDesignThresholdOnTopocentricAltitudeIsMinutesOff() {
        var compared = 0
        for (day in fixture()) {
            val record = assertNotNull(day.objects["moon"])
            val literal = assertNotNull(record.horizon)
            val limb = assertNotNull(record.horizonUpperLimb)
            val observer = day.observer
            val margin: (Instant) -> Double = { date ->
                val frame = SkyFrame(date, observer)
                val distance = frame.state(SolarSystemBody.MOON).distanceKilometers
                val parallax = AstroMath.asinDeg(SkyFrame.earthEquatorialRadiusKilometers / distance)
                frame.horizontal(SolarSystemBody.MOON, false).altitude - (0.7275 * parallax - 0.5667)
            }
            val found = RiseSetCalculator.crossings(margin, 0.0, day.interval)
            assertCrossings(found, literal, 3.0, "${day.site} ${day.date} literal recipe")

            val correct = RiseSetCalculator.crossings(SolarSystemBody.MOON, observer, day.interval)
            if (found.size != correct.size) continue
            for ((wrong, right) in found.zip(correct)) {
                val delay = wrong.time.timeIntervalSince(right.time)
                assertTrue(abs(delay) > 150, "${day.site} ${day.date}")
                assertTrue(abs(delay) < 1_100, "${day.site} ${day.date}")
                assertEquals(right.kind == AltitudeCrossing.Kind.RISING, delay > 0, "${day.site} ${day.date}")
                compared += 1
            }
            assertEquals(correct.size, limb.crossings.size)
        }
        assertTrue(compared > 40)
    }

    @Test
    fun testPlanetsAndStarsMatch() {
        for (day in fixture()) {
            for (key in listOf("venus", "jupiter", "sirius", "vega")) {
                val label = "${day.site} ${day.date} $key"
                val record = assertNotNull(day.objects[key])
                val horizon = assertNotNull(record.horizon)
                val tolerance = timeTolerance(key)
                val found: List<AltitudeCrossing>
                val summary: RiseTransitSet
                val culminations: List<AltitudeCulmination>
                val body = SolarSystemBody.fromRawValue(key)
                if (body != null) {
                    found = RiseSetCalculator.crossings(body, day.observer, day.interval)
                    summary = RiseSetCalculator.riseTransitSet(body, day.observer, day.interval)
                    culminations = RiseSetCalculator.upperCulminations(body, day.observer, day.interval)
                } else {
                    val (position, motion) = assertNotNull(stars[key])
                    found = RiseSetCalculator.crossings(position, motion, day.observer, day.interval)
                    summary = RiseSetCalculator.riseTransitSet(position, motion, day.observer, day.interval)
                    culminations = RiseSetCalculator.upperCulminations(position, motion, day.observer, day.interval)
                }
                assertCrossings(found, horizon, tolerance, "$label horizon")
                assertSummary(summary, horizon, record.transits, tolerance, "$label summary")
                assertCulminations(culminations, record.transits, tolerance, "$label culmination")
            }
        }
    }

    @Test
    fun testFixtureCoversPolarDayNightAndSingleEvents() {
        // Guards the fixture itself: the cases this suite is meant to exercise are present.
        var alwaysUp = 0
        var alwaysDown = 0
        var singleCrossing = 0
        for (day in fixture()) {
            for (record in day.objects.values) {
                for (horizon in listOf(
                    record.horizon, record.horizonUpperLimb, record.civil, record.nautical, record.astronomical,
                )) {
                    if (horizon == null) continue
                    if (horizon.alwaysUp) alwaysUp += 1
                    if (horizon.alwaysDown) alwaysDown += 1
                    if (horizon.crossings.size == 1) singleCrossing += 1
                }
            }
        }
        assertTrue(alwaysUp > 10)
        assertTrue(alwaysDown > 3)
        assertTrue(singleCrossing > 5)
    }
}

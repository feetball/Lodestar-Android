package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the port to the Swift algorithms more tightly than the reference fixtures can: the expected values were
 * computed by an independent Python transliteration of the Swift sources (same series tables, same formulas,
 * glibc `libm`). The Kotlin results agree with it to about 1e-14 (positions, magnitudes, angles) and, for the
 * rise/set searches, to the last bit of the event times over several thousand random cases.
 */
class EphemerisGoldenTests {
    private class Golden(
        val body: SolarSystemBody,
        val x: Double,
        val y: Double,
        val z: Double,
        val magnitude: Double,
        val elongation: Double?,
        val phaseAngle: Double?,
        val eclipticLongitude: Double,
        val eclipticLatitude: Double,
    )

    // Geocentric J2000 place in AU, magnitude, elongation, phase angle, ecliptic longitude and latitude of date,
    // at JD(UT) 2460000.5 with ΔT 69.2 s.
    private val goldens = listOf(
        Golden(SolarSystemBody.SUN, 0.9026399263340973, -0.37243543880713204, -0.16145302441811832, -26.76245113263115, null, null, 336.1069663360772, -5.6099724957875834e-05),
        Golden(SolarSystemBody.MOON, 0.0020028381132084785, 0.0014481849233305046, 0.0006398413519987875, -8.960161696837945, 62.53971068608114, 117.33474470750224, 38.64639628993715, 0.24867801466738315),
        Golden(SolarSystemBody.MERCURY, 1.0042145639998106, -0.7592285587260252, -0.3786081859197763, -0.45277494988361866, 16.057100436624935, 36.982325030967814, 320.16911525900446, -1.97918389057021),
        Golden(SolarSystemBody.VENUS, 1.3855669941754163, 0.12869882103344332, 0.03348506132350854, -3.933967460859683, 29.642161316167208, 42.6307118082418, 5.738250739034205, -0.8421373758306485),
        Golden(SolarSystemBody.MARS, 0.24386890814919257, 0.9687746603006953, 0.47151106175792606, 0.32319742136669216, 101.43767981891862, 36.71353609132069, 77.55528668349325, 2.454248818188737),
        Golden(SolarSystemBody.JUPITER, 5.630734983191895, 1.0170034468297622, 0.3190105809636387, -2.106198690556921, 34.890640770550235, 6.565237537175094, 10.981974488233982, -1.117564230983587),
        Golden(SolarSystemBody.SATURN, 9.200185453015369, -5.093833553296655, -2.4689130897644755, 0.8691938454392028, 7.4740278262817, 0.7510370284041006, 328.740841940183, -1.2689871053961066),
        Golden(SolarSystemBody.URANUS, 14.112424660002022, 13.035210431549439, 5.523746834506865, 5.813294853745334, 69.30417212314357, 2.6989253870906453, 45.41077208452628, -0.3335185467005317),
        Golden(SolarSystemBody.NEPTUNE, 30.6785896986986, -2.6660930021336617, -1.841537011268762, 7.825404071558248, 18.33460502383711, 0.596359499764235, 354.4055805648261, -1.1684743850759078),
    )

    @Test
    fun testBodyStatesMatchTheIndependentTransliteration() {
        val jd = JulianDate(2460000.5, 69.2)
        for (golden in goldens) {
            val state = Ephemeris.state(golden.body, jd)
            val label = golden.body.rawValue
            val scale = abs(golden.x) + abs(golden.y) + abs(golden.z)
            assertEquals(golden.x, state.geocentricJ2000.x, 1e-12 * scale, label)
            assertEquals(golden.y, state.geocentricJ2000.y, 1e-12 * scale, label)
            assertEquals(golden.z, state.geocentricJ2000.z, 1e-12 * scale, label)
            assertEquals(golden.magnitude, assertNotNull(state.apparentMagnitude), 1e-10, label)
            assertEquals(golden.eclipticLongitude, state.eclipticLongitudeOfDate, 1e-9, label)
            assertEquals(golden.eclipticLatitude, state.eclipticLatitudeOfDate, 1e-9, label)
            if (golden.elongation == null || golden.phaseAngle == null) {
                assertNull(state.elongation, label)
                assertNull(state.phaseAngle, label)
            } else {
                assertEquals(golden.elongation, assertNotNull(state.elongation), 1e-9, label)
                assertEquals(golden.phaseAngle, assertNotNull(state.phaseAngle), 1e-9, label)
            }
        }
    }

    private class Events(
        val rise: Double,
        val transit: Double,
        val set: Double,
        val transitAltitude: Double,
    )

    // Seattle, the 24 hours from 2026-10-17T07:00:00Z (local midnight, PDT); event times in seconds after the start.
    private val seattle = Observer(GeographicLocation(47.6, -122.3, 60.0))
    private val windowStart = instantFromTimeIntervalSinceReferenceDate(813_913_200.0)
    private val window = DateInterval(windowStart, 86_400.0)

    private fun assertEvents(label: String, result: RiseTransitSet, expected: Events) {
        assertEquals(RiseTransitSet.Circumstance.NORMAL, result.circumstance, label)
        assertEquals(expected.rise, assertNotNull(result.rise).timeIntervalSince(windowStart), 1e-3, "$label rise")
        assertEquals(
            expected.transit, assertNotNull(result.transit).timeIntervalSince(windowStart), 1e-3, "$label transit",
        )
        assertEquals(expected.set, assertNotNull(result.set).timeIntervalSince(windowStart), 1e-3, "$label set")
        assertEquals(expected.transitAltitude, assertNotNull(result.transitAltitude), 1e-9, "$label altitude")
    }

    @Test
    fun testRiseTransitSetMatchesTheIndependentTransliteration() {
        assertEvents(
            "moon", RiseSetCalculator.riseTransitSet(SolarSystemBody.MOON, seattle, window),
            Events(53064.446065, 67562.343634, 82064.661342, 16.07403857891164),
        )
        assertEvents(
            "sun", RiseSetCalculator.riseTransitSet(SolarSystemBody.SUN, seattle, window),
            Events(27074.722546, 46452.068645, 65820.003197, 32.91732580586748),
        )
        assertEvents(
            "jupiter", RiseSetCalculator.riseTransitSet(SolarSystemBody.JUPITER, seattle, window),
            Events(6894.026346, 32652.434986, 58405.577387, 57.06712678899304),
        )
        val sirius = EquatorialCoordinate(101.28715539, -16.71611582)
        val motion = ProperMotion(-546.01, -1223.08)
        assertEvents(
            "sirius", RiseSetCalculator.riseTransitSet(sirius, motion, seattle, window),
            Events(5121.86199, 22278.909191, 39435.928174, 25.650646844326182),
        )
        // The separate searches give the same events.
        val crossings = RiseSetCalculator.crossings(SolarSystemBody.MOON, seattle, window)
        assertEquals(listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING), crossings.map { it.kind })
        assertEquals(53064.446065, crossings[0].time.timeIntervalSince(windowStart), 1e-3)
        assertEquals(82064.661342, crossings[1].time.timeIntervalSince(windowStart), 1e-3)
        val culminations = RiseSetCalculator.upperCulminations(sirius, motion, seattle, window)
        assertEquals(1, culminations.size)
        assertEquals(22278.909191, culminations[0].time.timeIntervalSince(windowStart), 1e-3)
        assertEquals(25.650646844326182, culminations[0].altitude, 1e-9)
    }

    @Test
    fun testTwilightMatchesTheIndependentTransliteration() {
        val night = TwilightCalculator.night(
            windowStart.addingTimeInterval(5 * 3600.0), seattle, ZoneOffset.ofTotalSeconds(-25_200),
        )
        fun after(date: Instant?): Double = assertNotNull(date).timeIntervalSince(windowStart)
        assertEquals(43_200.0, night.interval.start.timeIntervalSince(windowStart), 1e-6)
        assertEquals(86_400.0, night.interval.duration, 1e-6)
        assertEquals(65820.003197, after(night.sunset), 1e-3)
        assertEquals(67688.534825, after(night.civilDusk), 1e-3)
        assertEquals(69828.820546, after(night.nauticalDusk), 1e-3)
        assertEquals(71964.577152, after(night.astronomicalDusk), 1e-3)
        assertEquals(107401.954274, after(night.astronomicalDawn), 1e-3)
        assertEquals(109542.047392, after(night.nauticalDawn), 1e-3)
        assertEquals(111687.815169, after(night.civilDawn), 1e-3)
        assertEquals(113562.342859, after(night.sunrise), 1e-3)
        assertEquals(89676.55498, after(night.solarMidnight), 1e-3)
        assertEquals(-52.063999308179426, night.sunAltitudeAtMidnight, 1e-9)
        assertTrue(assertNotNull(night.astronomicalNight).duration > 30_000)
    }
}

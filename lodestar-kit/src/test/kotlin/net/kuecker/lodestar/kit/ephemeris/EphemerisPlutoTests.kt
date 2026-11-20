package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pluto: the series of Meeus, chapter 37, against the book's example and JPL DE440s, the magnitude against JPL
 * Horizons, and the sanity of its distances over the span in which the series is valid (1885–2099).
 */
class EphemerisPlutoTests {
    // MARK: The series

    /**
     * Example 37.a: Pluto on 1992 October 13.0 TT (JDE 2448908.5), heliocentric, referred to the mean equinox and
     * ecliptic of J2000.0. The book gives l = 232.74071°, b = +14.58782°, r = 29.711111 AU (the 43 rows give
     * 232.740711423°, 14.587817302°, 29.711110981 AU).
     */
    @Test
    fun testMeeusExample37a() {
        val centuries = (2_448_908.5 - JulianDate.j2000) / JulianDate.daysPerJulianCentury
        val pluto = PlutoTheory.heliocentricEcliptic(centuries)
        assertEquals(232.74071, pluto.longitude, 1e-5)
        assertEquals(14.58782, pluto.latitude, 1e-5)
        assertEquals(29.711111, pluto.radius, 1e-6)
        assertEquals(43, PlutoTheory.termCount)
    }

    @Test
    fun testHeliocentricJ2000IsTheEclipticPlaceRotatedToTheMeanEquator() {
        for (jde in listOf(2_448_908.5, 2_451_545.0, 2_460_000.5, 2_415_020.5)) {
            val centuries = (jde - JulianDate.j2000) / JulianDate.daysPerJulianCentury
            val ecliptic = PlutoTheory.heliocentricEcliptic(centuries)
            val direction = EclipticCoordinate(ecliptic.longitude, ecliptic.latitude).unitVector
            val expected = Matrix3.rotationX(AstroMath.radians(Obliquity.j2000)) * (direction * ecliptic.radius)
            val vector = PlutoTheory.heliocentricJ2000(jde)
            assertEquals(0.0, (vector - expected).length, 1e-9, "JDE $jde")
            assertEquals(ecliptic.radius, vector.length, 1e-9, "JDE $jde")
        }
    }

    /**
     * The series is a sum of sines: whatever the date, r = 40.7241346 ± Σ√(A²+B²) = 29.648...51.800 AU,
     * b is between −25.354° and +17.538°, and l is normalised.
     */
    @Test
    fun testSeriesStaysBoundedForAnyDate() {
        for (step in -300..300) {
            val centuries = step * 0.2137
            val pluto = PlutoTheory.heliocentricEcliptic(centuries)
            assertTrue(pluto.radius in 29.6..51.9, "T = $centuries")
            assertTrue(pluto.latitude in -25.4..17.6, "T = $centuries")
            assertTrue(pluto.longitude >= 0.0 && pluto.longitude < 360.0, "T = $centuries")
        }
    }

    // MARK: Apparent place

    /**
     * DE440s on 1992 October 13.0 TT: apparent α = 232.927709°, δ = −4.456119° (J2000 axes), Δ = 30.528738 AU,
     * Sun–Pluto distance 29.711092 AU at the time the light left, elongation 34.4155°, phase angle 1.0874°. (The
     * book's astrometric place, 15h 31m 43.7s, −4° 27′ 29″, is the same without aberration.)
     */
    @Test
    fun testApparentPlaceOnTheDayOfMeeusExample37a() {
        val state = Ephemeris.state(SolarSystemBody.PLUTO, JulianDate.fromTt(2_448_908.5))
        val place = EquatorialCoordinate(state.geocentricJ2000)
        assertEquals(232.927709, place.rightAscension, 0.001)
        assertEquals(-4.456119, place.declination, 0.001)
        assertEquals(30.528738, state.distanceAU, 0.0005)
        assertEquals(29.711092, assertNotNull(state.heliocentricDistanceAU), 0.0005)
        assertEquals(34.4155, assertNotNull(state.elongation), 0.01)
        assertEquals(1.0874, assertNotNull(state.phaseAngle), 0.02)
        assertEquals(0.99991, assertNotNull(state.illuminatedFraction), 0.0005)
        assertNull(state.isWaxing)
        // 2 · 1188.3 km seen from 30.5287 AU.
        assertEquals(0.1073, state.angularDiameterArcseconds, 0.001)
        assertEquals(state.geocentricJ2000, Ephemeris.geocentricJ2000(SolarSystemBody.PLUTO, state.julianDate))
        assertNotNull(state.apparentMagnitude)
    }

    // MARK: Magnitude

    @Test
    fun testPhotometryFormula() {
        // V(1, 0) = −1.00 and 0.041 mag per degree of phase angle.
        assertEquals(-1.00, Photometry.pluto(1.0, 1.0, 0.0), 1e-12)
        assertEquals(-1.00 + 0.082, Photometry.pluto(1.0, 1.0, 2.0), 1e-12)
        assertEquals(5 * log10(33.0 * 32.0) - 1.00 + 0.041, Photometry.pluto(33.0, 32.0, 1.0), 1e-12)
        assertEquals(5 * log10(30.0 * 31.0) - 1.00, Photometry.pluto(30.0, 31.0, 0.0), 1e-12)
    }

    /**
     * JPL Horizons' observer table for Pluto (command 999, centre 500@399, times in TT): the apparent visual
     * magnitude APmag, which is printed to 0.001 and includes Charon. The instants are the largest phase angle
     * seen from the Earth (1.96°, 1988), Meeus' example 37.a, J2000, the smallest phase angle (opposition, 2018)
     * and four instants of the next years.
     */
    private val horizonsMagnitudes: List<Pair<Double, Double>> = listOf(
        2_447_374.5 to 13.800, 2_448_908.5 to 13.833, 2_451_545.0 to 13.902, 2_458_311.5 to 14.197,
        2_460_000.5 to 14.494, 2_461_041.5 to 14.572, 2_462_000.5 to 14.536, 2_466_000.5 to 14.865,
    )

    /**
     * The formula against the Horizons table: the largest difference over these instants is 0.0004 mag (series and
     * DE440s Earth), so 0.005 is ten times that and half the 0.010 by which V(1, 0) = −1.01 would be off.
     */
    @Test
    fun testMagnitudeMatchesHorizons() {
        assertTrue(horizonsMagnitudes.size >= 8)
        for ((jd, expected) in horizonsMagnitudes) {
            val state = Ephemeris.state(SolarSystemBody.PLUTO, JulianDate.fromTt(jd))
            assertEquals(expected, assertNotNull(state.apparentMagnitude), 0.005, "JD $jd")
        }
    }

    // MARK: Distances over the valid span

    /**
     * 1885-01-01 to 2099-12-31 every 53 days (1,482 instants). DE440s (and the series): r 29.66...48.93 AU,
     * Δ 28.69...49.85 AU, |β| up to 17.72°, phase angle up to 1.96°, so the disc is 0.066″ to 0.114″ across.
     */
    @Test
    fun testGeocentricDistanceRangeOverTheValidSpan() {
        val last = JulianDate.julianDay(2099, 12, 31)
        var jd = JulianDate.julianDay(1885, 1, 1)
        while (jd <= last) {
            val state = Ephemeris.state(SolarSystemBody.PLUTO, JulianDate(jd))
            val message = "JD $jd"
            assertTrue(state.distanceAU in 28.5..50.1, message)
            assertTrue(assertNotNull(state.heliocentricDistanceAU) in 29.5..49.2, message)
            assertTrue(abs(state.eclipticLatitudeOfDate) < 17.9, message)
            assertTrue(assertNotNull(state.phaseAngle) < 2.1, message)
            assertTrue(state.angularDiameterArcseconds in 0.064..0.116, message)
            jd += 53
        }
    }

    // MARK: Cross-twin check

    /**
     * The state at JD(UT) 2460000.5 with ΔT 69.2 s, with the digits the Kotlin twin computes (its
     * `EphemerisGoldenTests` pins the same row): the two implementations of the series, the light-time loop and the
     * magnitude must agree to the last few digits. DE440s is 1.2×10⁻⁴ AU from this place (3×10⁻⁶ of the
     * distance); `testApparentPlaceOnTheDayOfMeeusExample37a` is the check against DE440s itself.
     */
    @Test
    fun testStateAtTheGoldenInstant() {
        val state = Ephemeris.state(SolarSystemBody.PLUTO, JulianDate(2_460_000.5, 69.2))
        assertEquals(17.233150638279188, state.geocentricJ2000.x, 1e-9)
        assertEquals(-27.870347727564585, state.geocentricJ2000.y, 1e-9)
        assertEquals(-13.661324765442227, state.geocentricJ2000.z, 1e-9)
        assertEquals(14.493880860819205, assertNotNull(state.apparentMagnitude), 1e-9)
        assertEquals(36.78370544834634, assertNotNull(state.elongation), 1e-9)
        assertEquals(0.9782224377925626, assertNotNull(state.phaseAngle), 1e-9)
        assertEquals(299.3872492780666, state.eclipticLongitudeOfDate, 1e-9)
        assertEquals(-2.339787769333409, state.eclipticLatitudeOfDate, 1e-9)
    }

    // MARK: The body

    @Test
    fun testPlutoIsADwarfPlanetListedWithThePlanets() {
        assertEquals(SolarSystemBody.PLUTO, SolarSystemBody.fromRawValue("pluto"))
        assertEquals("Pluto", SolarSystemBody.PLUTO.displayName)
        assertTrue(SolarSystemBody.PLUTO.isPlanet)
        assertEquals(SolarSystemBody.PLUTO, SolarSystemBody.entries.last())
        assertEquals(1_188.3, SolarSystemBody.PLUTO.meanRadiusKilometers, 0.05)
        val data = Json.encodeToString(SolarSystemBody.serializer(), SolarSystemBody.PLUTO)
        assertEquals("\"pluto\"", data)
        assertEquals(SolarSystemBody.PLUTO, Json.decodeFromString(SolarSystemBody.serializer(), data))
    }
}

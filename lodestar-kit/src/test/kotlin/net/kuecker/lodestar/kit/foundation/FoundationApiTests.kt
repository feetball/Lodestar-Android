package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the Foundation API that the Swift tests do not exercise directly: operators, edge
 * cases, constructors, and the `Codable` JSON shapes the port has to keep.
 */
class FoundationApiTests {
    // MARK: Vector3 and Matrix3

    @Test
    fun testVectorArithmetic() {
        val a = Vector3(1.0, 2.0, 3.0)
        val b = Vector3(4.0, -5.0, 6.0)
        assertEquals(Vector3(5.0, -3.0, 9.0), a + b)
        assertEquals(Vector3(-3.0, 7.0, -3.0), a - b)
        assertEquals(Vector3(-1.0, -2.0, -3.0), -a)
        assertEquals(Vector3(2.0, 4.0, 6.0), a * 2.0)
        assertEquals(Vector3(2.0, 4.0, 6.0), 2.0 * a)
        assertEquals(Vector3(0.5, 1.0, 1.5), a / 2.0)
        assertEquals(12.0, a.dot(b))
        assertEquals(Vector3(27.0, 6.0, -13.0), a.cross(b))
        assertEquals(14.0, a.lengthSquared)
        var sum = a
        sum += b
        sum -= a
        assertEquals(b, sum)
        assertEquals("(1.0, 2.0, 3.0)", a.toString())
        assertEquals(Vector3.zero, Vector3(0.0, 0.0, 0.0))
    }

    @Test
    fun testVectorFinitenessAndAngles() {
        assertTrue(Vector3(1.0, 2.0, 3.0).isFinite)
        assertFalse(Vector3(1.0, Double.NaN, 3.0).isFinite)
        assertFalse(Vector3(1.0, 2.0, Double.POSITIVE_INFINITY).isFinite)
        assertEquals(Vector3.zero, Vector3(Double.NaN, 0.0, 0.0).normalized)
        assertEquals(1.0, Vector3(3.0, -4.0, 12.0).normalized.length, 1e-15)
        val x = Vector3(1.0, 0.0, 0.0)
        assertEquals(PI / 2, x.angleRadians(Vector3(0.0, 2.0, 0.0)), 1e-15)
        assertEquals(PI, x.angleRadians(Vector3(-3.0, 0.0, 0.0)), 1e-15)
        assertEquals(0.0, x.angleRadians(Vector3(5.0, 0.0, 0.0)))
        // Stable at tiny separations, where acos(dot) would return 0.
        assertEquals(1e-12, x.angleRadians(Vector3(1.0, 1e-12, 0.0)), 1e-24)
    }

    @Test
    fun testMatrixAccessorsAndProducts() {
        val m = Matrix3(Vector3(1.0, 2.0, 3.0), Vector3(4.0, 5.0, 6.0), Vector3(7.0, 8.0, 10.0))
        assertEquals(6.0, m[1, 2])
        assertEquals(7.0, m[2, 0])
        assertEquals(Vector3(1.0, 4.0, 7.0), m.c0)
        assertEquals(Vector3(2.0, 5.0, 8.0), m.c1)
        assertEquals(Vector3(3.0, 6.0, 10.0), m.c2)
        assertEquals(m, Matrix3.fromColumns(m.c0, m.c1, m.c2))
        assertEquals(m, m.transposed.transposed)
        assertEquals(-3.0, m.determinant)
        assertEquals(Vector3(14.0, 32.0, 53.0), m * Vector3(1.0, 2.0, 3.0))
        assertEquals(m, m * Matrix3.identity)
        assertEquals(m, Matrix3.identity * m)
        assertEquals(
            Matrix3(Vector3(30.0, 36.0, 45.0), Vector3(66.0, 81.0, 102.0), Vector3(109.0, 134.0, 169.0)),
            m * m,
        )
        assertEquals("[(1.0, 2.0, 3.0), (4.0, 5.0, 6.0), (7.0, 8.0, 10.0)]", m.toString())
        assertTrue(m.isFinite)
        assertFalse(Matrix3(Vector3.zero, Vector3(0.0, Double.NaN, 0.0), Vector3.zero).isFinite)
    }

    @Test
    fun testMatrixIndexOutOfRangeFails() {
        assertFailsWith<IllegalArgumentException> { Matrix3.identity[3, 0] }
        assertFailsWith<IllegalArgumentException> { Matrix3.identity[0, -1] }
    }

    @Test
    fun testRotationConstructors() {
        for (angle in listOf(-2.5, 0.0, 0.3, 1.7)) {
            assertEquals(Matrix3.rotationX(angle).transposed, Matrix3.frameRotationX(angle))
            assertEquals(Matrix3.rotationY(angle).transposed, Matrix3.frameRotationY(angle))
            assertEquals(Matrix3.rotationZ(angle).transposed, Matrix3.frameRotationZ(angle))
            val about = Matrix3.rotation(axis = Vector3(0.0, 0.0, 5.0), angleRadians = angle)
            val expected = Matrix3.rotationZ(angle)
            for (row in 0..2) for (column in 0..2) assertEquals(expected[row, column], about[row, column], 1e-15)
        }
        assertEquals(Matrix3.identity, Matrix3.rotation(axis = Vector3.zero, angleRadians = 1.0))
        assertEquals(Matrix3.identity, Matrix3.rotation(axis = Vector3(Double.NaN, 1.0, 0.0), angleRadians = 1.0))
    }

    // MARK: AstroMath

    @Test
    fun testAstroMathHelpers() {
        assertEquals(PI, AstroMath.radians(180.0))
        assertEquals(180.0, AstroMath.degrees(PI))
        assertEquals(3600.0 * 180 / PI, AstroMath.arcsecondsPerRadian)
        assertEquals(0.0, AstroMath.acosDeg(2.0))
        assertEquals(-90.0, AstroMath.asinDeg(-2.0))
        assertEquals(45.0, AstroMath.atan2Deg(1.0, 1.0), 1e-13)
        assertEquals(0.5, AstroMath.sinDeg(30.0), 1e-15)
        assertEquals(0.5, AstroMath.cosDeg(60.0), 1e-15)
        assertEquals(1.0, AstroMath.tanDeg(45.0), 1e-15)
        assertEquals(0.0, AstroMath.normalizeDegrees(360.0))
        assertEquals(359.0, AstroMath.normalizeDegrees(-361.0), 1e-12)
        assertEquals(Double.POSITIVE_INFINITY, AstroMath.normalizeDegrees(Double.POSITIVE_INFINITY))
        assertEquals(180.0, AstroMath.normalizeSignedDegrees(180.0))
        assertEquals(-179.0, AstroMath.normalizeSignedDegrees(181.0), 1e-12)
        assertTrue(AstroMath.normalizeSignedDegrees(Double.NaN).isNaN())
    }

    @Test
    fun testClampAndPolynomialOverloads() {
        assertEquals(5, AstroMath.clamp(7, 1, 5))
        assertEquals(1, AstroMath.clamp(-3, 1, 5))
        assertEquals(3, AstroMath.clamp(3, 1, 5))
        assertEquals(5.0, AstroMath.clamp(7.0, 1.0, 5.0))
        assertEquals("b", AstroMath.clamp("b", "a", "c"))
        assertEquals("c", AstroMath.clamp("d", "a", "c"))
        assertTrue(AstroMath.clamp(Double.NaN, -90.0, 90.0).isNaN())
        assertEquals(17.0, AstroMath.polynomial(2.0, 1.0, 2.0, 3.0))
        assertEquals(AstroMath.polynomial(0.37, listOf(1.5, -2.0, 0.25, 4.0)), AstroMath.polynomial(0.37, 1.5, -2.0, 0.25, 4.0))
        assertEquals(0.0, AstroMath.polynomial(3.0, emptyList()))
        assertEquals(4.5, AstroMath.polynomial(123.0, listOf(4.5)))
    }

    // MARK: Coordinates

    @Test
    fun testCoordinateConstructionNormalizes() {
        val equatorial = EquatorialCoordinate(rightAscension = -10.0, declination = 95.0)
        assertEquals(350.0, equatorial.rightAscension)
        assertEquals(90.0, equatorial.declination)
        assertEquals(350.0 / 15, equatorial.rightAscensionHours)
        assertEquals(90.0, EquatorialCoordinate.fromRightAscensionHours(rightAscensionHours = 6.0, declination = -100.0).rightAscension)
        assertEquals(-90.0, EquatorialCoordinate.fromRightAscensionHours(rightAscensionHours = 6.0, declination = -100.0).declination)
        assertEquals(EquatorialCoordinate(0.0, 0.0), EquatorialCoordinate(Vector3.zero))
        assertEquals(EquatorialCoordinate(90.0, 0.0), EquatorialCoordinate(Vector3(0.0, 2.0, 0.0)))
        assertEquals(90.0, EquatorialCoordinate(Vector3(0.0, 0.0, 0.1)).declination)

        val horizontal = HorizontalCoordinate(azimuth = -90.0, altitude = -120.0)
        assertEquals(270.0, horizontal.azimuth)
        assertEquals(-90.0, horizontal.altitude)

        val ecliptic = EclipticCoordinate(longitude = 370.0, latitude = -100.0)
        assertEquals(10.0, ecliptic.longitude, 1e-12)
        assertEquals(-90.0, ecliptic.latitude)
        assertEquals(EclipticCoordinate(0.0, 0.0), EclipticCoordinate(Vector3.zero))

        val galactic = GalacticCoordinate(longitude = -30.0, latitude = 100.0)
        assertEquals(330.0, galactic.longitude, 1e-12)
        assertEquals(90.0, galactic.latitude)
        assertEquals(GalacticCoordinate(0.0, 0.0), GalacticCoordinate(Vector3(1.0, 0.0, 0.0)))
        assertEquals(GalacticCoordinate(90.0, 0.0), GalacticCoordinate(Vector3(0.0, 1.0, 0.0)))
    }

    @Test
    fun testCoordinateUnitVectorsAndSeparations() {
        assertEquals(Vector3(1.0, 0.0, 0.0), EquatorialCoordinate(0.0, 0.0).unitVector)
        val pole = EquatorialCoordinate(123.0, 90.0).unitVector
        assertEquals(1.0, pole.z, 1e-15)
        assertEquals(90.0, EquatorialCoordinate(0.0, 0.0).angularSeparation(EquatorialCoordinate(90.0, 0.0)), 1e-12)
        // Opposite directions are 180° apart.
        assertEquals(180.0, HorizontalCoordinate(0.0, 10.0).angularSeparation(HorizontalCoordinate(180.0, -10.0)), 1e-9)
        assertEquals(160.0, HorizontalCoordinate(0.0, 10.0).angularSeparation(HorizontalCoordinate(180.0, 10.0)), 1e-9)
        // North is +y, east is +x, up is +z.
        assertEquals(Vector3(0.0, 1.0, 0.0), HorizontalCoordinate(0.0, 0.0).unitVector)
        assertEquals(0.0, HorizontalCoordinate(Vector3(0.0, 1.0, 0.0)).azimuth)
        assertEquals(180.0, HorizontalCoordinate(Vector3(0.0, -1.0, 0.0)).azimuth)
        assertEquals(270.0, HorizontalCoordinate(Vector3(-1.0, 0.0, 0.0)).azimuth)
        assertEquals(-90.0, HorizontalCoordinate(Vector3(0.0, 0.0, -2.0)).altitude)
        assertEquals(0.0, HorizontalCoordinate(Vector3(0.0, 0.0, -2.0)).azimuth)
        assertEquals(HorizontalCoordinate(0.0, 0.0), HorizontalCoordinate(Vector3.zero))
    }

    @Test
    fun testGeographicLocationConstructionAndValidationBoundaries() {
        val wrapped = GeographicLocation(latitude = -100.0, longitude = 190.0, elevation = 20_000.0)
        assertEquals(-90.0, wrapped.latitude)
        assertEquals(-170.0, wrapped.longitude, 1e-12)
        assertEquals(12_000.0, wrapped.elevation)
        assertEquals(-1_000.0, GeographicLocation(0.0, 0.0, -5_000.0).elevation)
        assertEquals(0.0, GeographicLocation(10.0, 20.0).elevation)
        assertEquals(GeographicLocation(0.0, 0.0, 0.0), GeographicLocation(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
        assertEquals(-1_000.0..12_000.0, GeographicLocation.elevationRange)

        assertNotNull(GeographicLocation.validated(latitude = 90.0, longitude = 360.0, elevation = 12_000.0))
        assertEquals(0.0, GeographicLocation.validated(latitude = 90.0, longitude = 360.0)?.longitude)
        assertNotNull(GeographicLocation.validated(latitude = -90.0, longitude = -180.0, elevation = -1_000.0))
        assertNull(GeographicLocation.validated(latitude = 90.0001, longitude = 0.0))
        assertNull(GeographicLocation.validated(latitude = -90.0001, longitude = 0.0))
        assertNull(GeographicLocation.validated(latitude = 0.0, longitude = 360.0001))
        assertNull(GeographicLocation.validated(latitude = 0.0, longitude = -180.0001))
        assertNull(GeographicLocation.validated(latitude = 0.0, longitude = 0.0, elevation = -1_000.0001))
        assertNull(GeographicLocation.validated(latitude = 0.0, longitude = 0.0, elevation = 12_000.0001))
        assertNull(GeographicLocation.validated(latitude = 0.0, longitude = 0.0, elevation = Double.NaN))
    }

    @Test
    fun testCoordinateTransformsHelpers() {
        val local = CoordinateTransforms.localEquatorialVector(hourAngle = 90.0, declination = 0.0)
        assertEquals(0.0, local.x, 1e-15)
        assertEquals(-1.0, local.y, 1e-15)
        val toHorizontal = CoordinateTransforms.localEquatorialToHorizontal(latitude = 90.0)
        // At the north pole the celestial pole is the zenith.
        assertEquals(1.0, (toHorizontal * Vector3(0.0, 0.0, 1.0)).z, 1e-15)
        assertEquals(1.0, CoordinateTransforms.equatorialJ2000ToGalacticMatrix.determinant, 1e-12)
        val (hourAngle, declination) = CoordinateTransforms.horizontalToEquatorial(HorizontalCoordinate(0.0, 90.0), latitude = 90.0)
        assertEquals(0.0, hourAngle, 1e-12)
        assertEquals(90.0, declination, 1e-12)
    }

    @Test
    fun testProperMotionConstructionAndApplication() {
        val sanitized = ProperMotion(raMilliarcsecondsPerYear = Double.NaN, decMilliarcsecondsPerYear = Double.POSITIVE_INFINITY)
        assertEquals(0.0, sanitized.raMilliarcsecondsPerYear)
        assertEquals(0.0, sanitized.decMilliarcsecondsPerYear)
        val start = EquatorialCoordinate(rightAscension = 123.0, declination = 45.0)
        assertEquals(0.0, sanitized.apply(start, years = 500.0).angularSeparation(start), 1e-12)
        // 1000 mas/yr in μα* for 100 years is 100″ east; on the equator that is 100″ of right ascension.
        val east = ProperMotion(1000.0, 0.0).apply(EquatorialCoordinate(0.0, 0.0), years = 100.0)
        assertEquals(100.0 / 3600, east.rightAscension, 1e-6)
        assertEquals(0.0, east.declination, 1e-12)
        // 3600 mas/yr in δ for 100 years is 0.1° north.
        val north = ProperMotion(0.0, 3600.0).apply(EquatorialCoordinate(200.0, 0.0), years = 100.0)
        assertEquals(0.1, north.declination, 1e-6)
        assertEquals(200.0, north.rightAscension, 1e-9)
        // Near the pole the motion still works on the unit vector.
        val polar = ProperMotion(100.0, 100.0).apply(EquatorialCoordinate(10.0, 90.0), years = 10.0)
        assertTrue(polar.declination < 90.0 && polar.declination > 89.99)
    }

    // MARK: Time

    @Test
    fun testJulianDateConstructorsEstimateDeltaT() {
        val j2000 = JulianDate(JulianDate.j2000)
        assertEquals(JulianDate.deltaT(decimalYear = 2000.0), j2000.deltaT)
        assertEquals(JulianDate.j2000 + j2000.deltaT / JulianDate.secondsPerDay, j2000.tt)
        assertEquals(0.0, j2000.centuriesUT)
        assertEquals(2000.0 + j2000.deltaT / JulianDate.secondsPerDay / 365.25, j2000.julianYearTT, 1e-12)

        val later = j2000.addingDays(36_525.0)
        assertEquals(JulianDate(JulianDate.j2000 + 36_525.0), later)
        assertEquals(1.0, later.centuriesUT, 1e-15)
        assertEquals(JulianDate.deltaT(decimalYear = 2100.0), later.deltaT, 1e-3)

        val fromTt = JulianDate.fromTt(2_461_000.25)
        assertEquals(2_461_000.25, fromTt.tt, 1e-9)
        assertEquals(JulianDate.deltaT(decimalYear = JulianDate.decimalYear(2_461_000.25)), fromTt.deltaT)

        assertEquals(2440587.5, JulianDate(Instant.EPOCH).ut)
        assertEquals(Instant.EPOCH, JulianDate(ut = 2_440_587.5, deltaT = 0.0).date)
        assertEquals(JulianDate.unixEpoch, JulianDate.julianDay(year = 1970, month = 1, day = 1))
    }

    @Test
    fun testJulianDateKeepsSubSecondPrecision() {
        val date = Instant.ofEpochSecond(1_760_000_000, 250_000_000)
        val jd = JulianDate(date)
        // A JD near 2.46e6 days resolves about 4e-10 days = 40 µs.
        assertEquals(0.0, jd.date.timeIntervalSince(date), 1e-4)
        assertEquals(0.25, jd.date.timeIntervalSince(Instant.ofEpochSecond(1_760_000_000)), 1e-4)
        assertEquals(1_760_000_000.25 / 86_400.0 + 2_440_587.5, jd.ut)
    }

    @Test
    fun testJulianDateOrderingUsesUniversalTimeOnly() {
        val a = JulianDate(ut = 2_460_000.5, deltaT = 69.0)
        val b = JulianDate(ut = 2_460_000.5, deltaT = 70.0)
        val c = JulianDate(ut = 2_460_001.5, deltaT = 1.0)
        assertTrue(a != b)
        assertEquals(0, a.compareTo(b))
        assertTrue(a <= b && b <= a && !(a < b) && !(a > b))
        assertTrue(a < c && c > b)
        assertEquals(listOf(a, c), listOf(c, a).sorted())
        assertEquals(0, JulianDate(ut = Double.NaN, deltaT = 0.0).compareTo(a))
    }

    @Test
    fun testDeltaTSegmentsAndExtremes() {
        assertEquals(64.69, JulianDate.deltaT(decimalYear = 2005.0))
        assertEquals(69.11, JulianDate.deltaT(decimalYear = 2026.0), 1e-9)
        assertEquals(64.77, JulianDate.deltaT(decimalYear = 2005.5), 1e-9)
        assertEquals(-20 + 32 * 1.44, JulianDate.deltaT(decimalYear = 1700.0), 1e-9)
        assertEquals(-20 + 32 * 3.8 * 3.8, JulianDate.deltaT(decimalYear = 2200.0), 1e-9)
        assertEquals(93.0, JulianDate.deltaT(decimalYear = 2050.0), 1e-9)
        assertEquals(69.2, JulianDate.deltaT(decimalYear = Double.POSITIVE_INFINITY))
        // Smooth from 1800 on: no step of a quarter year exceeds a second. (As in Swift, the long-term
        // expression used before 1800 does not join the 1800 polynomial: it is 32 s lower.)
        var previous = JulianDate.deltaT(decimalYear = 1800.0)
        var year = 1800.25
        while (year <= 2160.0) {
            val value = JulianDate.deltaT(decimalYear = year)
            assertEquals(previous, value, 1.0, "ΔT jumps at $year")
            previous = value
            year += 0.25
        }
    }

    @Test
    fun testSiderealTimeAtJ2000() {
        val jd = JulianDate(ut = JulianDate.j2000, deltaT = 64.0)
        assertEquals(280.46061837, SiderealTime.greenwichMean(jd), 1e-12)
        val nutation = Nutation(longitude = 0.001, obliquity = 0.0)
        assertEquals(
            280.46061837 + 0.001 * AstroMath.cosDeg(23.4),
            SiderealTime.greenwichApparent(jd, nutation, trueObliquity = 23.4),
            1e-12,
        )
    }

    // MARK: Earth orientation

    @Test
    fun testEarthOrientationAtJ2000() {
        val jd = JulianDate.fromTt(JulianDate.j2000)
        assertEquals(Obliquity.j2000, Obliquity.mean(jd), 1e-15)
        assertEquals(23.0 + 26.0 / 60 + 21.448 / 3600, Obliquity.j2000)
        val precession = Precession.matrixFromJ2000(jd)
        for (row in 0..2) for (column in 0..2) {
            assertEquals(if (row == column) 1.0 else 0.0, precession[row, column], 1e-15)
        }
        assertEquals(Nutation(jd).matrix(Obliquity.mean(jd)).determinant, 1.0, 1e-14)
        // 2000 Jan 1.5 TT: Δψ ≈ −13.9″, Δε ≈ −5.8″ (IAU 1980).
        assertEquals(-13.9, Nutation(jd).longitude * 3600, 0.1)
        assertEquals(-5.8, Nutation(jd).obliquity * 3600, 0.1)
        assertEquals(357.5291, SolarCoordinates.meanAnomaly(jd), 1e-3)
        assertEquals(280.46646 + (1.914602 * AstroMath.sinDeg(357.52911) + 0.019993 * AstroMath.sinDeg(2 * 357.52911) + 0.000289 * AstroMath.sinDeg(3 * 357.52911)), SolarCoordinates.geometricLongitude(jd), 1e-4)
    }

    @Test
    fun testAberrationApplyAndRemoveAreInverse() {
        val jd = JulianDate(2_460_500.5)
        val beta = Aberration.earthVelocity(jd)
        val direction = EquatorialCoordinate(210.0, -35.0).unitVector
        val apparent = Aberration.apply(direction, beta)
        assertEquals(1.0, apparent.length, 1e-15)
        assertEquals(0.0, apparent.angle(direction) * 3600, 21.0)
        assertTrue(apparent.angle(direction) * 3600 > 1.0)
        // First-order inverse: the residual is of second order in β (about 1e-4 arcsec).
        assertEquals(0.0, Aberration.remove(apparent, beta).angle(direction) * 3600, 1e-3)
        assertEquals(20.49552, Aberration.constantArcseconds)
    }

    // MARK: Refraction

    @Test
    fun testAtmosphereRefractionScale() {
        assertEquals(1.0, Atmosphere.standard.refractionScale)
        assertEquals(Atmosphere(1010.0, 10.0), Atmosphere.standard)
        assertEquals(0.5, Atmosphere(pressureMillibars = 505.0, temperatureCelsius = 10.0).refractionScale, 1e-15)
        assertEquals((700.0 / 1010.0) * (283.0 / 273.0), Atmosphere(700.0, 0.0).refractionScale, 1e-15)
        for ((pressure, temperature) in listOf(
            299.9 to 10.0, 1100.1 to 10.0, 1010.0 to -60.1, 1010.0 to 60.1,
            Double.POSITIVE_INFINITY to 10.0, 1010.0 to Double.NaN,
        )) {
            assertEquals(1.0, Atmosphere(pressure, temperature).refractionScale, "$pressure hPa, $temperature °C")
        }
        assertTrue(Atmosphere(300.0, -60.0).refractionScale != 1.0)
        assertTrue(Atmosphere(1100.0, 60.0).refractionScale != 1.0)
    }

    @Test
    fun testRefractionEdgeCases() {
        assertEquals(0.0, Refraction.refractionForTrueAltitude(Double.NaN))
        assertEquals(0.0, Refraction.refractionForTrueAltitude(95.0))
        assertEquals(0.0, Refraction.refractionForTrueAltitude(-5.0))
        assertEquals(0.0, Refraction.refractionForApparentAltitude(Double.NaN))
        assertEquals(0.0, Refraction.refractionForApparentAltitude(90.0))
        // The fade between -1° and -5° is continuous and ends at zero.
        val edge = Refraction.refractionForTrueAltitude(-1.0)
        assertEquals(edge, Refraction.refractionForTrueAltitude(-1.0 - 1e-9), 1e-8)
        assertEquals(edge / 2, Refraction.refractionForTrueAltitude(-3.0), 1e-12)
        assertTrue(Refraction.refractionForTrueAltitude(-4.999) in 0.0..0.001)
        // Above the zenith and for non-finite input the apparent altitude stays within [.., 90].
        assertEquals(90.0, Refraction.apparentAltitude(trueAltitude = 90.0))
        assertEquals(90.0, Refraction.apparentAltitude(trueAltitude = 123.0))
        assertEquals(90.0, Refraction.apparentAltitude(trueAltitude = Double.NaN))
        assertTrue(Refraction.trueAltitude(apparentAltitude = Double.NaN).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, Refraction.trueAltitude(apparentAltitude = Double.POSITIVE_INFINITY))
        // Refraction raises objects: apparent > true above the fade end; true < apparent.
        assertTrue(Refraction.apparentAltitude(trueAltitude = 0.0) > 0.4)
        assertTrue(Refraction.trueAltitude(apparentAltitude = 0.0) < -0.4)
        assertEquals(
            Refraction.apparentAltitude(5.0, Atmosphere(505.0, 10.0)) - 5.0,
            (Refraction.apparentAltitude(5.0) - 5.0) / 2,
            1e-12,
        )
    }

    // MARK: SkyFrame

    private val munich = Observer(GeographicLocation(latitude = 48.14, longitude = 11.58, elevation = 520.0))

    @Test
    fun testSkyFrameFromInstantMatchesSkyFrameFromJulianDate() {
        val instant = instantFromTimeIntervalSince1970(1_770_000_000.0)
        val fromInstant = SkyFrame(instant, munich)
        val fromJulianDate = SkyFrame(JulianDate(instant), munich)
        assertEquals(fromJulianDate.julianDate, fromInstant.julianDate)
        assertEquals(fromJulianDate.j2000ToHorizontal, fromInstant.j2000ToHorizontal)
        assertEquals(fromJulianDate.observerPositionOfDate, fromInstant.observerPositionOfDate)
        assertEquals(fromJulianDate.localApparentSiderealTime, fromInstant.localApparentSiderealTime)
        assertEquals(0.0, fromInstant.date.timeIntervalSince(instant), 1e-4)
        assertEquals(munich, fromInstant.observer)
    }

    @Test
    fun testSkyFrameFieldsAreConsistent() {
        val frame = SkyFrame(instantFromTimeIntervalSince1970(1_770_000_000.0), munich)
        assertEquals(Obliquity.mean(frame.julianDate), frame.meanObliquity)
        assertEquals(frame.meanObliquity + frame.nutation.obliquity, frame.trueObliquity)
        assertEquals(
            AstroMath.normalizeDegrees(frame.greenwichApparentSiderealTime + munich.location.longitude),
            frame.localApparentSiderealTime,
        )
        assertEquals(frame.precessionNutationMatrix, frame.nutation.matrix(frame.meanObliquity) * frame.precessionMatrix)
        assertEquals(frame.j2000ToHorizontal, frame.equatorialOfDateToHorizontal * frame.precessionNutationMatrix)
        assertEquals((frame.julianDate.tt - JulianDate.j2000) / 365.25, frame.yearsSinceJ2000)
        // The rotation matrices are proper rotations.
        assertEquals(1.0, frame.j2000ToHorizontal.determinant, 1e-12)
        assertEquals(1.0, frame.precessionNutationMatrix.determinant, 1e-12)
        // The observer is about one Earth radius from the centre.
        val radiusKilometers = frame.observerPositionOfDate.length * SkyFrame.kilometersPerAU
        assertEquals(6_370.0, radiusKilometers, 15.0)
    }

    @Test
    fun testSkyFrameHandlesNonFiniteDatesWithoutThrowing() {
        val frame = SkyFrame(JulianDate(ut = Double.NaN, deltaT = 69.0), munich)
        assertEquals(Instant.EPOCH, frame.date)
        assertTrue(frame.localApparentSiderealTime.isNaN())
        // A vector without a usable length gives (0°, 0°), as in Swift, so the result is not NaN.
        val horizontal = frame.horizontal(j2000 = EquatorialCoordinate(10.0, 20.0), refraction = false)
        assertEquals(HorizontalCoordinate(0.0, 0.0), horizontal)
        assertEquals(69.2, JulianDate(Double.NaN).deltaT)
    }

    @Test
    fun testGeocentricObserverPosition() {
        val scale = SkyFrame.earthEquatorialRadiusKilometers / SkyFrame.kilometersPerAU
        val equator = SkyFrame.geocentricObserverPosition(GeographicLocation(0.0, 0.0), localSiderealTime = 0.0)
        assertEquals(Vector3(scale, 0.0, 0.0), equator)
        val east = SkyFrame.geocentricObserverPosition(GeographicLocation(0.0, 0.0), localSiderealTime = 90.0)
        assertEquals(scale, east.y, 1e-15)
        assertEquals(0.0, east.x, 1e-15)
        val pole = SkyFrame.geocentricObserverPosition(GeographicLocation(90.0, 0.0), localSiderealTime = 0.0)
        assertEquals(scale * (1 - 1 / 298.257), pole.z, 1e-12)
        val high = SkyFrame.geocentricObserverPosition(GeographicLocation(0.0, 0.0, elevation = 8_000.0), localSiderealTime = 0.0)
        assertEquals(scale * (1 + 8_000.0 / 6_378_140.0), high.x, 1e-15)
    }

    @Test
    fun testSkyFrameRefractedHelperAndPoleOfFrame() {
        val frame = SkyFrame(instantFromTimeIntervalSince1970(1_770_000_000.0), munich)
        val geometric = HorizontalCoordinate(azimuth = 123.0, altitude = 0.0)
        val refracted = SkyFrame.refracted(geometric, Atmosphere.standard)
        assertEquals(123.0, refracted.azimuth)
        assertEquals(Refraction.apparentAltitude(trueAltitude = 0.0), refracted.altitude)
        val pole = frame.horizontalOfCelestialPole(north = true, refraction = true)
        assertEquals(Refraction.apparentAltitude(frame.horizontalOfCelestialPole(north = true, refraction = false).altitude), pole.altitude, 1e-12)
        // A non-default atmosphere changes the refracted result only.
        val thin = SkyFrame(frame.julianDate, Observer(munich.location, Atmosphere(700.0, 25.0)))
        val star = EquatorialCoordinate(279.23473, 38.78369)
        assertEquals(
            frame.horizontal(j2000 = star, refraction = false),
            thin.horizontal(j2000 = star, refraction = false),
        )
    }

    // MARK: Codable JSON shapes

    private val json = Json

    @Test
    fun testVectorAndMatrixJsonShapes() {
        assertEquals("""{"x":1.0,"y":-2.5,"z":3.0}""", json.encodeToString(Vector3(1.0, -2.5, 3.0)))
        assertEquals(Vector3(1.0, -2.5, 3.0), json.decodeFromString<Vector3>("""{"x":1,"y":-2.5,"z":3}"""))
        val matrix = Matrix3(Vector3(1.0, 2.0, 3.0), Vector3(4.0, 5.0, 6.0), Vector3(7.0, 8.0, 9.0))
        val text = json.encodeToString(matrix)
        assertEquals("""{"r0":{"x":1.0,"y":2.0,"z":3.0},"r1":{"x":4.0,"y":5.0,"z":6.0},"r2":{"x":7.0,"y":8.0,"z":9.0}}""", text)
        assertEquals(matrix, json.decodeFromString<Matrix3>(text))
        assertFailsWith<SerializationException> { json.decodeFromString<Vector3>("""{"x":1,"y":2}""") }
    }

    @Test
    fun testCoordinateJsonShapes() {
        assertEquals(
            """{"rightAscension":10.0,"declination":-20.0}""",
            json.encodeToString(EquatorialCoordinate(10.0, -20.0)),
        )
        assertEquals("""{"azimuth":10.0,"altitude":20.0}""", json.encodeToString(HorizontalCoordinate(10.0, 20.0)))
        assertEquals("""{"longitude":10.0,"latitude":20.0}""", json.encodeToString(EclipticCoordinate(10.0, 20.0)))
        assertEquals("""{"longitude":10.0,"latitude":20.0}""", json.encodeToString(GalacticCoordinate(10.0, 20.0)))
        assertEquals(
            """{"latitude":10.0,"longitude":-20.0,"elevation":5.0}""",
            json.encodeToString(GeographicLocation(10.0, -20.0, 5.0)),
        )
        assertEquals(
            """{"raMilliarcsecondsPerYear":1.5,"decMilliarcsecondsPerYear":-2.0}""",
            json.encodeToString(ProperMotion(1.5, -2.0)),
        )
        assertEquals("""{"longitude":-0.5,"obliquity":0.25}""", json.encodeToString(Nutation(-0.5, 0.25)))
        assertEquals("""{"ut":2451545.0,"deltaT":64.0}""", json.encodeToString(JulianDate(2_451_545.0, 64.0)))
        for (coordinate in listOf(EquatorialCoordinate(10.0, -20.0), EquatorialCoordinate(359.5, 89.0))) {
            assertEquals(coordinate, json.decodeFromString<EquatorialCoordinate>(json.encodeToString(coordinate)))
        }
        val location = GeographicLocation(-33.87, 151.21, 50.0)
        assertEquals(location, json.decodeFromString<GeographicLocation>(json.encodeToString(location)))
    }

    @Test
    fun testDecodingStoresValuesAsGivenLikeSwiftSynthesizedCodable() {
        // Swift's synthesized `init(from:)` assigns the stored properties directly: no normalization.
        val equatorial = json.decodeFromString<EquatorialCoordinate>("""{"rightAscension":400,"declination":100}""")
        assertEquals(400.0, equatorial.rightAscension)
        assertEquals(100.0, equatorial.declination)
        val location = json.decodeFromString<GeographicLocation>("""{"latitude":120,"longitude":540,"elevation":0}""")
        assertEquals(120.0, location.latitude)
        assertEquals(540.0, location.longitude)
        assertFailsWith<SerializationException> {
            json.decodeFromString<GeographicLocation>("""{"latitude":1,"longitude":2}""")
        }
    }

    @Test
    fun testObserverAndAtmosphereJsonAlwaysCarryEveryKey() {
        assertEquals(
            """{"pressureMillibars":1010.0,"temperatureCelsius":10.0}""",
            json.encodeToString(Atmosphere.standard),
        )
        val observer = Observer(GeographicLocation(1.0, 2.0, 3.0))
        val text = json.encodeToString(observer)
        assertEquals(
            """{"location":{"latitude":1.0,"longitude":2.0,"elevation":3.0},"atmosphere":{"pressureMillibars":1010.0,"temperatureCelsius":10.0}}""",
            text,
        )
        assertEquals(observer, json.decodeFromString<Observer>(text))
        // Swift's synthesized decoding requires every key, even those with default values.
        assertFailsWith<SerializationException> { json.decodeFromString<Atmosphere>("""{"pressureMillibars":900}""") }
        assertFailsWith<SerializationException> {
            json.decodeFromString<Observer>("""{"location":{"latitude":1,"longitude":2,"elevation":3}}""")
        }
        assertEquals(
            Atmosphere(900.0, 5.0),
            json.decodeFromString<Atmosphere>("""{"pressureMillibars":900,"temperatureCelsius":5}"""),
        )
    }
}

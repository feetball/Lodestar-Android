package net.kuecker.lodestar.kit.foundation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Foundation layer checks against worked examples in Meeus, *Astronomical Algorithms* (2nd ed.). */
class FoundationTests {
    private fun dms(d: Double, m: Double, s: Double): Double {
        val sign = if (d < 0 || (d == 0.0 && (m < 0 || s < 0))) -1.0 else 1.0
        return sign * (abs(d) + abs(m) / 60 + abs(s) / 3600)
    }

    private fun hms(h: Double, m: Double, s: Double): Double = (h + m / 60 + s / 3600) * 15

    // MARK: Math

    @Test
    fun testAngleNormalisation() {
        assertEquals(330.0, AstroMath.normalizeDegrees(-30.0), 1e-12)
        assertEquals(0.5, AstroMath.normalizeDegrees(720.5), 1e-12)
        assertEquals(0.0, AstroMath.normalizeDegrees(-1e-15))
        assertEquals(-170.0, AstroMath.normalizeSignedDegrees(190.0), 1e-12)
        assertEquals(180.0, AstroMath.normalizeSignedDegrees(-180.0), 1e-12)
        assertTrue(AstroMath.normalizeDegrees(Double.NaN).isNaN())
        assertEquals(90.0, AstroMath.asinDeg(1.0000000001), 1e-12)
        assertEquals(17.0, AstroMath.polynomial(2.0, listOf(1.0, 2.0, 3.0)), 1e-12)
    }

    @Test
    fun testVectorBasics() {
        val a = Vector3(1.0, 0.0, 0.0)
        val b = Vector3(0.0, 1.0, 0.0)
        assertEquals(Vector3(0.0, 0.0, 1.0), a.cross(b))
        assertEquals(90.0, a.angle(b), 1e-12)
        assertEquals(Vector3.zero, Vector3.zero.normalized)
        assertEquals(5.0, Vector3(3.0, 4.0, 0.0).length, 1e-12)
        assertEquals(
            AstroMath.degrees(1e-9),
            Vector3(1.0, 0.0, 0.0).angle(Vector3(1.0, 1e-9, 0.0)),
            1e-15,
        )
    }

    @Test
    fun testActiveAndFrameRotations() {
        val quarter = PI / 2
        assertVector(Vector3(0.0, 1.0, 0.0), Matrix3.rotationZ(quarter) * Vector3(1.0, 0.0, 0.0))
        assertVector(Vector3(0.0, 0.0, 1.0), Matrix3.rotationX(quarter) * Vector3(0.0, 1.0, 0.0))
        assertVector(Vector3(1.0, 0.0, 0.0), Matrix3.rotationY(quarter) * Vector3(0.0, 0.0, 1.0))
        assertVector(Vector3(0.0, -1.0, 0.0), Matrix3.frameRotationZ(quarter) * Vector3(1.0, 0.0, 0.0))
        val axis = Vector3(1.0, 2.0, 3.0)
        val r = Matrix3.rotation(axis = axis, angleRadians = 0.7)
        assertEquals(1.0, r.determinant, 1e-12)
        assertVector(axis, r * axis)
        assertMatrix(Matrix3.identity, r * r.transposed)
        val product = Matrix3.rotationX(0.3) * Matrix3.rotationY(-1.1)
        assertVector(
            Matrix3.rotationX(0.3) * (Matrix3.rotationY(-1.1) * Vector3(0.2, -0.5, 0.9)),
            product * Vector3(0.2, -0.5, 0.9),
        )
        val noisy = Matrix3(Vector3(1.01, 0.02, 0.0), Vector3(0.0, 0.98, 0.01), Vector3(0.0, 0.0, 1.02))
        val cleaned = noisy.orthonormalized()
        assertMatrix(Matrix3.identity, cleaned * cleaned.transposed)
        assertEquals(1.0, cleaned.determinant, 1e-12)
    }

    // MARK: Time

    @Test
    fun testJulianDayFromCalendar() {
        // Meeus examples 7.a and the table in ch. 7.
        assertEquals(
            2_436_116.31,
            JulianDate.julianDay(year = 1957, month = 10, day = 4, hour = 19, minute = 26, second = 24.0),
            1e-6,
        )
        assertEquals(2_451_545.0, JulianDate.julianDay(year = 2000, month = 1, day = 1, hour = 12))
        assertEquals(2_446_966.0, JulianDate.julianDay(year = 1987, month = 6, day = 19, hour = 12))
        assertEquals(2_447_187.5, JulianDate.julianDay(year = 1988, month = 1, day = 27))
        assertEquals(2_305_447.5, JulianDate.julianDay(year = 1600, month = 1, day = 1))
        assertEquals(2_415_020.5, JulianDate.julianDay(year = 1900, month = 1, day = 1))
    }

    @Test
    fun testDateRoundTrip() {
        val date = instantFromTimeIntervalSince1970(1_791_331_200.0) // 2026-10-07T00:00:00Z
        val jd = JulianDate(date)
        assertEquals(JulianDate.julianDay(year = 2026, month = 10, day = 7), jd.ut, 1e-9)
        assertEquals(date.timeIntervalSince1970, jd.date.timeIntervalSince1970, 1e-4)
        assertEquals(jd.ut, JulianDate.fromTt(jd.tt).ut, 1e-9)
    }

    @Test
    fun testDeltaT() {
        assertEquals(63.86, JulianDate.deltaT(decimalYear = 2000.0), 0.01)
        assertEquals(69.36, JulianDate.deltaT(decimalYear = 2020.0), 0.01)
        assertEquals(29.07, JulianDate.deltaT(decimalYear = 1950.0), 0.01)
        // No jumps at the segment boundaries.
        for (boundary in listOf(1860.0, 1900.0, 1920.0, 1941.0, 1961.0, 1986.0, 2005.0, 2026.0, 2050.0, 2150.0)) {
            val before = JulianDate.deltaT(decimalYear = boundary - 1e-6)
            val after = JulianDate.deltaT(decimalYear = boundary + 1e-6)
            assertEquals(after, before, 1.5, "ΔT discontinuity at $boundary")
        }
        assertEquals(69.2, JulianDate.deltaT(decimalYear = Double.NaN))
    }

    @Test
    fun testSiderealTime() {
        // Meeus 12.a: 1987 April 10, 0h UT → GMST 13h10m46.3668s.
        val a = JulianDate(2_446_895.5)
        assertEquals(hms(13.0, 10.0, 46.3668), SiderealTime.greenwichMean(a), 0.0005 / 240)
        // Apparent: 13h10m46.1351s with Δψ = −3.788″, ε = 23°26′36.85″.
        val nutation = Nutation(longitude = -3.788 / 3600, obliquity = 9.443 / 3600)
        assertEquals(
            hms(13.0, 10.0, 46.1351),
            SiderealTime.greenwichApparent(a, nutation = nutation, trueObliquity = dms(23.0, 26.0, 36.85)),
            0.001 / 240,
        )
        // Meeus 12.b: 1987 April 10, 19h21m00s UT → 8h34m57.0896s.
        val b = JulianDate(2_446_896.30625)
        assertEquals(hms(8.0, 34.0, 57.0896), SiderealTime.greenwichMean(b), 0.001 / 240)
    }

    // MARK: Earth orientation

    @Test
    fun testNutationAndObliquity() {
        // Meeus 22.a: 1987 April 10, 0h TD.
        val jd = JulianDate.fromTt(2_446_895.5)
        val nutation = Nutation(jd)
        assertEquals(-3.788, nutation.longitude * 3600, 0.002)
        assertEquals(9.443, nutation.obliquity * 3600, 0.002)
        assertEquals(dms(23.0, 26.0, 27.407), Obliquity.mean(jd), 0.001 / 3600)
        assertEquals(dms(23.0, 26.0, 36.850), Obliquity.mean(jd) + nutation.obliquity, 0.003 / 3600)
    }

    @Test
    fun testPrecessionWithProperMotion() {
        // Meeus 21.b: θ Persei, J2000 → 2028 Nov 13.19 TD (mean place of date incl. proper motion).
        val jd = JulianDate.fromTt(2_462_088.69)
        val j2000 = EquatorialCoordinate(rightAscension = hms(2.0, 44.0, 11.986), declination = dms(49.0, 13.0, 42.48))
        val years = (jd.tt - JulianDate.j2000) / 365.25
        // Meeus gives μα = +0.03425 s/yr (not μα*) and μδ = −0.0895″/yr.
        val pm = ProperMotion(
            raMilliarcsecondsPerYear = 0.03425 * 15 * 1000 * AstroMath.cosDeg(j2000.declination),
            decMilliarcsecondsPerYear = -89.5,
        )
        val moved = pm.apply(j2000, years = years)
        val ofDate = EquatorialCoordinate(Precession.matrixFromJ2000(jd) * moved.unitVector)
        assertEquals(41.547214, ofDate.rightAscension, 0.00001)
        assertEquals(49.348483, ofDate.declination, 0.00001)
    }

    @Test
    fun testApparentPlace() {
        // Meeus 23.a: θ Persei apparent place for 2028 Nov 13.19 TD:
        // α = 2h46m14.390s, δ = +49°21′07.45″.
        val jd = JulianDate.fromTt(2_462_088.69)
        val frame = SkyFrame(jd, Observer(GeographicLocation(latitude = 0.0, longitude = 0.0)))
        val j2000 = EquatorialCoordinate(rightAscension = hms(2.0, 44.0, 11.986), declination = dms(49.0, 13.0, 42.48))
        val pm = ProperMotion(
            raMilliarcsecondsPerYear = 0.03425 * 15 * 1000 * AstroMath.cosDeg(j2000.declination),
            decMilliarcsecondsPerYear = -89.5,
        )
        val apparent = frame.apparentPlace(j2000 = j2000, properMotion = pm)
        assertEquals(hms(2.0, 46.0, 14.390), apparent.rightAscension, 0.05 / 3600)
        assertEquals(dms(49.0, 21.0, 7.45), apparent.declination, 0.05 / 3600)
    }

    @Test
    fun testAberrationMagnitude() {
        val beta = Aberration.earthVelocity(JulianDate(2_460_000.5))
        assertEquals(20.5, beta.length * AstroMath.arcsecondsPerRadian, 0.4)
        val jd = JulianDate(2_460_000.5)
        val sunDirection = EquatorialCoordinate(
            Matrix3.rotationX(AstroMath.radians(Obliquity.mean(jd))) *
                EclipticCoordinate(longitude = SolarCoordinates.geometricLongitude(jd), latitude = 0.0).unitVector,
        )
        // The Earth moves perpendicular to the Sun direction (to within the eccentricity terms).
        assertEquals(90.0, beta.normalized.angle(sunDirection.unitVector), 1.5)
    }

    // MARK: Coordinates

    @Test
    fun testEquatorialToHorizontal() {
        // Meeus 13.b: Venus from USNO; H = 64.352133°, δ = −6.719892°, φ = 38.921389°
        // → A = 68.0337° from the south, h = 15.1249°.
        val horizontal = CoordinateTransforms.equatorialToHorizontal(
            hourAngle = 64.352133,
            declination = -6.719892,
            latitude = 38.921389,
        )
        assertEquals(68.0337 + 180, horizontal.azimuth, 0.0002)
        assertEquals(15.1249, horizontal.altitude, 0.0002)
        val back = CoordinateTransforms.horizontalToEquatorial(horizontal, latitude = 38.921389)
        assertEquals(64.352133, back.hourAngle, 1e-9)
        assertEquals(-6.719892, back.declination, 1e-9)
    }

    @Test
    fun testEclipticEquatorialRoundTrip() {
        // Meeus 13.a: Pollux, α = 116.328942°, δ = 28.026183°, ε = 23.4392911° → λ = 113.215630°, β = 6.684170°.
        val pollux = EquatorialCoordinate(rightAscension = 116.328942, declination = 28.026183)
        val ecliptic = CoordinateTransforms.equatorialToEcliptic(pollux, obliquity = 23.4392911)
        assertEquals(113.215630, ecliptic.longitude, 1e-6)
        assertEquals(6.684170, ecliptic.latitude, 1e-6)
        val back = CoordinateTransforms.eclipticToEquatorial(ecliptic, obliquity = 23.4392911)
        assertEquals(pollux.rightAscension, back.rightAscension, 1e-9)
        assertEquals(pollux.declination, back.declination, 1e-9)
    }

    @Test
    fun testGalacticFrame() {
        val centre = CoordinateTransforms.galacticToEquatorialJ2000(GalacticCoordinate(longitude = 0.0, latitude = 0.0))
        assertEquals(266.40499, centre.rightAscension, 0.001)
        assertEquals(-28.93617, centre.declination, 0.001)
        val pole = CoordinateTransforms.galacticToEquatorialJ2000(GalacticCoordinate(longitude = 0.0, latitude = 90.0))
        assertEquals(192.85948, pole.rightAscension, 0.001)
        assertEquals(27.12825, pole.declination, 0.001)
        val sgrA = EquatorialCoordinate(rightAscension = hms(17.0, 45.0, 40.04), declination = dms(-29.0, 0.0, 28.1))
        val galactic = CoordinateTransforms.equatorialJ2000ToGalactic(sgrA)
        assertEquals(-0.0558, AstroMath.normalizeSignedDegrees(galactic.longitude), 0.001)
        assertEquals(-0.0462, galactic.latitude, 0.001)
    }

    @Test
    fun testHorizontalVectorRoundTrip() {
        val cases = listOf(0.0 to 0.0, 90.0 to 45.0, 215.5 to -12.0, 359.9 to 89.5, 10.0 to -89.0)
        for ((az, alt) in cases) {
            val h = HorizontalCoordinate(azimuth = az, altitude = alt)
            val back = HorizontalCoordinate(h.unitVector * 3.7)
            assertEquals(alt, back.altitude, 1e-9)
            assertEquals(0.0, AstroMath.normalizeSignedDegrees(back.azimuth - az), 1e-7)
        }
        val zenith = HorizontalCoordinate(Vector3(0.0, 0.0, 1.0))
        assertEquals(90.0, zenith.altitude)
        assertEquals(0.0, zenith.azimuth)
        // East is azimuth 90°.
        assertEquals(90.0, HorizontalCoordinate(Vector3(1.0, 0.0, 0.0)).azimuth, 1e-12)
    }

    @Test
    fun testGeographicLocationValidation() {
        assertNull(GeographicLocation.validated(latitude = 91.0, longitude = 0.0))
        assertNull(GeographicLocation.validated(latitude = Double.NaN, longitude = 0.0))
        assertNull(GeographicLocation.validated(latitude = 10.0, longitude = Double.POSITIVE_INFINITY))
        assertNull(GeographicLocation.validated(latitude = 10.0, longitude = 10.0, elevation = 50_000.0))
        assertEquals(
            -10.0,
            GeographicLocation.validated(latitude = 10.0, longitude = 350.0)?.longitude ?: 0.0,
            1e-12,
        )
        val clamped = GeographicLocation(latitude = 120.0, longitude = 540.0, elevation = Double.NaN)
        assertEquals(90.0, clamped.latitude)
        assertEquals(180.0, clamped.longitude)
        assertEquals(0.0, clamped.elevation)
    }

    // MARK: Refraction

    @Test
    fun testRefraction() {
        // Meeus 16.a uses Bennett's formula: apparent altitude 0.5° → R ≈ 28.75′.
        assertEquals(28.75, Refraction.refractionForApparentAltitude(0.5) * 60, 0.05)
        // Sæmundsson at the horizon ≈ 29′, and ≈ 1′ at 45°.
        assertEquals(28.9, Refraction.refractionForTrueAltitude(0.0) * 60, 0.3)
        assertEquals(1.0, Refraction.refractionForTrueAltitude(45.0) * 60, 0.05)
        assertEquals(0.0, Refraction.refractionForTrueAltitude(90.0))
        assertEquals(0.0, Refraction.refractionForTrueAltitude(-6.0))
        // Exact inverse, monotonic mapping.
        var previous = Double.NEGATIVE_INFINITY
        for (tenth in -60..900 step 3) {
            val h = tenth.toDouble() / 10
            val apparent = Refraction.apparentAltitude(trueAltitude = h)
            assertTrue(apparent > previous, "apparent altitude $apparent is not above $previous at true altitude $h")
            previous = apparent
            assertEquals(h, Refraction.trueAltitude(apparentAltitude = apparent), 1e-7)
        }
        // Thin, warm air refracts less.
        val thin = Atmosphere(pressureMillibars = 700.0, temperatureCelsius = 25.0)
        assertTrue(
            Refraction.refractionForTrueAltitude(5.0, atmosphere = thin) < Refraction.refractionForTrueAltitude(5.0),
        )
        assertEquals(1.0, Atmosphere(pressureMillibars = Double.NaN, temperatureCelsius = 10.0).refractionScale)
    }

    // MARK: SkyFrame

    @Test
    fun testCelestialPoleAltitudeEqualsLatitude() {
        for (latitude in listOf(-60.0, -10.0, 0.5, 40.0, 78.0)) {
            val frame = SkyFrame(
                instantFromTimeIntervalSince1970(1_790_000_000.0),
                Observer(GeographicLocation(latitude = latitude, longitude = 12.0)),
            )
            val north = frame.horizontalOfCelestialPole(north = true, refraction = false)
            assertEquals(latitude, north.altitude, 1e-9)
            if (latitude != 90.0) assertEquals(0.0, AstroMath.normalizeSignedDegrees(north.azimuth), 1e-6)
            val south = frame.horizontalOfCelestialPole(north = false, refraction = false)
            assertEquals(-latitude, south.altitude, 1e-9)
        }
    }

    @Test
    fun testObjectOnMeridianAtZenith() {
        // A star whose apparent RA equals the local sidereal time and whose apparent declination
        // equals the latitude must be at the zenith.
        val frame = SkyFrame(
            instantFromTimeIntervalSince1970(1_800_000_000.0),
            Observer(GeographicLocation(latitude = 35.0, longitude = -100.0)),
        )
        val apparent = EquatorialCoordinate(rightAscension = frame.localApparentSiderealTime, declination = 35.0)
        val horizontal = HorizontalCoordinate(frame.equatorialOfDateToHorizontal * apparent.unitVector)
        assertEquals(90.0, horizontal.altitude, 1e-9)
        assertEquals(0.0, frame.hourAngle(apparentRightAscension = apparent.rightAscension), 1e-9)
    }

    @Test
    fun testHorizontalJ2000RoundTrip() {
        val frame = SkyFrame(
            instantFromTimeIntervalSince1970(1_760_000_000.0),
            Observer(GeographicLocation(latitude = -33.87, longitude = 151.21, elevation = 50.0)),
        )
        val vega = EquatorialCoordinate(rightAscension = 279.23473, declination = 38.78369)
        for (refraction in listOf(false, true)) {
            val horizontal = frame.horizontal(j2000 = vega, refraction = refraction)
            val back = frame.j2000(horizontal, refracted = refraction)
            assertEquals(0.0, back.angularSeparation(vega) * 3600, 0.05)
        }
        val fast = HorizontalCoordinate(frame.horizontalVector(j2000UnitVector = vega.unitVector))
        val full = frame.horizontal(j2000 = vega, refraction = false)
        assertEquals(0.0, fast.angularSeparation(full) * 3600, 1e-6)
    }

    @Test
    fun testTopocentricParallaxOfNearbyBody() {
        // A body 60 Earth radii away on the observer's horizon is displaced downward by ≈ its
        // horizontal parallax (~57′); at the zenith there is no displacement.
        val observer = Observer(GeographicLocation(latitude = 0.0, longitude = 0.0))
        val frame = SkyFrame(instantFromTimeIntervalSince1970(1_770_000_000.0), observer)
        val distance = 60 * SkyFrame.earthEquatorialRadiusKilometers / SkyFrame.kilometersPerAU
        val zenithOfDate = frame.equatorialOfDateToHorizontal.transposed * Vector3(0.0, 0.0, 1.0)
        val zenithJ2000 = frame.precessionNutationMatrix.transposed * zenithOfDate
        val atZenith = frame.horizontal(geocentricJ2000 = zenithJ2000 * distance, refraction = false)
        assertEquals(90.0, atZenith.altitude, 1e-6)
        val eastOfDate = frame.equatorialOfDateToHorizontal.transposed * Vector3(1.0, 0.0, 0.0)
        val eastJ2000 = frame.precessionNutationMatrix.transposed * eastOfDate
        val atHorizon = frame.horizontal(geocentricJ2000 = eastJ2000 * distance, refraction = false)
        assertEquals(-AstroMath.degrees(asin(1.0 / 60)), atHorizon.altitude, 0.01)
    }

    // MARK: Helpers

    private fun assertVector(expected: Vector3, actual: Vector3, accuracy: Double = 1e-12) {
        assertEquals(expected.x, actual.x, accuracy)
        assertEquals(expected.y, actual.y, accuracy)
        assertEquals(expected.z, actual.z, accuracy)
    }

    private fun assertMatrix(expected: Matrix3, actual: Matrix3, accuracy: Double = 1e-12) {
        assertVector(expected.r0, actual.r0, accuracy)
        assertVector(expected.r1, actual.r1, accuracy)
        assertVector(expected.r2, actual.r2, accuracy)
    }
}

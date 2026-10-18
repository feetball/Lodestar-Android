package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Orbital elements and two-body propagation of comets and asteroids. */
class CometsOrbitTests {
    private fun elements(
        q: Double = 1.0,
        e: Double = 0.5,
        i: Double = 0.0,
        w: Double = 0.0,
        node: Double = 0.0,
        t: Double = 2_460_000.0,
        epoch: Double? = null,
    ): SmallBodyElements = assertNotNull(SmallBodyElements.create(q, e, i, w, node, t, epoch))

    // MARK: Elements

    @Test
    fun validElementsKeepTheirValues() {
        val orbit = elements(q = 0.5, e = 0.9, i = 30.0, w = 120.0, node = 250.0, t = 2_460_100.5, epoch = 2_460_000.5)
        assertEquals(0.5, orbit.perihelionDistanceAU)
        assertEquals(0.9, orbit.eccentricity)
        assertEquals(30.0, orbit.inclination)
        assertEquals(120.0, orbit.argumentOfPerihelion)
        assertEquals(250.0, orbit.longitudeOfAscendingNode)
        assertEquals(2_460_100.5, orbit.perihelionJulianDayTT)
        assertEquals(2_460_000.5, orbit.epochJulianDayTT)
    }

    @Test
    fun epochDefaultsToThePerihelionTime() {
        assertEquals(2_460_000.0, elements().epochJulianDayTT)
    }

    @Test
    fun anglesAreReducedToTheirRange() {
        val orbit = elements(w = -30.0, node = 725.0)
        assertEquals(330.0, orbit.argumentOfPerihelion, 1e-12)
        assertEquals(5.0, orbit.longitudeOfAscendingNode, 1e-12)
    }

    @Test
    fun invalidElementsAreRejected() {
        assertNull(SmallBodyElements.create(0.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(-1.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(0.000_001, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1_000.1, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, -0.1, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 100.1, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 180.1, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, -0.1, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, Double.NaN, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, Double.POSITIVE_INFINITY, 2_460_000.0))
        assertNull(SmallBodyElements.create(Double.NaN, 0.5, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, Double.NaN, 0.0, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, Double.NaN, 0.0, 0.0, 2_460_000.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, -1.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, 6_000_001.0))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, Double.NaN))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0, Double.NaN))
        assertNull(SmallBodyElements.create(1.0, 0.5, 0.0, 0.0, 0.0, 2_460_000.0, 7_000_000.0))
    }

    @Test
    fun boundaryValuesAreAccepted() {
        assertNotNull(SmallBodyElements.create(0.00001, 0.0, 0.0, 0.0, 0.0, 0.0))
        assertNotNull(SmallBodyElements.create(1000.0, 100.0, 180.0, 360.0, 360.0, 6_000_000.0))
    }

    @Test
    fun shapeFollowsTheEccentricity() {
        assertEquals(OrbitShape.ELLIPTIC, elements(e = 0.999_999_9).shape)
        assertEquals(OrbitShape.PARABOLIC, elements(e = 1.0).shape)
        assertEquals(OrbitShape.HYPERBOLIC, elements(e = 1.000_000_1).shape)
        assertEquals(OrbitShape.ELLIPTIC, elements(e = 0.0).shape)
    }

    @Test
    fun derivedQuantitiesOfAnEllipse() {
        // Ceres (JPL SBDB): a = 2.765552595 AU, e = 0.07969229515 -> q = 2.545159361, Q = 2.985945829, P = 1679.853 d.
        val orbit = elements(q = 2.545_159_361_382_861, e = 0.079_692_295_148_165_86)
        assertEquals(2.765_552_595_034_094, assertNotNull(orbit.semiMajorAxisAU), 1e-9)
        assertEquals(2.985_945_828_685_327, assertNotNull(orbit.aphelionDistanceAU), 1e-9)
        assertEquals(1_679.853_119_758_983, assertNotNull(orbit.periodDays), 2e-3)
        assertEquals(1_679.853_119_758_983 / 365.25, assertNotNull(orbit.periodYears), 1e-5)
        assertEquals(0.214_304_450_648_43, assertNotNull(orbit.meanMotion), 1e-8)
    }

    @Test
    fun unboundOrbitsHaveNoPeriod() {
        for (e in listOf(1.0, 1.5, 6.1)) {
            val orbit = elements(e = e)
            assertNull(orbit.semiMajorAxisAU)
            assertNull(orbit.aphelionDistanceAU)
            assertNull(orbit.meanMotion)
            assertNull(orbit.periodDays)
            assertNull(orbit.periodYears)
            assertNull(orbit.meanAnomaly(2_460_010.0))
        }
    }

    @Test
    fun meanAnomalyGrowsFromPerihelion() {
        val orbit = elements(q = 1.0, e = 0.0, t = 2_460_000.0)
        // A circular orbit of radius 1 AU has the Gaussian mean motion, 0.9856076686 degrees per day.
        assertEquals(0.985_607_668_6, assertNotNull(orbit.meanMotion), 1e-9)
        assertEquals(0.0, assertNotNull(orbit.meanAnomaly(2_460_000.0)), 1e-12)
        assertEquals(98.560_766_86, assertNotNull(orbit.meanAnomaly(2_460_100.0)), 1e-7)
        assertEquals(360.0 - 98.560_766_86, assertNotNull(orbit.meanAnomaly(2_459_900.0)), 1e-7)
    }

    @Test
    fun meanAnomalyConstructorRecoversTheAnomalyAtTheEpoch() {
        // Vesta (MPC): epoch 2026 June 9, M = 81.19019 degrees, a = 2.3613660 AU, e = 0.0902037.
        val orbit = assertNotNull(
            SmallBodyElements.fromMeanAnomaly(2.361_366, 0.090_203_7, 7.143_93, 151.468_56, 103.701_35, 81.190_19, 2_461_200.5),
        )
        assertEquals(81.190_19, assertNotNull(orbit.meanAnomaly(2_461_200.5)), 1e-9)
        assertEquals(2.361_366 * (1 - 0.090_203_7), orbit.perihelionDistanceAU, 1e-12)
        assertEquals(2_461_200.5, orbit.epochJulianDayTT)
        // JPL's time of perihelion for the same orbit: 2460901.5874.
        assertEquals(2_460_901.587_4, orbit.perihelionJulianDayTT, 2e-3)
    }

    @Test
    fun meanAnomalyConstructorReducesTheAnomaly() {
        val a = assertNotNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        val b = assertNotNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 5.0, 10.0, 20.0, 400.0, 2_460_000.5))
        val c = assertNotNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 5.0, 10.0, 20.0, -320.0, 2_460_000.5))
        assertEquals(a.perihelionJulianDayTT, b.perihelionJulianDayTT, 1e-9)
        assertEquals(a.perihelionJulianDayTT, c.perihelionJulianDayTT, 1e-9)
    }

    @Test
    fun meanAnomalyConstructorRejectsInvalidInput() {
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, 1.0, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, 1.5, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, -0.1, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(0.0, 0.1, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(-2.0, 0.1, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(Double.NaN, 0.1, 5.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 5.0, 10.0, 20.0, Double.NaN, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 200.0, 10.0, 20.0, 40.0, 2_460_000.5))
        assertNull(SmallBodyElements.fromMeanAnomaly(2.0, 0.1, 5.0, 10.0, 20.0, 40.0, -5.0))
    }

    // MARK: Stumpff functions

    @Test
    fun stumpffFunctionsAtZero() {
        assertEquals(0.5, OrbitPropagator.stumpffC(0.0), 1e-15)
        assertEquals(1.0 / 6.0, OrbitPropagator.stumpffS(0.0), 1e-15)
    }

    @Test
    fun stumpffFunctionsSatisfyTheirDefiningIdentities() {
        // 1 - z C(z) = cos(sqrt z) and 1 - z S(z) = sin(sqrt z) / sqrt z, with cosh and sinh for z < 0.
        for (z in listOf(-400.0, -50.0, -4.0, -1.5, -1.0, -0.999, -0.3, -1e-6, 1e-9, 0.2, 0.999, 1.0, 1.001, 2.5, 4.0, 9.8696, 30.0, 400.0)) {
            val c = OrbitPropagator.stumpffC(z)
            val s = OrbitPropagator.stumpffS(z)
            val cosine = if (z >= 0) cos(sqrt(z)) else cosh(sqrt(-z))
            val sinc = if (z > 0) sin(sqrt(z)) / sqrt(z) else if (z < 0) sinh(sqrt(-z)) / sqrt(-z) else 1.0
            val scale = max(1.0, abs(cosine))
            assertEquals(cosine, 1 - z * c, 1e-12 * scale, "cos identity at z = $z")
            assertEquals(sinc, 1 - z * s, 1e-12 * max(1.0, abs(sinc)), "sin identity at z = $z")
        }
    }

    @Test
    fun stumpffFunctionsAreContinuousAcrossTheSeriesBoundary() {
        for (z in listOf(1.0, -1.0)) {
            assertEquals(OrbitPropagator.stumpffC(z - 1e-9), OrbitPropagator.stumpffC(z + 1e-9), 1e-8)
            assertEquals(OrbitPropagator.stumpffS(z - 1e-9), OrbitPropagator.stumpffS(z + 1e-9), 1e-8)
        }
        // Known values: C(4) = (1 - cos 2) / 4 and S(4) = (2 - sin 2) / 8.
        assertEquals((1 - cos(2.0)) / 4, OrbitPropagator.stumpffC(4.0), 1e-15)
        assertEquals((2 - sin(2.0)) / 8, OrbitPropagator.stumpffS(4.0), 1e-15)
        assertEquals((cosh(2.0) - 1) / 4, OrbitPropagator.stumpffC(-4.0), 1e-15)
        assertEquals((sinh(2.0) - 2) / 8, OrbitPropagator.stumpffS(-4.0), 1e-15)
    }

    // MARK: Kepler's equation against an independent solver

    @Test
    fun universalSolverMatchesClassicalAnomaliesForEveryEccentricity() {
        var worst = 0.0
        for (case in CometsKeplerData.cases) {
            val position = assertNotNull(OrbitPropagator.perifocal(case.q, case.e, case.days), "e = ${case.e}, dt = ${case.days}")
            val error = max(abs(position.x - case.x), abs(position.y - case.y))
            val tolerance = 1e-10 * max(1.0, case.r)
            assertTrue(error <= tolerance, "e = ${case.e}, q = ${case.q}, dt = ${case.days}: error $error AU")
            assertEquals(case.r, position.r, tolerance, "distance e = ${case.e}, dt = ${case.days}")
            worst = max(worst, error / max(1.0, case.r))
        }
        assertTrue(worst < 1e-10, "worst relative error $worst")
    }

    @Test
    fun meeusExampleForKeplersEquation() {
        // Meeus, Astronomical Algorithms, Example 30.a: e = 0.1, M = 5 degrees gives E = 5.554589 degrees.
        // For a = 1 AU the mean motion is k radians per day, so M = 5 degrees takes 5 / 0.9856076686 days.
        val q = 0.9
        val days = 5.0 / AstroMath.degrees(OrbitPropagator.gaussianGravitationalConstant)
        val position = assertNotNull(OrbitPropagator.perifocal(q, 0.1, days))
        val eccentricAnomaly = AstroMath.radians(5.554_589)
        assertEquals(cos(eccentricAnomaly) - 0.1, position.x, 2e-8)
        assertEquals(sqrt(1 - 0.01) * sin(eccentricAnomaly), position.y, 2e-8)
    }

    @Test
    fun parabolicOrbitFollowsBarkersEquation() {
        // Meeus ch. 34: for e = 1, r = q (1 + s^2) with s = tan(nu / 2) and s + s^3 / 3 = k dt / sqrt(2 q^3).
        val q = 0.921
        val days = 52.5
        val position = assertNotNull(OrbitPropagator.perifocal(q, 1.0, days))
        val w = OrbitPropagator.gaussianGravitationalConstant * days / sqrt(2 * q * q * q)
        var s = w
        repeat(60) { s -= (s + s * s * s / 3 - w) / (1 + s * s) }
        assertEquals(q * (1 - s * s), position.x, 1e-12)
        assertEquals(2 * q * s, position.y, 1e-12)
        assertEquals(q * (1 + s * s), position.r, 1e-12)
    }

    // MARK: Invariants

    @Test
    fun positionIsContinuousThroughEccentricityOne() {
        for (days in listOf(-120.0, 3.0, 60.0, 900.0)) {
            val below = assertNotNull(OrbitPropagator.perifocal(0.8, 0.999_999_9, days))
            val parabola = assertNotNull(OrbitPropagator.perifocal(0.8, 1.0, days))
            val above = assertNotNull(OrbitPropagator.perifocal(0.8, 1.000_000_1, days))
            assertEquals(parabola.x, below.x, 1e-5)
            assertEquals(parabola.y, below.y, 1e-5)
            assertEquals(parabola.x, above.x, 1e-5)
            assertEquals(parabola.y, above.y, 1e-5)
        }
    }

    @Test
    fun ellipseRepeatsAfterOnePeriod() {
        val orbit = elements(q = 0.587, e = 0.967, i = 162.0, w = 112.0, node = 59.0, t = 2_446_470.0)
        val period = assertNotNull(orbit.periodDays)
        for (offset in listOf(-3000.0, 0.0, 40.0, 4000.0)) {
            val first = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, 2_446_470.0 + offset))
            for (cycles in listOf(1, 7, -4)) {
                val later = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, 2_446_470.0 + offset + cycles * period))
                assertTrue((first - later).length < 2e-9 * max(1.0, first.length), "offset $offset, cycles $cycles")
            }
        }
    }

    @Test
    fun visVivaAndAngularMomentumHoldAlongTheOrbit() {
        val mu = OrbitPropagator.gaussianGravitationalConstant * OrbitPropagator.gaussianGravitationalConstant
        for (e in listOf(0.3, 0.97, 1.0, 1.0004, 2.2)) {
            val orbit = elements(q = 0.6, e = e, i = 35.0, w = 80.0, node = 200.0, t = 2_460_000.0)
            val expectedMomentum = sqrt(mu * 0.6 * (1 + e))
            for (days in listOf(-300.0, -20.0, 0.5, 45.0, 700.0)) {
                val step = 0.01
                val before = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, 2_460_000.0 + days - step))
                val here = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, 2_460_000.0 + days))
                val after = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, 2_460_000.0 + days + step))
                val velocity = (after - before) / (2 * step)
                val momentum = here.cross(velocity).length
                assertEquals(expectedMomentum, momentum, 2e-7 * expectedMomentum, "e = $e, dt = $days")
                val inverseAxis = (1 - e) / 0.6
                val expectedSpeedSquared = mu * (2 / here.length - inverseAxis)
                assertEquals(expectedSpeedSquared, velocity.dot(velocity), 1e-6 * expectedSpeedSquared, "e = $e, dt = $days")
            }
        }
    }

    @Test
    fun perihelionIsTheClosestPoint() {
        for (e in listOf(0.2, 1.0, 3.0)) {
            val orbit = elements(q = 0.75, e = e, t = 2_460_000.0)
            val atPerihelion = assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_460_000.0))
            assertEquals(0.75, atPerihelion, 1e-12)
            for (days in listOf(-30.0, -1.0, 1.0, 30.0)) {
                assertTrue(assertNotNull(OrbitPropagator.heliocentricDistance(orbit, 2_460_000.0 + days)) > 0.75)
            }
        }
    }

    // MARK: Orientation

    @Test
    fun orientationAnglesRotateThePlane() {
        val t = 2_460_000.0
        // Zero angles: perihelion on the +x axis of the ecliptic, motion towards +y.
        val flat = elements(q = 1.0, e = 0.5, t = t)
        val flatPerihelion = assertNotNull(OrbitPropagator.heliocentricEcliptic(flat, t))
        assertEquals(1.0, flatPerihelion.x, 1e-12)
        assertEquals(0.0, flatPerihelion.y, 1e-12)
        assertEquals(0.0, flatPerihelion.z, 1e-12)
        assertTrue(assertNotNull(OrbitPropagator.heliocentricEcliptic(flat, t + 5)).y > 0)
        // The argument of perihelion turns the perihelion within the plane.
        val turned = assertNotNull(OrbitPropagator.heliocentricEcliptic(elements(q = 1.0, e = 0.5, w = 90.0, t = t), t))
        assertEquals(0.0, turned.x, 1e-12)
        assertEquals(1.0, turned.y, 1e-12)
        // The node turns the whole orbit about the pole.
        val node = assertNotNull(OrbitPropagator.heliocentricEcliptic(elements(q = 1.0, e = 0.5, node = 90.0, t = t), t))
        assertEquals(0.0, node.x, 1e-12)
        assertEquals(1.0, node.y, 1e-12)
        // With the perihelion at the ascending node (w = 0) and i = 90 degrees the orbit climbs towards +z.
        val polar = elements(q = 1.0, e = 0.5, i = 90.0, t = t)
        val climbing = assertNotNull(OrbitPropagator.heliocentricEcliptic(polar, t + 20))
        assertTrue(climbing.z > 0.1)
        assertEquals(0.0, climbing.y, 1e-12)
        // A retrograde orbit (i = 180) runs towards -y.
        assertTrue(assertNotNull(OrbitPropagator.heliocentricEcliptic(elements(i = 180.0, t = t), t + 5)).y < 0)
    }

    @Test
    fun equatorialFrameIsTheEclipticTiltedByTheObliquityOfJ2000() {
        val t = 2_460_000.0
        val orbit = elements(q = 1.0, e = 0.0, w = 90.0, t = t)
        val ecliptic = assertNotNull(OrbitPropagator.heliocentricEcliptic(orbit, t))
        val equatorial = assertNotNull(OrbitPropagator.heliocentricJ2000(orbit, t))
        val obliquity = AstroMath.radians(Obliquity.j2000)
        assertEquals(ecliptic.x, equatorial.x, 1e-14)
        assertEquals(ecliptic.y * cos(obliquity) - ecliptic.z * sin(obliquity), equatorial.y, 1e-14)
        assertEquals(ecliptic.y * sin(obliquity) + ecliptic.z * cos(obliquity), equatorial.z, 1e-14)
        assertEquals(ecliptic.length, equatorial.length, 1e-14)
        // The +y ecliptic axis points to RA 90 degrees, dec 23.44 degrees.
        assertEquals(Obliquity.j2000, EquatorialCoordinate(equatorial).declination, 1e-9)
    }

    // MARK: Domain

    @Test
    fun unboundOrbitsAreLimitedToTheSpanAroundPerihelion() {
        val orbit = elements(q = 0.4, e = 1.0002, t = 2_460_000.0)
        assertNotNull(OrbitPropagator.heliocentricJ2000(orbit, 2_460_000.0 + 49_999.0))
        assertNotNull(OrbitPropagator.heliocentricJ2000(orbit, 2_460_000.0 - 49_999.0))
        assertNull(OrbitPropagator.heliocentricJ2000(orbit, 2_460_000.0 + 50_001.0))
        assertNull(OrbitPropagator.heliocentricJ2000(orbit, 2_460_000.0 - 50_001.0))
        assertNull(OrbitPropagator.heliocentricDistance(orbit, 2_460_000.0 + 1e9))
        assertEquals(50_000.0, OrbitPropagator.maximumUnboundSpanDays)
    }

    @Test
    fun ellipsesWorkForAnyDate() {
        val orbit = elements(q = 2.0, e = 0.1, t = 2_460_000.0)
        for (jd in listOf(0.0, 1e5, 5e6, 5.9e6)) {
            assertNotNull(OrbitPropagator.heliocentricJ2000(orbit, jd))
        }
    }

    @Test
    fun nonFiniteDatesGiveNothing() {
        val orbit = elements()
        assertNull(OrbitPropagator.heliocentricJ2000(orbit, Double.NaN))
        assertNull(OrbitPropagator.heliocentricJ2000(orbit, Double.POSITIVE_INFINITY))
        assertNull(OrbitPropagator.heliocentricEcliptic(orbit, Double.NEGATIVE_INFINITY))
        assertNull(OrbitPropagator.heliocentricDistance(orbit, Double.NaN))
    }

    @Test
    fun nearParabolicEllipseWithAHugePeriodStillSolves() {
        // e = 1 - 1e-12 has an enormous semi-major axis; the position stays finite and close to the parabola.
        val orbit = elements(q = 1.0, e = 1.0 - 1e-12, t = 2_460_000.0)
        val position = assertNotNull(OrbitPropagator.perifocal(1.0, 1.0 - 1e-12, 100.0))
        val parabola = assertNotNull(OrbitPropagator.perifocal(1.0, 1.0, 100.0))
        assertEquals(parabola.x, position.x, 1e-9)
        assertEquals(parabola.y, position.y, 1e-9)
        assertNotNull(orbit.periodDays)
    }

    @Test
    fun invalidInputsToTheSolverGiveNothing() {
        // The semi-major axis overflows, so the period is not a number.
        assertNull(OrbitPropagator.perifocal(1e300, 1.0 - 1e-16, 1.0))
        assertNull(OrbitPropagator.perifocal(-1.0, 0.5, 1.0))
        assertNull(OrbitPropagator.perifocal(0.0, 0.5, 1.0))
        assertNull(OrbitPropagator.perifocal(1.0, -0.5, 1.0))
        assertNull(OrbitPropagator.perifocal(Double.NaN, 0.5, 1.0))
        assertNull(OrbitPropagator.perifocal(1.0, Double.NaN, 1.0))
    }

    @Test
    fun solverIsFast() {
        var checksum = 0.0
        val started = System.nanoTime()
        for (index in 0 until 200_000) {
            val e = when (index % 4) {
                0 -> 0.1
                1 -> 0.97
                2 -> 1.0
                else -> 1.5
            }
            val position = assertNotNull(OrbitPropagator.perifocal(0.8, e, (index % 997) - 400.0))
            checksum += position.r
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(checksum > 0)
        assertTrue(seconds < 5.0, "200 000 solutions took $seconds s")
        assertFalse(checksum.isNaN())
    }
}

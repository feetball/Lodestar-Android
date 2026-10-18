package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `Ephemeris` against the worked examples of Meeus, *Astronomical Algorithms* (2nd ed.), values
 * computed with JPL DE440s, and structural invariants of `BodyState`.
 */
class EphemerisMeeusTests {
    private val greenwich = Observer(GeographicLocation(0.0, 0.0))

    // MARK: Moon

    /** Example 47.a: the Moon on 1992 April 12 at 0h TD (JDE 2448724.5). */
    @Test
    fun testLunarSeriesReproducesMeeusExample47a() {
        val centuries = (2_448_724.5 - JulianDate.j2000) / JulianDate.daysPerJulianCentury
        val moon = LunarTheory.geocentricEcliptic(centuries)
        assertEquals(133.162655, moon.longitude, 1e-5)
        assertEquals(-3.229126, moon.latitude, 1e-5)
        assertEquals(368_409.7, moon.distanceKilometers, 0.05)
    }

    @Test
    fun testMoonApparentPlaceMeeusExample47a() {
        // The book gives α = 134.688470°, δ = +13.768368° without light-time (0.7″ in longitude);
        // DE440s gives α = 134.687915°, δ = +13.768449°, the truncated series' own error.
        val jd = JulianDate.fromTt(2_448_724.5)
        val frame = SkyFrame(jd, greenwich)
        val apparent = frame.geocentricApparentEquatorial(SolarSystemBody.MOON)
        assertEquals(134.688470, apparent.rightAscension, 0.0004)
        assertEquals(13.768368, apparent.declination, 0.0004)
        val state = frame.state(SolarSystemBody.MOON)
        assertEquals(368_409.7, state.distanceKilometers, 0.5)
        // Apparent longitude: λ + Δψ (+0.004610°) minus the Moon's motion during the light-time (0.7″).
        assertEquals(133.162655 + 0.004610, state.eclipticLongitudeOfDate, 0.0004)
        assertEquals(-3.229126, state.eclipticLatitudeOfDate, 0.0004)
    }

    /**
     * Example 48.a: the same instant; the book finds i = 69.0756° and k = 0.6786 (with its own
     * low-accuracy Sun). DE440s gives i = 69.0840°, k = 0.67850.
     */
    @Test
    fun testMoonPhaseMeeusExample48a() {
        val state = Ephemeris.state(SolarSystemBody.MOON, JulianDate.fromTt(2_448_724.5))
        assertEquals(0.6786, assertNotNull(state.illuminatedFraction), 0.0005)
        assertEquals(69.0756, assertNotNull(state.phaseAngle), 0.01)
        assertEquals(110.7929, assertNotNull(state.elongation), 0.002)
        assertEquals(true, state.isWaxing)
        // 368 410 km: the disc is 2 · 1737.4 km across, 32.4′.
        assertEquals(1945.5, state.angularDiameterArcseconds, 0.5)
    }

    /**
     * The Moon is not corrected for annual aberration: that, 20.5″ at its largest, cancels against
     * the Earth's motion during the light-time, leaving only the Moon's own motion (0.7″).
     */
    @Test
    fun testMoonDirectionHasNoAnnualAberration() {
        val jd = JulianDate.fromTt(2_459_000.25)
        val geometric = LunarTheory.geocentricEcliptic(jd.centuriesTT)
        val geometricJ2000 = Matrix3.rotationX(AstroMath.radians(Obliquity.mean(jd))) *
            EclipticCoordinate(geometric.longitude, geometric.latitude).unitVector
        val ofDate = Precession.matrixFromJ2000(jd).transposed * geometricJ2000
        val apparent = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, jd)
        assertTrue(ofDate.angle(apparent) * 3600 < 1.5)
    }

    // MARK: Sun

    /** Example 25.a (low accuracy, 0.01°): the Sun on 1992 October 13 at 0h TD. */
    @Test
    fun testSunMeeusExample25a() {
        val jd = JulianDate.fromTt(2_448_908.5)
        val frame = SkyFrame(jd, greenwich)
        val apparent = frame.geocentricApparentEquatorial(SolarSystemBody.SUN)
        assertEquals(198.38083, apparent.rightAscension, 0.01)
        assertEquals(-7.78507, apparent.declination, 0.01)
        assertEquals(0.99766, frame.state(SolarSystemBody.SUN).distanceAU, 0.0001)
    }

    /**
     * Example 25.b (VSOP87, about 1″), cross-checked against DE440s, which gives α = 198.378134°,
     * δ = −7.783811° and R = 0.99760852 AU.
     */
    @Test
    fun testSunMeeusExample25b() {
        val jd = JulianDate.fromTt(2_448_908.5)
        val frame = SkyFrame(jd, greenwich)
        val apparent = frame.geocentricApparentEquatorial(SolarSystemBody.SUN)
        assertEquals(198.378178, apparent.rightAscension, 0.0003)
        assertEquals(-7.783871, apparent.declination, 0.0003)
        assertEquals(
            0.0,
            apparent.angularSeparation(EquatorialCoordinate(198.378134, -7.783811)) * 3600,
            1.0,
        )
        val state = frame.state(SolarSystemBody.SUN)
        assertEquals(0.99760852, state.distanceAU, 0.00001)
        // Apparent ecliptic longitude (nutation and aberration included): 199.905998° from the DE440s place
        // and the true obliquity of date, 199.90606° in the book. The Sun's latitude is 0.0002°.
        assertEquals(199.906, state.eclipticLongitudeOfDate, 0.0003)
        assertEquals(0.0002, state.eclipticLatitudeOfDate, 0.0004)
    }

    @Test
    fun testSunPhysicalAppearance() {
        val state = Ephemeris.state(SolarSystemBody.SUN, JulianDate(2_460_000.5))
        assertEquals(32.0, state.angularDiameterArcseconds / 60, 0.6)
        assertEquals(-26.74, assertNotNull(state.apparentMagnitude), 0.1)
        assertNull(state.heliocentricDistanceAU)
        assertNull(state.elongation)
        assertNull(state.phaseAngle)
        assertNull(state.illuminatedFraction)
        assertNull(state.isWaxing)
        // Brighter at perihelion than at aphelion.
        val january = Ephemeris.state(SolarSystemBody.SUN, JulianDate(JulianDate.julianDay(2026, 1, 4)))
        val july = Ephemeris.state(SolarSystemBody.SUN, JulianDate(JulianDate.julianDay(2026, 7, 6)))
        assertTrue(january.distanceAU < 0.984)
        assertTrue(july.distanceAU > 1.016)
        assertTrue(january.angularDiameterArcseconds > july.angularDiameterArcseconds)
    }

    // MARK: Planets

    /** Distance ranges over 1900–2100 from DE440s, widened by a little. */
    @Test
    fun testPlanetDistancesStayWithinTheirOrbits() {
        val heliocentricRange: Map<SolarSystemBody, ClosedFloatingPointRange<Double>> = mapOf(
            SolarSystemBody.MERCURY to 0.30..0.47, SolarSystemBody.VENUS to 0.71..0.73,
            SolarSystemBody.MARS to 1.37..1.67, SolarSystemBody.JUPITER to 4.94..5.47,
            SolarSystemBody.SATURN to 9.0..10.1, SolarSystemBody.URANUS to 18.2..20.15,
            SolarSystemBody.NEPTUNE to 29.7..30.4,
        )
        val geocentricRange: Map<SolarSystemBody, ClosedFloatingPointRange<Double>> = mapOf(
            SolarSystemBody.MERCURY to 0.54..1.46, SolarSystemBody.VENUS to 0.26..1.74,
            SolarSystemBody.MARS to 0.37..2.68, SolarSystemBody.JUPITER to 3.9..6.5,
            SolarSystemBody.SATURN to 7.9..11.1, SolarSystemBody.URANUS to 17.2..21.1,
            SolarSystemBody.NEPTUNE to 28.7..31.4,
        )
        for ((body, helio) in heliocentricRange) {
            for (step in 0 until 120) {
                val jd = JulianDate(2_451_545 + step.toDouble() * 197.3)
                val state = Ephemeris.state(body, jd)
                assertTrue(helio.contains(assertNotNull(state.heliocentricDistanceAU)), "$body r at step $step")
                assertTrue(assertNotNull(geocentricRange[body]).contains(state.distanceAU), "$body Δ at step $step")
            }
        }
    }

    /** DE440s: Mars at opposition on 2020 October 13, 23h UT, 0.4192 AU away, magnitude −2.69. */
    @Test
    fun testMarsOppositionOfOctober2020() {
        val jd = JulianDate(JulianDate.julianDay(2020, 10, 13, 23))
        val state = Ephemeris.state(SolarSystemBody.MARS, jd)
        assertEquals(0.4192, state.distanceAU, 0.0005)
        assertEquals(177.0, assertNotNull(state.elongation), 0.05)
        assertEquals(-2.69, assertNotNull(state.apparentMagnitude), 0.05)
        assertEquals(0.9997, assertNotNull(state.illuminatedFraction), 0.0005)
        assertEquals(22.3, state.angularDiameterArcseconds, 0.1)
    }

    /**
     * DE440s: Venus at inferior conjunction on 2020 June 3 (0.2886 AU, a 58″ crescent) and at greatest
     * western elongation on 2020 August 13 (45.8°, half lit, magnitude −4.43).
     */
    @Test
    fun testVenusAtInferiorConjunctionAndGreatestElongation() {
        val conjunctionDate = JulianDate.julianDay(2020, 6, 3, 12)
        val conjunction = Ephemeris.state(SolarSystemBody.VENUS, JulianDate(conjunctionDate))
        assertEquals(0.66, assertNotNull(conjunction.elongation), 0.02)
        assertEquals(0.2886, conjunction.distanceAU, 0.0005)
        assertEquals(57.8, conjunction.angularDiameterArcseconds, 0.3)
        assertTrue(assertNotNull(conjunction.illuminatedFraction) < 0.0005)
        assertTrue(assertNotNull(conjunction.phaseAngle) > 178)
        assertEquals(-3.2, assertNotNull(conjunction.apparentMagnitude), 0.1)

        val elongation = Ephemeris.state(SolarSystemBody.VENUS, JulianDate(JulianDate.julianDay(2020, 8, 13)))
        assertEquals(45.79, assertNotNull(elongation.elongation), 0.02)
        assertEquals(0.5007, assertNotNull(elongation.illuminatedFraction), 0.002)
        assertEquals(-4.43, assertNotNull(elongation.apparentMagnitude), 0.03)
    }

    /**
     * Saturn's rings were wide open at the 2017 opposition (magnitude −0.06) and edge-on in March 2025
     * (1.14, DE440s with Mallama & Hilton): the tilt alone dims the planet by 1.2 magnitudes.
     */
    @Test
    fun testSaturnRingTiltChangesTheMagnitude() {
        val open = Ephemeris.state(SolarSystemBody.SATURN, JulianDate(JulianDate.julianDay(2017, 6, 15)))
        val edgeOn = Ephemeris.state(SolarSystemBody.SATURN, JulianDate(JulianDate.julianDay(2025, 3, 23)))
        assertEquals(-0.06, assertNotNull(open.apparentMagnitude), 0.03)
        assertEquals(1.14, assertNotNull(edgeOn.apparentMagnitude), 0.05)
    }

    // MARK: Mallama & Hilton equations (reference values from Skyfield's magnitudelib)

    @Test
    fun testPhotometryReproducesSkyfieldReferenceValues() {
        val p = Photometry
        val tolerance = 1e-9
        assertEquals(-1.2447218703, p.mercury(sunDistance = 0.387, earthDistance = 0.8, phaseAngle = 60.0), tolerance)
        assertEquals(2.3549732248, p.mercury(sunDistance = 0.45, earthDistance = 1.3, phaseAngle = 120.0), tolerance)
        assertEquals(2.1457947204, p.mercury(sunDistance = 0.31, earthDistance = 0.72, phaseAngle = 145.0), tolerance)
        assertEquals(-4.9310584918, p.venus(sunDistance = 0.723, earthDistance = 0.5, phaseAngle = 100.0), tolerance)
        assertEquals(-3.9700818919, p.venus(sunDistance = 0.725, earthDistance = 1.5, phaseAngle = 30.0), tolerance)
        assertEquals(-4.0424252442, p.venus(sunDistance = 0.72, earthDistance = 0.3, phaseAngle = 170.0), tolerance)
        assertEquals(-1.4284674528, p.mars(sunDistance = 1.5, earthDistance = 0.6, phaseAngle = 20.0), tolerance)
        assertEquals(1.6812448916, p.mars(sunDistance = 1.6, earthDistance = 2.0, phaseAngle = 45.0), tolerance)
        assertEquals(2.0497402218, p.mars(sunDistance = 1.4, earthDistance = 2.5, phaseAngle = 60.0), tolerance)
        assertEquals(-2.6851868298, p.jupiter(sunDistance = 5.2, earthDistance = 4.2, phaseAngle = 5.0), tolerance)
        assertEquals(-1.7006067534, p.jupiter(sunDistance = 5.4, earthDistance = 6.2, phaseAngle = 11.0), tolerance)
        assertEquals(
            0.3528264210,
            p.saturn(
                sunDistance = 9.5, earthDistance = 8.5, phaseAngle = 3.0, sunLatitude = 10.0, earthLatitude = 12.0,
            ),
            tolerance,
        )
        assertEquals(
            0.8007098250,
            p.saturn(
                sunDistance = 9.5, earthDistance = 10.0, phaseAngle = 5.5, sunLatitude = -20.0, earthLatitude = -5.0,
            ),
            tolerance,
        )
        // Opposite sides of the ring plane: the rings do not contribute.
        assertEquals(
            0.8725687124,
            p.saturn(
                sunDistance = 9.6, earthDistance = 9.0, phaseAngle = 4.0, sunLatitude = 10.0, earthLatitude = -5.0,
            ),
            tolerance,
        )
        assertEquals(
            5.5606630834,
            p.uranus(sunDistance = 19.2, earthDistance = 18.2, sunLatitude = 60.0, earthLatitude = 50.0),
            tolerance,
        )
        assertEquals(
            5.7721179831,
            p.uranus(sunDistance = 19.0, earthDistance = 20.0, sunLatitude = -10.0, earthLatitude = 30.0),
            tolerance,
        )
        assertEquals(7.7122974229, p.neptune(sunDistance = 30.1, earthDistance = 29.1, year = 2026.8), tolerance)
        assertEquals(7.8957147428, p.neptune(sunDistance = 30.0, earthDistance = 31.0, year = 1990.5), tolerance)
        assertEquals(7.8003459312, p.neptune(sunDistance = 29.9, earthDistance = 29.0, year = 1970.0), tolerance)
    }

    @Test
    fun testSunAndMoonMagnitudes() {
        val p = Photometry
        assertEquals(-26.74, p.sun(1.0), 1e-12)
        assertEquals(-16.74, p.sun(100.0), 1e-12)
        val meanDistance = 384_400 / SkyFrame.kilometersPerAU
        // Full moon at the mean distance, and a quarter moon (Allen: −12.73 + 0.026·90 + 4e-9·90⁴).
        assertEquals(-12.73, p.moon(sunDistance = 1.0, earthDistance = meanDistance, phaseAngle = 0.0), 1e-9)
        assertEquals(-10.128, p.moon(sunDistance = 1.0, earthDistance = meanDistance, phaseAngle = 90.0), 0.001)
        // A body 10 % closer is 0.2 magnitudes brighter.
        assertEquals(
            -12.73 + 5 * log10(0.9),
            p.moon(sunDistance = 1.0, earthDistance = meanDistance * 0.9, phaseAngle = 0.0),
            1e-9,
        )
    }

    // MARK: BodyState invariants

    @Test
    fun testBodyStateInvariantsAcrossBodiesAndTime() {
        for (body in SolarSystemBody.entries) {
            for (step in 0 until 400) {
                val jd = JulianDate(2_415_020.5 + step.toDouble() * 197.37)
                val state = Ephemeris.state(body, jd)
                val message = "$body at JD ${jd.ut}"
                assertEquals(body, state.body)
                assertEquals(jd, state.julianDate)
                assertTrue(state.geocentricJ2000.isFinite, message)
                assertTrue(state.distanceAU > 0, message)
                assertEquals(state.geocentricJ2000.length, state.distanceAU, 1e-12, message)
                assertTrue(state.angularDiameterArcseconds > 0, message)
                assertTrue(state.eclipticLongitudeOfDate in 0.0..<360.0, message)
                assertTrue(state.eclipticLatitudeOfDate in -90.0..90.0, message)
                // Largest geocentric ecliptic latitudes: the Moon 5.3°, Venus 8.9°, the other planets less.
                val latitudeLimit = if (body == SolarSystemBody.SUN) {
                    0.001
                } else if (body == SolarSystemBody.MOON) {
                    5.4
                } else {
                    9.5
                }
                assertTrue(abs(state.eclipticLatitudeOfDate) < latitudeLimit, message)

                assertEquals(body == SolarSystemBody.SUN, state.heliocentricDistanceAU == null, message)
                assertEquals(body == SolarSystemBody.SUN, state.elongation == null, message)
                assertEquals(body == SolarSystemBody.SUN, state.phaseAngle == null, message)
                assertEquals(body == SolarSystemBody.SUN, state.illuminatedFraction == null, message)
                assertNotNull(state.apparentMagnitude, message)
                assertEquals(body != SolarSystemBody.MOON, state.isWaxing == null, message)
                if (body != SolarSystemBody.SUN) {
                    val elongation = assertNotNull(state.elongation)
                    val phaseAngle = assertNotNull(state.phaseAngle)
                    val fraction = assertNotNull(state.illuminatedFraction)
                    assertTrue(elongation in 0.0..180.0, message)
                    assertTrue(phaseAngle in 0.0..180.0, message)
                    assertTrue(fraction in 0.0..1.0, message)
                    assertEquals((1 + cos(AstroMath.radians(phaseAngle))) / 2, fraction, 1e-12, message)
                    assertTrue(assertNotNull(state.heliocentricDistanceAU) > 0, message)
                }
                if (body == SolarSystemBody.MOON) {
                    assertTrue(state.distanceKilometers in 356_000.0..407_000.0, message)
                    // 1737.4 km seen from 356 700–406 500 km: 29.4′ to 33.5′ across.
                    assertTrue(state.angularDiameterArcseconds / 60 in 29.3..33.5, message)
                    // Waxing means east of the Sun: elongation measured eastwards below 180°.
                    val sun = Ephemeris.state(SolarSystemBody.SUN, jd)
                    val separation = state.eclipticLongitudeOfDate - sun.eclipticLongitudeOfDate
                    val eastOfSun = AstroMath.normalizeDegrees(separation) < 180
                    assertEquals(eastOfSun, state.isWaxing, message)
                    // Inner and outer limits of the Moon's phase angle follow from its elongation:
                    // phase angle + elongation ≈ 180° (the Sun is far away).
                    assertEquals(
                        180.0, assertNotNull(state.phaseAngle) + assertNotNull(state.elongation), 0.35, message,
                    )
                }
                if (body == SolarSystemBody.MERCURY || body == SolarSystemBody.VENUS) {
                    assertTrue(
                        assertNotNull(state.elongation) < (if (body == SolarSystemBody.MERCURY) 28.5 else 47.5),
                        message,
                    )
                }
            }
        }
    }

    @Test
    fun testStateIsSendableHashableAndStable() {
        val jd = JulianDate(2_460_000.5)
        val state = Ephemeris.state(SolarSystemBody.JUPITER, jd)
        val again = Ephemeris.state(SolarSystemBody.JUPITER, jd)
        assertEquals(state, again)
        assertEquals(1, setOf(state, again).size)
        assertNotEquals(state, Ephemeris.state(SolarSystemBody.JUPITER, jd.addingDays(1.0)))
        // Swift also checks the `Sendable` conformance here; a Kotlin data class of vals is immutable, and
        // there is no marker to check.
    }

    @Test
    fun testStateForTimesFarFromToday() {
        // The series are trigonometric, so they stay finite and sensible for thousands of years.
        for (year in listOf(-2000, -1000, 0, 1000, 1800, 2200, 3000, 4000)) {
            val jd = JulianDate(JulianDate.julianDay(year, 6, 1))
            for (body in SolarSystemBody.entries) {
                val state = Ephemeris.state(body, jd)
                assertTrue(state.geocentricJ2000.isFinite, "$body in $year")
                assertTrue(state.distanceAU > 0.002 && state.distanceAU < 35, "$body distance in $year")
            }
        }
    }

    @Test
    fun testNonFiniteTimesDoNotTrap() {
        for (body in SolarSystemBody.entries) {
            val state = Ephemeris.state(body, JulianDate(Double.NaN, 69.0))
            assertFalse(state.geocentricJ2000.isFinite)
        }
    }

    // MARK: SkyFrame conveniences

    @Test
    fun testSkyFrameConveniencesAgreeWithTheLowLevelPipeline() {
        val observer = Observer(GeographicLocation(47.6, -122.3, 60.0))
        val frame = SkyFrame(instantFromTimeIntervalSince1970(1_790_000_000.0), observer)
        for (body in SolarSystemBody.entries) {
            val state = frame.state(body)
            assertEquals(frame.julianDate, state.julianDate)
            val vector = state.geocentricJ2000
            assertEquals(frame.horizontal(vector, true), frame.horizontal(body))
            assertEquals(frame.horizontal(vector, false), frame.horizontal(body, false))
            assertEquals(frame.topocentricEquatorial(vector), frame.topocentricEquatorial(body))
            assertEquals(
                EquatorialCoordinate(frame.precessionNutationMatrix * vector),
                frame.geocentricApparentEquatorial(body),
            )
        }
    }

    @Test
    fun testMoonParallaxReachesAboutOneDegreeAtTheHorizon() {
        // Seen from the surface the Moon is displaced downwards by up to its horizontal parallax,
        // nearly 1°, relative to the geocentre; the Sun's parallax is only 8.8″.
        val observer = Observer(GeographicLocation(20.0, 10.0))
        var largest = 0.0
        for (halfHour in 0 until 48) {
            val frame = SkyFrame(
                instantFromTimeIntervalSince1970(1_790_000_000.0 + halfHour.toDouble() * 1800),
                observer,
            )
            val geocentric = frame.geocentricApparentEquatorial(SolarSystemBody.MOON)
            val topocentric = frame.topocentricEquatorial(SolarSystemBody.MOON)
            largest = swiftMax(largest, geocentric.angularSeparation(topocentric))
            val sunShift = frame.geocentricApparentEquatorial(SolarSystemBody.SUN)
                .angularSeparation(frame.topocentricEquatorial(SolarSystemBody.SUN)) * 3600
            assertTrue(sunShift < 9.0)
        }
        assertTrue(largest in 0.88..1.03, "largest displacement $largest°")
    }

    // MARK: Bodies

    @Test
    fun testSolarSystemBodyProperties() {
        assertEquals(
            listOf("sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn", "uranus", "neptune"),
            SolarSystemBody.entries.map { it.rawValue },
        )
        assertEquals(
            listOf("Sun", "Moon", "Mercury", "Venus", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"),
            SolarSystemBody.entries.map { it.displayName },
        )
        assertEquals(7, SolarSystemBody.entries.count { it.isPlanet })
        assertFalse(SolarSystemBody.SUN.isPlanet)
        assertFalse(SolarSystemBody.MOON.isPlanet)
        assertEquals(1737.4, SolarSystemBody.MOON.meanRadiusKilometers, 0.05)
        assertEquals(695_700.0, SolarSystemBody.SUN.meanRadiusKilometers, 1.0)
        assertEquals(69_911.0, SolarSystemBody.JUPITER.meanRadiusKilometers, 1.0)
        for (body in SolarSystemBody.entries) {
            assertTrue(body.meanRadiusKilometers > 0)
            val data = Json.encodeToString(SolarSystemBody.serializer(), body)
            assertEquals(body, Json.decodeFromString(SolarSystemBody.serializer(), data))
            assertEquals("\"${body.rawValue}\"", data)
        }
    }
}

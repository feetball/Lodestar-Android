package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Positions, distances, magnitudes and tails of comets and asteroids against JPL Horizons (https://ssd.jpl.nasa.gov/api/horizons.api,
 * queried 2026-10-18). Each case takes the osculating elements Horizons itself reports at an epoch (so only planetary
 * perturbations separate the two-body motion from Horizons' N-body integration) and compares the observer table at
 * and around that epoch: C/2023 A3 (Tsuchinshan-ATLAS) in October 2024, 12P/Pons-Brooks in April 2024, 1P/Halley in
 * 1986, and the asteroids (4) Vesta and (1) Ceres in 2026.
 *
 * Expected two-body error (measured, see the tolerances below): below 1 arcsecond at the epoch and within 1.2 arcseconds
 * after 15 days for a comet 0.5 AU from the Earth; 0.6 arcseconds after 60 days for Halley; 4 arcseconds after 120 days
 * for the main-belt asteroids. Elements that are months old are still good to an arcminute (see `staleElements`),
 * elements that are decades old are not.
 */
class CometsEphemerisTests {
    private fun body(case: CometsHorizonsData.Case): SmallBody {
        val orbit = assertNotNull(
            OrbitalElements.create(
                case.perihelionDistance, case.eccentricity, case.inclination, case.argumentOfPerihelion,
                case.ascendingNode, case.perihelionTimeTDB, case.epochTDB,
            ),
        )
        val isAsteroid = case.title.startsWith("(")
        val photometry: SmallBodyPhotometry = if (isAsteroid) {
            SmallBodyPhotometry.Asteroid(assertNotNull(AsteroidMagnitudes.create(case.absoluteMagnitude, case.slope)))
        } else {
            SmallBodyPhotometry.Comet(
                assertNotNull(
                    CometMagnitudes.create(
                        case.absoluteMagnitude, case.slope, case.nuclearMagnitude, case.nuclearSlope, case.nuclearPhaseCoefficient,
                    ),
                ),
            )
        }
        return assertNotNull(
            SmallBody.create(
                designation = case.title, kind = if (isAsteroid) SmallBodyKind.ASTEROID else SmallBodyKind.COMET,
                orbit = orbit, photometry = photometry,
            ),
        )
    }

    private fun arcseconds(a: Vector3, b: Vector3): Double = a.angle(b) * 3600

    private fun direction(ra: Double, dec: Double): Vector3 = EquatorialCoordinate(ra, dec).unitVector

    /** Allowed two-body error in arcseconds at [offset] days from the epoch. */
    private fun allowedArcseconds(offset: Double): Double = 2.0 + 0.1 * abs(offset)

    // MARK: Horizons

    @Test
    fun astrometricPositionsAgreeWithHorizons() {
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val error = arcseconds(state.astrometricJ2000, direction(row.raAstrometric, row.decAstrometric))
                assertTrue(error <= allowedArcseconds(row.offsetDays), "${case.title} at ${row.offsetDays} d: $error arcsec")
            }
        }
    }

    @Test
    fun apparentPositionsAgreeWithHorizons() {
        // Horizons' "a-app" is the geocentric apparent place: true equator and equinox of date, light-time, deflection, aberration.
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val julianDate = JulianDate(case.epochTDB + row.offsetDays)
                val frame = SkyFrame(julianDate, Observer(GeographicLocation(0.0, 0.0, 0.0)))
                val apparent = assertNotNull(frame.geocentricApparentEquatorial(small))
                val error = apparent.angularSeparation(EquatorialCoordinate(row.raApparent, row.decApparent)) * 3600
                assertTrue(error <= allowedArcseconds(row.offsetDays) + 0.5, "${case.title} at ${row.offsetDays} d: $error arcsec")
            }
        }
    }

    @Test
    fun aberrationDisplacesTheApparentPositionByTheConstantOfAberration() {
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val shift = arcseconds(state.astrometricJ2000, state.geocentricJ2000)
                assertTrue(shift > 0.5 && shift < 21.0, "${case.title}: $shift arcsec")
                assertEquals(state.astrometricJ2000.length, state.geocentricJ2000.length, 1e-12)
            }
        }
    }

    @Test
    fun distancesElongationAndPhaseAgreeWithHorizons() {
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                assertEquals(row.sunDistance, state.heliocentricDistanceAU, 5e-5, "${case.title} r at ${row.offsetDays}")
                assertEquals(row.earthDistance, state.distanceAU, 5e-5, "${case.title} delta at ${row.offsetDays}")
                assertEquals(row.elongation, state.elongation, 0.01, "${case.title} elongation at ${row.offsetDays}")
                assertEquals(row.phaseAngle, state.phaseAngle, 0.03, "${case.title} phase at ${row.offsetDays}")
                assertEquals(state.distanceAU * SkyFrame.kilometersPerAU, state.distanceKilometers, 1e-3)
                assertEquals(state.heliocentricJ2000.length, state.heliocentricDistanceAU, 1e-15)
            }
        }
    }

    @Test
    fun magnitudesAgreeWithHorizons() {
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val magnitude = assertNotNull(state.apparentMagnitude)
                if (row.magnitude == 6.0 && case.title.startsWith("C/2023 A3")) {
                    // Horizons lists nothing brighter than 6.0 for this record (the formula gives 5.83 and 5.87).
                    assertTrue(magnitude <= 6.0 && magnitude >= 5.8, "${case.title}: $magnitude")
                } else {
                    assertEquals(row.magnitude, magnitude, 0.01, "${case.title} at ${row.offsetDays}")
                }
                val nuclear = row.nuclearMagnitude
                if (nuclear != null) {
                    assertEquals(nuclear, assertNotNull(state.nuclearMagnitude), 0.01, "${case.title} nuclear at ${row.offsetDays}")
                } else {
                    assertNull(state.nuclearMagnitude)
                }
            }
        }
    }

    @Test
    fun magnitudesFollowTheCatalogFormulas() {
        // Independent evaluation of the formulas with Horizons' own r and delta.
        for (case in CometsHorizonsData.cases.filter { !it.title.startsWith("(") }) {
            for (row in case.rows.filter { it.magnitude != 6.0 }) {
                val total = case.absoluteMagnitude + 5 * log10(row.earthDistance) + case.slope * log10(row.sunDistance)
                assertEquals(row.magnitude, total, 0.0006, "${case.title} total at ${row.offsetDays}")
                val nuclearMagnitude = case.nuclearMagnitude
                val nuclearSlope = case.nuclearSlope
                val nuclear = row.nuclearMagnitude
                if (nuclearMagnitude != null && nuclearSlope != null && nuclear != null) {
                    val expected = nuclearMagnitude + 5 * log10(row.earthDistance) + nuclearSlope * log10(row.sunDistance) +
                        case.nuclearPhaseCoefficient * row.phaseAngle
                    assertEquals(nuclear, expected, 0.0006, "${case.title} nuclear at ${row.offsetDays}")
                }
            }
        }
    }

    @Test
    fun asteroidMagnitudeFollowsTheIAUHGSystem() {
        // (4) Vesta, H = 3.25, G = 0.32; Horizons APmag 8.018 for r = 2.347784276612, delta = 2.53640919646355, alpha = 23.5847 degrees.
        val vesta = assertNotNull(AsteroidMagnitudes.create(3.25, 0.32))
        assertEquals(8.018, assertNotNull(vesta.apparent(2.347_784_276_612, 2.536_409_196_463_55, 23.5847)), 0.0006)
        // (1) Ceres, H = 3.34, G = 0.12: 8.855 at alpha = 5.0813 degrees.
        val ceres = assertNotNull(AsteroidMagnitudes.create(3.34, 0.12))
        assertEquals(8.855, assertNotNull(ceres.apparent(2.766_134_002_962, 3.740_251_165_185_17, 5.0813)), 0.0006)
        // At zero phase the magnitude is H + 5 log10(r delta).
        assertEquals(3.25 + 5 * log10(2.0 * 1.5), assertNotNull(vesta.apparent(2.0, 1.5, 0.0)), 1e-12)
        // Fainter with growing phase angle, for any G.
        for (g in listOf(0.0, 0.15, 0.5, 1.0)) {
            val magnitudes = assertNotNull(AsteroidMagnitudes.create(10.0, g))
            var previous = -100.0
            for (phase in listOf(0.0, 10.0, 30.0, 60.0, 90.0, 120.0)) {
                val value = assertNotNull(magnitudes.apparent(1.5, 1.0, phase))
                assertTrue(value > previous, "G = $g, alpha = $phase")
                previous = value
            }
        }
        // G = 1 uses only the second basis function, G = 0 only the first.
        val phase = 40.0
        val t = kotlin.math.tan(Math.toRadians(phase) / 2)
        val first = kotlin.math.exp(-3.33 * Math.pow(t, 0.63))
        val second = kotlin.math.exp(-1.87 * Math.pow(t, 1.22))
        assertEquals(-2.5 * log10(first), assertNotNull(assertNotNull(AsteroidMagnitudes.create(0.0, 0.0)).apparent(1.0, 1.0, phase)), 1e-12)
        assertEquals(-2.5 * log10(second), assertNotNull(assertNotNull(AsteroidMagnitudes.create(0.0, 1.0)).apparent(1.0, 1.0, phase)), 1e-12)
    }

    @Test
    fun cometMagnitudeFormulas() {
        // 12P/Pons-Brooks on 2024 April 21: M1 = 5, K1 = 15, M2 = 11, K2 = 10 -> T-mag 4.416, N-mag 10.953 (Horizons).
        val magnitudes = assertNotNull(CometMagnitudes.create(5.0, 15.0, 11.0, 10.0))
        assertEquals(4.416, assertNotNull(magnitudes.total(0.780_792_203_878, 1.605_357_418_629_92)), 0.0006)
        assertEquals(10.953, assertNotNull(magnitudes.nuclear(0.780_792_203_878, 1.605_357_418_629_92, 29.7253)), 0.0006)
        // 1P/Halley on 1986 Feb 9 with the phase coefficient PC = 0.03: N-mag 13.786.
        val halley = assertNotNull(CometMagnitudes.create(5.5, 8.0, 13.6, 5.0, 0.03))
        assertEquals(4.602, assertNotNull(halley.total(0.587_194_099_879, 1.549_715_750_555_96)), 0.0006)
        assertEquals(13.786, assertNotNull(halley.nuclear(0.587_194_099_879, 1.549_715_750_555_96, 13.0346)), 0.0006)
        // At r = delta = 1 AU the magnitude is M1.
        assertEquals(5.0, assertNotNull(magnitudes.total(1.0, 1.0)), 1e-12)
        // The MPC slope parameter k gives K1 = 2.5 k.
        val mpc = assertNotNull(CometMagnitudes.fromMPC(6.5, 3.2))
        assertEquals(8.0, mpc.totalSlope, 1e-12)
        assertEquals(6.5, mpc.totalMagnitude)
        assertNull(mpc.nuclearMagnitude)
        assertNull(mpc.nuclear(1.0, 1.0, 0.0))
        // Invalid distances give nothing.
        assertNull(magnitudes.total(0.0, 1.0))
        assertNull(magnitudes.total(1.0, -1.0))
        assertNull(magnitudes.total(Double.NaN, 1.0))
        assertNull(magnitudes.nuclear(1.0, 0.0, 0.0))
        assertNull(assertNotNull(AsteroidMagnitudes.create(3.0, 0.15)).apparent(0.0, 1.0, 10.0))
        assertNull(assertNotNull(AsteroidMagnitudes.create(3.0, 0.15)).apparent(1.0, 1.0, 180.0))
        assertNull(assertNotNull(AsteroidMagnitudes.create(3.0, 0.15)).apparent(1.0, 1.0, -1.0))
        assertNull(assertNotNull(AsteroidMagnitudes.create(3.0, 0.15)).apparent(1.0, 1.0, Double.NaN))
    }

    @Test
    fun photometryValuesAreValidated() {
        assertNull(CometMagnitudes.create(-21.0, 10.0))
        assertNull(CometMagnitudes.create(41.0, 10.0))
        assertNull(CometMagnitudes.create(Double.NaN, 10.0))
        assertNull(CometMagnitudes.create(5.0, 61.0))
        assertNull(CometMagnitudes.create(5.0, -6.0))
        assertNull(CometMagnitudes.create(5.0, 10.0, 11.0, 70.0))
        assertNull(CometMagnitudes.create(5.0, 10.0, 45.0, 10.0))
        assertNull(CometMagnitudes.create(5.0, 10.0, 11.0, 10.0, 0.6))
        assertNull(CometMagnitudes.create(5.0, 10.0, nuclearPhaseCoefficient = Double.NaN))
        // Nuclear parameters are used only when both are given.
        val onlyMagnitude = assertNotNull(CometMagnitudes.create(5.0, 10.0, 11.0, null, 0.03))
        assertNull(onlyMagnitude.nuclearMagnitude)
        assertNull(onlyMagnitude.nuclearSlope)
        assertEquals(0.0, onlyMagnitude.nuclearPhaseCoefficient)
        assertNull(CometMagnitudes.fromMPC(5.0, Double.NaN))
        assertNull(AsteroidMagnitudes.create(-6.0))
        assertNull(AsteroidMagnitudes.create(41.0))
        assertNull(AsteroidMagnitudes.create(10.0, -0.3))
        assertNull(AsteroidMagnitudes.create(10.0, 1.1))
        assertNull(AsteroidMagnitudes.create(Double.NaN))
        assertEquals(0.15, assertNotNull(AsteroidMagnitudes.create(10.0)).slopeParameter)
        assertEquals(10.0, CometMagnitudes.defaultTotalSlope)
        assertEquals(0.15, AsteroidMagnitudes.defaultSlopeParameter)
    }

    @Test
    fun tailPositionAngleAgreesWithHorizons() {
        // Horizons' PsAng is the position angle of the extended Sun -> target radius vector, north through east.
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val angle = assertNotNull(state.tailPositionAngle)
                val difference = abs(AstroMath.normalizeSignedDegrees(angle - row.tailPositionAngle))
                assertTrue(difference < 0.02, "${case.title} at ${row.offsetDays}: ${state.tailPositionAngle} vs ${row.tailPositionAngle}")
            }
        }
    }

    @Test
    fun tailPositionAngleGeometry() {
        // A comet due north of the Earth's line of sight with the Sun below it: the tail points north.
        val line = Vector3(1.0, 0.0, 0.0)
        assertEquals(0.0, assertNotNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(1.0, 0.0, 1.0))), 1e-9)
        assertEquals(90.0, assertNotNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(1.0, 1.0, 0.0))), 1e-9)
        assertEquals(180.0, assertNotNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(1.0, 0.0, -1.0))), 1e-9)
        assertEquals(270.0, assertNotNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(1.0, -1.0, 0.0))), 1e-9)
        // Along the line of sight or at a celestial pole there is no direction.
        assertNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(5.0, 0.0, 0.0)))
        assertNull(SmallBodyEphemeris.antiSolarPositionAngle(line, Vector3(-2.0, 0.0, 0.0)))
        assertNull(SmallBodyEphemeris.antiSolarPositionAngle(Vector3(0.0, 0.0, 1.0), Vector3(1.0, 0.0, 1.0)))
    }

    // MARK: Topocentric

    @Test
    fun topocentricPositionsAgreeWithHorizons() {
        // Sites: Los Angeles (C/2023 A3, 0.47 AU away), Sydney (Halley) and Mauna Kea (12P). Horizons' airless azimuth/elevation
        // use the observed UT1 and polar motion, which the kit neglects: allow 25 arcseconds.
        for (row in CometsHorizonsData.topocentricRows) {
            val case = assertNotNull(CometsHorizonsData.cases.firstOrNull { caseKey(it) == row.caseKey })
            val small = body(case)
            val observer = Observer(GeographicLocation(row.latitude, row.longitude, row.elevationMeters))
            val frame = SkyFrame(JulianDate(row.julianDayUT), observer)
            val equatorial = assertNotNull(frame.topocentricEquatorial(small))
            val error = equatorial.angularSeparation(EquatorialCoordinate(row.raApparent, row.decApparent)) * 3600
            assertTrue(error < 25.0, "${case.title}: apparent place off by $error arcsec")
            val horizontal = assertNotNull(frame.horizontal(small, refraction = false))
            val reference = HorizontalCoordinate(row.azimuth, row.elevation)
            val horizontalError = horizontal.angularSeparation(reference) * 3600
            assertTrue(horizontalError < 25.0, "${case.title}: azimuth/elevation off by $horizontalError arcsec")
        }
    }

    private fun caseKey(case: CometsHorizonsData.Case): String = when {
        case.title.startsWith("C/2023 A3") -> "c2023a3"
        case.title.startsWith("12P") -> "p12"
        case.title.startsWith("1P") -> "halley1986"
        case.title.startsWith("(4)") -> "vesta"
        else -> "ceres"
    }

    @Test
    fun parallaxMattersForNearbyComets() {
        // C/2023 A3 at 0.47 AU: the topocentric place differs from the geocentric one by up to 18 arcseconds.
        val case = CometsHorizonsData.c2023a3
        val small = body(case)
        val row = CometsHorizonsData.topocentricRows.first { it.caseKey == "c2023a3" }
        val frame = SkyFrame(JulianDate(row.julianDayUT), Observer(GeographicLocation(row.latitude, row.longitude, row.elevationMeters)))
        val geocentric = assertNotNull(frame.geocentricApparentEquatorial(small))
        val topocentric = assertNotNull(frame.topocentricEquatorial(small))
        val shift = geocentric.angularSeparation(topocentric) * 3600
        assertTrue(shift > 5.0 && shift < 20.0, "parallax $shift arcsec")
    }

    @Test
    fun skyFrameExtensionsAgree() {
        val case = CometsHorizonsData.p12
        val small = body(case)
        val julianDate = JulianDate(case.epochTDB + 11)
        val frame = SkyFrame(julianDate, Observer(GeographicLocation(48.0, 11.0, 500.0)))
        val state = assertNotNull(frame.state(small))
        assertEquals(julianDate, state.julianDate)
        assertEquals(state, SmallBodyEphemeris.state(small, julianDate))
        assertEquals(state.geocentricJ2000, assertNotNull(SmallBodyEphemeris.geocentricJ2000(small, julianDate)))
        assertEquals(frame.horizontal(state.geocentricJ2000, true), frame.horizontal(small, true))
        assertEquals(frame.horizontal(state.geocentricJ2000, false), frame.horizontal(small, false))
        assertEquals(frame.topocentricEquatorial(state.geocentricJ2000), frame.topocentricEquatorial(small))
        assertEquals(EquatorialCoordinate(frame.precessionNutationMatrix * state.geocentricJ2000), frame.geocentricApparentEquatorial(small))
        // Refraction raises the altitude and leaves the azimuth.
        val airless = assertNotNull(frame.horizontal(small, refraction = false))
        val refracted = assertNotNull(frame.horizontal(small, refraction = true))
        assertEquals(airless.azimuth, refracted.azimuth, 1e-9)
        assertTrue(refracted.altitude >= airless.altitude)
    }

    @Test
    fun outOfRangeDatesGiveNoState() {
        val hyperbolic = body(CometsHorizonsData.c2023a3)
        val far = JulianDate(CometsHorizonsData.c2023a3.perihelionTimeTDB + 60_000.0)
        assertNull(SmallBodyEphemeris.state(hyperbolic, far))
        assertNull(SmallBodyEphemeris.geocentricJ2000(hyperbolic, far))
        val frame = SkyFrame(far, Observer(GeographicLocation(0.0, 0.0, 0.0)))
        assertNull(frame.state(hyperbolic))
        assertNull(frame.horizontal(hyperbolic))
        assertNull(frame.topocentricEquatorial(hyperbolic))
        assertNull(frame.geocentricApparentEquatorial(hyperbolic))
        val altitude = SmallBodyEphemeris.altitude(hyperbolic, Observer(GeographicLocation(0.0, 0.0, 0.0)))
        assertTrue(altitude(far.date).isNaN())
    }

    // MARK: Elements of other origins

    @Test
    fun staleElementsDegradeGracefullyForCometsWithinMonths() {
        // The Small-Body Database elements of 12P are osculating at 2023 September 24; the table starts 179 days later.
        val pons = SmallBodyJSONParser.parse(CometsTestData.jsonPonsBrooks)
        val case = CometsHorizonsData.p12
        var worst = 0.0
        for (row in case.rows) {
            val state = assertNotNull(SmallBodyEphemeris.state(pons, JulianDate(case.epochTDB + row.offsetDays)))
            worst = max(worst, arcseconds(state.astrometricJ2000, direction(row.raAstrometric, row.decAstrometric)))
        }
        assertTrue(worst < 36.0, "12P from elements 200 days old: $worst arcsec") // measured 27"
        assertTrue(worst > 5.0)
        // C/2023 A3: osculating at 2024 May 18, the table is 3 weeks to 5 months later.
        val atlas = SmallBodyJSONParser.parse(CometsTestData.jsonTsuchinshanAtlas)
        var worstAtlas = 0.0
        for (row in CometsHorizonsData.c2023a3.rows) {
            val state = assertNotNull(SmallBodyEphemeris.state(atlas, JulianDate(CometsHorizonsData.c2023a3.epochTDB + row.offsetDays)))
            worstAtlas = max(worstAtlas, arcseconds(state.astrometricJ2000, direction(row.raAstrometric, row.decAstrometric)))
        }
        assertTrue(worstAtlas < 30.0, "C/2023 A3 from elements 4 months old: $worstAtlas arcsec") // measured 20"
    }

    @Test
    fun staleElementsOfDecadesAreUseless() {
        // The Small-Body Database elements of Halley are osculating at 1968; two-body motion to 1986 is hopeless.
        val halley = SmallBodyJSONParser.parse(CometsTestData.jsonHalley)
        val case = CometsHorizonsData.halley1986
        for (row in case.rows) {
            val state = assertNotNull(SmallBodyEphemeris.state(halley, JulianDate(case.epochTDB + row.offsetDays)))
            val error = state.astrometricJ2000.angle(direction(row.raAstrometric, row.decAstrometric))
            assertTrue(error > 0.3 && error < 8.0, "Halley from 1968 elements at ${row.offsetDays} d: $error degrees")
        }
    }

    @Test
    fun smallBodyDatabaseAsteroidsAgreeWithHorizons() {
        for ((json, case) in listOf(
            Pair(CometsTestData.jsonVesta, CometsHorizonsData.vesta),
            Pair(CometsTestData.jsonCeres, CometsHorizonsData.ceres),
        )) {
            val small = SmallBodyJSONParser.parse(json)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val error = arcseconds(state.astrometricJ2000, direction(row.raAstrometric, row.decAstrometric))
                assertTrue(error <= allowedArcseconds(row.offsetDays), "${case.title} at ${row.offsetDays} d: $error arcsec")
                assertEquals(row.magnitude, assertNotNull(state.apparentMagnitude), 0.01)
            }
        }
    }

    @Test
    fun mpcMinorPlanetLinesAgreeWithHorizons() {
        // The MPC elements are osculating at 2026 June 9, like JPL's. Horizons uses G = 0.32 for Vesta and the MPC G = 0.15, so
        // the magnitudes differ by 0.2.
        for ((line, case) in listOf(
            Pair(CometsTestData.asteroidVesta, CometsHorizonsData.vesta),
            Pair(CometsTestData.asteroidCeres, CometsHorizonsData.ceres),
        )) {
            val small = MinorPlanetElementsParser.parseLine(line)
            for (row in case.rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(case.epochTDB + row.offsetDays)))
                val error = arcseconds(state.astrometricJ2000, direction(row.raAstrometric, row.decAstrometric))
                assertTrue(error <= 8.0 + 0.1 * abs(row.offsetDays), "${case.title} at ${row.offsetDays} d: $error arcsec")
            }
        }
        val vesta = MinorPlanetElementsParser.parseLine(CometsTestData.asteroidVesta)
        val row = CometsHorizonsData.vesta.rows[2]
        val state = assertNotNull(SmallBodyEphemeris.state(vesta, JulianDate(CometsHorizonsData.vesta.epochTDB + row.offsetDays)))
        assertEquals(row.magnitude, assertNotNull(state.apparentMagnitude), 0.3)
    }

    @Test
    fun mpcCometLinesAgreeWithHorizons() {
        // Elements osculating at 2026-10-17 from the MPC file against Horizons' own solutions on 2026-10-18 and 2026-11-17.
        val lines = listOf(
            Triple(CometsTestData.cometHartleyIras, CometsTestData.horizonsHartleyIras, 5.0),
            Triple(CometsTestData.cometGehrels, CometsTestData.horizonsGehrels, 5.0),
            Triple(CometsTestData.cometSchwassmannWachmann, CometsTestData.horizonsSchwassmannWachmann, 5.0),
            Triple(CometsTestData.cometBernardinelliBernstein, CometsTestData.horizonsBernardinelliBernstein, 5.0),
            Triple(CometsTestData.cometLeonard, CometsTestData.horizonsLeonard, 5.0),
            Triple(CometsTestData.cometWierzchos, CometsTestData.horizonsWierzchos, 60.0),
        )
        for ((line, rows, allowedArcseconds) in lines) {
            val small = CometElementsParser.parseLine(line)
            for (row in rows) {
                val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(2_461_331.5 + row.dayOffset)), small.title)
                val error = arcseconds(state.astrometricJ2000, direction(row.ra, row.dec))
                assertTrue(error <= allowedArcseconds, "${small.title} at ${row.dayOffset} d: $error arcsec")
                assertEquals(row.sunDistance, state.heliocentricDistanceAU, 5e-4 * row.sunDistance, small.title)
                assertEquals(row.earthDistance, state.distanceAU, 5e-4 * row.earthDistance, small.title)
                assertEquals(row.elongation, state.elongation, 0.05, small.title)
                // The MPC magnitude parameters (visual estimates) differ from JPL's; check against the MPC formula instead.
                val magnitudes = (assertNotNull(small.photometry) as SmallBodyPhotometry.Comet).magnitudes
                val expected = magnitudes.totalMagnitude + 5 * log10(row.earthDistance) + magnitudes.totalSlope * log10(row.sunDistance)
                assertEquals(expected, assertNotNull(state.apparentMagnitude), 0.01, small.title)
            }
        }
    }

    @Test
    fun mpcTailAnglesAgreeWithHorizons() {
        val small = CometElementsParser.parseLine(CometsTestData.cometHartleyIras)
        for (row in CometsTestData.horizonsHartleyIras) {
            val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(2_461_331.5 + row.dayOffset)))
            val difference = abs(AstroMath.normalizeSignedDegrees(assertNotNull(state.tailPositionAngle) - row.tailPositionAngle))
            assertTrue(difference < 0.2, "${small.title}: $difference degrees")
        }
    }

    // MARK: Consistency

    @Test
    fun geocentricShortcutMatchesTheState() {
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val julianDate = JulianDate(case.epochTDB + row.offsetDays)
                val state = assertNotNull(SmallBodyEphemeris.state(small, julianDate))
                assertEquals(state.geocentricJ2000, assertNotNull(SmallBodyEphemeris.geocentricJ2000(small, julianDate)))
            }
        }
    }

    @Test
    fun elongationAndPhaseSatisfyTheTriangle() {
        // Sun, Earth and body form a triangle with sides R (Sun-Earth), r (Sun-body), delta (Earth-body):
        // the angles at the Earth (elongation) and the body (phase) sum with the angle at the Sun to 180 degrees.
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                val julianDate = JulianDate(case.epochTDB + row.offsetDays)
                val state = assertNotNull(SmallBodyEphemeris.state(small, julianDate))
                val earth = PlanetaryTheory.heliocentricJ2000(PlanetaryTheory.Planet.EARTH, julianDate.tt)
                // With the law of sines r / sin(elongation) = R / sin(phase).
                val ratio = state.heliocentricDistanceAU / earth.length
                val sines = AstroMath.sinDeg(state.elongation) / AstroMath.sinDeg(state.phaseAngle)
                assertEquals(ratio, sines, 2e-3 * ratio, "${case.title} at ${row.offsetDays}")
                assertTrue(state.elongation >= 0 && state.elongation <= 180)
                assertTrue(state.phaseAngle >= 0 && state.phaseAngle <= 180)
            }
        }
    }

    @Test
    fun photographableNeedsBrightnessAndElongation() {
        val case = CometsHorizonsData.c2023a3
        val atlas = body(case)
        fun state(offset: Double): SmallBodyState =
            assertNotNull(SmallBodyEphemeris.state(atlas, JulianDate(case.epochTDB + offset)))
        // Oct 5 and Oct 12: magnitude 5.9 and 5.8 but only 15 and 12 degrees from the Sun.
        assertFalse(state(-7.0).isPhotographable())
        assertFalse(state(0.0).isPhotographable())
        assertTrue(state(0.0).isPhotographable(magnitudeLimit = 10.0, minimumElongation = 10.0))
        // Oct 20 (magnitude 6.9, 43 degrees) and Oct 27 (8.0, 54 degrees).
        assertTrue(state(8.0).isPhotographable())
        assertTrue(state(15.0).isPhotographable())
        assertFalse(state(15.0).isPhotographable(magnitudeLimit = 7.5))
        assertTrue(state(8.0).isPhotographable(magnitudeLimit = 7.5))
        assertFalse(state(8.0).isPhotographable(minimumElongation = 60.0))
        assertEquals(10.0, SmallBodyEphemeris.defaultMagnitudeLimit)
        assertEquals(25.0, SmallBodyEphemeris.defaultMinimumElongation)
        // A body without photometry is never photographable.
        val orbit = assertNotNull(OrbitalElements.create(1.0, 0.5, 10.0, 20.0, 30.0, case.perihelionTimeTDB))
        val bare = assertNotNull(SmallBody.create("X", "", SmallBodyKind.COMET, null, orbit, null))
        val bareState = assertNotNull(SmallBodyEphemeris.state(bare, JulianDate(case.epochTDB + 8)))
        assertNull(bareState.apparentMagnitude)
        assertNull(bareState.nuclearMagnitude)
        assertFalse(bareState.isPhotographable(magnitudeLimit = 99.0, minimumElongation = 0.0))
    }

    // MARK: Tail framing

    @Test
    fun tailAngleFromVerticalMatchesAnIndependentConstruction() {
        // Move 0.02 degrees from the comet along the position angle of the tail and compare the displacement in the
        // horizontal frame: right = increasing azimuth, up = increasing altitude.
        val observers = listOf(
            Observer(GeographicLocation(34.0522, -118.2437, 90.0)),
            Observer(GeographicLocation(-33.8688, 151.2093, 50.0)),
            Observer(GeographicLocation(64.0, 20.0, 10.0)),
        )
        var checked = 0
        for (case in CometsHorizonsData.cases) {
            val small = body(case)
            for (row in case.rows) {
                for (observer in observers) {
                    for (hours in listOf(0.0, 0.25, 0.5, 0.75)) {
                        val julianDate = JulianDate(case.epochTDB + row.offsetDays + hours)
                        val frame = SkyFrame(julianDate, observer)
                        val state = assertNotNull(frame.state(small))
                        val angle = SmallBodyEphemeris.tailAngleFromVertical(state, frame) ?: continue
                        val position = EquatorialCoordinate(state.astrometricJ2000)
                        val tail = assertNotNull(state.tailPositionAngle)
                        val step = 0.02
                        val moved = EquatorialCoordinate(
                            position.rightAscension + step * AstroMath.sinDeg(tail) / AstroMath.cosDeg(position.declination),
                            position.declination + step * AstroMath.cosDeg(tail),
                        )
                        val here = frame.horizontal(position, refraction = false)
                        val there = frame.horizontal(moved, refraction = false)
                        if (here.altitude > 85.0) continue
                        val towardsRight = AstroMath.normalizeSignedDegrees(there.azimuth - here.azimuth) * AstroMath.cosDeg(here.altitude)
                        val towardsUp = there.altitude - here.altitude
                        val expected = AstroMath.atan2Deg(towardsRight, towardsUp)
                        assertEquals(0.0, abs(AstroMath.normalizeSignedDegrees(angle - expected)), 0.35, "${case.title} ${row.offsetDays} d")
                        checked += 1
                    }
                }
            }
        }
        assertTrue(checked > 100, "only $checked cases were checked")
    }

    /** A synthetic state 1e6 AU away in the horizontal direction [h] (ENU), whose tail points along [tail] (ENU) for [frame]. */
    private fun syntheticState(frame: SkyFrame, h: Vector3, tail: Vector3): SmallBodyState {
        val toJ2000 = frame.j2000ToHorizontal.transposed
        val orbit = assertNotNull(OrbitalElements.create(1.0, 0.5, 10.0, 20.0, 30.0, 2_460_000.0))
        val body = assertNotNull(SmallBody.create("S", "", SmallBodyKind.COMET, null, orbit))
        val apparent = toJ2000 * h.normalized * 1e6
        return SmallBodyState(
            body = body, julianDate = frame.julianDate, astrometric = apparent, apparent = apparent,
            heliocentric = toJ2000 * tail.normalized * 3.0, elongation = 90.0, phaseAngle = 30.0,
            apparentMagnitude = 5.0, nuclearMagnitude = null, tailPositionAngle = null,
        )
    }

    @Test
    fun tailAngleFromVerticalForConstructedGeometry() {
        // A body 30 degrees up in the south; "up" along the sky is the direction towards the zenith.
        val frame = SkyFrame(JulianDate(2_461_000.5), Observer(GeographicLocation(40.0, -100.0, 0.0)))
        val h = Vector3(0.0, -cos(Math.toRadians(30.0)), kotlin.math.sin(Math.toRadians(30.0)))
        val up = Vector3(0.0, 0.5, sqrt(3.0) / 2)
        val right = Vector3(-1.0, 0.0, 0.0) // facing south, the right hand is west
        fun angle(tail: Vector3): Double = assertNotNull(SmallBodyEphemeris.tailAngleFromVertical(syntheticState(frame, h, tail), frame))
        assertEquals(0.0, angle(h + up * 0.2), 1e-5)
        assertEquals(90.0, angle(h + right * 0.2), 1e-5)
        assertEquals(180.0, abs(angle(h - up * 0.2)), 1e-5)
        assertEquals(-90.0, angle(h - right * 0.2), 1e-5)
        assertEquals(45.0, angle(h + up * 0.2 + right * 0.2), 1e-5)
        assertEquals(-135.0, angle(h - up * 0.2 - right * 0.2), 1e-5)
        // The tail along the line of sight has no direction on the sky.
        assertNull(SmallBodyEphemeris.tailAngleFromVertical(syntheticState(frame, h, h), frame))
        assertNull(SmallBodyEphemeris.tailAngleFromVertical(syntheticState(frame, h, h * -1.0), frame))
        // At the zenith "up" is undefined.
        assertNull(SmallBodyEphemeris.tailAngleFromVertical(syntheticState(frame, Vector3(0.0, 0.0, 1.0), Vector3(1.0, 0.0, 1.0)), frame))
    }

    // MARK: Rise, transit and set

    @Test
    fun riseTransitSetOfAnAsteroidAgreesWithAFixedPosition() {
        // Vesta moves 0.25 degrees per day: its rise, transit and set differ from those of a fixed point at its position at
        // noon by about a minute.
        val case = CometsHorizonsData.vesta
        val small = body(case)
        val observer = Observer(GeographicLocation(40.0, -105.0, 1600.0))
        val start = JulianDate(case.epochTDB + 120).date // 2026-10-07 UT
        val interval = DateInterval(start, 86_400.0)
        val result = SmallBodyEphemeris.riseTransitSet(small, observer, interval)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, result.circumstance)
        val middle = JulianDate(start.addingTimeInterval(43_200.0))
        val place = EquatorialCoordinate(assertNotNull(SmallBodyEphemeris.state(small, middle)).astrometricJ2000)
        val fixed = RiseSetCalculator.riseTransitSet(place, null, observer, interval)
        assertEquals(fixed.rise!!.epochSecond.toDouble(), result.rise!!.epochSecond.toDouble(), 240.0)
        assertEquals(fixed.set!!.epochSecond.toDouble(), result.set!!.epochSecond.toDouble(), 240.0)
        assertEquals(fixed.transit!!.epochSecond.toDouble(), result.transit!!.epochSecond.toDouble(), 240.0)
        assertEquals(assertNotNull(fixed.transitAltitude), assertNotNull(result.transitAltitude), 0.5)
    }

    @Test
    fun riseTransitSetEventsAreConsistentWithTheAltitude() {
        val case = CometsHorizonsData.halley1986
        val small = body(case)
        val observer = Observer(GeographicLocation(-33.8688, 151.2093, 50.0))
        val start = JulianDate(case.epochTDB + 60).date
        val interval = DateInterval(start, 86_400.0)
        val altitude = SmallBodyEphemeris.altitude(small, observer)
        val result = SmallBodyEphemeris.riseTransitSet(small, observer, interval)
        val rise = assertNotNull(result.rise)
        val set = assertNotNull(result.set)
        val transit = assertNotNull(result.transit)
        assertEquals(RiseSetCalculator.standardHorizonAltitude, altitude(rise), 0.01)
        assertEquals(RiseSetCalculator.standardHorizonAltitude, altitude(set), 0.01)
        assertTrue(altitude(rise.addingTimeInterval(120.0)) > altitude(rise.addingTimeInterval(-120.0)))
        assertTrue(altitude(set.addingTimeInterval(120.0)) < altitude(set.addingTimeInterval(-120.0)))
        assertTrue(altitude(transit) >= altitude(transit.addingTimeInterval(600.0)))
        assertTrue(altitude(transit) >= altitude(transit.addingTimeInterval(-600.0)))
        assertEquals(altitude(transit), assertNotNull(result.transitAltitude), 0.05)
        val crossings = SmallBodyEphemeris.crossings(small, observer, interval)
        assertEquals(listOf(AltitudeCrossing.Kind.RISING, AltitudeCrossing.Kind.SETTING).toSet(), crossings.map { it.kind }.toSet())
        assertEquals(rise, crossings.first { it.kind == AltitudeCrossing.Kind.RISING }.time)
        val culminations = SmallBodyEphemeris.upperCulminations(small, observer, interval)
        assertEquals(transit, culminations.first().time)
    }

    @Test
    fun riseTransitSetOfACometThatNeverRises() {
        // Halley in April 1986 from Alaska: dec -47 never rises above the horizon at latitude 70 N.
        val case = CometsHorizonsData.halley1986
        val small = body(case)
        val observer = Observer(GeographicLocation(70.0, -150.0, 0.0))
        val interval = DateInterval(JulianDate(case.epochTDB + 60).date, 86_400.0)
        val result = SmallBodyEphemeris.riseTransitSet(small, observer, interval)
        assertEquals(RiseTransitSet.Circumstance.ALWAYS_DOWN, result.circumstance)
        assertNull(result.rise)
        assertNull(result.set)
    }

    @Test
    fun altitudeFunctionAllocatesNoStateAcrossCalls() {
        val case = CometsHorizonsData.vesta
        val small = body(case)
        val observer = Observer(GeographicLocation(10.0, 20.0, 0.0))
        val altitude = SmallBodyEphemeris.altitude(small, observer)
        val instant = JulianDate(case.epochTDB + 3).date
        assertEquals(altitude(instant), altitude(instant))
        val frame = SkyFrame(instant, observer)
        assertEquals(assertNotNull(frame.horizontal(small, refraction = false)).altitude, altitude(instant), 1e-12)
    }

    // MARK: Cost

    @Test
    fun stateIsCheap() {
        val small = body(CometsHorizonsData.p12)
        val started = System.nanoTime()
        var checksum = 0.0
        for (index in 0 until 20_000) {
            val state = assertNotNull(SmallBodyEphemeris.state(small, JulianDate(2_460_400.0 + index * 0.01)))
            checksum += state.distanceAU
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue(checksum > 0)
        assertTrue(seconds < 5.0, "20 000 states took $seconds s")
        assertNotNull(SmallBodyEphemeris.state(small, JulianDate(Instant.parse("2024-04-21T00:00:00Z"))))
    }
}

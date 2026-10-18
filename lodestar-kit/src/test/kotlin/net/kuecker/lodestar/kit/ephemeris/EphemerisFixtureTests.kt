package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Loads the JSON reference fixtures (Skyfield with JPL DE440s) bundled with the tests.
 *
 * A missing or malformed fixture throws, so the calling test fails instead of passing vacuously. Keys are
 * decoded from snake case, like Swift's `.convertFromSnakeCase`.
 */
internal object EphemerisFixtureStore {
    /** The fixture file is not among the test resources. */
    class MissingFixture(val name: String) :
        Exception("Fixture $name is missing from the test bundle's Fixtures directory")

    @OptIn(ExperimentalSerializationApi::class)
    val json: Json = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    inline fun <reified T> load(fileName: String): T {
        val stream = EphemerisFixtureStore::class.java.getResourceAsStream("/fixtures/$fileName")
            ?: throw MissingFixture(fileName)
        return json.decodeFromString<T>(stream.use { it.readBytes().decodeToString() })
    }

    /** The `Instant` of a fixture's UT1 Julian Date. */
    fun date(universalJulianDate: Double): Instant =
        instantFromTimeIntervalSince1970((universalJulianDate - JulianDate.unixEpoch) * JulianDate.secondsPerDay)
}

/**
 * Compares `Ephemeris` with `bodies_apparent.json`: apparent places of the Sun, Moon and planets from
 * DE440s, at 50 instants between 1950 and 2049, each seen from one of six observing sites.
 */
class EphemerisFixtureTests {
    @Serializable
    private data class BodyCase(
        val utc: String,
        val ut1Jd: Double,
        val ttJd: Double,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
        val body: String,
        val raApparentJ2000Deg: Double,
        val decApparentJ2000Deg: Double,
        val raApparentDeg: Double,
        val decApparentDeg: Double,
        val raTopoDeg: Double,
        val decTopoDeg: Double,
        val distanceAu: Double,
        val azDeg: Double,
        val altDeg: Double,
        val illuminatedFraction: Double? = null,
        val phaseAngleDeg: Double? = null,
        val elongationDeg: Double? = null,
        val heliocentricDistanceAu: Double? = null,
        val apparentMagnitude: Double? = null,
    ) {
        val solarSystemBody: SolarSystemBody get() = SolarSystemBody.fromRawValue(body) ?: SolarSystemBody.SUN

        /** The instant with exactly the fixture's UT1 and TT, so ΔT differences do not enter. */
        val julianDate: JulianDate get() = JulianDate(ut1Jd, (ttJd - ut1Jd) * JulianDate.secondsPerDay)

        val observer: Observer get() = Observer(GeographicLocation(lat, lon, elevationM))
    }

    @Serializable
    private data class BodyFixture(val cases: List<BodyCase>)

    private companion object {
        /**
         * Maximum acceptable angular error in arcseconds of an apparent position, by body. The Sun and
         * planets come from truncated VSOP87 (observed maxima 0.2″ Sun, 0.6″ Mercury, 1.6″ Venus, 4.8″
         * Mars, 2.7″ Jupiter, 4.3″ Saturn, 4.2″ Uranus, 4.1″ Neptune); the Moon from Meeus' truncated
         * ELP-2000 (8.1″). The limits leave room for other instants and platforms.
         */
        val positionToleranceArcseconds: Map<SolarSystemBody, Double> = mapOf(
            SolarSystemBody.SUN to 1.0, SolarSystemBody.MOON to 15.0, SolarSystemBody.MERCURY to 2.0,
            SolarSystemBody.VENUS to 4.0, SolarSystemBody.MARS to 10.0, SolarSystemBody.JUPITER to 6.0,
            SolarSystemBody.SATURN to 8.0, SolarSystemBody.URANUS to 8.0, SolarSystemBody.NEPTUNE to 8.0,
        )

        /** Maximum acceptable relative error of the geocentric and heliocentric distances. */
        val distanceToleranceRelative: Map<SolarSystemBody, Double> = mapOf(
            SolarSystemBody.SUN to 5e-6, SolarSystemBody.MOON to 2.5e-4, SolarSystemBody.MERCURY to 1e-5,
            SolarSystemBody.VENUS to 2e-5, SolarSystemBody.MARS to 4e-5, SolarSystemBody.JUPITER to 4e-5,
            SolarSystemBody.SATURN to 5e-5, SolarSystemBody.URANUS to 4e-5, SolarSystemBody.NEPTUNE to 3e-5,
        )
    }

    private fun fixtureCases(): List<BodyCase> {
        val cases = EphemerisFixtureStore.load<BodyFixture>("bodies_apparent.json").cases
        assertEquals(450, cases.size, "50 instants x 9 bodies")
        for (body in SolarSystemBody.entries) {
            assertEquals(50, cases.count { it.solarSystemBody == body }, "cases for $body")
        }
        return cases
    }

    private fun assertAngle(error: Double, tolerance: Double, what: String, fixtureCase: BodyCase) {
        assertTrue(
            error <= tolerance,
            "${fixtureCase.body} $what at ${fixtureCase.utc}: $error″ > $tolerance″",
        )
    }

    // MARK: Positions

    @Test
    fun testGeocentricJ2000DirectionMatchesDE440s() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val state = Ephemeris.state(body, fixtureCase.julianDate)
            val expected = EquatorialCoordinate(fixtureCase.raApparentJ2000Deg, fixtureCase.decApparentJ2000Deg)
            val error = EquatorialCoordinate(state.geocentricJ2000).angularSeparation(expected) * 3600
            assertAngle(error, positionToleranceArcseconds[body] ?: 0.0, "J2000 direction", fixtureCase)
            assertEquals(state.geocentricJ2000, Ephemeris.geocentricJ2000(body, fixtureCase.julianDate))
        }
    }

    /**
     * The apparent ecliptic coordinates of date follow from the fixture's apparent place of date and the true
     * obliquity: λ includes the nutation in longitude, β is relative to the mean ecliptic of date.
     */
    @Test
    fun testEclipticCoordinatesOfDateMatchDE440s() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val jd = fixtureCase.julianDate
            val trueObliquity = Obliquity.mean(jd) + Nutation(jd).obliquity
            val place = EquatorialCoordinate(fixtureCase.raApparentDeg, fixtureCase.decApparentDeg)
            val expected = CoordinateTransforms.equatorialToEcliptic(place, trueObliquity)
            val state = Ephemeris.state(body, jd)
            val tolerance = (positionToleranceArcseconds[body] ?: 0.0) / 3600
            assertEquals(
                0.0,
                AstroMath.normalizeSignedDegrees(state.eclipticLongitudeOfDate - expected.longitude) *
                    AstroMath.cosDeg(expected.latitude),
                tolerance,
                "${fixtureCase.body} ecliptic longitude at ${fixtureCase.utc}",
            )
            assertEquals(
                expected.latitude,
                state.eclipticLatitudeOfDate,
                tolerance,
                "${fixtureCase.body} ecliptic latitude at ${fixtureCase.utc}",
            )
        }
    }

    @Test
    fun testGeocentricApparentPlaceOfDateMatchesDE440s() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val frame = SkyFrame(fixtureCase.julianDate, fixtureCase.observer)
            val expected = EquatorialCoordinate(fixtureCase.raApparentDeg, fixtureCase.decApparentDeg)
            val error = frame.geocentricApparentEquatorial(body).angularSeparation(expected) * 3600
            assertAngle(error, positionToleranceArcseconds[body] ?: 0.0, "apparent place of date", fixtureCase)
        }
    }

    @Test
    fun testTopocentricPlaceAndHorizontalCoordinatesMatchDE440s() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val tolerance = positionToleranceArcseconds[body] ?: 0.0
            val frame = SkyFrame(fixtureCase.julianDate, fixtureCase.observer)

            val expectedPlace = EquatorialCoordinate(fixtureCase.raTopoDeg, fixtureCase.decTopoDeg)
            assertAngle(
                frame.topocentricEquatorial(body).angularSeparation(expectedPlace) * 3600,
                tolerance, "topocentric place", fixtureCase,
            )

            val expectedHorizontal = HorizontalCoordinate(fixtureCase.azDeg, fixtureCase.altDeg)
            val geometric = frame.horizontal(body, false)
            assertAngle(
                geometric.angularSeparation(expectedHorizontal) * 3600, tolerance,
                "azimuth and altitude", fixtureCase,
            )

            // Refraction only ever raises the body, and by more the closer to the horizon it is.
            val refracted = frame.horizontal(body, true)
            assertTrue(refracted.altitude >= geometric.altitude)
            assertEquals(geometric.azimuth, refracted.azimuth, 1e-9)
        }
    }

    /**
     * The same comparison with the library's own estimate of ΔT instead of Skyfield's: the Moon moves
     * 0.55″ per second of time, so the ΔT model adds well under an arcsecond.
     */
    @Test
    fun testPlaceFromDateUsesLibraryDeltaT() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val frame = SkyFrame(
                EphemerisFixtureStore.date(fixtureCase.ut1Jd),
                fixtureCase.observer,
            )
            val expectedHorizontal = HorizontalCoordinate(fixtureCase.azDeg, fixtureCase.altDeg)
            val error = frame.horizontal(body, false).angularSeparation(expectedHorizontal) * 3600
            assertAngle(error, (positionToleranceArcseconds[body] ?: 0.0) + 2, "altitude and azimuth", fixtureCase)
        }
    }

    // MARK: Distances and phases

    @Test
    fun testDistancesMatchDE440s() {
        for (fixtureCase in fixtureCases()) {
            val body = fixtureCase.solarSystemBody
            val state = Ephemeris.state(body, fixtureCase.julianDate)
            val tolerance = distanceToleranceRelative[body] ?: 0.0
            assertEquals(
                1.0, state.distanceAU / fixtureCase.distanceAu, tolerance,
                "${fixtureCase.body} distance at ${fixtureCase.utc}",
            )
            assertEquals(state.distanceAU * SkyFrame.kilometersPerAU, state.distanceKilometers, 1e-6)
            val expected = fixtureCase.heliocentricDistanceAu
            if (expected != null) {
                assertEquals(
                    1.0, assertNotNull(state.heliocentricDistanceAU) / expected, tolerance,
                    "${fixtureCase.body} heliocentric distance at ${fixtureCase.utc}",
                )
            }
        }
    }

    @Test
    fun testPhaseElongationAndIlluminationMatchDE440s() {
        for (fixtureCase in fixtureCases()) {
            val state = Ephemeris.state(fixtureCase.solarSystemBody, fixtureCase.julianDate)
            val expectedFraction = fixtureCase.illuminatedFraction
            val expectedPhaseAngle = fixtureCase.phaseAngleDeg
            val expectedElongation = fixtureCase.elongationDeg
            if (expectedFraction == null || expectedPhaseAngle == null || expectedElongation == null) {
                assertEquals("sun", fixtureCase.body)
                assertNull(state.illuminatedFraction)
                assertNull(state.phaseAngle)
                assertNull(state.elongation)
                continue
            }
            val message = "${fixtureCase.body} at ${fixtureCase.utc}"
            assertEquals(expectedFraction, assertNotNull(state.illuminatedFraction), 0.0005, message)
            assertEquals(expectedPhaseAngle, assertNotNull(state.phaseAngle), 0.02, message)
            assertEquals(expectedElongation, assertNotNull(state.elongation), 0.01, message)
        }
    }

    /**
     * Mallama & Hilton (2018) as implemented by Skyfield. Skyfield takes the Sun to be at the
     * solar-system barycentre, which shifts r and α slightly; for Mercury at large phase angles that is
     * worth up to 0.13 mag, for the others at most 0.03 mag (Uranus) — Skyfield also switches on its
     * phase term for Uranus above 3.1° while this implementation ignores it.
     */
    @Test
    fun testPlanetMagnitudesMatchMallamaHilton() {
        val tolerance: Map<SolarSystemBody, Double> = mapOf(
            SolarSystemBody.MERCURY to 0.2, SolarSystemBody.VENUS to 0.05, SolarSystemBody.MARS to 0.05,
            SolarSystemBody.JUPITER to 0.02, SolarSystemBody.SATURN to 0.02, SolarSystemBody.URANUS to 0.03,
            SolarSystemBody.NEPTUNE to 0.03,
        )
        for (fixtureCase in fixtureCases()) {
            val state = Ephemeris.state(fixtureCase.solarSystemBody, fixtureCase.julianDate)
            val expected = fixtureCase.apparentMagnitude
            if (expected == null) {
                assertFalse(fixtureCase.solarSystemBody.isPlanet, "planets have a magnitude in the fixture")
                continue
            }
            assertEquals(
                expected, assertNotNull(state.apparentMagnitude), tolerance[fixtureCase.solarSystemBody] ?: 0.0,
                "${fixtureCase.body} magnitude at ${fixtureCase.utc}",
            )
        }
    }

    // MARK: Moon-specific flags

    @Test
    fun testMoonWaxingFlagFollowsElongation() {
        for (fixtureCase in fixtureCases().filter { it.solarSystemBody == SolarSystemBody.MOON }) {
            val state = Ephemeris.state(SolarSystemBody.MOON, fixtureCase.julianDate)
            val sun = Ephemeris.state(SolarSystemBody.SUN, fixtureCase.julianDate)
            val difference = AstroMath.normalizeDegrees(state.eclipticLongitudeOfDate - sun.eclipticLongitudeOfDate)
            assertEquals(difference < 180, state.isWaxing, "Moon at ${fixtureCase.utc}")
        }
    }
}

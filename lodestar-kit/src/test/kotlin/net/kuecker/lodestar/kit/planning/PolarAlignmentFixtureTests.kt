package net.kuecker.lodestar.kit.planning

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import net.kuecker.lodestar.kit.foundation.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The polar-scope reading against Skyfield (JPL DE440s, IAU 2006/2000A): the fixtures hold the apparent
 * places and the azimuths and altitudes of Polaris and σ Octantis for ten instants between 1957 and 2039,
 * each seen from two of six observing sites.
 * None of the reference numbers comes from LodestarKit.
 */
class PolarAlignmentFixtureTests {
    @Serializable
    private data class StarCase(
        val utc: String,
        val ut1Jd: Double,
        val ttJd: Double,
        val lat: Double,
        val lon: Double,
        val elevationM: Double,
        val star: String,
        val raApparentDeg: Double,
        val decApparentDeg: Double,
        val azDeg: Double,
        val altDeg: Double,
    ) {
        /** The instant with exactly the fixture's UT1 and TT, so ΔT differences do not enter. */
        val julianDate: JulianDate get() = JulianDate(ut1Jd, (ttJd - ut1Jd) * JulianDate.secondsPerDay)

        val frame: SkyFrame
            get() = SkyFrame(julianDate, Observer(GeographicLocation(lat, lon, elevationM)))

        val hemisphere: PolarAlignment.Hemisphere
            get() = if (star == "Polaris") PolarAlignment.Hemisphere.NORTH else PolarAlignment.Hemisphere.SOUTH
    }

    @Serializable
    private data class StarFixture(val cases: List<StarCase>)

    @Serializable
    private data class SiderealCase(val utc: String, val gastHours: Double)

    @Serializable
    private data class SiderealFixture(val cases: List<SiderealCase>)

    /** Reads a fixture from the test resources; keys are decoded from snake case, like Swift's `.convertFromSnakeCase`. */
    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> load(name: String): T {
        val json = Json {
            ignoreUnknownKeys = true
            namingStrategy = JsonNamingStrategy.SnakeCase
        }
        val stream = assertNotNull(
            PolarAlignmentFixtureTests::class.java.getResourceAsStream("/fixtures/$name"),
            "Fixture $name is missing from the test resources",
        )
        return json.decodeFromString<T>(stream.use { it.readBytes().decodeToString() })
    }

    private fun poleStarCases(): List<StarCase> {
        val cases = load<StarFixture>("stars_apparent.json").cases
            .filter { it.star == "Polaris" || it.star == "sigma Octantis" }
        assertTrue(cases.size >= 20, "the fixtures hold both stars at many instants")
        return cases
    }

    /** Difference of two angles in degrees, in (−180, 180]. */
    private fun difference(a: Double, b: Double): Double = AstroMath.normalizeSignedDegrees(a - b)

    /**
     * The reading worked out from the star's azimuth and altitude alone: the direction of the star from the
     * pole in the plane tangent to the sky at the pole, seen by an observer who looks at the pole with the
     * zenith above it, in degrees clockwise from the top.
     */
    private fun drawnAngle(star: StarCase): Pair<Double, Double> {
        val latitude = star.lat
        val northern = star.hemisphere == PolarAlignment.Hemisphere.NORTH
        val pole = Vector3(
            0.0,
            if (northern) AstroMath.cosDeg(latitude) else -AstroMath.cosDeg(latitude),
            if (northern) AstroMath.sinDeg(latitude) else -AstroMath.sinDeg(latitude),
        )
        val zenith = Vector3(0.0, 0.0, 1.0)
        val up = (zenith - pole * zenith.dot(pole)).normalized
        val right = pole.cross(up)
        val direction = HorizontalCoordinate(star.azDeg, star.altDeg).unitVector
        val offset = direction - pole * direction.dot(pole)
        return Pair(
            AstroMath.normalizeDegrees(AstroMath.atan2Deg(offset.dot(right), offset.dot(up))),
            direction.angle(pole),
        )
    }

    // MARK: Against Skyfield

    @Test
    fun testClockAngleMatchesTheAngleDerivedFromAzimuthAndAltitude() {
        // The reference azimuths and altitudes include diurnal aberration (up to 0.3″), which moves the star
        // by up to 0.01° around the pole, so that is the best agreement to ask for.
        for (star in poleStarCases()) {
            val label = "${star.star} at ${star.lat}, ${star.lon} on ${star.utc}"
            val (expectedAngle, expectedSeparation) = drawnAngle(star)
            val reading = PolarAlignment.polarScopeReading(star.frame, star.hemisphere)
            assertEquals(0.0, difference(reading.angle, expectedAngle), 0.015, label)
            assertEquals(expectedSeparation, reading.separationFromPole, 0.001, label)

            val inverted = PolarAlignment.polarScopeReading(star.frame, star.hemisphere, PolarAlignment.ScopeView.INVERTED)
            assertEquals(0.0, difference(inverted.angle, expectedAngle + 180), 0.015, label)
        }
    }

    @Test
    fun testSeparationFromThePoleIsNinetyMinusTheApparentDeclination() {
        for (star in poleStarCases()) {
            val reading = PolarAlignment.polarScopeReading(star.frame, star.hemisphere)
            assertEquals(
                90 - kotlin.math.abs(star.decApparentDeg), reading.separationFromPole, 1e-4,
                "${star.star} on ${star.utc}",
            )
            assertEquals(if (star.star == "Polaris") "Polaris" else "σ Octantis", reading.referenceStarName)
        }
    }

    @Test
    fun testHourAngleIsLocalSiderealTimeMinusTheApparentRightAscension() {
        // Only two of the fixtures' instants also have a Skyfield sidereal time: 2000-01-01T12:00Z and 2026-10-17T00:00Z.
        val sidereal = load<SiderealFixture>("sidereal.json").cases
        var checked = 0
        for (star in poleStarCases()) {
            val instant = sidereal.firstOrNull { it.utc == star.utc } ?: continue
            checked += 1
            val reading = PolarAlignment.polarScopeReading(star.frame, star.hemisphere)
            val expected = AstroMath.normalizeSignedDegrees(instant.gastHours * 15 + star.lon - star.raApparentDeg)
            assertEquals(0.0, difference(reading.hourAngle, expected), 0.002, "${star.star} on ${star.utc}")
            val sign = if (star.hemisphere == PolarAlignment.Hemisphere.NORTH) -1.0 else 1.0
            assertEquals(0.0, difference(reading.angle, sign * expected), 0.002, "${star.star} on ${star.utc}")
        }
        assertTrue(checked >= 4, "at least two instants appear in both fixtures")
    }
}

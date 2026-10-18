package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-checks the Foundation code against the Skyfield / JPL DE440s / ERFA reference fixtures
 * (`Fixtures/README.md`). The Swift test suite does not load these files at the Foundation level; they
 * guard the port against transcription errors. The tolerances are about twice the differences caused
 * by the models themselves (IAU 1976/1980 instead of IAU 2006/2000A, no light deflection or diurnal
 * aberration, Sæmundsson instead of Bennett refraction).
 */
class FoundationFixtureTests {
    @Serializable
    private data class SiderealCase(
        val utc: String,
        @SerialName("ut1_jd") val ut1Jd: Double,
        @SerialName("delta_t_seconds") val deltaTSeconds: Double,
        @SerialName("gmst_hours") val gmstHours: Double,
        @SerialName("gast_hours") val gastHours: Double,
        @SerialName("nutation_dpsi_arcsec") val nutationLongitudeArcseconds: Double,
        @SerialName("nutation_deps_arcsec") val nutationObliquityArcseconds: Double,
        @SerialName("mean_obliquity_deg") val meanObliquity: Double,
        @SerialName("precession_matrix") val precessionMatrix: List<Double>,
        @SerialName("nutation_matrix") val nutationMatrix: List<Double>,
        @SerialName("earth_beta_icrs") val earthBeta: List<Double>,
    )

    @Serializable
    private data class SiderealFile(val cases: List<SiderealCase>)

    @Serializable
    private data class StarCase(
        val utc: String,
        @SerialName("ut1_jd") val ut1Jd: Double,
        @SerialName("tt_jd") val ttJd: Double,
        val site: String,
        val lat: Double,
        val lon: Double,
        @SerialName("elevation_m") val elevation: Double,
        val star: String,
        @SerialName("ra_j2000_deg") val raJ2000: Double,
        @SerialName("dec_j2000_deg") val decJ2000: Double,
        @SerialName("pmra_mas_per_year") val properMotionRa: Double,
        @SerialName("pmdec_mas_per_year") val properMotionDec: Double,
        @SerialName("ra_apparent_deg") val raApparent: Double,
        @SerialName("dec_apparent_deg") val decApparent: Double,
        @SerialName("az_deg") val azimuth: Double,
        @SerialName("alt_deg") val altitude: Double,
        @SerialName("alt_refracted_deg") val altitudeRefracted: Double?,
    )

    @Serializable
    private data class StarFile(val cases: List<StarCase>)

    @Serializable
    private data class BodyCase(
        val utc: String,
        @SerialName("ut1_jd") val ut1Jd: Double,
        @SerialName("tt_jd") val ttJd: Double,
        val lat: Double,
        val lon: Double,
        @SerialName("elevation_m") val elevation: Double,
        val body: String,
        @SerialName("ra_apparent_j2000_deg") val raJ2000: Double,
        @SerialName("dec_apparent_j2000_deg") val decJ2000: Double,
        @SerialName("ra_apparent_deg") val raApparent: Double,
        @SerialName("dec_apparent_deg") val decApparent: Double,
        @SerialName("ra_topo_deg") val raTopocentric: Double,
        @SerialName("dec_topo_deg") val decTopocentric: Double,
        @SerialName("distance_au") val distanceAU: Double,
        @SerialName("az_deg") val azimuth: Double,
        @SerialName("alt_deg") val altitude: Double,
    )

    @Serializable
    private data class BodyFile(val cases: List<BodyCase>)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun fixture(name: String): String {
            val stream = checkNotNull(FoundationFixtureTests::class.java.getResourceAsStream("/fixtures/$name")) {
                "Fixture $name is missing from the test resources"
            }
            return stream.use { it.readBytes().decodeToString() }
        }
    }

    private fun matrixElements(m: Matrix3): List<Double> =
        listOf(m.r0.x, m.r0.y, m.r0.z, m.r1.x, m.r1.y, m.r1.z, m.r2.x, m.r2.y, m.r2.z)

    private fun assertWithin(limit: Double, error: Double, what: String) {
        assertTrue(error <= limit, "$what: $error > $limit")
    }

    /** Time of day difference of two sidereal times given in hours, in seconds. */
    private fun siderealSecondsDifference(degrees: Double, expectedHours: Double): Double =
        abs(AstroMath.normalizeSignedDegrees(degrees - expectedHours * 15)) / 15 * 3600

    @Test
    fun testSiderealTimeAndEarthOrientationMatchReferenceValues() {
        val cases = json.decodeFromString<SiderealFile>(fixture("sidereal.json")).cases
        assertEquals(44, cases.size)
        for (case in cases) {
            // The instant with exactly the fixture's UT1 and ΔT.
            val jd = JulianDate(ut = case.ut1Jd, deltaT = case.deltaTSeconds)
            val label = case.utc

            assertWithin(0.02, siderealSecondsDifference(SiderealTime.greenwichMean(jd), case.gmstHours), "GMST $label")
            val nutation = Nutation(jd)
            val meanObliquity = Obliquity.mean(jd)
            val gast = SiderealTime.greenwichApparent(jd, nutation, meanObliquity + nutation.obliquity)
            assertWithin(0.02, siderealSecondsDifference(gast, case.gastHours), "GAST $label")

            assertWithin(0.03, abs(nutation.longitude * 3600 - case.nutationLongitudeArcseconds), "Δψ $label")
            assertWithin(0.02, abs(nutation.obliquity * 3600 - case.nutationObliquityArcseconds), "Δε $label")
            assertWithin(0.1, abs(meanObliquity - case.meanObliquity) * 3600, "mean obliquity $label")

            val precession = matrixElements(Precession.matrixFromJ2000(jd))
            for ((index, expected) in case.precessionMatrix.withIndex()) {
                assertWithin(0.3, abs(precession[index] - expected) * AstroMath.arcsecondsPerRadian, "P[$index] $label")
            }
            val nutationMatrix = matrixElements(nutation.matrix(meanObliquity))
            for ((index, expected) in case.nutationMatrix.withIndex()) {
                assertWithin(
                    0.03,
                    abs(nutationMatrix[index] - expected) * AstroMath.arcsecondsPerRadian,
                    "N[$index] $label",
                )
            }

            val beta = Aberration.earthVelocity(jd)
            val expectedBeta = Vector3(case.earthBeta[0], case.earthBeta[1], case.earthBeta[2])
            assertWithin(0.05, (beta - expectedBeta).length * AstroMath.arcsecondsPerRadian, "Earth β $label")
        }
    }

    @Test
    fun testDeltaTModelTracksReferenceValuesBetween1950And2026() {
        // After 2026 the model blends towards the Espenak & Meeus long-term parabola and departs from
        // Skyfield's near-constant extrapolation by design (21 s in 2049), so only the span with
        // measured data is compared.
        val cases = json.decodeFromString<SiderealFile>(fixture("sidereal.json")).cases
        var compared = 0
        for (case in cases) {
            val year = JulianDate.decimalYear(case.ut1Jd)
            if (year < 1950 || year > 2026) continue
            compared += 1
            assertWithin(1.0, abs(JulianDate.deltaT(year) - case.deltaTSeconds), "ΔT ${case.utc}")
        }
        assertTrue(compared >= 30, "only $compared instants between 1950 and 2026")
    }

    @Test
    fun testStarApparentPlaceAndHorizontalCoordinatesMatchSkyfield() {
        val cases = json.decodeFromString<StarFile>(fixture("stars_apparent.json")).cases
        assertEquals(240, cases.size)
        var refractedChecked = 0
        for (case in cases) {
            val label = "${case.star} ${case.utc} ${case.site}"
            val jd = JulianDate(ut = case.ut1Jd, deltaT = (case.ttJd - case.ut1Jd) * JulianDate.secondsPerDay)
            val frame = SkyFrame(jd, Observer(GeographicLocation(case.lat, case.lon, case.elevation)))
            val j2000 = EquatorialCoordinate(case.raJ2000, case.decJ2000)
            val properMotion = ProperMotion(case.properMotionRa, case.properMotionDec)

            val apparent = frame.apparentPlace(j2000 = j2000, properMotion = properMotion)
            val expectedApparent = EquatorialCoordinate(case.raApparent, case.decApparent)
            assertWithin(0.3, apparent.angularSeparation(expectedApparent) * 3600, "apparent place $label")

            // Skyfield's az/alt include diurnal aberration (up to 0.32″), which is neglected here.
            val geometric = frame.horizontal(j2000 = j2000, properMotion = properMotion, refraction = false)
            val expectedHorizontal = HorizontalCoordinate(case.azimuth, case.altitude)
            assertWithin(0.6, geometric.angularSeparation(expectedHorizontal) * 3600, "horizontal $label")

            // Bennett (Skyfield) and Sæmundsson differ by up to 0.07′ above the horizon.
            val refractedAltitude = case.altitudeRefracted
            if (refractedAltitude != null && refractedAltitude > 0) {
                refractedChecked += 1
                val refracted = frame.horizontal(j2000 = j2000, properMotion = properMotion, refraction = true)
                assertWithin(5.0, abs(refracted.altitude - refractedAltitude) * 3600, "refracted altitude $label")
            }
        }
        assertTrue(refractedChecked > 50, "only $refractedChecked refracted altitudes were compared")
    }

    @Test
    fun testTopocentricFrameMatchesSkyfieldForSolarSystemBodies() {
        // The fixture's own geocentric J2000 vectors isolate the frame code (precession, nutation,
        // observer position and parallax, sidereal time) from the ephemeris error; the Moon's
        // parallax is about 1°.
        val cases = json.decodeFromString<BodyFile>(fixture("bodies_apparent.json")).cases
        assertEquals(450, cases.size)
        for (case in cases) {
            val label = "${case.body} ${case.utc}"
            val jd = JulianDate(ut = case.ut1Jd, deltaT = (case.ttJd - case.ut1Jd) * JulianDate.secondsPerDay)
            val frame = SkyFrame(jd, Observer(GeographicLocation(case.lat, case.lon, case.elevation)))
            val geocentric = EquatorialCoordinate(case.raJ2000, case.decJ2000).unitVector * case.distanceAU

            val geocentricOfDate = EquatorialCoordinate(frame.precessionNutationMatrix * geocentric)
            val expectedGeocentric = EquatorialCoordinate(case.raApparent, case.decApparent)
            assertWithin(0.3, geocentricOfDate.angularSeparation(expectedGeocentric) * 3600, "geocentric place $label")

            val topocentric = frame.topocentricEquatorial(geocentricJ2000 = geocentric)
            val expectedTopocentric = EquatorialCoordinate(case.raTopocentric, case.decTopocentric)
            assertWithin(0.8, topocentric.angularSeparation(expectedTopocentric) * 3600, "topocentric place $label")

            val horizontal = frame.horizontal(geocentricJ2000 = geocentric, refraction = false)
            val expectedHorizontal = HorizontalCoordinate(case.azimuth, case.altitude)
            assertWithin(0.7, horizontal.angularSeparation(expectedHorizontal) * 3600, "horizontal $label")
        }
    }
}

package net.kuecker.lodestar.kit.catalog

import kotlinx.serialization.json.Json
import net.kuecker.lodestar.kit.catalog.CatalogSmallBodyFixture as Fixture
import net.kuecker.lodestar.kit.comets.AsteroidMagnitudes
import net.kuecker.lodestar.kit.comets.CometMagnitudes
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyBrightness
import net.kuecker.lodestar.kit.comets.SmallBodyElements
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.SmallBodyPhotometry
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Comets and asteroids as catalog objects: categories, position case, factory, peak magnitude estimate, prominence. */
class CatalogSmallBodyModelTests {
    // MARK: Categories

    @Test
    fun testCometAndAsteroidCategories() {
        assertEquals("Comet", ObjectCategory.COMET.displayName)
        assertEquals("Asteroid", ObjectCategory.ASTEROID.displayName)
        assertEquals("sparkle", ObjectCategory.COMET.systemImageName)
        assertEquals("circle.fill", ObjectCategory.ASTEROID.systemImageName)
        assertEquals(ObjectGroup.SOLAR_SYSTEM, ObjectCategory.COMET.group)
        assertEquals(ObjectGroup.SOLAR_SYSTEM, ObjectCategory.ASTEROID.group)
        assertEquals(
            listOf(ObjectCategory.SUN, ObjectCategory.MOON, ObjectCategory.PLANET, ObjectCategory.COMET, ObjectCategory.ASTEROID),
            ObjectGroup.SOLAR_SYSTEM.categories,
        )
        assertEquals(ObjectCategory.COMET, ObjectCategory.fromRawValue("comet"))
        assertEquals(ObjectCategory.ASTEROID, ObjectCategory.fromRawValue("asteroid"))
        assertEquals("\"comet\"", Json.encodeToString(ObjectCategory.serializer(), ObjectCategory.COMET))
        assertEquals(ObjectCategory.ASTEROID, Json.decodeFromString(ObjectCategory.serializer(), "\"asteroid\""))
        // The open-NGC type codes never produce them.
        assertTrue(listOf("G", "OCl", "PN", "*", "Other", "comet").none {
            val category = ObjectCategory.fromOpenNGCType(it)
            category == ObjectCategory.COMET || category == ObjectCategory.ASTEROID
        })
    }

    // MARK: Factory

    @Test
    fun testCometObject() {
        val body = Fixture.tsuchinshanAtlas
        val obj = CelestialObject.fromSmallBody(body)
        assertEquals("comet.C/2023 A3", obj.id)
        assertEquals("C/2023 A3 (Tsuchinshan-ATLAS)", obj.name)
        assertEquals(listOf("C/2023 A3"), obj.designations)
        assertEquals(listOf("Tsuchinshan-ATLAS"), obj.alternateNames)
        assertEquals(ObjectCategory.COMET, obj.category)
        assertEquals(ObjectGroup.SOLAR_SYSTEM, obj.group)
        assertNull(obj.constellation)
        assertNull(obj.constellationName)
        assertNull(obj.magnitude)
        assertNull(obj.angularSize)
        assertNull(obj.colorIndex)
        assertNull(obj.spectralType)
        assertNull(obj.distanceLightYears)
        assertNull(obj.notes)
        assertTrue(obj.catalogNumbers.isEmpty())
        assertEquals(ObjectPosition.SmallBodyOrbit(body), obj.position)
        assertEquals(body, obj.smallBody)
        assertNull(obj.j2000)
        assertTrue(obj.isSolarSystem)
        assertFalse(obj.isDeepSky)
        assertEquals("Comet", obj.subtitle)
        assertTrue(obj.isListed)
        assertEquals(Prominence.SHOWPIECE, obj.prominence)
        assertEquals(3.6858435, assertNotNull(obj.estimatedPeakMagnitude), 1e-6)
        assertEquals(assertNotNull(obj.estimatedPeakMagnitude), obj.rankingMagnitude)
    }

    @Test
    fun testNumberedCometObjectUsesTheNumberedTitle() {
        val obj = CelestialObject.fromSmallBody(Fixture.ponsBrooks)
        assertEquals("comet.12P", obj.id)
        assertEquals("12P/Pons-Brooks", obj.name)
        assertEquals(listOf("12P"), obj.designations)
        assertEquals(listOf("Pons-Brooks"), obj.alternateNames)
        assertEquals(3.9154425, assertNotNull(obj.estimatedPeakMagnitude), 1e-6)
        assertEquals("comet.1P", CelestialObject.fromSmallBody(Fixture.halley).id)
        assertEquals(Prominence.SHOWPIECE, CelestialObject.fromSmallBody(Fixture.haleBopp).prominence)
    }

    @Test
    fun testAsteroidObjects() {
        val vesta = CelestialObject.fromSmallBody(Fixture.vesta)
        assertEquals("asteroid.4", vesta.id)
        assertEquals("(4) Vesta", vesta.name)
        assertEquals(listOf("(4)"), vesta.designations, "a numbered asteroid's designation is its number in parentheses")
        assertEquals(listOf("Vesta"), vesta.alternateNames)
        assertEquals(ObjectCategory.ASTEROID, vesta.category)
        assertEquals("Asteroid", vesta.subtitle)
        assertNull(vesta.magnitude)
        assertNull(vesta.constellation)
        assertEquals(5.2109315, assertNotNull(vesta.estimatedPeakMagnitude), 1e-6)
        assertEquals(Prominence.SHOWPIECE, vesta.prominence)
        assertTrue(vesta.isListed)

        val ceres = CelestialObject.fromSmallBody(Fixture.ceres)
        assertEquals("asteroid.1", ceres.id)
        assertEquals("(1) Ceres", ceres.name)
        assertEquals(6.3134413, assertNotNull(ceres.estimatedPeakMagnitude), 1e-6)
        assertEquals(Prominence.PROMINENT, ceres.prominence)

        val eros = CelestialObject.fromSmallBody(Fixture.eros)
        assertEquals(9.1564466, assertNotNull(eros.estimatedPeakMagnitude), 1e-6, "the Earth distance is never below 0.5 AU")
        assertEquals(Prominence.NOTABLE, eros.prominence)
        assertTrue(eros.isListed)

        val provisional = CelestialObject.fromSmallBody(Fixture.yr4)
        assertEquals("asteroid.2024 YR4", provisional.id)
        assertEquals("2024 YR4", provisional.name)
        assertEquals(listOf("2024 YR4"), provisional.designations)
        assertTrue(provisional.alternateNames.isEmpty())
        assertEquals(Prominence.OBSCURE, provisional.prominence)
        assertFalse(provisional.isListed, "a faint asteroid is found by search only")

        val unnamed = CelestialObject.fromSmallBody(Fixture.numberedUnnamed)
        assertEquals("asteroid.202411", unnamed.id)
        assertEquals(listOf("(202411)"), unnamed.designations)
        assertEquals(Fixture.numberedUnnamed.title, unnamed.name)
    }

    @Test
    fun testBodyWithoutMagnitudeParametersIsObscureAndLast() {
        val obj = CelestialObject.fromSmallBody(Fixture.withoutMagnitude)
        assertNull(obj.estimatedPeakMagnitude)
        assertEquals(Prominence.OBSCURE, obj.prominence)
        assertFalse(obj.isListed)
        assertEquals(Double.POSITIVE_INFINITY, obj.rankingMagnitude)
        assertNull(CelestialObject.custom("x", EquatorialCoordinate(1.0, 2.0)).estimatedPeakMagnitude)
        assertNull(CelestialObject.custom("x", EquatorialCoordinate(1.0, 2.0)).smallBody)
    }

    @Test
    fun testObjectsEqualByValueAndHashByIdentifier() {
        val first = CelestialObject.fromSmallBody(Fixture.vesta)
        val second = CelestialObject.fromSmallBody(Fixture.vesta)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val fresher = CelestialObject.fromSmallBody(withEpoch(Fixture.vesta, 2_461_300.5))
        assertEquals(first.id, fresher.id, "the identifier survives an element update")
        assertEquals(first.hashCode(), fresher.hashCode())
        assertNotEquals(first, fresher)
        assertNotEquals(first, CelestialObject.fromSmallBody(Fixture.ceres))
    }

    // MARK: Peak magnitude estimate

    @Test
    fun testCometEstimateUsesTheClampedSunDistanceAndARightAngleView() {
        val body = Fixture.haleBopp
        val photometry = (body.photometry as SmallBodyPhotometry.Comet).magnitudes
        val r = body.orbit.perihelionDistanceAU
        val expected = photometry.totalMagnitude + 5 * log10(sqrt(1 + r * r)) + photometry.totalSlope * log10(r)
        assertEquals(expected, assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(body)), 1e-12)

        // A sungrazer: 0.05 AU is treated as 0.4226 AU, closer to the Sun than sin 25° nothing can be 25° from it.
        val grazer = syntheticComet(perihelion = 0.05, totalMagnitude = 10.0, slope = 10.0)
        val clamped = 10.0 + 5 * log10(sqrt(1 + 0.4226 * 0.4226)) + 10.0 * log10(0.4226)
        assertEquals(clamped, assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(grazer)), 1e-12)
        assertEquals(0.4226, SmallBodyBrightness.minimumSunDistanceAU)
        assertEquals(0.5, SmallBodyBrightness.minimumAsteroidEarthDistanceAU)
    }

    @Test
    fun testAsteroidEstimateIsTheOppositionAtPerihelion() {
        val body = Fixture.vesta
        val photometry = (body.photometry as SmallBodyPhotometry.Asteroid).magnitudes
        val q = body.orbit.perihelionDistanceAU
        assertEquals(
            photometry.absoluteMagnitude + 5 * log10(q * (q - 1)),
            assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(body)),
            1e-12,
        )
        // The slope parameter does not matter at zero phase.
        val steeper = SmallBody.create(
            "4", "Vesta", SmallBodyKind.ASTEROID, 4, body.orbit,
            SmallBodyPhotometry.Asteroid(assertNotNull(AsteroidMagnitudes.create(photometry.absoluteMagnitude, 0.4))),
        )
        assertEquals(
            assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(body)),
            assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(assertNotNull(steeper))),
            1e-12,
        )
        assertNull(SmallBodyBrightness.peakMagnitudeEstimate(Fixture.withoutMagnitude))
    }

    @Test
    fun testEstimateOrdersTheFamousBodiesAsExpected() {
        val estimates = listOf(Fixture.haleBopp, Fixture.tsuchinshanAtlas, Fixture.vesta, Fixture.ceres, Fixture.eros, Fixture.encke, Fixture.yr4)
            .map { assertNotNull(SmallBodyBrightness.peakMagnitudeEstimate(it)) }
        assertEquals(estimates.sorted(), estimates)
    }

    // MARK: Prominence

    @Test
    fun testSmallBodyProminenceThresholds() {
        assertEquals(6.0, Prominence.showpieceSmallBodyMagnitude)
        assertEquals(8.0, Prominence.prominentSmallBodyMagnitude)
        assertEquals(10.0, Prominence.notableSmallBodyMagnitude)
        assertEquals(Prominence.SHOWPIECE, Prominence.forSmallBody(-20.0))
        assertEquals(Prominence.SHOWPIECE, Prominence.forSmallBody(6.0))
        assertEquals(Prominence.PROMINENT, Prominence.forSmallBody(6.0000001))
        assertEquals(Prominence.PROMINENT, Prominence.forSmallBody(8.0))
        assertEquals(Prominence.NOTABLE, Prominence.forSmallBody(8.0000001))
        assertEquals(Prominence.NOTABLE, Prominence.forSmallBody(10.0))
        assertEquals(Prominence.OBSCURE, Prominence.forSmallBody(10.0000001))
        assertEquals(Prominence.OBSCURE, Prominence.forSmallBody(30.0))
        assertEquals(Prominence.OBSCURE, Prominence.forSmallBody(null))
    }

    @Test
    fun testCometsBrighterThanSixthMagnitudeAreShowpieces() {
        assertEquals(Prominence.SHOWPIECE, CelestialObject.fromSmallBody(Fixture.comet("C/2030 A1", "Bright", 5.9)).prominence)
        assertEquals(Prominence.SHOWPIECE, CelestialObject.fromSmallBody(Fixture.comet("C/2030 A2", "Edge", 6.0)).prominence)
        assertEquals(Prominence.PROMINENT, CelestialObject.fromSmallBody(Fixture.comet("C/2030 A3", "Binocular", 7.0)).prominence)
        assertEquals(Prominence.NOTABLE, CelestialObject.fromSmallBody(Fixture.comet("C/2030 A4", "Camera", 9.0)).prominence)
        val faint = CelestialObject.fromSmallBody(Fixture.comet("C/2030 A5", "Faint", 12.0))
        assertEquals(Prominence.OBSCURE, faint.prominence)
        assertFalse(faint.isListed)
        // The listed objects are exactly the NOTABLE or better ones.
        for (magnitude in listOf(-3.0, 4.0, 6.5, 8.5, 10.0, 11.0, 15.0)) {
            val obj = CelestialObject.fromSmallBody(Fixture.comet("C/2030 B", "B", magnitude))
            assertEquals(obj.prominence <= Prominence.lastListed, obj.isListed, "$magnitude")
        }
    }

    // MARK: Helpers

    private fun syntheticComet(perihelion: Double, totalMagnitude: Double, slope: Double): SmallBody {
        val orbit = assertNotNull(SmallBodyElements.create(perihelion, 0.9, 20.0, 30.0, 40.0, 2_461_000.0, 2_461_200.5))
        val magnitudes = assertNotNull(CometMagnitudes.create(totalMagnitude, slope))
        return assertNotNull(
            SmallBody.create("C/2031 S1", "", SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes)),
        )
    }

    private fun withEpoch(body: SmallBody, epoch: Double): SmallBody {
        val orbit = assertNotNull(
            SmallBodyElements.fromMeanAnomaly(
                semiMajorAxisAU = assertNotNull(body.orbit.semiMajorAxisAU),
                eccentricity = body.orbit.eccentricity, inclination = body.orbit.inclination,
                argumentOfPerihelion = body.orbit.argumentOfPerihelion,
                longitudeOfAscendingNode = body.orbit.longitudeOfAscendingNode, meanAnomaly = 10.0, epochJulianDayTT = epoch,
            ),
        )
        return assertNotNull(SmallBody.create(body.designation, body.name, body.kind, body.number, orbit, body.photometry))
    }
}

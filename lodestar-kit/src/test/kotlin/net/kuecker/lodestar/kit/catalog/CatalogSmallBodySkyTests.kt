package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.catalog.CatalogSmallBodyFixture as Fixture
import net.kuecker.lodestar.kit.comets.SmallBody
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.comets.horizontal
import net.kuecker.lodestar.kit.comets.state
import net.kuecker.lodestar.kit.comets.topocentricEquatorial
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SkyFrame and rise/set helpers for the catalog objects of comets and asteroids. */
class CatalogSmallBodySkyTests {
    private val observer = Observer(GeographicLocation(latitude = 40.0, longitude = -105.0, elevation = 1600.0))

    /** 2026-10-18 03:00 UT. */
    private val date: Instant = Instant.parse("2026-10-18T03:00:00Z")

    private val frame = SkyFrame(date, observer)

    private val bodies: List<SmallBody> = Fixture.comets + listOf(Fixture.oumuamua, Fixture.ceres, Fixture.vesta, Fixture.eros, Fixture.yr4)

    @Test
    fun testHorizontalPositionEqualsTheCometsEngineState() {
        for (body in bodies) {
            val obj = CelestialObject.fromSmallBody(body)
            for (refraction in listOf(true, false)) {
                assertEquals(assertNotNull(frame.horizontal(body, refraction)), frame.horizontal(obj, refraction), body.id)
            }
            assertEquals(frame.horizontal(obj, refraction = true), frame.horizontal(obj), "refraction is on by default")
            // The same pipeline by hand: the state's apparent geocentric vector through the frame.
            val state = assertNotNull(frame.state(body))
            assertEquals(frame.horizontal(state.geocentricJ2000, true), frame.horizontal(obj), body.id)
        }
    }

    @Test
    fun testApparentEquatorialPlaceIsTopocentric() {
        for (body in bodies) {
            val obj = CelestialObject.fromSmallBody(body)
            assertEquals(assertNotNull(frame.topocentricEquatorial(body)), frame.apparentEquatorial(obj), body.id)
        }
        // Eros is 0.3 to 1 AU away in 2026: parallax moves it by arcminutes against the geocentric place.
        val eros = CelestialObject.fromSmallBody(Fixture.eros)
        val geocentric = assertNotNull(frame.state(Fixture.eros)).let { EquatorialCoordinate(frame.precessionNutationMatrix * it.geocentricJ2000) }
        assertTrue(frame.apparentEquatorial(eros).angularSeparation(geocentric) > 0.001)
    }

    @Test
    fun testCurrentMagnitudeIsTheStateMagnitude() {
        for (body in bodies) {
            val obj = CelestialObject.fromSmallBody(body)
            val expected = assertNotNull(frame.state(body)).apparentMagnitude
            assertNotNull(expected, body.id)
            assertEquals(expected, frame.currentMagnitude(obj), body.id)
        }
        // Ceres at opposition-ish distances is a few magnitudes fainter than its peak estimate would let it be at the Sun's side.
        val vesta = CelestialObject.fromSmallBody(Fixture.vesta)
        val magnitude = assertNotNull(frame.currentMagnitude(vesta))
        assertTrue(magnitude >= assertNotNull(vesta.estimatedPeakMagnitude) - 1e-9, "never brighter than the best case: $magnitude")
        assertNull(frame.currentMagnitude(CelestialObject.fromSmallBody(Fixture.withoutMagnitude)), "no parameters, no magnitude")
    }

    @Test
    fun testAngularSizeStaysTheCatalogSize() {
        for (body in bodies) {
            assertNull(frame.currentAngularSize(CelestialObject.fromSmallBody(body)), body.id)
        }
    }

    @Test
    fun testRiseTransitSetEqualsTheCometsEngine() {
        val interval = DateInterval(Instant.parse("2026-10-18T12:00:00Z"), 86_400.0)
        for (body in listOf(Fixture.vesta, Fixture.ceres, Fixture.tsuchinshanAtlas, Fixture.ponsBrooks)) {
            val obj = CelestialObject.fromSmallBody(body)
            val expected = SmallBodyEphemeris.riseTransitSet(body, observer, interval)
            assertEquals(expected, RiseSetCalculator.riseTransitSet(obj, observer, interval), body.id)
        }
        // Vesta in October 2026 is in Taurus-Aries, high in the night sky of Colorado: it rises, transits and sets once.
        val vesta = RiseSetCalculator.riseTransitSet(CelestialObject.fromSmallBody(Fixture.vesta), observer, interval)
        assertEquals(RiseTransitSet.Circumstance.NORMAL, vesta.circumstance)
        assertNotNull(vesta.rise)
        assertNotNull(vesta.transit)
        assertNotNull(vesta.set)
    }

    @Test
    fun testObjectsOfSmallBodiesAreSolarSystemObjects() {
        for (body in bodies) {
            val obj = CelestialObject.fromSmallBody(body)
            assertTrue(obj.isSolarSystem, body.id)
            assertNull(obj.j2000, body.id)
        }
    }

    @Test
    fun testAnOrbitThatCannotBeEvaluatedIsBelowTheHorizon() {
        // 'Oumuamua is on a hyperbolic orbit; the two-body motion is only evaluated within 137 years of perihelion (2017).
        val farFuture = SkyFrame(Instant.parse("2200-01-01T00:00:00Z"), observer)
        val obj = CelestialObject.fromSmallBody(Fixture.oumuamua)
        assertNull(farFuture.state(Fixture.oumuamua))
        assertEquals(HorizontalCoordinate(azimuth = 0.0, altitude = -90.0), farFuture.horizontal(obj))
        assertEquals(HorizontalCoordinate(azimuth = 0.0, altitude = -90.0), farFuture.horizontal(obj, refraction = false))
        assertEquals(EquatorialCoordinate(rightAscension = 0.0, declination = 0.0), farFuture.apparentEquatorial(obj))
        assertNull(farFuture.currentMagnitude(obj))
        val events = RiseSetCalculator.riseTransitSet(obj, observer, DateInterval(Instant.parse("2200-01-01T00:00:00Z"), 86_400.0))
        assertNull(events.rise)
        assertNull(events.set)
        // Within the span it works.
        assertNotNull(frame.state(Fixture.oumuamua))
    }
}

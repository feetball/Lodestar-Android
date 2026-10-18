package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mount.MountError
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountTargetingTest {
    private val catalog: Catalog by lazy { Catalog.loadBundled() }
    private val observer = MountTestSky.boulder
    private val date = MountTestSky.start

    private fun target(id: String): MountTarget =
        MountTargeting.target(checkNotNull(catalog.objectWithID(id)) { id }, observer, date)

    @Test
    fun aStarKeepsItsCatalogPlaceAndProperMotion() {
        val vega = checkNotNull(catalog.objectWithID("star.hip91262"))
        val target = MountTargeting.target(vega, observer, date)
        assertEquals(vega.j2000, target.j2000)
        assertNotNull(target.properMotion)
        assertFalse(target.isSun)
        assertEquals(SkyFrame(date, observer).horizontal(vega.j2000!!, vega.properMotionOrNull(), false).altitude, target.horizontal.altitude, 1e-9)
    }

    private fun net.kuecker.lodestar.kit.catalog.CelestialObject.properMotionOrNull() =
        (position as? net.kuecker.lodestar.kit.catalog.ObjectPosition.Fixed)?.properMotion

    @Test
    fun theMoonIsPlacedAsSeenFromTheObserverNotFromTheEarthsCentre() {
        val moon = target("moon")
        val frame = SkyFrame(date, observer)
        // Looking along the target's J2000 place gives back the Moon's local, geometric direction.
        val back = frame.horizontal(moon.j2000, null, false)
        assertEquals(0.0, back.angularSeparation(moon.horizontal), 0.01)
        // The geocentric direction is up to a degree away, so the conversion matters.
        val geocentric = frame.horizontal(SolarSystemBody.MOON, false)
        assertEquals(0.0, geocentric.angularSeparation(moon.horizontal), 0.01)
        assertFalse(moon.isSun)
    }

    @Test
    fun theSunIsMarked() {
        assertTrue(target("sun").isSun)
    }

    @Test
    fun aPlanetHasAJ2000PlaceNearItsLocalDirection() {
        val jupiter = target("planet.jupiter")
        val frame = SkyFrame(date, observer)
        val check = frame.horizontal(jupiter.j2000, null, false)
        assertTrue(check.angularSeparation(jupiter.horizontal) < 0.01)
    }

    @Test
    fun theAltitudeProblemUsesTheEnginesErrorAndLooksAhead() {
        val low = MountTestSky.target("Orion Nebula", MountTestSky.orionNebula)
        val problem = assertNotNull(MountTargeting.altitudeProblem(low, observer, date, 5.0))
        assertEquals(5.0, problem.limit)
        assertTrue(problem.altitude < 5.0)
        assertTrue(problem.userMessage.contains("below your altitude limit of 5.0°"), problem.userMessage)
        assertIs<MountError>(problem)

        val high = MountTestSky.target("Andromeda", MountTestSky.andromeda)
        assertNull(MountTargeting.altitudeProblem(high, observer, date, 5.0))
        // A target that is up now but sets within the lookahead is refused as well.
        val setting = MountTestSky.target("Altair", MountTestSky.altair)
        val altitudeNow = MountTargeting.altitude(setting.j2000, null, observer, date)
        val limit = altitudeNow - 0.01
        assertNull(MountTargeting.altitudeProblem(setting, observer, date, limit, lookaheadSeconds = 0.0))
        assertNotNull(MountTargeting.altitudeProblem(setting, observer, date, limit, lookaheadSeconds = 600.0))
    }

    @Test
    fun aSlewIsDescribedByDistanceAndChangeOfAzimuthAndAltitude() {
        val from = HorizontalCoordinate(350.0, 40.0)
        val to = HorizontalCoordinate(20.0, 25.0)
        val summary = assertNotNull(MountTargeting.slew(from, to))
        assertEquals(30.0, summary.azimuthChange, 1e-9)
        assertEquals(-15.0, summary.altitudeChange, 1e-9)
        assertTrue(abs(summary.distanceDegrees - from.angularSeparation(to)) < 1e-9)
        assertNull(MountTargeting.slew(null, to))
        assertEquals(-30.0, MountTargeting.slew(to, from)!!.azimuthChange, 1e-9)
    }

    @Test
    fun theMarkerIsWhereTheMountPointsInTheLocalSky() {
        val position = EquatorialCoordinate.fromRightAscensionHours(0.7123, 41.2689)
        val marker = MountMarker.make(position, observer, date)
        val expected = SkyFrame(date, observer).horizontal(position, null, true)
        assertEquals(expected, marker.horizontal)
        assertEquals(1.0, marker.direction.length, 1e-12)
        assertEquals(0.0, marker.direction.angle(expected.unitVector), 1e-9)
    }
}

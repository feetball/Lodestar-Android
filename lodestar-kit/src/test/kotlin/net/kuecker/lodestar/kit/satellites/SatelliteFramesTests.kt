package net.kuecker.lodestar.kit.satellites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.date
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.fixtureText
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.observer
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SatelliteFramesTests {
    private val frames = Json.parseToJsonElement(fixtureText("satellite_reference.json")).jsonObject["frames"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun testSiderealTimeMatchesValladosExample() {
        // Vallado, Fundamentals of Astrodynamics and Applications, example 3-5: 1992 August 20, 12:14 UT1.
        val time = Instant.parse("1992-08-20T12:14:00Z")
        assertEquals(152.578787810, AstroMath.degrees(SatelliteFrames.greenwichMeanSiderealTime(time)), 1e-6)
        val value = SatelliteFrames.greenwichMeanSiderealTime(Instant.parse("2026-10-18T12:00:00Z"))
        assertTrue(value >= 0.0 && value < 2 * PI)
    }

    @Test
    fun testSiderealTimeAndSunMatchSkyfield() {
        assertEquals(6, frames.size)
        for (entry in frames) {
            val time = date(entry["time"]!!.jsonPrimitive.double)
            // Skyfield uses UT1 = UTC + 0.09 s; LodestarKit takes UT1 = UTC: 0.09 s is 0.0004 degrees of rotation.
            val expected = entry["greenwichMeanSiderealTimeDegrees"]!!.jsonPrimitive.double
            val ours = AstroMath.degrees(SatelliteFrames.greenwichMeanSiderealTime(time))
            val difference = abs(AstroMath.normalizeSignedDegrees(ours - expected))
            assertTrue(difference < 0.001, "GMST differs by $difference degrees at $time")

            val sun = entry["sunTemeKilometers"]!!.jsonArray.map { it.jsonPrimitive.double }
            val reference = Vector3(sun[0], sun[1], sun[2])
            val position = SatelliteFrames.sunPosition(time)
            assertTrue(position.angle(reference) < 0.002, "Sun direction differs by ${position.angle(reference)} degrees at $time")
            assertEquals(1.0, position.length / reference.length, 2e-5)
            assertTrue(position.length in 1.46e8..1.53e8)
        }
    }

    @Test
    fun testGeodeticPositions() {
        val equator = SatelliteFrames.earthFixedPosition(GeographicLocation(0.0, 0.0, 0.0))
        assertEquals(6378.137, equator.x, 1e-9)
        assertEquals(0.0, equator.y, 1e-9)
        assertEquals(0.0, equator.z, 1e-9)
        val pole = SatelliteFrames.earthFixedPosition(GeographicLocation(90.0, 0.0, 0.0))
        assertEquals(6356.752314245, pole.z, 1e-6)
        assertEquals(0.0, pole.x, 1e-6)
        val middle = SatelliteFrames.earthFixedPosition(GeographicLocation(45.0, 0.0, 0.0))
        assertEquals(4517.590878849, middle.x, 1e-6)
        assertEquals(4487.348408866, middle.z, 1e-6)
        val boulder = SatelliteFrames.earthFixedPosition(GeographicLocation(40.015, -105.2705, 1655.0))
        assertEquals(-1288.6754988503, boulder.x, 1e-6)
        assertEquals(-4720.1513475913, boulder.y, 1e-6)
        assertEquals(4080.3254410207, boulder.z, 1e-6)
        // Height is measured along the ellipsoid normal.
        val high = SatelliteFrames.earthFixedPosition(GeographicLocation(0.0, 90.0, 1000.0))
        assertEquals(6379.137, high.y, 1e-9)
    }

    @Test
    fun testEarthFixedStateOfAGeostationaryOrbitIsAtRest() {
        val time = Instant.parse("2026-10-18T00:00:00Z")
        val angle = SatelliteFrames.greenwichMeanSiderealTime(time)
        val radius = 42164.0
        // The position over longitude 30 degrees east, moving with the Earth's rotation.
        val longitude = 30.0 * PI / 180
        val inertial = Vector3(radius * cos(angle + longitude), radius * sin(angle + longitude), 0.0)
        val velocity = Vector3(-SatelliteFrames.earthRotationRate * inertial.y, SatelliteFrames.earthRotationRate * inertial.x, 0.0)
        val fixed = SatelliteFrames.earthFixedState(TemeState(inertial, velocity), time)
        assertEquals(radius * cos(longitude), fixed.position.x, 1e-6)
        assertEquals(radius * sin(longitude), fixed.position.y, 1e-6)
        assertEquals(0.0, fixed.velocity.length, 1e-9)
        // A satellite at rest in inertial space moves backwards over the ground at the Earth's rotation speed.
        val still = SatelliteFrames.earthFixedState(TemeState(inertial, Vector3.zero), time)
        assertEquals(SatelliteFrames.earthRotationRate * radius, still.velocity.length, 1e-9)
    }

    /** The TEME state of a point given in the Earth-fixed frame, at rest relative to the ground plus [relative]. */
    private fun temeState(fixed: Vector3, relativeVelocity: Vector3, time: Instant): TemeState {
        val angle = SatelliteFrames.greenwichMeanSiderealTime(time)
        val c = cos(angle)
        val s = sin(angle)
        // Inverse of the rotation to the Earth-fixed frame.
        val position = Vector3(c * fixed.x - s * fixed.y, s * fixed.x + c * fixed.y, fixed.z)
        val rotating = Vector3(c * relativeVelocity.x - s * relativeVelocity.y, s * relativeVelocity.x + c * relativeVelocity.y, relativeVelocity.z)
        val corotation = Vector3(-SatelliteFrames.earthRotationRate * position.y, SatelliteFrames.earthRotationRate * position.x, 0.0)
        return TemeState(position, rotating + corotation)
    }

    @Test
    fun testLookAtASatelliteStraightOverheadAndDueEast() {
        val place = observer(40.0, -105.0, 1600.0)
        val time = Instant.parse("2026-10-18T05:00:00Z")
        val base = SatelliteFrames.earthFixedPosition(place.location)
        val latitude = 40.0 * PI / 180
        val longitude = -105.0 * PI / 180
        val up = Vector3(cos(latitude) * cos(longitude), cos(latitude) * sin(longitude), sin(latitude))
        val east = Vector3(-sin(longitude), cos(longitude), 0.0)
        val north = Vector3(-sin(latitude) * cos(longitude), -sin(latitude) * sin(longitude), cos(latitude))

        val overhead = SatelliteFrames.look(temeState(base + up * 500.0, up * 1.5, time), time, place)
        assertEquals(90.0, overhead.altitude, 1e-6)
        assertEquals(500.0, overhead.rangeKilometers, 1e-6)
        assertEquals(1.5, overhead.rangeRateKilometersPerSecond, 1e-6)

        val eastward = SatelliteFrames.look(temeState(base + east * 100.0 + up * 0.5, east * 7.0, time), time, place)
        assertEquals(90.0, eastward.azimuth, 1e-3)
        assertEquals(0.0, eastward.altitude, 0.3) // the tangent plane through the observer
        assertEquals(7.0, eastward.rangeRateKilometersPerSecond, 0.01)
        assertEquals(eastward.horizontal.unitVector.x, eastward.unitVector.x, 0.0)

        val northward = SatelliteFrames.look(temeState(base + north * 1000.0 + up * 300.0, Vector3.zero, time), time, place)
        assertEquals(0.0, northward.azimuth, 1e-6)
        assertEquals(AstroMath.degrees(kotlin.math.atan2(300.0, 1000.0)), northward.altitude, 0.2)
        assertEquals(0.0, northward.rangeRateKilometersPerSecond, 1e-9)

        val behind = SatelliteFrames.look(temeState(base - up * 800.0, Vector3.zero, time), time, place)
        assertEquals(-90.0, behind.altitude, 1e-6)
        val west = SatelliteFrames.look(temeState(base - east * 500.0 + up * 0.1, Vector3.zero, time), time, place)
        assertEquals(270.0, west.azimuth, 1e-2)
    }
}

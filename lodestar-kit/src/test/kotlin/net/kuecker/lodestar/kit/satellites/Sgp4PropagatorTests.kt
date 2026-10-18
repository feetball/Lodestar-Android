package net.kuecker.lodestar.kit.satellites

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.date
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.fixtureText
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.referenceSatellites
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The SGP4 verification vectors are in [Sgp4VerificationTests]; these tests cover the rest of the propagator API. */
class Sgp4PropagatorTests {
    private val satellites = referenceSatellites()

    @Test
    fun testStatesMatchThePythonSgp4PackageForEveryOrbitType() {
        // The `sgp4` package (Brandon Rhodes' port of the same reference code) for the element sets of the
        // CelesTrak snapshot, at times from 12 hours before to two weeks after the epoch.
        val reference = Json.parseToJsonElement(fixtureText("satellite_reference.json")).jsonObject
        var worstPosition = 0.0
        var worstVelocity = 0.0
        val states = reference["sgp4States"]!!.jsonArray.map { it.jsonObject }
        assertEquals(48, states.size)
        for (entry in states) {
            val id = entry["satellite"]!!.jsonPrimitive.content
            val minutes = entry["minutes"]!!.jsonPrimitive.double
            val result = satellites.getValue(id).propagator.propagate(minutes)
            assertEquals(0, entry["error"]!!.jsonPrimitive.int)
            val state = assertNotNull(result.state, "$id at $minutes min")
            val position = entry["position"]!!.jsonArray.map { it.jsonPrimitive.double }
            val velocity = entry["velocity"]!!.jsonArray.map { it.jsonPrimitive.double }
            worstPosition = max(worstPosition, max(abs(state.position.x - position[0]), max(abs(state.position.y - position[1]), abs(state.position.z - position[2]))))
            worstVelocity = max(worstVelocity, max(abs(state.velocity.x - velocity[0]), max(abs(state.velocity.y - velocity[1]), abs(state.velocity.z - velocity[2]))))
        }
        println("sgp4 package: worst position difference $worstPosition km, velocity $worstVelocity km/s")
        assertTrue(worstPosition < 1e-7, "position differs by $worstPosition km")
        assertTrue(worstVelocity < 1e-10, "velocity differs by $worstVelocity km/s")
    }

    @Test
    fun testTheOrbitTypesAreRecognized() {
        assertTrue(!satellites.getValue("iss").propagator.isDeepSpace)
        assertTrue(!satellites.getValue("starlink").propagator.isDeepSpace)
        assertTrue(!satellites.getValue("polar").propagator.isDeepSpace)
        assertTrue(satellites.getValue("gps").propagator.isDeepSpace)
        assertTrue(satellites.getValue("molniya").propagator.isDeepSpace)
        assertTrue(satellites.getValue("geo").propagator.isDeepSpace)
        val iss = satellites.getValue("iss").propagator
        assertTrue(iss.perigeeAltitudeKilometers in 380.0..440.0, "perigee ${iss.perigeeAltitudeKilometers}")
        assertTrue(iss.apogeeAltitudeKilometers in iss.perigeeAltitudeKilometers..450.0)
        assertTrue(iss.semiMajorAxisEarthRadii in 1.05..1.07)
        val molniya = satellites.getValue("molniya").propagator
        assertTrue(molniya.apogeeAltitudeKilometers > 35_000.0)
        assertTrue(molniya.perigeeAltitudeKilometers < 2_500.0)
    }

    @Test
    fun testTheResultDoesNotDependOnTheOrderOfTheCalls() {
        // The reference resumes its resonance integration from the previous call; this port restarts it, which
        // must give the same numbers whatever was asked before.
        for (id in listOf("molniya", "geo", "gps", "iss")) {
            val propagator = satellites.getValue(id).propagator
            val times = listOf(3000.0, 100.0, -500.0, 3000.0, 20_000.0, 3000.0, -20_000.0, 0.0, 3000.0)
            val first = times.map { propagator.propagate(it).state }
            val again = times.reversed().map { propagator.propagate(it).state }.reversed()
            assertEquals(first, again, id)
            assertEquals(first[0], first[3])
            assertEquals(first[0], first[5])
            assertEquals(first[0], first[8])
            assertEquals(first[0], Sgp4Propagator(propagator.elements).propagate(3000.0).state)
        }
    }

    @Test
    fun testStateAtADateUsesTheEpoch() {
        val propagator = satellites.getValue("iss").propagator
        val epoch = propagator.elements.epoch
        assertEquals(propagator.propagate(0.0), propagator.state(epoch))
        assertEquals(0.0, propagator.minutesSince(epoch), 1e-9)
        assertEquals(90.0, propagator.minutesSince(epoch.addingTimeInterval(5400.0)), 1e-9)
        val later = propagator.state(epoch.addingTimeInterval(5400.0)).state
        val direct = propagator.propagate(90.0).state
        assertNotNull(later)
        assertEquals(direct!!.position.x, later.position.x, 1e-6)
        assertEquals(direct.velocity.z, later.velocity.z, 1e-9)
    }

    @Test
    fun testTheIssStaysOnItsOrbit() {
        val propagator = satellites.getValue("iss").propagator
        for (minutes in listOf(0.0, 30.0, 90.0, 1000.0, -2000.0)) {
            val state = assertNotNull(propagator.propagate(minutes).state)
            val radius = state.position.length
            assertTrue(radius in 6_700.0..6_850.0, "radius $radius at $minutes")
            val speed = state.velocity.length
            assertTrue(speed in 7.6..7.7, "speed $speed at $minutes")
            // Angular momentum is perpendicular to the position and has the orbit's inclination.
            val momentum = state.position.cross(state.velocity)
            assertEquals(51.64, AstroMath.degrees(kotlin.math.acos(momentum.z / momentum.length)), 0.05)
        }
    }

    @Test
    fun testFailuresAreReportedWithTheReferencesCodes() {
        val propagator = satellites.getValue("iss").propagator
        assertEquals(Sgp4Error.NON_FINITE_STATE, propagator.propagate(Double.NaN).error)
        assertEquals(Sgp4Error.NON_FINITE_STATE, propagator.propagate(Double.POSITIVE_INFINITY).error)
        assertEquals(Sgp4Error.TIME_OUT_OF_RANGE, propagator.propagate(Sgp4Propagator.maximumMinutesFromEpoch * 1.01).error)
        assertEquals(Sgp4Error.TIME_OUT_OF_RANGE, propagator.propagate(-2e7).error)
        assertNull(propagator.propagate(Double.NaN).state)
        assertNull(propagator.propagate(0.0).error)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), Sgp4Error.entries.map { it.rawValue })
        assertEquals(Sgp4Error.DECAYED, Sgp4Error.fromRawValue(6))
        assertNull(Sgp4Error.fromRawValue(5))
        assertEquals(6, Sgp4Error.DECAYED.code)
        // A satellite a day after its elements say it re-entered.
        val decayed = OrbitalElements.create(
            catalogNumber = 28872, epoch = date(1_133_226_000.0), meanMotion = 16.46015938, eccentricity = 0.0303955,
            inclination = 96.4736, rightAscensionOfAscendingNode = 157.9986, argumentOfPericenter = 244.0492,
            meanAnomaly = 110.6523, bstar = 0.24476e-3, meanMotionDot = 0.25992681,
        )
        val result = Sgp4Propagator(decayed).propagate(60.0)
        assertEquals(Sgp4Error.DECAYED, result.error)
    }

    @Test
    fun testResultsAreImmutableValues() {
        val a = satellites.getValue("molniya").propagator
        val b = Sgp4Propagator(a.elements)
        assertEquals(a.propagate(1234.5), b.propagate(1234.5))
        assertEquals(a.semiMajorAxisEarthRadii, b.semiMajorAxisEarthRadii, 0.0)
        assertTrue(a.maximumSpeedKilometersPerSecond > 9.0) // Molniya: 10 km/s at perigee
        assertTrue(satellites.getValue("iss").propagator.maximumSpeedKilometersPerSecond in 7.7..9.0)
    }
}

package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.boulder
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.date
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.referenceSatellites
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Satellite], [SatelliteLook] and the Aim-screen support: where the satellite is, now and at culmination. */
class SatelliteTests {
    private val satellites = referenceSatellites()
    private val iss = satellites.getValue("iss")
    private val windowStart: Instant = Instant.parse("2026-10-18T04:00:00Z")

    // The pass of 2026-10-18 11:23:54 - 11:30:40 UTC over Boulder: culmination 74.95 degrees at 11:27:16; the ISS
    // leaves the Earth's shadow at 11:27:45 and the sky is dark (Skyfield: tests/SatelliteReferenceTests).
    private fun culminationPass(): SatellitePass {
        val finder = SatellitePassFinder(boulder, DateInterval(Instant.parse("2026-10-18T11:00:00Z"), 3600.0))
        return finder.passes(iss).single()
    }

    @Test
    fun testLookAtCulminationMatchesThePass() {
        val pass = culminationPass()
        val look = assertNotNull(iss.look(pass.culmination.date, boulder))
        assertEquals(pass.culmination.altitude, look.altitude, 1e-6)
        assertEquals(pass.culmination.azimuth, look.azimuth, 1e-6)
        assertEquals(pass.culmination.rangeKilometers, look.rangeKilometers, 1e-6)
        assertEquals(74.95, look.altitude, 0.01)
        // Refraction raises the apparent altitude by a few arcseconds at this height, and the direction is a unit vector.
        assertTrue(look.apparentAltitude > look.altitude && look.apparentAltitude - look.altitude < 0.01)
        assertEquals(1.0, look.direction.length, 1e-12)
        assertEquals(1.0, look.apparentDirection.length, 1e-12)
        assertTrue(look.direction.angle(look.apparentDirection) < 0.01)
        assertEquals(look.azimuth, look.horizontal.azimuth, 0.0)
        assertEquals(look.apparentAltitude, look.apparentHorizontal.altitude, 0.0)
        // Near the zenith the satellite is closest: range rate about zero, angular speed about 1 degree per second.
        assertTrue(abs(look.rangeRateKilometersPerSecond) < 0.5)
        assertTrue(look.angularSpeed in 0.8..1.5, "angular speed ${look.angularSpeed}")
        assertTrue(look.rangeKilometers in 400.0..460.0)
        assertEquals(iss.catalogNumber, 25544)
        assertEquals("ISS (ZARYA)", iss.name)
        assertEquals(pass.culmination.date, look.date)
    }

    @Test
    fun testVisibilityFollowsSunlightAndTwilight() {
        val inShadow = assertNotNull(iss.look(Instant.parse("2026-10-18T11:26:30Z"), boulder))
        assertTrue(!inShadow.isSunlit && inShadow.illuminatedFraction == 0.0)
        assertTrue(inShadow.observerIsDark())
        assertTrue(!inShadow.isVisible())
        assertNull(inShadow.magnitude)

        val lit = assertNotNull(iss.look(Instant.parse("2026-10-18T11:29:00Z"), boulder))
        assertTrue(lit.isSunlit && lit.illuminatedFraction == 1.0)
        assertTrue(lit.observerIsDark() && lit.isAboveHorizon && lit.isVisible())
        assertTrue(lit.sunAltitude < -6.0)
        assertTrue(!lit.observerIsDark(-90.0))
        assertTrue(!lit.isVisible(-90.0))

        // Daytime: lit, above the horizon, but the observer's sky is bright.
        val day = assertNotNull(iss.look(Instant.parse("2026-10-18T17:57:19Z"), boulder))
        assertTrue(day.isSunlit && day.isAboveHorizon && !day.observerIsDark() && !day.isVisible())
        assertTrue(day.sunAltitude > 20.0)
        assertTrue(day.isVisible(darknessSunAltitude = 90.0))

        // Below the horizon.
        val below = assertNotNull(iss.look(Instant.parse("2026-10-18T12:30:00Z"), boulder))
        assertTrue(!below.isAboveHorizon && !below.isVisible(90.0))
    }

    @Test
    fun testMagnitudeNeedsAnIntrinsicMagnitudeAndSunlight() {
        val lit = assertNotNull(iss.look(Instant.parse("2026-10-18T11:29:00Z"), boulder))
        val magnitude = assertNotNull(lit.magnitude)
        val expected = assertNotNull(SatelliteBrightness.apparentMagnitude(-1.8, lit.rangeKilometers, lit.phaseAngle, lit.illuminatedFraction))
        assertEquals(expected, magnitude, 1e-12)
        assertTrue(magnitude in -5.5..0.0, "magnitude $magnitude")
        assertTrue(lit.phaseAngle in 0.0..180.0)

        assertNull(satellites.getValue("starlink").look(Instant.parse("2026-10-18T11:29:00Z"), boulder)?.magnitude)
        val custom = Satellite(satellites.getValue("starlink").elements, intrinsicMagnitude = 4.0)
        assertNotNull(custom.look(Instant.parse("2026-10-18T11:29:00Z"), boulder)?.magnitude)
        assertNull(Satellite(iss.elements, intrinsicMagnitude = null).look(Instant.parse("2026-10-18T11:29:00Z"), boulder)?.magnitude)
        assertEquals(-1.8, iss.intrinsicMagnitude)
        assertEquals(0.0, satellites.getValue("css").intrinsicMagnitude)
        assertEquals(2.2, satellites.getValue("hst").intrinsicMagnitude)
        assertNull(satellites.getValue("polar").intrinsicMagnitude)
    }

    @Test
    fun testLookIsMissingWhereSgp4Fails() {
        assertNull(iss.look(Instant.parse("2050-01-01T00:00:00Z"), boulder))
        assertNotNull(iss.look(Instant.parse("2026-10-20T00:00:00Z"), boulder))
    }

    @Test
    fun testASharedSkyGivesTheSameLooks() {
        val time = Instant.parse("2026-10-18T11:29:00Z")
        val context = SatelliteSkyContext(time, boulder)
        for (satellite in satellites.values) {
            assertEquals(satellite.look(time, boulder), satellite.look(context))
        }
        assertEquals(time, context.date)
        assertTrue(context.sunAltitude < -6.0 && context.isDark())
        assertTrue(!context.isDark(-30.0))
        assertEquals(context.sunPosition, SatelliteFrames.sunPosition(time))
    }

    @Test
    fun testTrackSamplesTheSkyAtAFixedStep() {
        val start = Instant.parse("2026-10-18T11:24:00Z")
        val end = Instant.parse("2026-10-18T11:30:00Z")
        val track = iss.track(start, end, 30.0, boulder)
        assertEquals(13, track.size)
        for ((index, look) in track.withIndex()) {
            assertEquals(start.timeIntervalSince1970 + 30.0 * index, look.date.timeIntervalSince1970, 1e-6)
            assertEquals(iss.look(look.date, boulder)!!.azimuth, look.azimuth, 1e-9)
        }
        assertTrue(track.first().altitude > 10.0 && track.first().altitude < 20.0)
        assertTrue(track.map { it.altitude }.max() > 70.0)
        // The shadow ends in the middle of the track.
        assertTrue(!track.first().isSunlit && track.last().isSunlit)
        assertEquals(1, track.zipWithNext().count { (a, b) -> a.isSunlit != b.isSunlit })

        assertTrue(iss.track(end, start, 30.0, boulder).isEmpty())
        assertEquals(1, iss.track(start, start, 30.0, boulder).size)
        // The step is clamped to 0.1 ... 3600 s, the number of points to 20 000, an unusable step becomes 1 s.
        assertEquals(1801, iss.track(start, start.addingTimeInterval(180.0), 0.0, boulder).size)
        assertEquals(Satellite.maximumTrackPoints, iss.track(start, start.addingTimeInterval(86_400.0), 0.1, boulder).size)
        assertEquals(181, iss.track(start, start.addingTimeInterval(180.0), Double.NaN, boulder).size)
        assertEquals(2, iss.track(start, start.addingTimeInterval(4000.0), 100_000.0, boulder).size)
    }

    @Test
    fun testTheAimTargetIsAPointOnTheTrack() {
        // The Aim screen shows where to wait: the apparent position at some time, and where the pass peaks.
        val pass = culminationPass()
        val wait = pass.culmination.date.addingTimeInterval(-40.0)
        val waiting = assertNotNull(iss.look(wait, boulder))
        val culminating = assertNotNull(iss.look(pass.culmination.date, boulder))
        val separation = waiting.apparentHorizontal.angularSeparation(culminating.apparentHorizontal)
        assertTrue(separation in 20.0..60.0, "40 s before the zenith the ISS is $separation degrees away")
        assertEquals(pass.culmination.horizontal.angularSeparation(culminating.horizontal), 0.0, 1e-6)
        assertEquals(1.0, pass.culmination.direction.length, 1e-12)
        assertEquals(culminating.direction.x, pass.culmination.direction.x, 1e-9)
    }

    @Test
    fun testNameFallsBackToTheCatalogNumber() {
        val unnamed = OrbitalElements.create(
            catalogNumber = 12345, epoch = date(1_792_294_095.0), meanMotion = 15.5, eccentricity = 0.001,
            inclination = 51.6, rightAscensionOfAscendingNode = 10.0, argumentOfPericenter = 20.0, meanAnomaly = 30.0,
        )
        assertEquals("NORAD 12345", Satellite(unnamed).name)
    }
}

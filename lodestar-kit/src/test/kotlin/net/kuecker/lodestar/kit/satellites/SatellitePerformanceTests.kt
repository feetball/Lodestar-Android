package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Timing of the expensive calls on the sizes the app uses: 100 satellites over three days, and 10 000 Starlink
 * element sets for an exposure. The limits are an order of magnitude above what a desktop needs, so they only
 * catch a regression (a phone is about three to five times slower than the desktop this was measured on); the
 * measured times are printed.
 */
class SatellitePerformanceTests {
    private fun resource(name: String): ByteArray {
        val stream = checkNotNull(SatellitePerformanceTests::class.java.getResourceAsStream("/fixtures/$name")) {
            "The fixture $name is missing from the test resources."
        }
        return stream.use { it.readBytes() }
    }

    private val boulder = Observer(assertNotNull(GeographicLocation.validated(40.015, -105.2705, 1655.0)))
    private val start: Instant = instantFromTimeIntervalSince1970(1_792_296_000.0) // 2026-10-18T04:00:00Z

    private fun starlink(copies: Int): List<Satellite> {
        val base = ElementSetParser.parseJson(resource("satellite_starlink_sample.json")).elements
        val result = ArrayList<Satellite>(base.size * copies)
        for (copy in 0 until copies) {
            for ((position, element) in base.withIndex()) {
                result.add(
                    Satellite(
                        OrbitalElements.create(
                            name = element.name,
                            catalogNumber = 200_000 + 1000 * copy + position,
                            epoch = element.epoch,
                            meanMotion = element.meanMotion,
                            eccentricity = element.eccentricity,
                            inclination = element.inclination,
                            rightAscensionOfAscendingNode = (element.rightAscensionOfAscendingNode + 15.0 * copy) % 360.0,
                            argumentOfPericenter = element.argumentOfPericenter,
                            meanAnomaly = (element.meanAnomaly + 37.0 * copy) % 360.0,
                            bstar = element.bstar,
                        ),
                    ),
                )
            }
        }
        return result
    }

    private fun milliseconds(block: () -> Unit): Double {
        val begin = System.nanoTime()
        block()
        return (System.nanoTime() - begin) / 1.0e6
    }

    @Test
    fun testTenThousandStarlinkSatellitesForOneExposure() {
        var satellites = emptyList<Satellite>()
        val setup = milliseconds { satellites = starlink(81) }
        assertTrue(satellites.size >= 10_000)
        val exposure = DateInterval(instantFromTimeIntervalSince1970(1_792_373_400.0), 30.0) // 2026-10-19T01:30:00Z
        val frames = listOf(
            CameraFrame.create(HorizontalCoordinate(200.0, 50.0), 100.0, 70.0),
            CameraFrame.create(HorizontalCoordinate(200.0, 50.0), 20.0, 13.0),
        )
        for (frame in frames) {
            assertNotNull(frame)
            var assessment: StreakAssessment? = null
            val elapsed = milliseconds { assessment = StreakRisk.assess(satellites, boulder, frame, exposure) }
            val result = assertNotNull(assessment)
            println("assess ${satellites.size} satellites, ${frame.widthDegrees} deg frame: $elapsed ms (setup of the satellites $setup ms), ${result.crossings.size} crossings")
            assertEquals(satellites.size, result.evaluatedCount)
            assertTrue(elapsed < 5_000.0, "the assessment took $elapsed ms")
        }
    }

    @Test
    fun testHundredSatellitesOverThreeDays() {
        val satellites = starlink(1) + ElementSetParser.parseJson(resource("satellite_stations.json")).elements.map { Satellite(it) }
        val chosen = satellites.take(100)
        assertEquals(100, chosen.size)
        val interval = DateInterval(start, 3 * 86_400.0)
        var count = 0
        val elapsed = milliseconds {
            val finder = SatellitePassFinder(boulder, interval)
            for (satellite in chosen) count += finder.passes(satellite).size
        }
        println("passes of 100 satellites over 3 days: $elapsed ms, $count passes")
        assertTrue(count > 100)
        assertTrue(elapsed < 5_000.0, "the pass search took $elapsed ms")
    }
}

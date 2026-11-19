package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.boulder
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.fixtureText
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SatelliteTransitFinder] against an independent reference. The ISS element set is the CelesTrak TLE of 2026-11-19
 * (`satellite_iss_transit.tle`). The expected transits for Boulder in the 14 days from 2026-11-19T17:00Z come from
 * `Tools/make_satellite_transit_reference.py` (a brute-force Skyfield 1.55 + DE440s search that shares no code with
 * the kit):
 *
 *     2026-11-23T20:33:23.62Z  Sun   centreline 23150.6 m @ 357.5°
 *     2026-11-24T03:05:37.61Z  Moon   centreline 15707.9 m @ 230.2°
 *     2026-11-25T02:17:56.29Z  Moon   centreline 40162.7 m @ 241.9°
 *
 * The other checks use only the kit's own public pieces (`Satellite.look`, `SkyFrame`) to measure the separation.
 * (Swift: `SatelliteTransitFinderTests.swift`.)
 */
class SatelliteTransitFinderTests {
    private val satellite = Satellite(ElementSetParser.parseTwoLineText(fixtureText("satellite_iss_transit.tle")).elements.single())
    private val from: Instant = Instant.parse("2026-11-19T17:00:00Z")
    private val to: Instant = from.plusSeconds(14 * 86_400L)

    private fun transits(body: SolarSystemBody, observer: Observer = boulder, maxGround: Double = 50_000.0): List<SatelliteTransit> =
        SatelliteTransitFinder.transits(satellite, body, observer, from, to, maxGround)

    private fun allTransits(): List<SatelliteTransit> =
        (transits(SolarSystemBody.SUN) + transits(SolarSystemBody.MOON)).sortedBy { it.date }

    /** The angle between the satellite and the body's centre from the kit's own `look` and `SkyFrame`, in degrees. */
    private fun separation(body: SolarSystemBody, observer: Observer, date: Instant): Double {
        val look = assertNotNull(satellite.look(date, observer))
        val direction = SkyFrame(date, observer).horizontal(body, false)
        return look.direction.angle(direction.unitVector)
    }

    private fun observer(location: GeographicLocation): Observer = Observer(location)

    @Test
    fun testSatelliteSpanTable() {
        assertEquals(109.0, SatelliteTransitFinder.satelliteSpanMeters(25544))
        assertEquals(55.0, SatelliteTransitFinder.satelliteSpanMeters(48274))
        assertEquals(55.0, SatelliteTransitFinder.satelliteSpanMeters(53239))
        assertEquals(50.0, SatelliteTransitFinder.satelliteSpanMeters(99_999))
    }

    @Test
    fun testTheBoulderTransitsMatchTheSkyfieldReference() {
        val found = allTransits()
        assertEquals(3, found.size)
        assertEquals(listOf(SolarSystemBody.SUN, SolarSystemBody.MOON, SolarSystemBody.MOON), found.map { it.body })
        val expected = listOf(
            Triple("2026-11-23T20:33:23.62Z", 23_150.6, 357.5),
            Triple("2026-11-24T03:05:37.61Z", 15_707.9, 230.2),
            Triple("2026-11-25T02:17:56.29Z", 40_162.7, 241.9),
        )
        for ((transit, reference) in found.zip(expected)) {
            // The kit takes UT1 = UTC and ignores polar motion; the two searches agree to a few milliseconds and tens of metres.
            assertEquals(Instant.parse(reference.first).toEpochMilli() / 1000.0, transit.date.toEpochMilli() / 1000.0, TIME_TOLERANCE)
            val centreline = assertNotNull(transit.centreline)
            assertEquals(reference.second, centreline.distanceMeters, DISTANCE_TOLERANCE)
            assertEquals(reference.third, centreline.bearing, BEARING_TOLERANCE)
            assertTrue(!transit.isFromSite)
            assertEquals(25544, transit.catalogNumber)
        }
    }

    @Test
    fun testTransitsAreChronologicalAndDescribeTheirGeometry() {
        val found = allTransits()
        assertEquals(found.map { it.date }.sorted(), found.map { it.date })
        for (transit in found) {
            assertTrue(transit.date >= from && transit.date <= to)
            val radius = if (transit.body == SolarSystemBody.SUN) 0.2615..0.2725 else 0.24..0.30
            assertTrue(transit.bodyAngularRadius in radius, "radius ${transit.bodyAngularRadius}")
            assertEquals(109.0 / (transit.satelliteRangeKilometers * 1000.0) * 180.0 / Math.PI, transit.satelliteAngularSize, 1e-9)
            assertTrue(transit.angularSpeed in 0.05..2.0, "speed ${transit.angularSpeed}")
            assertTrue(transit.bodyAltitude >= SatelliteTransitFinder.minimumBodyAltitude)
            assertTrue(transit.bodyAzimuth >= 0.0 && transit.bodyAzimuth < 360.0)
            assertEquals(transit.sunAltitude > SatelliteTransitFinder.daylightSunAltitude, transit.isDaylight)
            assertEquals(transit.sunAltitude < SatellitePassFinder.defaultDarknessSunAltitude, transit.isNight)
            assertTrue(!(transit.isDaylight && transit.isNight))
            if (transit.body == SolarSystemBody.SUN) assertTrue(transit.isDaylight)
            // The body's direction is the SkyFrame's.
            val direction = SkyFrame(transit.date, boulder).horizontal(transit.body, false)
            assertEquals(direction.altitude, transit.bodyAltitude, 0.005)
            assertEquals(0.0, abs(AstroMath.normalizeSignedDegrees(direction.azimuth - transit.bodyAzimuth)) * cos(Math.toRadians(direction.altitude)), 0.005)
            // The satellite's direction is the kit's own look; the separation is the one the kit measures independently.
            assertEquals(separation(transit.body, boulder, transit.date), transit.minimumSeparation, 0.005)
            assertEquals(assertNotNull(satellite.look(transit.date, boulder)).rangeKilometers, transit.satelliteRangeKilometers, 1e-6)
            // The crossing of the whole disc takes diameter / speed (the centreline's speed differs by a few per cent).
            assertEquals(2.0 * transit.bodyAngularRadius / transit.angularSpeed, transit.centrelineDurationSeconds, 0.15 * transit.centrelineDurationSeconds)
            assertTrue(transit.centrelineDurationSeconds in 0.2..2.0, "duration ${transit.centrelineDurationSeconds}")
        }
        assertTrue(found.first { it.body == SolarSystemBody.SUN }.isDaylight)
    }

    @Test
    fun testObserverOnTheCentrelineSeesTheSatelliteCrossTheCentre() {
        for (transit in allTransits()) {
            val centreline = assertNotNull(transit.centreline)
            assertTrue(centreline.separation < 0.01)
            val moved = observer(centreline.location)
            // Measured with the kit's own look and SkyFrame, not the finder.
            assertTrue(separation(transit.body, moved, transit.date) < 0.02, "separation ${separation(transit.body, moved, transit.date)}")
            // And found again from there, as a transit from the site itself.
            val again = SatelliteTransitFinder.transits(
                satellite, transit.body, moved, transit.date.minusSeconds(900), transit.date.plusSeconds(900), 0.0,
            )
            val here = again.single()
            assertTrue(here.isFromSite)
            assertTrue(here.minimumSeparation < 0.02, "separation ${here.minimumSeparation}")
            assertEquals(transit.date.toEpochMilli() / 1000.0, here.date.toEpochMilli() / 1000.0, 1.0)
            assertTrue(assertNotNull(here.centreline).distanceMeters < 100.0)
        }
    }

    @Test
    fun testTheCrossingDurationIsTheChordOverTheAngularSpeed() {
        for (transit in allTransits()) {
            val centreline = assertNotNull(transit.centreline)
            val moved = observer(centreline.location)
            val here = SatelliteTransitFinder.transits(
                satellite, transit.body, moved, transit.date.minusSeconds(900), transit.date.plusSeconds(900), 0.0,
            ).single()
            // From the centreline the chord is the whole diameter.
            assertEquals(here.centrelineDurationSeconds, here.siteDurationSeconds, 0.03 * here.centrelineDurationSeconds)
            assertEquals(2.0 * here.bodyAngularRadius / here.angularSpeed, here.siteDurationSeconds, 0.03 * here.siteDurationSeconds)
            // At the ends of the chord the satellite's centre is on the limb of the disc (measured with the kit's own pieces).
            val half = here.siteDurationSeconds / 2.0
            for (sign in listOf(-1.0, 1.0)) {
                val edge = here.date.addingTimeInterval(sign * half)
                assertEquals(here.bodyAngularRadius, separation(transit.body, moved, edge), 0.01)
            }
            // Inside the chord the centre is inside the disc.
            assertTrue(separation(transit.body, moved, here.date) < here.bodyAngularRadius)
        }
    }

    @Test
    fun testASmallerGroundDistanceKeepsTheNearerTransits() {
        assertEquals(1, allTransitsWithin(20_000.0).size)
        assertEquals(2, allTransitsWithin(30_000.0).size)
        assertEquals(3, allTransitsWithin(45_000.0).size)
        assertEquals(0, allTransitsWithin(0.0).size)
        // Negative and non-finite distances are made harmless; a huge one is limited to 200 km.
        assertEquals(0, allTransitsWithin(-5.0).size)
        assertEquals(3, allTransitsWithin(Double.NaN).size)
        assertTrue(allTransitsWithin(1.0e9).size >= 3)
        assertTrue(allTransitsWithin(1.0e9).all { (it.centreline?.distanceMeters ?: 0.0) <= SatelliteTransitFinder.maximumGroundDistanceMeters })
    }

    private fun allTransitsWithin(maxGround: Double): List<SatelliteTransit> =
        listOf(SolarSystemBody.SUN, SolarSystemBody.MOON).flatMap { transits(it, boulder, maxGround) }

    @Test
    fun testNoTransitsWhenTheBodyIsBelowTheHorizon() {
        val passes = SatellitePassFinder(boulder, DateInterval(from, to), minimumAltitude = 3.0).passes(satellite)
        for (body in listOf(SolarSystemBody.SUN, SolarSystemBody.MOON)) {
            // Passes with the body well below the horizon at both ends (it moves a quarter of a degree a minute).
            val withBodyDown = passes.filter { pass ->
                listOf(pass.rise.date, pass.set.date).all { SkyFrame(it, boulder).horizontal(body, false).altitude < -10.0 }
            }
            assertTrue(withBodyDown.size >= 10, "only ${withBodyDown.size} passes with the $body down")
            for (pass in withBodyDown.take(10)) {
                val found = SatelliteTransitFinder.transits(
                    satellite, body, boulder, pass.rise.date.minusSeconds(300), pass.set.date.plusSeconds(300), 200_000.0,
                )
                assertEquals(emptyList(), found)
            }
        }
    }

    @Test
    fun testInvalidRequestsGiveNoTransits() {
        assertEquals(emptyList(), SatelliteTransitFinder.transits(satellite, SolarSystemBody.MARS, boulder, from, to))
        assertEquals(emptyList(), SatelliteTransitFinder.transits(satellite, SolarSystemBody.SUN, boulder, to, from))
        assertEquals(emptyList(), SatelliteTransitFinder.transits(satellite, SolarSystemBody.SUN, boulder, from, from))
    }

    @Test
    fun testTheWindowIsLimitedToThirtyDays() {
        val found = SatelliteTransitFinder.transits(satellite, SolarSystemBody.MOON, boulder, from, from.plusSeconds(90 * 86_400L))
        assertTrue(found.all { it.date <= from.plusSeconds((SatelliteTransitFinder.maximumWindowDays * 86_400).toLong()) })
    }

    @Test
    fun testAFourteenDaySearchOfBothBodiesIsFast() {
        transits(SolarSystemBody.SUN)
        val begin = System.nanoTime()
        allTransits()
        val milliseconds = (System.nanoTime() - begin) / 1.0e6
        println("ISS transits of the Sun and the Moon, 14 days: %.0f ms".format(milliseconds))
        assertTrue(milliseconds < 3_000.0, "took $milliseconds ms")
    }

    @Test
    fun testTransitsBeyondTheGroundDistanceAreDropped() {
        val sun = transits(SolarSystemBody.SUN).single()
        assertNotNull(sun.centreline)
        // With a reach shorter than its centreline distance the transit is dropped, not reported without one.
        assertTrue(transits(SolarSystemBody.SUN, boulder, 10_000.0).isEmpty())
        assertNull(transits(SolarSystemBody.SUN).firstOrNull { it.isFromSite })
    }

    private companion object {
        const val TIME_TOLERANCE = 0.25
        const val DISTANCE_TOLERANCE = 500.0
        const val BEARING_TOLERANCE = 1.0
    }
}

package net.kuecker.lodestar.kit.mosaic

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GalacticCoordinate
import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.planning.DarknessKind
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.summary
import java.time.ZoneId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MilkyWayPanoramaTests {
    private val boulder = Observer(GeographicLocation(latitude = 40.015, longitude = -105.27, elevation = 1650.0))
    private val paranal = Observer(GeographicLocation(latitude = -24.627, longitude = -70.404, elevation = 2635.0))
    private val denver = ZoneId.of("America/Denver")

    /** 2026-07-16 05:00 UTC, 23:00 in Colorado: the core stands in the south, 21° high. */
    private val july = instantFromTimeIntervalSince1970(1_784_178_000.0)

    /** Local noon on 2026-08-12 in Colorado: the night of the new moon. */
    private val august = instantFromTimeIntervalSince1970(1_786_557_600.0)

    /** Local noon on 2026-12-15 in Colorado: the core is up only by day. */
    private val december = instantFromTimeIntervalSince1970(1_797_361_200.0)

    // MARK: The arch

    @Test
    fun testArchIsTheUpperHalfOfTheGalacticPlane() {
        val frame = SkyFrame(july, boulder)
        val arch = MilkyWayPanorama.arch(frame, refraction = false)
        assertTrue(arch.path.size in 175..186, "${arch.path.size}")
        for (point in arch.path) {
            assertTrue(point.horizontal.altitude >= 0.0)
            // Every point is on the galactic plane.
            val place = frame.j2000(point.horizontal, false)
            assertEquals(0.0, CoordinateTransforms.equatorialJ2000ToGalactic(place).latitude, 1e-6)
        }
        // In order of increasing galactic longitude, one degree apart.
        for (index in 0 until arch.path.size - 1) {
            val step = AstroMath.normalizeDegrees(arch.path[index + 1].galacticLongitude - arch.path[index].galacticLongitude)
            assertEquals(1.0, step, 1e-9)
        }
        // The two ends are on the horizon.
        assertTrue(arch.path.first().horizontal.altitude < 1.0)
        assertTrue(arch.path.last().horizontal.altitude < 1.0)
        // The ends of a half circle are opposite each other: 180° apart in azimuth.
        val span = AstroMath.normalizeDegrees(arch.path.last().horizontal.azimuth - arch.path.first().horizontal.azimuth)
        assertEquals(180.0, span, 2.0)
    }

    @Test
    fun testHighestPointAndInclination() {
        val frame = SkyFrame(july, boulder)
        val arch = MilkyWayPanorama.arch(frame, refraction = false)
        val highest = assertNotNull(arch.highestPoint)
        // The north galactic pole (IAU, J2000: 192.85948°, +27.12825°) gives the tilt of the plane independently: inclination = 90° − |altitude of the pole|.
        val pole = frame.horizontal(EquatorialCoordinate(192.85948, 27.12825), null, false)
        assertEquals(90.0 - abs(pole.altitude), arch.inclination, 0.01)
        // A full arch reaches its inclination, and nothing on the path is higher.
        assertEquals(arch.inclination, highest.horizontal.altitude, 1e-6)
        for (point in arch.path) assertTrue(point.horizontal.altitude <= highest.horizontal.altitude + 1e-6)
        // The highest point lies on the plane, at the galactic longitude whose altitude is greatest.
        val best = arch.path.maxByOrNull { it.horizontal.altitude }!!
        assertEquals(0.0, abs(AstroMath.normalizeSignedDegrees(best.galacticLongitude - highest.galacticLongitude)), 1.0)
        assertTrue(highest.horizontal.altitude >= best.horizontal.altitude)
    }

    @Test
    fun testRefractionLiftsTheArchSlightly() {
        val frame = SkyFrame(july, boulder)
        val plain = MilkyWayPanorama.arch(frame, refraction = false)
        val refracted = MilkyWayPanorama.arch(frame, refraction = true)
        assertTrue(refracted.path.size >= plain.path.size)
        assertEquals(plain.inclination, refracted.inclination, 1e-9)
        assertEquals(plain.highestPoint!!.horizontal.altitude, refracted.highestPoint!!.horizontal.altitude, 0.02)
        assertTrue(refracted.core.altitude > plain.core.altitude)
    }

    @Test
    fun testCoreTiltAgainstAFiniteDifference() {
        // The tilt is the angle between the plane's direction at the core and the horizontal direction. Measured here by
        // placing two points of the plane half a degree either side of the core, in azimuth and altitude.
        for (seconds in listOf(1_784_178_000.0, 1_784_178_000.0 - 4 * 3600, 1_784_178_000.0 + 2 * 3600, 1_778_922_000.0)) {
            val frame = SkyFrame(instantFromTimeIntervalSince1970(seconds), boulder)
            val arch = MilkyWayPanorama.arch(frame, refraction = false)
            val before = frame.horizontal(CoordinateTransforms.galacticToEquatorialJ2000(GalacticCoordinate(-0.25, 0.0)), null, false)
            val after = frame.horizontal(CoordinateTransforms.galacticToEquatorialJ2000(GalacticCoordinate(0.25, 0.0)), null, false)
            val altitude = AstroMath.asinDeg((AstroMath.sinDeg(before.altitude) + AstroMath.sinDeg(after.altitude)) / 2)
            val alongHorizon = abs(AstroMath.normalizeSignedDegrees(after.azimuth - before.azimuth)) * AstroMath.cosDeg(altitude)
            val upward = abs(after.altitude - before.altitude)
            val expected = AstroMath.atan2Deg(upward, alongHorizon)
            val tilt = assertNotNull(arch.coreTilt)
            assertEquals(expected, tilt, 0.05, "at $seconds")
            assertTrue(tilt in 0.0..90.0)
        }
    }

    @Test
    fun testArchAboveAHighMinimumAltitudeIsEmpty() {
        val frame = SkyFrame(july, boulder)
        val arch = MilkyWayPanorama.arch(frame, minimumAltitude = 89.0, refraction = false)
        assertTrue(arch.path.isEmpty())
        assertNull(arch.highestPoint)
        assertNull(arch.panoramaRegion())
        assertEquals(89.0, arch.minimumAltitude)
        // A raised floor shortens the path.
        val raised = MilkyWayPanorama.arch(frame, minimumAltitude = 30.0, refraction = false)
        assertTrue(raised.path.size < MilkyWayPanorama.arch(frame, refraction = false).path.size)
        assertTrue(raised.path.all { it.horizontal.altitude >= 30.0 })
        // Arguments that are not numbers fall back to the defaults.
        assertEquals(0.0, MilkyWayPanorama.arch(frame, minimumAltitude = Double.NaN).minimumAltitude)
        assertEquals(MilkyWayPanorama.arch(frame).path.size, MilkyWayPanorama.arch(frame, longitudeStep = Double.NaN).path.size)
        assertTrue(MilkyWayPanorama.arch(frame, longitudeStep = 0.001).path.size > 600)
    }

    @Test
    fun testArchInTheSouthernHemisphere() {
        // From Paranal in July the core passes near the zenith.
        val frame = SkyFrame(july, paranal)
        val arch = MilkyWayPanorama.arch(frame, refraction = false)
        assertTrue(arch.path.size in 175..186)
        assertTrue(arch.core.altitude > 60)
        val times = MilkyWayPanorama.bestTimes(paranal, listOf(DateInterval(july.addingTimeInterval(-6 * 3600.0), july.addingTimeInterval(6 * 3600.0))))
        assertTrue(assertNotNull(times.coreHighest).coreAltitude > 80)
    }

    // MARK: The panorama box

    @Test
    fun testPanoramaBoxCoversTheArch() {
        for (seconds in listOf(1_784_178_000.0, 1_784_178_000.0 - 5 * 3600, 1_773_661_200.0)) {
            val frame = SkyFrame(instantFromTimeIntervalSince1970(seconds), boulder)
            val arch = MilkyWayPanorama.arch(frame, refraction = false)
            val region = assertNotNull(arch.panoramaRegion(minimumAltitude = 5.0, margin = 5.0)) as MosaicRegion.Horizontal
            assertTrue(region.azimuthSpan > 100 && region.azimuthSpan <= 360)
            assertTrue(region.azimuthStart >= 0 && region.azimuthStart < 360)
            assertTrue(region.minimumAltitude >= -90 && region.minimumAltitude < region.maximumAltitude && region.maximumAltitude <= 90)
            // Every point of the arch above 5° lies inside the box, with the margin to spare.
            for (point in arch.path.filter { it.horizontal.altitude >= 5.0 }) {
                val offset = AstroMath.normalizeDegrees(point.horizontal.azimuth - region.azimuthStart)
                assertTrue(offset >= 4.99 && offset <= region.azimuthSpan - 4.99, "azimuth offset $offset of ${region.azimuthSpan}")
                assertTrue(point.horizontal.altitude >= region.minimumAltitude + 4.99 || region.minimumAltitude <= -85.0)
                assertTrue(point.horizontal.altitude <= region.maximumAltitude - 4.99 || region.maximumAltitude >= 89.99)
            }
            // The box is a good region for the planner, and the panels it plans cover the arch.
            val plan = MosaicPlanner.plan(region, MosaicTestSupport.frame(24.0), MosaicOrientation.PORTRAIT)
            val quads = plan.panels.map { MosaicTestSupport.Quad(it) }
            for (point in arch.path.filter { it.horizontal.altitude >= 5.0 }) {
                val vector = point.horizontal.unitVector
                assertTrue(quads.any { it.contains(vector) }, "arch point ${point.horizontal} not covered")
            }
            assertTrue(plan.panelCount in 3..24, "${plan.panelCount}")
        }
    }

    @Test
    fun testPanoramaBoxSettings() {
        val frame = SkyFrame(july, boulder)
        val arch = MilkyWayPanorama.arch(frame, refraction = false)
        val tight = arch.panoramaRegion(minimumAltitude = 20.0, margin = 0.0) as MosaicRegion.Horizontal
        val loose = arch.panoramaRegion(minimumAltitude = 5.0, margin = 10.0) as MosaicRegion.Horizontal
        assertTrue(tight.azimuthSpan < loose.azimuthSpan)
        assertTrue(tight.minimumAltitude >= 19.99)
        assertEquals(arch.highestPoint!!.horizontal.altitude, tight.maximumAltitude, 0.01)
        assertEquals(loose.azimuthSpan - 20.0, (arch.panoramaRegion(5.0, 0.0) as MosaicRegion.Horizontal).azimuthSpan, 1e-9)
        // Margins and floors that are not numbers fall back to 5°; a huge margin is limited to 45°.
        assertEquals(arch.panoramaRegion(5.0, 5.0), arch.panoramaRegion(Double.NaN, Double.NaN))
        val wide = arch.panoramaRegion(5.0, 1000.0) as MosaicRegion.Horizontal
        val bare = arch.panoramaRegion(5.0, 0.0) as MosaicRegion.Horizontal
        assertEquals(bare.azimuthSpan + 90.0, wide.azimuthSpan, 1e-9)
        assertEquals(bare.minimumAltitude - 45.0, wide.minimumAltitude, 1e-9)
        assertEquals(90.0, wide.maximumAltitude)
        // The result is always an altitude range at least a degree high.
        val flat = MilkyWayArch(
            listOf(MilkyWayPoint(10.0, net.kuecker.lodestar.kit.foundation.HorizontalCoordinate(100.0, 30.0))), null, 0.0,
            net.kuecker.lodestar.kit.foundation.HorizontalCoordinate(0.0, 0.0), null, 0.0,
        )
        val box = flat.panoramaRegion(0.0, 0.0) as MosaicRegion.Horizontal
        assertEquals(1.0, box.maximumAltitude - box.minimumAltitude, 1e-9)
        assertEquals(1.0, box.azimuthSpan, 1e-9)
        assertEquals(100.0, box.azimuthStart, 1e-9)
    }

    @Test
    fun testPanoramaOfAnArchPassingNearTheZenithFollowsThePath() {
        // In September at Boulder the plane passes close to the zenith: the box is as wide as the arch is, not narrower.
        val frame = SkyFrame(instantFromTimeIntervalSince1970(1_789_495_200.0 + 9 * 3600), boulder)
        val arch = MilkyWayPanorama.arch(frame, refraction = false)
        assertTrue(arch.inclination > 70)
        val region = assertNotNull(arch.panoramaRegion()) as MosaicRegion.Horizontal
        assertTrue(region.azimuthSpan > 150)
        assertTrue(region.maximumAltitude > 75)
    }

    // MARK: Best times

    @Test
    fun testCoreHighestAgreesWithTheNightSummary() {
        val summary = NightPlanner.summary(august, boulder, denver)
        val expected = assertNotNull(summary.milkyWayCore.bestTime)
        val times = MilkyWayPanorama.bestTimes(summary, DarknessKind.MOON_FREE)
        val highest = assertNotNull(times.coreHighest)
        assertEquals(0.0, abs(highest.date.timeIntervalSince(expected)), 180.0)
        assertEquals(summary.milkyWayCore.maxAltitude!!, highest.coreAltitude, 0.2)
        assertEquals(summary.milkyWayCore.bestAzimuth!!, highest.coreAzimuth, 0.6)
        // The Moon is down all night: astronomical darkness gives the same answer.
        assertEquals(highest.date, MilkyWayPanorama.bestTimes(summary).coreHighest!!.date)
    }

    @Test
    fun testMostHorizontalAndMostVerticalAreTheExtremesOfTheNight() {
        for (start in listOf(july, august)) {
            val summary = NightPlanner.summary(start, boulder, denver)
            val intervals = summary.astronomicalDarknessIntervals
            val times = MilkyWayPanorama.bestTimes(summary)
            val flat = assertNotNull(times.mostHorizontal)
            val steep = assertNotNull(times.mostVertical)
            // Brute force every five minutes.
            var smallest = 90.0
            var largest = 0.0
            for (interval in intervals) {
                var offset = 0.0
                while (offset <= interval.duration) {
                    val frame = SkyFrame(interval.start.addingTimeInterval(offset), boulder)
                    val arch = MilkyWayPanorama.arch(frame, refraction = false)
                    val tilt = arch.coreTilt
                    if (tilt != null && arch.core.altitude >= 10.0) {
                        smallest = minOf(smallest, tilt)
                        largest = maxOf(largest, tilt)
                    }
                    offset += 300.0
                }
            }
            val flatTilt = assertNotNull(flat.coreTilt)
            val steepTilt = assertNotNull(steep.coreTilt)
            assertTrue(flatTilt <= smallest + 1e-6, "flat $flatTilt against $smallest")
            assertEquals(smallest, flatTilt, 1.0)
            assertTrue(steepTilt >= largest - 1e-6, "steep $steepTilt against $largest")
            assertEquals(largest, steepTilt, 1.0)
            assertTrue(flat.coreAltitude >= 10.0 - 1e-6 && steep.coreAltitude >= 10.0 - 1e-6)
            assertTrue(flatTilt <= steepTilt)
            // The moments are inside the dark intervals.
            for (moment in listOf(flat, steep, assertNotNull(times.coreHighest))) {
                assertTrue(intervals.any { moment.date in it }, "${moment.date}")
            }
            // Inclination at the moment agrees with a fresh arch.
            val fresh = MilkyWayPanorama.arch(SkyFrame(flat.date, boulder), refraction = false)
            assertEquals(fresh.inclination, flat.inclination, 1e-6)
            assertEquals(assertNotNull(fresh.coreTilt), flatTilt, 1e-6)
        }
    }

    @Test
    fun testNoCoreInDecemberAtNight() {
        val summary = NightPlanner.summary(december, boulder, denver)
        val times = MilkyWayPanorama.bestTimes(summary)
        assertNull(times.coreHighest)
        assertNull(times.mostHorizontal)
        assertNull(times.mostVertical)
        // Raising the limit hides July's core as well: it only gets 21° high.
        val high = MilkyWayPanorama.bestTimes(NightPlanner.summary(july, boulder, denver), minimumCoreAltitude = 30.0)
        assertNull(high.coreHighest)
        assertNotNull(MilkyWayPanorama.bestTimes(NightPlanner.summary(july, boulder, denver), minimumCoreAltitude = 15.0).coreHighest)
        // Nothing to search.
        assertNull(MilkyWayPanorama.bestTimes(boulder, emptyList()).coreHighest)
    }

    @Test
    fun testBestTimesHandleASingleInstant() {
        val instant = DateInterval(july, 0.0)
        val times = MilkyWayPanorama.bestTimes(boulder, listOf(instant))
        for (moment in listOf(times.coreHighest, times.mostHorizontal, times.mostVertical)) {
            assertEquals(0.0, abs(assertNotNull(moment).date.timeIntervalSince(july)), 1e-6)
        }
        assertEquals(times.mostHorizontal!!.coreTilt, times.mostVertical!!.coreTilt)
    }

    @Test
    fun testMilkyWayHelpersAreFast() {
        val frame = SkyFrame(july, boulder)
        MilkyWayPanorama.arch(frame)
        var start = System.nanoTime()
        repeat(50) { MilkyWayPanorama.arch(frame) }
        val archMilliseconds = (System.nanoTime() - start) / 50.0 / 1e6
        assertTrue(archMilliseconds < 20.0, "$archMilliseconds ms per arch")
        val summary = NightPlanner.summary(july, boulder, denver)
        MilkyWayPanorama.bestTimes(summary)
        start = System.nanoTime()
        repeat(10) { MilkyWayPanorama.bestTimes(summary) }
        val timesMilliseconds = (System.nanoTime() - start) / 10.0 / 1e6
        assertTrue(timesMilliseconds < 300.0, "$timesMilliseconds ms per search")
    }
}

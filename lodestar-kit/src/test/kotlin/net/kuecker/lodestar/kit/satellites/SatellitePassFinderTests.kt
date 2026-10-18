package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.boulder
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.geostationary
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.observer
import net.kuecker.lodestar.kit.satellites.SatelliteTestSupport.referenceSatellites
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Properties of [SatellitePassFinder]; the times against Skyfield are in [SatelliteReferenceTests]. */
class SatellitePassFinderTests {
    private val satellites = referenceSatellites()
    private val iss = satellites.getValue("iss")
    private val start: Instant = Instant.parse("2026-10-18T04:00:00Z")
    private val threeDays = DateInterval(start, 3 * 86_400.0)

    private fun issPasses(): List<SatellitePass> = SatellitePassFinder(boulder, threeDays).passes(iss)

    @Test
    fun testPassesAreOrderedAndInternallyConsistent() {
        val passes = issPasses()
        assertEquals(18, passes.size)
        var previousSet = start
        for (pass in passes) {
            assertTrue(pass.rise.date >= previousSet)
            assertTrue(pass.rise.date < pass.culmination.date && pass.culmination.date < pass.set.date)
            previousSet = pass.set.date
            // Rise and set are where the altitude equals the threshold.
            assertEquals(10.0, pass.rise.altitude, 0.01)
            assertEquals(10.0, pass.set.altitude, 0.01)
            assertTrue(pass.culmination.altitude >= 10.0)
            assertEquals(pass.set.date.timeIntervalSince(pass.rise.date), pass.duration, 1e-9)
            assertEquals(DateInterval(pass.rise.date, pass.set.date), pass.interval)
            assertTrue(pass.duration in 60.0..700.0, "duration ${pass.duration}")
            assertTrue(!pass.isRiseCut && !pass.isSetCut)
            // No point of the pass is higher than the culmination.
            var offset = 0.0
            while (offset <= pass.duration) {
                val altitude = iss.look(pass.rise.date.addingTimeInterval(offset), boulder)!!.altitude
                assertTrue(altitude <= pass.culmination.altitude + 1e-6)
                assertTrue(altitude >= 10.0 - 0.01)
                offset += 10.0
            }
            // Visible parts lie inside the pass, in order, and the satellite is lit and the sky dark there.
            var cursor = pass.rise.date
            for (part in pass.visibleIntervals) {
                assertTrue(part.start >= cursor && part.end <= pass.set.date && part.duration > 0.0)
                cursor = part.end
                val inside = iss.look(part.start.addingTimeInterval(part.duration / 2), boulder)!!
                assertTrue(inside.isVisible())
            }
            assertEquals(pass.visibleIntervals.isNotEmpty(), pass.isVisible)
            assertEquals(pass.visibleIntervals.firstOrNull()?.start, pass.visibleSpan?.start)
            assertEquals(pass.visibleIntervals.lastOrNull()?.end, pass.visibleSpan?.end)
        }
        assertEquals(4, passes.count { it.isVisible })
        assertTrue(passes.all { it.brightest == null || it.isVisible })
    }

    @Test
    fun testTheBrightestPointOfAVisiblePassIsTheBrightestSample() {
        val visible = issPasses().first { it.isVisible }
        val brightest = assertNotNull(visible.brightest)
        assertTrue(visible.visibleIntervals.any { brightest.date >= it.start && brightest.date <= it.end })
        val magnitude = assertNotNull(brightest.magnitude)
        for (part in visible.visibleIntervals) {
            var offset = 0.0
            while (offset <= part.duration) {
                val look = iss.look(part.start.addingTimeInterval(offset), boulder)!!
                assertTrue(look.magnitude!! >= magnitude - 1e-6, "magnitude ${look.magnitude} is brighter than $magnitude")
                offset += 1.0
            }
        }
        assertTrue(magnitude in -5.5..-0.5, "magnitude $magnitude")
        // A satellite with an unknown intrinsic magnitude has no brightest point.
        val starlink = SatellitePassFinder(boulder, threeDays).passes(satellites.getValue("starlink")).filter { it.isVisible }
        assertTrue(starlink.isNotEmpty() && starlink.all { it.brightest == null })
    }

    @Test
    fun testAHigherThresholdGivesShorterPassesInsideTheLowerOnes() {
        val low = SatellitePassFinder(boulder, threeDays, minimumAltitude = 10.0).passes(iss)
        val high = SatellitePassFinder(boulder, threeDays, minimumAltitude = 40.0).passes(iss)
        assertTrue(high.size in 1 until low.size)
        for (pass in high) {
            val containing = low.single { it.rise.date <= pass.culmination.date && pass.culmination.date <= it.set.date }
            assertTrue(pass.rise.date > containing.rise.date && pass.set.date < containing.set.date)
            assertEquals(containing.culmination.altitude, pass.culmination.altitude, 1e-3)
            assertEquals(40.0, pass.rise.altitude, 0.01)
        }
        // A threshold of 80 degrees leaves only a near-zenith pass or none.
        assertTrue(SatellitePassFinder(boulder, threeDays, minimumAltitude = 80.0).passes(iss).isEmpty())
        // A negative threshold lengthens the pass.
        val horizon = SatellitePassFinder(boulder, threeDays, minimumAltitude = 0.0).passes(iss)
        assertTrue(horizon.size >= low.size)
        assertEquals(0.0, horizon[0].rise.altitude, 0.01)
    }

    @Test
    fun testASubWindowGivesTheSamePassesCutAtItsEdges() {
        val full = issPasses()
        val target = full[3]
        // A window that starts in the middle of a pass and ends in the middle of the next one.
        val next = full[4]
        val begin = target.rise.date.addingTimeInterval(target.duration / 2)
        val finish = next.rise.date.addingTimeInterval(next.duration / 3)
        val sub = SatellitePassFinder(boulder, DateInterval(begin, finish)).passes(iss)
        assertEquals(2, sub.size)
        assertTrue(sub[0].isRiseCut && !sub[0].isSetCut)
        assertTrue(!sub[1].isRiseCut && sub[1].isSetCut)
        assertEquals(begin, sub[0].rise.date)
        assertEquals(finish.timeIntervalSince1970, sub[1].set.date.timeIntervalSince1970, 1e-6)
        assertEquals(target.set.date.timeIntervalSince1970, sub[0].set.date.timeIntervalSince1970, 0.02)
        assertEquals(next.rise.date.timeIntervalSince1970, sub[1].rise.date.timeIntervalSince1970, 0.02)
        // The rising part of the second pass has the full pass's first half.
        assertEquals(next.rise.azimuth, sub[1].rise.azimuth, 0.01)
        assertTrue(sub[1].culmination.altitude <= next.culmination.altitude + 1e-6)
        // A window that holds the whole culmination of the pass reports it.
        val around = SatellitePassFinder(boulder, DateInterval(target.rise.date.addingTimeInterval(-60.0), target.duration + 120.0)).passes(iss)
        assertEquals(1, around.size)
        assertEquals(target.culmination.altitude, around[0].culmination.altitude, 1e-3)
        assertEquals(target.culmination.date.timeIntervalSince1970, around[0].culmination.date.timeIntervalSince1970, 0.1)
    }

    @Test
    fun testAGeostationarySatelliteOverTheObserverIsOnePassCutAtBothEnds() {
        val epoch = Instant.parse("2026-10-18T00:00:00Z")
        val geo = Satellite(geostationary(30.0, epoch))
        val equator = observer(0.0, 30.0)
        val passes = SatellitePassFinder(equator, DateInterval(epoch.addingTimeInterval(3600.0), 2 * 86_400.0)).passes(geo)
        assertEquals(1, passes.size)
        assertTrue(passes[0].isRiseCut && passes[0].isSetCut)
        assertEquals(epoch.addingTimeInterval(3600.0), passes[0].rise.date)
        assertEquals(epoch.addingTimeInterval(3600.0 + 2 * 86_400.0).timeIntervalSince1970, passes[0].set.date.timeIntervalSince1970, 1e-6)
        assertTrue(passes[0].culmination.altitude > 85.0, "altitude ${passes[0].culmination.altitude}")
        // From the other side of the Earth it never rises.
        assertTrue(SatellitePassFinder(observer(0.0, -150.0), DateInterval(epoch, 86_400.0)).passes(geo).isEmpty())
    }

    @Test
    fun testASatelliteThatNeverReachesTheObserver() {
        // The ISS (51.6 degrees inclination) never gets 10 degrees above the horizon of the North Pole.
        assertTrue(SatellitePassFinder(observer(89.9, 0.0), threeDays).passes(iss).isEmpty())
        // The polar-orbiting ENVISAT does, many times a day.
        val polar = SatellitePassFinder(observer(89.9, 0.0), DateInterval(start, 86_400.0)).passes(satellites.getValue("polar"))
        assertTrue(polar.size >= 10)
    }

    @Test
    fun testADecayedSatelliteHasNoPasses() {
        val decayed = OrbitalElements.create(
            catalogNumber = 28872, epoch = Instant.parse("2005-11-29T00:00:00Z"), meanMotion = 16.46015938, eccentricity = 0.0303955,
            inclination = 96.4736, rightAscensionOfAscendingNode = 157.9986, argumentOfPericenter = 244.0492,
            meanAnomaly = 110.6523, bstar = 0.24476e-3, meanMotionDot = 0.25992681,
        )
        val window = DateInterval(Instant.parse("2005-12-01T00:00:00Z"), 3 * 86_400.0)
        assertTrue(SatellitePassFinder(boulder, window).passes(Satellite(decayed)).isEmpty())
        // Far from the epoch SGP4 refuses altogether.
        assertTrue(SatellitePassFinder(boulder, DateInterval(Instant.parse("2050-01-01T00:00:00Z"), 86_400.0)).passes(iss).isEmpty())
    }

    @Test
    fun testInvalidSettingsFallBackToDefaults() {
        val finder = SatellitePassFinder(boulder, threeDays, minimumAltitude = Double.NaN, darknessSunAltitude = -6.0)
        assertEquals(issPasses().size, finder.passes(iss).size)
        assertEquals(10.0, SatellitePassFinder.defaultMinimumAltitude, 0.0)
        assertEquals(-6.0, SatellitePassFinder.defaultDarknessSunAltitude, 0.0)
        // A zero-length window has no passes, a huge window is cut at 30 days.
        assertTrue(SatellitePassFinder(boulder, DateInterval(start, 0.0)).passes(iss).isEmpty())
        val month = SatellitePassFinder(boulder, DateInterval(start, 400 * 86_400.0)).passes(iss)
        assertTrue(month.last().rise.date < start.addingTimeInterval(SatellitePassFinder.maximumWindowDays * 86_400.0 + 1))
        assertTrue(month.size > 100)
        assertTrue(month.last().isSetCut || month.last().set.date < start.addingTimeInterval(30 * 86_400.0))
    }

    @Test
    fun testTheDarknessThresholdDecidesWhatIsVisible() {
        val civil = SatellitePassFinder(boulder, threeDays, darknessSunAltitude = -6.0).passes(iss)
        val astronomical = SatellitePassFinder(boulder, threeDays, darknessSunAltitude = -18.0).passes(iss)
        val daylight = SatellitePassFinder(boulder, threeDays, darknessSunAltitude = 90.0).passes(iss)
        val visibleCivil = civil.sumOf { pass -> pass.visibleIntervals.sumOf { it.duration } }
        val visibleAstronomical = astronomical.sumOf { pass -> pass.visibleIntervals.sumOf { it.duration } }
        val visibleAnytime = daylight.sumOf { pass -> pass.visibleIntervals.sumOf { it.duration } }
        assertTrue(visibleAstronomical <= visibleCivil && visibleCivil < visibleAnytime)
        // With no darkness requirement the visible time is the sunlit time of the passes.
        for (pass in daylight) {
            for (part in pass.visibleIntervals) {
                assertTrue(iss.look(part.start.addingTimeInterval(part.duration / 2), boulder)!!.isSunlit)
            }
        }
    }

    @Test
    fun testOneFinderServesManyThreads() {
        val finder = SatellitePassFinder(boulder, threeDays)
        val expected = satellites.values.map { finder.passes(it) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { round ->
                pool.submit(Callable { satellites.values.map { finder.passes(it) } })
            }
            for (future in futures) assertEquals(expected, future.get())
        } finally {
            pool.shutdown()
        }
        assertTrue(expected.all { it.all { pass -> abs(pass.duration) >= 0.0 } })
    }

    @Test
    fun testPassesOfDeepSpaceOrbitsLastHours() {
        val molniya = SatellitePassFinder(observer(-33.8688, 151.2093, 20.0), DateInterval(start.addingTimeInterval(4 * 3600.0), 3 * 86_400.0))
            .passes(satellites.getValue("molniya"))
        assertEquals(4, molniya.size)
        // Three long passes of an hour and a half and a graze of six minutes that peaks at 10.3 degrees.
        assertEquals(listOf(1.54, 1.61, 0.10, 1.67), molniya.map { Math.round(it.duration / 36.0) / 100.0 })
        assertEquals(10.3, molniya[2].culmination.altitude, 0.05)
        assertNull(molniya.firstOrNull { it.culmination.altitude < 10.0 })
        // The two passes of the geosynchronous TDRS 7 last sixteen hours each.
        val geo = SatellitePassFinder(observer(-33.8688, 151.2093, 20.0), DateInterval(start.addingTimeInterval(4 * 3600.0), 3 * 86_400.0))
            .passes(satellites.getValue("geo"))
        assertEquals(listOf(true, false, false, true), geo.map { it.isRiseCut || it.isSetCut })
        assertEquals(listOf(16.13, 16.14), geo.filter { !it.isRiseCut && !it.isSetCut }.map { Math.round(it.duration / 36.0) / 100.0 })
    }
}

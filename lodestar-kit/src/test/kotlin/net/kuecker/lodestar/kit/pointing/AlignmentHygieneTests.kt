package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.PointingTestSupport as Support
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.nextDown
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlignmentHygieneTests {
    private val mount = MountProfile.backCameraPortrait
    private val alignedAt = Instant.ofEpochSecond(1_800_000_000)

    /** A sample looking at the given place at `time` seconds. */
    private fun sample(time: Double, azimuth: Double = 0.0, altitude: Double = 30.0): SteadinessSample =
        assertNotNull(SteadinessSample.create(time, HorizontalCoordinate(azimuth, altitude)))

    /** The detector after samples every 1/8 s from `from` to `through` (both included, exact binary fractions). */
    private fun feed(
        detector: SteadinessDetector,
        from: Double,
        through: Double,
        azimuth: Double = 0.0,
        altitude: Double = 30.0,
    ): SteadinessDetector {
        var result = detector
        var step = (from * 8).toInt()
        while (step <= (through * 8).toInt()) {
            result = result.adding(sample(step / 8.0, azimuth, altitude))
            step += 1
        }
        return result
    }

    /** An alignment point whose recorded target is the given place. */
    private fun point(azimuth: Double, altitude: Double, name: String? = null, index: Int = 0): AlignmentPoint {
        val target = HorizontalCoordinate(azimuth, altitude)
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(target, 0.0))
        return AlignmentPoint(attitude, target, Instant.ofEpochSecond(1_800_000_000L + index), name)
    }

    /**
     * Great-circle distance in degrees from the haversine formula (Sinnott, Sky & Telescope 68, 159, 1984), which
     * shares no code with the unit-vector angle the engine uses.
     */
    private fun haversine(azimuth1: Double, altitude1: Double, azimuth2: Double, altitude2: Double): Double {
        val phi1 = Math.toRadians(altitude1)
        val phi2 = Math.toRadians(altitude2)
        val lambda = Math.toRadians(azimuth2 - azimuth1)
        val a = sin((phi2 - phi1) / 2) * sin((phi2 - phi1) / 2) + cos(phi1) * cos(phi2) * sin(lambda / 2) * sin(lambda / 2)
        return Math.toDegrees(2 * asin(sqrt(a)))
    }

    // MARK: Steadiness window

    @Test
    fun testNotEnoughHistoryIsNeverSteady() {
        var detector = SteadinessDetector()
        assertFalse(detector.isSteady)
        assertEquals(0.0, detector.progress)
        assertEquals(0, detector.sampleCount)
        detector = detector.adding(sample(5.0))
        assertFalse(detector.isSteady)
        assertEquals(0.0, detector.progress)
        assertEquals(1, detector.sampleCount)
        // 1.375 s of samples is short of the 1.5 s window, however still the phone was.
        detector = feed(SteadinessDetector(), 0.0, 1.375)
        assertFalse(detector.isSteady)
        assertEquals(1.375 / 1.5, detector.progress, 1e-12)
        // A longer window needs longer.
        assertFalse(feed(SteadinessDetector(windowSeconds = 3.0), 0.0, 2.875).isSteady)
        assertTrue(feed(SteadinessDetector(windowSeconds = 3.0), 0.0, 3.0).isSteady)
    }

    @Test
    fun testAFullWindowOfStillSamplesIsSteady() {
        val half = feed(SteadinessDetector(), 0.0, 0.75)
        assertEquals(0.5, half.progress, 1e-12)
        val detector = feed(SteadinessDetector(), 0.0, 1.5)
        assertTrue(detector.isSteady)
        assertEquals(1.0, detector.progress)
        assertEquals(13, detector.sampleCount)
        // And it stays so.
        assertTrue(feed(detector, 1.625, 4.0).isSteady)
    }

    @Test
    fun testOneOutlierBreaksSteadinessUntilItLeavesTheWindow() {
        var detector = feed(SteadinessDetector(), 0.0, 2.0)
        assertTrue(detector.isSteady)
        // One sample a degree off, as when the tripod is bumped.
        detector = detector.adding(sample(2.125, altitude = 31.0))
        assertFalse(detector.isSteady)
        assertEquals(0.0, detector.progress)
        // Back in place: the outlier is still in the window (inclusive at its start) until 3.625.
        var time = 2.25
        while (time <= 3.625) {
            detector = detector.adding(sample(time))
            assertFalse(detector.isSteady, "at $time")
            if (time == 2.5) assertEquals(0.25 / 1.5, detector.progress, 1e-12)
            time += 0.125
        }
        // One step later it has left the window.
        detector = detector.adding(sample(3.75))
        assertTrue(detector.isSteady)
        assertEquals(1.0, detector.progress)
    }

    @Test
    fun testSampleExactlyAtToleranceCounts() {
        val still = sample(0.0)
        val off = sample(1.5, altitude = 30.35)
        // The tolerance is the angle the detector measures between the newest sample and the others.
        val tolerance = off.direction.angle(still.direction)
        assertEquals(0.35, tolerance, 1e-9)
        for ((toleranceDegrees, expected) in listOf(tolerance to true, tolerance.nextDown() to false)) {
            var detector = SteadinessDetector(toleranceDegrees = toleranceDegrees)
            detector = feed(detector, 0.0, 1.375)
            detector = detector.adding(off)
            assertEquals(expected, detector.isSteady, "tolerance $toleranceDegrees")
        }
    }

    @Test
    fun testSlowDriftWithinToleranceIsSteady() {
        // 0.2° per second: 0.3° across the 1.5 s window, inside the 0.35° tolerance; 0.5° per second: 0.75°.
        var slow = SteadinessDetector()
        var fast = SteadinessDetector()
        var step = 0
        while (step <= 24) {
            val time = step / 8.0
            slow = slow.adding(sample(time, altitude = 30.0 + 0.2 * time))
            fast = fast.adding(sample(time, altitude = 30.0 + 0.5 * time))
            step += 1
        }
        assertTrue(slow.isSteady)
        assertFalse(fast.isSteady)
        // Only the last 0.625 s stayed within the tolerance of the newest sample (5 steps of 0.0625°).
        assertEquals(0.625 / 1.5, fast.progress, 1e-9)
    }

    @Test
    fun testAGapStartsTheWindowOver() {
        val steady = feed(SteadinessDetector(), 0.0, 2.0)
        assertTrue(steady.isSteady)
        // A pause of exactly the maximum gap is not a gap.
        assertTrue(steady.adding(sample(2.5)).isSteady)
        // A longer one is: nothing is known about the time in between.
        var detector = steady.adding(sample(2.75))
        assertFalse(detector.isSteady)
        assertEquals(0.0, detector.progress)
        assertEquals(1, detector.sampleCount)
        detector = feed(detector, 2.875, 4.125)
        assertFalse(detector.isSteady)
        detector = detector.adding(sample(4.25))
        assertTrue(detector.isSteady)
    }

    @Test
    fun testSamplesThatAreNotLaterThanTheNewestOne() {
        val steady = feed(SteadinessDetector(), 0.0, 2.0)
        // The same instant again is ignored, whatever it says.
        assertEquals(steady, steady.adding(sample(2.0, azimuth = 40.0, altitude = 10.0)))
        // An earlier one means the clock restarted: start over.
        val restarted = steady.adding(sample(1.0))
        assertFalse(restarted.isSteady)
        assertEquals(0.0, restarted.progress)
        assertEquals(1, restarted.sampleCount)
    }

    @Test
    fun testProgressFollowsTheTimeTheCameraHasBeenStill() {
        var detector = feed(SteadinessDetector(), 0.0, 1.5)
        assertEquals(1.0, detector.progress)
        // The camera is moved a degree up and held there.
        detector = feed(detector, 1.625, 2.125, altitude = 31.0)
        assertFalse(detector.isSteady)
        assertEquals(0.5 / 1.5, detector.progress, 1e-12)
        detector = feed(detector, 2.25, 3.125, altitude = 31.0)
        assertTrue(detector.isSteady)
    }

    @Test
    fun testTheWindowKeepsOnlyRecentSamples() {
        var detector = SteadinessDetector()
        for (index in 0 until 1000) detector = detector.adding(sample(index / 64.0))
        assertTrue(detector.isSteady)
        // 1.5 s at 64 Hz is 96 intervals: 97 samples, the oldest exactly at the start of the window.
        assertEquals(97, detector.sampleCount)
    }

    @Test
    fun testInvalidConfigurationFallsBackToTheDefaults() {
        val defaults = SteadinessDetector()
        assertEquals(1.5, defaults.windowSeconds)
        assertEquals(0.35, defaults.toleranceDegrees)
        assertEquals(0.5, defaults.maximumGapSeconds)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(defaults, SteadinessDetector(windowSeconds = bad, toleranceDegrees = bad, maximumGapSeconds = bad))
        }
        val custom = SteadinessDetector(windowSeconds = 2.0, toleranceDegrees = 0.1, maximumGapSeconds = 0.25)
        assertEquals(2.0, custom.windowSeconds)
        assertEquals(0.1, custom.toleranceDegrees)
        assertEquals(0.25, custom.maximumGapSeconds)
    }

    // MARK: Samples

    @Test
    fun testInvalidSamplesAreRejected() {
        val direction = Vector3(0.0, 1.0, 0.0)
        assertNull(SteadinessSample.create(Double.NaN, direction))
        assertNull(SteadinessSample.create(Double.POSITIVE_INFINITY, direction))
        assertNull(SteadinessSample.create(1.0, Vector3.zero))
        assertNull(SteadinessSample.create(1.0, Vector3(Double.NaN, 0.0, 1.0)))
        assertNull(SteadinessSample.create(1.0, Vector3(0.0, Double.POSITIVE_INFINITY, 1.0)))
        assertNull(SteadinessSample.create(1.0, Vector3(1e200, 1e200, 1e200)))
        assertNull(SteadinessSample.create(Double.NaN, HorizontalCoordinate(10.0, 20.0)))
        assertNull(SteadinessSample.create(1.0, HorizontalCoordinate(Double.NaN, 20.0)))
        // Any length is accepted and normalised.
        val sample = assertNotNull(SteadinessSample.create(1.0, Vector3(0.0, 0.0, 5.0)))
        assertEquals(1.0, sample.timestamp)
        Support.assertVector(sample.direction, Vector3(0.0, 0.0, 1.0), accuracy = 1e-15)
    }

    @Test
    fun testSampleFromAnAttitudeLooksAlongTheMountsCameraAxis() {
        val boresight = HorizontalCoordinate(123.0, 35.0)
        for (preset in MountProfile.presets) {
            val attitude = Support.attitude(
                mount = preset, frame = Support.cameraFrame(boresight, 0.0), timestamp = 42.5,
            )
            val sample = assertNotNull(SteadinessSample.create(attitude, preset), preset.id)
            assertEquals(42.5, sample.timestamp)
            Support.assertVector(sample.direction, boresight.unitVector, accuracy = 1e-12, message = preset.id)
        }
        // The same attitude seen through a mount whose camera axis is another one looks elsewhere (here 90° away).
        val attitude = Support.attitude(mount = mount, frame = Support.cameraFrame(boresight, 0.0))
        val other = assertNotNull(SteadinessSample.create(attitude, MountProfile.flatOnTopTopEdgeForward))
        assertEquals(90.0, other.direction.angle(boresight.unitVector), 1e-9)
    }

    // MARK: Point separation

    @Test
    fun testMinimumPointSeparation() {
        assertEquals(20.0, AlignmentHygiene.minimumPointSeparationDegrees)
    }

    @Test
    fun testNoExistingPointsMeansNoWarning() {
        val candidate = HorizontalCoordinate(100.0, 40.0)
        assertNull(AlignmentHygiene.bestSeparation(candidate, emptyList()))
        assertNull(AlignmentHygiene.separationWarning(candidate, emptyList()))
    }

    @Test
    fun testSeparationAcrossTheAzimuthSeam() {
        // 2° of azimuth apart, one on each side of north: not 358°.
        val existing = listOf(point(359.0, 30.0))
        val expected = haversine(359.0, 30.0, 1.0, 30.0)
        assertEquals(1.7320288, expected, 1e-6)
        val best = assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(1.0, 30.0), existing))
        assertEquals(expected, best.degrees, 1e-9)
        assertEquals(expected, assertNotNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(1.0, 30.0), existing)), 1e-9)
        // A candidate whose azimuth is given beyond 360° is the same place.
        assertEquals(expected, assertNotNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(361.0, 30.0), existing)), 1e-9)
        // 20° of azimuth at 30° altitude is only 17.3° of sky.
        val wide = AlignmentHygiene.bestSeparation(HorizontalCoordinate(10.0, 30.0), listOf(point(350.0, 30.0)))
        assertEquals(haversine(350.0, 30.0, 10.0, 30.0), assertNotNull(wide).degrees, 1e-9)
        assertEquals(17.2983302, wide.degrees, 1e-6)
        // Opposite sides of the sky are 180° apart.
        val opposite = AlignmentHygiene.bestSeparation(HorizontalCoordinate(180.0, 0.0), listOf(point(0.0, 0.0)))
        assertEquals(180.0, assertNotNull(opposite).degrees, 1e-9)
        assertNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(180.0, 0.0), listOf(point(0.0, 0.0))))
    }

    @Test
    fun testSeparationNearTheZenith() {
        // Azimuths 180° apart at 89° altitude are 2° apart over the zenith.
        val north = listOf(point(0.0, 89.0))
        assertEquals(2.0, assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(180.0, 89.0), north)).degrees, 1e-9)
        val east = listOf(point(90.0, 89.0))
        assertEquals(2.0, assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(270.0, 89.0), east)).degrees, 1e-9)
        // At the zenith itself the azimuth means nothing: 1° from a star at 89° altitude whatever its azimuth.
        for (azimuth in listOf(0.0, 123.0, 359.9)) {
            val best = AlignmentHygiene.bestSeparation(HorizontalCoordinate(0.0, 90.0), listOf(point(azimuth, 89.0)))
            assertEquals(1.0, assertNotNull(best).degrees, 1e-9)
        }
        // Over the zenith from altitude 80° to altitude 80° on the far side: 20°.
        val over = AlignmentHygiene.bestSeparation(HorizontalCoordinate(180.0, 80.0), listOf(point(0.0, 80.0)))
        assertEquals(20.0, assertNotNull(over).degrees, 1e-9)
    }

    @Test
    fun testWarningOnlyBelowTheMinimum() {
        val existing = listOf(point(100.0, 40.0, name = "Vega"))
        // 8° above it.
        val close = HorizontalCoordinate(100.0, 48.0)
        assertEquals(8.0, assertNotNull(AlignmentHygiene.separationWarning(close, existing)), 1e-9)
        // 19.9° and 20.1° along the horizon.
        val below = listOf(point(0.0, 0.0))
        assertEquals(19.9, assertNotNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(19.9, 0.0), below)), 1e-9)
        assertNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(20.1, 0.0), below))
        // Far enough: no warning, but the nearest point and its distance are still known.
        val far = HorizontalCoordinate(160.0, 40.0)
        assertNull(AlignmentHygiene.separationWarning(far, existing))
        val best = assertNotNull(AlignmentHygiene.bestSeparation(far, existing))
        assertEquals(haversine(100.0, 40.0, 160.0, 40.0), best.degrees, 1e-9)
        assertEquals(45.0420242, best.degrees, 1e-6)
        assertEquals(AlignmentPointSeparation(best.degrees, 0, "Vega"), best)
    }

    @Test
    fun testBestSeparationPicksTheNearestPointAndItsName() {
        val existing = listOf(
            point(40.0, 50.0, name = "Vega", index = 0),
            point(200.0, 40.0, name = "Arcturus", index = 1),
            point(300.0, 20.0, index = 2),
        )
        val nearArcturus = assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(190.0, 45.0), existing))
        assertEquals(1, nearArcturus.pointIndex)
        assertEquals("Arcturus", nearArcturus.pointName)
        assertEquals(haversine(190.0, 45.0, 200.0, 40.0), nearArcturus.degrees, 1e-9)
        assertEquals(8.8979375, nearArcturus.degrees, 1e-6)
        // A point without a name is reported without one.
        val nearUnnamed = assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(305.0, 22.0), existing))
        assertEquals(2, nearUnnamed.pointIndex)
        assertNull(nearUnnamed.pointName)
        assertEquals(5.0778285, nearUnnamed.degrees, 1e-6)
        // The warning reports the smallest separation, however far the others are.
        assertEquals(nearUnnamed.degrees, assertNotNull(AlignmentHygiene.separationWarning(HorizontalCoordinate(305.0, 22.0), existing)))
        // Two points at the same place: the earlier one is reported.
        val twins = listOf(point(10.0, 30.0, name = "First"), point(10.0, 30.0, name = "Second", index = 1))
        assertEquals(0, assertNotNull(AlignmentHygiene.bestSeparation(HorizontalCoordinate(12.0, 30.0), twins)).pointIndex)
    }

    // MARK: Staleness

    @Test
    fun testStaleThresholds() {
        assertEquals(10_800.0, AlignmentHygiene.compassStaleAfterSeconds)
        assertEquals(5_400.0, AlignmentHygiene.gyroLockStaleAfterSeconds)
        assertEquals(10_800.0, AlignmentHygiene.staleAfterSeconds(gyroLock = false))
        assertEquals(5_400.0, AlignmentHygiene.staleAfterSeconds(gyroLock = true))
        fun stale(afterSeconds: Double, gyroLock: Boolean): Boolean =
            AlignmentHygiene.isStale(alignedAt, alignedAt.addingTimeInterval(afterSeconds), gyroLock)
        // Compass: 3 hours.
        assertFalse(stale(0.0, gyroLock = false))
        assertFalse(stale(10_799.0, gyroLock = false))
        assertTrue(stale(10_800.0, gyroLock = false))
        assertTrue(stale(86_400.0, gyroLock = false))
        // Gyro lock: 90 minutes.
        assertFalse(stale(5_399.0, gyroLock = true))
        assertTrue(stale(5_400.0, gyroLock = true))
        assertTrue(stale(10_799.0, gyroLock = true))
        // 90 minutes old is fine for the compass.
        assertFalse(stale(5_400.0, gyroLock = false))
        assertFalse(stale(7_200.0, gyroLock = false))
    }

    @Test
    fun testAnAlignmentFromTheFutureIsNotStale() {
        // The clock was set back after aligning.
        assertFalse(AlignmentHygiene.isStale(alignedAt, alignedAt.addingTimeInterval(-3_600.0), gyroLock = true))
        assertFalse(AlignmentHygiene.isStale(alignedAt, alignedAt.addingTimeInterval(-86_400.0), gyroLock = false))
    }
}

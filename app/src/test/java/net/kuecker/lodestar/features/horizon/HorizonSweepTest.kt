package net.kuecker.lodestar.features.horizon

import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Matrix3
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonRecorder
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.model.HeadingSource
import net.kuecker.lodestar.services.CompassQuality
import net.kuecker.lodestar.services.MotionSample
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HorizonSweepTest {
    /** A sample of a level, upright phone whose back camera points at [azimuth] / [altitude] (true north frame). */
    private fun sample(azimuth: Double, altitude: Double, timestamp: Double): MotionSample {
        val forward = HorizontalCoordinate(azimuth, altitude).unitVector
        val up = (Vector3(0.0, 0.0, 1.0) - forward * forward.z).normalized
        val back = -forward
        val right = up.cross(back)
        fun northWestUp(v: Vector3) = Vector3(v.y, -v.x, v.z)
        val attitude = assertNotNull(
            DeviceAttitude.create(Matrix3(northWestUp(right), northWestUp(up), northWestUp(back)), 0.0, timestamp),
        )
        return MotionSample(
            attitude = attitude, rawAttitude = attitude, gravity = Vector3(0.0, 0.0, -1.0), angularSpeed = 0.0,
            magneticFieldMicrotesla = null, compassQuality = CompassQuality.HIGH, timestamp = timestamp,
        )
    }

    private fun skylineAt(azimuth: Double) = 8.0 + 5.0 * sin(Math.toRadians(2 * azimuth))

    /** Turns the phone once round at [degreesPerSecond] with the camera on [skylineAt], 60 samples a second. */
    private fun sweepRound(sweep: HorizonSweep, degreesPerSecond: Double, quality: Double?, start: Double = 0.0): Double {
        var time = start
        val step = 1.0 / 60
        var azimuth = 0.0
        while (azimuth < 360.0) {
            sweep.feed(sample(azimuth, skylineAt(azimuth), time), 0.0, quality, isHeadingMissing = false, hasInterference = false)
            sweep.publishIfDue((time * 1e9).toLong())
            time += step
            azimuth += degreesPerSecond * step
        }
        return time
    }

    @Test
    fun aSlowTurnRecordsTheWholeSkylineWhereTheCameraSawIt() {
        val sweep = HorizonSweep(null)
        sweepRound(sweep, degreesPerSecond = 20.0, quality = 1.0)
        val recording = sweep.finish(HorizonRecorder.UnrecordedFill.KEEP_BASE)
        assertTrue(recording.isComplete)
        assertEquals(HorizonHint.NONE, sweep.state.hint)
        for (azimuth in 0 until 360 step 7) {
            assertEquals(skylineAt(azimuth.toDouble()), recording.profile.altitude(azimuth.toDouble()), 0.3, "at $azimuth°")
        }
        sweep.publishIfDue(Long.MAX_VALUE)
        assertTrue(sweep.state.coverage > 0.99)
        assertTrue(sweep.state.isComplete)
    }

    @Test
    fun aFastTurnIsReportedAsTooFast() {
        val sweep = HorizonSweep(null)
        sweepRound(sweep, degreesPerSecond = 120.0, quality = 1.0)
        assertEquals(HorizonHint.SLOW_DOWN, sweep.state.hint)
    }

    @Test
    fun aCompassThatCannotBeTrustedRecordsNothingAndSaysWhy() {
        val sweep = HorizonSweep(null)
        sweepRound(sweep, degreesPerSecond = 20.0, quality = 0.0)
        assertEquals(0, sweep.state.recordedBins)
        assertEquals(HorizonHint.CALIBRATE_COMPASS, sweep.state.hint)
        // Even then the screen knows where the camera points.
        assertNotNull(sweep.state.azimuth)
        assertNotNull(sweep.state.altitude)
    }

    @Test
    fun aSecondPassOverAStretchReplacesTheFirst() {
        val sweep = HorizonSweep(null)
        val end = sweepRound(sweep, degreesPerSecond = 30.0, quality = 1.0)
        // Five seconds later the camera sweeps 0°…90° again, now half a degree lower than before.
        var time = end + 5
        var azimuth = 0.0
        while (azimuth < 90.0) {
            sweep.feed(sample(azimuth, skylineAt(azimuth) - 0.5, time), 0.0, 1.0, isHeadingMissing = false, hasInterference = false)
            time += 1.0 / 60
            azimuth += 0.5
        }
        val profile = sweep.finish(HorizonRecorder.UnrecordedFill.KEEP_BASE).profile
        assertEquals(skylineAt(45.0) - 0.5, profile.altitude(45.0), 0.3)
    }

    @Test
    fun theStoredSkylineStaysWhereNothingNewWasRecorded() {
        val stored = HorizonProfile.constant(6.0)
        val sweep = HorizonSweep(stored)
        var time = 0.0
        var azimuth = 100.0
        while (azimuth < 200.0) {
            sweep.feed(sample(azimuth, 11.0, time), 0.0, 1.0, isHeadingMissing = false, hasInterference = false)
            time += 1.0 / 60
            azimuth += 0.4
        }
        val recording = sweep.finish(HorizonRecorder.UnrecordedFill.KEEP_BASE)
        assertTrue(!recording.isComplete)
        assertEquals(6.0, recording.profile.altitude(300.0), 0.05)
        assertEquals(11.0, recording.profile.altitude(150.0), 0.3)
        // The screen draws the stored skyline where nothing is recorded yet.
        sweep.publishIfDue(0L)
        assertEquals(6.0, sweep.state.profile.altitude(300.0), 0.05)
    }

    @Test
    fun startingOverForgetsTheSweep() {
        val sweep = HorizonSweep(null)
        sweepRound(sweep, degreesPerSecond = 20.0, quality = 1.0)
        assertTrue(sweep.hasRecording)
        sweep.restart()
        assertTrue(!sweep.hasRecording)
        assertEquals(0, sweep.state.recordedBins)
    }

    @Test
    fun theSameSampleIsOnlyAddedOnce() {
        val sweep = HorizonSweep(null)
        val sample = sample(10.0, 5.0, 3.0)
        repeat(5) { sweep.feed(sample, 0.0, 1.0, isHeadingMissing = false, hasInterference = false) }
        sweep.publishIfDue(0L)
        assertEquals(1, sweep.state.recordedBins)
    }

    @Test
    fun qualityFollowsTheCompassOrTheGyroHeading() {
        assertEquals(0.0, HorizonSweep.quality(HeadingSource.COMPASS, true, CompassQuality.UNCALIBRATED))
        assertEquals(0.2, HorizonSweep.quality(HeadingSource.COMPASS, true, CompassQuality.LOW))
        assertEquals(0.6, HorizonSweep.quality(HeadingSource.COMPASS, true, CompassQuality.MEDIUM))
        assertEquals(1.0, HorizonSweep.quality(HeadingSource.COMPASS, true, CompassQuality.HIGH))
        assertNull(HorizonSweep.quality(HeadingSource.COMPASS, true, CompassQuality.UNAVAILABLE))
        // Magnetic interference cannot move the gyro heading; without a heading it means nothing.
        assertEquals(1.0, HorizonSweep.quality(HeadingSource.GYRO_LOCK, true, CompassQuality.UNAVAILABLE))
        assertEquals(0.0, HorizonSweep.quality(HeadingSource.GYRO_LOCK, false, CompassQuality.UNAVAILABLE))
    }

    @Test
    fun theHintFollowsTheMostUsefulAdvice() {
        val rejection = HorizonRecorder.Rejection.entries.associateBy { it.name }
        fun hint(recent: HorizonRecorder.Rejection?, rate: Double? = null, missing: Boolean = false, noise: Boolean = false) =
            HorizonSweep.hint(recent, rate, missing, noise)
        assertEquals(HorizonHint.NONE, hint(null, 10.0))
        assertEquals(HorizonHint.SLOW_DOWN, hint(null, 50.0))
        assertEquals(HorizonHint.SLOW_DOWN, hint(rejection.getValue("TOO_FAST")))
        assertEquals(HorizonHint.CALIBRATE_COMPASS, hint(rejection.getValue("LOW_QUALITY")))
        assertEquals(HorizonHint.SET_HEADING, hint(rejection.getValue("LOW_QUALITY"), missing = true))
        assertEquals(HorizonHint.POINT_AT_SKYLINE, hint(rejection.getValue("OUT_OF_RANGE")))
        assertEquals(HorizonHint.INTERFERENCE, hint(null, 10.0, noise = true))
        assertEquals(HorizonHint.CALIBRATE_COMPASS, hint(rejection.getValue("LOW_QUALITY"), noise = true))
    }

    @Test
    fun pointingAboveTheSkylineIsOutOfRange() {
        val sweep = HorizonSweep(null)
        var time = 0.0
        repeat(30) {
            sweep.feed(sample(it * 3.0, 75.0, time), 0.0, 1.0, isHeadingMissing = false, hasInterference = false)
            time += 1.0 / 60
        }
        sweep.publishIfDue(0L)
        assertEquals(HorizonHint.POINT_AT_SKYLINE, sweep.state.hint)
        assertEquals(0, sweep.state.recordedBins)
        assertTrue(abs(assertNotNull(sweep.state.altitude) - 75.0) < 0.01)
    }
}

package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.pointing.SteadinessSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class AimHoldStillTest {
    /** A boresight sample stamped `time` seconds on the sensor clock. */
    private fun sample(time: Double, altitude: Double = 30.0): SteadinessSample =
        assertNotNull(SteadinessSample.create(time, HorizontalCoordinate(0.0, altitude)))

    private fun waitingProgress(status: AimHoldStillStatus): Double = assertIs<AimHoldStillStatus.Waiting>(status).progress

    @Test
    fun syncsOnceTheCameraHasBeenStillForTheWholeWindow() {
        val wait = AimHoldStill(startedAt = 0.0)
        // 1/8 s steps are exact in binary; the window is 1.5 s.
        for (step in 0..11) {
            val time = step / 8.0
            val status = wait.update(time, sample(time))
            assertEquals(time / 1.5, waitingProgress(status), 1e-12)
        }
        assertEquals(AimHoldStillStatus.Steady, wait.update(1.5, sample(1.5)))
    }

    @Test
    fun aShakeRestartsTheWindow() {
        val wait = AimHoldStill(startedAt = 0.0)
        for (step in 0..8) wait.update(step / 8.0, sample(step / 8.0))
        // A degree up at 1.125 s: the bar empties and the window starts again from there.
        assertEquals(0.0, waitingProgress(wait.update(1.125, sample(1.125, altitude = 31.0))), 1e-12)
        assertEquals(0.5 / 1.5, waitingProgress(wait.update(1.625, sample(1.625, altitude = 31.0))), 1e-12)
        // Still steady 1.5 s after the move, at 2.625 s.
        var status: AimHoldStillStatus = AimHoldStillStatus.Waiting(0.0)
        var step = 14
        while (step <= 21) {
            status = wait.update(step / 8.0, sample(step / 8.0, altitude = 31.0))
            step += 1
        }
        assertEquals(AimHoldStillStatus.Steady, status)
    }

    @Test
    fun givesUpAfterSixSecondsOfMotion() {
        val wait = AimHoldStill(startedAt = 100.0)
        var step = 0
        // Shaking all the time: 0.5° up and down between samples.
        while (step < 47) {
            val time = step / 8.0
            val status = wait.update(100.0 + time, sample(time, altitude = if (step % 2 == 0) 30.0 else 30.5))
            assertEquals(0.0, waitingProgress(status), 1e-12)
            step += 1
        }
        assertEquals(AimHoldStillStatus.TimedOut, wait.update(106.0, sample(6.0, altitude = 30.5)))
    }

    @Test
    fun sensorsThatDeliverNothingTimeOutToo() {
        val wait = AimHoldStill(startedAt = 5.0)
        assertEquals(AimHoldStillStatus.Waiting(0.0), wait.update(5.0, null))
        assertEquals(AimHoldStillStatus.Waiting(0.0), wait.update(10.9, null))
        assertEquals(AimHoldStillStatus.TimedOut, wait.update(11.0, null))
    }

    @Test
    fun aSteadyWindowBeatsATimeoutAtTheSameMoment() {
        val wait = AimHoldStill(startedAt = 0.0, timeoutSeconds = 1.5)
        for (step in 0..11) wait.update(step / 8.0, sample(step / 8.0))
        assertEquals(AimHoldStillStatus.Steady, wait.update(1.5, sample(1.5)))
    }

    @Test
    fun theSensorClockNeedNotMatchTheCallersClock() {
        // Sensor timestamps are seconds since boot, the caller's clock starts anywhere.
        val wait = AimHoldStill(startedAt = 0.0)
        var status: AimHoldStillStatus = AimHoldStillStatus.Waiting(0.0)
        for (step in 0..12) status = wait.update(step / 8.0, sample(86_400.0 + step / 8.0))
        assertEquals(AimHoldStillStatus.Steady, status)
    }

    @Test
    fun theSameSampleSeenTwiceCountsOnce() {
        val wait = AimHoldStill(startedAt = 0.0)
        for (step in 0..11) {
            val time = step / 8.0
            wait.update(time, sample(time))
            // The next look finds the sensor's latest sample unchanged.
            assertEquals(time / 1.5, waitingProgress(wait.update(time + 0.01, sample(time))), 1e-12)
        }
        assertEquals(AimHoldStillStatus.Steady, wait.update(1.5, sample(1.5)))
    }

    @Test
    fun theNoticeTextIsTheAgreedOne() {
        assertEquals("The phone is still moving. Steady the tripod and try again.", AimHoldStill.TIMEOUT_NOTICE)
        assertEquals(6.0, AimHoldStill.TIMEOUT_SECONDS)
    }
}

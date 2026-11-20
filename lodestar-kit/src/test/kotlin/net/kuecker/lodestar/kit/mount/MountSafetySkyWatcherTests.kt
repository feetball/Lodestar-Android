package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.deneb
import net.kuecker.lodestar.kit.mount.SkyWatcherTestSupport.vega
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stop logic of the Sky-Watcher driver: a stop that fails on one axis, a disconnect with a stop still pending, and declaring the
 * home position when an axis does not answer. The injected faults replace a reply, not the command: the simulated controller has
 * carried the command out by then, so what the tests look at is what the driver sent (`commandLog`) and what it believes afterwards.
 */
class MountSafetySkyWatcherTests {
    private val altAz = SkyWatcherMountMode.ALT_AZIMUTH
    private val equatorial = SkyWatcherMountMode.EQUATORIAL

    // MARK: Stopping both axes

    /** Old code: the stop of axis 1 throws and the loop ends, so the log has "K1" and never "K2". */
    @Test
    fun testAStopThatFailsOnAxis1StillReachesAxis2() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz)
        rig.controller.connect()
        rig.simulator.inject("K1", MountSimulatedFault.Replace("!2\r"))
        rig.simulator.clearLog()
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.controller.abort() })
        val log = rig.simulator.commandLog.toList()
        val first = log.indexOf("K1")
        val second = log.indexOf("K2")
        assertTrue(first >= 0, "$log")
        assertTrue(second >= 0, "$log")
        assertTrue(first < second, "$log")
    }

    /** When both axes fail, the error of the first one is thrown. */
    @Test
    fun testWhenBothStopsFailTheFirstErrorIsThrown() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz)
        rig.controller.connect()
        rig.simulator.inject("K1", MountSimulatedFault.Replace("!2\r"))
        rig.simulator.inject("K2", MountSimulatedFault.Replace("!5\r"))
        rig.simulator.clearLog()
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.driver.abort() })
        val log = rig.simulator.commandLog.toList()
        assertTrue("K1" in log && "K2" in log, "$log")
    }

    /** A stop of axis 1 that gets no answer is tried the usual number of times, and axis 2 is stopped afterwards all the same. */
    @Test
    fun testAStopOfAxis1ThatTimesOutStillReachesAxis2() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz)
        rig.controller.connect()
        rig.simulator.inject("K1", MountSimulatedFault.Silence, times = 3)
        rig.simulator.clearLog()
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { rig.driver.abort() })
        val log = rig.simulator.commandLog.toList()
        assertEquals(3, log.count { it == "K1" }, "$log")
        assertEquals(1, log.count { it == "K2" }, "$log")
    }

    // MARK: Disconnecting with a stop pending

    /**
     * Sidereal tracking runs into the counterweight limit: tracking is switched off and the stop is left to the next poll. A
     * disconnect before that poll must send it. Old code: `motion` is STOPPED by then, so nothing is sent and axis 1 turns on at
     * the sidereal rate with nobody to watch the limit.
     */
    @Test
    fun testDisconnectStopsAnAxisWhoseStopWasStillPending() {
        val rig = SkyWatcherTestSupport.rig(mode = equatorial)
        rig.controller.connect()
        rig.controller.setTracking(true)
        assertTrue(rig.simulator.isMoving)
        // Sidereal tracking carries axis 1 past 95 degrees (90 plus the overlap of 5 and a quarter of a degree) in about 6.5 hours.
        rig.clock.advance(25_000.0)
        rig.driver.readPosition()
        assertEquals(MountError.OutsideMountLimits, rig.driver.trackingStopReason)
        assertTrue(rig.simulator.isMoving, "the stop waits for the next poll")
        rig.controller.disconnect()
        rig.clock.advance(2.0)
        assertFalse(rig.simulator.isMoving)
    }

    /**
     * The other way to a pending stop: the stop that ends a nudge does not get through (the driver keeps it for the next poll).
     * Old code sends nothing at the disconnect.
     */
    @Test
    fun testDisconnectSendsTheStopOfANudgeThatDidNotGetThrough() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz, settings = MountSettings(maximumNudgeSeconds = 30.0))
        rig.controller.connect()
        // Due east and 40 degrees up: well away from every limit and from the pole.
        rig.simulator.placeAxes(axis1Degrees = 90.0, axis2Degrees = 40.0)
        rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.FIND, 30.0)
        assertTrue(rig.simulator.isMoving)
        rig.simulator.inject("K1", MountSimulatedFault.Silence, times = 3)
        // The controller repeats the stop; the driver has handed the unfinished stop to the next poll by then, so this returns.
        rig.controller.stopNudge()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        rig.simulator.clearLog()
        rig.controller.disconnect()
        val log = rig.simulator.commandLog.toList()
        assertTrue("K1" in log && "K2" in log, "$log")
    }

    // MARK: Declaring the home position

    /** Old code: `E` reaches axis 1 (its zero moves) and axis 2 fails, so the alignment and the tracking are still in place. */
    @Test
    fun testDeclaringHomeForgetsTheAlignmentAndTheTrackingEvenWhenAnAxisFails() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz, errors = SkyWatcherPointingErrors(axis1Index = -40.0, axis2Index = -10.0))
        rig.simulator.placeAxes(axis1Degrees = 40.0, axis2Degrees = 10.0)
        rig.controller.connect()
        rig.syncOn(vega)
        assertEquals(1, rig.driver.alignmentPoints.size)
        rig.controller.setTracking(true)
        assertEquals(true, rig.driver.readTracking())
        rig.simulator.inject("E2", MountSimulatedFault.Replace("!2\r"))
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.driver.declareHomePosition() })
        // Axis 1 has its new zero, so the old model no longer fits the encoders.
        assertEquals(0.0, rig.simulator.axis1Degrees, 1e-3)
        assertEquals(0, rig.driver.alignmentPoints.size)
        assertNull(rig.driver.alignmentFit)
        assertEquals(false, rig.driver.readAligned())
        assertEquals(false, rig.driver.readTracking())
        assertEquals(MountError.NotAligned, assertFailsWith<MountError> { rig.controller.goto(deneb) })
        SkyWatcherTestSupport.poll(rig, 3)
        assertFalse(rig.simulator.isMoving)
    }

    /** Pins the other half: when the axes cannot even be stopped, no `E` is sent, no zero has moved and the alignment stays. */
    @Test
    fun testDeclaringHomeKeepsTheAlignmentWhenTheAxesCannotBeStopped() {
        val rig = SkyWatcherTestSupport.rig(mode = altAz, errors = SkyWatcherPointingErrors(axis1Index = -40.0, axis2Index = -10.0))
        rig.simulator.placeAxes(axis1Degrees = 40.0, axis2Degrees = 10.0)
        rig.controller.connect()
        rig.syncOn(vega)
        rig.simulator.inject("K1", MountSimulatedFault.Replace("!2\r"))
        rig.simulator.clearLog()
        assertEquals(MountError.Busy, assertFailsWith<MountError> { rig.driver.declareHomePosition() })
        assertEquals(1, rig.driver.alignmentPoints.size)
        val log = rig.simulator.commandLog.toList()
        assertFalse("E1" in log || "E2" in log, "$log")
    }
}

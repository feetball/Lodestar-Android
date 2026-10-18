package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountTestSupport.andromeda
import net.kuecker.lodestar.kit.mount.MountTestSupport.deneb
import net.kuecker.lodestar.kit.mount.MountTestSupport.rigNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MountControllerCommandTests {
    // MARK: Sync

    @Test
    fun testSyncMovesTheMountsIdeaOfItsPosition() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            val sent = rig.epoch.fromJ2000(deneb, rig.frame())
            val expectedOffset = MountTestSupport.parkPosition.angularSeparation(sent)
            val result = rig.controller.sync(deneb)
            assertEquals(expectedOffset, result.offsetDegrees, 0.03, name)
            assertEquals(1, rig.mount.syncsPerformed, name)
            assertEquals(0, rig.mount.slewsStarted, name)
            val tolerance = if (name == "lx200-meade-low") 0.03 else 0.004
            assertEquals(0.0, rig.mountJ2000().angularSeparation(deneb), tolerance, name)
            assertEquals(0.0, assertNotNull(rig.controller.status.position, name).angularSeparation(deneb), tolerance, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
        }
    }

    @Test
    fun testSyncIsRefusedWhileMoving() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            assertFailsWith<MountError.SlewInProgress>(name) { rig.controller.sync(deneb) }
            rig.controller.abort()
            rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.FIND, 5.0)
            assertFailsWith<MountError.Busy>(name) { rig.controller.sync(deneb) }
            assertEquals(0, rig.mount.syncsPerformed, name)
        }
    }

    @Test
    fun testSyncOffsetLimitCatchesTheWrongStar() {
        val rig = MountTestSupport.rig("lx200-onstep", MountSettings(maximumSyncOffsetDegrees = 5.0))
        rig.controller.connect()
        val error = assertFailsWith<MountError.SyncOffsetTooLarge> { rig.controller.sync(andromeda) }
        assertEquals(5.0, error.limitDegrees, 0.0)
        assertTrue(error.offsetDegrees > 20.0)
        assertTrue(error.userMessage.contains("Check that you centred the right star"))
        assertEquals(0, rig.mount.syncsPerformed)
        // A near star is fine.
        val near = EquatorialCoordinate(MountTestSupport.parkPosition.rightAscension, MountTestSupport.parkPosition.declination + 1.0)
        val neighbour = rig.epoch.toJ2000(near, rig.frame())
        assertEquals(1.0, rig.controller.sync(neighbour).offsetDegrees, 0.1)
        assertEquals(1, rig.mount.syncsPerformed)
    }

    @Test
    fun testSyncNeedsTheCapability() {
        val clock = ManualMountClock(MountTestSupport.start)
        val mount = MountSimulatorModel(clock, MountTestSupport.parkPosition)
        val alpaca = AlpacaSimulator(clock, mount, canSync = false)
        val controller = MountController(AlpacaDriver(alpaca), MountTestSupport.boulder, MountSettings.standard, clock)
        assertFalse(controller.connect().canSync)
        assertEquals(MountError.NotSupported(MountFeature.SYNC), assertFailsWith<MountError.NotSupported> { controller.sync(deneb) })
        assertEquals(0, mount.syncsPerformed)

        val oldClock = ManualMountClock(MountTestSupport.start)
        val oldMount = MountSimulatorModel(oldClock, MountTestSupport.parkPosition)
        val oldHandControl = NexStarSimulator(oldClock, oldMount, NexStarFlavor.CELESTRON, 4, 3, 0)
        val oldController = MountController(NexStarDriver(oldHandControl, oldClock), MountTestSupport.boulder, MountSettings.standard, oldClock)
        val capabilities = oldController.connect()
        assertFalse(capabilities.canSync)
        assertFailsWith<MountError.NotSupported> { oldController.sync(deneb) }
    }

    @Test
    fun testMotionStartedOnTheHandControllerBlocksGotoAndSync() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.mount.beginSlew(andromeda)
            val status = rig.controller.poll()
            assertEquals(MountPhase.IDLE, status.phase, name)
            assertEquals(true, status.isMoving, name)
            assertFailsWith<MountError.SlewInProgress>(name) { rig.controller.goto(deneb) }
            assertFailsWith<MountError.SlewInProgress>(name) { rig.controller.sync(deneb) }
            assertEquals(0, rig.mount.syncsPerformed, name)
            rig.controller.abort()
            assertEquals(false, rig.controller.status.isMoving, name)
            rig.controller.goto(deneb)
            assertEquals(MountPhase.SLEWING, rig.controller.status.phase, name)
        }
    }

    // MARK: Nudges

    @Test
    fun testNudgeMovesTheMountAndEndsAfterItsDuration() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            val before = rig.mount.position
            rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 1.0)
            assertEquals(MountPhase.NUDGING, rig.controller.status.phase, name)
            assertTrue(rig.mount.isNudging, name)
            rig.clock.advance(1.5)
            val status = rig.controller.poll()
            assertEquals(MountPhase.IDLE, status.phase, name)
            assertFalse(rig.mount.isNudging, name)
            val moved = rig.mount.position.rightAscension - before.rightAscension
            val rate = MountNudgeRate.CENTER.degreesPerSecond
            assertTrue(moved >= rate * 1.0 && moved <= rate * 2.5, "$name moved $moved at rate $rate")
            assertEquals(before.declination, rig.mount.position.declination, 1e-9, name)
        }
    }

    @Test
    fun testNudgeDirectionsMoveTheRightWay() {
        for (name in rigNames) {
            for (direction in MountNudgeDirection.entries) {
                val rig = MountTestSupport.rig(name)
                rig.controller.connect()
                val before = rig.mount.position
                rig.controller.nudge(direction, MountNudgeRate.FIND, 1.0)
                rig.clock.advance(1.0)
                rig.controller.stopNudge()
                val after = rig.mount.position
                val context = "$name $direction"
                assertEquals(MountPhase.IDLE, rig.controller.status.phase, context)
                when (direction) {
                    MountNudgeDirection.NORTH -> assertTrue(after.declination > before.declination + 0.01, context)
                    MountNudgeDirection.SOUTH -> assertTrue(after.declination < before.declination - 0.01, context)
                    MountNudgeDirection.EAST -> assertTrue(after.rightAscension > before.rightAscension + 0.01, context)
                    MountNudgeDirection.WEST -> assertTrue(after.rightAscension < before.rightAscension - 0.01, context)
                }
            }
        }
    }

    @Test
    fun testGuideRateNudgeUsesTheMountsOwnPulseOnAlpaca() {
        val rig = MountTestSupport.rig("alpaca-topocentric")
        rig.controller.connect()
        val before = rig.mount.position
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE, 2.0)
        val simulator = assertNotNull(rig.alpacaSimulator)
        assertTrue(simulator.methodLog.contains("pulseguide"))
        assertFalse(simulator.methodLog.contains("moveaxis"))
        assertEquals(MountNudgeRate.GUIDE.degreesPerSecond * 2.0, rig.mount.position.declination - before.declination, 1e-6)
        rig.clock.advance(2.5)
        assertEquals(MountPhase.IDLE, rig.controller.poll().phase)
        assertFalse(simulator.methodLog.contains("moveaxis"))
    }

    @Test
    fun testFasterNudgeOnAlpacaUsesMoveAxisAndStopsItWithRateZero() {
        val rig = MountTestSupport.rig("alpaca-topocentric")
        rig.controller.connect()
        rig.controller.nudge(MountNudgeDirection.WEST, MountNudgeRate.FIND, 1.0)
        val simulator = assertNotNull(rig.alpacaSimulator)
        assertEquals(1, simulator.methodLog.count { it == "moveaxis" })
        rig.clock.advance(1.2)
        rig.controller.poll()
        assertEquals(2, simulator.methodLog.count { it == "moveaxis" })
        assertFalse(rig.mount.isNudging)
    }

    @Test
    fun testNudgeIsValidatedAndExcludesSlews() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        assertFailsWith<MountError.InvalidInput> { rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 0.0) }
        assertFailsWith<MountError.InvalidInput> { rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, Double.NaN) }
        assertFailsWith<MountError.InvalidInput> { rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 11.0) }
        assertFalse(rig.mount.isNudging)
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 5.0)
        assertFailsWith<MountError.Busy> { rig.controller.goto(andromeda) }
        rig.controller.stopNudge()
        rig.controller.goto(andromeda)
        assertFailsWith<MountError.SlewInProgress> { rig.controller.nudge(MountNudgeDirection.NORTH) }
    }

    @Test
    fun testANewNudgeReplacesTheRunningOne() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 5.0)
        rig.controller.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 5.0)
        val log = assertNotNull(rig.streamSimulator).commandLog
        assertTrue(log.indexOf("Qn") > log.indexOf("Mn"), "$log")
        assertTrue(log.indexOf("Me") > log.indexOf("Qn"), "$log")
        val position = rig.mount.position
        rig.clock.advance(1.0)
        assertEquals(position.declination, rig.mount.position.declination, 1e-9)
        assertTrue(rig.mount.position.rightAscension > position.rightAscension)
    }

    @Test
    fun testAbortAlsoStopsANudge() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.nudge(MountNudgeDirection.SOUTH, MountNudgeRate.SLEW, 5.0)
            assertTrue(rig.mount.isNudging, name)
            rig.controller.abort()
            assertFalse(rig.mount.isNudging, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
        }
    }

    @Test
    fun testNexStarNudgePausesTrackingAndResumesIt() {
        val rig = MountTestSupport.rig("nexstar-celestron")
        rig.controller.connect()
        val simulator = assertNotNull(rig.streamSimulator)
        assertTrue(rig.mount.isTracking)
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 1.0)
        assertFalse(rig.mount.isTracking)
        rig.clock.advance(1.5)
        rig.controller.poll()
        assertTrue(rig.mount.isTracking)
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
        val log = simulator.commandLog
        assertTrue(log.indexOf("T") < log.indexOf("P"), "$log")
        assertTrue(log.lastIndexOf("T") > log.lastIndexOf("P") - 1, "$log")
    }

    // MARK: Tracking and site

    @Test
    fun testTrackingCanBeSwitched() {
        for (name in rigNames) {
            if (name == "lx200-meade-low") continue
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            assertEquals(true, rig.controller.status.isTracking, name)
            rig.controller.setTracking(false)
            assertFalse(rig.mount.isTracking, name)
            assertEquals(false, rig.controller.status.isTracking, name)
            assertEquals(false, rig.controller.poll().isTracking, name)
            rig.controller.setTracking(true)
            assertTrue(rig.mount.isTracking, name)
            assertEquals(true, rig.controller.poll().isTracking, name)
        }
    }

    @Test
    fun testMeadeMountsCannotSwitchTracking() {
        val rig = MountTestSupport.rig("lx200-meade-low")
        val capabilities = rig.controller.connect()
        assertFalse(capabilities.canSetTracking)
        assertEquals(MountError.NotSupported(MountFeature.TRACKING), assertFailsWith<MountError.NotSupported> { rig.controller.setTracking(false) })
        assertEquals(null, rig.controller.status.isTracking)
    }

    @Test
    fun testTrackingCannotBeChangedDuringASlew() {
        val rig = MountTestSupport.rig("nexstar-synscan")
        rig.controller.connect()
        rig.controller.goto(andromeda)
        assertFailsWith<MountError.SlewInProgress> { rig.controller.setTracking(false) }
        assertTrue(rig.mount.isTracking)
    }

    @Test
    fun testSiteAndTimeAreSentAsUTC() {
        val location = MountTestSupport.boulder.location
        val lx = MountTestSupport.rig("lx200-onstep")
        lx.controller.connect()
        lx.controller.setSiteAndTime(location, MountTestSupport.start)
        assertEquals(
            listOf("St+40*01", "Sg105*16", "SG+00", "SC10/18/26", "SL04:00:00"),
            (lx.streamSimulator as LX200Simulator).siteCommands,
        )
        val meade = MountTestSupport.rig("lx200-meade-low")
        meade.controller.connect()
        meade.controller.setSiteAndTime(location, MountTestSupport.start)
        assertEquals(
            listOf("St+40*01", "Sg105*16", "SG+00.0", "SC10/18/26", "SL04:00:00"),
            (meade.streamSimulator as LX200Simulator).siteCommands,
        )
        val nexstar = MountTestSupport.rig("nexstar-synscan")
        nexstar.controller.connect()
        nexstar.controller.setSiteAndTime(location, MountTestSupport.start)
        val handControl = nexstar.streamSimulator as NexStarSimulator
        assertEquals(listOf(40, 0, 54, 0, 105, 16, 12, 1), handControl.receivedLocation)
        assertEquals(listOf(4, 0, 0, 10, 18, 26, 0, 0), handControl.receivedTime)
        val alpaca = MountTestSupport.rig("alpaca-topocentric")
        alpaca.controller.connect()
        alpaca.controller.setSiteAndTime(location, MountTestSupport.start)
        assertEquals(
            listOf("SiteLatitude=40.015", "SiteLongitude=-105.27", "SiteElevation=1650.0", "UTCDate=2026-10-18T04:00:00.000Z"),
            alpaca.alpacaSimulator?.siteValues,
        )
    }

    @Test
    fun testDisconnectStopsANudgeAndClosesTheSession() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        rig.controller.nudge(MountNudgeDirection.NORTH, MountNudgeRate.FIND, 5.0)
        rig.controller.disconnect()
        assertFalse(rig.mount.isNudging)
        assertEquals(MountPhase.DISCONNECTED, rig.controller.status.phase)
        assertFalse(rig.controller.isConnected)
        assertFailsWith<MountError.NotConnected> { rig.controller.goto(andromeda) }
        // A new session works.
        rig.controller.connect()
        assertEquals(MountPhase.IDLE, rig.controller.status.phase)
    }
}

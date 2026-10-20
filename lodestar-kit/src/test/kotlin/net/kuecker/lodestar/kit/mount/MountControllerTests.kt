package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mount.MountTestSupport.altair
import net.kuecker.lodestar.kit.mount.MountTestSupport.andromeda
import net.kuecker.lodestar.kit.mount.MountTestSupport.deneb
import net.kuecker.lodestar.kit.mount.MountTestSupport.orionNebula
import net.kuecker.lodestar.kit.mount.MountTestSupport.rigNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountControllerTests {
    // MARK: Connecting

    @Test
    fun testConnectReadsCapabilitiesAndPosition() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            assertEquals(MountPhase.DISCONNECTED, rig.controller.status.phase, name)
            val capabilities = rig.controller.connect()
            assertEquals(capabilities, rig.controller.capabilities, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
            assertTrue(capabilities.canSlew && capabilities.canAbort && capabilities.canSync, name)
            val position = assertNotNull(rig.controller.status.position, name)
            assertEquals(0.0, position.angularSeparation(rig.mountJ2000()), 0.001, name)
            assertNull(rig.controller.status.target, name)
            assertNull(rig.controller.status.outcome, name)
            // Connecting twice is harmless.
            assertEquals(capabilities, rig.controller.connect(), name)
        }
    }

    @Test
    fun testCapabilitiesDescribeEachProtocol() {
        val onStep = MountTestSupport.rig("lx200-onstep").controller.connect()
        assertEquals(MountProtocol.LX200, onStep.kind)
        assertEquals("On-Step", onStep.name)
        assertEquals("4.24", onStep.firmware)
        assertTrue(onStep.canSetTracking)
        assertEquals(true, onStep.isAligned)
        val meade = MountTestSupport.rig("lx200-meade-low").controller.connect()
        assertEquals("LX200 Classic", meade.name)
        assertFalse(meade.canSetTracking)
        assertNull(meade.isAligned)
        val celestron = MountTestSupport.rig("nexstar-celestron").controller.connect()
        assertEquals(MountProtocol.NEXSTAR, celestron.kind)
        assertEquals("Celestron NexStar 6/8 SE 5.28", celestron.name)
        assertTrue(celestron.gotoNeedsAlignment)
        assertEquals(true, celestron.isAligned)
        val synScan = MountTestSupport.rig("nexstar-synscan").controller.connect()
        assertEquals("Sky-Watcher AZ GOTO 4.38.06", synScan.name)
        val alpaca = MountTestSupport.rig("alpaca-topocentric").controller.connect()
        assertEquals(MountProtocol.ALPACA, alpaca.kind)
        assertEquals("Simulated Alpaca Mount", alpaca.name)
        assertFalse(alpaca.gotoNeedsAlignment)
    }

    @Test
    fun testEpochFollowsTheProtocol() {
        val expected = mapOf(
            "lx200-onstep" to MountEpoch.MEAN_OF_DATE,
            "lx200-meade-low" to MountEpoch.MEAN_OF_DATE,
            "nexstar-celestron" to MountEpoch.MEAN_OF_DATE,
            "nexstar-synscan" to MountEpoch.J2000,
            "alpaca-topocentric" to MountEpoch.APPARENT_OF_DATE,
            "alpaca-j2000" to MountEpoch.J2000,
        )
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            assertEquals(expected[name], rig.epoch, name)
        }
    }

    @Test
    fun testOperationsNeedAConnection() {
        val rig = MountTestSupport.rig("lx200-onstep")
        assertFailsWith<MountError.NotConnected> { rig.controller.goto(andromeda) }
        assertFailsWith<MountError.NotConnected> { rig.controller.sync(andromeda) }
        assertFailsWith<MountError.NotConnected> { rig.controller.abort() }
        assertFailsWith<MountError.NotConnected> { rig.controller.nudge(MountNudgeDirection.NORTH) }
        assertFailsWith<MountError.NotConnected> { rig.controller.setTracking(true) }
        assertEquals(MountStatus.disconnected, rig.controller.poll())
    }

    // MARK: Goto

    @Test
    fun testGotoSlewsToTheTargetAndArrives() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            assertEquals(MountPhase.SLEWING, rig.controller.status.phase, name)
            assertEquals(andromeda, rig.controller.status.target, name)
            assertNull(rig.controller.status.outcome, name)
            val status = rig.controller.awaitSettled()
            assertEquals(MountPhase.IDLE, status.phase, name)
            val outcome = status.outcome
            assertTrue(outcome is MountSlewOutcome.Arrived, "$name: $outcome")
            assertEquals(1, rig.mount.slewsStarted, name)
            // The mount was told the target in its own frame and points there; the controller reports J2000.
            assertEquals(0.0, rig.mountJ2000().angularSeparation(andromeda), 0.002, name)
            assertEquals(0.0, assertNotNull(status.position, name).angularSeparation(andromeda), 0.002, name)
            assertTrue((status.separation ?: 1.0) < 0.01, name)
            assertNull(status.slewElapsedSeconds, name)
        }
    }

    @Test
    fun testGotoTakesAsLongAsTheMountNeeds() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        val before = rig.clock.now()
        rig.controller.goto(deneb)
        rig.controller.awaitSettled()
        val seconds = rig.clock.now().epochSecond - before.epochSecond
        val distance = MountTestSupport.parkPosition.angularSeparation(rig.epoch.fromJ2000(deneb, rig.frame()))
        // Rate 4 degrees per second, 0.4 s start delay, polled once a second; right ascension and declination move together.
        assertTrue(seconds >= 1, "took $seconds s for $distance degrees")
        assertTrue(seconds <= distance / 4.0 + 8.0, "took $seconds s for $distance degrees")
        assertTrue(rig.controller.status.outcome is MountSlewOutcome.Arrived)
    }

    @Test
    fun testGotoSendsTheTargetInTheMountsFrame() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            rig.controller.awaitSettled()
            val sent = rig.epoch.fromJ2000(andromeda, rig.frame())
            // Resolution of the position format: 1 s of right ascension (0.004 degrees) for OnStep, 0.1 min and 1 arcminute
            // (0.025 degrees) for the low precision Meade format; the other protocols are finer.
            val tolerance = if (name == "lx200-meade-low") 0.03 else if (name == "lx200-onstep") 0.004 else 0.001
            assertEquals(0.0, rig.mount.position.angularSeparation(sent), tolerance, name)
        }
    }

    @Test
    fun testSecondGotoWhileSlewingIsRefusedUntilAborted() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            assertFailsWith<MountError.SlewInProgress>(name) { rig.controller.goto(deneb) }
            assertEquals(1, rig.mount.slewsStarted, name)
            rig.controller.abort()
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
            rig.controller.goto(deneb)
            assertEquals(2, rig.mount.slewsStarted, name)
            rig.controller.awaitSettled()
            assertEquals(0.0, rig.mountJ2000().angularSeparation(deneb), 0.002, name)
        }
    }

    @Test
    fun testAbortStopsTheMountMidSlew() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            rig.controller.goto(andromeda)
            rig.clock.advance(2.0)
            val moving = rig.controller.poll()
            assertEquals(MountPhase.SLEWING, moving.phase, name)
            assertEquals(true, moving.isMoving, name)
            assertTrue((moving.slewElapsedSeconds ?: 0.0) >= 2.0, name)
            rig.controller.abort()
            val status = rig.controller.status
            assertEquals(MountPhase.IDLE, status.phase, name)
            assertEquals(MountSlewOutcome.Aborted, status.outcome, name)
            assertFalse(rig.mount.isSlewing, name)
            val stopped = rig.mount.position
            rig.clock.advance(5.0)
            assertEquals(stopped, rig.mount.position, name)
            // It is somewhere between start and target.
            val separation = rig.mountJ2000().angularSeparation(andromeda)
            assertTrue(separation > 1.0, "$name: $separation")
            // Polling afterwards reports the mount at rest and keeps the outcome.
            val after = rig.controller.poll()
            assertEquals(false, after.isMoving, name)
            assertEquals(MountSlewOutcome.Aborted, after.outcome, name)
        }
    }

    @Test
    fun testGotoBelowTheAltitudeLimitIsRefusedWithoutTouchingTheMount() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name)
            rig.controller.connect()
            val altitude = MountTestSupport.altitude(orionNebula, rig.clock)
            assertTrue(altitude < 0, "Orion should be down: $altitude")
            val error = assertFailsWith<MountError.BelowHorizonLimit>(name) { rig.controller.goto(orionNebula) }
            assertEquals(altitude, error.altitude, 0.5, name)
            assertEquals(0.0, error.limit, 0.0, name)
            assertTrue(error.userMessage.contains("below your altitude limit"), name)
            assertEquals(0, rig.mount.slewsStarted, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
        }
    }

    @Test
    fun testAltitudeLimitIsConfigurableAndChecksTheSlewDuration() {
        val probe = MountTestSupport.rig("lx200-onstep")
        val nowAltitude = MountTestSupport.altitude(altair, probe.clock)
        assertTrue(nowAltitude in 30.0..55.0, "Altair altitude $nowAltitude")
        val strict = MountTestSupport.rig("lx200-onstep", MountSettings(altitudeLimit = nowAltitude + 5.0))
        strict.controller.connect()
        val refusal = assertFailsWith<MountError.BelowHorizonLimit> { strict.controller.goto(altair) }
        assertEquals(nowAltitude + 5.0, refusal.limit, 1e-9)
        val relaxed = MountTestSupport.rig("lx200-onstep", MountSettings(altitudeLimit = nowAltitude - 5.0))
        relaxed.controller.connect()
        relaxed.controller.goto(altair)
        assertEquals(MountPhase.SLEWING, relaxed.controller.status.phase)

        // Altair is setting: a limit just under its altitude now is met now, but not ten minutes later.
        val later = MountTestSupport.altitude(altair, ManualMountClock(MountTestSupport.start.plusSeconds(600)))
        assertTrue(later < nowAltitude - 0.5, "Altair should sink: $nowAltitude -> $later")
        val lookahead = MountTestSupport.rig(
            "lx200-onstep", MountSettings(altitudeLimit = nowAltitude - 0.05, horizonLookaheadSeconds = 600.0),
        )
        lookahead.controller.connect()
        val error = assertFailsWith<MountError.BelowHorizonLimit> { lookahead.controller.goto(altair) }
        assertEquals(later, error.altitude, 0.05)
        val noLookahead = MountTestSupport.rig(
            "lx200-onstep", MountSettings(altitudeLimit = nowAltitude - 0.05, horizonLookaheadSeconds = 0.0),
        )
        noLookahead.controller.connect()
        noLookahead.controller.goto(altair)
        assertEquals(MountPhase.SLEWING, noLookahead.controller.status.phase)
    }

    /**
     * The J2000 place [degreesAbove] degrees straight above the Sun, as seen by the rig's observer at the clock's time (so
     * exactly [degreesAbove] degrees from the Sun).
     */
    private fun targetNearTheSunOf(rig: MountRig, degreesAbove: Double): EquatorialCoordinate {
        val frame = rig.frame()
        val sun = frame.horizontal(SolarSystemBody.SUN, refraction = false)
        return frame.j2000(HorizontalCoordinate(sun.azimuth, sun.altitude + degreesAbove), refracted = false)
    }

    @Test
    fun testGotoNearTheDaytimeSunIsRefusedWithoutTouchingTheMount() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name, begin = MountTestSupport.midday)
            rig.controller.connect()
            val sun = rig.frame().horizontal(SolarSystemBody.SUN, refraction = false)
            assertTrue(sun.altitude in 30.0..50.0, "$name: the Sun should be high: ${sun.altitude}")
            // Eight degrees above the Sun: well above the horizon, so only the Sun guard can refuse it.
            val nearby = targetNearTheSunOf(rig, 8.0)
            val refusal = assertFailsWith<MountError.NearSun>(name) { rig.controller.goto(nearby) }
            assertEquals(8.0, refusal.separationDegrees, 0.1, name)
            assertEquals(15.0, refusal.limitDegrees, 0.0, name)
            assertEquals(0, rig.mount.slewsStarted, name)
            assertEquals(MountPhase.IDLE, rig.controller.status.phase, name)
            assertNull(rig.controller.status.target, name)
        }
    }

    @Test
    fun testGotoAwayFromTheDaytimeSunIsAllowedAndTheExclusionIsConfigurable() {
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name, begin = MountTestSupport.midday)
            rig.controller.connect()
            // The opposite side of the sky, 45 degrees up.
            val frame = rig.frame()
            val sun = frame.horizontal(SolarSystemBody.SUN, refraction = false)
            val faraway = frame.j2000(HorizontalCoordinate(sun.azimuth + 180.0, 45.0), refracted = false)
            rig.controller.goto(faraway)
            assertEquals(MountPhase.SLEWING, rig.controller.status.phase, name)
            assertEquals(1, rig.mount.slewsStarted, name)
        }
        // 20 degrees from the Sun is outside the standard 15 degrees, but inside a 25 degree exclusion.
        val wide = MountTestSupport.rig("lx200-onstep", begin = MountTestSupport.midday)
        wide.controller.connect()
        val twenty = targetNearTheSunOf(wide, 20.0)
        wide.controller.goto(twenty)
        assertEquals(MountPhase.SLEWING, wide.controller.status.phase)
        val strict = MountTestSupport.rig("lx200-onstep", MountSettings(sunExclusionDegrees = 25.0), begin = MountTestSupport.midday)
        strict.controller.connect()
        val refusal = assertFailsWith<MountError.NearSun> { strict.controller.goto(twenty) }
        assertEquals(25.0, refusal.limitDegrees, 0.0)
        assertEquals(0, strict.mount.slewsStarted)
    }

    @Test
    fun testGotoNearTheSunIsAllowedOnceItHasSet() {
        // A few minutes after sunset at Boulder, the Sun is about four degrees below the horizon.
        var evening = MountTestSupport.midday
        for (step in 0 until 1_000) {
            val altitude = SkyFrame(evening, MountTestSupport.boulder).horizontal(SolarSystemBody.SUN, refraction = false).altitude
            if (altitude < -4.0) break
            evening = evening.plusSeconds(60)
        }
        for (name in rigNames) {
            val rig = MountTestSupport.rig(name, begin = evening)
            rig.controller.connect()
            val sun = rig.frame().horizontal(SolarSystemBody.SUN, refraction = false)
            assertTrue(sun.altitude in -8.0..-3.0, "$name: the Sun should have just set: ${sun.altitude}")
            // Nine degrees from the Sun and about five degrees above the horizon.
            val nearby = targetNearTheSunOf(rig, 9.0)
            rig.controller.goto(nearby)
            assertEquals(MountPhase.SLEWING, rig.controller.status.phase, name)
        }
    }

    @Test
    fun testInvalidTargetsAreRefused() {
        val rig = MountTestSupport.rig("lx200-onstep")
        rig.controller.connect()
        val broken = net.kuecker.lodestar.kit.foundation.EquatorialCoordinate(Double.NaN, 10.0)
        assertFailsWith<MountError.InvalidInput> { rig.controller.goto(broken) }
        assertFailsWith<MountError.InvalidInput> { rig.controller.sync(broken) }
        assertEquals(0, rig.mount.slewsStarted)
    }
}

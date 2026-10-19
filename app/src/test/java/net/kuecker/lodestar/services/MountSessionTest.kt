package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.mount.LX200Simulator
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSimulatedFault
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.services.MountTestSky.altair
import net.kuecker.lodestar.services.MountTestSky.andromeda
import net.kuecker.lodestar.services.MountTestSky.orionNebula
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MountSessionTest {
    private val kinds = listOf("lx200", "nexstar", "alpaca")
    private val settings = MountSettingsValues(altitudeLimit = 5.0)

    private fun session(kind: String, observer: Boolean = true): Pair<MountSession, SimulatedMountFactory> {
        val factory = SimulatedMountFactory(kind)
        return MountSession(factory, observerProvider = { if (observer) MountTestSky.boulder else null }) to factory
    }

    private fun connected(kind: String, values: MountSettingsValues = settings): Pair<MountSession, SimulatedMountFactory> {
        val (session, factory) = session(kind)
        val result = session.connect(MountTestSky.connection, values)
        assertIs<MountResult.Success>(result, "$kind: $result")
        return session to factory
    }

    /** Polls until the slew has ended; each poll moves the simulated clock on. */
    private fun settle(session: MountSession) {
        repeat(400) {
            if (session.status.phase != MountPhase.SLEWING) return
            session.poll()
        }
        throw AssertionError("the slew did not end")
    }

    @Test
    fun connectingFindsWhatTheMountCanDo() {
        for (kind in kinds) {
            val (session, _) = connected(kind)
            assertTrue(session.isConnected, kind)
            val capabilities = assertNotNull(session.capabilities, kind)
            assertTrue(capabilities.canSlew && capabilities.canAbort && capabilities.canSync, kind)
            assertEquals(MountPhase.IDLE, session.status.phase, kind)
            assertNotNull(session.status.position, kind)
        }
    }

    @Test
    fun connectingNeedsAnObservingLocation() {
        val (session, factory) = session("lx200", observer = false)
        val result = session.connect(MountTestSky.connection, settings)
        val failure = assertIs<MountResult.Failure>(result)
        assertTrue(failure.message.contains("location"), failure.message)
        assertEquals(0, factory.opened)
    }

    @Test
    fun aFailedLinkIsReportedWithAHintAndLeavesNothingOpen() {
        val (session, factory) = session("lx200")
        factory.failure = MountError.Timeout("connect")
        val failure = assertIs<MountResult.Failure>(session.connect(MountTestSky.connection, settings))
        assertTrue(failure.message.contains("didn't answer in time"), failure.message)
        assertTrue(failure.message.contains("Wi-Fi"), failure.message)
        assertFalse(session.isConnected)
        assertNull(session.capabilities)
        val http = MountTestSky.connection.copy(link = MountLinkKind.HTTP)
        factory.failure = MountError.Transport("the mount refused the connection")
        val alpaca = assertIs<MountResult.Failure>(session.connect(http, settings))
        assertTrue(alpaca.message.contains("Alpaca server"), alpaca.message)
    }

    @Test
    fun theLocationAndTimeAreSentOnlyWhenWanted() {
        val (_, wanting) = connected("lx200", settings.copy(sendsSiteAndTime = true))
        assertTrue((wanting.stream as LX200Simulator).siteCommands.isNotEmpty())
        val (_, declining) = connected("lx200", settings.copy(sendsSiteAndTime = false))
        assertTrue((declining.stream as LX200Simulator).siteCommands.isEmpty())
    }

    @Test
    fun aGotoSlewsAndArrivesOnEveryProtocol() {
        for (kind in kinds) {
            val (session, factory) = connected(kind)
            val result = session.goto(MountTestSky.target("Andromeda", andromeda))
            assertIs<MountResult.Success>(result, "$kind: $result")
            assertEquals(MountPhase.SLEWING, session.status.phase, kind)
            settle(session)
            val outcome = session.status.outcome
            assertIs<MountSlewOutcome.Arrived>(outcome, kind)
            assertEquals(1, factory.model.slewsStarted, kind)
        }
    }

    @Test
    fun aGotoBelowTheAltitudeLimitIsRefusedWithTheEnginesReason() {
        for (kind in kinds) {
            val (session, factory) = connected(kind)
            val failure = assertIs<MountResult.Failure>(session.goto(MountTestSky.target("Orion Nebula", orionNebula)), kind)
            assertIs<MountError.BelowHorizonLimit>(failure.error, kind)
            assertTrue(failure.message.contains("below your altitude limit of 5.0°"), failure.message)
            assertEquals(0, factory.model.slewsStarted, kind)
        }
    }

    @Test
    fun aNewAltitudeLimitAppliesToTheNextGotoWithoutReconnecting() {
        val (session, factory) = connected("lx200", settings.copy(altitudeLimit = 50.0))
        val target = MountTestSky.target("Altair", altair)
        assertIs<MountError.BelowHorizonLimit>(assertIs<MountResult.Failure>(session.goto(target)).error)
        session.configure(settings.copy(altitudeLimit = 5.0))
        assertIs<MountResult.Success>(session.goto(target))
        assertEquals(5.0, session.activeAltitudeLimit)
        assertEquals(1, factory.opened)
        assertEquals(1, factory.model.slewsStarted)
        // A stricter limit applies as well.
        settle(session)
        session.configure(settings.copy(altitudeLimit = 60.0))
        assertIs<MountError.BelowHorizonLimit>(assertIs<MountResult.Failure>(session.goto(target)).error)
    }

    @Test
    fun aSecondGotoWhileSlewingIsRefused() {
        val (session, _) = connected("lx200")
        assertIs<MountResult.Success>(session.goto(MountTestSky.target("Andromeda", andromeda)))
        val failure = assertIs<MountResult.Failure>(session.goto(MountTestSky.target("Altair", altair)))
        assertEquals(MountError.SlewInProgress, failure.error)
    }

    @Test
    fun stopHaltsASlew() {
        for (kind in kinds) {
            val (session, factory) = connected(kind)
            session.goto(MountTestSky.target("Andromeda", andromeda))
            session.poll()
            assertIs<MountResult.Success>(session.abort(), kind)
            assertEquals(MountPhase.IDLE, session.status.phase, kind)
            assertEquals(MountSlewOutcome.Aborted, session.status.outcome, kind)
            assertTrue(factory.model.stopsReceived >= 1, kind)
            assertFalse(factory.model.isSlewing, kind)
        }
    }

    @Test
    fun syncTellsTheMountAndReportsTheOffset() {
        for (kind in kinds) {
            val (session, factory) = connected(kind)
            val result = assertIs<MountResult.Success>(session.sync(MountTestSky.target("Andromeda", andromeda)), kind)
            assertNotNull(result.offsetDegrees, kind)
            assertTrue(result.message.orEmpty().startsWith("Synced to Andromeda"), result.message)
            assertEquals(1, factory.model.syncsPerformed, kind)
        }
    }

    @Test
    fun aSyncOffsetBeyondTheLimitIsRefused() {
        val (session, factory) = connected("lx200", settings.copy(maximumSyncOffset = 5.0))
        val failure = assertIs<MountResult.Failure>(session.sync(MountTestSky.target("Andromeda", andromeda)))
        assertIs<MountError.SyncOffsetTooLarge>(failure.error)
        assertEquals(0, factory.model.syncsPerformed)
    }

    @Test
    fun nudgesMoveTheMountAndEndWhenToldTo() {
        for (kind in kinds) {
            val (session, factory) = connected(kind)
            val before = assertNotNull(session.status.position)
            assertIs<MountResult.Success>(session.nudge(MountNudgeDirection.NORTH, MountNudgeRate.FIND), kind)
            assertEquals(MountPhase.NUDGING, session.status.phase, kind)
            factory.clock.advance(0.3)
            assertIs<MountResult.Success>(session.stopNudge(), kind)
            assertEquals(MountPhase.IDLE, session.status.phase, kind)
            session.poll()
            val after = assertNotNull(session.status.position)
            assertTrue(after.declination > before.declination, "$kind: ${before.declination} -> ${after.declination}")
        }
    }

    @Test
    fun trackingCanBeSwitchedOnTheMountsThatSupportIt() {
        val (session, _) = connected("lx200")
        assertIs<MountResult.Success>(session.setTracking(false))
        session.poll()
        assertEquals(false, session.status.isTracking)
        assertIs<MountResult.Success>(session.setTracking(true))
    }

    @Test
    fun commandsWithoutASessionFailPolitely() {
        val (session, _) = session("lx200")
        val failure = assertIs<MountResult.Failure>(session.goto(MountTestSky.target("Andromeda", andromeda)))
        assertEquals(MountError.NotConnected, failure.error)
        assertIs<MountResult.Failure>(session.abort())
        assertNull(session.poll())
    }

    @Test
    fun disconnectingStopsAMovingMountFirst() {
        val (session, factory) = connected("lx200")
        session.goto(MountTestSky.target("Andromeda", andromeda))
        session.disconnect(stopFirst = true)
        assertFalse(session.isConnected)
        assertTrue(factory.model.stopsReceived >= 1)
        assertFalse(factory.model.isSlewing)
    }

    @Test
    fun threeFailedPollsEndTheSession() {
        val (session, factory) = connected("lx200")
        factory.stream!!.inject("GR", MountSimulatedFault.Disconnect)
        repeat(4) { session.poll() }
        assertFalse(session.isConnected)
        assertEquals(MountError.ConnectionLost, session.status.lastError)
    }

    @Test
    fun cancellingTheConnectUnblocksAndReportsCancelled() {
        val opened = CountDownLatch(1)
        val blocking = MountLinkFactory { _, _, _, _, track ->
            val closed = CountDownLatch(1)
            track(Closeable { closed.countDown() })
            opened.countDown()
            // Like a socket connect: blocked until somebody closes the socket.
            closed.await()
            throw MountError.Transport("the connection was closed")
        }
        val session = MountSession(blocking, observerProvider = { MountTestSky.boulder })
        var result: MountResult? = null
        val thread = Thread { result = session.connect(MountTestSky.connection, settings) }
        thread.start()
        assertTrue(opened.await(2, TimeUnit.SECONDS))
        session.cancelConnect()
        thread.join(2_000)
        assertFalse(thread.isAlive)
        assertEquals("Cancelled.", assertIs<MountResult.Failure>(result).message)
        assertFalse(session.isConnected)
    }

    @Test
    fun theObserverIsPassedOnWhenItChanges() {
        var observer = MountTestSky.boulder
        val factory = SimulatedMountFactory("lx200")
        val session = MountSession(factory, observerProvider = { observer })
        assertIs<MountResult.Success>(session.connect(MountTestSky.connection, settings))
        val nebula = MountTestSky.target("Orion Nebula", orionNebula)
        // Below the horizon of Boulder at this time ...
        assertIs<MountError.BelowHorizonLimit>(assertIs<MountResult.Failure>(session.goto(nebula)).error)
        // ... and high in the sky of an observer in the southern hemisphere, once the poll has handed over the new site.
        observer = Observer(
            GeographicLocation(latitude = -30.0, longitude = 20.0, elevation = 100.0),
        )
        session.poll()
        assertIs<MountResult.Success>(session.goto(nebula))
    }
}

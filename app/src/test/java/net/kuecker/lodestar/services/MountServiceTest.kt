package net.kuecker.lodestar.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.kuecker.lodestar.features.mount.MountText
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSimulatedFault
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.services.MountTestSky.andromeda
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The service on its real worker thread, against the engine's simulators, with polls every few milliseconds. */
class MountServiceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = MapMountStore()

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun service(kind: String = "lx200"): Pair<MountService, SimulatedMountFactory> {
        val factory = SimulatedMountFactory(kind)
        val observer = { MountTestSky.boulder }
        val service = MountService(
            scope = scope,
            session = MountSession(factory, observerProvider = observer),
            preferences = MountPreferences(store),
            observerProvider = observer,
            demoFactory = MountDemoLinkFactory(observer),
            pollMillis = 5,
            nudgePollMillis = 5,
        )
        return service to factory
    }

    private fun <T : Any> await(what: String, timeoutMillis: Long = 5_000, block: () -> T?): T {
        val end = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < end) {
            block()?.let { return it }
            Thread.sleep(2)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun awaitState(service: MountService, state: MountConnectionState) =
        await("state $state") { service.snapshot.takeIf { it.state == state } }

    private fun MountService.connectAndWait(): MountSnapshot {
        connect(MountTestSky.connection)
        return awaitState(this, MountConnectionState.CONNECTED)
    }

    @Test
    fun connectingPublishesTheStateTheCapabilitiesAndTheMarker() {
        val (service, _) = service()
        assertEquals(MountConnectionState.DISCONNECTED, service.snapshot.state)
        service.connect(MountTestSky.connection)
        assertEquals(MountConnectionState.CONNECTING, service.snapshot.state)
        val snapshot = awaitState(service, MountConnectionState.CONNECTED)
        assertNotNull(snapshot.capabilities)
        assertEquals(MountTestSky.connection, snapshot.connection)
        assertEquals(MountPhase.IDLE, snapshot.status.phase)
        val marker = await("the marker") { service.marker }
        assertEquals(0.0, marker.position.angularSeparation(snapshot.status.position!!), 1e-9)
        // The connection is remembered, and so is the fact that one was made.
        assertEquals(MountTestSky.connection.host, service.preferences.lastConnection.host)
        assertTrue(service.preferences.hasConnectedBefore)
    }

    @Test
    fun aFailedConnectionExplainsItself() {
        val (service, factory) = service()
        factory.failure = MountError.Timeout("connect")
        service.connect(MountTestSky.connection)
        val snapshot = awaitState(service, MountConnectionState.DISCONNECTED)
        assertTrue(snapshot.connectProblem.orEmpty().contains("didn't answer in time"), snapshot.connectProblem)
        assertFalse(snapshot.lostConnection)
        assertNull(service.marker)
        // And it can be tried again.
        factory.failure = null
        service.connect(MountTestSky.connection)
        awaitState(service, MountConnectionState.CONNECTED)
    }

    @Test
    fun theSunIsRefusedForGotoAndSync() {
        val (service, factory) = service()
        service.connectAndWait()
        val sun = MountTestSky.target("Sun", andromeda).copy(isSun = true)
        val goto = runBlocking { service.goto(sun) }
        val sync = runBlocking { service.sync(sun) }
        assertEquals(MountTargeting.SUN_REFUSAL, assertIs<MountResult.Failure>(goto).message)
        assertEquals(MountTargeting.SUN_REFUSAL, assertIs<MountResult.Failure>(sync).message)
        assertEquals(0, factory.model.slewsStarted)
    }

    @Test
    fun gotoSlewsPollsAndArrives() {
        val (service, factory) = service()
        service.connectAndWait()
        val result = runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) }
        assertIs<MountResult.Success>(result)
        val snapshot = await("arrival") { service.snapshot.takeIf { it.status.outcome is MountSlewOutcome.Arrived } }
        assertEquals(MountPhase.IDLE, snapshot.status.phase)
        assertEquals(1, factory.model.slewsStarted)
        assertTrue(MountText.phaseLine(snapshot).startsWith("Arrived"))
    }

    @Test
    fun theSlewStartDistanceIsKeptForTheProgressBar() {
        val (service, _) = service()
        service.connectAndWait()
        runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) }
        val snapshot = await("a slew in progress") { service.snapshot.takeIf { it.slewStartSeparation != null } }
        assertTrue(snapshot.slewStartSeparation!! > 10.0)
        await("the end of the slew") { service.snapshot.takeIf { it.status.phase == MountPhase.IDLE && it.slewStartSeparation == null } }
    }

    @Test
    fun stopHaltsTheMountAndGoesAheadOfWhatIsQueued() {
        val (service, factory) = service()
        service.connectAndWait()
        runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) }
        service.stop()
        val snapshot = await("the stop") { service.snapshot.takeIf { it.status.outcome == MountSlewOutcome.Aborted } }
        assertEquals(MountPhase.IDLE, snapshot.status.phase)
        assertTrue(factory.model.stopsReceived >= 1)
        assertEquals("Stopped.", snapshot.notice?.text)
    }

    @Test
    fun aRefusedGotoComesBackWithTheReasonAndNoSlewStarts() {
        val (service, factory) = service()
        service.connectAndWait()
        val result = runBlocking { service.goto(MountTestSky.target("Orion Nebula", MountTestSky.orionNebula)) }
        val failure = assertIs<MountResult.Failure>(result)
        assertIs<MountError.BelowHorizonLimit>(failure.error)
        assertEquals(0, factory.model.slewsStarted)
        assertTrue(service.snapshot.notice?.isError == true)
    }

    @Test
    fun theAltitudeLimitSettingReachesTheNextGoto() {
        val (service, _) = service()
        service.connectAndWait()
        service.preferences.update { it.copy(altitudeLimit = 70.0) }
        service.settingsChanged()
        val altair = MountTestSky.target("Altair", MountTestSky.altair)
        assertIs<MountError.BelowHorizonLimit>(assertIs<MountResult.Failure>(runBlocking { service.goto(altair) }).error)
        service.preferences.update { it.copy(altitudeLimit = 5.0) }
        service.settingsChanged()
        assertIs<MountResult.Success>(runBlocking { service.goto(altair) })
    }

    @Test
    fun syncReportsHowFarOffTheMountWas() {
        val (service, factory) = service()
        service.connectAndWait()
        val result = assertIs<MountResult.Success>(runBlocking { service.sync(MountTestSky.target("Andromeda", andromeda)) })
        assertNotNull(result.offsetDegrees)
        assertEquals(1, factory.model.syncsPerformed)
    }

    @Test
    fun nudgesMoveTheMountAndEndOnRelease() {
        val (service, factory) = service()
        service.connectAndWait()
        val before = service.snapshot.status.position!!
        // Three presses in a row: the ones that have not started are replaced by the newest.
        repeat(3) { service.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER) }
        val after = await("a new position") { service.snapshot.status.position?.takeIf { it != before } }
        assertTrue(after.rightAscension != before.rightAscension)
        service.stopNudge()
        await("the nudge to end") { service.snapshot.takeIf { it.status.phase == MountPhase.IDLE } }
        assertFalse(factory.model.isNudging)
    }

    @Test
    fun commandsWithoutAConnectionFailWithoutHanging() {
        val (service, _) = service()
        val failure = assertIs<MountResult.Failure>(runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) })
        assertEquals(MountError.NotConnected, failure.error)
        service.stop()
        service.nudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE)
        service.stopNudge()
        service.disconnect()
        assertEquals(MountConnectionState.DISCONNECTED, service.snapshot.state)
    }

    @Test
    fun disconnectingStopsTheMountAndClosesTheSession() {
        val (service, factory) = service()
        service.connectAndWait()
        runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) }
        service.disconnect()
        val snapshot = awaitState(service, MountConnectionState.DISCONNECTED)
        assertFalse(snapshot.lostConnection)
        assertTrue(factory.model.stopsReceived >= 1)
        assertNull(service.marker)
        // Connecting again starts a new session.
        service.connectAndWait()
        assertEquals(2, factory.opened)
    }

    @Test
    fun aDroppedLinkIsReportedAsLost() {
        val (service, factory) = service()
        service.connectAndWait()
        factory.stream!!.inject("GR", MountSimulatedFault.Disconnect)
        val snapshot = await("the loss") { service.snapshot.takeIf { it.lostConnection } }
        assertEquals(MountConnectionState.DISCONNECTED, snapshot.state)
        assertTrue(snapshot.notice?.text.orEmpty().contains("connection to the mount was lost"))
        assertNull(service.marker)
        service.dismissProblem()
        assertFalse(service.snapshot.lostConnection)
        // Reconnecting uses the same connection.
        service.reconnect()
        awaitState(service, MountConnectionState.CONNECTED)
    }

    @Test
    fun leavingTheAppStopsTheMountAndComingBackReconnectsWithoutMovingIt() {
        val (service, factory) = service()
        service.connectAndWait()
        runBlocking { service.goto(MountTestSky.target("Andromeda", andromeda)) }
        service.setForeground(false)
        val away = awaitState(service, MountConnectionState.DISCONNECTED)
        assertFalse(away.lostConnection)
        assertTrue(factory.model.stopsReceived >= 1)
        assertEquals(1, factory.model.slewsStarted)
        service.setForeground(true)
        val back = awaitState(service, MountConnectionState.CONNECTED)
        assertEquals(MountTestSky.connection, back.connection)
        assertEquals(2, factory.opened)
        assertEquals(0, factory.model.slewsStarted, "the new session starts nothing by itself")
    }

    @Test
    fun aQuickTripToTheBackgroundAndBackStillReconnects() {
        val (service, factory) = service()
        service.connectAndWait()
        service.setForeground(false)
        service.setForeground(true)
        val back = await("the reconnection") { service.snapshot.takeIf { it.state == MountConnectionState.CONNECTED && factory.opened == 2 } }
        assertEquals(MountTestSky.connection, back.connection)
    }

    @Test
    fun theAppStayingAwayAfterADisconnectDoesNotReconnect() {
        val (service, factory) = service()
        service.connectAndWait()
        service.disconnect()
        awaitState(service, MountConnectionState.DISCONNECTED)
        service.setForeground(false)
        service.setForeground(true)
        Thread.sleep(50)
        assertEquals(MountConnectionState.DISCONNECTED, service.snapshot.state)
        assertEquals(1, factory.opened)
    }

    @Test
    fun theSimulatedMountWorksWithoutHardware() {
        val (service, _) = service()
        service.connect(MountTestSky.connection, demo = true)
        val snapshot = awaitState(service, MountConnectionState.CONNECTED)
        assertTrue(snapshot.isDemo)
        assertTrue(snapshot.capabilities!!.name.isNotEmpty())
        val marker = await("the marker") { service.marker }
        // The simulated mount waits high in the south.
        assertEquals(180.0, marker.horizontal.azimuth, 2.0)
        assertEquals(50.0, marker.horizontal.altitude, 2.0)
        service.disconnect()
        awaitState(service, MountConnectionState.DISCONNECTED)
        assertNull(store.getString("lx200.tcp.host"), "the demo does not overwrite the remembered address")
    }

    @Test
    fun notificationsAreDismissedByTheirId() {
        val (service, _) = service()
        service.connectAndWait()
        service.postNotice("Hello", isError = false)
        val notice = assertNotNull(service.snapshot.notice)
        service.dismissNotice(notice.id + 1)
        assertNotNull(service.snapshot.notice)
        service.dismissNotice(notice.id)
        assertNull(service.snapshot.notice)
    }
}

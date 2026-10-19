package net.kuecker.lodestar.services

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.features.mount.MountText
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.kit.mount.SkyWatcherPointingErrors
import net.kuecker.lodestar.services.MountTestSky.altair
import net.kuecker.lodestar.services.MountTestSky.andromeda
import net.kuecker.lodestar.services.MountTestSky.deneb
import net.kuecker.lodestar.services.MountTestSky.enif
import net.kuecker.lodestar.services.MountTestSky.mirfak
import net.kuecker.lodestar.services.MountTestSky.vega
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Sky-Watcher Wi-Fi mount through the session and the service: set-up, alignment, Go to, and the demo. */
class MountSkyWatcherTest {
    private val connection = MountProtocols.option(MountProtocol.SKYWATCHER).defaultConnection()
    private val settings = MountSettingsValues(altitudeLimit = 5.0)
    private val errors = SkyWatcherPointingErrors(axis1Index = 2.0, axis2Index = 1.1, baseTiltX = 0.5, baseTiltY = -0.4, orthogonality = 0.1, cone = 0.12)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun session(
        factory: SimulatedSkyWatcherFactory,
        observer: () -> Observer? = { MountTestSky.boulder },
    ) = MountSession(factory, observerProvider = observer)

    private fun connected(
        mode: SkyWatcherMountMode = SkyWatcherMountMode.ALT_AZIMUTH,
        mountErrors: SkyWatcherPointingErrors = errors,
        startAxes: Pair<Double, Double>? = null,
        observer: () -> Observer? = { MountTestSky.boulder },
        values: MountSettingsValues = settings,
    ): Pair<MountSession, SimulatedSkyWatcherFactory> {
        val factory = SimulatedSkyWatcherFactory(mode, mountErrors, startAxes)
        val session = session(factory, observer)
        assertIs<MountResult.Success>(session.connect(connection, values))
        return session to factory
    }

    private fun MountSession.syncOn(factory: SimulatedSkyWatcherFactory, name: String, star: EquatorialCoordinate): MountResult {
        factory.placeOn(star)
        return sync(MountTestSky.target(name, star))
    }

    private fun settle(session: MountSession) {
        repeat(600) {
            if (session.status.phase != MountPhase.SLEWING) return
            session.poll()
        }
        throw AssertionError("the slew did not end")
    }

    // Connecting and the start position

    @Test
    fun connectingReportsTheMountAndItsArrangement() {
        val (session, _) = connected()
        val capabilities = assertNotNull(session.capabilities)
        assertEquals(MountProtocol.SKYWATCHER, capabilities.kind)
        assertEquals("Sky-Watcher AZ-GTi 3.22", capabilities.name)
        assertTrue(capabilities.gotoNeedsAlignment)
        val info = assertNotNull(session.skyWatcherInfo())
        assertEquals(SkyWatcherMountMode.ALT_AZIMUTH, info.mode)
        assertFalse(info.startsAwayFromHome)
        assertFalse(info.isAligned)
        assertNull(info.pierSide)
        assertNull(info.homeErrorDegrees)
        assertNull(session.skyWatcherInfo()?.predictedErrorDegrees)
    }

    @Test
    fun theOtherMountsHaveNoSkyWatcherInfo() {
        val factory = SimulatedMountFactory("lx200")
        val session = MountSession(factory, observerProvider = { MountTestSky.boulder })
        session.connect(MountTestSky.connection, settings)
        assertNull(session.skyWatcherInfo())
    }

    @Test
    fun theArrangementChosenInTheAdvancedOptionsReachesTheDriver() {
        val factory = SimulatedSkyWatcherFactory(SkyWatcherMountMode.ALT_AZIMUTH, errors)
        val session = session(factory)
        val advanced = MountAdvanced(skyWatcherMode = SkyWatcherMountMode.EQUATORIAL, reverseAxis1 = true)
        assertIs<MountResult.Success>(session.connect(connection, settings.copy(advanced = advanced)))
        assertEquals(SkyWatcherMountMode.EQUATORIAL, session.skyWatcherInfo()?.mode)
        assertEquals(advanced, factory.openedAdvanced)
        assertNotNull(session.skyWatcherInfo()?.pierSide)
    }

    @Test
    fun aMountThatWasNotAtHomeIsNoticedAndHomeCanBeDeclared() {
        val (session, factory) = connected(startAxes = 35.0 to -12.0)
        val info = assertNotNull(session.skyWatcherInfo())
        assertTrue(info.startsAwayFromHome)
        assertEquals(35.0, info.startAxis1Degrees, 0.01)
        assertTrue(MountText.startPositionLine(info).contains("not at its home position"))
        assertIs<MountResult.Success>(session.declareHome())
        val after = assertNotNull(session.skyWatcherInfo())
        assertFalse(after.startsAwayFromHome)
        assertEquals(0.0, after.startAxis1Degrees, 0.01)
        assertEquals(0.0, factory.simulator.axis1Degrees, 0.01)
        assertEquals("The mount started at its home position.", MountText.startPositionLine(after))
    }

    @Test
    fun theSiteIsPassedToTheDriverWithoutSendingAnythingToTheMount() {
        val (_, factory) = connected(values = settings.copy(sendsSiteAndTime = false))
        // The mount has no clock or site: setSiteAndTime sends nothing, so only inquiries and the initialization were sent.
        assertTrue(factory.simulator.commandLog.none { it.startsWith("E") || it.startsWith("W") })
    }

    // Alignment

    @Test
    fun goToIsRefusedUntilTheFirstSync() {
        val (session, factory) = connected()
        val failure = assertIs<MountResult.Failure>(session.goto(MountTestSky.target("Andromeda", andromeda)))
        assertEquals(MountHints.SKYWATCHER_NOT_ALIGNED, failure.message)
        assertEquals(MountError.NotAligned, failure.error)
        assertEquals(0, factory.simulator.gotosStarted)
    }

    @Test
    fun theFirstSyncShowsTheHomePositionErrorAndAlignsTheMount() {
        val (session, factory) = connected()
        val result = assertIs<MountResult.Success>(session.syncOn(factory, "Vega", vega))
        assertTrue(result.message.orEmpty().startsWith("Synced to Vega. The mount's home position was"), result.message)
        val info = assertNotNull(session.skyWatcherInfo())
        assertTrue(info.isAligned)
        assertEquals(listOf("Vega"), info.stars.map { it.name })
        assertEquals(assertNotNull(result.offsetDegrees), assertNotNull(info.homeErrorDegrees), 1e-9)
        assertTrue(info.homeErrorDegrees > 1.0, "the simulated mount starts a few degrees off")
        assertEquals("Aligned on 1 star, ≈ ${MountText.accuracyText(info.predictedErrorDegrees!!)} accuracy", MountText.alignmentLine(info))
        // Go to works now.
        assertIs<MountResult.Success>(session.goto(MountTestSky.target("Andromeda", andromeda)))
    }

    @Test
    fun threeStarsAlignTheMountAndGoToArrivesOnTarget() {
        val (session, factory) = connected()
        session.syncOn(factory, "Vega", vega)
        session.syncOn(factory, "Mirfak", mirfak)
        session.syncOn(factory, "Enif", enif)
        val info = assertNotNull(session.skyWatcherInfo())
        assertEquals(listOf("Vega", "Mirfak", "Enif"), info.stars.map { it.name })
        assertTrue(info.stars.all { it.residualDegrees < 0.1 }, "${info.stars}")
        assertTrue(info.predictedErrorDegrees!! < 0.5, "${info.predictedErrorDegrees}")
        assertTrue(MountText.alignmentLine(info).startsWith("Aligned on 3 stars, "), MountText.alignmentLine(info))
        // The home position error stays that of the first sync.
        assertTrue(info.homeErrorDegrees!! > 1.0)

        assertIs<MountResult.Success>(session.goto(MountTestSky.target("Deneb", deneb)))
        settle(session)
        assertIs<MountSlewOutcome.Arrived>(session.status.outcome)
        assertTrue(factory.trueJ2000().angularSeparation(deneb) < 0.15, "${factory.trueJ2000().angularSeparation(deneb)}")
    }

    @Test
    fun aStarThatDoesNotFitIsRefusedByName() {
        val (session, factory) = connected()
        session.syncOn(factory, "Vega", vega)
        session.syncOn(factory, "Mirfak", mirfak)
        // The telescope stands on Enif, but the user says it is Deneb.
        factory.placeOn(enif)
        val failure = assertIs<MountResult.Failure>(session.sync(MountTestSky.target("Deneb", deneb)))
        assertEquals("That does not fit the other stars. Check that you centred Deneb.", failure.message)
        assertIs<MountError.Rejected>(failure.error)
        assertEquals(2, session.skyWatcherInfo()?.stars?.size)
    }

    @Test
    fun aStarCanBeRemovedAndTheAlignmentStartedOver() {
        val (session, factory) = connected()
        session.syncOn(factory, "Vega", vega)
        session.syncOn(factory, "Mirfak", mirfak)
        val removed = assertIs<MountResult.Success>(session.removeAlignmentStar(0))
        assertEquals("Removed Vega from the alignment.", removed.message)
        assertEquals(listOf("Mirfak"), session.skyWatcherInfo()?.stars?.map { it.name })
        assertIs<MountResult.Failure>(session.removeAlignmentStar(5))
        assertIs<MountResult.Success>(session.clearAlignment())
        val info = assertNotNull(session.skyWatcherInfo())
        assertFalse(info.isAligned)
        assertNull(info.homeErrorDegrees)
        assertEquals("Not aligned: centre a star and tap Sync", MountText.alignmentLine(info))
        assertEquals(MountHints.SKYWATCHER_NOT_ALIGNED, assertIs<MountResult.Failure>(session.goto(MountTestSky.target("Deneb", deneb))).message)
    }

    @Test
    fun syncingTheSameStarAgainReplacesItAndABigOffsetIsFlagged() {
        val (session, factory) = connected()
        session.syncOn(factory, "Vega", vega)
        session.syncOn(factory, "Mirfak", mirfak)
        session.syncOn(factory, "Vega", vega)
        assertEquals(2, session.skyWatcherInfo()?.stars?.size)
        val text = MountHints.skyWatcherSynced("Altair", 0.9, 3)
        assertTrue(text.contains("which is a lot"), text)
        assertFalse(MountHints.skyWatcherSynced("Altair", 0.1, 3).contains("a lot"))
    }

    @Test
    fun theAltitudeLimitAppliesAtOnceAndKeepsTheAlignment() {
        val (session, factory) = connected(values = settings.copy(altitudeLimit = 50.0))
        session.syncOn(factory, "Vega", vega)
        val target = MountTestSky.target("Altair", altair)
        val refused = assertIs<MountResult.Failure>(session.goto(target))
        assertIs<MountError.BelowHorizonLimit>(refused.error)
        assertTrue(refused.message.contains("below your altitude limit of 50.0°"), refused.message)
        session.configure(settings.copy(altitudeLimit = 5.0))
        assertIs<MountResult.Success>(session.goto(target))
        assertEquals(1, factory.opened, "the session was not restarted")
        assertTrue(assertNotNull(session.skyWatcherInfo()).isAligned, "the alignment survived the new limit")
        settle(session)
        session.configure(settings.copy(altitudeLimit = 60.0))
        assertIs<MountError.BelowHorizonLimit>(assertIs<MountResult.Failure>(session.goto(target)).error)
        assertTrue(assertNotNull(session.skyWatcherInfo()).isAligned)
    }

    @Test
    fun movingTheSiteClearsTheAlignmentWithAnExplanation() {
        var observer = MountTestSky.boulder
        val (session, factory) = connected(observer = { observer })
        session.syncOn(factory, "Vega", vega)
        // A small move changes nothing.
        observer = Observer(GeographicLocation(latitude = 40.016, longitude = -105.27, elevation = 1650.0))
        session.poll()
        assertTrue(assertNotNull(session.skyWatcherInfo()).isAligned)
        assertNull(session.takeNotice())
        // A move of a degree or two forgets the stars, because they described the sky of the old place.
        observer = Observer(GeographicLocation(latitude = 41.5, longitude = -105.27, elevation = 1650.0))
        session.poll()
        assertFalse(assertNotNull(session.skyWatcherInfo()).isAligned)
        assertEquals("Your location changed, so the star alignment was cleared. Align on a star again.", session.takeNotice())
        assertNull(session.takeNotice())
    }

    @Test
    fun anEquatorialMountReportsItsPierSideAndWhyTrackingStopped() {
        val (session, factory) = connected(SkyWatcherMountMode.EQUATORIAL, SkyWatcherPointingErrors())
        session.syncOn(factory, "Vega", vega)
        val info = assertNotNull(session.skyWatcherInfo())
        // Vega is west of the meridian: the telescope stands east of the pier.
        assertEquals(SkyWatcherPierSide.EAST, info.pierSide)
        assertEquals("Telescope east of the pier", MountText.pierSideLine(info.pierSide!!))
        assertNull(info.trackingStopReason)
    }

    // The service and the simulated mount

    private fun service(): MountService {
        val observer = { MountTestSky.boulder }
        return MountService(
            scope = scope,
            session = MountSession(SimulatedSkyWatcherFactory(SkyWatcherMountMode.ALT_AZIMUTH, errors), observerProvider = observer),
            preferences = MountPreferences(MapMountStore()),
            observerProvider = observer,
            demoFactory = { demo -> MountDemoLinkFactory(demo, observer) },
            pollMillis = 5,
            nudgePollMillis = 5,
        )
    }

    private fun <T : Any> await(what: String, block: () -> T?): T {
        val end = System.nanoTime() + 5_000_000_000
        while (System.nanoTime() < end) {
            block()?.let { return it }
            Thread.sleep(2)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun theServicePublishesTheAlignmentAfterEverySync() {
        val service = service()
        service.connect(connection)
        val connected = await("the connection") { service.snapshot.takeIf { it.state == MountConnectionState.CONNECTED } }
        assertIs<MountResult.Failure>(runBlocking { service.goto(MountTestSky.target("Deneb", deneb)) })
        assertEquals(false, connected.isAligned)
        assertEquals(MountHints.SKYWATCHER_NOT_ALIGNED, MountText.notAlignedBanner(connected))
        assertEquals(0, service.snapshot.skyWatcher?.stars?.size)
    }

    @Test
    fun theSimulatedSkyWatcherMountCanBeAlignedStepByStep() {
        val service = service()
        val demoConnection = MountProtocols.option(MountProtocol.SKYWATCHER).defaultConnection()
        service.connect(demoConnection, demo = MountDemoKind.SKYWATCHER)
        val snapshot = await("the connection") { service.snapshot.takeIf { it.state == MountConnectionState.CONNECTED } }
        assertEquals(MountDemoKind.SKYWATCHER, snapshot.demo)
        assertTrue(snapshot.isDemo)
        assertEquals("Sky-Watcher AZ-GTi 3.22", snapshot.capabilities?.name)
        assertEquals(false, snapshot.skyWatcher?.isAligned)
        assertEquals(false, snapshot.skyWatcher?.startsAwayFromHome)
        assertNotNull(await("the marker") { service.marker })

        val target = MountTestSky.target("Vega", vega)
        // The simulated mount can stand where a centred star would be, then Sync.
        assertIs<MountResult.Success>(runBlocking { service.centreDemo(target) })
        val synced = assertIs<MountResult.Success>(runBlocking { service.sync(target) })
        assertTrue(synced.message.orEmpty().contains("home position"), synced.message)
        val aligned = await("the alignment") { service.snapshot.takeIf { it.skyWatcher?.isAligned == true } }
        assertEquals(listOf("Vega"), aligned.skyWatcher?.stars?.map { it.name })
        assertTrue(aligned.skyWatcher?.homeErrorDegrees!! > 1.0)

        assertIs<MountResult.Success>(runBlocking { service.clearAlignment() })
        await("the alignment to be cleared") { service.snapshot.takeIf { it.skyWatcher?.isAligned == false } }
        service.disconnect()
        await("the disconnection") { service.snapshot.takeIf { it.state == MountConnectionState.DISCONNECTED } }
    }

    @Test
    fun theOnlyHelperOfTheSimulatedMountIsNotThereForARealOne() {
        val service = service()
        service.connect(connection)
        await("the connection") { service.snapshot.takeIf { it.state == MountConnectionState.CONNECTED } }
        val failure = assertIs<MountResult.Failure>(runBlocking { service.centreDemo(MountTestSky.target("Vega", vega)) })
        assertIs<MountError.NotSupported>(failure.error)
    }
}

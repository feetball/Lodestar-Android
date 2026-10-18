package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.mount.MountTestSupport.andromeda
import net.kuecker.lodestar.kit.mount.MountTestSupport.start
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protocol-specific behaviour of the three drivers, exercised against the simulators without a controller. */
class MountDriverTests {
    private class Fixture<S>(val clock: ManualMountClock, val mount: MountSimulatorModel, val simulator: S)

    private fun lx200(
        dialect: LX200Dialect = LX200Dialect.ONSTEP,
        highPrecision: Boolean = true,
        configure: (LX200Simulator) -> Unit = {},
    ): Fixture<LX200Simulator> {
        val clock = ManualMountClock(start)
        val mount = MountSimulatorModel(clock, MountTestSupport.parkPosition)
        val simulator = LX200Simulator(clock, mount, dialect, highPrecision)
        configure(simulator)
        return Fixture(clock, mount, simulator)
    }

    private fun nexStar(
        flavor: NexStarFlavor = NexStarFlavor.SYNSCAN,
        major: Int = 4,
        minor: Int = 38,
        patch: Int = 6,
        trackingMode: Int = 1,
    ): Fixture<NexStarSimulator> {
        val clock = ManualMountClock(start)
        val mount = MountSimulatorModel(clock, MountTestSupport.parkPosition)
        return Fixture(clock, mount, NexStarSimulator(clock, mount, flavor, major, minor, patch, trackingMode = trackingMode))
    }

    // MARK: LX200

    @Test
    fun testALowPrecisionMountIsSwitchedToHighPrecisionOnce() {
        val f = lx200(LX200Dialect.MEADE, highPrecision = false)
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        assertTrue(f.simulator.highPrecision)
        assertEquals(LX200Precision.HIGH, driver.precision)
        assertEquals(1, f.simulator.commandLog.count { it == "U" })
        driver.readPosition()
        assertEquals(1, f.simulator.commandLog.count { it == "U" })
    }

    @Test
    fun testAHighPrecisionMountIsLeftAlone() {
        val f = lx200(LX200Dialect.MEADE, highPrecision = true)
        LX200Driver(f.simulator, f.clock).connect()
        assertEquals(0, f.simulator.commandLog.count { it == "U" })
        assertTrue(f.simulator.highPrecision)
    }

    @Test
    fun testLowPrecisionCanBeKeptAndTargetsUseIt() {
        val f = lx200(LX200Dialect.MEADE, highPrecision = false)
        val driver = LX200Driver(f.simulator, f.clock, LX200Configuration(prefersHighPrecision = false))
        driver.connect()
        assertEquals(LX200Precision.LOW, driver.precision)
        assertEquals(0, f.simulator.commandLog.count { it == "U" })
        driver.slew(EquatorialCoordinate(187.5, 45.5))
        assertTrue(f.simulator.commandLog.contains("Sr12:30.0"), "${f.simulator.commandLog}")
        assertTrue(f.simulator.commandLog.contains("Sd+45*30"), "${f.simulator.commandLog}")
        assertEquals(1, f.mount.slewsStarted)
    }

    @Test
    fun testAPrecisionChangeBehindTheDriversBackIsNoticedByTheNextReading() {
        val f = lx200(LX200Dialect.MEADE, highPrecision = true)
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        // Someone toggles the format on the hand controller; a strict Meade mount now refuses the high precision target.
        f.simulator.highPrecision = false
        assertIs<MountError.Rejected>(assertFailsWith<MountError> { driver.slew(EquatorialCoordinate(187.5, 45.5)) })
        assertEquals(0, f.mount.slewsStarted)
        driver.readPosition()
        assertEquals(LX200Precision.LOW, driver.precision)
        driver.slew(EquatorialCoordinate(187.5, 45.5))
        assertEquals(1, f.mount.slewsStarted)
    }

    @Test
    fun testIdentificationIsOptional() {
        val f = lx200(LX200Dialect.MEADE) { it.inject("GVP", MountSimulatedFault.Silence) }
        val driver = LX200Driver(f.simulator, f.clock)
        val begin = f.clock.secondsSince1970
        val capabilities = driver.connect()
        assertEquals("LX200-compatible mount", capabilities.name)
        assertNull(capabilities.firmware)
        assertEquals(LX200Dialect.MEADE, driver.dialect)
        // Only the short identification timeout was spent waiting.
        assertTrue(f.clock.secondsSince1970 - begin < 2.0, "${f.clock.secondsSince1970 - begin}")
    }

    @Test
    fun testGarbageDuringIdentificationIsIgnored() {
        val f = lx200(LX200Dialect.MEADE) { it.inject("GVP", MountSimulatedFault.Replace("x".repeat(70) + "#")) }
        val capabilities = LX200Driver(f.simulator, f.clock).connect()
        assertEquals("LX200-compatible mount", capabilities.name)
        assertNull(capabilities.firmware)
    }

    @Test
    fun testTheDialectCanBeForced() {
        val f = lx200(LX200Dialect.ONSTEP)
        val driver = LX200Driver(f.simulator, f.clock, LX200Configuration(dialect = LX200Dialect.MEADE))
        val capabilities = driver.connect()
        assertEquals(LX200Dialect.MEADE, driver.dialect)
        assertFalse(capabilities.canSetTracking)
        assertFalse(f.simulator.commandLog.contains("GU"))
        assertNull(driver.readTracking())
        assertNull(driver.readAligned())
        assertEquals(MountError.NotSupported(MountFeature.TRACKING), assertFailsWith<MountError.NotSupported> { driver.setTracking(false) })
    }

    @Test
    fun testOnStepReportsTrackingAndAlignment() {
        val f = lx200(LX200Dialect.ONSTEP) { it.alignedStars = 0 }
        val driver = LX200Driver(f.simulator, f.clock)
        assertEquals(false, driver.connect().isAligned)
        assertEquals(false, driver.readAligned())
        f.simulator.alignedStars = 3
        assertEquals(true, driver.readAligned())
        assertEquals(true, driver.readTracking())
        f.mount.isTracking = false
        assertEquals(false, driver.readTracking())
    }

    @Test
    fun testMeadeDateReplyTextDoesNotDisturbTheNextCommand() {
        val f = lx200(LX200Dialect.MEADE)
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        driver.setSiteAndTime(GeographicLocation(40.0, -105.0, 0.0), start)
        val position = driver.readPosition()
        assertEquals(0.0, position.angularSeparation(MountTestSupport.parkPosition), 0.03)
        assertEquals(listOf("St+40*00", "Sg105*00", "SG+00.0", "SC10/18/26", "SL04:00:00"), f.simulator.siteCommands)
    }

    @Test
    fun testAMountThatRefusesTheSiteSaysWhich() {
        val f = lx200(LX200Dialect.ONSTEP) { it.inject("Sg", MountSimulatedFault.Replace("0")) }
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        val error = assertFailsWith<MountError.Rejected> { driver.setSiteAndTime(GeographicLocation(40.0, -105.0, 0.0), start) }
        assertTrue(error.detail.contains("longitude"), error.detail)
        assertFailsWith<MountError.InvalidInput> {
            driver.setSiteAndTime(GeographicLocation(40.0, -105.0, 0.0), java.time.Instant.parse("+10000-01-01T00:00:00Z"))
        }
    }

    @Test
    fun testOnStepSyncLooksAtTheStatusBeforeTouchingTheTarget() {
        val f = lx200(LX200Dialect.ONSTEP)
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        f.simulator.clearLog()
        f.mount.isParked = true
        assertEquals(MountError.Parked, assertFailsWith<MountError> { driver.sync(andromeda) })
        f.mount.isParked = false
        f.mount.beginSlew(andromeda)
        assertEquals(MountError.SlewInProgress, assertFailsWith<MountError> { driver.sync(andromeda) })
        assertTrue(f.simulator.commandLog.none { it.startsWith("Sr") || it == "CM" }, "${f.simulator.commandLog}")
    }

    @Test
    fun testGarbageWhereAFlagIsExpectedIsReported() {
        val f = lx200(LX200Dialect.ONSTEP)
        val driver = LX200Driver(f.simulator, f.clock)
        driver.connect()
        f.simulator.inject("Sr", MountSimulatedFault.Replace("x"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.slew(andromeda) })
        f.simulator.inject("MS", MountSimulatedFault.Replace("z"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.slew(andromeda) })
    }

    // MARK: NexStar and SynScan

    @Test
    fun testOldCelestronFirmwareGetsFewerFeaturesAndStandardPositions() {
        val old = nexStar(NexStarFlavor.CELESTRON, 1, 5, 0)
        val driver = NexStarDriver(old.simulator, old.clock)
        val capabilities = driver.connect()
        assertFalse(capabilities.canSync || capabilities.canSetTracking || capabilities.canNudge || capabilities.canSetSiteAndTime)
        assertTrue(old.simulator.commandLog.contains("E"))
        assertFalse(old.simulator.commandLog.contains("e"))
        assertNull(driver.readTracking())
        assertEquals(MountEpoch.MEAN_OF_DATE, driver.epoch)
        assertEquals(0.0, driver.readPosition().angularSeparation(MountTestSupport.parkPosition), 0.01)

        val recent = nexStar(NexStarFlavor.CELESTRON, 1, 6, 0)
        val recentDriver = NexStarDriver(recent.simulator, recent.clock)
        val recentCapabilities = recentDriver.connect()
        assertTrue(recentCapabilities.canSetTracking && recentCapabilities.canNudge)
        assertFalse(recentCapabilities.canSync || recentCapabilities.canSetSiteAndTime)
        assertTrue(recent.simulator.commandLog.contains("e"))

        val clockProgram = nexStar(NexStarFlavor.CELESTRON, 2, 3, 0)
        assertTrue(NexStarDriver(clockProgram.simulator, clockProgram.clock).connect().canSetSiteAndTime)
        val syncing = nexStar(NexStarFlavor.CELESTRON, 4, 10, 0)
        assertTrue(NexStarDriver(syncing.simulator, syncing.clock).connect().canSync)
    }

    @Test
    fun testSynScanFirmwareGatesSyncAndPassThroughMoves() {
        val cases = listOf(
            Triple(3, 36, 0) to false, Triple(3, 37, 0) to true, Triple(4, 37, 2) to false, Triple(4, 37, 3) to true, Triple(4, 38, 6) to true,
        )
        for ((version, expected) in cases) {
            val f = nexStar(NexStarFlavor.SYNSCAN, version.first, version.second, version.third)
            val capabilities = NexStarDriver(f.simulator, f.clock).connect()
            assertEquals(expected, capabilities.canSync, "$version")
            assertTrue(capabilities.canNudge, "$version")
            assertTrue(capabilities.canSetTracking, "$version")
        }
        val tooOld = nexStar(NexStarFlavor.SYNSCAN, 3, 2, 0)
        assertFalse(NexStarDriver(tooOld.simulator, tooOld.clock).connect().canNudge)
        val driver = NexStarDriver(tooOld.simulator, tooOld.clock)
        val capabilities = driver.connect()
        assertEquals("Sky-Watcher AZ GOTO 3.02.00", capabilities.name)
        assertEquals("3.02.00", capabilities.firmware)
    }

    @Test
    fun testTheEpochDefaultsFollowTheFlavorAndCanBeOverridden() {
        val synScan = nexStar(NexStarFlavor.SYNSCAN)
        val automatic = NexStarDriver(synScan.simulator, synScan.clock)
        automatic.connect()
        assertEquals(MountEpoch.J2000, automatic.epoch)
        val celestron = nexStar(NexStarFlavor.CELESTRON, 5, 28, 0)
        val celestronDriver = NexStarDriver(celestron.simulator, celestron.clock)
        celestronDriver.connect()
        assertEquals(MountEpoch.MEAN_OF_DATE, celestronDriver.epoch)
        val legacy = nexStar(NexStarFlavor.SYNSCAN)
        val forced = NexStarDriver(legacy.simulator, legacy.clock, NexStarConfiguration(epoch = MountEpoch.MEAN_OF_DATE))
        assertEquals(MountEpoch.MEAN_OF_DATE, forced.epoch)
        forced.connect()
        assertEquals(MountEpoch.MEAN_OF_DATE, forced.epoch)
        val celestronAsJ2000 = nexStar(NexStarFlavor.CELESTRON, 5, 28, 0)
        val override = NexStarDriver(celestronAsJ2000.simulator, celestronAsJ2000.clock, NexStarConfiguration(epoch = MountEpoch.J2000))
        override.connect()
        assertEquals(MountEpoch.J2000, override.epoch)
    }

    @Test
    fun testTrackingIsSwitchedBackOnInTheModeThatWasRunning() {
        val equatorial = nexStar(trackingMode = 2)
        val driver = NexStarDriver(equatorial.simulator, equatorial.clock)
        driver.connect()
        driver.setTracking(false)
        assertEquals(false, driver.readTracking())
        driver.setTracking(true)
        assertEquals(2, equatorial.simulator.trackingMode)
        assertEquals(true, driver.readTracking())

        val stopped = nexStar(trackingMode = 1)
        stopped.mount.isTracking = false
        val preferred = NexStarDriver(stopped.simulator, stopped.clock, NexStarConfiguration(preferredTrackingMode = NexStarTrackingMode.EQ_NORTH))
        preferred.connect()
        preferred.setTracking(true)
        assertEquals(2, stopped.simulator.trackingMode)
        assertTrue(stopped.mount.isTracking)
    }

    @Test
    fun testAMotorControllerThatDoesNotAnswerIsReported() {
        val f = nexStar()
        val driver = NexStarDriver(f.simulator, f.clock)
        driver.connect()
        f.simulator.inject("P", MountSimulatedFault.Replace("\u0000#"))
        assertIs<MountError.Rejected>(assertFailsWith<MountError> { driver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 1.0) })
        // Tracking was paused for the move; stopping the failed nudge puts it back.
        assertFalse(f.mount.isTracking)
        driver.stopNudge(MountNudgeDirection.NORTH)
        assertFalse(f.mount.isNudging)
        assertTrue(f.mount.isTracking)
    }

    @Test
    fun testEastAndWestCanBeSwapped() {
        for (invert in listOf(false, true)) {
            val f = nexStar()
            val driver = NexStarDriver(f.simulator, f.clock, NexStarConfiguration(invertsEastWest = invert))
            driver.connect()
            val before = f.mount.position.rightAscension
            driver.startNudge(MountNudgeDirection.EAST, MountNudgeRate.FIND, 1.0)
            f.clock.advance(1.0)
            driver.stopNudge(MountNudgeDirection.EAST)
            val moved = f.mount.position.rightAscension - before
            assertTrue(if (invert) moved < -0.01 else moved > 0.01, "invert=$invert moved $moved")
        }
    }

    @Test
    fun testUnalignedHandControlsDoNotGetGotoCommands() {
        val f = nexStar()
        f.mount.isAligned = false
        val driver = NexStarDriver(f.simulator, f.clock)
        assertEquals(false, driver.connect().isAligned)
        assertEquals(MountError.NotAligned, assertFailsWith<MountError> { driver.slew(andromeda) })
        assertFalse(f.simulator.commandLog.contains("r"))
        assertEquals(false, driver.readAligned())
        f.mount.isAligned = true
        assertEquals(true, driver.readAligned())
    }

    @Test
    fun testUnalignedPositionsMayBeMeaningless() {
        val nonsense = MountSimulatedFault.Replace("34AB0500,50000500#")
        val unaligned = nexStar()
        unaligned.mount.isAligned = false
        unaligned.simulator.inject("e", nonsense, 3)
        val driver = NexStarDriver(unaligned.simulator, unaligned.clock)
        driver.connect()
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.readPosition() })

        val aligned = nexStar()
        aligned.simulator.inject("e", nonsense)
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { NexStarDriver(aligned.simulator, aligned.clock).connect() })
    }

    @Test
    fun testTheControllerShowsAnUnalignedMountAndNoticesWhenItIsAligned() {
        val clock = ManualMountClock(start)
        val mount = MountSimulatorModel(clock, MountTestSupport.parkPosition)
        mount.isAligned = false
        val simulator = NexStarSimulator(clock, mount)
        simulator.inject("e", MountSimulatedFault.Replace("34AB0500,50000500#"), 3)
        val controller = MountController(NexStarDriver(simulator, clock), MountTestSupport.boulder, MountSettings.standard, clock)
        controller.connect()
        assertEquals(false, controller.status.isAligned)
        assertNull(controller.status.position)
        val polled = controller.poll()
        assertNull(polled.lastError)
        assertEquals(false, polled.isAligned)
        assertEquals(MountError.NotAligned, assertFailsWith<MountError> { controller.goto(andromeda) })
        mount.isAligned = true
        val aligned = controller.poll()
        assertEquals(true, aligned.isAligned)
        assertNotNull(aligned.position)
        controller.goto(andromeda)
        assertEquals(MountPhase.SLEWING, controller.status.phase)
    }

    @Test
    fun testSiteAndTimeAreRefusedByOldHandControls() {
        val f = nexStar(NexStarFlavor.CELESTRON, 2, 2, 0)
        val driver = NexStarDriver(f.simulator, f.clock)
        driver.connect()
        assertEquals(
            MountError.NotSupported(MountFeature.SITE_AND_TIME),
            assertFailsWith<MountError> { driver.setSiteAndTime(GeographicLocation(40.0, -105.0, 0.0), start) },
        )
        val recent = nexStar()
        val recentDriver = NexStarDriver(recent.simulator, recent.clock)
        recentDriver.connect()
        assertFailsWith<MountError.InvalidInput> {
            recentDriver.setSiteAndTime(GeographicLocation(40.0, -105.0, 0.0), java.time.Instant.parse("2150-01-01T00:00:00Z"))
        }
    }

    @Test
    fun testGarbageWhereAnAcknowledgementIsExpected() {
        val f = nexStar()
        val driver = NexStarDriver(f.simulator, f.clock)
        driver.connect()
        f.simulator.inject("r", MountSimulatedFault.Replace("?"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.slew(andromeda) })
        f.simulator.inject("L", MountSimulatedFault.Replace("2#"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.readSlewing() })
        f.simulator.inject("t", MountSimulatedFault.Replace("\u0009#"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.readTracking() })
        f.simulator.inject("J", MountSimulatedFault.Replace("\u0007#"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.readAligned() })
        f.simulator.inject("L", MountSimulatedFault.Replace("1x"))
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { driver.readSlewing() })
    }

    // MARK: Alpaca

    private class Recorder(private val inner: AlpacaTransport) : AlpacaTransport {
        val requests = ArrayList<AlpacaHTTPRequest>()

        override fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse {
            requests.add(request)
            return inner.send(request, timeoutSeconds)
        }
    }

    private fun alpaca(configure: (AlpacaSimulator) -> Unit = {}, build: (ManualMountClock, MountSimulatorModel) -> AlpacaSimulator): Triple<ManualMountClock, MountSimulatorModel, AlpacaSimulator> {
        val clock = ManualMountClock(start)
        val mount = MountSimulatorModel(clock, MountTestSupport.parkPosition)
        val simulator = build(clock, mount)
        configure(simulator)
        return Triple(clock, mount, simulator)
    }

    @Test
    fun testOtherCoordinateSystemsAreRefused() {
        for (system in listOf(0, 3, 4)) {
            val (_, _, simulator) = alpaca { clock, mount -> AlpacaSimulator(clock, mount, equatorialSystem = system) }
            val error = assertFailsWith<MountError.UnsupportedCoordinateSystem>("$system") { AlpacaDriver(simulator).connect() }
            assertEquals(system, error.code)
        }
    }

    @Test
    fun testNudgesFallBackToWhatTheMountOffers() {
        val (_, mountA, onlyPulses) = alpaca { clock, mount -> AlpacaSimulator(clock, mount, canMoveAxis = false) }
        val pulseDriver = AlpacaDriver(onlyPulses)
        assertTrue(pulseDriver.connect().canNudge)
        pulseDriver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.SLEW, 1.0)
        assertTrue(onlyPulses.methodLog.contains("pulseguide"))
        assertFalse(onlyPulses.methodLog.contains("moveaxis"))
        assertTrue(mountA.position.declination > MountTestSupport.parkPosition.declination)

        val (_, _, onlyAxes) = alpaca { clock, mount -> AlpacaSimulator(clock, mount, canPulseGuide = false) }
        val axisDriver = AlpacaDriver(onlyAxes)
        assertTrue(axisDriver.connect().canNudge)
        axisDriver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.GUIDE, 1.0)
        assertTrue(onlyAxes.methodLog.contains("moveaxis"))
        axisDriver.stopNudge(MountNudgeDirection.NORTH)
        assertEquals(2, onlyAxes.methodLog.count { it == "moveaxis" })

        val (_, _, neither) = alpaca { clock, mount -> AlpacaSimulator(clock, mount, canPulseGuide = false, canMoveAxis = false) }
        val bareDriver = AlpacaDriver(neither)
        assertFalse(bareDriver.connect().canNudge)
        assertEquals(MountError.NotSupported(MountFeature.NUDGE), assertFailsWith<MountError> { bareDriver.startNudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 1.0) })
    }

    @Test
    fun testRequestsAreNumberedConsecutivelyWithOneClientIdentity() {
        val (clock, _, simulator) = alpaca { c, m -> AlpacaSimulator(c, m) }
        val recorder = Recorder(simulator)
        val controller = MountController(
            AlpacaDriver(recorder, AlpacaConfiguration(clientID = 42)), MountTestSupport.boulder, MountSettings.standard, clock,
        )
        controller.connect()
        controller.goto(andromeda)
        controller.poll()
        controller.abort()
        val ids = recorder.requests.map { it.clientTransactionID }
        assertEquals((1..ids.size).toList(), ids)
        assertTrue(ids.size > 15)
        assertTrue(recorder.requests.all { (it.query + it.form).contains(AlpacaField("ClientID", "42")) })
        assertTrue(recorder.requests.all { it.path.startsWith("/api/v1/telescope/0/") })
    }

    @Test
    fun testTheMoveAxisSignIsConfigurable() {
        for (eastIsPositive in listOf(true, false)) {
            val (clock, _, simulator) = alpaca({}) { c, m -> AlpacaSimulator(c, m, canPulseGuide = false) }
            val recorder = Recorder(simulator)
            val driver = AlpacaDriver(recorder, AlpacaConfiguration(primaryPositiveIsEast = eastIsPositive, secondaryPositiveIsNorth = !eastIsPositive))
            driver.connect()
            clock.advance(0.0)
            driver.startNudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 1.0)
            driver.startNudge(MountNudgeDirection.NORTH, MountNudgeRate.CENTER, 1.0)
            val rates = recorder.requests.filter { it.path.endsWith("/moveaxis") }.map { request ->
                request.form.first { it.name == "Rate" }.value.toDouble()
            }
            assertEquals(2, rates.size)
            assertEquals(eastIsPositive, rates[0] > 0, "east primary $eastIsPositive")
            assertEquals(!eastIsPositive, rates[1] > 0, "north secondary")
        }
    }

    @Test
    fun testServerAndDeviceErrorsAreRecordedByPolls() {
        val (clock, _, simulator) = alpaca { c, m -> AlpacaSimulator(c, m) }
        val controller = MountController(AlpacaDriver(simulator), MountTestSupport.boulder, MountSettings.standard, clock)
        controller.connect()
        simulator.inject("slewing", AlpacaFault.Status(500, "Internal error\r\n"))
        assertEquals(MountError.Remote(500, "Internal error"), controller.poll().lastError)
        assertNull(controller.poll().lastError)
        simulator.inject("rightascension", AlpacaFault.Error(1031, "Not connected"))
        assertEquals(MountError.NotConnected, controller.poll().lastError)
        assertNull(controller.poll().lastError)
        simulator.inject("declination", AlpacaFault.WrongTransactionID)
        assertIs<MountError.MalformedReply>(controller.poll().lastError)
        assertNull(controller.poll().lastError)
    }

    @Test
    fun testDisconnectingClosesTheServerSession() {
        val (clock, _, simulator) = alpaca { c, m -> AlpacaSimulator(c, m) }
        val controller = MountController(AlpacaDriver(simulator), MountTestSupport.boulder, MountSettings.standard, clock)
        controller.connect()
        assertTrue(simulator.isConnected)
        controller.disconnect()
        assertFalse(simulator.isConnected)
    }

    @Test
    fun testTransportExceptionsBecomeTransportErrors() {
        val failing = object : AlpacaTransport {
            override fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse =
                throw IllegalStateException("socket closed")
        }
        assertEquals(MountError.Transport("socket closed"), assertFailsWith<MountError> { AlpacaDriver(failing).connect() })
        val brokenStream = object : MountTransport {
            override fun send(bytes: ByteArray) = throw java.io.IOException("broken pipe")

            override fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray = throw java.io.IOException("reset")

            override fun discardPendingInput() = throw java.io.IOException("flush failed")
        }
        val clock = ManualMountClock(start)
        assertEquals(MountError.Transport("flush failed"), assertFailsWith<MountError> { LX200Driver(brokenStream, clock).connect() })
        assertEquals(MountError.Transport("flush failed"), assertFailsWith<MountError> { NexStarDriver(brokenStream, clock).connect() })
    }

    @Test
    fun testAlpacaSlewIsRefusedBeforeSendingWhenTheMountIsParked() {
        val (clock, mount, simulator) = alpaca { c, m -> AlpacaSimulator(c, m) }
        val driver = AlpacaDriver(simulator)
        driver.connect()
        mount.isParked = true
        simulator.let { assertEquals(MountError.Parked, assertFailsWith<MountError> { driver.slew(andromeda) }) }
        assertFalse(simulator.methodLog.contains("slewtocoordinatesasync"))
        assertEquals(0, mount.slewsStarted)
        assertEquals(start, clock.now().let { start })
    }
}

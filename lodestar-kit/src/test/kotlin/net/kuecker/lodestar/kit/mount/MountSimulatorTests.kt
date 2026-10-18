package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountTestSupport.start
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The simulators are test equipment, so they get their own checks: replies are exactly the documented ones. */
class MountSimulatorTests {
    private val clock = ManualMountClock(start)

    private fun mount(rate: Double = 4.0, delay: Double = 0.4, at: EquatorialCoordinate = EquatorialCoordinate(150.0, 10.0)) =
        MountSimulatorModel(clock, at, rate, delay)

    private fun drain(transport: MountTransport): String {
        val text = StringBuilder()
        while (true) {
            try {
                text.append(MountText.latin1(transport.receive(256, 0.0)))
            } catch (_: MountError.Timeout) {
                return text.toString()
            }
        }
    }

    private fun ask(transport: MountTransport, command: String): String {
        transport.discardPendingInput()
        transport.send(MountText.ascii(command))
        return drain(transport)
    }

    private fun askBytes(transport: MountTransport, vararg command: Int): String {
        transport.discardPendingInput()
        transport.send(ByteArray(command.size) { command[it].toByte() })
        return drain(transport)
    }

    // MARK: The moving mount

    @Test
    fun testASlewMovesAtTheConfiguredRateAfterTheStartDelay() {
        val mount = mount(at = EquatorialCoordinate(150.0, 0.0))
        mount.beginSlew(EquatorialCoordinate(150.0, 40.0))
        assertTrue(mount.isSlewing)
        clock.advance(0.3)
        assertEquals(0.0, mount.position.declination, 0.0)
        assertTrue(mount.isSlewing)
        clock.advance(0.1 + 5.0)
        assertEquals(20.0, mount.position.declination, 1e-6)
        clock.advance(4.9)
        assertTrue(mount.isSlewing)
        assertEquals(39.6, mount.position.declination, 1e-6)
        clock.advance(0.2)
        assertFalse(mount.isSlewing)
        assertEquals(EquatorialCoordinate(150.0, 40.0), mount.position)
        assertEquals(1, mount.slewsStarted)
    }

    @Test
    fun testRightAscensionTakesTheShortWayRound() {
        val mount = mount(delay = 0.0, at = EquatorialCoordinate(350.0, 0.0))
        mount.beginSlew(EquatorialCoordinate(10.0, 0.0))
        clock.advance(2.5)
        assertEquals(0.0, mount.position.rightAscension, 1e-9)
        clock.advance(2.5)
        assertEquals(10.0, mount.position.rightAscension, 1e-9)
        assertFalse(mount.isSlewing)
        val backwards = mount(delay = 0.0, at = EquatorialCoordinate(10.0, 0.0))
        backwards.beginSlew(EquatorialCoordinate(350.0, 0.0))
        clock.advance(1.0)
        assertEquals(6.0, backwards.position.rightAscension, 1e-9)
    }

    @Test
    fun testBothAxesMoveTogetherAndFinishTogetherWhenEqual() {
        val mount = mount(delay = 0.0, at = EquatorialCoordinate(100.0, 0.0))
        mount.beginSlew(EquatorialCoordinate(120.0, 8.0))
        clock.advance(2.0)
        assertEquals(EquatorialCoordinate(108.0, 8.0), mount.position)
        assertTrue(mount.isSlewing)
        clock.advance(3.0)
        assertEquals(EquatorialCoordinate(120.0, 8.0), mount.position)
        assertFalse(mount.isSlewing)
    }

    @Test
    fun testStopSyncAndNudge() {
        val mount = mount(delay = 0.0)
        mount.beginSlew(EquatorialCoordinate(200.0, 50.0))
        clock.advance(2.0)
        mount.stop()
        val stopped = mount.position
        assertFalse(mount.isSlewing)
        clock.advance(10.0)
        assertEquals(stopped, mount.position)
        assertEquals(1, mount.stopsReceived)
        mount.sync(EquatorialCoordinate(12.0, -3.0))
        assertEquals(EquatorialCoordinate(12.0, -3.0), mount.position)
        assertEquals(1, mount.syncsPerformed)
        mount.setNudgeRates(0.5, -0.25)
        assertTrue(mount.isNudging)
        assertFalse(mount.isSlewing)
        clock.advance(4.0)
        assertEquals(EquatorialCoordinate(14.0, -4.0), mount.position)
        mount.stop()
        assertFalse(mount.isNudging)
        mount.displace(-20.0, 1.0)
        assertEquals(EquatorialCoordinate(354.0, -3.0), mount.position)
    }

    @Test
    fun testNudgesStopAtThePole() {
        val mount = mount(delay = 0.0, at = EquatorialCoordinate(0.0, 89.0))
        mount.setNudgeRates(0.0, 1.0)
        clock.advance(10.0)
        assertEquals(90.0, mount.position.declination, 0.0)
    }

    // MARK: Stream behaviour

    @Test
    fun testTimeoutsCostTheirFullDurationAndRepliesCostTheLatency() {
        val simulator = LX200Simulator(clock, mount(), latencySeconds = 0.25)
        val before = clock.secondsSince1970
        simulator.send(MountText.ascii(":GVP#"))
        assertEquals(0.25, clock.secondsSince1970 - before, 1e-9)
        assertEquals("On-Step#", MountText.latin1(simulator.receive(100, 1.0)))
        val afterReply = clock.secondsSince1970
        assertFailsWith<MountError.Timeout> { simulator.receive(100, 1.5) }
        assertEquals(1.5, clock.secondsSince1970 - afterReply, 1e-9)
    }

    @Test
    fun testRepliesCanArriveInPiecesAndOldInputIsDiscarded() {
        val simulator = LX200Simulator(clock, mount(), chunkSize = 3)
        simulator.send(MountText.ascii(":GVP#"))
        assertEquals("On-", MountText.latin1(simulator.receive(100, 1.0)))
        assertEquals("St", MountText.latin1(simulator.receive(2, 1.0)))
        assertEquals("ep#", MountText.latin1(simulator.receive(100, 1.0)))
        simulator.send(MountText.ascii(":GVP#"))
        simulator.discardPendingInput()
        assertFailsWith<MountError.Timeout> { simulator.receive(100, 0.1) }
    }

    @Test
    fun testInjectedFaultsHitOnlyMatchingCommandsOnce() {
        val simulator = LX200Simulator(clock, mount())
        simulator.inject("GR", MountSimulatedFault.Silence)
        simulator.inject("GD", MountSimulatedFault.Replace("??#"))
        simulator.inject("GVP", MountSimulatedFault.Truncate(2))
        assertEquals("", ask(simulator, ":GR#"))
        assertTrue(ask(simulator, ":GR#").endsWith("#"))
        assertEquals("??#", ask(simulator, ":GD#"))
        assertTrue(ask(simulator, ":GD#").startsWith("+10*"))
        assertEquals("On", ask(simulator, ":GVP#"))
        assertEquals("On-Step#", ask(simulator, ":GVP#"))
        simulator.inject("GR", MountSimulatedFault.Disconnect)
        assertFailsWith<MountError.Transport> { ask(simulator, ":GR#") }
        assertFailsWith<MountError.Transport> { simulator.send(MountText.ascii(":GR#")) }
        assertFailsWith<MountError.Transport> { simulator.receive(1, 1.0) }
        assertEquals(listOf("GR", "GR", "GD", "GD", "GVP", "GVP", "GR"), simulator.commandLog)
    }

    // MARK: LX200 replies

    @Test
    fun testOnStepRepliesFollowTheReference() {
        val mount = mount(at = EquatorialCoordinate(187.5, -0.5))
        val simulator = LX200Simulator(clock, mount, LX200Dialect.ONSTEP)
        assertEquals("12:30:00#", ask(simulator, ":GR#"))
        assertEquals("-00*30:00#", ask(simulator, ":GD#"))
        assertEquals("On-Step#", ask(simulator, ":GVP#"))
        assertEquals("4.24#", ask(simulator, ":GVN#"))
        assertEquals("#", ask(simulator, ":D#"))
        assertEquals("NpEo000#", ask(simulator, ":GU#"))
        assertEquals("GT2#", ask(simulator, ":GW#"))
        assertEquals("1", ask(simulator, ":Sr13:00:00#"))
        assertEquals("1", ask(simulator, ":Sd+10*00:00#"))
        assertEquals("1", ask(simulator, ":Sr13:00.0#"))
        assertEquals("0", ask(simulator, ":Sr25:00:00#"))
        assertEquals("0", ask(simulator, ":Sd+95*00#"))
        assertEquals("1", ask(simulator, ":Sr13:00:00#"))
        assertEquals("0", ask(simulator, ":MS#"))
        assertEquals("\u007f#", ask(simulator, ":D#"))
        assertEquals("pEo000#", ask(simulator, ":GU#"))
        assertEquals("5", ask(simulator, ":MS#"))
        assertEquals("#", ask(simulator, ":CM#"))
        assertEquals("", ask(simulator, ":Q#"))
        assertEquals("#", ask(simulator, ":D#"))
        assertEquals("N/A#", ask(simulator, ":CM#"))
        assertEquals("1", ask(simulator, ":Td#"))
        assertEquals("nNpEo000#", ask(simulator, ":GU#"))
        assertEquals("", ask(simulator, ":Bogus#"))
    }

    @Test
    fun testOnStepRefusalDigits() {
        val simulator = LX200Simulator(clock, mount(), LX200Dialect.ONSTEP)
        assertEquals("9", ask(simulator, ":MS#"))
        ask(simulator, ":Sr13:00:00#")
        ask(simulator, ":Sd+10*00:00#")
        simulator.refusalCode = '1'
        assertEquals("1", ask(simulator, ":MS#"))
        assertEquals("E1#", ask(simulator, ":CM#"))
        simulator.refusalCode = null
        simulator.mount.isParked = true
        assertEquals("4", ask(simulator, ":MS#"))
        assertEquals("0", ask(simulator, ":Te#"))
    }

    @Test
    fun testMeadeRepliesFollowTheReference() {
        val simulator = LX200Simulator(clock, mount(at = EquatorialCoordinate(187.5, 45.5)), LX200Dialect.MEADE, highPrecision = false)
        assertEquals("12:30.0#", ask(simulator, ":GR#"))
        assertEquals("+45*30#", ask(simulator, ":GD#"))
        assertEquals("LX200 Classic#", ask(simulator, ":GVP#"))
        // :U# toggles the format and has no reply.
        assertEquals("", ask(simulator, ":U#"))
        assertEquals("12:30:00#", ask(simulator, ":GR#"))
        assertEquals("+45*30:00#", ask(simulator, ":GD#"))
        // A Meade mount only takes targets in the current format.
        assertEquals("0", ask(simulator, ":Sr12:30.0#"))
        assertEquals("1", ask(simulator, ":Sr12:30:00#"))
        assertEquals("0", ask(simulator, ":Sd+45*30#"))
        assertEquals("1", ask(simulator, ":Sd+45*30:00#"))
        simulator.refusalCode = '1'
        assertEquals("1Object below horizon#", ask(simulator, ":MS#"))
        simulator.refusalCode = '2'
        assertEquals("2Object below higher#", ask(simulator, ":MS#"))
        simulator.refusalCode = null
        assertEquals("0", ask(simulator, ":MS#"))
        assertEquals("Coordinates matched        #", ask(simulator, ":CM#"))
        // Unknown commands (here an OnStep-only one) get no answer.
        assertEquals("", ask(simulator, ":GU#"))
        assertEquals("", ask(simulator, ":Te#"))
        assertEquals("1Updating Planetary Data#${" ".repeat(30)}#", ask(simulator, ":SC10/18/26#"))
    }

    @Test
    fun testBusyControllersAnswerWithNAK() {
        val simulator = LX200Simulator(clock, mount())
        simulator.busyReplies = 2
        assertEquals("\u0015", ask(simulator, ":GR#"))
        assertEquals("\u0015", ask(simulator, ":GD#"))
        assertTrue(ask(simulator, ":GR#").endsWith("#"))
    }

    @Test
    fun testLX200MovesAndRates() {
        val simulator = LX200Simulator(clock, mount(delay = 0.0, at = EquatorialCoordinate(100.0, 0.0)))
        ask(simulator, ":RC#")
        ask(simulator, ":Mn#")
        ask(simulator, ":Me#")
        clock.advance(10.0)
        val rate = MountNudgeRate.CENTER.degreesPerSecond
        assertEquals(10 * rate, simulator.mount.position.declination, 1e-6)
        assertEquals(100.0 + 10 * rate, simulator.mount.position.rightAscension, 1e-6)
        ask(simulator, ":Qn#")
        clock.advance(10.0)
        assertEquals(10 * rate, simulator.mount.position.declination, 1e-6)
        ask(simulator, ":Qe#")
        val settled = simulator.mount.position
        clock.advance(10.0)
        assertEquals(settled, simulator.mount.position)
        ask(simulator, ":Ms#")
        ask(simulator, ":Mw#")
        assertTrue(simulator.mount.isNudging)
        ask(simulator, ":Q#")
        assertFalse(simulator.mount.isNudging)
    }

    // MARK: NexStar replies

    @Test
    fun testHandControlRepliesFollowTheReference() {
        val mount = mount(at = EquatorialCoordinate(0x34AB05 / 16_777_216.0 * 360.0, 0x12CE05 / 16_777_216.0 * 360.0))
        val celestron = NexStarSimulator(clock, mount, NexStarFlavor.CELESTRON, 5, 28, 0, model = 12)
        assertEquals("\u0005\u001C#", askBytes(celestron, 'V'.code))
        assertEquals("\u000C#", askBytes(celestron, 'm'.code))
        assertEquals("\u0001#", askBytes(celestron, 'J'.code))
        assertEquals("0#", askBytes(celestron, 'L'.code))
        assertEquals("34AB0500,12CE0500#", askBytes(celestron, 'e'.code))
        assertEquals("34AB,12CE#", askBytes(celestron, 'E'.code))
        assertEquals("\u0001#", askBytes(celestron, 't'.code))
        assertEquals("#", askBytes(celestron, 'T'.code, 0))
        assertEquals("\u0000#", askBytes(celestron, 't'.code))
        assertEquals("A#", askBytes(celestron, 'K'.code, 'A'.code))
        val synScan = NexStarSimulator(clock, mount, NexStarFlavor.SYNSCAN, 4, 38, 6)
        assertEquals("042606#", askBytes(synScan, 'V'.code))
        assertEquals("", askBytes(synScan, 'x'.code))
    }

    @Test
    fun testHandControlGotoSyncAndSlews() {
        val mount = mount(delay = 0.0, at = EquatorialCoordinate(100.0, 0.0))
        val simulator = NexStarSimulator(clock, mount, NexStarFlavor.SYNSCAN)
        // GOTO precise 12AB0500,40000500 = 26.25°, 90.0001°: use a reachable target instead.
        val target = EquatorialCoordinate(0x1D58C6 / 16_777_216.0 * 360.0, 0x1D58C6 / 16_777_216.0 * 360.0 / 2)
        assertEquals("#", ask(simulator, "r" + NexStarCodec.encodeAngle(target.rightAscension, true) + "," + NexStarCodec.encodeAngle(target.declination, true)))
        assertEquals("1#", askBytes(simulator, 'L'.code))
        assertEquals("#", askBytes(simulator, 'M'.code))
        assertEquals("0#", askBytes(simulator, 'L'.code))
        assertEquals("#", ask(simulator, "s34AB0500,12CE0500"))
        assertEquals(0x34AB05 / 16_777_216.0 * 360.0, mount.position.rightAscension, 1e-9)
        assertEquals(1, mount.syncsPerformed)
        // Variable rate slew, 150 arcseconds per second positive on the declination axis, then stop with a fixed rate of 0.
        assertEquals("#", askBytes(simulator, 'P'.code, 3, 17, 6, 2, 88, 0, 0))
        clock.advance(10.0)
        assertEquals(10.05 * 150.0 / 3600, mount.position.declination - 0x12CE05 / 16_777_216.0 * 360.0, 1e-6)
        assertEquals("#", askBytes(simulator, 'P'.code, 2, 17, 36, 0, 0, 0, 0))
        assertFalse(mount.isNudging)
        // A device that is not there answers with one extra byte.
        assertEquals("\u0000#", askBytes(simulator, 'P'.code, 3, 99, 6, 0, 1, 0, 0))
        // Firmware version of a motor controller.
        assertEquals("\u0004&#", askBytes(simulator, 'P'.code, 1, 16, 254, 0, 0, 0, 2))
    }

    @Test
    fun testAnUnalignedHandControlAcknowledgesButDoesNotMove() {
        val mount = mount(delay = 0.0)
        mount.isAligned = false
        val simulator = NexStarSimulator(clock, mount)
        assertEquals("\u0000#", askBytes(simulator, 'J'.code))
        assertEquals("#", ask(simulator, "r34AB0500,12CE0500"))
        assertFalse(mount.isSlewing)
        assertEquals(0, mount.slewsStarted)
        assertEquals("#", ask(simulator, "s34AB0500,12CE0500"))
        assertEquals(0, mount.syncsPerformed)
    }

    @Test
    fun testCommandsMayArriveInPieces() {
        val mount = mount(delay = 0.0)
        val simulator = NexStarSimulator(clock, mount)
        simulator.send(MountText.ascii("r34AB05"))
        assertEquals("", drain(simulator))
        simulator.send(MountText.ascii("00,12CE0500"))
        assertEquals("#", drain(simulator))
        assertEquals(1, mount.slewsStarted)
        val lx = LX200Simulator(clock, mount)
        lx.send(MountText.ascii(":G"))
        assertEquals("", drain(lx))
        lx.send(MountText.ascii("VP#:GVN#"))
        assertEquals("On-Step#4.24#", drain(lx))
        // Characters before the start of a command are skipped.
        lx.send(MountText.ascii("xx:GVP#"))
        assertEquals("On-Step#", drain(lx))
        assertContentEquals(listOf("r", "GVP", "GVN", "GVP"), simulator.commandLog + lx.commandLog)
    }

    // MARK: Alpaca replies

    @Test
    fun testAlpacaRepliesFollowTheDocumentedShapes() {
        val simulator = AlpacaSimulator(clock, mount(at = EquatorialCoordinate(187.5, 45.5)))
        val builder = AlpacaRequestBuilder()
        fun body(request: AlpacaHTTPRequest): String = simulator.send(request, 5.0).body
        assertEquals(
            "{\"Value\":false,\"ClientTransactionID\":1,\"ServerTransactionID\":1,\"ErrorNumber\":0,\"ErrorMessage\":\"\"}",
            body(builder.connected()),
        )
        assertEquals(
            "{\"ClientTransactionID\":2,\"ServerTransactionID\":2,\"ErrorNumber\":1031,\"ErrorMessage\":\"Not connected\"}",
            body(builder.abortSlew()),
        )
        assertEquals("{\"ClientTransactionID\":3,\"ServerTransactionID\":3,\"ErrorNumber\":0,\"ErrorMessage\":\"\"}", body(builder.setConnected(true)))
        assertTrue(body(builder.rightAscension()).startsWith("{\"Value\":12.500000000,"))
        assertTrue(body(builder.declination()).startsWith("{\"Value\":45.500000000,"))
        assertTrue(body(builder.equatorialSystem()).startsWith("{\"Value\":1,"))
        assertTrue(body(builder.trackingRates()).startsWith("{\"Value\":[0,1,2],"))
        assertTrue(body(builder.canMoveAxis(AlpacaAxis.PRIMARY)).startsWith("{\"Value\":true,"))
        assertTrue(body(builder.canMoveAxis(AlpacaAxis.TERTIARY)).startsWith("{\"Value\":false,"))
        assertTrue(body(builder.name()).startsWith("{\"Value\":\"Simulated Alpaca Mount\","))
        val slew = builder.slewToCoordinatesAsync(13.0, 10.0)
        assertTrue(body(slew).contains("\"ErrorNumber\":0"))
        assertTrue(body(builder.slewing()).startsWith("{\"Value\":true,"))
    }

    @Test
    fun testAlpacaValidatesPathsMethodsAndParameters() {
        val simulator = AlpacaSimulator(clock, mount())
        val builder = AlpacaRequestBuilder()
        simulator.send(builder.setConnected(true), 5.0)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/api/v1/telescope/0/bogus", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/api/v1/telescope/1/slewing", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/api/v1/camera/0/slewing", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/slewing", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/api/v1/telescope/0/abortslew", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/api/v1/telescope/0/canmoveaxis", emptyList(), emptyList(), 1), 5.0).statusCode)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/other", emptyList(), emptyList(), 1), 5.0).statusCode)
        // Out of range values are reported as InvalidValue (0x401), a parked mount as 0x408.
        for (request in listOf(
            AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/slewtocoordinatesasync", emptyList(), listOf(AlpacaField("RightAscension", "25"), AlpacaField("Declination", "0")), 5),
            AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/slewtocoordinatesasync", emptyList(), listOf(AlpacaField("RightAscension", "5")), 6),
            AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/synctocoordinates", emptyList(), listOf(AlpacaField("RightAscension", "5"), AlpacaField("Declination", "91")), 7),
        )) {
            assertTrue(simulator.send(request, 5.0).body.contains("\"ErrorNumber\":1025"), request.path)
        }
        assertTrue(simulator.send(AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/tracking", emptyList(), listOf(AlpacaField("Tracking", "maybe")), 8), 5.0).body.contains("1025"))
        assertTrue(simulator.send(AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/pulseguide", emptyList(), listOf(AlpacaField("Direction", "7"), AlpacaField("Duration", "100")), 9), 5.0).body.contains("1025"))
        assertTrue(simulator.send(AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/moveaxis", emptyList(), listOf(AlpacaField("Axis", "2"), AlpacaField("Rate", "1")), 10), 5.0).body.contains("1025"))
        // Parameter names are matched ignoring case.
        assertTrue(simulator.send(AlpacaHTTPRequest(AlpacaMethod.PUT, "/api/v1/telescope/0/tracking", emptyList(), listOf(AlpacaField("tracking", "false")), 11), 5.0).body.contains("\"ErrorNumber\":0"))
        assertFalse(simulator.mount.isTracking)
    }

    @Test
    fun testAlpacaManagementEndpoints() {
        val simulator = AlpacaSimulator(clock, mount(), deviceNumber = 3)
        val builder = AlpacaRequestBuilder(3, 5)
        val versions = builder.apiVersions()
        assertEquals(listOf(1), AlpacaResponseParser.apiVersions(simulator.send(versions, 5.0), versions))
        val description = builder.serverDescription()
        assertEquals("Lodestar", AlpacaResponseParser.serverDescription(simulator.send(description, 5.0), description).manufacturer)
        val devices = builder.configuredDevices()
        val parsed = AlpacaResponseParser.configuredDevices(simulator.send(devices, 5.0), devices)
        assertEquals(listOf("Telescope", "Camera"), parsed.map { it.deviceType })
        assertEquals(3, parsed.first { it.isTelescope }.deviceNumber)
        assertEquals(400, simulator.send(AlpacaHTTPRequest(AlpacaMethod.GET, "/management/v1/other", emptyList(), emptyList(), 1), 5.0).statusCode)
        // Discovery: the simulator is what the datagram finds.
        assertTrue(AlpacaDiscovery.isRequest(AlpacaDiscovery.requestPayload))
    }

    @Test
    fun testAlpacaFaults() {
        val simulator = AlpacaSimulator(clock, mount())
        val builder = AlpacaRequestBuilder()
        simulator.inject("name", AlpacaFault.Status(503, "Busy"))
        assertEquals(AlpacaHTTPResponse(503, "Busy"), simulator.send(builder.name(), 5.0))
        simulator.inject("name", AlpacaFault.Body("not json"))
        assertEquals(AlpacaHTTPResponse(200, "not json"), simulator.send(builder.name(), 5.0))
        simulator.inject("name", AlpacaFault.Error(1280, "Boom \"quoted\""))
        assertTrue(simulator.send(builder.name(), 5.0).body.contains("\"ErrorMessage\":\"Boom \\\"quoted\\\"\""))
        val before = clock.secondsSince1970
        simulator.inject("name", AlpacaFault.Timeout)
        assertFailsWith<MountError.Timeout> { simulator.send(builder.name(), 7.0) }
        assertTrue(clock.secondsSince1970 - before >= 7.0)
        simulator.inject("name", AlpacaFault.WrongTransactionID)
        val request = builder.name()
        assertTrue(simulator.send(request, 5.0).body.contains("\"ClientTransactionID\":${request.clientTransactionID + 1000}"))
        assertEquals(listOf("name", "name", "name", "name", "name"), simulator.methodLog)
    }
}

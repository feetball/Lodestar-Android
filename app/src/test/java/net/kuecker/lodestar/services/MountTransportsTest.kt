package net.kuecker.lodestar.services

import kotlinx.coroutines.runBlocking
import net.kuecker.lodestar.kit.mount.AlpacaDiscovery
import net.kuecker.lodestar.kit.mount.AlpacaEndpoint
import net.kuecker.lodestar.kit.mount.AlpacaField
import net.kuecker.lodestar.kit.mount.AlpacaHTTPRequest
import net.kuecker.lodestar.kit.mount.AlpacaHTTPResponse
import net.kuecker.lodestar.kit.mount.AlpacaMethod
import net.kuecker.lodestar.kit.mount.AlpacaRequestBuilder
import net.kuecker.lodestar.kit.mount.AlpacaSimulator
import net.kuecker.lodestar.kit.mount.AlpacaDriver
import net.kuecker.lodestar.kit.mount.LX200Driver
import net.kuecker.lodestar.kit.mount.LX200Simulator
import net.kuecker.lodestar.kit.mount.ManualMountClock
import net.kuecker.lodestar.kit.mount.MountController
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSettings
import net.kuecker.lodestar.kit.mount.MountSimulatorModel
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The transports against real sockets on the loopback interface, and the engine's drivers over them. */
class MountTransportsTest {
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()
    private val cleanup = ArrayList<Closeable>()

    @AfterTest
    fun tearDown() {
        cleanup.forEach { runCatching { it.close() } }
    }

    private fun <T : Closeable> track(item: T): T = item.also { cleanup.add(it) }

    /** A TCP server that runs [serve] for each client on its own thread. */
    private fun server(serve: (Socket) -> Unit): ServerSocket {
        val socket = track(ServerSocket(0, 8, loopback))
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: java.io.IOException) {
                    return@thread
                }
                cleanup.add(client)
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
        return socket
    }

    // TCP

    @Test
    fun tcpSendsAndReceivesWhatArrivesNotMore() {
        val server = server { client ->
            val request = client.getInputStream().readNBytes(5)
            assertEquals(":GR#\n", String(request, Charsets.ISO_8859_1))
            client.getOutputStream().write("12:34:56#".toByteArray())
        }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        transport.send(":GR#\n".toByteArray())
        val first = transport.receive(4, 2.0)
        assertTrue(first.size in 1..4)
        val rest = StringBuilder(String(first))
        while (rest.length < 9) rest.append(String(transport.receive(64, 2.0)))
        assertEquals("12:34:56#", rest.toString())
    }

    @Test
    fun tcpDiscardsLateRepliesBeforeTheNextCommand() {
        val server = server { client ->
            client.getOutputStream().write("late#".toByteArray())
            client.getInputStream().read()
            client.getOutputStream().write("fresh#".toByteArray())
        }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        Thread.sleep(150)
        transport.discardPendingInput()
        transport.send(byteArrayOf(1))
        assertEquals("fresh#", String(transport.receive(64, 2.0)))
    }

    @Test
    fun tcpTimesOutWhenTheMountSaysNothing() {
        val server = server { client -> client.getInputStream().read() }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        val started = System.nanoTime()
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { transport.receive(16, 0.2) })
        assertTrue((System.nanoTime() - started) / 1e9 < 2.0)
    }

    @Test
    fun tcpReportsAClosedConnectionAndARefusedOne() {
        val server = server { client -> client.close() }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        assertIs<MountError.Transport>(assertFailsWith<MountError> { transport.receive(16, 2.0) })
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val refused = assertFailsWith<MountError> { TcpMountTransport("127.0.0.1", port).open() }
        assertIs<MountError.Transport>(refused)
        assertTrue(refused.userMessage.contains("refused"), refused.userMessage)
    }

    @Test
    fun tcpCloseUnblocksAReaderAtOnce() {
        val server = server { client -> client.getInputStream().read() }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        var failure: MountError? = null
        val reader = thread { failure = runCatching { transport.receive(16, 30.0) }.exceptionOrNull() as? MountError }
        Thread.sleep(100)
        transport.close()
        reader.join(2_000)
        assertTrue(!reader.isAlive)
        assertIs<MountError.Transport>(failure)
    }

    @Test
    fun tcpRejectsBadPortsAndUsesBeforeOpen() {
        assertIs<MountError.InvalidInput>(assertFailsWith<MountError> { TcpMountTransport("127.0.0.1", 0).open() })
        val transport = TcpMountTransport("127.0.0.1", 9999)
        assertEquals(MountError.NotConnected, assertFailsWith<MountError> { transport.send(byteArrayOf(1)) })
        assertEquals(MountError.NotConnected, assertFailsWith<MountError> { transport.receive(1, 0.1) })
        transport.close()
        assertIs<MountError.Transport>(assertFailsWith<MountError> { transport.open() })
    }

    // UDP

    private fun udpEcho(reply: (ByteArray) -> List<ByteArray>): DatagramSocket {
        val socket = track(DatagramSocket(0, loopback))
        thread(isDaemon = true) {
            val buffer = ByteArray(2_048)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: java.io.IOException) {
                    return@thread
                }
                for (answer in reply(packet.data.copyOf(packet.length))) {
                    socket.send(DatagramPacket(answer, answer.size, packet.socketAddress))
                }
            }
        }
        return socket
    }

    @Test
    fun udpReturnsDatagramsInPiecesNoLargerThanAsked() {
        val server = udpEcho { listOf("0123456789".toByteArray()) }
        val transport = track(UdpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        transport.send("hello".toByteArray())
        val parts = listOf(transport.receive(4, 2.0), transport.receive(4, 2.0), transport.receive(4, 2.0))
        assertEquals(listOf(4, 4, 2), parts.map { it.size })
        assertEquals("0123456789", parts.joinToString("") { String(it) })
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { transport.receive(4, 0.1) })
    }

    @Test
    fun udpDiscardDropsLeftoversAndLateDatagrams() {
        val server = udpEcho { listOf("abc".toByteArray(), "def".toByteArray()) }
        val transport = track(UdpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        transport.send(byteArrayOf(1))
        assertEquals("a", String(transport.receive(1, 2.0)))
        Thread.sleep(100)
        transport.discardPendingInput()
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { transport.receive(8, 0.1) })
    }

    // HTTP

    /** Answers every request with [respond]'s bytes, after reading the request head and body. */
    private fun httpServer(respond: (head: String, body: String, out: OutputStream) -> Unit): ServerSocket = server { client ->
        val input = client.getInputStream()
        val head = readHead(input)
        val length = Regex("(?i)content-length: *(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
        val body = String(input.readNBytes(length), Charsets.UTF_8)
        respond(head, body, client.getOutputStream())
        client.close()
    }

    private fun readHead(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (!bytes.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            val value = input.read()
            if (value < 0) break
            bytes.write(value)
        }
        return bytes.toString(Charsets.ISO_8859_1)
    }

    private fun http(server: ServerSocket): AlpacaHttpTransport =
        track(AlpacaHttpTransport(AlpacaEndpoint("127.0.0.1", server.localPort)))

    private val get = AlpacaRequestBuilder().rightAscension()

    @Test
    fun httpSendsAWellFormedRequest() {
        var seen = ""
        val server = httpServer { head, _, out ->
            seen = head
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
        }
        val response = http(server).send(get, 2.0)
        assertEquals(AlpacaHTTPResponse(200, "{}"), response)
        assertTrue(seen.startsWith("GET ${get.target} HTTP/1.1\r\n"), seen)
        assertTrue(seen.contains("Host: 127.0.0.1:${server.localPort}\r\n"), seen)
        assertTrue(seen.contains("Connection: close\r\n"))
    }

    @Test
    fun httpPutsFormBodiesWithTheirLength() {
        var seen = ""
        var received = ""
        val server = httpServer { head, body, out ->
            seen = head
            received = body
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }
        val put = AlpacaRequestBuilder().setTracking(true)
        http(server).send(put, 2.0)
        assertTrue(seen.startsWith("PUT ${put.path} HTTP/1.1"), seen)
        assertTrue(seen.contains("Content-Type: application/x-www-form-urlencoded"), seen)
        assertEquals(put.body, received)
        assertTrue(seen.contains("Content-Length: ${put.body.toByteArray().size}\r\n"), seen)
    }

    @Test
    fun httpReadsChunkedBodiesAndBodiesWithoutALength() {
        val chunked = httpServer { _, _, out ->
            out.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"a\"\r\n5;ext=1\r\n:1,\"b\r\n3\r\n\":2\r\n1\r\n}\r\n0\r\nX-Trailer: 1\r\n\r\n".toByteArray())
        }
        assertEquals("{\"a\":1,\"b\":2}", http(chunked).send(get, 2.0).body)
        val untilClose = httpServer { _, _, out -> out.write("HTTP/1.0 200 OK\r\n\r\nplain body ä".toByteArray()) }
        assertEquals("plain body ä", http(untilClose).send(get, 2.0).body)
    }

    @Test
    fun httpPassesErrorStatusesOnAsNormalReplies() {
        val server = httpServer { _, _, out -> out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 4\r\n\r\nnope".toByteArray()) }
        assertEquals(AlpacaHTTPResponse(400, "nope"), http(server).send(get, 2.0))
    }

    @Test
    fun httpFailsCleanlyOnSlowGarbledAndRefusedServers() {
        val slow = httpServer { _, _, _ -> Thread.sleep(3_000) }
        val started = System.nanoTime()
        assertIs<MountError.Timeout>(assertFailsWith<MountError> { http(slow).send(get, 0.3) })
        assertTrue((System.nanoTime() - started) / 1e9 < 2.0)
        val garbled = httpServer { _, _, out -> out.write("this is not http\r\n\r\n".toByteArray()) }
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { http(garbled).send(get, 2.0) })
        val truncated = httpServer { _, _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 50\r\n\r\nshort".toByteArray()) }
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { http(truncated).send(get, 2.0) })
        val huge = httpServer { _, _, out -> out.write("HTTP/1.1 200 OK\r\nContent-Length: 99999999\r\n\r\n".toByteArray()) }
        assertIs<MountError.MalformedReply>(assertFailsWith<MountError> { http(huge).send(get, 2.0) })
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val refused = AlpacaHttpTransport(AlpacaEndpoint("127.0.0.1", port))
        assertIs<MountError.Transport>(assertFailsWith<MountError> { refused.send(get, 2.0) })
    }

    @Test
    fun httpCloseCancelsARequestInFlight() {
        val server = httpServer { _, _, _ -> Thread.sleep(5_000) }
        val transport = http(server)
        var failure: Throwable? = null
        val sender = thread { failure = runCatching { transport.send(get, 30.0) }.exceptionOrNull() }
        Thread.sleep(150)
        transport.close()
        sender.join(2_000)
        assertTrue(!sender.isAlive)
        assertTrue(failure is MountError, "$failure")
        assertIs<MountError.Transport>(assertFailsWith<MountError> { transport.send(get, 1.0) })
    }

    // The engine's drivers over the sockets

    private val observer = MountTestSky.boulder

    private fun settle(controller: MountController) {
        repeat(600) {
            if (controller.status.phase != MountPhase.SLEWING) return
            controller.poll()
        }
        throw AssertionError("the slew did not end")
    }

    @Test
    fun anLx200MountAnswersOverTcp() {
        val clock = ManualMountClock(MountTestSky.start)
        val model = MountSimulatorModel(clock, MountTestSky.parkPosition)
        val simulator = LX200Simulator(clock, model)
        val server = server { client ->
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val buffer = ByteArray(256)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                simulator.send(buffer.copyOf(count))
                while (true) {
                    val reply = try {
                        simulator.receive(1_024, 0.001)
                    } catch (_: MountError.Timeout) {
                        break
                    }
                    output.write(reply)
                }
            }
        }
        val transport = track(TcpMountTransport("127.0.0.1", server.localPort))
        transport.open()
        val controller = MountController(LX200Driver(transport, clock), observer, MountSettings(altitudeLimit = 5.0), clock)
        val capabilities = controller.connect()
        assertEquals("On-Step", capabilities.name)
        controller.goto(MountTestSky.andromeda)
        settle(controller)
        assertIs<MountSlewOutcome.Arrived>(controller.status.outcome)
        assertEquals(1, model.slewsStarted)
        controller.abort()
        controller.disconnect()
    }

    @Test
    fun anAlpacaServerAnswersOverHttp() {
        val clock = ManualMountClock(MountTestSky.start)
        val model = MountSimulatorModel(clock, MountTestSky.parkPosition)
        val simulator = AlpacaSimulator(clock, model, equatorialSystem = 1)
        val server = httpServer { head, body, out ->
            val line = head.lineSequence().first().split(' ')
            val method = AlpacaMethod.valueOf(line[0])
            val path = line[1].substringBefore('?')
            fun fields(text: String) = text.split('&').filter { it.isNotEmpty() }.map {
                AlpacaField(URLDecoder.decode(it.substringBefore('='), "UTF-8"), URLDecoder.decode(it.substringAfter('=', ""), "UTF-8"))
            }
            val query = fields(line[1].substringAfter('?', ""))
            val form = fields(body)
            val transaction = (query + form).first { it.name == "ClientTransactionID" }.value.toInt()
            val response = synchronized(simulator) { simulator.send(AlpacaHTTPRequest(method, path, query, form, transaction), 10.0) }
            val bytes = response.body.toByteArray()
            out.write("HTTP/1.1 ${response.statusCode} X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        val transport = http(server)
        val controller = MountController(AlpacaDriver(transport), observer, MountSettings(altitudeLimit = 5.0), clock)
        val capabilities = controller.connect()
        assertEquals("Simulated Alpaca Mount", capabilities.name)
        controller.goto(MountTestSky.andromeda)
        settle(controller)
        assertIs<MountSlewOutcome.Arrived>(controller.status.outcome)
        controller.sync(MountTestSky.andromeda)
        assertEquals(1, model.syncsPerformed)
    }

    // Discovery

    @Test
    fun alpacaDiscoveryFindsTheTelescopesOfAnAnsweringServer() {
        val http = httpServer { _, _, out ->
            val json = """{"Value":[{"DeviceName":"Test Mount","DeviceType":"Telescope","DeviceNumber":2,"UniqueID":"abc"},""" +
                """{"DeviceName":"Cam","DeviceType":"Camera","DeviceNumber":0,"UniqueID":"def"}],"ClientTransactionID":1,"ServerTransactionID":1,"ErrorNumber":0,"ErrorMessage":""}"""
            val bytes = json.toByteArray()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
            out.write(bytes)
        }
        val udp = udpEcho { request ->
            if (AlpacaDiscovery.isRequest(request)) listOf(AlpacaDiscovery.responsePayload(http.localPort)) else emptyList()
        }
        val discovery = MountAlpacaDiscovery(
            broadcastAddresses = { listOf(loopback) },
            discoveryPort = udp.localPort,
            listenMillis = 600,
        )
        val found = runBlocking { discovery.discover() }
        assertEquals(1, found.size, "$found")
        assertEquals(MountFoundTelescope("127.0.0.1", http.localPort, "Test Mount", 2, "abc"), found.single())
    }

    @Test
    fun alpacaDiscoveryFindsNothingWhenNobodyAnswers() {
        val udp = udpEcho { emptyList() }
        val discovery = MountAlpacaDiscovery(broadcastAddresses = { listOf(loopback) }, discoveryPort = udp.localPort, listenMillis = 300)
        assertEquals(emptyList(), runBlocking { discovery.discover() })
    }

    @Test
    fun theBroadcastAddressesAlwaysEndWithTheLimitedBroadcast() {
        val addresses = MountBroadcast.addresses(null)
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), addresses.last().address)
    }
}

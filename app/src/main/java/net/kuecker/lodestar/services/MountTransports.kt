package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.mount.AlpacaEndpoint
import net.kuecker.lodestar.kit.mount.AlpacaHTTPRequest
import net.kuecker.lodestar.kit.mount.AlpacaHTTPResponse
import net.kuecker.lodestar.kit.mount.AlpacaMethod
import net.kuecker.lodestar.kit.mount.AlpacaTransport
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountTransport
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

// The three ways Lodestar talks to a mount: a TCP socket and a "connected" UDP socket for the byte-stream protocols
// (LX200, NexStar/SynScan, and the Sky-Watcher Wi-Fi protocol), and HTTP for ASCOM Alpaca. Every call blocks, so the
// service runs them on a worker thread; every wait has a timeout, and close() from another thread unblocks any call
// at once (that is how Cancel works).
//
// HTTP is written on a plain socket on purpose. Alpaca servers on a local network speak cleartext http://, and
// Android refuses cleartext through its URL classes (HttpURLConnection, WebView and the libraries on top of them) for
// apps that target API 28 or later unless a network security config allows it. That config can name hosts but not
// address ranges, so it cannot cover a user-typed 192.168.x.x address without opening cleartext traffic for a long
// list of hosts. Raw sockets are not subject to that policy, so nothing is opened up: the app's own web traffic
// (map tiles, place search) stays HTTPS-only, and this client only ever talks to the address the user entered.

/** Where sockets come from: the system default, or the Wi-Fi network when the phone is joined to a mount's access point. */
interface MountSocketProvider {
    /** A new unconnected TCP socket. */
    fun createTcpSocket(): Socket

    /** Makes [socket] (not yet connected) send its datagrams through the right network. */
    fun bind(socket: DatagramSocket)

    /** The address of [host] (a name or a numeric address). */
    @Throws(UnknownHostException::class)
    fun resolve(host: String): InetAddress

    /** The name of the network interface the sockets use, if known (to find its broadcast address). */
    val interfaceName: String? get() = null

    /** The system's default network. */
    object System : MountSocketProvider {
        override fun createTcpSocket(): Socket = Socket()

        override fun bind(socket: DatagramSocket) = Unit

        override fun resolve(host: String): InetAddress = InetAddress.getByName(host)
    }
}

/** Words for socket failures that a person can act on. */
internal object MountNetworkErrors {
    private const val MAXIMUM_LENGTH = 120

    /** A short description of [error] to put after "Could not talk to the mount: ". */
    fun describe(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "the connection timed out"
        is ConnectException -> if (error.message.orEmpty().contains("refused", ignoreCase = true)) {
            "the mount refused the connection (is the port right?)"
        } else {
            "the mount could not be reached"
        }
        is NoRouteToHostException -> "no route to the mount (is the phone on its Wi-Fi?)"
        is UnknownHostException -> "unknown host name"
        is PortUnreachableException -> "nothing is listening on that port"
        is SocketException -> socketMessage(error)
        else -> sanitized(error.message ?: "network error")
    }

    private fun socketMessage(error: SocketException): String {
        val text = error.message.orEmpty()
        return when {
            text.contains("unreachable", ignoreCase = true) -> "the network is unreachable (is the phone on the mount's Wi-Fi?)"
            text.contains("reset", ignoreCase = true) -> "the mount reset the connection"
            text.contains("closed", ignoreCase = true) -> "the connection was closed"
            text.contains("broken pipe", ignoreCase = true) -> "the connection was closed"
            else -> sanitized(text.ifEmpty { "network error" })
        }
    }

    /** [text] with control characters replaced and its length capped. */
    fun sanitized(text: String): String {
        val cleaned = buildString {
            for (ch in text) append(if (ch.code < 0x20 || ch.code == 0x7F) ' ' else ch)
        }.trim()
        return if (cleaned.length <= MAXIMUM_LENGTH) cleaned else cleaned.substring(0, MAXIMUM_LENGTH - 1) + "…"
    }

    fun transport(error: Throwable): MountError = MountError.Transport(describe(error))
}

private fun millis(seconds: Double, minimum: Int = 1): Int {
    if (!seconds.isFinite()) return minimum
    return ceil(seconds * 1000).coerceIn(minimum.toDouble(), 3_600_000.0).toInt()
}

/**
 * A TCP connection to an LX200 or NexStar Wi-Fi bridge. Call [open] first (it connects, with a timeout); [close] may
 * be called from any thread and makes a blocked call fail.
 */
class TcpMountTransport(
    private val host: String,
    private val port: Int,
    private val sockets: MountSocketProvider = MountSocketProvider.System,
    private val connectTimeoutSeconds: Double = 4.0,
) : MountTransport, Closeable {
    private val socketRef = AtomicReference<Socket?>(null)
    private val closed = AtomicBoolean(false)
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Connects to the mount. */
    @Throws(MountError::class)
    fun open() {
        if (closed.get()) throw MountError.Transport("the connection was cancelled")
        if (port !in 1..65_535) throw MountError.InvalidInput("the port must be from 1 to 65535")
        try {
            val socket = sockets.createTcpSocket()
            socketRef.set(socket)
            if (closed.get()) {
                socket.close()
                throw MountError.Transport("the connection was cancelled")
            }
            val address = sockets.resolve(host)
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(address, port), millis(connectTimeoutSeconds))
            input = socket.getInputStream()
            output = socket.getOutputStream()
        } catch (error: SocketTimeoutException) {
            close()
            throw MountError.Timeout("connect")
        } catch (error: MountError) {
            throw error
        } catch (error: IOException) {
            close()
            throw MountNetworkErrors.transport(error)
        }
    }

    @Throws(MountError::class)
    override fun send(bytes: ByteArray) {
        val stream = output ?: throw MountError.NotConnected
        try {
            stream.write(bytes)
            stream.flush()
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
    }

    @Throws(MountError::class)
    override fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray {
        val socket = socketRef.get()
        val stream = input
        if (socket == null || stream == null) throw MountError.NotConnected
        val limit = maxCount.coerceIn(1, MAXIMUM_READ)
        val buffer = ByteArray(limit)
        val count = try {
            socket.soTimeout = millis(timeoutSeconds)
            stream.read(buffer, 0, limit)
        } catch (error: SocketTimeoutException) {
            throw MountError.Timeout("waiting for the mount")
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
        if (count < 0) throw MountError.Transport("the mount closed the connection")
        return buffer.copyOf(count)
    }

    @Throws(MountError::class)
    override fun discardPendingInput() {
        val stream = input ?: return
        try {
            var rounds = 0
            while (rounds < MAXIMUM_DISCARD_ROUNDS) {
                val waiting = stream.available()
                if (waiting <= 0) break
                stream.skip(waiting.toLong())
                rounds++
            }
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
    }

    /** Closes the connection; safe to call more than once and from any thread. */
    override fun close() {
        closed.set(true)
        try {
            socketRef.get()?.close()
        } catch (_: IOException) {
            // Closing a dead socket has nothing left to report.
        }
    }

    private companion object {
        const val MAXIMUM_READ = 65_536
        const val MAXIMUM_DISCARD_ROUNDS = 64
    }
}

/**
 * A UDP socket "connected" to the mount: one datagram per [send], replies as datagrams (the SynScan Wi-Fi adapter and
 * the Sky-Watcher Wi-Fi mounts). Call [open] first.
 */
class UdpMountTransport(
    private val host: String,
    private val port: Int,
    private val sockets: MountSocketProvider = MountSocketProvider.System,
) : MountTransport, Closeable {
    private val socketRef = AtomicReference<DatagramSocket?>(null)
    private val closed = AtomicBoolean(false)
    private var leftover = ByteArray(0)
    private val datagram = ByteArray(MAXIMUM_DATAGRAM)

    /** Creates the socket and connects it to the mount's address. */
    @Throws(MountError::class)
    fun open() {
        if (closed.get()) throw MountError.Transport("the connection was cancelled")
        if (port !in 1..65_535) throw MountError.InvalidInput("the port must be from 1 to 65535")
        try {
            val socket = DatagramSocket(null)
            socketRef.set(socket)
            if (closed.get()) {
                socket.close()
                throw MountError.Transport("the connection was cancelled")
            }
            sockets.bind(socket)
            socket.connect(InetSocketAddress(sockets.resolve(host), port))
        } catch (error: MountError) {
            throw error
        } catch (error: IOException) {
            close()
            throw MountNetworkErrors.transport(error)
        }
    }

    @Throws(MountError::class)
    override fun send(bytes: ByteArray) {
        val socket = socketRef.get() ?: throw MountError.NotConnected
        try {
            socket.send(DatagramPacket(bytes, bytes.size))
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
    }

    @Throws(MountError::class)
    override fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray {
        val socket = socketRef.get() ?: throw MountError.NotConnected
        val limit = maxCount.coerceAtLeast(1)
        if (leftover.isNotEmpty()) return take(limit)
        try {
            socket.soTimeout = millis(timeoutSeconds)
            val packet = DatagramPacket(datagram, datagram.size)
            socket.receive(packet)
            leftover = datagram.copyOf(packet.length)
        } catch (error: SocketTimeoutException) {
            throw MountError.Timeout("waiting for the mount")
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
        if (leftover.isEmpty()) throw MountError.Timeout("waiting for the mount")
        return take(limit)
    }

    @Throws(MountError::class)
    override fun discardPendingInput() {
        leftover = ByteArray(0)
        val socket = socketRef.get() ?: return
        try {
            socket.soTimeout = 1
            var rounds = 0
            while (rounds < MAXIMUM_DISCARD_ROUNDS) {
                socket.receive(DatagramPacket(datagram, datagram.size))
                rounds++
            }
        } catch (_: SocketTimeoutException) {
            // Nothing more waiting: the socket is clean.
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
    }

    /** Closes the socket; safe to call more than once and from any thread. */
    override fun close() {
        closed.set(true)
        socketRef.get()?.close()
    }

    private fun take(limit: Int): ByteArray {
        val count = minOf(limit, leftover.size)
        val result = leftover.copyOfRange(0, count)
        leftover = leftover.copyOfRange(count, leftover.size)
        return result
    }

    private companion object {
        const val MAXIMUM_DATAGRAM = 2_048
        const val MAXIMUM_DISCARD_ROUNDS = 64
    }
}

/**
 * An HTTP/1.1 client for one Alpaca server. Each request uses a new connection (`Connection: close`), which keeps it
 * simple and immune to stale keep-alive connections, and costs a few milliseconds on a local network. Bodies are read
 * by `Content-Length`, chunked encoding or until the server closes; they are capped at one megabyte.
 */
class AlpacaHttpTransport(
    private val endpoint: AlpacaEndpoint,
    private val sockets: MountSocketProvider = MountSocketProvider.System,
    private val connectTimeoutSeconds: Double = 4.0,
) : AlpacaTransport, Closeable {
    private val inFlight = AtomicReference<Socket?>(null)
    private val closed = AtomicBoolean(false)

    @Throws(MountError::class)
    override fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse {
        if (closed.get()) throw MountError.Transport("the connection was cancelled")
        val deadline = System.nanoTime() + (timeoutSeconds.coerceIn(0.1, 120.0) * 1e9).toLong()
        val socket = try {
            sockets.createTcpSocket()
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        }
        inFlight.set(socket)
        try {
            if (closed.get()) throw MountError.Transport("the connection was cancelled")
            val connectBudget = minOf(millis(connectTimeoutSeconds), remainingMillis(deadline, "connect"))
            socket.connect(InetSocketAddress(sockets.resolve(endpoint.host), endpoint.port), connectBudget)
            socket.tcpNoDelay = true
            socket.getOutputStream().apply {
                write(HttpWire.requestBytes(request, endpoint))
                flush()
            }
            return HttpWire.readResponse(socket, deadline)
        } catch (error: SocketTimeoutException) {
            throw MountError.Timeout("HTTP request")
        } catch (error: MountError) {
            throw error
        } catch (error: IOException) {
            throw MountNetworkErrors.transport(error)
        } finally {
            inFlight.compareAndSet(socket, null)
            try {
                socket.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }

    /** Cancels a request in progress and refuses new ones; safe from any thread. */
    override fun close() {
        closed.set(true)
        try {
            inFlight.get()?.close()
        } catch (_: IOException) {
            // Already closed.
        }
    }

    private fun remainingMillis(deadline: Long, operation: String): Int {
        val remaining = (deadline - System.nanoTime()) / 1_000_000
        if (remaining <= 0) throw MountError.Timeout(operation)
        return remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** The bytes of an HTTP/1.1 exchange. */
internal object HttpWire {
    const val MAXIMUM_BODY = 1_048_576
    private const val MAXIMUM_HEADER = 16_384
    private const val CR = '\r'.code
    private const val LF = '\n'.code

    fun requestBytes(request: AlpacaHTTPRequest, endpoint: AlpacaEndpoint): ByteArray {
        val body = if (request.method == AlpacaMethod.PUT) request.body.toByteArray(Charsets.UTF_8) else ByteArray(0)
        val hostHeader = if (endpoint.host.contains(':')) "[${endpoint.host}]:${endpoint.port}" else "${endpoint.host}:${endpoint.port}"
        val head = buildString {
            append(request.method.name).append(' ').append(request.target).append(" HTTP/1.1\r\n")
            append("Host: ").append(hostHeader).append("\r\n")
            append("User-Agent: Lodestar\r\n")
            append("Accept: application/json\r\n")
            append("Connection: close\r\n")
            if (request.method == AlpacaMethod.PUT) {
                append("Content-Type: ").append(request.contentType ?: "application/x-www-form-urlencoded").append("\r\n")
                append("Content-Length: ").append(body.size).append("\r\n")
            }
            append("\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** Reads one response from [socket] before [deadline] (a `System.nanoTime()` value). */
    fun readResponse(socket: Socket, deadline: Long): AlpacaHTTPResponse {
        val reader = Reader(socket, deadline)
        val statusLine = reader.line(MAXIMUM_HEADER)
        val parts = statusLine.split(' ', limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull()
        if (!statusLine.startsWith("HTTP/") || status == null || status !in 100..599) {
            throw MountError.MalformedReply("not an HTTP reply")
        }
        var length: Long? = null
        var chunked = false
        var headerBytes = statusLine.length
        while (true) {
            val line = reader.line(MAXIMUM_HEADER)
            if (line.isEmpty()) break
            headerBytes += line.length
            if (headerBytes > MAXIMUM_HEADER) throw MountError.MalformedReply("HTTP header too long")
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "content-length") length = value.toLongOrNull()
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }
        val body = when {
            status in 100..199 || status == 204 || status == 304 -> ByteArray(0)
            chunked -> reader.chunkedBody()
            length != null -> reader.exactly(length)
            else -> reader.untilClosed()
        }
        return AlpacaHTTPResponse(status, String(body, Charsets.UTF_8))
    }

    private class Reader(private val socket: Socket, private val deadline: Long) {
        private val input = socket.getInputStream()
        private val buffer = ByteArray(2_048)
        private var start = 0
        private var end = 0

        private fun fill(): Boolean {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) throw MountError.Timeout("HTTP reply")
            socket.soTimeout = remaining.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val count = input.read(buffer, 0, buffer.size)
            if (count < 0) return false
            start = 0
            end = count
            return true
        }

        private fun next(): Int {
            if (start == end && !fill()) return -1
            return buffer[start++].toInt() and 0xFF
        }

        /** One line without its CR LF; fails when it is longer than [limit] or the connection ends first. */
        fun line(limit: Int): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = next()
                if (value < 0) throw MountError.MalformedReply("the reply ended early")
                if (value == LF) break
                if (value != CR) bytes.write(value)
                if (bytes.size() > limit) throw MountError.MalformedReply("HTTP line too long")
            }
            return String(bytes.toByteArray(), Charsets.ISO_8859_1)
        }

        fun exactly(count: Long): ByteArray {
            if (count < 0 || count > MAXIMUM_BODY) throw MountError.MalformedReply("HTTP body too large")
            val result = ByteArray(count.toInt())
            for (index in result.indices) {
                val value = next()
                if (value < 0) throw MountError.MalformedReply("the reply ended early")
                result[index] = value.toByte()
            }
            return result
        }

        fun untilClosed(): ByteArray {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = next()
                if (value < 0) break
                bytes.write(value)
                if (bytes.size() > MAXIMUM_BODY) throw MountError.MalformedReply("HTTP body too large")
            }
            return bytes.toByteArray()
        }

        fun chunkedBody(): ByteArray {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val sizeText = line(MAXIMUM_HEADER).substringBefore(';').trim()
                val size = sizeText.toLongOrNull(16) ?: throw MountError.MalformedReply("bad chunk size")
                if (size < 0) throw MountError.MalformedReply("bad chunk size")
                if (size == 0L) break
                if (bytes.size() + size > MAXIMUM_BODY) throw MountError.MalformedReply("HTTP body too large")
                bytes.write(exactly(size))
                if (line(MAXIMUM_HEADER).isNotEmpty()) throw MountError.MalformedReply("bad chunk end")
            }
            // Trailer headers, if any, end with an empty line.
            var trailer = line(MAXIMUM_HEADER)
            while (trailer.isNotEmpty()) trailer = line(MAXIMUM_HEADER)
            return bytes.toByteArray()
        }
    }
}

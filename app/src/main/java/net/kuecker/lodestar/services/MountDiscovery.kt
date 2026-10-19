package net.kuecker.lodestar.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.mount.AlpacaDiscovery
import net.kuecker.lodestar.kit.mount.AlpacaEndpoint
import net.kuecker.lodestar.kit.mount.AlpacaRequestBuilder
import net.kuecker.lodestar.kit.mount.AlpacaResponseParser
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.SkyWatcherDiscovery
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A telescope offered by an Alpaca server on the local network. */
data class MountFoundTelescope(
    val host: String,
    val port: Int,
    val deviceName: String,
    val deviceNumber: Int,
    val uniqueID: String,
)

/**
 * Finds Alpaca servers with the discovery broadcast (a UDP datagram "alpacadiscovery1" to port 32227 of every local
 * broadcast address; each server answers with its HTTP port), then asks each one which telescopes it has.
 *
 * Everything runs on the IO dispatcher and ends when the calling coroutine is cancelled.
 */
class MountAlpacaDiscovery(
    private val sockets: MountSocketProvider = MountSocketProvider.System,
    private val broadcastAddresses: (String?) -> List<InetAddress> = { MountBroadcast.addresses(it) },
    private val discoveryPort: Int = AlpacaDiscovery.port,
    private val listenMillis: Int = 2_000,
    private val deviceTimeoutSeconds: Double = 3.0,
) {
    /** The telescopes found, ordered by address; empty when nothing answered. Never throws except for cancellation. */
    suspend fun discover(): List<MountFoundTelescope> = withContext(Dispatchers.IO) {
        val servers = try {
            listen()
        } catch (_: IOException) {
            emptyList()
        }
        coroutineScope {
            servers.map { server -> async { telescopes(server) } }.awaitAll().flatten()
        }.sortedWith(compareBy({ it.host }, { it.port }, { it.deviceNumber }))
    }

    private suspend fun listen(): List<AlpacaEndpoint> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            sockets.bind(socket)
            socket.bind(InetSocketAddress(0))
            socket.broadcast = true
            val payload = AlpacaDiscovery.requestPayload
            for (address in broadcastAddresses(sockets.interfaceName).take(MAXIMUM_TARGETS)) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, discoveryPort))
                } catch (_: IOException) {
                    // One unreachable interface must not stop the others.
                }
            }
            val found = LinkedHashSet<AlpacaEndpoint>()
            val buffer = ByteArray(1_024)
            val end = System.nanoTime() + listenMillis * 1_000_000L
            while (found.size < MAXIMUM_SERVERS) {
                currentCoroutineContext().ensureActive()
                val remaining = (end - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                socket.soTimeout = minOf(remaining, SLICE_MILLIS).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address?.hostAddress ?: continue
                val port = try {
                    AlpacaDiscovery.parseResponse(packet.data.copyOf(packet.length))
                } catch (_: MountError) {
                    continue
                }
                AlpacaEndpoint.validated(host, port)?.let(found::add)
            }
            return found.toList()
        } finally {
            socket.close()
        }
    }

    private fun telescopes(server: AlpacaEndpoint): List<MountFoundTelescope> {
        val transport = AlpacaHttpTransport(server, sockets)
        return try {
            val request = AlpacaRequestBuilder(deviceNumber = 0, clientID = CLIENT_ID).configuredDevices()
            val response = transport.send(request, deviceTimeoutSeconds)
            AlpacaResponseParser.configuredDevices(response, request)
                .filter { it.isTelescope }
                .map { MountFoundTelescope(server.host, server.port, it.deviceName, it.deviceNumber, it.uniqueID) }
        } catch (_: MountError) {
            emptyList()
        } finally {
            transport.close()
        }
    }

    private companion object {
        const val CLIENT_ID = 4711
        const val SLICE_MILLIS = 250L
        const val MAXIMUM_SERVERS = 16
        const val MAXIMUM_TARGETS = 16
    }
}

/**
 * Finds Sky-Watcher Wi-Fi mounts: sends the version probe (`:e1`) to the mount's access point address and, when the phone is
 * on another network, to that network's broadcast address, and collects the replies for about a second and a half. A mount
 * in access point mode answers at 192.168.4.1; one that joined the home network answers the broadcast.
 */
class MountSkyWatcherProbe(
    private val sockets: MountSocketProvider = MountSocketProvider.System,
    private val targets: () -> List<InetAddress> = { defaultTargets(sockets) },
    private val port: Int = SkyWatcherDiscovery.port,
    private val listenMillis: Int = 1_500,
) {
    /** The mounts that answered, ordered by address; empty when none did. Never throws except for cancellation. */
    suspend fun discover(): List<MountFoundTelescope> = withContext(Dispatchers.IO) {
        try {
            listen()
        } catch (_: IOException) {
            emptyList()
        }
    }

    private suspend fun listen(): List<MountFoundTelescope> {
        val socket = DatagramSocket(null)
        try {
            socket.reuseAddress = true
            sockets.bind(socket)
            socket.bind(InetSocketAddress(0))
            socket.broadcast = true
            val payload = SkyWatcherDiscovery.probePayload
            for (address in targets().take(MAXIMUM_TARGETS)) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, address, port))
                } catch (_: IOException) {
                    // One unreachable address must not stop the others.
                }
            }
            val found = LinkedHashMap<String, MountFoundTelescope>()
            val buffer = ByteArray(64)
            val end = System.nanoTime() + listenMillis * 1_000_000L
            while (found.size < MAXIMUM_MOUNTS) {
                currentCoroutineContext().ensureActive()
                val remaining = (end - System.nanoTime()) / 1_000_000
                if (remaining <= 0) break
                socket.soTimeout = minOf(remaining, SLICE_MILLIS).toInt().coerceAtLeast(1)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val host = packet.address?.hostAddress ?: continue
                val version = try {
                    SkyWatcherDiscovery.parseProbeReply(packet.data.copyOf(packet.length))
                } catch (_: MountError) {
                    continue
                }
                found.getOrPut(host) {
                    MountFoundTelescope(host, port, "Sky-Watcher ${version.mountName} ${version.description}", 0, "")
                }
            }
            return found.values.sortedBy { it.host }
        } finally {
            socket.close()
        }
    }

    companion object {
        private const val SLICE_MILLIS = 250L
        private const val MAXIMUM_MOUNTS = 8
        private const val MAXIMUM_TARGETS = 4

        /** The addresses worth probing from the phone's address on its Wi-Fi network (just the access point if that is unknown). */
        fun defaultTargets(sockets: MountSocketProvider): List<InetAddress> {
            val local = sockets.localAddress
            val names = if (local == null) {
                listOf(SkyWatcherDiscovery.accessPointAddress)
            } else {
                SkyWatcherDiscovery.probeTargets(local.first, local.second)
            }
            return names.mapNotNull { name -> if (name.isEmpty()) null else runCatching { InetAddress.getByName(name) }.getOrNull() }
        }
    }
}

/** The broadcast addresses of the phone's IPv4 networks. */
object MountBroadcast {
    /**
     * The directed broadcast address of every IPv4 network that is up (those of [interfaceName] first, when that is
     * known) followed by the limited broadcast address 255.255.255.255.
     */
    fun addresses(interfaceName: String?): List<InetAddress> {
        val preferred = ArrayList<InetAddress>()
        val others = ArrayList<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                for (entry in network.interfaceAddresses) {
                    val broadcast = entry.broadcast ?: continue
                    (if (network.name == interfaceName) preferred else others).add(broadcast)
                }
            }
        } catch (_: IOException) {
            // Without the interface list only the limited broadcast below remains.
        }
        val limited = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        return (preferred + others + limited).filterIsInstance<Inet4Address>().distinct()
    }
}

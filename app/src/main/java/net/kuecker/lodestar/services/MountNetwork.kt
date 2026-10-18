package net.kuecker.lodestar.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Sends the mount's traffic over the phone's Wi-Fi even when Android would prefer mobile data. A mount's own access
 * point has no internet, so Android keeps mobile data as the default network and packets to 192.168.x.x would leave
 * over the cellular link and never arrive. Sockets made by the Wi-Fi [Network] always use it.
 *
 * The callback only watches networks (it needs no permission beyond `ACCESS_NETWORK_STATE`); while there is no Wi-Fi
 * network the system default is used.
 */
class MountWifiNetwork(context: Context) : MountSocketProvider {
    private val manager: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

    @Volatile
    private var wifi: Network? = null

    @Volatile
    private var interfaceNameValue: String? = null

    private var isWatching = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifi = network
            interfaceNameValue = manager?.getLinkProperties(network)?.interfaceName
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (network == wifi) interfaceNameValue = linkProperties.interfaceName
        }

        override fun onLost(network: Network) {
            if (network == wifi) {
                wifi = null
                interfaceNameValue = null
            }
        }
    }

    /** Starts watching for the Wi-Fi network. Call from any thread, once. */
    @Synchronized
    fun start() {
        if (isWatching || manager == null) return
        // A request asks for internet by default; a mount's access point may not offer it.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            isWatching = true
        } catch (_: SecurityException) {
            // Without network-state access the default network is used.
        } catch (_: RuntimeException) {
            // The system limits the number of callbacks an app may register; the default network still works.
        }
    }

    /** Stops watching. */
    @Synchronized
    fun stop() {
        if (!isWatching || manager == null) return
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        isWatching = false
        wifi = null
    }

    override val interfaceName: String? get() = interfaceNameValue

    override fun createTcpSocket(): Socket = wifi?.socketFactory?.createSocket() ?: Socket()

    override fun bind(socket: DatagramSocket) {
        val network = wifi ?: return
        try {
            network.bindSocket(socket)
        } catch (_: IOException) {
            // The network went away between the lookup and the bind; the socket then fails when it is used.
        }
    }

    @Throws(UnknownHostException::class)
    override fun resolve(host: String): InetAddress {
        val network = wifi ?: return InetAddress.getByName(host)
        return try {
            network.getByName(host)
        } catch (error: UnknownHostException) {
            InetAddress.getByName(host)
        }
    }
}

package net.kuecker.lodestar.kit.mount

/**
 * Finding a Sky-Watcher Wi-Fi mount: the access point address of a mount that makes its own network, and a UDP probe for mounts
 * that joined the home network. The engine does no networking: the apps send [probePayload] as a UDP datagram to port [port] of
 * [accessPointAddress] (or of the subnet's broadcast address, see [broadcastAddress]) and hand every reply to [parseProbeReply].
 *
 * Reference: "Motor Controller Command Set", section "Wi-Fi Connection": the module runs a UDP server on port 11880; in access
 * point mode its address is 192.168.4.1; commands and replies each fit in one datagram.
 */
object SkyWatcherDiscovery {
    /** The UDP port of the mount's motor controller. */
    const val port: Int = 11880

    /** The mount's address when it runs its own access point. */
    const val accessPointAddress: String = "192.168.4.1"

    /** The datagram to send: `:e1` and a carriage return (inquire the motor board version of channel 1). */
    val probePayload: ByteArray get() = SkyWatcherCodec.inquire(SkyWatcherCommand.INQUIRE_VERSION, SkyWatcherAxis.AXIS1)

    /** True if [bytes] is the probe datagram (for simulators that answer it). */
    fun isProbe(bytes: ByteArray): Boolean = bytes.contentEquals(probePayload)

    /**
     * Decodes a reply datagram to the probe.
     *
     * @throws MountError.MalformedReply if it is not a motor board version reply.
     */
    @Throws(MountError::class)
    fun parseProbeReply(datagram: ByteArray): SkyWatcherVersion {
        return when (val reply = SkyWatcherCodec.parseDatagram(datagram)) {
            is SkyWatcherReply.Success -> SkyWatcherCodec.decodeVersion(reply.payload)
            is SkyWatcherReply.Failure -> throw MountError.MalformedReply("the device answered with error ${reply.code}")
        }
    }

    /** The directed broadcast address of the subnet of [address] (dotted IPv4) with [prefixLength] network bits (8 to 30), or null. */
    fun broadcastAddress(address: String, prefixLength: Int): String? {
        val octets = parseIPv4(address) ?: return null
        if (prefixLength < 8 || prefixLength > 30) return null
        val value = (octets[0] shl 24) or (octets[1] shl 16) or (octets[2] shl 8) or octets[3]
        val hostMask = (1 shl (32 - prefixLength)) - 1
        val broadcast = value or hostMask
        return "${(broadcast ushr 24) and 255}.${(broadcast ushr 16) and 255}.${(broadcast ushr 8) and 255}.${broadcast and 255}"
    }

    /**
     * The addresses to probe, best first: the access point address (when the phone is on the mount's own network, [address] is in
     * 192.168.4.0/24, only that), otherwise the subnet's broadcast address; empty when [address] is not a valid address.
     */
    fun probeTargets(address: String, prefixLength: Int): List<String> {
        val octets = parseIPv4(address) ?: return emptyList()
        if (octets[0] == 192 && octets[1] == 168 && octets[2] == 4) return listOf(accessPointAddress)
        val broadcast = broadcastAddress(address, prefixLength) ?: return listOf(accessPointAddress)
        return listOf(broadcast, accessPointAddress)
    }

    private fun parseIPv4(text: String): IntArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return null
            var value = 0
            for (character in part) {
                if (character !in '0'..'9') return null
                value = value * 10 + (character - '0')
            }
            if (value > 255) return null
            result[index] = value
        }
        return result
    }
}

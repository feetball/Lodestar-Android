package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.mount.AlpacaConfiguration
import net.kuecker.lodestar.kit.mount.AlpacaDriver
import net.kuecker.lodestar.kit.mount.AlpacaEndpoint
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.mount.AlpacaTransport
import net.kuecker.lodestar.kit.mount.LX200Configuration
import net.kuecker.lodestar.kit.mount.LX200Driver
import net.kuecker.lodestar.kit.mount.MountClock
import net.kuecker.lodestar.kit.mount.MountDriver
import net.kuecker.lodestar.kit.mount.MountEpoch
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.MountTransport
import net.kuecker.lodestar.kit.mount.NexStarConfiguration
import net.kuecker.lodestar.kit.mount.NexStarDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherConfiguration
import net.kuecker.lodestar.kit.mount.SkyWatcherDiscovery
import net.kuecker.lodestar.kit.mount.SkyWatcherDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode

/** How the phone reaches the mount. */
enum class MountLinkKind(val rawValue: String, val title: String) {
    TCP("tcp", "TCP"),
    UDP("udp", "UDP"),
    HTTP("http", "HTTP");

    companion object {
        fun fromRawValue(raw: String?): MountLinkKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** An address to connect to. */
data class MountEndpoint(val host: String, val port: Int)

/** Everything needed to open a session with a mount. */
data class MountConnection(
    val protocol: MountProtocol,
    val link: MountLinkKind,
    val host: String,
    val port: Int,
    /** The Alpaca device number of the telescope (0 for the others). */
    val deviceNumber: Int = 0,
    /** The Alpaca telescope's name, to show in the form. */
    val deviceName: String? = null,
)

/** Options that few people need: only shown in the Advanced section, and only those the protocol has. */
data class MountAdvanced(
    /** The coordinate frame to send; null lets the driver pick the frame its protocol expects. */
    val epoch: MountEpoch? = null,
    /** Reverses east and west for fast moves (for a mount that turns the other way). */
    val swapEastWest: Boolean = false,
    /** Reverses north and south for fast moves. */
    val swapNorthSouth: Boolean = false,
    /** Sky-Watcher Wi-Fi: how the mount is set up, for mounts that can be either; null takes it from the mount model. */
    val skyWatcherMode: SkyWatcherMountMode? = null,
    /** Sky-Watcher Wi-Fi: the first axis counts the other way round. */
    val reverseAxis1: Boolean = false,
    /** Sky-Watcher Wi-Fi: the second axis counts the other way round. */
    val reverseAxis2: Boolean = false,
)

/** Which advanced options a protocol offers. */
enum class MountAdvancedField { EPOCH, SWAP_EAST_WEST, SWAP_NORTH_SOUTH, ARRANGEMENT, REVERSE_AXIS_1, REVERSE_AXIS_2 }

/** The ways the form can look for mounts on the network, with the words for its button and for finding nothing. */
enum class MountDiscoveryKind(val buttonTitle: String, val emptyText: String) {
    /** ASCOM Alpaca servers answering the discovery broadcast. */
    ALPACA(
        "Find Alpaca devices",
        "No Alpaca telescope answered. Check that the server is running on this Wi-Fi network, or type its address and port.",
    ),

    /** Sky-Watcher Wi-Fi mounts answering a probe datagram. */
    SKYWATCHER(
        "Find mount",
        "No Sky-Watcher mount answered. Check that the mount is switched on and the phone is on its Wi-Fi network, " +
            "or type its address.",
    ),
}

/** What a protocol's driver is built from: the connection options, the clock and the opened transport. */
class MountDriverContext(
    val connection: MountConnection,
    val advanced: MountAdvanced,
    val clock: MountClock,
    /** Where the phone is: mounts that work out the sky themselves (Sky-Watcher Wi-Fi) need the site. */
    val location: GeographicLocation,
    /** The byte stream for [MountLinkKind.TCP] and [MountLinkKind.UDP] links. */
    val stream: MountTransport?,
    /** The HTTP client for [MountLinkKind.HTTP] links. */
    val http: AlpacaTransport?,
) {
    fun requireStream(): MountTransport = stream ?: throw MountError.InvalidInput("this protocol needs a TCP or UDP connection")

    fun requireHttp(): AlpacaTransport = http ?: throw MountError.InvalidInput("this protocol needs an HTTP connection")
}

/**
 * One kind of mount in the connection form: the texts, the connection types and their default addresses, and how to
 * build the engine's driver. [MountProtocols.all] lists them and the form shows what is listed, so supporting another
 * protocol of the engine means adding one entry there.
 */
class MountProtocolOption(
    val protocol: MountProtocol,
    /** The name in the picker. */
    val title: String,
    /** One line: what it connects to. */
    val summary: String,
    /** What it works with and what the mount needs first. */
    val explanation: String,
    /** The connection types, the first is the default. */
    val links: List<MountLinkKind>,
    /** The default address of each connection type. */
    val defaults: Map<MountLinkKind, MountEndpoint>,
    /** How the form can search the local network for this kind of mount, if it can. */
    val discovery: MountDiscoveryKind? = null,
    val advancedFields: Set<MountAdvancedField> = emptySet(),
    val createDriver: (MountDriverContext) -> MountDriver,
) {
    /** Whether the form offers a search for this kind of mount. */
    val supportsDiscovery: Boolean get() = discovery != null

    init {
        require(links.isNotEmpty() && links.all { it in defaults }) { "every connection type needs a default address" }
    }

    /** The default connection of this protocol. */
    fun defaultConnection(link: MountLinkKind = links.first()): MountConnection {
        val endpoint = defaults.getValue(if (link in links) link else links.first())
        return MountConnection(protocol, if (link in links) link else links.first(), endpoint.host, endpoint.port)
    }
}

/** The protocols the connection form offers, in the order of the picker. */
object MountProtocols {
    private val lx200 = MountProtocolOption(
        protocol = MountProtocol.LX200,
        title = "LX200 / OnStep",
        summary = "OnStep, SkyFi and other LX200-compatible Wi-Fi mounts",
        explanation = "Works with OnStep controllers (its Wi-Fi port is 9999), SkyFi adapters (port 4030) and most " +
            "Wi-Fi bridges that speak the Meade LX200 command set. Join the mount's Wi-Fi first. Turn tracking on " +
            "and set the mount's date, time and site (Lodestar can send them).",
        links = listOf(MountLinkKind.TCP),
        defaults = mapOf(MountLinkKind.TCP to MountEndpoint("192.168.0.1", 9999)),
        advancedFields = setOf(MountAdvancedField.EPOCH),
        createDriver = { context ->
            LX200Driver(context.requireStream(), context.clock, LX200Configuration(epoch = context.advanced.epoch ?: MountEpoch.MEAN_OF_DATE))
        },
    )

    private val skyWatcher = MountProtocolOption(
        protocol = MountProtocol.SKYWATCHER,
        title = SkyWatcherDriver.menuTitle,
        summary = "Sky-Watcher mounts with built-in Wi-Fi, and EQ mounts with the Sky-Watcher Wi-Fi adapter",
        explanation = "Works with the Wi-Fi built into the Sky-Watcher AZ-GTi and AZ-GTe (on their tripod, or on a " +
            "wedge as an equatorial mount), the Star Adventurer GTi, and EQ mounts with the Sky-Watcher Wi-Fi " +
            "adapter. Join the mount's own Wi-Fi network first. These mounts know nothing about the sky: Lodestar " +
            "does the pointing from your location and the phone's clock, and sends the mount nothing but motor " +
            "commands. So switch the mount on at its home position (the pictures show it) and align it on one to " +
            "three stars before the first Go to.",
        links = listOf(MountLinkKind.UDP),
        defaults = mapOf(MountLinkKind.UDP to MountEndpoint(SkyWatcherDiscovery.accessPointAddress, SkyWatcherDiscovery.port)),
        discovery = MountDiscoveryKind.SKYWATCHER,
        advancedFields = setOf(
            MountAdvancedField.ARRANGEMENT, MountAdvancedField.REVERSE_AXIS_1, MountAdvancedField.REVERSE_AXIS_2,
        ),
        createDriver = { context ->
            SkyWatcherDriver(
                context.requireStream(),
                context.clock,
                SkyWatcherConfiguration(
                    location = context.location,
                    mode = context.advanced.skyWatcherMode,
                    invertsAxis1 = context.advanced.reverseAxis1,
                    invertsAxis2 = context.advanced.reverseAxis2,
                ),
            )
        },
    )

    private val nexstar = MountProtocolOption(
        protocol = MountProtocol.NEXSTAR,
        title = "Celestron NexStar / SynScan",
        summary = "NexStar and SynScan hand controllers with a Wi-Fi adapter",
        explanation = "Works with Celestron NexStar hand controls behind a Wi-Fi bridge (TCP, usually port 2000) and " +
            "Sky-Watcher SynScan hand controls with the SynScan Wi-Fi adapter (UDP, usually port 11880). For the " +
            "Wi-Fi built into Star Adventurer GTi and AZ-GTi mounts choose Sky-Watcher Wi-Fi instead. Do the star " +
            "alignment on the hand controller first: it refuses to slew before that.",
        links = listOf(MountLinkKind.UDP, MountLinkKind.TCP),
        defaults = mapOf(
            MountLinkKind.UDP to MountEndpoint("192.168.4.1", 11880),
            MountLinkKind.TCP to MountEndpoint("1.2.3.4", 2000),
        ),
        advancedFields = setOf(MountAdvancedField.EPOCH, MountAdvancedField.SWAP_EAST_WEST),
        createDriver = { context ->
            NexStarDriver(
                context.requireStream(), context.clock,
                NexStarConfiguration(epoch = context.advanced.epoch, invertsEastWest = context.advanced.swapEastWest),
            )
        },
    )

    private val alpaca = MountProtocolOption(
        protocol = MountProtocol.ALPACA,
        title = "ASCOM Alpaca",
        summary = "Mounts shared by an ASCOM Alpaca server on the network",
        explanation = "Works with any telescope an ASCOM Alpaca server offers: ASCOM Remote on a Windows PC, " +
            "ASCOM-based mount drivers, INDI or Raspberry Pi setups with an Alpaca bridge, and simulators. Phone " +
            "and server must be on the same network. Use Find Alpaca devices, or type the address and port " +
            "(ASCOM Remote uses 11111).",
        links = listOf(MountLinkKind.HTTP),
        defaults = mapOf(MountLinkKind.HTTP to MountEndpoint("192.168.1.10", 11111)),
        discovery = MountDiscoveryKind.ALPACA,
        advancedFields = setOf(MountAdvancedField.SWAP_EAST_WEST, MountAdvancedField.SWAP_NORTH_SOUTH),
        createDriver = { context ->
            AlpacaDriver(
                context.requireHttp(),
                AlpacaConfiguration(
                    deviceNumber = context.connection.deviceNumber,
                    primaryPositiveIsEast = !context.advanced.swapEastWest,
                    secondaryPositiveIsNorth = !context.advanced.swapNorthSouth,
                ),
            )
        },
    )

    /** The protocols in the order of the picker. */
    val all: List<MountProtocolOption> = listOf(lx200, skyWatcher, nexstar, alpaca)

    /** The option of [protocol], or the first one when the engine knows a protocol this list does not. */
    fun option(protocol: MountProtocol?): MountProtocolOption = all.firstOrNull { it.protocol == protocol } ?: all.first()

    /** The protocols of the engine that no option covers (none, unless the engine gained a protocol). */
    val missing: List<MountProtocol> get() = MountProtocol.entries.filter { protocol -> all.none { it.protocol == protocol } }

    /** The Alpaca endpoint of an HTTP [connection], or null when its host or port is unusable. */
    fun alpacaEndpoint(connection: MountConnection): AlpacaEndpoint? = AlpacaEndpoint.validated(connection.host, connection.port)
}

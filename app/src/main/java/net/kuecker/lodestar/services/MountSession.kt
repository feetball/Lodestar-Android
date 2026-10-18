package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountClock
import net.kuecker.lodestar.kit.mount.MountController
import net.kuecker.lodestar.kit.mount.MountDriver
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSettings
import net.kuecker.lodestar.kit.mount.MountStatus
import net.kuecker.lodestar.kit.mount.SystemMountClock
import net.kuecker.lodestar.shared.AppFormat
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** The outcome of something the user asked the mount to do, in words ready to show. */
sealed interface MountResult {
    data class Success(val message: String? = null, val offsetDegrees: Double? = null) : MountResult

    data class Failure(val message: String, val error: MountError? = null) : MountResult
}

/** An open link to a mount: the engine's driver and what must be closed when the session ends. */
class MountOpenedLink(
    val driver: MountDriver,
    /** The clock the driver and its controller share (the real one, unless a simulator needs its own). */
    val clock: MountClock,
    private val closers: List<Closeable>,
    /** True for the built-in simulated mount. */
    val isDemo: Boolean = false,
) : Closeable {
    override fun close() {
        for (closer in closers) {
            try {
                closer.close()
            } catch (_: IOException) {
                // A link that cannot be closed is already dead.
            }
        }
    }
}

/** Opens links: sockets for a real mount, a simulator for the demo and the tests. */
fun interface MountLinkFactory {
    /**
     * Opens the link to [connection] and returns it. Everything that holds a resource is passed to [track] as soon as it
     * exists, so a cancel can close it while this call is still blocked in a connect.
     */
    @Throws(MountError::class)
    fun open(connection: MountConnection, advanced: MountAdvanced, clock: MountClock, track: (Closeable) -> Unit): MountOpenedLink
}

/** The real thing: a TCP socket, a UDP socket or an HTTP client to the address in the connection. */
class SocketMountLinkFactory(private val sockets: MountSocketProvider = MountSocketProvider.System) : MountLinkFactory {
    override fun open(connection: MountConnection, advanced: MountAdvanced, clock: MountClock, track: (Closeable) -> Unit): MountOpenedLink {
        val option = MountProtocols.all.firstOrNull { it.protocol == connection.protocol }
            ?: throw MountError.InvalidInput("this kind of mount is not supported by this version of Lodestar")
        if (connection.link !in option.links) throw MountError.InvalidInput("${option.title} cannot use ${connection.link.title}")
        if (connection.host.isBlank()) throw MountError.InvalidInput("the address is empty")
        return when (connection.link) {
            MountLinkKind.TCP -> {
                val transport = TcpMountTransport(connection.host.trim(), connection.port, sockets)
                track(transport)
                transport.open()
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, transport, null))
                MountOpenedLink(driver, clock, listOf(transport))
            }
            MountLinkKind.UDP -> {
                val transport = UdpMountTransport(connection.host.trim(), connection.port, sockets)
                track(transport)
                transport.open()
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, transport, null))
                MountOpenedLink(driver, clock, listOf(transport))
            }
            MountLinkKind.HTTP -> {
                val endpoint = MountProtocols.alpacaEndpoint(connection.copy(host = connection.host.trim()))
                    ?: throw MountError.InvalidInput("the address or port is not valid")
                val transport = AlpacaHttpTransport(endpoint, sockets)
                track(transport)
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, null, transport))
                MountOpenedLink(driver, clock, listOf(transport))
            }
        }
    }
}

/** Advice that goes with a failure. */
object MountHints {
    /** The text to show when connecting to [connection] failed with [error]. */
    fun connectMessage(error: MountError, connection: MountConnection): String {
        val hint = when {
            error is MountError.UnsupportedCoordinateSystem -> "Set the telescope to JNow or J2000 in its driver."
            error is MountError.NotAligned -> "Align the mount on its hand controller first."
            connection.link == MountLinkKind.HTTP && error.isCommunicationFailure ->
                "Is the Alpaca server running, and is the phone on the same network? Is the port right?"
            error.isCommunicationFailure ->
                "Is the phone on the mount's Wi-Fi? Is the right address and port selected?"
            else -> null
        }
        return if (hint == null) error.userMessage else "${error.userMessage} $hint"
    }
}

/**
 * One session with a mount, on the engine's [MountController]: connects, polls and executes the commands. Not thread
 * safe, like the controller: the service calls everything from its worker, except [cancelConnect] and [closeNow],
 * which may come from any thread and only close the link.
 */
class MountSession(
    private val factory: MountLinkFactory,
    private val clock: MountClock = SystemMountClock(),
    private val observerProvider: () -> Observer?,
) {
    private var link: MountOpenedLink? = null
    private var controller: MountController? = null
    private var values = MountSettingsValues()
    private val tracked = CopyOnWriteArrayList<Closeable>()

    @Volatile
    private var cancelled = false

    /** What the mount can do; null while not connected. */
    val capabilities: MountCapabilities? get() = controller?.capabilities

    /** The controller's state; disconnected without a session. */
    val status: MountStatus get() = controller?.status ?: MountStatus.disconnected

    /** True while the controller has a session. */
    val isConnected: Boolean get() = controller?.isConnected == true

    /** True when the open link is the built-in simulated mount. */
    val isDemo: Boolean get() = link?.isDemo == true

    /** The altitude limit the controller works with, if connected. */
    val activeAltitudeLimit: Double? get() = controller?.settings?.altitudeLimit

    /**
     * Opens the link, runs the engine's handshake and, if wanted and possible, sends the phone's site and time. The
     * connection counts as made even if the site and time are refused (the message says so).
     */
    fun connect(connection: MountConnection, settings: MountSettingsValues, factoryOverride: MountLinkFactory? = null): MountResult {
        closeLink()
        cancelled = false
        values = settings
        val observer = observerProvider()
            ?: return MountResult.Failure("Lodestar needs your observing location before it can talk to a mount. Set it in Settings.")
        try {
            val opened = (factoryOverride ?: factory).open(connection, settings.advanced, clock) { tracked.add(it) }
            link = opened
            val created = MountController(opened.driver, observer, engineSettings(settings), opened.clock)
            val found = created.connect()
            controller = created
            val note = if (settings.sendsSiteAndTime && found.canSetSiteAndTime) siteAndTimeNote(created, observer) else null
            return MountResult.Success(note)
        } catch (error: MountError) {
            closeLink()
            return if (cancelled) MountResult.Failure("Cancelled.") else MountResult.Failure(MountHints.connectMessage(error, connection), error)
        }
    }

    /** Closes the link being opened, from any thread; a blocked [connect] then fails. */
    fun cancelConnect() {
        cancelled = true
        closeTracked()
    }

    /** The controller's next refresh; null without a session. Also hands the controller the current observer. */
    fun poll(): MountStatus? {
        val current = controller ?: return null
        observerProvider()?.let { if (it != current.observer) current.observer = it }
        return current.poll()
    }

    /** Takes over new settings; the engine's own limits change before the next slew or sync. */
    fun configure(settings: MountSettingsValues) {
        values = settings
    }

    fun goto(target: MountTarget): MountResult = command {
        val current = refreshedController()
        current.goto(target.j2000, target.properMotion)
        MountResult.Success("Slewing to ${target.name}.")
    }

    fun sync(target: MountTarget): MountResult = command {
        val current = refreshedController()
        val result = current.sync(target.j2000, target.properMotion)
        MountResult.Success("Synced to ${target.name}. The mount was ${AppFormat.separation(result.offsetDegrees)} off.", result.offsetDegrees)
    }

    /** Stops every movement. If the mount cannot be stopped the message says to use its own controls. */
    fun abort(): MountResult {
        val current = controller ?: return MountResult.Failure(MountError.NotConnected.userMessage, MountError.NotConnected)
        return try {
            current.abort()
            MountResult.Success("Stopped.")
        } catch (error: MountError) {
            MountResult.Failure("${error.userMessage} Stop the mount with its own controls or power switch.", error)
        }
    }

    fun nudge(direction: MountNudgeDirection, rate: MountNudgeRate): MountResult = command {
        controller().nudge(direction, rate, NUDGE_SECONDS)
        MountResult.Success()
    }

    fun stopNudge(): MountResult = command {
        controller().stopNudge()
        MountResult.Success()
    }

    fun setTracking(enabled: Boolean): MountResult = command {
        controller().setTracking(enabled)
        MountResult.Success(if (enabled) "Tracking is on." else "Tracking is off.")
    }

    fun sendSiteAndTime(): MountResult = command {
        val current = controller()
        val observer = observerProvider() ?: throw MountError.InvalidInput("there is no observing location")
        current.setSiteAndTime(observer.location)
        MountResult.Success("Sent your location and the time (UTC) to the mount.")
    }

    /**
     * Ends the session. With [stopFirst] a slew or nudge in progress is stopped before disconnecting, because after
     * this the app can no longer stop it.
     */
    fun disconnect(stopFirst: Boolean) {
        val current = controller
        if (current != null && stopFirst && current.status.phase != MountPhase.IDLE) {
            try {
                current.abort()
            } catch (_: MountError) {
                // The link is going away anyway; nothing better can be done.
            }
        }
        current?.disconnect()
        closeLink()
    }

    /** Closes the link from any thread without talking to the mount. */
    fun closeNow() {
        cancelled = true
        closeTracked()
    }

    private fun command(block: () -> MountResult): MountResult = try {
        block()
    } catch (error: MountError) {
        MountResult.Failure(error.userMessage, error)
    }

    private fun controller(): MountController = controller ?: throw MountError.NotConnected

    /** The controller, replaced by one with the current limits when they changed (only when the mount is idle). */
    private fun refreshedController(): MountController {
        val current = controller()
        val wanted = engineSettings(values)
        if (current.settings == wanted || current.status.phase != MountPhase.IDLE) return current
        val opened = link ?: throw MountError.NotConnected
        val observer = observerProvider() ?: current.observer
        val replacement = MountController(opened.driver, observer, wanted, opened.clock)
        replacement.connect()
        controller = replacement
        return replacement
    }

    private fun siteAndTimeNote(current: MountController, observer: Observer): String? = try {
        current.setSiteAndTime(observer.location)
        null
    } catch (error: MountError) {
        "Connected, but the mount did not accept your location and time: ${error.userMessage}"
    }

    private fun engineSettings(settings: MountSettingsValues): MountSettings = MountSettings(
        altitudeLimit = settings.altitudeLimit,
        maximumSyncOffsetDegrees = settings.maximumSyncOffset,
    )

    private fun closeLink() {
        controller = null
        link?.close()
        link = null
        closeTracked()
        tracked.clear()
    }

    private fun closeTracked() {
        for (closer in tracked) {
            try {
                closer.close()
            } catch (_: IOException) {
                // Already closed.
            }
        }
    }

    private companion object {
        /** Each nudge command moves the mount this long; holding the arrow repeats it. */
        const val NUDGE_SECONDS = 0.5
    }
}

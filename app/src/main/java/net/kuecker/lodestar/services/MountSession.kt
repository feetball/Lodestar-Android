package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountClock
import net.kuecker.lodestar.kit.mount.MountController
import net.kuecker.lodestar.kit.mount.MountDriver
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountFeature
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSettings
import net.kuecker.lodestar.kit.mount.MountStatus
import net.kuecker.lodestar.kit.mount.SkyWatcherAlignmentPoint
import net.kuecker.lodestar.kit.mount.SkyWatcherDriver
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.kit.mount.SystemMountClock
import net.kuecker.lodestar.shared.AppFormat
import java.io.Closeable
import java.io.IOException
import java.time.Instant
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

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
    /** What only a simulated mount can do (put itself where the user would have centred a star). */
    val demoHelper: MountDemoHelper? = null,
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
    fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        location: GeographicLocation,
        track: (Closeable) -> Unit,
    ): MountOpenedLink
}

/** The one thing a simulated Sky-Watcher mount does for the user: stand where a star they would have centred is. */
fun interface MountDemoHelper {
    /** Moves the simulated mount so that its telescope points at the J2000 place [star] now. */
    fun centreOn(star: EquatorialCoordinate)
}

/** The real thing: a TCP socket, a UDP socket or an HTTP client to the address in the connection. */
class SocketMountLinkFactory(private val sockets: MountSocketProvider = MountSocketProvider.System) : MountLinkFactory {
    override fun open(
        connection: MountConnection,
        advanced: MountAdvanced,
        clock: MountClock,
        location: GeographicLocation,
        track: (Closeable) -> Unit,
    ): MountOpenedLink {
        val option = MountProtocols.all.firstOrNull { it.protocol == connection.protocol }
            ?: throw MountError.InvalidInput("this kind of mount is not supported by this version of Lodestar")
        if (connection.link !in option.links) throw MountError.InvalidInput("${option.title} cannot use ${connection.link.title}")
        if (connection.host.isBlank()) throw MountError.InvalidInput("the address is empty")
        return when (connection.link) {
            MountLinkKind.TCP -> {
                val transport = TcpMountTransport(connection.host.trim(), connection.port, sockets)
                track(transport)
                transport.open()
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, location, transport, null))
                MountOpenedLink(driver, clock, listOf(transport))
            }
            MountLinkKind.UDP -> {
                val transport = UdpMountTransport(connection.host.trim(), connection.port, sockets)
                track(transport)
                transport.open()
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, location, transport, null))
                MountOpenedLink(driver, clock, listOf(transport))
            }
            MountLinkKind.HTTP -> {
                val endpoint = MountProtocols.alpacaEndpoint(connection.copy(host = connection.host.trim()))
                    ?: throw MountError.InvalidInput("the address or port is not valid")
                val transport = AlpacaHttpTransport(endpoint, sockets)
                track(transport)
                val driver = option.createDriver(MountDriverContext(connection, advanced, clock, location, null, transport))
                MountOpenedLink(driver, clock, listOf(transport))
            }
        }
    }
}

/** One star of a Sky-Watcher alignment, as the screens list it. */
data class MountAlignmentStar(
    val name: String,
    /** Where the star is on the sky (J2000), if the star was synced in this session. */
    val j2000: EquatorialCoordinate?,
    /** How far the fitted model points from the star, degrees. */
    val residualDegrees: Double,
)

/** What the screens show of a Sky-Watcher Wi-Fi mount beyond the common status: the set-up and the alignment. */
data class MountSkyWatcherInfo(
    val mode: SkyWatcherMountMode,
    /** The encoder angles when the session began. */
    val startAxis1Degrees: Double,
    val startAxis2Degrees: Double,
    /** The mount was probably not at its home position when it was switched on (or was moved since). */
    val startsAwayFromHome: Boolean,
    /** Equatorial mounts: which side of the pier the telescope is on. */
    val pierSide: SkyWatcherPierSide?,
    /** Why tracking stopped by itself, if it did. */
    val trackingStopReason: MountError?,
    /** The alignment stars, oldest first. */
    val stars: List<MountAlignmentStar>,
    /** The expected error away from the stars, degrees; null before the first sync. */
    val predictedErrorDegrees: Double?,
    /** The largest distance between a star and where the fitted model points, degrees. */
    val maximumResidualDegrees: Double?,
    /** How far the mount's home position was off, from the first sync; null before it. */
    val homeErrorDegrees: Double?,
) {
    /** True once there is a star to point from: Go to needs it. */
    val isAligned: Boolean get() = stars.isNotEmpty()
}

/** Advice that goes with a failure. */
object MountHints {
    /** What a Sky-Watcher mount says to Go to before the first sync. */
    const val SKYWATCHER_NOT_ALIGNED = "Not aligned yet. Centre a star with the nudge pad, then tap Sync."

    /** The text when the fit refuses a star. */
    fun skyWatcherRejected(name: String): String = "That does not fit the other stars. Check that you centred $name."

    /** The text after a sync on a Sky-Watcher mount; [stars] is the number of stars now in the alignment. */
    fun skyWatcherSynced(name: String, offsetDegrees: Double, stars: Int): String {
        val off = AppFormat.separation(offsetDegrees)
        return when {
            stars == 1 -> "Synced to $name. The mount's home position was $off off."
            offsetDegrees > SUSPICIOUS_OFFSET ->
                "Synced to $name. The mount was $off off, which is a lot: check that you centred the right star and that the mount has not been bumped."
            else -> "Synced to $name. The mount was $off off."
        }
    }

    private const val SUSPICIOUS_OFFSET = 0.5

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
    private val syncedStars = IdentityHashMap<SkyWatcherAlignmentPoint, SyncedStar>()
    private var homeError: Double? = null
    private var siteSent: GeographicLocation? = null
    private var pendingNotice: String? = null

    @Volatile
    private var cancelled = false

    private class SyncedStar(val name: String, val j2000: EquatorialCoordinate)

    private val skyWatcher: SkyWatcherDriver? get() = link?.driver as? SkyWatcherDriver

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
        syncedStars.clear()
        homeError = null
        pendingNotice = null
        val observer = observerProvider()
            ?: return MountResult.Failure("Lodestar needs your observing location before it can talk to a mount. Set it in Settings.")
        try {
            val opened = (factoryOverride ?: factory).open(connection, settings.advanced, clock, observer.location) { tracked.add(it) }
            link = opened
            val created = MountController(opened.driver, observer, engineSettings(settings), opened.clock)
            val found = created.connect()
            controller = created
            siteSent = observer.location
            // A mount that works out its own sky always needs the site; the others only if the user wants it sent.
            val sendsSite = settings.sendsSiteAndTime || skyWatcher != null
            val note = if (sendsSite && found.canSetSiteAndTime) siteAndTimeNote(created, observer) else null
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
        val observer = observerProvider()
        if (observer != null && observer != current.observer) current.observer = observer
        val status = current.poll()
        if (observer != null) followSite(current, observer.location)
        return status
    }

    /** A message the session wants shown (the alignment was cleared because the phone moved), once. */
    fun takeNotice(): String? = pendingNotice.also { pendingNotice = null }

    /** Takes over new settings; the engine's own limits change before the next slew or sync. */
    fun configure(settings: MountSettingsValues) {
        values = settings
    }

    fun goto(target: MountTarget): MountResult = command {
        val current = refreshedController()
        if (skyWatcher != null) {
            // The engine's own limit is at its lowest for these mounts, so the user's limit applies at once, with the same words.
            val date = link?.clock?.now() ?: Instant.now()
            MountTargeting.altitudeProblem(target, current.observer, date, values.altitudeLimit)?.let { throw it }
        }
        current.goto(target.j2000, target.properMotion)
        MountResult.Success("Slewing to ${target.name}.")
    }

    fun sync(target: MountTarget): MountResult = command {
        val current = refreshedController()
        val driver = skyWatcher
        if (driver == null) {
            val result = current.sync(target.j2000, target.properMotion)
            return@command MountResult.Success(
                "Synced to ${target.name}. The mount was ${AppFormat.separation(result.offsetDegrees)} off.", result.offsetDegrees,
            )
        }
        val result = try {
            current.sync(target.j2000, target.properMotion)
        } catch (error: MountError.Rejected) {
            return@command MountResult.Failure(MountHints.skyWatcherRejected(target.name), error)
        }
        val points = driver.alignmentPoints
        points.lastOrNull()?.let { syncedStars[it] = SyncedStar(target.name, target.j2000) }
        syncedStars.keys.removeAll { key -> points.none { it === key } }
        if (points.size == 1) homeError = result.offsetDegrees
        MountResult.Success(MountHints.skyWatcherSynced(target.name, result.offsetDegrees, points.size), result.offsetDegrees)
    }

    /** Where the star alignment of a Sky-Watcher mount stands; null for the other mounts. */
    fun skyWatcherInfo(): MountSkyWatcherInfo? {
        val driver = skyWatcher ?: return null
        val mode = driver.mountMode ?: return null
        val points = driver.alignmentPoints
        val fit = driver.alignmentFit
        val residuals = fit?.residualsDegrees.orEmpty()
        val stars = points.mapIndexed { index, point ->
            val known = syncedStars[point]
            MountAlignmentStar(known?.name ?: "Star ${index + 1}", known?.j2000, residuals.getOrNull(index) ?: 0.0)
        }
        val start = driver.startupAxisAngles
        val away = start != null && (abs(start.axis1Degrees) > HOME_TOLERANCE || abs(start.axis2Degrees) > HOME_TOLERANCE)
        return MountSkyWatcherInfo(
            mode = mode,
            startAxis1Degrees = start?.axis1Degrees ?: 0.0,
            startAxis2Degrees = start?.axis2Degrees ?: 0.0,
            startsAwayFromHome = away,
            pierSide = if (mode == SkyWatcherMountMode.EQUATORIAL) driver.pierSide else null,
            trackingStopReason = driver.trackingStopReason,
            stars = stars,
            predictedErrorDegrees = fit?.predictedErrorDegrees,
            maximumResidualDegrees = fit?.maximumResidualDegrees,
            homeErrorDegrees = if (points.isEmpty()) null else homeError,
        )
    }

    /** Declares that the mount stands at its home position now; the star alignment is forgotten. */
    fun declareHome(): MountResult = command {
        val driver = skyWatcher ?: throw MountError.NotSupported(MountFeature.OTHER)
        controller().abort()
        driver.declareHomePosition()
        syncedStars.clear()
        homeError = null
        MountResult.Success("This is now the mount's home position. The star alignment was cleared.")
    }

    /** Forgets the star alignment of a Sky-Watcher mount. */
    fun clearAlignment(): MountResult = command {
        val driver = skyWatcher ?: throw MountError.NotSupported(MountFeature.OTHER)
        driver.clearAlignment()
        syncedStars.clear()
        homeError = null
        MountResult.Success("The star alignment was cleared.")
    }

    /** Drops one star of the alignment (by its place in [MountSkyWatcherInfo.stars]) and fits the rest. */
    fun removeAlignmentStar(index: Int): MountResult = command {
        val driver = skyWatcher ?: throw MountError.NotSupported(MountFeature.OTHER)
        val name = syncedStars[driver.alignmentPoints.getOrNull(index)]?.name ?: "the star"
        driver.removeAlignmentPoint(index)
        val points = driver.alignmentPoints
        syncedStars.keys.removeAll { key -> points.none { it === key } }
        if (points.isEmpty()) homeError = null
        MountResult.Success("Removed $name from the alignment.")
    }

    /** The simulated Sky-Watcher mount stands where the user would have centred [target]. */
    fun centreDemo(target: MountTarget): MountResult = command {
        val helper = link?.demoHelper ?: throw MountError.NotSupported(MountFeature.OTHER)
        helper.centreOn(target.j2000)
        link?.driver?.abort()
        MountResult.Success("The simulated mount now points at ${target.name}.")
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
        MountResult.Failure(wording(error), error)
    }

    /** The text for [error]: the engine's, except where it talks about a hand controller the mount does not have. */
    private fun wording(error: MountError): String =
        if (error is MountError.NotAligned && skyWatcher != null) MountHints.SKYWATCHER_NOT_ALIGNED else error.userMessage

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

    private fun engineSettings(settings: MountSettingsValues): MountSettings =
        if (skyWatcher != null) {
            // The driver judges a sync by how well the stars fit, and the altitude limit is applied in goto, so a changed
            // limit never has to restart the controller (which would forget the alignment).
            MountSettings(altitudeLimit = MountSettingsValues.altitudeLimitRange.start, maximumSyncOffsetDegrees = MountSettingsValues.NO_SYNC_LIMIT)
        } else {
            MountSettings(altitudeLimit = settings.altitudeLimit, maximumSyncOffsetDegrees = settings.maximumSyncOffset)
        }

    /** Hands a Sky-Watcher driver the new site when the phone has moved, so that its sky stays right. */
    private fun followSite(current: MountController, location: GeographicLocation) {
        val driver = skyWatcher ?: return
        val sent = siteSent ?: return
        val moved = abs(location.latitude - sent.latitude) > SITE_TOLERANCE ||
            abs(AstroMath.normalizeSignedDegrees(location.longitude - sent.longitude)) > SITE_TOLERANCE
        if (!moved || current.status.phase == MountPhase.SLEWING) return
        val before = driver.alignmentPoints.size
        try {
            current.setSiteAndTime(location)
        } catch (_: MountError) {
            return
        }
        siteSent = location
        if (before > 0 && driver.alignmentPoints.isEmpty()) {
            syncedStars.clear()
            homeError = null
            pendingNotice = "Your location changed, so the star alignment was cleared. Align on a star again."
        }
    }

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

        /** A Sky-Watcher mount that starts further than this from its home position (degrees on either axis) was not at home. */
        const val HOME_TOLERANCE = 10.0

        /** The phone moving less than this (degrees of latitude or longitude) does not change the mount's sky. */
        const val SITE_TOLERANCE = 0.01
    }
}

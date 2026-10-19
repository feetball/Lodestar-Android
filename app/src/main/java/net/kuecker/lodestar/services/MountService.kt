package net.kuecker.lodestar.services

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountStatus
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/** Where the connection to the mount stands. */
enum class MountConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/** The simulated mounts the form offers in place of hardware. */
enum class MountDemoKind(val title: String) {
    /** An OnStep-like LX200 mount that slews at 4° per second. */
    LX200("Simulated OnStep mount"),

    /** A Sky-Watcher AZ-GTi that has to be aligned on stars first, with a few degrees of set-up error. */
    SKYWATCHER("Simulated Sky-Watcher mount"),
}

/** A message about something that just happened, for the user to read. [id] changes with every new one. */
data class MountNotice(val id: Long, val text: String, val isError: Boolean)

/** Everything the screens show of the mount, as one immutable value. */
data class MountSnapshot(
    val state: MountConnectionState = MountConnectionState.DISCONNECTED,
    /** The engine's state: phase, position, distance to the target, slew time, last outcome. */
    val status: MountStatus = MountStatus.disconnected,
    val capabilities: MountCapabilities? = null,
    /** What the session is, or is being, connected to. */
    val connection: MountConnection? = null,
    /** Which simulated mount the session is, if it is one. */
    val demo: MountDemoKind? = null,
    /** The set-up and alignment of a Sky-Watcher Wi-Fi mount; null for the others. */
    val skyWatcher: MountSkyWatcherInfo? = null,
    /** The last session ended without the user asking: the link failed. */
    val lostConnection: Boolean = false,
    /** Why the last attempt to connect failed. */
    val connectProblem: String? = null,
    /** A hiccup of the link right now (cleared by the next good poll). */
    val linkProblem: String? = null,
    /** The distance to the target when the current slew began, for the progress bar. */
    val slewStartSeparation: Double? = null,
    val notice: MountNotice? = null,
) {
    val isConnected: Boolean get() = state == MountConnectionState.CONNECTED

    /** True while the session is a simulated mount. */
    val isDemo: Boolean get() = demo != null

    /**
     * Whether the mount knows where it points: a Sky-Watcher mount after its first sync (the engine's flag is never reset
     * by clearing the alignment), the others as the mount reports it.
     */
    val isAligned: Boolean? get() = skyWatcher?.isAligned ?: status.isAligned

    /** True while the mount is under Lodestar's command and moving. */
    val isMoving: Boolean get() = status.phase == MountPhase.SLEWING || status.phase == MountPhase.NUDGING
}

/**
 * The connection to a GoTo mount, for every screen: connection state, the mount's position (polled about once a
 * second, twice while nudging), slewing state, errors as text, and the commands. All commands are queued on one worker
 * (the engine's controller is not thread safe); Stop jumps the queue. Nothing here blocks the main thread, and nothing
 * moves the mount unless a screen asked for it: there is no automatic slewing.
 *
 * Safety: when the app leaves the foreground the service stops any slew or nudge, disconnects, and reconnects when the
 * app returns (without moving anything).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MountService(
    private val scope: CoroutineScope,
    private val session: MountSession,
    val preferences: MountPreferences,
    private val observerProvider: () -> Observer?,
    private val demoFactory: (MountDemoKind) -> MountLinkFactory,
    private val sockets: MountSocketProvider = MountSocketProvider.System,
    private val pollMillis: Long = POLL_MILLIS,
    private val nudgePollMillis: Long = NUDGE_POLL_MILLIS,
    private val worker: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    /** The time the mount's place is worked out at: the app's clock (so a screenshot's frozen time holds). */
    private val now: () -> Instant = Instant::now,
) {
    /** What the screens show; observable. */
    var snapshot: MountSnapshot by mutableStateOf(MountSnapshot())
        private set

    /** Where the mount points, for the sky overlay; null while not connected or before the first reading. */
    var marker: MountMarker? by mutableStateOf(null)
        private set

    private class Task(
        val isUrgent: Boolean,
        val key: String?,
        val endsSession: Boolean,
        val run: () -> MountResult,
        val done: CompletableDeferred<MountResult>,
    )

    private val lock = Any()
    private val tasks = ArrayDeque<Task>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val noticeIds = AtomicLong()
    private var isSessionActive = false
    @Volatile
    private var isForeground = true

    @Volatile
    private var resumeAfterBackground: Pair<MountConnection, MountDemoKind?>? = null
    private var slewStart: Double? = null

    // Connecting

    /** Starts connecting to [connection] (or to the built-in simulated mount of kind [demo]); ignored while a session exists. */
    fun connect(connection: MountConnection, demo: MountDemoKind? = null) {
        synchronized(lock) {
            if (isSessionActive) return
            isSessionActive = true
        }
        resumeAfterBackground = null
        if (demo == null) preferences.remember(connection)
        snapshot = MountSnapshot(state = MountConnectionState.CONNECTING, connection = connection, demo = demo)
        scope.launch(worker) { runSession(connection, demo) }
    }

    /** Connects again to the connection used last, or the demo if that was the session. */
    fun reconnect() {
        val last = snapshot
        connect(last.connection ?: preferences.lastConnection, last.demo)
    }

    /** Stops an attempt to connect that is still waiting for the mount. */
    fun cancelConnect() {
        if (snapshot.state == MountConnectionState.CONNECTING) session.cancelConnect()
    }

    /** Stops any movement, disconnects and closes the link. */
    fun disconnect() {
        resumeAfterBackground = null
        if (snapshot.state == MountConnectionState.CONNECTING) {
            session.cancelConnect()
            return
        }
        enqueue(isUrgent = true, key = null, endsSession = true) {
            session.disconnect(stopFirst = true)
            MountResult.Success("Disconnected.")
        }
    }

    // Commands

    /** Slews to [target]. The result arrives when the mount has accepted the move (not when it has arrived). */
    suspend fun goto(target: MountTarget): MountResult {
        if (target.isSun) return MountResult.Failure(MountTargeting.SUN_REFUSAL)
        return enqueue(isUrgent = false, key = null) { session.goto(target) }.await()
    }

    /** Tells the mount it points at [target]. */
    suspend fun sync(target: MountTarget): MountResult {
        if (target.isSun) return MountResult.Failure(MountTargeting.SUN_REFUSAL)
        return enqueue(isUrgent = false, key = null) { session.sync(target) }.await()
    }

    /** Stops every movement now; ahead of anything waiting in the queue. */
    fun stop() {
        enqueue(isUrgent = true, key = KEY_STOP, endsSession = false) { session.abort() }
    }

    /** Moves the mount for half a second; a newer nudge replaces one that has not started yet. */
    fun nudge(direction: MountNudgeDirection, rate: MountNudgeRate) {
        enqueue(isUrgent = false, key = KEY_NUDGE, endsSession = false) { session.nudge(direction, rate) }
    }

    /** Ends a nudge at once. */
    fun stopNudge() {
        enqueue(isUrgent = true, key = KEY_NUDGE, endsSession = false) { session.stopNudge() }
    }

    /** Declares that the Sky-Watcher mount stands at its home position now (the alignment is forgotten). */
    suspend fun declareHome(): MountResult = enqueue(isUrgent = false, key = null) { session.declareHome() }.await()

    /** Forgets the star alignment of the Sky-Watcher mount. */
    suspend fun clearAlignment(): MountResult = enqueue(isUrgent = false, key = null) { session.clearAlignment() }.await()

    /** Drops the alignment star at [index] of [MountSkyWatcherInfo.stars]. */
    suspend fun removeAlignmentStar(index: Int): MountResult =
        enqueue(isUrgent = false, key = null) { session.removeAlignmentStar(index) }.await()

    /** The simulated Sky-Watcher mount stands where the user would have centred [target]. */
    suspend fun centreDemo(target: MountTarget): MountResult = enqueue(isUrgent = false, key = null) { session.centreDemo(target) }.await()

    suspend fun setTracking(enabled: Boolean): MountResult =
        enqueue(isUrgent = false, key = null) { session.setTracking(enabled) }.await()

    suspend fun sendSiteAndTime(): MountResult = enqueue(isUrgent = false, key = null) { session.sendSiteAndTime() }.await()

    /** Hands changed settings to the session; the engine applies the altitude limit before the next slew. */
    fun settingsChanged() {
        val values = preferences.settings
        enqueue(isUrgent = false, key = KEY_SETTINGS, endsSession = false) {
            session.configure(values)
            MountResult.Success()
        }
    }

    /** Looks on the local network for mounts of the kind [kind] (about two seconds). */
    suspend fun discover(kind: MountDiscoveryKind): List<MountFoundTelescope> = when (kind) {
        MountDiscoveryKind.ALPACA -> MountAlpacaDiscovery(sockets).discover()
        MountDiscoveryKind.SKYWATCHER -> MountSkyWatcherProbe(sockets).discover()
    }

    /** Forgets a lost connection or a failed attempt (and its message) so the screens stop showing it. */
    fun dismissProblem() {
        val current = snapshot
        if (current.state == MountConnectionState.DISCONNECTED) {
            snapshot = current.copy(lostConnection = false, connectProblem = null, notice = null)
        }
    }

    /** Shows [text] as the current message on the mount card. */
    fun postNotice(text: String, isError: Boolean) {
        snapshot = snapshot.copy(notice = notice(text, isError))
    }

    /** Hides [notice] if it is still the one showing. */
    fun dismissNotice(id: Long) {
        val current = snapshot
        if (current.notice?.id == id) snapshot = current.copy(notice = null)
    }

    /** Called by the app: false when it left the screen (everything is stopped), true when it came back. */
    fun setForeground(isInForeground: Boolean) {
        if (isInForeground == isForeground) return
        isForeground = isInForeground
        if (!isInForeground) {
            val current = snapshot
            val connection = current.connection
            if (current.state != MountConnectionState.DISCONNECTED && connection != null) {
                resumeAfterBackground = connection to current.demo
            }
            disconnectForBackground()
        } else {
            // A session still winding down reconnects when it has ended (see endSession).
            if (!synchronized(lock) { isSessionActive }) reconnectAfterBackground()
        }
    }

    private fun reconnectAfterBackground() {
        val resume = resumeAfterBackground ?: return
        resumeAfterBackground = null
        connect(resume.first, resume.second)
    }

    // Worker

    private fun disconnectForBackground() {
        if (snapshot.state == MountConnectionState.CONNECTING) {
            session.cancelConnect()
            return
        }
        enqueue(isUrgent = true, key = null, endsSession = true) {
            session.disconnect(stopFirst = true)
            MountResult.Success()
        }
    }

    private fun enqueue(
        isUrgent: Boolean,
        key: String?,
        endsSession: Boolean = false,
        run: () -> MountResult,
    ): CompletableDeferred<MountResult> {
        val done = CompletableDeferred<MountResult>()
        val task = Task(isUrgent, key, endsSession, run, done)
        val replaced = ArrayList<Task>()
        val accepted = synchronized(lock) {
            if (!isSessionActive || snapshot.state != MountConnectionState.CONNECTED) {
                false
            } else {
                if (key != null) {
                    val iterator = tasks.iterator()
                    while (iterator.hasNext()) {
                        val queued = iterator.next()
                        if (queued.key == key && !queued.endsSession) {
                            replaced.add(queued)
                            iterator.remove()
                        }
                    }
                }
                if (isUrgent) tasks.addFirst(task) else tasks.addLast(task)
                true
            }
        }
        for (old in replaced) old.done.complete(MountResult.Success())
        if (accepted) {
            wake.trySend(Unit)
        } else {
            done.complete(MountResult.Failure(MountError.NotConnected.userMessage, MountError.NotConnected))
        }
        return done
    }

    private fun takeTask(): Task? = synchronized(lock) { tasks.removeFirstOrNull() }

    private suspend fun runSession(connection: MountConnection, demo: MountDemoKind?) {
        val factoryOverride = demo?.let(demoFactory)
        val connected = guarded { session.connect(connection, preferences.settings, factoryOverride) }
        if (connected is MountResult.Failure) {
            endSession(lost = false, problem = connected.message.takeUnless { it == CANCELLED })
            return
        }
        preferences.markConnected()
        val note = (connected as? MountResult.Success)?.message
        publish(notice = note?.let { notice(it, isError = true) })
        var nextPoll = System.nanoTime() + pollMillis * NANOS_PER_MILLI
        while (true) {
            val task = takeTask()
            if (task != null) {
                val result = guarded(task.run)
                task.done.complete(result)
                publish(notice = noticeFor(task, result))
                if (task.endsSession) {
                    endSession(lost = false, problem = null)
                    return
                }
                continue
            }
            if (!session.isConnected) {
                endSession(lost = true, problem = null)
                return
            }
            val nowNanos = System.nanoTime()
            if (nowNanos >= nextPoll) {
                guarded { session.poll(); MountResult.Success() }
                publish(notice = session.takeNotice()?.let { notice(it, isError = true) })
                val interval = if (session.status.phase == MountPhase.NUDGING) nudgePollMillis else pollMillis
                nextPoll = System.nanoTime() + interval * NANOS_PER_MILLI
                continue
            }
            withTimeoutOrNull((nextPoll - nowNanos) / NANOS_PER_MILLI + 1) { wake.receive() }
        }
    }

    private fun noticeFor(task: Task, result: MountResult): MountNotice? = when {
        task.key == KEY_NUDGE || task.key == KEY_SETTINGS -> (result as? MountResult.Failure)?.let { notice(it.message, true) }
        result is MountResult.Failure -> notice(result.message, isError = true)
        result is MountResult.Success && result.message != null -> notice(result.message, isError = false)
        else -> null
    }

    private fun notice(text: String, isError: Boolean) = MountNotice(noticeIds.incrementAndGet(), text, isError)

    /** Runs [block], turning anything unexpected into a failure so that no network surprise can crash the app. */
    private fun guarded(block: () -> MountResult): MountResult = try {
        block()
    } catch (error: MountError) {
        MountResult.Failure(error.userMessage, error)
    } catch (error: RuntimeException) {
        MountResult.Failure("Something went wrong talking to the mount (${error.javaClass.simpleName}).")
    }

    private fun publish(notice: MountNotice? = null) {
        val status = session.status
        val previous = snapshot
        val moving = status.phase == MountPhase.SLEWING
        if (!moving) {
            slewStart = null
        } else if (slewStart == null) {
            slewStart = status.separation
        }
        snapshot = previous.copy(
            state = MountConnectionState.CONNECTED,
            status = status,
            capabilities = session.capabilities,
            skyWatcher = session.skyWatcherInfo(),
            lostConnection = false,
            connectProblem = null,
            linkProblem = status.lastError?.userMessage,
            slewStartSeparation = slewStart,
            notice = notice ?: previous.notice,
        )
        val position = status.position
        val observer = observerProvider()
        marker = if (position != null && observer != null) MountMarker.make(position, observer, now()) else null
    }

    private fun endSession(lost: Boolean, problem: String?) {
        val orphans = synchronized(lock) {
            val rest = tasks.toList()
            tasks.clear()
            rest
        }
        for (task in orphans) {
            task.done.complete(MountResult.Failure(MountError.NotConnected.userMessage, MountError.NotConnected))
        }
        session.closeNow()
        slewStart = null
        marker = null
        val previous = snapshot
        snapshot = MountSnapshot(
            connection = previous.connection,
            demo = previous.demo,
            lostConnection = lost,
            connectProblem = problem,
            notice = if (lost) notice(LOST_TEXT, isError = true) else null,
        )
        synchronized(lock) { isSessionActive = false }
        if (isForeground) reconnectAfterBackground()
    }

    companion object {
        const val KEY = "mount.service"
        const val POLL_MILLIS = 1_000L
        const val NUDGE_POLL_MILLIS = 500L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val KEY_STOP = "stop"
        private const val KEY_NUDGE = "nudge"
        private const val KEY_SETTINGS = "settings"
        private const val CANCELLED = "Cancelled."
        private const val LOST_TEXT = "The connection to the mount was lost. Stop it with its own controls if it is still moving."

        /**
         * The service for the running app: remembered settings in the device-local `lodestar.mount` preferences, sockets
         * over the phone's Wi-Fi, and the app's lifecycle (call from the main thread).
         */
        fun create(context: Context, observerProvider: () -> Observer?, now: () -> Instant = Instant::now): MountService {
            val appContext = context.applicationContext
            val network = MountWifiNetwork(appContext).also { it.start() }
            val store = SharedPreferencesMountStore(appContext.getSharedPreferences("lodestar.mount", Context.MODE_PRIVATE))
            val session = MountSession(SocketMountLinkFactory(network), observerProvider = observerProvider)
            val service = MountService(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                session = session,
                preferences = MountPreferences(store),
                observerProvider = observerProvider,
                demoFactory = { kind -> MountDemoLinkFactory(kind, observerProvider, now) },
                sockets = network,
                now = now,
            )
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = service.setForeground(true)

                    override fun onStop(owner: LifecycleOwner) = service.setForeground(false)
                },
            )
            return service
        }
    }
}

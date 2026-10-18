package net.kuecker.lodestar.features.mount

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.services.MountConnection
import net.kuecker.lodestar.services.MountFoundTelescope
import net.kuecker.lodestar.services.MountLinkKind
import net.kuecker.lodestar.services.MountProtocolOption
import net.kuecker.lodestar.services.MountProtocols
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained

/**
 * The mount service of the app, created on first use and kept with the app model (so it survives tab switches and
 * screen rebuilds). It never starts anything on its own: no network is touched until the user taps Connect.
 */
@Composable
fun rememberMountService(): MountService {
    val app = LocalAppModel.current
    val context = LocalContext.current
    return remember(app) {
        app.retained.getOrPut(MountService.KEY) {
            MountService.create(context) { app.observerContext?.observer }
        }
    }
}

/** The sheets over the Aim screen that belong to the mount. */
enum class MountSheetKind {
    /** The connection and settings, with the nudge pad while connected. */
    CONTROL,

    /** Confirming a slew to the Aim target. */
    GOTO,

    /** Confirming a sync to the Aim target. */
    SYNC,

    /** The nudge pad on its own, over the sky. */
    NUDGE,
}

/** Which mount sheet is open over the Aim screen; shared by the controls bar, the mount card and the sheet host. */
class MountAimState {
    var sheet: MountSheetKind? by mutableStateOf(null)
        private set

    fun open(kind: MountSheetKind) {
        sheet = kind
    }

    fun close() {
        sheet = null
    }

    companion object {
        const val KEY = "mount.aim"
    }
}

/** The shared [MountAimState]. */
@Composable
fun rememberMountAimState(): MountAimState = rememberRetained(MountAimState.KEY) { MountAimState() }

/**
 * The connection being edited in the form: protocol, connection type, address, port and, for Alpaca, the telescope
 * picked from the search. It starts from the connection used last and is shared by the Settings screen and the Aim
 * sheet, so both show the same entries.
 */
class MountDraft(private val service: MountService) {
    var protocol: MountProtocol by mutableStateOf(service.preferences.lastConnection.protocol)
        private set
    var link: MountLinkKind by mutableStateOf(service.preferences.lastConnection.link)
        private set
    var host: String by mutableStateOf(service.preferences.lastConnection.host)
        private set
    var port: Int by mutableStateOf(service.preferences.lastConnection.port)
        private set
    var deviceNumber: Int by mutableStateOf(service.preferences.lastConnection.deviceNumber)
        private set
    var deviceName: String? by mutableStateOf(service.preferences.lastConnection.deviceName)
        private set

    /** True while the search for Alpaca devices runs. */
    var isSearching: Boolean by mutableStateOf(false)
        private set

    /** The telescopes the last search found; null before the first search. */
    var found: List<MountFoundTelescope>? by mutableStateOf(null)
        private set

    /** Whether the Advanced section is open. */
    var showsAdvanced: Boolean by mutableStateOf(false)

    private var searchJob: Job? = null

    val option: MountProtocolOption get() = MountProtocols.option(protocol)

    /** The connection the form describes. */
    fun connection(): MountConnection = MountConnection(option.protocol, link, host.trim(), port, deviceNumber, deviceName)

    /** The user typed another address: a telescope picked from the search no longer applies. */
    fun editHost(value: String) {
        if (value == host) return
        host = value.take(MAXIMUM_HOST_LENGTH)
        forgetTelescope()
    }

    /** The user typed another port. */
    fun editPort(value: Int) {
        if (value == port) return
        port = value
        forgetTelescope()
    }

    /** Switches to [next] and fills the address from what was last used with it. */
    fun selectProtocol(next: MountProtocol) {
        if (next == protocol) return
        load(service.preferences.connection(next))
    }

    /** Switches the connection type of the protocol. */
    fun selectLink(next: MountLinkKind) {
        if (next == link || next !in option.links) return
        load(service.preferences.connection(protocol, next))
    }

    /** Takes the address of a telescope the search found. */
    fun choose(telescope: MountFoundTelescope) {
        host = telescope.host
        port = telescope.port
        deviceNumber = telescope.deviceNumber
        deviceName = telescope.deviceName
    }

    /** Searches the local network for Alpaca telescopes; a search already running is replaced. */
    fun search(scope: CoroutineScope) {
        searchJob?.cancel()
        isSearching = true
        found = null
        searchJob = scope.launch {
            try {
                found = service.discoverAlpaca()
            } finally {
                isSearching = false
            }
        }
    }

    /** Stops a search in progress. */
    fun cancelSearch() {
        searchJob?.cancel()
        isSearching = false
    }

    private fun forgetTelescope() {
        deviceNumber = 0
        deviceName = null
    }

    private fun load(connection: MountConnection) {
        protocol = connection.protocol
        link = connection.link
        host = connection.host
        port = connection.port
        deviceNumber = connection.deviceNumber
        deviceName = connection.deviceName
        found = null
    }
}

/** The shared [MountDraft] for [service]. */
@Composable
fun rememberMountDraft(service: MountService): MountDraft = rememberRetained(DRAFT_KEY) { MountDraft(service) }

private const val DRAFT_KEY = "mount.draft"
private const val MAXIMUM_HOST_LENGTH = 253

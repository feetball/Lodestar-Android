package net.kuecker.lodestar.features.satellites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.shared.rememberRetained

/** The satellite screens that open from the Tonight tab. */
sealed interface SatelliteScreen {
    /** The visible passes of the next three days. */
    data object Passes : SatelliteScreen

    /** One pass, with its sky path and the two Aim actions. */
    data class Detail(val item: SatellitePassItem) : SatelliteScreen
}

/**
 * Where the satellite cards ask for a screen. The Tonight tab owns the navigation stack, so a card only files a request
 * here and the tab's [SatelliteNavigationEffect] pushes the screen.
 */
class SatelliteRequests {
    /** The screen asked for and not yet opened. */
    var pending: SatelliteScreen? by mutableStateOf(null)
        private set

    /** Asks for [screen]. */
    fun open(screen: SatelliteScreen) {
        pending = screen
    }

    internal fun take(): SatelliteScreen? = pending.also { pending = null }
}

/** The requests of this app session (kept with the app model). */
@Composable
internal fun rememberSatelliteRequests(): SatelliteRequests = rememberRetained("satellites.requests") { SatelliteRequests() }

/**
 * Opens the screens the satellite cards ask for: put it in the tab that hosts the cards, with a function that pushes the
 * screen on the tab's navigation stack.
 */
@Composable
fun SatelliteNavigationEffect(onOpen: (SatelliteScreen) -> Unit) {
    val requests = rememberSatelliteRequests()
    val latest by rememberUpdatedState(onOpen)
    LaunchedEffect(requests.pending) {
        requests.take()?.let(latest)
    }
    SatelliteScreenshotEffect(requests)
}

/** The content of a pushed satellite [screen]; [onBack] pops it. */
@Composable
fun SatelliteRouteScreen(screen: SatelliteScreen, onBack: () -> Unit) {
    when (screen) {
        SatelliteScreen.Passes -> SatellitePassesScreen(onBack)
        is SatelliteScreen.Detail -> SatellitePassDetailScreen(screen.item, onBack)
    }
}

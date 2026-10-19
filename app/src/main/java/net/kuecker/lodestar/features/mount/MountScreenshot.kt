package net.kuecker.lodestar.features.mount

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountProtocol
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountDemoKind
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountTargeting
import net.kuecker.lodestar.shared.LocalAppModel

/** How long a screenshot waits for the simulated mount to answer, in milliseconds. */
private const val CONNECT_TIMEOUT_MS = 30_000L

/**
 * Debug screenshots only: connects a simulated mount and waits until it has reported where it points. There are two
 * of them, the OnStep-like one and the Sky-Watcher AZ-GTi (see [kind]). Nothing is remembered of the connection.
 */
internal suspend fun MountService.connectDemoAndWait(kind: MountDemoKind = MountDemoKind.LX200) {
    if (snapshot.state == MountConnectionState.DISCONNECTED) connect(preferences.lastConnection, demo = kind)
    withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        snapshotFlow { snapshot }.first { it.isConnected && it.status.position != null }
    }
}

/**
 * Debug screenshots only: connects the simulated Sky-Watcher mount (an AZ-GTi on its tripod) and aligns it on [stars]
 * stars the way the set-up asks the user to: the star it suggests next for the app's time (a screenshot's clock stands
 * still), put in the middle of the frame by the simulated mount's helper in place of the nudge arrows, then Sync.
 */
private suspend fun MountService.connectSkyWatcherDemo(app: AppModel, stars: Int) {
    // The arrangement is a remembered setting: the form that picked Equatorial must not carry over to this mount.
    chooseArrangement(null)
    connectDemoAndWait(MountDemoKind.SKYWATCHER)
    if (stars == 0) return
    val observer = app.observerContext?.observer ?: return
    val catalog = snapshotFlow { app.catalog.catalog }.filterNotNull().first()
    val candidates = withContext(Dispatchers.Default) {
        MountAlignmentSuggestions.candidates(catalog, observer, app.clock.now)
    }
    val used = ArrayList<EquatorialCoordinate>()
    repeat(stars) {
        val star = MountAlignmentSuggestions.suggest(candidates, used, count = 1).firstOrNull()?.candidate ?: return
        val target = MountTargeting.target(star.obj, observer, app.clock.now)
        centreDemo(target)
        sync(target)
        used.add(star.j2000)
    }
}

/** Debug screenshots only: the Sky-Watcher arrangement, as the advanced options set it (null: Automatic). */
private fun MountService.chooseArrangement(mode: SkyWatcherMountMode?) {
    preferences.update { it.copy(advanced = it.advanced.copy(skyWatcherMode = mode)) }
}

/** How many alignment stars the simulated Sky-Watcher mount has on a screen that connects it; null on the others. */
private fun ScreenshotScreen.alignmentStars(): Int? = when (this) {
    ScreenshotScreen.MOUNT_SW_CONTROL, ScreenshotScreen.AIM_SW_MOUNT_SHEET, ScreenshotScreen.AIM_SW_GOTO,
    ScreenshotScreen.AIM_SW_SYNC -> 0
    ScreenshotScreen.MOUNT_SW_ALIGNED_1 -> 1
    ScreenshotScreen.MOUNT_SW_ALIGNED_2, ScreenshotScreen.AIM_SW_MOUNT_SHEET_ALIGNED,
    ScreenshotScreen.AIM_SW_GOTO_ALIGNED, ScreenshotScreen.AIM_SW_SYNC_ALIGNED -> 2
    else -> null
}

/**
 * Debug screenshots only (it does nothing otherwise): the `mount-control` and `mount-sw-*` screens of Settings ▸ Mount
 * Control. The Sky-Watcher forms (not connected) put the form on that mount type, `mount-sw-equatorial` on the
 * Equatorial arrangement with the advanced options open; the others connect a simulated mount, the Sky-Watcher one
 * aligned on as many stars as the screen stands for.
 */
@Composable
internal fun MountControlScreenshotEffect() {
    val app = LocalAppModel.current
    val service = rememberMountService()
    val draft = rememberMountDraft(service)
    ScreenshotScreenEffect(
        ScreenshotScreen.MOUNT_CONTROL, ScreenshotScreen.MOUNT_SW_SETUP, ScreenshotScreen.MOUNT_SW_EQUATORIAL,
        ScreenshotScreen.MOUNT_SW_CONTROL, ScreenshotScreen.MOUNT_SW_ALIGNED_1, ScreenshotScreen.MOUNT_SW_ALIGNED_2,
    ) { screen ->
        val stars = screen.alignmentStars()
        when {
            stars != null -> service.connectSkyWatcherDemo(app, stars)
            screen == ScreenshotScreen.MOUNT_CONTROL -> service.connectDemoAndWait()
            else -> {
                val isEquatorial = screen == ScreenshotScreen.MOUNT_SW_EQUATORIAL
                service.chooseArrangement(if (isEquatorial) SkyWatcherMountMode.EQUATORIAL else null)
                draft.selectProtocol(MountProtocol.SKYWATCHER)
                draft.showsAdvanced = isEquatorial
            }
        }
    }
}

/**
 * Debug screenshots only (it does nothing otherwise): the `aim-*` and `aim-sw-*` screens connect a simulated mount (the
 * Sky-Watcher one with the alignment they stand for), wait for the Aim target and open the sheet (or start the slew)
 * they stand for.
 */
@Composable
internal fun MountScreenshotEffect(sheets: MountAimState, target: CelestialObject?) {
    val app = LocalAppModel.current
    val service = rememberMountService()
    val currentTarget by rememberUpdatedState(target)
    ScreenshotScreenEffect(
        ScreenshotScreen.AIM_MOUNT, ScreenshotScreen.AIM_SLEW, ScreenshotScreen.AIM_MOUNT_SHEET,
        ScreenshotScreen.AIM_GOTO, ScreenshotScreen.AIM_SYNC, ScreenshotScreen.AIM_NUDGE,
        ScreenshotScreen.AIM_SW_MOUNT_SHEET, ScreenshotScreen.AIM_SW_MOUNT_SHEET_ALIGNED, ScreenshotScreen.AIM_SW_GOTO,
        ScreenshotScreen.AIM_SW_GOTO_ALIGNED, ScreenshotScreen.AIM_SW_SYNC, ScreenshotScreen.AIM_SW_SYNC_ALIGNED,
    ) { screen ->
        val stars = screen.alignmentStars()
        if (stars == null) service.connectDemoAndWait() else service.connectSkyWatcherDemo(app, stars)
        val goal = snapshotFlow { currentTarget }.filterNotNull().first()
        when (screen) {
            ScreenshotScreen.AIM_MOUNT_SHEET, ScreenshotScreen.AIM_SW_MOUNT_SHEET,
            ScreenshotScreen.AIM_SW_MOUNT_SHEET_ALIGNED -> sheets.open(MountSheetKind.CONTROL)
            ScreenshotScreen.AIM_GOTO, ScreenshotScreen.AIM_SW_GOTO,
            ScreenshotScreen.AIM_SW_GOTO_ALIGNED -> sheets.open(MountSheetKind.GOTO)
            ScreenshotScreen.AIM_SYNC, ScreenshotScreen.AIM_SW_SYNC,
            ScreenshotScreen.AIM_SW_SYNC_ALIGNED -> sheets.open(MountSheetKind.SYNC)
            ScreenshotScreen.AIM_NUDGE -> sheets.open(MountSheetKind.NUDGE)
            ScreenshotScreen.AIM_SLEW -> app.observerContext?.observer?.let { observer ->
                service.goto(MountTargeting.target(goal, observer, app.clock.now))
            }
            else -> Unit
        }
    }
}

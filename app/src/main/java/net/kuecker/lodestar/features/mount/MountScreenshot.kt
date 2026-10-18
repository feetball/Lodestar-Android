package net.kuecker.lodestar.features.mount

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountTargeting
import net.kuecker.lodestar.shared.LocalAppModel

/** How long a screenshot waits for the simulated mount to answer, in milliseconds. */
private const val CONNECT_TIMEOUT_MS = 30_000L

/**
 * Debug screenshots only: connects the built-in simulated mount and waits until it has reported where it points.
 * Nothing is remembered of the connection.
 */
internal suspend fun MountService.connectDemoAndWait() {
    if (snapshot.state == MountConnectionState.DISCONNECTED) connect(preferences.lastConnection, demo = true)
    withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        snapshotFlow { snapshot }.first { it.isConnected && it.status.position != null }
    }
}

/**
 * Debug screenshots only (it does nothing otherwise): the `aim-*` screens connect the simulated mount, wait for the
 * Aim target and open the sheet (or start the slew) they stand for.
 */
@Composable
internal fun MountScreenshotEffect(sheets: MountAimState, target: CelestialObject?) {
    val app = LocalAppModel.current
    val service = rememberMountService()
    val currentTarget by rememberUpdatedState(target)
    ScreenshotScreenEffect(
        ScreenshotScreen.AIM_MOUNT, ScreenshotScreen.AIM_SLEW, ScreenshotScreen.AIM_MOUNT_SHEET,
        ScreenshotScreen.AIM_GOTO, ScreenshotScreen.AIM_SYNC, ScreenshotScreen.AIM_NUDGE,
    ) { screen ->
        service.connectDemoAndWait()
        val goal = snapshotFlow { currentTarget }.filterNotNull().first()
        when (screen) {
            ScreenshotScreen.AIM_MOUNT_SHEET -> sheets.open(MountSheetKind.CONTROL)
            ScreenshotScreen.AIM_GOTO -> sheets.open(MountSheetKind.GOTO)
            ScreenshotScreen.AIM_SYNC -> sheets.open(MountSheetKind.SYNC)
            ScreenshotScreen.AIM_NUDGE -> sheets.open(MountSheetKind.NUDGE)
            ScreenshotScreen.AIM_SLEW -> app.observerContext?.observer?.let { observer ->
                service.goto(MountTargeting.target(goal, observer, app.clock.now))
            }
            else -> Unit
        }
    }
}

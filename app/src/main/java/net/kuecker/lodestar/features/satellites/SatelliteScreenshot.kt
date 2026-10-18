package net.kuecker.lodestar.features.satellites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.aim.AimViewModel
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.services.SatellitePassItem
import net.kuecker.lodestar.services.SatellitePasses
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.rememberRetained

/** Debug screenshots only: the first pass that is not over at the app's time, once the satellite data and the place are there. */
private suspend fun firstPass(app: AppModel): SatellitePassItem? {
    val library = snapshotFlow { app.online.satellites }.filter { it.hasPassData }.first()
    val observer = snapshotFlow { app.observerContext?.observer }.filterNotNull().first()
    val now = app.clock.now
    val items = withContext(Dispatchers.Default) { SatellitePasses.compute(library, observer, now) }
    return SatellitePasses.upcoming(items, now).firstOrNull()
}

/**
 * Debug screenshots only (it does nothing otherwise): `screen=satellite-passes` opens the Passes screen and
 * `screen=satellite-detail` the detail of the first pass on top of it, through the same requests the cards make.
 */
@Composable
internal fun SatelliteScreenshotEffect(requests: SatelliteRequests) {
    val app = LocalAppModel.current
    ScreenshotScreenEffect(ScreenshotScreen.SATELLITES, ScreenshotScreen.SATELLITE_DETAIL) { screen ->
        val first = firstPass(app) ?: return@ScreenshotScreenEffect
        requests.open(SatelliteScreen.Passes)
        if (screen == ScreenshotScreen.SATELLITE_DETAIL) {
            // The Passes screen is pushed before the detail is asked for.
            snapshotFlow { requests.pending }.filter { it == null }.first()
            requests.open(SatelliteScreen.Detail(first))
        }
    }
}

/** How far from the satellite the camera is turned in the screenshot, in degrees of azimuth and of altitude. */
private const val CAMERA_OFFSET_AZIMUTH = 22.0
private const val CAMERA_OFFSET_ALTITUDE = 9.0

/**
 * Debug screenshots only (it does nothing otherwise): `screen=aim-satellite` makes Aim follow the first pass, with the
 * camera turned a little away from the satellite so that the guidance has something to say.
 */
@Composable
fun SatelliteAimScreenshotEffect(model: AimViewModel) {
    val app = LocalAppModel.current
    ScreenshotScreenEffect(ScreenshotScreen.AIM_SATELLITE) {
        val first = firstPass(app) ?: return@ScreenshotScreenEffect
        val observer = app.observerContext?.observer ?: return@ScreenshotScreenEffect
        val source = SatelliteFollowSource(first.satellite, first.name, observer, first)
        model.satellite.start(source, app.target?.id)
        source.fix(app.clock.now)?.let { fix ->
            val at = fix.horizontal
            app.motion.useScreenshotPointing(
                HorizontalCoordinate(at.azimuth - CAMERA_OFFSET_AZIMUTH, (at.altitude + CAMERA_OFFSET_ALTITUDE).coerceIn(0.0, 85.0)),
            )
        }
    }
}

/**
 * Debug screenshots only (it does nothing otherwise): `screen=aim-streak-sheet` opens the list of Starlink crossings as
 * soon as the warning has a result.
 */
@Composable
fun StreakScreenshotEffect() {
    val monitor = rememberRetained("satellites.streak") { StreakMonitor() }
    ScreenshotScreenEffect(ScreenshotScreen.AIM_STREAK_SHEET) {
        snapshotFlow { monitor.state }.filter { it is StreakState.Ready }.first()
        monitor.isSheetOpen = true
    }
}

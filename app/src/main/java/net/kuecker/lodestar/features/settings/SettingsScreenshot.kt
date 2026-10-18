package net.kuecker.lodestar.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.horizon.HorizonRecordingScreen
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.NavStack

/**
 * Debug screenshots only (it does nothing otherwise): opens the screen of Settings that `screen=` asks for. The mount
 * and the planners are pushed onto the stack; the skyline recording covers the screen, as it does from the site list.
 */
@Composable
internal fun SettingsScreenshotEffect(stack: NavStack<SettingsRoute>) {
    val app = LocalAppModel.current
    var recordsSkyline by remember { mutableStateOf(false) }
    ScreenshotScreenEffect(
        ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL, ScreenshotScreen.STAR_TRAILS,
        ScreenshotScreen.TIME_LAPSE, ScreenshotScreen.SKYLINE,
    ) { screen ->
        when (screen) {
            ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL -> stack.push(SettingsRoute.MountControl)
            ScreenshotScreen.STAR_TRAILS, ScreenshotScreen.TIME_LAPSE -> stack.push(SettingsRoute.TrailsTimeLapse)
            else -> recordsSkyline = true
        }
    }
    val site = (app.settings.locationSource as? LocationSource.Site)
        ?.let { source -> app.settings.savedSites.firstOrNull { it.id == source.id } }
    if (recordsSkyline && site != null) HorizonRecordingScreen(site, onClose = { recordsSkyline = false })
}

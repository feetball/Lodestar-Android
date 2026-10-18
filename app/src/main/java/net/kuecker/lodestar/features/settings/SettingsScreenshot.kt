package net.kuecker.lodestar.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.horizon.HorizonRecordingScreen
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.NavStack

/**
 * Debug screenshots only (it does nothing otherwise): opens the screen of Settings that `screen=` asks for. The mount
 * and the planners are pushed onto the stack; the skyline recording covers the screen, as it does from the site list,
 * and the site editor is a sheet, as it is there.
 */
@Composable
internal fun SettingsScreenshotEffect(stack: NavStack<SettingsRoute>) {
    val app = LocalAppModel.current
    var recordsSkyline by remember { mutableStateOf(false) }
    var editsSite by remember { mutableStateOf(false) }
    ScreenshotScreenEffect(
        ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL, ScreenshotScreen.STAR_TRAILS,
        ScreenshotScreen.TIME_LAPSE, ScreenshotScreen.SKYLINE, ScreenshotScreen.SITE_EDITOR,
    ) { screen ->
        when (screen) {
            ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL -> stack.push(SettingsRoute.MountControl)
            ScreenshotScreen.STAR_TRAILS, ScreenshotScreen.TIME_LAPSE -> stack.push(SettingsRoute.TrailsTimeLapse)
            ScreenshotScreen.SITE_EDITOR -> editsSite = true
            else -> recordsSkyline = true
        }
    }
    val site = (app.settings.locationSource as? LocationSource.Site)
        ?.let { source -> app.settings.savedSites.firstOrNull { it.id == source.id } }
    if (recordsSkyline && site != null) HorizonRecordingScreen(site, onClose = { recordsSkyline = false })
    if (editsSite && site != null) {
        LodestarSheet(onDismiss = { editsSite = false }) {
            SiteEditor(SiteEditorTarget.Existing(site), onClose = LocalSheetDismiss.current, modifier = Modifier.weight(1f))
        }
    }
}

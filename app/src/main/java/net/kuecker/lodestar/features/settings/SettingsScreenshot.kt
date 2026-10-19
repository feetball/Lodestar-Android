package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.horizon.HorizonRecordingScreen
import net.kuecker.lodestar.model.LocationSource
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.NavStack
import net.kuecker.lodestar.shared.rememberRetained

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
    val scroll = rememberRetained("settings.scroll") { ScrollState(0) }
    ScreenshotScreenEffect(
        ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL, ScreenshotScreen.STAR_TRAILS,
        ScreenshotScreen.TIME_LAPSE, ScreenshotScreen.SKYLINE, ScreenshotScreen.SITE_EDITOR,
        ScreenshotScreen.SETTINGS_BOTTOM, ScreenshotScreen.MOUNT_SW_SETUP, ScreenshotScreen.MOUNT_SW_EQUATORIAL,
        ScreenshotScreen.MOUNT_SW_CONTROL, ScreenshotScreen.MOUNT_SW_ALIGNED_1, ScreenshotScreen.MOUNT_SW_ALIGNED_2,
    ) { screen ->
        when (screen) {
            ScreenshotScreen.SETTINGS_BOTTOM -> {
                // The root has been laid out when it knows how far it scrolls; the feeds named by `online=` fill in later.
                snapshotFlow { scroll.maxValue }.first { it > 0 }
                delay(SETTLE_MS)
                scroll.scrollTo(scroll.maxValue)
            }
            ScreenshotScreen.MOUNT_SETUP, ScreenshotScreen.MOUNT_CONTROL, ScreenshotScreen.MOUNT_SW_SETUP,
            ScreenshotScreen.MOUNT_SW_EQUATORIAL, ScreenshotScreen.MOUNT_SW_CONTROL, ScreenshotScreen.MOUNT_SW_ALIGNED_1,
            ScreenshotScreen.MOUNT_SW_ALIGNED_2 -> stack.push(SettingsRoute.MountControl)
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

/** The time the sections of the root get to change size (the feeds fill in), in milliseconds, before it scrolls to the end. */
private const val SETTLE_MS = 2_500L

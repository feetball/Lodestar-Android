package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.BuildConfig
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

private const val PRIVACY_STATEMENT =
    "Everything Lodestar does happens on your phone. There are no accounts, no analytics and no tracking, and " +
        "Lodestar never collects your location, sites or settings. The network is used by OpenFreeMap, which " +
        "loads map tiles when you open the site map, by your phone's geocoding service (usually Google's), " +
        "which receives a site's coordinates to look up its country and time zone while you add or edit it, and " +
        "by the optional downloads in Online Data, which are off until you switch them on: CelesTrak, the Minor " +
        "Planet Center and MET Norway see your IP address, and MET Norway also your location rounded to about 10 km."

/**
 * Settings ▸ About: version, accuracy notes, the privacy statement, data credits and licences, and the sensor
 * diagnostics.
 */
@Composable
internal fun SettingsAboutSection(onOpen: (SettingsRoute) -> Unit) {
    val palette = LocalPalette.current
    SettingsSection(title = "About") {
        SettingsRow { InfoRow("Version", SettingsAppInfo.versionDescription) }
        SettingsDivider()
        SettingsNavigationRow("Accuracy Notes", Icons.Outlined.GpsFixed) { onOpen(SettingsRoute.AccuracyNotes) }
        SettingsDivider()
        SettingsRow {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle("Privacy", icon = Icons.Outlined.PanTool)
                Text(PRIVACY_STATEMENT, color = palette.primaryText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        SettingsDivider()
        SettingsNavigationRow("Data Credits & Licenses", Icons.AutoMirrored.Outlined.LibraryBooks) {
            onOpen(SettingsRoute.DataCredits)
        }
        SettingsDivider()
        SettingsNavigationRow("Sensor Diagnostics", Icons.Outlined.MonitorHeart) { onOpen(SettingsRoute.SensorDiagnostics) }
    }
}

/** Facts about the running app taken from its build. */
internal object SettingsAppInfo {
    /** "1.0 (12)": the version name and the version code. */
    val versionDescription: String get() = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
}

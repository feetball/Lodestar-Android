package net.kuecker.lodestar.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import net.kuecker.lodestar.kit.ephemeris.GreatRedSpot
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.model.GreatRedSpotSetting
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsOptionalNumberField
import net.kuecker.lodestar.shared.SettingsSection
import java.time.Instant
import java.time.ZoneId

/**
 * Settings ▸ Great Red Spot: the spot's System II longitude, which times its transits in the events calendar and on
 * Jupiter's screen. Empty means the kit's dated default; a number counts from the day it was entered.
 */
@Composable
internal fun SettingsJupiterSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val override = app.settings.greatRedSpot
    val today = remember { Instant.now() }
    val zone = ZoneId.systemDefault()
    val reference = GreatRedSpot.reference
    val defaultToday = AngleFormatter.formatDegrees(reference.systemIILongitude(today), 0)
    val current = override?.spot ?: reference
    SettingsSection(
        title = "Great Red Spot",
        footer = "The spot drifts by a few degrees a month, which moves its transit by a few minutes. The default is " +
            "Sky & Telescope's for 2026: ${AngleFormatter.formatDegrees(GreatRedSpot.referenceLongitude, 0)} on " +
            "June 1, 2026, drifting 1.75° a month, $defaultToday today. To use the longitude from a recent image or table, enter it here: " +
            "it counts from today.",
    ) {
        SettingsOptionalNumberField(
            title = "System II longitude",
            value = override?.longitude,
            onValueChange = { value ->
                store.update { it.copy(greatRedSpot = value?.let { longitude -> GreatRedSpotSetting(longitude, Instant.now()) }) }
            },
            range = 0.0..360.0,
            unit = "°",
            fractionDigits = 0,
            placeholder = "Default",
            caption = "Now ${AngleFormatter.formatDegrees(current.systemIILongitude(today), 0)}" +
                if (override == null) " (default)" else " (yours, entered ${AppFormat.day(override.asOf, zone)})",
        )
        if (override != null) {
            SettingsDivider()
            SettingsActionRow("Reset to Default", onClick = { store.update { it.copy(greatRedSpot = null) } }, icon = Icons.Outlined.RestartAlt)
        }
    }
}

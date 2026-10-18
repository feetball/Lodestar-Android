package net.kuecker.lodestar.features.settings

import androidx.compose.runtime.Composable
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.SettingsToggleRow

/**
 * Settings ▸ Night Mode: when the red night palette is used, how bright it is, screen dimming, the camera at night,
 * the experimental red overlay, and the guide to dimming the whole phone.
 */
@Composable
internal fun SettingsNightSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    val modeOptions = NightModeSetting.entries.map { SettingsMenuOption(it.title, it) }
    SettingsSection(
        title = "Night Mode",
        footer = "Night mode draws Lodestar in pure red on black so your eyes stay adapted to the dark.",
    ) {
        SettingsRow {
            SettingsSegmentedPicker(
                title = "Night mode",
                selection = settings.nightMode,
                options = modeOptions,
                onSelect = { mode -> store.update { it.copy(nightMode = mode) } },
            )
            SettingsCaption(statusText(settings.nightMode, hasLocation = app.observerContext != null, isActive = app.isNightModeActive))
        }
        SettingsDivider()
        SettingsSliderRow(
            title = "Red brightness",
            valueText = AppFormat.percent(settings.nightIntensity),
            value = settings.nightIntensity,
            onValueChange = { value -> store.update { it.copy(nightIntensity = value) } },
            range = 0.25..1.0,
            step = 0.05,
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Dim the screen",
            checked = settings.dimScreenAtNight,
            onCheckedChange = { enabled -> store.update { it.copy(dimScreenAtNight = enabled) } },
            caption = "Lowers the screen brightness while night mode is on and gives it back afterwards.",
        )
        if (settings.dimScreenAtNight) {
            SettingsDivider()
            SettingsSliderRow(
                title = "Screen brightness",
                valueText = AppFormat.percent(settings.nightBrightness),
                value = settings.nightBrightness,
                onValueChange = { value -> store.update { it.copy(nightBrightness = value) } },
                range = 0.01..1.0,
                step = 0.01,
            )
        }
        SettingsDivider()
        SettingsToggleRow(
            title = "Camera preview at night",
            checked = settings.cameraPreviewAtNight,
            onCheckedChange = { enabled -> store.update { it.copy(cameraPreviewAtNight = enabled) } },
            caption = "Shows the camera picture in red tones behind the sky. It is brighter than the sky overlay " +
                "alone, so it costs some night vision.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Red screen overlay (experimental)",
            checked = settings.redFilterOverlay,
            onCheckedChange = { enabled -> store.update { it.copy(redFilterOverlay = enabled) } },
            caption = "Multiplies Lodestar's whole screen with pure red, so anything that is not red already, such " +
                "as maps, turns red as well. It does not reach the keyboard or system dialogs and only works in " +
                "night mode. The phone-wide settings below are more reliable.",
        )
    }
    SettingsSection(title = "Night Vision for the Whole Phone") {
        SettingsRow { ColorFiltersGuideCard(showsTitle = false) }
    }
}

private fun statusText(mode: NightModeSetting, hasLocation: Boolean, isActive: Boolean): String = when (mode) {
    NightModeSetting.OFF -> "Normal colors at all times."
    NightModeSetting.ON -> "Red at all times."
    NightModeSetting.AUTOMATIC -> {
        val rule = "Switches on when the Sun is more than 6° below the horizon."
        when {
            !hasLocation -> rule
            isActive -> "On now. $rule"
            else -> "Off now. $rule"
        }
    }
}

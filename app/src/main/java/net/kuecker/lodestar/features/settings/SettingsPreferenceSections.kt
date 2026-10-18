package net.kuecker.lodestar.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Timelapse
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.SettingsToggleRow

/** Settings ▸ Feedback: haptics, audio cues and keeping the screen awake while aiming. */
@Composable
internal fun SettingsFeedbackSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    SettingsSection(title = "Feedback") {
        SettingsToggleRow(
            title = "Haptics",
            checked = settings.hapticsEnabled,
            onCheckedChange = { enabled -> store.update { it.copy(hapticsEnabled = enabled) } },
            caption = "A tap when the camera reaches the target and when it leaves it.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Audio cues",
            checked = settings.audioCuesEnabled,
            onCheckedChange = { enabled -> store.update { it.copy(audioCuesEnabled = enabled) } },
            caption = "Ticks that speed up and rise in pitch as the camera nears the target, and a chime on target. " +
                "Their volume follows the phone's volume settings.",
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Keep screen awake",
            checked = settings.keepScreenAwake,
            onCheckedChange = { enabled -> store.update { it.copy(keepScreenAwake = enabled) } },
            caption = "Stops the phone's screen from turning off while the Aim screen is open.",
        )
    }
}

/** The levels of the deep-sky detail setting, as stored in `AppSettings.deepSkyProminence` (1 … 4). */
private val deepSkyDetailOptions = listOf(
    SettingsMenuOption("Showpieces", 1),
    SettingsMenuOption("Prominent", 2),
    SettingsMenuOption("Notable", 3),
    SettingsMenuOption("Everything", 4),
)

private const val DEEP_SKY_DETAIL_CAPTION =
    "Which deep-sky objects the Aim screen and the sky chart draw. Showpieces: the 250 or so best known, such as " +
        "the Messier and Caldwell objects. Prominent: about 490, adding other bright or large ones. Notable: about " +
        "1,000. Everything: about 2,400, including more than a thousand faint objects and dark nebulae, which " +
        "crowds the sky."

/** Settings ▸ Sky Display: what the Aim screen and the sky chart draw. */
@Composable
internal fun SettingsSkyDisplaySection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    val styleOptions = CoordinateStyle.entries.map { SettingsMenuOption(it.title, it) }
    SettingsSection(title = "Sky Display") {
        SettingsToggleRow(
            title = "Constellation lines",
            checked = settings.showConstellationLines,
            onCheckedChange = { shown -> store.update { it.copy(showConstellationLines = shown) } },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Constellation names",
            checked = settings.showConstellationNames,
            onCheckedChange = { shown -> store.update { it.copy(showConstellationNames = shown) } },
        )
        SettingsDivider()
        SettingsToggleRow(
            title = "Deep-sky objects",
            checked = settings.showDeepSkyObjects,
            onCheckedChange = { shown -> store.update { it.copy(showDeepSkyObjects = shown) } },
        )
        if (settings.showDeepSkyObjects) {
            SettingsDivider()
            SettingsMenuPicker(
                title = "Deep-sky detail",
                selection = settings.deepSkyProminence,
                options = deepSkyDetailOptions,
                onSelect = { level -> store.update { it.copy(deepSkyProminence = level) } },
            )
            SettingsDivider()
            SettingsRow { SettingsCaption(DEEP_SKY_DETAIL_CAPTION) }
        }
        SettingsDivider()
        SettingsToggleRow(
            title = "Milky Way",
            checked = settings.showMilkyWay,
            onCheckedChange = { shown -> store.update { it.copy(showMilkyWay = shown) } },
        )
        SettingsDivider()
        SettingsSliderRow(
            title = "Faintest stars",
            valueText = AppFormat.magnitude(settings.starMagnitudeLimit),
            value = settings.starMagnitudeLimit,
            onValueChange = { value -> store.update { it.copy(starMagnitudeLimit = value) } },
            range = 1.0..6.5,
            step = 0.5,
        )
        SettingsDivider()
        SettingsRow {
            SettingsCaption(
                "Stars fainter than this are left out. Under a dark sky the eye reaches about magnitude 6.",
            )
        }
        SettingsDivider()
        SettingsRow {
            SettingsSegmentedPicker(
                title = "Coordinates",
                selection = settings.coordinateStyle,
                options = styleOptions,
                onSelect = { style -> store.update { it.copy(coordinateStyle = style) } },
            )
        }
    }
}

/** Settings ▸ Atmosphere: the weather values that decide how much the air lifts objects near the horizon. */
@Composable
internal fun SettingsAtmosphereSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    SettingsSection(
        title = "Atmosphere",
        footer = "The air bends light, so objects near the horizon look higher than they are. Lodestar corrects for " +
            "it with these values; the defaults (10 °C, 1010 hPa) suit most places.",
    ) {
        SettingsNumberField(
            title = "Temperature", value = settings.temperatureCelsius,
            onValueChange = { value -> store.update { it.copy(temperatureCelsius = value) } },
            range = -60.0..60.0, unit = "°C", fractionDigits = 0,
        )
        SettingsDivider()
        SettingsNumberField(
            title = "Pressure", value = settings.pressureMillibars,
            onValueChange = { value -> store.update { it.copy(pressureMillibars = value) } },
            range = 300.0..1100.0, unit = "hPa", fractionDigits = 0,
        )
    }
}

/** Settings ▸ Tools: links to the exposure calculator, the star trails and time-lapse planner, and polar alignment. */
@Composable
internal fun SettingsToolsSection(onOpen: (SettingsRoute) -> Unit) {
    SettingsSection(title = "Tools") {
        SettingsNavigationRow("Exposure Calculator", Icons.Outlined.Timer) { onOpen(SettingsRoute.ExposureCalculator) }
        SettingsDivider()
        SettingsNavigationRow("Star Trails & Time-Lapse", Icons.Outlined.Timelapse) {
            onOpen(SettingsRoute.TrailsTimeLapse)
        }
        SettingsDivider()
        SettingsNavigationRow("Polar Alignment", Icons.Outlined.AutoAwesome) { onOpen(SettingsRoute.PolarAlignment) }
    }
}

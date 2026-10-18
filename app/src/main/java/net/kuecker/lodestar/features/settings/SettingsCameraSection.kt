package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsOptionalNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.theme.LocalPalette

/** The identifier stored when the user picks a sensor that is not a preset. */
private const val CUSTOM_SENSOR_ID = "custom"

/** Range accepted for a custom sensor's width and height; it matches what the settings keep on load. */
private val customSensorRange = 1.0..100.0

/** Range accepted for the pixel pitch; it matches what the settings keep on load. */
private val pixelPitchRange = 0.5..20.0

/** Range accepted for the camera's resolution in megapixels. */
private val megapixelsRange = 1.0..500.0

/**
 * Settings ▸ Camera & Lens: the sensor, lens and pixel size of the camera on the tripod, used for the framing
 * rectangle on the Aim screen and by the exposure tools, with a live field-of-view readout.
 */
@Composable
internal fun SettingsCameraSection() {
    val app = LocalAppModel.current
    val store = app.settingsStore
    val settings = app.settings
    val isCustomSensor = SensorFormat.presets.none { it.id == settings.sensorID }
    SettingsSection(
        title = "Camera & Lens",
        footer = "Describe the camera on the tripod, not the phone. The framing rectangle on the Aim screen and " +
            "the exposure tools use these values.",
    ) {
        SettingsMenuPicker(
            title = "Sensor",
            selection = settings.sensorID,
            options = sensorOptions(settings.sensorID),
            onSelect = { id -> store.update { it.copy(sensorID = id) } },
        )
        if (isCustomSensor) {
            SettingsDivider()
            SettingsNumberField(
                title = "Sensor width", value = settings.customSensorWidth,
                onValueChange = { value -> store.update { it.copy(customSensorWidth = value) } },
                range = customSensorRange, unit = "mm", fractionDigits = 1,
            )
            SettingsDivider()
            SettingsNumberField(
                title = "Sensor height", value = settings.customSensorHeight,
                onValueChange = { value -> store.update { it.copy(customSensorHeight = value) } },
                range = customSensorRange, unit = "mm", fractionDigits = 1,
            )
        }
        SettingsDivider()
        SettingsNumberField(
            title = "Focal length", value = settings.focalLength,
            onValueChange = { value -> store.update { it.copy(focalLength = value) } },
            range = LensSetup.focalLengthRange, unit = "mm", fractionDigits = 1,
        )
        SettingsDivider()
        SettingsNumberField(
            title = "Aperture", value = settings.aperture,
            onValueChange = { value -> store.update { it.copy(aperture = value) } },
            range = LensSetup.apertureRange, prefix = "f/", fractionDigits = 2,
        )
        SettingsDivider()
        SettingsOptionalNumberField(
            title = "Pixel pitch", value = settings.pixelPitch,
            onValueChange = { value -> store.update { it.copy(pixelPitch = value) } },
            range = pixelPitchRange, unit = "µm", fractionDigits = 2,
        )
        SettingsDivider()
        SettingsMegapixelsHelper(
            sensor = app.lensSetup.sensor,
            onUse = { pitch -> store.update { it.copy(pixelPitch = pitch) } },
        )
        SettingsDivider()
        SettingsFieldOfViewReadout(app.lensSetup)
        SettingsDivider()
        SettingsToggleRow(
            title = "Camera preview",
            checked = settings.cameraPreviewEnabled,
            onCheckedChange = { enabled -> store.update { it.copy(cameraPreviewEnabled = enabled) } },
            caption = "Shows the phone's camera behind the sky on the Aim screen. Lodestar asks for camera access " +
                "the first time you turn it on.",
        )
    }
}

/**
 * The presets, then "Custom". A stored identifier that is not a preset stands for the custom size, so it is the
 * value of the "Custom" choice.
 */
private fun sensorOptions(storedID: String): List<SettingsMenuOption<String>> {
    val options = SensorFormat.presets.map { SettingsMenuOption(it.name, it.id) }
    val isPreset = SensorFormat.presets.any { it.id == storedID }
    return options + SettingsMenuOption("Custom", if (isPreset) CUSTOM_SENSOR_ID else storedID)
}

/** Works out the pixel pitch from the camera's resolution: enter the megapixels, then apply the result. */
@Composable
private fun SettingsMegapixelsHelper(sensor: SensorFormat, onUse: (Double) -> Unit) {
    val palette = LocalPalette.current
    var megapixels by remember { mutableStateOf<Double?>(null) }
    // The pitch for the entered resolution on the chosen sensor, when it is plausible.
    val derived = megapixels?.let { sensor.pixelPitchMicrometers(it) }?.takeIf { it in pixelPitchRange }
    SettingsOptionalNumberField(
        title = "Resolution", value = megapixels, onValueChange = { megapixels = it },
        range = megapixelsRange, unit = "MP", fractionDigits = 1, placeholder = "e.g. 45",
    )
    SettingsDivider()
    SettingsRow {
        if (derived != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pixel pitch ${SettingsFormat.decimal(derived, 2)} µm",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                LodestarButton("Use", onClick = { onUse(derived) }, kind = LodestarButtonKind.SECONDARY)
            }
        } else {
            SettingsCaption(
                "Not sure of the pixel pitch? Enter the camera's resolution and Lodestar works it out from the " +
                    "sensor size. The NPF exposure rule needs it.",
            )
        }
    }
}

/** The field of view of the chosen camera and lens, the full-frame equivalent focal length and the image scale. */
@Composable
private fun SettingsFieldOfViewReadout(setup: LensSetup) {
    val frame = setup.fieldOfView
    SettingsRow {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(
                "Field of view (landscape)",
                "${AngleFormatter.formatDegrees(frame.horizontal)} × ${AngleFormatter.formatDegrees(frame.vertical)}",
            )
            InfoRow("Diagonal", AngleFormatter.formatDegrees(frame.diagonal))
            InfoRow("Full-frame equivalent", "${setup.equivalentFocalLengthMillimeters.roundedHalfAwayFromZero().toInt()} mm")
            setup.imageScaleArcsecondsPerPixel?.let { scale ->
                InfoRow("Image scale", "${SettingsFormat.decimal(scale, 1)}″ per pixel")
            }
        }
    }
}

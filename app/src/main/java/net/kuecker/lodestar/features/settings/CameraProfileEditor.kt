package net.kuecker.lodestar.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.phototools.CameraPreset
import net.kuecker.lodestar.kit.phototools.LensProjection
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.model.CameraProfile
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsDivider
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsNumberText
import net.kuecker.lodestar.shared.SettingsOptionalNumberField
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.theme.LocalPalette

/** What the camera profile editor starts from. */
internal sealed interface CameraProfileEditorTarget {
    /** A saved profile. */
    data class Existing(val profile: CameraProfile) : CameraProfileEditorTarget

    /** A new profile from a preset. */
    data class FromPreset(val preset: CameraPreset) : CameraProfileEditorTarget

    /** A new profile from what a photo says; the editor shows what was read. */
    data class FromPhoto(val result: CameraPhotoResult) : CameraProfileEditorTarget
}

/** Range accepted for the camera's resolution in megapixels. */
private val megapixelsRange = 1.0..500.0

/**
 * Adds or edits a camera profile: its name, sensor, lens and pixel pitch, with the field of view they give. Show it
 * inside a `LodestarSheet` and give it `Modifier.weight(1f)`; Save adds a new profile (and makes it the active one) or
 * changes the saved one, Cancel keeps everything as it was.
 */
@Composable
internal fun CameraProfileEditor(target: CameraProfileEditorTarget, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val store = app.settingsStore
    val existing = (target as? CameraProfileEditorTarget.Existing)?.profile
    val initial = remember(target) {
        when (target) {
            is CameraProfileEditorTarget.Existing -> target.profile
            is CameraProfileEditorTarget.FromPreset -> CameraProfile.fromPreset(target.preset)
            is CameraProfileEditorTarget.FromPhoto -> CameraProfile.fromPhoto(target.result.suggestion)
        }
    }
    var profile by remember(target) { mutableStateOf(initial) }
    var nameText by remember(target) { mutableStateOf(initial.name) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    var isConfirmingDelete by remember { mutableStateOf(false) }
    val hasOtherCameras = app.settings.cameraProfiles.size > 1

    fun save() {
        val saved = profile.edited(name = nameText)
        if (existing != null) {
            store.updateCameraProfile(saved)
        } else if (!store.addCameraProfile(saved)) {
            failureMessage = "You can keep up to ${AppSettings.MAXIMUM_CAMERA_PROFILES} profiles. Delete one to add another."
            return
        }
        onClose()
    }

    Column(modifier.fillMaxWidth()) {
        CameraSheetBar(
            title = if (existing == null) "New Camera" else "Edit Camera",
            onCancel = onClose,
            onSave = ::save,
        )
        Column(Modifier.weight(1f).imePadding().verticalScroll(rememberScrollState())) {
            SettingsSection(title = "Name") {
                SettingsRow {
                    CameraNameField(
                        value = nameText,
                        onValueChange = { nameText = it.take(CameraProfile.MAXIMUM_NAME_LENGTH) },
                    )
                    failureMessage?.let {
                        Text(it, color = palette.critical, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (target is CameraProfileEditorTarget.FromPhoto) CameraPhotoSummary(target.result)
            SettingsSection(
                title = "Sensor",
                footer = "The pixel pitch is the spacing of the photosites. The NPF exposure rule and the image scale need it.",
            ) {
                SettingsMenuPicker(
                    title = "Sensor",
                    selection = profile.sensorID,
                    options = sensorOptions(),
                    onSelect = { id -> profile = profile.edited(sensorID = id) },
                )
                if (profile.isCustomSensor) {
                    SettingsDivider()
                    SettingsNumberField(
                        title = "Sensor width", value = profile.customSensorWidth,
                        onValueChange = { profile = profile.edited(customSensorWidth = it) },
                        range = CameraProfile.customSensorRange, unit = "mm", fractionDigits = 1,
                    )
                    SettingsDivider()
                    SettingsNumberField(
                        title = "Sensor height", value = profile.customSensorHeight,
                        onValueChange = { profile = profile.edited(customSensorHeight = it) },
                        range = CameraProfile.customSensorRange, unit = "mm", fractionDigits = 1,
                    )
                }
                SettingsDivider()
                SettingsOptionalNumberField(
                    title = "Pixel pitch", value = profile.pixelPitch,
                    onValueChange = { profile = profile.edited(pixelPitch = it) },
                    range = CameraProfile.pixelPitchRange, unit = "µm", fractionDigits = 2,
                )
                SettingsDivider()
                SettingsMegapixelsHelper(
                    sensor = profile.sensor,
                    onUse = { pitch -> profile = profile.edited(pixelPitch = pitch) },
                )
            }
            SettingsSection(title = "Lens") {
                SettingsMenuPicker(
                    title = "Lens type",
                    selection = profile.projection,
                    options = LensProjection.entries.map { SettingsMenuOption(it.displayName, it) },
                    caption = "A fisheye lens bends straight lines and sees a much wider view; its frame is drawn curved.",
                    onSelect = { projection ->
                        profile = profile.edited(
                            projection = projection,
                            imageCircleDiameter = if (projection.isFisheye) profile.imageCircleDiameter else null,
                        )
                    },
                )
                SettingsDivider()
                SettingsNumberField(
                    title = "Focal length", value = profile.focalLength,
                    onValueChange = { profile = profile.edited(focalLength = it) },
                    range = LensSetup.focalLengthRange, unit = "mm", fractionDigits = 1,
                )
                SettingsDivider()
                SettingsNumberField(
                    title = "Aperture", value = profile.aperture,
                    onValueChange = { profile = profile.edited(aperture = it) },
                    range = LensSetup.apertureRange, prefix = "f/", fractionDigits = 2,
                )
                if (profile.projection.isFisheye) {
                    SettingsDivider()
                    SettingsOptionalNumberField(
                        title = "Image circle", value = profile.imageCircleDiameter,
                        onValueChange = { profile = profile.edited(imageCircleDiameter = it) },
                        range = LensSetup.imageCircleRange, unit = "mm", fractionDigits = 1,
                        placeholder = "Fills the sensor",
                        caption = "For a circular fisheye whose picture is a circle smaller than the sensor: its " +
                            "diameter on the sensor. The frame is then that circle.",
                    )
                }
            }
            SettingsSection(title = "Frame") { SettingsFieldOfViewReadout(profile.lensSetup) }
            if (hasOtherCameras && existing != null) {
                SettingsSection(title = "Delete") {
                    if (isConfirmingDelete) {
                        SettingsRow { SettingsCaption("Delete “${existing.name}”? This cannot be undone.") }
                        SettingsDivider()
                        SettingsActionRow(
                            title = "Delete Camera",
                            isDestructive = true,
                            onClick = {
                                store.removeCameraProfile(existing.id)
                                onClose()
                            },
                        )
                        SettingsDivider()
                        SettingsActionRow(title = "Keep Camera", onClick = { isConfirmingDelete = false })
                    } else {
                        SettingsActionRow(title = "Delete Camera…", isDestructive = true, onClick = { isConfirmingDelete = true })
                    }
                }
            }
        }
    }
}

/** The presets, then "Custom"; a profile's own identifier for a custom size is "custom". */
private fun sensorOptions(): List<SettingsMenuOption<String>> =
    SensorFormat.presets.map { SettingsMenuOption(it.name, it.id) } +
        SettingsMenuOption("Custom", CameraProfile.CUSTOM_SENSOR_ID)

/** The sheet's title bar: Cancel, the title, and Save. */
@Composable
private fun CameraSheetBar(title: String, onCancel: () -> Unit, onSave: (() -> Unit)?) {
    val palette = LocalPalette.current
    val colors = ButtonDefaults.textButtonColors(contentColor = palette.accent, disabledContentColor = palette.tertiaryText)
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onCancel, colors = colors) { Text("Cancel") }
        Text(
            title,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (onSave != null) {
            TextButton(onClick = onSave, colors = colors) { Text("Save", fontWeight = FontWeight.SemiBold) }
        } else {
            Spacer(Modifier.width(64.dp))
        }
    }
}

/** A single-line text field for the profile's name, drawn with palette colours. */
@Composable
private fun CameraNameField(value: String, onValueChange: (String) -> Unit) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = palette.primaryText),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Camera name" },
        decorationBox = { field ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text("Camera name", color = palette.tertiaryText, style = MaterialTheme.typography.bodyLarge)
                }
                field()
            }
        },
    )
}

/** What the photo said, so the photographer can check it before saving; the picture itself is not kept. */
@Composable
private fun CameraPhotoSummary(result: CameraPhotoResult) {
    SettingsSection(
        title = "Read from the photo",
        footer = "Check the values below before you save. The sensor comes from the crop factor, and the pixel pitch " +
            "assumes the picture is neither cropped nor reduced. Lodestar does not keep the picture.",
    ) {
        SettingsRow {
            for ((label, value) in CameraPhotoText.lines(result)) InfoRow(label, value)
        }
    }
}

/** Works out the pixel pitch from the camera's resolution: enter the megapixels, then apply the result. */
@Composable
private fun SettingsMegapixelsHelper(sensor: SensorFormat, onUse: (Double) -> Unit) {
    val palette = LocalPalette.current
    var megapixels by remember { mutableStateOf<Double?>(null) }
    // The pitch for the entered resolution on the chosen sensor, when it is plausible.
    val derived = megapixels?.let { sensor.pixelPitchMicrometers(it) }?.takeIf { it in CameraProfile.pixelPitchRange }
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
internal fun SettingsFieldOfViewReadout(setup: LensSetup) {
    val frame = setup.fieldOfView
    SettingsRow {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow(
                "Field of view (landscape)",
                "${AngleFormatter.formatDegrees(frame.horizontal)} × ${AngleFormatter.formatDegrees(frame.vertical)}",
            )
            InfoRow("Diagonal", AngleFormatter.formatDegrees(frame.diagonal))
            if (setup.imageCircleDiameterMillimeters != null) InfoRow("Frame", "Circle")
            InfoRow("Full-frame equivalent", "${setup.equivalentFocalLengthMillimeters.roundedHalfAwayFromZero().toInt()} mm")
            setup.imageScaleArcsecondsPerPixel?.let { scale ->
                InfoRow("Image scale", "${SettingsFormat.decimal(scale, 1)}″ per pixel")
            }
        }
    }
}

/** The subtitle texts of camera profiles. */
internal object CameraProfileText {
    /** "24 mm · f/2.8 · Full Frame · 74° × 53°", with the lens type for a fisheye. */
    fun summary(profile: CameraProfile): String = summary(profile.lensSetup)

    /** [summary] of a camera and lens. */
    fun summary(setup: LensSetup): String {
        val frame = setup.fieldOfView
        val parts = mutableListOf(
            "${SettingsNumberText.text(setup.focalLengthMillimeters, 1)} mm",
            "f/${SettingsNumberText.text(setup.aperture, 2)}",
            setup.sensor.name,
        )
        if (setup.projection.isFisheye) parts.add(setup.projection.displayName)
        parts.add("${AngleFormatter.formatDegrees(frame.horizontal, 0)} × ${AngleFormatter.formatDegrees(frame.vertical, 0)}")
        return parts.joinToString(" · ")
    }
}

/**
 * The sheet that lists the presets: a plain full-frame camera to start from, and the smart telescopes. Picking one
 * calls [onPick]; the editor then opens with the preset's values.
 */
@Composable
internal fun CameraPresetPicker(onPick: (CameraPreset) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        CameraSheetBar(title = "Add Preset", onCancel = LocalSheetDismiss.current, onSave = null)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            SettingsSection(title = "Plain camera") { CameraPresetRow(CameraPreset.fullFrame24, onPick) }
            SettingsSection(
                title = "Smart telescopes",
                footer = "The numbers are the makers' published specifications. A smart telescope's sensor size is its " +
                    "pixel count times its pixel pitch.",
            ) {
                for ((index, preset) in CameraPreset.smartTelescopes.withIndex()) {
                    if (index > 0) SettingsDivider()
                    CameraPresetRow(preset, onPick)
                }
            }
        }
    }
}

@Composable
private fun CameraPresetRow(preset: CameraPreset, onPick: (CameraPreset) -> Unit) {
    val palette = LocalPalette.current
    SettingsRow(onClick = { onPick(preset) }) {
        Text(preset.name, color = palette.primaryText, style = MaterialTheme.typography.bodyLarge)
        Text(CameraProfileText.summary(preset.setup), color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

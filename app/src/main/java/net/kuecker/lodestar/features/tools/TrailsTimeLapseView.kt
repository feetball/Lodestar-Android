package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.kit.phototools.ExposureRule
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.phototools.StarTrailPlanner
import net.kuecker.lodestar.kit.phototools.TimeLapsePlanner
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.theme.LocalPalette

/** What the planner is working out. */
internal enum class TrailsMode(val title: String) {
    STAR_TRAILS("Star Trails"),
    TIME_LAPSE("Time-Lapse"),
}

/** The unit of a trail length asked for in "Time for a Chosen Trail". */
internal enum class ArcUnit(val title: String) {
    DEGREES("Degrees"),
    PIXELS("Pixels"),
}

/** What the length of a time-lapse is given by. */
internal enum class TimeLapseBasis(val title: String) {
    CLIP_LENGTH("Clip"),
    SHOOTING_TIME("Shooting"),
    FRAMES("Pictures"),
}

/**
 * The planner's inputs. The camera starts from the user's lens setup and is the planner's own copy: changes made here
 * never touch Settings.
 */
internal class TrailsInputs(camera: LensSetup) {
    var mode by mutableStateOf(TrailsMode.STAR_TRAILS)

    // Camera and lens
    var sensor by mutableStateOf(camera.sensor)
    var focalLength by mutableDoubleStateOf(camera.focalLengthMillimeters)
    var aperture by mutableDoubleStateOf(camera.aperture)
    var pixelPitch by mutableStateOf(camera.pixelPitchMicrometers)

    // Star trails
    var declination by mutableDoubleStateOf(0.0)
    var durationMinutes by mutableDoubleStateOf(60.0)
    var arcUnit by mutableStateOf(ArcUnit.DEGREES)
    var arcDegrees by mutableDoubleStateOf(10.0)
    var arcPixels by mutableDoubleStateOf(1000.0)
    var rule by mutableStateOf(ExposureRule.NPF)

    /** The length of each frame of the stack, or `null` for what the rule allows. */
    var frameSeconds by mutableStateOf<Double?>(null)
    var gapSeconds by mutableDoubleStateOf(StarTrailPlanner.defaultGapSeconds)

    // Time-lapse
    var interval by mutableDoubleStateOf(15.0)
    var exposure by mutableDoubleStateOf(10.0)
    var frameRate by mutableDoubleStateOf(24.0)
    var basis by mutableStateOf(TimeLapseBasis.CLIP_LENGTH)
    var clipSeconds by mutableDoubleStateOf(10.0)
    var shootingMinutes by mutableDoubleStateOf(60.0)
    var frameCount by mutableDoubleStateOf(240.0)
    var frameSizeMegabytes by mutableStateOf<Double?>(null)
    var writeSeconds by mutableDoubleStateOf(TimeLapsePlanner.defaultMinimumGapSeconds)

    /** The camera and lens the inputs describe. */
    val setup: LensSetup get() = LensSetup(sensor, focalLength, aperture, pixelPitch)

    /** Goes back to [camera] (the lens setup from Settings). */
    fun reset(camera: LensSetup) {
        sensor = camera.sensor
        focalLength = camera.focalLengthMillimeters
        aperture = camera.aperture
        pixelPitch = camera.pixelPitchMicrometers
    }
}

/**
 * Plans star trails and time-lapses. Star trails: how long the trails get for a camera, a lens and a declination, how
 * long a chosen trail takes, how to stack it from short exposures, and where the pole stands. Time-lapse: how many
 * pictures, how long to shoot, how long the clip plays, and what it costs in storage and battery.
 *
 * Push it onto a navigation stack (Settings ▸ Tools). The inputs are kept while another tab is shown.
 */
@Composable
fun TrailsTimeLapseScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    val myCamera = app.lensSetup
    val inputs = rememberWhilePushed("tools.trails.inputs") { TrailsInputs(myCamera) }
    val scroll = rememberWhilePushed("tools.trails.scroll") { ScrollState(0) }
    val dismissKeyboard = rememberKeyboardDismissal()
    ScreenshotScreenEffect(ScreenshotScreen.TIME_LAPSE) { inputs.mode = TrailsMode.TIME_LAPSE }
    ToolsScreenFrame(title = "Star Trails & Time-Lapse", onBack = onBack) {
        Column(Modifier.weight(1f).imePadding().nestedScroll(dismissKeyboard).verticalScroll(scroll)) {
            FormSection(title = "Plan") {
                row { ToolsSegmentedRow(TrailsMode.entries, inputs.mode, { it.title }, "Plan") { inputs.mode = it } }
            }
            when (inputs.mode) {
                TrailsMode.STAR_TRAILS -> StarTrailForm(inputs, myCamera)
                TrailsMode.TIME_LAPSE -> TimeLapseForm(inputs)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A row of segments, one per option, drawn in the palette (the stock colours would not be red at night). */
@Composable
internal fun <T> ToolsSegmentedRow(
    options: List<T>,
    selection: T,
    title: (T) -> String,
    description: String,
    onSelect: (T) -> Unit,
) {
    val palette = LocalPalette.current
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = description }) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selection,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = palette.surfaceRaised,
                    activeContentColor = palette.primaryText,
                    activeBorderColor = palette.accent,
                    inactiveContainerColor = palette.surface,
                    inactiveContentColor = palette.secondaryText,
                    inactiveBorderColor = palette.stroke,
                ),
                label = { Text(title(option), maxLines = 1) },
            )
        }
    }
}

/** The sensor presets, plus the user's own sensor when it is a custom size. */
internal fun sensorChoices(myCamera: LensSetup): List<SensorFormat> =
    if (myCamera.sensor in SensorFormat.presets) SensorFormat.presets else SensorFormat.presets + myCamera.sensor

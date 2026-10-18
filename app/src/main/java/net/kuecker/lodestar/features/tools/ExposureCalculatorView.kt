package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.apparentEquatorial
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatExposure
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.phototools.ExposureCalculator
import net.kuecker.lodestar.kit.phototools.ExposureRule
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsOptionalNumberField
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.text.NumberFormat

/**
 * The calculator's inputs. They start from the user's camera and are the calculator's own copy: changes made here
 * never touch Settings.
 */
private class ExposureInputs(camera: LensSetup) {
    var rule by mutableStateOf(ExposureRule.NPF)
    var sensor by mutableStateOf(camera.sensor)
    var focalLength by mutableDoubleStateOf(camera.focalLengthMillimeters)
    var aperture by mutableDoubleStateOf(camera.aperture)
    var pixelPitch by mutableStateOf(camera.pixelPitchMicrometers)
    var declination by mutableDoubleStateOf(0.0)
    var testSeconds by mutableDoubleStateOf(20.0)

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
 * Estimates the longest exposure that keeps stars looking like points, for the camera and lens from Settings or any
 * other combination tried here, and shows how long the star trails of a chosen exposure would be.
 *
 * Push it onto a navigation stack (Settings ▸ Tools). The inputs are kept while another tab is shown.
 */
@Composable
fun ExposureCalculatorScreen(onBack: () -> Unit) {
    val app = LocalAppModel.current
    val myCamera = app.lensSetup
    val inputs = rememberWhilePushed("tools.exposure.inputs") { ExposureInputs(myCamera) }
    val scroll = rememberWhilePushed("tools.exposure.scroll") { ScrollState(0) }
    val setup = inputs.setup
    val target = app.target
    // Worked out once per target, not on every tick of the clock.
    val targetDeclination = remember(target?.id) {
        target?.let { Snapshot.withoutReadObservation { apparentDeclination(app, it) } }
    }
    val dismissKeyboard = rememberKeyboardDismissal()
    ToolsScreenFrame(title = "Exposure Calculator", onBack = onBack) {
        Column(Modifier.weight(1f).imePadding().nestedScroll(dismissKeyboard).verticalScroll(scroll)) {
            RuleSection(inputs)
            CameraSection(inputs, myCamera, setup)
            DeclinationSection(inputs, target, targetDeclination)
            ResultSection(inputs, setup)
            ComparisonSection(inputs, setup)
            TrailSection(inputs, setup)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Closes the keyboard when the user scrolls the form (iOS: `scrollDismissesKeyboard`). */
@Composable
private fun rememberKeyboardDismissal(): NestedScrollConnection {
    val focus = LocalFocusManager.current
    return remember(focus) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) focus.clearFocus()
                return Offset.Zero
            }
        }
    }
}

// MARK: Inputs

@Composable
private fun RuleSection(inputs: ExposureInputs) {
    val palette = LocalPalette.current
    val rule = inputs.rule
    FormSection(title = "Rule") {
        row { RulePicker(rule) { inputs.rule = it } }
        row {
            Text(
                rule.displayName,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            SettingsCaption(rule.summary)
            SettingsCaption(rule.formula)
        }
    }
}

/** The rule picker: one segment per rule, in palette colours. */
@Composable
private fun RulePicker(selected: ExposureRule, onSelect: (ExposureRule) -> Unit) {
    val palette = LocalPalette.current
    val rules = ExposureRule.entries
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        rules.forEachIndexed { index, rule ->
            SegmentedButton(
                selected = rule == selected,
                onClick = { onSelect(rule) },
                shape = SegmentedButtonDefaults.itemShape(index, rules.size),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = palette.surfaceRaised,
                    activeContentColor = palette.primaryText,
                    activeBorderColor = palette.accent,
                    inactiveContainerColor = palette.surface,
                    inactiveContentColor = palette.secondaryText,
                    inactiveBorderColor = palette.stroke,
                ),
                label = { Text(rule.shortName) },
            )
        }
    }
}

@Composable
private fun CameraSection(inputs: ExposureInputs, myCamera: LensSetup, setup: LensSetup) {
    // The presets, plus the user's own sensor when it is a custom size.
    val sensorOptions = if (myCamera.sensor in SensorFormat.presets) {
        SensorFormat.presets
    } else {
        SensorFormat.presets + myCamera.sensor
    }
    val footer = if (inputs.rule == ExposureRule.NPF) {
        "NPF needs the pixel pitch. Without it Lodestar assumes ${defaultPitchText()} µm, typical of a " +
            "high-resolution camera."
    } else {
        "The 500, 400 and 300 rules use the focal length multiplied by the sensor's crop factor."
    }
    FormSection(title = "Camera & Lens", footer = footer) {
        control {
            SettingsMenuPicker(
                title = "Sensor",
                selection = inputs.sensor,
                options = sensorOptions.map { SettingsMenuOption(it.name, it) },
                onSelect = { inputs.sensor = it },
            )
        }
        control {
            SettingsNumberField(
                title = "Focal length",
                value = inputs.focalLength,
                onValueChange = { inputs.focalLength = it },
                range = LensSetup.focalLengthRange,
                unit = "mm",
                fractionDigits = 1,
            )
        }
        control {
            SettingsNumberField(
                title = "Aperture",
                value = inputs.aperture,
                onValueChange = { inputs.aperture = it },
                range = LensSetup.apertureRange,
                prefix = "f/",
                fractionDigits = 2,
            )
        }
        control {
            SettingsOptionalNumberField(
                title = "Pixel pitch",
                value = inputs.pixelPitch,
                onValueChange = { inputs.pixelPitch = it },
                range = SensorFormat.pixelPitchRange,
                unit = "µm",
                fractionDigits = 2,
            )
        }
        if (setup != myCamera) action("Reset to My Camera") { inputs.reset(myCamera) }
    }
}

@Composable
private fun DeclinationSection(inputs: ExposureInputs, target: CelestialObject?, targetDeclination: Double?) {
    FormSection(
        title = "Target Declination",
        footer = "Stars near the celestial pole move more slowly, so the exposure can be longer. 0° is the " +
            "celestial equator, the worst case.",
    ) {
        control {
            SettingsSliderRow(
                title = "Declination",
                valueText = AngleFormatter.formatSignedDegrees(inputs.declination, 1),
                value = inputs.declination,
                onValueChange = { inputs.declination = it },
                range = -90.0..90.0,
                step = 1.0,
            )
        }
        if (target != null && targetDeclination != null) {
            action(
                "Use ${target.name} (${AngleFormatter.formatSignedDegrees(targetDeclination, 1)})",
                Icons.Outlined.GpsFixed,
            ) { inputs.declination = targetDeclination }
        } else {
            caption("Choose a target on the Objects tab to use its declination here.")
        }
    }
}

// MARK: Results

@Composable
private fun ResultSection(inputs: ExposureInputs, setup: LensSetup) {
    val palette = LocalPalette.current
    val rule = inputs.rule
    val estimate = ExposureCalculator.maxExposure(rule, setup, inputs.declination)
    FormSection(title = "Longest Exposure") {
        row {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Text(
                    AngleFormatter.formatExposure(estimate.seconds),
                    color = palette.accent,
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.SemiBold)
                        .monospacedDigits(),
                )
                Text(
                    "${rule.displayName} · ${lensText(setup)}",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        info("Star trail at this exposure", trailText(estimate.seconds, inputs.declination, setup))
        for (note in estimate.notes) caption(note)
        if (setup.pixelPitchMicrometers == null && rule != ExposureRule.NPF) {
            caption(
                "Trail lengths in pixels assume a pixel pitch of ${defaultPitchText()} µm because yours is " +
                    "not set.",
            )
        }
    }
}

@Composable
private fun ComparisonSection(inputs: ExposureInputs, setup: LensSetup) {
    FormSection(title = "All Rules", footer = "The same camera and declination with each rule.") {
        for (other in ExposureRule.entries) {
            val estimate = ExposureCalculator.maxExposure(other, setup, inputs.declination)
            info(other.displayName, AngleFormatter.formatExposure(estimate.seconds))
        }
    }
}

@Composable
private fun TrailSection(inputs: ExposureInputs, setup: LensSetup) {
    val pixels = ExposureCalculator.trailLengthPixels(
        exposureSeconds = inputs.testSeconds,
        declination = inputs.declination,
        setup = setup,
    )
    FormSection(
        title = "Check an Exposure",
        footer = "A rough guide: under one pixel stars stay points; beyond about three pixels the trails are plain " +
            "to see at full size.",
    ) {
        control {
            SettingsNumberField(
                title = "Exposure",
                value = inputs.testSeconds,
                onValueChange = { inputs.testSeconds = it },
                range = 0.1..3600.0,
                unit = "s",
                fractionDigits = 1,
            )
        }
        info("Star trail", trailText(inputs.testSeconds, inputs.declination, setup))
        caption(verdict(pixels))
    }
}

// MARK: Helpers

/** [value] in the user's number format with between [minimumDigits] and [maximumDigits] decimals. */
private fun decimal(value: Double, minimumDigits: Int, maximumDigits: Int): String =
    NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = minimumDigits
        maximumFractionDigits = maximumDigits
    }.format(value)

/** The pixel pitch assumed when none is set, e.g. "4.0". */
private fun defaultPitchText(): String = decimal(ExposureCalculator.defaultPixelPitchMicrometers, 1, 1)

/** The declination of [obj] now, from the observer's place (the equator when the location is not known yet). */
private fun apparentDeclination(app: AppModel, obj: CelestialObject): Double {
    val observer = app.observerContext?.observer ?: Observer(GeographicLocation(0.0, 0.0))
    return SkyFrame(app.clock.now, observer).apparentEquatorial(obj).declination
}

/** "24 mm f/2.8 on Full Frame". */
private fun lensText(setup: LensSetup): String =
    "${decimal(setup.focalLengthMillimeters, 0, 1)} mm f/${decimal(setup.aperture, 0, 2)} on ${setup.sensor.name}"

/** "1.2 px (18″)": the length of a star trail in the image and on the sky. */
private fun trailText(seconds: Double, declination: Double, setup: LensSetup): String {
    val pixels =
        ExposureCalculator.trailLengthPixels(exposureSeconds = seconds, declination = declination, setup = setup)
    val arcseconds = ExposureCalculator.trailLengthArcseconds(exposureSeconds = seconds, declination = declination)
    return "${decimal(pixels, 1, 1)} px (${AppFormat.separation(arcseconds / AstroMath.arcsecondsPerDegree)})"
}

private fun verdict(trailPixels: Double): String = when {
    trailPixels < 1 -> "Stars stay sharp points."
    trailPixels < 2 -> "Stars are nearly sharp; trails show only when you zoom in."
    trailPixels < 4 -> "Slight trails are visible at full size."
    else -> "Clear star trails."
}

/** The label used in the segmented picker. */
private val ExposureRule.shortName: String
    get() = when (this) {
        ExposureRule.RULE500 -> "500"
        ExposureRule.RULE400 -> "400"
        ExposureRule.RULE300 -> "300"
        ExposureRule.NPF -> "NPF"
    }

/** The rule written as a formula. */
private val ExposureRule.formula: String
    get() = when (this) {
        ExposureRule.RULE500 -> "Seconds = 500 ÷ (focal length × crop factor)"
        ExposureRule.RULE400 -> "Seconds = 400 ÷ (focal length × crop factor)"
        ExposureRule.RULE300 -> "Seconds = 300 ÷ (focal length × crop factor)"
        ExposureRule.NPF -> "Seconds = (35 × f-number + 30 × pixel pitch in µm) ÷ focal length in mm"
    }

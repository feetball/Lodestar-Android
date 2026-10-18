package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatExposure
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.SensorFormat
import net.kuecker.lodestar.kit.phototools.StarTrailError
import net.kuecker.lodestar.kit.phototools.StarTrailPlanner
import net.kuecker.lodestar.kit.phototools.StarTrailPole
import net.kuecker.lodestar.kit.planning.PolarAlignment
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsNumberField
import net.kuecker.lodestar.shared.SettingsOptionalNumberField
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** The longest total time of a star trail, in minutes: the planner's limit of 24 hours. */
private const val MAXIMUM_MINUTES = StarTrailPlanner.maximumDurationSeconds / 60

/** The star trail half of the planner: camera, declination, time, results, stacking plan and the pole. */
@Composable
internal fun StarTrailForm(inputs: TrailsInputs, myCamera: LensSetup) {
    val app = LocalAppModel.current
    val target = app.target
    // Worked out once per target, not on every tick of the clock.
    val targetDeclination = remember(target?.id) {
        target?.let { Snapshot.withoutReadObservation { apparentDeclination(app, it) } }
    }
    val setup = inputs.setup
    CameraSection(inputs, myCamera, setup)
    val isNorth = (app.observerContext?.location?.latitude ?: 0.0) >= 0
    DeclinationSection(inputs, target, targetDeclination, isNorth)
    DurationSection(inputs)
    TrailSection(inputs, setup)
    ArcSection(inputs, setup)
    StackSection(inputs, setup)
    PoleSection(setup, target)
}

// MARK: Inputs

@Composable
private fun CameraSection(inputs: TrailsInputs, myCamera: LensSetup, setup: LensSetup) {
    FormSection(
        title = "Camera & Lens",
        footer = "Starts from the camera in Settings; changes here stay in this planner. Without a pixel pitch " +
            "Lodestar assumes ${defaultPitchText()} µm, typical of a high-resolution camera.",
    ) {
        control {
            SettingsMenuPicker(
                title = "Sensor",
                selection = inputs.sensor,
                options = sensorChoices(myCamera).map { SettingsMenuOption(it.name, it) },
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
private fun DeclinationSection(
    inputs: TrailsInputs,
    target: CelestialObject?,
    targetDeclination: Double?,
    isNorth: Boolean,
) {
    val poleDeclination = if (isNorth) 90.0 else -90.0
    FormSection(
        title = "Target Declination",
        footer = "A star at the celestial equator (0°) draws the longest trail; the closer to the pole, the shorter. " +
            "At the pole itself a star does not move.",
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
        action(
            "Use the celestial pole (${AngleFormatter.formatSignedDegrees(poleDeclination, 0)})",
            Icons.Outlined.Explore,
        ) { inputs.declination = poleDeclination }
    }
}

@Composable
private fun DurationSection(inputs: TrailsInputs) {
    FormSection(
        title = "Total Time",
        footer = "The time the trails should span, from the start of the first exposure to the end of the last. " +
            "The sky turns 15° in an hour.",
    ) {
        control {
            SettingsNumberField(
                title = "Duration",
                value = inputs.durationMinutes,
                onValueChange = { inputs.durationMinutes = it },
                range = 0.1..MAXIMUM_MINUTES,
                unit = "min",
                fractionDigits = 1,
            )
        }
        info("In hours and minutes", TrailsText.duration(inputs.durationMinutes * 60.0))
    }
}

// MARK: Results

@Composable
private fun TrailSection(inputs: TrailsInputs, setup: LensSetup) {
    val result = TrailsText.starTrail {
        StarTrailPlanner.trail(inputs.durationMinutes * 60.0, inputs.declination, setup)
    }
    FormSection(title = "Star Trail", footer = TrailsText.RATE_NOTE) {
        when (result) {
            is Planned.Ok -> {
                val trail = result.value
                headline(
                    AngleFormatter.formatDegrees(trail.arcLengthDegrees, 1),
                    "long on the sky at ${AngleFormatter.formatSignedDegrees(trail.declination, 1)} declination",
                )
                info("Trail in the picture", TrailsText.pixels(trail.arcLengthPixels))
                info("Turn about the pole", AngleFormatter.formatDegrees(trail.rotationDegrees, 1))
                info("Distance from the pole", AngleFormatter.formatDegrees(trail.radiusFromPoleDegrees, 1))
                if (trail.usedDefaultPixelPitch) {
                    caption(
                        "The length in pixels assumes a pixel pitch of ${defaultPitchText()} µm because yours is " +
                            "not set.",
                    )
                }
                caption(
                    "Pixels are counted at the centre of the picture. Near the edges of a wide lens the trails " +
                        "are longer.",
                )
            }
            is Planned.Failed -> problem(result.failure)
        }
    }
}

@Composable
private fun ArcSection(inputs: TrailsInputs, setup: LensSetup) {
    val result = TrailsText.starTrail {
        when (inputs.arcUnit) {
            ArcUnit.DEGREES -> StarTrailPlanner.duration(inputs.arcDegrees, inputs.declination)
            ArcUnit.PIXELS -> StarTrailPlanner.duration(inputs.arcPixels, inputs.declination, setup)
        }
    }
    FormSection(
        title = "Time for a Chosen Trail",
        footer = "How long stars at this declination need to draw a trail of the length you want.",
    ) {
        row {
            ToolsSegmentedRow(ArcUnit.entries, inputs.arcUnit, { it.title }, "Unit of the trail length") {
                inputs.arcUnit = it
            }
        }
        control {
            when (inputs.arcUnit) {
                ArcUnit.DEGREES -> SettingsNumberField(
                    title = "Trail length",
                    value = inputs.arcDegrees,
                    onValueChange = { inputs.arcDegrees = it },
                    range = 0.1..360.0,
                    unit = "°",
                    fractionDigits = 1,
                )
                ArcUnit.PIXELS -> SettingsNumberField(
                    title = "Trail length",
                    value = inputs.arcPixels,
                    onValueChange = { inputs.arcPixels = it },
                    range = 1.0..100_000.0,
                    unit = "px",
                    fractionDigits = 0,
                )
            }
        }
        when (result) {
            is Planned.Ok -> {
                info("Time needed", TrailsText.duration(result.value))
                action("Use as the total time") {
                    inputs.durationMinutes = (result.value / 60.0).coerceIn(0.1, MAXIMUM_MINUTES)
                }
            }
            is Planned.Failed -> problem(result.failure)
        }
    }
}

@Composable
private fun StackSection(inputs: TrailsInputs, setup: LensSetup) {
    val plan = TrailsText.starTrail {
        StarTrailPlanner.stackingPlan(
            totalDuration = inputs.durationMinutes * 60.0,
            declination = inputs.declination,
            setup = setup,
            rule = inputs.rule,
            exposureSeconds = inputs.frameSeconds,
            gapSeconds = inputs.gapSeconds,
        )
    }
    FormSection(
        title = "Stacking Plan",
        footer = "Many short exposures stacked in software draw the same trails as one long exposure, without its " +
            "noise and sensor heat. Each frame is as long as the rule allows without trailing, rounded down to a " +
            "setting you can dial.",
    ) {
        row { RulePicker(inputs.rule) { inputs.rule = it } }
        control {
            SettingsOptionalNumberField(
                title = "Frame length",
                value = inputs.frameSeconds,
                onValueChange = { inputs.frameSeconds = it },
                range = 0.1..StarTrailPlanner.maximumExposureSeconds,
                unit = "s",
                fractionDigits = 1,
                placeholder = "Automatic",
            )
        }
        control {
            SettingsNumberField(
                title = "Gap between frames",
                value = inputs.gapSeconds,
                onValueChange = { inputs.gapSeconds = it },
                range = 0.0..StarTrailPlanner.maximumGapSeconds,
                unit = "s",
                fractionDigits = 1,
            )
        }
        when (plan) {
            is Planned.Ok -> {
                val stack = plan.value
                headline(
                    "${TrailsText.count(stack.frameCount)} frames of " +
                        AngleFormatter.formatExposure(stack.exposureSeconds),
                    "${TrailsText.seconds(stack.gapSeconds)} between frames · ${inputs.rule.displayName}",
                )
                info("Time covered", TrailsText.duration(stack.actualDuration))
                info("Shutter open in all", TrailsText.duration(stack.lightSeconds))
                info("Longest frame by the rule", AngleFormatter.formatExposure(stack.estimate.seconds))
                info("Trail in one frame", TrailsText.pixels(stack.exposureTrailPixels))
                info("Movement during the gap", TrailsText.pixels(stack.gapPixels))
                caption(TrailsText.gapVerdict(stack.gapPixels))
                if (inputs.frameSeconds != null && stack.exposureSeconds > stack.estimate.seconds) {
                    caption("This frame is longer than the rule allows, so stars may trail within each frame.")
                }
                if (inputs.frameSeconds == null) {
                    for (note in stack.estimate.notes) caption(note)
                }
            }
            is Planned.Failed -> problem(plan.failure)
        }
    }
}

// MARK: The pole

/** Where the pole stands now and, with a target, how it sits in the frame. */
private class PoleView(
    val pole: StarTrailPole,
    val pointing: HorizontalCoordinate?,
    val separation: Double?,
    val inLandscape: Boolean?,
    val inPortrait: Boolean?,
    /** Whether the pole is behind the camera when it points at the target. */
    val isBehind: Boolean,
)

private fun poleView(observer: Observer, date: Instant, target: CelestialObject?, setup: LensSetup): PoleView {
    val frame = SkyFrame(date, observer)
    val pole = StarTrailPlanner.pole(frame)
    val pointing = target?.let { frame.horizontal(it) }
    if (pointing == null || pointing.altitude < 0) return PoleView(pole, pointing, null, null, null, false)
    val landscape = setup.fieldOfView
    val inLandscape = positionIn(pole, pointing, landscape)
    val inPortrait = positionIn(pole, pointing, landscape.rotated)
    return PoleView(
        pole = pole,
        pointing = pointing,
        separation = pole.direction.angularSeparation(pointing),
        inLandscape = inLandscape?.isInFrame,
        inPortrait = inPortrait?.isInFrame,
        isBehind = inLandscape == null,
    )
}

private fun positionIn(
    pole: StarTrailPole,
    pointing: HorizontalCoordinate,
    frameSize: FrameSize,
) = try {
    StarTrailPlanner.polePosition(pole.direction, pointing, frameSize)
} catch (_: StarTrailError) {
    null
}

@Composable
private fun PoleSection(setup: LensSetup, target: CelestialObject?) {
    val app = LocalAppModel.current
    val observer = app.observerContext?.observer
    // The pole hardly moves; a target does, by 15° an hour: bring it up to date once a minute.
    val minute by remember { derivedStateOf { app.clock.now.epochSecond / 60 } }
    val view = observer?.let { place ->
        remember(place, target?.id, minute, setup) {
            poleView(place, Instant.ofEpochSecond(minute * 60), target, setup)
        }
    }
    FormSection(
        title = "Where the Pole Is",
        footer = "Trails are arcs of circles centred on the celestial pole. Point the camera near it and the stars " +
            "draw circles around the frame; point away from it and the arcs flatten.",
    ) {
        if (view == null) {
            caption("Lodestar needs an observing location to find the pole. Choose one in Settings.")
        } else {
            val pole = view.pole
            val isNorthPole = pole.hemisphere == PolarAlignment.Hemisphere.NORTH
            info("Pole", if (isNorthPole) "North celestial pole" else "South celestial pole")
            info("Azimuth", AppFormat.azimuth(pole.direction.azimuth))
            info("Altitude", AppFormat.altitude(pole.direction.altitude))
            caption(
                if (pole.isAboveHorizon) {
                    "The pole is ${AngleFormatter.formatDegrees(pole.direction.altitude, 0)} above the horizon " +
                        "here: aim the camera at it for full circles."
                } else {
                    "The pole is below the horizon here, so no star circles it for you; trails are arcs low in " +
                        "the sky."
                },
            )
            if (target != null) caption(targetLine(target, view))
        }
    }
}

private fun targetLine(target: CelestialObject, view: PoleView): String {
    val pointing = view.pointing
    val separation = view.separation
    if (pointing == null || separation == null) return "${target.name} is below the horizon now."
    val where = when {
        view.isBehind -> "behind the camera"
        view.inLandscape == true && view.inPortrait == true -> "inside the frame, held either way"
        view.inLandscape == true -> "inside a landscape frame but outside a portrait one"
        view.inPortrait == true -> "inside a portrait frame but outside a landscape one"
        else -> "outside the frame"
    }
    val away = AppFormat.separation(separation)
    return "With ${target.name} at the centre of the frame, the pole is $away away, $where."
}

// MARK: Rows

/** A large result with a line under it. */
internal fun FormRows.headline(value: String, detail: String) = row {
    val palette = LocalPalette.current
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            value,
            color = palette.accent,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
        )
        Text(detail, color = palette.secondaryText, style = MaterialTheme.typography.bodySmall)
    }
}

/** Why there is no result: the planner's refusal, in the critical colour. */
internal fun FormRows.problem(message: String) = row {
    Text(message, color = LocalPalette.current.critical, style = MaterialTheme.typography.bodyMedium)
}

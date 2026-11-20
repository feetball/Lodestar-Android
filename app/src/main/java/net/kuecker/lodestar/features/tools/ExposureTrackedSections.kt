package net.kuecker.lodestar.features.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatExposure
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.kit.phototools.MoonExposure
import net.kuecker.lodestar.kit.phototools.TrackedExposure
import net.kuecker.lodestar.model.AppSettings
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SettingsMenuOption
import net.kuecker.lodestar.shared.SettingsMenuPicker
import net.kuecker.lodestar.shared.SettingsRow
import net.kuecker.lodestar.shared.SettingsSliderRow
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

// The Tracked, Alt-Az Mount and Moon sections of the exposure calculator.

/** How often the target's and the Moon's place are worked out again while the calculator is open. */
private const val SKY_REFRESH_MS = 30_000L

/** The ISO speeds of the Moon section's picker. */
private val ISO_CHOICES = listOf(100, 200, 400, 800, 1600, 3200, 6400, 12800)

/** The step of the altitude and azimuth steppers, in degrees. */
private const val STEPPER_STEP = 5.0

/** The altitudes the altitude stepper offers, in degrees: 5° to 85° keeps clear of the zenith, where the rate diverges. */
internal val STEPPER_ALTITUDE_RANGE = 5.0..85.0

/** Where the target and the Moon are at one instant: what the Alt-Az Mount and Moon sections need. */
internal data class ExposureSky(
    /** The target's azimuth in degrees, or `null` without a target or a location. */
    val targetAzimuth: Double?,
    /** The target's altitude in degrees, or `null` without a target or a location. */
    val targetAltitude: Double?,
    /** The lit fraction of the Moon's disc, 0 to 1. */
    val moonIlluminatedFraction: Double,
    /** The Moon's altitude in degrees, or `null` without a location. */
    val moonAltitude: Double?,
    /** The Moon's apparent diameter in degrees. */
    val moonDiameterDegrees: Double,
)

/** The astronomy behind [ExposureSky]. Pure, so it can run on any thread. */
internal object ExposureSkyCalculator {
    /**
     * Where [target] and the Moon are at [date]. Without an [observer] the Moon's phase and size are worked out from the
     * equator (they hardly depend on the place) and no altitude is given.
     */
    fun compute(observer: Observer?, target: CelestialObject?, date: Instant): ExposureSky {
        val frame = SkyFrame(date, observer ?: Observer(GeographicLocation(0.0, 0.0)))
        val targetPlace = if (observer != null && target != null) frame.horizontal(target, true) else null
        val moon = frame.state(SolarSystemBody.MOON)
        return ExposureSky(
            targetAzimuth = targetPlace?.azimuth,
            targetAltitude = targetPlace?.altitude,
            moonIlluminatedFraction = moon.illuminatedFraction ?: 1.0,
            moonAltitude = if (observer != null) frame.horizontal(SolarSystemBody.MOON, true).altitude else null,
            moonDiameterDegrees = moon.angularDiameterArcseconds / AstroMath.arcsecondsPerDegree,
        )
    }
}

/**
 * The sky now for [target], refreshed every 30 s while the screen is open, the app is in the foreground and the
 * observer, the target or the display time travel does not change. `null` until the first result.
 */
@Composable
internal fun rememberExposureSky(app: AppModel, target: CelestialObject?): ExposureSky? {
    val context = app.observerContext
    val isForeground = app.isForeground
    var sky by remember { mutableStateOf<ExposureSky?>(null) }
    LaunchedEffect(context, target?.id, isForeground) {
        if (!isForeground) return@LaunchedEffect
        while (isActive) {
            val date = app.clock.displayDate
            sky = withContext(Dispatchers.Default) { ExposureSkyCalculator.compute(context?.observer, target, date) }
            delay(SKY_REFRESH_MS)
        }
    }
    return sky
}

// MARK: Tracked

/** A tracker whose axis is off the pole: the drift rate and the longest exposure for the current lens. */
@Composable
internal fun TrackedSection(setup: LensSetup) {
    val app = LocalAppModel.current
    val error = app.settings.polarErrorDegrees
    val drift = TrackedExposure.driftRate(error)
    val seconds = TrackedExposure.maxExposureForPolarError(setup = setup, polarErrorDegrees = error)
    FormSection(
        title = "Tracked",
        footer = "Worst case: a tracker whose axis is off the pole drifts the stars by up to 15.04″ × " +
            "sin(error) a second. The limit allows ${decimal(TrackedExposure.defaultTolerancePixels, 1, 1)}\u00A0px " +
            "of drift.",
    ) {
        control {
            SettingsSliderRow(
                title = "Polar alignment error",
                valueText = "${decimal(error, 1, 1)}°",
                value = error,
                onValueChange = { value ->
                    val rounded = (value * 10).roundToInt() / 10.0
                    app.settingsStore.update { it.copy(polarErrorDegrees = rounded) }
                },
                range = AppSettings.POLAR_ERROR_RANGE,
                step = 0.1,
            )
        }
        info("Drift rate", rateText(drift))
        info("Longest exposure", AngleFormatter.formatExposure(seconds))
        if (setup.pixelPitchMicrometers == null) caption(defaultPitchCaption())
    }
}

// MARK: Alt-az mount

/**
 * The field rotation of an alt-azimuth mount: the rate and the longest exposure at the target's place now, or at the
 * place set with the steppers when there is no target (or no location to place it).
 */
@Composable
internal fun AltAzSection(
    setup: LensSetup,
    target: CelestialObject?,
    sky: ExposureSky?,
    latitude: Double?,
    altitude: Double,
    azimuth: Double,
    onAltitudeChange: (Double) -> Unit,
    onAzimuthChange: (Double) -> Unit,
) {
    val targetAzimuth = sky?.targetAzimuth
    val targetAltitude = sky?.targetAltitude
    val place = if (target != null && targetAzimuth != null && targetAltitude != null) {
        TargetPlace(targetAzimuth, targetAltitude)
    } else {
        null
    }
    val shownAzimuth = place?.azimuth ?: azimuth
    val shownAltitude = place?.altitude ?: altitude
    val observerLatitude = latitude ?: 0.0
    val rate = TrackedExposure.fieldRotationRate(observerLatitude, shownAzimuth, shownAltitude)
    val seconds = TrackedExposure.maxExposureForFieldRotation(
        setup = setup,
        latitude = observerLatitude,
        azimuth = shownAzimuth,
        altitude = shownAltitude,
    )
    FormSection(
        title = "Alt-Az Mount",
        footer = "An alt-azimuth mount keeps the target centred but turns the field about it. The limit allows " +
            "${decimal(TrackedExposure.defaultTolerancePixels, 1, 1)}\u00A0px of trailing at the corner of the frame.",
    ) {
        if (target != null && place != null) {
            info(target.name, "${AppFormat.azimuth(shownAzimuth)} · ${AppFormat.altitude(shownAltitude)}")
        } else {
            caption(
                if (target != null) {
                    "Lodestar needs a location to place ${target.name}. Set its place here instead."
                } else {
                    "Choose a target on the Objects tab to use its place here, or set one below."
                },
            )
            control {
                StepperRow(
                    title = "Altitude",
                    valueText = AppFormat.altitude(altitude),
                    onDecrement = { onAltitudeChange((altitude - STEPPER_STEP).coerceIn(STEPPER_ALTITUDE_RANGE)) },
                    onIncrement = { onAltitudeChange((altitude + STEPPER_STEP).coerceIn(STEPPER_ALTITUDE_RANGE)) },
                    canDecrement = altitude > STEPPER_ALTITUDE_RANGE.start,
                    canIncrement = altitude < STEPPER_ALTITUDE_RANGE.endInclusive,
                )
            }
            control {
                StepperRow(
                    title = "Azimuth",
                    valueText = AppFormat.azimuth(azimuth),
                    onDecrement = { onAzimuthChange(wrapAzimuth(azimuth - STEPPER_STEP)) },
                    onIncrement = { onAzimuthChange(wrapAzimuth(azimuth + STEPPER_STEP)) },
                    canDecrement = true,
                    canIncrement = true,
                )
            }
        }
        info("Field rotation", rateText(rate))
        info("Longest exposure", seconds?.let { AngleFormatter.formatExposure(it) } ?: "No limit")
        if (seconds == null) caption("No field rotation here: due east or west an alt-azimuth mount keeps the sky upright.")
        if (latitude == null) caption("Location unknown: assuming the equator, where the field turns fastest.")
        if (setup.pixelPitchMicrometers == null) caption(defaultPitchCaption())
    }
}

/** Where the target is, in degrees. */
private class TargetPlace(val azimuth: Double, val altitude: Double)

/** Azimuth in [0, 360). */
private fun wrapAzimuth(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

/** A row with a title, a value and buttons that lower and raise it. */
@Composable
private fun StepperRow(
    title: String,
    valueText: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    canDecrement: Boolean,
    canIncrement: Boolean,
) {
    val palette = LocalPalette.current
    SettingsRow {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title,
                color = palette.primaryText,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDecrement, enabled = canDecrement) {
                Icon(
                    Icons.Outlined.RemoveCircleOutline,
                    contentDescription = "Lower ${title.lowercase()}, now $valueText",
                    tint = if (canDecrement) palette.accent else palette.tertiaryText,
                )
            }
            Text(
                valueText,
                color = palette.secondaryText,
                style = MaterialTheme.typography.bodyLarge.monospacedDigits(),
            )
            IconButton(onClick = onIncrement, enabled = canIncrement) {
                Icon(
                    Icons.Outlined.AddCircleOutline,
                    contentDescription = "Raise ${title.lowercase()}, now $valueText",
                    tint = if (canIncrement) palette.accent else palette.tertiaryText,
                )
            }
        }
    }
}

// MARK: Moon

/** The Moon: the shutter time for tonight's phase and height at the calculator's aperture, and its size in pixels. */
@Composable
internal fun MoonSection(setup: LensSetup, sky: ExposureSky?) {
    val app = LocalAppModel.current
    val iso = nearestIso(app.settings.exposureISO)
    FormSection(
        title = "Moon",
        footer = "Looney 11: f/11 at 1/ISO for a full Moon, with a longer exposure for a thinner or lower Moon. " +
            "A starting point: check the histogram.",
    ) {
        control {
            SettingsMenuPicker(
                title = "ISO",
                selection = iso,
                options = ISO_CHOICES.map { SettingsMenuOption("$it", it) },
                onSelect = { value -> app.settingsStore.update { it.copy(exposureISO = value) } },
            )
        }
        if (sky == null) {
            caption("Working out the Moon…")
        } else {
            val suggestion = MoonExposure.suggest(
                illuminatedFraction = sky.moonIlluminatedFraction,
                altitude = sky.moonAltitude ?: 90.0,
                aperture = setup.aperture,
                iso = iso.toDouble(),
            )
            val height = sky.moonAltitude?.let { if (it > 0) AppFormat.altitude(it) else "below the horizon" }
            info("Moon", listOfNotNull(AppFormat.percent(sky.moonIlluminatedFraction) + " lit", height).joinToString(" · "))
            info(
                "Shutter at f/${decimal(setup.aperture, 0, 2)}, ISO $iso",
                AngleFormatter.formatExposure(suggestion.seconds),
            )
            caption(suggestion.note)
            if (sky.moonAltitude == null) caption("Without a location the Moon's height is unknown: the shutter assumes it is high in the sky.")
            moonSizeText(sky, setup)?.let { caption(it) }
        }
    }
}

/** The choice of the ISO picker closest to [iso]: stored values need not be one of the choices. */
private fun nearestIso(iso: Int): Int = ISO_CHOICES.minByOrNull { abs(it - iso) } ?: ISO_CHOICES.first()

/** "The Moon spans 454 px at your focal length", with the pixel pitch that was assumed when there is none. */
private fun moonSizeText(sky: ExposureSky, setup: LensSetup): String? {
    val pixels = TrackedExposure.pixelSize(sky.moonDiameterDegrees, setup) ?: return null
    val text = "The Moon spans ${decimal(pixels.roundToInt().toDouble(), 0, 0)}\u00A0px at your focal length"
    return if (setup.pixelPitchMicrometers == null) "$text, assuming ${defaultPitchText()}\u00A0µm pixels." else "$text."
}

// MARK: Helpers

/** "0.26″/s": a rate in arcseconds per second, with fewer decimals the larger it is. */
private fun rateText(arcsecondsPerSecond: Double): String {
    val digits = when {
        arcsecondsPerSecond >= 100 -> 0
        arcsecondsPerSecond >= 10 -> 1
        else -> 2
    }
    return "${decimal(arcsecondsPerSecond, digits, digits)}″/s"
}

private fun defaultPitchCaption(): String =
    "The limits assume a pixel pitch of ${defaultPitchText()}\u00A0µm because yours is not set."

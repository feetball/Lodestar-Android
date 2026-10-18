package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.kit.catalog.riseTransitSet
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.phototools.FrameSize
import net.kuecker.lodestar.kit.pointing.AlignmentModel
import net.kuecker.lodestar.kit.pointing.DeviceAttitude
import net.kuecker.lodestar.kit.pointing.GuidanceTracker
import net.kuecker.lodestar.kit.pointing.MountProfile
import net.kuecker.lodestar.shared.SkySnapshotOptions
import java.time.Instant

/**
 * The preferences and calibration the Aim screen aims and draws with, gathered from the app model. It is compared as
 * a whole (data class equality), so the screen reacts once when anything in it changes. Create it with
 * `AimConfiguration(app)` while composing, so the composition reads the app state it is made from.
 */
data class AimConfiguration(
    val mount: MountProfile,
    val alignment: AlignmentModel,
    /** The DSLR's picture in landscape orientation, in degrees. */
    val lensFrame: FrameSize,
    val showConstellationLines: Boolean,
    val showConstellationNames: Boolean,
    val showDeepSkyObjects: Boolean,
    val showMilkyWay: Boolean,
    /** The deep-sky detail setting: objects up to this prominence level are drawn (1 showpieces … 4 everything). */
    val deepSkyProminence: Prominence,
    val starMagnitudeLimit: Double,
    /**
     * Whether the camera picture is wanted behind the sky (it also has to run). Never with a mount that points the
     * phone's camera somewhere else than the lens.
     */
    val wantsCameraPreview: Boolean,
    val isNight: Boolean,
) {
    /** Whether the phone's back camera looks along the lens (see the companion's function of the same name). */
    val phoneCameraFollowsLens: Boolean get() = phoneCameraFollowsLens(mount)

    /** The compass correction of the alignment, for everything drawn from the phone's own camera. */
    val headingOffset: Double get() = alignment.headingOffset(mount)

    /** Separation in degrees within which the DSLR counts as pointing at the target. */
    val onTargetThreshold: Double
        get() = GuidanceTracker.defaultOnTargetThreshold(
            horizontalFieldOfView = lensFrame.horizontal,
            verticalFieldOfView = lensFrame.vertical,
        )

    val skyOptions: SkySnapshotOptions
        get() = SkySnapshotOptions(
            starMagnitudeLimit = starMagnitudeLimit,
            includeConstellations = showConstellationLines || showConstellationNames,
            includeDeepSky = showDeepSkyObjects,
            includeMilkyWay = showMilkyWay,
            deepSkyProminence = deepSkyProminence,
        )

    companion object {
        /** Gathers the configuration from the app model (main thread). */
        operator fun invoke(app: AppModel): AimConfiguration {
            val settings = app.settings
            val mount = app.mountProfile
            val isNight = app.isNightModeActive
            return AimConfiguration(
                mount = mount,
                alignment = app.alignment,
                lensFrame = app.lensSetup.fieldOfView,
                showConstellationLines = settings.showConstellationLines,
                showConstellationNames = settings.showConstellationNames,
                showDeepSkyObjects = settings.showDeepSkyObjects,
                showMilkyWay = settings.showMilkyWay,
                deepSkyProminence = Prominence.fromRawValue(settings.deepSkyProminence) ?: Prominence.PROMINENT,
                starMagnitudeLimit = settings.starMagnitudeLimit,
                wantsCameraPreview = settings.cameraPreviewEnabled && (!isNight || settings.cameraPreviewAtNight) &&
                    phoneCameraFollowsLens(mount),
                isNight = isNight,
            )
        }

        /**
         * Whether the phone's back camera looks along the lens. It does not when the phone lies on top of the camera:
         * its picture then shows the ground, and the sky is drawn as seen from the lens instead.
         */
        fun phoneCameraFollowsLens(mount: MountProfile): Boolean =
            mount.cameraAxis.dot(DeviceAttitude.backCameraAxis) > 0.99
    }
}

/** Everything that makes the sky snapshot behind the overlay out of date. */
data class AimSkyInputs(
    val observer: Observer,
    val options: SkySnapshotOptions,
    val target: CelestialObject?,
    /** The site's recorded skyline, or `null` for an open horizon. */
    val horizon: HorizonProfile?,
)

/** The sheets the Aim screen presents. */
enum class AimSheet {
    TARGET_PICKER,
    ALIGNMENT,
    MOSAIC,
}

/** When the target is next above the horizon, for the below-horizon warning. */
data class AimRiseInfo(
    val targetID: String,
    val nextRise: Instant?,
    val circumstance: RiseTransitSet.Circumstance,
) {
    companion object {
        /** The first rise within the next 24 hours. Searches the ephemeris, so run it off the main thread. */
        fun compute(obj: CelestialObject, observer: Observer, date: Instant): AimRiseInfo {
            val day = DateInterval(date, 24 * 3600.0)
            val result = RiseSetCalculator.riseTransitSet(obj, observer, day)
            return AimRiseInfo(obj.id, result.rise, result.circumstance)
        }
    }
}

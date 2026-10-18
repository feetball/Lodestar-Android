package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.planning.PolarAlignment
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Why a star trail could not be planned. */
sealed class StarTrailError(message: String) : Exception(message) {
    /** The duration is not a finite number of seconds in (0, [StarTrailPlanner.maximumDurationSeconds]]. */
    data class InvalidDuration(val seconds: Double) :
        StarTrailError("The duration $seconds s is not in (0, 86400] seconds.")

    /** The declination is not a finite number in [−90, 90] degrees. */
    data class InvalidDeclination(val degrees: Double) :
        StarTrailError("The declination $degrees is not in [-90, 90] degrees.")

    /** The arc is not a finite length in (0, 360] degrees, or the pixel length is not a finite positive number. */
    data class InvalidArc(val value: Double) : StarTrailError("The trail length $value is not usable.")

    /** A star at a celestial pole does not move, so no duration produces a trail. */
    data object NoMotionAtThePole : StarTrailError("A star at a celestial pole does not move.")

    /** The trail needs more than [StarTrailPlanner.maximumDurationSeconds] seconds. */
    data class DurationTooLong(val seconds: Double) :
        StarTrailError("The trail needs $seconds s, more than 24 hours.")

    /** The single exposure is not a finite number of seconds in (0, 3600]. */
    data class InvalidExposure(val seconds: Double) :
        StarTrailError("The exposure $seconds s is not in (0, 3600] seconds.")

    /** The gap between exposures is not a finite number of seconds in [0, [StarTrailPlanner.maximumGapSeconds]]. */
    data class InvalidGap(val seconds: Double) :
        StarTrailError("The gap $seconds s is not in [0, 60] seconds.")

    /** The plan would need [count] frames, more than the [limit] the planner handles. */
    data class TooManyFrames(val count: Int, val limit: Int) :
        StarTrailError("The plan needs $count frames; the limit is $limit.")

    /** The camera frame is not a pair of angles in (0, 180) degrees. */
    data class InvalidFrame(val horizontal: Double, val vertical: Double) :
        StarTrailError("The frame $horizontal by $vertical degrees is not usable.")
}

/**
 * The trail a star draws while the shutter is open (or over a stack of exposures).
 *
 * @property duration The time in seconds.
 * @property declination The star's declination in degrees.
 * @property rotationDegrees The angle the star turns about the celestial pole: the sidereal rate times the time,
 * the same for every star. This is the angle of the arc on the concentric circles around the pole.
 * @property arcLengthDegrees The length of the trail on the sky in degrees, `rotation × cos(declination)`.
 * @property arcLengthPixels The length in image pixels, using the image scale at the centre of the frame (a pixel
 * pitch of 4 µm when the setup has none, see [usedDefaultPixelPitch]). Trails near the edges of a wide
 * rectilinear frame are longer in pixels than this.
 * @property radiusFromPoleDegrees The angular distance of the star from the celestial pole, `90° − |declination|`:
 * the radius of its circle on the sky.
 * @property usedDefaultPixelPitch `true` when the setup has no pixel pitch and [arcLengthPixels] assumes 4 µm.
 */
@ConsistentCopyVisibility
data class StarTrail internal constructor(
    val duration: Double,
    val declination: Double,
    val rotationDegrees: Double,
    val arcLengthDegrees: Double,
    val arcLengthPixels: Double,
    val radiusFromPoleDegrees: Double,
    val usedDefaultPixelPitch: Boolean,
)

/**
 * A plan for a star trail made by stacking many short exposures.
 *
 * @property totalDuration The time the trail should span in seconds, as requested.
 * @property estimate The longest single exposure the rule allows, with its notes.
 * @property exposureSeconds The exposure planned for each frame: the estimate rounded down to a value that is
 * easy to dial in (whole seconds from 2 s, tenths of a second below), or the caller's own exposure.
 * @property gapSeconds The time between the end of one exposure and the start of the next.
 * @property frameCount The number of frames: enough to span [totalDuration].
 * @property actualDuration The time the frames span, `frameCount × (exposure + gap)`, in seconds.
 * @property lightSeconds The time the shutter is open in total, `frameCount × exposure`.
 * @property exposureTrailPixels The length of the trail in a single frame, in pixels.
 * @property gapPixels The distance a star moves in the gap between two frames, in pixels: the break in the stacked
 * trail. Below about 2 pixels it is hardly visible.
 * @property trail The trail of the whole stack over [actualDuration] (at most 24 hours): its rotation and length.
 */
@ConsistentCopyVisibility
data class StarTrailStackPlan internal constructor(
    val totalDuration: Double,
    val estimate: ExposureEstimate,
    val exposureSeconds: Double,
    val gapSeconds: Double,
    val frameCount: Int,
    val actualDuration: Double,
    val lightSeconds: Double,
    val exposureTrailPixels: Double,
    val gapPixels: Double,
    val trail: StarTrail,
)

/**
 * Where a celestial pole is: the centre of the star-trail circles.
 *
 * @property hemisphere The pole's hemisphere.
 * @property direction The azimuth and altitude of the pole, 0° azimuth (north) for the north pole and 180° for
 * the south pole. Its altitude is about the observer's latitude (negative if the pole is below the horizon).
 */
@ConsistentCopyVisibility
data class StarTrailPole internal constructor(
    val hemisphere: PolarAlignment.Hemisphere,
    val direction: HorizontalCoordinate,
) {
    /** `true` when the pole is above the horizon, so full circles around it can be photographed. */
    val isAboveHorizon: Boolean
        get() = direction.altitude > 0.0
}

/**
 * Where the pole falls in the camera frame.
 *
 * @property x Horizontal position in units of half the frame width: −1 at the left edge, +1 at the right edge.
 * @property y Vertical position in units of half the frame height: −1 at the bottom edge, +1 at the top edge.
 * @property separation The angle between the optical axis and the pole in degrees.
 * @property isInFrame `true` when the pole is inside the frame, `|x| ≤ 1` and `|y| ≤ 1`.
 */
@ConsistentCopyVisibility
data class StarTrailPolePosition internal constructor(
    val x: Double,
    val y: Double,
    val separation: Double,
    val isInFrame: Boolean,
)

/**
 * Plans star trails: how long they get, how many frames a stack takes, and where the circles around the pole
 * fall in the frame.
 *
 * Stars move at the sidereal rate of 15.041° per hour about the celestial pole, so a star at declination δ draws
 * an arc of `15.041° × hours × cos δ` along its circle. All functions are pure and take microseconds.
 */
object StarTrailPlanner {
    /** The rate at which the sky turns about the celestial poles, in degrees per hour. */
    const val rotationRateDegreesPerHour: Double = ExposureCalculator.siderealRateArcsecondsPerSecond

    /** The longest duration the planner accepts: 24 hours. */
    const val maximumDurationSeconds: Double = 86_400.0

    /** The longest single exposure the planner accepts for a stack: an hour. */
    const val maximumExposureSeconds: Double = 3_600.0

    /** The gap between stacked exposures when the caller gives none: one second, a typical write time. */
    const val defaultGapSeconds: Double = 1.0

    /** The longest gap the planner accepts. */
    const val maximumGapSeconds: Double = 60.0

    /** The most frames a stack plan may have. */
    const val maximumFrameCount: Int = 100_000

    private const val degreesPerSecond: Double = rotationRateDegreesPerHour / 3600.0

    /**
     * The trail of a star at [declination] during [duration].
     *
     * @param duration The time in seconds, in (0, [maximumDurationSeconds]].
     * @param declination The star's declination in degrees, [−90, 90].
     * @param setup The camera and lens, for the length in pixels.
     * @throws StarTrailError.InvalidDuration for a duration out of range.
     * @throws StarTrailError.InvalidDeclination for a declination out of range.
     */
    @Throws(StarTrailError::class)
    fun trail(duration: Double, declination: Double, setup: LensSetup): StarTrail {
        if (!(duration > 0.0 && duration <= maximumDurationSeconds)) throw StarTrailError.InvalidDuration(duration)
        if (!(declination >= -90.0 && declination <= 90.0)) throw StarTrailError.InvalidDeclination(declination)
        val rotation = degreesPerSecond * duration
        return StarTrail(
            duration = duration,
            declination = declination,
            rotationDegrees = rotation,
            arcLengthDegrees = rotation * AstroMath.cosDeg(declination),
            arcLengthPixels = ExposureCalculator.trailLengthPixels(duration, declination, setup),
            radiusFromPoleDegrees = 90.0 - abs(declination),
            usedDefaultPixelPitch = setup.pixelPitchMicrometers == null,
        )
    }

    /**
     * The time a star at [declination] needs to draw an arc of [arc] degrees on the sky.
     *
     * @param arc The length of the trail in degrees, in (0, 360].
     * @param declination The star's declination in degrees, [−90, 90].
     * @return The duration in seconds.
     * @throws StarTrailError.InvalidArc for an arc out of range.
     * @throws StarTrailError.InvalidDeclination for a declination out of range.
     * @throws StarTrailError.NoMotionAtThePole at a declination of ±90°.
     * @throws StarTrailError.DurationTooLong if the arc takes more than 24 hours.
     */
    @Throws(StarTrailError::class)
    fun duration(arc: Double, declination: Double): Double {
        if (!(arc > 0.0 && arc <= 360.0)) throw StarTrailError.InvalidArc(arc)
        return seconds(arc, declination)
    }

    /**
     * The time a star at [declination] needs to draw a trail [pixels] long in the image.
     *
     * @param pixels The length of the trail in pixels, finite and positive.
     * @param declination The star's declination in degrees, [−90, 90].
     * @param setup The camera and lens; a missing pixel pitch counts as 4 µm.
     * @return The duration in seconds.
     * @throws StarTrailError.InvalidArc for a length that is not a positive number.
     * @throws StarTrailError.InvalidDeclination for a declination out of range.
     * @throws StarTrailError.NoMotionAtThePole at a declination of ±90°.
     * @throws StarTrailError.DurationTooLong if the trail takes more than 24 hours.
     */
    @Throws(StarTrailError::class)
    fun duration(pixels: Double, declination: Double, setup: LensSetup): Double {
        if (!(pixels > 0.0 && pixels.isFinite())) throw StarTrailError.InvalidArc(pixels)
        val pitch = setup.pixelPitchMicrometers ?: ExposureCalculator.defaultPixelPitchMicrometers
        val arcDegrees = pixels * setup.imageScale(pitch) / AstroMath.arcsecondsPerDegree
        return seconds(arcDegrees, declination)
    }

    private fun seconds(arc: Double, declination: Double): Double {
        if (!(declination >= -90.0 && declination <= 90.0)) throw StarTrailError.InvalidDeclination(declination)
        val factor = AstroMath.cosDeg(declination)
        if (!(factor > 1e-9)) throw StarTrailError.NoMotionAtThePole
        val result = arc / (degreesPerSecond * factor)
        if (!(result <= maximumDurationSeconds)) throw StarTrailError.DurationTooLong(result)
        return result
    }

    /**
     * Plans a stack of short exposures that together draw a trail of [totalDuration].
     *
     * Each frame is exposed as long as [rule] allows without trailing (see [ExposureCalculator.maxExposure]),
     * rounded down, unless [exposureSeconds] says otherwise; frames follow each other after [gapSeconds].
     *
     * @param totalDuration The time the trail should span in seconds, in (0, [maximumDurationSeconds]].
     * @param declination The declination of the target in degrees, or null to assume the celestial equator.
     * @param setup The camera and lens.
     * @param rule The exposure rule that sets the single exposure.
     * @param exposureSeconds The exposure to use instead of the rule's, in (0, [maximumExposureSeconds]].
     * @param gapSeconds The time between exposures in seconds, [0, [maximumGapSeconds]].
     * @throws StarTrailError for a value out of range, or if more than [maximumFrameCount] frames are needed.
     */
    @Throws(StarTrailError::class)
    fun stackingPlan(
        totalDuration: Double,
        declination: Double?,
        setup: LensSetup,
        rule: ExposureRule = ExposureRule.NPF,
        exposureSeconds: Double? = null,
        gapSeconds: Double = defaultGapSeconds,
    ): StarTrailStackPlan {
        if (!(totalDuration > 0.0 && totalDuration <= maximumDurationSeconds)) {
            throw StarTrailError.InvalidDuration(totalDuration)
        }
        if (declination != null && !(declination >= -90.0 && declination <= 90.0)) {
            throw StarTrailError.InvalidDeclination(declination)
        }
        if (exposureSeconds != null && !(exposureSeconds > 0.0 && exposureSeconds <= maximumExposureSeconds)) {
            throw StarTrailError.InvalidExposure(exposureSeconds)
        }
        if (!(gapSeconds >= 0.0 && gapSeconds <= maximumGapSeconds)) throw StarTrailError.InvalidGap(gapSeconds)

        val estimate = ExposureCalculator.maxExposure(rule, setup, declination)
        val exposure = exposureSeconds ?: practicalExposure(estimate.seconds)
        val period = exposure + gapSeconds
        val frames = maxOf(1.0, ceil(totalDuration / period - 1e-9))
        if (frames > maximumFrameCount) {
            throw StarTrailError.TooManyFrames(if (frames >= 1e9) 1_000_000_000 else frames.toInt(), maximumFrameCount)
        }
        val frameCount = frames.toInt()
        val actual = frames * period
        val trailDeclination = declination ?: 0.0
        return StarTrailStackPlan(
            totalDuration = totalDuration,
            estimate = estimate,
            exposureSeconds = exposure,
            gapSeconds = gapSeconds,
            frameCount = frameCount,
            actualDuration = actual,
            lightSeconds = frames * exposure,
            exposureTrailPixels = ExposureCalculator.trailLengthPixels(exposure, trailDeclination, setup),
            gapPixels = ExposureCalculator.trailLengthPixels(gapSeconds, trailDeclination, setup),
            trail = trail(minOf(actual, maximumDurationSeconds), trailDeclination, setup),
        )
    }

    /**
     * An exposure rounded down to a value that is easy to set: whole seconds from 2 s up, tenths of a second
     * from 0.1 s, and unchanged below that.
     */
    internal fun practicalExposure(seconds: Double): Double = when {
        seconds >= 2.0 -> floor(seconds)
        seconds >= 0.1 -> floor(seconds * 10.0) / 10.0
        else -> seconds
    }

    /**
     * Where the celestial pole stands: the centre of the star-trail circles.
     *
     * @param frame The instant and observer.
     * @param hemisphere The pole to find; null takes the observer's hemisphere (north on or above the equator).
     * @param refraction Whether to raise the altitude by atmospheric refraction.
     */
    fun pole(
        frame: SkyFrame,
        hemisphere: PolarAlignment.Hemisphere? = null,
        refraction: Boolean = true,
    ): StarTrailPole {
        val chosen = hemisphere ?: PolarAlignment.Hemisphere.fromLatitude(frame.observer.location.latitude)
        return StarTrailPole(
            hemisphere = chosen,
            direction = frame.horizontalOfCelestialPole(chosen == PolarAlignment.Hemisphere.NORTH, refraction),
        )
    }

    /**
     * Where a pole falls in the camera frame, for drawing the circles around it.
     *
     * The picture is a rectilinear view. With [roll] 0 its top points towards the zenith.
     *
     * @param pole The direction of the pole, see [pole].
     * @param pointing The direction the optical axis points.
     * @param frameSize The angular size of the frame as held: `horizontal` runs along the picture's x axis.
     * @param roll The turn of the camera about its axis in degrees, positive clockwise as seen through the
     * camera, as in the mosaic guidance.
     * @return The position, or null if the pole is behind the camera (90° or more from the axis).
     * @throws StarTrailError.InvalidFrame if the frame is not a pair of angles in (0, 180) degrees.
     */
    @Throws(StarTrailError::class)
    fun polePosition(
        pole: HorizontalCoordinate,
        pointing: HorizontalCoordinate,
        frameSize: FrameSize,
        roll: Double = 0.0,
    ): StarTrailPolePosition? {
        if (!(frameSize.horizontal > 0.0 && frameSize.horizontal < 180.0 &&
                frameSize.vertical > 0.0 && frameSize.vertical < 180.0)
        ) {
            throw StarTrailError.InvalidFrame(frameSize.horizontal, frameSize.vertical)
        }
        val axis = pointing.unitVector
        val toPole = pole.unitVector
        val depth = toPole.dot(axis)
        if (!(depth > 1e-9)) return null
        val zenith = Vector3(0.0, 0.0, 1.0)
        val level = zenith - axis * axis.z
        val levelUp = if (level.length > 1e-9) level.normalized else Vector3(0.0, 1.0, 0.0)
        val levelRight = axis.cross(levelUp)
        val turn = if (roll.isFinite()) roll else 0.0
        val up = (levelUp * AstroMath.cosDeg(turn) + levelRight * AstroMath.sinDeg(turn)).normalized
        val right = axis.cross(up)
        val x = toPole.dot(right) / depth / AstroMath.tanDeg(frameSize.horizontal / 2)
        val y = toPole.dot(up) / depth / AstroMath.tanDeg(frameSize.vertical / 2)
        return StarTrailPolePosition(
            x = x,
            y = y,
            separation = axis.angle(toPole),
            isInFrame = abs(x) <= 1.0 && abs(y) <= 1.0,
        )
    }
}

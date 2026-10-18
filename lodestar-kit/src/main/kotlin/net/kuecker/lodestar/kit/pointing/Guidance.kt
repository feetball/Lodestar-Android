package net.kuecker.lodestar.kit.pointing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** How close the camera is to the target. */
@Serializable
enum class GuidanceState(
    /** The stable string used in JSON. */
    val rawValue: String,
) {
    /** Within the on-target threshold (with hysteresis). */
    @SerialName("onTarget")
    ON_TARGET("onTarget"),

    /** Not on target, but within a few frame widths. */
    @SerialName("close")
    CLOSE("close"),

    /** Further away. */
    @SerialName("far")
    FAR("far");

    companion object {
        /** The state with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): GuidanceState? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What the photographer has to do to bring a target to the centre of the frame.
 *
 * Values are produced by [GuidanceTracker.update]; the constructor is private.
 */
@ConsistentCopyVisibility
data class Guidance private constructor(
    /** Great-circle angle between the boresight and the target in degrees, [0, 180]. */
    val separation: Double,
    /**
     * Change of azimuth that centres the target, degrees in (−180, 180]; positive means pan right
     * (clockwise as seen from above). Unreliable when [isNearZenith].
     */
    val panDelta: Double,
    /** Change of altitude that centres the target in degrees; positive means tilt up. */
    val tiltDelta: Double,
    /**
     * Direction of the shortest way to the target, drawn on the phone's screen: radians
     * counter-clockwise from "screen right" (device +x, the right edge of the portrait phone), so 0
     * is right and π/2 is up (device +y, the top edge). The angle is in the phone's own screen axes,
     * whichever way the phone is turned in the clamp: rotate it by the interface orientation if the
     * UI is not drawn in portrait. Meaningless when [separation] is (nearly) zero.
     */
    val arrowAngle: Double,
    /** `true` when the target is less than 90° from the boresight. */
    val isTargetInFront: Boolean,
    /** `true` when the boresight or the target is within 5° of the zenith or nadir. */
    val isNearZenith: Boolean,
    /** `true` when the target's apparent altitude is negative. */
    val targetBelowHorizon: Boolean,
    /** On target / close / far, with hysteresis supplied by [GuidanceTracker]. */
    val state: GuidanceState,
) {
    companion object {
        /**
         * Beyond this absolute altitude (degrees) of the boresight or the target, azimuth is a poor
         * guide: use [arrowAngle] and [separation] only.
         */
        const val nearZenithAltitude: Double = 85.0

        /**
         * Builds the guidance for [target] seen from [solution] (Swift's file-private
         * `init(solution:target:state:)`). [direction] is the target's unit vector and [separation] its
         * angle from the boresight, which the tracker has computed already.
         */
        internal fun create(
            solution: PointingSolution,
            target: HorizontalCoordinate,
            direction: Vector3,
            separation: Double,
            state: GuidanceState,
        ): Guidance {
            val boresight = solution.boresightVector
            return Guidance(
                separation = separation,
                panDelta = AstroMath.normalizeSignedDegrees(target.azimuth - solution.boresight.azimuth),
                tiltDelta = target.altitude - solution.boresight.altitude,
                arrowAngle = arrowAngle(solution, direction),
                isTargetInFront = boresight.dot(direction) > 0,
                isNearZenith = swiftMax(abs(solution.boresight.altitude), abs(target.altitude)) > nearZenithAltitude,
                targetBelowHorizon = target.altitude < 0,
                state = state,
            )
        }

        /**
         * Angle of the great-circle tangent at the boresight, measured on the screen axes. A target
         * exactly behind the camera has no unique direction; screen-right is reported.
         */
        private fun arrowAngle(solution: PointingSolution, direction: Vector3): Double {
            // `direction - boresight * boresight.dot(direction)` written out, so that no vectors are allocated.
            val boresight = solution.boresightVector
            val along = boresight.dot(direction)
            val x = direction.x - boresight.x * along
            val y = direction.y - boresight.y * along
            val z = direction.z - boresight.z * along
            if (!(sqrt(x * x + y * y + z * z) > 1e-12)) return 0.0
            val up = solution.screenUpVector
            val right = solution.screenRightVector
            return atan2(x * up.x + y * up.y + z * up.z, x * right.x + y * right.y + z * right.z)
        }
    }
}

/**
 * Produces [Guidance] and remembers whether the camera is on target, so the on-target state does
 * not flicker at the edge of the threshold: it is entered below [onTargetThreshold] and left
 * only beyond 1.5 times that. Call [reset] when the target changes.
 *
 * Unlike the Swift struct (whose `update` and `reset` are `mutating`), this is a class that holds the
 * state: [update] and [reset] change the object itself, two references to one tracker share their state,
 * and the class is not thread safe (call it from one thread, or synchronize externally). Equality (and
 * the hash code) compares the thresholds and the current on-target state, like the Swift `Hashable`
 * conformance; do not use a tracker as a key of a hash-based collection while it is in use.
 *
 * Non-positive or non-finite thresholds fall back to 0.5°; the close threshold defaults to three times
 * the on-target threshold, at least 5°.
 *
 * @param onTargetThreshold Separation in degrees below which the camera counts as on target.
 * @param closeThreshold Separation in degrees below which the camera counts as close; null for the default.
 */
class GuidanceTracker(onTargetThreshold: Double, closeThreshold: Double? = null) {
    /** Separation in degrees below which the camera counts as on target. */
    val onTargetThreshold: Double

    /** Separation in degrees below which the camera counts as close. */
    val closeThreshold: Double

    private var isOnTarget = false

    init {
        val enter = if (onTargetThreshold.isFinite() && onTargetThreshold > 0) onTargetThreshold else minimumThreshold
        this.onTargetThreshold = enter
        this.closeThreshold = if (closeThreshold != null && closeThreshold.isFinite()) {
            swiftMax(closeThreshold, enter)
        } else {
            swiftMax(3 * enter, 5.0)
        }
    }

    /**
     * Guidance from the current pointing to [target] (its apparent, refracted horizontal position);
     * null when the target is not a finite direction.
     */
    fun update(solution: PointingSolution, target: HorizontalCoordinate): Guidance? {
        if (!(target.azimuth.isFinite() && target.altitude.isFinite())) return null
        val direction = target.unitVector
        val separation = solution.boresightVector.angle(direction)
        if (isOnTarget) {
            if (separation > onTargetThreshold * hysteresisFactor) isOnTarget = false
        } else if (separation < onTargetThreshold) {
            isOnTarget = true
        }
        val state = if (isOnTarget) {
            GuidanceState.ON_TARGET
        } else if (separation < closeThreshold) {
            GuidanceState.CLOSE
        } else {
            GuidanceState.FAR
        }
        return Guidance.create(solution, target, direction, separation, state)
    }

    /** Forgets the on-target state, e.g. when the user picks another target. */
    fun reset() {
        isOnTarget = false
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is GuidanceTracker &&
                onTargetThreshold == other.onTargetThreshold &&
                closeThreshold == other.closeThreshold &&
                isOnTarget == other.isOnTarget
            )

    override fun hashCode(): Int {
        var result = onTargetThreshold.hashCode()
        result = 31 * result + closeThreshold.hashCode()
        result = 31 * result + isOnTarget.hashCode()
        return result
    }

    override fun toString(): String =
        "GuidanceTracker(onTargetThreshold=$onTargetThreshold, closeThreshold=$closeThreshold, isOnTarget=$isOnTarget)"

    companion object {
        /** Ratio between the leaving and entering separations. */
        const val hysteresisFactor: Double = 1.5
        private const val minimumThreshold = 0.5

        /**
         * The suggested on-target threshold for a camera frame: a quarter of the frame's short side,
         * at least 0.5°. Invalid field-of-view values count as zero.
         */
        fun defaultOnTargetThreshold(horizontalFieldOfView: Double, verticalFieldOfView: Double): Double {
            val horizontal = if (horizontalFieldOfView.isFinite()) swiftMax(horizontalFieldOfView, 0.0) else 0.0
            val vertical = if (verticalFieldOfView.isFinite()) swiftMax(verticalFieldOfView, 0.0) else 0.0
            return swiftMax(minimumThreshold, 0.25 * swiftMin(horizontal, vertical))
        }
    }
}

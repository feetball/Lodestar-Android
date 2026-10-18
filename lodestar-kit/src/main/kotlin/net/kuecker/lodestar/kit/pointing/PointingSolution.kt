package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*

/**
 * Where the camera is pointing and how it is oriented, in the world (ENU) frame.
 *
 * Create values with [solve]; the constructor is internal, like Swift's memberwise initializer.
 */
@ConsistentCopyVisibility
data class PointingSolution internal constructor(
    /** The direction the camera's optical axis points, as azimuth and altitude. */
    val boresight: HorizontalCoordinate,
    /** The optical axis as an ENU unit vector. */
    val boresightVector: Vector3,
    /** The camera's "up" (towards the top of the picture) as an ENU unit vector. */
    val cameraUpVector: Vector3,
    /** The camera's right (towards the right of the picture) as an ENU unit vector. */
    val cameraRightVector: Vector3,
    /**
     * Rotation of the camera about its optical axis in degrees: 0 when the camera's horizontal
     * axis is level, positive when the camera is turned clockwise as seen by the photographer
     * (its right side lower), in (−180, 180]. Undefined, reported as 0, within 0.0001° of the
     * zenith or nadir; it changes quickly just beyond that.
     */
    val roll: Double,
    /**
     * The world direction that the phone's screen draws as "right" (a unit vector perpendicular to
     * the boresight).
     */
    val screenRightVector: Vector3,
    /**
     * The world direction that the phone's screen draws as "up" (a unit vector perpendicular to the
     * boresight).
     */
    val screenUpVector: Vector3,
) {
    companion object {
        /** Within this many degrees of the zenith or nadir the horizon has no direction, so `roll` is 0. */
        private const val rollUndefinedWithin = 1e-4

        /**
         * Combines the phone attitude, the mount and the alignment into the camera's pointing.
         *
         * The alignment is applied only if it was solved for this mount ([AlignmentModel.isApplicable]);
         * otherwise the nominal mount geometry is used.
         */
        fun solve(
            attitude: DeviceAttitude,
            mount: MountProfile,
            alignment: AlignmentModel = AlignmentModel.identity,
        ): PointingSolution {
            val applied = if (alignment.isApplicable(mount)) alignment else AlignmentModel.identity
            val correction = applied.deviceCorrectionMatrix
            val axis = correction * mount.cameraAxis
            val up = correction * mount.cameraUp
            val right = axis.cross(up)
            val deviceToWorld = attitude.withHeadingOffset(applied.headingOffset).deviceToWorld

            val forwardWorld = (deviceToWorld * axis).normalized
            val upWorld = (deviceToWorld * up).normalized
            val rightWorld = (deviceToWorld * right).normalized
            return PointingSolution(
                boresight = HorizontalCoordinate(forwardWorld),
                boresightVector = forwardWorld,
                cameraUpVector = upWorld,
                cameraRightVector = rightWorld,
                roll = roll(forwardWorld, upWorld),
                screenRightVector = rightWorld * mount.screenRight.x + upWorld * mount.screenUp.x,
                screenUpVector = rightWorld * mount.screenRight.y + upWorld * mount.screenUp.y,
            )
        }

        /** Angle of the camera's up vector from the level orientation, positive towards the right. */
        private fun roll(forward: Vector3, up: Vector3): Double {
            val levelRight = forward.cross(PointingMath.worldUp)
            val length = levelRight.length
            if (!(length > AstroMath.sinDeg(rollUndefinedWithin))) return 0.0
            val right = levelRight / length
            val levelUp = right.cross(forward)
            return AstroMath.atan2Deg(up.dot(right), up.dot(levelUp))
        }
    }
}

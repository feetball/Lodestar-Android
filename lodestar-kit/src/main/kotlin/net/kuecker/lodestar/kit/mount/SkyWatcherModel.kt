package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.CoordinateTransforms
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Vector3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The small errors of a two-axis mount that the alignment model can find, all in degrees.
 *
 * `axis1Index` and `axis2Index` are the angles by which the encoder zero is off the documented home position (the user
 * never sets it perfectly). `baseTiltX` and `baseTiltY` tilt the primary axis away from where it should point (equatorial: the
 * polar axis error, alt-azimuth: the level error): about the local x axis (equatorial: towards the meridian point on the equator,
 * alt-azimuth: east) and about the y axis (equatorial: east, alt-azimuth: north). `orthogonality` is the angle by which the
 * secondary axis is not perpendicular to the primary axis, `cone` the angle by which the optical axis is not perpendicular to
 * the secondary axis (equatorial: not parallel to the polar axis at the home position).
 */
data class SkyWatcherPointingErrors(
    /** Offset of the encoder zero of axis 1. */
    val axis1Index: Double = 0.0,
    /** Offset of the encoder zero of axis 2. */
    val axis2Index: Double = 0.0,
    /** Tilt of the primary axis about the local x axis. */
    val baseTiltX: Double = 0.0,
    /** Tilt of the primary axis about the local y axis. */
    val baseTiltY: Double = 0.0,
    /** Non-perpendicularity of the secondary axis. */
    val orthogonality: Double = 0.0,
    /** Collimation (cone) error of the optical axis. */
    val cone: Double = 0.0,
) {
    /**
     * Equatorial mounts: how far the polar axis points too high (positive) or too low above where the celestial pole is, in
     * degrees. Meaningful once at least two stars are aligned.
     */
    val polarAxisAltitudeError: Double get() = baseTiltY

    /**
     * Equatorial mounts in the northern hemisphere (the sign flips in the southern one): how far the polar axis points east
     * (positive) or west of the true pole, as an azimuth difference at the given [latitude].
     */
    fun polarAxisAzimuthError(latitude: Double): Double {
        val scale = cos(AstroMath.radians(latitude))
        return if (kotlin.math.abs(scale) < 0.05) 0.0 else -baseTiltX / scale
    }

    internal fun asRadians(): DoubleArray = doubleArrayOf(
        AstroMath.radians(axis1Index), AstroMath.radians(axis2Index), AstroMath.radians(baseTiltX),
        AstroMath.radians(baseTiltY), AstroMath.radians(orthogonality), AstroMath.radians(cone),
    )

    internal companion object {
        fun fromRadians(values: DoubleArray): SkyWatcherPointingErrors = SkyWatcherPointingErrors(
            AstroMath.degrees(values[0]), AstroMath.degrees(values[1]), AstroMath.degrees(values[2]),
            AstroMath.degrees(values[3]), AstroMath.degrees(values[4]), AstroMath.degrees(values[5]),
        )
    }
}

/** One way of reaching a direction: the encoder angles of both axes and the side of the mount. */
data class SkyWatcherAxisSolution(
    /** The encoder angle of axis 1 in degrees, (-180, 180]. */
    val axis1: Double,
    /** The encoder angle of axis 2 in degrees, (-180, 180]. */
    val axis2: Double,
    /** The pier side this solution is on. */
    val pierSide: SkyWatcherPierSide,
)

/**
 * The geometry of a two-axis mount as a function of its encoder angles, and its inverse.
 *
 * Equatorial mounts: axis 1 turns about the polar axis, axis 2 about the declination axis. At the home position (both angles
 * 0) the counterweight hangs down and the telescope points at the pole; the hour angle of the pointing is
 * `axis1 + 90°` for axis 2 > 0 (pier side east) and `axis1 - 90°` for axis 2 < 0 (pier side west), the declination
 * `90° - |axis2|`. The counterweight is below the horizontal while `|axis1| < 90°`, so the pier side changes at the meridian.
 * In the southern hemisphere the sky is mirrored (hour angle and declination change sign), as the mount is the mirror
 * image of a northern one.
 *
 * Alt-azimuth mounts: axis 1 is the azimuth (from north through east), axis 2 the altitude; home is level, pointing north.
 *
 * Directions are unit vectors in the local frame of the sky: equatorial mounts use the local equatorial frame (x towards the
 * meridian point on the celestial equator, y east, z towards the north celestial pole, in both hemispheres), alt-azimuth mounts the
 * ENU horizon frame. The [errors] describe how the real mount deviates from this ideal geometry (see [SkyWatcherPointingErrors]).
 */
data class SkyWatcherPointingModel(
    /** The arrangement of the axes. */
    val mode: SkyWatcherMountMode,
    /** True when the mount stands in the southern hemisphere (equatorial mounts only). */
    val isSouthernHemisphere: Boolean,
    /** The errors found by the alignment. */
    val errors: SkyWatcherPointingErrors,
) {
    /** The direction the mount points at when the encoders read [axis1Degrees] and [axis2Degrees]. */
    fun direction(axis1Degrees: Double, axis2Degrees: Double): Vector3 =
        rawDirection(mode, isSouthernHemisphere, errors.asRadians(), axis1Degrees, axis2Degrees)

    /**
     * The two ways of pointing at [direction]: the encoder angles that make [direction] return it, one for each side of the
     * mount (use [SkyWatcherAxisSolution.pierSide] to pick). At the pole of the primary axis, where axis 1 is undefined, both
     * use [preferredAxis1Degrees].
     */
    fun solutions(direction: Vector3, preferredAxis1Degrees: Double = 0.0): List<SkyWatcherAxisSolution> {
        val values = errors.asRadians()
        val unit = mirrored(direction.normalized)
        val tiltX = values[2]
        val tiltY = values[3]
        val orthogonality = values[4]
        val cone = values[5]
        // Undo the tilt of the primary axis.
        val ux = unit.x * cos(tiltY) - unit.z * sin(tiltY)
        val uy = unit.y
        val uz = unit.x * sin(tiltY) + unit.z * cos(tiltY)
        val wx = ux
        val wy = uy * cos(tiltX) + uz * sin(tiltX)
        val wz = -uy * sin(tiltX) + uz * cos(tiltX)
        val qx = sin(cone)
        val qz = (wz + qx * sin(orthogonality)) / cos(orthogonality)
        val tx = qx * cos(orthogonality) + qz * sin(orthogonality)
        val radius = sqrt(wx * wx + wy * wy)
        val base: Double
        val spread: Double
        if (radius < 1e-12) {
            base = AstroMath.radians(preferredAxis1Degrees + errors.axis1Index)
            spread = 0.0
        } else {
            base = -atan2(wy, wx)
            spread = acos(AstroMath.clamp(tx / radius, -1.0, 1.0))
        }
        val offset = if (mode == SkyWatcherMountMode.EQUATORIAL) PI / 2 else 0.0
        val result = ArrayList<SkyWatcherAxisSolution>(2)
        for (index in 0 until 2) {
            val theta = if (index == 0) base + spread else base - spread
            val qy = wx * sin(theta) + wy * cos(theta)
            val phi = atan2(qz, qy)
            val modelAxis1 = AstroMath.normalizeSignedDegrees(AstroMath.degrees(theta))
            val modelAxis2 = AstroMath.normalizeSignedDegrees(AstroMath.degrees(phi - offset))
            result.add(
                SkyWatcherAxisSolution(
                    axis1 = AstroMath.normalizeSignedDegrees(modelAxis1 - errors.axis1Index),
                    axis2 = AstroMath.normalizeSignedDegrees(modelAxis2 - errors.axis2Index),
                    pierSide = side(modelAxis2),
                ),
            )
        }
        return result
    }

    /** The pier side for the encoder angle [axis2Degrees] of axis 2. */
    fun pierSide(axis2Degrees: Double): SkyWatcherPierSide =
        side(AstroMath.normalizeSignedDegrees(axis2Degrees + errors.axis2Index))

    /** The angle of axis 1 in the ideal geometry (the encoder angle plus the index error), (-180, 180]. */
    fun modelAxis1(axis1Degrees: Double): Double = AstroMath.normalizeSignedDegrees(axis1Degrees + errors.axis1Index)

    private fun side(modelAxis2: Double): SkyWatcherPierSide =
        if (mode == SkyWatcherMountMode.EQUATORIAL) {
            if (modelAxis2 >= 0) SkyWatcherPierSide.EAST else SkyWatcherPierSide.WEST
        } else {
            if (kotlin.math.abs(modelAxis2) <= 90) SkyWatcherPierSide.EAST else SkyWatcherPierSide.WEST
        }

    private fun mirrored(vector: Vector3): Vector3 =
        if (mode == SkyWatcherMountMode.EQUATORIAL && isSouthernHemisphere) Vector3(vector.x, -vector.y, -vector.z) else vector

    internal companion object {
        /** The model of a mount without errors. */
        fun nominal(mode: SkyWatcherMountMode, isSouthernHemisphere: Boolean): SkyWatcherPointingModel =
            SkyWatcherPointingModel(mode, isSouthernHemisphere, SkyWatcherPointingErrors())

        /**
         * The forward geometry with the errors given in radians as `[axis1Index, axis2Index, baseTiltX, baseTiltY,
         * orthogonality, cone]`: the optical axis `q` is rotated about the secondary axis, tilted by the orthogonality error,
         * turned about the primary axis and finally tilted with the base.
         */
        fun rawDirection(
            mode: SkyWatcherMountMode,
            isSouthernHemisphere: Boolean,
            errors: DoubleArray,
            axis1Degrees: Double,
            axis2Degrees: Double,
        ): Vector3 {
            val theta = AstroMath.radians(axis1Degrees) + errors[0]
            val secondary = AstroMath.radians(axis2Degrees) + errors[1]
            val phi = if (mode == SkyWatcherMountMode.EQUATORIAL) secondary + PI / 2 else secondary
            val tiltX = errors[2]
            val tiltY = errors[3]
            val orthogonality = errors[4]
            val cone = errors[5]
            val qx = sin(cone)
            val qy = cos(cone) * cos(phi)
            val qz = cos(cone) * sin(phi)
            // Tilt the secondary axis (rotation about y).
            val rx = qx * cos(orthogonality) + qz * sin(orthogonality)
            val ry = qy
            val rz = -qx * sin(orthogonality) + qz * cos(orthogonality)
            // Turn about the primary axis (rotation about z by -theta).
            val sx = rx * cos(theta) + ry * sin(theta)
            val sy = -rx * sin(theta) + ry * cos(theta)
            val sz = rz
            // Tilt the base: about x, then about y.
            val ux = sx
            val uy = sy * cos(tiltX) - sz * sin(tiltX)
            val uz = sy * sin(tiltX) + sz * cos(tiltX)
            val vx = ux * cos(tiltY) + uz * sin(tiltY)
            val vy = uy
            val vz = -ux * sin(tiltY) + uz * cos(tiltY)
            return if (mode == SkyWatcherMountMode.EQUATORIAL && isSouthernHemisphere) Vector3(vx, -vy, -vz) else Vector3(vx, vy, vz)
        }
    }
}

/**
 * Conversions between apparent places of date and the local frames of [SkyWatcherPointingModel]: the local equatorial frame
 * (x towards the meridian point on the celestial equator, y east, z towards the north celestial pole; the same in both hemispheres)
 * for equatorial mounts and the ENU horizon frame for alt-azimuth mounts. Refraction is not applied.
 */
object SkyWatcherSky {
    /** The sidereal rate in degrees per second (the rate at which the local sidereal time advances). */
    val siderealDegreesPerSecond: Double get() = MountNudgeRate.siderealDegreesPerSecond

    /**
     * The direction in the local frame of the apparent place [place] when the local apparent sidereal time is [localSiderealTime]
     * degrees (equatorial mounts: `(cos δ cos H, -cos δ sin H, sin δ)`; [latitude] is only used for alt-azimuth mounts).
     */
    fun localDirection(
        place: EquatorialCoordinate,
        localSiderealTime: Double,
        latitude: Double,
        mode: SkyWatcherMountMode,
    ): Vector3 {
        val hourAngle = AstroMath.normalizeSignedDegrees(localSiderealTime - place.rightAscension)
        if (mode == SkyWatcherMountMode.ALT_AZIMUTH) {
            return CoordinateTransforms.equatorialToHorizontal(hourAngle, place.declination, latitude).unitVector
        }
        val cosDeclination = AstroMath.cosDeg(place.declination)
        return Vector3(
            cosDeclination * AstroMath.cosDeg(hourAngle),
            -cosDeclination * AstroMath.sinDeg(hourAngle),
            AstroMath.sinDeg(place.declination),
        )
    }

    /** The apparent place that a local [direction] corresponds to at the local apparent sidereal time [localSiderealTime] (the inverse of [localDirection]). */
    fun apparentPlace(
        direction: Vector3,
        localSiderealTime: Double,
        latitude: Double,
        mode: SkyWatcherMountMode,
    ): EquatorialCoordinate {
        val hourAngle: Double
        val declination: Double
        if (mode == SkyWatcherMountMode.ALT_AZIMUTH) {
            val solved = CoordinateTransforms.horizontalToEquatorial(HorizontalCoordinate(direction), latitude)
            hourAngle = solved.hourAngle
            declination = solved.declination
        } else {
            val length = direction.length
            if (!(length > 0)) return EquatorialCoordinate(0.0, 0.0)
            hourAngle = AstroMath.atan2Deg(-direction.y, direction.x)
            declination = AstroMath.asinDeg(direction.z / length)
        }
        return EquatorialCoordinate(localSiderealTime - hourAngle, declination)
    }

    /** The geometric altitude in degrees of the apparent place [place] at the local apparent sidereal time [localSiderealTime]. */
    fun altitude(place: EquatorialCoordinate, localSiderealTime: Double, latitude: Double): Double {
        val hourAngle = AstroMath.normalizeSignedDegrees(localSiderealTime - place.rightAscension)
        return CoordinateTransforms.equatorialToHorizontal(hourAngle, place.declination, latitude).altitude
    }
}

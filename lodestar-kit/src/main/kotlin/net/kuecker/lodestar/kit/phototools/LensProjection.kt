package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * How a lens maps the angle `θ` of a ray from the optical axis to the distance `r` of its image from the centre
 * of the frame, for a lens of focal length `f`.
 *
 * [RECTILINEAR] lenses keep straight lines straight; fisheye lenses trade that for a much wider view and come in
 * the three classic mappings. The inverse mapping, [angleDegrees], is what the field-of-view numbers and the
 * frame outline are built on.
 */
@Serializable
enum class LensProjection(
    /** The stable string used in JSON and as [id]. */
    val rawValue: String,
) {
    /** `r = f · tan θ`: straight lines stay straight. Every ordinary lens; the angle across a frame stays below 180°. */
    @SerialName("rectilinear")
    RECTILINEAR("rectilinear"),

    /** `r = f · θ`: equal angles take equal distances on the sensor (θ in radians). */
    @SerialName("fisheyeEquidistant")
    FISHEYE_EQUIDISTANT("fisheyeEquidistant"),

    /** `r = 2f · sin(θ / 2)`: equal solid angles take equal areas on the sensor; the common circular and diagonal fisheye. */
    @SerialName("fisheyeEquisolid")
    FISHEYE_EQUISOLID("fisheyeEquisolid"),

    /** `r = 2f · tan(θ / 2)`: keeps small shapes round, so the edges are stretched. */
    @SerialName("fisheyeStereographic")
    FISHEYE_STEREOGRAPHIC("fisheyeStereographic");

    /** A stable identifier, equal to the raw value. */
    val id: String
        get() = rawValue

    /** A short name for pickers, for example "Fisheye (equisolid)". */
    val displayName: String
        get() = when (this) {
            RECTILINEAR -> "Rectilinear"
            FISHEYE_EQUIDISTANT -> "Fisheye (equidistant)"
            FISHEYE_EQUISOLID -> "Fisheye (equisolid)"
            FISHEYE_STEREOGRAPHIC -> "Fisheye (stereographic)"
        }

    /** Whether this is one of the fisheye mappings. */
    val isFisheye: Boolean
        get() = this != RECTILINEAR

    /**
     * The angle of a ray from the optical axis, in degrees from 0 to 180, that lands [imageRadiusMillimeters] from
     * the centre of the frame behind a lens of [focalLength] millimetres. This is the inverse of the mapping.
     *
     * An image distance beyond what the mapping can reach (more than `2f` for the equisolid mapping, or
     * `π · f` for the equidistant one) gives 180°. The caller guarantees a finite, non-negative radius and a
     * positive, finite focal length.
     */
    fun angleDegrees(imageRadiusMillimeters: Double, focalLength: Double): Double =
        min(180.0, angleRadians(imageRadiusMillimeters, focalLength) * 180.0 / PI)

    /**
     * The forward mapping: the distance in millimetres from the centre of the frame at which a ray [angleDegrees] from
     * the optical axis lands, or null when the lens cannot image that angle (a rectilinear lens at 90° or more, a
     * stereographic one at 180°) or an argument is not a finite number in range.
     */
    fun imageRadiusMillimeters(angleDegrees: Double, focalLength: Double): Double? {
        if (!(angleDegrees.isFinite() && angleDegrees >= 0.0 && angleDegrees <= 180.0 &&
                focalLength.isFinite() && focalLength > 0.0)
        ) {
            return null
        }
        val theta = angleDegrees * PI / 180.0
        return when (this) {
            RECTILINEAR -> if (angleDegrees < 90.0) focalLength * tan(theta) else null
            FISHEYE_EQUIDISTANT -> focalLength * theta
            FISHEYE_EQUISOLID -> 2.0 * focalLength * sin(theta / 2.0)
            FISHEYE_STEREOGRAPHIC -> if (angleDegrees < 180.0) 2.0 * focalLength * tan(theta / 2.0) else null
        }
    }

    /** [angleDegrees] in radians, from 0 to π, for the frame outline. */
    internal fun angleRadians(imageRadiusMillimeters: Double, focalLength: Double): Double {
        val ratio = imageRadiusMillimeters / focalLength
        val radians = when (this) {
            RECTILINEAR -> atan(ratio)
            FISHEYE_EQUIDISTANT -> ratio
            FISHEYE_EQUISOLID -> 2.0 * asin(min(1.0, ratio / 2.0))
            FISHEYE_STEREOGRAPHIC -> 2.0 * atan(ratio / 2.0)
        }
        return min(PI, radians)
    }

    companion object {
        /** The projection with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): LensProjection? = entries.firstOrNull { it.rawValue == raw }
    }
}

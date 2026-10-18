package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * A precomputed table for asking, many thousand times per frame, whether a direction in the sky is hidden
 * below the skyline.
 *
 * Build one mask when the profile or the view changes (1441 table entries, about 0.05 ms) and keep it.
 * [isBelow] then costs an `atan2` and a square root and allocates nothing, so a sky view can test every star
 * it draws. The comparison is made against the profile's own altitudes, which are
 * **apparent** (as the eye sees the obstacle): test directions that are drawn with refraction applied. For a
 * geometric direction near the horizon the answer can differ by up to the refraction, about 0.5°.
 *
 * @property profile The skyline the mask was built from.
 */
class HorizonMask(val profile: HorizonProfile) {
    /** Sine of the skyline altitude at every [entriesPerDegree]-th of a degree; the last entry repeats the first. */
    private val sines = DoubleArray(HorizonProfile.binCount * entriesPerDegree + 1) { index ->
        AstroMath.sinDeg(profile.altitude(index.toDouble() / entriesPerDegree.toDouble()))
    }

    /** `true` when every direction is above the skyline (a flat profile hides nothing above the horizon). */
    private val isFlat: Boolean = profile.isFlat

    /**
     * Whether the direction with East–North–Up components ([x], [y], [z]) — any length — is below the
     * skyline, i.e. its altitude is lower than the skyline's at its azimuth. A direction exactly on the skyline is
     * not below it; one that is not finite or has no length is not below it either.
     */
    fun isBelow(x: Double, y: Double, z: Double): Boolean {
        val length = sqrt(x * x + y * y + z * z)
        if (!(length > 0 && length.isFinite())) return false
        if (isFlat) return z < 0
        val horizontal = x * x + y * y
        if (horizontal == 0.0) return z < 0
        val azimuth = AstroMath.normalizeDegrees(AstroMath.atan2Deg(x, y))
        val position = azimuth * entriesPerDegree.toDouble()
        val lower = floor(position).toInt()
        val fraction = position - lower.toDouble()
        val skyline = sines[lower] + (sines[lower + 1] - sines[lower]) * fraction
        return z < skyline * length
    }

    /** Whether the East–North–Up [direction] (any length) is below the skyline; see the component form. */
    fun isBelow(direction: Vector3): Boolean = isBelow(direction.x, direction.y, direction.z)

    /** Whether the direction at [azimuth] and [altitude] (degrees) is below the skyline. */
    fun isBelow(azimuth: Double, altitude: Double): Boolean {
        if (!(azimuth.isFinite() && altitude.isFinite())) return false
        return altitude < profile.altitude(azimuth)
    }

    private companion object {
        /** Table resolution: a quarter of a degree, finer than the skyline's own 1° nodes. */
        const val entriesPerDegree: Int = 4
    }
}

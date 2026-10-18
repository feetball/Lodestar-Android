package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.sin

/** Rotation helpers shared by the pointing code. */
internal object PointingMath {
    /** Local vertical in the ENU world frame. */
    val worldUp: Vector3 = Vector3(0.0, 0.0, 1.0)

    /**
     * The rotation matrix of a rotation vector (axis direction × angle in radians), using the
     * Rodrigues formula with coefficients that stay accurate as the angle tends to zero.
     */
    fun rotationMatrix(rotationVector: Vector3): Matrix3 {
        val w = rotationVector
        val angle = w.length
        val sine = sinc(angle)
        val half = sinc(angle / 2)
        val versine = 0.5 * half * half // (1 − cos θ) / θ²
        val k = Matrix3(Vector3(0.0, -w.z, w.y), Vector3(w.z, 0.0, -w.x), Vector3(-w.y, w.x, 0.0))
        val k2 = k * k
        return Matrix3(
            Vector3(1.0, 0.0, 0.0) + k.r0 * sine + k2.r0 * versine,
            Vector3(0.0, 1.0, 0.0) + k.r1 * sine + k2.r1 * versine,
            Vector3(0.0, 0.0, 1.0) + k.r2 * sine + k2.r2 * versine,
        )
    }

    /**
     * Rotates a world-frame vector about the vertical so that its azimuth grows by `degrees`
     * (clockwise as seen from above).
     */
    fun yawed(vector: Vector3, degrees: Double): Vector3 {
        val s = AstroMath.sinDeg(degrees)
        val c = AstroMath.cosDeg(degrees)
        return Vector3(vector.x * c + vector.y * s, -vector.x * s + vector.y * c, vector.z)
    }

    /**
     * `true` when every component of [vector] is zero (IEEE comparison, so `-0.0` counts as zero, like
     * Swift's `vector == .zero`; a data-class `equals` would tell `0.0` and `-0.0` apart).
     */
    fun isZero(vector: Vector3): Boolean = vector.x == 0.0 && vector.y == 0.0 && vector.z == 0.0

    private fun sinc(x: Double): Double = if (abs(x) < 1e-8) 1 - x * x / 6 else sin(x) / x
}

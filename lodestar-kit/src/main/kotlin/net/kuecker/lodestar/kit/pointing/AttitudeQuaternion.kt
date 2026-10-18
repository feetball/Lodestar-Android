package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.atan2
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A unit quaternion `w + xi + yj + zk` for the rotations of device attitudes.
 *
 * The components are mutable, like the Swift struct's `var`s; nothing in the library mutates a quaternion
 * after it was created, so values may be shared freely.
 */
internal class AttitudeQuaternion private constructor(
    /** The scalar part. */
    var w: Double,
    /** The `i` component. */
    var x: Double,
    /** The `j` component. */
    var y: Double,
    /** The `k` component. */
    var z: Double,
) {
    /** The rotation matrix of the (normalised) quaternion. */
    val matrix: Matrix3
        get() {
            val q = normalized
            return Matrix3(
                Vector3(1 - 2 * (q.y * q.y + q.z * q.z), 2 * (q.x * q.y - q.z * q.w), 2 * (q.x * q.z + q.y * q.w)),
                Vector3(2 * (q.x * q.y + q.z * q.w), 1 - 2 * (q.x * q.x + q.z * q.z), 2 * (q.y * q.z - q.x * q.w)),
                Vector3(2 * (q.x * q.z - q.y * q.w), 2 * (q.y * q.z + q.x * q.w), 1 - 2 * (q.x * q.x + q.y * q.y)),
            )
        }

    /** The quaternion scaled to unit length; the identity when it has no length. */
    val normalized: AttitudeQuaternion
        get() {
            val length = norm(w, x, y, z)
            if (!(length > 0)) return AttitudeQuaternion(1.0, 0.0, 0.0, 0.0)
            return AttitudeQuaternion(w / length, x / length, y / length, z / length)
        }

    /** The 4-dimensional dot product. */
    fun dot(other: AttitudeQuaternion): Double = w * other.w + x * other.x + y * other.y + z * other.z

    /** The rotation angle in degrees (0…180) that turns `this` into [other] (Swift `angle(to:)`). */
    fun angle(other: AttitudeQuaternion): Double = AstroMath.degrees(2 * arcLength(other))

    /**
     * Spherical interpolation along the shortest arc: `this` at `t = 0`, [other] at `t = 1`
     * (Swift `slerp(to:_:)`).
     */
    fun slerp(other: AttitudeQuaternion, t: Double): AttitudeQuaternion {
        val target = nearerSign(other)
        val theta = arcLength(other)
        val sine = sin(theta)
        // For an arc this short the linear blend is exact to rounding error.
        val from = if (sine > 1e-12) sin((1 - t) * theta) / sine else 1 - t
        val to = if (sine > 1e-12) sin(t * theta) / sine else t
        return AttitudeQuaternion(
            from * w + to * target.w,
            from * x + to * target.x,
            from * y + to * target.y,
            from * z + to * target.z,
        ).normalized
    }

    /** `q` and `−q` are the same rotation; returns the one nearer to `this`. */
    private fun nearerSign(other: AttitudeQuaternion): AttitudeQuaternion =
        if (dot(other) < 0) AttitudeQuaternion(-other.w, -other.x, -other.y, -other.z) else other

    /**
     * Angle in radians (0…π/2) between the unit 4-vectors `this` and the nearer of ±[other],
     * as `2·atan2(|p − q|, |p + q|)`, which stays accurate for tiny angles.
     */
    private fun arcLength(other: AttitudeQuaternion): Double {
        val q = nearerSign(other)
        val difference = norm(w - q.w, x - q.x, y - q.y, z - q.z)
        val sum = norm(w + q.w, x + q.x, y + q.y, z + q.z)
        return 2 * atan2(difference, sum)
    }

    companion object {
        /** The quaternion of a proper rotation matrix (Shepperd's method). */
        operator fun invoke(m: Matrix3): AttitudeQuaternion {
            val m00 = m.r0.x
            val m01 = m.r0.y
            val m02 = m.r0.z
            val m10 = m.r1.x
            val m11 = m.r1.y
            val m12 = m.r1.z
            val m20 = m.r2.x
            val m21 = m.r2.y
            val m22 = m.r2.z
            val trace = m00 + m11 + m22
            val w: Double
            val x: Double
            val y: Double
            val z: Double
            if (trace > 0) {
                val s = sqrt(trace + 1) * 2
                w = s / 4
                x = (m21 - m12) / s
                y = (m02 - m20) / s
                z = (m10 - m01) / s
            } else if (m00 > m11 && m00 > m22) {
                val s = sqrt(1 + m00 - m11 - m22) * 2
                w = (m21 - m12) / s
                x = s / 4
                y = (m01 + m10) / s
                z = (m02 + m20) / s
            } else if (m11 > m22) {
                val s = sqrt(1 + m11 - m00 - m22) * 2
                w = (m02 - m20) / s
                x = (m01 + m10) / s
                y = s / 4
                z = (m12 + m21) / s
            } else {
                val s = sqrt(1 + m22 - m00 - m11) * 2
                w = (m10 - m01) / s
                x = (m02 + m20) / s
                y = (m12 + m21) / s
                z = s / 4
            }
            return AttitudeQuaternion(w, x, y, z).normalized
        }

        private fun norm(a: Double, b: Double, c: Double, d: Double): Double = sqrt(a * a + b * b + c * c + d * d)
    }
}

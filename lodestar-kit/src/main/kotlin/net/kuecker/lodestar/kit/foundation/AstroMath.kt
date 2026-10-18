package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Numeric helpers shared by the astronomy code.
 *
 * All angles are in degrees unless a parameter or result name ends in `Radians`.
 */
object AstroMath {
    /** Radians per degree, π / 180. */
    const val radiansPerDegree: Double = PI / 180.0

    /** Degrees per radian, 180 / π. */
    const val degreesPerRadian: Double = 180.0 / PI

    /** Arcseconds per degree, 3600. */
    const val arcsecondsPerDegree: Double = 3600.0

    /** Arcseconds per radian, 180 · 3600 / π. */
    const val arcsecondsPerRadian: Double = 180.0 * 3600.0 / PI

    /** Converts degrees to radians. */
    fun radians(degrees: Double): Double = degrees * radiansPerDegree

    /** Converts radians to degrees. */
    fun degrees(radians: Double): Double = radians * degreesPerRadian

    /** Reduces an angle to the range [0, 360). Non-finite input is returned unchanged. */
    fun normalizeDegrees(degrees: Double): Double {
        if (!degrees.isFinite()) return degrees
        var reduced = degrees % 360.0
        if (reduced < 0) reduced += 360.0
        // `-tiny + 360` can round to exactly 360.
        return if (reduced >= 360) 0.0 else reduced
    }

    /** Reduces an angle to the range (-180, 180]. Non-finite input is returned unchanged. */
    fun normalizeSignedDegrees(degrees: Double): Double {
        val reduced = normalizeDegrees(degrees)
        return if (reduced > 180) reduced - 360.0 else reduced
    }

    /**
     * Restricts `value` to the closed range `lower..upper`, with Swift's `min(max(value, lower), upper)`
     * semantics (a NaN `value` stays NaN).
     */
    fun <T : Comparable<T>> clamp(value: T, lower: T, upper: T): T {
        val atLeastLower = if (lower >= value) lower else value
        return if (upper < atLeastLower) upper else atLeastLower
    }

    /** [clamp] for `Double`, without boxing. */
    fun clamp(value: Double, lower: Double, upper: Double): Double =
        swiftMin(swiftMax(value, lower), upper)

    /** [clamp] for `Int`, without boxing. */
    fun clamp(value: Int, lower: Int, upper: Int): Int {
        val atLeastLower = if (lower >= value) lower else value
        return if (upper < atLeastLower) upper else atLeastLower
    }

    /** Sine of an angle given in degrees. */
    fun sinDeg(degrees: Double): Double = sin(degrees * radiansPerDegree)

    /** Cosine of an angle given in degrees. */
    fun cosDeg(degrees: Double): Double = cos(degrees * radiansPerDegree)

    /** Tangent of an angle given in degrees. */
    fun tanDeg(degrees: Double): Double = tan(degrees * radiansPerDegree)

    /** Arcsine in degrees; the argument is clamped to [-1, 1] to absorb rounding error. */
    fun asinDeg(x: Double): Double = asin(clamp(x, -1.0, 1.0)) * degreesPerRadian

    /** Arccosine in degrees; the argument is clamped to [-1, 1] to absorb rounding error. */
    fun acosDeg(x: Double): Double = acos(clamp(x, -1.0, 1.0)) * degreesPerRadian

    /** Two-argument arctangent in degrees, in (-180, 180]. */
    fun atan2Deg(y: Double, x: Double): Double = atan2(y, x) * degreesPerRadian

    /** Evaluates `c[0] + c[1]·x + c[2]·x² + …` with Horner's scheme. */
    fun polynomial(x: Double, coefficients: List<Double>): Double {
        var result = 0.0
        var index = coefficients.size - 1
        while (index >= 0) {
            result = result * x + coefficients[index]
            index--
        }
        return result
    }

    /**
     * Evaluates `c[0] + c[1]·x + c[2]·x² + …` with Horner's scheme; the allocation-light spelling of
     * [polynomial] for literal coefficients (`polynomial(t, 21.448, -46.8150, -0.00059, 0.001813)`).
     */
    fun polynomial(x: Double, vararg coefficients: Double): Double {
        var result = 0.0
        var index = coefficients.size - 1
        while (index >= 0) {
            result = result * x + coefficients[index]
            index--
        }
        return result
    }
}

/**
 * A three-component vector of `Double`s (positions, directions, velocities).
 *
 * Immutable value type; every operation returns a new vector.
 *
 * @property x First component.
 * @property y Second component.
 * @property z Third component.
 */
@Serializable
data class Vector3(val x: Double, val y: Double, val z: Double) {
    /** The squared Euclidean length, `x² + y² + z²`. */
    val lengthSquared: Double get() = x * x + y * y + z * z

    /** The Euclidean length. */
    val length: Double get() = sqrt(lengthSquared)

    /** The vector scaled to unit length, or [zero] when the vector has no length. */
    val normalized: Vector3
        get() {
            val l = length
            return if (l > 0) this / l else zero
        }

    /** `true` when all components are finite. */
    val isFinite: Boolean get() = x.isFinite() && y.isFinite() && z.isFinite()

    /** The dot product with [other]. */
    fun dot(other: Vector3): Double = x * other.x + y * other.y + z * other.z

    /** The cross product with [other]. */
    fun cross(other: Vector3): Vector3 =
        Vector3(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )

    /** Angle between two vectors in radians, numerically stable for small and near-π angles. */
    fun angleRadians(other: Vector3): Double {
        val cx = y * other.z - z * other.y
        val cy = z * other.x - x * other.z
        val cz = x * other.y - y * other.x
        return atan2(sqrt(cx * cx + cy * cy + cz * cz), dot(other))
    }

    /** Angle between two vectors in degrees. */
    fun angle(other: Vector3): Double = AstroMath.degrees(angleRadians(other))

    /** Component-wise sum. */
    operator fun plus(other: Vector3): Vector3 = Vector3(x + other.x, y + other.y, z + other.z)

    /** Component-wise difference. */
    operator fun minus(other: Vector3): Vector3 = Vector3(x - other.x, y - other.y, z - other.z)

    /** The negated vector. */
    operator fun unaryMinus(): Vector3 = Vector3(-x, -y, -z)

    /** The vector scaled by [scalar]. */
    operator fun times(scalar: Double): Vector3 = Vector3(x * scalar, y * scalar, z * scalar)

    /** The vector divided by [scalar]. */
    operator fun div(scalar: Double): Vector3 = Vector3(x / scalar, y / scalar, z / scalar)

    /** Formats as `(x, y, z)`, like Swift's `description`. */
    override fun toString(): String = "($x, $y, $z)"

    companion object {
        /** The zero vector. */
        val zero: Vector3 = Vector3(0.0, 0.0, 0.0)
    }
}

/** The vector scaled by [this] (Swift's `Double * Vector3`). */
operator fun Double.times(vector: Vector3): Vector3 = vector * this

/**
 * A 3×3 matrix stored as three row vectors.
 *
 * `m * v` is the ordinary matrix–vector product. The `rotationX/Y/Z` constructors are *active*
 * right-handed rotations (they rotate vectors counter-clockwise when viewed from the tip of the
 * axis). The `frameRotationX/Y/Z` constructors are the *passive* rotations used by SOFA and Meeus
 * (`R1`, `R2`, `R3`): they re-express a fixed vector in a frame rotated by the given angle.
 *
 * Immutable value type; every operation returns a new matrix.
 *
 * @property r0 First row.
 * @property r1 Second row.
 * @property r2 Third row.
 */
@Serializable
data class Matrix3(val r0: Vector3, val r1: Vector3, val r2: Vector3) {
    /** First column. */
    val c0: Vector3 get() = Vector3(r0.x, r1.x, r2.x)

    /** Second column. */
    val c1: Vector3 get() = Vector3(r0.y, r1.y, r2.y)

    /** Third column. */
    val c2: Vector3 get() = Vector3(r0.z, r1.z, r2.z)

    /** The transposed matrix (the inverse, for a rotation). */
    val transposed: Matrix3 get() = Matrix3(c0, c1, c2)

    /** The determinant. */
    val determinant: Double get() = r0.dot(r1.cross(r2))

    /** `true` when all elements are finite. */
    val isFinite: Boolean get() = r0.isFinite && r1.isFinite && r2.isFinite

    /** Element access with zero-based indices; throws [IllegalArgumentException] when out of range. */
    operator fun get(row: Int, column: Int): Double {
        require(row in 0..2 && column in 0..2) { "Matrix3 index out of range" }
        val r = when (row) {
            0 -> r0
            1 -> r1
            else -> r2
        }
        return when (column) {
            0 -> r.x
            1 -> r.y
            else -> r.z
        }
    }

    /** The matrix–vector product `this · v`. */
    operator fun times(v: Vector3): Vector3 = Vector3(r0.dot(v), r1.dot(v), r2.dot(v))

    /** The matrix product `this · b`. */
    operator fun times(b: Matrix3): Matrix3 =
        Matrix3(
            Vector3(
                r0.x * b.r0.x + r0.y * b.r1.x + r0.z * b.r2.x,
                r0.x * b.r0.y + r0.y * b.r1.y + r0.z * b.r2.y,
                r0.x * b.r0.z + r0.y * b.r1.z + r0.z * b.r2.z,
            ),
            Vector3(
                r1.x * b.r0.x + r1.y * b.r1.x + r1.z * b.r2.x,
                r1.x * b.r0.y + r1.y * b.r1.y + r1.z * b.r2.y,
                r1.x * b.r0.z + r1.y * b.r1.z + r1.z * b.r2.z,
            ),
            Vector3(
                r2.x * b.r0.x + r2.y * b.r1.x + r2.z * b.r2.x,
                r2.x * b.r0.y + r2.y * b.r1.y + r2.z * b.r2.y,
                r2.x * b.r0.z + r2.y * b.r1.z + r2.z * b.r2.z,
            ),
        )

    /**
     * The nearest proper rotation obtained by Gram–Schmidt orthonormalisation of the rows
     * (row 0 keeps its direction, row 1 is made perpendicular to it, row 2 = row 0 × row 1).
     * Useful to clean up sensor-supplied rotation matrices.
     */
    fun orthonormalized(): Matrix3 {
        val x = r0.normalized
        val y = (r1 - x * x.dot(r1)).normalized
        return Matrix3(x, y, x.cross(y))
    }

    /** Formats as `[(r0), (r1), (r2)]`, like Swift's `description`. */
    override fun toString(): String = "[$r0, $r1, $r2]"

    companion object {
        /** The identity matrix. */
        val identity: Matrix3 = Matrix3(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))

        /** Creates a matrix from its three columns (Swift `init(columns:_:_:)`). */
        fun fromColumns(c0: Vector3, c1: Vector3, c2: Vector3): Matrix3 =
            Matrix3(
                Vector3(c0.x, c1.x, c2.x),
                Vector3(c0.y, c1.y, c2.y),
                Vector3(c0.z, c1.z, c2.z),
            )

        /** Active rotation about the x axis. */
        fun rotationX(angleRadians: Double): Matrix3 {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3(Vector3(1.0, 0.0, 0.0), Vector3(0.0, c, -s), Vector3(0.0, s, c))
        }

        /** Active rotation about the y axis. */
        fun rotationY(angleRadians: Double): Matrix3 {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3(Vector3(c, 0.0, s), Vector3(0.0, 1.0, 0.0), Vector3(-s, 0.0, c))
        }

        /** Active rotation about the z axis. */
        fun rotationZ(angleRadians: Double): Matrix3 {
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            return Matrix3(Vector3(c, -s, 0.0), Vector3(s, c, 0.0), Vector3(0.0, 0.0, 1.0))
        }

        /** Passive (frame) rotation about the x axis — SOFA/Meeus `R1(θ)`. */
        fun frameRotationX(angleRadians: Double): Matrix3 = rotationX(angleRadians).transposed

        /** Passive (frame) rotation about the y axis — SOFA/Meeus `R2(θ)`. */
        fun frameRotationY(angleRadians: Double): Matrix3 = rotationY(angleRadians).transposed

        /** Passive (frame) rotation about the z axis — SOFA/Meeus `R3(θ)`. */
        fun frameRotationZ(angleRadians: Double): Matrix3 = rotationZ(angleRadians).transposed

        /**
         * Active rotation by `angleRadians` about an arbitrary axis (Rodrigues' formula).
         * Returns [identity] when the axis has no length.
         */
        fun rotation(axis: Vector3, angleRadians: Double): Matrix3 {
            val k = axis.normalized
            if (k == Vector3.zero) return identity
            val c = cos(angleRadians)
            val s = sin(angleRadians)
            val t = 1.0 - c
            return Matrix3(
                Vector3(t * k.x * k.x + c, t * k.x * k.y - s * k.z, t * k.x * k.z + s * k.y),
                Vector3(t * k.x * k.y + s * k.z, t * k.y * k.y + c, t * k.y * k.z - s * k.x),
                Vector3(t * k.x * k.z - s * k.y, t * k.y * k.z + s * k.x, t * k.z * k.z + c),
            )
        }
    }
}

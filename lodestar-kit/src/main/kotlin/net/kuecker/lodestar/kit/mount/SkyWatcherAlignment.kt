package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.Vector3
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * One alignment measurement: the star whose direction in the mount's local frame is [direction] was centred while the encoders
 * read [axis1Degrees] and [axis2Degrees].
 */
data class SkyWatcherAlignmentPoint(
    /** The encoder angle of axis 1 in degrees (position count times 360 over the counts per revolution, signed by the axis sense). */
    val axis1Degrees: Double,
    /** The encoder angle of axis 2 in degrees. */
    val axis2Degrees: Double,
    /** The true direction of the star in the local frame (see [SkyWatcherPointingModel]). */
    val direction: Vector3,
    /** When the measurement was taken. */
    val timestamp: Instant,
    /** The star's name, if the app gave one (at most 120 characters). */
    val name: String? = null,
)

/** The result of fitting the pointing model to the alignment points. */
data class SkyWatcherAlignmentFit(
    /** The model with the fitted errors. */
    val model: SkyWatcherPointingModel,
    /** For each point, the angle in degrees between the star and where the fitted model says the mount points. */
    val residualsDegrees: List<Double>,
    /**
     * How far, in degrees, the model is expected to be off when it points somewhere else than at the aligned stars (the root mean
     * square over a grid of axis angles, from the covariance of the fitted errors). A single star leaves about a degree, a well
     * spread set of three stars a few tenths: add stars while the number is large.
     */
    val predictedErrorDegrees: Double,
) {
    /** The root mean square of the residuals in degrees. */
    val rmsDegrees: Double
        get() {
            if (residualsDegrees.isEmpty()) return 0.0
            var sum = 0.0
            for (value in residualsDegrees) sum += value * value
            return sqrt(sum / residualsDegrees.size)
        }

    /** The largest residual in degrees. */
    val maximumResidualDegrees: Double
        get() {
            var result = 0.0
            for (value in residualsDegrees) if (value > result) result = value
            return result
        }

    /**
     * False when the fitted errors are larger than a mount set up by hand can have (see [SkyWatcherAlignmentSolver.maximumIndexDegrees]
     * and its siblings): usually a wrong star was centred, or the mount was not at its home position. The residuals may look fine
     * then, because enough free errors can explain anything.
     */
    val isPlausible: Boolean
        get() {
            val errors = model.errors
            return abs(errors.axis1Index) <= SkyWatcherAlignmentSolver.maximumIndexDegrees &&
                abs(errors.axis2Index) <= SkyWatcherAlignmentSolver.maximumIndexDegrees &&
                sqrt(errors.baseTiltX * errors.baseTiltX + errors.baseTiltY * errors.baseTiltY) <= SkyWatcherAlignmentSolver.maximumTiltDegrees &&
                abs(errors.orthogonality) <= SkyWatcherAlignmentSolver.maximumOrthogonalityDegrees &&
                abs(errors.cone) <= SkyWatcherAlignmentSolver.maximumConeDegrees
        }
}

/**
 * How much the alignment fit trusts the points compared to what a hand-made set-up usually looks like (all in degrees). The two index
 * offsets have no prior: the home position can be off by any amount.
 */
data class SkyWatcherAlignmentPriors(
    /** The assumed scatter of one centred pointing; also covers what the model does not describe. */
    val measurementSigmaDegrees: Double = 0.1,
    /** The usual size of the tilt of the primary axis (polar alignment error, level error). */
    val tiltSigmaDegrees: Double = 1.0,
    /** The usual size of the orthogonality error. */
    val orthogonalitySigmaDegrees: Double = 0.2,
    /** The usual size of the cone error (a camera on a bracket is not collimated with the mount). */
    val coneSigmaDegrees: Double = 1.0,
) {
    companion object {
        /** The values the driver uses. */
        val standard: SkyWatcherAlignmentPriors = SkyWatcherAlignmentPriors()
    }
}

/**
 * Fits the [SkyWatcherPointingModel] errors to alignment points by damped (Levenberg–Marquardt) least squares, in the spirit of
 * the phone alignment of `AlignmentSolver`: the six errors are always fitted together, and a prior on each of the four small ones
 * (what a hand-made set-up looks like, [SkyWatcherAlignmentPriors]) keeps the poorly determined ones at zero, so that a few stars never
 * invent large corrections.
 *
 * One star determines the two index offsets exactly and leaves the others at zero. Two stars begin to show the tilt of the primary axis
 * (the polar alignment error of an equatorial mount, the level error of an alt-azimuth one), three well-spread stars the orthogonality
 * and cone errors as well. How much the fit can be trusted away from the stars is [SkyWatcherAlignmentFit.predictedErrorDegrees]; the
 * residuals tell how well the model explains the points: with a wrong star they are large.
 */
object SkyWatcherAlignmentSolver {
    /** The most alignment points kept. */
    const val maximumPoints: Int = 8

    /** The largest home position error (encoder zero) of a plausible alignment, degrees. */
    const val maximumIndexDegrees: Double = 60.0

    /** The largest tilt of the primary axis (polar alignment or level error) of a plausible alignment, degrees. */
    const val maximumTiltDegrees: Double = 6.0

    /** The largest orthogonality error of a plausible alignment, degrees. */
    const val maximumOrthogonalityDegrees: Double = 1.5

    /** The largest cone error of a plausible alignment, degrees. */
    const val maximumConeDegrees: Double = 1.5

    private const val maximumIterations = 80
    private const val derivativeStep = 1e-6
    private const val parameterCount = 6
    private val equatorialSampleAxis1 = doubleArrayOf(-60.0, -20.0, 20.0, 60.0)
    private val equatorialSampleAxis2 = doubleArrayOf(10.0, 40.0, 70.0, 100.0)
    private val altAzimuthSampleAxis1 = doubleArrayOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)
    private val altAzimuthSampleAxis2 = doubleArrayOf(20.0, 45.0, 70.0)

    /**
     * Fits the model.
     *
     * @throws MountError.InvalidInput if there are no points, more than [maximumPoints] or a value is not finite.
     */
    @Throws(MountError::class)
    fun solve(
        points: List<SkyWatcherAlignmentPoint>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        priors: SkyWatcherAlignmentPriors = SkyWatcherAlignmentPriors.standard,
    ): SkyWatcherAlignmentFit {
        if (points.isEmpty()) throw MountError.InvalidInput("there is no alignment point")
        if (points.size > maximumPoints) throw MountError.InvalidInput("at most $maximumPoints alignment points are used")
        for (point in points) {
            if (!point.axis1Degrees.isFinite() || !point.axis2Degrees.isFinite() || !point.direction.isFinite ||
                !(point.direction.length > 0)
            ) {
                throw MountError.InvalidInput("an alignment point is not a number")
            }
        }
        val directions = points.map { it.direction.normalized }
        val values = DoubleArray(parameterCount)
        initialIndex(points, directions, mode, isSouthernHemisphere, values)

        var cost = costOf(values, points, directions, mode, isSouthernHemisphere, priors)
        var lambda = 1e-3
        for (iteration in 0 until maximumIterations) {
            val residual = residuals(values, points, directions, mode, isSouthernHemisphere, priors)
            val jacobian = jacobianOf(values, points, directions, mode, isSouthernHemisphere, priors, residual.size)
            val normal = normalMatrix(jacobian, residual.size)
            val gradient = DoubleArray(parameterCount)
            for (a in 0 until parameterCount) {
                var sum = 0.0
                for (row in residual.indices) sum += jacobian[a][row] * residual[row]
                gradient[a] = sum
            }
            var accepted = false
            var largestStep = 0.0
            var newCost = cost
            for (attempt in 0 until 14) {
                val damped = Array(parameterCount) { row -> DoubleArray(parameterCount) { column -> normal[row][column] } }
                for (index in 0 until parameterCount) damped[index][index] += lambda * (normal[index][index] + 1e-9)
                val step = solveLinear(damped, DoubleArray(parameterCount) { -gradient[it] })
                if (step == null) {
                    lambda *= 4
                    continue
                }
                val trial = values.copyOf()
                for (index in 0 until parameterCount) trial[index] += step[index]
                val trialCost = costOf(trial, points, directions, mode, isSouthernHemisphere, priors)
                if (trialCost < cost) {
                    for (index in 0 until parameterCount) values[index] = trial[index]
                    largestStep = 0.0
                    for (index in 0 until parameterCount) largestStep = maxOf(largestStep, abs(step[index]))
                    newCost = trialCost
                    lambda = maxOf(lambda / 3, 1e-9)
                    accepted = true
                    break
                }
                lambda *= 4
            }
            if (!accepted) break
            val improvement = cost - newCost
            cost = newCost
            if (largestStep < 1e-12 || improvement < 1e-14 * (1 + cost)) break
        }

        val model = SkyWatcherPointingModel(mode, isSouthernHemisphere, SkyWatcherPointingErrors.fromRadians(values))
        val residualsDegrees = ArrayList<Double>(points.size)
        for (index in points.indices) {
            val pointed = model.direction(points[index].axis1Degrees, points[index].axis2Degrees)
            residualsDegrees.add(pointed.angle(directions[index]))
        }
        val predicted = predictedError(values, points, directions, mode, isSouthernHemisphere, priors)
        return SkyWatcherAlignmentFit(model, residualsDegrees, predicted)
    }

    // The index offsets that make each point fit the ideal geometry, averaged (circular mean) over the points.
    private fun initialIndex(
        points: List<SkyWatcherAlignmentPoint>,
        directions: List<Vector3>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        values: DoubleArray,
    ) {
        val nominal = SkyWatcherPointingModel.nominal(mode, isSouthernHemisphere)
        var sin1 = 0.0
        var cos1 = 0.0
        var sin2 = 0.0
        var cos2 = 0.0
        for (index in points.indices) {
            val point = points[index]
            val candidates = nominal.solutions(directions[index], point.axis1Degrees)
            var bestOffset1 = 0.0
            var bestOffset2 = 0.0
            var bestCost = Double.MAX_VALUE
            for (candidate in candidates) {
                val offset1 = AstroMath.normalizeSignedDegrees(candidate.axis1 - point.axis1Degrees)
                val offset2 = AstroMath.normalizeSignedDegrees(candidate.axis2 - point.axis2Degrees)
                val candidateCost = abs(offset1) + abs(offset2)
                if (candidateCost < bestCost) {
                    bestCost = candidateCost
                    bestOffset1 = offset1
                    bestOffset2 = offset2
                }
            }
            sin1 += AstroMath.sinDeg(bestOffset1)
            cos1 += AstroMath.cosDeg(bestOffset1)
            sin2 += AstroMath.sinDeg(bestOffset2)
            cos2 += AstroMath.cosDeg(bestOffset2)
        }
        values[0] = atan2(sin1, cos1)
        values[1] = atan2(sin2, cos2)
    }

    /** The weighted residuals: three per point (the vector between the pointed and the true direction over the scatter) and four priors. */
    private fun residuals(
        values: DoubleArray,
        points: List<SkyWatcherAlignmentPoint>,
        directions: List<Vector3>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        priors: SkyWatcherAlignmentPriors,
    ): DoubleArray {
        val sigma = AstroMath.radians(priors.measurementSigmaDegrees)
        val result = DoubleArray(3 * points.size + 4)
        for (index in points.indices) {
            val pointed = SkyWatcherPointingModel.rawDirection(
                mode, isSouthernHemisphere, values, points[index].axis1Degrees, points[index].axis2Degrees,
            )
            result[3 * index] = (pointed.x - directions[index].x) / sigma
            result[3 * index + 1] = (pointed.y - directions[index].y) / sigma
            result[3 * index + 2] = (pointed.z - directions[index].z) / sigma
        }
        val base = 3 * points.size
        result[base] = values[2] / AstroMath.radians(priors.tiltSigmaDegrees)
        result[base + 1] = values[3] / AstroMath.radians(priors.tiltSigmaDegrees)
        result[base + 2] = values[4] / AstroMath.radians(priors.orthogonalitySigmaDegrees)
        result[base + 3] = values[5] / AstroMath.radians(priors.coneSigmaDegrees)
        return result
    }

    private fun costOf(
        values: DoubleArray,
        points: List<SkyWatcherAlignmentPoint>,
        directions: List<Vector3>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        priors: SkyWatcherAlignmentPriors,
    ): Double {
        var sum = 0.0
        for (value in residuals(values, points, directions, mode, isSouthernHemisphere, priors)) sum += value * value
        return sum
    }

    /** The Jacobian by central differences, one row per parameter. */
    private fun jacobianOf(
        values: DoubleArray,
        points: List<SkyWatcherAlignmentPoint>,
        directions: List<Vector3>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        priors: SkyWatcherAlignmentPriors,
        rows: Int,
    ): Array<DoubleArray> {
        val jacobian = Array(parameterCount) { DoubleArray(rows) }
        for (column in 0 until parameterCount) {
            val up = values.copyOf()
            val down = values.copyOf()
            up[column] += derivativeStep
            down[column] -= derivativeStep
            val high = residuals(up, points, directions, mode, isSouthernHemisphere, priors)
            val low = residuals(down, points, directions, mode, isSouthernHemisphere, priors)
            for (row in 0 until rows) jacobian[column][row] = (high[row] - low[row]) / (2 * derivativeStep)
        }
        return jacobian
    }

    private fun normalMatrix(jacobian: Array<DoubleArray>, rows: Int): Array<DoubleArray> {
        val normal = Array(parameterCount) { DoubleArray(parameterCount) }
        for (a in 0 until parameterCount) {
            for (b in 0 until parameterCount) {
                var sum = 0.0
                for (row in 0 until rows) sum += jacobian[a][row] * jacobian[b][row]
                normal[a][b] = sum
            }
        }
        return normal
    }

    /** The root mean square over a grid of axis angles of the pointing error that the covariance of the fitted errors implies. */
    private fun predictedError(
        values: DoubleArray,
        points: List<SkyWatcherAlignmentPoint>,
        directions: List<Vector3>,
        mode: SkyWatcherMountMode,
        isSouthernHemisphere: Boolean,
        priors: SkyWatcherAlignmentPriors,
    ): Double {
        val residual = residuals(values, points, directions, mode, isSouthernHemisphere, priors)
        val jacobian = jacobianOf(values, points, directions, mode, isSouthernHemisphere, priors, residual.size)
        val normal = normalMatrix(jacobian, residual.size)
        val covariance = Array(parameterCount) { DoubleArray(parameterCount) }
        for (column in 0 until parameterCount) {
            val unit = DoubleArray(parameterCount)
            unit[column] = 1.0
            val solved = solveLinear(normal, unit) ?: return 90.0
            for (row in 0 until parameterCount) covariance[row][column] = solved[row]
        }
        val grid1 = if (mode == SkyWatcherMountMode.EQUATORIAL) equatorialSampleAxis1 else altAzimuthSampleAxis1
        val grid2 = if (mode == SkyWatcherMountMode.EQUATORIAL) equatorialSampleAxis2 else altAzimuthSampleAxis2
        var total = 0.0
        var count = 0
        for (axis1 in grid1) {
            for (axis2 in grid2) {
                val sensitivity = Array(parameterCount) { Vector3.zero }
                for (column in 0 until parameterCount) {
                    val up = values.copyOf()
                    val down = values.copyOf()
                    up[column] += derivativeStep
                    down[column] -= derivativeStep
                    val high = SkyWatcherPointingModel.rawDirection(mode, isSouthernHemisphere, up, axis1, axis2)
                    val low = SkyWatcherPointingModel.rawDirection(mode, isSouthernHemisphere, down, axis1, axis2)
                    sensitivity[column] = (high - low) / (2 * derivativeStep)
                }
                var variance = 0.0
                for (a in 0 until parameterCount) {
                    for (b in 0 until parameterCount) {
                        variance += sensitivity[a].dot(sensitivity[b]) * covariance[a][b]
                    }
                }
                total += maxOf(variance, 0.0)
                count += 1
            }
        }
        return AstroMath.degrees(sqrt(total / count.toDouble()))
    }

    /** Solves `a x = b` by Gaussian elimination with partial pivoting; null if the matrix is singular. */
    private fun solveLinear(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { row -> DoubleArray(n + 1) { column -> if (column < n) a[row][column] else b[row] } }
        for (column in 0 until n) {
            var pivot = column
            for (row in column + 1 until n) if (abs(m[row][column]) > abs(m[pivot][column])) pivot = row
            if (abs(m[pivot][column]) < 1e-300) return null
            if (pivot != column) {
                val swap = m[pivot]
                m[pivot] = m[column]
                m[column] = swap
            }
            for (row in column + 1 until n) {
                val factor = m[row][column] / m[column][column]
                for (k in column until n + 1) m[row][k] -= factor * m[column][k]
            }
        }
        val x = DoubleArray(n)
        for (row in n - 1 downTo 0) {
            var sum = m[row][n]
            for (k in row + 1 until n) sum -= m[row][k] * x[k]
            x[row] = sum / m[row][row]
        }
        for (value in x) if (!value.isFinite()) return null
        return x
    }
}

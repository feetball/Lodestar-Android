package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds the heading offset and device-frame correction that bring the phone-derived boresights
 * onto the true positions of the aligned objects.
 *
 * The model for point *i* is `boresightᵢ = Rz(−heading) · Dᵢ · R(ω) · a₀`: the raw device-to-world
 * rotation `Dᵢ`, the mount's nominal camera axis `a₀` turned by the device-frame rotation vector
 * `ω`, then the world yawed by the heading offset. `ω` has no component along `a₀` (a twist about
 * the optical axis does not move the boresight), so it is written `θ_r·right + θ_u·up`.
 */
internal object AlignmentSolver {
    /** The result of a fit. */
    class Fit(
        /** Compass correction in degrees. */
        val headingOffset: Double,
        /** Device-frame rotation vector in degrees. */
        val deviceCorrection: Vector3,
        /** Angular distance in degrees between each corrected boresight and its target. */
        val residuals: List<Double>,
        /** Expected pointing error in degrees away from the points (see [Problem.uncertainty]). */
        val uncertainty: Double,
    )

    /** Assumed scatter of one centred pointing measurement (radians). Weights the data against the prior. */
    private val measurementSigma = AstroMath.radians(0.25)

    /**
     * Prior scatter of the device-frame angles (radians): irrelevant for well-spread points, but it
     * keeps degenerate sets (all at one altitude and roll) from inventing large corrections.
     */
    private val correctionSigma = AstroMath.radians(10.0)

    /**
     * Mean of cos²(altitude) over the sky above the horizon: how much of the compass error shows
     * up as pointing error when the camera looks at a random direction in the sky.
     */
    private const val meanSquaredAltitudeCosine = 2.0 / 3

    /** Cap on the reported uncertainty, degrees. */
    private const val maximumUncertainty = 90.0
    private const val maximumIterations = 100

    /** Damping increases tried per iteration before the search is declared converged. */
    private const val maximumDampingAttempts = 12

    /** Convergence threshold for the largest parameter step, radians. */
    private const val stepTolerance = 1e-11

    /** Step of the central differences for the Jacobian, radians. */
    private const val differenceStep = 1e-6

    /**
     * Fits the model to validated points (at least one). A single point is solved in closed form;
     * more points are refined by Levenberg–Marquardt starting from the newest point's solution.
     */
    fun fit(points: List<AlignmentPoint>, mount: MountProfile): Fit {
        val problem = Problem(points, mount)
        var parameters = closedForm(points[points.size - 1], mount)
        if (points.size > 1) {
            parameters = refine(problem, parameters)
        }
        return problem.fit(parameters)
    }

    /**
     * Exact solution for one point as `[heading, θ_r, θ_u]` in radians: the altitude error is removed
     * by the smallest device-frame rotation about the horizontal axis perpendicular to the measured
     * boresight (valid for any camera roll), the remaining azimuth error becomes the heading offset.
     */
    private fun closedForm(point: AlignmentPoint, mount: MountProfile): DoubleArray {
        val deviceToWorld = point.attitude.deviceToWorld
        val measured = (deviceToWorld * mount.cameraAxis).normalized
        val coordinate = HorizontalCoordinate(measured)
        // Rotating by +θ about ẑ × b lowers b along its vertical circle by θ.
        val horizontalAxis = PointingMath.worldUp.cross(measured).normalized
        val tilt = AstroMath.radians(coordinate.altitude - point.target.altitude)
        val deviceRotation = (deviceToWorld.transposed * horizontalAxis) * tilt
        val heading = AstroMath.radians(AstroMath.normalizeSignedDegrees(point.target.azimuth - coordinate.azimuth))
        return doubleArrayOf(heading, deviceRotation.dot(mount.cameraRight), deviceRotation.dot(mount.cameraUp))
    }

    /** Levenberg–Marquardt with Marquardt scaling on the three parameters. */
    private fun refine(problem: Problem, start: DoubleArray): DoubleArray {
        var parameters = start.copyOf()
        var residuals = problem.residualVector(parameters)
        var cost = sumOfSquares(residuals)
        var damping = 1e-3
        for (iteration in 0 until maximumIterations) {
            val normal = problem.normalEquations(parameters, residuals)
            val hessian = normal.hessian
            val gradient = normal.gradient
            var accepted = false
            var converged = false
            for (attempt in 0 until maximumDampingAttempts) {
                val damped = Array(3) { hessian[it].copyOf() }
                for (a in 0 until 3) damped[a][a] += damping * swiftMax(hessian[a][a], 1e-9)
                val step = solve(damped, doubleArrayOf(-gradient[0], -gradient[1], -gradient[2]))
                if (step != null) {
                    val candidate = doubleArrayOf(parameters[0] + step[0], parameters[1] + step[1], parameters[2] + step[2])
                    val candidateResiduals = problem.residualVector(candidate)
                    val candidateCost = sumOfSquares(candidateResiduals)
                    if (candidateCost < cost) {
                        parameters = candidate
                        residuals = candidateResiduals
                        cost = candidateCost
                        damping = swiftMax(damping / 5, 1e-12)
                        accepted = true
                        converged = step.all { abs(it) < stepTolerance }
                        break
                    }
                }
                damping *= 5
            }
            if (!accepted || converged) break
        }
        return parameters
    }

    private fun sumOfSquares(values: DoubleArray): Double {
        var total = 0.0
        for (value in values) total += value * value
        return total
    }

    /** Gaussian elimination with partial pivoting for a 3×3 system; null when singular. */
    private fun solve(matrix: Array<DoubleArray>, right: DoubleArray): DoubleArray? {
        val a = Array(3) { matrix[it].copyOf() }
        val b = right.copyOf()
        for (column in 0 until 3) {
            var pivot = column
            for (row in column + 1 until 3) {
                if (abs(a[row][column]) > abs(a[pivot][column])) pivot = row
            }
            if (!(abs(a[pivot][column]) > 1e-300)) return null
            val rowSwap = a[column]
            a[column] = a[pivot]
            a[pivot] = rowSwap
            val valueSwap = b[column]
            b[column] = b[pivot]
            b[pivot] = valueSwap
            for (row in column + 1 until 3) {
                val factor = a[row][column] / a[column][column]
                for (k in column until 3) a[row][k] -= factor * a[column][k]
                b[row] -= factor * b[column]
            }
        }
        val x = doubleArrayOf(0.0, 0.0, 0.0)
        for (row in 2 downTo 0) {
            var sum = b[row]
            for (k in row + 1 until 3) sum -= a[row][k] * x[k]
            x[row] = sum / a[row][row]
        }
        return x
    }

    /** The Gauss–Newton normal matrix `JᵀJ` and gradient `Jᵀr`. */
    private class NormalEquations(val hessian: Array<DoubleArray>, val gradient: DoubleArray)

    /** The measurements in a form that can be evaluated quickly for trial parameters. */
    private class Problem(points: List<AlignmentPoint>, mount: MountProfile) {
        /** The raw device-to-world rotation of each point. */
        val deviceToWorld: List<Matrix3> = points.map { it.attitude.deviceToWorld }

        /** The true direction of each point's target. */
        val targets: List<Vector3> = points.map { it.target.unitVector }

        /** The mount's nominal camera axis `a₀`. */
        val axis: Vector3 = mount.cameraAxis

        /** The camera's right in device coordinates. */
        val right: Vector3 = mount.cameraRight

        /** The camera's up in device coordinates. */
        val up: Vector3 = mount.cameraUp

        /** Device-frame rotation vector (radians) for the parameters. */
        fun rotationVector(p: DoubleArray): Vector3 = right * p[1] + up * p[2]

        /** Corrected world boresights for the parameters `[heading, θ_r, θ_u]` (radians). */
        fun boresights(p: DoubleArray): List<Vector3> {
            val correctedAxis = PointingMath.rotationMatrix(rotationVector(p)) * axis
            val headingDegrees = AstroMath.degrees(p[0])
            return deviceToWorld.map { PointingMath.yawed(it * correctedAxis, headingDegrees) }
        }

        /** Weighted chord residuals (three per point) followed by the two prior terms. */
        fun residualVector(p: DoubleArray): DoubleArray {
            val out = DoubleArray(3 * targets.size + 2)
            var index = 0
            val boresights = boresights(p)
            for (i in targets.indices) {
                val difference = (boresights[i] - targets[i]) / measurementSigma
                out[index++] = difference.x
                out[index++] = difference.y
                out[index++] = difference.z
            }
            out[index++] = p[1] / correctionSigma
            out[index] = p[2] / correctionSigma
            return out
        }

        /** The Gauss–Newton normal matrix `JᵀJ` and gradient `Jᵀr` at the parameters. */
        fun normalEquations(p: DoubleArray, residuals: DoubleArray): NormalEquations {
            val hessian = Array(3) { DoubleArray(3) }
            val gradient = DoubleArray(3)
            val columns = jacobianColumns(p)
            val derivative = DoubleArray(3)
            for (row in residuals.indices) {
                derivative[0] = columns[0][row]
                derivative[1] = columns[1][row]
                derivative[2] = columns[2][row]
                for (a in 0 until 3) {
                    gradient[a] += derivative[a] * residuals[row]
                    for (b in 0 until 3) hessian[a][b] += derivative[a] * derivative[b]
                }
            }
            return NormalEquations(hessian, gradient)
        }

        /** Central-difference Jacobian of `residualVector`, one column per parameter. */
        private fun jacobianColumns(p: DoubleArray): Array<DoubleArray> {
            val h = differenceStep
            return Array(3) { index ->
                val plus = p.copyOf()
                val minus = p.copyOf()
                plus[index] += h
                minus[index] -= h
                val high = residualVector(plus)
                val low = residualVector(minus)
                DoubleArray(high.size) { (high[it] - low[it]) / (2 * h) }
            }
        }

        /**
         * Expected pointing error in degrees away from the points, from the parameter covariance
         * `(JᵀJ)⁻¹` (the prior keeps it finite). A heading error moves the boresight by cos(altitude)
         * times its size and the two device angles move it one-for-one in perpendicular directions,
         * so the mean squared error over the sky and over camera rolls (which average out the
         * cross terms) is `⟨cos²⟩·var(heading) + var(θ_r) + var(θ_u)`.
         */
        fun uncertainty(p: DoubleArray): Double {
            val hessian = normalEquations(p, residualVector(p)).hessian
            fun variance(index: Int): Double? {
                val unit = doubleArrayOf(0.0, 0.0, 0.0)
                unit[index] = 1.0
                return solve(hessian, unit)?.get(index)
            }
            val heading = variance(0)
            val right = variance(1)
            val up = variance(2)
            if (heading == null || right == null || up == null) return maximumUncertainty
            val meanSquare = meanSquaredAltitudeCosine * heading + right + up
            return swiftMin(AstroMath.degrees(sqrt(swiftMax(meanSquare, 0.0))), maximumUncertainty)
        }

        /** The [Fit] for the parameters `[heading, θ_r, θ_u]` (radians). */
        fun fit(p: DoubleArray): Fit {
            val boresights = boresights(p)
            val residuals = targets.indices.map { boresights[it].angle(targets[it]) }
            return Fit(
                headingOffset = AstroMath.normalizeSignedDegrees(AstroMath.degrees(p[0])),
                deviceCorrection = rotationVector(p) * AstroMath.degreesPerRadian,
                residuals = residuals,
                uncertainty = uncertainty(p),
            )
        }
    }
}

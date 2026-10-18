package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*

/** Conversions between CoreMotion-style reference frames. */
object ReferenceFrames {
    /**
     * Largest disagreement about the vertical, in degrees, between the two attitudes that is still
     * accepted as "the same physical orientation".
     */
    private const val maximumTiltMismatch = 10.0

    /**
     * The true azimuth ψ of an `.xArbitraryZVertical` reference frame, found from two attitudes of
     * the same physical orientation: one in the arbitrary frame and one in a frame of known
     * heading (true north by default). This lets the app switch from compass-referenced attitude
     * to gyro-only attitude (immune to magnetic disturbances) without losing the heading.
     *
     * Both frames describe one device orientation, so `M₁ · T(ψ₁) = M₂ · T(ψ₂)`; the yaw is read
     * from `K = M₂ᵀ · M₁ · T(ψ₁) = T(ψ₂)` by averaging its two redundant sine and cosine entries,
     * which tolerates small inconsistencies between the samples.
     *
     * Swift: `referenceAzimuth(ofArbitraryFrameAttitude:knownFrameAttitude:knownFrameAzimuth:)`.
     *
     * @param arbitrary The rotation matrix in the arbitrary frame.
     * @param known The matrix recorded at the same orientation in the frame of known heading.
     * @param knownFrameAzimuth True azimuth of the known frame's x axis (0° for true north, the
     *   declination for magnetic north).
     * @return ψ in degrees, (−180, 180]; null when an input is not finite or the two matrices
     *   disagree about the vertical by more than 10° (they were not taken at the same orientation).
     */
    fun referenceAzimuth(arbitrary: Matrix3, known: Matrix3, knownFrameAzimuth: Double = 0.0): Double? {
        if (!(arbitrary.isFinite && known.isFinite && knownFrameAzimuth.isFinite())) return null
        val k = arbitrary.transposed * known * DeviceAttitude.referenceTransform(knownFrameAzimuth)
        if (!(k[2, 2] >= AstroMath.cosDeg(maximumTiltMismatch))) return null
        val sine = (k[0, 0] + k[1, 1]) / 2
        val cosine = (k[0, 1] - k[1, 0]) / 2
        return AstroMath.normalizeSignedDegrees(AstroMath.atan2Deg(sine, cosine))
    }
}

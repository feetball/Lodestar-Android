package net.kuecker.lodestar.kit.comets

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * The magnitude parameters of a comet, in the form JPL Horizons and the Small-Body Database use.
 *
 * The total magnitude is `m1 = M1 + 5·log10(Δ) + K1·log10(r)` and the nuclear magnitude
 * `m2 = M2 + 5·log10(Δ) + K2·log10(r) + PC·α`, with `r` the Sun distance and `Δ` the Earth distance in AU and `α` the
 * phase angle in degrees. The MPC lists `M1` and a slope `k` with `K1 = 2.5·k` ([fromMPC]).
 * Total magnitudes of comets are rough: the coma makes them uncertain by a magnitude or more.
 *
 * @property totalMagnitude The absolute total magnitude M1 (the magnitude at r = Δ = 1 AU).
 * @property totalSlope The coefficient K1 of `log10(r)` in the total magnitude (10 is the classical default).
 * @property nuclearMagnitude The absolute nuclear magnitude M2, if known.
 * @property nuclearSlope The coefficient K2 of `log10(r)` in the nuclear magnitude; present exactly when M2 is.
 * @property nuclearPhaseCoefficient The phase coefficient PC of the nuclear magnitude in magnitudes per degree
 * (0 when unknown).
 */
@ConsistentCopyVisibility
data class CometMagnitudes private constructor(
    val totalMagnitude: Double,
    val totalSlope: Double,
    val nuclearMagnitude: Double?,
    val nuclearSlope: Double?,
    val nuclearPhaseCoefficient: Double,
) {
    /**
     * The total magnitude at Sun distance [sunDistanceAU] and Earth distance [earthDistanceAU]; `null` if a distance
     * is not positive.
     */
    fun total(sunDistanceAU: Double, earthDistanceAU: Double): Double? {
        if (!(sunDistanceAU > 0 && earthDistanceAU > 0 && sunDistanceAU.isFinite() && earthDistanceAU.isFinite())) return null
        return totalMagnitude + 5 * log10(earthDistanceAU) + totalSlope * log10(sunDistanceAU)
    }

    /**
     * The nuclear magnitude (the brightness of the bare nucleus, not of the coma); `null` without nuclear parameters
     * or for a distance that is not positive.
     */
    fun nuclear(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double? {
        val magnitude = nuclearMagnitude ?: return null
        val slope = nuclearSlope ?: return null
        if (!(sunDistanceAU > 0 && earthDistanceAU > 0 && sunDistanceAU.isFinite() && earthDistanceAU.isFinite())) return null
        return magnitude + 5 * log10(earthDistanceAU) + slope * log10(sunDistanceAU) + nuclearPhaseCoefficient * phaseAngle
    }

    companion object {
        /** The classical default slope K1 of the total magnitude, 10. */
        const val defaultTotalSlope: Double = 10.0

        /**
         * Creates comet magnitudes, or returns `null` if a value is not finite or out of range (M1, M2: −20…40;
         * K1, K2: −5…60; PC: −0.1…0.5). The nuclear parameters are used only if both [nuclearMagnitude] and
         * [nuclearSlope] are given.
         */
        fun create(
            totalMagnitude: Double,
            totalSlope: Double,
            nuclearMagnitude: Double? = null,
            nuclearSlope: Double? = null,
            nuclearPhaseCoefficient: Double = 0.0,
        ): CometMagnitudes? {
            if (!(totalMagnitude >= -20 && totalMagnitude <= 40)) return null
            if (!(totalSlope >= -5 && totalSlope <= 60)) return null
            if (!(nuclearPhaseCoefficient >= -0.1 && nuclearPhaseCoefficient <= 0.5)) return null
            if (nuclearMagnitude == null || nuclearSlope == null) {
                return CometMagnitudes(totalMagnitude, totalSlope, null, null, 0.0)
            }
            if (!(nuclearMagnitude >= -20 && nuclearMagnitude <= 40)) return null
            if (!(nuclearSlope >= -5 && nuclearSlope <= 60)) return null
            return CometMagnitudes(totalMagnitude, totalSlope, nuclearMagnitude, nuclearSlope, nuclearPhaseCoefficient)
        }

        /**
         * Creates the magnitudes from the two numbers of the MPC comet file: the absolute magnitude and the slope
         * parameter `k` (the total magnitude is `H + 5·log10(Δ) + 2.5·k·log10(r)`, so `K1 = 2.5·k`).
         */
        fun fromMPC(absoluteMagnitude: Double, slopeParameter: Double): CometMagnitudes? =
            create(absoluteMagnitude, 2.5 * slopeParameter)
    }
}

/**
 * The H, G magnitude system of the IAU (1985; Bowell et al. 1989, *Asteroids II*) for an asteroid:
 * `V = H + 5·log10(r·Δ) − 2.5·log10((1 − G)·Φ1 + G·Φ2)` with `Φi = exp(−Ai·tan(α/2)^Bi)`,
 * `A1 = 3.33, B1 = 0.63, A2 = 1.87, B2 = 1.22`.
 *
 * @property absoluteMagnitude H, the magnitude at r = Δ = 1 AU and zero phase angle.
 * @property slopeParameter G, the slope parameter (0.15 when unknown).
 */
@ConsistentCopyVisibility
data class AsteroidMagnitudes private constructor(
    val absoluteMagnitude: Double,
    val slopeParameter: Double,
) {
    /**
     * The visual magnitude at Sun distance [sunDistanceAU], Earth distance [earthDistanceAU] and phase angle
     * [phaseAngle] in degrees; `null` if a distance is not positive, the phase angle is not in [0, 180) or the phase
     * function is not positive.
     */
    fun apparent(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double? {
        if (!(sunDistanceAU > 0 && earthDistanceAU > 0 && sunDistanceAU.isFinite() && earthDistanceAU.isFinite())) return null
        if (!(phaseAngle >= 0 && phaseAngle < 180)) return null
        val t = tan(AstroMath.radians(phaseAngle) / 2)
        val phi1 = exp(-3.33 * t.pow(0.63))
        val phi2 = exp(-1.87 * t.pow(1.22))
        val flux = (1 - slopeParameter) * phi1 + slopeParameter * phi2
        if (!(flux > 0)) return null
        return absoluteMagnitude + 5 * log10(sunDistanceAU * earthDistanceAU) - 2.5 * log10(flux)
    }

    companion object {
        /** The slope parameter assumed when a catalog gives none, 0.15. */
        const val defaultSlopeParameter: Double = 0.15

        /** Creates the parameters, or returns `null` if H is not in −5…40 or G not in −0.25…1. */
        fun create(absoluteMagnitude: Double, slopeParameter: Double = defaultSlopeParameter): AsteroidMagnitudes? {
            if (!(absoluteMagnitude >= -5 && absoluteMagnitude <= 40)) return null
            if (!(slopeParameter >= -0.25 && slopeParameter <= 1)) return null
            return AsteroidMagnitudes(absoluteMagnitude, slopeParameter)
        }
    }
}

/** The brightness model of a [SmallBody]: the comet law or the asteroid H, G system. */
sealed interface SmallBodyPhotometry {
    /** The comet total-magnitude law. */
    data class Comet(val magnitudes: CometMagnitudes) : SmallBodyPhotometry

    /** The asteroid H, G system. */
    data class Asteroid(val magnitudes: AsteroidMagnitudes) : SmallBodyPhotometry

    /**
     * The apparent magnitude, which is the total magnitude for a comet and the visual magnitude for an asteroid; `null`
     * if the geometry is outside the range of the model (see [CometMagnitudes.total], [AsteroidMagnitudes.apparent]).
     */
    fun apparentMagnitude(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double? =
        when (this) {
            is Comet -> magnitudes.total(sunDistanceAU, earthDistanceAU)
            is Asteroid -> magnitudes.apparent(sunDistanceAU, earthDistanceAU, phaseAngle)
        }

    /** The nuclear magnitude of a comet that has nuclear parameters; `null` otherwise. */
    fun nuclearMagnitude(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double? =
        when (this) {
            is Comet -> magnitudes.nuclear(sunDistanceAU, earthDistanceAU, phaseAngle)
            is Asteroid -> null
        }
}

package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * Ambient conditions that scale atmospheric refraction.
 *
 * @property pressureMillibars Station pressure in millibars (hPa).
 * @property temperatureCelsius Air temperature in degrees Celsius.
 */
@Serializable
data class Atmosphere(
    @Required val pressureMillibars: Double = 1010.0,
    @Required val temperatureCelsius: Double = 10.0,
) {
    /** Multiplier applied to standard refraction (Meeus 16.5). Implausible inputs fall back to 1. */
    val refractionScale: Double
        get() {
            if (!pressureMillibars.isFinite() || !temperatureCelsius.isFinite() ||
                pressureMillibars !in 300.0..1100.0 || temperatureCelsius !in -60.0..60.0
            ) {
                return 1.0
            }
            return (pressureMillibars / 1010) * (283 / (273 + temperatureCelsius))
        }

    companion object {
        /** 1010 mbar, 10 °C — the conditions the standard refraction formulas assume. */
        val standard: Atmosphere = Atmosphere()
    }
}

/**
 * Atmospheric refraction near the Earth's surface.
 *
 * Below −1° true altitude the refraction is faded linearly to zero at −5° so the true→apparent
 * mapping stays continuous and monotonic (the formulas diverge below the horizon and objects
 * there are not visible anyway).
 */
object Refraction {
    private const val fadeStart: Double = -1.0
    private const val fadeEnd: Double = -5.0

    /** Refraction in degrees to **add** to a true (geometric) altitude (Sæmundsson 1986, Meeus 16.4). */
    fun refractionForTrueAltitude(trueAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double {
        if (!trueAltitude.isFinite()) return 0.0
        if (trueAltitude >= 90) return 0.0
        if (trueAltitude < fadeStart) {
            if (!(trueAltitude > fadeEnd)) return 0.0
            val edge = saemundsson(fadeStart) * atmosphere.refractionScale
            return edge * (trueAltitude - fadeEnd) / (fadeStart - fadeEnd)
        }
        return saemundsson(trueAltitude) * atmosphere.refractionScale
    }

    /**
     * Refraction in degrees to **subtract** from an apparent (observed) altitude
     * (Bennett 1982, Meeus 16.3). Accurate to about 0.07′ above the horizon.
     */
    fun refractionForApparentAltitude(
        apparentAltitude: Double,
        atmosphere: Atmosphere = Atmosphere.standard,
    ): Double {
        if (!apparentAltitude.isFinite() || !(apparentAltitude < 90)) return 0.0
        val h = swiftMax(apparentAltitude, -1.5)
        val arcminutes = 1 / AstroMath.tanDeg(h + 7.31 / (h + 4.4))
        return swiftMax(0.0, arcminutes / 60) * atmosphere.refractionScale
    }

    /** The altitude at which an object at [trueAltitude] is seen. */
    fun apparentAltitude(trueAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double =
        swiftMin(90.0, trueAltitude + refractionForTrueAltitude(trueAltitude, atmosphere))

    /**
     * The geometric altitude of an object seen at [apparentAltitude]; the exact inverse of
     * [apparentAltitude] (solved by Newton iteration).
     */
    fun trueAltitude(apparentAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double {
        if (!apparentAltitude.isFinite()) return apparentAltitude
        var h = apparentAltitude - refractionForApparentAltitude(apparentAltitude, atmosphere)
        for (iteration in 0 until 8) {
            val residual = Refraction.apparentAltitude(h, atmosphere) - apparentAltitude
            if (abs(residual) < 1e-10) break
            val step = 1e-4
            val slope = (Refraction.apparentAltitude(h + step, atmosphere) -
                Refraction.apparentAltitude(h - step, atmosphere)) / (2 * step)
            if (!(slope > 0.1)) break
            h -= residual / slope
        }
        return h
    }

    /** Sæmundsson's formula in degrees for standard conditions. */
    private fun saemundsson(h: Double): Double {
        val arcminutes = 1.02 / AstroMath.tanDeg(h + 10.3 / (h + 5.11))
        return swiftMax(0.0, arcminutes / 60)
    }
}

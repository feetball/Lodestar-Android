package net.kuecker.lodestar.kit.comets

import kotlin.math.max
import kotlin.math.sqrt

/**
 * A date-free estimate of how bright a comet or an asteroid can get, for ranking and rating objects in a catalog.
 *
 * The real brightness depends on the date ([SmallBodyState.apparentMagnitude]); a catalog needs one number per body to
 * order a list, rate its [net.kuecker.lodestar.kit.catalog.Prominence] and decide whether a browse list shows it. The
 * estimate uses the perihelion distance `q` only:
 *
 * - **Asteroid:** at the opposition nearest the perihelion, `H + 5·log10(r·d)` with `r = max(q, minimumSunDistanceAU)`
 *   and `d = max(r − 1, minimumAsteroidEarthDistanceAU)`, at zero phase angle. This is the best an asteroid can do: a
 *   body that is fainter than this at its best opposition is never brighter.
 * - **Comet:** at the perihelion seen from a typical angle, `M1 + 5·log10(√(1 + r²)) + K1·log10(r)` with
 *   `r = max(q, minimumSunDistanceAU)`. Comets are brightest near the Sun, but at a random time they are neither opposite
 *   the Sun nor at the closest approach, so the Earth distance is taken as `√(1 + r²)`, the distance when the angle at the
 *   Earth is a right angle. Comet magnitudes are observers' estimates, uncertain by a magnitude or two.
 *
 * Sun distances below [minimumSunDistanceAU] are not used, since closer to the Sun than that a body cannot stand 25° from
 * it ([SmallBodyEphemeris.defaultMinimumElongation]): the sungrazers of the SOHO coronagraph would otherwise outshine
 * everything. The result is a rating, not a prediction; use [SmallBodyEphemeris.state] for a date.
 */
object SmallBodyBrightness {
    /** The smallest Sun distance used, 0.4226 AU, which is sin 25° (the default minimum elongation). */
    const val minimumSunDistanceAU: Double = 0.4226

    /** The smallest Earth distance used for an asteroid, 0.5 AU, so that rare close approaches do not rate an asteroid. */
    const val minimumAsteroidEarthDistanceAU: Double = 0.5

    /**
     * The best-case magnitude described in the type's documentation; `null` if the body has no magnitude parameters or
     * they give no value.
     */
    fun peakMagnitudeEstimate(body: SmallBody): Double? {
        val photometry = body.photometry ?: return null
        val sunDistance = max(body.orbit.perihelionDistanceAU, minimumSunDistanceAU)
        return when (photometry) {
            is SmallBodyPhotometry.Comet ->
                photometry.magnitudes.total(sunDistance, sqrt(1 + sunDistance * sunDistance))
            is SmallBodyPhotometry.Asteroid ->
                photometry.magnitudes.apparent(sunDistance, max(sunDistance - 1, minimumAsteroidEarthDistanceAU), 0.0)
        }
    }
}

package net.kuecker.lodestar.kit.comets

/**
 * Deterministic orbit generator for the selection tests. The generator is a 64-bit linear congruential sequence
 * (Knuth's MMIX constants) so that the Kotlin and Swift tests see the same bodies.
 */
internal class SyntheticOrbits(seed: Long) {
    private var state: Long = seed

    /** The next number in [0, 1). */
    fun next(): Double {
        state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        return (state ushr 11).toDouble() / 9_007_199_254_740_992.0
    }

    /** The next number in [low, high). */
    fun next(low: Double, high: Double): Double = low + (high - low) * next()

    /** [count] comets and asteroids with perihelion times (or epochs) within three years of 2026 October. */
    fun bodies(count: Int): List<SmallBody> {
        val result = ArrayList<SmallBody>(count)
        var index = 0
        while (result.size < count) {
            index += 1
            val isAsteroid = next() < 0.3
            val inclination = next(0.0, 180.0)
            val argument = next(0.0, 360.0)
            val node = next(0.0, 360.0)
            if (isAsteroid) {
                val axis = next(1.0, 4.5)
                val eccentricity = next(0.0, 0.6)
                val anomaly = next(0.0, 360.0)
                val orbit = OrbitalElements.fromMeanAnomaly(axis, eccentricity, inclination % 40, argument, node, anomaly, 2_461_200.5)
                val magnitudes = AsteroidMagnitudes.create(next(3.0, 15.0), 0.15)
                if (orbit == null || magnitudes == null) continue
                SmallBody.create("S$index", "", SmallBodyKind.ASTEROID, index, orbit, SmallBodyPhotometry.Asteroid(magnitudes))
                    ?.let { result.add(it) }
            } else {
                val shape = next()
                val perihelion = Math.exp(next(Math.log(0.1), Math.log(6.0)))
                val eccentricity = if (shape < 0.55) next(0.3, 0.999) else if (shape < 0.65) 1.0 else next(1.0, 1.02)
                val time = 2_461_300.0 + next(-1_100.0, 1_100.0)
                val orbit = OrbitalElements.create(perihelion, eccentricity, inclination, argument, node, time, 2_461_330.5)
                val magnitudes = CometMagnitudes.create(next(4.0, 16.0), next(5.0, 20.0))
                if (orbit == null || magnitudes == null) continue
                SmallBody.create("C/S$index", "", SmallBodyKind.COMET, null, orbit, SmallBodyPhotometry.Comet(magnitudes))
                    ?.let { result.add(it) }
            }
        }
        return result
    }
}

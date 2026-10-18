package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/** Observers, a synthetic skyline and a sweep generator shared by the Horizon tests. */
internal object HorizonTestSupport {
    /** A reproducible source of random numbers (SplitMix64); the Swift tests use the same sequence. */
    class SeededGenerator(seed: ULong) {
        private var state: ULong = seed

        fun next(): ULong {
            state += 0x9E37_79B9_7F4A_7C15uL
            var z = state
            z = (z xor (z shr 30)) * 0xBF58_476D_1CE4_E5B9uL
            z = (z xor (z shr 27)) * 0x94D0_49BB_1331_11EBuL
            return z xor (z shr 31)
        }

        /** A number in [0, 1) from the top 53 bits. */
        fun nextDouble(): Double = (next() shr 11).toDouble() / 9_007_199_254_740_992.0

        /** A number in [-1, 1). */
        fun nextSigned(): Double = 2 * nextDouble() - 1
    }

    val boulder: Observer = Observer(GeographicLocation(40.0, -105.27, 1650.0))
    val equator: Observer = Observer(GeographicLocation(0.0, 0.0, 0.0))
    val tromso: Observer = Observer(GeographicLocation(69.65, 18.96, 0.0))
    val sydney: Observer = Observer(GeographicLocation(-33.87, 151.21, 0.0))

    /**
     * The skyline the synthetic sweeps follow: low trees (2°) all round, a ridge to the east that peaks at 30°
     * at azimuth 90° and falls away linearly over 40° on either side, and a 12° building from 184° to 196° with
     * sloping sides that reach the trees at 180° and 200°. It is linear between whole degrees, so a bin's median is
     * the skyline at its middle.
     */
    fun truth(azimuth: Double): Double {
        var altitude = 2.0
        val ridge = 1 - abs(AstroMath.normalizeSignedDegrees(azimuth - 90)) / 40
        if (ridge > 0) altitude = maxOf(altitude, 30 * ridge)
        val wrapped = AstroMath.normalizeDegrees(azimuth)
        if (wrapped >= 180 && wrapped <= 200) {
            altitude = maxOf(altitude, 2 + 10 * minOf(1.0, minOf(wrapped - 180, 200 - wrapped) / 4))
        }
        return altitude
    }

    /** [truth] at every whole degree. */
    val truthProfile: HorizonProfile =
        HorizonProfile.create(List(360) { truth(it.toDouble()) }) ?: error("the synthetic skyline is valid")

    /**
     * Feeds [recorder] with a camera that moves at constant speed from azimuth [from] to azimuth [to] (they may
     * exceed 360°) at [rate] degrees per second, sampled at [hertz], starting at time [start], following the
     * synthetic skyline plus [bias] and a uniform noise of ±[noise] (and, with probability [outliers], a +15°
     * error). Samples whose azimuth satisfies [skip] are not sent. Returns the time the sweep ends.
     */
    fun sweep(
        recorder: HorizonRecorder,
        from: Double,
        to: Double,
        rate: Double,
        hertz: Double,
        start: Double = 0.0,
        noise: Double = 0.0,
        outliers: Double = 0.0,
        bias: Double = 0.0,
        random: SeededGenerator? = null,
        skip: (Double) -> Boolean = { false },
    ): Double {
        val duration = abs(to - from) / rate
        val count = floor(duration * hertz).toInt()
        for (index in 0..count) {
            val fraction = if (count == 0) 0.0 else index.toDouble() / count.toDouble()
            val azimuth = from + (to - from) * fraction
            if (skip(azimuth)) continue
            var altitude = truth(azimuth) + bias
            if (random != null) {
                if (noise > 0) altitude += noise * random.nextSigned()
                if (outliers > 0 && random.nextDouble() < outliers) altitude += 15
            }
            recorder.add(azimuth, altitude, start + duration * fraction)
        }
        return start + duration
    }

    /** The largest difference, over the whole degrees in [azimuths], between a profile and the synthetic skyline. */
    fun largestError(profile: HorizonProfile, azimuths: IntRange = 0..359): Double =
        azimuths.maxOf { abs(profile.altitude(it.toDouble()) - truth(it.toDouble())) }

    /** The mean absolute difference over the whole degrees in [azimuths]. */
    fun meanError(profile: HorizonProfile, azimuths: IntRange = 0..359): Double =
        azimuths.sumOf { abs(profile.altitude(it.toDouble()) - truth(it.toDouble())) } / azimuths.count().toDouble()

    /** A skyline of [altitude] degrees between azimuths [from] and [to] (clockwise) and 0° elsewhere. */
    fun wall(altitude: Double, from: Double, to: Double): HorizonProfile =
        HorizonProfile.flat.setting(altitude, AzimuthRange.between(from, to))

    /** An instant [seconds] after 2026-06-20T18:00:00Z. */
    fun instant(seconds: Double): Instant = Instant.parse("2026-06-20T18:00:00Z").addingTimeInterval(seconds)
}

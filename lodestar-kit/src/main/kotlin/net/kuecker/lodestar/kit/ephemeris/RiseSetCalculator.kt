package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * An instant at which a target's altitude passes through a horizon threshold.
 *
 * @property time The instant of the crossing.
 * @property kind Whether the altitude was rising or setting through the threshold.
 */
data class AltitudeCrossing(val time: Instant, val kind: Kind) {
    /** The direction of a crossing. */
    @Serializable
    enum class Kind(val rawValue: String) {
        /** The altitude increases through the threshold. */
        @SerialName("rising")
        RISING("rising"),

        /** The altitude decreases through the threshold. */
        @SerialName("setting")
        SETTING("setting"),
        ;

        companion object {
            /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): Kind? = entries.firstOrNull { it.rawValue == raw }
        }
    }
}

/**
 * An upper culmination: a local maximum of a target's altitude.
 *
 * @property time The instant of maximum altitude.
 * @property altitude Geometric altitude of the target's centre at [time], in degrees.
 */
data class AltitudeCulmination(val time: Instant, val altitude: Double)

/**
 * The first rise, transit and set of a target inside a time window.
 *
 * @property rise The first rising crossing in the window, if any.
 * @property transit The first upper culmination (maximum altitude) strictly inside the window, if any. It is
 * reported whether or not the target is above the horizon then.
 * @property set The first setting crossing in the window, if any.
 * @property transitAltitude Geometric altitude of the centre at [transit], in degrees.
 * @property circumstance Whether the target rises and sets in the window, or stays above or below the horizon
 * throughout.
 */
data class RiseTransitSet(
    val rise: Instant?,
    val transit: Instant?,
    val set: Instant?,
    val transitAltitude: Double?,
    val circumstance: Circumstance,
) {
    /** Whether the target crosses the horizon in the window at all. */
    @Serializable
    enum class Circumstance(val rawValue: String) {
        /** The target crosses the horizon at least once in the window. */
        @SerialName("normal")
        NORMAL("normal"),

        /** The target stays above the horizon throughout the window (circumpolar, midnight Sun). */
        @SerialName("alwaysUp")
        ALWAYS_UP("alwaysUp"),

        /** The target stays below the horizon throughout the window. */
        @SerialName("alwaysDown")
        ALWAYS_DOWN("alwaysDown"),
        ;

        companion object {
            /** The circumstance whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): Circumstance? = entries.firstOrNull { it.rawValue == raw }
        }
    }
}

/**
 * Finds when targets rise, culminate and set.
 *
 * All altitudes are **geometric** (no refraction) and topocentric, referring to the target's
 * centre; the horizon thresholds below absorb refraction and the apparent size of the Sun and
 * Moon. A search samples the altitude curve every 10 minutes at most, brackets sign changes
 * and refines them to better than a second, so targets that rise or set twice, or only graze
 * the horizon, inside the window are found; a window that contains no crossing is reported
 * as always up or always down.
 *
 * Overloads: the Swift searches `crossings(of:…)`, `upperCulminations(of:…)` and `riseTransitSet(of:…)` take an
 * altitude function, a [SolarSystemBody] (`…(for:…)` for the rise/transit/set) or a J2000 position
 * (`…(forJ2000:…)` / `…(ofJ2000:…)`); in Kotlin they share one name per search and differ by the type of the
 * first parameter.
 */
object RiseSetCalculator {
    /** Horizon altitude for stars, planets and deep-sky objects: 34′ of refraction. */
    const val standardHorizonAltitude: Double = -0.5667

    /** Horizon altitude for the Sun's centre: 34′ of refraction plus 16′ of semi-diameter. */
    const val sunHorizonAltitude: Double = -0.8333

    /** The largest interval between altitude samples, in seconds. */
    const val maximumSampleInterval: Double = 600.0

    /** The resolution, in seconds, of the event times the searches return. */
    const val defaultTolerance: Double = 0.25

    // MARK: Generic searches

    /**
     * All instants within [interval] at which [altitude] (degrees) passes through [threshold].
     *
     * @param altitude The altitude of the target in degrees at a given instant. It must be
     * continuous; values that are not finite are skipped.
     * @param sampleInterval Seconds between samples, clamped to 1...[maximumSampleInterval].
     * @param tolerance The resolution of the returned times in seconds (at least a millisecond).
     */
    fun crossings(
        altitude: (Instant) -> Double,
        threshold: Double,
        interval: DateInterval,
        sampleInterval: Double = maximumSampleInterval,
        tolerance: Double = defaultTolerance,
    ): List<AltitudeCrossing> {
        val evaluate = evaluator(altitude)
        val profile = RiseSetProfile(interval, clampedStep(sampleInterval), evaluate)
        return profile.crossings(threshold, clampedTolerance(tolerance), evaluate)
    }

    /** All upper culminations (local maxima of [altitude]) strictly inside [interval]. */
    fun upperCulminations(
        altitude: (Instant) -> Double,
        interval: DateInterval,
        sampleInterval: Double = maximumSampleInterval,
        tolerance: Double = defaultTolerance,
    ): List<AltitudeCulmination> {
        val evaluate = evaluator(altitude)
        val profile = RiseSetProfile(interval, clampedStep(sampleInterval), evaluate)
        return profile.upperCulminations(clampedTolerance(tolerance), evaluate)
    }

    /** The first rise, transit and set of a target whose [altitude] is compared with [threshold]. */
    fun riseTransitSet(altitude: (Instant) -> Double, threshold: Double, interval: DateInterval): RiseTransitSet =
        riseTransitSet(threshold, interval, evaluator(altitude))

    // MARK: Solar-system bodies

    /**
     * The first rise, transit and set of [body] inside [interval], for an observer.
     *
     * The Sun uses [sunHorizonAltitude]; the planets [standardHorizonAltitude]. For the Moon the
     * event is the upper limb touching the horizon after 34′ of refraction, i.e. the centre's
     * topocentric altitude equals [standardHorizonAltitude] minus the Moon's apparent semi-diameter;
     * this agrees to within seconds with Meeus' `0.7275·π − 0.5667°` threshold for the
     * geocentric altitude (which would be about π ≈ 1° too high for a topocentric one).
     */
    fun riseTransitSet(body: SolarSystemBody, observer: Observer, interval: DateInterval): RiseTransitSet =
        riseTransitSet(threshold(body), interval, evaluator(body, observer))

    /** All crossings of [body]'s horizon (see [riseTransitSet]) inside [interval]. */
    fun crossings(body: SolarSystemBody, observer: Observer, interval: DateInterval): List<AltitudeCrossing> {
        val evaluate = evaluator(body, observer)
        val profile = RiseSetProfile(interval, maximumSampleInterval, evaluate)
        return profile.crossings(threshold(body), defaultTolerance, evaluate)
    }

    /** All upper culminations of [body] inside [interval]. */
    fun upperCulminations(
        body: SolarSystemBody,
        observer: Observer,
        interval: DateInterval,
    ): List<AltitudeCulmination> {
        val evaluate = evaluator(body, observer)
        val profile = RiseSetProfile(interval, maximumSampleInterval, evaluate)
        return profile.upperCulminations(defaultTolerance, evaluate)
    }

    // MARK: Fixed positions

    /**
     * The first rise, transit and set of a fixed object inside [interval], using
     * [standardHorizonAltitude].
     *
     * @param position The J2000 catalog place of the object.
     * @param properMotion Its proper motion, if known.
     */
    fun riseTransitSet(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
    ): RiseTransitSet =
        riseTransitSet(standardHorizonAltitude, interval, evaluator(position, properMotion, observer))

    /** All crossings of the standard horizon by a fixed object inside [interval]. */
    fun crossings(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
    ): List<AltitudeCrossing> {
        val evaluate = evaluator(position, properMotion, observer)
        val profile = RiseSetProfile(interval, maximumSampleInterval, evaluate)
        return profile.crossings(standardHorizonAltitude, defaultTolerance, evaluate)
    }

    /** All upper culminations of a fixed object inside [interval]. */
    fun upperCulminations(
        position: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        observer: Observer,
        interval: DateInterval,
    ): List<AltitudeCulmination> {
        val evaluate = evaluator(position, properMotion, observer)
        val profile = RiseSetProfile(interval, maximumSampleInterval, evaluate)
        return profile.upperCulminations(defaultTolerance, evaluate)
    }

    // MARK: Shared machinery

    /** The horizon threshold for the limb-adjusted altitude of [body]. */
    private fun threshold(body: SolarSystemBody): Double =
        if (body == SolarSystemBody.SUN) sunHorizonAltitude else standardHorizonAltitude

    private fun riseTransitSet(
        threshold: Double,
        interval: DateInterval,
        evaluate: RiseSetProfile.Evaluator,
    ): RiseTransitSet {
        val profile = RiseSetProfile(interval, maximumSampleInterval, evaluate)
        val crossings = profile.crossings(threshold, defaultTolerance, evaluate)
        val culmination = profile.upperCulminations(defaultTolerance, evaluate).firstOrNull()
        val circumstance = if (crossings.isNotEmpty()) {
            RiseTransitSet.Circumstance.NORMAL
        } else if (profile.limbAltitudes[0] >= threshold) {
            RiseTransitSet.Circumstance.ALWAYS_UP
        } else {
            RiseTransitSet.Circumstance.ALWAYS_DOWN
        }
        return RiseTransitSet(
            rise = crossings.firstOrNull { it.kind == AltitudeCrossing.Kind.RISING }?.time,
            transit = culmination?.time,
            set = crossings.firstOrNull { it.kind == AltitudeCrossing.Kind.SETTING }?.time,
            transitAltitude = culmination?.altitude,
            circumstance = circumstance,
        )
    }

    /**
     * Adapts a caller's altitude function (`Instant` → degrees) to the profile's evaluator, which works
     * in seconds since the reference date.
     */
    private fun evaluator(altitude: (Instant) -> Double): RiseSetProfile.Evaluator =
        RiseSetProfile.Evaluator { time ->
            RiseSetSample(centreAltitude = altitude(instantFromTimeIntervalSinceReferenceDate(time)))
        }

    /** Altitude of a solar-system body for an observer. */
    internal fun evaluator(body: SolarSystemBody, observer: Observer): RiseSetProfile.Evaluator =
        RiseSetSampler(RiseSetSampler.Target.Body(body), observer)

    /** Altitude of a fixed object (apparent place including aberration) for an observer. */
    private fun evaluator(
        position: EquatorialCoordinate,
        properMotion: ProperMotion?,
        observer: Observer,
    ): RiseSetProfile.Evaluator =
        RiseSetSampler(RiseSetSampler.Target.Fixed(position, properMotion), observer)

    private fun clampedStep(step: Double): Double =
        if (step.isFinite()) AstroMath.clamp(step, 1.0, maximumSampleInterval) else maximumSampleInterval

    private fun clampedTolerance(tolerance: Double): Double =
        if (tolerance.isFinite()) swiftMax(tolerance, 0.001) else defaultTolerance
}

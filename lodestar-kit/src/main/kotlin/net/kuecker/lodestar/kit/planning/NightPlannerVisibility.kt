package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*

// MARK: One target at a time

/**
 * How well a target, described by a position function, can be observed during the dark hours of a night.
 *
 * The function is called with a [SkyFrame] for each sample instant (every 10 minutes at most across
 * the dark intervals) and must return the target's geometric horizontal coordinates (use
 * `refraction = false`). Rise, transit and set use the standard horizon of −0.5667°; for the Sun and
 * the Moon, whose events are those of the upper limb, pass the body to the [SolarSystemBody] overload
 * instead.
 *
 * @param position The target's horizontal coordinates in the given frame.
 * @param summary The night, from [summary].
 * @param darkness Which dark intervals of the night to count.
 * @param minimumAltitude The altitude in degrees from which the target is usable.
 */
fun NightPlanner.visibility(
    position: (SkyFrame) -> HorizontalCoordinate,
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): TargetVisibility =
    visibilityOfPosition(
        position, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude,
    )

/**
 * How well a target, described by a position function, can be observed during [interval], which
 * stands for both the night (for rise, transit and set) and its dark time. See the overload taking
 * a [NightSummary]. The cost grows with the length of the interval; it is meant for spans of up to
 * a few days.
 */
fun NightPlanner.visibility(
    position: (SkyFrame) -> HorizontalCoordinate,
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): TargetVisibility =
    visibilityOfPosition(position, observer, interval, listOf(interval), minimumAltitude)

/**
 * How well the Sun, the Moon or a planet can be observed during the dark hours of a night.
 *
 * Rise, transit and set follow `RiseSetCalculator.riseTransitSet(body, observer, interval)`, so the
 * Moon's events are those of its upper limb, as in [NightSummary.moon].
 *
 * @param body The Sun, the Moon or a planet.
 * @param summary The night, from [summary].
 * @param darkness Which dark intervals of the night to count.
 * @param minimumAltitude The altitude in degrees from which the body is usable.
 */
fun NightPlanner.visibility(
    body: SolarSystemBody,
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): TargetVisibility =
    visibilityOfBody(body, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude)

/**
 * How well the Sun, the Moon or a planet can be observed during [interval], which stands for both
 * the night and its dark time. See the overload taking a [NightSummary].
 */
fun NightPlanner.visibility(
    body: SolarSystemBody,
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): TargetVisibility =
    visibilityOfBody(body, observer, interval, listOf(interval), minimumAltitude)

// MARK: Many fixed targets

/**
 * How well each of many fixed targets can be observed during the dark hours of a night
 * (Swift `visibility(ofFixed:properMotions:during:darkness:minimumAltitude:)`).
 *
 * This is the fast path for ranking a catalog (a thousand objects take a few hundredths of a second):
 * the sky frames for the dark hours are built once and shared by all targets. The targets are J2000
 * catalog places, moved by their proper motion to the middle of the night when one is given, and
 * include annual aberration. A target whose coordinates are not finite never rises. To judge a single
 * object, pass a list of one.
 *
 * Call it as `NightPlanner.visibility(targets, summary = summary)` (or with `properMotions` too): the
 * parameters after the defaulted `properMotions` are passed by name.
 *
 * @param targets The J2000 places of the objects.
 * @param properMotions The proper motion of each target, in the order of [targets]; a missing or `null`
 * entry means the object has no known proper motion.
 * @param summary The night, from [summary].
 * @param darkness Which dark intervals of the night to count.
 * @param minimumAltitude The altitude in degrees from which a target is usable.
 * @return One [TargetVisibility] per target, in the order given.
 */
fun NightPlanner.visibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): List<TargetVisibility> =
    visibilityOfFixed(
        targets, properMotions, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude,
    )

/**
 * How well each of many fixed targets can be observed during [interval], which stands for both
 * the night and its dark time. See the overload taking a [NightSummary].
 */
fun NightPlanner.visibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
): List<TargetVisibility> =
    visibilityOfFixed(targets, properMotions, observer, interval, listOf(interval), minimumAltitude)

// MARK: Implementation

private fun visibilityOfPosition(
    position: (SkyFrame) -> HorizontalCoordinate,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
): TargetVisibility {
    val sampler = DarknessSampler(observer, darkness)
    val profile = sampler.profile { AstroMath.sinDeg(position(it).altitude) }
    val events = RiseSetCalculator.riseTransitSet(
        { position(SkyFrame(it, observer)).altitude },
        RiseSetCalculator.standardHorizonAltitude,
        night,
    )
    return targetVisibility(profile, events, minimumAltitude)
}

private fun visibilityOfBody(
    body: SolarSystemBody,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
): TargetVisibility {
    val sampler = DarknessSampler(observer, darkness)
    return targetVisibility(
        sampler.profile(body),
        RiseSetCalculator.riseTransitSet(body, observer, night),
        minimumAltitude,
    )
}

private fun visibilityOfFixed(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?>,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
): List<TargetVisibility> {
    val sampler = DarknessSampler(observer, darkness)
    val middle = night.start.addingTimeInterval(night.duration / 2)
    val years = SkyFrame(middle, observer).yearsSinceJ2000
    val results = ArrayList<TargetVisibility>(targets.size)
    for (index in targets.indices) {
        val target = targets[index]
        val motion = if (index < properMotions.size) properMotions[index] else null
        val direction = motion?.applyToUnitVector(target, years) ?: target.unitVector
        if (!direction.isFinite) {
            results.add(targetVisibility(DarknessProfile(emptyList()), neverRises, minimumAltitude))
            continue
        }
        val profile = sampler.profile { it.horizontalVector(direction).z }
        val events = RiseSetCalculator.riseTransitSet(target, motion, observer, night)
        results.add(targetVisibility(profile, events, minimumAltitude))
    }
    return results
}

/** The rise, transit and set of a target that has no usable position. */
private val neverRises = RiseTransitSet(
    rise = null,
    transit = null,
    set = null,
    transitAltitude = null,
    circumstance = RiseTransitSet.Circumstance.ALWAYS_DOWN,
)

private fun targetVisibility(
    profile: DarknessProfile,
    events: RiseTransitSet,
    minimumAltitude: Double,
): TargetVisibility {
    val minimum = if (minimumAltitude.isFinite()) {
        AstroMath.clamp(minimumAltitude, -90.0, 90.0)
    } else {
        NightPlanner.defaultMinimumAltitude
    }
    val peak = profile.maximum(profile.darkness)
    return TargetVisibility(
        riseTransitSet = events,
        minimumAltitude = minimum,
        maxAltitudeDuringDarkness = peak?.let { AstroMath.asinDeg(it.sine) },
        bestTime = peak?.date,
        intervalsAboveMinimum = profile.intervals(AstroMath.sinDeg(minimum)),
    )
}

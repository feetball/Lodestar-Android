package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonVisibility

/**
 * How long one target is clear of the skyline during the dark hours of a night (Swift
 * `UnobstructedVisibility`).
 *
 * Clear means above the skyline (the flat horizon when there is none) and at least the minimum altitude high; see
 * [NightPlanner.unobstructedVisibility].
 *
 * @property intervals The stretches of darkness in which the target is clear, in time order. They are the
 * `unobstructedIntervals` that [TargetVisibility] reports for the same target, night, darkness and skyline.
 * @property isBehindSkyline `true` when the target reaches the minimum altitude in the dark but is never above the
 * skyline then. Always `false` without a skyline.
 */
data class UnobstructedVisibility(
    val intervals: List<DateInterval>,
    val isBehindSkyline: Boolean,
) {
    /** The hours of darkness in [intervals]. */
    val hours: Double
        get() = IntervalSet.totalDuration(intervals) / 3600
}

/**
 * How long each of many fixed targets is clear of the skyline during the dark hours of a night
 * (Swift `unobstructedVisibility(ofFixed:properMotions:during:darkness:minimumAltitude:horizon:)`).
 *
 * This is the cheap way to ask "how many hours tonight is it above my skyline?" of a whole catalog: it finds the
 * stretches above the skyline and the minimum altitude and nothing else (no rise, transit and set, no best time),
 * and builds the sky frames of the night once for all targets. On a desktop JVM, warmed up, 11,000 targets take
 * about 0.8 s with a skyline and a few hundredths of a second without, a quarter and a twentieth of what
 * `visibility` with the same arguments costs (3 s and 0.9 s); a phone needs several times that, so run it off the
 * main thread. The result per target is the one
 * `visibility(...).unobstructedIntervals` gives, to within a second.
 *
 * The targets are J2000 catalog places, moved by their proper motion to the middle of the night when one is given,
 * and include annual aberration. A target whose coordinates are not finite is never clear. For the Sun, the Moon,
 * the planets and objects that move on their own, use `visibility` with a body or a position function.
 *
 * Call it as `NightPlanner.unobstructedVisibility(targets, summary = summary, horizon = profile)`.
 *
 * @param targets The J2000 places of the objects.
 * @param properMotions The proper motion of each target, in the order of [targets]; a missing or `null` entry means
 * the object has no known proper motion.
 * @param summary The night, from [summary].
 * @param darkness Which dark intervals of the night to count.
 * @param minimumAltitude The altitude in degrees from which a target is usable; not finite means the default.
 * @param horizon The site's skyline, or `null` for a flat horizon.
 * @return One [UnobstructedVisibility] per target, in the order given.
 */
fun NightPlanner.unobstructedVisibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile? = null,
): List<UnobstructedVisibility> =
    unobstructedOfFixed(
        targets, properMotions, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude, horizon,
    )

/**
 * How long each of many fixed targets is clear of the skyline during [interval], which stands for both the night
 * and its dark time. See the overload taking a [NightSummary].
 */
fun NightPlanner.unobstructedVisibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile? = null,
): List<UnobstructedVisibility> =
    unobstructedOfFixed(targets, properMotions, observer, interval, listOf(interval), minimumAltitude, horizon)

// MARK: Implementation

private fun unobstructedOfFixed(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?>,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
    horizon: HorizonProfile?,
): List<UnobstructedVisibility> {
    val minimum = if (minimumAltitude.isFinite()) {
        AstroMath.clamp(minimumAltitude, -90.0, 90.0)
    } else {
        NightPlanner.defaultMinimumAltitude
    }
    val sampler = DarknessSampler(observer, darkness)
    val middle = night.start.addingTimeInterval(night.duration / 2)
    val years = SkyFrame(middle, observer).yearsSinceJ2000
    val threshold = AstroMath.sinDeg(minimum)

    // The stretches above the minimum altitude over a flat horizon, as `visibility` finds them.
    fun openHorizon(index: Int): List<DateInterval> {
        val target = targets[index]
        val motion = if (index < properMotions.size) properMotions[index] else null
        val direction = motion?.applyToUnitVector(target, years) ?: target.unitVector
        if (!direction.isFinite) return emptyList()
        return sampler.profile { it.horizontalVector(direction).z }.intervals(threshold)
    }

    if (horizon == null) {
        return targets.indices.map { UnobstructedVisibility(openHorizon(it), isBehindSkyline = false) }
    }
    val clear = HorizonVisibility(horizon).visibleWindows(targets, properMotions, observer, darkness, minimum)
    return targets.indices.map { index ->
        val windows = clear[index]
        UnobstructedVisibility(windows, isBehindSkyline = windows.isEmpty() && openHorizon(index).isNotEmpty())
    }
}

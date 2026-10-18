package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.planning.*

// The Tonight planner's visibility calls with a skyline. They answer with the same `TargetVisibility` as the
// calls without `horizon`, so a screen switches by adding the argument:
//
// - `riseTransitSet.rise` / `.set` are when the target first clears the skyline / sinks behind it in the night
//   (`circumstance` ALWAYS_UP: visible all night, ALWAYS_DOWN: hidden all night); `transit` is the culmination.
// - `intervalsAboveMinimum` (and `hoursAboveMinimum`) are the dark stretches in which the target is above the
//   skyline AND at least `minimumAltitude` high.
// - `maxAltitudeDuringDarkness` and `bestTime` are the highest point among those stretches. A target that is
//   never usable has no such stretch; its values then describe its highest point in the dark, hidden or not,
//   so filter on `intervalsAboveMinimum.isEmpty()` (not on the altitude) to drop hidden targets.

/**
 * How well a target, described by a position function, can be observed during the dark hours of a night
 * over [horizon]. See `NightPlanner.visibility` for the parameters; the function must return geometric
 * coordinates (`refraction = false`). The cost is a few hundred position evaluations, each building a [SkyFrame].
 */
fun NightPlanner.visibility(
    position: (SkyFrame) -> HorizontalCoordinate,
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): TargetVisibility =
    visibilityOverHorizon(position, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude, horizon)

/** The position-function overload for a span that stands for both the night and its dark time. */
fun NightPlanner.visibility(
    position: (SkyFrame) -> HorizontalCoordinate,
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): TargetVisibility =
    visibilityOverHorizon(position, observer, interval, listOf(interval), minimumAltitude, horizon)

/**
 * How well the Sun, the Moon or a planet can be observed during the dark hours of a night over [horizon];
 * the Sun and the Moon count by their upper limb.
 */
fun NightPlanner.visibility(
    body: SolarSystemBody,
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): TargetVisibility =
    visibilityOverHorizon(body, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude, horizon)

/** The body overload for a span that stands for both the night and its dark time. */
fun NightPlanner.visibility(
    body: SolarSystemBody,
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): TargetVisibility =
    visibilityOverHorizon(body, observer, interval, listOf(interval), minimumAltitude, horizon)

/**
 * How well each of many fixed targets can be observed during the dark hours of a night over [horizon]: the
 * Tonight list. The frames of the night are built once and shared (a thousand targets take about half
 * a second, about twice the cost without a skyline). The targets are J2000 places with optional proper
 * motions, as in `NightPlanner.visibility` without `horizon`; call it as
 * `NightPlanner.visibility(targets, summary = summary, horizon = profile)`.
 *
 * @return One [TargetVisibility] per target, in the order given.
 */
fun NightPlanner.visibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    summary: NightSummary,
    darkness: DarknessKind = DarknessKind.ASTRONOMICAL,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): List<TargetVisibility> =
    visibilityOverHorizon(
        targets, properMotions, summary.observer, summary.night, summary.darkness(darkness), minimumAltitude, horizon,
    )

/** The batch overload for a span that stands for both the night and its dark time. */
fun NightPlanner.visibility(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?> = emptyList(),
    observer: Observer,
    interval: DateInterval,
    minimumAltitude: Double = NightPlanner.defaultMinimumAltitude,
    horizon: HorizonProfile,
): List<TargetVisibility> =
    visibilityOverHorizon(targets, properMotions, observer, interval, listOf(interval), minimumAltitude, horizon)

// MARK: Implementation

private fun visibilityOverHorizon(
    position: (SkyFrame) -> HorizontalCoordinate,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
    horizon: HorizonProfile,
): TargetVisibility =
    HorizonVisibility(horizon).targetVisibility(
        HorizonFunctionTrack { SkyFrame(it, observer).let(position) }, night, darkness, 0.0, minimumAltitude,
    )

private fun visibilityOverHorizon(
    body: SolarSystemBody,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
    horizon: HorizonProfile,
): TargetVisibility {
    val offset = if (body == SolarSystemBody.SUN) {
        RiseSetCalculator.sunHorizonAltitude - RiseSetCalculator.standardHorizonAltitude
    } else {
        0.0
    }
    return HorizonVisibility(horizon).targetVisibility(
        HorizonBodyTrack(HorizonSkyFrames(observer), body), night, darkness, offset, minimumAltitude,
    )
}

private fun visibilityOverHorizon(
    targets: List<EquatorialCoordinate>,
    properMotions: List<ProperMotion?>,
    observer: Observer,
    night: DateInterval,
    darkness: List<DateInterval>,
    minimumAltitude: Double,
    horizon: HorizonProfile,
): List<TargetVisibility> {
    val visibility = HorizonVisibility(horizon)
    val frames = HorizonSkyFrames(observer)
    return targets.indices.map { index ->
        val target = targets[index]
        val motion = if (index < properMotions.size) properMotions[index] else null
        val track = HorizonFixedTrack(frames, target, motion)
        if (target.unitVector.isFinite) {
            visibility.targetVisibility(track, night, darkness, 0.0, minimumAltitude)
        } else {
            visibility.neverVisible(minimumAltitude)
        }
    }
}

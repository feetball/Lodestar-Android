package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.ceil

/**
 * A [SkyFrame] for every sample instant across a set of dark intervals, shared by all the targets
 * evaluated against those intervals (Swift `NightPlanner.DarknessSampler`).
 *
 * Building a frame costs far more than projecting one target with it, so a list of targets is
 * evaluated by building the frames once and sampling each target at every frame.
 *
 * Prepares the frames for [intervals], which are sorted and merged first.
 */
internal class DarknessSampler(observer: Observer, intervals: List<DateInterval>) {
    /** One dark interval with its sample frames, evenly spaced from the start to the end. */
    class Stretch(val interval: DateInterval, val step: Double, val frames: List<SkyFrame>)

    /** The dark intervals, in time order, with their frames. */
    val stretches: List<Stretch> = IntervalSet.normalized(intervals).map { interval ->
        val wanted = ceil(interval.duration / maximumStep)
        val gapCount = swiftMin(wanted, maximumGapCount.toDouble()).toInt()
        val step = interval.duration / gapCount.toDouble()
        val frames = ArrayList<SkyFrame>(gapCount + 1)
        for (index in 0..gapCount) {
            val date = if (index == gapCount) {
                interval.end
            } else {
                interval.start.addingTimeInterval(index.toDouble() * step)
            }
            frames.add(SkyFrame(date, observer))
        }
        Stretch(interval, step, frames)
    }

    /**
     * Samples a target by the sine of its geometric altitude at each frame. Values that are not
     * finite count as far below the horizon.
     */
    inline fun profile(sine: (SkyFrame) -> Double): DarknessProfile {
        val profiles = ArrayList<DarknessProfile.Stretch>(stretches.size)
        for (stretch in stretches) {
            val frames = stretch.frames
            val sines = DoubleArray(frames.size)
            for (index in sines.indices) {
                val value = sine(frames[index])
                sines[index] = if (value.isFinite()) AstroMath.clamp(value, -1.0, 1.0) else -1.0
            }
            profiles.add(DarknessProfile.Stretch(stretch.interval, AltitudeProfile(stretch.step, sines)))
        }
        return DarknessProfile(profiles)
    }

    /** Samples the Sun, the Moon or a planet by the sine of its geometric topocentric altitude. */
    fun profile(body: SolarSystemBody): DarknessProfile =
        profile { AstroMath.sinDeg(it.horizontal(body, false).altitude) }

    companion object {
        /** The longest allowed gap between samples, in seconds. */
        const val maximumStep: Double = 600.0

        /** The largest number of gaps in one dark interval; longer intervals are sampled more coarsely. */
        const val maximumGapCount: Int = 10_000
    }
}

/**
 * The altitude of one target across a set of dark intervals (Swift `NightPlanner.DarknessProfile`).
 *
 * @property stretches The profile of each dark interval, in time order.
 */
internal class DarknessProfile(val stretches: List<Stretch>) {
    /** The profile of one dark interval. */
    class Stretch(val interval: DateInterval, val altitude: AltitudeProfile)

    /** The highest point of a target (Swift tuple `(date:, sine:)`). */
    class Peak(val date: Instant, val sine: Double)

    /** The dark intervals the profile covers. */
    val darkness: List<DateInterval> get() = stretches.map { it.interval }

    /**
     * The highest point of the target inside [queries], which may be the dark intervals themselves
     * or parts of them; `null` when [queries] overlaps none of them.
     */
    fun maximum(queries: List<DateInterval>): Peak? {
        var best: Peak? = null
        for (stretch in stretches) {
            for (query in queries) {
                val start = maxOf(query.start, stretch.interval.start)
                val end = minOf(query.end, stretch.interval.end)
                if (end < start) continue
                val peak = stretch.altitude.maximum(
                    start.timeIntervalSince(stretch.interval.start),
                    end.timeIntervalSince(stretch.interval.start),
                )
                val current = best
                if (current != null && current.sine >= peak.sine) continue
                best = Peak(stretch.interval.start.addingTimeInterval(peak.offset), peak.sine)
            }
        }
        return best
    }

    /** The parts of the dark intervals where the sine of the altitude is at least [threshold]. */
    fun intervals(threshold: Double): List<DateInterval> {
        val spans = ArrayList<DateInterval>()
        for (stretch in stretches) {
            for (span in stretch.altitude.offsets(threshold)) {
                spans.add(
                    DateInterval(
                        stretch.interval.start.addingTimeInterval(span.start),
                        stretch.interval.start.addingTimeInterval(span.end),
                    ),
                )
            }
        }
        return IntervalSet.normalized(spans)
    }
}

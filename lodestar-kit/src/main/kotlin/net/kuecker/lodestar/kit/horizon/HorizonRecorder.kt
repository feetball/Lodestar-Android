package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.floor

/**
 * Records a [HorizonProfile] while the user sweeps the camera along the skyline.
 *
 * The app feeds one sample for every attitude update: the azimuth and altitude of the camera's optical axis
 * (the boresight, in true-north azimuth), the sensor clock and, if the platform has one, a sensor quality.
 * The camera is held so that the skyline runs through the middle of the picture; the boresight altitude is
 * then the skyline altitude.
 *
 * - **Bins.** Samples are collected per whole degree: bin `n` holds azimuths from `n − 0.5°` to `n + 0.5°`
 *   and becomes the skyline altitude at azimuth `n°`. A bin is the **median** of the (at most
 *   [Configuration.maximumSamplesPerBin]) most recent samples it holds, which removes hand jitter and
 *   single wild readings. Bin 0 follows bin 359, so a sweep may cross north.
 * - **Backtracking.** Order and direction do not matter. A bin that is entered again more than
 *   [Configuration.newPassInterval] seconds after its last sample starts over, so sweeping back over a
 *   stretch replaces what was recorded there (the careful second pass wins); a wobble back and forth within
 *   a few seconds just adds samples to the median.
 * - **Rejected samples.** A sample is not used when a number is not finite ([Rejection.INVALID]), its
 *   quality is below [Configuration.minimumQuality] ([Rejection.LOW_QUALITY]), its altitude is outside
 *   −10°…+60° ([Rejection.OUT_OF_RANGE]) or the camera sweeps faster than [Configuration.maximumSweepRate]
 *   degrees per second since the previous sample ([Rejection.TOO_FAST]; the sensors lag behind a fast
 *   sweep, and a single wild reading in azimuth rejects itself and the sample after it). The speed is that of
 *   the azimuth change, scaled by the cosine of the altitude; altitude jitter, which is what the median is
 *   for, does not count. The first reason that applies, in this order, is reported.
 * - **Gaps.** Bins without a sample are filled from the nearest recorded bins by linear interpolation when
 *   the run of missing bins is at most [Configuration.maximumInterpolatedGap] long (sweeping slightly too
 *   fast). Longer runs are gaps: they are reported, in [gaps] while recording and in [Recording.gaps] at the
 *   end, and get the value the [UnrecordedFill] chooses.
 * - **Partial sweeps.** Recording only part of the circle is fine. What the rest becomes is explicit: with
 *   [UnrecordedFill.KEEP_BASE] (the default) it keeps the altitude of the `base` profile passed to [finish],
 *   or 0° (flat) when there is none; with [UnrecordedFill.INTERPOLATE] it is a straight line between the
 *   recorded ends. Passing the stored profile as `base` is how a new partial sweep is merged into it.
 *
 * Unlike the Swift struct (whose `add` is `mutating`), this is a class that holds the recording: [add],
 * [clear] and [reset] change the object itself, two references share their state, and the class is not thread
 * safe (feed it from one thread). Memory is fixed: 72 KB with the default 25 samples per bin (at most 288 KB).
 * Call [reset] (or make a new recorder) for a new sweep: the sensor clock is expected to keep increasing within
 * one recording.
 *
 * @property configuration The thresholds this recorder applies.
 */
class HorizonRecorder(val configuration: Configuration = Configuration.standard) {
    /**
     * The thresholds of a [HorizonRecorder].
     *
     * Construction (`Configuration(minimumQuality, …)`) replaces a value that is not finite, or not in its range,
     * by its default and clamps the counts; see [standard].
     *
     * @property minimumQuality Samples with a quality below this are rejected; [0, 1], default 0.25. A sample
     * without a quality is accepted.
     * @property maximumSweepRate Samples that follow the previous sample (one that passed the validity, quality
     * and range checks) faster than this many degrees per second are rejected; positive, default 60.
     * @property maximumSamplesPerBin How many recent samples a bin keeps for its median; 1…100, default 25.
     * @property newPassInterval Seconds after which re-entering a bin starts it over; positive, default 3.
     * @property maximumInterpolatedGap The longest run of empty bins (degrees) that is filled by interpolation
     * and is not reported as a gap; 0…30, default 6.
     */
    @ConsistentCopyVisibility
    data class Configuration private constructor(
        val minimumQuality: Double,
        val maximumSweepRate: Double,
        val maximumSamplesPerBin: Int,
        val newPassInterval: Double,
        val maximumInterpolatedGap: Int,
    ) {
        companion object {
            /** The default thresholds. */
            val standard: Configuration = Configuration(0.25, 60.0, 25, 3.0, 6)

            /** Creates a configuration; a value out of range falls back to its default (counts are clamped). */
            operator fun invoke(
                minimumQuality: Double = 0.25,
                maximumSweepRate: Double = 60.0,
                maximumSamplesPerBin: Int = 25,
                newPassInterval: Double = 3.0,
                maximumInterpolatedGap: Int = 6,
            ): Configuration = Configuration(
                if (minimumQuality.isFinite()) AstroMath.clamp(minimumQuality, 0.0, 1.0) else 0.25,
                if (maximumSweepRate.isFinite() && maximumSweepRate > 0) maximumSweepRate else 60.0,
                AstroMath.clamp(maximumSamplesPerBin, 1, 100),
                if (newPassInterval.isFinite() && newPassInterval > 0) newPassInterval else 3.0,
                AstroMath.clamp(maximumInterpolatedGap, 0, 30),
            )
        }
    }

    /** Why [add] did not use a sample. Serialized form: the [rawValue]. */
    enum class Rejection(val rawValue: String) {
        /** The azimuth, altitude or timestamp is not finite. */
        INVALID("invalid"),

        /** The sensor quality is below [Configuration.minimumQuality]. */
        LOW_QUALITY("lowQuality"),

        /** The altitude is outside −10°…+60°, so it cannot be the skyline. */
        OUT_OF_RANGE("outOfRange"),

        /** The camera swept faster than [Configuration.maximumSweepRate]. */
        TOO_FAST("tooFast"),
        ;

        companion object {
            /** The reason whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): Rejection? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /** What happens to the bins that no sample reached and that interpolation does not fill. */
    enum class UnrecordedFill(val rawValue: String) {
        /** They keep the `base` profile's altitude, or 0° without a base. */
        KEEP_BASE("keepBase"),

        /** They follow a straight line between the nearest recorded bins on either side (wrapping through north). */
        INTERPOLATE("interpolate"),
        ;

        companion object {
            /** The policy whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: String): UnrecordedFill? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /**
     * A finished recording.
     *
     * @property profile The skyline: recorded bins, interpolated small gaps, and the unrecorded rest as chosen.
     * @property coverage The fraction of the 360 bins that received at least one sample, [0, 1].
     * @property gaps The unrecorded stretches longer than [Configuration.maximumInterpolatedGap], in azimuth
     * order (the range that starts first after north comes first).
     * @property interpolatedBinCount How many bins were filled by interpolation, small gaps and, with
     * [UnrecordedFill.INTERPOLATE], large ones.
     */
    data class Recording(
        val profile: HorizonProfile,
        val coverage: Double,
        val gaps: List<AzimuthRange>,
        val interpolatedBinCount: Int,
    ) {
        /** `true` when no gap is left: every azimuth was recorded or lies in a small gap. */
        val isComplete: Boolean get() = gaps.isEmpty()
    }

    private val capacity: Int = configuration.maximumSamplesPerBin
    private val samples = DoubleArray(HorizonProfile.binCount * capacity)
    private val counts = IntArray(HorizonProfile.binCount)
    private val heads = IntArray(HorizonProfile.binCount)
    private val lastTimes = DoubleArray(HorizonProfile.binCount)
    private val scratch = DoubleArray(capacity)
    private var recordedCount = 0
    private var acceptedTotal = 0
    private val rejectedTotals = IntArray(Rejection.entries.size)
    private var hasPrevious = false
    private var previousAzimuth = 0.0
    private var previousAltitude = 0.0
    private var previousTime = 0.0
    private var latestRate = Double.NaN

    // MARK: Feeding

    /**
     * Feeds one sample and returns why it was not used, or `null` when it was.
     *
     * @param azimuth Azimuth of the camera axis in degrees from true north through east.
     * @param altitude Altitude of the camera axis in degrees; the skyline is at the middle of the picture.
     * @param timestamp The sensor clock in seconds (it only needs to increase while the user sweeps).
     * @param quality The quality of the heading, 0 (unusable) to 1 (best), if known; `null` or not finite means
     * unknown, which is accepted. Platforms map their accuracy levels onto this range.
     */
    fun add(azimuth: Double, altitude: Double, timestamp: Double, quality: Double? = null): Rejection? {
        if (!(azimuth.isFinite() && altitude.isFinite() && timestamp.isFinite())) return reject(Rejection.INVALID)
        if (quality != null && quality.isFinite() && quality < configuration.minimumQuality) {
            return reject(Rejection.LOW_QUALITY)
        }
        if (altitude < HorizonProfile.lowestAltitude || altitude > HorizonProfile.highestAltitude) {
            return reject(Rejection.OUT_OF_RANGE)
        }
        val wrapped = AstroMath.normalizeDegrees(azimuth)
        var tooFast = false
        if (hasPrevious) {
            val elapsed = timestamp - previousTime
            if (elapsed > 0) {
                val turn = AstroMath.normalizeSignedDegrees(wrapped - previousAzimuth) *
                    AstroMath.cosDeg((altitude + previousAltitude) / 2)
                latestRate = abs(turn) / elapsed
                tooFast = latestRate > configuration.maximumSweepRate
            }
        }
        hasPrevious = true
        previousAzimuth = wrapped
        previousAltitude = altitude
        previousTime = timestamp
        if (tooFast) return reject(Rejection.TOO_FAST)

        val bin = floor(wrapped + 0.5).toInt() % HorizonProfile.binCount
        if (counts[bin] > 0 && timestamp - lastTimes[bin] > configuration.newPassInterval) {
            counts[bin] = 0
            heads[bin] = 0
            recordedCount -= 1
        }
        if (counts[bin] == 0) recordedCount += 1
        val slot = heads[bin]
        samples[bin * capacity + slot] = altitude
        heads[bin] = if (slot + 1 == capacity) 0 else slot + 1
        if (counts[bin] < capacity) counts[bin] += 1
        lastTimes[bin] = timestamp

        acceptedTotal += 1
        return null
    }

    private fun reject(reason: Rejection): Rejection {
        rejectedTotals[reason.ordinal] += 1
        return reason
    }

    // MARK: Progress

    /** How many samples were used. */
    val acceptedCount: Int get() = acceptedTotal

    /** How many samples were rejected for [reason]. */
    fun rejectedCount(reason: Rejection): Int = rejectedTotals[reason.ordinal]

    /**
     * The sweep speed in degrees per second (the change of azimuth times the cosine of the altitude) between the
     * last two samples that passed the validity, quality and range checks, so a [Rejection.TOO_FAST] sample
     * shows its speed; `null` before the second one. Show it to ask the user to slow down before samples are
     * rejected.
     */
    val sweepRate: Double? get() = if (latestRate.isNaN()) null else latestRate

    /**
     * The azimuth of the last sample that passed the validity, quality and range checks, in degrees; `null` before
     * the first.
     */
    val lastAzimuth: Double? get() = if (hasPrevious) previousAzimuth else null

    /**
     * The altitude of the last sample that passed the validity, quality and range checks, in degrees; `null` before
     * the first.
     */
    val lastAltitude: Double? get() = if (hasPrevious) previousAltitude else null

    /** How many of the 360 bins hold at least one sample. */
    val recordedBinCount: Int get() = recordedCount

    /** The fraction of the circle that has been recorded, [0, 1]: the progress of the sweep. */
    val coverage: Double get() = recordedCount.toDouble() / HorizonProfile.binCount.toDouble()

    /** Whether the bin of [azimuth] (any angle, in degrees) holds a sample. */
    fun isRecorded(azimuth: Double): Boolean {
        if (!azimuth.isFinite()) return false
        return counts[binIndex(azimuth)] > 0
    }

    /** For each whole degree 0…359, whether its bin holds a sample; draw the recorded arc from it. */
    val recordedMask: List<Boolean> get() = List(HorizonProfile.binCount) { counts[it] > 0 }

    /**
     * The unrecorded stretches that are longer than [Configuration.maximumInterpolatedGap], in azimuth order:
     * the parts the user still has to sweep. Each range starts at the lower edge of its first bin (e.g.
     * 79.5° for a gap that starts at bin 80). With nothing recorded it is the whole circle.
     */
    val gaps: List<AzimuthRange> get() = describe(unrecordedRuns())

    // MARK: Result

    /**
     * The skyline recorded so far (see [finish]); cheap enough to redraw while the user sweeps.
     *
     * @param fill What the unrecorded bins that are not interpolated become.
     * @param base The profile the unrecorded bins keep with [UnrecordedFill.KEEP_BASE]; flat when `null`.
     */
    fun profile(fill: UnrecordedFill = UnrecordedFill.KEEP_BASE, base: HorizonProfile? = null): HorizonProfile =
        finish(fill, base).profile

    /**
     * Ends the recording: the skyline, how much of the circle was recorded and the gaps that are left.
     * The recorder is not changed; the sweep can continue and be finished again.
     *
     * @param fill What the unrecorded bins that are not interpolated become.
     * @param base The profile the unrecorded bins keep with [UnrecordedFill.KEEP_BASE]; flat when `null`.
     */
    fun finish(fill: UnrecordedFill = UnrecordedFill.KEEP_BASE, base: HorizonProfile? = null): Recording {
        val binCount = HorizonProfile.binCount
        val values = DoubleArray(binCount) { base?.altitudes?.get(it) ?: 0.0 }
        if (recordedCount == 0) return Recording(HorizonProfile.fromClamped(values), 0.0, listOf(AzimuthRange.full), 0)

        val medians = DoubleArray(binCount)
        for (bin in 0 until binCount) {
            if (counts[bin] > 0) {
                medians[bin] = median(bin)
                values[bin] = medians[bin]
            }
        }
        var interpolated = 0
        val runs = unrecordedRuns()
        for (run in runs) {
            val interpolate = run.length <= configuration.maximumInterpolatedGap || fill == UnrecordedFill.INTERPOLATE
            if (!interpolate) continue
            val before = (run.start + binCount - 1) % binCount
            val after = (run.start + run.length) % binCount
            for (offset in 0 until run.length) {
                val fraction = (offset + 1).toDouble() / (run.length + 1).toDouble()
                values[(run.start + offset) % binCount] = medians[before] + (medians[after] - medians[before]) * fraction
            }
            interpolated += run.length
        }
        return Recording(HorizonProfile.fromClamped(values), coverage, describe(runs), interpolated)
    }

    // MARK: Editing the recording

    /**
     * Forgets everything recorded in [range], so that stretch can be swept again. Samples that arrive
     * later are collected from scratch there.
     */
    fun clear(range: AzimuthRange) {
        for (bin in 0 until HorizonProfile.binCount) {
            if (counts[bin] > 0 && range.contains(bin.toDouble())) {
                counts[bin] = 0
                heads[bin] = 0
                recordedCount -= 1
            }
        }
    }

    /** Forgets all samples and counts; the recorder is as new. */
    fun reset() {
        counts.fill(0)
        heads.fill(0)
        recordedCount = 0
        acceptedTotal = 0
        rejectedTotals.fill(0)
        hasPrevious = false
        previousAzimuth = 0.0
        previousAltitude = 0.0
        previousTime = 0.0
        latestRate = Double.NaN
    }

    // MARK: Helpers

    /** A run of unrecorded bins: the first bin and how many follow it (wrapping through bin 359 → 0). */
    private class Run(val start: Int, val length: Int)

    private fun binIndex(azimuth: Double): Int =
        floor(AstroMath.normalizeDegrees(azimuth) + 0.5).toInt() % HorizonProfile.binCount

    /** The median of the samples of a recorded bin. */
    private fun median(bin: Int): Double {
        val count = counts[bin]
        val base = bin * capacity
        for (index in 0 until count) {
            val value = samples[base + index]
            var position = index
            while (position > 0 && scratch[position - 1] > value) {
                scratch[position] = scratch[position - 1]
                position -= 1
            }
            scratch[position] = value
        }
        val middle = count / 2
        return if (count % 2 == 1) scratch[middle] else (scratch[middle - 1] + scratch[middle]) / 2
    }

    /** All runs of unrecorded bins, in bin order starting after the first recorded bin; empty when all are recorded. */
    private fun unrecordedRuns(): List<Run> {
        val binCount = HorizonProfile.binCount
        if (recordedCount == 0) return listOf(Run(0, binCount))
        val first = (0 until binCount).first { counts[it] > 0 }
        val runs = ArrayList<Run>()
        var start = -1
        for (offset in 1..binCount) {
            val bin = (first + offset) % binCount
            if (counts[bin] == 0) {
                if (start < 0) start = bin
            } else if (start >= 0) {
                runs.add(Run(start, (bin - start + binCount) % binCount))
                start = -1
            }
        }
        return runs
    }

    /** The ranges of the runs longer than the interpolation limit, in azimuth order. */
    private fun describe(runs: List<Run>): List<AzimuthRange> {
        if (recordedCount == 0) return listOf(AzimuthRange.full)
        return runs.filter { it.length > configuration.maximumInterpolatedGap }
            .map { AzimuthRange(it.start.toDouble() - 0.5, it.length.toDouble()) }
            .sortedBy { it.start }
    }
}

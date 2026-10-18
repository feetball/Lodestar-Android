package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The altitude of one target sampled at evenly spaced instants, with interpolation between samples
 * (Swift `NightPlanner.AltitudeProfile`).
 *
 * The profile stores the sine of the altitude, the vertical component of the direction to the target.
 * Unlike the altitude itself it stays smooth when a target passes close to the zenith, where the
 * altitude has a sharp peak. Between samples the curve is the cubic through the four nearest samples
 * (fewer for very short profiles). For samples ten minutes apart that is accurate to about 1e-7
 * in the sine, which is below a second for crossings and maxima.
 *
 * Positions along the profile are measured in seconds from its first sample.
 *
 * @property step Seconds between samples.
 * @property sines Sine of the altitude at each sample; at least two values. The array is not copied
 * and must not be modified.
 */
internal class AltitudeProfile(val step: Double, val sines: DoubleArray) {
    /** The highest point of a stretch of the profile (Swift tuple `(offset:, sine:)`). */
    class Peak(val offset: Double, val sine: Double)

    /** A stretch of the profile in seconds from its first sample (Swift tuple `(start:, end:)`). */
    class Span(val start: Double, val end: Double)

    /** A point of the interpolated curve: a fractional sample index and its sine. */
    private class Point(val index: Double, val sine: Double)

    /** The length of the profile in seconds. */
    val duration: Double get() = step * (sines.size - 1).toDouble()

    /**
     * The highest point between two offsets: its position in seconds and its sine.
     *
     * The offsets are clamped to the profile. The maximum may lie on either end.
     */
    fun maximum(startOffset: Double, endOffset: Double): Peak {
        val last = (sines.size - 1).toDouble()
        val lower = AstroMath.clamp(startOffset / step, 0.0, last)
        val upper = AstroMath.clamp(endOffset / step, lower, last)
        var bestIndex = lower
        var bestSine = sine(lower)
        val atUpper = sine(upper)
        if (atUpper > bestSine) {
            bestIndex = upper
            bestSine = atUpper
        }

        val firstSample = ceil(lower).toInt()
        val lastSample = floor(upper).toInt()
        if (firstSample <= lastSample) {
            var peak = firstSample
            for (index in firstSample..lastSample) {
                if (sines[index] > sines[peak]) peak = index
            }
            val refined = refinedMaximum(swiftMax(peak.toDouble() - 1, lower), swiftMin(peak.toDouble() + 1, upper))
            val candidateIndex: Double
            val candidateSine: Double
            if (refined.sine > sines[peak]) {
                candidateIndex = refined.index
                candidateSine = refined.sine
            } else {
                candidateIndex = peak.toDouble()
                candidateSine = sines[peak]
            }
            if (candidateSine > bestSine) {
                bestIndex = candidateIndex
                bestSine = candidateSine
            }
        }
        return Peak(bestIndex * step, bestSine)
    }

    /** The stretches of the profile, as offsets in seconds, where the sine is at least [threshold]. */
    fun offsets(threshold: Double): List<Span> {
        val result = ArrayList<Span>()
        var isOpen = sines[0] >= threshold
        var opened = 0.0
        for (cell in 0 until sines.size - 1) {
            if ((sines[cell] >= threshold) == (sines[cell + 1] >= threshold)) continue
            val crossing = crossingOffset(cell, threshold)
            if (sines[cell + 1] >= threshold) {
                opened = crossing
                isOpen = true
            } else if (isOpen) {
                result.add(Span(opened, crossing))
                isOpen = false
            }
        }
        if (isOpen) result.add(Span(opened, duration))
        return result.filter { it.end > it.start }
    }

    // MARK: Interpolation and search

    /** The interpolated sine at a fractional sample index [position] between 0 and the last sample. */
    fun sine(position: Double): Double {
        val count = minOf(4, sines.size)
        val first = minOf(maxOf(floor(position).toInt() - 1, 0), sines.size - count)
        var total = 0.0
        for (a in 0 until count) {
            var term = sines[first + a]
            for (b in 0 until count) {
                if (b == a) continue
                term *= (position - (first + b).toDouble()) / (a - b).toDouble()
            }
            total += term
        }
        return total
    }

    /**
     * The offset in seconds where the interpolated curve crosses [threshold] between samples [cell]
     * and `cell + 1`, which lie on opposite sides of it (bisection).
     */
    private fun crossingOffset(cell: Int, threshold: Double): Double {
        var lower = cell.toDouble()
        var upper = (cell + 1).toDouble()
        val startsAbove = sines[cell] >= threshold
        repeat(bisectionSteps) {
            val middle = (lower + upper) / 2
            if ((sine(middle) >= threshold) == startsAbove) lower = middle else upper = middle
        }
        return (lower + upper) / 2 * step
    }

    /**
     * Golden-section search for the highest point of the interpolated curve between two sample
     * indices, which must bracket a single peak.
     */
    private fun refinedMaximum(lower: Double, upper: Double): Point {
        val inverseGolden = (sqrt(5.0) - 1) / 2
        var a = lower
        var b = upper
        var c = b - inverseGolden * (b - a)
        var d = a + inverseGolden * (b - a)
        var atC = sine(c)
        var atD = sine(d)
        repeat(goldenSectionSteps) {
            if (!(b - a > 1e-9)) return@repeat
            if (atC > atD) {
                b = d
                d = c
                atD = atC
                c = b - inverseGolden * (b - a)
                atC = sine(c)
            } else {
                a = c
                c = d
                atC = atD
                d = a + inverseGolden * (b - a)
                atD = sine(d)
            }
        }
        return if (atC > atD) Point(c, atC) else Point(d, atD)
    }

    private companion object {
        const val bisectionSteps = 40
        const val goldenSectionSteps = 60
    }
}

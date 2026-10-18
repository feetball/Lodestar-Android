package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.foundation.AstroMath
import kotlin.math.ceil

/**
 * The stretch of an interval around the highest point of an altitude curve in which the altitude stays at or above a
 * threshold: the "best window" of an event.
 *
 * @property start First instant of the window (UT Julian Date).
 * @property end Last instant of the window.
 * @property peakTime The instant of the greatest altitude inside the window.
 * @property peakAltitude The altitude at [peakTime].
 */
internal class AltitudeWindowResult(val start: Double, val end: Double, val peakTime: Double, val peakAltitude: Double)

/** Finds the best window of an altitude curve. */
internal object AltitudeWindow {
    /** Most samples taken in one search. */
    const val maximumSampleCount: Int = 2_000

    private const val edgeTolerance: Double = 1e-6
    private const val peakTolerance: Double = 1e-5

    /**
     * The window around the highest sample of [altitude] on [start]…[end] (UT Julian Dates, at most [step] days between
     * samples) in which the curve stays at or above [threshold]; the edges inside the interval are refined with Brent's
     * method and the peak with a golden-section search. `null` when no sample reaches the threshold.
     */
    fun best(start: Double, end: Double, step: Double, threshold: Double, altitude: (Double) -> Double): AltitudeWindowResult? {
        if (!(start.isFinite() && end.isFinite() && end >= start && step > 0)) return null
        val span = end - start
        val count = minOf(maxOf(1, ceil(span / step).toInt()), maximumSampleCount)
        val times = DoubleArray(count + 1) { if (it == count) end else start + span * it / count }
        val altitudes = DoubleArray(count + 1) { altitude(times[it]) }
        var best = -1
        for (index in 0..count) {
            if (altitudes[index] >= threshold && (best < 0 || altitudes[index] > altitudes[best])) best = index
        }
        if (best < 0) return null
        var left = best
        while (left > 0 && altitudes[left - 1] >= threshold) left -= 1
        var right = best
        while (right < count && altitudes[right + 1] >= threshold) right += 1
        val windowStart = if (left == 0) {
            start
        } else {
            EventSearch.root({ altitude(it) - threshold }, times[left - 1], times[left], edgeTolerance) ?: times[left]
        }
        val windowEnd = if (right == count) {
            end
        } else {
            EventSearch.root({ altitude(it) - threshold }, times[right], times[right + 1], edgeTolerance) ?: times[right]
        }
        var peakTime = times[best]
        var peakAltitude = altitudes[best]
        if (best in 1 until count) {
            val peak = EventSearch.maximum(altitude, times[best - 1], times[best + 1], peakTolerance)
            if (peak.second >= peakAltitude) {
                peakTime = AstroMath.clamp(peak.first, windowStart, windowEnd)
                peakAltitude = peak.second
            }
        }
        return AltitudeWindowResult(windowStart, windowEnd, peakTime, peakAltitude)
    }
}

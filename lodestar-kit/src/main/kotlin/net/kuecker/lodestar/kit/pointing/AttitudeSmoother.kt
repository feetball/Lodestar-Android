package net.kuecker.lodestar.kit.pointing

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.exp

/**
 * A low-pass filter for the phone attitude, for steady arrows and a steady overlay.
 *
 * Each sample is blended into the filtered attitude along the shortest rotation (spherical
 * interpolation) with an exponential time constant. The time constant adapts to the angular speed
 * of the phone, estimated from consecutive samples: at rest it is [timeConstant], so sensor noise
 * is averaged out; while the phone moves it shrinks, so that during steady motion the filtered
 * attitude lags the true one by less than [maximumLag] degrees (a sudden step of a few degrees
 * counts as fast motion and is followed at once). The filter restarts from the raw sample after a
 * large jump, a pause in the samples, a sample that is not later than its predecessor, or a change
 * of reference frame. Samples therefore need increasing timestamps; without them the smoother passes
 * every sample through unchanged.
 *
 * Unlike the Swift struct (whose `update` is `mutating`), this is a class that holds the filter state:
 * [update] and [reset] change the object itself, two references to one smoother share their state, and
 * the class is not thread safe (call it from one thread, or synchronize externally). Create one smoother
 * per attitude stream and keep it for the lifetime of the stream.
 *
 * Non-finite or non-positive arguments fall back to the defaults (0.12 s, 0.5°, 20°, 0.5 s).
 *
 * @param timeConstant Time constant in seconds when the phone is at rest.
 * @param maximumLag Upper bound, in degrees, for the lag during steady motion.
 * @param jumpThreshold A distance between the filtered and the new attitude beyond this (degrees)
 *   restarts the filter.
 * @param maximumTimeGap A pause between samples longer than this (seconds) restarts the filter.
 */
class AttitudeSmoother(
    timeConstant: Double = 0.12,
    maximumLag: Double = 0.5,
    jumpThreshold: Double = 20.0,
    maximumTimeGap: Double = 0.5,
) {
    /** Time constant in seconds when the phone is at rest. */
    val timeConstant: Double = valid(timeConstant, 0.12)

    /** Upper bound, in degrees, for the lag during steady motion. */
    val maximumLag: Double = valid(maximumLag, 0.5)

    /** A distance between the filtered and the new attitude beyond this (degrees) restarts the filter. */
    val jumpThreshold: Double = valid(jumpThreshold, 20.0)

    /** A pause between samples longer than this (seconds) restarts the filter. */
    val maximumTimeGap: Double = valid(maximumTimeGap, 0.5)

    private var filtered: AttitudeQuaternion? = null
    private var previousRaw: AttitudeQuaternion = AttitudeQuaternion(Matrix3.identity)
    private var previousTimestamp: Double = 0.0
    private var previousAzimuth: Double = 0.0

    /**
     * Feeds one sample and returns the filtered attitude, which carries the sample's reference
     * azimuth and timestamp (Swift `update(with:)`).
     */
    fun update(attitude: DeviceAttitude): DeviceAttitude {
        val raw = AttitudeQuaternion(attitude.matrix)
        val last = previousRaw
        val lastTimestamp = previousTimestamp
        val sameFrame = attitude.referenceAzimuth == previousAzimuth
        previousRaw = raw
        previousTimestamp = attitude.timestamp
        previousAzimuth = attitude.referenceAzimuth

        val current = filtered
        if (current == null || !sameFrame) {
            filtered = raw
            return attitude
        }
        val dt = attitude.timestamp - lastTimestamp
        if (!(dt > 0 && dt <= maximumTimeGap && current.angle(raw) <= jumpThreshold)) {
            filtered = raw
            return attitude
        }
        val speed = last.angle(raw) / dt
        val rate = 1 / timeConstant + speed / maximumLag
        val next = current.slerp(raw, 1 - exp(-dt * rate))
        filtered = next
        return DeviceAttitude.fromOrthonormal(next.matrix, attitude.referenceAzimuth, attitude.timestamp)
    }

    /** Forgets the filtered attitude; the next sample is passed through unchanged. */
    fun reset() {
        filtered = null
    }

    private companion object {
        fun valid(value: Double, fallback: Double): Double = if (value.isFinite() && value > 0) value else fallback
    }
}

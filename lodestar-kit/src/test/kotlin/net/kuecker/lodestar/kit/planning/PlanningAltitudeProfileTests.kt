package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private typealias Profile = AltitudeProfile

/**
 * The sampled-altitude machinery against a model whose answers are known in closed form: a fixed
 * target's sin(altitude) is `sin φ sin δ + cos φ cos δ cos H`, with the hour angle H advancing at the
 * sidereal rate.
 */
class PlanningAltitudeProfileTests {
    /** Hour angle advance in degrees per second of solar time. */
    private val rate = 360.985_647_366_29 / 86_400

    private class Model(
        val latitude: Double,
        val declination: Double,
        val hourAngleAtStart: Double,
        val rate: Double,
    ) {
        fun sine(seconds: Double): Double {
            val hourAngle = hourAngleAtStart + rate * seconds
            return AstroMath.sinDeg(latitude) * AstroMath.sinDeg(declination) +
                AstroMath.cosDeg(latitude) * AstroMath.cosDeg(declination) * AstroMath.cosDeg(hourAngle)
        }

        /** Seconds from the start at which the hour angle is [hourAngle] (degrees). */
        fun time(hourAngle: Double): Double = (hourAngle - hourAngleAtStart) / rate
    }

    /** A profile of the model over [duration] seconds, sampled every [step] seconds. */
    private fun profile(model: Model, duration: Double, step: Double = 600.0): Profile {
        val count = (duration / step).roundedHalfAwayFromZero().toInt()
        return Profile(
            duration / count.toDouble(),
            DoubleArray(count + 1) { model.sine(it.toDouble() * duration / count.toDouble()) },
        )
    }

    /** Declination −29° seen from 40° N, from 6 hours before to 6 hours after transit. */
    private val galacticCentreLike: Model get() = Model(40.0, -29.0, -90.0, rate)

    // MARK: Interpolation

    @Test
    fun testInterpolationIsExactAtTheSamples() {
        val model = galacticCentreLike
        val profile = profile(model, 43_200.0)
        for (index in 0..profile.sines.size - 1 step 7) {
            assertEquals(profile.sines[index], profile.sine(index.toDouble()), 1e-15)
        }
    }

    @Test
    fun testInterpolationBetweenSamplesFollowsTheSinusoidToTenMillionths() {
        val model = galacticCentreLike
        val profile = profile(model, 43_200.0)
        var worst = 0.0
        for (tick in 0..1_000) {
            val seconds = 43_200 * tick.toDouble() / 1_000
            worst = maxOf(worst, abs(profile.sine(seconds / profile.step) - model.sine(seconds)))
        }
        assertTrue(worst < 2e-7, "worst error $worst")
    }

    @Test
    fun testShortProfilesInterpolateExactlyWithLowerDegrees() {
        val linear = Profile(10.0, doubleArrayOf(0.2, 0.6))
        assertEquals(0.3, linear.sine(0.25), 1e-15)
        val quadratic = Profile(10.0, doubleArrayOf(1.0, 0.0, 1.0)) // x² − 2x + 1 sampled at 0, 1, 2
        assertEquals(0.25, quadratic.sine(0.5), 1e-15)
        assertEquals(0.25, quadratic.sine(1.5), 1e-15)
        assertEquals(10.0, linear.duration)
        assertEquals(20.0, quadratic.duration)
    }

    // MARK: Maximum

    @Test
    fun testMaximumIsTheCulminationOfTheSinusoid() {
        val model = galacticCentreLike
        val profile = profile(model, 43_200.0)
        val peak = profile.maximum(0.0, profile.duration)
        assertEquals(model.time(0.0), peak.offset, 0.5)
        // The culmination altitude is 90° − |latitude − declination| = 90° − 69°.
        assertEquals(AstroMath.cosDeg(69.0), peak.sine, 1e-7)
        assertEquals(21.0, AstroMath.asinDeg(peak.sine), 1e-5)
    }

    @Test
    fun testMaximumOfAPartOfTheProfileRespectsTheBounds() {
        val model = galacticCentreLike
        val profile = profile(model, 43_200.0)
        val transit = model.time(0.0)
        val before = profile.maximum(1_000.0, transit - 3_000)
        assertEquals(transit - 3_000, before.offset, 1e-6, "rising: the best moment is the end of the range")
        assertEquals(model.sine(transit - 3_000), before.sine, 1e-7)
        val after = profile.maximum(transit + 2_345, 40_000.0)
        assertEquals(transit + 2_345, after.offset, 1e-6, "falling: the best moment is the start of the range")
        val around = profile.maximum(transit - 100, transit + 5_000)
        assertEquals(transit, around.offset, 0.5)
        val point = profile.maximum(5_000.0, 5_000.0)
        assertEquals(5_000.0, point.offset, 1e-9)
        assertEquals(model.sine(5_000.0), point.sine, 1e-7)
    }

    @Test
    fun testMaximumClampsOffsetsOutsideTheProfile() {
        val profile = profile(galacticCentreLike, 43_200.0)
        val whole = profile.maximum(-1e9, 1e9)
        assertEquals(profile.maximum(0.0, profile.duration).offset, whole.offset, 1e-9)
    }

    @Test
    fun testMaximumOfAShortProfileIsItsHigherEnd() {
        val two = Profile(60.0, doubleArrayOf(0.1, 0.4))
        assertEquals(60.0, two.maximum(0.0, 60.0).offset, 1e-9)
        assertEquals(0.4, two.maximum(0.0, 60.0).sine, 1e-12)
    }

    /**
     * A target that passes within a hundredth of a degree of the zenith has a sharp altitude peak that
     * ten-minute samples of the altitude itself could not follow; the sine is smooth.
     */
    @Test
    fun testNearZenithPeakIsReproducedThroughTheSine() {
        val model = Model(40.0, 39.99, -90.0, rate)
        val profile = profile(model, 43_200.0)
        val peak = profile.maximum(0.0, profile.duration)
        assertEquals(89.99, AstroMath.asinDeg(peak.sine), 0.03)
        assertEquals(model.time(0.0), peak.offset, 30.0)
    }

    // MARK: Threshold crossings

    @Test
    fun testStretchesAboveAThresholdMatchTheClosedForm() {
        val model = galacticCentreLike
        val profile = profile(model, 43_200.0)
        for (altitude in listOf(-0.5667, 5.0, 10.0, 15.0, 20.0)) {
            val threshold = AstroMath.sinDeg(altitude)
            val cosine = (threshold - AstroMath.sinDeg(40.0) * AstroMath.sinDeg(-29.0)) /
                (AstroMath.cosDeg(40.0) * AstroMath.cosDeg(-29.0))
            val spans = profile.offsets(threshold)
            val halfWidth = AstroMath.acosDeg(cosine)
            assertEquals(1, spans.size, "altitude $altitude")
            val span = assertNotNull(spans.firstOrNull())
            assertEquals(model.time(-halfWidth), span.start, 0.5, "rise at $altitude")
            assertEquals(model.time(halfWidth), span.end, 0.5, "set at $altitude")
        }
    }

    @Test
    fun testStretchesWhenTheProfileStartsOrEndsAboveTheThreshold() {
        val model = Model(40.0, -29.0, -30.0, rate)
        val profile = profile(model, 20_000.0) // from H = −30° to +50°
        val threshold = AstroMath.sinDeg(15.0)
        val spans = profile.offsets(threshold)
        assertEquals(1, spans.size)
        val span = assertNotNull(spans.firstOrNull())
        assertEquals(0.0, span.start)
        val cosine = (threshold - AstroMath.sinDeg(40.0) * AstroMath.sinDeg(-29.0)) /
            (AstroMath.cosDeg(40.0) * AstroMath.cosDeg(-29.0))
        assertEquals(model.time(AstroMath.acosDeg(cosine)), span.end, 0.5)

        val everywhere = profile.offsets(-1.0)
        assertEquals(1, everywhere.size)
        assertEquals(0.0, everywhere.firstOrNull()?.start)
        assertEquals(profile.duration, everywhere.firstOrNull()?.end ?: Double.NaN, 1e-9)
        assertTrue(profile.offsets(0.99).isEmpty())
    }

    @Test
    fun testTwoSeparateStretchesWhenTheTargetCulminatesTwiceInTheProfile() {
        // A 32-hour profile of a circumpolar target covers two upper culminations (H = 0° and 360°).
        // The target stays between 50° (lower culmination) and 70° (upper culmination) high.
        val model = Model(60.0, 80.0, -60.0, rate)
        val profile = profile(model, 32.0 * 3_600)
        val threshold = AstroMath.sinDeg(65.0)
        val cosine = (threshold - AstroMath.sinDeg(60.0) * AstroMath.sinDeg(80.0)) /
            (AstroMath.cosDeg(60.0) * AstroMath.cosDeg(80.0))
        val halfWidth = AstroMath.acosDeg(cosine)
        val spans = profile.offsets(threshold)
        assertEquals(2, spans.size)
        val first = assertNotNull(spans.firstOrNull())
        val second = assertNotNull(spans.lastOrNull())
        assertEquals(model.time(-halfWidth), first.start, 0.5)
        assertEquals(model.time(halfWidth), first.end, 0.5)
        assertEquals(model.time(360 - halfWidth), second.start, 0.5)
        assertEquals(model.time(360 + halfWidth), second.end, 0.5)

        val always = profile.offsets(AstroMath.sinDeg(49.9))
        assertEquals(1, always.size)
        assertEquals(0.0, always.firstOrNull()?.start)
        assertEquals(profile.duration, always.firstOrNull()?.end ?: Double.NaN, 1e-9)
    }

    @Test
    fun testAThresholdAtASampleValueCountsTheSampleAsAbove() {
        val profile = Profile(60.0, doubleArrayOf(0.0, 0.5, 0.5, 0.0))
        val spans = profile.offsets(0.5)
        assertEquals(1, spans.size)
        assertEquals(60.0, spans.firstOrNull()?.start ?: Double.NaN, 3.0)
        assertEquals(120.0, spans.firstOrNull()?.end ?: Double.NaN, 3.0)
    }
}

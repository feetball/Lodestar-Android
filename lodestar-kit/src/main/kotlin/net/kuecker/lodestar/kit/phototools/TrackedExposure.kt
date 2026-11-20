package net.kuecker.lodestar.kit.phototools

import net.kuecker.lodestar.kit.formatting.NumberText
import net.kuecker.lodestar.kit.foundation.AstroMath
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow

/**
 * Exposure limits for shots that are not simply a fixed camera under a rotating sky: a star tracker or mount whose
 * polar alignment is off, an alt-azimuth mount that cannot cancel the rotation of the field, and the size of an object
 * in pixels.
 *
 * Every function is pure and cheap (a few trigonometric calls). Arguments are limited to their supported ranges
 * instead of rejected, as in [LensSetup]; a value that is not a number counts as the documented fallback.
 * Pixel counts need a pixel pitch; when the setup has none, [ExposureCalculator.defaultPixelPitchMicrometers] is
 * assumed, as for [ExposureCalculator.trailLengthPixels] (check `setup.pixelPitchMicrometers` to tell whether a result is
 * an estimate).
 */
object TrackedExposure {

    /**
     * The trailing, in pixels, that the limits allow by default.
     *
     * 1.5 px sits between the verdicts of the exposure calculator: under 1 px stars stay points, under 2 px they are
     * nearly sharp.
     */
    const val defaultTolerancePixels = 1.5

    /** The supported trailing tolerances, in pixels. */
    val tolerancePixelsRange: ClosedFloatingPointRange<Double> = 0.1..20.0

    /** The polar-alignment error, in degrees, used in place of one that is not a number. */
    const val defaultPolarErrorDegrees = 1.0

    /**
     * The supported polar-alignment errors, in degrees.
     *
     * The lower end, 0.01° (36″), is better than any portable alignment achieves; it keeps the longest exposure finite.
     */
    val polarErrorRange: ClosedFloatingPointRange<Double> = 0.01..90.0

    /**
     * The altitude, in degrees, beyond which the field-rotation rate is evaluated as if the target stood there.
     *
     * The rate grows as 1 / cos(altitude) and is unbounded at the zenith; this guard keeps it finite (about 8600 ″/s at
     * most) while the answer at such altitudes is "far too fast for any exposure" anyway.
     */
    const val maximumFieldRotationAltitude = 89.9

    /**
     * A field-rotation rate below this, in arcseconds per second, counts as no rotation.
     *
     * At an azimuth of exactly 90° or 270° the rate is zero apart from rounding (about 10⁻¹⁵); 10⁻⁶ ″/s would still
     * take a week to turn the corner of a frame by one pixel.
     */
    const val negligibleRateArcsecondsPerSecond = 1e-6

    private val latitudeRange: ClosedFloatingPointRange<Double> = -90.0..90.0

    // MARK: Alt-azimuth field rotation

    /**
     * The rate at which the field turns about the centre of the frame on an alt-azimuth mount, in arcseconds of
     * rotation per second of time.
     *
     * An alt-azimuth mount keeps the target at the centre of the frame but cannot keep the sky's orientation: the
     * field turns at the rate of change of the parallactic angle, `15.041 · cos(latitude) · cos(azimuth) / cos(altitude)`
     * (15.041 ″/s is the sidereal rate, `ExposureCalculator.siderealRateArcsecondsPerSecond`). The rate is zero due east
     * or west and largest due north or south; it is returned as a magnitude, so the azimuth may be measured from
     * north or south. It grows without bound towards the zenith, where an altitude beyond
     * [maximumFieldRotationAltitude] is evaluated at that altitude.
     *
     * @param latitude The observer's latitude in degrees, limited to ±90°.
     * @param azimuth The target's azimuth in degrees; any finite value.
     * @param altitude The target's altitude in degrees, limited to ±[maximumFieldRotationAltitude].
     * @return The magnitude of the rate in arcseconds per second, or 0 when an argument is not a finite number.
     */
    fun fieldRotationRate(latitude: Double, azimuth: Double, altitude: Double): Double {
        if (!latitude.isFinite() || !azimuth.isFinite() || !altitude.isFinite()) return 0.0
        val limitedLatitude = RangeLimiter.limit(latitude, latitudeRange, 0.0)
        val limitedAltitude = RangeLimiter.limit(
            altitude,
            -maximumFieldRotationAltitude..maximumFieldRotationAltitude,
            0.0,
        )
        val rate = ExposureCalculator.siderealRateArcsecondsPerSecond *
            AstroMath.cosDeg(limitedLatitude) * AstroMath.cosDeg(azimuth) / AstroMath.cosDeg(limitedAltitude)
        return abs(rate)
    }

    /**
     * The longest exposure, in seconds, before field rotation moves the corner of the frame by [tolerancePixels].
     *
     * The corner lies `r` pixels from the centre, half the sensor diagonal divided by the pixel pitch. A rotation by
     * `θ` radians moves it by `r · θ`, and the field turns by `rate · t / 206264.806` radians in `t` seconds, so the
     * limit is `t = tolerancePixels · 206264.806 / (r · rate)`. The limit does not depend on the focal length: a longer
     * lens turns the same angle but the corner's distance in pixels stays that of the sensor.
     *
     * @param setup The camera and lens; its sensor size and pixel pitch give `r`.
     * @param tolerancePixels The trailing allowed at the corner, limited to [tolerancePixelsRange]; 1.5 px by default.
     * @param latitude The observer's latitude in degrees.
     * @param azimuth The target's azimuth in degrees.
     * @param altitude The target's altitude in degrees.
     * @return The limit in seconds, or null when there is no rotation to limit (the rate is below
     *   [negligibleRateArcsecondsPerSecond], as due east or west, or an argument is not a finite number).
     */
    fun maxExposureForFieldRotation(
        setup: LensSetup,
        tolerancePixels: Double = defaultTolerancePixels,
        latitude: Double,
        azimuth: Double,
        altitude: Double,
    ): Double? {
        val rate = fieldRotationRate(latitude, azimuth, altitude)
        if (!(rate >= negligibleRateArcsecondsPerSecond)) return null
        val tolerance = RangeLimiter.limit(tolerancePixels, tolerancePixelsRange, defaultTolerancePixels)
        val pitch = setup.pixelPitchMicrometers ?: ExposureCalculator.defaultPixelPitchMicrometers
        val halfDiagonalPixels = setup.sensor.diagonalMillimeters * 1000.0 / 2.0 / pitch
        return tolerance * AstroMath.arcsecondsPerRadian / (halfDiagonalPixels * rate)
    }

    // MARK: Polar-alignment error

    /**
     * The worst-case drift of a tracker whose axis is [polarErrorDegrees] away from the celestial pole, in arcseconds
     * per second of time: `15.041 · sin(error)`.
     *
     * Derivation: the sky turns by the angle `Ω·t` about the true pole `p`, the tracker by the same angle in the
     * opposite sense about its own axis `p′`, which is `error` away from `p`. For a small angle the leftover is a
     * rotation about the vector `p − p′` by `Ω·t`, so a star at the unit vector `s` is displaced by
     * `Ω·t · |(p − p′) × s|`. That is largest when `s` is perpendicular to `p − p′`, that is, for a target 90° from the
     * direction in which the axis is off, and equals `Ω·t · |p − p′|`. The chord `|p − p′|` is `2·sin(error / 2)`,
     * which differs from `sin(error)` by `error² / 8` (0.1 % at 5°). The result holds for exposures short against a
     * sidereal day.
     *
     * This is the worst case. A target nearer the pole than 90° (or in the direction of the error) drifts less, and the
     * declination refinement is not offered because it needs the direction of the error, which is unknown.
     *
     * @param polarErrorDegrees The angle between the tracker's axis and the pole, limited to [polarErrorRange];
     *   its sign is ignored, and a value that is not a number counts as [defaultPolarErrorDegrees].
     * @return The drift in arcseconds per second.
     */
    fun driftRate(polarErrorDegrees: Double): Double {
        val error = RangeLimiter.limit(abs(polarErrorDegrees), polarErrorRange, defaultPolarErrorDegrees)
        return ExposureCalculator.siderealRateArcsecondsPerSecond * AstroMath.sinDeg(error)
    }

    /**
     * The longest exposure, in seconds, before polar-alignment error drifts a star by [tolerancePixels]:
     * `tolerancePixels × image scale ÷ driftRate`, in the worst case (see [driftRate]).
     *
     * @param setup The camera and lens; its image scale converts pixels to arcseconds.
     * @param tolerancePixels The drift allowed, limited to [tolerancePixelsRange]; 1.5 px by default.
     * @param polarErrorDegrees The angle between the tracker's axis and the pole.
     */
    fun maxExposureForPolarError(
        setup: LensSetup,
        tolerancePixels: Double = defaultTolerancePixels,
        polarErrorDegrees: Double,
    ): Double {
        val tolerance = RangeLimiter.limit(tolerancePixels, tolerancePixelsRange, defaultTolerancePixels)
        val pitch = setup.pixelPitchMicrometers ?: ExposureCalculator.defaultPixelPitchMicrometers
        return tolerance * setup.imageScale(pitch) / driftRate(polarErrorDegrees)
    }

    // MARK: Apparent size

    /**
     * The size of an object in pixels of the image: its angular diameter divided by the image scale.
     *
     * Uses the small-angle image scale `pitch / focal length`, accurate to 0.1 % up to a diameter of about 6°; the Moon,
     * the Sun and the planets are far inside that.
     *
     * @param angularDiameterDegrees The object's angular diameter in degrees.
     * @param setup The camera and lens.
     * @param assumingDefaultPixelPitch Whether a setup without a pixel pitch uses
     *   [ExposureCalculator.defaultPixelPitchMicrometers] (the default) or gives null.
     * @return The diameter in pixels, or null when the diameter is not a positive finite number or the pixel pitch is
     *   unknown and the default is not wanted.
     */
    fun pixelSize(
        angularDiameterDegrees: Double,
        setup: LensSetup,
        assumingDefaultPixelPitch: Boolean = true,
    ): Double? {
        if (!(angularDiameterDegrees.isFinite() && angularDiameterDegrees > 0.0)) return null
        val pitch = setup.pixelPitchMicrometers
            ?: if (assumingDefaultPixelPitch) ExposureCalculator.defaultPixelPitchMicrometers else return null
        return angularDiameterDegrees * AstroMath.arcsecondsPerDegree / setup.imageScale(pitch)
    }
}

/**
 * The shutter time suggested by [MoonExposure.suggest]. Created by [MoonExposure.suggest] only.
 */
@ConsistentCopyVisibility
data class MoonExposureSuggestion internal constructor(
    /** The suggested shutter time in seconds, for the aperture and ISO that were asked for. */
    val seconds: Double,
    /** The exposure value at ISO 100 that the Moon needs, after the phase and the extinction. */
    val exposureValue: Double,
    /** The airmass the extinction was worked out for: 1 at the zenith, at most [MoonExposure.maximumAirmass]. */
    val airmass: Double,
    /** A plain-language remark on the phase and the extinction, in English, suitable for a footnote. */
    val note: String,
)

/**
 * Exposure for the Moon by the Looney 11 rule, adjusted for phase and for a low Moon.
 *
 * Looney 11 says that a full Moon needs f/11 at a shutter time of one over the ISO, for example f/11 at 1/100 s at
 * ISO 100 (the rule is credited to NASA's Lunar Photography Guide; see Wikipedia, "Looney 11 rule"). That is an exposure
 * value of `log2(11² × 100)` = 13.56 at ISO 100. Looney 11 covers the full Moon only. The phase adjustment used here is
 * a rule of thumb: one stop more light at a half-lit Moon (f/8 at 1/ISO, EV 12.64) and two stops at a crescent
 * (f/5.6 at 1/ISO, EV 11.61), with a straight line between those three points and the ends held outside 20 % and 100 %
 * lit. Published phase tables differ by a stop or so, so the result is a starting point to check on the histogram.
 *
 * A low Moon is dimmed by the atmosphere: 0.3 stops (EV) per airmass above 1. One magnitude is 0.4 · log2(10) = 1.33
 * stops, so that is 0.23 magnitudes per airmass, the visual extinction of a site about 1 km above sea level (0.28 at sea
 * level, 0.24 at 0.5 km, 0.21 at 1 km and 0.16 at 2 km; asterism.org, "Atmospheric Extinction and Refraction"). The airmass
 * is the plane-parallel `1 / sin(altitude)`, capped at [maximumAirmass] (reached at 11.5°), where the formula and the
 * extinction itself are uncertain.
 */
object MoonExposure {

    /** The ISO used in place of one that is not a number. */
    const val defaultISO = 100.0

    /** The supported ISO speeds. */
    val isoRange: ClosedFloatingPointRange<Double> = 50.0..102_400.0

    /** The stops of light lost to the atmosphere per airmass above 1. */
    const val extinctionStopsPerAirmass = 0.3

    /** The largest airmass the extinction is worked out for. */
    const val maximumAirmass = 5.0

    /** The illuminated fraction of a crescent Moon, the thinnest the phase adjustment distinguishes. */
    private const val crescentFraction = 0.2

    /** The illuminated fraction of a half-lit Moon (first or last quarter). */
    private const val halfFraction = 0.5

    /** The illuminated fraction of a full Moon. */
    private const val fullFraction = 1.0

    /** The aperture, at a shutter time of 1/ISO, of a crescent Moon: two stops wider than f/11 is f/5.6. */
    private const val crescentAperture = 5.6

    /** The aperture, at a shutter time of 1/ISO, of a half-lit Moon: one stop wider than f/11 is f/8. */
    private const val halfAperture = 8.0

    /** The aperture, at a shutter time of 1/ISO, of a full Moon: Looney 11. */
    private const val fullAperture = 11.0

    /**
     * Suggests a shutter time for the Moon.
     *
     * The exposure value at ISO 100 is the phase's (see the type's description) minus [extinctionStopsPerAirmass] per
     * airmass above 1. For the aperture `N` and the ISO `S` the shutter time is `N² · 100 / (S · 2^EV)`.
     *
     * @param illuminatedFraction The lit fraction of the disc, 0 to 1; limited to that range, and a value that is not a
     *   number counts as a full Moon.
     * @param altitude The Moon's altitude in degrees, limited to ±90°. At or below the horizon the largest airmass is
     *   used; a value that is not a number counts as no extinction.
     * @param aperture The f-number, limited to [LensSetup.apertureRange].
     * @param iso The ISO speed, limited to [isoRange]; a value that is not a number counts as [defaultISO].
     */
    fun suggest(
        illuminatedFraction: Double,
        altitude: Double,
        aperture: Double,
        iso: Double,
    ): MoonExposureSuggestion {
        val fraction = RangeLimiter.limit(illuminatedFraction, 0.0..1.0, fullFraction)
        val f = RangeLimiter.limit(aperture, LensSetup.apertureRange, LensSetup.defaultAperture)
        val speed = RangeLimiter.limit(iso, isoRange, defaultISO)

        val fullValue = exposureValue(fullAperture)
        val phaseValue = phaseExposureValue(fraction)
        val airmass = airmass(altitude)
        val extinctionStops = extinctionStopsPerAirmass * (airmass - 1.0)
        val value = phaseValue - extinctionStops
        val seconds = f * f * 100.0 / (speed * 2.0.pow(value))

        val remarks = ArrayList<String>()
        val phaseStops = fullValue - phaseValue
        if (phaseStops >= 0.05) {
            remarks.add(
                "A ${NumberText.fixedDescription(fraction * 100.0, 0)}% lit Moon needs about " +
                    "${NumberText.fixedDescription(phaseStops, 1)} stops more light than a full Moon.",
            )
        }
        if (altitude.isFinite() && altitude <= 0.0) {
            remarks.add("The Moon is at or below the horizon: the extinction is the largest the rule counts.")
        } else if (airmass >= 1.05) {
            remarks.add(
                "Low Moon (${NumberText.fixedDescription(airmass, 1)} airmasses): " +
                    "${NumberText.fixedDescription(extinctionStops, 1)} stops more light for the extinction.",
            )
        }
        val note = if (remarks.isEmpty()) {
            "Full Moon high in the sky: the Looney 11 rule as it stands, f/11 at 1/ISO."
        } else {
            remarks.joinToString(" ")
        }
        return MoonExposureSuggestion(seconds = seconds, exposureValue = value, airmass = airmass, note = note)
    }

    /** The exposure value at ISO 100 of a shutter time of 1/ISO at the given f-number: `log2(N² × 100)`. */
    private fun exposureValue(aperture: Double): Double = log2(aperture * aperture * 100.0)

    /** The exposure value at ISO 100 for a Moon with the given illuminated fraction (0 to 1). */
    private fun phaseExposureValue(fraction: Double): Double {
        val lit = AstroMath.clamp(fraction, crescentFraction, fullFraction)
        val crescent = exposureValue(crescentAperture)
        val half = exposureValue(halfAperture)
        val full = exposureValue(fullAperture)
        return if (lit <= halfFraction) {
            crescent + (half - crescent) * (lit - crescentFraction) / (halfFraction - crescentFraction)
        } else {
            half + (full - half) * (lit - halfFraction) / (fullFraction - halfFraction)
        }
    }

    /** The plane-parallel airmass `1 / sin(altitude)`, at most [maximumAirmass]; 1 when the altitude is not a number. */
    private fun airmass(altitude: Double): Double {
        if (!altitude.isFinite()) return 1.0
        val sine = AstroMath.sinDeg(RangeLimiter.limit(altitude, -90.0..90.0, 90.0))
        return if (sine > 0.0) min(1.0 / sine, maximumAirmass) else maximumAirmass
    }
}

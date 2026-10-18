package net.kuecker.lodestar.kit.phototools

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.NumberText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** A rule of thumb for the longest exposure that keeps stars looking like points. */
@Serializable
enum class ExposureRule(
    /** The stable string used in JSON and as [id]. */
    val rawValue: String,
) {
    /** 500 ÷ full-frame-equivalent focal length. */
    @SerialName("rule500")
    RULE500("rule500"),

    /** 400 ÷ full-frame-equivalent focal length. */
    @SerialName("rule400")
    RULE400("rule400"),

    /** 300 ÷ full-frame-equivalent focal length. */
    @SerialName("rule300")
    RULE300("rule300"),

    /** Frédéric Michaud's NPF rule: (35 × aperture + 30 × pixel pitch) ÷ focal length. */
    @SerialName("npf")
    NPF("npf");

    /** A stable identifier, equal to the raw value. */
    val id: String
        get() = rawValue

    /** A short name for pickers, for example "500 rule". */
    val displayName: String
        get() = when (this) {
            RULE500 -> "500 rule"
            RULE400 -> "400 rule"
            RULE300 -> "300 rule"
            NPF -> "NPF rule"
        }

    /** A one-sentence description of how the rule works and when to prefer it. */
    val summary: String
        get() = when (this) {
            RULE500 ->
                "500 divided by the full-frame-equivalent focal length. Simple, but too lenient for dense sensors."
            RULE400 ->
                "A stricter variant: 400 divided by the full-frame-equivalent focal length."
            RULE300 ->
                "The strictest variant: 300 divided by the full-frame-equivalent focal length."
            NPF ->
                "Michaud's rule uses aperture, pixel pitch and focal length, and suits high-resolution sensors best."
        }

    companion object {
        /** The rule with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): ExposureRule? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The numerator of the 500/400/300 family; null for NPF. */
private val ExposureRule.equivalentFocalLengthNumerator: Double?
    get() = when (this) {
        ExposureRule.RULE500 -> 500.0
        ExposureRule.RULE400 -> 400.0
        ExposureRule.RULE300 -> 300.0
        ExposureRule.NPF -> null
    }

/**
 * The result of an exposure calculation. Created by [ExposureCalculator.maxExposure] only.
 */
@ConsistentCopyVisibility
data class ExposureEstimate internal constructor(
    /** The longest suggested exposure, in seconds. A guideline, not a guarantee. */
    val seconds: Double,
    /** The rule that produced the estimate. */
    val rule: ExposureRule,
    /**
     * Whether the NPF rule had to assume [ExposureCalculator.defaultPixelPitchMicrometers]
     * because the setup has no pixel pitch. Always false for the other rules.
     */
    val usedDefaultPixelPitch: Boolean,
    /**
     * The factor applied for the target's declination: 1 when none was given or it is at the
     * celestial equator, up to [ExposureCalculator.maximumDeclinationFactor] near the poles.
     */
    val declinationFactor: Double,
    /** Plain-language remarks on assumptions and adjustments, in English, suitable for a footnote. */
    val notes: List<String>,
)

/**
 * Computes the longest exposure that avoids visible star trails, and the length of the trails
 * an exposure produces.
 *
 * Stars move across the sky at the sidereal rate, which is fastest at the celestial equator and
 * slows by cos(declination) towards the poles. Without a declination every estimate assumes the
 * equator, the worst case.
 */
object ExposureCalculator {

    /**
     * The pixel pitch, in micrometres, that NPF assumes when the setup has none.
     *
     * 4.0 µm is typical of a 45-megapixel full-frame or a 24 to 26-megapixel APS-C camera. Cameras
     * with larger pixels can expose longer, so the assumption errs towards shorter exposures.
     */
    const val defaultPixelPitchMicrometers = 4.0

    /**
     * The largest factor by which a declination may lengthen an exposure.
     *
     * 1 / cos(declination) grows without bound towards the pole. The factor is capped at 5, which
     * is reached at about 78.5° of declination, because a wide frame also contains stars farther
     * from the pole than the target, and those trail more.
     */
    const val maximumDeclinationFactor = 5.0

    /**
     * The rate at which stars at the celestial equator cross the sky, in arcseconds per second
     * of time: 360° per sidereal day.
     */
    const val siderealRateArcsecondsPerSecond = 15.041

    /** NPF's weight, in seconds × millimetres per f-number, for the aperture term (diffraction). */
    private const val npfApertureCoefficient = 35.0

    /** NPF's weight, in seconds × millimetres per micrometre, for the pixel pitch term. */
    private const val npfPixelPitchCoefficient = 30.0

    /**
     * Estimates the longest exposure before stars visibly trail.
     *
     * The 500, 400 and 300 rules divide their constant by the full-frame-equivalent focal length
     * (focal length × crop factor). NPF uses the real focal length together with aperture and
     * pixel pitch: `(35 × N + 30 × p) / f`, with `N` the f-number, `p` the pixel pitch in µm and
     * `f` the focal length in mm. When the setup has no pixel pitch NPF assumes
     * [defaultPixelPitchMicrometers] and sets [ExposureEstimate.usedDefaultPixelPitch].
     *
     * A declination multiplies the result by 1 / cos(declination), capped at
     * [maximumDeclinationFactor], because stars near the celestial pole trail less. A declination
     * outside ±90° or not finite is ignored and noted.
     *
     * @param rule The rule to apply.
     * @param setup The camera and lens.
     * @param declination The declination of the target in degrees, or null to assume the celestial equator.
     */
    fun maxExposure(
        rule: ExposureRule,
        setup: LensSetup,
        declination: Double? = null,
    ): ExposureEstimate {
        val notes = ArrayList<String>()
        var usedDefaultPixelPitch = false

        val numerator = rule.equivalentFocalLengthNumerator
        val baseSeconds: Double
        if (numerator != null) {
            baseSeconds = numerator / setup.equivalentFocalLengthMillimeters
        } else {
            val pitch: Double
            val known = setup.pixelPitchMicrometers
            if (known != null) {
                pitch = known
            } else {
                pitch = defaultPixelPitchMicrometers
                usedDefaultPixelPitch = true
                notes.add(
                    "Pixel pitch unknown: assumed ${NumberText.swiftDescription(pitch)} \u00B5m. " +
                        "Enter the camera's resolution for an exact NPF result.",
                )
            }
            baseSeconds = (npfApertureCoefficient * setup.aperture + npfPixelPitchCoefficient * pitch) /
                setup.focalLengthMillimeters
        }

        val factor = declinationFactor(declination, notes)
        return ExposureEstimate(
            seconds = baseSeconds * factor,
            rule = rule,
            usedDefaultPixelPitch = usedDefaultPixelPitch,
            declinationFactor = factor,
            notes = notes.toList(),
        )
    }

    /**
     * The length of the trail a star draws during an exposure, in arcseconds.
     *
     * An exposure that is negative or not finite counts as zero, and a declination that is null,
     * not finite or outside ±90° counts as the celestial equator, where trails are longest.
     *
     * @param exposureSeconds The exposure time in seconds.
     * @param declination The star's declination in degrees, or null to assume the celestial equator.
     */
    fun trailLengthArcseconds(exposureSeconds: Double, declination: Double? = null): Double {
        val seconds = if (exposureSeconds.isFinite()) max(exposureSeconds, 0.0) else 0.0
        val degrees = declination?.takeIf { it >= -90.0 && it <= 90.0 } ?: 0.0
        return siderealRateArcsecondsPerSecond * seconds * cos(degrees * PI / 180.0)
    }

    /**
     * The length of the trail a star draws during an exposure, in pixels of the image.
     *
     * Divides [trailLengthArcseconds] by the setup's image scale.
     * When the setup has no pixel pitch, [defaultPixelPitchMicrometers] is assumed, as for NPF;
     * check `setup.pixelPitchMicrometers` to tell whether the result is an estimate.
     *
     * @param exposureSeconds The exposure time in seconds.
     * @param declination The star's declination in degrees, or null to assume the celestial equator.
     * @param setup The camera and lens.
     */
    fun trailLengthPixels(
        exposureSeconds: Double,
        declination: Double? = null,
        setup: LensSetup,
    ): Double {
        val pitch = setup.pixelPitchMicrometers ?: defaultPixelPitchMicrometers
        val trail = trailLengthArcseconds(exposureSeconds, declination)
        return trail / setup.imageScale(pitch)
    }

    // MARK: Declination

    /**
     * The exposure multiplier for a target declination, appending an explanatory note when it
     * changes the result. Returns 1 for null and for invalid declinations.
     */
    private fun declinationFactor(declination: Double?, notes: MutableList<String>): Double {
        if (declination == null) return 1.0
        if (!(declination >= -90.0 && declination <= 90.0)) {
            notes.add("Declination ignored: it must be between \u221290\u00B0 and +90\u00B0.")
            return 1.0
        }

        val uncapped = 1.0 / cos(declination * PI / 180.0)
        val factor = min(uncapped, maximumDeclinationFactor)
        // At the equator (and within rounding of it) there is nothing to explain.
        if (!(factor >= 1.05)) return factor

        val signed = AngleFormatter.formatSignedDegrees(declination, 1)
        val applied = NumberText.fixedDescription(factor, 1)
        if (uncapped > maximumDeclinationFactor) {
            notes.add(
                "Declination $signed lengthens the limit \u00D7$applied, the maximum, " +
                    "because a wide frame also contains stars farther from the pole.",
            )
        } else {
            notes.add(
                "Declination $signed lengthens the limit \u00D7$applied " +
                    "because stars near the celestial pole move more slowly.",
            )
        }
        return factor
    }
}

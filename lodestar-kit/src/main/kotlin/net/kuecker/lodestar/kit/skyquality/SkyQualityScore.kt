package net.kuecker.lodestar.kit.skyquality

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.planning.NightSummary

/** What a part of the imaging score looks at. */
enum class ImagingScoreComponentKind(
    /** The Swift case name. */
    val rawValue: String,
) {
    /** The hours of astronomical darkness. */
    DARKNESS_HOURS("darknessHours"),

    /** How much of that darkness the Moon spoils. */
    MOON("moon"),

    /** The brightness of the sky at the site (Bortle class). */
    SKY_BRIGHTNESS("skyBrightness"),

    /** The forecast cloud cover; only when a forecast is known. */
    CLOUDS("clouds"),
    ;

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ImagingScoreComponentKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A word for an imaging score.
 *
 * @property rawValue The Swift case name.
 */
enum class ImagingRating(val rawValue: String) {
    /** Below 3. */
    POOR("poor"),

    /** 3 to below 5.5. */
    FAIR("fair"),

    /** 5.5 to below 8. */
    GOOD("good"),

    /** 8 or more. */
    EXCELLENT("excellent"),
    ;

    companion object {
        /** The rating of a score from 0 to 10. */
        operator fun invoke(score: Double): ImagingRating = when {
            score >= 8.0 -> EXCELLENT
            score >= 5.5 -> GOOD
            score >= 3.0 -> FAIR
            else -> POOR
        }

        /** The rating whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ImagingRating? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One part of an [ImagingScore].
 *
 * @property kind What the part looks at.
 * @property quality How good that is, 0 (useless) to 1 (ideal).
 * @property weight The points this part can give at most; the weights of the parts add up to 10.
 * @property points What it gave: `weight * quality`. The points of the parts add up to the score.
 */
@ConsistentCopyVisibility
data class ImagingScoreComponent internal constructor(
    val kind: ImagingScoreComponentKind,
    val quality: Double,
    val weight: Double,
    val points: Double,
)

/**
 * The score of a night for deep-sky imaging at one site, 0 to 10, with the parts it is made of.
 *
 * @property total The score, 0 (nothing to shoot) to 10 (new moon, a Bortle 1 sky, long dark hours, clear).
 * @property components The parts, in this order: darkness hours, Moon, sky brightness, and clouds when a forecast is
 * known. Their [ImagingScoreComponent.points] add up to [total].
 */
@ConsistentCopyVisibility
data class ImagingScore internal constructor(
    val total: Double,
    val components: List<ImagingScoreComponent>,
) {
    /** `true` when the score includes the cloud forecast. */
    val includesClouds: Boolean get() = components.any { it.kind == ImagingScoreComponentKind.CLOUDS }

    /** A word for [total]. */
    val rating: ImagingRating get() = ImagingRating(total)

    /** The part of the given [kind], or `null` when it is not in the score. */
    fun component(kind: ImagingScoreComponentKind): ImagingScoreComponent? = components.firstOrNull { it.kind == kind }
}

/**
 * The imaging score of a night: how good it is for photographing faint objects, from the dark hours, the Moon, the
 * sky brightness of the site and, when known, the clouds.
 *
 * **Parts and weights** (points out of 10, each part gives `weight * quality`):
 *
 * | Part | Weight | Quality (0 to 1) |
 * |---|---|---|
 * | Darkness hours | 2 | hours of astronomical darkness / 6, at most 1 (six hours is a good night's work); 0 with no astronomical darkness |
 * | Moon | 3 | 1 - (share of the darkness with the Moon up) x (illuminated fraction of the Moon); 0 with no astronomical darkness |
 * | Sky brightness | 2 | (9 - Bortle class) / 8: class 1 gives 1, class 9 gives 0 |
 * | Clouds | 3 | 1 - cloud fraction; left out when no forecast is known |
 *
 * Without a cloud forecast the three other weights are scaled up to 10 (2.86, 4.29, 2.86) so that the total still runs from
 * 0 to 10 and the points of the parts still add up to it. The share of the darkness with the Moon up is
 * `(astronomicalDarkHours - moonFreeDarkHours) / astronomicalDarkHours`. A thin crescent that is up all night costs
 * little; a full Moon that is up for an hour of a long night costs little too. Cost: a few arithmetic operations.
 */
object SkyQualityScore {
    /** The hours of astronomical darkness that earn the full darkness quality. */
    const val fullDarknessHours: Double = 6.0

    private const val darknessWeight = 2.0
    private const val moonWeight = 3.0
    private const val skyWeight = 2.0
    private const val cloudWeight = 3.0

    /**
     * The imaging score of the night [night] at a site of sky quality [bortle].
     *
     * @param cloudFraction The forecast cloud cover of the night, 0 (clear) to 1 (overcast), or `null` when no fresh
     * forecast is known. A value outside 0-1 is limited to it; one that is not a number counts as unknown.
     */
    fun imaging(night: NightSummary, bortle: BortleEstimate, cloudFraction: Double?): ImagingScore = imaging(
        astronomicalDarkHours = night.astronomicalDarkHours,
        moonFreeDarkHours = night.moonFreeDarkHours,
        moonIlluminatedFraction = night.moon.illuminatedFraction,
        bortle = bortle,
        cloudFraction = cloudFraction,
    )

    /**
     * The imaging score from its inputs: [astronomicalDarkHours] and [moonFreeDarkHours] as in [NightSummary] (the
     * second is at most the first), the Moon's [moonIlluminatedFraction] (0 to 1), the site's [bortle] and the forecast
     * [cloudFraction] or `null`. Hours and fractions that are negative or not numbers count as 0.
     */
    fun imaging(
        astronomicalDarkHours: Double,
        moonFreeDarkHours: Double,
        moonIlluminatedFraction: Double,
        bortle: BortleEstimate,
        cloudFraction: Double?,
    ): ImagingScore {
        val dark = nonNegative(astronomicalDarkHours)
        val moonFree = AstroMath.clamp(nonNegative(moonFreeDarkHours), 0.0, dark)
        val lit = AstroMath.clamp(nonNegative(moonIlluminatedFraction), 0.0, 1.0)
        val darknessQuality = AstroMath.clamp(dark / fullDarknessHours, 0.0, 1.0)
        val moonQuality = if (dark > 0.0) 1.0 - (dark - moonFree) / dark * lit else 0.0
        val skyQuality = (9.0 - AstroMath.clamp(bortle.bortleClass, 1, 9).toDouble()) / 8.0
        val clouds = if (cloudFraction != null && cloudFraction.isFinite()) AstroMath.clamp(cloudFraction, 0.0, 1.0) else null

        val parts = ArrayList<Triple<ImagingScoreComponentKind, Double, Double>>()
        parts.add(Triple(ImagingScoreComponentKind.DARKNESS_HOURS, darknessWeight, darknessQuality))
        parts.add(Triple(ImagingScoreComponentKind.MOON, moonWeight, moonQuality))
        parts.add(Triple(ImagingScoreComponentKind.SKY_BRIGHTNESS, skyWeight, skyQuality))
        if (clouds != null) parts.add(Triple(ImagingScoreComponentKind.CLOUDS, cloudWeight, 1.0 - clouds))
        val weightSum = parts.sumOf { it.second }
        val components = parts.map { (kind, weight, quality) ->
            val scaled = weight * 10.0 / weightSum
            ImagingScoreComponent(kind, quality, scaled, scaled * quality)
        }
        return ImagingScore(components.sumOf { it.points }, components)
    }

    private fun nonNegative(value: Double): Double = if (value.isFinite() && value > 0.0) value else 0.0
}

package net.kuecker.lodestar.features.horizon

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.CompassPoint
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.model.HorizonSource
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.shared.AppFormat
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The words and numbers the horizon screens show. Pure functions, so they can be tested. */
internal object HorizonFormat {
    const val OPEN_HORIZON = "Open horizon"

    /** Bins on a profile are one degree wide: this many at the highest altitude, spread round, mean "all round". */
    private const val SPREAD_RESULTANT = 0.2

    /** A highest point counts as reached by every bin within this many degrees of the maximum. */
    private const val HIGHEST_TOLERANCE = 0.05

    /** Gaps are listed up to this many before "and N more". */
    private const val LISTED_GAPS = 3

    /**
     * "Open horizon" without a skyline, otherwise "Recorded · 86% covered · highest 14° in the east". The coverage is
     * left out when it is not known (the share of the circle the sweep reached is not part of the stored skyline).
     */
    fun status(profile: HorizonProfile?, coverage: Double?): String {
        if (profile == null || profile.isFlat) return OPEN_HORIZON
        val parts = ArrayList<String>(3)
        parts.add("Recorded")
        if (coverage != null) parts.add("${AppFormat.percent(coverage)} covered")
        parts.add(highest(profile))
        return parts.joinToString(" · ")
    }

    /**
     * What applies at a site, for the screens that show one line about it: "Open horizon", "Recorded · 86% covered ·
     * highest 14° in the east", "Terrain horizon · highest 9° in the south" or "Recorded + terrain · highest 14° in
     * the east". [coverage] is that of the recorded sweep, and is left out when it is not known.
     */
    fun summary(resolved: ResolvedHorizon, coverage: Double?): String {
        val profile = resolved.profile
        val source = resolved.source
        if (profile == null || source == null || profile.isFlat) return OPEN_HORIZON
        val parts = ArrayList<String>(3)
        parts.add(if (source == HorizonSource.RECORDED) "Recorded" else source.label)
        if (source != HorizonSource.TERRAIN && coverage != null) parts.add("${AppFormat.percent(coverage)} covered")
        parts.add(highest(profile))
        return parts.joinToString(" · ")
    }

    /** "Terrain horizon applied", "Recorded skyline applied": the heading of the notes that say a skyline was used. */
    fun applied(source: HorizonSource?): String = "${source?.label ?: "Skyline"} applied"

    /** The skyline as the spoken summaries name it: "recorded skyline", "terrain horizon", "recorded and terrain skyline". */
    fun spokenName(source: HorizonSource?): String = when (source) {
        HorizonSource.TERRAIN -> "terrain horizon"
        HorizonSource.RECORDED_AND_TERRAIN -> "recorded and terrain skyline"
        HorizonSource.RECORDED, null -> "recorded skyline"
    }

    /** "highest 14° in the east". */
    fun highest(profile: HorizonProfile): String =
        "highest ${AngleFormatter.formatDegrees(profile.maximumAltitude, 0)} ${highestPlace(profile)}"

    /** Where the skyline is highest: "in the east", or "all round" when it is about as high everywhere. */
    fun highestPlace(profile: HorizonProfile): String {
        val maximum = profile.maximumAltitude
        var east = 0.0
        var north = 0.0
        var count = 0
        for (index in 0 until HorizonProfile.binCount) {
            if (profile.altitudes[index] < maximum - HIGHEST_TOLERANCE) continue
            east += sin(AstroMath.radians(index.toDouble()))
            north += cos(AstroMath.radians(index.toDouble()))
            count++
        }
        if (count == 0 || hypot(east, north) < SPREAD_RESULTANT * count) return "all round"
        val azimuth = AstroMath.normalizeDegrees(AstroMath.degrees(atan2(east, north)))
        return "in the ${direction(azimuth)}"
    }

    /** The nearest of the eight main directions as a lowercase word: "northeast". */
    fun direction(azimuth: Double): String {
        val eighth = (AstroMath.normalizeDegrees(azimuth) / 45.0).roundToInt() % 8
        return CompassPoint.entries[eighth * 2].fullName.lowercase()
    }

    /** "80°–95°": the whole degrees an unrecorded stretch covers (a range starts at the lower edge of a bin). */
    fun gapText(gap: AzimuthRange): String {
        val first = Math.floorMod((gap.start + 0.5).roundToInt(), HorizonProfile.binCount)
        val last = Math.floorMod((gap.start + gap.length - 0.5).roundToInt(), HorizonProfile.binCount)
        return if (first == last) "$first°" else "$first°–$last°"
    }

    /** The first stretches that are still to sweep: "80°–95°, 200°–260° and 2 more". */
    fun gapsText(gaps: List<AzimuthRange>): String {
        val listed = gaps.take(LISTED_GAPS).joinToString(", ") { gapText(it) }
        val more = gaps.size - LISTED_GAPS
        return if (more > 0) "$listed and $more more" else listed
    }

    /** The great-circle distance between two places in metres (spherical Earth: enough for a warning). */
    fun distanceMetres(a: GeographicLocation, b: GeographicLocation): Double {
        val latitudeA = AstroMath.radians(a.latitude)
        val latitudeB = AstroMath.radians(b.latitude)
        val half = sin((latitudeB - latitudeA) / 2).let { it * it } +
            cos(latitudeA) * cos(latitudeB) * sin(AstroMath.radians(b.longitude - a.longitude) / 2).let { it * it }
        return 2 * EARTH_RADIUS_METRES * asin(sqrt(half.coerceIn(0.0, 1.0)))
    }

    private const val EARTH_RADIUS_METRES = 6_371_000.0
}

/**
 * The share of the circle a sweep reached, remembered next to a site's skyline. The stored skyline is only the 360
 * altitudes, so the coverage lives in the device-local preferences together with a fingerprint of the skyline it
 * belongs to; a skyline from elsewhere (a restore, another version) simply has no coverage to show. The degrees that
 * were swept are kept too (as 90 hex digits after the coverage), so that a terrain horizon can fill exactly the stretches
 * the sweep left open and a later sweep that keeps the old skyline for the rest can add up what both reached; entries
 * stored without them still read, they just have no stretches to fill.
 */
internal object HorizonCoverageMemory {
    private const val PREFERENCES = "lodestar.local"

    /**
     * What is remembered with a skyline.
     *
     * @property coverage The share of the circle swept, 0…1.
     * @property mask For each whole degree 0…359, whether it was swept; `null` when it was not stored.
     */
    class Entry(val coverage: Double, val mask: List<Boolean>?)

    private fun key(siteID: String) = "horizon.coverage.$siteID"

    /** The coverage recorded with [site]'s skyline, or `null` when there is none or the skyline has changed since. */
    fun recall(context: Context, site: SavedSite): Double? = entry(preferences(context), site)?.coverage

    /** Everything remembered with [site]'s skyline, or `null` when there is nothing or the skyline has changed since. */
    fun entry(context: Context, site: SavedSite): Entry? = entry(preferences(context), site)

    /** Like [entry], reading from [preferences] (the app's device-local ones). */
    fun entry(preferences: SharedPreferences, site: SavedSite): Entry? {
        val horizon = site.horizon ?: return null
        val text = preferences.getString(key(site.id), null) ?: return null
        val parts = text.split(':', limit = 3)
        if (parts.size < 2 || parts[0].toIntOrNull() != horizon.hashCode()) return null
        val coverage = parts[1].toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: return null
        return Entry(coverage, parts.getOrNull(2)?.let(::decode))
    }

    /**
     * Remembers [coverage] (and the swept degrees [mask], if known) for [horizon] of the site [siteID]; forgets it when
     * there is no skyline.
     */
    fun store(context: Context, siteID: String, horizon: HorizonProfile?, coverage: Double, mask: List<Boolean>? = null) {
        preferences(context).edit {
            if (horizon == null) {
                remove(key(siteID))
            } else {
                val swept = mask?.takeIf { it.size == HorizonProfile.binCount }?.let { ":${encode(it)}" }.orEmpty()
                putString(key(siteID), "${horizon.hashCode()}:$coverage$swept")
            }
        }
    }

    /** Drops what is remembered for the site [siteID]. */
    fun forget(context: Context, siteID: String) {
        preferences(context).edit { remove(key(siteID)) }
    }

    /**
     * What is known of the circle when a sweep that reached [sweep] is saved over a skyline with the memory [old] and
     * the old one stays wherever the sweep did not reach: both sweeps added up. `null` when nothing is remembered about
     * the old skyline, because then it is not known how much of it was ever swept.
     */
    fun merged(sweep: List<Boolean>, old: Entry?): Entry? {
        if (old == null) return null
        val oldMask = old.mask
        // Stored without the degrees: all that can be said is that at least the larger of the two shares is known.
        if (oldMask == null || oldMask.size != sweep.size) return Entry(maxOf(old.coverage, share(sweep)), null)
        val union = oldMask.zip(sweep) { a, b -> a || b }
        return Entry(share(union), union)
    }

    private fun share(mask: List<Boolean>): Double = mask.count { it }.toDouble() / HorizonProfile.binCount

    /** One hex digit per four degrees, the lowest degree in the lowest bit. */
    private fun encode(mask: List<Boolean>): String = buildString {
        for (start in mask.indices step 4) {
            var nibble = 0
            for (offset in 0 until 4) if (start + offset < mask.size && mask[start + offset]) nibble = nibble or (1 shl offset)
            append(nibble.toString(16))
        }
    }

    /** The degrees [encode] wrote, or `null` when the text is not exactly one circle of them. */
    private fun decode(text: String): List<Boolean>? {
        if (text.length * 4 != HorizonProfile.binCount) return null
        val mask = ArrayList<Boolean>(HorizonProfile.binCount)
        for (digit in text) {
            val nibble = digit.digitToIntOrNull(16) ?: return null
            for (offset in 0 until 4) mask.add(nibble and (1 shl offset) != 0)
        }
        return mask
    }

    /** The device-local preferences the memory lives in. */
    fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

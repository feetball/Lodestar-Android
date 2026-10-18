package net.kuecker.lodestar.features.horizon

import android.content.Context
import androidx.core.content.edit
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.CompassPoint
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.horizon.AzimuthRange
import net.kuecker.lodestar.kit.horizon.HorizonProfile
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
 * belongs to; a skyline from elsewhere (a restore, another version) simply has no coverage to show.
 */
internal object HorizonCoverageMemory {
    private const val PREFERENCES = "lodestar.local"

    private fun key(siteID: String) = "horizon.coverage.$siteID"

    /** The coverage recorded with [site]'s skyline, or `null` when there is none or the skyline has changed since. */
    fun recall(context: Context, site: SavedSite): Double? {
        val horizon = site.horizon ?: return null
        val text = preferences(context).getString(key(site.id), null) ?: return null
        val separator = text.indexOf(':')
        if (separator < 0 || text.substring(0, separator).toIntOrNull() != horizon.hashCode()) return null
        return text.substring(separator + 1).toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
    }

    /** Remembers [coverage] for [horizon] of the site [siteID]; forgets it when there is no skyline. */
    fun store(context: Context, siteID: String, horizon: HorizonProfile?, coverage: Double) {
        preferences(context).edit {
            if (horizon == null) remove(key(siteID)) else putString(key(siteID), "${horizon.hashCode()}:$coverage")
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}

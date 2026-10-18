package net.kuecker.lodestar.features.settings

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.parseLatitude
import net.kuecker.lodestar.kit.formatting.parseLongitude
import net.kuecker.lodestar.model.SavedSite
import net.kuecker.lodestar.services.LocationFix
import java.time.ZoneId

/**
 * The editable text of an observing site, and what it means once it is complete. Without a position the
 * coordinate texts are empty.
 */
internal data class SiteDraft(
    val name: String,
    val latitudeText: String = "",
    val longitudeText: String = "",
    /** Metres above sea level. */
    val elevation: Double = 0.0,
) {
    /** The position the text describes, or `null` while the latitude or longitude is incomplete or out of range. */
    val location: GeographicLocation?
        get() {
            val latitude = AngleFormatter.parseLatitude(latitudeText) ?: return null
            val longitude = AngleFormatter.parseLongitude(longitudeText) ?: return null
            return GeographicLocation.validated(latitude, longitude, elevation)
        }

    val isLatitudeValid: Boolean get() = AngleFormatter.parseLatitude(latitudeText) != null
    val isLongitudeValid: Boolean get() = AngleFormatter.parseLongitude(longitudeText) != null

    /** This draft with latitude and longitude written as text; the elevation is left alone. */
    fun withPosition(location: GeographicLocation): SiteDraft = copy(
        latitudeText = AngleFormatter.formatLatitude(location.latitude, 6),
        longitudeText = AngleFormatter.formatLongitude(location.longitude, 6),
    )

    companion object {
        /** A draft at the device's position. The altitude is used only when the fix says it is valid. */
        fun at(name: String, fix: LocationFix): SiteDraft {
            var draft = SiteDraft(name)
            GeographicLocation.validated(fix.latitude, fix.longitude)?.let { draft = draft.withPosition(it) }
            if (fix.verticalAccuracy > 0 && fix.altitude.isFinite()) {
                val range = GeographicLocation.elevationRange
                draft = draft.copy(elevation = AstroMath.clamp(fix.altitude, range.start, range.endInclusive))
            }
            return draft
        }

        /**
         * A draft of a saved site. Coordinates are written to a micro-degree (about 10 cm) so editing the name
         * leaves the position as it was.
         */
        fun of(site: SavedSite): SiteDraft = SiteDraft(site.name, elevation = site.elevation).withPosition(site.location)
    }
}

/** How far the lookup of a site's time zone has got. */
internal sealed interface SiteTimeZoneStatus {
    data object WaitingForCoordinates : SiteTimeZoneStatus

    data object Resolving : SiteTimeZoneStatus

    /** A regional zone: found by the geocoder, or chosen by the user. */
    data class Resolved(val zone: ZoneId) : SiteTimeZoneStatus

    /**
     * The geocoder gave no certain answer: [zone] is a guess among the country's zones, or an estimate from the
     * longitude when there was no answer at all.
     */
    data class Approximate(val zone: ZoneId) : SiteTimeZoneStatus

    /** The zone found or estimated so far. */
    val timeZone: ZoneId?
        get() = when (this) {
            is Resolved -> zone
            is Approximate -> zone
            WaitingForCoordinates, Resolving -> null
        }
}

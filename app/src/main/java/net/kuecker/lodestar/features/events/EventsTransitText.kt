package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.features.satellites.SatelliteText
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.satellites.SatelliteTransitCentreline
import net.kuecker.lodestar.kit.satellites.SatelliteTransitFinder
import net.kuecker.lodestar.shared.AppFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** The words of the ISS transit section and its detail. The iOS app uses the same texts. */
internal object EventsTransitText {
    const val TITLE = "ISS transits of the Sun and Moon"
    const val OFF_HINT = "Switch on Satellite passes in Online Data to predict when the ISS crosses the Sun or the Moon."
    const val DATA_HINT = "ISS transits need fresh orbit data. Update it in Online Data."
    const val ONLINE_DATA = "Online Data"
    const val FINDING = "Finding transits…"
    const val NONE = "No transits in the next 14 days within 50 km of here."
    const val NOTE = "The next 14 days, within 50 km of here. Times are to the second; check again on the day, because the orbit changes."
    const val EXPOSURE_SUN = "Burst mode, 1/2000 s or faster; a solar filter is mandatory for the Sun — never look at or image the Sun without one."
    const val EXPOSURE_MOON = "Burst mode, 1/2000 s or faster. Start the burst a few seconds early."
    const val EXPOSURE_TITLE = "Exposure"
    const val SOLAR_FILTER_TITLE = "Solar filter required"
    const val CENTRELINE_TITLE = "Centreline"
    const val CENTRELINE_NOTE =
        "From a place on the centreline the satellite crosses the middle of the disc at the same moment. Your elevation " +
            "is assumed; check the ground and the horizon before you drive."
    const val FROM_HERE_NOTE = "The satellite crosses the disc as seen from where you are. The centreline gives the longest crossing."
    const val NO_CENTRELINE = "The centreline is farther than 50 km from here."
    const val COPY = "Copy coordinates"
    const val COPIED = "Coordinates copied"
    const val DETAIL_TITLE = "Transit"
    const val CLOSE = "Close"

    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    /** "Sun" or "Moon". */
    fun bodyName(body: SolarSystemBody): String = if (body == SolarSystemBody.SUN) "Sun" else "Moon"

    /** "Sun transit", or "Moon transit from here" when the satellite crosses the disc as seen from the site. */
    fun title(item: EventsTransit): String =
        "${bodyName(item.transit.body)} transit" + if (item.transit.isFromSite) " from here" else ""

    /** "Aim at the Sun" / "Aim at the Moon". */
    fun aimLabel(body: SolarSystemBody): String = "Aim at the ${bodyName(body)}"

    /** "Sat 21:14:07": the weekday and the time to the second in the user's 12 or 24 hour style. */
    fun whenText(date: Instant, zone: ZoneId): String =
        "${weekday.withZone(zone).format(date)} ${SatelliteText.timeWithSeconds(date, zone)}"

    /** "0.6 s", or "<0.1 s" for a crossing shorter than that. */
    fun duration(seconds: Double, locale: Locale = Locale.getDefault()): String {
        if (!seconds.isFinite() || seconds < 0.05) return "<0.1 s"
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return "${format.format(seconds)} s"
    }

    /** A ground distance: "800 m", "2.3 km", "12 km". */
    fun distance(meters: Double, locale: Locale = Locale.getDefault()): String {
        if (!meters.isFinite()) return AppFormat.MISSING
        if (meters < 950.0) return "${(meters / 10.0).roundToInt() * 10} m"
        val kilometers = meters / 1000.0
        if (kilometers >= 9.95) return "${NumberFormat.getIntegerInstance(locale).format(kilometers.roundToInt())} km"
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return "${format.format(kilometers)} km"
    }

    /** "centreline 12 km NE". */
    fun centrelineSummary(centreline: SatelliteTransitCentreline): String =
        "centreline ${distance(centreline.distanceMeters)} ${AngleFormatter.compassPoint(centreline.bearing).abbreviation}"

    /** The second line of a row: "Sat 21:14:07 · 0.6 s" from the site, else "Sat 21:14:07 · centreline 12 km NE". */
    fun rowDetail(item: EventsTransit, zone: ZoneId): String {
        val transit = item.transit
        val tail = if (transit.isFromSite) {
            duration(transit.siteDurationSeconds)
        } else {
            transit.centreline?.let(::centrelineSummary).orEmpty()
        }
        return "${whenText(transit.date, zone)} · $tail"
    }

    /** What a screen reader says for a row. */
    fun spokenRow(item: EventsTransit, zone: ZoneId): String =
        "${item.satelliteName}, ${title(item)}, ${rowDetail(item, zone).replace(" · ", ", ")}"

    /** "Daylight", "Twilight" or "Night" for the Sun's altitude at the transit. */
    fun sky(isDaylight: Boolean, isNight: Boolean): String = when {
        isDaylight -> "Daylight"
        isNight -> "Night"
        else -> "Twilight"
    }

    /** The satellite's span and angular size: "109 m · 0.014° across". */
    fun size(catalogNumber: Int, angularSize: Double, locale: Locale = Locale.getDefault()): String {
        val meters = SatelliteTransitFinder.satelliteSpanMeters(catalogNumber).roundToInt()
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 3
            maximumFractionDigits = 3
        }
        return "$meters m · ${format.format(angularSize)}° across"
    }

    /** "612 km". */
    fun range(kilometers: Double, locale: Locale = Locale.getDefault()): String =
        "${NumberFormat.getIntegerInstance(locale).format(kilometers.roundToInt())} km"

    /** "Crosses the disc" or "Misses the disc by 7′": how the transit looks from the observer's own site. */
    fun fromHere(isFromSite: Boolean, separation: Double, bodyRadius: Double): String =
        if (isFromSite) "Crosses the disc" else "Misses the disc by ${AppFormat.separation(separation - bodyRadius)}"

    /** "NE (45°)": the compass point and the bearing. */
    fun bearing(degrees: Double): String =
        "${AngleFormatter.compassPoint(degrees).abbreviation} (${AngleFormatter.formatDegrees(degrees, 0)})"

    /** "40.12345, -105.23456": decimal degrees that a maps app takes as they are. */
    fun coordinates(location: GeographicLocation): String =
        "${"%.5f".format(Locale.ROOT, location.latitude)}, ${"%.5f".format(Locale.ROOT, location.longitude)}"
}

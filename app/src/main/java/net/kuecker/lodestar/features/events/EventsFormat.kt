package net.kuecker.lodestar.features.events

import android.text.format.DateFormat
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brightness1
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EclipseContact
import net.kuecker.lodestar.kit.events.ElongationDirection
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.LocalSolarEclipseKind
import net.kuecker.lodestar.kit.events.SeasonKind
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How much of the dark sky the Moon spoils at the peak of a meteor shower, from the Moon's lit fraction. */
internal enum class MoonInterference(val adjective: String) {
    LITTLE("little"),
    MODERATE("moderate"),
    STRONG("strong"),
    ;

    companion object {
        /** A thin Moon hardly matters; from about a quarter lit it washes out the fainter meteors; a bright one hides most. */
        operator fun invoke(illuminatedFraction: Double): MoonInterference = when {
            illuminatedFraction <= 0.25 -> LITTLE
            illuminatedFraction <= 0.6 -> MODERATE
            else -> STRONG
        }
    }
}

/** Text and icons for events: dates, one-line summaries, badges and the names of eclipse contacts. */
internal object EventsFormat {
    private val formatters = HashMap<String, DateTimeFormatter>()

    // Icons

    fun icon(kind: EventKind): ImageVector = when (kind) {
        EventKind.METEOR_SHOWER -> Icons.Outlined.AutoAwesome
        EventKind.CONJUNCTION -> Icons.Outlined.JoinInner
        EventKind.OPPOSITION -> Icons.Outlined.Public
        EventKind.GREATEST_ELONGATION -> Icons.Outlined.Straighten
        EventKind.EARTH_APSIS -> Icons.Outlined.Sync
        EventKind.SEASON -> Icons.Outlined.LightMode
        EventKind.FULL_MOON_SPECIAL -> Icons.Outlined.Brightness1
        EventKind.LUNAR_ECLIPSE -> Icons.Outlined.Contrast
        EventKind.SOLAR_ECLIPSE -> Icons.Outlined.WbSunny
    }

    // Dates

    /** The instant a list shows for [event]: the greatest eclipse here for a solar eclipse that can be seen, else its date. */
    fun displayDate(event: AstronomicalEvent): Instant =
        (event.details as? EventDetails.SolarEclipse)?.eclipse?.local?.maximum ?: event.date

    /** "Sat, Nov 14 · 21:34". */
    fun whenText(date: Instant, zone: ZoneId): String = "${AppFormat.day(date, zone)} · ${AppFormat.time(date, zone)}"

    /** The same for a screen reader: "Sat, Nov 14 at 21:34". */
    fun spokenWhen(date: Instant, zone: ZoneId): String = "${AppFormat.day(date, zone)} at ${AppFormat.time(date, zone)}"

    /** A time of day, with the weekday when it is not on the same local day as [reference]: "02:11" or "Sun 02:11". */
    fun timeNear(date: Instant, reference: Instant, zone: ZoneId): String {
        val time = AppFormat.time(date, zone)
        if (date.atZone(zone).toLocalDate() == reference.atZone(zone).toLocalDate()) return time
        return "${formatter("EEE").withZone(zone).format(date)} $time"
    }

    /** "Jul 17" for the ends of a meteor shower's activity. */
    fun monthDay(date: Instant, zone: ZoneId): String = formatter("MMMd").withZone(zone).format(date)

    /** "November 2026". */
    fun month(date: Instant, zone: ZoneId): String = formatter("yMMMM").withZone(zone).format(date)

    /** How far away [date] is: "Today", "Tomorrow", "In 5 days", "In 3 weeks", "In 4 months". */
    fun distance(date: Instant, now: Instant, zone: ZoneId): String {
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), date.atZone(zone).toLocalDate())
        return when {
            days <= 0 -> if (date >= now || days == 0L) "Today" else "Passed"
            days == 1L -> "Tomorrow"
            days <= 14 -> "In $days days"
            days < 90 -> "In ${days / 7} weeks"
            else -> "In ${days / 30} months"
        }
    }

    // Summaries

    /** The one line under an event's title in lists. */
    fun summary(event: AstronomicalEvent, context: ObserverContext): String {
        val zone = context.timeZone
        return when (val details = event.details) {
            is EventDetails.MeteorShower -> {
                val parts = arrayListOf("Up to ${details.peak.shower.zhr} an hour")
                event.visibility?.let { parts.add("radiant " + radiantHeight(it.altitude)) }
                parts.joinToString(" · ")
            }
            is EventDetails.Conjunction -> {
                val conjunction = details.conjunction
                val approach = conjunction.topocentric
                when {
                    approach != null && approach.isOccultation ->
                        "${conjunction.second.displayName} is hidden by the Moon from here"
                    conjunction.occultationPossible && conjunction.involvesMoon ->
                        "${AppFormat.separation(conjunction.separation)} apart · occultation possible elsewhere"
                    approach != null && conjunction.involvesMoon ->
                        "${AppFormat.separation(approach.separation)} apart from here"
                    else -> "${AppFormat.separation(conjunction.separation)} apart"
                }
            }
            is EventDetails.Opposition -> {
                val opposition = details.opposition
                listOfNotNull(
                    opposition.apparentMagnitude?.let { AppFormat.magnitude(it) },
                    "${number(opposition.distanceAU, 2)} AU away",
                ).joinToString(" · ")
            }
            is EventDetails.GreatestElongation -> {
                val elongation = details.elongation
                val east = elongation.direction == ElongationDirection.EAST
                listOfNotNull(
                    "${AngleFormatter.formatDegrees(elongation.angle, 0)} ${elongation.direction.displayName.lowercase(Locale.ROOT)} of the Sun",
                    if (east) "evening sky" else "morning sky",
                    elongation.apparentMagnitude?.let { AppFormat.magnitude(it) },
                ).joinToString(" · ")
            }
            is EventDetails.Season -> seasonSummary(details.season.kind, context.location.latitude < 0)
            is EventDetails.EarthApsis ->
                "${number(details.apsis.distanceAU, 4)} AU from the Sun"
            is EventDetails.FullMoonSpecial -> {
                val special = details.special
                "${number(special.distanceKilometers, 0)} km away · ${arcminutes(special.angularDiameterArcseconds)} across"
            }
            is EventDetails.LunarEclipse -> {
                val eclipse = details.eclipse
                "${eclipse.kind.displayName} · magnitude ${number(eclipse.umbralMagnitude, 2)} · greatest ${AppFormat.time(eclipse.greatest, zone)}"
            }
            is EventDetails.SolarEclipse -> solarSummary(details, zone)
        }
    }

    private fun solarSummary(details: EventDetails.SolarEclipse, zone: ZoneId): String {
        val eclipse = details.eclipse
        val local = eclipse.local ?: return "${eclipse.kind.displayName} · magnitude ${number(eclipse.greatestMagnitude, 2)}"
        val time = local.maximum?.let { AppFormat.time(it, zone) } ?: AppFormat.MISSING
        return when (local.kind) {
            LocalSolarEclipseKind.NONE -> "${eclipse.kind.displayName} eclipse · not visible here"
            LocalSolarEclipseKind.TOTAL ->
                "Total eclipse here: ${AngleFormatter.formatDuration(local.centralDuration)} at $time"
            LocalSolarEclipseKind.ANNULAR ->
                "Annular eclipse here: ${AngleFormatter.formatDuration(local.centralDuration)} at $time"
            LocalSolarEclipseKind.PARTIAL ->
                "Partial, ${AppFormat.percent(local.obscuration)} covered, maximum $time"
        }
    }

    private fun seasonSummary(kind: SeasonKind, isSouthern: Boolean): String = when (kind) {
        SeasonKind.MARCH_EQUINOX ->
            (if (isSouthern) "Start of autumn" else "Start of spring") + " · day and night are equal"
        SeasonKind.JUNE_SOLSTICE ->
            if (isSouthern) "Start of winter · shortest day" else "Start of summer · longest day"
        SeasonKind.SEPTEMBER_EQUINOX ->
            (if (isSouthern) "Start of spring" else "Start of autumn") + " · day and night are equal"
        SeasonKind.DECEMBER_SOLSTICE ->
            if (isSouthern) "Start of summer · longest day" else "Start of winter · shortest day"
    }

    /** "52° high", or "below the horizon" for a radiant that has not risen at the peak. */
    private fun radiantHeight(altitude: Double): String =
        if (altitude > 0) "${AngleFormatter.formatDegrees(altitude, 0)} high" else "below the horizon"

    // Visibility

    /** Whether the observer can see [event]: `null` for events that are not seen in the sky (seasons). */
    fun isVisibleFromHere(event: AstronomicalEvent): Boolean? = event.visibility?.isObservable

    /** The Moon's effect at the peak of a meteor shower, or `null` for other events. */
    fun moonInterference(event: AstronomicalEvent): MoonInterference? =
        (event.details as? EventDetails.MeteorShower)?.let { MoonInterference(it.peak.moonIlluminatedFraction) }

    /** "Moon 4% lit · little interference". */
    fun moonText(event: AstronomicalEvent): String? {
        val shower = event.details as? EventDetails.MeteorShower ?: return null
        val fraction = shower.peak.moonIlluminatedFraction
        return "Moon ${AppFormat.percent(fraction)} lit · ${MoonInterference(fraction).adjective} interference"
    }

    // Eclipse contacts

    /** What happens at [contact]; [central] names the central phase of a solar eclipse ("Totality" or "Annularity"). */
    fun contactTitle(contact: EclipseContact, central: String = "Totality"): String = when (contact) {
        EclipseContact.P1 -> "Penumbra begins"
        EclipseContact.U1 -> "Partial eclipse begins"
        EclipseContact.U2 -> "Total eclipse begins"
        EclipseContact.GREATEST -> "Greatest eclipse"
        EclipseContact.U3 -> "Total eclipse ends"
        EclipseContact.U4 -> "Partial eclipse ends"
        EclipseContact.P4 -> "Penumbra ends"
        EclipseContact.C1 -> "Partial eclipse begins"
        EclipseContact.C2 -> "$central begins"
        EclipseContact.MAXIMUM -> "Maximum eclipse"
        EclipseContact.C3 -> "$central ends"
        EclipseContact.C4 -> "Partial eclipse ends"
    }

    /** "35° up" or "12° below the horizon" for the eclipsed body at a contact. */
    fun altitudeText(altitude: Double, body: String): String =
        if (altitude >= 0) {
            "$body ${AngleFormatter.formatDegrees(altitude, 0)} up"
        } else {
            "$body ${AngleFormatter.formatDegrees(-altitude, 0)} below the horizon"
        }

    // Numbers

    fun number(value: Double, decimals: Int): String =
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = decimals
            maximumFractionDigits = decimals
        }.format(value)

    /** An angular size in arcseconds as arcminutes: "33.5′". */
    fun arcminutes(arcseconds: Double): String = "${number(arcseconds / 60, 1)}′"

    private fun formatter(skeleton: String): DateTimeFormatter = synchronized(formatters) {
        val locale = Locale.getDefault()
        formatters.getOrPut("${locale.toLanguageTag()}|$skeleton") {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }
    }
}

package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.features.tonight.TonightFormat
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.ContactVisibility
import net.kuecker.lodestar.kit.events.EclipseContact
import net.kuecker.lodestar.kit.events.ElongationDirection
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.LocalSolarEclipseKind
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatDuration
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.shared.AppFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** One line of the "Local times" or "From here" card. [isDimmed] greys out what happens with the subject below the horizon. */
internal data class EventInfoRow(val label: String, val value: String, val isDimmed: Boolean = false)

/** The local times and numbers of an event, as label and value rows. Plain text built from the engine's event. */
internal object EventsTimes {
    /** The local times of [event]: the moment itself, its phases and the dark window for the observer. */
    fun times(event: AstronomicalEvent, context: ObserverContext, plan: EventPlan?): List<EventInfoRow> {
        val zone = context.timeZone
        val rows = ArrayList<EventInfoRow>()
        fun add(label: String, value: String, isDimmed: Boolean = false) {
            rows.add(EventInfoRow(label, value, isDimmed))
        }
        when (val details = event.details) {
            is EventDetails.MeteorShower -> {
                val peak = details.peak
                add("Peak", EventsFormat.whenText(event.date, zone))
                add(
                    "Active",
                    "${EventsFormat.monthDay(peak.activityStart, zone)} – ${EventsFormat.monthDay(peak.activityEnd, zone)}",
                )
                add("Rate at the peak", "Up to ${peak.shower.zhr} meteors an hour")
                add("Moon at the peak", "${AppFormat.percent(peak.moonIlluminatedFraction)} lit")
            }
            is EventDetails.Conjunction -> {
                val conjunction = details.conjunction
                add("Closest approach", EventsFormat.whenText(event.date, zone))
                add("Separation", AppFormat.separation(conjunction.separation))
                conjunction.topocentric?.let {
                    add("Closest from here", "${AppFormat.time(it.time, zone)} · ${AppFormat.separation(it.separation)}")
                    if (it.isOccultation) add("Occultation", "The Moon covers ${conjunction.second.displayName} from here")
                }
                add("From the Sun", AngleFormatter.formatDegrees(conjunction.solarElongation, 0))
            }
            is EventDetails.Opposition -> {
                val opposition = details.opposition
                add("Opposition", EventsFormat.whenText(event.date, zone))
                opposition.apparentMagnitude?.let { add("Brightness", AppFormat.magnitude(it)) }
                add("Distance", "${EventsFormat.number(opposition.distanceAU, 2)} AU")
                add("Apparent size", "${EventsFormat.number(opposition.angularDiameterArcseconds, 1)}″")
            }
            is EventDetails.GreatestElongation -> {
                val elongation = details.elongation
                add("Greatest elongation", EventsFormat.whenText(event.date, zone))
                add(
                    "Angle from the Sun",
                    "${AngleFormatter.formatDegrees(elongation.angle, 1)} ${elongation.direction.displayName.lowercase(Locale.ROOT)}",
                )
                add("Sky", if (elongation.direction == ElongationDirection.EAST) "Evening, after sunset" else "Morning, before sunrise")
                elongation.apparentMagnitude?.let { add("Brightness", AppFormat.magnitude(it)) }
            }
            is EventDetails.Season -> add("Moment", EventsFormat.whenText(event.date, zone))
            is EventDetails.EarthApsis -> {
                add("Moment", EventsFormat.whenText(event.date, zone))
                add("Distance from the Sun", "${EventsFormat.number(details.apsis.distanceAU, 4)} AU")
            }
            is EventDetails.FullMoonSpecial -> {
                val special = details.special
                add("Full Moon", EventsFormat.whenText(event.date, zone))
                add("Kind", special.categories.joinToString(", ") { it.displayName })
                add("Distance", "${EventsFormat.number(special.distanceKilometers, 0)} km")
                add("Apparent size", EventsFormat.arcminutes(special.angularDiameterArcseconds))
            }
            is EventDetails.LunarEclipse -> lunarRows(event, details, zone, ::add)
            is EventDetails.SolarEclipse -> solarRows(event, details, zone, ::add)
        }
        // The dark window the visibility computed, for the events that are seen at night.
        val visibility = event.visibility
        if (visibility != null && !isEclipse(event)) {
            val window = visibility.bestWindow
            add("Best window", window?.let { TonightFormat.window(it, zone) } ?: "None at your location")
            visibility.bestTime?.let { add("Best time", EventsFormat.timeNear(it, event.date, zone)) }
        }
        plan?.let { addPlanRows(event, it, zone, ::add) }
        return rows
    }

    private fun isEclipse(event: AstronomicalEvent): Boolean =
        event.details is EventDetails.LunarEclipse || event.details is EventDetails.SolarEclipse

    /** The radiant and the Moon at the best time. */
    private fun addPlanRows(
        event: AstronomicalEvent,
        plan: EventPlan,
        zone: ZoneId,
        add: (String, String, Boolean) -> Unit,
    ) {
        if (event.visibility?.isObservable != true) return
        val at = AppFormat.time(plan.time, zone)
        plan.radiant?.let {
            add("Radiant at $at", "${AppFormat.altitude(it.altitude)} · ${AngleFormatter.compassPoint(it.azimuth).abbreviation}", false)
        }
        plan.meteorFrame?.let {
            add("Frame the sky at $at", "${AngleFormatter.compassPoint(it.azimuth).abbreviation} · ${AngleFormatter.formatDegrees(it.altitude, 0)} up", false)
        }
        plan.moon?.let {
            val text = if (it.isUp) {
                "${AngleFormatter.formatDegrees(it.altitude, 0)} up · ${AppFormat.percent(it.illuminatedFraction)} lit"
            } else {
                "Below the horizon"
            }
            add("Moon at $at", text, false)
        }
    }

    // Eclipses

    private fun lunarRows(
        event: AstronomicalEvent,
        details: EventDetails.LunarEclipse,
        zone: ZoneId,
        add: (String, String, Boolean) -> Unit,
    ) {
        val eclipse = details.eclipse
        add("Kind", "${eclipse.kind.displayName} lunar eclipse", false)
        add("Umbral magnitude", EventsFormat.number(eclipse.umbralMagnitude, 2), false)
        add("Penumbral magnitude", EventsFormat.number(eclipse.penumbralMagnitude, 2), false)
        if (eclipse.totalDuration > 0) add("Totality lasts", AngleFormatter.formatDuration(eclipse.totalDuration), false)
        if (eclipse.partialDuration > 0) add("Partial phase lasts", AngleFormatter.formatDuration(eclipse.partialDuration), false)
        // All the contacts, with the Moon's altitude where the engine worked it out.
        val altitudes = event.visibility?.contacts.orEmpty().associateBy { it.contact }
        for (contact in eclipse.contacts) {
            val where = altitudes[contact.contact]
            add(
                EventsFormat.contactTitle(contact.contact),
                contactValue(contact.time, event.date, zone, where, "Moon"),
                where != null && where.altitude < 0,
            )
        }
    }

    private fun solarRows(
        event: AstronomicalEvent,
        details: EventDetails.SolarEclipse,
        zone: ZoneId,
        add: (String, String, Boolean) -> Unit,
    ) {
        val eclipse = details.eclipse
        add("Kind", "${eclipse.kind.displayName} solar eclipse", false)
        add("Greatest eclipse (anywhere)", EventsFormat.whenText(event.date, zone), false)
        add("Global magnitude", EventsFormat.number(eclipse.greatestMagnitude, 3), false)
        val local = eclipse.local ?: return
        add("From here", localKindText(local.kind), false)
        if (local.kind == LocalSolarEclipseKind.NONE) return
        add("Magnitude here", EventsFormat.number(local.magnitude, 3), false)
        add("Sun covered", AppFormat.percent(local.obscuration), false)
        if (local.kind == LocalSolarEclipseKind.TOTAL || local.kind == LocalSolarEclipseKind.ANNULAR) {
            add(if (local.kind == LocalSolarEclipseKind.TOTAL) "Totality lasts" else "Annularity lasts", AngleFormatter.formatDuration(local.centralDuration), false)
        }
        val central = if (local.kind == LocalSolarEclipseKind.ANNULAR) "Annularity" else "Totality"
        val altitudes = event.visibility?.contacts.orEmpty().associateBy { it.contact }
        val contacts = listOf(
            EclipseContact.C1 to local.c1,
            EclipseContact.C2 to local.c2,
            EclipseContact.MAXIMUM to local.maximum,
            EclipseContact.C3 to local.c3,
            EclipseContact.C4 to local.c4,
        )
        for ((contact, time) in contacts) {
            if (time == null) continue
            val where = altitudes[contact]
            add(
                EventsFormat.contactTitle(contact, central),
                contactValue(time, local.maximum ?: event.date, zone, where, "Sun"),
                where != null && where.altitude < 0,
            )
        }
    }

    private fun localKindText(kind: LocalSolarEclipseKind): String = when (kind) {
        LocalSolarEclipseKind.NONE -> "No eclipse visible"
        LocalSolarEclipseKind.PARTIAL -> "Partial eclipse"
        LocalSolarEclipseKind.ANNULAR -> "Annular eclipse"
        LocalSolarEclipseKind.TOTAL -> "Total eclipse"
    }

    /** The time of a contact and, when known, the height of the eclipsed body: "03:41 · Moon 35° up". */
    private fun contactValue(time: Instant, reference: Instant, zone: ZoneId, where: ContactVisibility?, body: String): String {
        val clock = EventsFormat.timeNear(time, reference, zone)
        return if (where == null) clock else "$clock · ${EventsFormat.altitudeText(where.altitude, body)}"
    }

    // From here

    /**
     * What the observer can see: the subject's height at the event, the Sun, and the window. Empty for events that are
     * not seen in the sky.
     */
    fun visibilityRows(event: AstronomicalEvent, context: ObserverContext): List<EventInfoRow> {
        val visibility = event.visibility ?: return emptyList()
        val zone = context.timeZone
        val rows = ArrayList<EventInfoRow>()
        val subject = subjectName(event)
        rows.add(
            EventInfoRow(
                "$subject at the event",
                if (visibility.altitude > 0) {
                    "${AppFormat.altitude(visibility.altitude)} · ${AppFormat.azimuth(visibility.azimuth)}"
                } else {
                    "Below the horizon"
                },
            ),
        )
        if (!isEclipse(event)) {
            rows.add(
                EventInfoRow(
                    "Sun at the event",
                    AppFormat.altitude(visibility.sunAltitude) + if (visibility.isDark) " · dark" else "",
                ),
            )
        }
        visibility.bestWindow?.let { rows.add(EventInfoRow("Viewing window", TonightFormat.window(it, zone))) }
        return rows
    }

    private fun subjectName(event: AstronomicalEvent): String = when (val details = event.details) {
        is EventDetails.MeteorShower -> "Radiant"
        is EventDetails.Conjunction -> "Pair"
        is EventDetails.Opposition -> details.opposition.planet.displayName
        is EventDetails.GreatestElongation -> details.elongation.planet.displayName
        is EventDetails.FullMoonSpecial, is EventDetails.LunarEclipse -> "Moon"
        is EventDetails.SolarEclipse, is EventDetails.Season, is EventDetails.EarthApsis -> "Sun"
    }
}

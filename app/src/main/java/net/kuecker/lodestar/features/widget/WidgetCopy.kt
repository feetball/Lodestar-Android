package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.features.events.EventsFormat
import net.kuecker.lodestar.features.tonight.TonightFormat
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.planning.MilkyWayCoreVisibility
import net.kuecker.lodestar.shared.AppFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** How the widgets write times: the phone's 12/24-hour setting and language, in the observer's time zone. */
internal interface WidgetTimeFormat {
    /** A time of day, such as "21:34" or "9:34 PM". */
    fun time(instant: Instant, zone: ZoneId): String

    /** A weekday and date, such as "Sat, Nov 14". */
    fun day(instant: Instant, zone: ZoneId): String
}

/** One thing the widget shows: Darkness, the Moon, the Milky Way core or the next event. */
internal data class WidgetItem(
    val label: String,
    /** The label where there is little room. */
    val shortLabel: String,
    /** A short note beside the label that marks what is happening now ("now", "up now", "in 3 days"), or `null`. */
    val badge: String?,
    /** A figure at the other end of the label's line (the Moon's lit part), or `null`. */
    val trailing: String?,
    /** The main text: a time window, a phase, an event title. */
    val value: String,
    /** The line under it, or `null`. */
    val detail: String?,
    /** A last line for the roomiest layouts, or `null`. */
    val extra: String?,
    /** The whole item as a screen reader says it. */
    val spoken: String,
)

/** All the words of a widget for one snapshot. */
internal data class WidgetCopy(
    val place: String,
    val dark: WidgetItem,
    val moon: WidgetItem,
    val core: WidgetItem,
    val event: WidgetItem,
    /** The whole widget as a screen reader says it. */
    val spoken: String,
) {
    companion object {
        /** The widget's title. */
        const val TITLE = "Tonight"

        /** The line of a widget whose place the app has not given yet. */
        const val NEEDS_LOCATION = "Open Lodestar to set your location"

        /** The widget's text for [night] as it is at [now]. */
        fun of(night: WidgetNight, now: Instant, format: WidgetTimeFormat): WidgetCopy {
            val writer = Writer(night, now, format)
            val dark = writer.dark()
            val moon = writer.moon()
            val core = writer.core()
            val event = writer.event()
            val spoken = listOf(dark, moon, core, event).joinToString(" ") { it.spoken }
            return WidgetCopy(night.placeName, dark, moon, core, event, "$TITLE at ${night.placeName}. $spoken")
        }

        /** The Moon's lit part as a percentage with no decimals: "72%". */
        fun percent(fraction: Double): String {
            if (!fraction.isFinite()) return "—"
            return "${(fraction.coerceIn(0.0, 1.0) * 100).roundedHalfAwayFromZero().toInt()}%"
        }

        /** A length of time for a screen reader: "7 hours 9 minutes". */
        fun spokenDuration(hours: Double): String {
            val minutes = if (hours.isFinite() && hours > 0) (hours * 60).roundedHalfAwayFromZero().toInt() else 0
            val whole = minutes / 60
            val rest = minutes % 60
            val parts = ArrayList<String>()
            if (whole > 0) parts.add(if (whole == 1) "1 hour" else "$whole hours")
            if (rest > 0 || whole == 0) parts.add(if (rest == 1) "1 minute" else "$rest minutes")
            return parts.joinToString(" ")
        }
    }

    private class Writer(private val night: WidgetNight, private val now: Instant, private val format: WidgetTimeFormat) {
        private val zone = night.zone

        private fun time(instant: Instant) = format.time(instant, zone)

        /** A time as written in the widget: a line never breaks between "9:34" and "PM". */
        private fun shownTime(instant: Instant) = AppFormat.unbreakable(time(instant))

        /** "9:29 PM – 3:39 AM", which can break only at the spaces around the dash. */
        private fun window(interval: DateInterval) = "${shownTime(interval.start)} – ${shownTime(interval.end)}"

        private fun spokenWindows(intervals: List<DateInterval>) =
            intervals.joinToString(" and ") { "${time(it.start)} to ${time(it.end)}" }

        private fun isNow(intervals: List<DateInterval>) = intervals.any { now in it }

        // Darkness

        fun dark(): WidgetItem {
            val darkness = night.darkness
            if (darkness.windows.isEmpty()) {
                return WidgetItem(
                    "Dark", "Dark", null, null, "No full darkness", "The Sun stays above −18°", null,
                    "Darkness: there is no full astronomical darkness tonight.",
                )
            }
            val badge = if (isNow(darkness.windows)) "now" else null
            val moonFree = moonFreeText(darkness)
            return WidgetItem(
                "Dark", "Dark", badge, null,
                darkness.windows.joinToString(", ") { window(it) },
                "${TonightFormat.duration(darkness.hours)} of darkness", moonFree,
                "Darkness ${if (badge != null) "now, " else ""}from ${spokenWindows(darkness.windows)}, " +
                    "${spokenDuration(darkness.hours)}. $moonFree.",
            )
        }

        private fun moonFreeText(darkness: WidgetDarkness): String {
            val minute = 1.0 / 60
            return when {
                darkness.moonFreeHours >= darkness.hours - minute -> "The Moon stays down"
                darkness.moonFreeHours <= minute -> "Moonlit all through"
                else -> "${TonightFormat.duration(darkness.moonFreeHours)} without the Moon"
            }
        }

        // Moon

        fun moon(): WidgetItem {
            val moon = night.moon
            val phase = moon.phase.displayName
            val percent = percent(moon.illuminatedFraction)
            val badge = if (isNow(moon.upIntervals)) "up" else null
            return WidgetItem(
                "Moon", "Moon", badge, percent, phase, moonHorizon(moon), null,
                "Moon: $phase, $percent lit${if (badge != null) ", up now" else ""}. ${spokenMoonHorizon(moon)}",
            )
        }

        private fun moonHorizon(moon: WidgetMoon): String {
            val rise = moon.rise
            val set = moon.set
            return when (moon.circumstance) {
                RiseTransitSet.Circumstance.ALWAYS_UP -> "Up all night"
                RiseTransitSet.Circumstance.ALWAYS_DOWN -> "Down all night"
                RiseTransitSet.Circumstance.NORMAL -> when {
                    rise != null && set != null && set < rise -> "Sets ${shownTime(set)} · rises ${shownTime(rise)}"
                    rise != null && set != null -> "Rises ${shownTime(rise)} · sets ${shownTime(set)}"
                    rise != null -> "Rises ${shownTime(rise)}"
                    set != null -> "Sets ${shownTime(set)}"
                    else -> "Up all night"
                }
            }
        }

        private fun spokenMoonHorizon(moon: WidgetMoon): String {
            val rise = moon.rise
            val set = moon.set
            return when (moon.circumstance) {
                RiseTransitSet.Circumstance.ALWAYS_UP -> "It is up all night."
                RiseTransitSet.Circumstance.ALWAYS_DOWN -> "It is down all night."
                RiseTransitSet.Circumstance.NORMAL -> when {
                    rise != null && set != null && set < rise -> "It sets at ${time(set)} and rises at ${time(rise)}."
                    rise != null && set != null -> "It rises at ${time(rise)} and sets at ${time(set)}."
                    rise != null -> "It rises at ${time(rise)}."
                    set != null -> "It sets at ${time(set)}."
                    else -> "It is up all night."
                }
            }
        }

        // Milky Way core

        fun core(): WidgetItem {
            val core = night.core
            val label = "Milky Way core"
            val short = "Milky Way"
            val minimum = MilkyWayCoreVisibility.minimumAltitude.toInt()
            if (!core.hasDarkness) {
                return WidgetItem(
                    label, short, null, null, "No darkness", "Too bright to photograph", null,
                    "Milky Way core: it cannot be photographed, because there is no full darkness tonight.",
                )
            }
            if (core.visibleWindows.isEmpty()) {
                return WidgetItem(
                    label, short, null, null, "Low in the dark", "Below $minimum° while it is dark", null,
                    "Milky Way core: it stays below $minimum degrees while it is dark.",
                )
            }
            val badge = if (isNow(core.visibleWindows)) "now" else null
            val best = best(core)
            return WidgetItem(
                label, short, badge, null,
                core.visibleWindows.joinToString(", ") { window(it) }, best, null,
                "Milky Way core visible ${if (badge != null) "now, " else ""}from ${spokenWindows(core.visibleWindows)}." +
                    (spokenBest(core)?.let { " $it" } ?: ""),
            )
        }

        /** "Best 02:40 · 28° SE". */
        private fun best(core: WidgetCore): String? {
            val time = core.bestTime ?: return null
            val altitude = core.maxAltitude ?: return "Best ${shownTime(time)}"
            val height = AngleFormatter.formatDegrees(altitude, 0)
            val azimuth = core.bestAzimuth ?: return "Best ${shownTime(time)} · $height"
            return "Best ${shownTime(time)} · $height ${AngleFormatter.compassPoint(azimuth).abbreviation}"
        }

        private fun spokenBest(core: WidgetCore): String? {
            val time = core.bestTime ?: return null
            val altitude = core.maxAltitude ?: return "Best at ${time(time)}."
            val height = AngleFormatter.formatDegrees(altitude, 0)
            val azimuth = core.bestAzimuth ?: return "Best at ${time(time)}, $height up."
            val direction = AngleFormatter.compassPoint(azimuth).fullName.lowercase(Locale.ROOT)
            return "Best at ${time(time)}, $height up in the $direction."
        }

        // Event

        fun event(): WidgetItem {
            val event = night.event
            val label = "Next event"
            if (event == null) {
                return WidgetItem(
                    label, "Next", null, null, "Nothing in view", "No events are coming up", null,
                    "Next event: none is coming up.",
                )
            }
            val relative = EventsFormat.distance(event.date, now, zone).takeIf { it != "Passed" }
            val extra = listOfNotNull(
                event.showerRate?.let { "Up to $it an hour" },
                event.bestWindow?.let { "Best view ${window(it)}" },
                if (event.isVisibleFromHere == false) "Not visible from here" else null,
            ).joinToString(" · ").ifEmpty { null }
            return WidgetItem(
                label, "Next", relative?.replaceFirstChar { it.lowercase(Locale.ROOT) }, null, event.title,
                "${format.day(event.date, zone)} · ${shownTime(event.date)}", extra,
                "Next event: ${event.title}, ${format.day(event.date, zone)} at ${time(event.date)}." +
                    (extra?.let { " $it." } ?: ""),
            )
        }
    }
}

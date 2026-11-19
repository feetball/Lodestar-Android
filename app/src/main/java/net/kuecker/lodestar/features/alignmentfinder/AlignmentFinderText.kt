package net.kuecker.lodestar.features.alignmentfinder

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.planning.SkyWindowMatch
import net.kuecker.lodestar.kit.planning.SkyWindowMoonState
import net.kuecker.lodestar.shared.AppFormat
import java.time.ZoneId
import kotlin.math.roundToInt

/** The words of the Alignment finder; the same on both platforms, so keep them in step with the iOS `AlignmentFinderText`. */
internal object AlignmentFinderText {
    const val TITLE = "Alignment Finder"

    const val INTRO =
        "Finds the nights on which a target stands over a patch of sky, such as the Milky Way core over a ridge or in " +
            "a gap between trees."

    const val NO_SKYLINE_TITLE = "No skyline recorded"

    const val NO_SKYLINE =
        "This site has no recorded skyline, so there is nothing to pick from. Open the site in Settings ▸ Location and " +
            "choose Record Skyline…, or use an azimuth and altitude window."

    const val SKYLINE_FOOTER =
        "Tap or drag the strip to pick a direction. The window runs from the skyline up to 10° above it, so the " +
            "target is in the gap or just over the ridge."

    const val WINDOW_FOOTER =
        "Altitudes are as seen, with refraction. Azimuth runs from north through east: 90° is east, 180° south, " +
            "270° west."

    const val DIM_FOOTER = "Dim keeps the nights on which at most this share of the Moon’s disc is lit, whether or not it is up."

    const val SEARCHING = "Searching…"

    const val NEEDS_OBJECT = "Choose an object to search for."

    const val NEEDS_LOCATION = "Choose a location first."

    const val CUT_SHORT = "Showing the first 200 matches. Choose a shorter period or a narrower window to see the rest."

    const val PAST_NOTE = "Times are in the time zone of the observing site. Matches that have already ended are left out."

    const val SUN_NOTE = "There is no Aim button for the Sun. Use a solar filter and aim from the Aim tab."

    /** An azimuth as degrees and the compass point, e.g. "225° SW". */
    fun direction(degrees: Int): String = "$degrees° ${AngleFormatter.compassPoint(degrees.toDouble()).abbreviation}"

    /** An altitude in whole degrees, e.g. "18°". */
    fun altitude(degrees: Double): String = "${degrees.roundToInt()}°"

    /** The header of a night's group, e.g. "Night of Sat, Mar 7". */
    fun nightHeader(night: DateInterval, zone: ZoneId): String = "Night of ${AppFormat.day(night.start, zone)}"

    /** A match's day and times, e.g. "Sun, Mar 8 · 02:10–02:45". */
    fun rowTitle(match: SkyWindowMatch, zone: ZoneId): String =
        "${AppFormat.day(match.interval.start, zone)} · " +
            "${AppFormat.time(match.interval.start, zone)}–${AppFormat.time(match.interval.end, zone)}"

    /** What the target and the Moon do in the middle of a match, e.g. "Alt 18° · Az 225° SW · Moon down". */
    fun rowDetail(match: SkyWindowMatch): String =
        "Alt ${altitude(match.altitude)} · Az ${direction(match.azimuth.roundToInt() % 360)} · ${moon(match.moon)}"

    /** The Moon's state, e.g. "Moon down" or "Moon up, 63% lit". */
    fun moon(state: SkyWindowMoonState): String =
        if (state.isUp) "Moon up, ${AppFormat.percent(state.illuminatedFraction)} lit" else "Moon down"

    /** The note under a match whose target is below the skyline in the middle of it. */
    const val BEHIND_SKYLINE = "Behind your skyline"

    /** What a row says to a screen reader, one sentence per fact. */
    fun rowDescription(match: SkyWindowMatch, zone: ZoneId): String =
        "${rowTitle(match, zone)}. ${rowDetail(match)}." + if (match.isAboveSkyline) "" else " $BEHIND_SKYLINE."

    /** The title of the empty state. */
    fun emptyTitle(reason: AlignmentFinderEmptyReason, request: AlignmentFinderRequest): String = when (reason) {
        AlignmentFinderEmptyReason.NEVER_DARK -> "No darkness in this period"
        AlignmentFinderEmptyReason.ONLY_WITH_MOON -> "Only with the Moon in the way"
        AlignmentFinderEmptyReason.ONLY_IN_LIGHT -> "Only in daylight or twilight"
        AlignmentFinderEmptyReason.NEVER_IN_WINDOW ->
            if (request.isAtSkyline) "Never over this part of the skyline" else "Never in this window"
    }

    /** The explanation of the empty state, over [days] days. */
    fun emptyMessage(reason: AlignmentFinderEmptyReason, request: AlignmentFinderRequest, days: Int): String = when (reason) {
        AlignmentFinderEmptyReason.NEVER_DARK ->
            "At this place the Sun does not get low enough for this darkness setting in the next $days days. Choose " +
                "“Any darkness” or “Any time”."
        AlignmentFinderEmptyReason.ONLY_WITH_MOON ->
            "The target does stand there in the next $days days, but only when the Moon is up or brighter than the " +
                "Moon setting allows. Choose “Any” for the Moon, or a longer period."
        AlignmentFinderEmptyReason.ONLY_IN_LIGHT ->
            "The target does stand there in the next $days days, but only when the sky is lighter than the darkness " +
                "setting allows. Choose “Any darkness” or “Any time”."
        AlignmentFinderEmptyReason.NEVER_IN_WINDOW ->
            if (request.isAtSkyline) {
                "In the next $days days the target never passes through the strip of sky just above the skyline " +
                    "there. Try another direction, a wider tolerance or a longer period."
            } else {
                "In the next $days days the target never passes through this window, in daylight or at night. Try a " +
                    "wider window or a longer period."
            }
    }
}

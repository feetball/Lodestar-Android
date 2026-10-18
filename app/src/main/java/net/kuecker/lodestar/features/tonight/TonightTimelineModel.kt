package net.kuecker.lodestar.features.tonight

import net.kuecker.lodestar.kit.ephemeris.NightTwilight
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.foundation.timeIntervalSince
import net.kuecker.lodestar.kit.planning.NightSummary
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** How much of the Sun's light reaches the sky, from full night to day. */
internal enum class TonightSkyLevel(
    val title: String,
    /** How strongly the timeline fills the level: more for a brighter sky, nothing for full night. */
    val fillOpacity: Double,
) {
    NIGHT("Night", 0.0),
    ASTRONOMICAL("Astronomical", 0.16),
    NAUTICAL("Nautical", 0.36),
    CIVIL("Civil", 0.6),
    DAY("Day", 0.9),
    ;

    companion object {
        /** The level of the sky while the Sun's centre is at [sunAltitude] degrees. */
        operator fun invoke(sunAltitude: Double): TonightSkyLevel = when {
            sunAltitude <= TwilightCalculator.astronomicalAltitude -> NIGHT
            sunAltitude <= TwilightCalculator.nauticalAltitude -> ASTRONOMICAL
            sunAltitude <= TwilightCalculator.civilAltitude -> NAUTICAL
            sunAltitude <= RiseSetCalculator.sunHorizonAltitude -> CIVIL
            else -> DAY
        }
    }
}

/** The stretches of one night that the darkness timeline draws, ready for drawing. */
@ConsistentCopyVisibility
internal data class TonightTimelineModel private constructor(
    /** Sunset to sunrise; the whole night when the Sun does not set or does not rise. */
    val range: DateInterval,
    /** The sky's level through [range], in time order, without gaps. */
    val bands: List<Band>,
    /** When the Moon is above the horizon. */
    val moonUp: List<DateInterval>,
    /** Astronomical darkness with the Moon below the horizon. */
    val dark: List<DateInterval>,
    /** When the Galactic Centre is high enough in the dark. */
    val coreVisible: List<DateInterval>,
    /** The part of [coreVisible] in which the Moon is down too. */
    val coreMoonFree: List<DateInterval>,
    /** Whole hours for the time axis labels, spaced to fit. */
    val hourTicks: List<Instant>,
) {
    /** One stretch of the sky at a single level. */
    data class Band(val interval: DateInterval, val level: TonightSkyLevel)

    companion object {
        /** The timeline of [summary]'s night, with hour ticks on the whole hours of [zone]. */
        operator fun invoke(summary: NightSummary, zone: ZoneId): TonightTimelineModel {
            val twilight = summary.twilight
            val start = twilight.sunset ?: twilight.interval.start
            val end = twilight.sunrise ?: twilight.interval.end
            val range = DateInterval(start, if (end > start) end else start)
            return TonightTimelineModel(
                range = range,
                bands = bands(range, twilight, summary.observer),
                moonUp = clipped(summary.moon.upIntervals, range),
                dark = clipped(summary.darknessIntervals, range),
                coreVisible = clipped(summary.milkyWayCore.visibleIntervals, range),
                coreMoonFree = clipped(summary.milkyWayCore.moonFreeVisibleIntervals, range),
                hourTicks = hourTicks(range, zone),
            )
        }

        /**
         * The sky level between each pair of twilight events, judged by the Sun's altitude in the middle of the
         * stretch so that it is right even where an event never happens (white nights, polar winter).
         */
        private fun bands(range: DateInterval, twilight: NightTwilight, observer: Observer): List<Band> {
            if (range.duration <= 0) return emptyList()
            val events = listOfNotNull(
                twilight.sunset, twilight.civilDusk, twilight.nauticalDusk, twilight.astronomicalDusk,
                twilight.astronomicalDawn, twilight.nauticalDawn, twilight.civilDawn, twilight.sunrise,
            ).filter { it > range.start && it < range.end }.sorted()
            val cuts = listOf(range.start) + events + listOf(range.end)
            val bands = ArrayList<Band>()
            for ((from, to) in cuts.zipWithNext()) {
                if (to <= from) continue
                val middle = from.addingTimeInterval(to.timeIntervalSince(from) / 2)
                val sun = SkyFrame(middle, observer).horizontal(SolarSystemBody.SUN, false)
                val level = TonightSkyLevel(sun.altitude)
                val last = bands.lastOrNull()
                if (last != null && last.level == level) {
                    bands[bands.lastIndex] = Band(DateInterval(last.interval.start, to), level)
                } else {
                    bands.add(Band(DateInterval(from, to), level))
                }
            }
            return bands
        }

        /** The parts of [intervals] that lie inside [range]. */
        private fun clipped(intervals: List<DateInterval>, range: DateInterval): List<DateInterval> =
            intervals.mapNotNull { it.intersection(range) }.filter { it.duration > 0 }

        /**
         * The whole hours inside [range] that get a label: every hour, or every second, third, fourth or sixth
         * hour when the night is long.
         */
        private fun hourTicks(range: DateInterval, zone: ZoneId): List<Instant> {
            val hours = range.duration / 3600
            val spacing = listOf(1, 2, 3, 4).firstOrNull { hours / it <= 7.5 } ?: 6
            val ticks = ArrayList<Instant>()
            var cursor = range.start
            var searched = 0
            while (searched < MAXIMUM_TICK_SEARCH) {
                searched += 1
                val next = cursor.atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant()
                if (next >= range.end) break
                if (next.atZone(zone).hour % spacing == 0) ticks.add(next)
                cursor = next
            }
            return ticks
        }

        /** No night is longer than this many whole hours. */
        private const val MAXIMUM_TICK_SEARCH = 48
    }
}

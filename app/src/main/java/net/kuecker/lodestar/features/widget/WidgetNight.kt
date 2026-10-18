package net.kuecker.lodestar.features.widget

import net.kuecker.lodestar.features.events.EventsCalculator
import net.kuecker.lodestar.features.events.EventsFormat
import net.kuecker.lodestar.features.events.upcomingEvents
import net.kuecker.lodestar.features.tonight.TonightCalculator
import net.kuecker.lodestar.features.tonight.TonightInput
import net.kuecker.lodestar.features.tonight.TonightNightChoice
import net.kuecker.lodestar.features.tonight.TonightNightKey
import net.kuecker.lodestar.kit.ephemeris.MoonPhaseName
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventCalculator
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.events.EventOptions
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.NightSummary
import net.kuecker.lodestar.model.NightModeSetting
import net.kuecker.lodestar.model.ObserverContext
import net.kuecker.lodestar.theme.Palette
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Whether the widgets are drawn in the red night palette, and how bright it is. */
internal data class WidgetAppearance(val isNight: Boolean, val intensity: Double) {
    val palette: Palette get() = if (isNight) Palette.night(intensity) else Palette.normal
}

/** The astronomical darkness of the night. */
internal data class WidgetDarkness(
    /** The stretches with the Sun below −18°, in time order; empty when the Sun never gets that low. */
    val windows: List<DateInterval>,
    val hours: Double,
    /** The hours of [hours] in which the Moon is down as well. */
    val moonFreeHours: Double,
)

/** The Moon on the night: its phase at dusk and when it is up. */
internal data class WidgetMoon(
    val phase: MoonPhaseName,
    val illuminatedFraction: Double,
    val isWaxing: Boolean,
    val isSouthernHemisphere: Boolean,
    val rise: Instant?,
    val set: Instant?,
    val circumstance: RiseTransitSet.Circumstance,
    /** The stretches of the night in which the Moon is above the horizon (above the skyline, with one). */
    val upIntervals: List<DateInterval>,
)

/** The Milky Way's core (the Galactic Centre) on the night. */
internal data class WidgetCore(
    val hasDarkness: Boolean,
    /** When the core is at least [net.kuecker.lodestar.kit.planning.MilkyWayCoreVisibility.minimumAltitude] up in the dark. */
    val visibleWindows: List<DateInterval>,
    val bestTime: Instant?,
    val maxAltitude: Double?,
    val bestAzimuth: Double?,
)

/** The next astronomical event worth a look. */
internal data class WidgetEvent(
    val title: String,
    val kind: EventKind,
    /** When it happens, or for a solar eclipse that can be seen, its maximum here. */
    val date: Instant,
    /** Whether it can be seen from the place; `null` for events that are not seen in the sky (equinoxes, solstices). */
    val isVisibleFromHere: Boolean?,
    /** The stretch around the best view, for events that can be seen. */
    val bestWindow: DateInterval?,
    /** Meteors an hour at the best, for a meteor shower. */
    val showerRate: Int?,
)

/** Tonight, as the widgets show it. */
internal data class WidgetNight(
    val placeName: String,
    val zone: ZoneId,
    val darkness: WidgetDarkness,
    val moon: WidgetMoon,
    val core: WidgetCore,
    val event: WidgetEvent?,
)

/**
 * What a widget draws, worked out at [computedAt]: [night] is `null` when the app has no location yet. [nextRefresh]
 * is the next moment the picture changes by itself, which is when the widgets are redrawn.
 */
internal data class WidgetSnapshot(
    val computedAt: Instant,
    val appearance: WidgetAppearance,
    val night: WidgetNight?,
    val nextRefresh: Instant,
)

/**
 * The astronomy behind the widgets: Tonight's twilight, Moon and Milky Way core from the Tonight calculator (no
 * star catalog), and the next event from the events engine. Pure and offline, so it can run on any thread.
 */
internal object WidgetCalculator {
    /** How far ahead the first look for an event goes, in days. */
    const val EVENT_SPAN_DAYS = 60L

    /** The longer look when nothing comes within [EVENT_SPAN_DAYS]. */
    const val EVENT_FAR_SPAN_DAYS = 366L

    /** A refresh waits this long after the moment it is for, so the sky has surely changed. */
    const val REFRESH_MARGIN_SECONDS = 30L

    /** No refresh is planned further ahead than this; the periodic refresh covers the rest. */
    const val LONGEST_WAIT_HOURS = 12L

    /** Without a location the night palette follows the clock: after this hour (local) and before [MORNING_HOUR]. */
    private const val EVENING_HOUR = 20
    private const val MORNING_HOUR = 6

    /** The widgets for [inputs] at [now]. */
    fun snapshot(inputs: WidgetInputs, now: Instant): WidgetSnapshot {
        val context = inputs.place?.observerContext()
            ?: return WidgetSnapshot(now, appearance(inputs, now, null), null, nextPlacelessRefresh(inputs, now))

        val report = TonightCalculator.report(
            TonightInput(TonightNightKey(context, TonightNightChoice.Tonight), now, null),
        )
        val summary = report.summary
        val zone = context.timeZone
        val event = nextEvent(context, now)
        val night = WidgetNight(
            placeName = context.name,
            zone = zone,
            darkness = darkness(summary),
            moon = moon(summary),
            core = core(summary),
            event = event,
        )
        val appearance = appearance(inputs, now, context.observer)
        val followsSun = inputs.nightMode == NightModeSetting.AUTOMATIC
        val moments = transitions(summary, report.expiry, event, followsSun, now, zone)
        return WidgetSnapshot(now, appearance, night, nextRefresh(now, moments))
    }

    /** Whether the red night palette is in effect for [inputs] at [now], by the app's rule (night mode setting, else the Sun). */
    fun appearance(inputs: WidgetInputs, now: Instant, observer: Observer?): WidgetAppearance {
        val isNight = when (inputs.nightMode) {
            NightModeSetting.ON -> true
            NightModeSetting.OFF -> false
            NightModeSetting.AUTOMATIC -> if (observer != null) {
                SkyFrame(now, observer).horizontal(SolarSystemBody.SUN, false).altitude < TwilightCalculator.civilAltitude
            } else {
                val hour = ZonedDateTime.ofInstant(now, ZoneId.systemDefault()).hour
                hour >= EVENING_HOUR || hour < MORNING_HOUR
            }
        }
        return WidgetAppearance(isNight, inputs.nightIntensity)
    }

    // The night

    private fun darkness(summary: NightSummary) = WidgetDarkness(
        windows = summary.astronomicalDarknessIntervals,
        hours = summary.astronomicalDarkHours,
        moonFreeHours = summary.moonFreeDarkHours,
    )

    private fun moon(summary: NightSummary): WidgetMoon {
        val moon = summary.moon
        val phase = moon.phase
        return WidgetMoon(
            phase = phase.name,
            illuminatedFraction = phase.illuminatedFraction,
            isWaxing = phase.isWaxing,
            isSouthernHemisphere = summary.observer.location.latitude < 0,
            rise = moon.riseTransitSet.rise,
            set = moon.riseTransitSet.set,
            circumstance = moon.riseTransitSet.circumstance,
            upIntervals = moon.upIntervals,
        )
    }

    private fun core(summary: NightSummary): WidgetCore {
        val core = summary.milkyWayCore
        return WidgetCore(
            hasDarkness = summary.hasAstronomicalDarkness,
            visibleWindows = core.visibleIntervals,
            bestTime = core.bestTime,
            maxAltitude = core.maxAltitude,
            bestAzimuth = core.bestAzimuth,
        )
    }

    // The next event

    /**
     * The first event from [now] on that the Events card would list, preferring one that can be seen from here;
     * looks [EVENT_SPAN_DAYS] days ahead, and a year when that finds nothing.
     */
    fun nextEvent(context: ObserverContext, now: Instant): WidgetEvent? {
        val options = EventOptions(
            timeZone = context.timeZone,
            planetConjunctionThreshold = EventsCalculator.CONJUNCTION_THRESHOLD,
            moonConjunctionThreshold = EventsCalculator.CONJUNCTION_THRESHOLD,
        )
        for (days in listOf(EVENT_SPAN_DAYS, EVENT_FAR_SPAN_DAYS)) {
            val events = EventCalculator.events(now, now.plus(days, ChronoUnit.DAYS), context.observer, options)
            val upcoming = upcomingEvents(events, now)
            val chosen = upcoming.firstOrNull { EventsFormat.isVisibleFromHere(it) != false } ?: upcoming.firstOrNull()
            if (chosen != null) return widgetEvent(chosen)
        }
        return null
    }

    private fun widgetEvent(event: AstronomicalEvent): WidgetEvent {
        val visibility = event.visibility
        return WidgetEvent(
            title = event.title,
            kind = event.kind,
            date = EventsFormat.displayDate(event),
            isVisibleFromHere = visibility?.isObservable,
            bestWindow = visibility?.bestWindow,
            showerRate = (event.details as? EventDetails.MeteorShower)?.peak?.shower?.zhr,
        )
    }

    // Refreshes

    /**
     * The moments after which the picture is different: darkness, the Moon and the core starting or ending, dusk and
     * dawn (the night palette, when it follows the Sun), sunrise (tonight turns into the next night), the event
     * passing, and midnight when it changes how far away the event is said to be. Intervals are cut off at the ends of
     * the night, and those cuts are no changes of the sky, so they are left out.
     */
    fun transitions(
        summary: NightSummary,
        expiry: Instant,
        event: WidgetEvent?,
        followsSun: Boolean,
        now: Instant,
        zone: ZoneId,
    ): List<Instant> {
        val twilight = summary.twilight
        val night = summary.night
        val moments = ArrayList<Instant>()
        moments.add(expiry)
        moments.addAll(listOfNotNull(twilight.astronomicalDusk, twilight.astronomicalDawn))
        if (followsSun) moments.addAll(listOfNotNull(twilight.civilDusk, twilight.civilDawn))
        val intervals = summary.astronomicalDarknessIntervals + summary.moon.upIntervals + summary.milkyWayCore.visibleIntervals
        for (interval in intervals) {
            for (moment in listOf(interval.start, interval.end)) {
                if (moment > night.start && moment < night.end) moments.add(moment)
            }
        }
        if (event != null) {
            moments.add(event.date)
            val midnight = nextMidnight(now, zone)
            if (EventsFormat.distance(event.date, midnight, zone) != EventsFormat.distance(event.date, now, zone)) {
                moments.add(midnight)
            }
        }
        return moments
    }

    private fun nextMidnight(now: Instant, zone: ZoneId): Instant =
        now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()

    /**
     * When to redraw: the first of [moments] after [now], and never later than [LONGEST_WAIT_HOURS] ahead.
     * [REFRESH_MARGIN_SECONDS] are added so that the sky has surely changed by then.
     */
    fun nextRefresh(now: Instant, moments: List<Instant>): Instant {
        val limit = now.plus(LONGEST_WAIT_HOURS, ChronoUnit.HOURS)
        val next = moments.filter { it > now }.minOrNull()
        return (if (next != null && next < limit) next else limit).plusSeconds(REFRESH_MARGIN_SECONDS)
    }

    /** Without a place there is only the clock: the next time the night palette may change, if it follows the Sun at all. */
    private fun nextPlacelessRefresh(inputs: WidgetInputs, now: Instant): Instant {
        if (inputs.nightMode != NightModeSetting.AUTOMATIC) return nextRefresh(now, emptyList())
        val zone = ZoneId.systemDefault()
        val today = now.atZone(zone).toLocalDate()
        val moments = listOf(
            today.atTime(MORNING_HOUR, 0).atZone(zone).toInstant(),
            today.atTime(EVENING_HOUR, 0).atZone(zone).toInstant(),
            today.plusDays(1).atTime(MORNING_HOUR, 0).atZone(zone).toInstant(),
        )
        return nextRefresh(now, moments)
    }
}

package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * The astronomical events calendar: meteor shower maxima, conjunctions, oppositions, greatest elongations, the
 * Earth's apsides, equinoxes and solstices, special full Moons and lunar and solar eclipses, computed offline from the
 * kit's ephemerides.
 *
 * Every search is bounded: intervals longer than [maximumSpanDays] (about ten years) are cut short, dates outside the
 * years 1 to 4001 give nothing, and the work grows with the length of the interval. Cost of one year on a desktop JVM
 * once warm: about 0.1 s without an observer and 0.2 s with one (the conjunctions take half of it; the visibility of an event
 * a fraction of a millisecond); the first call of a process needs about 1 s for class loading and the planetary series.
 * Nothing is cached between calls. Run it on a background thread and keep the result.
 */
object EventCalculator {
    /** The longest interval, in days, that [events] searches; longer intervals are cut at this length. */
    const val maximumSpanDays: Double = EventSearch.maximumSpanDays

    /**
     * The events from [from] to [to] (both inclusive) of the kinds in [options], sorted by date (ties by kind).
     *
     * With an [observer] each event carries its [EventVisibility] (except equinoxes, solstices and apsides) and solar
     * eclipses carry their local circumstances; without one `visibility` is `null` and solar eclipses are global.
     * [EventOptions.timeZone] decides the calendar months of blue moons and the nights of the viewing windows.
     * Returns an empty list for a reversed or non-finite interval.
     */
    fun events(
        from: Instant,
        to: Instant,
        observer: Observer? = null,
        options: EventOptions = EventOptions(),
    ): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val end = EventSearch.limitedEnd(from, to)
        val zone = options.timeZone ?: EventVisibilityCalculator.defaultZone(observer)
        val kinds = options.kinds
        val found = ArrayList<AstronomicalEvent>()
        if (EventKind.METEOR_SHOWER in kinds) found.addAll(MeteorShowerFinder.events(from, end))
        if (EventKind.CONJUNCTION in kinds) found.addAll(ConjunctionFinder.events(from, end, observer, options))
        if (EventKind.OPPOSITION in kinds) found.addAll(OppositionFinder.events(from, end))
        if (EventKind.GREATEST_ELONGATION in kinds) found.addAll(GreatestElongationFinder.events(from, end))
        if (EventKind.EARTH_APSIS in kinds) found.addAll(EarthApsisFinder.events(from, end))
        if (EventKind.SEASON in kinds) found.addAll(SeasonFinder.events(from, end))
        if (EventKind.FULL_MOON_SPECIAL in kinds || EventKind.LUNAR_ECLIPSE in kinds || EventKind.SOLAR_ECLIPSE in kinds) {
            val phases = PhaseTable(
                if (EventKind.FULL_MOON_SPECIAL in kinds) FullMoonSpecialFinder.monthStart(from, zone) else from, end,
            )
            if (EventKind.FULL_MOON_SPECIAL in kinds) found.addAll(FullMoonSpecialFinder.events(from, end, zone, phases))
            if (EventKind.LUNAR_ECLIPSE in kinds) found.addAll(LunarEclipseFinder.events(from, end, phases))
            if (EventKind.SOLAR_ECLIPSE in kinds) found.addAll(SolarEclipseFinder.events(from, end, observer, phases))
        }

        val calculator = if (observer != null) EventVisibilityCalculator(observer, options, zone) else null
        val result = ArrayList<AstronomicalEvent>()
        for (event in found.sortedWith(compareBy({ it.date }, { it.kind.ordinal }))) {
            val visibility = calculator?.visibility(event)
            if (options.visibleOnly && visibility != null && !visibility.isObservable) continue
            result.add(if (visibility != null) event.copy(visibility = visibility) else event)
        }
        return result
    }

    /**
     * How well [observer] can see [event], computed from scratch (a few milliseconds); `null` for equinoxes, solstices
     * and apsides. Use it for an event that was computed without an observer.
     */
    fun visibility(event: AstronomicalEvent, observer: Observer, options: EventOptions = EventOptions()): EventVisibility? {
        val zone = options.timeZone ?: EventVisibilityCalculator.defaultZone(observer)
        return EventVisibilityCalculator(observer, options, zone).visibility(event)
    }
}

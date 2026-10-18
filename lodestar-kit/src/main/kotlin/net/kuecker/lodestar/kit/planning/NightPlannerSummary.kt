package net.kuecker.lodestar.kit.planning

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneId

/**
 * Summarises the night that starts on a local date: twilight, the Moon, the dark hours, the Milky
 * Way's core and the planets.
 *
 * The night runs from local noon of the date to local noon of the next day, so one summary covers an
 * evening and the morning after it. Darkness means astronomical darkness (the Sun more than 18° below
 * the horizon), reported with and without the Moon above the horizon. The Moon's phase and the
 * planets' magnitudes are evaluated at astronomical dusk ([NightSummary.referenceDate]). A summary
 * takes a few milliseconds and shares no state, so it can be computed on any thread.
 *
 * @param localDate Any instant on the desired date in [timeZone].
 * @param observer The place of observation.
 * @param timeZone The time zone that defines the local date and noon.
 */
fun NightPlanner.summary(localDate: Instant, observer: Observer, timeZone: ZoneId): NightSummary {
    val twilight = TwilightCalculator.night(localDate, observer, timeZone)
    val night = twilight.interval
    val astronomicalNight = twilight.astronomicalNight
    val astronomical = IntervalSet.normalized(if (astronomicalNight != null) listOf(astronomicalNight) else emptyList())
    val referenceDate = twilight.astronomicalDusk ?: twilight.solarMidnight

    val moon = moonSummary(observer, night, referenceDate)
    val sampler = DarknessSampler(observer, astronomical)
    val moonFree = IntervalSet.subtracting(moon.upIntervals, astronomical)
    val referenceTime = JulianDate(referenceDate)
    return NightSummary(
        observer = observer,
        night = night,
        twilight = twilight,
        referenceDate = referenceDate,
        moon = moon,
        astronomicalDarknessIntervals = astronomical,
        darknessIntervals = moonFree,
        milkyWayCore = milkyWayCore(observer, night, sampler, moonFree),
        planets = SolarSystemBody.entries.filter { it.isPlanet }.map {
            planetVisibility(it, observer, night, sampler, referenceTime)
        },
    )
}

// MARK: Moon

private fun moonSummary(observer: Observer, night: DateInterval, referenceDate: Instant): MoonSummary {
    val events = RiseSetCalculator.riseTransitSet(SolarSystemBody.MOON, observer, night)
    val crossings = RiseSetCalculator.crossings(SolarSystemBody.MOON, observer, night)
    return MoonSummary(
        phase = MoonPhaseCalculator.info(referenceDate),
        riseTransitSet = events,
        upIntervals = NightPlanner.upIntervals(crossings, events.circumstance, night),
    )
}

/**
 * The stretches of [window] in which a body is above the horizon, given all its horizon crossings
 * there. With no crossing the body is up throughout or not at all, as [circumstance] says;
 * otherwise it starts the window up when the first crossing is a setting.
 */
internal fun NightPlanner.upIntervals(
    crossings: List<AltitudeCrossing>,
    circumstance: RiseTransitSet.Circumstance,
    window: DateInterval,
): List<DateInterval> {
    var risenAt: Instant? = null
    val first = crossings.firstOrNull()
    if (first != null) {
        if (first.kind == AltitudeCrossing.Kind.SETTING) risenAt = window.start
    } else if (circumstance == RiseTransitSet.Circumstance.ALWAYS_UP) {
        risenAt = window.start
    }
    val intervals = ArrayList<DateInterval>()
    for (crossing in crossings) {
        when (crossing.kind) {
            AltitudeCrossing.Kind.RISING -> risenAt = crossing.time
            AltitudeCrossing.Kind.SETTING -> {
                val start = risenAt
                if (start != null) intervals.add(DateInterval(start, crossing.time))
                risenAt = null
            }
        }
    }
    val start = risenAt
    if (start != null) intervals.add(DateInterval(start, window.end))
    return IntervalSet.normalized(intervals)
}

// MARK: Milky Way core and planets

private fun milkyWayCore(
    observer: Observer,
    night: DateInterval,
    sampler: DarknessSampler,
    moonFree: List<DateInterval>,
): MilkyWayCoreVisibility {
    val core = MilkyWayCoreVisibility.galacticCentre
    val direction = core.unitVector
    val profile = sampler.profile { it.horizontalVector(direction).z }
    val visible = profile.intervals(AstroMath.sinDeg(MilkyWayCoreVisibility.minimumAltitude))
    val moonFreeVisible = IntervalSet.intersection(visible, moonFree)
    val best = profile.maximum(if (moonFreeVisible.isEmpty()) visible else moonFreeVisible)
    val bestAzimuth = best?.let {
        SkyFrame(it.date, observer).horizontal(core, refraction = false).azimuth
    }
    return MilkyWayCoreVisibility(
        riseTransitSet = RiseSetCalculator.riseTransitSet(position = core, observer = observer, interval = night),
        visibleIntervals = visible,
        moonFreeVisibleIntervals = moonFreeVisible,
        bestTime = best?.date,
        maxAltitude = best?.let { AstroMath.asinDeg(it.sine) },
        bestAzimuth = bestAzimuth,
    )
}

private fun planetVisibility(
    body: SolarSystemBody,
    observer: Observer,
    night: DateInterval,
    sampler: DarknessSampler,
    referenceTime: JulianDate,
): PlanetVisibility {
    val profile = sampler.profile(body)
    val peak = profile.maximum(profile.darkness)
    return PlanetVisibility(
        body = body,
        riseTransitSet = RiseSetCalculator.riseTransitSet(body, observer, night),
        visibleIntervals = profile.intervals(AstroMath.sinDeg(PlanetVisibility.minimumAltitude)),
        maxAltitudeDuringDarkness = peak?.let { AstroMath.asinDeg(it.sine) },
        magnitude = Ephemeris.state(body, referenceTime).apparentMagnitude,
    )
}

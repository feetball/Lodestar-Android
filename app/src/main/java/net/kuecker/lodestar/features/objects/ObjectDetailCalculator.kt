package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.AngularSize
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.apparentEquatorial
import net.kuecker.lodestar.kit.catalog.currentAngularSize
import net.kuecker.lodestar.kit.catalog.currentMagnitude
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.catalog.riseTransitSet
import net.kuecker.lodestar.kit.ephemeris.MoonPhaseCalculator
import net.kuecker.lodestar.kit.ephemeris.MoonPhaseInfo
import net.kuecker.lodestar.kit.ephemeris.NightTwilight
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.Refraction
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.phototools.ExposureCalculator
import net.kuecker.lodestar.kit.phototools.ExposureRule
import net.kuecker.lodestar.kit.phototools.FieldOfView
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** The astronomy behind the object detail screen. Pure, so it can run on any thread. */
internal object ObjectDetailCalculator {
    /** The spacing of the altitude curve's samples, in seconds. */
    const val SAMPLE_STEP = 600.0

    /** Computes the current state of an object and the night around [ObjectDetailInput.date]. */
    fun snapshot(input: ObjectDetailInput): ObjectDetailSnapshot {
        val observer = input.context.observer
        val reusableNight = input.previousNight?.takeIf { it.expiry > input.date }
        val tonight = reusableNight ?: night(input.obj, input.context, input.date)
        return ObjectDetailSnapshot(
            current = current(input.obj, observer, input.date),
            night = tonight,
            moon = moonConditions(input.obj, observer, tonight, input.date),
        )
    }

    // Now

    /** Where the object is, how bright, how large and how lit at [date]. */
    fun current(obj: CelestialObject, observer: Observer, date: Instant): ObjectDetailCurrent {
        val frame = SkyFrame(date, observer)
        var distance: ObjectDetailDistance? = obj.distanceLightYears?.let { ObjectDetailDistance.LightYears(it) }
        var illuminatedFraction: Double? = null
        var moonPhase: MoonPhaseInfo? = null
        val position = obj.position
        if (position is ObjectPosition.SolarSystem) {
            val state = frame.state(position.body)
            val isMoon = position.body == SolarSystemBody.MOON
            distance = if (isMoon) {
                ObjectDetailDistance.Kilometers(state.distanceKilometers)
            } else {
                ObjectDetailDistance.AstronomicalUnits(state.distanceAU)
            }
            illuminatedFraction = state.illuminatedFraction
            moonPhase = if (isMoon) MoonPhaseCalculator.info(date) else null
        }
        return ObjectDetailCurrent(
            date = date,
            horizontal = frame.horizontal(obj, true),
            equatorial = frame.apparentEquatorial(obj),
            magnitude = frame.currentMagnitude(obj),
            angularSize = frame.currentAngularSize(obj),
            distance = distance,
            illuminatedFraction = illuminatedFraction,
            moonPhase = moonPhase,
        )
    }

    // Tonight

    /** The night that is under way at [date], or the coming one when it is day. */
    fun night(obj: CelestialObject, context: ObserverContext, date: Instant): ObjectDetailNight {
        val observer = context.observer
        val twilight = currentTwilight(context, date)
        val interval = twilight.interval
        val chartStart = twilight.sunset ?: interval.start
        val chartEnd = maxOf(twilight.sunrise ?: interval.end, chartStart)
        val chartRange = DateInterval(chartStart, chartEnd)

        val events = RiseSetCalculator.riseTransitSet(obj, observer, interval)
        val samples = altitudeSamples(obj, observer, chartRange)
        val darkness = twilight.astronomicalNight
        return ObjectDetailNight(
            interval = interval,
            chartRange = chartRange,
            twilight = twilight,
            darkness = darkness,
            events = events,
            transitAltitude = events.transitAltitude?.let { Refraction.apparentAltitude(it, observer.atmosphere) },
            samples = samples,
            twilightBands = twilightBands(twilight, chartRange),
            hourTicks = hourTicks(chartRange, context.timeZone),
            bestInDarkness = bestPoint(obj, observer, samples, darkness, events.transit),
        )
    }

    /**
     * The twilight of the night that contains [date]: the one that began at noon yesterday while it is still
     * before sunrise, otherwise the one that begins at noon today.
     */
    private fun currentTwilight(context: ObserverContext, date: Instant): NightTwilight {
        val yesterday = date.atZone(context.timeZone).minusDays(1).toInstant()
        val previous = TwilightCalculator.night(yesterday, context.observer, context.timeZone)
        if (date < previous.interval.end && date < (previous.sunrise ?: previous.interval.end)) return previous
        return TwilightCalculator.night(date, context.observer, context.timeZone)
    }

    private fun apparentAltitude(obj: CelestialObject, observer: Observer, date: Instant): Double =
        SkyFrame(date, observer).horizontal(obj, true).altitude

    private fun altitudeSamples(obj: CelestialObject, observer: Observer, range: DateInterval): List<ObjectDetailAltitudeSample> {
        val gaps = if (range.duration > 0) max(1, ceil(range.duration / SAMPLE_STEP).toInt()) else 0
        return (0..gaps).map { step ->
            val date = if (step == gaps) range.end else range.start.addingTimeInterval(step * SAMPLE_STEP)
            ObjectDetailAltitudeSample(date, apparentAltitude(obj, observer, date))
        }
    }

    /**
     * The highest apparent altitude during [darkness], looked for at the samples, the ends of the darkness and the
     * transit, so that a culmination between two samples is not missed.
     */
    private fun bestPoint(
        obj: CelestialObject,
        observer: Observer,
        samples: List<ObjectDetailAltitudeSample>,
        darkness: DateInterval?,
        transit: Instant?,
    ): ObjectDetailPeak? {
        if (darkness == null) return null
        val candidates = samples.filter { it.date in darkness }
            .mapTo(ArrayList()) { ObjectDetailPeak(it.date, it.altitude) }
        val extraDates = mutableListOf(darkness.start, darkness.end)
        if (transit != null && transit in darkness) extraDates.add(transit)
        extraDates.mapTo(candidates) { ObjectDetailPeak(it, apparentAltitude(obj, observer, it)) }
        return candidates.maxByOrNull { it.altitude }
    }

    /** Hourly labels for the time axis: every hour, every two hours or every four, depending on the length. */
    private fun hourTicks(range: DateInterval, zone: ZoneId): List<Instant> {
        val spacing = when {
            range.duration > 18 * 3600 -> 4
            range.duration > 9 * 3600 -> 2
            else -> 1
        }
        val ticks = ArrayList<Instant>()
        var cursor: ZonedDateTime = range.start.atZone(zone)
        repeat(MAXIMUM_TICKS) {
            // The next whole local hour after the cursor.
            val next = cursor.truncatedTo(ChronoUnit.HOURS).plusHours(1)
            if (next.toInstant() > range.end) return ticks
            if (next.hour % spacing == 0) ticks.add(next.toInstant())
            cursor = next
        }
        return ticks
    }

    // Twilight shading

    /**
     * The stretches of the chart in which the Sun lights the sky: daylight and civil, nautical and astronomical
     * twilight. Full darkness has no band. Works at every latitude, including where the Sun does not set.
     */
    private fun twilightBands(twilight: NightTwilight, range: DateInterval): List<ObjectDetailTwilightBand> {
        /** The time the Sun is below [threshold], or `null` if it never gets that low during the night. */
        fun below(threshold: Double, evening: Instant?, morning: Instant?): DateInterval? {
            if (!(twilight.sunAltitudeAtMidnight < threshold)) return null
            val start = evening ?: twilight.interval.start
            val end = morning ?: twilight.interval.end
            return if (end > start) DateInterval(start, end) else null
        }
        val horizon = below(RiseSetCalculator.sunHorizonAltitude, twilight.sunset, twilight.sunrise)
        val civil = below(TwilightCalculator.civilAltitude, twilight.civilDusk, twilight.civilDawn)
        val nautical = below(TwilightCalculator.nauticalAltitude, twilight.nauticalDusk, twilight.nauticalDawn)
        val astronomical = below(TwilightCalculator.astronomicalAltitude, twilight.astronomicalDusk, twilight.astronomicalDawn)

        val regions = listOf(
            4 to remove(horizon, listOf(range)),
            3 to remove(civil, listOfNotNull(horizon)),
            2 to remove(nautical, listOfNotNull(civil)),
            1 to remove(astronomical, listOfNotNull(nautical)),
        )
        return regions.flatMap { (brightness, intervals) ->
            intervals.mapNotNull { interval ->
                val visible = interval.intersection(range)
                if (visible == null || visible.duration <= 0) null else ObjectDetailTwilightBand(visible, brightness)
            }
        }
    }

    /** The parts of [intervals] not covered by [removed]. */
    private fun remove(removed: DateInterval?, intervals: List<DateInterval>): List<DateInterval> {
        if (removed == null) return intervals
        return intervals.flatMap { interval ->
            val pieces = ArrayList<DateInterval>(2)
            if (removed.start > interval.start) pieces.add(DateInterval(interval.start, minOf(removed.start, interval.end)))
            if (removed.end < interval.end) pieces.add(DateInterval(maxOf(removed.end, interval.start), interval.end))
            pieces
        }
    }

    // Photography

    /** How the object fits the lens, and the exposure limits for its declination. */
    fun photography(obj: CelestialObject, size: AngularSize?, declination: Double?, lens: LensSetup): ObjectDetailPhotography {
        val frame = FieldOfView.of(lens.sensor, lens.focalLengthMillimeters)
        val framing = size
            ?.let { frame.framing(it.majorArcminutes, it.minorArcminutes ?: it.majorArcminutes) }
            ?.let { ObjectDetailFraming(it.majorAxisFraction, it.minorAxisFraction) }
        val tracksStars = obj.category != ObjectCategory.SUN && obj.category != ObjectCategory.MOON
        fun exposure(rule: ExposureRule) =
            if (tracksStars) ExposureCalculator.maxExposure(rule, lens, declination) else null
        return ObjectDetailPhotography(frame, framing, npf = exposure(ExposureRule.NPF), rule500 = exposure(ExposureRule.RULE500))
    }

    /**
     * How the Moon affects the object: now while the sky is dark, otherwise at the object's best time tonight.
     *
     * The impact is a rule of thumb from the Moon's illuminated fraction and its distance from the object.
     */
    fun moonConditions(obj: CelestialObject, observer: Observer, night: ObjectDetailNight, now: Instant): ObjectDetailMoonConditions? {
        if (obj.category == ObjectCategory.SUN || obj.category == ObjectCategory.MOON) return null
        val isDark = night.darkness?.let { now in it } ?: false
        val date = if (isDark) now else (night.bestInDarkness?.date ?: now)
        val frame = SkyFrame(date, observer)
        val moon = frame.horizontal(SolarSystemBody.MOON, false)
        val separation = frame.horizontal(obj, false).angularSeparation(moon)
        val illuminatedFraction = frame.state(SolarSystemBody.MOON).illuminatedFraction ?: 0.0
        val impact = moonImpact(moon.altitude, separation, illuminatedFraction)
        return ObjectDetailMoonConditions(date, date == now, separation, illuminatedFraction, impact)
    }

    private fun moonImpact(moonAltitude: Double, separation: Double, illuminatedFraction: Double): ObjectDetailMoonConditions.Impact {
        if (!(moonAltitude > 0)) return ObjectDetailMoonConditions.Impact.MOON_DOWN
        // Moonlight scatters across the whole sky but is strongest near the Moon.
        val proximity = min(max(1 - separation / 120, 0.25), 1.0)
        val effect = illuminatedFraction * proximity
        return when {
            effect >= 0.5 -> ObjectDetailMoonConditions.Impact.SEVERE
            effect >= 0.2 -> ObjectDetailMoonConditions.Impact.MODERATE
            else -> ObjectDetailMoonConditions.Impact.MINOR
        }
    }

    /** The most hour labels the time axis gets. */
    private const val MAXIMUM_TICKS = 48
}

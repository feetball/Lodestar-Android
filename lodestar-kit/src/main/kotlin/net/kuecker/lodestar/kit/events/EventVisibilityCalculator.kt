package net.kuecker.lodestar.kit.events

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** How dark the sky must be for the viewing window of an event; the values are the Sun's altitude limits. */
internal enum class DarknessLevel(val altitude: Double) {
    /** After sunset (the Sun's centre below −0.8333°). */
    SUNSET(RiseSetCalculator.sunHorizonAltitude),

    /** After the end of civil twilight (below −6°). */
    CIVIL(TwilightCalculator.civilAltitude),

    /** After the end of astronomical twilight (below −18°). */
    ASTRONOMICAL(TwilightCalculator.astronomicalAltitude),
    ;

    /** The part of [night] in which the Sun is below [altitude]; `null` when it never is. */
    fun interval(night: NightTwilight): DateInterval? {
        val dusk: Instant?
        val dawn: Instant?
        when (this) {
            SUNSET -> {
                dusk = night.sunset
                dawn = night.sunrise
            }
            CIVIL -> {
                dusk = night.civilDusk
                dawn = night.civilDawn
            }
            ASTRONOMICAL -> {
                dusk = night.astronomicalDusk
                dawn = night.astronomicalDawn
            }
        }
        if (dusk != null && dawn != null) return if (dawn > dusk) DateInterval(dusk, dawn) else null
        if (dusk != null) return DateInterval(dusk, night.interval.end)
        if (dawn != null) return DateInterval(night.interval.start, dawn)
        return if (night.sunAltitudeAtMidnight < altitude) night.interval else null
    }
}

/**
 * Works out how well one observer can see events. Holds the nights it has already computed, so create one per run and
 * do not share it between threads.
 */
internal class EventVisibilityCalculator(
    private val observer: Observer,
    private val options: EventOptions,
    private val timeZone: ZoneId,
) {
    private val nights = HashMap<Instant, NightTwilight>()

    /** The visibility of [event] for the observer, or `null` for events that are not seen in the sky. */
    fun visibility(event: AstronomicalEvent): EventVisibility? =
        when (val details = event.details) {
            is EventDetails.MeteorShower -> {
                val position = details.peak.radiant
                night(event.date, DarknessLevel.ASTRONOMICAL) { it.horizontal(position, null, false) }
            }
            is EventDetails.Conjunction -> conjunction(event.date, details.conjunction)
            is EventDetails.Opposition -> planet(event.date, details.opposition.planet)
            is EventDetails.GreatestElongation -> planet(event.date, details.elongation.planet)
            is EventDetails.FullMoonSpecial ->
                night(event.date, DarknessLevel.SUNSET) { it.horizontal(SolarSystemBody.MOON, false) }
            is EventDetails.LunarEclipse -> lunarEclipse(details.eclipse)
            is EventDetails.SolarEclipse -> solarEclipse(details.eclipse)
            is EventDetails.EarthApsis -> null
            is EventDetails.Season -> null
        }

    private fun planet(date: Instant, body: SolarSystemBody): EventVisibility =
        night(date, DarknessLevel.CIVIL, fixed(ConjunctionTarget.Body(body), JulianDate(date)))

    private fun conjunction(date: Instant, conjunction: Conjunction): EventVisibility {
        val jd = JulianDate(date)
        if (conjunction.involvesMoon) return night(date, DarknessLevel.CIVIL, fixed(conjunction.second, jd))
        val first = fixed(conjunction.first, jd)
        val second = fixed(conjunction.second, jd)
        return night(date, DarknessLevel.CIVIL) { HorizontalCoordinate(first(it).unitVector + second(it).unitVector) }
    }

    /** The place of a planet or star as it is at [jd]; planets keep their position for the whole night. */
    private fun fixed(target: ConjunctionTarget, jd: JulianDate): (SkyFrame) -> HorizontalCoordinate =
        when (target) {
            is ConjunctionTarget.Body -> {
                val vector = Ephemeris.geocentricJ2000(target.body, jd)
                val place: (SkyFrame) -> HorizontalCoordinate = { frame -> frame.horizontal(vector, false) }
                place
            }
            is ConjunctionTarget.Star -> {
                val position = target.star.position
                val motion = target.star.properMotion
                val place: (SkyFrame) -> HorizontalCoordinate = { frame -> frame.horizontal(position, motion, false) }
                place
            }
        }

    /** The visibility of a subject that stays up through the night, with the window inside the dark part of the night. */
    private fun night(date: Instant, level: DarknessLevel, position: (SkyFrame) -> HorizontalCoordinate): EventVisibility {
        val frame = SkyFrame(date, observer)
        val subject = position(frame)
        val sunAltitude = frame.horizontal(SolarSystemBody.SUN, false).altitude
        var window: DateInterval? = null
        var bestTime: Instant? = null
        val dark = level.interval(twilight(date))
        if (dark != null) {
            val result = AltitudeWindow.best(
                EventSearch.ut(dark.start), EventSearch.ut(dark.end), windowStep, options.minimumAltitude,
            ) { position(SkyFrame(JulianDate(it), observer)).altitude }
            if (result != null) {
                window = DateInterval(EventSearch.instant(result.start), EventSearch.instant(result.end))
                bestTime = EventSearch.instant(result.peakTime)
            }
        }
        return EventVisibility(
            altitude = subject.altitude,
            azimuth = subject.azimuth,
            sunAltitude = sunAltitude,
            isDark = sunAltitude < TwilightCalculator.astronomicalAltitude,
            bestWindow = window,
            bestTime = bestTime,
            darknessLimit = level.altitude,
            contacts = emptyList(),
        )
    }

    private fun lunarEclipse(eclipse: LunarEclipse): EventVisibility {
        val horizon = RiseSetCalculator.standardHorizonAltitude
        fun moon(time: Instant): HorizontalCoordinate = SkyFrame(time, observer).horizontal(SolarSystemBody.MOON, false)
        val contacts = eclipse.contacts.map {
            val place = moon(it.time)
            ContactVisibility(it.contact, it.time, place.altitude, place.azimuth)
        }
        val first = eclipse.u1 ?: eclipse.p1
        val last = eclipse.u4 ?: eclipse.p4
        val result = AltitudeWindow.best(EventSearch.ut(first), EventSearch.ut(last), eclipseStep, horizon) {
            SkyFrame(JulianDate(it), observer).horizontal(SolarSystemBody.MOON, false).altitude
        }
        var window: DateInterval? = null
        var bestTime: Instant? = null
        if (result != null) {
            window = DateInterval(EventSearch.instant(result.start), EventSearch.instant(result.end))
            val greatest = EventSearch.ut(eclipse.greatest)
            bestTime = if (greatest >= result.start && greatest <= result.end) eclipse.greatest else EventSearch.instant(result.peakTime)
        }
        val atGreatest = moon(eclipse.greatest)
        val sunAltitude = SkyFrame(eclipse.greatest, observer).horizontal(SolarSystemBody.SUN, false).altitude
        return EventVisibility(
            altitude = atGreatest.altitude,
            azimuth = atGreatest.azimuth,
            sunAltitude = sunAltitude,
            isDark = sunAltitude < TwilightCalculator.astronomicalAltitude,
            bestWindow = window,
            bestTime = bestTime,
            darknessLimit = 0.0,
            contacts = contacts,
        )
    }

    private fun solarEclipse(eclipse: SolarEclipse): EventVisibility {
        val local = eclipse.local ?: SolarEclipseFinder.localCircumstances(eclipse, observer)
        val reference = local.maximum ?: eclipse.greatest
        fun sun(time: Instant): HorizontalCoordinate = SkyFrame(time, observer).horizontal(SolarSystemBody.SUN, false)
        val contacts = ArrayList<ContactVisibility>()
        for (entry in listOf(
            EclipseContact.C1 to local.c1, EclipseContact.C2 to local.c2, EclipseContact.MAXIMUM to local.maximum,
            EclipseContact.C3 to local.c3, EclipseContact.C4 to local.c4,
        )) {
            val time = entry.second ?: continue
            val place = sun(time)
            contacts.add(ContactVisibility(entry.first, time, place.altitude, place.azimuth))
        }
        val place = sun(reference)
        return EventVisibility(
            altitude = place.altitude,
            azimuth = place.azimuth,
            sunAltitude = place.altitude,
            isDark = place.altitude < TwilightCalculator.astronomicalAltitude,
            bestWindow = local.visibleInterval,
            bestTime = if (local.visibleInterval != null) local.maximum else null,
            darknessLimit = 0.0,
            contacts = contacts,
        )
    }

    /**
     * The night (local noon to noon) that contains [date]. The day is decided by comparing [date] with its own local noon
     * rather than by shifting 12 absolute hours, which lands an hour off on the 23- and 25-hour days of a time change.
     */
    private fun twilight(date: Instant): NightTwilight {
        val own = TwilightCalculator.nightInterval(date, timeZone)
        val reference = if (date >= own.start) {
            date
        } else {
            runCatching { date.atZone(timeZone).minusDays(1).toInstant() }.getOrElse { date.addingTimeInterval(-86_400.0) }
        }
        val key = TwilightCalculator.nightInterval(reference, timeZone).start
        return nights.getOrPut(key) { TwilightCalculator.night(reference, observer, timeZone) }
    }

    companion object {
        private const val windowStep: Double = 15.0 / 1440
        private const val eclipseStep: Double = 5.0 / 1440

        /** The zone used when the options name none: UTC without an observer, else the whole-hour offset nearest the longitude. */
        fun defaultZone(observer: Observer?): ZoneId {
            if (observer == null) return ZoneOffset.UTC
            val hours = (observer.location.longitude / 15).roundedHalfAwayFromZero()
            return ZoneOffset.ofHours(if (hours.isFinite()) AstroMath.clamp(hours, -12.0, 12.0).toInt() else 0)
        }
    }
}

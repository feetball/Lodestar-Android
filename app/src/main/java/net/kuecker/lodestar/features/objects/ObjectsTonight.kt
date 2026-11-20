package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.NightTwilight
import net.kuecker.lodestar.kit.ephemeris.TwilightCalculator
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.planning.NightPlanner
import net.kuecker.lodestar.kit.planning.TargetVisibility
import net.kuecker.lodestar.kit.planning.UnobstructedVisibility
import net.kuecker.lodestar.kit.planning.unobstructedVisibility
import net.kuecker.lodestar.kit.planning.visibility
import java.time.Instant
import java.time.ZoneId

/**
 * The night the Objects list judges "tonight" by: the one under way, or the coming one once the Sun has risen.
 *
 * @property start Local noon at which the night starts; it changes when tonight turns into the next night.
 * @property darkness The hours of astronomical darkness, or `null` when the Sun does not get that low.
 */
internal data class ObjectsNight(val start: Instant, val darkness: DateInterval?)

/**
 * How many hours tonight each object is above the site's skyline and at least [MINIMUM_ALTITUDE] high during
 * astronomical darkness: the numbers behind the Objects list's "Hours Visible Tonight" order and "Visible ≥ 1 h
 * tonight" filter. Pure, so it can run on any thread.
 *
 * The altitude is the Tonight screen's minimum (the planner's default), so an object's hours here are the hours the
 * Tonight screen counts. Without a skyline the horizon is flat.
 */
internal object ObjectsTonight {
    /** The lowest altitude, in degrees, at which an object counts as visible: the Tonight screen's minimum. */
    const val MINIMUM_ALTITUDE: Double = NightPlanner.defaultMinimumAltitude

    /** The hours of visibility "Visible ≥ 1 h tonight" asks for. */
    const val MINIMUM_HOURS: Double = 1.0

    /**
     * The twilight of the night that contains [date]: the one that began at noon yesterday while it is still before
     * sunrise, otherwise the one that begins at noon today.
     */
    fun twilight(observer: Observer, zone: ZoneId, date: Instant): NightTwilight {
        val yesterday = date.atZone(zone).minusDays(1).toInstant()
        val previous = TwilightCalculator.night(yesterday, observer, zone)
        if (date < previous.interval.end && date < (previous.sunrise ?: previous.interval.end)) return previous
        return TwilightCalculator.night(date, observer, zone)
    }

    /** The night under way at [now] (the coming one by day). */
    fun night(observer: Observer, zone: ZoneId, now: Instant): ObjectsNight {
        val twilight = twilight(observer, zone, now)
        return ObjectsNight(twilight.interval.start, twilight.astronomicalNight)
    }

    /**
     * The hours of [night] each of [objects] is clear of [skyline] (the flat horizon when `null`), keyed by object
     * identifier. The deep-sky objects and stars, which are most of a catalog, go through the planner's cheap batch
     * (about 1 s for 11,000 objects with a skyline on a desktop, a few seconds on a phone); the Sun, Moon, planets,
     * comets and asteroids one at a time (a few milliseconds each).
     */
    fun visibility(
        objects: List<CelestialObject>,
        observer: Observer,
        night: ObjectsNight,
        skyline: HorizonProfile?,
    ): Map<String, UnobstructedVisibility> {
        val result = HashMap<String, UnobstructedVisibility>(objects.size * 2)
        val darkness = night.darkness
        if (darkness == null) {
            for (obj in objects) result[obj.id] = NONE
            return result
        }
        val fixed = ArrayList<CelestialObject>()
        val places = ArrayList<EquatorialCoordinate>()
        val motions = ArrayList<ProperMotion?>()
        for (obj in objects) {
            val position = obj.position
            if (position is ObjectPosition.Fixed) {
                fixed.add(obj)
                places.add(position.coordinate)
                motions.add(position.properMotion)
            } else {
                result[obj.id] = visibility(obj, observer, darkness, skyline)
            }
        }
        val batch = NightPlanner.unobstructedVisibility(
            places, motions, observer = observer, interval = darkness, minimumAltitude = MINIMUM_ALTITUDE, horizon = skyline,
        )
        for ((index, obj) in fixed.withIndex()) result[obj.id] = batch[index]
        return result
    }

    /** How long [obj] is clear of [skyline] during [darkness] (the planner's one-object calls, a few milliseconds). */
    fun visibility(
        obj: CelestialObject,
        observer: Observer,
        darkness: DateInterval,
        skyline: HorizonProfile?,
    ): UnobstructedVisibility {
        val found: TargetVisibility = when (val position = obj.position) {
            is ObjectPosition.Fixed -> return NightPlanner.unobstructedVisibility(
                listOf(position.coordinate), listOf(position.properMotion),
                observer = observer, interval = darkness, minimumAltitude = MINIMUM_ALTITUDE, horizon = skyline,
            ).first()
            is ObjectPosition.SolarSystem -> NightPlanner.visibility(
                position.body, observer, darkness, MINIMUM_ALTITUDE, skyline,
            )
            // A pole stands still at the latitude, and a comet or an asteroid moves against the stars: both follow
            // the catalog's own position.
            is ObjectPosition.CelestialPole, is ObjectPosition.SmallBodyOrbit -> {
                val place: (SkyFrame) -> HorizontalCoordinate = { it.horizontal(obj, false) }
                NightPlanner.visibility(place, observer, darkness, MINIMUM_ALTITUDE, skyline)
            }
        }
        return UnobstructedVisibility(found.unobstructedIntervals, found.isBehindSkyline)
    }

    private val NONE = UnobstructedVisibility(emptyList(), isBehindSkyline = false)
}

package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.ephemeris.topocentricEquatorial
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame

/**
 * Horizontal coordinates of an object at this frame's instant (Swift `horizontal(of:refraction:)`).
 *
 * Fixed objects include proper motion, aberration and precession; solar-system bodies are
 * topocentric; the celestial poles are the poles of date.
 *
 * @param obj The object.
 * @param refraction Whether to raise the altitude by atmospheric refraction.
 */
fun SkyFrame.horizontal(obj: CelestialObject, refraction: Boolean = true): HorizontalCoordinate =
    when (val position = obj.position) {
        is ObjectPosition.Fixed -> horizontal(position.coordinate, position.properMotion, refraction)
        is ObjectPosition.SolarSystem -> horizontal(position.body, refraction)
        is ObjectPosition.CelestialPole -> horizontalOfCelestialPole(position.north, refraction)
    }

/**
 * The apparent place of an object in the true equatorial frame of date (Swift `apparentEquatorial(of:)`).
 *
 * Fixed objects get proper motion, aberration, precession and nutation; solar-system bodies
 * are topocentric (parallax matters for the Moon); a celestial pole is at right ascension 0°
 * and declination ±90°.
 */
fun SkyFrame.apparentEquatorial(obj: CelestialObject): EquatorialCoordinate =
    when (val position = obj.position) {
        is ObjectPosition.Fixed -> apparentPlace(position.coordinate, position.properMotion)
        is ObjectPosition.SolarSystem -> topocentricEquatorial(position.body)
        is ObjectPosition.CelestialPole -> EquatorialCoordinate(0.0, if (position.north) 90.0 else -90.0)
    }

/**
 * The size an object has at this frame's instant (Swift `currentAngularSize(of:)`).
 *
 * For the Sun, the Moon and the planets this is the apparent diameter of the disc, which varies
 * with distance (the Moon between about 29′ and 34′); for other objects it is the catalog
 * size, which may be `null`.
 */
fun SkyFrame.currentAngularSize(obj: CelestialObject): AngularSize? {
    val position = obj.position as? ObjectPosition.SolarSystem ?: return obj.angularSize
    val diameter = state(position.body).angularDiameterArcseconds / 60
    return AngularSize.create(diameter, diameter)
}

/**
 * The magnitude an object has at this frame's instant (Swift `currentMagnitude(of:)`).
 *
 * The apparent magnitude from the ephemeris for the Sun, the Moon and the planets (the Moon's
 * depends on its phase); the catalog magnitude for other objects, which may be `null`.
 */
fun SkyFrame.currentMagnitude(obj: CelestialObject): Double? {
    val position = obj.position as? ObjectPosition.SolarSystem ?: return obj.magnitude
    return state(position.body).apparentMagnitude
}

/**
 * The first rise, transit and set of an object inside [interval] (Swift `riseTransitSet(for:observer:within:)`).
 *
 * Stars, deep-sky objects and planets use `standardHorizonAltitude`, the Sun `sunHorizonAltitude`
 * and the Moon its upper limb (see `riseTransitSet(body, observer, interval)` for a body).
 *
 * A celestial pole never rises or sets: the result is `ALWAYS_UP` when the pole is above
 * the standard horizon altitude for the observer's latitude and `ALWAYS_DOWN` otherwise, has no
 * rise, transit or set, and reports the pole's constant altitude as `transitAltitude`.
 */
fun RiseSetCalculator.riseTransitSet(obj: CelestialObject, observer: Observer, interval: DateInterval): RiseTransitSet =
    when (val position = obj.position) {
        is ObjectPosition.Fixed -> riseTransitSet(position.coordinate, position.properMotion, observer, interval)
        is ObjectPosition.SolarSystem -> riseTransitSet(position.body, observer, interval)
        is ObjectPosition.CelestialPole -> {
            // The altitude of the pole of date is the geodetic latitude, to the precision of this app.
            val altitude = if (position.north) observer.location.latitude else -observer.location.latitude
            RiseTransitSet(
                rise = null,
                transit = null,
                set = null,
                transitAltitude = altitude,
                circumstance = if (altitude >= standardHorizonAltitude) {
                    RiseTransitSet.Circumstance.ALWAYS_UP
                } else {
                    RiseTransitSet.Circumstance.ALWAYS_DOWN
                },
            )
        }
    }

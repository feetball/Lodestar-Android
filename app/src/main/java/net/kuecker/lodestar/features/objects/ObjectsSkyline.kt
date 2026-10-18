package net.kuecker.lodestar.features.objects

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.catalog.riseTransitSet
import net.kuecker.lodestar.kit.ephemeris.RiseSetCalculator
import net.kuecker.lodestar.kit.ephemeris.RiseTransitSet
import net.kuecker.lodestar.kit.foundation.DateInterval
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import net.kuecker.lodestar.kit.horizon.HorizonVisibility
import net.kuecker.lodestar.model.ObserverContext

/**
 * The site's recorded skyline when it actually hides something: `null` for an open horizon (no skyline, or a flat one).
 * Lists, rise and set times, "visible now" and the Tonight cards use the engine's skyline-aware calls when this is set.
 */
internal val ObserverContext.skyline: HorizonProfile?
    get() = horizon?.takeUnless { it.isFlat }

/**
 * The first rise, transit and set of [obj] inside [interval] over this skyline: "rise" is clearing the skyline and "set"
 * is sinking behind it. A celestial pole never rises or sets, so the flat horizon answers for it.
 */
internal fun HorizonVisibility.riseTransitSet(obj: CelestialObject, observer: Observer, interval: DateInterval): RiseTransitSet =
    when (val position = obj.position) {
        is ObjectPosition.Fixed -> riseTransitSet(position.coordinate, position.properMotion, observer, interval)
        is ObjectPosition.SolarSystem -> riseTransitSet(position.body, observer, interval)
        is ObjectPosition.CelestialPole -> RiseSetCalculator.riseTransitSet(obj, observer, interval)
        is ObjectPosition.SmallBodyOrbit -> riseTransitSet({ SkyFrame(it, observer).horizontal(obj, false) }, interval)
    }

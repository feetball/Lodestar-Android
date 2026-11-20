package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.topocentricEquatorial
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.planning.MeridianClock
import net.kuecker.lodestar.kit.planning.MeridianTransits
import java.time.Instant

/**
 * The transits of an object across the local meridian within [MeridianClock.searchHalfSpan] of [date] (Swift
 * `MeridianClock.transit(for:around:observer:)` for a `CelestialObject`).
 *
 * Fixed objects and solar-system bodies are as for the overloads of [MeridianClock]; a comet or an asteroid costs a
 * few milliseconds and has no transit while its orbit cannot be evaluated; a celestial pole never transits, so both
 * results are `null`.
 */
fun MeridianClock.transit(obj: CelestialObject, date: Instant, observer: Observer): MeridianTransits =
    when (val position = obj.position) {
        is ObjectPosition.Fixed -> transit(position.coordinate, position.properMotion, date, observer)
        is ObjectPosition.SolarSystem -> transit(position.body, date, observer)
        is ObjectPosition.SmallBodyOrbit ->
            transits(date) { instant ->
                val frame = SkyFrame(instant, observer)
                frame.topocentricEquatorial(position.body)?.let { frame.hourAngle(it.rightAscension) } ?: Double.NaN
            }
        is ObjectPosition.CelestialPole -> MeridianTransits(next = null, previous = null)
    }

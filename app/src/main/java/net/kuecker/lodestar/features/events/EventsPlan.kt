package net.kuecker.lodestar.features.events

import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.events.AstronomicalEvent
import net.kuecker.lodestar.kit.events.EventDetails
import net.kuecker.lodestar.kit.events.EventKind
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.model.ObserverContext
import java.time.Instant

/** Where the Moon is at the time an event is best seen, and how lit. */
internal data class EventMoon(val altitude: Double, val azimuth: Double, val illuminatedFraction: Double) {
    val isUp: Boolean get() = altitude > 0
}

/**
 * The few numbers the event detail needs beyond the engine's event: the Moon, and for a meteor shower the radiant and
 * the sky about 40° from it, all at [time].
 */
internal data class EventPlan(
    /** The moment the plan is for: the best time in the viewing window, else the event's own instant. */
    val time: Instant,
    val moon: EventMoon?,
    /** The radiant of a meteor shower (apparent altitude and azimuth). */
    val radiant: HorizontalCoordinate?,
    /** A point about 40° from the radiant, the part of the sky to frame, when the radiant is up. */
    val meteorFrame: HorizontalCoordinate?,
)

/** Works out an [EventPlan]. Pure and cheap, but called off the main thread like the rest of the astronomy. */
internal object EventsPlanner {
    /** How far from the radiant a meteor camera looks. */
    const val METEOR_OFFSET_DEGREES = 40.0

    fun plan(event: AstronomicalEvent, context: ObserverContext): EventPlan {
        val time = event.visibility?.bestTime ?: EventsFormat.displayDate(event)
        val frame = SkyFrame(time, context.observer)
        val moon = if (event.kind == EventKind.SEASON) {
            null
        } else {
            val position = frame.horizontal(SolarSystemBody.MOON, false)
            EventMoon(position.altitude, position.azimuth, frame.state(SolarSystemBody.MOON).illuminatedFraction ?: 0.0)
        }
        val shower = (event.details as? EventDetails.MeteorShower)?.peak?.shower
        val radiant = shower?.let { frame.horizontal(it.radiant(time), null, true) }
        return EventPlan(time, moon, radiant, radiant?.let(::meteorFrame))
    }

    /**
     * The point [METEOR_OFFSET_DEGREES] from [radiant] along the vertical circle through it towards the zenith, going on
     * past the zenith to the far side when the radiant is high. `null` when the radiant is below the horizon.
     */
    private fun meteorFrame(radiant: HorizontalCoordinate): HorizontalCoordinate? {
        if (!(radiant.altitude > 0)) return null
        val raised = radiant.altitude + METEOR_OFFSET_DEGREES
        return if (raised <= 90.0) {
            HorizontalCoordinate(radiant.azimuth, raised)
        } else {
            HorizontalCoordinate(radiant.azimuth + 180.0, 180.0 - raised)
        }
    }
}

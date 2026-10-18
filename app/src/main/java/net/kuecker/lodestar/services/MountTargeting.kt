package net.kuecker.lodestar.services

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.foundation.addingTimeInterval
import net.kuecker.lodestar.kit.mount.MountError
import java.time.Instant

/** A place for the mount to slew to: where the target is now, as J2000 coordinates the engine accepts. */
data class MountTarget(
    val name: String,
    val j2000: EquatorialCoordinate,
    val properMotion: ProperMotion?,
    /** Where the target stands now (geometric, no refraction: the frame of the mount's altitude limit). */
    val horizontal: HorizontalCoordinate,
    /** The Sun: slewing there needs a certified solar filter on the optics. */
    val isSun: Boolean,
)

/** How far and in which direction a slew takes the mount. */
data class MountSlewSummary(
    /** Great-circle distance on the sky, degrees. */
    val distanceDegrees: Double,
    /** Change of azimuth, degrees in (-180, 180]; positive turns clockwise seen from above (north towards east). */
    val azimuthChange: Double,
    /** Change of altitude, degrees; positive tilts up. */
    val altitudeChange: Double,
)

/** Turning objects of the catalog into targets for the mount, and the checks the confirmation sheet shows before the engine's own. */
object MountTargeting {
    /** The target for [obj] at [date]: fixed objects keep their proper motion, moving ones are placed for this instant. */
    fun target(obj: CelestialObject, observer: Observer, date: Instant): MountTarget {
        val frame = SkyFrame(date, observer)
        val isSun = obj.category == ObjectCategory.SUN
        val position = obj.position
        if (position is ObjectPosition.Fixed) {
            val geometric = frame.horizontal(position.coordinate, position.properMotion, false)
            return MountTarget(obj.name, position.coordinate, position.properMotion, geometric, isSun)
        }
        // A planet, the Moon (whose parallax is up to a degree) or a pole: take its true direction in the local sky and
        // turn it back into J2000 coordinates, which includes everything the ephemeris knows.
        val geometric = frame.horizontal(obj, false)
        return MountTarget(obj.name, frame.j2000(geometric, false), null, geometric, isSun)
    }

    /** The altitude of [target]'s J2000 place at [date] for [observer], geometric. */
    fun altitude(coordinate: EquatorialCoordinate, properMotion: ProperMotion?, observer: Observer, date: Instant): Double =
        SkyFrame(date, observer).horizontal(coordinate, properMotion, false).altitude

    /**
     * What the engine's altitude check says about [target]: the same test it makes before every slew (now, and
     * [lookaheadSeconds] from now), with the same error and the same words. Null when the slew is allowed.
     */
    fun altitudeProblem(
        target: MountTarget,
        observer: Observer,
        date: Instant,
        limit: Double,
        lookaheadSeconds: Double = LOOKAHEAD_SECONDS,
    ): MountError.BelowHorizonLimit? {
        val offsets = if (lookaheadSeconds > 0) doubleArrayOf(0.0, lookaheadSeconds) else doubleArrayOf(0.0)
        for (offset in offsets) {
            val altitude = altitude(target.j2000, target.properMotion, observer, date.addingTimeInterval(offset))
            if (altitude < limit) return MountError.BelowHorizonLimit(altitude, limit)
        }
        return null
    }

    /** The slew from where the mount points to [target]; null when the mount's place is unknown. */
    fun slew(from: HorizontalCoordinate?, to: HorizontalCoordinate): MountSlewSummary? {
        from ?: return null
        return MountSlewSummary(
            distanceDegrees = from.angularSeparation(to),
            azimuthChange = AstroMath.normalizeSignedDegrees(to.azimuth - from.azimuth),
            altitudeChange = to.altitude - from.altitude,
        )
    }

    /** The seconds the engine looks ahead (its default). */
    const val LOOKAHEAD_SECONDS = 60.0
}

/** Where the mount points, for the overlay: computed once per poll, read every frame. */
class MountMarker(
    /** Where the mount reports pointing, J2000. */
    val position: EquatorialCoordinate,
    /** The place in the local sky as the overlay shows stars (refraction included). */
    val horizontal: HorizontalCoordinate,
    /** [horizontal] as the unit vector the overlay projects. */
    val direction: Vector3,
) {
    companion object {
        /** The marker for the mount pointing at [position] at [date]. */
        fun make(position: EquatorialCoordinate, observer: Observer, date: Instant): MountMarker {
            val horizontal = SkyFrame(date, observer).horizontal(position, null, true)
            return MountMarker(position, horizontal, horizontal.unitVector)
        }
    }
}

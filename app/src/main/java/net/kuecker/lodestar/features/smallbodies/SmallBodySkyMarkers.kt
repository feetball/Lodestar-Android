package net.kuecker.lodestar.features.smallbodies

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.comets.SmallBodyEphemeris
import net.kuecker.lodestar.kit.comets.SmallBodyKind
import net.kuecker.lodestar.kit.comets.state
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.kit.horizon.HorizonMask
import net.kuecker.lodestar.shared.SkySnapshot
import kotlin.math.tan

/**
 * Which comets and asteroids a sky view draws, and where: the target always, otherwise the ones brighter than a
 * magnitude limit (a view with all 216 bundled asteroids and every comet of the file would be a rash of dots). Each
 * comet also gets the direction of its ion tail on the sky.
 */
internal object SmallBodySkyMarkers {
    /** Bodies fainter than this (and not the target) are not drawn: faint dots cannot be photographed and clutter. */
    const val DEFAULT_MAGNITUDE_LIMIT = 9.0

    /** The most markers a snapshot carries, brightest first. */
    const val MAXIMUM_COUNT = 80

    /** The tail direction is the body's direction moved this far (tangent of one degree) along the tail. */
    private val tailStep = tan(AstroMath.radians(1.0))

    /**
     * The markers of the small bodies in [smallBodies] (the comets and asteroids of the catalog) for [frame]: the
     * [target] and everything brighter than [magnitudeLimit], the target first and then the brightest first. A body
     * without magnitude parameters is drawn only as the target. [mask] marks those the recorded skyline hides.
     */
    fun build(
        smallBodies: List<CelestialObject>,
        frame: SkyFrame,
        target: CelestialObject?,
        magnitudeLimit: Double,
        mask: HorizonMask?,
    ): List<SkySnapshot.SmallBodyMarker> {
        if (smallBodies.isEmpty() && target?.smallBody == null) return emptyList()
        val markers = ArrayList<SkySnapshot.SmallBodyMarker>()
        val candidates = if (target?.smallBody != null && smallBodies.none { it.id == target.id }) {
            smallBodies + target
        } else {
            smallBodies
        }
        for (obj in candidates) {
            val body = obj.smallBody ?: continue
            val isTarget = obj.id == target?.id
            val state = frame.state(body)
            if (state == null) continue
            val magnitude = state.apparentMagnitude
            if (!isTarget && (magnitude == null || !(magnitude <= magnitudeLimit))) continue
            val horizontal = frame.horizontal(state.geocentricJ2000, true)
            val direction = horizontal.unitVector
            val tailAngle = if (body.kind == SmallBodyKind.COMET) SmallBodyEphemeris.tailAngleFromVertical(state, frame) else null
            markers.add(
                SkySnapshot.SmallBodyMarker(
                    obj = obj,
                    direction = direction,
                    horizontal = horizontal,
                    magnitude = magnitude,
                    tailEnd = tailAngle?.let { tailEnd(direction, it) },
                    isBehindSkyline = mask != null && mask.isBelow(direction),
                ),
            )
        }
        markers.sortWith(
            compareByDescending<SkySnapshot.SmallBodyMarker> { it.obj.id == target?.id }
                .thenBy { it.magnitude ?: Double.POSITIVE_INFINITY }
                .thenBy { it.obj.id },
        )
        return if (markers.size > MAXIMUM_COUNT) markers.subList(0, MAXIMUM_COUNT).toList() else markers
    }

    /**
     * A point one degree from [direction] along the tail, which lies [angleFromVertical] degrees from straight up
     * (positive to the observer's right when facing the body). `null` at the zenith, where "up" has no direction.
     */
    fun tailEnd(direction: Vector3, angleFromVertical: Double): Vector3? {
        val upRaw = Vector3(0.0, 0.0, 1.0) - direction * direction.z
        val upLength = upRaw.length
        if (!(upLength > 1e-9)) return null
        val up = upRaw / upLength
        val right = direction.cross(up)
        val along = up * AstroMath.cosDeg(angleFromVertical) + right * AstroMath.sinDeg(angleFromVertical)
        return (direction + along * tailStep).normalized
    }
}

package net.kuecker.lodestar.shared

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.ephemeris.state
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import java.time.Instant

/**
 * What the sky views draw, for one instant and observer: every visible thing as an apparent (refracted) unit vector
 * in the local East–North–Up frame.
 *
 * Building a snapshot touches every catalog object, so it runs off the main thread every few seconds; drawing a
 * frame then only rotates and projects these vectors. Immutable; safe to share between threads.
 */
class SkySnapshot(
    val date: Instant,
    val observer: Observer,
    /** Brightest first. */
    val stars: List<Star>,
    val bodies: List<Body>,
    val deepSky: List<DeepSky>,
    val constellations: List<ConstellationFigure>,
    val milkyWay: List<MilkyWayBand>,
    val target: Target?,
) {
    data class Star(
        val id: String,
        val direction: Vector3,
        val magnitude: Double,
        val colorIndex: Double?,
        val name: String?,
    )

    /** The Sun, the Moon or a planet. */
    data class Body(
        val obj: CelestialObject,
        val direction: Vector3,
        val horizontal: HorizontalCoordinate,
        val magnitude: Double?,
        /** Apparent diameter in degrees. */
        val angularDiameter: Double,
        val illuminatedFraction: Double?,
        val isWaxing: Boolean?,
    ) {
        val id: String get() = obj.id
    }

    data class DeepSky(
        val obj: CelestialObject,
        val direction: Vector3,
        /** Major axis in degrees, when known. */
        val majorAxis: Double?,
    ) {
        val id: String get() = obj.id
    }

    data class ConstellationFigure(
        val abbreviation: String,
        val name: String,
        val label: Vector3,
        val lines: List<List<Vector3>>,
    ) {
        val id: String get() = abbreviation
    }

    data class MilkyWayBand(
        /** 1 = faint outer glow … 5 = brightest core. */
        val level: Int,
        val polygons: List<List<Vector3>>,
        val holes: List<List<Vector3>>,
    )

    /** The current target's apparent position. */
    data class Target(val obj: CelestialObject, val direction: Vector3, val horizontal: HorizontalCoordinate)

    /**
     * Star directions packed as x, y, z per star in the order of [stars], for allocation-free projection in
     * per-frame drawing loops.
     */
    val starVectors: DoubleArray = DoubleArray(stars.size * 3).also { packed ->
        stars.forEachIndexed { index, star ->
            packed[index * 3] = star.direction.x
            packed[index * 3 + 1] = star.direction.y
            packed[index * 3 + 2] = star.direction.z
        }
    }

    /** The local frame used to build the snapshot (for horizon/grid drawing and inverse lookups). */
    val frame: SkyFrame get() = SkyFrame(date, observer)
}

/** Which layers to include and how deep to go. */
data class SkySnapshotOptions(
    val starMagnitudeLimit: Double = 5.0,
    val includeConstellations: Boolean = true,
    val includeDeepSky: Boolean = true,
    val includeMilkyWay: Boolean = true,
    /** Deep-sky objects fainter than this are left out (objects without a magnitude are kept). */
    val deepSkyMagnitudeLimit: Double = 10.0,
)

object SkySnapshotBuilder {
    /** Below this geometric altitude refraction is zero, so the vector is used as is. */
    private val refractionFloor = AstroMath.sinDeg(-5.0)

    private val deepSkyGroups = setOf(ObjectGroup.GALAXIES, ObjectGroup.NEBULAE, ObjectGroup.CLUSTERS, ObjectGroup.SPECIAL)

    /** Computes the snapshot. Pure; call it on a background dispatcher. */
    fun build(
        catalog: Catalog,
        observer: Observer,
        date: Instant,
        options: SkySnapshotOptions,
        target: CelestialObject?,
    ): SkySnapshot {
        val frame = SkyFrame(date, observer)
        val atmosphere = observer.atmosphere
        fun apparent(geometric: Vector3): Vector3 {
            if (geometric.z <= refractionFloor) return geometric
            return SkyFrame.refracted(HorizontalCoordinate(geometric), atmosphere).unitVector
        }
        fun direction(position: EquatorialCoordinate): Vector3 = apparent(frame.horizontalVector(position.unitVector))

        val stars = catalog.renderStars.asSequence()
            .takeWhile { it.magnitude <= options.starMagnitudeLimit }
            .map { star ->
                SkySnapshot.Star(
                    star.id, apparent(frame.horizontalVector(star.unitVector)), star.magnitude, star.colorIndex, star.name,
                )
            }
            .toList()

        val bodies = SolarSystemBody.entries.mapNotNull { body ->
            val obj = catalog.objectWithID(objectID(body)) ?: return@mapNotNull null
            val state = frame.state(body)
            val horizontal = frame.horizontal(body, true)
            SkySnapshot.Body(
                obj, horizontal.unitVector, horizontal, state.apparentMagnitude,
                state.angularDiameterArcseconds / 3600, state.illuminatedFraction, state.isWaxing,
            )
        }

        val deepSky = if (!options.includeDeepSky) emptyList() else catalog.listedObjects.mapNotNull { obj ->
            val position = obj.position as? ObjectPosition.Fixed ?: return@mapNotNull null
            if (obj.group !in deepSkyGroups) return@mapNotNull null
            val magnitude = obj.magnitude
            if (magnitude != null && magnitude > options.deepSkyMagnitudeLimit) return@mapNotNull null
            SkySnapshot.DeepSky(obj, direction(position.coordinate), obj.angularSize?.let { it.majorArcminutes / 60 })
        }

        val constellations = if (!options.includeConstellations) emptyList() else catalog.constellations.map { c ->
            SkySnapshot.ConstellationFigure(
                c.abbreviation, c.name, direction(c.labelPosition), c.lines.map { line -> line.map(::direction) },
            )
        }

        val milkyWay = if (!options.includeMilkyWay) emptyList() else catalog.milkyWay.levels.map { level ->
            SkySnapshot.MilkyWayBand(
                level.level,
                level.polygons.map { polygon -> polygon.map(::direction) },
                level.holes.map { hole -> hole.map(::direction) },
            )
        }

        val targetPosition = target?.let { obj ->
            val horizontal = frame.horizontal(obj, true)
            SkySnapshot.Target(obj, horizontal.unitVector, horizontal)
        }

        return SkySnapshot(date, observer, stars, bodies, deepSky, constellations, milkyWay, targetPosition)
    }

    /** Catalog identifier of a solar-system body ("sun", "moon", "planet.mars"). */
    fun objectID(body: SolarSystemBody): String = when (body) {
        SolarSystemBody.SUN -> "sun"
        SolarSystemBody.MOON -> "moon"
        else -> "planet.${body.rawValue}"
    }
}

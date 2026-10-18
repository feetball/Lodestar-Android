package net.kuecker.lodestar.shared

import net.kuecker.lodestar.features.smallbodies.SmallBodySkyMarkers
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.Prominence
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
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.horizon.HorizonMask
import net.kuecker.lodestar.kit.horizon.HorizonProfile
import java.time.Instant
import kotlin.math.roundToInt

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
    /** The site's recorded skyline the snapshot was built for, or `null` for an open horizon. */
    val horizon: HorizonProfile? = null,
    /** The comets and asteroids to draw: the target and the bright ones, target first, then brightest first. */
    val smallBodies: List<SmallBodyMarker> = emptyList(),
) {
    data class Star(
        val id: String,
        val direction: Vector3,
        val magnitude: Double,
        val colorIndex: Double?,
        val name: String?,
        /** `true` when the recorded skyline (trees, a ridge) hides the star; always `false` for an open horizon. */
        val isBehindSkyline: Boolean = false,
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
        /** `true` when the recorded skyline hides the body; always `false` for an open horizon. */
        val isBehindSkyline: Boolean = false,
    ) {
        val id: String get() = obj.id
    }

    /** A comet or an asteroid, drawn as a dot (a comet with a short line along its ion tail). */
    data class SmallBodyMarker(
        val obj: CelestialObject,
        val direction: Vector3,
        val horizontal: HorizontalCoordinate,
        /** The current magnitude, an estimate; `null` without magnitude parameters. */
        val magnitude: Double?,
        /** A point one degree from [direction] along the ion tail of a comet (apparent, East–North–Up); otherwise `null`. */
        val tailEnd: Vector3?,
        /** `true` when the recorded skyline hides the body; always `false` for an open horizon. */
        val isBehindSkyline: Boolean = false,
    ) {
        val id: String get() = obj.id
        val isComet: Boolean get() = obj.category == ObjectCategory.COMET
    }

    data class DeepSky(
        val obj: CelestialObject,
        val direction: Vector3,
        /** Major axis in degrees, when known. */
        val majorAxis: Double?,
        /** `true` when the recorded skyline hides the object; always `false` for an open horizon. */
        val isBehindSkyline: Boolean = false,
    ) {
        val id: String get() = obj.id

        /** How conspicuous the object is (1 showpiece … 4 obscure). */
        val prominence: Prominence get() = obj.prominence
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
    /**
     * Deep-sky objects of this prominence or better are included (the "Deep-sky detail" setting: 1 showpieces … 4
     * everything). There is no magnitude cut-off: the prominence already ranks by brightness, size and fame, and dark
     * nebulae have no magnitude at all. The views thin the symbols and names out further when they are zoomed out.
     */
    val deepSkyProminence: Prominence = Prominence.PROMINENT,
    /** Comets and asteroids brighter than this magnitude are included, and the target whatever its brightness. */
    val smallBodyMagnitudeLimit: Double = SmallBodySkyMarkers.DEFAULT_MAGNITUDE_LIMIT,
)

object SkySnapshotBuilder {
    /** Below this geometric altitude refraction is zero, so the vector is used as is. */
    private val refractionFloor = AstroMath.sinDeg(-5.0)

    /**
     * Computes the snapshot. Pure; call it on a background dispatcher.
     *
     * @param horizon The site's recorded skyline, or `null` for an open horizon: stars and deep-sky objects it hides
     * are flagged `isBehindSkyline` (the views dim or leave them out).
     */
    fun build(
        catalog: Catalog,
        observer: Observer,
        date: Instant,
        options: SkySnapshotOptions,
        target: CelestialObject?,
        horizon: HorizonProfile? = null,
    ): SkySnapshot {
        val frame = SkyFrame(date, observer)
        val atmosphere = observer.atmosphere
        val mask = horizon?.takeUnless { it.isFlat }?.let(::HorizonMask)
        fun apparent(geometric: Vector3): Vector3 {
            if (geometric.z <= refractionFloor) return geometric
            return SkyFrame.refracted(HorizontalCoordinate(geometric), atmosphere).unitVector
        }
        fun direction(position: EquatorialCoordinate): Vector3 = apparent(frame.horizontalVector(position.unitVector))

        val stars = catalog.renderStars.asSequence()
            .takeWhile { it.magnitude <= options.starMagnitudeLimit }
            .map { star ->
                val direction = apparent(frame.horizontalVector(star.unitVector))
                SkySnapshot.Star(
                    star.id, direction, star.magnitude, star.colorIndex, star.name,
                    isBehindSkyline = mask != null && mask.isBelow(direction),
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
                isBehindSkyline = mask != null && mask.isBelow(horizontal.unitVector),
            )
        }

        val deepSky = if (!options.includeDeepSky) emptyList() else deepSkyCandidates(catalog, options.deepSkyProminence)
            .mapNotNull { obj ->
                val position = obj.position as? ObjectPosition.Fixed ?: return@mapNotNull null
                val direction = direction(position.coordinate)
                SkySnapshot.DeepSky(
                    obj, direction, obj.angularSize?.let { it.majorArcminutes / 60 },
                    isBehindSkyline = mask != null && mask.isBelow(direction),
                )
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

        val smallBodies = SmallBodySkyMarkers.build(catalog.smallBodies, frame, target, options.smallBodyMagnitudeLimit, mask)

        return SkySnapshot(date, observer, stars, bodies, deepSky, constellations, milkyWay, targetPosition, horizon, smallBodies)
    }

    /**
     * The deep-sky objects of [maximum] prominence or better (obscure ones are not in the browse lists but are drawn
     * when asked for), plus the fixed special directions such as the Galactic Center.
     */
    private fun deepSkyCandidates(catalog: Catalog, maximum: Prominence): List<CelestialObject> {
        val special = catalog.listedObjects.filter {
            !it.isDeepSky && it.group == ObjectGroup.SPECIAL && it.position is ObjectPosition.Fixed &&
                it.prominence <= maximum
        }
        return catalog.deepSkyObjects(maximum) + special
    }

    /** Catalog identifier of a solar-system body ("sun", "moon", "planet.mars"). */
    fun objectID(body: SolarSystemBody): String = when (body) {
        SolarSystemBody.SUN -> "sun"
        SolarSystemBody.MOON -> "moon"
        else -> "planet.${body.rawValue}"
    }
}

/** Words for a recorded skyline, for the canvas summaries that accessibility services read in the sky views. */
object SkylineSummary {
    /** For example "The recorded skyline hides the sky up to 32 degrees, highest towards the east." */
    fun describe(profile: HorizonProfile): String {
        val altitudes = profile.altitudes
        var highest = 0
        for (index in altitudes.indices) {
            if (altitudes[index] > altitudes[highest]) highest = index
        }
        val top = altitudes[highest]
        if (top < 1.0) return "The recorded skyline stays below one degree."
        val direction = AngleFormatter.compassPoint(highest.toDouble()).fullName.lowercase()
        return "The recorded skyline hides the sky up to ${top.roundToInt()} degrees, highest towards the $direction."
    }
}

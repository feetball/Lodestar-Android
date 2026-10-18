package net.kuecker.lodestar.features.aim

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.foundation.Vector3
import net.kuecker.lodestar.shared.SkySnapshot
import net.kuecker.lodestar.shared.SkySnapshotBuilder
import java.time.Instant
import java.util.Locale
import kotlin.math.floor

/**
 * A rebuilt sky as the overlay draws it: the shared snapshot plus drawing groups derived from it, made together off
 * the main thread so a frame only projects and paints. Immutable; the directions the frame loop projects are also
 * kept packed as x, y, z in [DoubleArray]s.
 */
class AimSkyContent(val snapshot: SkySnapshot) {
    /** Stars grouped by brightness and tint, so each group is painted with one fill. Faintest group first. */
    val starGroups: List<AimStarGroup>

    /** Stars that have a proper name, brightest first. */
    val namedStars: List<AimNamedStar>

    /** The directions of [namedStars], packed. */
    val namedStarVectors: DoubleArray

    /** Brightest first. */
    val deepSky: List<AimDeepSkyMarker>

    /** The directions of [deepSky], packed. */
    val deepSkyVectors: DoubleArray

    /** The magnitudes of [deepSky] in the same order, ascending; infinite where unknown. */
    private val deepSkyMagnitudes: DoubleArray

    /** One layer per Milky Way brightness level, faintest first. */
    val milkyWay: List<AimMilkyWayLayer>

    /** The constellation names in capitals, in the order of the snapshot's figures. */
    val constellationNames: List<String>

    /** The label positions of the constellation figures, packed. */
    val constellationLabelVectors: DoubleArray

    /** The Sun, when the snapshot has it (the Moon's lit side points towards it). */
    val sun: SkySnapshot.Body?

    init {
        val stars = snapshot.stars
        val codes = IntArray(stars.size)
        val counts = IntArray(AimStarGroup.CODE_COUNT)
        val named = ArrayList<AimNamedStar>()
        for ((index, star) in stars.withIndex()) {
            val code = AimStarGroup.code(
                AimStarGroup.brightness(star.magnitude), AimStarGroup.tint(star.colorIndex), star.direction.z < 0,
            )
            codes[index] = code
            counts[code] += 1
            val name = star.name
            if (name != null && star.magnitude <= NAMED_STAR_MAGNITUDE_LIMIT) {
                named.add(AimNamedStar(name, star.direction))
            }
        }
        val grouped = Array(AimStarGroup.CODE_COUNT) { DoubleArray(counts[it] * 3) }
        val filled = IntArray(AimStarGroup.CODE_COUNT)
        val packed = snapshot.starVectors
        for (index in stars.indices) {
            val code = codes[index]
            System.arraycopy(packed, 3 * index, grouped[code], filled[code], 3)
            filled[code] += 3
        }
        // Faint stars first, so brighter ones are painted over them.
        starGroups = (AimStarGroup.CODE_COUNT - 1 downTo 0).filter { counts[it] > 0 }.map { AimStarGroup(it, grouped[it]) }
        namedStars = named
        namedStarVectors = pack(named.map { it.direction })
        deepSky = snapshot.deepSky.map { AimDeepSkyMarker(it) }.sortedBy { it.magnitude ?: Double.POSITIVE_INFINITY }
        deepSkyVectors = pack(deepSky.map { it.direction })
        deepSkyMagnitudes = DoubleArray(deepSky.size) { deepSky[it].magnitude ?: Double.POSITIVE_INFINITY }
        milkyWay = AimMilkyWayLayer.layers(snapshot.milkyWay, snapshot.frame)
        constellationNames = snapshot.constellations.map { it.name.uppercase(Locale.ROOT) }
        constellationLabelVectors = pack(snapshot.constellations.map { it.label })
        sun = snapshot.bodies.firstOrNull { it.obj.category == ObjectCategory.SUN }
    }

    /** How many of the [deepSky] markers, brightest first, are no fainter than [magnitude]. */
    fun deepSkyCount(magnitude: Double): Int {
        var low = 0
        var high = deepSkyMagnitudes.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (deepSkyMagnitudes[middle] <= magnitude) low = middle + 1 else high = middle
        }
        return low
    }

    companion object {
        /** Stars brighter than this get a name label. */
        private const val NAMED_STAR_MAGNITUDE_LIMIT = 3.0

        /** Builds the snapshot and the groups. Pure: call it from a background dispatcher. */
        fun build(catalog: Catalog, inputs: AimSkyInputs, date: Instant): AimSkyContent =
            AimSkyContent(
                SkySnapshotBuilder.build(catalog, inputs.observer, date, inputs.options, inputs.target),
            )

        private fun pack(directions: List<Vector3>): DoubleArray {
            val packed = DoubleArray(directions.size * 3)
            for ((index, direction) in directions.withIndex()) {
                packed[3 * index] = direction.x
                packed[3 * index + 1] = direction.y
                packed[3 * index + 2] = direction.z
            }
            return packed
        }
    }
}

/**
 * Stars of one look: the same brightness class and tint, above or below the horizon.
 *
 * @property code The look as one number, see [code]; the groups of a sky differ in it.
 * @property vectors The star directions packed as x, y, z.
 */
class AimStarGroup(val code: Int, val vectors: DoubleArray) {
    /** 0 for magnitudes below 0, up to the last class for magnitudes of 5 and fainter. */
    val brightness: Int get() = code / (TINT_LEVELS * 2)

    /** 0 for blue-white stars, 1 for white ones, 2 for orange ones. */
    val tint: Int get() = code / 2 % TINT_LEVELS
    val isBelowHorizon: Boolean get() = code % 2 == 1

    /** The number of stars. */
    val count: Int get() = vectors.size / 3

    companion object {
        /** Brightness classes: 0 for magnitudes below 0, up to the last class for magnitudes of 5 and fainter. */
        const val BRIGHTNESS_LEVELS = 7
        private const val TINT_LEVELS = 3

        /** The number of different looks. */
        const val CODE_COUNT = BRIGHTNESS_LEVELS * TINT_LEVELS * 2

        /** The look as one number that sorts by brightness, then tint, then horizon side. */
        fun code(brightness: Int, tint: Int, isBelowHorizon: Boolean): Int =
            (brightness * TINT_LEVELS + tint) * 2 + if (isBelowHorizon) 1 else 0

        fun brightness(magnitude: Double): Int {
            if (!magnitude.isFinite()) return BRIGHTNESS_LEVELS - 1
            return (floor(magnitude.coerceIn(-30.0, 30.0)).toInt() + 1).coerceIn(0, BRIGHTNESS_LEVELS - 1)
        }

        fun tint(colorIndex: Double?): Int {
            if (colorIndex == null || !colorIndex.isFinite()) return 1
            if (colorIndex < 0.3) return 0
            return if (colorIndex < 0.95) 1 else 2
        }
    }
}

class AimNamedStar(val name: String, val direction: Vector3)

/** A deep-sky object as a chart symbol. */
class AimDeepSkyMarker(deepSky: SkySnapshot.DeepSky) {
    enum class Shape {
        /** Galaxies. */
        ELLIPSE,

        /** Star clusters and associations. */
        CLUSTER_DOT,

        /** Nebulae and supernova remnants. */
        SQUARE,
        CIRCLE,
    }

    val name: String = deepSky.obj.name
    val direction: Vector3 = deepSky.direction

    /** Major axis in degrees; 0 when unknown. */
    val diameter: Double = deepSky.majorAxis ?: 0.0
    val magnitude: Double? = deepSky.obj.magnitude
    val shape: Shape = when (deepSky.obj.category) {
        ObjectCategory.GALAXY, ObjectCategory.GALAXY_GROUP -> Shape.ELLIPSE
        ObjectCategory.OPEN_CLUSTER, ObjectCategory.GLOBULAR_CLUSTER, ObjectCategory.STAR_ASSOCIATION -> Shape.CLUSTER_DOT
        ObjectCategory.EMISSION_NEBULA, ObjectCategory.REFLECTION_NEBULA, ObjectCategory.DARK_NEBULA,
        ObjectCategory.NEBULA, ObjectCategory.PLANETARY_NEBULA, ObjectCategory.SUPERNOVA_REMNANT,
        ObjectCategory.CLUSTER_WITH_NEBULA,
        -> Shape.SQUARE
        else -> Shape.CIRCLE
    }
}

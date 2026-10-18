package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate

/**
 * One of the 88 IAU constellations, with its stick figure.
 *
 * @property abbreviation The IAU three-letter abbreviation ("CMa"), which is also the identifier.
 * @property name The English name ("Canis Major").
 * @property genitive The genitive form used in star names ("Canis Majoris").
 * @property labelPosition Where to put the constellation's label, as a J2000 position.
 * @property lines The stick figure as polylines of J2000 positions. Consecutive vertices are joined by
 * great-circle arcs; a polyline may cross right ascension 0°/360°, so interpolate on the sphere rather
 * than in right ascension.
 */
@ConsistentCopyVisibility
data class Constellation internal constructor(
    val abbreviation: String,
    val name: String,
    val genitive: String,
    val labelPosition: EquatorialCoordinate,
    val lines: List<List<EquatorialCoordinate>>,
) {
    /** The IAU abbreviation, which is also the identifier ("CMa"). */
    val id: String get() = abbreviation
}

/**
 * The brightness outlines of the Milky Way as polygons on the celestial sphere.
 *
 * @property levels The levels in ascending order. Edges are great-circle arcs between consecutive
 * vertices, so subdivide long edges before drawing a wide field of view. The level-1 band circles the
 * whole sky; it is cut along right ascension 180° by a zero-width slit that must not be stroked.
 */
@ConsistentCopyVisibility
data class MilkyWayOutline internal constructor(val levels: List<Level>) {
    /**
     * The outlines of one brightness level.
     *
     * @property level The brightness level, from 1 (the faint outer glow) to 5 (the bright core).
     * @property polygons Filled rings of J2000 positions. A ring is implicitly closed (its first vertex is
     * not repeated).
     * @property holes Rings of J2000 positions that are cut out of the filled area (dark lanes). Fill
     * [polygons] and [holes] together with the even-odd rule, or fill the polygons and then punch out the
     * holes.
     */
    @ConsistentCopyVisibility
    data class Level internal constructor(
        val level: Int,
        val polygons: List<List<EquatorialCoordinate>>,
        val holes: List<List<EquatorialCoordinate>>,
    )
}

package net.kuecker.lodestar.kit.catalog.contract

// The imports are exactly the ones the Android app's sources use (CatalogService, SkySnapshot, ObjectIcons,
// CommonViews): this file fails to compile when one of those names or members is missing or has moved.
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSearchResult
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.ObjectPosition
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.ephemeris.horizontal
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.HorizontalCoordinate
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.foundation.Vector3
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppContractTests {
    /** CatalogService: `Catalog.loadBundled()`, `catalog?.objectWithID(id)`, `catalog?.search(query, limit)`. */
    @Test
    fun testCatalogServiceUsage() {
        val result = runCatching { Catalog.loadBundled() }
        val catalog: Catalog? = result.getOrNull()
        val sirius: CelestialObject? = catalog?.objectWithID("star.hip32349")
        assertEquals("Sirius", sirius?.name)
        val hits: List<CatalogSearchResult> = catalog?.search("sirius", 5) ?: emptyList()
        assertEquals("star.hip32349", hits.first().obj.id)
        assertEquals("star.hip32349", hits.first().id)
    }

    /** SkySnapshotBuilder: render stars, bodies, listed deep-sky objects, constellations, the Milky Way and the target. */
    @Test
    fun testSkySnapshotUsage() {
        val catalog = Catalog.loadBundled()
        val observer = Observer(GeographicLocation(latitude = 40.0, longitude = -105.0, elevation = 1600.0))
        val frame = SkyFrame(Instant.parse("2026-10-17T03:00:00Z"), observer)

        val stars = catalog.renderStars.asSequence()
            .takeWhile { it.magnitude <= 5.0 }
            .map { star ->
                listOf<Any?>(star.id, frame.horizontalVector(star.unitVector), star.magnitude, star.colorIndex, star.name)
            }
            .toList()
        assertTrue(stars.size in 1500..2500, "${stars.size}")

        val deepSkyGroups = setOf(ObjectGroup.GALAXIES, ObjectGroup.NEBULAE, ObjectGroup.CLUSTERS, ObjectGroup.SPECIAL)
        val deepSky = catalog.listedObjects.mapNotNull { obj ->
            val position = obj.position as? ObjectPosition.Fixed ?: return@mapNotNull null
            if (obj.group !in deepSkyGroups) return@mapNotNull null
            val magnitude = obj.magnitude
            if (magnitude != null && magnitude > 10.0) return@mapNotNull null
            Triple(obj, position.coordinate, obj.angularSize?.let { it.majorArcminutes / 60 })
        }
        assertTrue(deepSky.isNotEmpty())

        val bodies = SolarSystemBody.entries.mapNotNull { body ->
            val id = when (body) {
                SolarSystemBody.SUN -> "sun"
                SolarSystemBody.MOON -> "moon"
                else -> "planet.${body.rawValue}"
            }
            val obj = catalog.objectWithID(id) ?: return@mapNotNull null
            val horizontal: HorizontalCoordinate = frame.horizontal(body, true)
            obj to horizontal
        }
        assertEquals(9, bodies.size)

        val figures = catalog.constellations.map { c ->
            val label: EquatorialCoordinate = c.labelPosition
            listOf(c.abbreviation, c.name, label, c.lines.map { line -> line.map { it.unitVector } })
        }
        assertEquals(88, figures.size)

        val bands = catalog.milkyWay.levels.map { level ->
            Triple(level.level, level.polygons.map { polygon -> polygon.map { it.unitVector } }, level.holes.map { hole -> hole.size })
        }
        assertEquals(5, bands.size)

        val target: CelestialObject = assertNotNull(catalog.objectWithID("dso.M31"))
        val horizontal = frame.horizontal(target, true)
        val direction: Vector3 = horizontal.unitVector
        assertEquals(1.0, direction.length, 1e-12)
    }

    /** ObjectIcons and CommonViews: every category and group constant they match on exists. */
    @Test
    fun testIconMappingsCoverEveryCategoryAndGroup() {
        fun categoryName(category: ObjectCategory): String = when (category) {
            ObjectCategory.SUN -> "sun"
            ObjectCategory.MOON -> "moon"
            ObjectCategory.PLANET -> "planet"
            ObjectCategory.STAR -> "star"
            ObjectCategory.DOUBLE_STAR -> "doubleStar"
            ObjectCategory.GALAXY -> "galaxy"
            ObjectCategory.GALAXY_GROUP -> "galaxyGroup"
            ObjectCategory.EMISSION_NEBULA -> "emissionNebula"
            ObjectCategory.REFLECTION_NEBULA -> "reflectionNebula"
            ObjectCategory.DARK_NEBULA -> "darkNebula"
            ObjectCategory.NEBULA -> "nebula"
            ObjectCategory.PLANETARY_NEBULA -> "planetaryNebula"
            ObjectCategory.SUPERNOVA_REMNANT -> "supernovaRemnant"
            ObjectCategory.OPEN_CLUSTER -> "openCluster"
            ObjectCategory.GLOBULAR_CLUSTER -> "globularCluster"
            ObjectCategory.CLUSTER_WITH_NEBULA -> "clusterWithNebula"
            ObjectCategory.STAR_ASSOCIATION -> "starAssociation"
            ObjectCategory.ASTERISM -> "asterism"
            ObjectCategory.MILKY_WAY -> "milkyWay"
            ObjectCategory.CELESTIAL_POLE -> "celestialPole"
            ObjectCategory.CUSTOM -> "custom"
            ObjectCategory.OTHER -> "other"
        }

        fun groupName(group: ObjectGroup): String = when (group) {
            ObjectGroup.SOLAR_SYSTEM -> "solarSystem"
            ObjectGroup.STARS -> "stars"
            ObjectGroup.GALAXIES -> "galaxies"
            ObjectGroup.NEBULAE -> "nebulae"
            ObjectGroup.CLUSTERS -> "clusters"
            ObjectGroup.SPECIAL -> "special"
        }
        assertEquals(ObjectCategory.entries.map { it.rawValue }, ObjectCategory.entries.map { categoryName(it) })
        assertEquals(ObjectGroup.entries.map { it.rawValue }, ObjectGroup.entries.map { groupName(it) })
        val obj = CelestialObject.custom("Field", EquatorialCoordinate(rightAscension = 10.0, declination = 20.0))
        assertEquals(ObjectGroup.SPECIAL, obj.group)
        assertEquals("Custom Target", obj.category.displayName)
        assertEquals("plus.circle", obj.category.systemImageName)
    }
}

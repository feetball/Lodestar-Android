package net.kuecker.lodestar.features.objects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.ui.graphics.vector.ImageVector
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSortOrder
import net.kuecker.lodestar.features.smallbodies.SmallBodyFavorites
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectCategory
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.shared.icon

/**
 * The listed objects of a catalog in name order, with each object's place in that order, and the numbered lists of
 * the deep-sky catalogues (which also hold the obscure objects the browse list leaves out).
 *
 * Building it sorts the whole listing, so create it once per catalog off the main thread.
 */
internal class ObjectsCatalogIndex(catalog: Catalog) {
    /** Every listed object and every other deep-sky object, ordered by name. */
    private val everything: List<CelestialObject> =
        catalog.sorted(catalog.listedObjects + catalog.deepSkyObjects.filter { !it.isListed }, CatalogSortOrder.NAME)

    /** Every listed object, ordered by name. */
    val listed: List<CelestialObject> = everything.filter { it.isListed }

    private val ranks: Map<String, Int> = HashMap<String, Int>(everything.size * 2).also { map ->
        everything.forEachIndexed { index, obj -> map.putIfAbsent(obj.id, index) }
    }

    private val catalogues: Map<ObjectsCatalogueFilter, List<CelestialObject>> =
        ObjectsCatalogueFilter.entries.filter { it.isCatalogue }.associateWith { filter ->
            filter.catalogues.flatMap { catalog.objects(it) }
                .distinctBy { it.id }
                .mapNotNull { obj -> filter.numberKey(obj)?.let { it to obj } }
                .sortedWith(compareBy({ it.first }, { it.second.id }))
                .map { it.second }
        }

    /**
     * The objects [filter] lists: the listed objects in name order for [ObjectsCatalogueFilter.ALL], else every object
     * numbered in the catalogue, in catalogue order.
     */
    fun pool(filter: ObjectsCatalogueFilter): List<CelestialObject> = catalogues[filter] ?: listed

    /** The position of an object in name order; objects the browse lists do not include come last. */
    fun rank(id: String): Int = ranks[id] ?: Int.MAX_VALUE
}

/** One titled block of rows in the Objects list. */
internal class ObjectsListSection(
    val kind: Kind,
    val objects: List<CelestialObject>,
    /** Favorite comets and asteroids that have no current orbit: listed after [objects] with "no current orbit". */
    val unavailable: List<String> = emptyList(),
) {
    sealed interface Kind {
        data object Favorites : Kind
        data object Recent : Kind
        data class Group(val group: ObjectGroup) : Kind

        /** The comets or the asteroids, which the Solar System group holds besides the Sun, the Moon and the planets. */
        data class SmallBodies(val category: ObjectCategory) : Kind
        data class ByProminence(val prominence: Prominence) : Kind
        data class Catalogue(val filter: ObjectsCatalogueFilter) : Kind
        data object All : Kind
        data object Results : Kind
    }

    /** A stable identifier, also the prefix of the section's list keys. */
    val id: String
        get() = when (kind) {
            Kind.Favorites -> "favorites"
            Kind.Recent -> "recent"
            is Kind.Group -> "group.${kind.group.rawValue}"
            is Kind.SmallBodies -> "category.${kind.category.rawValue}"
            is Kind.ByProminence -> "prominence.${kind.prominence.rawValue}"
            is Kind.Catalogue -> "catalogue.${kind.filter.rawValue}"
            Kind.All -> "all"
            Kind.Results -> "results"
        }

    val title: String
        get() = when (kind) {
            Kind.Favorites -> "Favorites"
            Kind.Recent -> "Recent"
            is Kind.Group -> kind.group.displayName
            is Kind.SmallBodies -> "${kind.category.displayName}s"
            is Kind.ByProminence -> when (kind.prominence) {
                Prominence.SHOWPIECE -> "Showpieces"
                else -> kind.prominence.displayName
            }
            is Kind.Catalogue -> kind.filter.title
            Kind.All -> "All Objects"
            Kind.Results -> "Results"
        }

    val icon: ImageVector
        get() = when (kind) {
            Kind.Favorites -> Icons.Filled.Star
            Kind.Recent -> Icons.Outlined.Schedule
            is Kind.Group -> kind.group.icon
            is Kind.SmallBodies -> kind.category.icon
            is Kind.ByProminence -> when (kind.prominence) {
                Prominence.SHOWPIECE -> Icons.Outlined.AutoAwesome
                Prominence.PROMINENT -> Icons.Outlined.Stars
                Prominence.NOTABLE -> Icons.Filled.Star
                Prominence.OBSCURE -> Icons.Outlined.VisibilityOff
            }
            is Kind.Catalogue -> Icons.AutoMirrored.Outlined.List
            Kind.All -> Icons.AutoMirrored.Outlined.List
            Kind.Results -> Icons.Outlined.Search
        }
}

/** Turns the catalog and the user's options into the sections the Objects list shows. */
internal object ObjectsListBuilder {
    /**
     * Favorites, recents and the objects of the chosen catalogue (the browse listing for "All"), filtered and ordered
     * by [options].
     *
     * Without sky positions the options that need them (the horizon filter and the altitude and rise-time
     * orders) cannot be applied; they are skipped until positions arrive.
     */
    fun sections(
        options: ObjectsListOptions,
        catalog: Catalog,
        index: ObjectsCatalogIndex,
        favoriteIDs: List<String>,
        recentIDs: List<String>,
        positions: Map<String, ObjectSkyPosition>,
    ): List<ObjectsListSection> {
        val catalogue = options.catalogue
        // Catalogue number only orders a catalogue; name order is the pool's own order for "All".
        val fallbackOrder = if (catalogue.isCatalogue) ObjectsSortOrder.NUMBER else ObjectsSortOrder.NAME
        val sortOrder = when {
            options.sortOrder == ObjectsSortOrder.NUMBER && !catalogue.isCatalogue -> ObjectsSortOrder.NAME
            options.sortOrder.needsSkyPositions && positions.isEmpty() -> fallbackOrder
            else -> options.sortOrder
        }
        val effective = options.copy(
            sortOrder = sortOrder,
            showsOnlyAboveHorizon = options.showsOnlyAboveHorizon && positions.isNotEmpty(),
        )
        fun resolved(ids: List<String>): List<CelestialObject> {
            val seen = HashSet<String>()
            return ids.mapNotNull { id -> if (seen.add(id)) catalog.objectWithID(id) else null }
                .filter { passes(it, effective, positions) }
        }

        val sections = mutableListOf<ObjectsListSection>()
        val favorites = ordered(resolved(favoriteIDs), effective, index, positions)
        // A favorite comet that dropped out of the orbit data stays, as "no current orbit", unless a filter is on.
        val lostFavorites = if (effective.showsOnlyAboveHorizon || effective.showsOnlyPopular || catalogue.isCatalogue) {
            emptyList()
        } else {
            SmallBodyFavorites.withoutOrbit(favoriteIDs, catalog)
        }
        if (favorites.isNotEmpty() || lostFavorites.isNotEmpty()) {
            sections.add(ObjectsListSection(ObjectsListSection.Kind.Favorites, favorites, lostFavorites))
        }
        // Recents keep their most-recent-first order whatever the sort order is.
        val recents = resolved(recentIDs)
        if (recents.isNotEmpty()) sections.add(ObjectsListSection(ObjectsListSection.Kind.Recent, recents))

        // The pool is in name order (All) or catalogue order, and filtering and grouping keep that order, so a list
        // sorted the same way needs no sorting.
        val poolOrder = if (catalogue.isCatalogue) ObjectsSortOrder.NUMBER else ObjectsSortOrder.NAME
        val visible = index.pool(catalogue).filter { passes(it, effective, positions) }
        when (effective.grouping) {
            ObjectsGrouping.NONE -> {
                val all = ordered(visible, effective, index, positions, presorted = poolOrder)
                if (all.isNotEmpty()) {
                    val kind = if (catalogue.isCatalogue) {
                        ObjectsListSection.Kind.Catalogue(catalogue)
                    } else {
                        ObjectsListSection.Kind.All
                    }
                    sections.add(ObjectsListSection(kind, all))
                }
            }
            ObjectsGrouping.TYPE -> {
                val byGroup = visible.groupBy { it.group }
                for (group in ObjectGroup.entries) {
                    val members = byGroup[group] ?: continue
                    // The Solar System group holds the comets and asteroids too: they get a section each.
                    val smallBodies = members.groupBy { it.category }
                    val others = members.filter { it.smallBody == null }
                    val parts = listOf(ObjectCategory.COMET, ObjectCategory.ASTEROID).mapNotNull { category ->
                        smallBodies[category]?.let { ObjectsListSection.Kind.SmallBodies(category) to it }
                    }
                    val split = if (group == ObjectGroup.SOLAR_SYSTEM) {
                        listOf<Pair<ObjectsListSection.Kind, List<CelestialObject>>>(ObjectsListSection.Kind.Group(group) to others) + parts
                    } else {
                        listOf(ObjectsListSection.Kind.Group(group) to members)
                    }
                    for ((kind, part) in split) {
                        if (part.isEmpty()) continue
                        sections.add(ObjectsListSection(kind, ordered(part, effective, index, positions, presorted = poolOrder)))
                    }
                }
            }
            ObjectsGrouping.PROMINENCE -> {
                val byProminence = visible.groupBy { it.prominence }
                for (prominence in Prominence.entries) {
                    val members = byProminence[prominence] ?: continue
                    val objects = ordered(members, effective, index, positions, presorted = poolOrder)
                    sections.add(ObjectsListSection(ObjectsListSection.Kind.ByProminence(prominence), objects))
                }
            }
        }
        return sections
    }

    // Filtering

    private fun passes(obj: CelestialObject, options: ObjectsListOptions, positions: Map<String, ObjectSkyPosition>): Boolean {
        if (options.catalogue.isCatalogue && options.catalogue.numberKey(obj) == null) return false
        if (options.showsOnlyPopular && obj.prominence > Prominence.PROMINENT) return false
        if (options.showsOnlyAboveHorizon && positions[obj.id]?.isUp != true) return false
        // A comet or an asteroid has no catalog magnitude: its current one decides.
        val magnitude = obj.magnitude ?: if (obj.smallBody != null) positions[obj.id]?.magnitude else null
        if (options.hidesFaintObjects && magnitude != null && magnitude > ObjectsListOptions.FAINT_MAGNITUDE_LIMIT) return false
        return true
    }

    // Ordering

    /**
     * Sort keys compared in order; ties fall back to name order and then the identifier, so the result never
     * depends on the input order.
     */
    private class SortKey(
        val tier: Int,
        val value: Double,
        val secondary: Int,
        val rank: Int,
        val id: String,
    ) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            if (tier != other.tier) return tier.compareTo(other.tier)
            if (value != other.value) return if (value < other.value) -1 else 1
            if (secondary != other.secondary) return secondary.compareTo(other.secondary)
            if (rank != other.rank) return rank.compareTo(other.rank)
            return id.compareTo(other.id)
        }
    }

    /** [objects] in the order [options] ask for. [presorted] is the order they already are in, which saves sorting them. */
    private fun ordered(
        objects: List<CelestialObject>,
        options: ObjectsListOptions,
        index: ObjectsCatalogIndex,
        positions: Map<String, ObjectSkyPosition>,
        presorted: ObjectsSortOrder? = null,
    ): List<CelestialObject> {
        val order = options.sortOrder
        if (order == presorted) return objects

        fun key(obj: CelestialObject): SortKey {
            val position = positions[obj.id]
            // A value that is not a number would break the ordering; it sorts last like a missing one.
            fun make(tier: Int = 0, value: Double = 0.0, secondary: Int = 0) =
                SortKey(tier, if (value.isNaN()) Double.POSITIVE_INFINITY else value, secondary, index.rank(obj.id), obj.id)
            return when (order) {
                ObjectsSortOrder.NAME -> make()
                ObjectsSortOrder.NUMBER -> make(value = options.catalogue.numberKey(obj) ?: Double.POSITIVE_INFINITY)
                // Showpieces first, then the brightest; dark nebulae have no magnitude and follow by name.
                ObjectsSortOrder.PROMINENCE ->
                    make(tier = obj.prominence.rawValue, value = position?.magnitude ?: obj.magnitude ?: Double.POSITIVE_INFINITY)
                // Brightest first; objects with no known magnitude come last, the more prominent of them first.
                ObjectsSortOrder.BRIGHTNESS -> make(
                    value = position?.magnitude ?: obj.magnitude ?: Double.POSITIVE_INFINITY,
                    secondary = obj.prominence.rawValue,
                )
                // Highest first; objects without a position come last.
                ObjectsSortOrder.ALTITUDE -> make(value = position?.let { -it.altitude } ?: Double.POSITIVE_INFINITY)
                // Objects already up come first, highest first; then the soonest risings; then those that never rise.
                ObjectsSortOrder.RISE_TIME -> when {
                    position == null -> make(tier = 2)
                    position.isUp -> make(value = -position.altitude)
                    else -> {
                        val rise = position.nextRise
                        if (rise == null) make(tier = 2) else make(tier = 1, value = rise.epochSecond + rise.nano / 1e9)
                    }
                }
            }
        }
        return objects.map { it to key(it) }.sortedBy { it.second }.map { it.first }
    }
}

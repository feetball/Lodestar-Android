package net.kuecker.lodestar.features.objects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.graphics.vector.ImageVector
import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSortOrder
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.ObjectGroup
import net.kuecker.lodestar.shared.icon

/**
 * The listed objects of a catalog in name order, with each object's place in that order.
 *
 * Building it sorts the whole listing, so create it once per catalog off the main thread.
 */
internal class ObjectsCatalogIndex(catalog: Catalog) {
    /** Every listed object, ordered by name. */
    val listed: List<CelestialObject> = catalog.sorted(catalog.listedObjects, CatalogSortOrder.NAME)

    private val ranks: Map<String, Int> = HashMap<String, Int>(listed.size * 2).also { map ->
        listed.forEachIndexed { index, obj -> map.putIfAbsent(obj.id, index) }
    }

    /** The position of an object in name order; objects the browse lists do not include come last. */
    fun rank(id: String): Int = ranks[id] ?: Int.MAX_VALUE
}

/** One titled block of rows in the Objects list. */
internal class ObjectsListSection(val kind: Kind, val objects: List<CelestialObject>) {
    sealed interface Kind {
        data object Favorites : Kind
        data object Recent : Kind
        data class Group(val group: ObjectGroup) : Kind
        data object All : Kind
        data object Results : Kind
    }

    /** A stable identifier, also the prefix of the section's list keys. */
    val id: String
        get() = when (kind) {
            Kind.Favorites -> "favorites"
            Kind.Recent -> "recent"
            is Kind.Group -> "group.${kind.group.rawValue}"
            Kind.All -> "all"
            Kind.Results -> "results"
        }

    val title: String
        get() = when (kind) {
            Kind.Favorites -> "Favorites"
            Kind.Recent -> "Recent"
            is Kind.Group -> kind.group.displayName
            Kind.All -> "All Objects"
            Kind.Results -> "Results"
        }

    val icon: ImageVector
        get() = when (kind) {
            Kind.Favorites -> Icons.Filled.Star
            Kind.Recent -> Icons.Outlined.Schedule
            is Kind.Group -> kind.group.icon
            Kind.All -> Icons.AutoMirrored.Outlined.List
            Kind.Results -> Icons.Outlined.Search
        }
}

/** Turns the catalog and the user's options into the sections the Objects list shows. */
internal object ObjectsListBuilder {
    /**
     * Favorites, recents and the catalog's listed objects, filtered and ordered by [options].
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
        val effective = if (positions.isEmpty()) {
            options.copy(
                showsOnlyAboveHorizon = false,
                sortOrder = if (options.sortOrder.needsSkyPositions) ObjectsSortOrder.NAME else options.sortOrder,
            )
        } else {
            options
        }
        fun resolved(ids: List<String>): List<CelestialObject> {
            val seen = HashSet<String>()
            return ids.mapNotNull { id -> if (seen.add(id)) catalog.objectWithID(id) else null }
                .filter { passes(it, effective, positions) }
        }

        val sections = mutableListOf<ObjectsListSection>()
        val favorites = ordered(resolved(favoriteIDs), effective.sortOrder, index, positions)
        if (favorites.isNotEmpty()) sections.add(ObjectsListSection(ObjectsListSection.Kind.Favorites, favorites))
        // Recents keep their most-recent-first order whatever the sort order is.
        val recents = resolved(recentIDs)
        if (recents.isNotEmpty()) sections.add(ObjectsListSection(ObjectsListSection.Kind.Recent, recents))

        // The index is in name order and filtering and grouping keep that order, so these lists need no name sort.
        val visible = index.listed.filter { passes(it, effective, positions) }
        when (effective.grouping) {
            ObjectsGrouping.NONE -> {
                val all = ordered(visible, effective.sortOrder, index, positions, isInNameOrder = true)
                if (all.isNotEmpty()) sections.add(ObjectsListSection(ObjectsListSection.Kind.All, all))
            }
            ObjectsGrouping.TYPE -> {
                val byGroup = visible.groupBy { it.group }
                for (group in ObjectGroup.entries) {
                    val members = byGroup[group] ?: continue
                    val objects = ordered(members, effective.sortOrder, index, positions, isInNameOrder = true)
                    sections.add(ObjectsListSection(ObjectsListSection.Kind.Group(group), objects))
                }
            }
        }
        return sections
    }

    // Filtering

    private fun passes(obj: CelestialObject, options: ObjectsListOptions, positions: Map<String, ObjectSkyPosition>): Boolean {
        if (options.showsOnlyAboveHorizon && positions[obj.id]?.isAboveHorizon != true) return false
        val magnitude = obj.magnitude
        if (options.hidesFaintObjects && magnitude != null && magnitude > ObjectsListOptions.FAINT_MAGNITUDE_LIMIT) return false
        return true
    }

    // Ordering

    /**
     * Sort keys compared in order; ties fall back to name order and then the identifier, so the result never
     * depends on the input order.
     */
    private class SortKey(val tier: Int, val value: Double, val rank: Int, val id: String) : Comparable<SortKey> {
        override fun compareTo(other: SortKey): Int {
            if (tier != other.tier) return tier.compareTo(other.tier)
            if (value != other.value) return if (value < other.value) -1 else 1
            if (rank != other.rank) return rank.compareTo(other.rank)
            return id.compareTo(other.id)
        }
    }

    /** [objects] in the given order. [isInNameOrder] says they already are in name order, which saves sorting them. */
    private fun ordered(
        objects: List<CelestialObject>,
        order: ObjectsSortOrder,
        index: ObjectsCatalogIndex,
        positions: Map<String, ObjectSkyPosition>,
        isInNameOrder: Boolean = false,
    ): List<CelestialObject> {
        if (order == ObjectsSortOrder.NAME && isInNameOrder) return objects

        fun key(obj: CelestialObject): SortKey {
            val position = positions[obj.id]
            // A value that is not a number would break the ordering; it sorts last like a missing one.
            fun make(tier: Int = 0, value: Double = 0.0) =
                SortKey(tier, if (value.isNaN()) Double.POSITIVE_INFINITY else value, index.rank(obj.id), obj.id)
            return when (order) {
                ObjectsSortOrder.NAME -> make()
                // Brightest first; objects with no known magnitude come last.
                ObjectsSortOrder.BRIGHTNESS -> make(value = position?.magnitude ?: obj.magnitude ?: Double.POSITIVE_INFINITY)
                // Highest first; objects without a position come last.
                ObjectsSortOrder.ALTITUDE -> make(value = position?.let { -it.altitude } ?: Double.POSITIVE_INFINITY)
                // Objects already up come first, highest first; then the soonest risings; then those that never rise.
                ObjectsSortOrder.RISE_TIME -> when {
                    position == null -> make(tier = 2)
                    position.isAboveHorizon -> make(value = -position.altitude)
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

package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.SmallBody
import java.util.EnumMap

/**
 * The comets and asteroids that were added to a catalog: their objects, their own small search index and the order
 * of their listings. The bundled data of the catalog is not touched, so a catalog with other small bodies shares it.
 */
internal class SmallBodyTable private constructor(
    /** The objects, brightest estimated peak first (then by name and identifier). */
    val objects: List<CelestialObject>,
    /** The listed objects, in the order of [objects]. */
    val listed: List<CelestialObject>,
    /** The search index over [objects] (its object numbers are positions in [objects]); `null` when there are none. */
    val searchIndex: SearchIndex?,
    private val indexByID: Map<String, Int>,
) {
    /** `true` when no small body was added. */
    val isEmpty: Boolean get() = objects.isEmpty()

    /** The bodies of [objects], in their order. */
    val bodies: List<SmallBody> get() = objects.mapNotNull { it.smallBody }

    /** The object with an identifier, in constant time. */
    fun objectWithID(id: String): CelestialObject? = indexByID[id]?.let { objects[it] }

    /**
     * [base], the listed objects of the catalog by group, with the listed small bodies merged into their groups in name
     * order. Groups without small bodies are shared.
     */
    fun mergedListedByGroup(base: Map<ObjectGroup, List<CelestialObject>>): Map<ObjectGroup, List<CelestialObject>> {
        if (listed.isEmpty()) return base
        val result = EnumMap<ObjectGroup, List<CelestialObject>>(ObjectGroup::class.java)
        result.putAll(base)
        for ((group, added) in listed.groupBy { it.group }) {
            result[group] = CatalogOrdering.sorted((base[group] ?: emptyList()) + added, CatalogSortOrder.NAME)
        }
        return result
    }

    companion object {
        /** The table of a catalog without small bodies. */
        val empty = SmallBodyTable(emptyList(), emptyList(), null, emptyMap())

        /**
         * Makes the table of [bodies]. Bodies with the same identifier count once, the last one winning; at most
         * [Catalog.maximumSmallBodyCount] different bodies are taken, the first ones in the order of the list.
         */
        fun build(bodies: List<SmallBody>): SmallBodyTable {
            val distinct = LinkedHashMap<String, SmallBody>()
            for (body in bodies) {
                if (distinct.size >= Catalog.maximumSmallBodyCount && !distinct.containsKey(body.id)) continue
                distinct[body.id] = body
            }
            if (distinct.isEmpty()) return empty
            val drafts = distinct.values.map { CatalogFactory.smallBody(it) }
            val unordered = CatalogOrdering.items(drafts.map { it.obj })
            java.util.Arrays.sort(unordered, CatalogOrdering.magnitudeComparator)

            val table = CatalogAssembler.ObjectTable()
            for (item in unordered) table.append(drafts[item.index])
            val items = CatalogOrdering.items(table.objects) { table.nameKeys[it] }
            val ranked = items.sortedWith(CatalogOrdering.searchComparator)
            val index = table.search.finish(IntArray(ranked.size) { ranked[it].index })
            return SmallBodyTable(
                objects = table.objects,
                listed = table.objects.filter { it.isListed },
                searchIndex = index,
                indexByID = table.objects.withIndex().associate { (position, obj) -> obj.id to position },
            )
        }
    }
}

package net.kuecker.lodestar.kit.catalog

/** How a list of objects can be ordered. */
enum class CatalogSortOrder(val rawValue: String) {
    /**
     * Alphabetically by display name, ignoring case, diacritics and punctuation, with numbers in
     * numeric order ("M 2" before "M 10"). Names without any Latin letter or digit, which a custom
     * target can have, come first, ordered by their characters.
     */
    NAME("name"),

    /**
     * Brightest first. Objects without a known magnitude come last. Solar-system bodies, whose
     * brightness changes, are placed by a typical peak magnitude (Venus before Jupiter, and so on).
     */
    MAGNITUDE("magnitude"),
    ;

    companion object {
        /** The order whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): CatalogSortOrder? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The comparisons behind sorting and search ranking.
 *
 * Every order ends in the object's identifier, so the result never depends on the input order.
 */
internal object CatalogOrdering {
    /** An object with the values the comparisons need, computed once. */
    class Item(
        val index: Int,
        val name: String,
        val nameKey: ByteArray,
        val magnitude: Double,
        val isListed: Boolean,
        val id: String,
    )

    /** The comparison [nameOrder] as a three-way [Comparator]. */
    val nameComparator: Comparator<Item> = Comparator { lhs, rhs -> compareNames(lhs, rhs) }

    /** The comparison [magnitudeOrder] as a three-way [Comparator]. */
    val magnitudeComparator: Comparator<Item> = Comparator { lhs, rhs -> compareMagnitudes(lhs, rhs) }

    /** The comparison [searchOrder] as a three-way [Comparator]. */
    val searchComparator: Comparator<Item> = Comparator { lhs, rhs -> compareForSearch(lhs, rhs) }

    fun items(objects: List<CelestialObject>): Array<Item> =
        items(objects) { SearchText.key(objects[it].name).bytes }

    /**
     * Like [items], with the key of each name given by [nameKey] (by the object's position) instead of made
     * again; the catalog builder has made them for the search index already.
     */
    inline fun items(objects: List<CelestialObject>, nameKey: (Int) -> ByteArray): Array<Item> =
        Array(objects.size) { index ->
            val obj = objects[index]
            Item(
                index = index,
                name = obj.name,
                nameKey = nameKey(index),
                magnitude = obj.rankingMagnitude,
                isListed = obj.isListed,
                id = obj.id,
            )
        }

    fun sorted(objects: List<CelestialObject>, order: CatalogSortOrder): List<CelestialObject> {
        val items = items(objects)
        when (order) {
            CatalogSortOrder.NAME -> java.util.Arrays.sort(items, nameComparator)
            CatalogSortOrder.MAGNITUDE -> java.util.Arrays.sort(items, magnitudeComparator)
        }
        return List(items.size) { objects[items[it].index] }
    }

    /**
     * Alphabetical, then by identifier.
     *
     * Names without a single Latin letter or digit (a custom target called "Орион") have an empty
     * key and come first; among themselves they are ordered by their characters.
     */
    fun nameOrder(lhs: Item, rhs: Item): Boolean = compareNames(lhs, rhs) < 0

    /** Brighter first, then alphabetical. */
    fun magnitudeOrder(lhs: Item, rhs: Item): Boolean = compareMagnitudes(lhs, rhs) < 0

    /**
     * Listed objects first, then brighter first, then alphabetical: the order of equally good
     * search matches.
     */
    fun searchOrder(lhs: Item, rhs: Item): Boolean = compareForSearch(lhs, rhs) < 0

    private fun compareNames(lhs: Item, rhs: Item): Int {
        val natural = SearchText.naturalCompare(lhs.nameKey, rhs.nameKey)
        if (natural != 0) return natural
        if (lhs.nameKey.isEmpty()) {
            val byName = CatalogText.compare(lhs.name, rhs.name)
            if (byName != 0) return byName
        }
        return CatalogText.compare(lhs.id, rhs.id)
    }

    private fun compareMagnitudes(lhs: Item, rhs: Item): Int {
        if (lhs.magnitude != rhs.magnitude) return if (lhs.magnitude < rhs.magnitude) -1 else 1
        return compareNames(lhs, rhs)
    }

    private fun compareForSearch(lhs: Item, rhs: Item): Int {
        if (lhs.isListed != rhs.isListed) return if (lhs.isListed) -1 else 1
        return compareMagnitudes(lhs, rhs)
    }
}

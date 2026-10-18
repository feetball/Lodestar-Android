package net.kuecker.lodestar.kit.catalog

/**
 * One match of [Catalog.search].
 *
 * @property obj The object found (Swift `object`).
 * @property matchedText The name or designation of the object that matched, as displayed ("α CMa").
 * @property kind How well the text matched.
 */
@ConsistentCopyVisibility
data class CatalogSearchResult internal constructor(
    val obj: CelestialObject,
    val matchedText: String,
    val kind: MatchKind,
) {
    /** How well a result matches the query, best first (the entries are ordered from the best match to the worst). */
    enum class MatchKind(val rawValue: Int) {
        /** The query is the whole text, ignoring case, accents and punctuation. */
        EXACT(0),

        /** The text starts with the query. */
        PREFIX(1),

        /** A later word of the text starts with the query ("neb" in "Orion Nebula"). */
        WORD_PREFIX(2),

        /** The query occurs somewhere inside the text. */
        CONTAINS(3),

        /**
         * The letters of the query occur in order in the text, the first one starting a word
         * ("andgal" in "Andromeda Galaxy"). Such suggestions are only made for queries of three or
         * more characters, and only when nothing matches in any of the better ways.
         */
        FUZZY(4),
        ;

        companion object {
            /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
            fun fromRawValue(raw: Int): MatchKind? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    /** The identifier of the object found. */
    val id: String get() = obj.id
}

/** Runs [query] against the catalog's search index (and that of its small bodies); the implementation of [Catalog.search]. */
internal fun Catalog.performSearch(query: String, limit: Int): List<CatalogSearchResult> {
    val key = SearchText.key(query, maximumScalars = SearchText.maximumQueryScalars)
    val bundled = searchIndex.search(key.bytes, limit).map { hit ->
        val obj = objects[hit.objectIndex]
        val source = searchIndex.source(hit.keyIndex)
        CatalogSearchResult(obj, text(source.entry, obj), hit.kind)
    }
    val smallIndex = smallBodyTable.searchIndex ?: return bundled
    val own = smallIndex.search(key.bytes, limit).map { hit ->
        val obj = smallBodyTable.objects[hit.objectIndex]
        val source = smallIndex.source(hit.keyIndex)
        CatalogSearchResult(obj, text(source.entry, obj), hit.kind)
    }
    if (own.isEmpty()) return bundled
    return merged(bundled, own, limit)
}

/**
 * The best [limit] of two result lists, each of them already the best of its own index: better kinds first, equal kinds
 * in the order of equally good matches ([CatalogOrdering.searchComparator], which ends in the identifier and so is
 * total). A loose [CatalogSearchResult.MatchKind.FUZZY] suggestion only counts when there is no other match in either list,
 * as in a single index.
 */
private fun merged(first: List<CatalogSearchResult>, second: List<CatalogSearchResult>, limit: Int): List<CatalogSearchResult> {
    class Ranked(val result: CatalogSearchResult, val item: CatalogOrdering.Item)

    val all = ArrayList<Ranked>(first.size + second.size)
    for (result in first) all.add(Ranked(result, CatalogOrdering.item(result.obj)))
    for (result in second) all.add(Ranked(result, CatalogOrdering.item(result.obj)))
    all.sortWith { lhs, rhs ->
        if (lhs.result.kind != rhs.result.kind) {
            lhs.result.kind.rawValue.compareTo(rhs.result.kind.rawValue)
        } else {
            CatalogOrdering.searchComparator.compare(lhs.item, rhs.item)
        }
    }
    val hasBetterThanFuzzy = all.any { it.result.kind != CatalogSearchResult.MatchKind.FUZZY }
    val kept = if (hasBetterThanFuzzy) all.filter { it.result.kind != CatalogSearchResult.MatchKind.FUZZY } else all
    return kept.take(limit).map { it.result }
}

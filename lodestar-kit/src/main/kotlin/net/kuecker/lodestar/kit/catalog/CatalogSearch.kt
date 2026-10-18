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

/** Runs [query] against the catalog's search index; the implementation of [Catalog.search]. */
internal fun Catalog.performSearch(query: String, limit: Int): List<CatalogSearchResult> {
    val key = SearchText.key(query, maximumScalars = SearchText.maximumQueryScalars)
    return searchIndex.search(key.bytes, limit).map { hit ->
        val obj = objects[hit.objectIndex]
        val source = searchIndex.source(hit.keyIndex)
        CatalogSearchResult(obj, text(source.entry, obj), hit.kind)
    }
}

package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.catalog.CatalogSearchResult
import net.kuecker.lodestar.kit.catalog.CelestialObject

/** Finds the catalog objects of imported rows. */
object ObservingListMatcher {
    /** The most results of a catalog search that are looked at for one text. */
    private const val searchLimit = 8

    /**
     * The catalog object a row stands for: the first of its `searchTerms` (designation, alternates, name) that has an exact
     * match in [Catalog.search], which ignores case, accents, punctuation and spacing ("m31", "M 31" and "Messier 31" are
     * one text). Matches that are only a prefix or part of a name are not taken.
     */
    fun match(target: ImportedTarget, catalog: Catalog): CelestialObject? {
        for (term in target.searchTerms) {
            val result = catalog.search(term, searchLimit).firstOrNull { it.kind == CatalogSearchResult.MatchKind.EXACT }
            if (result != null) return result.obj
        }
        return null
    }

    /**
     * Matches every row. Cost: one catalog search per search term, a fraction of a millisecond each; a full list of 5,000
     * rows takes a second or two on a phone, so run it off the main thread.
     */
    fun match(targets: List<ImportedTarget>, catalog: Catalog): List<ObservingListMatch> =
        targets.map { ObservingListMatch(it, match(it, catalog)) }
}

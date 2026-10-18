package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.foundation.Vector3
import java.io.IOException

/**
 * A star as the sky overlay draws it.
 *
 * @property id The identifier of the star's [CelestialObject].
 * @property unitVector The J2000 catalog direction as a unit vector (x towards the equinox, z towards the
 * pole). Proper motion is not applied; at the precision of a camera overlay it is negligible except for a
 * handful of fast stars. Use `SkyFrame.horizontalVector(unitVector)` to place it.
 * @property magnitude The visual magnitude.
 * @property colorIndex The B−V color index, if known.
 * @property name The proper name, for the stars that have one.
 */
@ConsistentCopyVisibility
data class RenderStar internal constructor(
    val id: String,
    val unitVector: Vector3,
    val magnitude: Double,
    val colorIndex: Double?,
    val name: String?,
)

/**
 * The sky catalog: stars, deep-sky objects, solar-system bodies, constellations and the Milky Way.
 *
 * A catalog is immutable once created and safe to share between threads. Loading the bundled
 * data takes a fraction of a second, so create one catalog and keep it.
 *
 * ```kotlin
 * val catalog = Catalog.loadBundled()
 * val hits = catalog.search("m31")
 * ```
 *
 * @property objects Every object: the Sun, the Moon and the planets, then the Milky Way core and the
 * celestial poles, then the deep-sky objects (Messier, NGC, IC and others), then the stars from brightest
 * to faintest.
 * @property listedObjects The objects that browse lists show ([CelestialObject.isListed]), in the order of
 * [objects].
 * @property constellations The 88 constellations in alphabetical order of their names.
 * @property milkyWay The brightness outlines of the Milky Way.
 * @property renderStars All stars from brightest to faintest, prepared for drawing.
 * @property loadReport What happened to the records of the data files while loading.
 */
class Catalog private constructor(contents: CatalogContents) {
    val objects: List<CelestialObject> = contents.objects
    val listedObjects: List<CelestialObject> = contents.objects.filter { it.isListed }
    val constellations: List<Constellation> = contents.constellations.constellations
    val milkyWay: MilkyWayOutline = contents.milkyWay
    val renderStars: List<RenderStar> = contents.renderStars
    val loadReport: CatalogLoadReport = contents.report

    internal val searchIndex: SearchIndex = contents.searchIndex
    private val indexByID: Map<String, Int> = contents.indexByID
    private val constellationDirectory: ConstellationDirectory = contents.constellations
    private val listedByGroup: Map<ObjectGroup, List<CelestialObject>> = contents.listedByGroup

    /**
     * Creates a catalog from the JSON of the four data files (see `Tools/README.md` of the Swift package for
     * the format).
     *
     * Records that cannot be decoded, have values out of range, lack a required value or repeat an
     * identifier are skipped and counted in [loadReport].
     *
     * @throws CatalogError when a file is not valid JSON of the expected shape, declares an
     * unsupported schema, exceeds [maximumFileBytes], or contains no valid star or deep-sky object.
     */
    constructor(starsData: ByteArray, dsosData: ByteArray, constellationsData: ByteArray, milkyWayData: ByteArray) :
        this(CatalogAssembler.assemble(starsData, dsosData, constellationsData, milkyWayData))

    /**
     * The object with an identifier, in constant time (Swift `object(id:)`).
     *
     * Deep-sky objects that are known by an acronym also answer to it: `"dso.LMC"` finds the Large
     * Magellanic Cloud, whose own `id` is `"dso.ESO56-115"`.
     */
    fun objectWithID(id: String): CelestialObject? = indexByID[id]?.let { objects[it] }

    /**
     * The objects that best match a query, best first.
     *
     * Matching ignores case, diacritics, character width, punctuation and spacing, reads Greek
     * letters either way ("α" is "alpha" is "alp"), takes superscript digits as digits ("α¹" is
     * "alpha1") and drops leading zeros of numbers. So "m31", "M 31", "Messier 31" and "M031" are the
     * same query, as are "ngc224" and "NGC 0224", and "alpha cma", "alpha canis majoris" and "α CMa".
     * It matches names, common names and the designations of every object: Messier, NGC and IC
     * numbers, Bayer and Flamsteed designations with the constellation as abbreviation or genitive,
     * and HR, HD and HIP numbers.
     *
     * Results are ordered by [CatalogSearchResult.MatchKind]; matches of the same kind are ordered by listed
     * objects first, then brighter objects, then name. `MatchKind.FUZZY` results only appear when there
     * are no other matches.
     *
     * (In Swift this method is declared in `CatalogSearch.swift`; here it is a member so that callers need
     * no import.)
     *
     * @param query The text to look for. Only its first [maximumQueryLength] characters are used. A
     * query without letters or digits finds nothing.
     * @param limit The most results to return; a limit of zero or less returns none.
     */
    fun search(query: String, limit: Int = 50): List<CatalogSearchResult> = performSearch(query, limit)

    /** The listed objects of a group, sorted by name (Swift `objects(in:)`). */
    fun objects(group: ObjectGroup): List<CelestialObject> = listedByGroup[group] ?: emptyList()

    /** The constellation with an IAU abbreviation, ignoring case ("cma" finds "CMa"). */
    fun constellation(abbreviation: String): Constellation? = constellationDirectory.constellation(abbreviation)

    /**
     * [objects] ordered by [order], for example the objects of a group or a search.
     *
     * The sort is deterministic: objects that compare equal are ordered by identifier.
     */
    fun sorted(objects: List<CelestialObject>, order: CatalogSortOrder): List<CelestialObject> =
        CatalogOrdering.sorted(objects, order)

    /**
     * One of an object's displayed texts, numbered as in `KeyText.entry`: 0 is the name, then the
     * designations, then the alternate names (Swift `text(ofEntry:in:)`).
     */
    internal fun text(entry: Int, obj: CelestialObject): String {
        if (entry == 0) return obj.name
        if (entry <= obj.designations.size) return obj.designations[entry - 1]
        val alternate = entry - obj.designations.size - 1
        return if (alternate < obj.alternateNames.size) obj.alternateNames[alternate] else obj.name
    }

    companion object {
        /** The only version of the data files' `schema` this code reads. */
        const val supportedSchemaVersion: Int = 1

        /** The largest data file that is accepted, in bytes. */
        const val maximumFileBytes: Int = 64 * 1024 * 1024

        /** The longest query that is looked at, in Unicode scalars; the rest is ignored. */
        const val maximumQueryLength: Int = SearchText.maximumQueryScalars

        /**
         * Loads the catalog from the data files bundled with the library.
         *
         * This reads and checks about 1.4 MB of JSON and builds the search index, which takes a few
         * hundred milliseconds: call it off the main thread.
         *
         * @throws CatalogError when a file is missing, unreadable or unusable as a whole. Single
         * bad records are skipped and counted in [loadReport] instead.
         */
        fun loadBundled(): Catalog = Catalog(
            starsData = bundledData(CatalogFile.STARS),
            dsosData = bundledData(CatalogFile.DEEP_SKY_OBJECTS),
            constellationsData = bundledData(CatalogFile.CONSTELLATIONS),
            milkyWayData = bundledData(CatalogFile.MILKY_WAY),
        )

        /**
         * Where the data files are in the library's resources. The path is absolute on purpose: a name relative
         * to this class would be resolved against the class's package as it is at run time, which a minifier
         * (R8) may have renamed, while the resource files keep their paths.
         */
        private const val RESOURCE_DIRECTORY = "/net/kuecker/lodestar/kit/catalog/"

        /** The raw contents of one of the bundled data files (a resource in the package folder of this class). */
        internal fun bundledData(file: CatalogFile): ByteArray {
            val stream = Catalog::class.java.getResourceAsStream(RESOURCE_DIRECTORY + file.fileName)
                ?: throw CatalogError.MissingResource(file)
            try {
                return stream.use { it.readBytes() }
            } catch (error: IOException) {
                throw CatalogError.Unreadable(file, error.message ?: error.toString())
            }
        }
    }
}

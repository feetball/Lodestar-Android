package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.comets.SmallBody
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
 * The sky catalog: stars, deep-sky objects, solar-system bodies, constellations and the Milky Way, and, when they
 * were added, comets and asteroids.
 *
 * A catalog is immutable once created and safe to share between threads. Loading the bundled
 * data takes a fraction of a second, so create one catalog and keep it. Comets and asteroids arrive at run time (the
 * orbit files are downloaded); [withSmallBodies] and [addingSmallBodies] make a new catalog with them in a
 * millisecond or two and share the bundled data, so the base catalog is loaded once.
 *
 * ```kotlin
 * val base = Catalog.loadBundled()
 * val hits = base.search("m31")
 * val catalog = base.withSmallBodies(BundledAsteroids.load().bodies + downloadedComets)
 * val vesta = catalog.search("vesta").first().obj          // CelestialObject, category ASTEROID
 * ```
 *
 * @property objects Every object: the Sun, the Moon and the planets, then the Milky Way core and the
 * celestial poles, then the deep-sky objects (Messier, NGC, IC and others), then the stars from brightest
 * to faintest, then the comets and asteroids that were added, those with the brightest [CelestialObject.estimatedPeakMagnitude] first.
 * @property listedObjects The objects that browse lists show ([CelestialObject.isListed]), in the order of
 * [objects].
 * @property deepSkyObjects Every deep-sky object, listed or not, in the order of [objects].
 * @property constellations The 88 constellations in alphabetical order of their names.
 * @property milkyWay The brightness outlines of the Milky Way.
 * @property renderStars All stars from brightest to faintest, prepared for drawing.
 * @property loadReport What happened to the records of the data files while loading.
 */
class Catalog private constructor(private val contents: CatalogContents, small: SmallBodyTable) {
    val objects: List<CelestialObject> = if (small.isEmpty) contents.objects else contents.objects + small.objects
    val listedObjects: List<CelestialObject> = if (small.listed.isEmpty()) contents.listed else contents.listed + small.listed
    val deepSkyObjects: List<CelestialObject> = contents.deepSky
    val constellations: List<Constellation> = contents.constellations.constellations
    val milkyWay: MilkyWayOutline = contents.milkyWay
    val renderStars: List<RenderStar> = contents.renderStars
    val loadReport: CatalogLoadReport = contents.report

    /** The comets and asteroids that were added with [withSmallBodies] or [addingSmallBodies], as in [objects]. */
    val smallBodies: List<CelestialObject> = small.objects

    /** The search index of the bundled objects. */
    internal val searchIndex: SearchIndex = contents.searchIndex

    /** The small bodies' own table and search index. */
    internal val smallBodyTable: SmallBodyTable = small
    private val indexByID: Map<String, Int> = contents.indexByID
    private val constellationDirectory: ConstellationDirectory = contents.constellations
    private val listedByGroup: Map<ObjectGroup, List<CelestialObject>> = small.mergedListedByGroup(contents.listedByGroup)
    private val deepSkyUpTo: List<List<CelestialObject>> = contents.deepSkyUpTo
    private val byCatalogue: Map<DeepSkyCatalogue, List<CelestialObject>> = contents.byCatalogue

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
        this(CatalogAssembler.assemble(starsData, dsosData, constellationsData, milkyWayData), SmallBodyTable.empty)

    /**
     * The object with an identifier, in constant time (Swift `object(id:)`).
     *
     * Deep-sky objects that are known by an acronym also answer to it: `"dso.LMC"` finds the Large
     * Magellanic Cloud, whose own `id` is `"dso.ESO56-115"`.
     */
    fun objectWithID(id: String): CelestialObject? =
        indexByID[id]?.let { contents.objects[it] } ?: smallBodyTable.objectWithID(id)

    /**
     * A catalog with exactly these comets and asteroids as its small bodies, and everything else (the stars, the
     * deep-sky objects, the planets, the constellations) shared with this one. Small bodies added to this catalog before
     * are dropped: pass the complete set, for example the bundled asteroids and the comets of the last download.
     *
     * Each body becomes an object ([CelestialObject.fromSmallBody]) that [objectWithID], [search], [objects] with a
     * group and [sorted] find like any other, and that the `SkyFrame` functions for objects place. Bodies with the same
     * identifier count once and the last one wins, so a list `bundled + downloaded` lets fresh elements replace stale
     * ones. At most [maximumSmallBodyCount] different bodies are used, the first ones of the list.
     *
     * Cost (desktop JVM, warm): 1.5 ms for the 216 bundled asteroids, 5 ms for 1 000 comets, 0.2 s for 43 000 bodies; a
     * phone takes a few times that, so build a large set off the main thread. The receiver is not changed.
     */
    fun withSmallBodies(bodies: List<SmallBody>): Catalog {
        if (bodies.isEmpty() && smallBodyTable.isEmpty) return this
        return Catalog(contents, SmallBodyTable.build(bodies))
    }

    /**
     * A catalog with these comets and asteroids added to the ones this catalog has: a body whose identifier is already
     * there is replaced, the others stay. See [withSmallBodies].
     */
    fun addingSmallBodies(bodies: List<SmallBody>): Catalog = withSmallBodies(smallBodyTable.bodies + bodies)

    /**
     * The objects that best match a query, best first.
     *
     * Matching ignores case, diacritics, character width, punctuation and spacing, reads Greek
     * letters either way ("α" is "alpha" is "alp"), takes superscript digits as digits ("α¹" is
     * "alpha1") and drops leading zeros of numbers. So "m31", "M 31", "Messier 31" and "M031" are the
     * same query, as are "ngc224" and "NGC 0224", and "alpha cma", "alpha canis majoris" and "α CMa".
     * It matches names, common names and the designations of every object: Messier, NGC and IC
     * numbers, the numbers of the other deep-sky catalogues (Caldwell "C 14" and "Caldwell 14", Sharpless
     * "Sh2-155", "Sh 2-155" and "Sharpless 155", Barnard "B33" and "Barnard 33", Lynds "LDN 1622" and
     * "Lynds 1622", Collinder "Cr 399", Melotte "Mel 20", Trumpler "Tr 24", Stock "St 2"), Bayer and
     * Flamsteed designations with the constellation as abbreviation or genitive, and HR, HD and HIP numbers.
     * The comets and asteroids added with [withSmallBodies] are found by title, designation and name: "C/2023 A3",
     * "Tsuchinshan", "12P", "Pons-Brooks", "4 Vesta", "Vesta", "2024 YR4".
     *
     * Results are ordered by [CatalogSearchResult.MatchKind]; matches of the same kind are ordered by listed
     * objects first, then more prominent objects ([CelestialObject.prominence]), then brighter objects (objects
     * without a magnitude last), then name. `MatchKind.FUZZY` results only appear when there are no other
     * matches.
     *
     * (In Swift this method is declared in `CatalogSearch.swift`; here it is a member so that callers need
     * no import.)
     *
     * @param query The text to look for. Only its first [maximumQueryLength] characters are used. A
     * query without letters or digits finds nothing.
     * @param limit The most results to return; a limit of zero or less returns none.
     */
    fun search(query: String, limit: Int = 50): List<CatalogSearchResult> = performSearch(query, limit)

    /**
     * The listed objects of a group, sorted by name (Swift `objects(in:)`). The listed comets and asteroids are in
     * [ObjectGroup.SOLAR_SYSTEM].
     */
    fun objects(group: ObjectGroup): List<CelestialObject> = listedByGroup[group] ?: emptyList()

    /**
     * The deep-sky objects of a catalogue, listed or not, in the order of their catalogue numbers: Caldwell 1
     * to 109, Sharpless 1 to 313, and so on. An object that has several numbers in the catalogue (NGC 6523 and
     * NGC 6533 are both M 8's) is listed once, at the first. Constant time; the list is shared (Swift
     * `objects(in:)`).
     */
    fun objects(catalogue: DeepSkyCatalogue): List<CelestialObject> = byCatalogue[catalogue] ?: emptyList()

    /**
     * The deep-sky objects of [maximum] prominence or better, in the order of [deepSkyObjects]: the
     * showpieces for `Prominence.SHOWPIECE`, everything for `Prominence.OBSCURE`. Constant time; the lists are
     * shared. This is the list a sky view draws, with a limit that grows as the field of view narrows
     * (Swift `deepSkyObjects(upTo:)`).
     */
    fun deepSkyObjects(maximum: Prominence): List<CelestialObject> = deepSkyUpTo[maximum.ordinal]

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

        /** The most comets and asteroids a catalog takes in [withSmallBodies], 100 000 (all of `NEA.txt` is 43 000). */
        const val maximumSmallBodyCount: Int = 100_000

        /** The longest query that is looked at, in Unicode scalars; the rest is ignored. */
        const val maximumQueryLength: Int = SearchText.maximumQueryScalars

        /**
         * Loads the catalog from the data files bundled with the library.
         *
         * This reads and checks about 1.6 MB of JSON and builds the search index, which takes a few
         * tens of milliseconds on a warm JVM and a few hundred on a phone: call it off the main thread.
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
        internal const val RESOURCE_DIRECTORY = "/net/kuecker/lodestar/kit/catalog/"

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

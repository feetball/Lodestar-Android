package net.kuecker.lodestar.kit.catalog

import java.util.EnumMap

/** Everything a [Catalog] holds, as produced by [CatalogAssembler]. */
internal class CatalogContents(
    val objects: List<CelestialObject>,
    val constellations: ConstellationDirectory,
    val milkyWay: MilkyWayOutline,
    val renderStars: List<RenderStar>,
    val report: CatalogLoadReport,
    val searchIndex: SearchIndex,
    val indexByID: Map<String, Int>,
    val listedByGroup: Map<ObjectGroup, List<CelestialObject>>,
    val deepSky: List<CelestialObject>,
    val deepSkyUpTo: List<List<CelestialObject>>,
    val byCatalogue: Map<DeepSkyCatalogue, List<CelestialObject>>,
)

/** Reads the data files, validates their records and builds the catalog's objects and search index. */
internal object CatalogAssembler {
    private class ObjectTable {
        val objects = ArrayList<CelestialObject>()
        val search = SearchIndexBuilder()

        /** Per object: the search key of its name (the first key of the draft), empty if the name has none. */
        val nameKeys = ArrayList<ByteArray>()

        fun append(draft: ObjectDraft) {
            objects.add(draft.obj)
            search.beginObject()
            var nameKey = NO_KEY
            for ((position, key) in draft.keys.withIndex()) {
                search.add(key.text, key.entry)
                // The first key of a draft is the object's name (entry 0), which is also what the orderings use.
                if (position == 0 && search.keyCountOfCurrentObject > 0) nameKey = search.lastKeyBytes()
            }
            nameKeys.add(nameKey)
        }
    }

    /** An acronym of a deep-sky object that also finds it, and the object's position in the table. */
    private class AcronymAlias(val alias: String, val index: Int)

    fun assemble(
        starsData: ByteArray,
        dsosData: ByteArray,
        constellationsData: ByteArray,
        milkyWayData: ByteArray,
    ): CatalogContents {
        val report = CatalogLoadReport()
        val constellations = ConstellationDirectory(loadConstellations(constellationsData, report))
        val deepSky = loadDeepSkyObjects(dsosData, report)
        val stars = loadStars(starsData, report)
        val milkyWay = loadMilkyWay(milkyWayData, report)

        // Plain-star records of the deep-sky data are stars of the star catalog: they become
        // designations of those stars instead of objects of their own.
        val duplicatesOfStar = HashMap<Int, ArrayList<DeepSkyEntry>>()
        val distinctDeepSky = ArrayList<DeepSkyEntry>()
        for (entry in deepSky) {
            val star = duplicatedStar(entry, stars)
            if (star != null) {
                duplicatesOfStar.getOrPut(star) { ArrayList() }.add(entry)
            } else {
                distinctDeepSky.add(entry)
            }
        }
        report.deepSkyObjects.merged = deepSky.size - distinctDeepSky.size
        report.deepSkyObjects.accepted = distinctDeepSky.size

        val table = ObjectTable()
        table.objects.ensureCapacity(stars.size + distinctDeepSky.size + 16)
        for (obj in BuiltInObjects.solarSystem() + BuiltInObjects.special(constellations)) {
            table.append(ObjectDraft.plain(obj))
        }

        val acronymAliases = ArrayList<AcronymAlias>()
        val deepSkyObjects = ArrayList<CelestialObject>(distinctDeepSky.size)
        for (entry in distinctDeepSky) {
            val draft = CatalogFactory.deepSkyObject(entry, constellations)
            deepSkyObjects.add(draft.obj)
            for (alternate in draft.obj.alternateNames) {
                val alias = CatalogFactory.acronymAlias(alternate)
                if (alias != null) acronymAliases.add(AcronymAlias(alias, table.objects.size))
            }
            table.append(draft)
        }

        val renderStars = ArrayList<RenderStar>(stars.size)
        for ((position, entry) in stars.withIndex()) {
            val draft = CatalogFactory.star(entry, constellations, duplicatesOfStar[position] ?: emptyList())
            renderStars.add(
                RenderStar(
                    id = draft.obj.id, unitVector = entry.unitVector, magnitude = entry.magnitude,
                    colorIndex = entry.colorIndex, name = entry.properName,
                ),
            )
            table.append(draft)
        }

        val indexByID = HashMap<String, Int>(capacityFor(table.objects.size + acronymAliases.size))
        for ((index, obj) in table.objects.withIndex()) {
            indexByID[obj.id] = index
        }
        for (alias in acronymAliases) {
            if (!indexByID.containsKey(alias.alias)) indexByID[alias.alias] = alias.index
        }

        val items = CatalogOrdering.items(table.objects) { table.nameKeys[it] }
        val ranked = items.sortedWith(CatalogOrdering.searchComparator)
        val objectsByRank = IntArray(ranked.size) { ranked[it].index }

        val listedByName = items.filter { it.isListed }.sortedWith(CatalogOrdering.nameComparator)
            .map { table.objects[it.index] }
        val listedByGroup = EnumMap<ObjectGroup, List<CelestialObject>>(ObjectGroup::class.java)
        listedByGroup.putAll(listedByName.groupBy { it.group })
        for (group in ObjectGroup.entries) {
            if (!listedByGroup.containsKey(group)) listedByGroup[group] = emptyList()
        }

        return CatalogContents(
            objects = table.objects, constellations = constellations, milkyWay = milkyWay,
            renderStars = renderStars, report = report, searchIndex = table.search.finish(objectsByRank),
            indexByID = indexByID, listedByGroup = listedByGroup, deepSky = deepSkyObjects,
            deepSkyUpTo = deepSkyUpTo(deepSkyObjects), byCatalogue = byCatalogue(deepSkyObjects),
        )
    }

    /** The deep-sky objects of each prominence and the more prominent ones, in the order of [deepSky]. */
    private fun deepSkyUpTo(deepSky: List<CelestialObject>): List<List<CelestialObject>> =
        Prominence.entries.map { limit -> deepSky.filter { it.prominence <= limit } }

    /**
     * The deep-sky objects of each catalogue by catalogue number (then letter, then identifier). An object with
     * several numbers in one catalogue, such as NGC 6523 and NGC 6533, is listed once, at the first.
     */
    private fun byCatalogue(deepSky: List<CelestialObject>): Map<DeepSkyCatalogue, List<CelestialObject>> {
        class Placed(val number: CatalogNumber, val obj: CelestialObject)

        val placed = EnumMap<DeepSkyCatalogue, ArrayList<Placed>>(DeepSkyCatalogue::class.java)
        for (obj in deepSky) {
            var seen = 0
            for (number in obj.catalogNumbers) {
                val bit = 1 shl number.catalogue.ordinal
                if (seen and bit != 0) continue
                seen = seen or bit
                placed.getOrPut(number.catalogue) { ArrayList() }.add(Placed(number, obj))
            }
        }
        val result = EnumMap<DeepSkyCatalogue, List<CelestialObject>>(DeepSkyCatalogue::class.java)
        for (catalogue in DeepSkyCatalogue.entries) {
            val entries = placed[catalogue] ?: ArrayList()
            entries.sortWith { lhs, rhs ->
                when {
                    lhs.number.number != rhs.number.number -> lhs.number.number.compareTo(rhs.number.number)
                    lhs.number.suffix != rhs.number.suffix -> CatalogText.compare(lhs.number.suffix, rhs.number.suffix)
                    else -> CatalogText.compare(lhs.obj.id, rhs.obj.id)
                }
            }
            result[catalogue] = entries.map { it.obj }
        }
        return result
    }

    private val NO_KEY = ByteArray(0)

    /** A hash map capacity that holds [count] entries without growing. */
    private fun capacityFor(count: Int): Int = (count / 0.75f).toInt() + 1

    /** How close a deep-sky record must be to a star to be that star, in degrees. */
    private const val duplicateRadius: Double = 1.0 / 60

    /**
     * The position in [stars] of the star that a deep-sky record merely lists a second time, if any.
     *
     * OpenNGC includes a few stars as plain-star records (NGC 1990 is the star Alnilam, IC 1318 is
     * Sadr). Records of those types within an arcminute of a catalogued star are that star;
     * Messier objects are never merged.
     */
    private fun duplicatedStar(entry: DeepSkyEntry, stars: List<StarEntry>): Int? {
        if (entry.messier != null || !(entry.typeCode == "*" || entry.typeCode == "**")) return null
        val direction = entry.position.unitVector
        var nearestIndex = -1
        var nearestSeparation = Double.POSITIVE_INFINITY
        for ((index, star) in stars.withIndex()) {
            // The same as entry.position.angularSeparation(star.position), with the unit vectors computed once.
            val separation = direction.angle(star.unitVector)
            if (separation <= duplicateRadius && separation < nearestSeparation) {
                nearestIndex = index
                nearestSeparation = separation
            }
        }
        return if (nearestIndex >= 0) nearestIndex else null
    }

    // MARK: Files

    private fun loadConstellations(data: ByteArray, report: CatalogLoadReport): List<Constellation> {
        val file = decode(data, CatalogFile.CONSTELLATIONS, "constellations", ConstellationRecord::read)
        requireSupportedSchema(file.schema, CatalogFile.CONSTELLATIONS)
        val constellations = ArrayList<Constellation>()
        val abbreviations = HashSet<String>()
        for ((index, record) in file.records.withIndex()) {
            if (record == null) {
                report.reject(CatalogLoadReport::constellations) { "constellations[$index]: record has the wrong shape" }
                continue
            }
            try {
                val validated = CatalogValidation.constellation(record)
                val abbreviation = validated.constellation.abbreviation
                if (!abbreviations.add(abbreviation.lowercase(java.util.Locale.ROOT))) {
                    throw Rejection("duplicate abbreviation $abbreviation")
                }
                constellations.add(validated.constellation)
                report.constellations.accepted += 1
                report.constellationLines.accepted += validated.constellation.lines.size
                if (validated.rejectedLines.isNotEmpty()) {
                    report.constellationLines.rejected += validated.rejectedLines.size
                    report.note {
                        "constellations[$index] $abbreviation: ${validated.rejectedLines.size} invalid polylines skipped"
                    }
                }
            } catch (rejection: Rejection) {
                report.reject(CatalogLoadReport::constellations) { "constellations[$index]: ${rejection.reason}" }
            }
        }
        return constellations
    }

    private fun loadDeepSkyObjects(data: ByteArray, report: CatalogLoadReport): List<DeepSkyEntry> {
        val file = decode(data, CatalogFile.DEEP_SKY_OBJECTS, "objects", DeepSkyRecord::read)
        requireSupportedSchema(file.schema, CatalogFile.DEEP_SKY_OBJECTS)
        val entries = ArrayList<DeepSkyEntry>(file.records.size)
        val identifiers = HashSet<String>()
        for ((index, record) in file.records.withIndex()) {
            if (record == null) {
                report.reject(CatalogLoadReport::deepSkyObjects) { "dsos[$index]: record has the wrong shape" }
                continue
            }
            try {
                val entry = CatalogValidation.deepSkyObject(record)
                if (!identifiers.add(entry.id)) throw Rejection("duplicate id ${entry.id}")
                entries.add(entry)
            } catch (rejection: Rejection) {
                report.reject(CatalogLoadReport::deepSkyObjects) { "dsos[$index]: ${rejection.reason}" }
            }
        }
        if (entries.isEmpty()) {
            throw CatalogError.NoValidRecords(CatalogFile.DEEP_SKY_OBJECTS, report.deepSkyObjects.rejected)
        }
        return entries
    }

    /** The valid stars from brightest to faintest (equally bright stars keep their file order). */
    private fun loadStars(data: ByteArray, report: CatalogLoadReport): List<StarEntry> {
        val file = decode(data, CatalogFile.STARS, "stars", StarRecord::read)
        requireSupportedSchema(file.schema, CatalogFile.STARS)
        val entries = ArrayList<StarEntry>(file.records.size)
        val identifiers = HashSet<String>(capacityFor(file.records.size))
        for ((index, record) in file.records.withIndex()) {
            if (record == null) {
                report.reject(CatalogLoadReport::stars) { "stars[$index]: record has the wrong shape" }
                continue
            }
            try {
                val entry = CatalogValidation.star(record, index)
                val objectID = entry.objectID
                if (!identifiers.add(objectID)) throw Rejection("duplicate star $objectID")
                entries.add(entry)
                report.stars.accepted += 1
            } catch (rejection: Rejection) {
                report.reject(CatalogLoadReport::stars) { "stars[$index]: ${rejection.reason}" }
            }
        }
        if (entries.isEmpty()) {
            throw CatalogError.NoValidRecords(CatalogFile.STARS, report.stars.rejected)
        }
        entries.sortWith { lhs, rhs ->
            if (lhs.magnitude != rhs.magnitude) {
                if (lhs.magnitude < rhs.magnitude) -1 else 1
            } else {
                lhs.sourceIndex.compareTo(rhs.sourceIndex)
            }
        }
        return entries
    }

    private fun loadMilkyWay(data: ByteArray, report: CatalogLoadReport): MilkyWayOutline {
        val file = decode(data, CatalogFile.MILKY_WAY, "levels", MilkyWayLevelRecord::read)
        requireSupportedSchema(file.schema, CatalogFile.MILKY_WAY)
        val levels = ArrayList<MilkyWayOutline.Level>()
        for ((index, record) in file.records.withIndex()) {
            if (record == null) {
                report.reject(CatalogLoadReport::milkyWayRings) { "milkyway[$index]: level has the wrong shape" }
                continue
            }
            try {
                val validated = CatalogValidation.milkyWayLevel(record)
                levels.add(validated.level)
                report.milkyWayRings.accepted += validated.level.polygons.size + validated.level.holes.size
                if (validated.rejectedRings.isNotEmpty()) {
                    report.milkyWayRings.rejected += validated.rejectedRings.size
                    report.note {
                        "milkyway level ${validated.level.level}: skipped ${validated.rejectedRings.joinToString(", ")}"
                    }
                }
            } catch (rejection: Rejection) {
                report.reject(CatalogLoadReport::milkyWayRings) { "milkyway[$index]: ${rejection.reason}" }
            }
        }
        return MilkyWayOutline(levels.sortedBy { it.level })
    }

    // MARK: Helpers

    /**
     * Reads a data file whose records are under the member [recordsKey], each read by [readRecord].
     *
     * @throws CatalogError.Malformed when the file is larger than [Catalog.maximumFileBytes] or is not valid
     * JSON of the expected shape.
     */
    private fun <R> decode(
        data: ByteArray,
        file: CatalogFile,
        recordsKey: String,
        readRecord: (CatalogJsonReader) -> R?,
    ): DecodedFile<R> {
        if (data.size > Catalog.maximumFileBytes) {
            throw CatalogError.Malformed(file, "larger than ${Catalog.maximumFileBytes} bytes")
        }
        try {
            return CatalogJsonReader(data).readFile(recordsKey, readRecord)
        } catch (error: CatalogJsonException) {
            throw CatalogError.Malformed(file, error.message ?: "the data could not be decoded")
        }
    }

    private fun requireSupportedSchema(version: Long?, file: CatalogFile) {
        if (version != Catalog.supportedSchemaVersion.toLong()) {
            throw CatalogError.UnsupportedSchema(file, version)
        }
    }
}

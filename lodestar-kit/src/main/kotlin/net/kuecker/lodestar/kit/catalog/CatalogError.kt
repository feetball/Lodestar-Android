package net.kuecker.lodestar.kit.catalog

/**
 * One of the four data files a catalog is built from.
 *
 * @property rawValue The Swift case name.
 */
enum class CatalogFile(val rawValue: String) {
    /** The star catalog. */
    STARS("stars"),

    /** The deep-sky objects. */
    DEEP_SKY_OBJECTS("deepSkyObjects"),

    /** The constellations and their stick figures. */
    CONSTELLATIONS("constellations"),

    /** The Milky Way outlines. */
    MILKY_WAY("milkyWay"),
    ;

    /** The name of the bundled JSON file without its extension. */
    internal val resourceName: String
        get() = when (this) {
            STARS -> "stars"
            DEEP_SKY_OBJECTS -> "dsos"
            CONSTELLATIONS -> "constellations"
            MILKY_WAY -> "milkyway"
        }

    /** The file name for messages ("stars.json"). */
    val fileName: String get() = "$resourceName.json"

    companion object {
        /** The file whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): CatalogFile? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Why a catalog could not be loaded.
 *
 * Individual bad records never cause an error: they are skipped and counted in
 * [Catalog.loadReport]. Errors mean that a whole file is unusable. Every case is a data class, so
 * two errors are equal when their payloads are; [message] and [description] are a sentence that
 * names the data file and says what is wrong with it.
 *
 * @property description A sentence that names the data file and says what is wrong with it.
 */
sealed class CatalogError(val description: String) : Exception(description) {
    /** The same sentence as [description] (Swift `LocalizedError.errorDescription`). */
    val errorDescription: String? get() = description

    /** A data file is not part of the module's resources. */
    data class MissingResource(val file: CatalogFile) :
        CatalogError("The catalog data file ${file.fileName} is missing.")

    /** A data file exists but could not be read. */
    data class Unreadable(val file: CatalogFile, val reason: String) :
        CatalogError("The catalog data file ${file.fileName} could not be read: $reason")

    /**
     * A data file is not valid JSON of the expected shape: truncated, wrong types at the top
     * level, nested too deeply or larger than [Catalog.maximumFileBytes].
     */
    data class Malformed(val file: CatalogFile, val reason: String) :
        CatalogError("The catalog data file ${file.fileName} is malformed: $reason")

    /** A data file declares a schema version other than [Catalog.supportedSchemaVersion]. */
    data class UnsupportedSchema(val file: CatalogFile, val version: Long?) :
        CatalogError(
            "The catalog data file ${file.fileName} has an unsupported schema version (${version?.toString() ?: "none"}).",
        )

    /**
     * A data file that must contain objects yielded none, because it was empty or because every
     * record was rejected.
     */
    data class NoValidRecords(val file: CatalogFile, val rejected: Int) :
        CatalogError("The catalog data file ${file.fileName} has no valid records ($rejected rejected).")
}

/** What happened to the records of the data files while a catalog was loaded. */
class CatalogLoadReport internal constructor() {
    /** How many records were taken and how many were rejected. */
    class Counts internal constructor() {
        /** Records that passed validation and became part of the catalog. */
        var accepted: Int = 0
            internal set

        /** Records that describe an object that is already in the catalog and were merged into it. */
        var merged: Int = 0
            internal set

        /** Records that were skipped: undecodable, out of range, missing a required value or duplicates. */
        var rejected: Int = 0
            internal set

        override fun equals(other: Any?): Boolean =
            other is Counts && accepted == other.accepted && merged == other.merged && rejected == other.rejected

        override fun hashCode(): Int = (accepted * 31 + merged) * 31 + rejected

        override fun toString(): String = "Counts(accepted=$accepted, merged=$merged, rejected=$rejected)"
    }

    /** Star records. */
    var stars: Counts = Counts()
        internal set

    /** Deep-sky object records. */
    var deepSkyObjects: Counts = Counts()
        internal set

    /** Constellation records. */
    var constellations: Counts = Counts()
        internal set

    /** Stick-figure polylines of the accepted constellations. */
    var constellationLines: Counts = Counts()
        internal set

    /** Milky Way polygons and holes. */
    var milkyWayRings: Counts = Counts()
        internal set

    /** A description of the first problems found, for logging (at most [maximumIssueCount]). */
    var issues: List<String> = emptyList()
        internal set

    /** The total number of rejected records, polylines and rings. */
    val rejectedCount: Int
        get() = stars.rejected + deepSkyObjects.rejected + constellations.rejected +
            constellationLines.rejected + milkyWayRings.rejected

    /** `true` when nothing was rejected. */
    val isClean: Boolean get() = rejectedCount == 0

    /** Records a problem unless [maximumIssueCount] are noted already; [message] is only built when it is kept. */
    internal inline fun note(message: () -> String) {
        if (issues.size < maximumIssueCount) {
            issues = issues + message()
        }
    }

    /**
     * Counts a rejected record in [counts] (a property of this report, such as `CatalogLoadReport::stars`)
     * and notes why.
     */
    internal inline fun reject(counts: (CatalogLoadReport) -> Counts, message: () -> String) {
        counts(this).rejected += 1
        note(message)
    }

    override fun equals(other: Any?): Boolean =
        other is CatalogLoadReport && stars == other.stars && deepSkyObjects == other.deepSkyObjects &&
            constellations == other.constellations && constellationLines == other.constellationLines &&
            milkyWayRings == other.milkyWayRings && issues == other.issues

    override fun hashCode(): Int {
        var result = stars.hashCode()
        result = 31 * result + deepSkyObjects.hashCode()
        result = 31 * result + constellations.hashCode()
        result = 31 * result + constellationLines.hashCode()
        result = 31 * result + milkyWayRings.hashCode()
        return 31 * result + issues.hashCode()
    }

    override fun toString(): String =
        "CatalogLoadReport(stars=$stars, deepSkyObjects=$deepSkyObjects, constellations=$constellations, " +
            "constellationLines=$constellationLines, milkyWayRings=$milkyWayRings, issues=$issues)"

    companion object {
        /** The most problems [issues] lists. */
        const val maximumIssueCount: Int = 25
    }
}

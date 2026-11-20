package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.CelestialObject

/** A file format [ObservingListCodec] reads (Swift `ObservingListFormat`). */
enum class ObservingListFormat(val rawValue: String) {
    /** A CSV table with name or designation columns and optionally right ascension and declination columns. */
    GENERIC_CSV("csv"),

    /** A CSV export of a Telescopius target list (columns such as "Catalogue Entry" and "Familiar Name"). */
    TELESCOPIUS_CSV("telescopius"),

    /** A SkySafari observing list (`.skylist`). */
    SKY_SAFARI("skysafari");

    /** The name shown to people. */
    val displayName: String
        get() = when (this) {
            GENERIC_CSV -> "CSV"
            TELESCOPIUS_CSV -> "Telescopius CSV"
            SKY_SAFARI -> "SkySafari observing list"
        }
}

/**
 * One target read from a list.
 *
 * @property name The name or common name ("Pleiades"), if the row has one.
 * @property designation The designation in a catalogue ("M 45"), if the row has one.
 * @property alternates Further designations and names of the same object ("OCL 421.0"), in file order.
 * @property rightAscension The J2000 right ascension in degrees, [0, 360), if the row has valid coordinates.
 * @property declination The J2000 declination in degrees, [-90, 90], if the row has valid coordinates.
 * @property line The line the row starts on, from 1.
 */
data class ImportedTarget(
    val name: String?,
    val designation: String?,
    val alternates: List<String> = emptyList(),
    val rightAscension: Double? = null,
    val declination: Double? = null,
    val line: Int,
) {
    /** Whether the row has a valid right ascension and declination. */
    val hasCoordinates: Boolean
        get() = rightAscension != null && declination != null

    /** The texts to look the object up by, best first: the designation, the alternates, then the name; without repeats. */
    val searchTerms: List<String>
        get() {
            val terms = ArrayList<String>()
            for (term in listOfNotNull(designation) + alternates + listOfNotNull(name)) {
                if (term.isNotEmpty() && !terms.contains(term)) terms.add(term)
            }
            return terms
        }

    /** The text to show for the row: the name, else the designation, else null. */
    val displayName: String?
        get() = name ?: designation
}

/**
 * Why a row of a list could not be used as it is.
 *
 * @property line The line the row starts on, from 1.
 */
data class ObservingListRowError(val line: Int, val reason: Reason) {
    /** What is wrong. */
    enum class Reason(val rawValue: String) {
        /** The row has no name, designation or coordinates. */
        MISSING_NAME("missingName"),

        /** The right ascension is not a valid angle. */
        INVALID_RIGHT_ASCENSION("invalidRightAscension"),

        /** The declination is not a valid angle. */
        INVALID_DECLINATION("invalidDeclination"),

        /** Only one of right ascension and declination is given. */
        INCOMPLETE_COORDINATES("incompleteCoordinates"),

        /** A SkySafari object is not closed by `EndObject=SkyObject`. */
        UNTERMINATED_OBJECT("unterminatedObject");

        /** A short sentence for the person. */
        val message: String
            get() = when (this) {
                MISSING_NAME -> "No name, designation or coordinates."
                INVALID_RIGHT_ASCENSION -> "The right ascension is not valid."
                INVALID_DECLINATION -> "The declination is not valid."
                INCOMPLETE_COORDINATES -> "Right ascension and declination must both be given."
                UNTERMINATED_OBJECT -> "The object is not closed by EndObject."
            }
    }
}

/**
 * What [ObservingListCodec.decode] read from a file.
 *
 * @property format The format the file was read as.
 * @property targets The usable rows, in file order.
 * @property errors The rows that had a problem, in file order. A row whose coordinates are invalid but that has a name is
 * in [targets] (without coordinates) and here.
 * @property isTruncated `true` when the file has more rows than [ObservingListCodec.maximumTargets], which were not read.
 */
data class ObservingListImport(
    val format: ObservingListFormat,
    val targets: List<ImportedTarget>,
    val errors: List<ObservingListRowError>,
    val isTruncated: Boolean,
)

/**
 * One target to write to a list.
 *
 * @property name The name shown in lists ("Andromeda Galaxy").
 * @property designation The primary catalogue designation ("M 31"), if the object has one.
 * @property rightAscension The J2000 right ascension in degrees, if known.
 * @property declination The J2000 declination in degrees, if known.
 * @property typeName The kind of object ("Galaxy"), if known.
 */
data class ObservingListEntry(
    val name: String,
    val designation: String? = null,
    val rightAscension: Double? = null,
    val declination: Double? = null,
    val typeName: String? = null,
)

/**
 * A row of an imported list and the catalog object it was matched to.
 *
 * @property target The row.
 * @property obj The catalog object, or null when the catalog has no object with one of the row's names (Swift `object`).
 */
data class ObservingListMatch(val target: ImportedTarget, val obj: CelestialObject?)

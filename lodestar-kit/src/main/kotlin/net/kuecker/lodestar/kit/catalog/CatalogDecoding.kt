package net.kuecker.lodestar.kit.catalog

import net.kuecker.lodestar.kit.formatting.NumberText
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.ProperMotion
import net.kuecker.lodestar.kit.foundation.Vector3

// Decoding and validation of the four bundled JSON files (see Tools/README.md of the Swift package for the
// schema).
//
// Each file is decoded leniently, record by record, so that one bad record cannot take the rest
// down; every record that does decode is then validated before it becomes a catalog object.
// Swift's `Lossy` wrapper is the `null` element of `DecodedFile.records`; the `Decodable` structs are read by
// `CatalogJsonReader` (every number that Swift reads as `Int` is a `Long` here, so that no value of the
// file can overflow).

/** A record that failed validation, with the reason. */
internal class Rejection(val reason: String) : Exception(reason, null, false, false)

// MARK: - File shapes

/**
 * A star record as it appears in `stars.json`; every member is optional, a member of the wrong type makes
 * the whole record `null` when read.
 */
internal class StarRecord(
    val hip: Long?,
    val hr: Long?,
    val hd: Long?,
    val hyg: Long?,
    val n: String?,
    val b: String?,
    val f: Long?,
    val c: String?,
    val ra: Double?,
    val de: Double?,
    val pa: Double?,
    val pd: Double?,
    val m: Double?,
    val ci: Double?,
    val sp: String?,
    val d: Double?,
) {
    companion object {
        private val HIP = CatalogJsonReader.keyCode("hip")
        private val HR = CatalogJsonReader.keyCode("hr")
        private val HD = CatalogJsonReader.keyCode("hd")
        private val HYG = CatalogJsonReader.keyCode("hyg")
        private val N = CatalogJsonReader.keyCode("n")
        private val B = CatalogJsonReader.keyCode("b")
        private val F = CatalogJsonReader.keyCode("f")
        private val C = CatalogJsonReader.keyCode("c")
        private val RA = CatalogJsonReader.keyCode("ra")
        private val DE = CatalogJsonReader.keyCode("de")
        private val PA = CatalogJsonReader.keyCode("pa")
        private val PD = CatalogJsonReader.keyCode("pd")
        private val M = CatalogJsonReader.keyCode("m")
        private val CI = CatalogJsonReader.keyCode("ci")
        private val SP = CatalogJsonReader.keyCode("sp")
        private val D = CatalogJsonReader.keyCode("d")

        /** Reads the next value of [reader] as a star record; `null` if it has the wrong shape. */
        fun read(reader: CatalogJsonReader): StarRecord? {
            if (!reader.beginRecord()) return null
            var hip: Long? = null
            var hr: Long? = null
            var hd: Long? = null
            var hyg: Long? = null
            var n: String? = null
            var b: String? = null
            var f: Long? = null
            var c: String? = null
            var ra: Double? = null
            var de: Double? = null
            var pa: Double? = null
            var pd: Double? = null
            var m: Double? = null
            var ci: Double? = null
            var sp: String? = null
            var d: Double? = null
            var first = true
            while (true) {
                val key = reader.nextKey(first)
                first = false
                when (key) {
                    CatalogJsonReader.END_OF_OBJECT -> break
                    RA -> ra = reader.double(1 shl 8)
                    DE -> de = reader.double(1 shl 9)
                    M -> m = reader.double(1 shl 12)
                    HIP -> hip = reader.long(1 shl 0)
                    HR -> hr = reader.long(1 shl 1)
                    HD -> hd = reader.long(1 shl 2)
                    N -> n = reader.string(1 shl 4)
                    B -> b = reader.string(1 shl 5)
                    F -> f = reader.long(1 shl 6)
                    C -> c = reader.string(1 shl 7)
                    PA -> pa = reader.double(1 shl 10)
                    PD -> pd = reader.double(1 shl 11)
                    CI -> ci = reader.double(1 shl 13)
                    SP -> sp = reader.string(1 shl 14)
                    D -> d = reader.double(1 shl 15)
                    HYG -> hyg = reader.long(1 shl 3)
                    else -> reader.skipValue()
                }
            }
            if (reader.failedMask != 0) return null
            return StarRecord(hip, hr, hd, hyg, n, b, f, c, ra, de, pa, pd, m, ci, sp, d)
        }
    }
}

/** A deep-sky record as it appears in `dsos.json`; `minorAxis` is the member `min`. */
internal class DeepSkyRecord(
    val id: String?,
    val m: Long?,
    val ngc: List<Long>?,
    val ic: List<Long>?,
    val cat: List<String>?,
    val names: List<String>?,
    val t: String?,
    val ra: Double?,
    val de: Double?,
    val c: String?,
    val vm: Double?,
    val bm: Double?,
    val maj: Double?,
    val minorAxis: Double?,
    val pa: Double?,
    val h: String?,
    val sb: Double?,
    val p: Long?,
) {
    companion object {
        private val ID = CatalogJsonReader.keyCode("id")
        private val M = CatalogJsonReader.keyCode("m")
        private val NGC = CatalogJsonReader.keyCode("ngc")
        private val IC = CatalogJsonReader.keyCode("ic")
        private val CAT = CatalogJsonReader.keyCode("cat")
        private val NAMES = CatalogJsonReader.keyCode("names")
        private val T = CatalogJsonReader.keyCode("t")
        private val RA = CatalogJsonReader.keyCode("ra")
        private val DE = CatalogJsonReader.keyCode("de")
        private val C = CatalogJsonReader.keyCode("c")
        private val VM = CatalogJsonReader.keyCode("vm")
        private val BM = CatalogJsonReader.keyCode("bm")
        private val MAJ = CatalogJsonReader.keyCode("maj")
        private val MIN = CatalogJsonReader.keyCode("min")
        private val PA = CatalogJsonReader.keyCode("pa")
        private val H = CatalogJsonReader.keyCode("h")
        private val SB = CatalogJsonReader.keyCode("sb")
        private val P = CatalogJsonReader.keyCode("p")

        /** Reads the next value of [reader] as a deep-sky record; `null` if it has the wrong shape. */
        fun read(reader: CatalogJsonReader): DeepSkyRecord? {
            if (!reader.beginRecord()) return null
            var id: String? = null
            var m: Long? = null
            var ngc: List<Long>? = null
            var ic: List<Long>? = null
            var cat: List<String>? = null
            var names: List<String>? = null
            var t: String? = null
            var ra: Double? = null
            var de: Double? = null
            var c: String? = null
            var vm: Double? = null
            var bm: Double? = null
            var maj: Double? = null
            var minorAxis: Double? = null
            var pa: Double? = null
            var h: String? = null
            var sb: Double? = null
            var p: Long? = null
            var first = true
            while (true) {
                val key = reader.nextKey(first)
                first = false
                when (key) {
                    CatalogJsonReader.END_OF_OBJECT -> break
                    ID -> id = reader.string(1 shl 0)
                    M -> m = reader.long(1 shl 1)
                    NGC -> ngc = reader.longList(1 shl 2)
                    IC -> ic = reader.longList(1 shl 3)
                    NAMES -> names = reader.stringList(1 shl 4)
                    T -> t = reader.string(1 shl 5)
                    RA -> ra = reader.double(1 shl 6)
                    DE -> de = reader.double(1 shl 7)
                    C -> c = reader.string(1 shl 8)
                    VM -> vm = reader.double(1 shl 9)
                    BM -> bm = reader.double(1 shl 10)
                    MAJ -> maj = reader.double(1 shl 11)
                    MIN -> minorAxis = reader.double(1 shl 12)
                    PA -> pa = reader.double(1 shl 13)
                    H -> h = reader.string(1 shl 14)
                    SB -> sb = reader.double(1 shl 15)
                    CAT -> cat = reader.stringList(1 shl 16)
                    P -> p = reader.long(1 shl 17)
                    else -> reader.skipValue()
                }
            }
            if (reader.failedMask != 0) return null
            return DeepSkyRecord(id, m, ngc, ic, cat, names, t, ra, de, c, vm, bm, maj, minorAxis, pa, h, sb, p)
        }
    }
}

/**
 * A constellation record as it appears in `constellations.json`. `lines` has one entry per polyline,
 * `null` for an element that cannot be decoded (Swift's `[Lossy<[[Double]]>]`).
 */
internal class ConstellationRecord(
    val abbr: String?,
    val name: String?,
    val genitive: String?,
    val label: DoubleArray?,
    val lines: List<List<DoubleArray>?>?,
) {
    companion object {
        private val ABBR = CatalogJsonReader.keyCode("abbr")
        private val NAME = CatalogJsonReader.keyCode("name")
        private val GENITIVE = CatalogJsonReader.keyCode("genitive")
        private val LABEL = CatalogJsonReader.keyCode("label")
        private val LINES = CatalogJsonReader.keyCode("lines")

        /** Reads the next value of [reader] as a constellation record; `null` if it has the wrong shape. */
        fun read(reader: CatalogJsonReader): ConstellationRecord? {
            if (!reader.beginRecord()) return null
            var abbr: String? = null
            var name: String? = null
            var genitive: String? = null
            var label: DoubleArray? = null
            var lines: List<List<DoubleArray>?>? = null
            var first = true
            while (true) {
                val key = reader.nextKey(first)
                first = false
                when (key) {
                    CatalogJsonReader.END_OF_OBJECT -> break
                    ABBR -> abbr = reader.string(1 shl 0)
                    NAME -> name = reader.string(1 shl 1)
                    GENITIVE -> genitive = reader.string(1 shl 2)
                    LABEL -> label = reader.doubleList(1 shl 3)
                    LINES -> lines = reader.ringList(1 shl 4)
                    else -> reader.skipValue()
                }
            }
            if (reader.failedMask != 0) return null
            return ConstellationRecord(abbr, name, genitive, label, lines)
        }
    }
}

/** A Milky Way level as it appears in `milkyway.json`; the rings are as the `lines` of [ConstellationRecord]. */
internal class MilkyWayLevelRecord(
    val level: Long?,
    val polygons: List<List<DoubleArray>?>?,
    val holes: List<List<DoubleArray>?>?,
) {
    companion object {
        private val LEVEL = CatalogJsonReader.keyCode("level")
        private val POLYGONS = CatalogJsonReader.keyCode("polygons")
        private val HOLES = CatalogJsonReader.keyCode("holes")

        /** Reads the next value of [reader] as a Milky Way level; `null` if it has the wrong shape. */
        fun read(reader: CatalogJsonReader): MilkyWayLevelRecord? {
            if (!reader.beginRecord()) return null
            var level: Long? = null
            var polygons: List<List<DoubleArray>?>? = null
            var holes: List<List<DoubleArray>?>? = null
            var first = true
            while (true) {
                val key = reader.nextKey(first)
                first = false
                when (key) {
                    CatalogJsonReader.END_OF_OBJECT -> break
                    LEVEL -> level = reader.long(1 shl 0)
                    POLYGONS -> polygons = reader.ringList(1 shl 1)
                    HOLES -> holes = reader.ringList(1 shl 2)
                    else -> reader.skipValue()
                }
            }
            if (reader.failedMask != 0) return null
            return MilkyWayLevelRecord(level, polygons, holes)
        }
    }
}

// MARK: - Validated records

/**
 * A validated star record.
 *
 * @property sourceIndex The record's position in the file, to keep the order of equally bright stars.
 */
internal class StarEntry(
    val hipparcos: Int?,
    val harvard: Int?,
    val henryDraper: Int?,
    val hygRow: Int?,
    val properName: String?,
    val bayer: String?,
    val flamsteed: Int?,
    val constellation: String?,
    val position: EquatorialCoordinate,
    val properMotion: ProperMotion?,
    val magnitude: Double,
    val colorIndex: Double?,
    val spectralType: String?,
    val distanceParsecs: Double?,
    val sourceIndex: Int,
) {
    /** The direction of [position] as a unit vector, computed once (it is needed for drawing and for matching duplicates). */
    val unitVector: Vector3 = position.unitVector

    /** The catalog identifier: Hipparcos number, else Harvard Revised number, else HYG row. */
    val objectID: String
        get() {
            if (hipparcos != null) return "star.hip$hipparcos"
            if (harvard != null) return "star.hr$harvard"
            return "star.hyg${hygRow ?: 0}"
        }
}

/** A validated deep-sky record. */
internal class DeepSkyEntry(
    val id: String,
    val messier: Int?,
    val ngc: List<Int>,
    val ic: List<Int>,
    val cat: List<String>,
    val names: List<String>,
    val typeCode: String,
    val position: EquatorialCoordinate,
    val constellation: String?,
    val visualMagnitude: Double?,
    val blueMagnitude: Double?,
    val majorAxis: Double?,
    val minorAxis: Double?,
    val positionAngle: Double?,
    val hubbleType: String?,
    val surfaceBrightness: Double?,
    val prominence: Prominence?,
)

/**
 * A validated constellation.
 *
 * @property rejectedLines Positions in the record's `lines` of the polylines that were dropped.
 */
internal class ValidatedConstellation(val constellation: Constellation, val rejectedLines: List<Int>)

/**
 * A validated Milky Way level.
 *
 * @property rejectedRings The rings that were dropped, as "polygon 3" or "hole 0".
 */
internal class ValidatedLevel(val level: MilkyWayOutline.Level, val rejectedRings: List<String>)

// MARK: - Validation

/** The checks that turn decoded records into validated ones; every failed check throws a [Rejection]. */
internal object CatalogValidation {
    private const val MAGNITUDE_LOWER = -30.0
    private const val MAGNITUDE_UPPER = 40.0
    const val maximumNameScalars: Int = 80

    fun star(record: StarRecord, sourceIndex: Int): StarEntry {
        val hip = catalogNumber(record.hip, "hip")
        val hr = catalogNumber(record.hr, "hr")
        val hd = catalogNumber(record.hd, "hd")
        val hyg = catalogNumber(record.hyg, "hyg")
        if (hip == null && hr == null && hyg == null) {
            throw Rejection("no hip, hr or hyg number")
        }
        val magnitude = record.m
        if (magnitude == null || !magnitude.isFinite() || magnitude < MAGNITUDE_LOWER || magnitude > MAGNITUDE_UPPER) {
            throw Rejection("magnitude missing or out of range")
        }
        var flamsteed: Int? = null
        val flamsteedNumber = record.f
        if (flamsteedNumber != null) {
            if (flamsteedNumber < 1 || flamsteedNumber > 999) {
                throw Rejection("Flamsteed number $flamsteedNumber out of range")
            }
            flamsteed = flamsteedNumber.toInt()
        }
        var bayer: String? = null
        val bayerText = record.b
        if (bayerText != null) {
            val cleaned = cleanedText(bayerText, "b", 4)
            if (cleaned != null && containsWhiteSpace(cleaned)) {
                throw Rejection("Bayer letter contains whitespace")
            }
            bayer = cleaned
        }
        // The arguments of the Swift initializer are evaluated in this order, which decides which rejection is reported.
        val properName = cleanedText(record.n, "n", maximumNameScalars)
        val constellation = constellationAbbreviation(record.c)
        val position = position(record.ra, record.de)
        val properMotion = properMotion(record.pa, record.pd)
        val colorIndex = number(record.ci, "ci", -2.0, 10.0)
        val spectralType = cleanedText(record.sp, "sp", 24)
        val distanceParsecs = positiveNumber(record.d, "d", 1_000_000.0)
        return StarEntry(
            hipparcos = hip, harvard = hr, henryDraper = hd, hygRow = hyg,
            properName = properName, bayer = bayer, flamsteed = flamsteed,
            constellation = constellation, position = position, properMotion = properMotion,
            magnitude = magnitude, colorIndex = colorIndex, spectralType = spectralType,
            distanceParsecs = distanceParsecs, sourceIndex = sourceIndex,
        )
    }

    fun deepSkyObject(record: DeepSkyRecord): DeepSkyEntry {
        val rawID = record.id
        val id = if (rawID != null) cleanedText(rawID, "id", 40) else null
        if (id == null || !allIdentifierScalars(id)) {
            throw Rejection("id missing or contains unusual characters")
        }
        val rawType = record.t
        val typeCode = if (rawType != null) cleanedText(rawType, "t", 8) else null
        if (typeCode == null) {
            throw Rejection("$id: type missing")
        }
        var messier: Int? = null
        val messierNumber = record.m
        if (messierNumber != null) {
            if (messierNumber < 1 || messierNumber > 999) throw Rejection("$id: Messier number out of range")
            messier = messierNumber.toInt()
        }
        val major = positiveNumber(record.maj, "maj", AngularSize.maximumArcminutes)
        val minor = positiveNumber(record.minorAxis, "min", AngularSize.maximumArcminutes)
        if (major != null && minor != null && minor > major) {
            throw Rejection("$id: minor axis longer than major axis")
        }
        if (minor != null && major == null) {
            throw Rejection("$id: minor axis without major axis")
        }
        // The arguments of the Swift initializer are evaluated in this order, which decides which rejection is reported.
        val ngc = designationNumbers(record.ngc, "ngc")
        val ic = designationNumbers(record.ic, "ic")
        val cat = crossDesignations(record.cat)
        val names = commonNames(record.names)
        val position = position(record.ra, record.de)
        val constellation = constellationAbbreviation(record.c)
        val visualMagnitude = number(record.vm, "vm", MAGNITUDE_LOWER, MAGNITUDE_UPPER)
        val blueMagnitude = number(record.bm, "bm", MAGNITUDE_LOWER, MAGNITUDE_UPPER)
        val positionAngle = number(record.pa, "pa", 0.0, 360.0)
        val hubbleType = cleanedText(record.h, "h", 16)
        val surfaceBrightness = positiveNumber(record.sb, "sb", 40.0)
        val prominence = prominence(record.p)
        return DeepSkyEntry(
            id = id, messier = messier, ngc = ngc, ic = ic, cat = cat, names = names, typeCode = typeCode,
            position = position, constellation = constellation,
            visualMagnitude = visualMagnitude, blueMagnitude = blueMagnitude,
            majorAxis = major, minorAxis = minor, positionAngle = positionAngle,
            hubbleType = hubbleType, surfaceBrightness = surfaceBrightness, prominence = prominence,
        )
    }

    fun constellation(record: ConstellationRecord): ValidatedConstellation {
        val abbreviation = constellationAbbreviation(record.abbr) ?: throw Rejection("abbreviation missing")
        val name = cleanedText(record.name, "name", maximumNameScalars)
        val genitive = if (name != null) cleanedText(record.genitive, "genitive", maximumNameScalars) else null
        if (name == null || genitive == null) {
            throw Rejection("$abbreviation: name or genitive missing")
        }
        val label = record.label
        val labelPosition = (if (label != null && label.size == 2) positionOrNull(label[0], label[1]) else null)
            ?: throw Rejection("$abbreviation: label position missing or out of range")
        val lines = ArrayList<List<EquatorialCoordinate>>()
        val rejectedLines = ArrayList<Int>()
        val elements = record.lines
        if (elements != null) {
            for ((index, vertices) in elements.withIndex()) {
                val polyline = if (vertices != null && vertices.size >= 2) coordinatesOrNull(vertices) else null
                if (polyline != null) lines.add(polyline) else rejectedLines.add(index)
            }
        }
        return ValidatedConstellation(
            constellation = Constellation(
                abbreviation = abbreviation, name = name, genitive = genitive,
                labelPosition = labelPosition, lines = lines,
            ),
            rejectedLines = rejectedLines,
        )
    }

    fun milkyWayLevel(record: MilkyWayLevelRecord): ValidatedLevel {
        val levelNumber = record.level
        if (levelNumber == null || levelNumber < 1 || levelNumber > 100) {
            throw Rejection("level number missing or out of range")
        }
        val level = levelNumber.toInt()
        val rejectedRings = ArrayList<String>()
        fun rings(elements: List<List<DoubleArray>?>?, kind: String): List<List<EquatorialCoordinate>> {
            val result = ArrayList<List<EquatorialCoordinate>>()
            if (elements == null) return result
            for ((index, vertices) in elements.withIndex()) {
                val ring = if (vertices != null && vertices.size >= 3) coordinatesOrNull(vertices) else null
                if (ring != null) result.add(ring) else rejectedRings.add("$kind $index")
            }
            return result
        }
        val polygons = rings(record.polygons, "polygon")
        val holes = rings(record.holes, "hole")
        if (polygons.isEmpty() && holes.isEmpty()) {
            throw Rejection("level $level has no valid rings")
        }
        return ValidatedLevel(MilkyWayOutline.Level(level = level, polygons = polygons, holes = holes), rejectedRings)
    }

    // MARK: Field helpers

    private fun position(ra: Double?, dec: Double?): EquatorialCoordinate {
        if (ra == null || dec == null) throw Rejection("position missing or out of range")
        return positionOrNull(ra, dec) ?: throw Rejection("position missing or out of range")
    }

    /** The position of a right ascension and declination in degrees, `null` if either is not finite or out of range. */
    private fun positionOrNull(ra: Double, dec: Double): EquatorialCoordinate? {
        if (!ra.isFinite() || !dec.isFinite() || ra < 0.0 || ra > 360.0 || dec < -90.0 || dec > 90.0) return null
        return EquatorialCoordinate(rightAscension = ra, declination = dec)
    }

    /** The positions of [vertices], or `null` if one of them is not a valid pair (Swift's `try? coordinates(_:)`). */
    private fun coordinatesOrNull(vertices: List<DoubleArray>): List<EquatorialCoordinate>? {
        val result = ArrayList<EquatorialCoordinate>(vertices.size)
        for (vertex in vertices) {
            if (vertex.size != 2) return null
            result.add(positionOrNull(vertex[0], vertex[1]) ?: return null)
        }
        return result
    }

    private fun properMotion(ra: Double?, dec: Double?): ProperMotion? {
        if (ra == null && dec == null) return null
        val limit = 100_000.0
        val rightAscension = number(ra, "pa", -limit, limit) ?: 0.0
        val declination = number(dec, "pd", -limit, limit) ?: 0.0
        return ProperMotion(raMilliarcsecondsPerYear = rightAscension, decMilliarcsecondsPerYear = declination)
    }

    private fun catalogNumber(value: Long?, field: String): Int? {
        if (value == null) return null
        if (value < 1 || value > 99_999_999) throw Rejection("$field $value out of range")
        return value.toInt()
    }

    private fun designationNumbers(values: List<Long>?, field: String): List<Int> {
        val result = ArrayList<Int>()
        if (values == null) return result
        for (value in values) {
            if (value < 1 || value > 99_999) throw Rejection("$field $value out of range")
            val number = value.toInt()
            if (!result.contains(number)) result.add(number)
        }
        return result
    }

    /** The most cross-catalogue designations a record may carry. */
    const val maximumCrossDesignations: Int = 16

    /**
     * The designations of other catalogues in compact form (`C20`, `Sh2-117`, `LDN1622`, see [CatalogDesignation]),
     * without repeats. A malformed one, or more than [maximumCrossDesignations], rejects the record.
     */
    private fun crossDesignations(values: List<String>?): List<String> {
        val result = ArrayList<String>()
        if (values == null) return result
        for (value in values) {
            val text = cleanedText(value, "cat", 16)
            if (text == null || !CatalogDesignation.isCompact(text)) {
                throw Rejection("cat entry ${CatalogText.prefixCharacters(value, 16)} is not a catalogue designation")
            }
            if (!result.contains(text)) result.add(text)
        }
        if (result.size > maximumCrossDesignations) throw Rejection("cat has too many entries")
        return result
    }

    /** The prominence of a record: absent is `null` (the default applies), a number outside 1 to 4 rejects it. */
    private fun prominence(value: Long?): Prominence? {
        if (value == null) return null
        val prominence = if (value in 1L..4L) Prominence.fromRawValue(value.toInt()) else null
        return prominence ?: throw Rejection("p $value out of range")
    }

    private fun commonNames(values: List<String>?): List<String> {
        val result = ArrayList<String>()
        if (values == null) return result
        for (value in values) {
            val name = cleanedText(value, "names", maximumNameScalars) ?: continue
            if (!result.contains(name)) result.add(name)
        }
        return result
    }

    private fun number(value: Double?, field: String, lower: Double, upper: Double): Double? {
        if (value == null) return null
        if (!value.isFinite() || value < lower || value > upper) {
            throw Rejection("$field ${NumberText.swiftDescription(value)} out of range")
        }
        return value
    }

    private fun positiveNumber(value: Double?, field: String, upTo: Double): Double? {
        if (value == null) return null
        if (!value.isFinite() || !(value > 0) || value > upTo) {
            throw Rejection("$field ${NumberText.swiftDescription(value)} out of range")
        }
        return value
    }

    /**
     * The trimmed text, `null` when absent or blank; throws for text that is too long or contains
     * control characters.
     */
    private fun cleanedText(value: String?, field: String, maximumScalars: Int): String? {
        if (value == null) return null
        if (isAscii(value)) return cleanedAsciiText(value, field, maximumScalars)
        // Bounds the cost of looking at absurdly long strings.
        if (CatalogText.utf8Length(value) > 8 * maximumScalars) throw Rejection("$field is too long")
        var start = 0
        var end = value.length
        while (start < end) {
            val scalar = value.codePointAt(start)
            if (!CatalogText.isWhiteSpace(scalar)) break
            start += Character.charCount(scalar)
        }
        while (start < end) {
            val scalar = value.codePointBefore(end)
            if (!CatalogText.isWhiteSpace(scalar)) break
            end -= Character.charCount(scalar)
        }
        if (start >= end) return null
        var count = 0
        var index = start
        while (index < end) {
            val scalar = value.codePointAt(index)
            index += Character.charCount(scalar)
            count += 1
            if (CatalogText.isControl(scalar)) throw Rejection("$field contains control characters")
        }
        if (count > maximumScalars) throw Rejection("$field is too long")
        return if (start == 0 && end == value.length) value else value.substring(start, end)
    }

    /**
     * [cleanedText] for a text of ASCII characters only, which nearly every text is: the same checks in the
     * same order, without looking at Unicode scalars. A UTF-8 byte is a character, white space is U+0009 to
     * U+000D and U+0020, control characters are U+0000 to U+001F and U+007F.
     */
    private fun cleanedAsciiText(value: String, field: String, maximumScalars: Int): String? {
        if (value.length > 8 * maximumScalars) throw Rejection("$field is too long")
        var start = 0
        var end = value.length
        while (start < end && isAsciiWhiteSpace(value[start])) start += 1
        while (start < end && isAsciiWhiteSpace(value[end - 1])) end -= 1
        if (start >= end) return null
        for (index in start until end) {
            val character = value[index].code
            if (character < 0x20 || character == 0x7F) throw Rejection("$field contains control characters")
        }
        if (end - start > maximumScalars) throw Rejection("$field is too long")
        return if (start == 0 && end == value.length) value else value.substring(start, end)
    }

    private fun isAscii(text: String): Boolean {
        for (character in text) {
            if (character.code >= 0x80) return false
        }
        return true
    }

    private fun isAsciiWhiteSpace(character: Char): Boolean = character == ' ' || (character >= '\t' && character <= '\r')

    /** An IAU-style abbreviation: two to four ASCII letters. */
    private fun constellationAbbreviation(value: String?): String? {
        val text = cleanedText(value, "constellation", 4) ?: return null
        var count = 0
        var index = 0
        var letters = true
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            count += 1
            if (!((scalar >= 0x41 && scalar <= 0x5A) || (scalar >= 0x61 && scalar <= 0x7A))) letters = false
        }
        if (count < 2 || !letters) {
            throw Rejection("constellation abbreviation $text is malformed")
        }
        return text
    }

    private fun containsWhiteSpace(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            if (CatalogText.isWhiteSpace(scalar)) return true
            index += Character.charCount(scalar)
        }
        return false
    }

    private fun allIdentifierScalars(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            if (!isIdentifierScalar(scalar)) return false
            index += Character.charCount(scalar)
        }
        return true
    }

    private fun isIdentifierScalar(scalar: Int): Boolean = when (scalar) {
        in 0x30..0x39, in 0x41..0x5A, in 0x61..0x7A, 0x2D, 0x2E, 0x2B, 0x5F -> true
        else -> false
    }
}

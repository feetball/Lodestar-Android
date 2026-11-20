package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.catalog.CatalogText
import net.kuecker.lodestar.kit.formatting.AngleTextParser

/**
 * Reads and writes observing lists: CSV tables (generic and Telescopius) and SkySafari observing lists.
 *
 * ## Formats
 *
 * - **Generic CSV** (RFC 4180, comma, semicolon or tab separated, header row). Header names are matched without case and
 *   punctuation: a designation column ("designation", "catalogue entry", "catalog number", "object", "id"), a name column
 *   ("name", "familiar name", "common name", "target") and optionally "ra" or "right ascension" and "dec" or
 *   "declination". A coordinate is decimal degrees, or sexagesimal text such as `05h 35m 17.3s`, `05:35:17` or
 *   `-05° 23′ 28″`; a plain number in a right ascension column is degrees unless the header says hours ("RA (hours)").
 * - **Telescopius CSV**, as the "download as CSV" of a Telescopius target list writes it: columns "Catalogue Entry"
 *   (`M 45`), "Familiar Name" (`Pleiades`), "Alternative Entries" (a comma separated list inside quotation marks),
 *   "Right Ascension (j2000)" and "Declination (j2000)" (`03h 46' 24"`, `24º 06' 50"`), plus jNow columns, which are
 *   ignored, and a "Label". Exports before the epoch columns existed call them "Right Ascension" and "Declination".
 * - **SkySafari observing list** (`.skylist`, plain text): a first line `SkySafariObservingListVersion=3.0`, then for each
 *   object a block `SkyObject=BeginObject`, lines `Key=value` (`ObjectID`, `CommonName`, `CatalogNumber`, `DefaultIndex`,
 *   the keys may be indented with tabs) and `EndObject=SkyObject`. Only `CommonName` and `CatalogNumber` are used.
 *   SkySafari lists have no coordinates; objects are found by their catalogue numbers.
 *
 * Sources of the formats: Telescopius export header and row as quoted from a real export in the Telescopius forum
 * (https://forum.telescopius.com/t/csv-file-download-issues/1865), SkySafari lists as published by RASC
 * (https://rasc.ca/sites/default/files/RASC%20Finest%20NGC%20-%20Summer%20%28v2%29.skylist.txt) and in the SkySafari 6/7
 * example of https://github.com/Stellarium/stellarium/discussions/2308, and the generator
 * https://github.com/miguelvb/wds_catalog.
 *
 * ## Limits
 *
 * Files are limited to [maximumTextScalars] characters and [maximumTargets] rows; names are cut to 120 characters.
 * Reading is linear in the size of the file.
 */
object ObservingListCodec {
    /** The most rows read from, and written to, a list. */
    const val maximumTargets = 5_000

    /** The longest text that is read, in Unicode scalars. */
    const val maximumTextScalars = 4_000_000

    /** The longest name or designation kept, in Unicode scalars. */
    internal const val maximumNameScalars = 120

    /** The most alternate names kept for a row. */
    internal const val maximumAlternates = 20

    // MARK: Reading

    /**
     * Reads a list, finding out its format from the content.
     *
     * @throws InterchangeError when the file is empty, too large, has an unclosed quotation mark or (CSV) has no usable
     * column. Problems with single rows are in the result's `errors`.
     */
    @Throws(InterchangeError::class)
    fun decode(text: String): ObservingListImport {
        if (text.codePointCount(0, text.length) > maximumTextScalars) {
            throw InterchangeError.TooLarge(maximumTextScalars)
        }
        val content = if (text.isNotEmpty() && text[0] == '﻿') text.substring(1) else text
        if (!hasContent(content)) throw InterchangeError.Empty
        if (ObservingListSkySafari.hasHeader(content)) return ObservingListSkySafari.decode(content)
        return decodeCSV(content)
    }

    private fun hasContent(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val scalar = text.codePointAt(index)
            index += Character.charCount(scalar)
            if (!CatalogText.isWhiteSpace(scalar)) return true
        }
        return false
    }

    private fun decodeCSV(text: String): ObservingListImport {
        val table = InterchangeCSV.parse(text, maximumTargets + 1)
        val header = table.records.firstOrNull() ?: throw InterchangeError.Empty
        val columns = ObservingListColumns.create(header.fields) ?: throw InterchangeError.MissingColumns

        val targets = ArrayList<ImportedTarget>()
        val errors = ArrayList<ObservingListRowError>()
        for (record in table.records.drop(1)) {
            // A row of empty cells says nothing.
            if (record.fields.all { InterchangeText.singleLine(it, 1).isEmpty() }) continue

            fun cell(index: Int?, limit: Int = maximumNameScalars): String {
                if (index == null || index >= record.fields.size) return ""
                return InterchangeText.singleLine(InterchangeCSV.unguarded(record.fields[index]), limit)
            }

            val designation = cell(columns.designation)
            // Telescopius writes the custom name of a list entry in "Label"; it comes after the familiar name.
            val familiarName = cell(columns.name)
            val name = if (familiarName.isEmpty()) cell(columns.label) else familiarName
            val alternates = alternateNames(cell(columns.alternates, 2_000))
            val raText = cell(columns.rightAscension, AngleTextParser.maximumLength + 1)
            val decText = cell(columns.declination, AngleTextParser.maximumLength + 1)

            var rightAscension: Double? = null
            var declination: Double? = null
            var hasCoordinateError = false
            if (raText.isNotEmpty() || decText.isNotEmpty()) {
                if (raText.isEmpty() || decText.isEmpty()) {
                    errors.add(ObservingListRowError(record.line, ObservingListRowError.Reason.INCOMPLETE_COORDINATES))
                    hasCoordinateError = true
                } else {
                    val parsedRightAscension = ObservingListCoordinates.rightAscension(raText, columns.rightAscensionUnit)
                    val parsedDeclination = ObservingListCoordinates.declination(decText)
                    if (parsedRightAscension == null) {
                        errors.add(ObservingListRowError(record.line, ObservingListRowError.Reason.INVALID_RIGHT_ASCENSION))
                    }
                    if (parsedDeclination == null) {
                        errors.add(ObservingListRowError(record.line, ObservingListRowError.Reason.INVALID_DECLINATION))
                    }
                    hasCoordinateError = parsedRightAscension == null || parsedDeclination == null
                    if (!hasCoordinateError) {
                        rightAscension = parsedRightAscension
                        declination = parsedDeclination
                    }
                }
            }
            val isIdentified = designation.isNotEmpty() || name.isNotEmpty() || alternates.isNotEmpty()
            if (!isIdentified && rightAscension == null) {
                if (!hasCoordinateError) errors.add(ObservingListRowError(record.line, ObservingListRowError.Reason.MISSING_NAME))
                continue
            }
            targets.add(
                ImportedTarget(
                    name = name.ifEmpty { null },
                    designation = designation.ifEmpty { null },
                    alternates = alternates,
                    rightAscension = rightAscension,
                    declination = declination,
                    line = record.line,
                ),
            )
        }
        val format = if (columns.isTelescopius) ObservingListFormat.TELESCOPIUS_CSV else ObservingListFormat.GENERIC_CSV
        return ObservingListImport(format, targets, errors, table.isTruncated)
    }

    /** The names of a comma separated cell ("CL MELOTTE 22,H 0346+24"), without empty entries and repeats. */
    private fun alternateNames(text: String): List<String> {
        val result = ArrayList<String>()
        for (part in text.split(',')) {
            if (part.isEmpty()) continue
            val name = InterchangeText.singleLine(part, maximumNameScalars)
            if (name.isNotEmpty() && !result.contains(name)) result.add(name)
            if (result.size >= maximumAlternates) break
        }
        return result
    }

    // MARK: Writing

    /**
     * A list as CSV: `Name,Designation,RA J2000 (deg),Dec J2000 (deg),Type`, one row per entry (the first
     * [maximumTargets]), coordinates in decimal degrees with five places, text that a spreadsheet could run as a formula
     * guarded with an apostrophe.
     */
    fun encodeCSV(entries: List<ObservingListEntry>): String {
        val rows = ArrayList<List<String>>()
        rows.add(listOf("Name", "Designation", "RA J2000 (deg)", "Dec J2000 (deg)", "Type"))
        for (entry in entries.take(maximumTargets)) {
            val rightAscension = entry.rightAscension
            val declination = entry.declination
            val hasPosition = rightAscension != null && declination != null &&
                rightAscension.isFinite() && rightAscension >= 0 && rightAscension < 360 &&
                declination.isFinite() && kotlin.math.abs(declination) <= 90
            rows.add(
                listOf(
                    InterchangeCSV.guarded(InterchangeText.singleLine(entry.name, maximumNameScalars)),
                    InterchangeCSV.guarded(InterchangeText.singleLine(entry.designation ?: "", maximumNameScalars)),
                    if (hasPosition) InterchangeText.fixed(rightAscension, 5) else "",
                    if (hasPosition) InterchangeText.fixed(declination, 5) else "",
                    InterchangeCSV.guarded(InterchangeText.singleLine(entry.typeName ?: "", maximumNameScalars)),
                ),
            )
        }
        return InterchangeCSV.encode(rows)
    }

    /**
     * A list as a SkySafari observing list: version header, `SortedBy=Default Order`, and per entry (the first
     * [maximumTargets]) one `SkyObject` block with `ObjectID=2,-1,-1` (the value that generators of lists use, SkySafari
     * looks the object up), the designation as `CatalogNumber` or, without one, the name as `CommonName`, and
     * `DefaultIndex`. Lines end with LF and keys are indented with a tab, as SkySafari writes them.
     *
     * SkySafari only imports objects it knows, and an object must match every `CatalogNumber` and `CommonName` of its
     * block, so only one identifier is written. Targets that are only a place in the sky (custom targets) are written by
     * name and are not found by SkySafari.
     */
    fun encodeSkySafari(entries: List<ObservingListEntry>): String {
        val lines = arrayListOf("SkySafariObservingListVersion=3.0", "SortedBy=Default Order")
        for ((index, entry) in entries.take(maximumTargets).withIndex()) {
            val designation = InterchangeText.singleLine(entry.designation ?: "", maximumNameScalars)
            val name = InterchangeText.singleLine(entry.name, maximumNameScalars)
            lines.add("SkyObject=BeginObject")
            lines.add("\tObjectID=2,-1,-1")
            if (designation.isNotEmpty()) {
                lines.add("\tCatalogNumber=$designation")
            } else if (name.isNotEmpty()) {
                lines.add("\tCommonName=$name")
            }
            lines.add("\tDefaultIndex=$index")
            lines.add("EndObject=SkyObject")
        }
        return lines.joinToString("\n") + "\n"
    }
}

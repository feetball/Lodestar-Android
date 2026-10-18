package net.kuecker.lodestar.kit.satellites

/**
 * Why an orbital element set (a TLE or a CelesTrak OMM record) was rejected.
 *
 * Parsing a batch never throws for a single bad record: the record is skipped and reported in
 * [ElementSetBatch.skipped]. The errors below describe one record, or (for [MalformedJson], [InputTooLarge]) a
 * whole input that cannot be read at all. Every case is a data class, so errors compare by value; [message] and
 * [description] are one sentence that names the field and says what is wrong.
 *
 * @property description A sentence that names the field and says what is wrong.
 */
sealed class ElementSetError(val description: String) : Exception(description) {
    /** The same sentence as [description] (Swift `LocalizedError.errorDescription`). */
    val errorDescription: String? get() = description

    /** A TLE line is not exactly 69 characters long (after trailing white space is removed). */
    data class InvalidLineLength(val line: Int, val length: Int) :
        ElementSetError("TLE line $line has $length characters instead of 69.")

    /** A TLE line does not start with its line number ("1 " or "2 "). */
    data class UnexpectedLineNumber(val line: Int) :
        ElementSetError("TLE line $line does not start with its line number.")

    /** The modulo-10 checksum in column 69 of a TLE line does not match the line. */
    data class ChecksumMismatch(val line: Int, val expected: Int, val found: Int) :
        ElementSetError("TLE line $line has checksum digit $found but its contents add up to $expected.")

    /** A field cannot be read: [text] is not a number, a date or an identifier of the expected shape. */
    data class InvalidField(val field: String, val text: String) :
        ElementSetError("The field $field cannot be read from \"$text\".")

    /** A field is a number but outside the range that is physically or formally possible. */
    data class ValueOutOfRange(val field: String, val value: Double) :
        ElementSetError("The field $field is out of range: $value.")

    /** The catalog numbers on the two lines of a TLE differ. */
    data class CatalogNumberMismatch(val line1: Int, val line2: Int) :
        ElementSetError("The two TLE lines belong to different objects ($line1 and $line2).")

    /** A required field is absent from a record. */
    data class MissingField(val field: String) :
        ElementSetError("The required field $field is missing.")

    /** The input is not valid JSON, or its top level is neither an array nor an object. */
    data class MalformedJson(val reason: String) :
        ElementSetError("The element sets are not valid JSON: $reason")

    /** An element of a JSON array is not an object of OMM fields. */
    data class InvalidRecord(val reason: String) :
        ElementSetError("The record is not an OMM object: $reason")

    /** The input has more than [limit] characters. */
    data class InputTooLarge(val limit: Int) :
        ElementSetError("The element sets are larger than $limit characters.")
}

/**
 * A record that a batch parse skipped.
 *
 * @property index Zero-based position of the record in the input (the n-th TLE, the n-th array element).
 * @property name The object name, when it could be read.
 * @property error Why it was skipped.
 */
data class SkippedElementSet(val index: Int, val name: String?, val error: ElementSetError)

/**
 * The result of parsing many element sets at once: the valid ones and a report of the rest.
 *
 * @property elements The valid element sets, in input order.
 * @property skipped The first [ElementSetParser.maximumReportedSkips] skipped records.
 * @property skippedCount How many records were skipped in total.
 */
data class ElementSetBatch(
    val elements: List<OrbitalElements>,
    val skipped: List<SkippedElementSet>,
    val skippedCount: Int,
) {
    /**
     * The newest element set of each object (by catalog number), in order of first appearance. Feeds that mix
     * several downloads can contain one object twice.
     */
    fun latestPerObject(): ElementSetBatch {
        val newest = HashMap<Int, OrbitalElements>(elements.size * 2)
        val order = ArrayList<Int>(elements.size)
        for (candidate in elements) {
            val current = newest[candidate.catalogNumber]
            if (current == null) {
                order.add(candidate.catalogNumber)
                newest[candidate.catalogNumber] = candidate
            } else if (candidate.epoch > current.epoch) {
                newest[candidate.catalogNumber] = candidate
            }
        }
        val kept = ArrayList<OrbitalElements>(order.size)
        for (number in order) {
            val entry = newest[number]
            if (entry != null) kept.add(entry)
        }
        return ElementSetBatch(kept, skipped, skippedCount)
    }
}

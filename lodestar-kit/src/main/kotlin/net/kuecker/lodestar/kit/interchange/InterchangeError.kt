package net.kuecker.lodestar.kit.interchange

/**
 * Why a file could not be read or a document could not be made (Swift `InterchangeError`).
 *
 * Problems with single rows are not errors: they are reported in [ObservingListImport.errors] and the rest of the file
 * is still read.
 */
sealed class InterchangeError(message: String) : Exception(message) {
    /** The text has no content. */
    data object Empty : InterchangeError("The file is empty.")

    /** A CSV table has no column for a name or designation and no pair of right ascension and declination columns. */
    data object MissingColumns : InterchangeError("The table has no name, designation or coordinate columns.")

    /** The text is longer than [maximumScalars] Unicode scalars. */
    data class TooLarge(val maximumScalars: Int) : InterchangeError("The file is larger than $maximumScalars characters.")

    /** A quoted field that starts on [line] is never closed. */
    data class UnterminatedQuote(val line: Int) : InterchangeError("The quotation mark on line $line is never closed.")

    /** The plan is placed in azimuth and altitude, so its panels have no right ascension and declination. */
    data object HorizontalPlan : InterchangeError("A plan in azimuth and altitude has no fixed right ascension and declination.")
}

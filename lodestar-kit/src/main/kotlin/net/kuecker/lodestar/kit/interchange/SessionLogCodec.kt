package net.kuecker.lodestar.kit.interchange

import java.time.Instant

/**
 * Writes the session log as CSV and as an OpenAstronomyLog 2.1 XML document.
 *
 * Both writers take the first [maximumEntries] entries in date order (entries with the same date keep their order).
 * Cost: linear in the number of entries.
 */
object SessionLogCodec {
    /** The most entries written. */
    const val maximumEntries = 5_000

    /** The XML namespace of OpenAstronomyLog. */
    internal const val openAstronomyLogNamespace = "http://groups.google.com/group/openastronomylog"

    /** The name written for the observer when none is given. */
    internal const val defaultObserverName = "Lodestar Observer"

    // MARK: Nights

    /**
     * The night of an instant as a day count since 1970-01-01: the local calendar day of the time twelve hours earlier, so
     * that frames shot after midnight belong to the evening before, for a site [utcOffsetMinutes] east of UTC.
     */
    fun night(date: Instant, utcOffsetMinutes: Int): Long =
        InterchangeText.floorDivide(InterchangeText.wholeSeconds(date) + utcOffsetMinutes.toLong() * 60 - 43_200, 86_400L)

    /** The date of a night as "2026-11-18". */
    fun nightText(night: Long): String = InterchangeText.dateText(night)

    /** The entries in date order, at most [maximumEntries]. */
    internal fun ordered(entries: List<SessionLogEntry>): List<SessionLogEntry> =
        entries.sortedBy { it.date }.take(maximumEntries)

    // MARK: CSV

    /**
     * The log as CSV (RFC 4180, CRLF line breaks) with the columns `Date (UTC)`, `Night`, `Target`, `Target ID`,
     * `RA J2000 (deg)`, `Dec J2000 (deg)`, `Site`, `Latitude`, `Longitude`, `Camera`, `Focal length (mm)`,
     * `Aperture (f/)`, `Exposure (s)`, `ISO`, `Frames`, `Total exposure (s)`, `Filter`, `Imaged` (yes or no) and `Notes`.
     * Unknown values are empty; text that a spreadsheet could run as a formula is guarded with an apostrophe.
     */
    fun encodeCSV(entries: List<SessionLogEntry>): String {
        val rows = ArrayList<List<String>>()
        rows.add(
            listOf(
                "Date (UTC)", "Night", "Target", "Target ID", "RA J2000 (deg)", "Dec J2000 (deg)", "Site", "Latitude",
                "Longitude", "Camera", "Focal length (mm)", "Aperture (f/)", "Exposure (s)", "ISO", "Frames",
                "Total exposure (s)", "Filter", "Imaged", "Notes",
            ),
        )
        for (entry in ordered(entries)) {
            rows.add(
                listOf(
                    InterchangeText.dateTimeText(InterchangeText.wholeSeconds(entry.date)),
                    nightText(entry.night),
                    InterchangeCSV.guarded(entry.targetName),
                    InterchangeCSV.guarded(entry.targetID),
                    entry.rightAscension?.let { InterchangeText.fixed(it, 5) } ?: "",
                    entry.declination?.let { InterchangeText.fixed(it, 5) } ?: "",
                    InterchangeCSV.guarded(entry.siteName),
                    entry.siteLatitude?.let { InterchangeText.decimal(it, 5) } ?: "",
                    entry.siteLongitude?.let { InterchangeText.decimal(it, 5) } ?: "",
                    InterchangeCSV.guarded(entry.camera),
                    entry.focalLength?.let { InterchangeText.decimal(it, 1) } ?: "",
                    entry.lensAperture?.let { InterchangeText.decimal(it, 1) } ?: "",
                    InterchangeText.decimal(entry.exposureSeconds, 3),
                    if (entry.iso > 0) entry.iso.toString() else "",
                    entry.frameCount.toString(),
                    InterchangeText.decimal(entry.totalExposureSeconds, 3),
                    InterchangeCSV.guarded(entry.filter),
                    if (entry.isImaged) "yes" else "no",
                    InterchangeCSV.guarded(entry.notes),
                ),
            )
        }
        return InterchangeCSV.encode(rows)
    }

    // MARK: OpenAstronomyLog

    /**
     * The log as an OpenAstronomyLog 2.1 document (https://github.com/openastronomylog/openastronomylog, schema
     * `src/oal21.xsd`; the document is valid against it).
     *
     * - The document has one observer, and every site, session, target, scope (the lens: focal length and aperture),
     *   filter and imager (camera and sensor pixels) that the entries use once, referenced by `IDREF`.
     * - A site is written when an entry has coordinates; the entries of one night at one site form a session.
     * - A target is written with its J2000 position (`deg`, frame geocentric J2000) when known.
     * - Each entry becomes one observation: begin, end (begin plus the frames' total exposure), and a result that says
     *   what was shot (frames, exposure, ISO, filter, lens, camera, imaged or not) and carries the notes.
     * - Imagers need the sensor's pixel counts (OpenAstronomyLog's `ccdCameraType`), scopes the focal length and
     *   aperture; without them the observation has no imager or scope and the camera is only named in the result.
     * - Text is escaped (`& < > " '`), characters XML 1.0 cannot carry are dropped, dates are UTC.
     *
     * @param observer The observer's name; the last word becomes the surname. Empty gives `defaultObserverName`.
     */
    fun encodeOpenAstronomyLog(entries: List<SessionLogEntry>, observer: String = ""): String {
        val document = OpenAstronomyLogDocument()
        val observerName = InterchangeText.singleLine(observer, SessionLogEntry.maximumNameLength)
        document.setObserver(observerName.ifEmpty { defaultObserverName })
        for (entry in ordered(entries)) {
            document.add(entry)
        }
        return document.text()
    }
}

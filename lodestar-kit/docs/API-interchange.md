# Interchange (Kotlin and Swift) — API reference

Package `net.kuecker.lodestar.kit.interchange` (Swift: `LodestarKit/Sources/LodestarKit/Interchange/`). Foundation only; no network.
Reads observing lists (CSV, Telescopius CSV, SkySafari `.skylist`), writes observing lists, the session log (CSV and
OpenAstronomyLog 2.1) and the panels of a mosaic plan (CSV and text). The Swift and Kotlin sides share the fixtures in
`src/test/resources/fixtures/interchange/` (Swift: `Tests/LodestarKitTests/Fixtures/interchange/`); see the README there for
the source of each file.

Conventions
- Kotlin names on the left, Swift names on the right where they differ (Swift `Date` is `java.time.Instant`).
- Reading never traps: a file that is not usable throws `InterchangeError`; a row that is not usable is reported in
  `ObservingListImport.errors` and the rest is read.
- Text from files is cleaned: control characters are dropped, white space collapses, names are cut to 120 Unicode scalars.
- Text a spreadsheet could run as a formula (`=`, `+`, `-`, `@`, tab, carriage return at the start) is written with a leading
  apostrophe and read back without it.

---------------------------------------------------------------------------------------------------------

## ObservingListCodec  (Kotlin `object`, Swift `enum`)

```kotlin
object ObservingListCodec {
    const val maximumTargets = 5_000               // rows read and written
    const val maximumTextScalars = 4_000_000       // longest text read
    fun decode(text: String): ObservingListImport  // throws InterchangeError: Empty, TooLarge, MissingColumns, UnterminatedQuote
    fun encodeCSV(entries: List<ObservingListEntry>): String          // Name,Designation,RA J2000 (deg),Dec J2000 (deg),Type; CRLF
    fun encodeSkySafari(entries: List<ObservingListEntry>): String    // SkySafariObservingListVersion=3.0, LF, tab-indented keys
}
```

Formats (the format is found from the content):
- **Generic CSV**: RFC 4180, comma, semicolon or tab separated, header row. Header names are compared without case,
  punctuation and spacing: designation ("designation", "catalogue entry", "catalog number", "object", "id"), name ("name",
  "familiar name", "common name", "target"), "ra" / "right ascension", "dec" / "declination". A coordinate is decimal degrees
  or sexagesimal text (`05h 35m 17.3s`, `05:35:17`, `-05° 23′ 28″`); a plain number in a right ascension column is degrees
  unless the header says hours ("RA (hours)"). jNow and B1950 columns are ignored.
- **Telescopius CSV** (`Catalogue Entry`, `Familiar Name`, `Alternative Entries`, `Right Ascension (j2000)`,
  `Declination (j2000)`, `Label`, arcseconds written as `""`): `format == TELESCOPIUS_CSV`.
- **SkySafari observing list**: `SkyObject=BeginObject` … `EndObject=SkyObject` blocks with `CatalogNumber` and
  `CommonName`; there are no coordinates.

```kotlin
data class ImportedTarget(val name: String?, val designation: String?, val alternates: List<String> = emptyList(),
                          val rightAscension: Double? = null, val declination: Double? = null, val line: Int) {
    val hasCoordinates: Boolean
    val searchTerms: List<String>                // designation, alternates, name; no repeats
    val displayName: String?
}
data class ObservingListRowError(val line: Int, val reason: Reason)   // Reason: MISSING_NAME, INVALID_RIGHT_ASCENSION,
                                                                      // INVALID_DECLINATION, INCOMPLETE_COORDINATES, UNTERMINATED_OBJECT
data class ObservingListImport(val format: ObservingListFormat, val targets: List<ImportedTarget>,
                               val errors: List<ObservingListRowError>, val isTruncated: Boolean)
data class ObservingListEntry(val name: String, val designation: String? = null, val rightAscension: Double? = null,
                              val declination: Double? = null, val typeName: String? = null)
enum class ObservingListFormat { GENERIC_CSV, TELESCOPIUS_CSV, SKY_SAFARI }   // Swift: genericCSV, telescopiusCSV, skySafari
```

## ObservingListMatcher

```kotlin
object ObservingListMatcher {
    fun match(target: ImportedTarget, catalog: Catalog): CelestialObject?        // first search term with an EXACT catalog match
    fun match(targets: List<ImportedTarget>, catalog: Catalog): List<ObservingListMatch>   // ObservingListMatch(target, obj)
}
```
Cost: one `Catalog.search` per search term, a fraction of a millisecond each; run it off the main thread.

## SessionLogEntry and SessionLogCodec

```kotlin
data class SessionLogEntry(id, date: Instant, targetID, targetName, rightAscension?, declination?, siteName, siteLatitude?,
                           siteLongitude?, siteUTCOffsetMinutes?, camera, focalLength?, lensAperture?, sensorPixelsX?,
                           sensorPixelsY?, exposureSeconds, iso, frameCount, filter, notes, isImaged)
```
Every value is cleaned on creation (text cut and stripped, numbers clamped or dropped, coordinates only as a pair).
Kotlin creates entries with the companion `invoke`; the constructor is private.
`totalExposureSeconds`, `hasCoordinates` and `night` are derived.

```kotlin
object SessionLogCodec {
    const val maximumEntries = 5_000
    fun night(date: Instant, utcOffsetMinutes: Int): Long    // days since 1970-01-01 of the local time 12 h earlier
    fun nightText(night: Long): String                       // "2026-11-18"
    fun encodeCSV(entries: List<SessionLogEntry>): String    // 19 columns, date order, CRLF
    fun encodeOpenAstronomyLog(entries: List<SessionLogEntry>, observer: String = ""): String
}
```
The OpenAstronomyLog document is version 2.1 (schema `src/oal21.xsd` of https://github.com/openastronomylog/openastronomylog,
Apache-2.0) and validates against it: one observer, sites (from entries with coordinates), sessions (one per night and site),
targets, scopes (the lens), filters, imagers (when the sensor's pixel counts are known) and one observation per entry.

## MosaicPanelCodec

```kotlin
data class MosaicPanelRecord(panel, row, column, rightAscension, declination, positionAngle, order)
object MosaicPanelCodec {
    fun records(plan: MosaicPlan): List<MosaicPanelRecord>   // throws InterchangeError.HorizontalPlan for a plan in azimuth and altitude
    fun csv(records: List<MosaicPanelRecord>): String        // Panel,Row,Column,RA J2000 (deg),Dec J2000 (deg),Position angle (deg),Order
    fun text(records: List<MosaicPanelRecord>, targetName: String): String   // "M 31 R1C2, 00 42 44.3, +41 16 07" in shooting order
}
```
`positionAngle` is the sky position angle of the direction from the panel centre to the middle of its top edge, from north
through east.

## InterchangeError

`Empty`, `MissingColumns`, `TooLarge(maximumScalars)`, `UnterminatedQuote(line)`, `HorizontalPlan` (Swift: an `enum` with the
same cases, `description` for the sentence).

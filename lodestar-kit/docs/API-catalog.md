# Catalog (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.catalog`. Port of `LodestarKit/Sources/LodestarKit/Catalog/*.swift` plus the bundled data
(`stars.json`, `dsos.json`, `constellations.json`, `milkyway.json`, unchanged, in
`src/main/resources/net/kuecker/lodestar/kit/catalog/`). Depends on Foundation (coordinates, `SkyFrame`, `DateInterval`,
`Observer`), Ephemeris (`SolarSystemBody`, `RiseSetCalculator`, `RiseTransitSet`, the `SkyFrame` body extensions) and, for the
text of rejection messages only, Formatting (`NumberText.swiftDescription`, internal).

Import with `import net.kuecker.lodestar.kit.catalog.*`. The `SkyFrame` / `RiseSetCalculator` extensions below are top-level
functions: a plain class import misses them (the app imports `net.kuecker.lodestar.kit.catalog.horizontal`).

Conventions used below
- `public` is the default; `internal` is marked. `Companion` members are listed under their class.
- Swift `Data` is `ByteArray`; Swift `throws` is a thrown `CatalogError` (a sealed `Exception`); no declaration here throws anything else.
- Everything the loader returns is immutable and safe to share between threads; nothing in a loaded catalog is ever written again.
- "Swift: …" names the Swift declaration a Kotlin one replaces when the name differs.

---------------------------------------------------------------------------------------------------------

## Catalog.kt  (Swift `Catalog.swift`)

```kotlin
@ConsistentCopyVisibility data class RenderStar internal constructor(
    val id: String,                    // id of the star's CelestialObject
    val unitVector: Vector3,           // J2000 direction, no proper motion
    val magnitude: Double,
    val colorIndex: Double?,
    val name: String?,                 // proper name
)

class Catalog private constructor(contents: CatalogContents) {
    constructor(starsData: ByteArray, dsosData: ByteArray, constellationsData: ByteArray, milkyWayData: ByteArray)  // throws CatalogError
    val objects: List<CelestialObject>            // Sun, Moon, planets, Milky Way core + poles, deep-sky objects, stars bright to faint
    val listedObjects: List<CelestialObject>      // objects with isListed, in the order of `objects`
    val constellations: List<Constellation>       // 88, in file order (alphabetical by name)
    val milkyWay: MilkyWayOutline
    val renderStars: List<RenderStar>             // all stars, bright to faint
    val loadReport: CatalogLoadReport
    internal val searchIndex: SearchIndex
    fun objectWithID(id: String): CelestialObject?                 // Swift object(id:); also answers to acronyms ("dso.LMC")
    fun search(query: String, limit: Int = 50): List<CatalogSearchResult>   // member here, an extension in Swift
    fun objects(group: ObjectGroup): List<CelestialObject>         // Swift objects(in:); listed objects of a group, sorted by name
    fun constellation(abbreviation: String): Constellation?        // case-insensitive
    fun sorted(objects: List<CelestialObject>, order: CatalogSortOrder): List<CelestialObject>   // Swift sorted(_:by:)
    internal fun text(entry: Int, obj: CelestialObject): String    // Swift text(ofEntry:in:)
    companion object {
        const val supportedSchemaVersion: Int          // 1
        const val maximumFileBytes: Int                // 64 MiB
        const val maximumQueryLength: Int              // 64 scalars (Swift: extension static in CatalogSearch.swift)
        fun loadBundled(): Catalog                     // reads the four resources; ~1.4 MB of JSON; call off the main thread
        internal fun bundledData(file: CatalogFile): ByteArray     // MissingResource / Unreadable
    }
}
```

## CatalogSearch.kt  (Swift `CatalogSearch.swift`)

```kotlin
@ConsistentCopyVisibility data class CatalogSearchResult internal constructor(
    val obj: CelestialObject,          // Swift `object`
    val matchedText: String,           // displayed name / designation that matched ("α CMa")
    val kind: MatchKind,
) {
    enum class MatchKind(val rawValue: Int) {          // EXACT(0) PREFIX(1) WORD_PREFIX(2) CONTAINS(3) FUZZY(4); entries are ordered best first
        companion object { fun fromRawValue(raw: Int): MatchKind? }
    }
    val id: String                                     // obj.id
}
internal fun Catalog.performSearch(query: String, limit: Int): List<CatalogSearchResult>   // implementation of Catalog.search
```

## CelestialObject.kt  (Swift `CelestialObject.swift`)

```kotlin
sealed interface ObjectPosition {
    data class Fixed(val coordinate: EquatorialCoordinate, val properMotion: ProperMotion?) : ObjectPosition   // Swift .fixed(_, _)
    data class SolarSystem(val body: SolarSystemBody) : ObjectPosition                                        // .solarSystem(_)
    data class CelestialPole(val north: Boolean) : ObjectPosition                                             // .celestialPole(north:)
}

@ConsistentCopyVisibility data class CelestialObject internal constructor(
    val id: String, val name: String,
    val designations: List<String> = emptyList(), val alternateNames: List<String> = emptyList(),
    val category: ObjectCategory,
    val constellation: String? = null, val constellationName: String? = null,
    val magnitude: Double? = null, val angularSize: AngularSize? = null,
    val position: ObjectPosition,
    val colorIndex: Double? = null, val spectralType: String? = null, val distanceLightYears: Double? = null,
    val isListed: Boolean, val notes: String? = null,
) {
    override fun hashCode(): Int                       // id only; equals compares every property (as in Swift)
    val group: ObjectGroup
    val j2000: EquatorialCoordinate?                   // fixed objects only
    val isSolarSystem: Boolean
    val subtitle: String                               // "Galaxy · Andromeda · mag 3.4"
    internal val rankingMagnitude: Double              // catalog magnitude, typical peak magnitude for bodies, +∞ if unknown
    companion object {
        const val maximumCustomNameLength: Int         // 60
        const val defaultCustomName: String           // "Custom Target"
        fun custom(name: String, j2000: EquatorialCoordinate): CelestialObject           // new UUID
        fun custom(id: UUID, name: String, j2000: EquatorialCoordinate): CelestialObject // "custom." + UPPER-CASE uuid
    }
}
```

## ObjectCategory.kt  (Swift `ObjectCategory.swift`)

```kotlin
@Serializable enum class ObjectCategory(val rawValue: String) {
    SUN, MOON, PLANET, STAR, DOUBLE_STAR, GALAXY, GALAXY_GROUP, EMISSION_NEBULA, REFLECTION_NEBULA, DARK_NEBULA, NEBULA,
    PLANETARY_NEBULA, SUPERNOVA_REMNANT, OPEN_CLUSTER, GLOBULAR_CLUSTER, CLUSTER_WITH_NEBULA, STAR_ASSOCIATION, ASTERISM,
    MILKY_WAY, CELESTIAL_POLE, CUSTOM, OTHER          // JSON / rawValue: the Swift case name, e.g. "doubleStar"
    val displayName: String
    val systemImageName: String                        // SF Symbol name (the iOS icons); the Android app maps the enum itself
    val group: ObjectGroup
    companion object {
        fun fromRawValue(raw: String): ObjectCategory?
        fun fromOpenNGCType(code: String): ObjectCategory      // Swift init(openNGCType:)
    }
}
@Serializable enum class ObjectGroup(val rawValue: String) {
    SOLAR_SYSTEM, STARS, GALAXIES, NEBULAE, CLUSTERS, SPECIAL
    val displayName: String
    val systemImageName: String
    val categories: List<ObjectCategory>
    companion object { fun fromRawValue(raw: String): ObjectGroup? }
}
```

## AngularSize.kt  (Swift `AngularSize.swift`)

```kotlin
@Serializable(with = AngularSizeSerializer::class) @ConsistentCopyVisibility
data class AngularSize private constructor(val majorArcminutes: Double, val minorArcminutes: Double?, val positionAngle: Double?) {
    val description: String                            // "178′ × 70′", "8.4′", "36″"; toString() returns it
    companion object {
        const val maximumArcminutes: Double            // 21600
        fun create(majorArcminutes: Double, minorArcminutes: Double? = null, positionAngle: Double? = null): AngularSize?   // Swift init?
    }
}
internal object AngularSizeSerializer : KSerializer<AngularSize>    // keys majorArcminutes, minorArcminutes?, positionAngle?; strict types
```

## Constellation.kt  (Swift `Constellation.swift`)

```kotlin
@ConsistentCopyVisibility data class Constellation internal constructor(
    val abbreviation: String, val name: String, val genitive: String,
    val labelPosition: EquatorialCoordinate, val lines: List<List<EquatorialCoordinate>>,
) { val id: String }                                   // abbreviation
@ConsistentCopyVisibility data class MilkyWayOutline internal constructor(val levels: List<Level>) {
    @ConsistentCopyVisibility data class Level internal constructor(
        val level: Int, val polygons: List<List<EquatorialCoordinate>>, val holes: List<List<EquatorialCoordinate>>)
}
```

## CatalogError.kt  (Swift `CatalogError.swift`)

```kotlin
enum class CatalogFile(val rawValue: String) {        // STARS("stars") DEEP_SKY_OBJECTS("deepSkyObjects") CONSTELLATIONS("constellations") MILKY_WAY("milkyWay")
    internal val resourceName: String                  // "stars", "dsos", "constellations", "milkyway"
    val fileName: String                               // "stars.json", "dsos.json", …
    companion object { fun fromRawValue(raw: String): CatalogFile? }
}
sealed class CatalogError(val description: String) : Exception(description) {      // message == description
    val errorDescription: String?
    data class MissingResource(val file: CatalogFile) : CatalogError
    data class Unreadable(val file: CatalogFile, val reason: String) : CatalogError
    data class Malformed(val file: CatalogFile, val reason: String) : CatalogError
    data class UnsupportedSchema(val file: CatalogFile, val version: Long?) : CatalogError
    data class NoValidRecords(val file: CatalogFile, val rejected: Int) : CatalogError
}
class CatalogLoadReport internal constructor() {
    class Counts internal constructor() { var accepted: Int; var merged: Int; var rejected: Int }   // setters internal
    var stars: Counts; var deepSkyObjects: Counts; var constellations: Counts          // setters internal
    var constellationLines: Counts; var milkyWayRings: Counts
    var issues: List<String>                           // the first `maximumIssueCount` problems
    val rejectedCount: Int
    val isClean: Boolean
    internal inline fun note(message: () -> String)
    internal inline fun reject(counts: (CatalogLoadReport) -> Counts, message: () -> String)   // e.g. reject(CatalogLoadReport::stars) { "…" }
    companion object { const val maximumIssueCount: Int }      // 25
}                                                      // equals / hashCode / toString over all members
```

## CatalogOrdering.kt  (Swift `CatalogOrdering.swift`)

```kotlin
enum class CatalogSortOrder(val rawValue: String) {   // NAME("name"), MAGNITUDE("magnitude")
    companion object { fun fromRawValue(raw: String): CatalogSortOrder? }
}
internal object CatalogOrdering {
    class Item(val index: Int, val name: String, val nameKey: ByteArray, val magnitude: Double, val isListed: Boolean, val id: String)
    val nameComparator: Comparator<Item>; val magnitudeComparator: Comparator<Item>; val searchComparator: Comparator<Item>   // three-way forms
    fun items(objects: List<CelestialObject>): Array<Item>
    inline fun items(objects: List<CelestialObject>, nameKey: (Int) -> ByteArray): Array<Item>   // with the keys made already
    fun sorted(objects: List<CelestialObject>, order: CatalogSortOrder): List<CelestialObject>
    fun nameOrder(lhs: Item, rhs: Item): Boolean; fun magnitudeOrder(lhs: Item, rhs: Item): Boolean; fun searchOrder(lhs: Item, rhs: Item): Boolean
}
```

## CelestialObjectSky.kt  (Swift `CelestialObject+Sky.swift`)

```kotlin
fun SkyFrame.horizontal(obj: CelestialObject, refraction: Boolean = true): HorizontalCoordinate   // Swift horizontal(of:refraction:)
fun SkyFrame.apparentEquatorial(obj: CelestialObject): EquatorialCoordinate                       // apparentEquatorial(of:)
fun SkyFrame.currentAngularSize(obj: CelestialObject): AngularSize?                               // currentAngularSize(of:)
fun SkyFrame.currentMagnitude(obj: CelestialObject): Double?                                      // currentMagnitude(of:)
fun RiseSetCalculator.riseTransitSet(obj: CelestialObject, observer: Observer, interval: DateInterval): RiseTransitSet   // riseTransitSet(for:observer:within:)
```
`frame.horizontal(obj, true)` resolves next to Foundation's members and Ephemeris' `horizontal(body, refraction)` by the type of the first argument.

## Search internals  (Swift `SearchText.swift`, `SearchIndex.swift`, `GreekAlphabet.swift`) — all internal

```kotlin
internal object SearchText {
    const val maximumKeyLength: Int                    // 64
    const val maximumQueryScalars: Int                 // 64
    class Key(val bytes: ByteArray, val wordStarts: Long)          // wordStarts: the 64-bit mask (Swift UInt64); equals/hashCode by content
    fun key(text: String, maximumScalars: Int = Int.MAX_VALUE): Key           // Swift key(for:maximumScalars:)
    fun naturalPrecedes(lhs: ByteArray, rhs: ByteArray): Boolean
    fun naturalCompare(lhs: ByteArray, rhs: ByteArray): Int                   // three-way form (Kotlin addition)
}
internal class KeyBuilder {                            // Swift: private struct; here reusable, sums up the symbol mask
    val bytes: ByteArray; var count: Int; var wordStarts: Long; var symbolMask: Long
    fun reset(); fun readText(text: String, maximumScalars: Int); fun complete(); fun toKey(): SearchText.Key
    fun read(scalar: Int, allowDecomposition: Boolean)
}
internal object GreekAlphabet {
    data class Letter(val name: String, val abbreviations: List<String>) { val capitalizedName: String; val forms: List<String> }
    val letters: List<Letter>; val nameBytes: List<ByteArray>
    fun index(scalar: Int): Int?                       // scalar = Unicode code point
    fun letter(scalar: Int): Letter?
}
internal data class SearchHit(val objectIndex: Int, val keyIndex: Int, val kind: CatalogSearchResult.MatchKind)
internal data class SearchKeySource(val obj: Int, val entry: Int)              // Swift's (object:entry:) tuple
internal class SearchIndex(val text: ByteArray, val keyStarts: IntArray, val keyOwners: IntArray, val keyWordStarts: LongArray,
                           val keyEntries: IntArray, val keyLengths: IntArray, val symbolMasks: LongArray, val objectsByRank: IntArray) {
    val keyCount: Int
    fun source(keyIndex: Int): SearchKeySource
    fun search(query: ByteArray, limit: Int): List<SearchHit>
    companion object { const val minimumFuzzyLength: Int; fun symbolBit(byte: Byte): Long; fun symbolMask(bytes: ByteArray): Long }
}
internal class SearchIndexBuilder {
    val keyCountOfCurrentObject: Int
    fun beginObject(); fun add(string: String, entry: Int); fun lastKeyBytes(): ByteArray; fun finish(objectsByRank: IntArray): SearchIndex
}
```

## Building the catalog  (Swift `CatalogAssembler.swift`, `CatalogFactory.swift`, `BuiltInObjects.swift`) — all internal

```kotlin
internal class CatalogContents(objects, constellations: ConstellationDirectory, milkyWay, renderStars, report, searchIndex, indexByID, listedByGroup)
internal object CatalogAssembler { fun assemble(starsData: ByteArray, dsosData: ByteArray, constellationsData: ByteArray, milkyWayData: ByteArray): CatalogContents }
internal object BuiltInObjects { fun solarSystem(): List<CelestialObject>; fun special(constellations: ConstellationDirectory): List<CelestialObject> }
internal class KeyText(val text: String, val entry: Int)
internal class ObjectDraft(val obj: CelestialObject, val keys: List<KeyText>) { companion object { fun plain(obj: CelestialObject): ObjectDraft } }  // Swift init(plain:)
internal class ConstellationDirectory(val constellations: List<Constellation>) { fun constellation(abbreviation: String): Constellation? }
internal object CatalogDesignation { class Spelling(val display: String, val aliases: List<String>); fun spelling(id: String): Spelling }   // Swift spelling(ofIdentifier:)
internal object CatalogFactory {
    const val lightYearsPerParsec: Double; const val maximumListedStarMagnitude: Double; const val quasarHipparcosNumber: Int
    val popularNames: Map<Int, List<String>>
    fun star(entry: StarEntry, constellations: ConstellationDirectory, duplicates: List<DeepSkyEntry> = emptyList()): ObjectDraft
    fun deepSkyObject(entry: DeepSkyEntry, constellations: ConstellationDirectory): ObjectDraft
    fun spellings(entry: DeepSkyEntry): List<CatalogDesignation.Spelling>
    fun acronymAlias(alternateName: String): String?
}
internal object CatalogFormat { const val minus: String; fun oneDecimal(value: Double): String; fun trimmedOneDecimal(value: Double): String }
```

## Decoding and validation  (Swift `CatalogDecoding.swift`) — all internal

```kotlin
internal class Rejection(val reason: String) : Exception        // no stack trace
internal class StarRecord(hip, hr, hd, hyg: Long?, n, b: String?, f: Long?, c: String?, ra, de, pa, pd, m, ci: Double?, sp: String?, d: Double?) { companion object { fun read(reader: CatalogJsonReader): StarRecord? } }
internal class DeepSkyRecord(id: String?, m: Long?, ngc, ic: List<Long>?, names: List<String>?, t: String?, ra, de: Double?, c: String?, vm, bm, maj, minorAxis, pa: Double?, h: String?, sb: Double?) { companion object { fun read(…): DeepSkyRecord? } }
internal class ConstellationRecord(abbr, name, genitive: String?, label: DoubleArray?, lines: List<List<DoubleArray>?>?) { companion object { fun read(…): ConstellationRecord? } }
internal class MilkyWayLevelRecord(level: Long?, polygons: List<List<DoubleArray>?>?, holes: List<List<DoubleArray>?>?) { companion object { fun read(…): MilkyWayLevelRecord? } }
internal class StarEntry(…, val position: EquatorialCoordinate, …, val sourceIndex: Int) { val unitVector: Vector3; val objectID: String }
internal class DeepSkyEntry(…)
internal class ValidatedConstellation(val constellation: Constellation, val rejectedLines: List<Int>)
internal class ValidatedLevel(val level: MilkyWayOutline.Level, val rejectedRings: List<String>)
internal object CatalogValidation {
    const val maximumNameScalars: Int                  // 80
    fun star(record: StarRecord, sourceIndex: Int): StarEntry
    fun deepSkyObject(record: DeepSkyRecord): DeepSkyEntry
    fun constellation(record: ConstellationRecord): ValidatedConstellation
    fun milkyWayLevel(record: MilkyWayLevelRecord): ValidatedLevel
}
```
A `null` record is a `Lossy` element that failed to decode (Swift `Lossy<Value>.value == nil`).

## CatalogJsonReader.kt  (Kotlin only — replaces `JSONDecoder` + `Decodable`) — all internal

```kotlin
internal class CatalogJsonException(message: String) : Exception     // no stack trace; becomes CatalogError.Malformed
internal class DecodedFile<R>(val schema: Long?, val records: List<R?>)
internal class CatalogJsonReader(buffer: ByteArray) {
    var failedMask: Int
    fun <R> readFile(recordsKey: String, readRecord: (CatalogJsonReader) -> R?): DecodedFile<R>
    fun beginRecord(): Boolean; fun nextKey(first: Boolean): Long; fun skipValue()
    fun long(bit: Int): Long?; fun double(bit: Int): Double?; fun string(bit: Int): String?
    fun longList(bit: Int): List<Long>?; fun stringList(bit: Int): List<String>?; fun doubleList(bit: Int): DoubleArray?
    fun ringList(bit: Int): List<List<DoubleArray>?>?
    companion object { const val MAXIMUM_DEPTH: Int; const val END_OF_OBJECT: Long; const val OTHER_KEY: Long; fun keyCode(name: String): Long }
}
```

## CatalogText.kt  (Kotlin only — Swift `String` / `Unicode.Scalar` behaviour) — internal

```kotlin
internal object CatalogText {
    fun isWhiteSpace(scalar: Int): Boolean             // Unicode White_Space (Swift scalar.properties.isWhitespace)
    fun isControl(scalar: Int): Boolean                // general category Cc
    fun isSpaceOrTab(scalar: Int): Boolean             // CharacterSet.whitespaces
    fun trimmingSpacesAndTabs(text: String): String    // trimmingCharacters(in: .whitespaces)
    fun prefixCharacters(text: String, maxLength: Int): String   // String.prefix(_:) by grapheme clusters
    fun utf8Length(text: String): Int                  // text.utf8.count without encoding
    fun compare(lhs: String, rhs: String): Int         // Swift String ordering: NFC scalars
}
```

## Resources

`src/main/resources/net/kuecker/lodestar/kit/catalog/{stars,dsos,constellations,milkyway}.json` — byte-identical to the Swift
resources (checked with md5). Loaded by the absolute path `/net/kuecker/lodestar/kit/catalog/<name>.json` through
`Catalog::class.java.getResourceAsStream`.

---------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

### Names
| Swift | Kotlin |
| --- | --- |
| `Catalog.object(id:)` | **`Catalog.objectWithID(id)`** |
| `CatalogSearchResult.object` | **`CatalogSearchResult.obj`** |
| `Catalog.objects(in:)` | `Catalog.objects(group)` (a property `objects` and a method `objects(group)` coexist) |
| `Catalog.sorted(_:by:)` | `Catalog.sorted(objects, order)` |
| `Catalog.constellation(abbreviation:)` | `Catalog.constellation(abbreviation)` |
| `Catalog(starsData:dsosData:constellationsData:milkyWayData:)` | same four `ByteArray`s, positional or named |
| `SkyFrame.horizontal(of:refraction:)` etc. | extension functions `horizontal(obj, refraction = true)`, `apparentEquatorial(obj)`, `currentAngularSize(obj)`, `currentMagnitude(obj)` |
| `RiseSetCalculator.riseTransitSet(for:observer:within:)` | `RiseSetCalculator.riseTransitSet(obj, observer, interval)` (extension; `obj` is a `CelestialObject`) |
| `AngularSize(majorArcminutes:minorArcminutes:positionAngle:)` (failable) | **`AngularSize.create(major, minor = null, angle = null)`** |
| `ObjectCategory(openNGCType:)` | `ObjectCategory.fromOpenNGCType(code)` |
| `ObjectCategory(rawValue:)`, `ObjectGroup(rawValue:)`, `CatalogFile(rawValue:)`, `CatalogSortOrder(rawValue:)`, `MatchKind(rawValue:)` | `X.fromRawValue(raw)` |
| `ObjectPosition.fixed / solarSystem / celestialPole(north:)` | `ObjectPosition.Fixed(coordinate, properMotion)` / `SolarSystem(body)` / `CelestialPole(north)` |
| enum cases | UPPER_SNAKE_CASE (`galaxyGroup` → `GALAXY_GROUP`, `solarSystem` → `SOLAR_SYSTEM`, `deepSkyObjects` → `DEEP_SKY_OBJECTS`, `wordPrefix` → `WORD_PREFIX`); `allCases` → `entries` |
| `CatalogError` cases `.malformed(file, reason:)` … | nested data classes `CatalogError.Malformed(file, reason)`, `MissingResource`, `Unreadable`, `UnsupportedSchema(file, version: Long?)`, `NoValidRecords(file, rejected)` |
| `CatalogLoadReport.reject(\.stars, "…")` | `report.reject(CatalogLoadReport::stars) { "…" }` (lambda: the message is only built when it is kept) |
| `ObjectDraft(plain:)`, `CatalogDesignation.spelling(ofIdentifier:)`, `GreekAlphabet.index(of:)/letter(for:)`, `SearchText.key(for:)`, `SearchIndex.source(ofKey:)`, `CatalogFactory.spellings(of:)` | `ObjectDraft.plain(obj)`, `spelling(id)`, `index(scalar)` / `letter(scalar)` (scalar = code point `Int`), `SearchText.key(text, …)`, `source(keyIndex)`, `spellings(entry)` |
| `CatalogOrdering.nameOrder(_:_:) -> Bool` | kept, plus three-way `Comparator`s (`nameComparator`, …) used for sorting |

### Where a Swift extension became a member
`Catalog.search(_:limit:)` and `Catalog.maximumQueryLength` are declared in `CatalogSearch.swift` as extensions. They are members of
`Catalog` / its companion here, because the app calls `catalog.search(query, limit)` with only `import …catalog.Catalog` (an
extension function would need its own import). The implementation lives in `CatalogSearch.kt` (`Catalog.performSearch`).

### Types and values
- `CelestialObject` is a `data class` with an `internal` constructor (`@ConsistentCopyVisibility`, so `copy` is internal too);
  `equals` compares every property, `hashCode` uses `id` only, like Swift's custom `hash(into:)`. Custom targets get the id
  `"custom." + UUID.toString().uppercase(Locale.ROOT)` so that they match the ids Swift's `uuidString` writes.
- `Hashable` value types (`Constellation`, `MilkyWayOutline`, `RenderStar`, `CatalogSearchResult`, `AngularSize`, `ObjectPosition`,
  `CatalogError`) are data classes. Double members compare with `equals` (NaN equals NaN, 0.0 differs from -0.0); nothing depends on it.
- `CatalogLoadReport` and `CatalogLoadReport.Counts` are classes with public getters and `internal` setters (Swift:
  `public internal(set) var`); they have value-like `equals`/`hashCode`/`toString`, and they are only written while the catalog is
  being built.
- `UnsupportedSchema.version` is `Long?` (Swift `Int?`, 64 bit) so that a huge `schema` number is reported as it is.
- `SearchText.Key.wordStarts` is a `Long` holding the 64-bit pattern (Swift `UInt64`); `UInt8` bytes are `Byte` (read with `and 0xFF`).
- `SearchIndex` keeps the keys as parallel primitive arrays (Swift: `[KeyRecord]`); scan loops read only `symbolMasks` for the keys
  they reject. `MatchKind` stores its Swift `Int` raw value in `rawValue`, and Kotlin enums compare by ordinal, which is the same order.
- `KeyBuilder` is `internal` and reusable (Swift: `private struct`), the index builder reuses one for its 50 000 keys and takes the
  character mask from it; `SearchIndexBuilder.keyCountOfCurrentObject` / `lastKeyBytes()` let the assembler reuse each name's key
  for `CatalogOrdering.Item.nameKey`. Results are identical to making each key afresh (a test checks this).
- `ConstellationDirectory.constellation(abbreviation)` first tries the abbreviation as spelled (no copy), then the lower-cased one.

### JSON decoding (the biggest difference)
Swift decodes with `JSONDecoder` into `Decodable` structs with `Lossy` elements. Kotlin has no counterpart that is as strict and
as fast, so `CatalogJsonReader` is a hand-written pull parser over the UTF-8 bytes that builds the record objects directly. It
reproduces what the Swift code depends on:
- RFC 8259 grammar only: no `NaN`/`Infinity`, no `+1`, `01`, `.5`, `1.`, no trailing commas, single quotes, comments or trailing
  content; raw control characters in strings, malformed UTF-8 (overlong forms, surrogates, > U+10FFFF) and lone surrogate
  `\u` escapes are errors (`Malformed` for the whole file, like a syntax error in Swift).
- At most 512 nested containers (the depth limit of `JSONDecoder`); deeper input is `Malformed`, never a stack overflow.
- A syntax error anywhere fails the file even after valid records; type errors fail only the record (`null` entry = Swift's `Lossy`).
- Member types as `decodeIfPresent`: absent and `null` are alike; a wrong type spoils the record; an `Int` field takes any number that
  is exactly an integer in 64-bit range (`1`, `1.0`, `1e2`) and rejects `1.5`, quotes, booleans, overflow; a `Double` field takes any
  finite number (`1e999` spoils the record); the last occurrence of a repeated key counts; unknown members are skipped but must
  be valid JSON. A UTF-8 byte order mark at the start is skipped (Swift's behaviour for BOMs is not documented).
- Numbers are converted exactly: up to 18 significant digits with a decimal exponent of at most 22 take the exact
  `mantissa ×/÷ 10ᵏ` path, everything else `Double.parseDouble`; checked bit for bit against Python's `float` on 120 000 random tokens.
- Syntax verdicts were checked against Python's strict `json` module on 60 000 mutated documents with 0 differences (the same
  verdicts are in the test fixture `catalog_json_cases.txt`, 707 cases).
- Measured on the stars file (1.3 MB, JVM 21, warm): this reader 13–25 ms; `kotlinx.serialization` `parseToJsonElement` 32–360 ms
  (tree); typed streaming decode into `@Serializable` classes 47–370 ms; in interpreter-only mode (`-Xint`, a stand-in for a cold
  Android start) 0.39 s against 2.4 s and 3.3 s. Cold first call in a fresh JVM: about 55 ms against 190–200 ms.

### Strings and Unicode
- Swift counts grapheme clusters and iterates Unicode scalars; Kotlin strings are UTF-16. `CatalogText` supplies White_Space,
  Cc, `CharacterSet.whitespaces`, `prefix(_:)` by grapheme cluster (`java.text.BreakIterator`, ROOT locale), `utf8.count` and
  Swift's NFC scalar ordering for `String <`/`!=` (used where names without Latin letters tie). Search keys iterate code points and use
  `java.text.Normalizer` NFKD where Swift calls `decomposedStringWithCompatibilityMapping`.
- Unicode data comes from the JDK / the Android ICU in use, not from Apple's. Characters assigned in a newer Unicode version than
  the runtime's can be treated as unassigned (dropped from custom names, separators in search keys); no character the bundled data uses is affected.
- `Locale.ROOT` everywhere (`lowercase`, `uppercase`); the default locale is never used.
- Diagnostic texts of `Rejection` that print a `Double` use `NumberText.swiftDescription` (Formatting) so that they read like Swift's `\(value)`.

### Loading
- Resources are read with the **absolute** path `/net/kuecker/lodestar/kit/catalog/<name>.json` (not a name relative to the class):
  R8 renames packages, which would break a relative lookup in the minified release app, while the resource files keep their paths.
  No keep rule is needed.
- `Catalog.bundledData(file)` throws `MissingResource` / `Unreadable` instead of Swift's `Bundle.module` lookup and `.mappedIfSafe`.
- Sorting uses the JDK's stable `Arrays.sort`; every order ends in the object id, so the result is the same as Swift's whatever the algorithm.
- `duplicatedStar` computes the unit vectors once and uses `Vector3.angle`, which is exactly what `EquatorialCoordinate.angularSeparation` does.

### Behaviour kept from Swift on purpose (do not "fix")
- Plain-star records of the deep-sky data within one arcminute of a catalogued star are merged into it (NGC 1990 → Alnilam, IC 1318 → Sadr).
- Rejections are reported in the order of Swift's initializer arguments (e.g. a star with a bad name and a bad position reports the name).
- `custom(name:j2000:)` makes non-finite components 0 *after* the coordinate type has normalized them (infinite right ascension → 0, infinite declination → ±90).
- A celestial pole is "always up" when its altitude is `>=` the standard horizon altitude (−0.5667°).

### Tests
`src/test/kotlin/net/kuecker/lodestar/kit/catalog/`: 1:1 ports of the eight Swift suites (150 tests, same names, inputs, expectations)
— `CatalogLoadingTests` (25, with `CatalogFixture`), `CatalogMalformedDataTests` (31), `CatalogModelTests` (22),
`CatalogPerformanceTests` (5), `CatalogSearchIndexTests` (6), `CatalogSearchTests` (27) + `CatalogSearchTextTests` (11) = 38 in the
Swift file, `CatalogSkyTests` (15), `CatalogSortingTests` (8). Changed because Swift has no counterpart:
- `CatalogModelTests.testThePublicTypesAreSendable`: `Sendable` is a compile-time promise; the Kotlin test checks the property it stands for
  (every instance field of the public types is `final`; `CatalogLoadReport` is documented as written only by the loader).
- `catalog(withStar:)` / `catalog(withDeepSky:)` (overloads by label) are `catalogWithStar(record)` / `catalogWithDeepSky(record)`.
- `DispatchQueue.concurrentPerform` / `Task.detached` are a fixed thread pool; `JSONDecoder` of the raw files is `kotlinx.serialization`
  (an independent decoder of the same files); `UUID.uuidString` is compared in upper case.
- `CatalogPerformanceTests`: after a warm-up run, three times the optimized Swift budgets (load 3 s, search 15 ms, sorts 300 ms).
- `CatalogSearchTests.testRandomTextNeverCrashes` / `testGarbageInputNeverCrashes` build strings from code points (no lone surrogates).
Extras (not in Swift): `CatalogJsonReaderTests` (11: grammar, 512-level nesting, UTF-8, escapes, numbers bit for bit, integers, record
and polyline shapes, the 707-case corpus), `CatalogPortExtrasTests` (20: boundaries of Swift rules found by mutating the port,
determinism of two loads, index/keys consistency, `CatalogText` against `\p{IsWhite_Space}` for all code points, error and report
values, JSON shapes), `contract/AppContractTests` (3, in package `…catalog.contract` with exactly the imports the Android app uses).

### Performance notes (JVM 21, warm, one core, shared machine so noisy)
`Catalog.loadBundled()` 30–80 ms (best 29 ms); first call in a fresh JVM about 0.5 s; interpreter-only about 1.7 s. A search over the
9 704 objects (about 39 000 keys) averages 0.2 ms, worst query 0.5 ms; `search("a", Int.MAX_VALUE)` 3 ms; sorting the listed objects
5 ms, all objects 16 ms; 194 000 id lookups 55 ms. Allocation per search: two arrays with one entry per object and the result.

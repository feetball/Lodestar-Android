# Deep-sky catalogues and prominence — API (Kotlin and Swift)

Extends the catalog module (`net.kuecker.lodestar.kit.catalog` / `LodestarKit` `Catalog/`). Everything is added to
both engines with the same names; where a Swift name differs it is given. No existing declaration changed
signature. What changed in behaviour is listed under "Behaviour changes" — read that before touching the UI.

## What is new

| | Before | Now |
| --- | --- | --- |
| `dsos.json` | 693 objects, 86 KB: Messier, named, V ≤ 10, nebulae ≥ 10′ | **2,441 objects, 284 KB**: + Caldwell (109), Sharpless 2 (313), Barnard (349), Lynds (398 of 1,787), 17 large Collinder/Melotte/Trumpler/Stock clusters, 736 more NGC/IC objects (V ≤ 11.5, nebulae ≥ 3′, clusters ≥ 30′) |
| schema | 1 | **1** (two new optional members per record: `cat`, `p`; old decoders skip them) |
| per object | — | `prominence` (1 showpiece … 4 obscure), `catalogNumbers`, `catalogues`, `isDeepSky` |
| catalog | `objects(in group)` | + `objects(in catalogue)`, `deepSkyObjects`, `deepSkyObjects(upTo:)` |
| search | M/NGC/IC | + `C 14`, `Caldwell 14`, `Sh2-155`, `Sh 2-155`, `Sharpless 155`, `B33`, `Barnard 33`, `LDN 1622`, `Lynds 1622`, `Cr 399`, `Mel 20`, `Tr 24`, `St 2` … |

Generator: `Tools/build_catalogs.py` (sources pinned in `Tools/sources.lock.json`, rules in `Tools/README.md`). The same
`dsos.json` is in both engines' resources (byte for byte; the other three data files are unchanged).

## Model

### Prominence

```kotlin
enum class Prominence(val rawValue: Int) {        // Comparable: SHOWPIECE < PROMINENT < NOTABLE < OBSCURE
    SHOWPIECE(1), PROMINENT(2), NOTABLE(3), OBSCURE(4);
    val displayName: String                        // "Showpiece" "Prominent" "Notable" "Obscure"
    companion object {
        fun fromRawValue(raw: Int): Prominence?
        val lastListed: Prominence                 // NOTABLE
        const val showpieceStarMagnitude = 1.5; const val prominentStarMagnitude = 3.5; const val notableStarMagnitude = 5.0
        fun forStar(starMagnitude: Double): Prominence    // ≤1.5 → 1, ≤3.5 → 2, ≤5.0 → 3, else 4
    }
}
```
```swift
public enum Prominence: Int, Comparable, CaseIterable, Hashable, Sendable {
    case showpiece = 1, prominent, notable, obscure
    public static let lastListed = Prominence.notable
    public static let showpieceStarMagnitude = 1.5, prominentStarMagnitude = 3.5, notableStarMagnitude = 5.0
    public var displayName: String
    public init(starMagnitude: Double)
}
```

Rules (data side, `dso_prominence` in the generator; full table in `Tools/README.md`): 1 = Messier or Caldwell object, 26
hand-picked popular targets, anything brighter than magnitude 4; 2 = brighter than 8, named and (brighter than 10 or larger
than 30′), nebulae ≥ 60′, Sharpless regions ≥ 60′ of brightness class 2–3, named dark nebulae, Barnard objects ≥ 3°;
3 = everything the old selection rule picked (so nothing listed before is unlisted), other named objects, nebulae ≥ 10′,
Sharpless ≥ 30′, Barnard ≥ 1°, Lynds clouds ≥ 3° or opacity 6 and ≥ 20′, clusters ≥ 30′; 4 = the rest.
**Dark nebulae have no magnitude** (`magnitude == null`): they are rated by name, size and (Lynds) opacity only.
Stars follow `Prominence.forStar`; the Sun, Moon, planets, Milky Way core, poles and custom targets are showpieces.
A deep-sky record without `p` counts as `NOTABLE`.

Bundled data: 253 showpieces, 234 prominent, 506 notable, 1,446 obscure deep-sky objects (two notable NGC/IC records are
the stars Alnilam and Sadr and are merged into them as before).

### DeepSkyCatalogue and CatalogNumber

```kotlin
@Serializable enum class DeepSkyCatalogue(val rawValue: String, val displayName: String, val abbreviation: String) {
    MESSIER("messier","Messier","M"), CALDWELL("caldwell","Caldwell","C"), NGC("ngc","NGC","NGC"), IC("ic","IC","IC"),
    SHARPLESS("sharpless","Sharpless","Sh2"), BARNARD("barnard","Barnard","B"), LYNDS("lynds","Lynds","LDN"),
    COLLINDER("collinder","Collinder","Cr"), MELOTTE("melotte","Melotte","Mel"), TRUMPLER("trumpler","Trumpler","Tr"),
    STOCK("stock","Stock","St");
    companion object { fun fromRawValue(raw: String): DeepSkyCatalogue? }
}
data class CatalogNumber(val catalogue: DeepSkyCatalogue, val number: Int, val suffix: String = "") {
    val label: String        // "M 31", "NGC 7000", "C 14", "Sh2-155", "LDN 1622", "B 67a", "Cr 399"
}
```
```swift
public enum DeepSkyCatalogue: String, Codable, CaseIterable, Hashable, Sendable {
    case messier, caldwell, ngc, ic, sharpless, barnard, lynds, collinder, melotte, trumpler, stock
    public var displayName: String; public var abbreviation: String
}
public struct CatalogNumber: Hashable, Sendable {
    public let catalogue: DeepSkyCatalogue; public let number: Int; public let suffix: String
    public var label: String
}
```
(`CatalogNumber` has an internal initializer in both languages; the catalog makes them.)

### CelestialObject (new members)

| Kotlin | Swift | |
| --- | --- | --- |
| `val prominence: Prominence` | `let prominence: Prominence` | see above; default `NOTABLE` for objects built by hand |
| `val catalogNumbers: List<CatalogNumber>` | `let catalogNumbers: [CatalogNumber]` | Messier, NGC, IC, then the others, in the order of `designations`; empty for stars, solar-system bodies, ESO/PGC/UGC objects |
| `val catalogues: List<DeepSkyCatalogue>` | `var catalogues: [DeepSkyCatalogue]` | distinct catalogues, same order |
| `val isDeepSky: Boolean` | `var isDeepSky: Bool` | `id` starts with `"dso."` |

`designations` now also lists the cross-catalogue numbers: `dso.NGC7000` → `["NGC 7000", "Caldwell 20", "Sh2-117"]`,
`dso.C9` → `["Caldwell 9", "Sh2-155"]` (Cave Nebula), `dso.C41` → `["Caldwell 41", "Melotte 25", "Collinder 50"]`. Ids of the 693
old objects are unchanged. New ids: `dso.Sh2-129`, `dso.B72`, `dso.B67a`, `dso.LDN1622`, `dso.Cl69`, `dso.Mel20`, `dso.Tr24`, `dso.St2`.
Sharpless regions that OpenNGC already lists are not separate objects: Sh2-25 is `dso.M8`, Sh2-117 is `dso.NGC7000`, Sh2-155 is `dso.C9`
(47 of the 313, see `Tools/README.md`).

### Catalog (new members)

```kotlin
val deepSkyObjects: List<CelestialObject>                         // every deep-sky object, listed or not, in `objects` order
fun deepSkyObjects(maximum: Prominence): List<CelestialObject>    // prominence ≤ maximum; O(1), shared lists
fun objects(catalogue: DeepSkyCatalogue): List<CelestialObject>   // by catalogue number, listed or not; O(1), shared
```
```swift
public let deepSkyObjects: [CelestialObject]
public func deepSkyObjects(upTo maximum: Prominence) -> [CelestialObject]
public func objects(in catalogue: DeepSkyCatalogue) -> [CelestialObject]
```
`objects(in:)` is sorted by (number, letter, id); an object with two numbers in one catalogue (M 8 = NGC 6523 + NGC 6533) appears
once, at the first. Counts: Messier 110, Caldwell 109 (1…109), Sharpless 313 (1…313), Barnard 349, Lynds 398, NGC 1,225, IC 167,
Collinder 17, Melotte 9, Trumpler 2, Stock 1.

### Behaviour changes (existing API)

1. **`isListed` for deep-sky objects** is now `prominence <= NOTABLE` (993 of 2,439 are listed; before: all 693). Obscure objects
   are not in `listedObjects`, `objects(group)` or any browse list, but they are in `objects`, `deepSkyObjects`, `objects(catalogue)`
   and found by search. Everything the old data listed is still listed (checked in the tests).
2. **Search ranking** among equally good matches (same `MatchKind`): listed first, then **prominence**, then brightness (objects
   without a magnitude last), then name — before: listed, brightness, name. Reason: a showpiece without a magnitude (Horsehead,
   Flying Bat) must not rank below a faint galaxy. `CatalogSortOrder.NAME`/`MAGNITUDE` are unchanged.
3. `Catalog.search` KDoc/DocC lists the new designation forms; `MatchKind` and `CatalogSearchResult` are unchanged.
4. Loading: `dsos.json` unknown members are ignored, so an old engine reads the new file as before; a new engine reads old
   files (no `p` → `NOTABLE`, no `cat`).
5. Data validation (record rejected, counted in `loadReport`, reason in `issues`): `p` outside 1…4 (`p 5 out of range`), a `cat`
   entry that is not a compact designation (`cat entry ESO56-115 is not a catalogue designation`), more than 16 entries
   (`cat has too many entries`), too long / control characters (`cat is too long`, `cat contains control characters`); wrong JSON
   types (`"p":"2"`, `"cat":"C20"`) give `record has the wrong shape` as for every other member. A compact designation is 1–6 ASCII
   letters (the first upper case), a number of 1–6 digits, optionally one more letter (`C20`, `B67a`, `NGC2818A`, `LDN1622`), or
   `Sh2-` and 1–4 digits.

## Search

Matching is the existing key normalization (case, spaces, punctuation and leading zeros do not matter), so every row works:

| Type | Finds `dso.C9` / `dso.NGC7000` / … |
| --- | --- |
| Sharpless | `Sh2-155`, `Sh 2-155`, `sh2155`, `Sharpless 155`, `Sharpless 2-155` (alias from Sh2-100 on only: see below) |
| Barnard | `B33`, `B 33`, `Barnard 33`, `B67a` |
| Lynds | `LDN 1622`, `LDN1622`, `Lynds 1622`, `L 1622` |
| Caldwell | `C14`, `C 14`, `Caldwell 14` (also finds NGC 7000 for `C20`) |
| Clusters | `Cr 399`, `Cl 399`, `Collinder 399`, `Mel 20`, `Melotte 20`, `Mel 22` (= M 45), `Mel 25` (= Hyades), `Tr 24`, `St 2` |

`matchedText` of a result is the designation as displayed ("Sh2-155" for "Sharpless 155"). Because keys have no punctuation,
"Sharpless 2-45" and "Sharpless 245" are the same key; `Sharpless 2-N` is therefore only an alias for N ≥ 100, so
"Sharpless 245" finds Sh2-245 and not M 17 (use `Sh2-45`, `Sh 2-45` or `Sharpless 45` for M 17). No other key is ambiguous
(checked over the whole file).

## Notes for the UI agents

**Sky views.** Draw `deepSkyObjects(upTo:)`, never `listedObjects`, and grow the limit as the field of view narrows. Suggested
(vertical FOV): above 40° `SHOWPIECE` (253 objects over the whole sky), 15°–40° `PROMINENT` (487), 5°–15° `NOTABLE` (993),
below 5° `OBSCURE` (2,439). Both calls return shared lists in constant time; cache the visible subset per frame as you cache stars.
Dark nebulae have no magnitude, so do not size or fade markers by magnitude: use `angularSize` (circle of `majorArcminutes`; Barnard
objects may have none: draw a fixed small ring) and a muted style; label only `SHOWPIECE`/`PROMINENT` ones at wide fields. A
Sharpless region or Lynds cloud can be 10°+ across (Barnard's Loop 20°, Sh2-109 18°): clip and label at the visible part.

**Browse and filter.** Offer: (1) **Catalogue chips** from `DeepSkyCatalogue` in the order Messier, Caldwell, Sharpless, Barnard,
Lynds, NGC, IC, plus one "Clusters" chip for Collinder/Melotte/Trumpler/Stock, each opening `objects(in:)` (numbered lists: a
Caldwell/Messier "challenge" list can show checkmarks for observed objects; Sharpless and Barnard lists contain obscure objects: show
a prominence badge and sort by number, with a "popular only" toggle = `prominence <= PROMINENT`). (2) **Category** filter inside
the Nebulae group: `EMISSION_NEBULA`, `REFLECTION_NEBULA`, `DARK_NEBULA`, `PLANETARY_NEBULA`, `SUPERNOVA_REMNANT`,
`CLUSTER_WITH_NEBULA`, `NEBULA`: the Nebulae group goes from 164 to 432 listed objects (1,292 with the obscure ones, 748 of
them dark nebulae); galaxies from 140 to 149 listed (642 in all), clusters from 383 to 407 (a sub-filter pays off for nebulae only). (3) The group lists
(`objects(group)`) stay "the interesting ones"; a "Show obscure objects" switch can use `deepSkyObjects` filtered by group.

**Detail view.** Show `catalogNumbers.map { it.label }` as chips ("NGC 7000 · C 20 · Sh2-117"); `designations` has the long forms.
`notes` is only set for galaxies (Hubble type, surface brightness). Sharpless/Barnard/Lynds positions are the catalogues' own centers,
good to a fraction of the object's size (the Spaghetti Nebula is 0.4° from SIMBAD's center): do not use them for plate solving.

**Search.** `catalog.search(query)` is unchanged; results can now be obscure (unlisted) objects: show them, but unlisted ones come
after listed ones at equal match quality. Typing a catalogue word ("caldwell", "sharpless", "barnard 3") lists that catalogue's
objects by prominence.

**Cost.** Warm load +7 ms in Kotlin (median 43 → 50 ms, JVM) and +36 ms in Swift (optimized, 235 → 271 ms); search 0.10 → 0.11 ms
average (Swift optimized) over 11,452 objects; `objects(in:)`, `deepSkyObjects(upTo:)` O(1). Cold first load on the JVM +10–25%.

## Verification

- Counts per catalogue, catalogue lists ordered by number, catalogue numbers re-derived from the raw records of the data file,
  Caldwell 1…109 (26 checked against Moore's list), Sharpless identifications, names, positions against SIMBAD/Wikipedia
  (Medusa Nebula 0.02°, Snake Nebula 0.03°, Horsehead exact, Tulip Nebula 0.11°), dark-nebula and Lynds cut, search aliases (61
  spellings), prominence rules and counts, unchanged listing of the old objects, decoding and about 35 malformed variants of `p`/`cat`:
  `CatalogCataloguesTests` (30 tests, same names in `Tests/LodestarKitTests/CatalogCataloguesTests.swift`).
- Existing catalog tests updated for the new data (counts 693 → 2,441, listing rule, ranking rule, designations of M 8, C 41,
  H 5, NGC 281, NGC 2239).
- The generator checks its own constellation lookup (Delporte/Roman, precessed to B1875) against OpenNGC's constellation of every
  NGC/IC object it keeps (no disagreement), Moore's Caldwell list, the positions of five objects (SIMBAD for the two planetary
  nebulae), and rebuilds
  stars/constellations/milkyway byte for byte.

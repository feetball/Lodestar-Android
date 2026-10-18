# Comets and bright asteroids as catalog objects — API (Kotlin and Swift)

Kotlin package `net.kuecker.lodestar.kit.catalog` (+ `comets` for the estimate), Swift folder `Sources/LodestarKit/Catalog` (+ `Comets`).
Both implementations have the same types, members, constants, algorithms and evaluation order; every test exists in both
(`CatalogSmallBody*Tests`, `CatalogBundledAsteroidsTests`). This document is the catalog half of the comets feature: the engine
that computes the positions is `API-comets.md` (`SmallBody`, `SmallBodyEphemeris`, the parsers), the elements type is
`SmallBodyElements` (the name `OrbitalElements` belongs to the satellites area).

Naming map (as in the other API documents): Swift `struct` ↔ Kotlin immutable `data class`; Swift `enum` without payload ↔ `enum class`
with UPPER_SNAKE constants and `rawValue`; Swift `enum` with payloads ↔ `sealed interface`; `Date` ↔ `java.time.Instant`; Swift
`init(smallBody:)` ↔ Kotlin `CelestialObject.fromSmallBody(body)` (as `init(openNGCType:)` ↔ `fromOpenNGCType`); Swift argument labels
disappear in Kotlin (parameter names are the Swift internal names).

## Quick start

```kotlin
// Once: the bundled catalog (stars, deep sky, planets) and the 216 bundled bright asteroids; both work offline.
val base = Catalog.loadBundled()                                                 // ~100 ms, off the main thread
val asteroids = BundledAsteroids.load()                                          // 216 bodies, osculating at 2026-06-09, ~2 ms
var catalog = base.withSmallBodies(asteroids.bodies)                             // ~2 ms; shares everything of `base`

// After each download/parse of CometEls.txt (and of a newer asteroid file, if the app has one):
catalog = base.withSmallBodies(asteroids.bodies + parsedComets + downloadedAsteroids)   // later bodies replace earlier ones with the same id

// Use the objects like any other.
val hit = catalog.search("Tsuchinshan").first().obj                              // category COMET, id "comet.C/2023 A3"
val vesta = catalog.objectWithID("asteroid.4")!!
val frame = SkyFrame(Instant.now(), observer)
val place = frame.horizontal(vesta)                                              // the comets engine's pipeline, parallax included
val magnitude = frame.currentMagnitude(vesta)                                    // Double?  "≈ 6.4"; null without parameters
val events = RiseSetCalculator.riseTransitSet(vesta, observer, DateInterval(Instant.now(), 86_400.0))
```
```swift
let base = try Catalog.loadBundled()
let asteroids = try BundledAsteroids.load()
var catalog = base.withSmallBodies(asteroids.bodies)
catalog = base.withSmallBodies(asteroids.bodies + parsedComets + downloadedAsteroids)
let hit = catalog.search("Tsuchinshan").first?.object
let vesta = catalog.object(id: "asteroid.4")
let frame = SkyFrame(date: Date(), observer: observer)
let place = frame.horizontal(of: vesta)
let magnitude = frame.currentMagnitude(of: vesta)
let events = RiseSetCalculator.riseTransitSet(for: vesta, observer: observer, within: DateInterval(start: Date(), duration: 86_400))
```

`Catalog.loadBundled()` is **unchanged** (9 solar-system objects, 11 452 objects in all): a catalog has small bodies only after
`withSmallBodies`/`addingSmallBodies`, so existing code and tests see no new objects until the app opts in.

## Model

```kotlin
sealed interface ObjectPosition {                       // new case
    data class SmallBodyOrbit(val body: SmallBody) : ObjectPosition       // Swift: case smallBody(SmallBody)
}
enum class ObjectCategory { …, PLANET, COMET("comet"), ASTEROID("asteroid"), STAR, … }   // Swift: .comet, .asteroid (24 cases)
//   displayName "Comet" / "Asteroid"; systemImageName "sparkle" / "circle.fill" (both exist since SF Symbols 1–3); group SOLAR_SYSTEM
//   (`ObjectGroup.SOLAR_SYSTEM.categories` = SUN, MOON, PLANET, COMET, ASTEROID)

// CelestialObject — new members
val smallBody: SmallBody?                               // the body of an object whose position is SmallBodyOrbit, else null
val estimatedPeakMagnitude: Double?                     // the date-free rating below; null for other objects / no parameters
val isSolarSystem: Boolean                              // now also true for comets and asteroids
companion fun fromSmallBody(body: SmallBody): CelestialObject      // Swift: CelestialObject(smallBody: body)

// Prominence — new members
companion { const val showpieceSmallBodyMagnitude = 6.0; prominentSmallBodyMagnitude = 8.0; notableSmallBodyMagnitude = 10.0
            fun forSmallBody(peakMagnitude: Double?): Prominence }    // Swift: Prominence(smallBodyPeakMagnitude:)

object SmallBodyBrightness {                            // package comets; Swift: SmallBodyBrightness.peakMagnitudeEstimate(of:)
    const val minimumSunDistanceAU = 0.4226             // sin 25°
    const val minimumAsteroidEarthDistanceAU = 0.5
    fun peakMagnitudeEstimate(body: SmallBody): Double?
}

// Catalog — new members
val smallBodies: List<CelestialObject>                  // the small bodies of this catalog, brightest estimated peak first
fun withSmallBodies(bodies: List<SmallBody>): Catalog   // exactly these bodies; everything else shared with the receiver
fun addingSmallBodies(bodies: List<SmallBody>): Catalog // these in addition to the receiver's; same id replaces
companion const val maximumSmallBodyCount = 100_000

// Bundled asteroids (package catalog; Swift: BundledAsteroids, a struct)
data class BundledAsteroids(val bodies: List<SmallBody>, val rejectedLineCount: Int, val oldestEpoch: Instant, val newestEpoch: Instant) {
    fun ageDays(date: Instant): Double                  // Swift: ageDays(at:)
    companion { const val resourceFileName = "asteroids.txt"; maximumFileBytes = 1 MiB; peakMagnitudeLimit = 11.0; maximumAbsoluteMagnitude = 10.5
                fun load(): BundledAsteroids }          // throws CatalogError.BrightAsteroidsUnavailable; Swift: static func load() throws
}
// CatalogError — new case: BrightAsteroidsUnavailable(reason) (Swift: .brightAsteroidsUnavailable(reason:)); not tied to a CatalogFile
```
```swift
public enum ObjectPosition { …; case smallBody(SmallBody) }
public enum ObjectCategory: String { …; case comet, asteroid }                       // 24 cases
public extension CelestialObject { init(smallBody: SmallBody); var smallBody: SmallBody?; var estimatedPeakMagnitude: Double? }
public enum SmallBodyBrightness { static let minimumSunDistanceAU, minimumAsteroidEarthDistanceAU: Double
                                  static func peakMagnitudeEstimate(of body: SmallBody) -> Double? }
extension Prominence { init(smallBodyPeakMagnitude: Double?); static let showpieceSmallBodyMagnitude, prominentSmallBodyMagnitude, notableSmallBodyMagnitude }
// Catalog (final class): let smallBodies: [CelestialObject]; func withSmallBodies(_ bodies: [SmallBody]) -> Catalog
//                        func addingSmallBodies(_ bodies: [SmallBody]) -> Catalog; static let maximumSmallBodyCount = 100_000
public struct BundledAsteroids: Sendable {            // bodies, rejectedLineCount, oldestEpoch, newestEpoch: Date
    func ageDays(at date: Date) -> Double;  static func load() throws -> BundledAsteroids
    static let resourceFileName, maximumFileBytes, peakMagnitudeLimit, maximumAbsoluteMagnitude }
// CatalogError: case brightAsteroidsUnavailable(reason: String)
```

### What an object looks like

| | Kotlin / Swift value |
| --- | --- |
| `id` | the body's: `comet.C/2023 A3`, `comet.12P`, `asteroid.4`, `asteroid.2024 YR4` (stable across element updates; store this for favourites) |
| `name` | `SmallBody.title`: `C/2023 A3 (Tsuchinshan-ATLAS)`, `12P/Pons-Brooks`, `(4) Vesta`, `2024 YR4` |
| `designations` | `["C/2023 A3"]`, `["12P"]`, `["(4)"]` (a numbered asteroid: the number in parentheses), `["2024 YR4"]` |
| `alternateNames` | `["Tsuchinshan-ATLAS"]`, `["Pons-Brooks"]`, `["Vesta"]`; empty for an unnamed body |
| `category` / `group` | `COMET` or `ASTEROID` / `SOLAR_SYSTEM` |
| `constellation`, `constellationName`, `magnitude`, `angularSize`, `colorIndex`, `spectralType`, `distanceLightYears`, `notes` | `null` (it moves; use `SkyFrame` for place and `currentMagnitude`) |
| `subtitle` | `Comet` / `Asteroid` (no magnitude: add `mag 6.4` from `currentMagnitude` yourself, as an estimate) |
| `position` | `SmallBodyOrbit(body)` / `.smallBody(body)` |
| `prominence`, `isListed` | from `estimatedPeakMagnitude` (below): ≤ 6 showpiece, ≤ 8 prominent, ≤ 10 notable, else obscure; listed ⇔ notable or better |
| `isSolarSystem`, `j2000`, `isDeepSky` | `true`, `null`, `false` |

**Peak magnitude estimate** (`SmallBodyBrightness.peakMagnitudeEstimate`, also `CelestialObject.estimatedPeakMagnitude`): one number per
body without a date, for ranking, rating and listing. Asteroid: `H + 5·log10(r·d)` at the opposition nearest the perihelion
(`r = max(q, 0.4226)`, `d = max(r − 1, 0.5)`, zero phase) — the best case; Vesta 5.2, Ceres 6.3, Eros 9.2. Comet:
`M1 + 5·log10(√(1 + r²)) + K1·log10(r)` with `r = max(q, 0.4226)` — at perihelion seen at a right angle; Hale-Bopp −1.7, C/2023 A3 3.7,
12P 3.9, Halley 3.9, Encke 10.8. Sun distances below sin 25° are not used (closer to the Sun nothing can stand 25° from it), which keeps the
SOHO sungrazers (`H` is meaningless for them) from outshining everything. It is a rating, **not** a prediction: show
`frame.currentMagnitude(obj)` for "now". About 44 of the 963 comets of `CometEls.txt` are listed (estimate ≤ 10), 16 are showpieces.

## Catalog behaviour

* **Where they are.** `objects` = bundled objects (unchanged, same order) + the small bodies, brightest estimate first (then name, id); `listedObjects` likewise.
  `objects(group: SOLAR_SYSTEM)` = the 9 bodies + the *listed* small bodies, sorted by name; the other groups are the bundled lists. `smallBodies` is the
  small-body part of `objects`. The faint ones (obscure) are in `objects`, `objectWithID`, `search` and `sorted`, but not in `listedObjects`/`objects(group)`.
* **Replace and add.** `withSmallBodies(list)` makes the small bodies exactly `list`: bodies with the same id count once, the **last one wins** (so
  `bundled + downloaded` lets fresh elements replace the bundled ones), and at most `maximumSmallBodyCount` distinct bodies are taken (the first
  ones of the list). `addingSmallBodies(list)` keeps the receiver's small bodies and adds/replaces by id. The order of the input never changes the result.
  `withSmallBodies([])` on a catalog without small bodies returns the same instance. The receiver is never changed; thread safe.
* **Search.** `search` finds small bodies by title, designation and name with the usual rules (case, spacing, punctuation, leading zeros ignored):
  `C/2023 A3`, `c2023a3`, `2023 A3` (later word), `Tsuchinshan`, `atlas`, `12P`, `Pons-Brooks`, `Vesta`, `4 Vesta`, `(4) Vesta`, `4`, `2024 YR4`, `yr4`. Equal
  match kinds are ordered as for every object (listed, prominence, brightness estimate, name) — so the exact "Atlas" star of the Pleiades still comes before the
  comet whose name has "ATLAS" as a later word. Results of the bundled objects and of the small bodies are merged exactly as one index would order them; a
  `FUZZY` suggestion appears only if neither source has a better match. `matchedText` is the matching entry: the designation, the name or the title.
* **Sky.** `SkyFrame.horizontal(obj, refraction)`, `apparentEquatorial(obj)`, `currentMagnitude(obj)` and `RiseSetCalculator.riseTransitSet(obj, …)`
  call the comets engine (`horizontal(body)`, `topocentricEquatorial(body)`, `state(body)?.apparentMagnitude`, `SmallBodyEphemeris.riseTransitSet`) and
  return exactly its values. `currentAngularSize(obj)` is `null`. A body whose orbit cannot be evaluated at that instant (an unbound orbit more than
  137 years from perihelion) is at azimuth 0°, altitude −90° (`horizontal`), RA 0°/Dec 0° (`apparentEquatorial`) and has no magnitude.
* **Not changed:** `Catalog.loadBundled()`, `loadReport`, the render stars, constellations, Milky Way, deep-sky lists, `CatalogFile`.

## Bundled bright asteroids

`asteroids.txt` in both engines (Kotlin resource folder of the catalog package, Swift `Resources`): an extract of the Minor Planet Center's
`MPCORB.DAT` in the MPC one-line format, the MPC header kept above the data (their licence), 216 lines, 46.7 kB, plain ASCII.

* **Selection** (`Tools/build_bright_asteroids.py`): `H ≤ 10.5` **and** best-case peak magnitude `H + 5·log10(r·d) ≤ 11.0` with `r = max(q, 0.4226)`,
  `d = max(r − 1, 0.5)` — exactly `SmallBodyBrightness`'s asteroid formula, so every asteroid that can reach magnitude 11 at some opposition is in, no
  other. That is the main belt from (4) Vesta (5.2) down to (471) Papagena and fainter, plus (433) Eros and (1036) Ganymed; no trans-Neptunian objects
  (Eris, Makemake and Pluto have `H < 0` but are never brighter than magnitude 14). **The task's cut "H ≤ 8.5 (≈ 200)" does not exist in the data:** 5 925 asteroids
  have `H ≤ 8.5` (1.2 MB, mostly trans-Neptunian objects and faint main-belt asteroids); about 200 asteroids is the *peak-magnitude* cut above.
* **Source and pin.** `https://www.minorplanetcenter.net/iau/MPCORB/MPCORB.DAT.gz` (94 MB, rewritten daily, no archive). The snapshot of 2026-10-18 12:59 GMT
  is pinned in `Tools/asteroids.lock.json` (sha256 `a7c75d31ef59…2376a3`, 93 812 401 bytes) together with the sha256 of the output;
  `python3 -I Tools/build_bright_asteroids.py --source MPCORB.DAT.gz` rebuilds the files byte for byte from that snapshot, `--check` verifies the shipped files
  against the lock offline, `--refresh-lock --cache /dev/shm/asteroids` pins today's file and regenerates (then update the counts in the tests: 216 asteroids,
  the epoch, the reference positions with `Tools/make_asteroid_reference.py`). The 94 MB download goes to a cache directory (use a RAM disk); nothing large is committed.
* **Freshness.** The elements are osculating at **2026-06-09** (MPC epoch `K2669`, JD 2461200.5 TT) for all 216; `BundledAsteroids.oldestEpoch/newestEpoch`
  report it and `ageDays(date)` the extrapolation span. Two-body error against JPL Horizons (geocentric astrometric J2000, seven of the asteroids):

  | days after the epoch | 0 | 30 | 120 | 131 (2026-10-18) | 365 | 731 |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | worst of Ceres, Pallas, Juno, Vesta, Iris, Hebe, Eros | 0.1″ | 0.3″ | 4.0″ | 5.3″ | 37″ | 13′ (Hebe; median 3′) |

  Good for aiming a lens for a year; show "elements of <date>" when `ageDays > 365` and replace the bundled bodies by downloaded ones as soon as there are any
  (append them after the bundled list). Elements of the bundled file also agree with Horizons' own osculating elements for the day (Vesta, Ceres: q, e within 2·10⁻⁶,
  angles within 2·10⁻⁴°).
* **Licence.** MPC orbit data may be included in software if the MPC header stays with the data and the Minor Planet Center is credited; the header is the first 47
  lines of `asteroids.txt` (a test checks that it is there). For ATTRIBUTION.md: "Orbital elements: IAU Minor Planet Center (minorplanetcenter.net); JPL Small-Body Database
  (ssd.jpl.nasa.gov)". The reference positions in the tests are JPL Horizons output (NASA/JPL, public domain, credit requested).

## Cost (desktop; Kotlin: warm JVM; Swift: optimized build, in brackets the unoptimized debug build)

| call | Kotlin | Swift |
| --- | ---: | ---: |
| `BundledAsteroids.load()` (parse 216 lines) | 1.4 ms | 3.8 ms (debug: ~10 ms) |
| `withSmallBodies` with the 216 bundled asteroids | 1.4 ms | 3.8 ms (10 ms) |
| `withSmallBodies` with 1 000 comets | 4.6 ms | 8.7 ms (29 ms) |
| `withSmallBodies` with 43 000 bodies (all of `NEA.txt`) | 195 ms | 510 ms (1.6 s) |
| `search` with ~1 200 small bodies added: "vesta" / "a" (limit 50) | 0.2 / 0.5 ms | 0.08 / 0.4 ms |

A search of the bundled catalog alone takes ≈ 0.1 ms on average; the small bodies add one more bounded scan of their own small index and a merge of at most
2 × `limit` results. A phone is a few times slower than these desktop figures. Constant time: `objectWithID`/`object(id:)`, `objects(group:)`, `smallBodies`.
Per object (the comets engine): `horizontal`/`apparentEquatorial`/`currentMagnitude` ≈ 5 µs (per frame is fine), `riseTransitSet(obj, …)` 3–4 ms (once per night, not per frame).

Call `withSmallBodies` off the main thread when the list is large (NEA-sized), after each file refresh, never per frame.

## Notes for the apps

**Wiring.** On launch: `base = Catalog.loadBundled()`, `asteroids = BundledAsteroids.load()`, `catalog = base.withSmallBodies(asteroids.bodies + cachedBodies)`.
After each comet/asteroid refresh (see `API-comets.md`: at most daily, at least monthly, keep the last good file): rebuild from `base` (keep `base`, it is the expensive part)
with the freshly parsed bodies *after* the bundled ones. Persist favourites by `SmallBody.id` / `CelestialObject.id`; on launch look it up with `objectWithID`; if a comet has
dropped out of the file keep the favourite and show "no current orbit". "Bright comets now" and "This month" lists come from `SmallBodySelection` (1–2 ms), not from the catalog; the
catalog is for search, the target picker, the detail screen and the aim screen. A user-fetched single body (`SmallBodyJSONParser`) is added with `addingSmallBodies([body])`.

**`when`/`switch` over the new cases — what must be extended**

| Where (current apps) | Kind | Do |
| --- | --- | --- |
| Android `shared/ObjectIcons.kt` `ObjectCategory.icon` | **compile error** (exhaustive `when`) | add `COMET` and `ASTEROID` icons (iOS uses `systemImageName`, which is done: `sparkle`, `circle.fill`) |
| Android `features/objects/ObjectsSkyline.kt` `HorizonVisibility.riseTransitSet(obj, …)` | **compile error** (exhaustive `when (position)`) | add `is ObjectPosition.SmallBodyOrbit -> riseTransitSet({ SkyFrame(it, observer).horizontal(obj, refraction = false) }, interval)` (the generic position-function overload; the same closure feeds `NightPlanner.visibility(position, summary)`, `altitudeCurve(observer, interval) { it.horizontal(obj, false) }`, `HorizonVisibility.isVisible(position, at)` and `visibleWindows`) |
| iOS: nothing breaks the build (`switch` over `category` has `default`, over `group` is unchanged); Android `SkyChartScene.bodyMarker`, `SkyOverlayRendererSky`, `AimSkyContent.shape` have `else` | silent | comets/asteroids drawn through the planet path would look like planets: give them a marker (a dot with a short tail line; `SmallBodyEphemeris.tailAngleFromVertical`) before adding them to a `SkySnapshot` |
| Android `ObjectDetailCalculator.current` / iOS `ObjectDetailCalculator.current(of:)`: `if position is SolarSystem` (distance, illuminated fraction) | silent | add a branch for small bodies: `frame.state(body)` gives `distanceAU`, `heliocentricDistanceAU`, `elongation`, `phaseAngle`, `apparentMagnitude`; no distance/illumination for a body without a state (`null`) |
| Android `ObjectsPositionModel` / iOS `ObjectsPositionModel`: `if (obj.isSolarSystem) frame.currentMagnitude(obj)` | works | now true for comets and asteroids; show the magnitude as an estimate ("≈ 6.4") and "no brightness data" for `null` |
| `TonightCalculator`, `SkySnapshot` deep-sky filters (`as? Fixed` / `case .fixed`, `isDeepSky`, group tests) | skip small bodies | intended: small bodies are not deep-sky targets; add them to Tonight with `NightPlanner.visibility({ frame -> frame.horizontal(obj, false) }, summary)` (one object, ≈ 1 ms) |
| Anything keyed on `ObjectGroup.SOLAR_SYSTEM` lists (Objects tab "Solar System" section) | grows | the section now holds up to ~100–150 listed small bodies after the 9 bodies; split by `category` (planets / comets / asteroids) if a single section is too long |
| Mosaic: `MosaicRegion.fitting(obj)` | typed error | throws `MosaicError.ObjectNotFixed(id)` for a comet/asteroid, as for a planet (they move; no mosaic of them); the Mosaic screen should not offer them |
| Aim target picker, favourites, search UI | works | objects are ordinary `CelestialObject`s; the identifier is the key |

**UI text.** Row: `obj.name`, subtitle `obj.category.displayName` + current magnitude as an estimate + elongation (`frame.state(body)`); warn "in the twilight glow"
below 25°, "position approximate" when the elements are older than 60 days (comets: `body.orbit.epochJulianDayTT`) or, for the bundled asteroids, `ageDays > 365`.
A comet's `name` is long ("C/2023 A3 (Tsuchinshan-ATLAS)"): use `obj.alternateNames.first ?? obj.name` for a compact label. `estimatedPeakMagnitude` is for ordering and
rating, never for "how bright is it now".

## Tests

Kotlin and Swift, mirrored by name and values: `CatalogSmallBodyModelTests` (11), `CatalogSmallBodySkyTests` (7), `CatalogSmallBodySearchTests` (17),
`CatalogBundledAsteroidsTests` (9) and one timing test in `CatalogPerformanceTests` (45 new tests per language); the category/group tests of `CatalogModelTests` (24 categories, the verified SF Symbol
list gains `circle.fill`) and the exhaustive `when`s of `AppContractTests`/`CatalogLoadingTests` were extended. Verification sources: the comets engine itself (the sky
position of the object is *equal* to `SmallBodyEphemeris`'s), JPL Horizons osculating elements and astrometric positions of seven bundled asteroids at six dates
(`CatalogAsteroidReferenceData`, generated by `Tools/make_asteroid_reference.py`), the MPC lines of 2026-10-18.

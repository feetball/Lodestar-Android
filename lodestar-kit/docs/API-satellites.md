# Satellites — ISS passes, bright satellites, streak warnings (Swift + Kotlin) — API reference

When the ISS (or another bright satellite) crosses the sky, where to aim and when; and a warning when satellites
(Starlink and others) will probably streak through a long exposure. The engine **parses and computes only**: the apps
download CelesTrak GP data and hand the bytes to `ElementSetParser`; there is no networking in the kit.

| Piece | Swift (`LodestarKit`, folder `Satellites/`) | Kotlin (`net.kuecker.lodestar.kit.satellites`) |
|---|---|---|
| Typed parse errors, batch result | `ElementSetError.swift` | `ElementSetError.kt` |
| Validated element set (`OrbitalElements`), calendar helpers | `OrbitalElements.swift` | `OrbitalElements.kt` |
| TLE + CelesTrak OMM JSON parsing | `ElementSetParser.swift` | `ElementSetParser.kt` |
| CelesTrak groups, freshness limits | `ElementSetGroup.swift` | `ElementSetGroup.kt` |
| SGP4/SDP4 propagator (port of the reference) | `Sgp4Propagator.swift`, `Sgp4DeepSpace.swift` | `Sgp4Propagator.kt`, `Sgp4DeepSpace.kt` |
| TEME → Earth-fixed → horizon, Sun in TEME, Earth shadow | `SatelliteFrames.swift` | `SatelliteFrames.kt` |
| Magnitude model | `SatelliteBrightness.swift` | `SatelliteBrightness.kt` |
| `Satellite`, `SatelliteLook`, `SatelliteSkyContext` (Aim support) | `Satellite.swift` | `Satellite.kt` |
| Pass prediction | `SatellitePassFinder.swift` | `SatellitePassFinder.kt` |
| Camera frame, streak risk | `StreakRisk.swift` | `StreakRisk.kt` |

Imports: Swift `import LodestarKit`. Kotlin `import net.kuecker.lodestar.kit.satellites.*`.

Depends on Foundation (`Vector3`, `Matrix3`, `HorizontalCoordinate`, `GeographicLocation`, `Observer`, `JulianDate`,
`Precession`, `Nutation`, `Obliquity`, `Refraction`, `DateInterval`), Ephemeris (`Ephemeris.geocentricJ2000` for the Sun) and
Pointing (`PointingSolution`, only for `CameraFrame` created from a measured pointing). Nothing outside `Satellites/` was edited.
The Kotlin parser uses `kotlinx.serialization.json` (already a dependency); the Swift parser uses `JSONDecoder`.

## Conventions

- Angles in degrees; azimuth from true north through east [0, 360); altitude [−90, 90]; ENU vectors x east, y north, z up.
  Distances in kilometres (satellite work), speeds km/s, times `Date` / `Instant`, intervals `DateInterval`.
- **Altitudes are geometric** (no refraction); `SatelliteLook.apparentAltitude` / `apparentHorizontal` add refraction for
  pointing a camera. Pass thresholds (`minimumAltitude`) are geometric.
- **Frames.** SGP4 gives TEME. TEME → Earth-fixed rotates by Greenwich *mean* sidereal time (the reference's `gstime`), with UT1
  taken as UTC (≤ 0.9 s ≈ 0.004°) and **polar motion ignored** (< 0.3″, ~10 m). Observer: WGS-84 geodetic → Earth-fixed km, ENU from
  the ellipsoid normal. No light time or aberration for satellites (< 0.01°).
- **Sun** for shadow and twilight: `Ephemeris.geocentricJ2000(.sun)` (apparent, VSOP87) rotated to TEME with precession, nutation and
  the equation of the equinoxes; over a window it is interpolated (cubic Lagrange) from hourly nodes.
- **Earth shadow** (`EarthShadow`): conical model, Earth a sphere of the WGS-84 equatorial radius, Sun disc 695 700 km; the
  illuminated fraction is 1 in sunlight, 0 in the umbra, in between in the 3–8 s penumbra of a low orbit. `isSunlit` means fraction ≥ 0.5,
  which is the point-source-Sun criterion that Skyfield's `is_sunlit` uses. The atmosphere (which dims sunlight ~20 km before the geometric
  shadow) is not modelled.
- **Darkness** = Sun's geometric centre below −6° by default (`darknessSunAltitude`).
- Errors are typed (`ElementSetError`, `Sgp4Error`). Batch parses never throw for one bad record. No force unwraps on data paths.
- Everything is pure computation. `OrbitalElements`, `Sgp4Propagator`, `Satellite`, `SatellitePassFinder`, `CameraFrame`,
  `SatelliteSkyContext` are immutable and thread safe (one finder or one satellite list may be used from many threads).
- Naming across languages as in the kit rules: Swift `struct` ↔ Kotlin `data class`, throwing `init` ↔ `companion create` (throws), failable
  `init?` ↔ `create` returning `null`, enums without payload ↔ `enum class` (`rawValue`), `Date` ↔ `Instant`, Swift `Result`-like enum
  with payload ↔ `sealed interface`. Kotlin parameter names = Swift internal names; Swift external labels are shown in the Swift blocks.

---------------------------------------------------------------------------------------------------------

## Quick usage

```swift
// App service: after a CelesTrak download (FORMAT=json) — parse once, build the satellites once, keep them.
let batch = try ElementSetParser.parseJson(downloadedData)            // 11 000 Starlink records: ~0.2–0.5 s
let stations = batch.elements.map { Satellite(elements: $0) }          // 3 µs each; magnitude -1.8 for the ISS built in
let iss = stations.first { $0.catalogNumber == 25544 }!

// Passes screen: next three days, off the main thread.
let finder = SatellitePassFinder(observer: observer, interval: DateInterval(start: .now, duration: 3 * 86_400))
let passes = finder.passes(of: iss)                                    // ~3 ms
for pass in passes where pass.isVisible {                              // sunlit + dark sky
    print(pass.rise.date, pass.culmination.altitude, pass.brightest?.magnitude as Any, pass.visibleSpan as Any)
}

// Aim screen (every sensor tick): where it is now, where to wait, where it peaks.
let look = iss.look(at: .now, observer: observer)                      // 40 µs; or share a SatelliteSkyContext (2 µs per satellite)
let target = look?.apparentHorizontal                                  // feed the guidance arrow
let peak = pass.culmination.horizontal                                 // "it will peak at az/alt …"
let sky = iss.track(from: pass.rise.date, to: pass.set.date, step: 5, observer: observer)   // polyline of the path

// Streak warning for the current framing: once per second at most, on a background thread.
let frame = CameraFrame(solution: pointingSolution, widthDegrees: 54.4, heightDegrees: 37.8)!     // or CameraFrame(boresight:…, rollDegrees:)
let risk = StreakRisk.assess(starlink, observer: observer, frame: frame,
                             exposure: DateInterval(start: .now, duration: 30))                    // 10 000 satellites: 30–90 ms
// risk.sunlitAboveSummaryAltitudeCount  "47 sunlit satellites above 20° right now"
// risk.crossingsPerMinute               "≈ 0.8 satellite crossings per minute in this frame"
// risk.probabilityOfStreak(exposureSeconds: 30), risk.crossings.first?.entry.date
```

```kotlin
val batch = ElementSetParser.parseJson(downloadedBytes)
val stations = batch.elements.map { Satellite(it) }
val iss = stations.first { it.catalogNumber == 25544 }

val finder = SatellitePassFinder(observer, DateInterval(Instant.now(), 3 * 86_400.0))
val passes = finder.passes(iss)
for (pass in passes.filter { it.isVisible }) println("${pass.rise.date} ${pass.culmination.altitude} ${pass.brightest?.magnitude}")

val look = iss.look(Instant.now(), observer)                 // SatelliteLook? (null if SGP4 fails)
val target = look?.apparentHorizontal
val sky = iss.track(pass.rise.date, pass.set.date, 5.0, observer)

val frame = CameraFrame.create(pointingSolution, 54.4, 37.8)!!
val risk = StreakRisk.assess(starlink, observer, frame, DateInterval(Instant.now(), 30.0))
```

---------------------------------------------------------------------------------------------------------

## ElementSetError.swift / ElementSetError.kt

```swift
public enum ElementSetError: Error, Hashable, Sendable, LocalizedError, CustomStringConvertible {
    case invalidLineLength(line: Int, length: Int)          // a TLE line is not 69 characters (trailing white space ignored)
    case unexpectedLineNumber(line: Int)                    // line does not start with "1 " / "2 "
    case checksumMismatch(line: Int, expected: Int, found: Int)
    case invalidField(field: String, text: String)          // not a number / date / identifier of the expected shape
    case valueOutOfRange(field: String, value: Double)
    case catalogNumberMismatch(line1: Int, line2: Int)
    case missingField(field: String)                        // required OMM member absent (or null)
    case malformedJSON(reason: String)                      // not JSON, or top level neither array nor object
    case invalidRecord(reason: String)                      // array element is not an object
    case inputTooLarge(limit: Int)
}
public struct SkippedElementSet: Hashable, Sendable { public let index: Int; public let name: String?; public let error: ElementSetError }
public struct ElementSetBatch: Hashable, Sendable {
    public let elements: [OrbitalElements]                  // valid sets, input order
    public let skipped: [SkippedElementSet]                 // first 50 skipped records
    public let skippedCount: Int
    public func latestPerObject() -> ElementSetBatch        // newest epoch per catalog number, first-appearance order
}
```

```kotlin
sealed class ElementSetError(val description: String) : Exception(description) {
    data class InvalidLineLength(val line: Int, val length: Int) ; data class UnexpectedLineNumber(val line: Int)
    data class ChecksumMismatch(val line: Int, val expected: Int, val found: Int) ; data class InvalidField(val field: String, val text: String)
    data class ValueOutOfRange(val field: String, val value: Double) ; data class CatalogNumberMismatch(val line1: Int, val line2: Int)
    data class MissingField(val field: String) ; data class MalformedJson(val reason: String)
    data class InvalidRecord(val reason: String) ; data class InputTooLarge(val limit: Int)
}
data class SkippedElementSet(val index: Int, val name: String?, val error: ElementSetError)
data class ElementSetBatch(val elements: List<OrbitalElements>, val skipped: List<SkippedElementSet>, val skippedCount: Int) { fun latestPerObject(): ElementSetBatch }
```

Field names in errors are the OMM keywords (`MEAN_MOTION`, `INCLINATION`, `EPOCH`, `BSTAR`, `NORAD_CAT_ID`, …); `separator` and `checksum` for TLE layout problems.

## OrbitalElements.swift / OrbitalElements.kt

A validated GP element set (angles °, mean motion rev/day, B* 1/Earth radii). Accepted ranges: catalog number 0…999 999 999, classification
`A…Z`, epoch years 1957…2099, mean motion (0, 100), eccentricity [0, 1), inclination [0, 180], node / argument of pericenter / mean anomaly
[0, 360], |B*| ≤ 10, |ṅ|, |n̈| ≤ 100 (SGP4 does not use them), ephemeris type 0…9, element set number 0…99 999, revolution number 0…9 999 999.
Names (≤ 80 chars) and designators (≤ 20) are cleaned of control characters.

```swift
public struct OrbitalElements: Hashable, Sendable {
    public let name: String?, objectId: String?          // objectId "1998-067A"
    public let catalogNumber: Int, classification: Character, epoch: Date
    public let meanMotion, eccentricity, inclination, rightAscensionOfAscendingNode, argumentOfPericenter, meanAnomaly: Double
    public let ephemerisType, elementSetNumber, revolutionNumberAtEpoch: Int
    public let bstar, meanMotionDot, meanMotionDdot: Double
    public var periodMinutes: Double
    public func ageDays(_ date: Date) -> Double           // signed, days since the epoch
    public init(name: String? = nil, objectId: String? = nil, catalogNumber: Int, classification: Character = "U", epoch: Date,
                meanMotion: Double, eccentricity: Double, inclination: Double, rightAscensionOfAscendingNode: Double,
                argumentOfPericenter: Double, meanAnomaly: Double, ephemerisType: Int = 0, elementSetNumber: Int = 0,
                revolutionNumberAtEpoch: Int = 0, bstar: Double = 0, meanMotionDot: Double = 0, meanMotionDdot: Double = 0) throws
}
```
```kotlin
data class OrbitalElements /* private constructor */ (val name: String?, val objectId: String?, val catalogNumber: Int, val classification: Char,
    val epoch: Instant, val meanMotion: Double, … , val meanMotionDdot: Double) {
    val periodMinutes: Double ; fun ageDays(date: Instant): Double
    companion object { fun create(name: String? = null, objectId: String? = null, catalogNumber: Int, classification: Char = 'U', epoch: Instant, …): OrbitalElements }  // throws ElementSetError
}
```
The SGP4 epoch (days since 1949-12-31) is derived exactly as the reference code does (internal `sgp4EpochDays`), so verification agrees to micrometres.

## ElementSetParser.swift / ElementSetParser.kt

```swift
public enum ElementSetParser {
    public static let maximumInputCharacters = 64_000_000, maximumRecordCount = 200_000, maximumReportedSkips = 50
    public static func parseTwoLine(line1: String, line2: String, name: String? = nil) throws -> OrbitalElements
    public static func parseTwoLineText(_ text: String) throws -> ElementSetBatch      // 2LE or 3LE ("0 " name prefix removed); throws only .inputTooLarge
    public static func checksum(_ line: String) -> Int                                  // mod-10 sum of the first 68 characters
    public static func parseJson(_ json: String) throws -> ElementSetBatch              // OMM JSON array or single object
    public static func parseJson(_ bytes: Data) throws -> ElementSetBatch               // throws .malformedJSON / .inputTooLarge
}
```
```kotlin
object ElementSetParser { const val maximumInputCharacters = 64_000_000; const val maximumRecordCount = 200_000; const val maximumReportedSkips = 50
    fun parseTwoLine(line1: String, line2: String, name: String? = null): OrbitalElements
    fun parseTwoLineText(text: String): ElementSetBatch ; fun checksum(line: String): Int
    fun parseJson(json: String): ElementSetBatch ; fun parseJson(bytes: ByteArray): ElementSetBatch }
```
- **TLE**: 69 characters per line, line numbers, mod-10 checksum, separator columns, per-field validation; catalog numbers may be `99999` or **Alpha-5**
  (`A0000` = 100 000 … `Z9999` = 339 999, no `I`/`O`); blank designator / element number / revolution number / ephemeris type accepted;
  both lines must name the same object; epoch day must exist in that year. A pair of lines is recognised by `1 ` + `2 `; an unpaired
  line 1 or 2 is skipped and reported.
- **OMM JSON** (CelesTrak `FORMAT=json`, also Space-Track): required `EPOCH, MEAN_MOTION, ECCENTRICITY, INCLINATION, RA_OF_ASC_NODE,
  ARG_OF_PERICENTER, MEAN_ANOMALY, NORAD_CAT_ID, BSTAR`; optional `OBJECT_NAME, OBJECT_ID, CLASSIFICATION_TYPE (U), EPHEMERIS_TYPE,
  ELEMENT_SET_NO, REV_AT_EPOCH, MEAN_MOTION_DOT, MEAN_MOTION_DDOT (0)`; numbers may be JSON numbers or JSON-grammar strings; `EPOCH` is
  `YYYY-MM-DDTHH:MM:SS[.f…]` with optional `Z`/`+00:00`. **Use JSON**: a TLE cannot carry catalog numbers above 339 999 and CelesTrak's
  TLE feeds already omit objects numbered above 99 999 (4 of the 21 stations and 473 of the 11 124 Starlink satellites on 2026-10-18).
- Cost ≈ 20–40 µs per OMM record (11 124 Starlink records, 4.7 MB: 0.2–0.5 s on a desktop JVM).

## ElementSetGroup.swift / ElementSetGroup.kt

```swift
public enum ElementSetGroup: String, CaseIterable, Hashable, Sendable {      // Kotlin: STATIONS, VISUAL, STARLINK (rawValue)
    case stations, visual, starlink
    public static let minimumDownloadIntervalHours = 2.0
    public var maximumAgeDays: Double          // stations 3, visual 7, starlink 3
    public var refreshIntervalHours: Double    // 12, 24, 24
    public var typicalRecordCount: Int         // 25, 160, 11 000
    public var query: String                   // "GROUP=stations&FORMAT=json" → https://celestrak.org/NORAD/elements/gp.php?<query>
    public func isFresh(_ elements: OrbitalElements, at date: Date) -> Bool
}
```
Kotlin: `ElementSetGroup.fromRawValue(raw)`, `isFresh(elements, date)`. It is data only; the engine never downloads.

## Sgp4Propagator.swift, Sgp4DeepSpace.swift / Sgp4Propagator.kt, Sgp4DeepSpace.kt

A port of the public-domain reference implementation (Vallado, Crawford, Hujsak & Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753;
the C++ `SGP4.cpp` of 2020-07-13 that the Python `sgp4` package also follows): near-earth, and deep-space SDP4 (lunar-solar terms, 12 h and 24 h
resonance, Lyddane branch, the reference's `sgp4fix`es), WGS-72 constants, operation mode `'i'`. The reference keeps the resonance integrator's
progress inside the element record and resumes from it; this port restarts the integration at the epoch on every call (≤ |t|/720 steps), which is
what the reference does when time reverses — identical numbers, and the propagator is immutable and thread safe.

```swift
public final class Sgp4Propagator: Sendable {
    public static let maximumMinutesFromEpoch = 1.0e7             // ≈ 19 years
    public let elements: OrbitalElements
    public init(elements: OrbitalElements)                        // JVM: 2.4 µs near Earth, 6 µs deep space (Swift release 0.5 / 1 µs)
    public var isDeepSpace: Bool                                  // period ≥ 225 min
    public var semiMajorAxisEarthRadii, perigeeAltitudeKilometers, apogeeAltitudeKilometers: Double
    public func propagate(minutesSinceEpoch: Double) -> Sgp4Result // 0.6 µs LEO, 3 µs deep space (same on Swift release)
    public func state(at date: Date) -> Sgp4Result
    public func minutesSince(_ date: Date) -> Double
}
public struct TemeState: Hashable, Sendable { public let position: Vector3 /* km */, velocity: Vector3 /* km/s */ }
public enum Sgp4Result: Hashable, Sendable { case success(TemeState), failure(Sgp4Error); var state: TemeState?; var error: Sgp4Error? }
public enum Sgp4Error: Int, Hashable, Sendable, CaseIterable, Error {
    case meanEccentricityInvalid = 1, meanMotionNotPositive = 2, perturbedEccentricityInvalid = 3, semiLatusRectumNegative = 4,
         decayed = 6, nonFiniteState = 7 /* not in the reference */, timeOutOfRange = 8 /* not in the reference */
    var code: Int
}
```
```kotlin
class Sgp4Propagator(val elements: OrbitalElements) { companion object { const val maximumMinutesFromEpoch = 1.0e7 }
    val isDeepSpace: Boolean ; val semiMajorAxisEarthRadii: Double ; val perigeeAltitudeKilometers: Double ; val apogeeAltitudeKilometers: Double
    fun propagate(minutesSinceEpoch: Double): Sgp4Result ; fun state(date: Instant): Sgp4Result ; fun minutesSince(date: Instant): Double }
data class TemeState(val position: Vector3, val velocity: Vector3)
sealed interface Sgp4Result { val state: TemeState? ; val error: Sgp4Error? ; data class Success(val temeState: TemeState) ; data class Failure(val sgp4Error: Sgp4Error) }
enum class Sgp4Error(val rawValue: Int) { MEAN_ECCENTRICITY_INVALID(1), MEAN_MOTION_NOT_POSITIVE(2), PERTURBED_ECCENTRICITY_INVALID(3),
    SEMI_LATUS_RECTUM_NEGATIVE(4), DECAYED(6), NON_FINITE_STATE(7), TIME_OUT_OF_RANGE(8) ; val code: Int ; companion fun fromRawValue(raw: Int): Sgp4Error? }
```
Accuracy of the *model* (not the port): the elements, not the arithmetic, limit it — an along-track error of a few km at epoch that grows 1–3 km/day for low orbits
(≈ 0.2–0.4 s/day for the ISS), much faster after a reboost or a drag spike. Check `OrbitalElements.ageDays` / `ElementSetGroup.maximumAgeDays`.

## SatelliteFrames.swift / SatelliteFrames.kt

```swift
public enum SatelliteFrames {
    public static let earthEquatorialRadiusKilometers = 6378.137, earthFlattening = 1 / 298.257223563, earthRotationRate = 7.29211514670698e-5
    public static func greenwichMeanSiderealTime(at date: Date) -> Double                       // radians [0, 2π), UT1 = UTC
    public static func earthFixedState(of state: TemeState, at date: Date) -> TemeState         // position rotated by GMST; velocity relative to the rotating Earth
    public static func earthFixedPosition(of location: GeographicLocation) -> Vector3           // WGS-84, km
    public static func sunPosition(at date: Date) -> Vector3                                    // TEME km, apparent (1.5e8 long)
    public static func look(at state: TemeState, date: Date, observer: Observer) -> SatelliteDirection
}
public struct SatelliteDirection { azimuth, altitude, rangeKilometers, rangeRateKilometersPerSecond; var horizontal: HorizontalCoordinate; var unitVector: Vector3 }
public enum EarthShadow {
    public static let sunRadiusKilometers = 695_700.0, sunlitThreshold = 0.5
    public static func illuminatedFraction(satellite: Vector3, sun: Vector3) -> Double          // 0 umbra … 1 sunlight (any common frame, km)
    public static func isSunlit(satellite: Vector3, sun: Vector3) -> Bool                       // fraction ≥ 0.5
}
```
Kotlin: `SatelliteFrames.greenwichMeanSiderealTime(date)`, `earthFixedState(state, date)`, `earthFixedPosition(location)`, `sunPosition(date)`,
`look(state, date, observer)`; `EarthShadow.illuminatedFraction(satellite, sun)`, `isSunlit(satellite, sun)`; `data class SatelliteDirection`.

## SatelliteBrightness.swift / SatelliteBrightness.kt

Standard-magnitude model: `m = m₀ − 15 + 5·log10(ρ_km) − 2.5·log10(sin β + (π − β)·cos β) − 2.5·log10(illuminated fraction)`; `m₀` is the
**intrinsic magnitude at 1000 km and 90° phase** (Heavens-Above: "at 1000 km distance, 50 % illuminated"), β the Sun–satellite–observer
angle; the phase term is a diffuse-sphere law. No atmospheric extinction. A real satellite glints: the model is good to ~1 mag (worse for the
ISS, which can flare brighter). **Unknown intrinsic magnitude → no magnitude (`nil`)**.

```swift
public enum SatelliteBrightness {
    public static let issIntrinsicMagnitude = -1.8                                  // Heavens-Above, 25544, retrieved 2026-10-18
    public static func intrinsicMagnitude(catalogNumber: Int) -> Double?            // 25544 → -1.8, 48274 (CSS Tianhe) → 0.0, 20580 (HST) → 2.2, else nil
    public static func apparentMagnitude(intrinsicMagnitude: Double, rangeKilometers: Double, phaseAngle: Double,
                                         illuminatedFraction: Double = 1) -> Double?  // nil: not lit, non-finite/out-of-range input, phase ≈ 180°
}
```
Kotlin: `SatelliteBrightness.intrinsicMagnitude(catalogNumber)`, `apparentMagnitude(intrinsicMagnitude, rangeKilometers, phaseAngle, illuminatedFraction = 1.0)`.
Sanity: ISS at 450 km, 60° phase → −4.2; at 1000 km, 90° → −1.8.

## Satellite.swift / Satellite.kt — the Aim-screen API

```swift
public final class Satellite: Sendable {
    public static let maximumTrackPoints = 20_000
    public let elements: OrbitalElements, intrinsicMagnitude: Double?, propagator: Sgp4Propagator
    public init(elements: OrbitalElements, intrinsicMagnitude: Double?)          // explicit (nil = unknown)
    public convenience init(elements: OrbitalElements)                           // built-in magnitude for the catalog number, if any
    public var name: String                                                      // "NORAD 12345" without a name
    public var catalogNumber: Int
    public func look(at date: Date, observer: Observer) -> SatelliteLook?        // 40 µs on the JVM, 8 µs Swift release; nil where SGP4 fails
    public func look(in context: SatelliteSkyContext) -> SatelliteLook?          // 2 µs JVM, 1 µs Swift release: share one context across satellites
    public func track(from start: Date, to end: Date, step: Double, observer: Observer) -> [SatelliteLook]   // ≤ 20 000 points and 30 days; step clamped 0.1…3600 s
}
public struct SatelliteSkyContext: Sendable {                                    // sky of one observer at one instant (40 µs JVM, 8 µs Swift release to build)
    public let date: Date, observer: Observer, sunPosition: Vector3 /* TEME km */, sunAltitude: Double
    public init(date: Date, observer: Observer)
    public func isDark(darknessSunAltitude: Double = -6) -> Bool
}
public struct SatelliteLook: Hashable, Sendable {
    public let date: Date
    public let azimuth, altitude, apparentAltitude: Double                       // ° — altitude geometric; apparentAltitude with refraction (observer.atmosphere)
    public let rangeKilometers, rangeRateKilometersPerSecond, angularSpeed: Double   // angularSpeed in °/s
    public let illuminatedFraction, phaseAngle, sunAltitude: Double
    public let magnitude: Double?                                                // nil: intrinsic unknown or not lit
    public let temeState: TemeState
    public var horizontal, apparentHorizontal: HorizontalCoordinate              // apparentHorizontal = where to point the camera
    public var direction, apparentDirection: Vector3                             // ENU unit vectors
    public var isAboveHorizon, isSunlit: Bool
    public func observerIsDark(darknessSunAltitude: Double = -6) -> Bool
    public func isVisible(darknessSunAltitude: Double = -6) -> Bool              // above horizon ∧ sunlit ∧ dark sky
}
```
Kotlin: `Satellite(elements, intrinsicMagnitude = <built-in>)`, `look(date, observer)`, `look(context)`, `track(start, end, step, observer)`;
`SatelliteSkyContext(date, observer)`; `data class SatelliteLook` (same members). **Aim support:** `look(...).apparentHorizontal` /
`.apparentDirection` for "where is it now", `SatellitePass.culmination.horizontal` / `.direction` for "where it peaks",
`track(...)` for the path to draw, and any `look(at: t)` for "the point to wait at".

## SatellitePassFinder.swift / SatellitePassFinder.kt

A *pass* is a stretch with geometric altitude ≥ `minimumAltitude` (default 10°). Visible parts = sunlit (≥ half the Sun's disc) ∧ Sun below
`darknessSunAltitude` (default −6°). Passes in progress at the window's start/end are cut there (`isRiseCut`/`isSetCut`; `rise`/`set` = window edge).

```swift
public struct SatellitePassFinder: Sendable {
    public static let defaultMinimumAltitude = 10.0, defaultDarknessSunAltitude = -6.0, maximumWindowDays = 30.0
    public let observer: Observer, interval: DateInterval, minimumAltitude: Double, darknessSunAltitude: Double
    public init(observer: Observer, interval: DateInterval, minimumAltitude: Double = 10, darknessSunAltitude: Double = -6)
    public func passes(of satellite: Satellite) -> [SatellitePass]               // chronological
}
public struct SatellitePass: Hashable, Sendable {
    public let rise, culmination, set: SatellitePassPoint
    public let visibleIntervals: [DateInterval]                                  // usually 0 or 1
    public let brightest: SatellitePassPoint?                                    // nil without an intrinsic magnitude or visible part
    public let isRiseCut, isSetCut: Bool
    public var interval: DateInterval, duration: Double, isVisible: Bool, visibleSpan: DateInterval?
}
public struct SatellitePassPoint: Hashable, Sendable { date, azimuth, altitude, rangeKilometers, isSunlit, magnitude: Double?; var horizontal, direction }
```
Kotlin: `SatellitePassFinder(observer, interval, minimumAltitude = 10.0, darknessSunAltitude = -6.0).passes(satellite)`; same data classes.

Method: adaptive coarse steps (a satellite `g`° below the threshold at range `ρ` cannot reach it sooner than `ρ·sin g / v_max`, so nothing is
missed), bisection to 0.01 s (rise/set), golden section to 0.05 s (culmination), visible edges by 5 s samples (30 s for passes ≥ 1 h) and bisection,
brightest point refined by golden section. Cost (desktop JVM, warm; a Swift release build is about the same): ISS or Starlink 3 days **3 ms**, GPS 6 ms, Molniya 22 ms, geosynchronous < 1 ms;
creating a finder for 3 days 0.5 ms (hourly Sun nodes) — **100 satellites over 3 days ≈ 0.3–0.4 s** (a phone: ~1 s; run it off the main thread).
Window limited to 30 days. Failed propagation (decay, > 19 years from epoch) gives no passes.

## StreakRisk.swift / StreakRisk.kt

```swift
public struct CameraFrame: Hashable, Sendable {
    public static let maximumFieldOfView = 170.0
    public let boresight, up, right: Vector3, widthDegrees, heightDegrees: Double          // ENU
    public init?(boresight: HorizontalCoordinate, widthDegrees: Double, heightDegrees: Double, rollDegrees: Double = 0)
    public init?(solution: PointingSolution, widthDegrees: Double, heightDegrees: Double)   // from the Aim screen's measured pointing
    public var cornerAngle: Double, boresightCoordinate: HorizontalCoordinate
    public func position(of direction: Vector3) -> FramePosition?                            // x right, y up; edges at ±1; nil behind the camera
    public func contains(_ direction: Vector3) -> Bool
}
public struct StreakOptions: Hashable, Sendable { minimumAltitude = 0, summaryAltitude = 20, rateWindow = 120, limitingMagnitude: Double? = nil }
public enum StreakRisk {
    public static let maximumSatelliteCount = 100_000, maximumExposureSeconds = 3_600.0
    public static func assess(_ satellites: [Satellite], observer: Observer, frame: CameraFrame, exposure: DateInterval,
                              options: StreakOptions = StreakOptions()) -> StreakAssessment
}
public struct StreakAssessment: Sendable {
    public let crossings: [SatelliteCrossing]                  // sunlit satellites crossing the frame during the exposure, by entry time
    public let evaluatedCount, aboveHorizonCount: Int
    public let sunlitAboveSummaryAltitudeCount: Int            // "N sunlit satellites above 20° right now" (at the exposure start)
    public let crossingsPerMinute: Double, rateWindow: Double  // crossings that begin within rateWindow (or the exposure), per minute
    public func probabilityOfStreak(exposureSeconds: Double) -> Double      // 1 − exp(−rate · t)
}
public struct SatelliteCrossing: Sendable { satellite: Satellite, entry, exit: CrossingPoint, startsBeforeExposure, endsAfterExposure: Bool, magnitude: Double?; var duration: Double }
public struct CrossingPoint: Hashable, Sendable { date, azimuth, altitude, position: FramePosition? }
public struct FramePosition: Hashable, Sendable { x, y }
```
Kotlin: `CameraFrame.create(boresight, widthDegrees, heightDegrees, rollDegrees = 0.0)` / `create(solution, widthDegrees, heightDegrees)` (nullable),
`frame.position(direction)`, `contains(direction)`; `StreakRisk.assess(satellites, observer, frame, exposure, options)`; same data classes
(`StreakOptions(minimumAltitude = 0.0, summaryAltitude = 20.0, rateWindow = 120.0, limitingMagnitude = null)`).

- **Frame geometry.** Rectilinear lens: gnomonic coordinates `x = (d·right)/(d·boresight)`, inside when `|x| ≤ tan(w/2)`, `|y| ≤ tan(h/2)`. Roll 0° =
  picture's horizontal edges level (camera in landscape on a level head); positive roll = camera turned clockwise as the photographer sees it (matches
  `PointingSolution.roll`); portrait = swap width/height or roll 90°. Zenith/nadir: picture's up is north. Use `FieldOfView` for width/height.
- **A crossing** is a stretch where the satellite is inside the frame, above `minimumAltitude` and sunlit; entry/exit are exact to ~0.1 s (linear
  interpolation of frame position, altitude and illumination between samples ≤ min(0.5·smaller side, 3°) apart).
- **Cost** (desktop): 10 000 Starlink sets, 30 s exposure: **30–90 ms** (cold JVM first call ~0.1 s); Swift release similar; a phone 3–5×.
  `Satellite(...)` construction for 11 000 satellites ≈ 10–35 ms; their memory ≈ 12 MB.

---------------------------------------------------------------------------------------------------------

## Notes for the UI agents — which call where

| Screen / moment | Call | How often | Cost |
|---|---|---|---|
| App start / data refresh (service) | `ElementSetParser.parseJson` per group → `map { Satellite(...) }` | when a download arrives | stations 1 ms, visual 20 ms, Starlink 0.2–0.5 s (background) |
| **Satellites list** ("ISS tonight", next visible passes) | `SatellitePassFinder(observer, interval).passes(of:)` per satellite; show `rise.date`, `culmination.altitude/azimuth`, `visibleSpan`, `brightest?.magnitude`; filter `isVisible` | once on appear, again when the location changes or elements refresh | 3 ms per satellite; 100 satellites ≈ 0.4 s — background thread |
| **Pass detail** (sky path) | `satellite.track(from: pass.rise.date, to: pass.set.date, step: 5, observer:)` | once per pass | ~100 points, < 1 ms each batch of 100 |
| **Aim** at a satellite | build one `SatelliteSkyContext(date: now, observer:)` per sensor tick, `satellite.look(in:)` → `apparentHorizontal` as the `GuidanceTracker` target; show `rangeKilometers`, `angularSpeed`, `magnitude`, `isSunlit` | every attitude update (≤ 30 Hz) | 40 µs + 2 µs per satellite |
| Aim "wait here" (long exposure on a pass) | `pass.culmination.horizontal` (or `look(at: t)` for a chosen time) as a fixed target; time until it arrives = `t − now` | on pass selection | negligible |
| **Streak warning** chip in Aim/Exposure | `StreakRisk.assess(starlinkSatellites, …)` with `CameraFrame(solution:widthDegrees:heightDegrees:)` and the planned exposure | when the pointing/exposure changes, at most once per second, background thread; skip when the Starlink list is older than its limit | 30–90 ms desktop, ~0.3 s phone |
| Exposure detail | show `crossings` (entry/exit times, positions at the frame edge, `magnitude`), `probabilityOfStreak(exposureSeconds:)` | with the chip | — |
| Tonight / planner | pass the night's `DateInterval` (e.g. from `TwilightCalculator`) to `SatellitePassFinder`; use `isVisible` | once per refresh | as above |

Handling `nil`: `look` and `track` skip a time where SGP4 fails (decayed, > 19 years from epoch) — show "orbit data too old". Check
`ElementSetGroup.isFresh` before trusting second-level times and show a staleness hint otherwise.

Unknown magnitude (everything but ISS, Tianhe/CSS, HST): show "—", not a number. For Starlink streaks do not filter by magnitude (a sunlit Starlink
is magnitude ~4–7, bright enough to streak a long exposure); `StreakOptions.limitingMagnitude` only drops *known* magnitudes.

## Notes for the app services — what to download

All requests: `https://celestrak.org/NORAD/elements/gp.php?GROUP=<group>&FORMAT=json` (OMM JSON; **not** TLE — object numbers above 99 999 cannot be
written as TLE, and several current stations are already beyond that; the SGP4 vector file `SGP4-VER.TLE` is the only TLE the tests need).

| Group | Why | Size (2026-10-18) | Refresh | Freshness limit |
|---|---|---|---|---|
| `stations` | ISS, Tiangong modules, visiting vehicles (several objects share the ISS orbit — dedupe by name or keep 25544 / 48274 only) | 21 records, 9 KB | every 12 h while the app is used; always when the newest epoch is older than 2 days and the user opens the satellite screen | 3 days |
| `visual` | ~150 brightest satellites/rocket bodies incl. HST | 155 records, 65 KB | daily | 7 days |
| `starlink` | streak warning | 11 124 records, 4.7 MB | daily, on Wi-Fi, only if the streak feature is on | 3 days (rate estimates tolerate older sets; individual crossing times do not) |

- **Etiquette / limits.** CelesTrak updates each group about every 2 hours and answers repeated requests for unchanged data with **HTTP 403**:
  never download a group more than once per `ElementSetGroup.minimumDownloadIntervalHours` (2 h); on 403 keep the cached copy; send a descriptive
  `User-Agent`. 
- **Cache.** Store the raw JSON bytes plus the download time per group (≈ 4.8 MB total with Starlink; stations + visual only: 75 KB). Re-parse at
  start (0.2–0.5 s for Starlink) or keep the `[Satellite]` array in memory (≈ 1.2 KB per satellite: 11 000 ≈ 12 MB). Do not persist parsed state.
- **Data hygiene.** Run `batch.latestPerObject()` if several downloads are merged; log `batch.skippedCount` / `batch.skipped` (a few skipped records are normal,
  hundreds mean the format changed); drop elements whose `epoch` is in the future by more than a day.
- **Accuracy vs age.** SGP4 along-track error grows by about 1–3 km a day for low orbits (≈ 0.2–0.4 s/day for the ISS), but a reboost, a drag spike or a
  manoeuvre makes older sets wrong by seconds to minutes: that is what the freshness limits above protect against.

## Verification

All numbers are asserted in the tests of both languages (same fixtures, byte for byte; generator `Tools/make_satellite_fixtures.py`).

| What | Reference | Result |
|---|---|---|
| SGP4/SDP4, 33 element sets of `SGP4-VER.TLE` (near-earth, 12 h and 24 h resonance, Lyddane, low perigee, decay, error codes 1, 3, 4, 6), 666 states and all error times | output of the reference C++ (`SGP4.cpp` 2020-07-13, mode `i`, WGS-72) built from CelesTrak's `cpp.zip` (sha256 pinned) = `tcppver.out` | position ≤ 5·10⁻⁹ km (5 µm: the last printed digit), velocity ≤ 5·10⁻¹⁰ km/s; tolerance in the tests 10⁻⁷ km / 10⁻⁸ km/s; the official MATLAB (Dec 2015) output of the 2006 distribution agrees to the same digit except 23599 after 420 min (< 1 km, an older Lyddane rule; C++ and Java v2 agree with this port) |
| SGP4 on the snapshot element sets (ISS, CSS, HST, Starlink, GPS, Molniya, TDRS 7 (24 h resonance), ENVISAT) at 6 times each | Python `sgp4` 2.27 | ≤ 1·10⁻⁹ km, 4·10⁻¹³ km/s |
| 115 complete passes (rise, culmination, set, azimuths, culmination altitude) of 10 satellite/site cases over three days: ISS, CSS, HST, Starlink, GPS, Molniya, TDRS 7, ENVISAT at Boulder, Sydney, Tromsø | Skyfield 1.55 + JPL DE421 (`find_events` refined to 1 ms) | LEO: ≤ 0.11 s in time, 0.0035° in culmination altitude, 0.01° in direction; passes of hours (TDRS 7, 16 h): ≤ 1.5 s |
| 28 visible parts (sunlit ∧ Sun < −6°) | Skyfield `is_sunlit` + DE421 Sun altitude | edges ≤ 0.9 s |
| 20 Earth-shadow transitions (ISS, Starlink; 24 h) | Skyfield `is_sunlit` bisected to 1 ms | ≤ 0.10 s (apparent vs geometric Sun direction: 20″) |
| 50 looks (az/alt/range/range rate/sunlit/Sun altitude) | Skyfield | 0.0006°, 33 m, 5·10⁻⁵ km/s, 0.003°; 0 sunlit mismatches |
| GMST (Vallado ex. 3-5: 152.578787810°), Sun in TEME (6 dates), WGS-84 positions | Vallado 2013; Skyfield `TEME` frame | GMST 10⁻⁶°; Sun direction < 0.002°, distance 2·10⁻⁵ |
| Streaks: 47 crossings in 5 frames (100°×70° … 24°×16°, rolls 0/90/−20/30°, zenith pointing, Boulder/Sydney/Tromsø) of a 2 976-satellite constellation, counts above the horizon / sunlit above 20° | Skyfield positions + `is_sunlit`, frame geometry in NumPy, 0.1 s sampling | all matched, entry/exit ≤ 0.11 s, counts and rates equal |
| Magnitude | closed-form checks of the formula; ISS at 450 km, 60° → −4.2 | exact |

## Limits and approximations

- UT1 ≡ UTC and no polar motion (≤ 0.004° / 10 m); apparent (aberrated) Sun direction for the shadow (0.1 s difference from a geometric Sun).
- Shadow edge is geometric (no atmosphere), fraction ≥ 0.5 counts as sunlit; the penumbra is modelled but the magnitude is only scaled by the fraction.
- The brightness model is a sphere: glints, flares and tumbling are not predicted; only 3 intrinsic magnitudes are built in.
- A pass cut by the search window reports the window edge as rise/set; windows are limited to 30 days; streak exposures to one hour.
- `StreakRisk` prefilter uses the shadow shortcut for windows ≤ 20 min (a low-orbit satellite is lit for ≥ 1 h at a stretch); longer windows scan every satellite that can reach the frame.
- TLE input is strict about 69-character lines and checksums (CelesTrak data always satisfies this); hand-edited lines with wrong checksums are rejected by design.

## Data licences (for ATTRIBUTION.md)

- **CelesTrak GP data** (orbital elements derived from US Space Force / 18th SDS public data; fixtures `satellite_stations.*`, `satellite_omm_sample.json`,
  `satellite_starlink_sample.json`, snapshot of 2026-10-18): free to use; CelesTrak asks for attribution ("Orbital data: CelesTrak, T. S. Kelso").
  The app downloads it at run time; only small snapshots are in the test fixtures.
- **SGP4 reference implementation** (Vallado, Crawford, Hujsak, Kelso, AIAA 2006-6753, via CelesTrak): distributed as freely usable source; the port is derived work with the
  authors cited in the source comments. `sgp4_ver.tle` is the distribution's `SGP4-VER.TLE`; `sgp4_tcppver.out` is output of the reference code.
- **Skyfield (MIT), `sgp4` Python package (MIT), JPL DE421 (public domain, NASA/JPL)**: used only to generate the reference fixtures; nothing is shipped.
- **Heavens-Above intrinsic magnitudes** (ISS −1.8, CSS 0.0, HST 2.2): facts cited in the source comments; no data file is shipped.
- Earth-shadow geometry: Montenbruck & Gill, *Satellite Orbits*, §3.4.2 (published method).

# Comets and asteroids as photo targets — API (Kotlin and Swift)

Kotlin package `net.kuecker.lodestar.kit.comets` (`lodestar-kit`), Swift folder `Sources/LodestarKit/Comets` (`LodestarKit`).
Both implementations have the same types, members, constants, algorithms and evaluation order; every test exists in both (127 per
language, `Comets*Tests`). The engine **parses and computes only**: the orbit files are downloaded by the apps at run time (see
"Data for the apps"). No global state, no network access, no I/O.

Naming map (as in the other API documents): Swift `struct` ↔ Kotlin immutable `data class`; Swift `enum` without payload ↔ `enum class`
with UPPER_SNAKE constants and `rawValue`; Swift `enum` with payloads ↔ `sealed interface`; `Date` ↔ `java.time.Instant`; Swift
failable `init?` ↔ `companion create(...)`; Swift argument labels disappear in Kotlin (parameter names are the Swift internal
names). Kotlin imports: `net.kuecker.lodestar.kit.comets.*` plus `…foundation.*` (`JulianDate`, `SkyFrame`, `Observer`,
`DateInterval`) and, for the `SkyFrame` extensions, `net.kuecker.lodestar.kit.comets.state`, `.horizontal`, `.topocentricEquatorial`.

## Quick start

```kotlin
// 1. Download CometEls.txt (164 kB, daily) and parse it leniently.
val parsed = CometElementsParser.parse(text)                       // 963 bodies in 2 ms; parsed.rejectedLineCount lines were skipped
// 2. Bright comets now / this month.
val now = Instant.now()
val tonight = SmallBodySelection.brightNow(parsed.bodies, now, magnitudeLimit = 10.0)           // sorted, brightest first
val month = SmallBodySelection.brightest(parsed.bodies, DateInterval(now, now.plus(31, ChronoUnit.DAYS)), magnitudeLimit = 10.0)
// 3. One comet on the sky.
val frame = SkyFrame(now, Observer(GeographicLocation(32.78, -96.80, 150.0)))
val body = tonight.first().body
val state = frame.state(body)                                      // SmallBodyState?  magnitude, distances, elongation, tail angle
val horizontal = frame.horizontal(body)                            // HorizontalCoordinate? (refraction on by default)
val tilt = state?.let { SmallBodyEphemeris.tailAngleFromVertical(it, frame) }
val events = SmallBodyEphemeris.riseTransitSet(body, frame.observer, DateInterval(now, 86_400.0))
```
```swift
let parsed = try CometElementsParser.parse(text)
let now = Date()
let tonight = SmallBodySelection.brightNow(in: parsed.bodies, at: now, magnitudeLimit: 10)
let month = SmallBodySelection.brightest(in: parsed.bodies, during: DateInterval(start: now, duration: 31 * 86_400), magnitudeLimit: 10)
let frame = SkyFrame(date: now, observer: Observer(location: GeographicLocation(latitude: 32.78, longitude: -96.80, elevation: 150)))
let body = tonight[0].body
let state = frame.state(of: body)                                  // SmallBodyState?
let horizontal = frame.horizontal(of: body)                        // HorizontalCoordinate?
let tilt = state.flatMap { SmallBodyEphemeris.tailAngleFromVertical(of: $0, in: frame) }
let events = SmallBodyEphemeris.riseTransitSet(of: body, observer: frame.observer, within: DateInterval(start: now, duration: 86_400))
```

## Data for the apps (what to download, how often)

| File | URL | Size | Content | Refresh |
| --- | --- | ---: | --- | --- |
| `CometEls.txt` | `https://www.minorplanetcenter.net/iau/MPCORB/CometEls.txt` | 164 kB, 963 lines (2026-10-18), no server-side gzip | every comet of the last decades, MPC fixed-width format, elements osculating at the file's date | the server updates it about daily (`Last-Modified: 2026-10-18 08:15 GMT`); fetch **at most once a day** with `If-Modified-Since`, **at least monthly** |
| bright asteroids | own static file in MPC one-line format, see below | about 40 kB (≈ 200 lines) | the asteroids with H ≤ 8.5 | monthly is plenty |
| single object | `https://ssd-api.jpl.nasa.gov/sbdb.api?des=<designation>&phys-par=1&full-prec=1` | 3–6 kB | JPL elements + magnitude parameters of one comet or asteroid (`SmallBodyJSONParser`) | on demand |

* **Asteroids.** `MPCORB.DAT` (320 MB, 94 MB gzipped), `NEA.txt` (8.7 MB), `PHA.txt` (0.5 MB), `Distant.txt` and `Unusual.txt`
  (1.7 and 8.7 MB) all use the one-line format that `MinorPlanetElementsParser` reads, but **the apps must not download them**. Only
  about 130 asteroids have H ≤ 8 (they reach magnitude 10 or brighter at opposition; Vesta peaks near 5.5, Ceres near 7.5). Build a
  small file on a server (the GitHub Pages site of the app) from `MPCORB.DAT.gz` once a month and let the apps download that:
  `gzip -dc MPCORB.DAT.gz | awk 'NR>1 && /^[0-9A-Za-z~]/ && substr($0,9,5)+0 <= 8.5 && substr($0,9,5) ~ /[0-9]/ && substr($0,9,5)+0 < 90' > bright-asteroids.txt`
  (add the MPCORB header text to the file, see the licence below). `MinorPlanetElementsParser.parse` skips a header up to the line of
  dashes and ignores blank lines; lines with an unknown H (99.99) parse to a body without photometry. Alternatively fetch the ~25
  brightest asteroids one by one from the JPL API above.
* **Freshness.** The MPC elements of all perturbed comets are osculating at the update date, so the age of the file is the age of the
  elements. Positions are two-body extrapolations; the error grows with the distance in time from that epoch (table below): fine
  to plan a month ahead, show "elements of <date>" when the file is older than 60 days and stop listing after ~200 days. Keep the last good file; a download or parse error
  must never delete it (check `rejectedLineCount` and that `bodies` is not empty before replacing it).
* **Magnitudes.** The MPC magnitude parameters of the comet file are **visual total-magnitude estimates by observers**; JPL's `M1`/`K1`
  are often very different (161P/Hartley-IRAS: MPC `M1` 8.5, JPL 15.2 — 9.7 vs 14.9 now). Use the MPC file for "what is bright": it is
  the better predictor of what the eye and a camera see, but still ±1–2 magnitudes, and a comet's *total* magnitude spreads over the
  coma: a magnitude-9 comet of 20′ is much harder than a magnitude-9 star.
* **Licences.** MPC orbit files: software may include them if the header is kept and the Minor Planet Center is credited (credit the
  observers and orbit computers implicitly through the MPC); JPL SBDB/Horizons data are NASA/JPL products, public domain, credit requested.
  Put "Orbital elements: IAU Minor Planet Center (minorplanetcenter.net); JPL Small-Body Database (ssd.jpl.nasa.gov)" in ATTRIBUTION.md.

## Parsers

```kotlin
object CometElementsParser {                       // MPC CometEls.txt, fixed width, 79..400 characters per line
    const val minimumLineLength: Int; const val maximumLineLength: Int; const val defaultSlopeParameter: Double   // 79, 400, 4.0
    fun parseLine(line: String): SmallBody                                                // throws SmallBodyParseError
    fun parse(text: String): SmallBodyParseResult                                         // lenient; throws InputTooLarge (> 64 Mi chars)
}
object MinorPlanetElementsParser {                 // MPC one-line format: MPCORB.DAT, NEA.txt, PHA.txt, ... (103..400 characters)
    const val minimumLineLength: Int; const val maximumLineLength: Int; const val unknownMagnitude: Double   // 103, 400, 99.0
    fun parseLine(line: String): SmallBody
    fun parse(text: String): SmallBodyParseResult                                         // skips the MPCORB header and blank lines
}
object SmallBodyJSONParser {                       // JPL SBDB API answer for one object (strict RFC 8259 reader, ≤ 1 Mi chars, ≤ 32 levels)
    const val maximumInputLength: Int; const val maximumNesting: Int; const val defaultTotalSlope: Double          // 1_048_576, 32, 10.0
    fun parse(json: String): SmallBody
}
data class SmallBodyParseResult(val bodies: List<SmallBody>, val rejectedLineCount: Int, val rejections: List<SmallBodyLineRejection>) {
    companion object { const val maximumReportedRejections: Int }                         // 50
}
data class SmallBodyLineRejection(val lineNumber: Int, val error: SmallBodyParseError)    // 1-based
sealed class SmallBodyParseError(val description: String) : Exception {
    data class LineTooShort(val length: Int, val minimum: Int); data class LineTooLong(val length: Int, val maximum: Int)
    data class MissingField(val field: SmallBodyField); data class MalformedField(val field: SmallBodyField, val text: String)
    data class FieldOutOfRange(val field: SmallBodyField, val value: Double); data class InvalidDesignation(val text: String)
    data class InconsistentElements(val reason: String); data class DuplicateObject(val id: String)
    data class InvalidJSON(val reason: String); data class UnsupportedObject(val reason: String)
    data class InputTooLarge(val length: Int, val maximum: Int)
}
enum class SmallBodyField(val rawValue: String) { COMET_NUMBER, ORBIT_TYPE, PACKED_DESIGNATION, PERIHELION_YEAR, PERIHELION_MONTH, PERIHELION_DAY,
    PERIHELION_TIME, PERIHELION_DISTANCE, ECCENTRICITY, ARGUMENT_OF_PERIHELION, LONGITUDE_OF_ASCENDING_NODE, INCLINATION, EPOCH,
    ABSOLUTE_MAGNITUDE, SLOPE_PARAMETER, NUCLEAR_MAGNITUDE, MEAN_ANOMALY, MEAN_MOTION, SEMI_MAJOR_AXIS, DESIGNATION; val displayName: String }
```
```swift
public enum CometElementsParser { static let minimumLineLength = 79, maximumLineLength = 400, defaultSlopeParameter = 4.0
    static func parseLine(_ line: String) throws -> SmallBody;  static func parse(_ text: String) throws -> SmallBodyParseResult }
public enum MinorPlanetElementsParser { static let minimumLineLength = 103, maximumLineLength = 400, unknownMagnitude = 99.0
    static func parseLine(_ line: String) throws -> SmallBody;  static func parse(_ text: String) throws -> SmallBodyParseResult }
public enum SmallBodyJSONParser { static let maximumInputLength = 1_048_576, maximumNesting = 32, defaultTotalSlope = 10.0
    static func parse(_ json: String) throws -> SmallBody }
public struct SmallBodyParseResult { let bodies: [SmallBody]; let rejectedLineCount: Int; let rejections: [SmallBodyLineRejection]; static let maximumReportedRejections = 50 }
public struct SmallBodyLineRejection { let lineNumber: Int; let error: SmallBodyParseError }
public enum SmallBodyParseError: Error, Hashable, Sendable, LocalizedError {
    case lineTooShort(length: Int, minimum: Int), lineTooLong(length: Int, maximum: Int), missingField(SmallBodyField),
         malformedField(SmallBodyField, text: String), fieldOutOfRange(SmallBodyField, value: Double), invalidDesignation(text: String),
         inconsistentElements(reason: String), duplicateObject(id: String), invalidJSON(reason: String), unsupportedObject(reason: String),
         inputTooLarge(length: Int, maximum: Int);  var description: String }
public enum SmallBodyField: String, CaseIterable { case cometNumber, orbitType, packedDesignation, perihelionYear, perihelionMonth, perihelionDay,
    perihelionTime, perihelionDistance, eccentricity, argumentOfPerihelion, longitudeOfAscendingNode, inclination, epoch, absoluteMagnitude,
    slopeParameter, nuclearMagnitude, meanAnomaly, meanMotion, semiMajorAxis, designation;  var displayName: String }
```
What a parser validates: every column is a plain decimal (no `NaN`, exponents, hex or suffixes; JSON values may also carry an
exponent), inside its range (q 0.00001…1000 AU, e 0…100 (asteroids: < 1), i 0…180°, angles 0…360°, years 1583…9999, real calendar dates,
slope 0…24, H −20…40 (asteroids −5…40, G −0.25…1)), the asteroid mean motion must agree with the semi-major axis within 1 %, texts are
≤ 80 characters without control characters. A bad line never aborts a batch: it is counted (`rejectedLineCount`), the first 50 are
kept with their line number, a repeated object is skipped (`DuplicateObject`), blank lines and the MPCORB header are ignored. Windows
line endings work. Orbit type `A` of the comet file (asteroid on a cometary orbit) becomes `kind = ASTEROID`. Packed designations (comet and
minor-planet, numbers up to 15 396 335, survey designations, packed epochs) are unpacked when the readable name is missing.
Cost: `CometEls.txt` 2 ms; `NEA.txt` (43 000 lines) 80 ms.

## Model types

```kotlin
enum class SmallBodyKind(val rawValue: String) { COMET, ASTEROID; val displayName: String }                    // "comet", "asteroid"
enum class OrbitShape(val rawValue: String) { ELLIPTIC, PARABOLIC, HYPERBOLIC }                                 // e < 1, == 1, > 1

data class OrbitalElements private constructor(                       // J2000 ecliptic, Julian Days in TT
    val perihelionDistanceAU: Double, val eccentricity: Double, val inclination: Double, val argumentOfPerihelion: Double,
    val longitudeOfAscendingNode: Double, val perihelionJulianDayTT: Double, val epochJulianDayTT: Double) {
    val shape: OrbitShape; val semiMajorAxisAU: Double?; val aphelionDistanceAU: Double?; val meanMotion: Double?     // °/day
    val periodDays: Double?; val periodYears: Double?; fun meanAnomaly(julianDayTT: Double): Double?                  // elliptic only
    companion object {
        fun create(perihelionDistanceAU: Double, eccentricity: Double, inclination: Double, argumentOfPerihelion: Double,
                   longitudeOfAscendingNode: Double, perihelionJulianDayTT: Double, epochJulianDayTT: Double? = null): OrbitalElements?
        fun fromMeanAnomaly(semiMajorAxisAU: Double, eccentricity: Double, inclination: Double, argumentOfPerihelion: Double,
                            longitudeOfAscendingNode: Double, meanAnomaly: Double, epochJulianDayTT: Double): OrbitalElements?   // asteroids
    }
}
data class CometMagnitudes private constructor(val totalMagnitude: Double, val totalSlope: Double,          // M1, K1: m = M1 + 5 log10 Δ + K1 log10 r
    val nuclearMagnitude: Double?, val nuclearSlope: Double?, val nuclearPhaseCoefficient: Double) {          // M2, K2, PC: + PC·α
    fun total(sunDistanceAU: Double, earthDistanceAU: Double): Double?
    fun nuclear(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double?
    companion object { const val defaultTotalSlope: Double                                                      // 10.0
        fun create(totalMagnitude: Double, totalSlope: Double, nuclearMagnitude: Double? = null, nuclearSlope: Double? = null,
                   nuclearPhaseCoefficient: Double = 0.0): CometMagnitudes?
        fun fromMPC(absoluteMagnitude: Double, slopeParameter: Double): CometMagnitudes? }                      // K1 = 2.5 k
}
data class AsteroidMagnitudes private constructor(val absoluteMagnitude: Double, val slopeParameter: Double) { // IAU 1985 H, G
    fun apparent(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double?
    companion object { const val defaultSlopeParameter: Double; fun create(absoluteMagnitude: Double, slopeParameter: Double = 0.15): AsteroidMagnitudes? }
}
sealed interface SmallBodyPhotometry {
    data class Comet(val magnitudes: CometMagnitudes); data class Asteroid(val magnitudes: AsteroidMagnitudes)
    fun apparentMagnitude(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double?
    fun nuclearMagnitude(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double): Double?
}
data class SmallBody private constructor(
    val id: String,            // "comet.C/2023 A3", "comet.12P", "asteroid.4", "asteroid.2024 YR4": stable across element updates
    val designation: String,   // "C/2023 A3", "12P", "73P-B", "P/2020 K9", "4", "2024 YR4"
    val name: String,          // "Tsuchinshan-ATLAS", "Pons-Brooks", "Vesta" or ""
    val kind: SmallBodyKind, val number: Int?, val orbit: OrbitalElements, val photometry: SmallBodyPhotometry?) {
    val displayName: String    // name, else designation
    val title: String          // "C/2023 A3 (Tsuchinshan-ATLAS)", "12P/Pons-Brooks", "(4) Vesta", "2024 YR4"
    companion object { const val maximumTextLength: Int            // 80
        fun create(designation: String, name: String = "", kind: SmallBodyKind, number: Int? = null, orbit: OrbitalElements,
                   photometry: SmallBodyPhotometry? = null): SmallBody? }
}
```
```swift
public enum SmallBodyKind: String, CaseIterable, Codable { case comet, asteroid; var displayName: String }
public enum OrbitShape: String, CaseIterable, Codable { case elliptic, parabolic, hyperbolic }
public struct OrbitalElements: Hashable, Sendable {
    public let perihelionDistanceAU, eccentricity, inclination, argumentOfPerihelion, longitudeOfAscendingNode, perihelionJulianDayTT, epochJulianDayTT: Double
    public init?(perihelionDistanceAU: Double, eccentricity: Double, inclination: Double, argumentOfPerihelion: Double,
                 longitudeOfAscendingNode: Double, perihelionJulianDayTT: Double, epochJulianDayTT: Double? = nil)
    public static func fromMeanAnomaly(semiMajorAxisAU:eccentricity:inclination:argumentOfPerihelion:longitudeOfAscendingNode:meanAnomaly:epochJulianDayTT:) -> OrbitalElements?
    public var shape: OrbitShape; semiMajorAxisAU, aphelionDistanceAU, meanMotion, periodDays, periodYears: Double?;  func meanAnomaly(at julianDayTT: Double) -> Double?
}
public struct CometMagnitudes: Hashable, Sendable { // totalMagnitude, totalSlope, nuclearMagnitude?, nuclearSlope?, nuclearPhaseCoefficient
    public init?(totalMagnitude: Double, totalSlope: Double, nuclearMagnitude: Double? = nil, nuclearSlope: Double? = nil, nuclearPhaseCoefficient: Double = 0)
    public static func fromMPC(absoluteMagnitude: Double, slopeParameter: Double) -> CometMagnitudes?
    public func total(sunDistanceAU: Double, earthDistanceAU: Double) -> Double?
    public func nuclear(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double) -> Double? }
public struct AsteroidMagnitudes: Hashable, Sendable { // absoluteMagnitude, slopeParameter
    public init?(absoluteMagnitude: Double, slopeParameter: Double = 0.15)
    public func apparent(sunDistanceAU: Double, earthDistanceAU: Double, phaseAngle: Double) -> Double? }
public enum SmallBodyPhotometry: Hashable, Sendable { case comet(CometMagnitudes), asteroid(AsteroidMagnitudes)
    func apparentMagnitude(sunDistanceAU:earthDistanceAU:phaseAngle:) -> Double?;  func nuclearMagnitude(sunDistanceAU:earthDistanceAU:phaseAngle:) -> Double? }
public struct SmallBody: Hashable, Sendable { // id, designation, name, kind, number, orbit, photometry; displayName, title
    public init?(designation: String, name: String = "", kind: SmallBodyKind, number: Int? = nil, orbit: OrbitalElements, photometry: SmallBodyPhotometry? = nil) }
```
Brightness: comets `m = M1 + 5 log10 Δ + K1 log10 r` (total) and `M2 + 5 log10 Δ + K2 log10 r + PC·α` (nuclear), asteroids
`V = H + 5 log10(rΔ) − 2.5 log10((1−G)Φ1 + GΦ2)` (IAU 1985, `Φi = exp(−Ai tan(α/2)^Bi)`). The MPC comet file lists `H` and a slope `k`
(`K1 = 2.5 k`; the default `k = 4` is the classical 10 log r); JPL lists `M1`, `K1` directly.

## Propagation and the sky

```kotlin
object OrbitPropagator {                            // universal-variable two-body motion, all eccentricities (Stumpff functions)
    const val gaussianGravitationalConstant: Double   // 0.01720209895
    const val maximumUnboundSpanDays: Double          // 50_000: parabolic/hyperbolic orbits are evaluated within ±137 years of perihelion
    fun heliocentricEcliptic(elements: OrbitalElements, julianDayTT: Double): Vector3?    // J2000 ecliptic, AU; null outside the span
    fun heliocentricJ2000(elements: OrbitalElements, julianDayTT: Double): Vector3?       // J2000 equatorial, AU
    fun heliocentricDistance(elements: OrbitalElements, julianDayTT: Double): Double?
}
object SmallBodyEphemeris {                         // ≈ 5 µs per state on a desktop CPU
    const val defaultMagnitudeLimit: Double           // 10.0
    const val defaultMinimumElongation: Double        // 25.0
    fun state(body: SmallBody, julianDate: JulianDate): SmallBodyState?
    fun geocentricJ2000(body: SmallBody, julianDate: JulianDate): Vector3?                 // = state.geocentricJ2000, cheaper
    fun tailAngleFromVertical(state: SmallBodyState, frame: SkyFrame): Double?             // (-180, 180], 0 = tail points up, + = to the right
    fun altitude(body: SmallBody, observer: Observer): (Instant) -> Double                  // geometric altitude, for RiseSetCalculator
    fun riseTransitSet(body: SmallBody, observer: Observer, interval: DateInterval): RiseTransitSet
    fun crossings(body: SmallBody, observer: Observer, interval: DateInterval): List<AltitudeCrossing>
    fun upperCulminations(body: SmallBody, observer: Observer, interval: DateInterval): List<AltitudeCulmination>
}
data class SmallBodyState private constructor(
    val body: SmallBody, val julianDate: JulianDate,
    val geocentricJ2000: Vector3,       // AU, apparent (light-time + annual aberration): the input of SkyFrame.horizontal(geocentricJ2000, refraction)
    val astrometricJ2000: Vector3,      // AU, light-time only (what star charts and Horizons' "astrometric" columns show)
    val heliocentricJ2000: Vector3,     // Sun -> body when the light left it
    val distanceAU: Double, val heliocentricDistanceAU: Double, val elongation: Double, val phaseAngle: Double,   // Δ, r, degrees
    val apparentMagnitude: Double?,     // total magnitude (comet) or V (asteroid); null without parameters
    val nuclearMagnitude: Double?,
    val tailPositionAngle: Double?) {   // [0, 360) anti-solar direction, north through east, J2000; null if the tail points along the line of sight
    val distanceKilometers: Double
    fun isPhotographable(magnitudeLimit: Double = 10.0, minimumElongation: Double = 25.0): Boolean
}
fun SkyFrame.state(body: SmallBody): SmallBodyState?
fun SkyFrame.horizontal(body: SmallBody, refraction: Boolean = true): HorizontalCoordinate?
fun SkyFrame.topocentricEquatorial(body: SmallBody): EquatorialCoordinate?
fun SkyFrame.geocentricApparentEquatorial(body: SmallBody): EquatorialCoordinate?
```
```swift
public enum OrbitPropagator { static let gaussianGravitationalConstant = 0.01720209895, maximumUnboundSpanDays = 50_000.0
    static func heliocentricEcliptic(of elements: OrbitalElements, at julianDayTT: Double) -> Vector3?
    static func heliocentricJ2000(of elements: OrbitalElements, at julianDayTT: Double) -> Vector3?
    static func heliocentricDistance(of elements: OrbitalElements, at julianDayTT: Double) -> Double? }
public enum SmallBodyEphemeris { static let defaultMagnitudeLimit = 10.0, defaultMinimumElongation = 25.0
    static func state(of body: SmallBody, at julianDate: JulianDate) -> SmallBodyState?
    static func geocentricJ2000(of body: SmallBody, at julianDate: JulianDate) -> Vector3?
    static func tailAngleFromVertical(of state: SmallBodyState, in frame: SkyFrame) -> Double?
    static func altitude(of body: SmallBody, observer: Observer) -> (Date) -> Double
    static func riseTransitSet(of body: SmallBody, observer: Observer, within interval: DateInterval) -> RiseTransitSet
    static func crossings(of body: SmallBody, observer: Observer, within interval: DateInterval) -> [AltitudeCrossing]
    static func upperCulminations(of body: SmallBody, observer: Observer, within interval: DateInterval) -> [AltitudeCulmination] }
public struct SmallBodyState: Hashable, Sendable { // the same members; isPhotographable(magnitudeLimit:minimumElongation:)
extension SkyFrame { func state(of: SmallBody) -> SmallBodyState?;  func horizontal(of: SmallBody, refraction: Bool = true) -> HorizontalCoordinate?
    func topocentricEquatorial(of: SmallBody) -> EquatorialCoordinate?;  func geocentricApparentEquatorial(of: SmallBody) -> EquatorialCoordinate? }
```
`nil` means "this orbit cannot be evaluated at that date": only parabolic/hyperbolic orbits more than 50 000 days from perihelion
(an `A/` object or interstellar visitor long gone). Ellipses work for every date.

The geocentric position follows exactly how `Ephemeris` builds `BodyState.geocentricJ2000` for a planet: VSOP87B Earth, light-time
by iteration (≤ 4 steps, tolerance 2·10⁻⁶ d), annual aberration (Meeus 23.3) — so `SkyFrame.horizontal(geocentricJ2000:refraction:)`,
`topocentricEquatorial` and the diurnal parallax work unchanged (essential for comets that pass within 0.1–0.5 AU: parallax up to 18″).

## Selection

```kotlin
data class BrightSmallBody(val body: SmallBody, val peak: SmallBodyState) { val peakMagnitude: Double; val peakDate: Instant }
object SmallBodySelection {
    const val maximumSampleCount: Int                                               // 64
    fun brightest(bodies: List<SmallBody>, interval: DateInterval, magnitudeLimit: Double = 10.0, minimumElongation: Double = 25.0,
                  kind: SmallBodyKind? = null, maximumCount: Int = 100): List<BrightSmallBody>
    fun brightNow(bodies: List<SmallBody>, date: Instant, magnitudeLimit: Double = 10.0, minimumElongation: Double = 25.0,
                  kind: SmallBodyKind? = null, maximumCount: Int = 100): List<BrightSmallBody>
}
```
```swift
public struct BrightSmallBody: Hashable, Sendable { let body: SmallBody; let peak: SmallBodyState; var peakMagnitude: Double; var peakDate: Date }
public enum SmallBodySelection { static let maximumSampleCount = 64
    static func brightest(in bodies: [SmallBody], during interval: DateInterval, magnitudeLimit: Double = 10, minimumElongation: Double = 25,
                          kind: SmallBodyKind? = nil, maximumCount: Int = 100) -> [BrightSmallBody]
    static func brightNow(in bodies: [SmallBody], at date: Date, magnitudeLimit: Double = 10, minimumElongation: Double = 25,
                          kind: SmallBodyKind? = nil, maximumCount: Int = 100) -> [BrightSmallBody] }
```
Algorithm: (1) records without magnitude parameters are skipped; (2) **prefilter** from the perihelion distance and time: the distance from
the Sun over the period is bounded by two Kepler solutions (start, end) plus the perihelion (or aphelion) when the period contains it;
with the Earth confined to 0.983…1.017 AU the brightest possible magnitude is `M1 + 5 log Δmin + K1 log rmin − 0.35` (asteroids
`H + 5 log(r Δ)min − 0.35`) — records above the limit are dropped without computing a position; (3) the survivors are sampled daily
(≤ 64 samples; the Earth state of each sample is computed once for all records), (4) the best sample is refined by a 24-step
golden-section search, (5) the brightest state with elongation ≥ `minimumElongation` is the `peak`, kept if its magnitude ≤ the limit;
sorted by peak magnitude, ties by identifier. Tests prove the prefilter bound against dense scans of 900 synthetic orbit windows and the
result against brute force. Cost (warm, desktop): the 963 comets of today's file: 1 ms now, 2 ms for a month; 43 000 NEAs: 60 ms and 0.5 s.

## Accuracy (measured against JPL Horizons, 2026-10-18)

Two-body motion of osculating elements; Earth from VSOP87B. "Elements at the epoch" = Horizons' own osculating elements for that day, so
only planetary perturbations remain.

| Target (Horizons record) | Days from the epoch | Astrometric position error |
| --- | --- | --- |
| C/2023 A3 Tsuchinshan–ATLAS, Oct 2024 (Δ = 0.47–0.8 AU) | −7, 0, 8, 15 | 0.05″, 0.13″, 0.50″, 1.14″ |
| 12P/Pons–Brooks, Mar–May 2024 | −20, 0, 11, 25 | 0.06″, 0.02″, 0.02″, 0.09″ |
| 1P/Halley, 1986 (record 90000030) | −30, 0, 30, 60 | 0.33″, 0.00″, 0.17″, 0.62″ |
| (4) Vesta, (1) Ceres, 2026 | 0, 30, 120 | ≤ 0.2″, ≤ 0.2″, 3.5″ / 4.0″ |
| 12P from the **published** SBDB elements (epoch 2023-09-24, 179–230 days away) | 2024 Mar 21 – May 5 | 10″–27″ |
| C/2023 A3 from the published elements of 2024-05-18 | Oct 5 – Oct 27 | 9″–20″ |
| MPC comet lines of 2026-10-17 vs Horizons on 2026-10-18 and 11-17 (161P, 78P, 29P, UN271, P/2026 R2) | 1 and 31 | 0.2″–2.4″ (C/2024 E1: 45″) |
| 1P/Halley from the SBDB elements of **1968** | 1986 Jan–Apr | 0.7°–6.3° (stale elements are useless) |

Other agreements with Horizons: r and Δ ≤ 1.5·10⁻⁵ AU, elongation ≤ 0.002°, phase angle ≤ 0.02°, tail position angle ≤ 0.004°, comet total and
nuclear magnitudes and asteroid V within 0.001 mag when the same M1/K1/H/G are used, topocentric apparent place and airless azimuth/elevation
within 25″ (Horizons uses observed UT1 and polar motion). Horizons caps the total magnitude of the A3 record at 6.0 (the formula gives 5.8).
Differences between sources: the MPC `G = 0.15` for Vesta against JPL's 0.32 changes V by 0.2 mag; MPC and JPL comet `M1` can differ by several magnitudes.

## Notes for the UI agents

* **Lists.** `SmallBodySelection.brightNow(...)` for "Bright comets now" (limit 10, elongation ≥ 25°) and `brightest(... 31 days ...)` for "This month"
  (the `peakDate` says when to go out); recompute when the app opens and after each file refresh, not per frame (1–2 ms). Offer limits 6 (naked eye),
  8 (binoculars/wide-field camera) and 10 (default, tripod camera). Row: `body.title`, subtitle `kind.displayName · mag 7.4 · 62° from the Sun`, a
  peak date for the month list. Show the magnitude as an estimate ("≈ 7.4" or "mag 7–8"): comet totals are ±1–2 mag.
* **Target card.** `frame.state(body)` once per second at most (5 µs, so every frame is fine too): magnitude, Δ and r in AU, elongation, phase,
  `horizontal(body)` for the pointing arrow (same pipeline as planets, parallax included), `riseTransitSet` (3–4 ms; compute once per
  night, not per frame) for rise/transit/set, and a "Photographable" badge from `isPhotographable()`. Warn when `elongation < 25°` ("in the
  twilight glow"), when the elements are older than 60 days ("position approximate") and when `apparentMagnitude == null` ("no brightness data").
* **Tail and framing.** `tailAngleFromVertical(state, frame)` returns the angle of the extended Sun→comet line against the vertical: tilt the
  camera by it to put the tail along an image axis. Draw it as a short line from the comet marker; the real tail is up to several degrees long and
  curves, so label it "ion tail direction (approximate)". `state.tailPositionAngle` is the same direction measured from celestial north (for a star-chart
  overlay). Foreshortening is `sin(phaseAngle)`: a comet seen almost end-on (phase near 0° or 180°) has a short, ill-defined tail.
* **Sky chart.** Plot each listed body at `horizontal(body)`; the comet's apparent size is not modelled (the coma can be 0.5–3°).
* **Never** present the two-body position as better than ~1′ for elements older than a month, and refresh the files (see the table above).
* Failure handling: `parse` never throws for bad lines; check `result.bodies.isEmpty()` / `rejectedLineCount` before replacing a good cache.
  The single-object parsers throw typed `SmallBodyParseError`s with a `description` fit for logs.

## Making comets and asteroids catalog objects (the small change in the catalog area)

`SmallBody` already carries what `CelestialObject` needs: a stable `id` ("comet.12P"), `title`, `designation`, `name`, `kind`, `number` and an orbit.
The catalog change is mechanical (the compiler lists every `when` over `ObjectPosition`):

1. **`ObjectPosition`**: add `data class SmallBodyOrbit(val body: SmallBody) : ObjectPosition` (Swift `case smallBody(SmallBody)`); `SmallBody` is a value
   type, so `CelestialObject` stays immutable and `Hashable`. `CelestialObject.isSolarSystem`: also true for it.
2. **`ObjectCategory`**: add `COMET("comet")` and `ASTEROID("asteroid")` (display names "Comet", "Asteroid"; SF Symbol `"sparkle"` / `"circle.fill"`
   or the app's own icons; `group = SOLAR_SYSTEM`).
3. **`CelestialObject` factory** (new, in the catalog package): `CelestialObject.fromSmallBody(body)`: `id = body.id`, `name = body.title`,
   `designations = listOf(body.designation) + body.name`, `category = COMET/ASTEROID by body.kind`, `magnitude = null` (it changes: use `currentMagnitude`),
   `position = ObjectPosition.SmallBodyOrbit(body)`, `isListed = true`. `rankingMagnitude` for these: the current magnitude if known, else `+∞`.
4. **`CelestialObjectSky.kt` / `CelestialObject+Sky.swift`** — four `when` branches:
   ```kotlin
   fun SkyFrame.horizontal(obj, refraction) = … is ObjectPosition.SmallBodyOrbit -> horizontal(position.body, refraction) ?: HorizontalCoordinate(0.0, -90.0)
   fun SkyFrame.apparentEquatorial(obj)     = … is ObjectPosition.SmallBodyOrbit -> topocentricEquatorial(position.body) ?: EquatorialCoordinate(0.0, 0.0)
   fun SkyFrame.currentMagnitude(obj)       = … (obj.position as? ObjectPosition.SmallBodyOrbit)?.let { state(it.body)?.apparentMagnitude } ?: obj.magnitude
   fun RiseSetCalculator.riseTransitSet(obj, observer, interval) = … is ObjectPosition.SmallBodyOrbit -> SmallBodyEphemeris.riseTransitSet(position.body, observer, interval)
   ```
   ```swift
   case .smallBody(let body): horizontal(of: body, refraction: refraction) ?? HorizontalCoordinate(azimuth: 0, altitude: -90)          // horizontal(of:)
   case .smallBody(let body): topocentricEquatorial(of: body) ?? EquatorialCoordinate(rightAscension: 0, declination: 0)                // apparentEquatorial(of:)
   // currentMagnitude(of:): if case .smallBody(let body) = object.position { return state(of: body)?.apparentMagnitude }
   case .smallBody(let body): return SmallBodyEphemeris.riseTransitSet(of: body, observer: observer, within: interval)                  // riseTransitSet(for:)
   ```
   `currentAngularSize` keeps returning the catalog size (`null`): a comet's coma size is unknown.
5. **Planner / pointing**: `NightPlanner`, `TargetVisibility` and the guidance code take `CelestialObject`, so they work through these four functions;
   code that builds a `RiseSetSampler.Target` for solar-system bodies needs no change (small bodies go through the generic altitude function above).
6. **Persistence**: store `SmallBody.id` for favourites; on launch re-parse the cached file and look the id up; if a comet has dropped out of the file keep the
   favourite, show "no current orbit".

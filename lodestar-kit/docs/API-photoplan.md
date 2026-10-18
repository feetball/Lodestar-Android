# Mosaic planner, Milky Way panorama helper, star-trail and time-lapse planners (Swift + Kotlin) — API reference

Two photo-planning features for both engines.

**A. Mosaic planner** — a target is bigger than the lens sees (Orion, the Milky Way core, M31 at 200 mm, the whole Milky Way
arch at 24 mm). The planner returns the panels that cover it (rows × columns, centres and corners, shooting order,
coverage, warnings); `MosaicGuidance` turns each panel into an azimuth/altitude **target for the existing Aim guidance** at a
given time, with the camera roll that keeps the frame edges aligned; `MilkyWayPanorama` finds the visible arch, a panorama
box that covers it, and the best times of a night.

**B. Star-trail and time-lapse planners** (new files in PhotoTools) — trail arcs in degrees and pixels, the duration for a
given arc, a stacking plan, the pole and its place in the frame; time-lapse frame counts, durations, gap check, storage, battery.

| Piece | Swift (`LodestarKit`) | Kotlin (`net.kuecker.lodestar.kit…`) |
|---|---|---|
| Mosaic values: region, point, panel, plan, coverage, warning, error, orientation | `Mosaic/MosaicTypes.swift` | `mosaic/MosaicTypes.kt` |
| Planner | `Mosaic/MosaicPlanner.swift` | `mosaic/MosaicPlanner.kt` |
| Panel → direction, roll | `Mosaic/MosaicGuidance.swift` | `mosaic/MosaicGuidance.kt` |
| Milky Way arch, panorama box, best times | `Mosaic/MilkyWayPanorama.swift` | `mosaic/MilkyWayPanorama.kt` |
| Star trails | `PhotoTools/StarTrailPlanner.swift` | `phototools/StarTrailPlanner.kt` |
| Time-lapse | `PhotoTools/TimeLapsePlanner.swift` | `phototools/TimeLapsePlanner.kt` |
| Internal (not API) | `Mosaic/MosaicGeometry.swift` | `mosaic/MosaicGeometry.kt` |

Imports: Swift `import LodestarKit`. Kotlin `import net.kuecker.lodestar.kit.mosaic.*` and `…phototools.*`.

Depends on Foundation (`SkyFrame`, coordinates, `Vector3`), PhotoTools (`FrameSize`, `LensSetup`, `ExposureCalculator`), Catalog
(`AngularSize`, `CelestialObject`, for "fit this object") and Planning (`NightSummary`, `PolarAlignment`). **Nothing existing was
edited**; only new files were added (`StarTrailPlanner`, `TimeLapsePlanner` in PhotoTools). No data files, no network, no state:
every function is pure and thread safe.

Conventions
- Angles in degrees; azimuth from north through east, [0, 360); altitude [−90, 90]; equatorial plans use **J2000** places (what
  `SkyFrame.horizontal(j2000:…)` takes). Altitudes in `MosaicPanelPointing` are refracted unless `refraction: false`.
- Naming across languages as in the kit rules: Swift `struct` ↔ Kotlin `data class` (result types have an `internal` constructor
  in both languages, like `ExposureEstimate`), Swift payload `enum` ↔ Kotlin `sealed interface`/`sealed class` (cases capitalised,
  `data object` for a case without payload), `Date` ↔ `Instant`, Kotlin parameter names = Swift internal names (the Swift labels
  are shown in the Swift block). Throwing functions are `throws` in Swift and `@Throws(…Error::class)` in Kotlin.
- Errors are typed enums/sealed classes (`MosaicError`, `StarTrailError`, `TimeLapseError`); no function traps or returns NaN.

---------------------------------------------------------------------------------------------------------

## Quick usage

```swift
// Planner screen: Orion with the user's 50 mm lens, upright camera, 25 % overlap
let setup = LensSetup(sensor: .fullFrame, focalLengthMillimeters: 50, aperture: 1.8, pixelPitchMicrometers: 4.3)
let region = MosaicRegion.equatorial(center: EquatorialCoordinate(rightAscensionHours: 5.6, declination: 0), width: 30, height: 25)
// or: try MosaicRegion.fitting(object: catalog.object(id: "dso.M42")!, margin: 0.2)
let orientation = try MosaicPlanner.recommendedOrientation(region: region, frame: setup.fieldOfView)
let plan = try MosaicPlanner.plan(region: region, setup: setup, orientation: orientation, overlap: 0.25,
                                  observerLatitude: observer.location.latitude)
title.text = plan.gridDescription                       // "3 × 2", or "10 panels in 3 rows"
for warning in plan.warnings { … }

// Aim screen, about once a second while a mosaic is active
let sky = SkyFrame(date: .now, observer: observer)
let pointings = MosaicGuidance.pointings(for: plan, frame: sky)               // all panels, indexed like plan.panels
let panel = plan.orderedPanels[step]                                           // the step-th panel to shoot
let target = pointings[panel.index]                                            // target.direction → GuidanceTracker.update
let guidance = tracker.update(solution: solution, target: target.direction)
let rollToTurn = AstroMath.normalizeSignedDegrees(target.roll - MosaicGuidance.roll(forward: boresight, up: cameraUp))
```

```kotlin
val setup = LensSetup(SensorFormat.fullFrame, 50.0, 1.8, 4.3)
val region = MosaicRegion.Equatorial(EquatorialCoordinate.fromRightAscensionHours(5.6, 0.0), 30.0, 25.0)
val orientation = MosaicPlanner.recommendedOrientation(region, setup.fieldOfView)
val plan = MosaicPlanner.plan(region, setup, orientation, overlap = 0.25, observerLatitude = observer.location.latitude)
title.text = plan.gridDescription

val sky = SkyFrame(Instant.now(), observer)
val pointings = MosaicGuidance.pointings(plan, sky)
val panel = plan.orderedPanels[step]
val target = pointings[panel.index]
val guidance = tracker.update(solution, target.direction)
val rollToTurn = AstroMath.normalizeSignedDegrees(target.roll - MosaicGuidance.roll(boresight, cameraUp))
```

---------------------------------------------------------------------------------------------------------

## MosaicTypes

### MosaicOrientation, MosaicFrameKind

```swift
public enum MosaicOrientation: String, CaseIterable, Codable, Hashable, Sendable { case landscape, portrait
    public func oriented(_ size: FrameSize) -> FrameSize }          // portrait = size.rotated
public enum MosaicFrameKind: String, CaseIterable, Codable, Hashable, Sendable { case equatorial, horizontal }
```
```kotlin
enum class MosaicOrientation(val rawValue: String) { LANDSCAPE("landscape"), PORTRAIT("portrait")
    fun oriented(size: FrameSize): FrameSize
    companion object { fun fromRawValue(raw: String): MosaicOrientation? } }
enum class MosaicFrameKind(val rawValue: String) { EQUATORIAL("equatorial"), HORIZONTAL("horizontal")
    companion object { fun fromRawValue(raw: String): MosaicFrameKind? } }
```
Landscape: the sensor's width runs along the rows. Portrait: the camera is turned a quarter turn, the sensor's height runs along the rows.

### MosaicRegion — what to cover

```swift
public enum MosaicRegion: Hashable, Sendable {
    case equatorial(center: EquatorialCoordinate, width: Double, height: Double, positionAngle: Double = 0)
    case horizontal(azimuthStart: Double, azimuthSpan: Double, minimumAltitude: Double, maximumAltitude: Double)
    public static let defaultMargin = 0.2, maximumMargin = 2.0
    public static func fitting(center: EquatorialCoordinate, size: AngularSize, margin: Double = defaultMargin) throws -> MosaicRegion
    public static func fitting(object: CelestialObject, margin: Double = defaultMargin) throws -> MosaicRegion
    public static func centered(centerAzimuth: Double, centerAltitude: Double, azimuthSpan: Double, altitudeSpan: Double) -> MosaicRegion
}
```
```kotlin
sealed interface MosaicRegion {
    data class Equatorial(val center: EquatorialCoordinate, val width: Double, val height: Double, val positionAngle: Double = 0.0)
    data class Horizontal(val azimuthStart: Double, val azimuthSpan: Double, val minimumAltitude: Double, val maximumAltitude: Double)
    companion object {
        const val defaultMargin: Double; const val maximumMargin: Double
        fun fitting(center: EquatorialCoordinate, size: AngularSize, margin: Double = defaultMargin): MosaicRegion   // @Throws
        fun fitting(obj: CelestialObject, margin: Double = defaultMargin): MosaicRegion                              // @Throws
        fun centered(centerAzimuth: Double, centerAltitude: Double, azimuthSpan: Double, altitudeSpan: Double): MosaicRegion
    }
}
```
- **Equatorial**: `width` ∈ (0, 360] along the rows, `height` ∈ (0, 180] across them, both in degrees of arc. At `positionAngle` 0 the rows
  follow declination circles: the region is the band between declinations `dec ± height/2` and the two hour circles that are `width`
  apart *measured along the declination circle through the centre* (so near a pole the right-ascension span is `width / cos dec`,
  capped at a full circle). `positionAngle` (degrees, north through east) is the direction of the **height** axis: 0 = height
  north-south, width east-west; 90 = height east-west, width north-south. The whole rectangle (and its rows) turn about the centre.
- **Horizontal**: a box in azimuth (`azimuthStart` is the left edge, the box extends towards increasing azimuth, span ≤ 360) and
  altitude. Rows follow altitude circles, the camera stays level.
- `fitting`: width = the object's major axis, height = its minor axis (round: square), each × (1 + margin); the region is turned so that
  its width lies along the catalog's position angle of the major axis (`positionAngle = pa − 90`, north-south when the catalog gives none).
  Throws `objectNotFixed` for the Moon, planets, Sun and poles, `objectHasNoSize` for stars and objects without a catalog size.
- `centered`: altitudes are clamped to ±90°; azimuth normalised.

### MosaicError

```swift
public enum MosaicError: Error, Hashable, Sendable {
    case nonFiniteInput
    case invalidWidth(value: Double), invalidHeight(value: Double), invalidAzimuthSpan(value: Double)
    case invalidAltitudeRange(minimum: Double, maximum: Double)
    case invalidOverlap(value: Double), invalidMargin(value: Double), invalidLatitude(value: Double)
    case invalidFrame(horizontal: Double, vertical: Double)
    case tooManyPanels(count: Int, limit: Int)
    case objectNotFixed(objectID: String), objectHasNoSize(objectID: String)
}
```
```kotlin
sealed class MosaicError(message: String) : Exception(message) {
    data object NonFiniteInput
    data class InvalidWidth(val value: Double); InvalidHeight(val value: Double); InvalidAzimuthSpan(val value: Double)
    data class InvalidAltitudeRange(val minimum: Double, val maximum: Double)
    data class InvalidOverlap(val value: Double); InvalidMargin(val value: Double); InvalidLatitude(val value: Double)
    data class InvalidFrame(val horizontal: Double, val vertical: Double)
    data class TooManyPanels(val count: Int, val limit: Int)
    data class ObjectNotFixed(val objectID: String); ObjectHasNoSize(val objectID: String)
}
```

### MosaicPoint, MosaicPanel, MosaicCoverage, MosaicWarning, MosaicPlan

```swift
public enum MosaicPoint: Hashable, Sendable {
    case equatorial(EquatorialCoordinate)                 // J2000, equatorial plans
    case horizontal(HorizontalCoordinate)                 // azimuth/altitude, horizontal plans
    public var equatorialCoordinate: EquatorialCoordinate? { get }
    public var horizontalCoordinate: HorizontalCoordinate? { get }
    public var unitVector: Vector3 { get }                // J2000 equatorial or ENU
}
public struct MosaicPanel: Hashable, Sendable {
    public let index: Int, row: Int, column: Int          // 0-based; index is row-major
    public let center: MosaicPoint
    public let corners: [MosaicPoint]                     // 4: top left, top right, bottom right, bottom left
    public let shootingPosition: Int                      // place in plan.shootingOrder, from 0
    public var id: String { get }                         // "R2C3" (1-based row and column)
    public func outline(segmentsPerEdge: Int = 8) -> [MosaicPoint]     // 4 × segments points, edges as great circles (1…64)
}
public struct MosaicCoverage: Hashable, Sendable {
    public let widthDegrees: Double          // arc along the parallel of the central row
    public let heightDegrees: Double        // along the central meridian
    public let longitudeSpanDegrees: Double // azimuth span (horizontal plans) / right-ascension span (equatorial, PA 0)
    public let regionAreaSquareDegrees: Double
    public let panelAreaSquareDegrees: Double   // sum of the frames, overlaps counted twice
    public let coveredFraction: Double          // of the region, sampled on a 41 × 61 lattice incl. edges; 1.0 normally
}
public enum MosaicWarning: Hashable, Sendable {
    case tooManyPanels(count: Int)                  // more than MosaicPlanner.recommendedPanelLimit (100)
    case overlapNotMet(coveredFraction: Double)     // could not cover the region within the attempts
}
public struct MosaicPlan: Hashable, Sendable {
    public let region: MosaicRegion
    public let kind: MosaicFrameKind
    public let orientation: MosaicOrientation
    public let frame: FrameSize                     // as held: .horizontal runs along the rows
    public let overlap: Double                      // requested
    public let achievedOverlap: Double?             // nil for a single panel
    public let rows: Int
    public let columnsPerRow: [Int]
    public let panels: [MosaicPanel]                // row-major
    public let shootingOrder: [Int]                 // panel indices in shooting order
    public let pole: MosaicPoint                    // what the rows circle: NCP (turned by the PA) / zenith
    public let coverage: MosaicCoverage
    public let warnings: [MosaicWarning]
    public var panelCount: Int { get }
    public var columns: Int { get }                 // largest row
    public var isRectangular: Bool { get }
    public var gridDescription: String { get }      // "3 × 1" (columns × rows) or "10 panels in 3 rows"
    public var orderedPanels: [MosaicPanel] { get }
    public func panel(row: Int, column: Int) -> MosaicPanel?
    public func regionOutline(pointsPerEdge: Int = 16) -> [[MosaicPoint]]   // 1 closed loop, or 2 circles for a full ring
}
```
```kotlin
sealed interface MosaicPoint {
    data class Equatorial(val coordinate: EquatorialCoordinate); data class Horizontal(val coordinate: HorizontalCoordinate)
    val equatorialCoordinate: EquatorialCoordinate?; val horizontalCoordinate: HorizontalCoordinate?; val unitVector: Vector3
}
data class MosaicPanel internal constructor(val index: Int, val row: Int, val column: Int, val center: MosaicPoint,
                                            val corners: List<MosaicPoint>, val shootingPosition: Int) {
    val id: String
    fun outline(segmentsPerEdge: Int = 8): List<MosaicPoint>
}
data class MosaicCoverage internal constructor(val widthDegrees: Double, val heightDegrees: Double, val longitudeSpanDegrees: Double,
                                               val regionAreaSquareDegrees: Double, val panelAreaSquareDegrees: Double, val coveredFraction: Double)
sealed interface MosaicWarning { data class TooManyPanels(val count: Int); data class OverlapNotMet(val coveredFraction: Double) }
data class MosaicPlan internal constructor(val region: MosaicRegion, val kind: MosaicFrameKind, val orientation: MosaicOrientation,
        val frame: FrameSize, val overlap: Double, val achievedOverlap: Double?, val rows: Int, val columnsPerRow: List<Int>,
        val panels: List<MosaicPanel>, val shootingOrder: List<Int>, val pole: MosaicPoint, val coverage: MosaicCoverage,
        val warnings: List<MosaicWarning>) {
    val panelCount: Int; val columns: Int; val isRectangular: Boolean; val gridDescription: String; val orderedPanels: List<MosaicPanel>
    fun panel(row: Int, column: Int): MosaicPanel?
    fun regionOutline(pointsPerEdge: Int = 16): List<List<MosaicPoint>>
}
```
Indexing: **rows count downwards from the pole side** (north for equatorial plans, zenith for horizontal plans) and **columns count from
the left edge of the picture** (east for equatorial plans, the start of the azimuth range for horizontal plans): the grid is the
stitched image. Rows may have different numbers of columns (rows nearer the pole of the grid are shorter on the sphere).

## MosaicPlanner

```swift
public enum MosaicPlanner {
    public static let defaultOverlap = 0.25
    public static let overlapRange: ClosedRange<Double> = 0.10...0.50
    public static let recommendedPanelLimit = 100
    public static let maximumPanelCount = 500
    public static func plan(region: MosaicRegion, frame: FrameSize, orientation: MosaicOrientation = .landscape,
                            overlap: Double = defaultOverlap, observerLatitude: Double? = nil, reverseOrder: Bool = false) throws -> MosaicPlan
    public static func plan(region: MosaicRegion, setup: LensSetup, orientation: …, overlap: …, observerLatitude: …, reverseOrder: …) throws -> MosaicPlan
    public static func recommendedOrientation(region: MosaicRegion, frame: FrameSize, overlap: Double = defaultOverlap) throws -> MosaicOrientation
}
```
```kotlin
object MosaicPlanner {
    const val defaultOverlap: Double; val overlapRange: ClosedFloatingPointRange<Double>
    const val recommendedPanelLimit: Int; const val maximumPanelCount: Int
    fun plan(region: MosaicRegion, frame: FrameSize, orientation: MosaicOrientation = LANDSCAPE, overlap: Double = defaultOverlap,
             observerLatitude: Double? = null, reverseOrder: Boolean = false): MosaicPlan                      // @Throws(MosaicError)
    fun plan(region: MosaicRegion, setup: LensSetup, orientation: …, overlap: …, observerLatitude: …, reverseOrder: …): MosaicPlan
    fun recommendedOrientation(region: MosaicRegion, frame: FrameSize, overlap: Double = defaultOverlap): MosaicOrientation
    internal fun plan(…, maximumAttempts: Int): MosaicPlan     // Swift: internal static func plan(…, maximumAttempts:) — test seam
    internal fun settingHourAngle(declination: Double, observerLatitude: Double?): Double
}
```
- `frame` is the **landscape** frame (`LensSetup.fieldOfView`); `orientation: portrait` swaps it. Throws `invalidOverlap` outside 10–50 %,
  `invalidFrame` unless both angles are in (0, 180), `invalidLatitude`, region errors, and `tooManyPanels` above 500 panels.
  A plan with 101…500 panels is returned with `MosaicWarning.tooManyPanels`.
- **Geometry.** Rows are circles of latitude about the plan's pole (celestial pole turned by the position angle / zenith); every frame is a
  rectilinear (gnomonic) view whose top edge points to that pole, so its edges are great-circle arcs and `corners` are exact. Rows are spaced
  by `(1 − overlap) × frame height` along the central meridian and centred on the region. **Columns per row are computed from the row's real
  width on the sphere**: the narrowest longitude width the frame has within the row's band of latitudes (5 samples; a frame covers less
  longitude on the side of its band nearer the equator of the grid) sets the step `(1 − overlap) × that width`; the columns are centred.
  A full circle (azimuth span 360, or a polar region) is a ring of equally spaced frames that wraps around.
- **Overlap** is the fraction of the frame that neighbouring panels share: along the row at the row's narrowest latitude, between rows on
  the central meridian (angular, not image-plane: 25 % of a 54° frame is a 13.5° strip). `achievedOverlap` is the smallest value found; it is
  above the request for full rings (360° does not divide into steps of 75 %) and when the next point applies.
- **Verification.** The frame edges are great circles, which bow away from circles of latitude: with wide lenses the top/bottom row leaves
  slivers at the region's edges. Each plan is checked on a 41 × 61 lattice of the region (edges and corners included); if a point is outside
  every frame, the planner adds `0.02` of overlap and extends the grid by the same fraction of a frame and tries again (up to 20 times, at most
  0.4 of a frame). `coverage.coveredFraction` is 1.0 for every case in the tests; if the attempts run out the plan carries
  `MosaicWarning.overlapNotMet(coveredFraction:)`.
- **Shooting order** (`shootingOrder`, `panel.shootingPosition`, `orderedPanels`): a serpentine through the rows. *Equatorial*: it starts at the
  corner panel that **sets first** — key = right ascension relative to the region centre + the hour angle at which that declination sets for
  `observerLatitude` (cos H = −tan φ tan δ; circumpolar = 180°) — so from the northern hemisphere it starts at the south-west corner; with
  `observerLatitude: nil` the stars are taken to set at 90° (the western end first). *Horizontal*: the left end of the bottom row, so panoramas
  read left to right (increasing azimuth), then back along the next row up. `reverseOrder: true` reverses the list ("start at the other end").
- `recommendedOrientation` plans both orientations and returns the one with fewer panels (landscape on a tie or if neither fits).

## MosaicGuidance

```swift
public struct MosaicPanelPointing: Hashable, Sendable {
    public let panelIndex: Int
    public let direction: HorizontalCoordinate        // panel centre → target of GuidanceTracker.update
    public let corners: [HorizontalCoordinate]        // top left, top right, bottom right, bottom left
    public let roll: Double                           // degrees (−180, 180]; see below
    public let isAboveHorizon: Bool                   // centre altitude > 0
    public let lowestAltitude: Double                 // lowest of centre and corners
}
public enum MosaicGuidance {
    public static func pointing(of panel: MosaicPanel, in plan: MosaicPlan, frame: SkyFrame, refraction: Bool = true) -> MosaicPanelPointing
    public static func pointings(for plan: MosaicPlan, frame: SkyFrame, refraction: Bool = true) -> [MosaicPanelPointing]
    public static func roll(forward: Vector3, up: Vector3) -> Double
    public static func parallacticAngle(hourAngle: Double, declination: Double, latitude: Double) -> Double
}
```
```kotlin
data class MosaicPanelPointing internal constructor(val panelIndex: Int, val direction: HorizontalCoordinate,
        val corners: List<HorizontalCoordinate>, val roll: Double, val isAboveHorizon: Boolean, val lowestAltitude: Double)
object MosaicGuidance {
    fun pointing(panel: MosaicPanel, plan: MosaicPlan, frame: SkyFrame, refraction: Boolean = true): MosaicPanelPointing
    fun pointings(plan: MosaicPlan, frame: SkyFrame, refraction: Boolean = true): List<MosaicPanelPointing>
    fun roll(forward: Vector3, up: Vector3): Double
    fun parallacticAngle(hourAngle: Double, declination: Double, latitude: Double): Double
}
```
- Equatorial panels go through `SkyFrame.horizontal(j2000:…)` (precession, nutation, aberration, optional refraction); horizontal panels
  are returned as planned. The result is for the instant of `frame`; equatorial plans have to be refreshed as time passes.
- **`roll`** is the turn of the camera about its optical axis that keeps the panel's edges aligned with the plan: how far the top of the
  picture must lean **clockwise as seen through the camera** from straight up so that it points to the plan's pole. Same sign convention as
  `PointingTestSupport.cameraFrame(boresight:rollDegrees:)`. Equatorial plan with position angle 0: the parallactic angle (positive west of
  the meridian), i.e. the frame's top edge is toward the north celestial pole and the rows follow the declination circle. Horizontal plan: 0.
  With a position angle θ the roll is `parallactic − θ`. It describes the picture, so it is the same for landscape and portrait; it is 0
  (not an error) for a panel at the zenith or exactly at the pole.
- `roll(forward:up:)` computes the same angle for a camera whose boresight and picture-top are known (ENU vectors, e.g. `PointingSolution`'s
  `boresightVector` and `screenUpVector`): `delta = normalizeSignedDegrees(panelRoll − cameraRoll)`, positive = turn clockwise.
- `parallacticAngle`: Meeus ch. 14, written `atan2(sin H cos φ, sin φ cos δ − cos φ sin δ cos H)`, no singularity at the poles.

## MilkyWayPanorama

```swift
public struct MilkyWayPoint: Hashable, Sendable { public let galacticLongitude: Double; public let horizontal: HorizontalCoordinate }
public struct MilkyWayArch: Hashable, Sendable {
    public let path: [MilkyWayPoint]            // galactic plane (b = 0) above minimumAltitude, ONE connected stretch, increasing longitude
    public let highestPoint: MilkyWayPoint?     // analytic maximum of the plane; nil if the path is empty
    public let inclination: Double              // angle between plane and horizon [0, 90] = altitude of the top of a full arch
    public let core: HorizontalCoordinate       // galactic centre (l = b = 0), may be below the horizon
    public let coreTilt: Double?                // angle between the plane and the horizontal direction at the core [0, 90]; nil near zenith/nadir
    public let minimumAltitude: Double
    public func panoramaRegion(minimumAltitude: Double = 5, margin: Double = 5) -> MosaicRegion?
}
public struct MilkyWayMoment: Hashable, Sendable { public let date: Date; coreAltitude: Double; coreAzimuth: Double; coreTilt: Double?; inclination: Double }
public struct MilkyWayBestTimes: Hashable, Sendable { public let coreHighest: MilkyWayMoment?; mostHorizontal: MilkyWayMoment?; mostVertical: MilkyWayMoment? }
public enum MilkyWayPanorama {
    public static let defaultLongitudeStep = 1.0, defaultPanoramaMinimumAltitude = 5.0, defaultPanoramaMargin = 5.0
    public static func arch(frame: SkyFrame, minimumAltitude: Double = 0, longitudeStep: Double = 1, refraction: Bool = true) -> MilkyWayArch
    public static func bestTimes(summary: NightSummary, darkness: DarknessKind = .astronomical, minimumCoreAltitude: Double = 10) -> MilkyWayBestTimes
    public static func bestTimes(observer: Observer, intervals: [DateInterval], minimumCoreAltitude: Double = 10) -> MilkyWayBestTimes
}
```
```kotlin
data class MilkyWayPoint internal constructor(val galacticLongitude: Double, val horizontal: HorizontalCoordinate)
data class MilkyWayArch internal constructor(val path: List<MilkyWayPoint>, val highestPoint: MilkyWayPoint?, val inclination: Double,
        val core: HorizontalCoordinate, val coreTilt: Double?, val minimumAltitude: Double) {
    fun panoramaRegion(minimumAltitude: Double = 5.0, margin: Double = 5.0): MosaicRegion?
}
data class MilkyWayMoment internal constructor(val date: Instant, val coreAltitude: Double, val coreAzimuth: Double, val coreTilt: Double?, val inclination: Double)
data class MilkyWayBestTimes internal constructor(val coreHighest: MilkyWayMoment?, val mostHorizontal: MilkyWayMoment?, val mostVertical: MilkyWayMoment?)
object MilkyWayPanorama {
    const val defaultLongitudeStep: Double; const val defaultPanoramaMinimumAltitude: Double; const val defaultPanoramaMargin: Double
    fun arch(frame: SkyFrame, minimumAltitude: Double = 0.0, longitudeStep: Double = 1.0, refraction: Boolean = true): MilkyWayArch
    fun bestTimes(summary: NightSummary, darkness: DarknessKind = ASTRONOMICAL, minimumCoreAltitude: Double = 10.0): MilkyWayBestTimes
    fun bestTimes(observer: Observer, intervals: List<DateInterval>, minimumCoreAltitude: Double = 10.0): MilkyWayBestTimes
}
```
- **Definitions.** *Inclination* = angle between the galactic plane and the horizon plane = the altitude reached by the top of the arch
  (geometric). *Core tilt* = the angle between the galactic plane's direction at the galactic centre (l = 0, b = 0) and the horizontal
  direction there: 0° = the plane runs along the horizon past the core (flat, as in spring pre-dawn), 90° = it stands upright through the core
  (autumn evenings; also exactly the moments when the plane passes through the zenith).
- `arch`: 360 samples (`longitudeStep` 0.25…10°; NaN/≤0 → 1°), `minimumAltitude` clamped to ±90 (NaN → 0). `highestPoint` is the analytic top
  of the plane, not a sample; `path` ends are within one sample of the horizon.
- `panoramaRegion` returns the **horizontal box for `MosaicPlanner.plan`**: azimuth from the arch's smallest to its largest azimuth *followed
  along the path* (correct for arches through the zenith), altitude from its lowest to highest point above `minimumAltitude`, both widened by
  `margin` (0…45°) and limited to the sky; at least 1° × 1°. An arch above the horizon is always 180° of azimuth, so with the defaults the box is
  about 170–190° wide: use a wide lens, portrait orientation, or raise `minimumAltitude` to narrow it. nil when nothing is above the minimum.
- `bestTimes`: samples every 10 minutes within the given intervals (≤ 5000 per interval), refines by golden-section search; a moment only counts
  while the core is ≥ `minimumCoreAltitude` (10°, as `MilkyWayCoreVisibility.minimumAltitude`). `coreHighest` = greatest core altitude;
  `mostHorizontal` = smallest tilt; `mostVertical` = largest tilt. All nil when the core never gets that high (winter nights, polar regions).
  With a `NightSummary` use `.astronomical` (all dark hours) or `.moonFree`; `coreHighest` equals `summary.milkyWayCore.bestTime` to within
  seconds when the Moon does not interfere (the core is taken at l = b = 0, 0.07° from Sgr A*).

## StarTrailPlanner (PhotoTools)

```swift
public enum StarTrailError: Error, Hashable, Sendable {
    case invalidDuration(seconds: Double), invalidDeclination(degrees: Double), invalidArc(value: Double), noMotionAtThePole
    case durationTooLong(seconds: Double), invalidExposure(seconds: Double), invalidGap(seconds: Double)
    case tooManyFrames(count: Int, limit: Int), invalidFrame(horizontal: Double, vertical: Double)
}
public struct StarTrail: Hashable, Sendable {
    public let duration: Double, declination: Double
    public let rotationDegrees: Double          // angle about the pole = 15.041°/h × time, same for every star: the arc angle on the circles
    public let arcLengthDegrees: Double         // on the sky = rotation × cos(declination)
    public let arcLengthPixels: Double          // at the image scale of the frame centre (4 µm pixels if the setup has none)
    public let radiusFromPoleDegrees: Double    // 90° − |declination|: radius of the star's circle
    public let usedDefaultPixelPitch: Bool
}
public struct StarTrailStackPlan: Hashable, Sendable {
    public let totalDuration: Double, estimate: ExposureEstimate, exposureSeconds: Double, gapSeconds: Double
    public let frameCount: Int, actualDuration: Double, lightSeconds: Double
    public let exposureTrailPixels: Double, gapPixels: Double, trail: StarTrail
}
public struct StarTrailPole: Hashable, Sendable { public let hemisphere: PolarAlignment.Hemisphere; public let direction: HorizontalCoordinate; public var isAboveHorizon: Bool { get } }
public struct StarTrailPolePosition: Hashable, Sendable { public let x: Double, y: Double, separation: Double; public let isInFrame: Bool }
public enum StarTrailPlanner {
    public static let rotationRateDegreesPerHour = 15.041      // = ExposureCalculator.siderealRateArcsecondsPerSecond
    public static let maximumDurationSeconds = 86_400.0, maximumExposureSeconds = 3_600.0, defaultGapSeconds = 1.0, maximumGapSeconds = 60.0
    public static let maximumFrameCount = 100_000
    public static func trail(duration: Double, declination: Double, setup: LensSetup) throws -> StarTrail
    public static func duration(forArcDegrees arc: Double, declination: Double) throws -> Double
    public static func duration(forArcPixels pixels: Double, declination: Double, setup: LensSetup) throws -> Double
    public static func stackingPlan(totalDuration: Double, declination: Double?, setup: LensSetup, rule: ExposureRule = .npf,
                                    exposureSeconds: Double? = nil, gapSeconds: Double = defaultGapSeconds) throws -> StarTrailStackPlan
    public static func pole(frame: SkyFrame, hemisphere: PolarAlignment.Hemisphere? = nil, refraction: Bool = true) -> StarTrailPole
    public static func polePosition(pole: HorizontalCoordinate, pointing: HorizontalCoordinate, frameSize: FrameSize, roll: Double = 0) throws -> StarTrailPolePosition?
}
```
```kotlin
sealed class StarTrailError(message: String) : Exception(message) {
    data class InvalidDuration(val seconds: Double); InvalidDeclination(val degrees: Double); InvalidArc(val value: Double)
    data object NoMotionAtThePole
    data class DurationTooLong(val seconds: Double); InvalidExposure(val seconds: Double); InvalidGap(val seconds: Double)
    data class TooManyFrames(val count: Int, val limit: Int); InvalidFrame(val horizontal: Double, val vertical: Double)
}
data class StarTrail internal constructor(val duration: Double, val declination: Double, val rotationDegrees: Double, val arcLengthDegrees: Double,
        val arcLengthPixels: Double, val radiusFromPoleDegrees: Double, val usedDefaultPixelPitch: Boolean)
data class StarTrailStackPlan internal constructor(val totalDuration: Double, val estimate: ExposureEstimate, val exposureSeconds: Double,
        val gapSeconds: Double, val frameCount: Int, val actualDuration: Double, val lightSeconds: Double, val exposureTrailPixels: Double,
        val gapPixels: Double, val trail: StarTrail)
data class StarTrailPole internal constructor(val hemisphere: PolarAlignment.Hemisphere, val direction: HorizontalCoordinate) { val isAboveHorizon: Boolean }
data class StarTrailPolePosition internal constructor(val x: Double, val y: Double, val separation: Double, val isInFrame: Boolean)
object StarTrailPlanner {
    const val rotationRateDegreesPerHour: Double; const val maximumDurationSeconds: Double; const val maximumExposureSeconds: Double
    const val defaultGapSeconds: Double; const val maximumGapSeconds: Double; const val maximumFrameCount: Int
    fun trail(duration: Double, declination: Double, setup: LensSetup): StarTrail
    fun duration(arc: Double, declination: Double): Double                                        // Swift duration(forArcDegrees:declination:)
    fun duration(pixels: Double, declination: Double, setup: LensSetup): Double                   // Swift duration(forArcPixels:declination:setup:)
    fun stackingPlan(totalDuration: Double, declination: Double?, setup: LensSetup, rule: ExposureRule = ExposureRule.NPF,
                     exposureSeconds: Double? = null, gapSeconds: Double = defaultGapSeconds): StarTrailStackPlan
    fun pole(frame: SkyFrame, hemisphere: PolarAlignment.Hemisphere? = null, refraction: Boolean = true): StarTrailPole
    fun polePosition(pole: HorizontalCoordinate, pointing: HorizontalCoordinate, frameSize: FrameSize, roll: Double = 0.0): StarTrailPolePosition?
    internal fun practicalExposure(seconds: Double): Double
}
```
- Sidereal rate **15.041°/h** (the constant of `ExposureCalculator`), so δ = 0 over 1 h is 15.041° (the text-book "15°" rounded), δ = 60° is 7.52°.
  Pixels use `ExposureCalculator.trailLengthPixels` (small-angle image scale at the centre; trails near the edges of a wide frame are longer).
- `stackingPlan`: single exposure = `ExposureCalculator.maxExposure(rule, setup, declination)` rounded **down** to a dialable value (whole seconds from 2 s,
  tenths from 0.1 s) unless `exposureSeconds` is given; frames = ceil(total / (exposure + gap)), ≥ 1. `gapPixels` is the break between two exposures'
  trails (visible above ~2 px). `declination: nil` = equator (worst case).
- `pole`: reuses `SkyFrame.horizontalOfCelestialPole` (the polar-alignment pole). `polePosition` places the pole in a rectilinear frame:
  x, y in half-frame units (−1…+1 = edges, x to the right, y up, with `roll` as in `MosaicPanelPointing.roll`), nil if the pole is behind the camera.
  The concentric circles are centred there; a star at declination δ has angular radius `radiusFromPoleDegrees` and the arc spans `rotationDegrees`.

## TimeLapsePlanner (PhotoTools)

```swift
public enum TimeLapseError: Error, Hashable, Sendable {
    case invalidInterval(seconds: Double), invalidExposure(seconds: Double), invalidFrameRate(framesPerSecond: Double)
    case invalidTarget(value: Double), invalidMinimumGap(seconds: Double), invalidFrameSize(megabytes: Double)
    case gapTooShort(gapSeconds: Double, minimumGapSeconds: Double, minimumIntervalSeconds: Double)
    case tooManyFrames(count: Int, limit: Int)
}
public enum TimeLapseTarget: Hashable, Sendable { case clipLength(seconds: Double), shootingTime(seconds: Double), frameCount(frames: Int) }
public enum BatteryDemand: String, CaseIterable, Codable, Hashable, Sendable { case light, moderate, heavy, veryHeavy }
public struct TimeLapsePlan: Hashable, Sendable {
    public let interval: Double, exposure: Double, frameRate: Double, frameCount: Int
    public let shootingTime: Double          // frameCount × interval
    public let clipLength: Double            // frameCount / frameRate
    public let gap: Double                   // interval − exposure
    public let speedUp: Double               // interval × frameRate
    public let frameSizeMegabytes: Double?, storageMegabytes: Double?, storageGigabytes: Double?    // 1 MB = 10⁶ B, 1 GB = 1000 MB
    public let exposureCount: Int, batteryDemand: BatteryDemand, batteryNote: String
}
public enum TimeLapsePlanner {
    public static let maximumIntervalSeconds = 3_600.0, maximumFrameRate = 240.0, maximumFrameSizeMegabytes = 10_000.0
    public static let maximumFrameCount = 100_000, defaultMinimumGapSeconds = 1.0, maximumMinimumGapSeconds = 60.0
    public static func plan(interval: Double, exposure: Double, frameRate: Double, target: TimeLapseTarget, frameSizeMegabytes: Double? = nil,
                            minimumGapSeconds: Double = defaultMinimumGapSeconds) throws -> TimeLapsePlan
    public static func minimumInterval(exposure: Double, minimumGapSeconds: Double = defaultMinimumGapSeconds) throws -> Double
    public static func batteryDemand(exposureCount: Int) -> BatteryDemand
    public static func batteryNote(exposureCount: Int) -> String
}
```
```kotlin
sealed class TimeLapseError(message: String) : Exception(message) {
    data class InvalidInterval(val seconds: Double); InvalidExposure(val seconds: Double); InvalidFrameRate(val framesPerSecond: Double)
    data class InvalidTarget(val value: Double); InvalidMinimumGap(val seconds: Double); InvalidFrameSize(val megabytes: Double)
    data class GapTooShort(val gapSeconds: Double, val minimumGapSeconds: Double, val minimumIntervalSeconds: Double)
    data class TooManyFrames(val count: Int, val limit: Int)
}
sealed interface TimeLapseTarget { data class ClipLength(val seconds: Double); data class ShootingTime(val seconds: Double); data class FrameCount(val frames: Int) }
enum class BatteryDemand(val rawValue: String) { LIGHT("light"), MODERATE("moderate"), HEAVY("heavy"), VERY_HEAVY("veryHeavy"); companion object { fun fromRawValue(raw: String): BatteryDemand? } }
data class TimeLapsePlan internal constructor(val interval: Double, val exposure: Double, val frameRate: Double, val frameCount: Int, val shootingTime: Double,
        val clipLength: Double, val gap: Double, val speedUp: Double, val frameSizeMegabytes: Double?, val storageMegabytes: Double?,
        val storageGigabytes: Double?, val exposureCount: Int, val batteryDemand: BatteryDemand, val batteryNote: String)
object TimeLapsePlanner {
    const val maximumIntervalSeconds: Double; const val maximumFrameRate: Double; const val maximumFrameSizeMegabytes: Double
    const val maximumFrameCount: Int; const val defaultMinimumGapSeconds: Double; const val maximumMinimumGapSeconds: Double
    fun plan(interval: Double, exposure: Double, frameRate: Double, target: TimeLapseTarget, frameSizeMegabytes: Double? = null,
             minimumGapSeconds: Double = defaultMinimumGapSeconds): TimeLapsePlan
    fun minimumInterval(exposure: Double, minimumGapSeconds: Double = defaultMinimumGapSeconds): Double
    fun batteryDemand(exposureCount: Int): BatteryDemand
    fun batteryNote(exposureCount: Int): String
}
```
- Frames: clip length → `round(clip × fps)` (half away from zero); shooting time → `floor(time / interval)` whole intervals; frame count as given. Fewer than
  one frame → `invalidTarget`.
- **Gap check:** `gap = interval − exposure` must be ≥ `minimumGapSeconds` (the camera's write time, default 1 s) or `gapTooShort` carries the gap, the minimum and
  the shortest working interval `exposure + minimum` (show it as the fix). `minimumInterval` gives that number up front.
- Battery thresholds are rules of thumb (≤ 300 light, ≤ 1000 moderate, ≤ 2500 heavy, more very heavy); `batteryNote` is English text for a footnote.

---------------------------------------------------------------------------------------------------------

## Notes for the UI agents

**Planner screen (inputs).** (1) Region: either *an object* (`MosaicRegion.fitting(object:margin:)`, margin slider 0…100 %, default 20 %), *a custom
equatorial rectangle* (centre RA/Dec, width, height in degrees, position angle 0…360), or *a horizontal panorama* (azimuth start/span, min/max altitude;
or `MosaicRegion.centered`). (2) The camera: the user's `LensSetup` (the frame comes from `setup.fieldOfView`). (3) Orientation toggle; use
`MosaicPlanner.recommendedOrientation` as the initial choice. (4) Overlap slider 10–50 % (`MosaicPlanner.overlapRange`), default 25 %. (5) The observer's latitude
(`observerLatitude`) so the shooting order starts at the panel that sets first. Re-plan whenever one of these changes (0.3–3 ms on a warm JVM, even for 140 panels; the first call a few times longer).
Show `plan.gridDescription`, `panelCount`, `coverage.widthDegrees × heightDegrees`, `achievedOverlap`, and the warnings (`tooManyPanels`: "N panels will take
a long time and the sky moves meanwhile"; `overlapNotMet`: "the region could not be fully covered"). `MosaicError` cases map to field errors
(`invalidWidth/Height/AzimuthSpan/AltitudeRange/Overlap/Margin`, `tooManyPanels` → "region too large for this lens"). For an object: `objectHasNoSize` → ask for a size by hand.

**Aim screen — iterating the panels.** Keep `step` (0-based) and take `plan.orderedPanels[step]`. About once a second (not per camera frame) build a `SkyFrame` for now
and call `MosaicGuidance.pointing(of: panel, in: plan, frame:)` (≈ 15 µs; `pointings(for:)` for the whole plan). Pass `pointing.direction` to
`GuidanceTracker.update(solution:target:)` as the target (`tracker.reset()` when the step changes). When the tracker reports `.onTarget` (and the roll is within the
user's tolerance) offer "Shot taken / Next": `step += 1`. Show "Panel R2C3 · 5 of 12". Skip or warn for panels with `isAboveHorizon == false` or
`lowestAltitude < 0`. For horizontal plans the directions never change; for equatorial plans they drift ~15°/h, so always recompute.
**Roll hint**: `MosaicGuidance.roll(forward: solution.boresightVector, up: solution.screenUpVector)` is the camera's roll (`up` must be the direction of the picture's top on the DSLR: the phone's top edge if the phone sits upright on the camera; if the phone is turned a quarter turn relative to the
camera, use the phone's right vector (`screenRightVector`) or its negative instead); `delta = AstroMath.normalizeSignedDegrees(pointing.roll − cameraRoll)`; show "turn the camera
clockwise/counter-clockwise by |delta|°" only when |delta| > 1°. It describes the picture, so it does not depend on landscape or portrait.

**Drawing in the Aim overlay.** Project `pointing.corners` (4 `HorizontalCoordinate`s, apparent) with the existing `PinholeProjection` and connect them in order: edges are
straight lines in a rectilinear view. Draw the panel being aimed at highlighted and the neighbours (indices within ±1 row/column, or all `pointings`) faint, numbered by
`shootingPosition + 1`; panels already shot filled/ticked. Skip a corner whose `depth ≤ 0` (behind the camera) or clip the polygon. The DSLR frame is the panel itself,
so nothing has to be scaled by the phone's own field of view.

**Drawing on the Sky chart.** Use `panel.outline(segmentsPerEdge: 8)` (great-circle edges, `MosaicPoint`s) and convert each point exactly like other chart points
(`.equatorial` → J2000 place through `SkyFrame`, `.horizontal` → az/alt); use `plan.regionOutline(pointsPerEdge: 16)` for the requested rectangle (dashed; two circles for a ring);
label panels with `panel.id`. For equatorial plans the whole picture rotates with the sky — convert at the chart's time. `plan.pole` is the point the rows circle.

**Milky Way arch screen.** `let arch = MilkyWayPanorama.arch(frame: SkyFrame(date: t, observer: o))` → draw `arch.path` as a polyline (azimuth/altitude chart or sky chart), mark
`arch.highestPoint` and `arch.core`, show `arch.inclination` and `arch.coreTilt`. "Plan a panorama": `arch.panoramaRegion()` (nil → "arch not above 5°") → `MosaicPlanner.plan(region:setup:orientation:)`
(an arch is about 180° wide: expect 5–7 portrait frames per row at 24 mm, and two rows when the arch is taller than the frame). The arch moves ≈ 15°/h: shoot a horizontal panorama within a few minutes, or re-run `arch`.
"Best time tonight": `MilkyWayPanorama.bestTimes(summary: summary)` once per summary; show the three moments with `core altitude/azimuth`, `coreTilt`, and let the user jump the chart to `moment.date`.

**Star-trail screen.** Inputs: duration (or desired arc in degrees/pixels → `duration(forArcDegrees|forArcPixels:)`), target declination (the Aim target's, or nil), the lens setup,
rule (default NPF), gap (1 s). Output: `StarTrailPlanner.stackingPlan` → "N frames of S s", trail length `plan.trail.arcLengthDegrees` and `arcLengthPixels`, `gapPixels` warning above 2 px.
Overlay: `pole(frame:)` → `polePosition(pole:pointing:frameSize:roll:)` → draw circles around that (x, y) with radius from `radiusFromPoleDegrees` and arc angle `rotationDegrees`
(draw in the same rectilinear projection as the overlay). nil position = the pole is behind the camera.

**Time-lapse screen.** Inputs: interval, exposure, frame rate, one of clip length / shooting time / frame count, optional per-frame size (MB; e.g. 25 for a 45 MP raw), the
camera's write time (default 1 s). Output: frames, shooting time, clip length, `speedUp`, gap, storage GB, `batteryNote`. On `gapTooShort` show the error with `minimumIntervalSeconds`
as the suggested interval.

---------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

| Swift | Kotlin |
|---|---|
| `MosaicRegion.equatorial(center:width:height:positionAngle:)`, `.horizontal(azimuthStart:…)` | `MosaicRegion.Equatorial(center, width, height, positionAngle)`, `.Horizontal(…)` |
| `MosaicRegion.fitting(center:size:margin:)`, `fitting(object:margin:)` | `MosaicRegion.fitting(center, size, margin)`, `fitting(obj, margin)` (`object` is a Swift-only name; Kotlin `obj`) |
| `MosaicPoint.equatorial(_)`, `.horizontal(_)`, `equatorialCoordinate` | `MosaicPoint.Equatorial(coordinate)`, `.Horizontal(coordinate)`, `equatorialCoordinate` (the Swift property cannot be called `equatorial`, it would clash with the case) |
| `MosaicWarning.tooManyPanels(count:)` | `MosaicWarning.TooManyPanels(count)` |
| `MosaicError` payload cases, `.nonFiniteInput` | sealed class `MosaicError` (an `Exception`), `data class …(value)`, `data object NonFiniteInput` |
| `MosaicPlanner.plan(region:frame:…) throws` | `plan(region, frame, …)` with `@Throws(MosaicError::class)` |
| `MosaicGuidance.pointing(of:in:frame:refraction:)`, `pointings(for:frame:refraction:)` | `pointing(panel, plan, frame, refraction)`, `pointings(plan, frame, refraction)` |
| `MilkyWayPanorama.arch(frame:…)`, `bestTimes(summary:darkness:…)`, `bestTimes(observer:intervals:…)` | same names, positional/named parameters |
| `StarTrailPlanner.duration(forArcDegrees:declination:)`, `duration(forArcPixels:declination:setup:)` | `duration(arc, declination)`, `duration(pixels, declination, setup)` |
| `TimeLapseTarget.clipLength(seconds:)`, `.frameCount(frames:)` | `TimeLapseTarget.ClipLength(seconds)`, `.FrameCount(frames)` |
| `BatteryDemand.veryHeavy` | `BatteryDemand.VERY_HEAVY` (`rawValue` `"veryHeavy"`, `fromRawValue`) |
| `StarTrailError.noMotionAtThePole` | `StarTrailError.NoMotionAtThePole` (`data object`) |
| `internal static func plan(…, maximumAttempts:)` | `internal fun plan(…, maximumAttempts)` — test seam for the `overlapNotMet` warning |

Everything else (numbers, constants, evaluation order, validation) is the same code in both languages. Not Codable/serializable: plans are computed values; persist the inputs
(`MosaicRegion` parts, `MosaicOrientation.rawValue`, overlap) and re-plan.

Known limits (by design)
- Rectilinear lenses only (as `FieldOfView`); fisheyes are not modelled. Frames wider than ~120° still plan, but the gnomonic geometry then needs a lot of overlap.
- The sag of frame edges relative to circles of latitude is handled by the coverage check, not by an analytic bound: coverage is verified on a lattice, so a sliver thinner than
  the lattice spacing (~1/60 of the region) could in principle remain. None was found: a development sweep of ~3 500 plans (lenses 4–135 mm, both orientations, regions at the poles,
  rings, position angles) all verified fully covered, and `testSweepOfLensesAndRegionsLeavesNoGaps` (≈ 200 plans, checked with a different lattice and the panels' corner quads) keeps that true.
- Equatorial plans use J2000 places and the J2000 pole as the row pole; the date's pole is up to ~0.4° away, which shows only in `roll` (the tests allow 0.5° against the parallactic
  angle of date).

## Tests

`kt/src/test/kotlin/net/kuecker/lodestar/kit/mosaic/` and `…/phototools/`; `swift/Tests/LodestarKitTests/` — the same names, inputs, expected values and tolerances in both languages.

| Suite | Tests | Covers |
|---|---|---|
| `MosaicPlannerTests` | 37 | known counts (1×1; 2×1 and 3×3 at the equator with exact centres; dec 60° `[1, 4, 5]`; pole-clamped `[2, 6]`; 24 mm FF portrait arch 3×1, spans 60…180°; 24 mm landscape 2×2; full circle 7 panels wrapping; MW core at 135 mm; M31 at 600 mm), **coverage by sampling the region** (14 regions × 2 orientations plus a ~200-plan sweep over lenses 14–135 mm, poles, position angles and horizontal boxes, independent corner-quad test), overlap measured along parallels (10/25/40 %), wide-lens gap closing and the `overlapNotMet` warning, corner geometry (diagonal = lens diagonal FOV), areas, **position angle = rigid rotation about the centre** (±0 to 1e-8), PA 90 runs rows north-south, fitting objects/catalog, orientation, serpentine order and "sets first" for ±40° latitude, `reverseOrder`, > 100 and > 500 panels, all validation errors, outline, region outline |
| `MosaicGuidanceTests` | 12 | directions = `SkyFrame`, refraction, horizontal plans level and time-independent, below-horizon flags, **roll = parallactic angle** (< 0.5°, signs), roll turns the picture top to the pole of date, parallactic angle vs Meeus and vs a finite-difference sky geometry (0.05°), PA offsets the roll, `roll(forward:up:)` round trip with `PointingTestSupport.cameraFrame`, **Aim loop**: every panel in shooting order reaches `.onTarget` with `GuidanceTracker`, speed |
| `MilkyWayPanoramaTests` | 14 | arch is the upper half of the plane (galactic latitude 0, 1° steps, ends on the horizon), inclination vs the galactic pole's altitude, highest point, core tilt vs a finite difference, empty arch, southern hemisphere, panorama box covers the arch and plans, box settings/clamps, zenith arch, `coreHighest` = `NightSummary.milkyWayCore.bestTime`, most horizontal/vertical vs a 5-minute brute force, winter nights, single instant, speed |
| `StarTrailPlannerTests` | 15 | δ = 0 over 1 h = 15.041° (15° ±0.05), δ = 60° → 7.52°, cos δ scaling, pixel lengths for 4 µm / 24 mm (34.377″/px) and 3.76 µm / 85 mm, default pitch, durations for arcs and pixels (round trips), stacking plans (NPF 9 s → 720 frames; 500 rule; dec 60°), pole altitude = latitude, pole position/roll/portrait/behind the camera, all errors |
| `TimeLapsePlannerTests` | 11 | clip/shooting-time/frame-count arithmetic (240 frames, 40 min; 360 frames, 15 s; 1680 frames), rounding, storage (360 × 25 MB = 9 GB), gap rule and `minimumInterval`, battery thresholds, validation, frame limit |

Kotlin: 89 new tests (982 in total, all green). Swift: 89 new tests (822 in total, all green). Build is warning-free in both (Kotlin `allWarningsAsErrors`; Swift 6 mode).

# Horizon — the photographer's real horizon (Swift + Kotlin) — API reference

Trees, ridges and buildings hide the sky near the horizon at a dark-sky site. The user sweeps the phone (camera along
the skyline) once; the **skyline** is stored with the observing site, and rise/set times, "visible now", the Tonight
planner and the sky views respect it.

| Piece | Swift (`LodestarKit`, folder `Horizon/`) | Kotlin (`net.kuecker.lodestar.kit.horizon`) |
|---|---|---|
| Skyline value, JSON, editing | `HorizonProfile.swift` (`AzimuthRange`, `SkylinePoint`, `HorizonProfile`) | `HorizonProfile.kt` |
| Recording a sweep | `HorizonRecorder.swift` | `HorizonRecorder.kt` |
| Visibility / rise / set / windows | `HorizonVisibility.swift` | `HorizonVisibility.kt` |
| Tonight planner with a skyline | `NightPlanner+Horizon.swift` | `NightPlannerHorizon.kt` |
| Fast "is this direction below the skyline" | `HorizonMask.swift` | `HorizonMask.kt` |
| Internal (not API) | `HorizonSkyTracks.swift` | `HorizonSkyTracks.kt` |

Imports: Swift `import LodestarKit`. Kotlin `import net.kuecker.lodestar.kit.horizon.*` — **that also brings in the
`NightPlanner.visibility(…, horizon = …)` extension overloads** (as with the planning area, they are extension functions on
the `NightPlanner` object).

Depends on Foundation, Ephemeris (`RiseSetCalculator`, internals of its search machinery) and Planning (`NightPlanner`,
`TargetVisibility`, its interval arithmetic). Nothing outside `Horizon/` was edited. Kotlin additionally reuses the internal
strict-JSON readers of `pointing/PointingSupport.kt`.

Conventions
- Angles in degrees; azimuth from true north through east, [0, 360); altitude [−90, 90]; times `Date` / `Instant`,
  intervals `DateInterval`; the sensor clock of the recorder is `Double` seconds (like `DeviceAttitude.timestamp`).
- **Skyline altitudes are apparent** (where the obstacle is seen, −10…+60°, rounded to 0.1°). Visibility compares the
  target's *geometric* altitude with `thresholdAltitude(skylineAltitude:)` (below), so refraction is handled once, in the library.
- Naming across languages as in the kit rules: Swift `struct` ↔ Kotlin `data class`, failable `init?` ↔ `companion create`,
  enums without payload ↔ `enum class` (UPPER_SNAKE, `rawValue`), `Date` ↔ `Instant`. Kotlin parameter names = Swift
  internal names; the Swift external labels are shown in the Swift block.
- Pure computation. `HorizonProfile`, `HorizonVisibility`, `HorizonMask`, `AzimuthRange` are immutable and thread safe.
  `HorizonRecorder` is mutable (Swift `struct` with `mutating`, Kotlin `class`) and not thread safe.

---------------------------------------------------------------------------------------------------------

## Quick usage

```swift
// Recording (every attitude update while the user sweeps)
var recorder = HorizonRecorder()
let solution = PointingSolution.solve(attitude: smoothed, mount: mount, alignment: alignment)
if let why = recorder.add(azimuth: solution.boresight.azimuth, altitude: solution.boresight.altitude,
                          timestamp: smoothed.timestamp, quality: headingQuality) { /* show "slow down" … */ }
progressRing.value = recorder.coverage
let recording = recorder.finish(fill: .keepBase, base: site.horizon)      // user taps Done
site.horizon = recording.profile.isFlat ? nil : recording.profile         // store with the site

// Using it
let horizon = HorizonVisibility(profile: site.horizon ?? .flat)
let moon = horizon.riseTransitSet(for: .moon, observer: observer, within: night)    // moon.rise = "clears the ridge at"
let visibilities = NightPlanner.visibility(ofFixed: places, properMotions: motions, during: summary,
                                           minimumAltitude: 20, horizon: site.horizon ?? .flat)
let hidden = HorizonMask(profile: profile)                                         // per sky frame
if hidden.isBelow(star.direction) { continue }
```

```kotlin
var recorder = HorizonRecorder()
val solution = PointingSolution.solve(smoothed, mount, alignment)
recorder.add(solution.boresight.azimuth, solution.boresight.altitude, smoothed.timestamp, quality = headingQuality)
progressRing.value = recorder.coverage
val recording = recorder.finish(UnrecordedFill.KEEP_BASE, base = site.horizon)
site = site.copy(horizon = if (recording.profile.isFlat) null else recording.profile)

val horizon = HorizonVisibility(site.horizon ?: HorizonProfile.flat)
val moon = horizon.riseTransitSet(SolarSystemBody.MOON, observer, night)
val visibilities = NightPlanner.visibility(places, motions, summary = summary, minimumAltitude = 20.0, horizon = profile)
val hidden = HorizonMask(profile)
if (hidden.isBelow(star.direction)) continue
```

---------------------------------------------------------------------------------------------------------

## HorizonProfile.swift / HorizonProfile.kt

### AzimuthRange and SkylinePoint

```swift
public struct AzimuthRange: Sendable, Hashable {
    public let start: Double                  // [0, 360)
    public let length: Double                 // [0, 360]; 360 = whole circle
    public init(start: Double, length: Double)        // normalizes start, clamps length, non-finite → 0
    public init(from start: Double, to end: Double)   // clockwise from → to; equal azimuths = empty (not full)
    public static let full: AzimuthRange
    public var end: Double { get }                     // [0, 360)
    public func contains(_ azimuth: Double) -> Bool    // any angle; ends included; NaN → false
}
public struct SkylinePoint: Sendable, Hashable { public let azimuth: Double; public let altitude: Double }
```
```kotlin
data class AzimuthRange private constructor(val start: Double, val length: Double) {
    val end: Double
    fun contains(azimuth: Double): Boolean
    companion object {
        val full: AzimuthRange
        operator fun invoke(start: Double, length: Double): AzimuthRange     // Swift init(start:length:)
        fun between(start: Double, end: Double): AzimuthRange                // Swift init(from:to:)
    }
}
data class SkylinePoint(val azimuth: Double, val altitude: Double)
```

### HorizonProfile

360 altitudes, one per whole degree of azimuth (node `n` = azimuth `n°`), linear interpolation, wrapping 359° → 0°.

```swift
public struct HorizonProfile: Sendable, Hashable, Codable, CustomStringConvertible {
    public static let binCount = 360
    public static let lowestAltitude = -10.0
    public static let highestAltitude = 60.0
    public static let formatVersion = 1
    public let altitudes: [Double]                          // 360 multiples of 0.1
    public static let flat: HorizonProfile                  // 0° everywhere
    public init?(altitudes: [Double])                       // nil unless 360 finite values in −10…+60 (after rounding to 0.1)
    public static func constant(_ altitude: Double) -> HorizonProfile     // clamped; non-finite → 0
    public var isFlat: Bool { get }
    public var maximumAltitude: Double { get }
    public var minimumAltitude: Double { get }
    public func altitude(at azimuth: Double) -> Double      // any angle, wrapped; non-finite → 0
    public func polyline(from: Double = 0, to: Double = 360, step: Double = 1) -> [SkylinePoint]
    // editing (all return a new profile, clamped to −10…+60, rounded to 0.1)
    public func raised(by delta: Double, in range: AzimuthRange) -> HorizonProfile      // negative lowers
    public func setting(to altitude: Double, in range: AzimuthRange) -> HorizonProfile
    public func replacing(_ range: AzimuthRange, with other: HorizonProfile) -> HorizonProfile
    public func maximum(with other: HorizonProfile) -> HorizonProfile                   // both sets of obstacles
    public init(from decoder: Decoder) throws               // validating
    public func encode(to encoder: Encoder) throws
}
```
```kotlin
@Serializable(with = HorizonProfileSerializer::class) @ConsistentCopyVisibility
data class HorizonProfile private constructor(val altitudes: List<Double>) {
    val isFlat: Boolean
    val maximumAltitude: Double
    val minimumAltitude: Double
    fun altitude(azimuth: Double): Double
    fun polyline(from: Double = 0.0, to: Double = 360.0, step: Double = 1.0): List<SkylinePoint>
    fun raised(delta: Double, range: AzimuthRange): HorizonProfile
    fun setting(altitude: Double, range: AzimuthRange): HorizonProfile
    fun replacing(range: AzimuthRange, other: HorizonProfile): HorizonProfile
    fun maximum(other: HorizonProfile): HorizonProfile
    companion object {
        const val binCount: Int; const val lowestAltitude: Double; const val highestAltitude: Double; const val formatVersion: Int
        val flat: HorizonProfile
        fun create(altitudes: List<Double>): HorizonProfile?       // Swift init?(altitudes:)
        fun constant(altitude: Double): HorizonProfile
    }
}
internal object HorizonProfileSerializer : KSerializer<HorizonProfile> { fun decode(element: JsonElement): HorizonProfile }
```

`polyline`: points at `from`, at every multiple of `step` in between, and at `to`; azimuths are **not wrapped**
(`from: 340, to: 380` → …, 359, 360, 361, …) so a line through north stays monotonic. The default (0…360, step 1) has
361 points and reproduces the skyline exactly; the last point equals the first. Step clamped to 0.1…45, span to 360°.
Use `HorizontalCoordinate(azimuth:altitude:)` on a point to get a direction (360 wraps to 0).

**JSON** (identical in both languages; key order not significant):
`{"version":1,"altitudeTenths":[0,0,25,31, … 360 integers …]}` — altitudes in tenths of a degree. Typical profile ≈ 1.2 KB,
flat 752 bytes, worst case 1832 bytes (all −10°). Decoding throws (Swift `DecodingError`, Kotlin `SerializationException`) for
another `version`, a length other than 360, a number that is not an integer (`12.5`; `12.0` is accepted), a value outside
−100…600, a wrong type or a missing key; unknown keys are ignored. `decode(encode(x)) == x` exactly.

---------------------------------------------------------------------------------------------------------

## HorizonRecorder.swift / HorizonRecorder.kt

Feed one sample per attitude update. Samples are binned per whole degree (bin `n` = `n ± 0.5°`, bin 0 follows bin 359);
a bin is the **median** of its (at most 25) most recent samples; re-entering a bin more than `newPassInterval` (3 s) after
its last sample starts it over (the careful second pass wins; a wobble within a few seconds only adds to the median).

```swift
public struct HorizonRecorder: Sendable {
    public struct Configuration: Sendable, Hashable {
        public let minimumQuality: Double          // [0, 1], default 0.25
        public let maximumSweepRate: Double        // °/s, default 60
        public let maximumSamplesPerBin: Int       // 1…100, default 25
        public let newPassInterval: Double         // s, default 3
        public let maximumInterpolatedGap: Int     // bins 0…30, default 6
        public static let standard: Configuration
        public init(minimumQuality: Double = 0.25, maximumSweepRate: Double = 60, maximumSamplesPerBin: Int = 25,
                    newPassInterval: Double = 3, maximumInterpolatedGap: Int = 6)   // bad values → defaults, counts clamped
    }
    public enum Rejection: String, Sendable, Hashable, CaseIterable { case invalid, lowQuality, outOfRange, tooFast }
    public enum UnrecordedFill: String, Sendable, Hashable, CaseIterable { case keepBase, interpolate }
    public struct Recording: Sendable, Hashable {
        public let profile: HorizonProfile
        public let coverage: Double                // fraction of the 360 bins with a sample
        public let gaps: [AzimuthRange]            // unrecorded stretches longer than maximumInterpolatedGap, azimuth order
        public let interpolatedBinCount: Int
        public var isComplete: Bool { get }        // gaps.isEmpty
        public init(profile: HorizonProfile, coverage: Double, gaps: [AzimuthRange], interpolatedBinCount: Int)
    }
    public let configuration: Configuration
    public init(configuration: Configuration = .standard)

    @discardableResult
    public mutating func add(azimuth: Double, altitude: Double, timestamp: Double, quality: Double? = nil) -> Rejection?

    public var acceptedCount: Int { get }
    public func rejectedCount(for reason: Rejection) -> Int
    public var sweepRate: Double? { get }          // °/s (Δazimuth · cos altitude) between the last two plausible samples
    public var lastAzimuth: Double? { get }
    public var lastAltitude: Double? { get }
    public var recordedBinCount: Int { get }
    public var coverage: Double { get }            // recordedBinCount / 360
    public func isRecorded(_ azimuth: Double) -> Bool
    public var recordedMask: [Bool] { get }        // 360 flags, index = whole degree
    public var gaps: [AzimuthRange] { get }        // what is still to sweep

    public func profile(fill: UnrecordedFill = .keepBase, base: HorizonProfile? = nil) -> HorizonProfile
    public func finish(fill: UnrecordedFill = .keepBase, base: HorizonProfile? = nil) -> Recording   // does not change the recorder
    public mutating func clear(_ range: AzimuthRange)     // forget a stretch to sweep it again
    public mutating func reset()
}
```
```kotlin
class HorizonRecorder(val configuration: Configuration = Configuration.standard) {
    @ConsistentCopyVisibility
    data class Configuration private constructor(
        val minimumQuality: Double, val maximumSweepRate: Double, val maximumSamplesPerBin: Int,
        val newPassInterval: Double, val maximumInterpolatedGap: Int,
    ) { companion object {
            val standard: Configuration
            operator fun invoke(minimumQuality: Double = 0.25, maximumSweepRate: Double = 60.0, maximumSamplesPerBin: Int = 25,
                                newPassInterval: Double = 3.0, maximumInterpolatedGap: Int = 6): Configuration } }
    enum class Rejection(val rawValue: String) { INVALID("invalid"), LOW_QUALITY("lowQuality"), OUT_OF_RANGE("outOfRange"), TOO_FAST("tooFast");
        companion object { fun fromRawValue(raw: String): Rejection? } }
    enum class UnrecordedFill(val rawValue: String) { KEEP_BASE("keepBase"), INTERPOLATE("interpolate");
        companion object { fun fromRawValue(raw: String): UnrecordedFill? } }
    data class Recording(val profile: HorizonProfile, val coverage: Double, val gaps: List<AzimuthRange>,
                         val interpolatedBinCount: Int) { val isComplete: Boolean }

    fun add(azimuth: Double, altitude: Double, timestamp: Double, quality: Double? = null): Rejection?
    val acceptedCount: Int
    fun rejectedCount(reason: Rejection): Int
    val sweepRate: Double?
    val lastAzimuth: Double?
    val lastAltitude: Double?
    val recordedBinCount: Int
    val coverage: Double
    fun isRecorded(azimuth: Double): Boolean
    val recordedMask: List<Boolean>
    val gaps: List<AzimuthRange>
    fun profile(fill: UnrecordedFill = UnrecordedFill.KEEP_BASE, base: HorizonProfile? = null): HorizonProfile
    fun finish(fill: UnrecordedFill = UnrecordedFill.KEEP_BASE, base: HorizonProfile? = null): Recording
    fun clear(range: AzimuthRange)
    fun reset()
}
```

Rules of `add` (the first reason that applies is returned, `null` = used):
1. `invalid` — azimuth, altitude or timestamp not finite.
2. `lowQuality` — `quality` finite and below `minimumQuality` (`nil`/NaN = unknown = accepted).
3. `outOfRange` — altitude outside −10…+60° (cannot be the skyline).
4. `tooFast` — |Δazimuth|·cos(mean altitude)/Δt above `maximumSweepRate` since the previous sample that passed 1–3
   (accepted or not, so a lone wild reading costs itself and the next sample, and a whip costs only the fast samples). Altitude
   jitter does not count. Samples with Δt ≤ 0 are not judged.

`finish` fills bins that got no sample: runs of ≤ `maximumInterpolatedGap` bins between two recorded bins are always linearly
interpolated; longer runs are *gaps*: they are listed in `gaps` and take the value `fill` says — `keepBase` (default): the
`base` profile's altitude, or 0° without a base; `interpolate`: a straight line between the nearest recorded bins, through
north if need be. **Partial sweeps** are therefore explicit; passing the stored profile as `base` merges a new partial sweep
into it. Nothing recorded → `base ?? flat`, `coverage` 0, `gaps == [.full]`. Gap ranges start at the lower edge of their
first bin (bins 80…95 → start 79.5°, length 16°).

Memory 72 KB (default 25 samples/bin). `add` is O(1); `finish`/`profile` ≈ 0.1 ms (fine at 10 Hz for a live preview).
Use a new recorder (or `reset()`) per recording: the sensor clock is expected to keep increasing within one.

---------------------------------------------------------------------------------------------------------

## HorizonVisibility.swift / HorizonVisibility.kt

A target is **visible** when its geometric altitude (centre; for the Moon the upper limb) reaches the *threshold* of the
skyline at its azimuth:

```
thresholdAltitude(h) = h + (−0.5667°) · R(h) / R(0)      R = standard refraction at apparent altitude max(h, 0) (Bennett/Meeus 16.3)
Sun:  threshold − 0.2666°  (upper limb)                  Moon: limb allowance (semi-diameter) added to the altitude
```
For the flat profile this is exactly `RiseSetCalculator`'s horizon (−0.5667° stars/planets/Moon limb, −0.8333° Sun), so
flat results are identical to `RiseSetCalculator` (tested to < 0.5 s; observed bit-identical). A 10° skyline needs a
star at geometric 9.911°; a 60° skyline at 59.9998°. Refraction is the standard atmosphere whatever the weather.

"Rising" = **clearing the skyline**, "setting" = **sinking behind it**. Results reuse the existing types: `AltitudeCrossing`
(`.rising`/`.setting`), `RiseTransitSet` (`rise` = first clearing, `set` = first sinking, `transit` = first upper culmination
whether hidden or not, `circumstance` `.alwaysUp` = visible throughout / `.alwaysDown` = hidden throughout) and
`[DateInterval]` windows.

```swift
public struct HorizonVisibility: Sendable, Hashable {
    public static let noMinimumAltitude = -90.0              // skyline only
    public static let fineSampleInterval = 120.0              // s; non-flat profiles. Flat: 600 s like RiseSetCalculator
    public let profile: HorizonProfile
    public init(profile: HorizonProfile)                       // builds two 361-entry tables, ≈ 10 µs
    public static func thresholdAltitude(skylineAltitude: Double) -> Double

    // at one instant
    public func margin(altitude: Double, azimuth: Double, refracted: Bool = false) -> Double     // °, ≥ 0 visible
    public func isVisible(altitude: Double, azimuth: Double, refracted: Bool = false) -> Bool
    public func isVisible(ofJ2000 position: EquatorialCoordinate, properMotion: ProperMotion? = nil,
                          observer: Observer, at date: Date) -> Bool                              // ≈ 5 µs
    public func isVisible(of body: SolarSystemBody, observer: Observer, at date: Date) -> Bool
    public func isVisible(of position: (Date) -> HorizontalCoordinate, at date: Date, refracted: Bool = false) -> Bool

    // clearing / sinking behind the skyline
    public func crossings(ofJ2000 position: EquatorialCoordinate, properMotion: ProperMotion? = nil, observer: Observer,
                          within interval: DateInterval, minimumAltitude: Double = noMinimumAltitude) -> [AltitudeCrossing]
    public func crossings(of body: SolarSystemBody, observer: Observer, within interval: DateInterval,
                          minimumAltitude: Double = noMinimumAltitude) -> [AltitudeCrossing]
    public func crossings(of position: (Date) -> HorizontalCoordinate, within interval: DateInterval, refracted: Bool = false,
                          minimumAltitude: Double = noMinimumAltitude, sampleInterval: TimeInterval? = nil,
                          tolerance: TimeInterval = RiseSetCalculator.defaultTolerance) -> [AltitudeCrossing]
    public func riseTransitSet(forJ2000 position: EquatorialCoordinate, properMotion: ProperMotion? = nil,
                               observer: Observer, within interval: DateInterval) -> RiseTransitSet
    public func riseTransitSet(for body: SolarSystemBody, observer: Observer, within interval: DateInterval) -> RiseTransitSet
    public func riseTransitSet(of position: (Date) -> HorizontalCoordinate, within interval: DateInterval, refracted: Bool = false,
                               sampleInterval: TimeInterval? = nil, tolerance: TimeInterval = RiseSetCalculator.defaultTolerance) -> RiseTransitSet

    // visible windows (sorted, disjoint, > 0 s; several intervals are merged first)
    public func visibleWindows(ofJ2000 position: EquatorialCoordinate, properMotion: ProperMotion? = nil, observer: Observer,
                               during intervals: [DateInterval], minimumAltitude: Double = noMinimumAltitude) -> [DateInterval]
    public func visibleWindows(ofJ2000 position: EquatorialCoordinate, properMotion: ProperMotion? = nil, observer: Observer,
                               within interval: DateInterval, minimumAltitude: Double = noMinimumAltitude) -> [DateInterval]
    public func visibleWindows(of body: SolarSystemBody, observer: Observer, during intervals: [DateInterval],
                               minimumAltitude: Double = noMinimumAltitude) -> [DateInterval]
    public func visibleWindows(of body: SolarSystemBody, observer: Observer, within interval: DateInterval,
                               minimumAltitude: Double = noMinimumAltitude) -> [DateInterval]
    public func visibleWindows(of position: (Date) -> HorizontalCoordinate, during intervals: [DateInterval], refracted: Bool = false,
                               minimumAltitude: Double = noMinimumAltitude, sampleInterval: TimeInterval? = nil,
                               tolerance: TimeInterval = RiseSetCalculator.defaultTolerance) -> [DateInterval]
    public func visibleWindows(of position: (Date) -> HorizontalCoordinate, within interval: DateInterval, refracted: Bool = false,
                               minimumAltitude: Double = noMinimumAltitude, sampleInterval: TimeInterval? = nil,
                               tolerance: TimeInterval = RiseSetCalculator.defaultTolerance) -> [DateInterval]
    public func visibleWindows(ofFixed positions: [EquatorialCoordinate], properMotions: [ProperMotion?] = [], observer: Observer,
                               during intervals: [DateInterval], minimumAltitude: Double = noMinimumAltitude) -> [[DateInterval]]
}
```
```kotlin
data class HorizonVisibility(val profile: HorizonProfile) {
    fun margin(altitude: Double, azimuth: Double, refracted: Boolean = false): Double
    fun isVisible(altitude: Double, azimuth: Double, refracted: Boolean = false): Boolean
    fun isVisible(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer, at: Instant): Boolean
    fun isVisible(body: SolarSystemBody, observer: Observer, at: Instant): Boolean
    fun isVisible(position: (Instant) -> HorizontalCoordinate, at: Instant, refracted: Boolean = false): Boolean

    fun crossings(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer,
                  interval: DateInterval, minimumAltitude: Double = noMinimumAltitude): List<AltitudeCrossing>
    fun crossings(body: SolarSystemBody, observer: Observer, interval: DateInterval,
                  minimumAltitude: Double = noMinimumAltitude): List<AltitudeCrossing>
    fun crossings(position: (Instant) -> HorizontalCoordinate, interval: DateInterval, refracted: Boolean = false,
                  minimumAltitude: Double = noMinimumAltitude, sampleInterval: Double? = null,
                  tolerance: Double = RiseSetCalculator.defaultTolerance): List<AltitudeCrossing>
    fun riseTransitSet(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer, interval: DateInterval): RiseTransitSet
    fun riseTransitSet(body: SolarSystemBody, observer: Observer, interval: DateInterval): RiseTransitSet
    fun riseTransitSet(position: (Instant) -> HorizontalCoordinate, interval: DateInterval, refracted: Boolean = false,
                       sampleInterval: Double? = null, tolerance: Double = RiseSetCalculator.defaultTolerance): RiseTransitSet

    fun visibleWindows(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer,
                       intervals: List<DateInterval>, minimumAltitude: Double = noMinimumAltitude): List<DateInterval>
    fun visibleWindows(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer,
                       interval: DateInterval, minimumAltitude: Double = noMinimumAltitude): List<DateInterval>
    fun visibleWindows(body: SolarSystemBody, observer: Observer, intervals: List<DateInterval>, minimumAltitude: Double = noMinimumAltitude): List<DateInterval>
    fun visibleWindows(body: SolarSystemBody, observer: Observer, interval: DateInterval, minimumAltitude: Double = noMinimumAltitude): List<DateInterval>
    fun visibleWindows(position: (Instant) -> HorizontalCoordinate, intervals: List<DateInterval>, refracted: Boolean = false,
                       minimumAltitude: Double = noMinimumAltitude, sampleInterval: Double? = null,
                       tolerance: Double = RiseSetCalculator.defaultTolerance): List<DateInterval>
    fun visibleWindows(position: (Instant) -> HorizontalCoordinate, interval: DateInterval, refracted: Boolean = false,
                       minimumAltitude: Double = noMinimumAltitude, sampleInterval: Double? = null,
                       tolerance: Double = RiseSetCalculator.defaultTolerance): List<DateInterval>
    fun visibleWindows(positions: List<EquatorialCoordinate>, properMotions: List<ProperMotion?> = emptyList(), observer: Observer,
                       intervals: List<DateInterval>, minimumAltitude: Double = noMinimumAltitude): List<List<DateInterval>>

    companion object {
        const val noMinimumAltitude: Double            // −90.0
        const val fineSampleInterval: Double           // 120.0
        fun thresholdAltitude(skylineAltitude: Double): Double
    }
}
```
Kotlin overloads share one name and differ by the type of the first parameter (`EquatorialCoordinate` / `SolarSystemBody` /
`(Instant) -> HorizontalCoordinate` / `List<EquatorialCoordinate>`).

Parameters and behaviour
- `minimumAltitude` (geometric degrees, e.g. the planner's 20°): the answer is "above the skyline **and** at least this high".
  The default `-90` asks for the skyline only. `riseTransitSet` has no minimum (it is about the skyline).
- Position functions return `HorizontalCoordinate` (geometric by default; pass `refracted: true` if you hand in apparent
  altitudes, which are then compared with the skyline as it is). The function must be continuous in time.
- `sampleInterval` (1…600 s, `nil` = automatic: 600 s flat, 120 s otherwise) and `tolerance` (event resolution, ≥ 1 ms,
  default 0.25 s) exist for the generic functions only.
- A search is: sample the margin (altitude + limb − threshold, or its minimum with `altitude − minimumAltitude`) every
  `sampleInterval`, refine each sign change with Brent's method, and look for a pair of crossings hidden between samples when a local
  extreme comes within 0.05° of the skyline (the `RiseSetCalculator` machinery itself). **Limit:** a change of visibility
  shorter than about two minutes (half a degree of sky motion) that does not come within 0.05° of a sample can be missed;
  skyline features of the profile's own 1° resolution are seen.
- Cost (warm JVM, 12 h interval): one fixed object 0.07 ms for windows, 0.08 ms for rise/transit/set; the Moon 0.2 ms;
  `isVisible(at:)` 5 µs (builds a frame); `HorizonVisibility(profile:)` 10 µs; the batch call for 700 objects and a
  night with a minimum altitude 60–240 ms (Swift release ≈ 50 ms). A call builds its own state, so any function may be
  called from any thread.

---------------------------------------------------------------------------------------------------------

## NightPlanner+Horizon.swift / NightPlannerHorizon.kt — the Tonight planner with a skyline

The same calls as `NightPlanner.visibility(…)` plus a required last parameter `horizon: HorizonProfile`; the answer is the same
`TargetVisibility`, so a screen switches by adding the argument.

```swift
extension NightPlanner {
    public static func visibility(of position: (SkyFrame) -> HorizontalCoordinate, during summary: NightSummary,
                                  darkness: DarknessKind = .astronomical, minimumAltitude: Double = defaultMinimumAltitude,
                                  horizon: HorizonProfile) -> TargetVisibility
    public static func visibility(of position: (SkyFrame) -> HorizontalCoordinate, observer: Observer, during interval: DateInterval,
                                  minimumAltitude: Double = defaultMinimumAltitude, horizon: HorizonProfile) -> TargetVisibility
    public static func visibility(of body: SolarSystemBody, during summary: NightSummary, darkness: DarknessKind = .astronomical,
                                  minimumAltitude: Double = defaultMinimumAltitude, horizon: HorizonProfile) -> TargetVisibility
    public static func visibility(of body: SolarSystemBody, observer: Observer, during interval: DateInterval,
                                  minimumAltitude: Double = defaultMinimumAltitude, horizon: HorizonProfile) -> TargetVisibility
    public static func visibility(ofFixed targets: [EquatorialCoordinate], properMotions: [ProperMotion?] = [],
                                  during summary: NightSummary, darkness: DarknessKind = .astronomical,
                                  minimumAltitude: Double = defaultMinimumAltitude, horizon: HorizonProfile) -> [TargetVisibility]
    public static func visibility(ofFixed targets: [EquatorialCoordinate], properMotions: [ProperMotion?] = [], observer: Observer,
                                  during interval: DateInterval, minimumAltitude: Double = defaultMinimumAltitude,
                                  horizon: HorizonProfile) -> [TargetVisibility]
}
```
```kotlin
fun NightPlanner.visibility(position: (SkyFrame) -> HorizontalCoordinate, summary: NightSummary, darkness: DarknessKind = ASTRONOMICAL,
                            minimumAltitude: Double = NightPlanner.defaultMinimumAltitude, horizon: HorizonProfile): TargetVisibility
fun NightPlanner.visibility(position: (SkyFrame) -> HorizontalCoordinate, observer: Observer, interval: DateInterval,
                            minimumAltitude: Double = 20.0, horizon: HorizonProfile): TargetVisibility
fun NightPlanner.visibility(body: SolarSystemBody, summary: NightSummary, darkness: DarknessKind = ASTRONOMICAL,
                            minimumAltitude: Double = 20.0, horizon: HorizonProfile): TargetVisibility
fun NightPlanner.visibility(body: SolarSystemBody, observer: Observer, interval: DateInterval, minimumAltitude: Double = 20.0,
                            horizon: HorizonProfile): TargetVisibility
fun NightPlanner.visibility(targets: List<EquatorialCoordinate>, properMotions: List<ProperMotion?> = emptyList(), summary: NightSummary,
                            darkness: DarknessKind = ASTRONOMICAL, minimumAltitude: Double = 20.0, horizon: HorizonProfile): List<TargetVisibility>
fun NightPlanner.visibility(targets: List<EquatorialCoordinate>, properMotions: List<ProperMotion?> = emptyList(), observer: Observer,
                            interval: DateInterval, minimumAltitude: Double = 20.0, horizon: HorizonProfile): List<TargetVisibility>
```
Kotlin call form (as without `horizon`, the parameters after the defaulted `properMotions` go by name):
`NightPlanner.visibility(targets, summary = summary, horizon = profile)`.

Meaning of the `TargetVisibility` fields with a skyline
- `riseTransitSet`: `rise`/`set` = first time in the **night** the target clears / sinks behind the skyline; `transit` = upper
  culmination; `circumstance` `.alwaysUp` = visible all night, `.alwaysDown` = hidden all night. Position-function overloads use
  the −0.5667° convention; the Sun and the Moon (body overloads) count by their upper limb.
- `intervalsAboveMinimum` / `hoursAboveMinimum`: the stretches of the chosen darkness in which the target is above the skyline
  **and** at least `minimumAltitude` high (the skyline only raises the bar where it is higher than `minimumAltitude`).
- `maxAltitudeDuringDarkness` / `bestTime`: the highest point **among those stretches**. A target that is never usable has no such
  stretch; the values then describe its highest point in the dark, hidden or not (what the call without `horizon` gives).
  **Filter hidden targets on `intervalsAboveMinimum.isEmpty`, not on the altitude.**
- No darkness at all → `nil` peak / `nil` best time / no intervals, but rise/transit/set are still reported. A target with a
  non-finite position never rises (as in the plain planner).
- Cost: ≈ 0.4–0.8 ms per fixed target on the JVM (700 targets through a 24 h night ≈ 0.25–0.55 s), about twice the plain
  planner; the frames of the night are shared by the whole batch.

---------------------------------------------------------------------------------------------------------

## HorizonMask.swift / HorizonMask.kt — per-frame tests for the sky views

```swift
public struct HorizonMask: Sendable {
    public let profile: HorizonProfile
    public init(profile: HorizonProfile)                                   // 1441-entry sine table, ≈ 0.05 ms
    public func isBelow(x: Double, y: Double, z: Double) -> Bool           // ENU components, any length; no allocation
    public func isBelow(_ direction: Vector3) -> Bool
    public func isBelow(azimuth: Double, altitude: Double) -> Bool         // degrees
}
```
```kotlin
class HorizonMask(val profile: HorizonProfile) {
    fun isBelow(x: Double, y: Double, z: Double): Boolean
    fun isBelow(direction: Vector3): Boolean
    fun isBelow(azimuth: Double, altitude: Double): Boolean
}
```
`isBelow` costs one `atan2` and one square root (≈ 0.1 µs), allocates nothing (Kotlin: tested), and treats a direction exactly on
the skyline as *not* below, the zenith as above, the nadir as below, non-finite or zero vectors as not below. The comparison
uses the profile's own (apparent) altitudes, so feed it directions as drawn **with refraction applied** (the
`SkySnapshotBuilder.apparent(…)` vectors); for a geometric direction near the horizon the answer can differ by the refraction (≈ 0.5°).
Resolution of the table: ¼° in azimuth (the skyline is linear between whole degrees, so the table is exact there).

---------------------------------------------------------------------------------------------------------

## Notes for the UI agents

### 1. The sweep flow (record screen)

What to feed `HorizonRecorder.add` for every attitude update (same stream as the Aim screen, 30–60 Hz):
- `azimuth`, `altitude` = `PointingSolution.solve(attitude, mount, alignment).boresight` (apply the star-alignment model if the
  user has one, so azimuth is true-north); the user holds the phone/camera so the **skyline runs through the crosshair**.
- `timestamp` = `DeviceAttitude.timestamp` (seconds, monotonic sensor clock).
- `quality` = heading accuracy mapped to [0, 1]: iOS `CMMagneticFieldCalibrationAccuracy` uncalibrated/low/medium/high →
  0 / 0.2 / 0.6 / 1; Android `SENSOR_STATUS_UNRELIABLE/ACCURACY_LOW/MEDIUM/HIGH` → 0 / 0.2 / 0.6 / 1; **1.0 in gyro-lock mode**
  (heading from the star alignment, immune to magnetic interference); `nil` if unknown. The default minimum 0.25 drops
  uncalibrated/low samples.
- Do not smooth or quantise before feeding; the median does that. Do feed the *smoothed attitude* (`AttitudeSmoother`) if the
  Aim screen already uses it.

What to show (all cheap, read every frame or at 10 Hz):
- **Progress ring / arc**: `recorder.coverage` (0…1) and `recorder.recordedMask` (360 flags; draw the recorded arcs).
- **Marker**: `lastAzimuth` / `lastAltitude` (current camera direction, even for a rejected sample).
- **Speed**: `sweepRate` (°/s). Warn above ~70 % of `configuration.maximumSweepRate` (42°/s); the add result `.tooFast` means
  samples are being dropped. A comfortable sweep is 10–25°/s (a full turn in 15–35 s).
- **Hints from the return value of `add`**: `.lowQuality` → "calibrate the compass (figure-8)"; `.outOfRange` → "point the camera
  at the skyline" (altitude above 60° or below −10°); `.tooFast` → "slow down".
- **Gaps**: `recorder.gaps` are the stretches still to sweep ("turn back to 80°–95°"); small ones (≤ 6°) fill themselves.
- **Live preview**: `recorder.profile(base: site.horizon)` at ≤ 10 Hz, drawn with `profile.polyline(...)` over the camera picture
  or as a ring.
- **Done**: `finish(fill:base:)` → if `recording.isComplete` save; otherwise offer *continue sweeping* / *keep the previous
  skyline for the rest* (`.keepBase` with `base: site.horizon`, the default) / *join the ends by a straight line* (`.interpolate`).
  A partial sweep of a known site is a merge: pass the stored profile as `base`.
- **Redo a stretch**: sweeping back over it after > 3 s replaces it automatically; `recorder.clear(range)` forgets it explicitly.
- **Fine editing** (after recording, on the stored profile): `profile.raised(by: 2, in: range)` ("raise the trees by 2°"),
  `profile.setting(to: 25, in: range)`, `profile.maximum(with: other)` (join two recordings), `profile.replacing(range, with: other)`.
  `AzimuthRange(from:to:)` is clockwise, so `from: 350, to: 10` runs through north. `profile.altitude(at:)` for a tap/cursor read-out.
- Start a **new recorder per recording** (or `reset()`); the recorder is not thread safe — feed it from the sensor thread/actor
  that owns the attitude, read it from the UI through a copy (Swift: `let snapshot = recorder`) or on the same thread.

### 2. Storing profiles per site

- Add `var horizon: HorizonProfile?` to `SavedSite` (Swift) / the site data class (Kotlin) — `nil` = flat; store `nil` when
  `profile.isFlat`. It is `Codable` / `@Serializable` with the strict validating decoder; JSON ≈ 1.2 KB typical, ≤ 1.9 KB.
  100 sites ≤ 190 KB. If settings are one JSON document in `UserDefaults`/DataStore, that is acceptable; otherwise keep
  `horizon-<siteID>.json` files and the same `Codable` type.
- Decode **leniently** like the existing `Lossy<SavedSite>`: a corrupt or future-version profile must not lose the site —
  Swift `horizon = (try? c.decodeIfPresent(HorizonProfile.self, forKey: .horizon)) ?? nil`; Kotlin: decode the field with
  `runCatching` / a nullable wrapper serializer. Old stored sites without the key already load (default `nil`).
- A skyline belongs to a *place*, not to the device GPS fix: for `LocationSource.device` offer "save this place as a site to
  record its horizon". Moving the camera mount or the tripod a few metres does not matter; a different spot does.
- Build the `HorizonVisibility` / `HorizonMask` objects from the profile when the site changes and keep them (10–50 µs each).

### 3. Which calls to switch to

| Screen | Today | With the skyline |
|---|---|---|
| **Tonight** target ranking | `NightPlanner.visibility(ofFixed:properMotions:during:darkness:minimumAltitude:)` | same call + `horizon:`; keep targets with `!intervalsAboveMinimum.isEmpty` (hours and "best at HH:MM" now respect the skyline) |
| Tonight planets / Moon / Milky Way core | `visibility(of: body …)`, `summary.moon`, `summary.milkyWayCore` | `visibility(of: body, …, horizon:)`; Moon "up" bars: `HorizonVisibility.visibleWindows(of: .moon, observer:, within: summary.night)`; core: `visibleWindows(ofJ2000: MilkyWayCoreVisibility.galacticCentre …, during: summary.astronomicalDarknessIntervals, minimumAltitude: 10)` (the planner's constant is internal; the core's J2000 place is Sgr A*, 17h 45m 40.04s, −29° 00′ 28.1″) |
| **Objects** "visible now" / "up now" | altitude ≥ 0 from `SkyFrame` | `HorizonVisibility.isVisible(ofJ2000:/of:body, observer:, at: now)` (5 µs). For a list whose refracted horizontals are already computed (`frame.horizontal(of: object, refraction: true)`): `horizon.isVisible(altitude: h.altitude, azimuth: h.azimuth, refracted: true)` — no extra astronomy |
| Objects detail rise / set | `RiseSetCalculator.riseTransitSet` | `HorizonVisibility.riseTransitSet(…)`: show "clears the skyline at HH:MM" (`.rise`) / "sinks behind the skyline at HH:MM" (`.set`); keep the flat times as "rises (flat horizon)" if wanted. "The Moon rises behind the ridge at 23:40" = `horizon.crossings(of: .moon, observer:, within: now…now+24h).first { $0.kind == .rising }`; its azimuth then: `SkyFrame(date:, observer:).horizontal(of: .moon, refraction: false).azimuth` |
| Visible windows bars | — | `visibleWindows(…, during: summary.darkness(.moonFree), minimumAltitude: 20)` |
| **Aim** screen | — | "target is behind the skyline" banner: `HorizonMask.isBelow(targetDirection)` or `HorizonVisibility.isVisible(…)`; draw the skyline over the camera view with `profile.polyline(from: az − fov/2, to: az + fov/2)` projected through `PinholeProjection` (azimuths are unwrapped, so no seam at north) |
| **Sky chart / Aim sky snapshot** | — | build `HorizonMask(profile:)` once; per star/DSO `if mask.isBelow(direction) { dim or skip }` (directions from `SkySnapshotBuilder`, i.e. refracted); draw the ground by filling under `polyline(step: 1)` mapped to the sky projection |

Calls without a site profile: pass `.flat` or keep the existing calls — flat results are identical to the existing ones.

### 4. Costs and rules of thumb

| Call | Cost |
|---|---|
| `recorder.add` | O(1), ~1 µs |
| `recorder.profile()` / `finish()` | ~0.1 ms |
| `HorizonVisibility(profile:)` | ~10 µs |
| `HorizonMask(profile:)` / `isBelow` | ~50 µs / ~0.1 µs, no allocation |
| `isVisible(… at:)` | ~5 µs |
| rise/transit/set or windows for one object, one night | ~0.1 ms (Moon ~0.2 ms) |
| `visibleWindows(ofFixed:)` 700 objects, one night | 60–240 ms JVM, ≈ 50 ms Swift release (< 1 s required) |
| `NightPlanner.visibility(ofFixed: … horizon:)` 700 targets | 0.25–0.55 s JVM, ≈ 0.3 s Swift release, ≈ 2× the plain call |

Run the planner calls off the main thread (they are pure). Do not call the per-object functions in a per-frame loop; use the
mask for per-frame questions and the batch functions for lists.

---------------------------------------------------------------------------------------------------------

## Tests

Swift `HorizonProfileTests` 21, `HorizonRecorderTests` 22, `HorizonVisibilityTests` 22, `HorizonPlannerTests` 13,
`HorizonMaskTests` 8, `HorizonPortTests` 3 (+ `HorizonTestSupport`); Kotlin the same names, plus two port-only cases
(`HorizonPortTests`: 5). Mirrored tests use the same inputs, expected values and tolerances; the synthetic sweeps use a
SplitMix64 generator with the same seeds in both languages. Kotlin-only: the mask's allocation check, strict-JSON key
tolerance, class reference semantics. Swift-only: value-copy semantics of the recorder.

Verification
- Flat profile vs `RiseSetCalculator`: 6 stars × 4 sites, 9 bodies × 3 sites × 3 dates, rise/transit/set/circumstance and all
  crossings within 0.5 s (observed 0.0 s).
- Closed form (Meeus, *Astronomical Algorithms* 2nd ed., eq. 13.6: sin h = sin φ sin δ + cos φ cos δ cos H): a star on the
  celestial equator at 6 h at latitude 0° climbs along azimuth 90° at 15.0411°/h; a 10° mountain to the east delays its rise
  by (9.911° + 0.5667°)/15.0411 = 2508 s (tested to ±2 s), a 15° mountain to the west advances its set likewise; a δ = +20°
  star (rises at azimuth 70°) by the exact `acos(sin h / cos δ)` hour-angle difference (±3 s); a mountain off its path changes
  nothing (±0.5 s). The refraction allowance is checked against Bennett (Meeus 16.3).
- Sun behind a 10° skyline at 40°N at the March equinox: delay 54.9 min (closed form ±60 s) and the first second at which a
  scan with full `SkyFrame`s puts the refracted upper limb at 10° (±3 s). The Moon behind an 8° ridge: same scan (±10 s).
- A rough skyline against a minute-by-minute scan of full frames (12 random objects × 600 instants); the planner with a skyline
  against the same scan for 120 targets; the planner with a flat profile against the plain planner (rise/set ≤ 1 s, window
  edges ≤ 2 s, hours ≤ 0.002 h, peak ≤ 0.01°).
- Performance: windows for 700 objects through a 11.4 h night (rough skyline, minimum altitude): Kotlin 60–240 ms warm
  (asserted < 1 s); Swift release ≈ 50 ms, Swift debug ≈ 0.4 s (asserted < 1 s release / < 20 s debug). The mask tests a
  million directions in 0.1 s (Swift release).

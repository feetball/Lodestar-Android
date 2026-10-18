# Planning (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.planning`. Port of `LodestarKit/Sources/LodestarKit/Planning/*.swift`
(IntervalSet, AltitudeSample, AltitudeProfile, DarknessSampler, TargetVisibility, NightPlanner, NightPlanner+Summary,
NightPlanner+Visibility, NightSummary, PolarAlignment). Depends on the Foundation and Ephemeris areas only (no Catalog).

Import with `import net.kuecker.lodestar.kit.planning.*` — **that also brings in `NightPlanner.summary(...)` and every
`NightPlanner.visibility(...)` overload**, which are extension functions on the `NightPlanner` object (Swift `extension NightPlanner`
in other files); a plain `import ...planning.NightPlanner` would miss them. Their call sites still read `NightPlanner.summary(...)`.

Conventions used below
- `public` is the default; `internal` is marked. `Companion` members are called `Foo.bar(...)`; Swift `static let/func` are
  companion (or `object`) members with the same name.
- `Date` is `java.time.Instant`, `TimeZone` is `java.time.ZoneId`, `DateInterval` and the `Instant` helpers (`addingTimeInterval`,
  `timeIntervalSince`, …) come from Foundation (`net.kuecker.lodestar.kit.foundation.*`). "Seconds" are `Double`.
- Value types are immutable `data class`es (Swift `Hashable` structs); lists are read-only `List`s in time order.
- All altitudes are geometric (no refraction), in degrees; azimuth runs from north through east, [0, 360).
- Everything here is a pure computation and shares no state, so any function may be called from any thread.

---------------------------------------------------------------------------------------------------------

## AltitudeSample.kt  (Swift `AltitudeSample.swift`)

```kotlin
data class AltitudeSample(val date: Instant, val altitude: Double, val azimuth: Double)
```

## TargetVisibility.kt  (Swift `TargetVisibility.swift`)

```kotlin
@Serializable enum class DarknessKind(val rawValue: String) {          // ASTRONOMICAL("astronomical"), MOON_FREE("moonFree")
    companion object { fun fromRawValue(raw: String): DarknessKind? }   // Swift init?(rawValue:)
}
data class TargetVisibility(
    val riseTransitSet: RiseTransitSet,                  // events in the whole night, horizon −0.5667°
    val minimumAltitude: Double,                         // the altitude intervalsAboveMinimum / hoursAboveMinimum refer to
    val maxAltitudeDuringDarkness: Double?,              // null when there is no darkness
    val bestTime: Instant?,                              // instant of maxAltitudeDuringDarkness
    val intervalsAboveMinimum: List<DateInterval>,       // in time order
) { val hoursAboveMinimum: Double }
```
`DarknessKind` serializes as `"astronomical"` / `"moonFree"` (the Swift raw values). `allCases` is `DarknessKind.entries`.

## NightSummary.kt  (Swift `NightSummary.swift`)

```kotlin
data class NightSummary(
    val observer: Observer, val night: DateInterval, val twilight: NightTwilight, val referenceDate: Instant,
    val moon: MoonSummary, val astronomicalDarknessIntervals: List<DateInterval>, val darknessIntervals: List<DateInterval>,
    val milkyWayCore: MilkyWayCoreVisibility, val planets: List<PlanetVisibility>,      // planets: Mercury … Neptune
) {
    val hasAstronomicalDarkness: Boolean
    val astronomicalDarkHours: Double
    val moonFreeDarkHours: Double
    fun darkness(kind: DarknessKind): List<DateInterval>
}
data class MoonSummary(val phase: MoonPhaseInfo, val riseTransitSet: RiseTransitSet, val upIntervals: List<DateInterval>) {
    val illuminatedFraction: Double
}
data class MilkyWayCoreVisibility(
    val riseTransitSet: RiseTransitSet, val visibleIntervals: List<DateInterval>, val moonFreeVisibleIntervals: List<DateInterval>,
    val bestTime: Instant?, val maxAltitude: Double?, val bestAzimuth: Double?,
) {
    companion object {
        const val minimumAltitude: Double                                 // 10.0
        internal val galacticCentre: EquatorialCoordinate                 // Sagittarius A*, 17h 45m 40.04s, −29° 00′ 28.1″
    }
}
data class PlanetVisibility(
    val body: SolarSystemBody, val riseTransitSet: RiseTransitSet, val visibleIntervals: List<DateInterval>,
    val maxAltitudeDuringDarkness: Double?, val magnitude: Double?,
) {
    val visibleDuringDarkness: Boolean
    companion object { const val minimumAltitude: Double }                // 10.0
}
```

## NightPlanner.kt  (Swift `NightPlanner.swift`)

```kotlin
object NightPlanner {
    const val defaultMinimumAltitude: Double                              // 20.0
    const val maximumCurveSampleCount: Int                                // 100_000
    fun altitudeCurve(observer: Observer, interval: DateInterval, step: Double = 600.0,
                      position: (SkyFrame) -> HorizontalCoordinate): List<AltitudeSample>
    fun altitudeCurve(place: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer,
                      interval: DateInterval, step: Double = 600.0): List<AltitudeSample>           // Swift altitudeCurve(ofJ2000:…)
    fun altitudeCurve(body: SolarSystemBody, observer: Observer, interval: DateInterval,
                      step: Double = 600.0): List<AltitudeSample>                                   // Swift altitudeCurve(of:…)
    internal fun curveGapCount(duration: Double, step: Double): Int
}
```
The three `altitudeCurve` overloads differ by the type of their first parameter. Call forms:
`NightPlanner.altitudeCurve(observer, interval) { frame -> frame.horizontal(place, null, false) }` (position function last, so a
trailing lambda works, with or without `step`), `NightPlanner.altitudeCurve(place, motion, observer, interval)` or
`NightPlanner.altitudeCurve(place, observer = observer, interval = interval)`, `NightPlanner.altitudeCurve(body, observer, interval)`.

## NightPlannerSummary.kt  (Swift `NightPlanner+Summary.swift`)

```kotlin
fun NightPlanner.summary(localDate: Instant, observer: Observer, timeZone: ZoneId): NightSummary     // Swift summary(nightStartingOn:…)
internal fun NightPlanner.upIntervals(crossings: List<AltitudeCrossing>, circumstance: RiseTransitSet.Circumstance,
                                      window: DateInterval): List<DateInterval>
```

## NightPlannerVisibility.kt  (Swift `NightPlanner+Visibility.swift`)

All extension functions on `NightPlanner`; `darkness` defaults to `DarknessKind.ASTRONOMICAL`, `minimumAltitude` to `NightPlanner.defaultMinimumAltitude`.

```kotlin
// one target at a time (position function) — Swift visibility(of: closure, during:/observer:during:…)
fun NightPlanner.visibility(position: (SkyFrame) -> HorizontalCoordinate, summary: NightSummary,
                            darkness: DarknessKind = ASTRONOMICAL, minimumAltitude: Double = 20.0): TargetVisibility
fun NightPlanner.visibility(position: (SkyFrame) -> HorizontalCoordinate, observer: Observer, interval: DateInterval,
                            minimumAltitude: Double = 20.0): TargetVisibility
// the Sun, the Moon or a planet — Swift visibility(of: body, during:/observer:during:…)
fun NightPlanner.visibility(body: SolarSystemBody, summary: NightSummary,
                            darkness: DarknessKind = ASTRONOMICAL, minimumAltitude: Double = 20.0): TargetVisibility
fun NightPlanner.visibility(body: SolarSystemBody, observer: Observer, interval: DateInterval,
                            minimumAltitude: Double = 20.0): TargetVisibility
// many fixed J2000 targets, one TargetVisibility per target in order — Swift visibility(ofFixed:properMotions:during:…)
fun NightPlanner.visibility(targets: List<EquatorialCoordinate>, properMotions: List<ProperMotion?> = emptyList(),
                            summary: NightSummary, darkness: DarknessKind = ASTRONOMICAL,
                            minimumAltitude: Double = 20.0): List<TargetVisibility>
fun NightPlanner.visibility(targets: List<EquatorialCoordinate>, properMotions: List<ProperMotion?> = emptyList(),
                            observer: Observer, interval: DateInterval, minimumAltitude: Double = 20.0): List<TargetVisibility>
```
The six overloads differ by the type of the first parameter and by `summary` against `observer, interval`. Because `properMotions` is
defaulted in the middle, pass the remaining arguments by name when it is omitted:
`NightPlanner.visibility(targets, summary = summary)`, `NightPlanner.visibility(targets, observer = observer, interval = window)`;
with motions all positional arguments work: `NightPlanner.visibility(targets, motions, summary)`. A `null` or missing entry of
`properMotions` means no proper motion; extra entries are ignored.

## PolarAlignment.kt  (Swift `PolarAlignment.swift`)

```kotlin
object PolarAlignment {
    @Serializable enum class Hemisphere(val rawValue: String) {          // NORTH("north"), SOUTH("south")
        companion object {
            fun fromRawValue(raw: String): Hemisphere?                   // Swift init?(rawValue:)
            fun fromLatitude(latitude: Double): Hemisphere               // Swift Hemisphere(latitude:); equator and NaN count as north
        }
    }
    @Serializable enum class ScopeView(val rawValue: String) {           // NAKED("naked"), INVERTED("inverted")
        companion object { fun fromRawValue(raw: String): ScopeView? }
    }
    internal data class ReferenceStar(val name: String, val hipparcosNumber: Int, val position: EquatorialCoordinate,
                                      val properMotion: ProperMotion)
    internal val polaris: ReferenceStar                                  // HIP 11767
    internal val sigmaOctantis: ReferenceStar                            // HIP 104382
    fun polarScopeReading(frame: SkyFrame, hemisphere: Hemisphere, view: ScopeView = ScopeView.NAKED): PolarScopeReading
    fun polarScopeReading(frame: SkyFrame, view: ScopeView = ScopeView.NAKED): PolarScopeReading   // hemisphere of the frame's observer
}
data class PolarScopeReading(
    val referenceStarName: String, val hipparcosNumber: Int, val hemisphere: PolarAlignment.Hemisphere,
    val view: PolarAlignment.ScopeView, val hourAngle: Double, val separationFromPole: Double, val angle: Double,
    val poleAltitude: Double, val poleAzimuth: Double,
) { val clockPosition: Double }                                          // angle / 30, [0, 12)
```
`Hemisphere` and `ScopeView` serialize as `"north"`/`"south"` and `"naked"`/`"inverted"`. `allCases` is `entries`.

## IntervalSet.kt  (Swift `NightPlanner.IntervalSet`, internal)

```kotlin
internal object IntervalSet {
    fun normalized(intervals: List<DateInterval>): List<DateInterval>
    fun intersection(first: List<DateInterval>, second: List<DateInterval>): List<DateInterval>
    fun subtracting(removed: List<DateInterval>, base: List<DateInterval>): List<DateInterval>      // Swift subtracting(_:from:)
    fun totalDuration(intervals: List<DateInterval>): Double                                         // Swift totalDuration(of:)
}
```

## AltitudeProfile.kt  (Swift `NightPlanner.AltitudeProfile`, internal)

```kotlin
internal class AltitudeProfile(val step: Double, val sines: DoubleArray) {
    class Peak(val offset: Double, val sine: Double)                     // Swift tuple (offset:, sine:)
    class Span(val start: Double, val end: Double)                       // Swift tuple (start:, end:)
    val duration: Double
    fun maximum(startOffset: Double, endOffset: Double): Peak            // Swift maximum(from:to:)
    fun offsets(threshold: Double): List<Span>                           // Swift offsets(atOrAbove:)
    fun sine(position: Double): Double                                   // Swift sine(atIndex:)
}
```

## DarknessSampler.kt  (Swift `NightPlanner.DarknessSampler` and `NightPlanner.DarknessProfile`, internal)

```kotlin
internal class DarknessSampler(observer: Observer, intervals: List<DateInterval>) {
    class Stretch(val interval: DateInterval, val step: Double, val frames: List<SkyFrame>)
    val stretches: List<Stretch>
    inline fun profile(sine: (SkyFrame) -> Double): DarknessProfile      // Swift profile(ofSine:); inline, so the Double is not boxed
    fun profile(body: SolarSystemBody): DarknessProfile                  // Swift profile(of:)
    companion object { const val maximumStep: Double; const val maximumGapCount: Int }   // 600.0, 10_000
}
internal class DarknessProfile(val stretches: List<Stretch>) {
    class Stretch(val interval: DateInterval, val altitude: AltitudeProfile)
    class Peak(val date: Instant, val sine: Double)                      // Swift tuple (date:, sine:)
    val darkness: List<DateInterval>
    fun maximum(queries: List<DateInterval>): Peak?                      // Swift maximum(within:)
    fun intervals(threshold: Double): List<DateInterval>                 // Swift intervals(atOrAbove:)
}
```

---------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

### Names
| Swift | Kotlin |
| --- | --- |
| `NightPlanner.summary(nightStartingOn:observer:timeZone:)` | extension `NightPlanner.summary(localDate, observer, timeZone)` |
| `NightPlanner.visibility(of:during:darkness:minimumAltitude:)` (closure / `SolarSystemBody`) | extension `visibility(position, summary, darkness, minimumAltitude)` / `visibility(body, summary, darkness, minimumAltitude)` |
| `NightPlanner.visibility(of:observer:during:minimumAltitude:)` | `visibility(position, observer, interval, minimumAltitude)` / `visibility(body, observer, interval, minimumAltitude)` |
| `NightPlanner.visibility(ofFixed:properMotions:during:darkness:minimumAltitude:)` | `visibility(targets, properMotions, summary, darkness, minimumAltitude)` — same name, overloaded on the first parameter's type |
| `NightPlanner.visibility(ofFixed:properMotions:observer:during:minimumAltitude:)` | `visibility(targets, properMotions, observer, interval, minimumAltitude)` |
| `NightPlanner.altitudeCurve(ofJ2000:properMotion:observer:interval:step:)` | `altitudeCurve(place, properMotion, observer, interval, step)` |
| `NightPlanner.altitudeCurve(of:observer:interval:step:)` | `altitudeCurve(body, observer, interval, step)` |
| `NightSummary.darkness(_ kind:)` | `darkness(kind)` |
| `PolarAlignment.Hemisphere(latitude:)` | **`PolarAlignment.Hemisphere.fromLatitude(latitude)`** (an enum class has no secondary constructors) |
| `DarknessKind.moonFree`, `.astronomical`; `Hemisphere.north/.south`; `ScopeView.naked/.inverted` | `MOON_FREE`, `ASTRONOMICAL`; `NORTH`, `SOUTH`; `NAKED`, `INVERTED` (with `rawValue` / `fromRawValue`, `@SerialName` of the raw strings) |
| `NightPlanner.IntervalSet`, `.AltitudeProfile`, `.DarknessSampler`, `.DarknessProfile` (internal, declared in extensions of `NightPlanner`) | top-level `internal` types of the package: `IntervalSet`, `AltitudeProfile`, `DarknessSampler`, `DarknessProfile` (Kotlin cannot add nested types from another file) |
| tuples `(offset:, sine:)`, `(start:, end:)`, `(date:, sine:)` of the internal types | `AltitudeProfile.Peak`, `AltitudeProfile.Span`, `DarknessProfile.Peak` |
| `AltitudeProfile.sine(atIndex position:)`, `.maximum(from:to:)`, `.offsets(atOrAbove:)` | `sine(position)`, `maximum(startOffset, endOffset)`, `offsets(threshold)` |
| `DarknessSampler.profile(ofSine:)` / `profile(of:)`, `DarknessProfile.maximum(within:)` / `intervals(atOrAbove:)` | `profile(sine)` / `profile(body)`, `maximum(queries)` / `intervals(threshold)` |
| `IntervalSet.subtracting(_:from:)`, `.totalDuration(of:)` | `subtracting(removed, base)`, `totalDuration(intervals)` |
| `Hemisphere.allCases`, `DarknessKind.allCases`, `ScopeView.allCases` | `.entries` |

### Types, access
- `internal` Swift members stay `internal` (`NightPlanner.curveGapCount`, `NightPlanner.upIntervals`, `MilkyWayCoreVisibility.galacticCentre`,
  `PolarAlignment.polaris/.sigmaOctantis/.ReferenceStar`, `IntervalSet` …); the consuming app cannot see them, the tests can.
- `AltitudeProfile.sines` is a `DoubleArray` (Swift `[Double]`) so the interpolation reads unboxed values; `DarknessSampler.profile(sine)` is `inline`
  for the same reason (a `(SkyFrame) -> Double` lambda would box every sample). Both are internal.
- `NightPlanner.summary` / `visibility` are extension functions (Swift `extension NightPlanner` in other files). The object is
  `object NightPlanner`, not an enum namespace; `NightPlanner.defaultMinimumAltitude` and `.maximumCurveSampleCount` are `const val`s.
- The data classes expose `copy` and `componentN` (Swift structs have neither); their lists should be treated as read-only.

### Behaviour kept from Swift on purpose (do not "fix")
- Altitudes and sample grids are identical: dark time is sampled at most every 600 s (at most 10 000 gaps per dark interval), curves at most
  every `step` seconds (clamped to ≥ 1 s, 600 s for a step that is not a positive finite number, at most 100 000 samples).
- A target's sine altitude is cubic-interpolated through the four nearest samples; the maximum and the threshold crossings are found by golden-section
  search and bisection (60 and 40 steps) exactly as in Swift. Transliterating `AltitudeProfile` independently (from the Swift text) and comparing it with
  the Kotlin class on 3 100 random profiles (sines, maxima over random ranges, threshold stretches: 99 200 results) gave bit-identical numbers.
- A non-finite sine counts as −1 (far below the horizon); a non-finite `minimumAltitude` becomes 20°, the rest is clamped to ±90°; a fixed target with
  non-finite coordinates never rises (`ALWAYS_DOWN`, no events, no altitude).
- `Hemisphere.fromLatitude(NaN)` and `fromLatitude(-0.0)` are north (`latitude < 0` is false), like Swift.

### Where `Instant` differs from Swift's `Date`
- An `Instant` cannot be NaN or infinite. The Foundation helpers saturate ±∞ at `Instant.MIN` / `Instant.MAX` and map NaN to a finite instant
  (the 2001 reference date). `IntervalSet` therefore treats an interval that starts or ends at `Instant.MIN` / `Instant.MAX` as "not finite" and drops it,
  which is what Swift does for an interval with an infinite or NaN end. A NaN date handed to `NightPlanner.summary` is the 2001 reference date, so the
  summary is for that date's night (Swift: an arbitrary but well-formed night).
- `Date` comparison and arithmetic go through the Foundation helpers (`addingTimeInterval`, `timeIntervalSince`), which convert through `Double`
  seconds like Swift, so all sample instants and interval ends are the ones Swift computes.

### Numerical fidelity and performance (JVM 21, one core)
- Operation order, constants and loop structures are copied from Swift (`sine(position)`, `crossingOffset`, `refinedMaximum`, `maximum`, `offsets`).
  `NightPlanner.summary` for Boulder takes 8–15 ms warm. Ranking 1 000 scattered targets with `visibility(targets, …)` through an 11-hour night takes
  50–60 ms once the JIT has warmed up (≈ 210–290 ms for the very first call): 5 ms for the 70 000 sine samples, 0.4 ms for the 70 shared `SkyFrame`s and
  the rest for the Ephemeris area's rise/transit/set search (≈ 45 µs per star). Per target the batch allocates only the `Vector3`s of
  `SkyFrame.horizontalVector`, one `DoubleArray` per dark interval and the result objects.

## Tests

`src/test/kotlin/.../planning/` — 1:1 ports of the Swift suites (same names, inputs, expected values and tolerances):
`PlanningAltitudeCurveTests` (11), `PlanningAltitudeProfileTests` (12), `PlanningIntervalSetTests` (6), `PlanningMilkyWayCoreTests` (14),
`PlanningNightSummaryTests` (22; also holds `PlanningTestSupport`, the sites and helpers, as in Swift), `PlanningPerformanceTests` (5),
`PlanningPlanetTests` (8), `PlanningVisibilityTests` (22), `PolarAlignmentFixtureTests` (3), `PolarAlignmentTests` (19 of 20, see below).
Fixtures `stars_apparent.json`, `sidereal.json` in `src/test/resources/fixtures/` (Skyfield/DE440s, loaded with snake-case keys).
`PlanningTestSupport.SeededGenerator` reproduces the Swift tests' SplitMix64 draws, including `Double.random(in:using:)` for `..<` and `...` ranges, so the
random targets and instants are the Swift ones.

Changed Swift tests (platform-specific):
- `PlanningIntervalSetTests.testNormalizedDropsIntervalsWithoutFiniteEnds` and `PlanningVisibilityTests.testUnusableIntervalsGiveNoDarknessInsteadOfFailing`
  build an interval with a NaN start. An `Instant` cannot hold NaN (the Foundation helper turns it into a finite date), so the interval starts at +∞
  (`Instant.MAX`) instead; the assertions are unchanged.
- `PlanningNightSummaryTests.testPlacesOnThePolesAndNonFiniteDatesStillGetASummary` — `Date(timeIntervalSinceReferenceDate: .nan)` becomes the reference
  date (same helper); the assertions are unchanged (they only require a 24 h ± 1 h night).
- `PlanningPerformanceTests`: budgets are the Swift release budgets times three (1.5 s for 1 000 targets, 6 s for a summary), each timed computation is run
  once before the timed run, and `withTaskGroup` becomes a six-thread `ExecutorService`.
- `PolarAlignmentTests.testAstrometryMatchesTheBundledStarCatalog` is **pending** on the Catalog area (it reads the bundled `stars.json`).

Extras (not in Swift): `PlanningApiTests` (10: enum JSON shapes and raw-value lookups, `Hemisphere.fromLatitude` for non-numbers, `IntervalSet` and the saturated
instants, sampler frame spacing and caps, non-finite samples, every documented call form of `visibility` and `altitudeCurve`, `PolarScopeReading` copies).

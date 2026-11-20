# Ephemeris (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.ephemeris`. Port of `LodestarKit/Sources/LodestarKit/Ephemeris/*.swift`
(SolarSystemBody, BodyState, Ephemeris, PlanetaryTheory, PlutoTheory, LunarTheory, Photometry, MoonPhase, RiseSetCalculator,
RiseSetProfile, RiseSetSampler, TwilightCalculator). Depends on the Foundation area.

Import with `import net.kuecker.lodestar.kit.ephemeris.*` — that also brings in the `SkyFrame` extension functions
(`state`, `horizontal`, `topocentricEquatorial`, `geocentricApparentEquatorial`), which a plain class import would miss.

Jupiter's physical ephemeris (`JupiterPhysical`), the Galilean moons (`GalileanMoons`) and the Great Red Spot (`GreatRedSpot`) live in this
package too; they are documented with the events that use them, in `API-events.md` (section "Jupiter (Great Red Spot, Galilean moons) and
planet stations").

Conventions used below
- `public` is the default; `internal` is marked. `Companion` members are listed under their class and are called
  `Foo.bar(...)`. Every Swift `static let/func` is a companion (or `object`) member with the same name.
- `Date` is `java.time.Instant`, `TimeZone` is `java.time.ZoneId`, `DateInterval` and the `Instant` helpers come from
  Foundation (`net.kuecker.lodestar.kit.foundation.*`). "Seconds" are `Double`.
- All value types are immutable `data class`es; every Swift `Codable` enum is `@Serializable` with the same raw strings.

---------------------------------------------------------------------------------------------------------

## SolarSystemBody.kt  (Swift `SolarSystemBody.swift`)

```kotlin
@Serializable enum class SolarSystemBody(val rawValue: String) {      // SUN, MOON, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE, PLUTO
    val displayName: String                                            // "Sun", "Moon", "Mercury", …, "Pluto"
    val meanRadiusKilometers: Double                                   // 695_700, 1_737.4, 2_439.7, 6_051.8, 3_389.5, 69_911, 58_232, 25_362, 24_622, 1_188.3
    val isPlanet: Boolean                                              // Mercury…Neptune and Pluto (a dwarf planet, listed with the planets)
    companion object { fun fromRawValue(raw: String): SolarSystemBody? }   // Swift init?(rawValue:)
}
```
`rawValue` is "sun", "moon", "mercury", "venus", "mars", "jupiter", "saturn", "uranus", "neptune", "pluto" (also the JSON form,
`@SerialName`). `allCases` is `SolarSystemBody.entries`; Pluto comes last, so the order of the other nine is unchanged.

## BodyState.kt  (Swift `BodyState.swift`)

```kotlin
@ConsistentCopyVisibility data class BodyState private constructor(          // constructor not public (Swift: internal init)
    val body: SolarSystemBody,
    val julianDate: JulianDate,
    val geocentricJ2000: Vector3,                     // AU, mean equatorial J2000, light-time (+ aberration except for the Moon)
    val distanceAU: Double,
    val heliocentricDistanceAU: Double?,              // null for the Sun
    val elongation: Double?,                          // degrees [0, 180]; null for the Sun
    val phaseAngle: Double?,                          // degrees [0, 180]; null for the Sun
    val illuminatedFraction: Double?,                 // [0, 1]; null for the Sun
    val apparentMagnitude: Double?,
    val angularDiameterArcseconds: Double,
    val isWaxing: Boolean?,                           // Moon only
    val eclipticLongitudeOfDate: Double,              // degrees [0, 360), true equinox of date
    val eclipticLatitudeOfDate: Double,
) {
    val distanceKilometers: Double
    companion object {
        internal operator fun invoke(body: SolarSystemBody, julianDate: JulianDate, geocentricJ2000: Vector3,
            heliocentricDistanceAU: Double?, elongation: Double?, phaseAngle: Double?, apparentMagnitude: Double?,
            isWaxing: Boolean?, ecliptic: EclipticCoordinate): BodyState       // Swift init(body:julianDate:…:ecliptic:)
    }
}
```
Equality/`hashCode` compare every field (Swift `Hashable`); there is no `copy` (like the Foundation types).

## Ephemeris.kt  (Swift `Ephemeris.swift`)

```kotlin
object Ephemeris {
    internal const val lightTimePerAU: Double                                           // days per AU
    fun state(body: SolarSystemBody, julianDate: JulianDate): BodyState                 // Swift state(of:at:)
    fun geocentricJ2000(body: SolarSystemBody, julianDate: JulianDate): Vector3         // Swift geocentricJ2000(of:at:)
    internal fun lunarElongation(julianDate: JulianDate): Double                        // Swift lunarElongation(at:)
}
fun SkyFrame.state(body: SolarSystemBody): BodyState                                     // Swift state(of:)
fun SkyFrame.horizontal(body: SolarSystemBody, refraction: Boolean = true): HorizontalCoordinate       // horizontal(of:refraction:)
fun SkyFrame.topocentricEquatorial(body: SolarSystemBody): EquatorialCoordinate                        // topocentricEquatorial(of:)
fun SkyFrame.geocentricApparentEquatorial(body: SolarSystemBody): EquatorialCoordinate                 // geocentricApparentEquatorial(of:)
```
`frame.horizontal(SolarSystemBody.SUN, false)` and `frame.state(body)` resolve next to Foundation's `SkyFrame.horizontal(j2000, …)` /
`horizontal(geocentricJ2000: Vector3, …)` members by the type of the first argument.

Sources: VSOP87B (Earth, Mercury to Neptune), Meeus chapter 47 (Moon), Meeus chapter 37 (Pluto, `PlutoTheory`, valid 1885–2099: 1.4″ against
DE440s over 1950–2049, 3.1″ in heliocentric longitude at worst). The light-time loop is shared: `planetGeometry` (VSOP87) and `plutoGeometry`
pass their heliocentric position function to one private helper; `planetState` takes the finished geometry.

## PlanetaryTheory.kt  (Swift `Ephemeris.PlanetaryTheory`, internal)

```kotlin
internal object PlanetaryTheory {
    enum class Planet { EARTH, MERCURY, VENUS, MARS, JUPITER, SATURN, URANUS, NEPTUNE }
    val termCount: Int                                                                  // 1002 (Kotlin addition, for tests)
    val tableDigest: String                                                             // SHA-256 of the tables (Kotlin addition, for tests)
    fun heliocentricJ2000(planet: Planet, julianDayTT: Double): Vector3                 // AU, mean equatorial J2000
}
internal class TableDigest { fun add(value: Int); fun add(value: Double); fun hex(): String }   // helper of the two tableDigest properties
```
The truncated VSOP87B tables (1,002 terms) are literals in one function per planet and coordinate (largest method: 2.5 KB of
bytecode, limit 64 KB), held in a private object per planet that loads on first use.

## PlutoTheory.kt  (Swift `Ephemeris.PlutoTheory`, internal)

```kotlin
internal object PlutoTheory {
    data class Position(val longitude: Double, val latitude: Double, val radius: Double)   // degrees [0, 360), degrees, AU; J2000.0 mean ecliptic and equinox
    val termCount: Int                                                                  // 43 (Kotlin addition, for tests)
    val tableDigest: String                                                             // SHA-256 of Table 37.A (Kotlin addition, for tests)
    fun heliocentricJ2000(julianDayTT: Double): Vector3                                 // AU, mean equatorial J2000, no light-time
    fun heliocentricEcliptic(centuries: Double): Position                               // Swift heliocentricEcliptic(centuries t:)
}
```
Jean Meeus, *Astronomical Algorithms* (2nd ed.), chapter 37: the 43 rows of Table 37.A (integer multipliers of the mean arguments J, S, P of
Jupiter, Saturn and Pluto; A and B of the sine and the cosine for l, b and r), flat `IntArray`/`DoubleArray` literals copied digit for digit
from `PlutoTheory.swift`. Valid 1885–2099; outside it the error grows to about 1400″ within 50 years, but the series stays finite
(r between 29.6 and 51.8 AU for any date). `tableDigest` covers the multipliers (their number, 129, then each as four bytes) and then, for longitude, latitude
and radius in that order, the number of coefficients (86) followed by each as IEEE 754 bits (big endian).

## LunarTheory.kt  (Swift `Ephemeris.LunarTheory`, internal)

```kotlin
internal object LunarTheory {
    data class Position(val longitude: Double, val latitude: Double, val distanceKilometers: Double)
    val tableDigest: String                                                             // SHA-256 of Tables 47.A/47.B (Kotlin addition, for tests)
    fun geocentricJ2000(jd: JulianDate): Vector3                                        // AU, one light-time ago
    fun geocentricEcliptic(centuries: Double): Position                                 // Swift geocentricEcliptic(centuries t:)
}
```

## Photometry.kt  (Swift `Ephemeris.Photometry`, internal in Swift — **public** here, as the task asked)

```kotlin
object Photometry {
    fun sun(distanceAU: Double): Double
    fun moon(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double
    fun planet(body: SolarSystemBody, heliocentric: Vector3, geocentric: Vector3, phaseAngle: Double, julianDate: JulianDate): Double?   // null for the Sun and the Moon
    fun mercury(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double
    fun venus(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double
    fun mars(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double
    fun jupiter(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double
    fun saturn(sunDistance: Double, earthDistance: Double, phaseAngle: Double, sunLatitude: Double, earthLatitude: Double): Double
    fun uranus(sunDistance: Double, earthDistance: Double, sunLatitude: Double, earthLatitude: Double): Double
    fun neptune(sunDistance: Double, earthDistance: Double, year: Double): Double
    fun pluto(sunDistance: Double, earthDistance: Double, phaseAngle: Double): Double  // 5 log10(r d) − 1.00 + 0.041 α, Pluto and Charon together (Binzel & Mulholland 1984; not in Mallama & Hilton)
}
```

## MoonPhase.kt  (Swift `MoonPhase.swift`)

```kotlin
@Serializable enum class MoonPhaseName(val rawValue: String) {         // NEW_MOON, WAXING_CRESCENT, FIRST_QUARTER, WAXING_GIBBOUS, FULL_MOON, WANING_GIBBOUS, LAST_QUARTER, WANING_CRESCENT
    val displayName: String                                             // "New Moon", "Waxing Crescent", …
    val symbolName: String                                              // SF Symbol name, "moonphase.new.moon", …
    companion object {
        const val principalHalfWidth: Double                            // 11.25
        fun fromElongation(elongation: Double): MoonPhaseName           // Swift init(elongation:)
        fun fromRawValue(raw: String): MoonPhaseName?                   // Swift init?(rawValue:)
    }
}
@Serializable enum class PrincipalMoonPhase(val rawValue: String) {   // NEW_MOON, FIRST_QUARTER, FULL_MOON, LAST_QUARTER
    val elongation: Double                                              // 0, 90, 180, 270
    val displayName: String
    val symbolName: String
    val phaseName: MoonPhaseName                                        // Swift `name` (clashes with Enum.name)
    companion object { fun fromRawValue(raw: String): PrincipalMoonPhase? }
}
@ConsistentCopyVisibility data class MoonPhaseInfo internal constructor(     // constructor not public (Swift: internal memberwise init)
    val date: Instant, val illuminatedFraction: Double, val phaseAngle: Double, val elongation: Double,
    val ageDays: Double, val name: MoonPhaseName,
) { val isWaxing: Boolean }
object MoonPhaseCalculator {
    const val meanSynodicMonthDays: Double                              // 29.530_588_861
    const val maximumPhaseCount: Int                                    // 1_000
    data class PhaseEvent(val kind: PrincipalMoonPhase, val date: Instant)       // NEW: the Swift tuple (kind:date:)
    fun info(date: Instant): MoonPhaseInfo                              // Swift info(at:)
    fun nextPhases(date: Instant, count: Int): List<PhaseEvent>         // Swift nextPhases(after:count:)
}
```

## RiseSetCalculator.kt  (Swift `RiseSetCalculator.swift`)

```kotlin
data class AltitudeCrossing(val time: Instant, val kind: Kind) {
    @Serializable enum class Kind(val rawValue: String) { RISING, SETTING; companion object { fun fromRawValue(raw: String): Kind? } }
}
data class AltitudeCulmination(val time: Instant, val altitude: Double)
data class RiseTransitSet(val rise: Instant?, val transit: Instant?, val set: Instant?, val transitAltitude: Double?, val circumstance: Circumstance) {
    @Serializable enum class Circumstance(val rawValue: String) {       // NORMAL("normal"), ALWAYS_UP("alwaysUp"), ALWAYS_DOWN("alwaysDown")
        companion object { fun fromRawValue(raw: String): Circumstance? }
    }
}
object RiseSetCalculator {
    const val standardHorizonAltitude: Double                           // -0.5667
    const val sunHorizonAltitude: Double                                // -0.8333
    const val maximumSampleInterval: Double                             // 600.0 s
    const val defaultTolerance: Double                                  // 0.25 s

    // generic searches (Swift crossings(of:threshold:within:sampleInterval:tolerance:) …)
    fun crossings(altitude: (Instant) -> Double, threshold: Double, interval: DateInterval,
                  sampleInterval: Double = maximumSampleInterval, tolerance: Double = defaultTolerance): List<AltitudeCrossing>
    fun upperCulminations(altitude: (Instant) -> Double, interval: DateInterval,
                          sampleInterval: Double = maximumSampleInterval, tolerance: Double = defaultTolerance): List<AltitudeCulmination>
    fun riseTransitSet(altitude: (Instant) -> Double, threshold: Double, interval: DateInterval): RiseTransitSet

    // solar-system bodies (Swift …(for:observer:within:) / …(of:observer:within:))
    fun riseTransitSet(body: SolarSystemBody, observer: Observer, interval: DateInterval): RiseTransitSet
    fun crossings(body: SolarSystemBody, observer: Observer, interval: DateInterval): List<AltitudeCrossing>
    fun upperCulminations(body: SolarSystemBody, observer: Observer, interval: DateInterval): List<AltitudeCulmination>

    // fixed positions (Swift …(forJ2000:properMotion:observer:within:) / …(ofJ2000:…))
    fun riseTransitSet(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer, interval: DateInterval): RiseTransitSet
    fun crossings(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer, interval: DateInterval): List<AltitudeCrossing>
    fun upperCulminations(position: EquatorialCoordinate, properMotion: ProperMotion? = null, observer: Observer, interval: DateInterval): List<AltitudeCulmination>

    internal fun evaluator(body: SolarSystemBody, observer: Observer): RiseSetProfile.Evaluator
}
```
The three overloads of each search differ by the type of the first parameter. With `properMotion` defaulted in the middle, call the
fixed-position forms either with all four arguments (`riseTransitSet(position, motion, observer, interval)`) or by name
(`riseTransitSet(position, observer = observer, interval = interval)`). The Catalog area's
`RiseSetCalculator.riseTransitSet(for object:…)` extension is `fun RiseSetCalculator.riseTransitSet(obj: CelestialObject, observer: Observer, interval: DateInterval)`
(the Swift enum namespace is a Kotlin `object`, so extension functions work).

## RiseSetProfile.kt  (Swift `RiseSetCalculator.Profile` / `Sample`, internal)

```kotlin
internal data class RiseSetSample(val centreAltitude: Double, val limbAllowance: Double = 0.0) { val limbAltitude: Double }
internal class RiseSetProfile(start: Double, end: Double, step: Double, evaluate: Evaluator) {
    constructor(interval: DateInterval, step: Double, evaluate: Evaluator)
    fun interface RealFunction { fun valueAt(x: Double): Double }                       // unboxed (Double) -> Double
    class Peak(val time: Double, val value: Double)                                      // Swift tuple (time:value:)
    class LowestPoint(val time: Double, val altitude: Double)                            // Swift tuple (time:altitude:)
    fun interface Evaluator {                                                            // Swift typealias (Double) -> Sample
        fun sample(time: Double): RiseSetSample
        fun centreAltitude(time: Double): Double                                         // default: sample(time).centreAltitude
        fun limbAltitude(time: Double): Double                                           // default: sample(time).limbAltitude
    }
    val times: DoubleArray
    val centreAltitudes: DoubleArray                                                     // Swift samples[i].centreAltitude
    val limbAltitudes: DoubleArray                                                       // Swift samples[i].limbAltitude
    fun crossings(threshold: Double, tolerance: Double, evaluate: Evaluator): List<AltitudeCrossing>
    fun upperCulminations(tolerance: Double, evaluate: Evaluator): List<AltitudeCulmination>
    fun lowestPoint(tolerance: Double, evaluate: Evaluator): LowestPoint
    companion object {
        const val maximumSampleCount: Int                                                // 100_000
        fun root(function: RealFunction, lower: Double, upper: Double, valueAtLower: Double, valueAtUpper: Double, tolerance: Double): Double   // Brent
        fun maximum(function: RealFunction, lower: Double, upper: Double, tolerance: Double): Peak                                              // golden section
    }
}
```

## RiseSetSampler.kt  (Swift `RiseSetCalculator.Sampler`, internal)

```kotlin
internal class RiseSetSampler(target: Target, observer: Observer) : RiseSetProfile.Evaluator {
    sealed interface Target {
        data class Body(val body: SolarSystemBody) : Target                                         // Swift .body(_)
        data class Fixed(val position: EquatorialCoordinate, val properMotion: ProperMotion?) : Target   // Swift .fixed(_, _)
    }
    override fun sample(time: Double): RiseSetSample            // Swift sample(at:); centreAltitude NaN for |time| ≥ 1e13 or non-finite
    override fun centreAltitude(time: Double): Double           // allocation-free
    override fun limbAltitude(time: Double): Double             // allocation-free
}
```
Not thread safe (it caches), like the Swift class.

## TwilightCalculator.kt  (Swift `TwilightCalculator.swift`)

```kotlin
data class NightTwilight(
    val sunset: Instant?, val civilDusk: Instant?, val nauticalDusk: Instant?, val astronomicalDusk: Instant?,
    val astronomicalDawn: Instant?, val nauticalDawn: Instant?, val civilDawn: Instant?, val sunrise: Instant?,
    val interval: DateInterval, val solarMidnight: Instant, val sunAltitudeAtMidnight: Double,
) { val astronomicalNight: DateInterval? }
object TwilightCalculator {
    const val civilAltitude: Double                                      // -6.0
    const val nauticalAltitude: Double                                   // -12.0
    const val astronomicalAltitude: Double                               // -18.0
    fun night(localDate: Instant, observer: Observer, timeZone: ZoneId): NightTwilight    // Swift night(startingOn:observer:timeZone:)
    internal fun nightInterval(date: Instant, timeZone: ZoneId): DateInterval              // Swift nightInterval(startingOn:timeZone:)
}
```

---------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

### Names
| Swift | Kotlin |
| --- | --- |
| `Ephemeris.state(of:at:)`, `Ephemeris.geocentricJ2000(of:at:)` | `Ephemeris.state(body, julianDate)`, `Ephemeris.geocentricJ2000(body, julianDate)` |
| `SkyFrame.state(of:)`, `.horizontal(of:refraction:)`, `.topocentricEquatorial(of:)`, `.geocentricApparentEquatorial(of:)` | extension functions `state(body)`, `horizontal(body, refraction = true)`, `topocentricEquatorial(body)`, `geocentricApparentEquatorial(body)` |
| `MoonPhaseName(elongation:)` | **`MoonPhaseName.fromElongation(elongation)`** |
| `PrincipalMoonPhase.name` | **`PrincipalMoonPhase.phaseName`** (`name` is `Enum.name`) |
| `MoonPhaseCalculator.info(at:)`, `.nextPhases(after:count:)` | `info(date)`, `nextPhases(date, count)`; the array of tuples `[(kind:, date:)]` is `List<MoonPhaseCalculator.PhaseEvent>` |
| `TwilightCalculator.night(startingOn:observer:timeZone:)` | `night(localDate, observer, timeZone)` |
| `RiseSetCalculator.crossings(of: closure …)`, `.crossings(of: body …)`, `.crossings(ofJ2000: …)` (same for `upperCulminations`, `riseTransitSet(of:/for:/forJ2000:)`) | one name per search, overloaded on the first parameter (`(Instant) -> Double`, `SolarSystemBody`, `EquatorialCoordinate`); parameters `altitude, threshold, interval, sampleInterval, tolerance` / `body, observer, interval` / `position, properMotion, observer, interval` |
| `LunarTheory.geocentricEcliptic(centuries t:)`, `PlutoTheory.heliocentricEcliptic(centuries t:)` | `geocentricEcliptic(centuries)`, `heliocentricEcliptic(centuries)` — the label is kept (the internal name `t` says nothing); Pluto's result is a `Position(longitude, latitude, radius)` instead of the tuple |
| `Photometry.mercury(sunDistance r:, earthDistance d:, phaseAngle α:)` (and the other planets, `moon`) | parameters keep their labels `sunDistance`, `earthDistance`, `phaseAngle` (the internal names `r`, `d`, `α` say nothing) |
| enum cases (`.rising`, `.alwaysUp`, `.newMoon`, …) | UPPER_SNAKE constants (`RISING`, `ALWAYS_UP`, `NEW_MOON`, …); `PlanetaryTheory.Planet.EARTH` … `NEPTUNE` |
| `.body(_)` / `.fixed(_, _)` of `Sampler.Target` | `Target.Body(body)` / `Target.Fixed(position, properMotion)` (unlabeled in Swift) |
| `Date` / `TimeZone` / closure `(Date) -> Double` | `Instant` / `ZoneId` / `(Instant) -> Double` |
| `JulianDate(tt:)` (tests) | `JulianDate.fromTt(tt)` (Foundation) |

### Types Swift declares inside extensions of other files
Kotlin cannot add nested types from another file, so these become top-level types of the package (same file name as in Swift):
`Ephemeris.PlanetaryTheory` → `PlanetaryTheory` (internal), `Ephemeris.PlutoTheory` → `PlutoTheory` (internal), `Ephemeris.LunarTheory` → `LunarTheory` (internal),
`Ephemeris.Photometry` → `Photometry` (**public** — Swift keeps it internal, but the task lists it among the API the app uses),
`RiseSetCalculator.Sample/.Profile/.Sampler` → `RiseSetSample` / `RiseSetProfile` / `RiseSetSampler` (internal; their nested
`Evaluator`, `Target` stay nested). Test code writes `LunarTheory.geocentricEcliptic(…)`, `Photometry.mercury(…)`,
`RiseSetSampler(RiseSetSampler.Target.Body(body), observer)` where Swift wrote `Ephemeris.LunarTheory…`, `Ephemeris.Photometry…`,
`RiseSetCalculator.Sampler(target: .body(body), …)`.

### Implementation differences (results are identical)
- `RiseSetProfile` keeps the samples as parallel `DoubleArray`s (`centreAltitudes`, `limbAltitudes`) instead of an array of `Sample`; the
  refinement loops (Brent, golden section) call `Evaluator.centreAltitude/limbAltitude` and an unboxed `RealFunction`, so they allocate nothing.
- `RiseSetSampler` writes the per-sample matrix–vector products out on plain `Double`s (same operations in the same order as the Swift
  `Matrix3`/`Vector3` code; the zero terms of the Earth-rotation matrix are dropped), remembers the last chunk and the four interpolation
  nodes, and indexes nodes with `Long` (the hourly node index exceeds `Int` for |t| > 7.7·10¹² s). A sample allocates nothing.
- `BodyState`, `MoonPhaseInfo`: constructors are not public (Swift: internal initializers), so only the library creates them.
- `MoonPhaseCalculator.nextPhases` returns an empty list for `Instant.MIN` / `Instant.MAX`: an `Instant` cannot be NaN or infinite, and the
  Foundation helpers saturate ±∞ at those two, which stand for Swift's `date.timeIntervalSince1970.isFinite == false`.
- `TwilightCalculator.nightInterval` uses `java.time` (`atStartOfDay`, `LocalTime.NOON`, `plusDays(1)`): the same noon-to-noon intervals as
  Foundation's Gregorian `Calendar` including DST gaps/overlaps (23 and 25 hour nights); java.time cannot fail, so Swift's `?? addingTimeInterval`
  fallbacks have no counterpart.
- Integer conversions: the sampler's node index is a `Long`; the other `Int(…)` conversions are of values bounded exactly as in Swift (at most
  100 000 samples, chunk index ≤ 4.6·10⁸, quarter index 0…3).

### Behaviour kept from Swift on purpose (do not "fix")
- Non-finite times give NaN results and no crossings (a sample at a non-finite time or beyond ±10¹³ s from the reference date is NaN);
  an empty or reversed interval, or a step that is not a positive finite number, gives a profile of one sample.
- `Ephemeris.state` for a NaN `JulianDate` returns a state with NaN components instead of throwing.
- The rise/set searches, thresholds (−0.5667°, −0.8333°, the Moon's upper limb), 10-minute sampling, 0.25 s tolerance and the
  "always up/down" decision (limb altitude of the first sample against the threshold) are unchanged.

### Serialization (`@Serializable`, kotlinx.serialization)
`SolarSystemBody`, `MoonPhaseName`, `PrincipalMoonPhase`, `AltitudeCrossing.Kind`, `RiseTransitSet.Circumstance` serialize as their raw
strings (`"sun"`, `"waxingCrescent"`, `"firstQuarter"`, `"rising"`, `"alwaysUp"`, …), like Swift's raw-value `Codable`. `BodyState`,
`MoonPhaseInfo`, `AltitudeCrossing`, `AltitudeCulmination`, `RiseTransitSet` and `NightTwilight` are not `Codable` in Swift and not serializable here.

### Numerical fidelity
- The 1,002 VSOP87B terms and the 120 Meeus chapter 47 rows were generated from the Swift source text by script and compared again by an
  independent script (token for token for VSOP87, row by row for Meeus): identical digits, 1,002 terms in 67 groups. The 43 rows of Meeus
  chapter 37 (Pluto) are written into both twins by `Tools/ephemeris/gen_pluto_theory.py` from `pluto_tables.py`, which records their
  comparison with four published transcriptions of the book's table (`check_pluto_theory.py` checks the series against the book's example
  and DE440s); the digest pin below covers them in Kotlin.
- An independent Python transliteration of the Swift sources (tables parsed from the Swift files, written without looking at the Kotlin)
  was compared with the Kotlin results on 405 body states (JD 1.0·10⁶ … 3.1·10⁶, i.e. the years −1970 … 3770), 200 magnitude evaluations,
  80 moon-phase infos, 14 `nextPhases` runs, 410 rise/set windows (stars with proper motion, Sun, Moon, planets, 260 of them near the
  polar circle at the solstices), 2 000 generic altitude curves (bumps and dips between samples, sinusoids, clamped steps and tolerances)
  and 60 twilight nights. Positions, magnitudes and angles differ by ≤ 6·10⁻¹⁴ (relative ≤ 3·10⁻¹⁵); every rise, set, transit, culmination
  and twilight time is bit-identical, as are all event counts and kinds; moon-phase instants differ by ≤ 1.2·10⁻⁷ s (one ulp of the date).
  The compact `EphemerisGoldenTests` keeps that comparison in the build.
- `tableDigest` (SHA-256 over the bits of every coefficient, in table order) of the three theories is pinned in `EphemerisApiTests` to the digest of
  the numbers in the Swift source files, so any later edit of a digit fails the build. `EphemerisGoldenTests` has a Pluto row, but it was
  computed by the Kotlin code itself and checked against DE440s (about 1e-4 AU per component), not by the independent transliteration;
  `EphemerisPlutoTests.testStateAtTheGoldenInstant` pins the same digits in both twins.

### Performance notes (JVM 21, warmed up, one core)
`Ephemeris.state` 9–16 µs; `geocentricJ2000` 4–9 µs; `RiseSetCalculator.riseTransitSet` for a star over 24 h 40–55 µs (29 KB allocated, almost
all of it the five six-hour `SkyFrame`s), Sun 80–100 µs, Jupiter 130–160 µs, Moon 270–285 µs; `TwilightCalculator.night` 90 µs;
`MoonPhaseCalculator.info` 105 µs; 5 000 stars (`riseTransitSet`, 24 h) 215–300 ms. A single `RiseSetSampler` sample costs 80–120 ns and
allocates nothing; Brent and golden-section searches allocate nothing per iteration.

## Tests

`src/test/kotlin/.../ephemeris/` — 1:1 ports of the Swift suites (same names, inputs, expected values and tolerances):
`EphemerisFixtureTests` (9), `EphemerisMeeusTests` (20), `EphemerisPlutoTests` (9), `EphemerisTwilightTests` (14), `MoonPhaseTests` (10),
`RiseSetCalculatorTests` (20), `RiseSetFixtureTests` (5) = 87 tests; fixtures `bodies_apparent.json` (500 cases: 50 instants × 10 bodies, Pluto
without a magnitude), `moon_phases.json`, `riseset.json` in `src/test/resources/fixtures/`.

Changed Swift tests (platform-specific):
- `EphemerisMeeusTests.testStateIsSendableHashableAndStable` — the `Sendable` assertion has no Kotlin counterpart and is dropped (a comment says so).
- `MoonPhaseTests.testNextPhasesArgumentsAndPaging` — `Date(timeIntervalSince1970: .nan)` cannot exist as an `Instant` (the Foundation helper
  maps NaN to the epoch); the assertion uses `Instant.MAX` and the saturated ±∞ instants instead.
- Foundation types differ in spelling only (`JulianDate.fromTt`, `Instant`, `ZoneId`/`ZoneOffset`, `DateComponents` → `ZonedDateTime`).

Extras (not in Swift): `EphemerisApiTests` (14: term count and table digests, Earth/Moon sanity, enum JSON shapes and raw-value lookups, the Pluto magnitude formula, sampler cache independence from the
evaluation order, sampler near its limits, Brent/golden-section, profile grid, extreme night intervals), `EphemerisGoldenTests` (3: pinned values from the
Python transliteration; the Pluto row is the Kotlin code's own output, see its comment).

# Foundation (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.foundation`. Port of `LodestarKit/Sources/LodestarKit/Foundation/*.swift`
(AstroMath, Coordinates, JulianDate, EarthOrientation, SkyFrame, Refraction) plus the Swift-compatibility
helpers every other area needs for `Date`, `DateInterval` and `min`/`max`.

Import everything with `import net.kuecker.lodestar.kit.foundation.*` — that also brings in the top-level
operator `Double.times(Vector3)` and the `Instant` extensions, which a plain class import would miss.

Conventions used below
- `public` is the default; `internal` is marked. `Companion` members are listed under their class and are called
  `Foo.bar(...)`. Every Swift `static let/func` is a companion (or `object`) member with the same name.
- All value types are immutable `data class`es with `Double` fields (no boxing, no mutation; Swift `var` fields
  became `val`, "mutation" is `copy(...)`).
- Every type that is `Codable` in Swift is `@Serializable` with the same JSON keys (details at the end).
- `Instant` is `java.time.Instant`; "seconds" are `Double`.

---------------------------------------------------------------------------------------------------------

## AstroMath.kt  (Swift `AstroMath.swift`)

```kotlin
object AstroMath {
    const val radiansPerDegree: Double
    const val degreesPerRadian: Double
    const val arcsecondsPerDegree: Double
    const val arcsecondsPerRadian: Double
    fun radians(degrees: Double): Double
    fun degrees(radians: Double): Double
    fun normalizeDegrees(degrees: Double): Double          // [0, 360); non-finite returned unchanged
    fun normalizeSignedDegrees(degrees: Double): Double    // (-180, 180]; non-finite returned unchanged
    fun <T : Comparable<T>> clamp(value: T, lower: T, upper: T): T   // Swift min(max(value, lower), upper)
    fun clamp(value: Double, lower: Double, upper: Double): Double   // added overload (no boxing); NaN stays NaN
    fun clamp(value: Int, lower: Int, upper: Int): Int               // added overload (no boxing)
    fun sinDeg(degrees: Double): Double
    fun cosDeg(degrees: Double): Double
    fun tanDeg(degrees: Double): Double
    fun asinDeg(x: Double): Double                          // x clamped to [-1, 1]
    fun acosDeg(x: Double): Double                          // x clamped to [-1, 1]
    fun atan2Deg(y: Double, x: Double): Double
    fun polynomial(x: Double, coefficients: List<Double>): Double              // c[0] + c[1]·x + …, Horner
    fun polynomial(x: Double, vararg coefficients: Double): Double             // added overload for literal coefficients
}

@Serializable data class Vector3(val x: Double, val y: Double, val z: Double) {
    val lengthSquared: Double
    val length: Double
    val normalized: Vector3                                 // Vector3.zero when the length is not > 0 (also for NaN)
    val isFinite: Boolean
    fun dot(other: Vector3): Double
    fun cross(other: Vector3): Vector3
    fun angleRadians(other: Vector3): Double                // Swift angleRadians(to:)
    fun angle(other: Vector3): Double                       // degrees; Swift angle(to:)
    operator fun plus(other: Vector3): Vector3
    operator fun minus(other: Vector3): Vector3
    operator fun unaryMinus(): Vector3
    operator fun times(scalar: Double): Vector3
    operator fun div(scalar: Double): Vector3
    override fun toString(): String                         // "(x, y, z)" like Swift's description
    companion object { val zero: Vector3 }
}
operator fun Double.times(vector: Vector3): Vector3         // top-level; Swift `s * v`

@Serializable data class Matrix3(val r0: Vector3, val r1: Vector3, val r2: Vector3) {   // row-major, rows r0…r2
    val c0: Vector3                                         // columns
    val c1: Vector3
    val c2: Vector3
    val transposed: Matrix3
    val determinant: Double
    val isFinite: Boolean
    operator fun get(row: Int, column: Int): Double         // m[row, column]; IllegalArgumentException outside 0..2
    operator fun times(v: Vector3): Vector3                 // m * v
    operator fun times(b: Matrix3): Matrix3                 // a * b
    fun orthonormalized(): Matrix3
    override fun toString(): String                         // "[(r0), (r1), (r2)]"
    companion object {
        val identity: Matrix3
        fun fromColumns(c0: Vector3, c1: Vector3, c2: Vector3): Matrix3   // Swift init(columns:_:_:)
        fun rotationX(angleRadians: Double): Matrix3        // active
        fun rotationY(angleRadians: Double): Matrix3
        fun rotationZ(angleRadians: Double): Matrix3
        fun frameRotationX(angleRadians: Double): Matrix3   // passive (SOFA R1/R2/R3)
        fun frameRotationY(angleRadians: Double): Matrix3
        fun frameRotationZ(angleRadians: Double): Matrix3
        fun rotation(axis: Vector3, angleRadians: Double): Matrix3   // identity for a zero-length axis
    }
}
```
Constructors: `Vector3(x, y, z)` replaces both `Vector3(x:y:z:)` and `Vector3(_:_:_:)`; `Matrix3(r0, r1, r2)` replaces
`Matrix3(rows:_:_:)`.

## Coordinates.kt  (Swift `Coordinates.swift`)

"Constructor" below means a companion `operator fun invoke` over a private primary constructor
(`@ConsistentCopyVisibility`): calls read exactly like constructor calls (positional or named arguments), but there
is no `copy`, no constructor reference `::EquatorialCoordinate` (write `{ EquatorialCoordinate(it) }`), and the values
are always normalized.

```kotlin
@Serializable data class EquatorialCoordinate(val rightAscension: Double, val declination: Double) {
    val rightAscensionHours: Double
    val unitVector: Vector3
    fun angularSeparation(other: EquatorialCoordinate): Double    // degrees; Swift angularSeparation(to:)
    companion object {
        operator fun invoke(rightAscension: Double, declination: Double): EquatorialCoordinate  // RA → [0,360), Dec clamped
        operator fun invoke(vector: Vector3): EquatorialCoordinate                              // zero/NaN-length → (0, 0)
        fun fromRightAscensionHours(rightAscensionHours: Double, declination: Double): EquatorialCoordinate
    }
}
@Serializable data class HorizontalCoordinate(val azimuth: Double, val altitude: Double) {
    val unitVector: Vector3                                       // ENU: x East, y North, z Up
    fun angularSeparation(other: HorizontalCoordinate): Double
    companion object {
        operator fun invoke(azimuth: Double, altitude: Double): HorizontalCoordinate
        operator fun invoke(vector: Vector3): HorizontalCoordinate                              // azimuth 0 at zenith/nadir
    }
}
@Serializable data class EclipticCoordinate(val longitude: Double, val latitude: Double) {
    val unitVector: Vector3
    companion object {
        operator fun invoke(longitude: Double, latitude: Double): EclipticCoordinate
        operator fun invoke(vector: Vector3): EclipticCoordinate
    }
}
@Serializable data class GalacticCoordinate(val longitude: Double, val latitude: Double) {
    val unitVector: Vector3
    companion object {
        operator fun invoke(longitude: Double, latitude: Double): GalacticCoordinate
        operator fun invoke(vector: Vector3): GalacticCoordinate
    }
}
@Serializable data class GeographicLocation(val latitude: Double, val longitude: Double, val elevation: Double) {
    companion object {
        val elevationRange: ClosedFloatingPointRange<Double>      // -1000.0..12000.0; use .start / .endInclusive
        operator fun invoke(latitude: Double, longitude: Double, elevation: Double = 0.0): GeographicLocation
        fun validated(latitude: Double, longitude: Double, elevation: Double = 0.0): GeographicLocation?
    }
}

object CoordinateTransforms {
    data class HourAngleDeclination(val hourAngle: Double, val declination: Double)   // NEW: the Swift tuple result
    val equatorialJ2000ToGalacticMatrix: Matrix3
    fun eclipticToEquatorial(ecliptic: EclipticCoordinate, obliquity: Double): EquatorialCoordinate
    fun equatorialToEcliptic(equatorial: EquatorialCoordinate, obliquity: Double): EclipticCoordinate
    fun equatorialToHorizontal(hourAngle: Double, declination: Double, latitude: Double): HorizontalCoordinate
    fun horizontalToEquatorial(horizontal: HorizontalCoordinate, latitude: Double): HourAngleDeclination
    internal fun localEquatorialVector(hourAngle: Double, declination: Double): Vector3
    internal fun localEquatorialToHorizontal(latitude: Double): Matrix3
    fun equatorialJ2000ToGalactic(equatorial: EquatorialCoordinate): GalacticCoordinate
    fun galacticToEquatorialJ2000(galactic: GalacticCoordinate): EquatorialCoordinate
}
```

## JulianDate.kt  (Swift `JulianDate.swift`)

```kotlin
@Serializable data class JulianDate(val ut: Double, val deltaT: Double) : Comparable<JulianDate> {
    constructor(ut: Double)                                 // Swift init(ut:): estimates ΔT
    constructor(date: Instant)                              // Swift init(date:)
    val tt: Double
    val date: Instant                                       // saturates for non-finite / out-of-range (see helpers)
    val centuriesTT: Double
    val centuriesUT: Double
    val julianYearTT: Double
    fun addingDays(days: Double): JulianDate                // ΔT re-estimated
    override fun compareTo(other: JulianDate): Int          // by ut only (Swift `<`); NaN compares equal
    companion object {
        const val j2000: Double
        const val unixEpoch: Double
        const val daysPerJulianCentury: Double
        const val secondsPerDay: Double
        fun fromTt(tt: Double): JulianDate                  // Swift init(tt:)
        fun decimalYear(julianDay: Double): Double
        fun julianDay(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Double = 0.0): Double
        fun deltaT(decimalYear: Double): Double             // parameter name kept (Swift internal name is `y`)
    }
}
object SiderealTime {
    fun greenwichMean(jd: JulianDate): Double
    fun greenwichApparent(jd: JulianDate, nutation: Nutation, trueObliquity: Double): Double
}
```

## EarthOrientation.kt  (Swift `EarthOrientation.swift`)

```kotlin
object Obliquity {
    const val j2000: Double
    fun mean(jd: JulianDate): Double
}
@Serializable data class Nutation(val longitude: Double, val obliquity: Double) {
    fun matrix(meanObliquity: Double): Matrix3
    companion object { operator fun invoke(jd: JulianDate): Nutation }   // Swift init(julianDate:)
}
object Precession {
    fun matrixFromJ2000(jd: JulianDate): Matrix3            // Swift matrixFromJ2000(to:)
}
object SolarCoordinates {
    fun meanAnomaly(jd: JulianDate): Double
    fun geometricLongitude(jd: JulianDate): Double
}
object Aberration {
    const val constantArcseconds: Double
    fun earthVelocity(jd: JulianDate): Vector3
    fun apply(direction: Vector3, earthVelocity: Vector3): Vector3          // Swift apply(to:earthVelocity:)
    fun remove(apparentDirection: Vector3, earthVelocity: Vector3): Vector3 // Swift remove(from:earthVelocity:)
}
```
(`Nutation.Term` and the 63-term table are private.)

## Refraction.kt  (Swift `Refraction.swift`)

```kotlin
@Serializable data class Atmosphere(val pressureMillibars: Double = 1010.0, val temperatureCelsius: Double = 10.0) {
    val refractionScale: Double
    companion object { val standard: Atmosphere }
}
object Refraction {
    fun refractionForTrueAltitude(trueAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double
    fun refractionForApparentAltitude(apparentAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double
    fun apparentAltitude(trueAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double
    fun trueAltitude(apparentAltitude: Double, atmosphere: Atmosphere = Atmosphere.standard): Double
}
```

## SkyFrame.kt  (Swift `SkyFrame.swift`)

```kotlin
@Serializable data class ProperMotion(val raMilliarcsecondsPerYear: Double, val decMilliarcsecondsPerYear: Double) {
    fun apply(position: EquatorialCoordinate, years: Double): EquatorialCoordinate     // Swift apply(to:years:)
    internal fun applyToUnitVector(position: EquatorialCoordinate, years: Double): Vector3  // Swift applyToUnitVector(of:years:)
    companion object { operator fun invoke(raMilliarcsecondsPerYear: Double, decMilliarcsecondsPerYear: Double): ProperMotion }  // non-finite → 0
}
@Serializable data class Observer(val location: GeographicLocation, val atmosphere: Atmosphere = Atmosphere.standard)

class SkyFrame(jd: JulianDate, val observer: Observer) {        // Swift init(julianDate:observer:)
    constructor(date: Instant, observer: Observer)               // Swift init(date:observer:)
    val date: Instant                                            // = jd.date (not the exact instant passed in)
    val julianDate: JulianDate
    val nutation: Nutation
    val meanObliquity: Double
    val trueObliquity: Double
    val greenwichApparentSiderealTime: Double
    val localApparentSiderealTime: Double
    val precessionMatrix: Matrix3
    val precessionNutationMatrix: Matrix3
    val equatorialOfDateToHorizontal: Matrix3
    val j2000ToHorizontal: Matrix3
    val earthVelocity: Vector3
    val observerPositionOfDate: Vector3
    val yearsSinceJ2000: Double
    fun apparentPlace(j2000: EquatorialCoordinate, properMotion: ProperMotion? = null): EquatorialCoordinate
    fun horizontal(j2000: EquatorialCoordinate, properMotion: ProperMotion? = null, refraction: Boolean = true): HorizontalCoordinate
    fun horizontalVector(j2000UnitVector: Vector3): Vector3
    fun topocentricEquatorial(geocentricJ2000: Vector3): EquatorialCoordinate
    fun horizontal(geocentricJ2000: Vector3, refraction: Boolean = true): HorizontalCoordinate
    fun horizontalOfCelestialPole(north: Boolean, refraction: Boolean = true): HorizontalCoordinate
    fun j2000(horizontal: HorizontalCoordinate, refracted: Boolean): EquatorialCoordinate      // Swift j2000(fromHorizontal:refracted:)
    fun hourAngle(apparentRightAscension: Double): Double
    companion object {
        const val earthEquatorialRadiusKilometers: Double
        const val kilometersPerAU: Double
        fun refracted(horizontal: HorizontalCoordinate, atmosphere: Atmosphere): HorizontalCoordinate
        internal fun geocentricObserverPosition(location: GeographicLocation, localSiderealTime: Double): Vector3
    }
}
```
`SkyFrame` is a plain final class (identity equality; Swift's struct is not `Equatable` either). Other areas extend it
with `fun SkyFrame.state(body: …)` etc.; the overloads of `horizontal` differ by their first parameter type
(`EquatorialCoordinate`, `Vector3`; extensions add `SolarSystemBody`, `CelestialObject`), so call sites stay unambiguous.

---------------------------------------------------------------------------------------------------------

## Swift-compatibility helpers (NEW — not in the Swift Foundation folder)

Other areas must use these instead of inventing their own, so that all dates behave identically.

### SwiftNumerics.kt  (existing file; first two helpers unchanged)

```kotlin
fun Double.roundedHalfAwayFromZero(): Double                  // Swift x.rounded()
fun formatFixed(value: Double, decimals: Int): String          // Swift String(format: "%.Nf"), C semantics
fun swiftMin(x: Double, y: Double): Double                    // Swift min(x, y) = y < x ? y : x
fun swiftMax(x: Double, y: Double): Double                    // Swift max(x, y) = y >= x ? y : x
```
`kotlin.math.min/max` differ from Swift for NaN (Kotlin: NaN wins; Swift: a NaN *second* argument is ignored,
`max(0, nan) == 0`, `min(90, nan) == 90`) and for `-0.0`. Use `swiftMin/swiftMax` wherever the Swift code calls `min`/`max`
on `Double` and a NaN can reach it; plain `minOf/maxOf` elsewhere. Other `rounded` modes: `.down` → `floor`, `.up` → `ceil`,
`.towardZero` → `truncate`, `.toNearestOrEven` → `kotlin.math.round`.

### SwiftDates.kt  (`Date` ⇄ `Instant`, Swift's Double arithmetic)

A Swift `Date` is a `Double` of seconds since 2001-01-01T00:00:00Z. These helpers convert through `Double` exactly the way
Swift does (sub-second precision kept; resolution ≈ 1e-7 s in the 2020s), so date arithmetic on Swift-style `Double`
seconds is reproduced bit for bit.

```kotlin
val Instant.timeIntervalSince1970: Double                      // Swift date.timeIntervalSince1970
val Instant.timeIntervalSinceReferenceDate: Double             // Swift date.timeIntervalSinceReferenceDate
fun instantFromTimeIntervalSince1970(seconds: Double): Instant           // Swift Date(timeIntervalSince1970:)
fun instantFromTimeIntervalSinceReferenceDate(seconds: Double): Instant  // Swift Date(timeIntervalSinceReferenceDate:)
fun Instant.addingTimeInterval(timeInterval: Double): Instant            // Swift date.addingTimeInterval(_:)
fun Instant.timeIntervalSince(date: Instant): Double                     // Swift a.timeIntervalSince(b)
val distantPast: Instant                                       // Swift Date.distantPast  (ref-date seconds -63114076800)
val distantFuture: Instant                                     // Swift Date.distantFuture (ref-date seconds 63113904000)
```
Out-of-range input saturates at `Instant.MIN/MAX` (infinities too); NaN gives `Instant.EPOCH` (1970 form) or the
2001 reference date (reference form). Nothing throws. Comparison operators (`<`, `==`, …) are `Instant`'s own.

### DateInterval.kt  (Foundation `DateInterval`)

```kotlin
data class DateInterval(val start: Instant, val duration: Double) : Comparable<DateInterval> {
    constructor(start: Instant, end: Instant)                  // Swift init(start:end:); IllegalArgumentException if end < start
    val end: Instant                                           // start.addingTimeInterval(duration)
    operator fun contains(date: Instant): Boolean              // inclusive; also `date in interval`
    fun intersects(dateInterval: DateInterval): Boolean
    fun intersection(dateInterval: DateInterval): DateInterval?   // Swift intersection(with:)
    override fun compareTo(other: DateInterval): Int           // by start, then duration
}
```
`init` requires `duration >= 0` (IllegalArgumentException), as Swift's `precondition`. Not `@Serializable` (see Serialization).

---------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

### Initializers
| Swift | Kotlin |
| --- | --- |
| `EquatorialCoordinate(rightAscension:declination:)` | `EquatorialCoordinate(rightAscension, declination)` (companion `invoke`) |
| `EquatorialCoordinate(rightAscensionHours:declination:)` | **`EquatorialCoordinate.fromRightAscensionHours(rightAscensionHours, declination)`** |
| `EquatorialCoordinate(vector:)`, `HorizontalCoordinate(vector:)`, `EclipticCoordinate(vector:)`, `GalacticCoordinate(vector:)` | `X(vector)` (companion `invoke` overload) |
| `HorizontalCoordinate(azimuth:altitude:)`, `EclipticCoordinate(longitude:latitude:)`, `GalacticCoordinate(longitude:latitude:)` | `X(azimuth, altitude)` / `X(longitude, latitude)` (companion `invoke`) |
| `GeographicLocation(latitude:longitude:elevation:)` | `GeographicLocation(latitude, longitude, elevation = 0.0)` (companion `invoke`) |
| `GeographicLocation.validated(latitude:longitude:elevation:)` | `GeographicLocation.validated(latitude, longitude, elevation = 0.0)` — same name, companion function, returns `GeographicLocation?` |
| `ProperMotion(raMilliarcsecondsPerYear:decMilliarcsecondsPerYear:)` | `ProperMotion(ra…, dec…)` (companion `invoke`; non-finite → 0) |
| `Vector3(x:y:z:)` and `Vector3(_:_:_:)` | `Vector3(x, y, z)` |
| `Matrix3(rows:_:_:)` | `Matrix3(r0, r1, r2)` |
| `Matrix3(columns:_:_:)` | **`Matrix3.fromColumns(c0, c1, c2)`** |
| `JulianDate(ut:deltaT:)` / `JulianDate(ut:)` / `JulianDate(date:)` | `JulianDate(ut, deltaT)` / `JulianDate(ut)` / `JulianDate(date)` |
| `JulianDate(tt:)` | **`JulianDate.fromTt(tt)`** |
| `Nutation(julianDate:)` | `Nutation(jd)` (companion `invoke`); `Nutation(longitude:obliquity:)` → `Nutation(longitude, obliquity)` |
| `SkyFrame(julianDate:observer:)` / `SkyFrame(date:observer:)` | `SkyFrame(jd, observer)` / `SkyFrame(date, observer)` (`date: Instant`) |
| `Observer(location:atmosphere:)`, `Atmosphere(pressureMillibars:temperatureCelsius:)` | same, positional or named |

### Members, parameters, types
- Parameter names follow the rule (Swift internal names): `angle(other)`, `angularSeparation(other)`, `matrixFromJ2000(jd)`,
  `Aberration.apply(direction, earthVelocity)`, `ProperMotion.apply(position, years)`, `SkyFrame.j2000(horizontal, refracted)`.
  The only kept label: `JulianDate.deltaT(decimalYear)` (the Swift internal name `y` is meaningless).
- `GeographicLocation.elevationRange` is a `ClosedFloatingPointRange<Double>`: `.start` / `.endInclusive` instead of
  `.lowerBound` / `.upperBound`; `in` and `contains` work.
- `CoordinateTransforms.horizontalToEquatorial` returns `CoordinateTransforms.HourAngleDeclination(hourAngle, declination)`
  instead of the tuple (`.hourAngle`, `.declination` and destructuring work).
- `Vector3`/`Matrix3`/`Observer`/`Atmosphere`: fields are `val` (Swift `var`). Replace field assignment by `copy(...)`
  (e.g. `observer = observer.copy(atmosphere = x)`); `a += b` / `a -= b` work on `var a: Vector3` through `plus`/`minus`.
- `Double * Vector3` is a top-level extension (needs the wildcard import); `m[r, c]` is `operator get`;
  `Matrix3.get` out of range throws `IllegalArgumentException` (Swift traps).
- `Vector3`/`Matrix3.toString()` reproduce Swift's `description` shape (`(x, y, z)`, `[(…), (…), (…)]`); numbers use Kotlin's
  `Double.toString` (`1.0E-5`, not `1e-05`).
- Arrays: `[Double]` → `List<Double>`; literal coefficient lists are better written with the `vararg` overload.
- `JulianDate`: `Comparable` orders by `ut` only (equality still compares `ut` and `deltaT`), like Swift. `tt`, `date`, `centuriesTT` … are
  computed properties.
- `JulianDate.date` / `SkyFrame.date` go through `instantFromTimeIntervalSince1970`; a JD near 2.46e6 days resolves 4e-10 d
  (≈ 40 µs), so `SkyFrame(instant, o).date` differs from `instant` by up to ~50 µs, as in Swift.
- `precondition` → `require` (`IllegalArgumentException`).

### Behaviour kept from Swift on purpose (do not "fix")
- NaN handling follows Swift's `min`/`max`: `Refraction.apparentAltitude(NaN) == 90.0`; `AstroMath.clamp(NaN, …)` stays NaN;
  `HorizontalCoordinate(vector)` / `EquatorialCoordinate(vector)` of a zero **or NaN** vector give (0°, 0°).
- Decoding bypasses the normalizing constructors, exactly like Swift's synthesized `Codable`:
  `{"rightAscension":400,"declination":100}` decodes to 400 / 100.
- `JulianDate.deltaT` is the Swift model: it jumps by 32 s at 1800 (the long-term parabola is used before 1800), is
  continuous at every later boundary, and after 2026 blends towards the Espenak–Meeus parabola (about 21 s above Skyfield's flat
  extrapolation in 2049).
- The frame pipeline neglects light deflection, diurnal aberration and polar motion (≤ 1″), as documented in Swift.

### Serialization (`@Serializable`, kotlinx.serialization)
Same keys as Swift's synthesized `Codable`, no `@SerialName` needed (Kotlin and Swift property names agree):
`Vector3 {x,y,z}`, `Matrix3 {r0,r1,r2}`, `JulianDate {ut,deltaT}`, `EquatorialCoordinate {rightAscension,declination}`,
`HorizontalCoordinate {azimuth,altitude}`, `EclipticCoordinate`/`GalacticCoordinate {longitude,latitude}`,
`GeographicLocation {latitude,longitude,elevation}`, `Nutation {longitude,obliquity}`,
`ProperMotion {raMilliarcsecondsPerYear,decMilliarcsecondsPerYear}`, `Atmosphere {pressureMillibars,temperatureCelsius}`,
`Observer {location,atmosphere}`. Properties with defaults (`Atmosphere`, `Observer.atmosphere`) are `@Required`, so every key is
always written and required on read, as in Swift. Not serializable: `SkyFrame` (not `Codable` in Swift either),
`CoordinateTransforms.HourAngleDeclination` (new) and `DateInterval` (Foundation's is `Codable`, but `Instant` has no default
serializer — give it one in the area that needs it).

### Numerical fidelity
Formulas, constants and evaluation order are copied from Swift (the 63-term nutation table and every numeric literal were compared
mechanically). `Math.sin/cos/tan/asin/atan2` may differ from glibc by one ulp; against an independent transliteration of the Swift
sources over 3 800 random inputs (all functions, full `SkyFrame` pipeline) the largest relative difference is 8e-16.

### Performance notes (JVM 21, warmed up)
`SkyFrame` construction ≈ 4–10 µs; `horizontalVector` ≈ 55 ns per object (5 000 objects ≈ 0.3 ms). Hot paths allocate only
`Vector3`/`Matrix3` results; `AstroMath.clamp(Double…)` and the `vararg` `polynomial` do not box.

## Tests

`src/test/kotlin/.../foundation/`
- `FoundationTests.kt` — 1:1 port of `FoundationTests.swift` (21 tests, same names, inputs, expected values, tolerances).
- Extra (not in Swift): `FoundationFixtureTests.kt` (4: sidereal time, nutation, precession, Earth velocity, ΔT, star and solar-system
  body positions against the Skyfield/ERFA fixtures `sidereal.json`, `stars_apparent.json`, `bodies_apparent.json` in
  `src/test/resources/fixtures/`), `FoundationApiTests.kt` (30: operators, edge cases, JSON shapes), `SwiftCompatTests.kt` (12: the helpers above).

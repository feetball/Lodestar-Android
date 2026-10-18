# Geomagnetism: Kotlin API

Package `net.kuecker.lodestar.kit.geomagnetism` (ported from `Sources/LodestarKit/Geomagnetism/`:
`WorldMagneticModel.swift`, `WMM2025Coefficients.swift`). World Magnetic Model 2025, degree 12, valid
2025.0 to 2030.0. Pure Kotlin/JVM, no dependency on any other kit area (the `foundation/` package is not
used; the Swift code only uses system math functions and `Date`).

Typical use:

```kotlin
val year = WorldMagneticModel.decimalYear(Instant.now())
val field = WorldMagneticModel.wmm2025.field(
    latitude = 48.1, longitude = 11.6, altitudeMeters = 520.0, decimalYear = year,
)
if (field.isValid) { field.declination; field.inclination; field.totalIntensity / 1000 /* microtesla */ }
val current = WorldMagneticModel.wmm2025.isWithinValidity(year)
```

All public types are immutable and thread-safe (Swift: `Sendable`). Visibility is as in Swift: Swift
`internal` is Kotlin `internal` (visible to tests of the same module).

## WorldMagneticModel.kt

```kotlin
// public
data class MagneticFieldElements(val north: Double, val east: Double, val down: Double)   // nT, geodetic NED
    val horizontalIntensity: Double          // H = hypot(north, east), nT
    val totalIntensity: Double               // F = hypot(H, down), nT
    val declination: Double                  // D, degrees, [-180, 180], east positive
    val inclination: Double                  // I, degrees, [-90, 90], positive down
    val isValid: Boolean                     // all three components finite
    val compassReliability: CompassReliability
    private companion object { const val degreesPerRadian; const val blackoutZoneThreshold = 2000.0; const val cautionZoneThreshold = 6000.0 }

enum class CompassReliability { RELIABLE, CAUTION, BLACKOUT }     // H >= 6000 / >= 2000 / below, nT

class WorldMagneticModel internal constructor(val epoch: Double, rows: List<CoefficientRow>)
    val epoch: Double                        // 2025.0 for wmm2025
    val validFrom: Double                    // = epoch
    val validUntil: Double                   // = epoch + 5.0, exclusive
    fun isWithinValidity(decimalYear: Double): Boolean
    fun field(latitude: Double, longitude: Double, altitudeMeters: Double, decimalYear: Double): MagneticFieldElements
    companion object {
        val wmm2025: WorldMagneticModel
        fun decimalYear(date: Instant): Double                       // NaN beyond about +-316,000 years from 1970
        internal fun decimalYear(secondsSince1970: Double): Double   // implementation behind decimalYear(Instant); accepts NaN/inf/huge
        private const val referenceRadiusKilometers = 6371.2         // also: minimumAltitudeMeters, maximumAltitudeMeters,
                                                                     // validityYears, secondsPerDay, maximumConvertibleSeconds,
                                                                     // daysFromYearOneToUnixEpoch (Long)
        private fun unixSeconds(year: Long): Double                  // Swift: unixSeconds(atStartOfYear:)
        private fun floorDivide(dividend: Long, divisor: Long): Long
    }

// internal
    internal fun synthesize(sinLatitude: Double, cosLatitude: Double, longitudeRadians: Double,
                            radiusKilometers: Double, decimalYear: Double): WorldMagneticModel.GeocentricComponents
    internal data class CoefficientRow(val degree: Int, val order: Int, val g: Double, val h: Double,
                                       val gDot: Double, val hDot: Double)
    internal data class GeocentricComponents(val north: Double, val east: Double, val down: Double)
    internal class GeocentricPosition(geodeticLatitudeDegrees: Double, heightKilometers: Double)
        val sinLatitude: Double
        val cosLatitude: Double
        val radiusKilometers: Double
        val rotationCosine: Double
        val rotationSine: Double
        private companion object { const val semiMajorAxisKilometers = 6378.137; const val flattening; const val eccentricitySquared }
    internal class LegendreTable(maximumDegree: Int, sinLatitude: Double, cosLatitude: Double)
        val value: DoubleArray               // P(n, m)(sin phi'), flat, indexed by index(degree, order); do not modify
        val latitudeDerivative: DoubleArray  // dP/d(phi')
        val overCosine: DoubleArray          // P / cos(phi') for m >= 1, 0 for m = 0
        companion object {
            fun entryCount(maximumDegree: Int): Int
            fun index(degree: Int, order: Int): Int
        }
```

## WMM2025Coefficients.kt

```kotlin
internal object WMM2025Coefficients {
    const val epoch: Double                                   // 2025.0
    val rows: List<WorldMagneticModel.CoefficientRow>         // 90 rows, ordered by degree then order
}
```

The table is a mechanical translation of the generated Swift file: the same 90 rows in the same order with
the digits copied verbatim (checked textually against `WMM2025Coefficients.swift` and numerically against
NOAA's `WMM.COF`). It compiles to a 2.2 KB static initializer, far below the JVM 64 KB method limit.

## Tests: WorldMagneticModelTests.kt

`class WorldMagneticModelTests`: the 38 Swift tests under their Swift names plus 2 Kotlin-specific ones
(`testInvalidCoefficientRows_areRejected`, `testDecimalYear_instantOverloadAgreesWithTheSecondsOverload`).

## Renames & deviations from Swift

Call shapes (labels vanish, as in the porting rules):

| Swift | Kotlin |
|---|---|
| `WorldMagneticModel.wmm2025` | `WorldMagneticModel.wmm2025` (companion) |
| `model.field(latitude:longitude:altitudeMeters:decimalYear:)` | `model.field(latitude, longitude, altitudeMeters, decimalYear)` |
| `model.isWithinValidity(decimalYear:)` | `model.isWithinValidity(decimalYear)` |
| `WorldMagneticModel.decimalYear(from: date)` (`Date`) | `WorldMagneticModel.decimalYear(date)` (`java.time.Instant`) |
| `CompassReliability.reliable / .caution / .blackout` | `CompassReliability.RELIABLE / CAUTION / BLACKOUT` |

Deviations and things consumers should know:

1. **`WMM2025Coefficients` is a top-level `internal object`**, not nested in `WorldMagneticModel`. Kotlin cannot add
   a nested type to a class from a second file. It is `internal`, so only the module's own code and tests see it
   (Swift: `WorldMagneticModel.WMM2025Coefficients`). `CoefficientRow`, `GeocentricPosition` and `LegendreTable`
   (declared in `WorldMagneticModel.swift`) stay nested as `WorldMagneticModel.CoefficientRow` etc.
2. **Extra internal overload `decimalYear(secondsSince1970: Double)`.** The public `decimalYear(Instant)` converts
   to POSIX seconds and delegates to it. Swift's `Date` can hold NaN, infinity or 1e300; `Instant` cannot, so
   the overload exists to keep the "not finite / out of range returns NaN" behaviour and its test inputs. For an
   `Instant` beyond about +-316,000 years from 1970 (for example `Instant.MAX`) the result is `NaN`, as in Swift.
3. **Tuple return of `synthesize`.** Swift returns `(north:east:down:)`; Kotlin returns the internal data class
   `WorldMagneticModel.GeocentricComponents(north, east, down)`.
4. **`LegendreTable` parameter names.** Swift is `init(maximumDegree:sinLatitude s:cosLatitude c:)`. The internal
   names `s` and `c` are meaningless as Kotlin parameter names, so the labels `sinLatitude` and `cosLatitude` are kept.
5. **Preconditions become `IllegalArgumentException`** (`require`). Swift traps on an empty or invalid coefficient row
   list in the internal `WorldMagneticModel.init(epoch:rows:)`, and on `LegendreTable` with `maximumDegree < 1`
   (the range `1...maximumDegree`). The public API has no preconditions: invalid arguments to `field` give clamping or
   non-finite elements exactly as documented.
6. **Value types.** `MagneticFieldElements` and `CoefficientRow` are data classes (Swift `Hashable`);
   `WorldMagneticModel`, `GeocentricPosition`, `LegendreTable` are plain classes (the Swift structs are not
   `Equatable`). Data-class equality compares doubles with `Double.compare`: NaN equals NaN and `0.0` differs from
   `-0.0`, unlike Swift's `==`. `WorldMagneticModel` has identity equality. `Sendable` has no Kotlin counterpart; all
   types are immutable and `field` shares no state, so concurrent calls are safe.
7. **`LegendreTable` arrays** are `DoubleArray` (internal class, hot numeric path) and are not defensively copied;
   callers must not modify them. `WorldMagneticModel` copies the coefficient rows into private arrays.
8. **Numerics.** Expression and summation order is identical to Swift. `remainder(dividingBy:)` is
   `Math.IEEEremainder`, `rounded(.down)` is `floor`, `hypot/atan2/sin/cos/pow/sqrt` are the `kotlin.math`
   (`java.lang.Math`) functions, whose last-bit results may differ from Swift's libm. Against a 25-digit independent
   reference (572 points including poles, date line and altitude extremes) the Kotlin field is within 7e-11 nT;
   `decimalYear` is bit-identical to an exact-integer reference on 9000 timestamps. The calendar helpers use `Long`
   (Swift `Int` is 64-bit).
9. **Initialisation.** `wmm2025` is created when `WorldMagneticModel` is first used (class initialisation); Swift
   creates it lazily on first access. All numeric constants are `const val`, so initialisation order cannot matter.
10. **Cost.** `field(...)` takes about 2.4 microseconds and allocates about 2.5 KB (three 91-element `DoubleArray`s for
    the Legendre tables, two 13-element arrays for cos/sin of the multiples of the longitude, a few small objects);
    `decimalYear` takes about 40 ns and does not allocate.

Test-only deviations (each platform-specific; same inputs, expected values and tolerances otherwise):

- `testPublicValueTypesAreSendable`: Swift checks `Sendable` conformance at compile time. The Kotlin test checks what it
  promises: no mutable instance fields in the public types, and 16 concurrent tasks on 8 threads return the same fields
  as a single-threaded run.
- `testDecimalYear_coversTheRangeOfFoundationDates`: Foundation's `Date.distantPast/distantFuture` are the instants
  -63114076800 s and +63113904000 s from the reference date 2001-01-01T00:00:00Z.
- `testDecimalYear_matchesTheGregorianCalendarForManyDates`: `Calendar`/`DateComponents` become `java.time`
  (identical in 1600..2400); the seeded `SplitMix64` and Swift's `Double.random(in:using:)` (including the Lemire
  bounded draw) are reimplemented exactly, so the same 500 dates are drawn (checked against an independent
  Python implementation).
- `testDecimalYear_isNotANumberForUnrepresentableDates`: the NaN, infinite and 1e300 inputs go through
  `decimalYear(secondsSince1970:)`; the extremes of `Instant` are added.
- The `decimalYear(year, month, ...)` helper builds the instant with `ZonedDateTime` in UTC.
- Swift's optional `first`/`last` of the coefficient list are `firstOrNull()`/`lastOrNull()`.

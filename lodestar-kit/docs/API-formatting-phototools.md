# LodestarKit Kotlin port: Formatting and PhotoTools

Packages: `net.kuecker.lodestar.kit.formatting` (`Formatting/*.swift`) and
`net.kuecker.lodestar.kit.phototools` (`PhotoTools/*.swift`).
Sources: `src/main/kotlin/net/kuecker/lodestar/kit/{formatting,phototools}/`.
Tests: `src/test/kotlin/net/kuecker/lodestar/kit/{formatting,phototools}/`.

Dependencies: only `net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero()` (Swift `.rounded()`), plus
`kotlinx-serialization-json`. Neither area uses `AstroMath`, vectors, dates or any other Foundation type, and neither uses
`String(format:)`, `NumberFormatter` or any other Foundation formatter (all output is built from integers), so nothing from
`foundation/` was inlined. The two areas depend on each other in one direction: `phototools` uses `formatting`
(`AngleFormatter.formatSignedDegrees`, `NumberText`).

Visibility notation: no modifier = public. Members of an `internal object` are listed without a modifier; they are internal
by inheritance (the Swift originals have the default `internal` access).

Using the extension functions: the duration/exposure formatters and the `parse…` functions are extension functions on the
`AngleFormatter` object (Swift `extension AngleFormatter` in other files). Call sites read the same,
`AngleFormatter.parseLatitude(text)`, but a caller outside the package must import them:
`import net.kuecker.lodestar.kit.formatting.*` (or `…formatting.formatDuration` and so on).

Quick usage:

```kotlin
import net.kuecker.lodestar.kit.formatting.*          // AngleFormatter (also its extension functions), CompassPoint, TypographyStyle
import net.kuecker.lodestar.kit.phototools.*
import kotlinx.serialization.json.Json

AngleFormatter.formatDegrees(12.34, decimals = 1)                           // "12.3°"
AngleFormatter.formatDeclination(degrees = -5.391, style = TypographyStyle.ASCII)   // "-05d 23' 28\""
AngleFormatter.formatExposure(seconds = 150.0)                              // "2m 30s"   (extension function: needs the import)
AngleFormatter.parseLatitude("33°52′08″S")                                  // -33.8688…  (Double?, null when invalid)
CompassPoint(azimuth = 200.0).abbreviation                                  // "SSW"
val lens = LensSetup(SensorFormat.fullFrame, focalLengthMillimeters = 24.0, aperture = 2.8, pixelPitchMicrometers = 5.9)
val estimate = ExposureCalculator.maxExposure(ExposureRule.NPF, lens, declination = 45.0)
val pixels = ExposureCalculator.trailLengthPixels(exposureSeconds = 10.0, setup = lens)   // setup needs its name when declination is omitted
val frame = FieldOfView.of(sensor = lens.sensor, focalLength = lens.focalLengthMillimeters)
val text = Json.encodeToString(LensSetup.serializer(), lens)                // keys: sensor, focalLengthMillimeters, aperture, pixelPitchMicrometers
val back = Json.decodeFromString(LensSetup.serializer(), text)              // throws SerializationException on out-of-range data
```

---------------------------------------------------------------------------------------------------------------------------

## formatting/TypographyStyle.kt

```kotlin
enum class TypographyStyle { UNICODE, ASCII }                 // Swift .unicode / .ascii; allCases -> entries
    internal val minus: String                                // U+2212 MINUS SIGN / "-"
    internal val degree: String                               // U+00B0 / "d"
    internal val arcminute: String                            // U+2032 / "'"
    internal val arcsecond: String                            // U+2033 / "\""
    internal val placeholder: String                          // U+2014 EM DASH / "--"
```

## formatting/CompassPoint.kt

```kotlin
@Serializable(with = CompassPointSerializer::class)
enum class CompassPoint(val rawValue: Int) {                  // JSON form: the raw integer, like the Swift Int-backed Codable
    NORTH(0), NORTH_NORTHEAST(1), NORTHEAST(2), EAST_NORTHEAST(3), EAST(4), EAST_SOUTHEAST(5), SOUTHEAST(6),
    SOUTH_SOUTHEAST(7), SOUTH(8), SOUTH_SOUTHWEST(9), SOUTHWEST(10), WEST_SOUTHWEST(11), WEST(12), WEST_NORTHWEST(13),
    NORTHWEST(14), NORTH_NORTHWEST(15)
    val centerAzimuth: Double
    val abbreviation: String                                  // "N", "NNE", ...
    val fullName: String                                      // "North", "North-northeast", ...
    companion object {
        const val sectorWidth: Double = 22.5
        fun fromRawValue(raw: Int): CompassPoint?             // Swift init?(rawValue:)
        operator fun invoke(azimuth: Double): CompassPoint    // Swift init(azimuth:); call as CompassPoint(azimuth = 90.0)
    }
}
internal object CompassPointSerializer : KSerializer<CompassPoint>   // encodes rawValue; unknown raw values throw SerializationException
```

## formatting/NumberText.kt

```kotlin
internal object NumberText {
    const val maximumDecimals = 6
    fun clampedDecimals(decimals: Int): Int
    fun powerOfTen(exponent: Int): Long                       // throws ArithmeticException on 64-bit overflow (Swift traps)
    fun padded(value: Long, width: Int): String
    data class FixedPoint(val units: Long, val decimals: Int) { val isZero: Boolean; val text: String }
    fun fixedPoint(magnitude: Double, decimals: Int): FixedPoint?
    fun trimmed(magnitude: Double, maxDecimals: Int): String?
    fun fixedDescription(value: Double, decimals: Int): String
    fun trimmedDescription(value: Double, maxDecimals: Int): String
    data class Sexagesimal(val whole: Long, val minutes: Long, val secondsUnits: Long, val decimals: Int) {
        val isZero: Boolean; val secondsText: String
    }
    fun sexagesimal(magnitude: Double, secondsDecimals: Int): Sexagesimal?
    fun swiftDescription(value: Double): String              // Kotlin only: Swift "\(double)", see deviations
    private const val exactIntegerLimit = 9_007_199_254_740_992.0       // 2^53
    private const val exponentialLimit = 18_014_398_509_481_984.0       // 2^54, used by swiftDescription
    private class DecimalDigits(val digits: String, val pointPosition: Int)
    private val candidateRoundings: List<RoundingMode>
    private fun shortestDigits(magnitude: Double): DecimalDigits
    private fun decimalDigits(value: BigDecimal): DecimalDigits
    private fun decimalNotation(digits: String, pointPosition: Int): String
    private fun exponentialNotation(digits: String, pointPosition: Int): String
}
```

## formatting/AngleFormatter.kt

```kotlin
object AngleFormatter {
    fun formatRightAscension(hours: Double, secondsDecimals: Int = 1, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatDeclination(degrees: Double, secondsDecimals: Int = 0, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatDegrees(degrees: Double, decimals: Int = 1, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatSignedDegrees(degrees: Double, decimals: Int = 1, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatAzimuth(degrees: Double, decimals: Int = 1, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatDMS(degrees: Double, secondsDecimals: Int = 0, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatLatitude(degrees: Double, decimals: Int = 4, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun formatLongitude(degrees: Double, decimals: Int = 4, style: TypographyStyle = TypographyStyle.UNICODE): String
    fun compassPoint(azimuth: Double): CompassPoint
    private fun degreesMinutesSeconds(parts: NumberText.Sexagesimal, sign: String, degreesWidth: Int, style: TypographyStyle): String
}
```

## formatting/AngleFormatterDurations.kt (extension functions, `AngleFormatter+Durations.swift`)

```kotlin
fun AngleFormatter.formatDuration(seconds: Double, style: TypographyStyle = TypographyStyle.UNICODE): String
fun AngleFormatter.formatExposure(seconds: Double, style: TypographyStyle = TypographyStyle.UNICODE): String
private const val maximumDurationSeconds = 1e12              // file-private (Swift private static let)
private fun decimalSeconds(seconds: Double, maxDecimals: Int, style: TypographyStyle): String
```

## formatting/AngleFormatterParsing.kt (extension functions, `AngleFormatter+Parsing.swift`)

```kotlin
fun AngleFormatter.parseRightAscension(text: String): Double?     // hours in [0, 24)
fun AngleFormatter.parseDeclination(text: String): Double?        // degrees in [-90, 90]
fun AngleFormatter.parseLatitude(text: String): Double?           // same grammar and range as declination
fun AngleFormatter.parseLongitude(text: String): Double?          // degrees east in (-180, 180]
```

## formatting/AngleTextParser.kt

```kotlin
internal object AngleTextParser {
    enum class Quantity { RIGHT_ASCENSION, DECLINATION, LONGITUDE }
    const val maximumLength = 64
    enum class Mark { HOUR, DEGREE, MINUTE, SECOND }
    data class UnitMark(val kind: Mark, val isLetter: Boolean)
    data class Token(val kind: Kind, val followsSpace: Boolean) {
        sealed interface Kind {                                       // Swift Token.Kind
            data class Number(val value: Double, val isWhole: Boolean) : Kind
            data class Sign(val isNegative: Boolean) : Kind
            data object Colon : Kind
            data class Mark(val unit: UnitMark) : Kind                // Swift .mark(UnitMark): payload unlabeled, named "unit"
            data class Hemisphere(val isNegative: Boolean) : Kind
            data object SouthOrSeconds : Kind
        }
    }
    fun parse(text: String, quantity: Quantity): Double?             // Swift parse(_:as:)
    private data class Field(val value: Double, val isWhole: Boolean, val mark: UnitMark?)
    private class TokenReader(tokens: List<Token>)                    // isAtEnd, upcoming, upcomingIsNumber,
                                                                      // upcomingIsAttachedSouthOrSeconds, takeHemisphere(), takeSign(),
                                                                      // takeNumber(), takeMark(), takeColon(), skip()
    private fun boundedScalars(text: String): IntArray?               // Kotlin only: code points, null above 64
    private fun readFields(reader: TokenReader): List<Field>?
    private fun markFits(mark: UnitMark?, index: Int): Boolean
    private fun combinedMagnitude(fields: List<Field>): Double?
    private fun mergedSign(hemisphere: Boolean?, sign: Boolean?): Boolean?
    private fun value(magnitude: Double, isNegative: Boolean, firstMark: Mark?, quantity: Quantity): Double?
    private fun normalizingZero(value: Double): Double
}
```

## formatting/AngleTextParserLexing.kt (`AngleTextParser+Lexing.swift`)

```kotlin
internal fun AngleTextParser.tokenize(scalars: IntArray, quantity: AngleTextParser.Quantity): List<AngleTextParser.Token>?
private fun isWhitespace(scalar: Int): Boolean                    // Unicode White_Space property, spelled out
private fun isDigit(scalar: Int): Boolean
private fun isDecimalMark(scalar: Int): Boolean
private class ScannedNumber(val kind: AngleTextParser.Token.Kind, val next: Int)   // Swift's (kind:next:) tuple
private fun number(scalars: IntArray, start: Int): ScannedNumber?   // Swift number(in:from:)
private fun symbol(scalar: Int, quantity: AngleTextParser.Quantity): AngleTextParser.Token.Kind?
private fun mergingDoubledPrimes(tokens: List<AngleTextParser.Token>): List<AngleTextParser.Token>
```

---------------------------------------------------------------------------------------------------------------------------

## phototools/RangeLimiter.kt

```kotlin
internal object RangeLimiter {
    fun limit(value: Double, range: ClosedFloatingPointRange<Double>, fallback: Double): Double   // Swift limit(_:to:fallback:)
}
```

## phototools/SensorFormat.kt

```kotlin
@Serializable(with = SensorFormatSerializer::class) @ConsistentCopyVisibility
data class SensorFormat private constructor(
    val id: String, val name: String, val widthMillimeters: Double, val heightMillimeters: Double,
) {
    val diagonalMillimeters: Double
    val cropFactor: Double
    fun pixelPitchMicrometers(megapixels: Double): Double?
    companion object {
        val dimensionRange: ClosedFloatingPointRange<Double>            // 1.0..200.0
        val pixelPitchRange: ClosedFloatingPointRange<Double>           // 0.3..100.0
        val fullFrame: SensorFormat; val apsC: SensorFormat; val apsCCanon: SensorFormat
        val microFourThirds: SensorFormat; val oneInch: SensorFormat; val mediumFormat44x33: SensorFormat
        val presets: List<SensorFormat>
        fun custom(width: Double, height: Double): SensorFormat
        fun validated(width: Double, height: Double): SensorFormat?
        internal fun decoded(id: String, name: String, width: Double, height: Double): SensorFormat?   // checks of init(from:)
        private val fullFrameDiagonalMillimeters: Double
        private fun isValidLabel(label: String): Boolean
    }
}
internal object SensorFormatSerializer : KSerializer<SensorFormat> { fun decode(element: JsonElement): SensorFormat }
internal fun JsonObject.requireString(key: String): String          // strict JSON field access, shared with LensSetup
internal fun JsonObject.requireDouble(key: String): Double
internal fun JsonObject.optionalDouble(key: String): Double?
```
JSON keys: `id`, `name`, `widthMillimeters`, `heightMillimeters`.

## phototools/LensSetup.kt

```kotlin
@Serializable(with = LensSetupSerializer::class) @ConsistentCopyVisibility
data class LensSetup private constructor(
    val sensor: SensorFormat, val focalLengthMillimeters: Double, val aperture: Double, val pixelPitchMicrometers: Double?,
) {
    val equivalentFocalLengthMillimeters: Double
    val fieldOfView: FrameSize
    val imageScaleArcsecondsPerPixel: Double?
    internal fun imageScale(pitch: Double): Double                      // Swift imageScale(forPixelPitch:)
    companion object {
        val focalLengthRange: ClosedFloatingPointRange<Double>          // 4.0..2000.0
        val apertureRange: ClosedFloatingPointRange<Double>             // 0.95..32.0
        const val defaultFocalLengthMillimeters = 24.0
        const val defaultAperture = 2.8
        private val arcsecondsPerMicrometerPerMillimeter: Double
        operator fun invoke(sensor: SensorFormat, focalLengthMillimeters: Double, aperture: Double,
                            pixelPitchMicrometers: Double? = null): LensSetup      // Swift public init: limits every value
        fun validated(sensor: SensorFormat, focalLengthMillimeters: Double, aperture: Double,
                      pixelPitchMicrometers: Double? = null): LensSetup?
    }
}
internal object LensSetupSerializer : KSerializer<LensSetup>
```
JSON keys: `sensor`, `focalLengthMillimeters`, `aperture`, `pixelPitchMicrometers` (omitted when null).

## phototools/FieldOfView.kt

```kotlin
object FieldOfView {
    fun horizontal(sensor: SensorFormat, focalLength: Double): Double
    fun vertical(sensor: SensorFormat, focalLength: Double): Double
    fun diagonal(sensor: SensorFormat, focalLength: Double): Double
    fun of(sensor: SensorFormat, focalLength: Double): FrameSize
    private fun usable(focalLength: Double): Double
}
data class FrameSize(val horizontal: Double, val vertical: Double, val diagonal: Double) {
    internal constructor(sensor: SensorFormat, focalLength: Double)
    val longSide: Double
    val shortSide: Double
    val rotated: FrameSize
    fun framing(objectMajorArcmin: Double, minorArcmin: Double): ObjectFraming?
}
@ConsistentCopyVisibility
data class ObjectFraming internal constructor(val majorAxisFraction: Double, val minorAxisFraction: Double) {
    val fillFraction: Double
    val fitsInFrame: Boolean
}
private fun angleDegrees(extent: Double, focalLength: Double): Double   // Swift fileprivate static on FieldOfView
private fun swiftMax(x: Double, y: Double): Double                      // Swift max/min semantics, see deviations
private fun swiftMin(x: Double, y: Double): Double
```

## phototools/ExposureCalculator.kt

```kotlin
@Serializable
enum class ExposureRule(val rawValue: String) {                         // allCases -> entries; JSON: "rule500" ...
    RULE500("rule500"), RULE400("rule400"), RULE300("rule300"), NPF("npf")
    val id: String
    val displayName: String
    val summary: String
    companion object { fun fromRawValue(raw: String): ExposureRule? }
}
private val ExposureRule.equivalentFocalLengthNumerator: Double?        // Swift fileprivate var
@ConsistentCopyVisibility
data class ExposureEstimate internal constructor(
    val seconds: Double, val rule: ExposureRule, val usedDefaultPixelPitch: Boolean, val declinationFactor: Double,
    val notes: List<String>,
)
object ExposureCalculator {
    const val defaultPixelPitchMicrometers = 4.0
    const val maximumDeclinationFactor = 5.0
    const val siderealRateArcsecondsPerSecond = 15.041
    private const val npfApertureCoefficient = 35.0
    private const val npfPixelPitchCoefficient = 30.0
    fun maxExposure(rule: ExposureRule, setup: LensSetup, declination: Double? = null): ExposureEstimate
    fun trailLengthArcseconds(exposureSeconds: Double, declination: Double? = null): Double
    fun trailLengthPixels(exposureSeconds: Double, declination: Double? = null, setup: LensSetup): Double
    private fun declinationFactor(declination: Double?, notes: MutableList<String>): Double
}
```
(`trailLengthPixels`: a call that omits `declination` names `setup`: `trailLengthPixels(exposureSeconds = 10.0, setup = lens)`.)

---------------------------------------------------------------------------------------------------------------------------

## Renames & deviations from Swift

Naming (per KOTLIN_PORT.md section 3)
- Enum cases are UPPER_SNAKE_CASE (`TypographyStyle.UNICODE/ASCII`, `CompassPoint.NORTH_NORTHEAST`, `ExposureRule.RULE500/RULE400/RULE300/NPF`);
  `allCases` is `entries`.
- `CompassPoint(azimuth:)` is the companion `operator fun invoke(azimuth)`, so `CompassPoint(azimuth = 90.0)` reads like Swift;
  `init?(rawValue:)` is `CompassPoint.fromRawValue(raw)`. Likewise `ExposureRule.fromRawValue(raw)`.
- `LensSetup(sensor:focalLengthMillimeters:aperture:pixelPitchMicrometers:)` (a normalizing initializer) is the companion
  `operator fun invoke`; the constructor is private and `copy()` is private (`@ConsistentCopyVisibility`) so a value can never
  bypass the limiting. `SensorFormat` has no public initializer in Swift either; its constructor and `copy()` are private.
- Swift's memberwise initializers are `internal`, so `ExposureEstimate` and `ObjectFraming` have `internal` constructors (and
  internal `copy()`); `FrameSize` keeps its public `(horizontal, vertical, diagonal)` constructor and has an internal
  `(sensor, focalLength)` one.
- Labels that vanish: `parse(_:as:)` is `parse(text, quantity)`; `limit(_:to:fallback:)` is `limit(value, range, fallback)`;
  `imageScale(forPixelPitch:)` is `imageScale(pitch)`; `number(in:from:)` is `number(scalars, start)`.
- Unlabeled enum payload: `Token.Kind.Mark(unit)` (Swift `.mark(UnitMark)`). Swift `Token.Kind.number(value:isWhole:)` keeps its labels as property names.
- `ClosedRange<Double>` constants are `ClosedFloatingPointRange<Double>` (a `ClosedRange<Double>` whose `contains` uses IEEE
  comparisons, so NaN and infinities are outside every range, as in Swift).
- File-private Swift members that the same file's other types use become file-private top-level functions or properties
  (`angleDegrees`, `ExposureRule.equivalentFocalLengthNumerator`, `maximumDurationSeconds`, `decimalSeconds`).
- Extension functions on `AngleFormatter` need an import outside the package (see top).
- `Sendable`, `Identifiable` (the `id` property is kept) and `Hashable` conformances have no counterpart; data classes provide
  structural equality and hashing.

Foundation behaviour that is reproduced instead of delegated
- `Double.description` ("\(value)") is needed for `SensorFormat.custom` identifiers (`custom-24.0x16.0`), the NPF pixel pitch
  note ("assumed 4.0 µm") and the huge-value fallback of `NumberText.fixedDescription/trimmedDescription`.
  `Double.toString()` is not used because its digits and notation vary by JVM and Android release. `NumberText.swiftDescription`
  computes the shortest digit string that reads back as the same value (closest to the exact value among equals), written as
  Swift writes it: plain decimal such as `36.0`, `0.0001`, `100.0`; exponential such as `1e-05`, `1.5e+20` below 0.0001 and
  above 2^54; `nan`, `inf`, `-inf`; `-0.0`. It matches the JDK 21 `Double.toString` digits on 20,000 random values (a test).
  Not verified against a Swift runtime (no Swift toolchain in this environment): the exact upper switch to exponential
  notation (implemented as above 2^54, from memory of SwiftDtoa); it is unreachable through the public API, which only prints
  values between 1 and 200 and between 1.05 and 5.
- `Unicode.Scalar.Properties.isWhitespace` is the Unicode White_Space property (U+0009 to U+000D, U+0020, U+0085, U+00A0,
  U+1680, U+2000 to U+200A, U+2028, U+2029, U+202F, U+205F, U+3000), spelled out in `AngleTextParserLexing.kt`.
  `Character.isWhitespace` is wrong for this (it excludes the no-break spaces and includes U+001C to U+001F).
- Input is counted in Unicode scalars (code points) via `String.codePointAt`, reading at most 65 of them, so a one-megabyte
  string is rejected after 65 steps. A lone surrogate counts as one scalar and is rejected by the grammar.
- Swift `min`/`max` pick the first argument when the comparison with the second is false, which differs from
  `kotlin.math.min/max` for NaN and signed zeros. `FrameSize.longSide/shortSide` and `ObjectFraming.fillFraction` use
  `swiftMax/swiftMin` because a `FrameSize` may hold any value given to its public initializer
  (`FrameSize(10.0, Double.NaN, 10.0).longSide` is 10.0, as in Swift). Everywhere else the inputs are finite and plain
  `min`/`max` agree.
- `Int64(x)` traps in Swift for NaN and out-of-range values; every conversion keeps the Swift guards (finite and below 2^53, or
  duration below 1e12, or capped at 1e9). `NumberText.powerOfTen` throws `ArithmeticException` where Swift traps on overflow.

Codable
- `SensorFormat`, `LensSetup` and `CompassPoint` use hand-written serializers. Encoding is format independent; decoding needs
  `kotlinx.serialization.json` (it reads a `JsonObject`) and throws `SerializationException` where Swift throws
  `DecodingError`. A document is rejected when a key is missing, a value has the wrong JSON type (quoted numbers, `true`,
  `null` for required values), an `id` or `name` is empty or over 128 UTF-8 bytes, or a size, focal length, aperture or pixel
  pitch is outside its range. Unlike a default `Json` instance, unknown keys are ignored and a `null` or absent
  `pixelPitchMicrometers` means unknown, whatever the `Json` configuration (Swift's `decodeIfPresent` behaviour);
  an unknown pixel pitch is never written (no `"pixelPitchMicrometers":null`).
- `Double` values are written like `36.0` where Foundation's `JSONEncoder` writes `36`; both decode to the same value.
- `CompassPoint` is encoded as its integer raw value (Swift `Int` raw value), not as a name; `ExposureRule` as its string raw value.

Tests (see the report for counts)
- All Swift tests are ported 1:1 under the same names; `FormattingTests.kt` and `PhotoToolsTests.kt` keep the Swift class names.
  Changed only where the Swift API has no equivalent: `XCTUnwrap` is `assertNotNull`; `JSONEncoder/Decoder/Serialization` are
  `Json.encodeToString/decodeFromString/parseToJsonElement`; `leastNonzeroMagnitude` is `Double.MIN_VALUE`; Swift
  `String.count` (grapheme clusters) in `testMultiScalarCharactersCountPerScalar` is `java.text.BreakIterator`, kept next to
  the scalar count; `SplitMix64` uses `ULong` so the random sequences are identical.
- Extra Kotlin-only tests live in `FormattingPortTests.kt` and `PhotoToolsPortTests.kt` (Double description, Unicode
  whitespace, lone surrogates, locale independence under `ar-SA`/`th-TH-u-nu-thai`/..., JSON shape, `Json` configuration
  independence, nested serializable classes, Swift `min`/`max` rules).

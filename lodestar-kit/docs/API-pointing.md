# Pointing (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.pointing`. Port of `LodestarKit/Sources/LodestarKit/Pointing/*.swift`
(DeviceAttitude, ReferenceFrames, MountProfile, AlignmentModel, AlignmentSolver, PointingSolution, Guidance,
PinholeProjection, AttitudeQuaternion, AttitudeSmoother, PointingMath).

Dependencies: only `net.kuecker.lodestar.kit.foundation` (`Vector3`, `Matrix3`, `AstroMath`, the coordinate types,
`swiftMax`/`swiftMin`, `formatFixed`, the `Instant` helpers) and `kotlinx-serialization-json`. Neither Formatting nor
PhotoTools is used. Import with `import net.kuecker.lodestar.kit.pointing.*`.

Conventions used below
- `public` is the default; `internal` is marked. Members of an `internal object/class` are listed without a modifier.
  `Companion` members are listed under their class and called `Foo.bar(...)`, as Swift's `static` members.
- Value types are immutable `data class`es. Where Swift's `init` validates or normalises, the primary constructor is
  private (`@ConsistentCopyVisibility`, so `copy` is private too) and values come from the companion functions shown.
- Angles are degrees unless a name ends in `Radians`. ENU = x East, y North, z Up. Device frame: x right edge, y top edge,
  z out of the screen; the back camera looks along −z. `v_device = matrix · v_reference`.
- Every public declaration has KDoc; the lines below are the signatures only.

Quick usage

```kotlin
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.*

val attitude = DeviceAttitude.create(matrix, referenceAzimuth = 0.0, timestamp = seconds) ?: return   // failable init
val smoother = AttitudeSmoother(timeConstant = 0.12)            // keep one per stream; update() mutates it
val smoothed = smoother.update(attitude)
val mount = MountProfile.backCameraPortrait
val pointing = PointingSolution.solve(smoothed, mount, alignment = AlignmentModel.identity)
val tracker = GuidanceTracker(onTargetThreshold = 0.5)          // keep one per target; reset() on a new target
val guidance = tracker.update(pointing, target = HorizontalCoordinate(azimuth = 120.0, altitude = 35.0))
```

---------------------------------------------------------------------------------------------------------

## DeviceAttitude.kt  (`DeviceAttitude.swift`)

```kotlin
@Serializable(with = DeviceAttitudeSerializer::class) @ConsistentCopyVisibility
data class DeviceAttitude private constructor(val matrix: Matrix3, val referenceAzimuth: Double, val timestamp: Double) {
    val worldToDevice: Matrix3                                   // matrix · referenceTransform(referenceAzimuth)
    val deviceToWorld: Matrix3                                   // worldToDevice.transposed
    fun withHeadingOffset(degrees: Double): DeviceAttitude       // non-finite or 0 → this
    companion object {
        val backCameraAxis: Vector3                              // (0, 0, −1)
        fun create(matrix: Matrix3, referenceAzimuth: Double = 0.0, timestamp: Double = 0.0): DeviceAttitude?   // Swift init?(matrix:referenceAzimuth:timestamp:)
        internal fun fromOrthonormal(matrix: Matrix3, referenceAzimuth: Double, timestamp: Double): DeviceAttitude   // Swift init(orthonormal:…)
        fun referenceTransform(azimuth: Double): Matrix3         // T(ψ)
    }
}
internal object DeviceAttitudeSerializer : KSerializer<DeviceAttitude> { fun decode(element: JsonElement): DeviceAttitude }
```
`create` returns null for non-finite input or a matrix that is not within 0.05 of a rotation; it orthonormalises unless the matrix is
within 1e-12 of one (then it is stored as given). `referenceAzimuth` is normalised to (−180, 180].

## ReferenceFrames.kt  (`ReferenceFrames.swift`)

```kotlin
object ReferenceFrames {
    fun referenceAzimuth(arbitrary: Matrix3, known: Matrix3, knownFrameAzimuth: Double = 0.0): Double?   // positional use: (arbitrary frame, known frame, known azimuth)
}
```

## MountProfile.kt  (`MountProfile.swift`)

```kotlin
@Serializable(with = MountProfileSerializer::class) @ConsistentCopyVisibility
data class MountProfile private constructor(
    val id: String, val name: String, val cameraAxis: Vector3, val cameraUp: Vector3, val screenRight: Vector3, val screenUp: Vector3,
) {
    val cameraRight: Vector3                                     // cameraAxis × cameraUp
    companion object {
        val backCameraPortrait: MountProfile                     // "backCamera.portrait"
        val backCameraPortraitUpsideDown: MountProfile           // "backCamera.portraitUpsideDown"
        val backCameraLandscapeLeft: MountProfile                // "backCamera.landscapeLeft"
        val backCameraLandscapeRight: MountProfile               // "backCamera.landscapeRight"
        val flatOnTopTopEdgeForward: MountProfile                // "flatOnTop.topEdgeForward"
        val flatOnTopTopEdgeBack: MountProfile                   // "flatOnTop.topEdgeBack"
        val presets: List<MountProfile>                          // the six above, in that order
        fun custom(name: String = "Custom", cameraAxis: Vector3, cameraUp: Vector3): MountProfile?
        internal fun make(id: String, name: String, axis: Vector3, up: Vector3): MountProfile?   // the checks shared by custom and decoding
    }
}
internal object MountProfileSerializer : KSerializer<MountProfile> { fun decode(element: JsonElement): MountProfile }
```
`id` is shortened to 64 and `name` to 80 characters (extended grapheme clusters, like Swift's `String.prefix`).

## AlignmentModel.kt  (`AlignmentModel.swift`)

```kotlin
@Serializable(with = AlignmentPointSerializer::class) @ConsistentCopyVisibility
data class AlignmentPoint private constructor(val attitude: DeviceAttitude, val target: HorizontalCoordinate, val timestamp: Instant, val name: String?) {
    companion object {
        operator fun invoke(attitude: DeviceAttitude, target: HorizontalCoordinate, timestamp: Instant, name: String? = null): AlignmentPoint   // name shortened to 120 characters
    }
}

sealed class AlignmentError(message: String) : Exception(message) {
    data object NonFiniteInput : AlignmentError
    data class BoresightTooHigh(val altitude: Double) : AlignmentError
    data class TargetTooHigh(val altitude: Double) : AlignmentError
    data class ImplausibleCorrection(val degrees: Double) : AlignmentError
}

@Serializable(with = AlignmentModelSerializer::class) @ConsistentCopyVisibility
data class AlignmentModel private constructor(
    val headingOffset: Double, val deviceCorrection: Vector3, val points: List<AlignmentPoint>, val residuals: List<Double>,
    val pointingUncertainty: Double?, val calibratedMount: MountProfile?,
) {
    val isAligned: Boolean
    val rmsResidual: Double
    internal val deviceCorrectionMatrix: Matrix3
    fun isApplicable(mount: MountProfile): Boolean               // Swift isApplicable(to:)
    fun headingOffset(mount: MountProfile): Double               // Swift headingOffset(for:)
    @Throws(AlignmentError::class) fun adding(point: AlignmentPoint, mount: MountProfile): AlignmentModel
    @Throws(AlignmentError::class) fun removingPoint(index: Int, mount: MountProfile): AlignmentModel   // Swift removingPoint(at:mount:)
    companion object {
        const val maximumPointCount: Int                         // 12
        const val maximumAltitude: Double                        // 80.0
        const val maximumDeviceCorrection: Double                // 45.0
        val identity: AlignmentModel
        internal operator fun invoke(headingOffset: Double, deviceCorrection: Vector3, points: List<AlignmentPoint>, residuals: List<Double>,
                                     pointingUncertainty: Double?, calibratedMount: MountProfile?): AlignmentModel   // Swift's internal init; normalises the heading, copies the lists
        @Throws(AlignmentError::class) fun solve(points: List<AlignmentPoint>, mount: MountProfile): AlignmentModel
    }
}
internal object AlignmentPointSerializer : KSerializer<AlignmentPoint> { fun decode(element: JsonElement): AlignmentPoint }
internal object AlignmentModelSerializer : KSerializer<AlignmentModel> { fun decode(element: JsonElement): AlignmentModel }
```

## AlignmentSolver.kt  (`AlignmentSolver.swift`, all internal)

```kotlin
internal object AlignmentSolver {
    class Fit(val headingOffset: Double, val deviceCorrection: Vector3, val residuals: List<Double>, val uncertainty: Double)
    fun fit(points: List<AlignmentPoint>, mount: MountProfile): Fit
    // private: measurementSigma, correctionSigma, meanSquaredAltitudeCosine, maximumUncertainty, maximumIterations,
    // maximumDampingAttempts, stepTolerance, differenceStep, closedForm, refine, sumOfSquares, solve (3×3 Gaussian
    // elimination), NormalEquations, Problem (boresights, residualVector, normalEquations, jacobianColumns, uncertainty, fit)
}
```

## PointingSolution.kt  (`PointingSolution.swift`)

```kotlin
@ConsistentCopyVisibility
data class PointingSolution internal constructor(
    val boresight: HorizontalCoordinate, val boresightVector: Vector3, val cameraUpVector: Vector3, val cameraRightVector: Vector3,
    val roll: Double, val screenRightVector: Vector3, val screenUpVector: Vector3,
) {
    companion object {
        fun solve(attitude: DeviceAttitude, mount: MountProfile, alignment: AlignmentModel = AlignmentModel.identity): PointingSolution
    }
}
```

## Guidance.kt  (`Guidance.swift`)

```kotlin
@Serializable enum class GuidanceState(val rawValue: String) {   // JSON: "onTarget" | "close" | "far"
    ON_TARGET("onTarget"), CLOSE("close"), FAR("far");
    companion object { fun fromRawValue(raw: String): GuidanceState? }
}

@ConsistentCopyVisibility
data class Guidance private constructor(
    val separation: Double, val panDelta: Double, val tiltDelta: Double, val arrowAngle: Double,
    val isTargetInFront: Boolean, val isNearZenith: Boolean, val targetBelowHorizon: Boolean, val state: GuidanceState,
) {
    companion object {
        const val nearZenithAltitude: Double                     // 85.0
        internal fun create(solution: PointingSolution, target: HorizontalCoordinate, direction: Vector3, separation: Double, state: GuidanceState): Guidance
    }
}

class GuidanceTracker(onTargetThreshold: Double, closeThreshold: Double? = null) {   // a class, see "Stateful filters"
    val onTargetThreshold: Double
    val closeThreshold: Double
    fun update(solution: PointingSolution, target: HorizontalCoordinate): Guidance?   // null for a non-finite target
    fun reset()
    override fun equals(other: Any?): Boolean                    // thresholds + on-target state (Swift Hashable)
    override fun hashCode(): Int
    override fun toString(): String
    companion object {
        const val hysteresisFactor: Double                       // 1.5
        fun defaultOnTargetThreshold(horizontalFieldOfView: Double, verticalFieldOfView: Double): Double
    }
}
```

## PinholeProjection.kt  (`PinholeProjection.swift`)

```kotlin
@ConsistentCopyVisibility
data class ProjectedPoint internal constructor(val x: Double, val y: Double, val depth: Double)

@ConsistentCopyVisibility
data class PinholeProjection private constructor(val viewportWidth: Double, val viewportHeight: Double, val focalLength: Double) {
    val longSideFieldOfView: Double
    val horizontalFieldOfView: Double
    val verticalFieldOfView: Double
    fun project(worldDirection: Vector3, attitude: DeviceAttitude, headingOffset: Double): ProjectedPoint?
    fun project(worldDirections: List<Vector3>, attitude: DeviceAttitude, headingOffset: Double): List<ProjectedPoint?>
    fun unproject(x: Double, y: Double, attitude: DeviceAttitude, headingOffset: Double): Vector3?
    fun contains(point: ProjectedPoint, margin: Double = 0.0): Boolean
    // Kotlin additions for the hot path (see "Per-frame use"):
    fun contains(x: Double, y: Double, margin: Double = 0.0): Boolean
    fun projectInto(x: Double, y: Double, z: Double, worldToDevice: Matrix3, out: DoubleArray, outIndex: Int = 0): Boolean
    fun projectInto(x: Double, y: Double, z: Double, worldToDevice: Matrix3, out: FloatArray, outIndex: Int = 0): Boolean
    fun projectAllInto(directions: DoubleArray, count: Int, worldToDevice: Matrix3, out: DoubleArray, visible: BooleanArray? = null): Int
    fun projectAllInto(directions: DoubleArray, count: Int, worldToDevice: Matrix3, out: FloatArray, visible: BooleanArray? = null): Int
    fun unprojectInto(x: Double, y: Double, deviceToWorld: Matrix3, out: DoubleArray, outIndex: Int = 0): Boolean
    companion object {
        fun create(viewportWidth: Double, viewportHeight: Double, focalLength: Double): PinholeProjection?                      // Swift init?(…focalLength:)
        fun fromLongSideFieldOfView(viewportWidth: Double, viewportHeight: Double, longSideFieldOfView: Double): PinholeProjection?   // Swift init?(…longSideFieldOfView:)
        fun dslrFrameOutline(solution: PointingSolution, horizontalFOV: Double, verticalFOV: Double, samplesPerEdge: Int): List<Vector3>
        fun worldToDevice(attitude: DeviceAttitude, headingOffset: Double): Matrix3     // Kotlin addition: attitude.withHeadingOffset(headingOffset).worldToDevice
        fun deviceToWorld(attitude: DeviceAttitude, headingOffset: Double): Matrix3     // Kotlin addition
    }
}
```

## AttitudeQuaternion.kt  (`AttitudeQuaternion.swift`, internal)

```kotlin
internal class AttitudeQuaternion private constructor(var w: Double, var x: Double, var y: Double, var z: Double) {
    val matrix: Matrix3
    val normalized: AttitudeQuaternion
    fun dot(other: AttitudeQuaternion): Double
    fun angle(other: AttitudeQuaternion): Double                 // degrees; Swift angle(to:)
    fun slerp(other: AttitudeQuaternion, t: Double): AttitudeQuaternion   // Swift slerp(to:_:)
    companion object { operator fun invoke(m: Matrix3): AttitudeQuaternion }   // Swift init(_ m: Matrix3), Shepperd's method
}
```

## AttitudeSmoother.kt  (`AttitudeSmoother.swift`)

```kotlin
class AttitudeSmoother(timeConstant: Double = 0.12, maximumLag: Double = 0.5, jumpThreshold: Double = 20.0, maximumTimeGap: Double = 0.5) {   // a class, see "Stateful filters"
    val timeConstant: Double                                     // non-finite or non-positive arguments fall back to the defaults
    val maximumLag: Double
    val jumpThreshold: Double
    val maximumTimeGap: Double
    fun update(attitude: DeviceAttitude): DeviceAttitude         // Swift update(with:)
    fun reset()
}
```

## PointingMath.kt  (`PointingMath.swift`, internal)

```kotlin
internal object PointingMath {
    val worldUp: Vector3
    fun rotationMatrix(rotationVector: Vector3): Matrix3
    fun yawed(vector: Vector3, degrees: Double): Vector3         // Swift yawed(_:byAzimuth:)
    fun isZero(vector: Vector3): Boolean                         // Kotlin addition: IEEE `vector == .zero` (−0.0 counts as zero)
}
```

## PointingSupport.kt  (Kotlin only, internal)

```kotlin
internal fun String.prefixCharacters(maxLength: Int): String     // Swift String.prefix(_:) — counts extended grapheme clusters
internal fun Decoder.decodeJsonTree(what: String): JsonElement   // the serializers decode from the JSON tree
internal fun JsonElement.toJsonObject(what: String): JsonObject
internal fun JsonElement.toJsonArray(what: String): JsonArray
internal fun JsonObject.requiredField(key: String): JsonElement
internal fun JsonObject.optionalField(key: String): JsonElement?     // absent or JSON null → null (Swift decodeIfPresent)
internal fun JsonElement.toStrictString(what: String): String
internal fun JsonElement.toStrictDouble(what: String): Double        // rejects quoted numbers, true/false, null, NaN, hex, overflow
internal fun JsonElement.toVector3(what: String): Vector3
internal fun JsonElement.toMatrix3(what: String): Matrix3
```

---------------------------------------------------------------------------------------------------------

## Per-frame use: allocation-free overloads (Kotlin additions)

`project`/`unproject` keep the Swift signatures. A frame with thousands of stars should use the primitive overloads instead; they
compute exactly the same numbers (identical arithmetic, tested bit for bit against the regular API) and allocate nothing per call.

```kotlin
val rotation = PinholeProjection.worldToDevice(attitude, headingOffset)    // once per frame (the overlay applies the heading offset)
val out = FloatArray(2 * stars.size)                                       // reuse across frames
for (i in stars.indices) {
    val visible = projection.projectInto(x[i], y[i], z[i], rotation, out, 2 * i)   // out[2i], out[2i+1] = screen x, y; false if behind the camera
    // …
}
// or, with the directions packed as x0,y0,z0,x1,…
val projected = projection.projectAllInto(xyz, count, rotation, out, visible)       // NaN for directions that are not in front
```

- `projectInto(x, y, z, worldToDevice, out, outIndex)`: any non-zero ENU direction (it is normalised). Returns false and writes nothing
  when the direction is behind the camera, grazes the image plane (cos < 1e-6), is zero, or is not finite.
- `projectAllInto(directions, count, worldToDevice, out, visible)`: `directions` holds `count` consecutive `x, y, z` triples, `out`
  gets `x, y` pairs. Directions that are not projected get `NaN, NaN` (so stale values never survive in a reused array); `visible[i]`
  (optional) tells which were projected; the result is how many. `IllegalArgumentException` when an array is too short.
- `unprojectInto(x, y, deviceToWorld, out, outIndex)`: writes the ENU unit vector seen at the viewport position; false for non-finite input.
- `worldToDevice` may be any rotation from the frame the directions are written in to the device frame, so a caller that holds J2000
  unit vectors can pass `PinholeProjection.worldToDevice(attitude, offset) * skyFrame.j2000ToHorizontal` once per frame and skip the
  per-star conversion (aberration and refraction are then not applied).
- Float variants convert the Double result with `toFloat()`.

Measured on JDK 21 after warm-up, 5 000 stars per frame: `project(List)` 0.39 ms and 120 KB of garbage; `project(Vector3,…)` per star
1.7 ms and 1.5 MB; `projectInto` 0.12 ms (0.16 ms for Float) and 288 bytes (the rotation); `projectAllInto` 0.08 ms. `PointingSolution.solve`,
`GuidanceTracker.update` and `AttitudeSmoother.update` take 3–6 µs and 0.2–2 KB per call; `Guidance` creation allocates only the result and
the target's unit vector.

## Stateful filters

Swift's `AttitudeSmoother` and `GuidanceTracker` are structs with `mutating` methods. In Kotlin both are plain classes holding private mutable
state: `update`/`reset` change the object, two references to one instance share the filter, and instances are **not thread safe** (call from one
thread, or synchronize externally). Create one per stream/target and keep it. `GuidanceTracker` implements `equals`/`hashCode` over its
thresholds and on-target flag (Swift `Hashable`); `AttitudeSmoother` uses identity equality (Swift: not `Equatable`). All other structs stay
immutable data classes.

## Renames & deviations from Swift

Initializers and factories
| Swift | Kotlin |
| --- | --- |
| `DeviceAttitude(matrix:referenceAzimuth:timestamp:)` (failable) | `DeviceAttitude.create(matrix, referenceAzimuth = 0.0, timestamp = 0.0)` |
| `DeviceAttitude(orthonormal:referenceAzimuth:timestamp:)` (internal) | `DeviceAttitude.fromOrthonormal(matrix, referenceAzimuth, timestamp)` (internal) |
| `PinholeProjection(viewportWidth:viewportHeight:focalLength:)` (failable) | `PinholeProjection.create(viewportWidth, viewportHeight, focalLength)` |
| `PinholeProjection(viewportWidth:viewportHeight:longSideFieldOfView:)` (failable) | `PinholeProjection.fromLongSideFieldOfView(viewportWidth, viewportHeight, longSideFieldOfView)` |
| `AlignmentPoint(attitude:target:timestamp:name:)` | `AlignmentPoint(attitude, target, timestamp, name = null)` (companion `invoke`; the constructor is private) |
| `AlignmentModel(headingOffset:…)` (internal) | `AlignmentModel(headingOffset, …)` (internal companion `invoke`) |
| `AttitudeQuaternion(_ m:)` | `AttitudeQuaternion(m)` (companion `invoke`) |
| `Guidance(solution:target:state:)` (fileprivate) | `Guidance.create(solution, target, direction, separation, state)` (internal; the tracker passes the two values it has computed already) |
| `PointingSolution(…)`, `ProjectedPoint(…)` memberwise (internal) | internal constructors |
| `MountProfile.custom(name:cameraAxis:cameraUp:)` | `MountProfile.custom(name = "Custom", cameraAxis, cameraUp)` — call with named arguments when `name` is omitted |

Members and parameters
- Labels vanish (rule §3): `update(with:)` → `update(attitude)`, `isApplicable(to:)` → `isApplicable(mount)`, `headingOffset(for:)` → `headingOffset(mount)`,
  `removingPoint(at:mount:)` → `removingPoint(index, mount)`, `slerp(to:_:)` → `slerp(other, t)`, `angle(to:)` → `angle(other)`,
  `yawed(_:byAzimuth:)` → `yawed(vector, degrees)`, `referenceAzimuth(ofArbitraryFrameAttitude:knownFrameAttitude:knownFrameAzimuth:)` →
  `referenceAzimuth(arbitrary, known, knownFrameAzimuth)` (the Swift internal names).
- Kept label: `PointingMath.rotationMatrix(rotationVector)` (the Swift internal name `w` is meaningless).
- `AlignmentModel` has a property `headingOffset` and a function `headingOffset(mount)`, as in Swift.
- Types: `Date` → `java.time.Instant` (`AlignmentPoint.timestamp`), `TimeInterval` → `Double` seconds, `[T]` → `List<T>` (copied when a model is built).
- `AlignmentError` is a `sealed class … : Exception` (not an enum): `AlignmentError.NonFiniteInput` (data object), `.BoresightTooHigh(altitude)`,
  `.TargetTooHigh(altitude)`, `.ImplausibleCorrection(degrees)`. Cases are values (data class equality) and carry an English `message`
  (`NonFiniteInput` is a singleton, so its stack trace is that of its creation).
  `solve`, `adding` and `removingPoint` declare `@Throws(AlignmentError::class)`; Swift's `try` is just a call.
- `GuidanceState`: `ON_TARGET`, `CLOSE`, `FAR`; `rawValue` and `fromRawValue`; JSON values `"onTarget"`, `"close"`, `"far"` unchanged.
- `Hashable` structs → data classes (`Double` fields compare with `equals`: `NaN == NaN`, `0.0 != -0.0`). Do not rely on either.
- `AttitudeQuaternion` keeps mutable `w/x/y/z` (Swift `var`s; a test negates a quaternion in place) and is internal.
- Swift `fileprivate`/`private` members are `private` (the file-level ones in `Guidance.kt` are the companion's).

Behaviour kept from Swift on purpose
- Guards keep their NaN behaviour (`guard a <= b` is `if (!(a <= b))`); `min`/`max` on `Double` are `swiftMin`/`swiftMax`.
- Vectors are compared to zero with IEEE equality (`PointingMath.isZero`): a vector whose length overflows (components ≥ 1e154) normalises to
  `±0.0`, which is "zero" for `MountProfile.custom(cameraAxis = …)` and `project`. As in Swift, a finite but astronomically large `cameraUp`
  is not rejected (it gives a degenerate profile).
- `String.prefix` counts extended grapheme clusters (`BreakIterator`, `Locale.ROOT`): names are cut at 120 (points), 64 (ids) and 80 (mount
  names) *characters*, never inside a surrogate pair or combining sequence. The segmentation rules are the runtime's: JDK 20+ and Android's ICU
  implement UAX #29 extended grapheme clusters like Swift (emoji ZWJ sequences, flags and skin tones are one character; checked on JDK 21),
  older JDKs only the simpler legacy rules.
- `AlignmentModel.solve` keeps the newest 12 points, rejects points above 80°, and rejects a correction longer than 45° (including NaN).

Serialization (`@Serializable`, kotlinx.serialization)
- Same keys as Swift's `Codable`: `DeviceAttitude {matrix, referenceAzimuth, timestamp}`, `MountProfile {id, name, cameraAxis, cameraUp}` (the screen
  directions are recomputed), `AlignmentPoint {attitude, target, timestamp, name?}`, `AlignmentModel {headingOffset, deviceCorrection, points, residuals,
  pointingUncertainty?, calibratedMount?}`, `GuidanceState` as its raw string. `HorizontalCoordinate`/`Vector3`/`Matrix3` use Foundation's forms.
- `AlignmentPoint.timestamp` is a JSON number: seconds since 2001-01-01T00:00:00Z, which is how Swift's `JSONEncoder` writes a `Date` by default
  (the conversion goes through `Double`, so an `Instant` with sub-microsecond digits is not reproduced exactly).
- Missing optionals (`name`, `pointingUncertainty`, `calibratedMount`) are left out, whatever `encodeDefaults`/`explicitNulls` say; `null` or absent
  means "none" on reading (Swift `decodeIfPresent`).
- Decoding has Swift's validation and throws `SerializationException` where Swift throws `DecodingError`: attitudes must be finite and within 0.05 of a
  rotation; mounts pass the checks of `custom`; points need azimuth in [0°, 360°) and altitude in [−90°, 90°]; models need |heading| ≤ 180, correction
  ≤ 45°, ≤ 12 points, one non-negative residual per point, a non-negative uncertainty exactly when there are points. The decoders read the JSON
  tree: unknown keys are ignored whatever the `Json` configuration, and a quoted number, `true`, `null` for a required value, a missing key or a number
  that does not fit a `Double` is rejected. Decoding is JSON only (encoding is format independent).
- `Guidance`, `GuidanceTracker`, `PointingSolution`, `PinholeProjection`, `ProjectedPoint` and `AttitudeSmoother` are not serializable (not `Codable` in Swift).

Numerical fidelity
- Formulas, constants and evaluation order are copied from Swift. `sin`/`cos`/`exp`/`atan2` come from `kotlin.math` (JVM intrinsics) and can differ
  from Apple's or glibc's libm in the last bit; every ported test passes with its Swift tolerance except one: `testStepResponseNeverOvershoots` compares a
  distance that is only resolved to ~1.2e-6° (acos near 1) with 1e-9 slack, which flickers with the last bit of the platform's libm; the Kotlin test allows
  2e-6° there (the error itself still shrinks by 13 % per sample).

## Tests

`src/test/kotlin/.../pointing/` — 1:1 ports of the ten Swift files (141 tests, same names, inputs, expected values and tolerances; shared helpers
`PointingTestRandom` (SplitMix64 on `ULong`, identical sequences), `PointingTestSupport`, `AlignmentTestScenario` and `ExpectedMount` live in the files
that define them in Swift) plus `PointingPortTests.kt` (26 Kotlin-only tests: bit-for-bit comparison of the allocation-free overloads with the
regular API, a heap-allocation check, JSON forms and strictness, grapheme-cluster truncation, IEEE zero checks, filter independence). Fixture:
`src/test/resources/fixtures/stars_apparent.json` (identical to the Foundation copy).

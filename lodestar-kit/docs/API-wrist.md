# Wrist (Kotlin) — API reference

Package `net.kuecker.lodestar.kit.wrist`. Kotlin only: the iOS twin is `AimWristMessage` in the iOS repository's `WidgetShared/Core` (it travels as a
WatchConnectivity dictionary, here as one line of text), not in LodestarKit.

## AimWristMessage.kt

What the phone sends the watch's Aim page while it aims, and the words the page shows.

```kotlin
data class AimWristMessage(sent: Double, pan: Double, tilt: Double, separation: Double, state: GuidanceState,
                           isBelowHorizon: Boolean, isNearZenith: Boolean, isNight: Boolean, targetName: String) {
    val arrowAngle: Double        // radians counter-clockwise from "pan right"; near the zenith the pan is ignored while there is a tilt
    val isOnTarget: Boolean       // state == ON_TARGET and not below the horizon
    val moves: List<String>       // "Pan right 12°", "Tilt up 3°"; "Almost there" within 2° or with nothing to move
    val distanceText: String      // "26° away", one decimal below 10°
    val spokenSummary: String     // "Pan right 12°, Tilt up 3°, 12° away, Orion Nebula"
    fun encode(): ByteArray       // UTF-8 "LA1|sent|pan|tilt|separation|state|below|zenith|night|target" (flags 0/1, name cut to 40 characters)
    companion object {
        const val PATH = "/lodestar/aim"            // the data layer message path
        const val CAPABILITY = "lodestar_wear_aim"  // declared by the watch app (res/values/wear.xml)
        const val MAXIMUM_NAME_LENGTH = 40
        fun of(guidance: Guidance, targetName: String, isNight: Boolean, sent: Instant): AimWristMessage
        fun decode(bytes: ByteArray): AimWristMessage?   // null: not an aim message, or a value not finite / out of range
    }
}
```
Decoding is defensive: version tag `LA1`, ten fields, |pan| and |tilt| at most 180, separation within 0...180, a known state name.

## Tests

`src/test/kotlin/.../wrist/AimWristMessageTests.kt` mirrors the iOS `AimWristMessageTests` (same inputs and expected words) plus the encoding.

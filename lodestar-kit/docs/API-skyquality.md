# Sky quality: light pollution map, Bortle estimate, imaging score (Swift + Kotlin) — API reference

An offline estimate of how dark the sky is at a site, from a bundled night-lights grid, and a score for how good a night is
for imaging. Both engines implement the same API (Swift `LodestarKit`, Kotlin `net.kuecker.lodestar.kit.skyquality`).

| Piece | Swift (`LodestarKit`) | Kotlin (`net.kuecker.lodestar.kit.skyquality`) |
|---|---|---|
| Map, estimate, directions, error | `SkyQuality/LightPollutionMap.swift` | `skyquality/LightPollutionMap.kt` |
| Imaging score | `SkyQuality/SkyQualityScore.swift` | `skyquality/SkyQualityScore.kt` |
| Data | `Resources/lightpollution.bin` | `resources/net/kuecker/lodestar/kit/catalog/lightpollution.bin` |
| Generator | `Tools/build_light_pollution.py` (iOS repository), pinned in `Tools/lightpollution.lock.json` | same file |

Imports: Swift `import LodestarKit`. Kotlin `import net.kuecker.lodestar.kit.skyquality.*` (the list extensions `darkest`,
`darkestHint` and `horizonGlow` are top-level and come with the star import).

Depends on Foundation (`GeographicLocation`, `AstroMath`), Formatting (`CompassPoint`) and Planning (`NightSummary`). No
network, no state. Naming: Swift `struct` with `init(skyGlowIndex:level:)` is Kotlin `BortleEstimate(skyGlowIndex, level)`
(a `companion operator fun invoke`); Swift `init(score:)` of `ImagingRating` is Kotlin `ImagingRating(score)`; Swift
`init?(rawValue:)` is Kotlin `fromRawValue`; result types have an `internal` constructor in both languages; Swift extension
on `[DirectionBrightness]` is a Kotlin extension on `List<DirectionBrightness>`.

## The data

`lightpollution.bin` is a 3600 x 1800 grid of 0.1 degree cells (about 11 km; row 0 is the northernmost, column 0 starts at
180 W) with 16 levels, 0 (dark) to 15 (the centre of a big city), as 16 header bytes and run-length encoded rows; 137 kB.
Source: **NASA GIBS, layer `VIIRS_Black_Marble`** (Suomi NPP VIIRS day/night band composite of 2016-01-01), credit "NASA
Worldview / GIBS, Black Marble (VIIRS, Suomi NPP)". The generator removes the land's base texture, averages the light per
cell and quantizes it on a log scale (level 0 is below 0.0025 of the display's full scale). The format is described in the
script's docstring. `load()` expands the file to 6.5 MB (one byte per cell) in about 10 ms: load it once, off the main thread.

## Quick usage

```swift
let map = try LightPollutionMap.load()
let here = GeographicLocation(latitude: 41.66, longitude: -77.82)
let estimate = map.bortleEstimate(at: here)        // BortleEstimate(bortleClass: 2, ...)
estimate.summary                                   // "Bortle ~2 (typical dark sky)"
BortleEstimate.caveat                              // show it with every estimate
let ring = map.darkestDirection(around: here)      // 16 DirectionBrightness, north first
ring.darkestHint?.direction                        // .northwest, or nil when every direction is alike
ring.horizonGlow(azimuth: 300)                     // 0...1 for the ring of a sky chart
let score = SkyQualityScore.imaging(night: summary, bortle: estimate, cloudFraction: nil)
score.total                                        // 0...10; score.components has the parts
```

```kotlin
val map = LightPollutionMap.load()
val here = GeographicLocation(41.66, -77.82)
val estimate = map.bortleEstimate(here)
estimate.summary
BortleEstimate.caveat
val ring = map.darkestDirection(here)
ring.darkestHint?.direction
ring.horizonGlow(300.0)
val score = SkyQualityScore.imaging(summary, estimate, cloudFraction = null)
```

## LightPollutionMap

| Swift | Kotlin | Notes |
|---|---|---|
| `static func load() throws -> LightPollutionMap` | `fun load(): LightPollutionMap` | throws `SkyQualityError.lightPollutionMapUnavailable(reason:)` / `LightPollutionMapUnavailable(reason)` for a missing, oversized (> 1 MiB) or malformed file |
| `func level(at: GeographicLocation) -> Int` | `fun level(at)` | the nearest cell, 0-15; a lookup |
| `func skyGlowIndex(at:) -> Double` | `fun skyGlowIndex(at)` | the glow model below; tens of microseconds |
| `func bortleEstimate(at:) -> BortleEstimate` | `fun bortleEstimate(at)` | glow index to class |
| `func darkestDirection(around:radiusKm: = 30) -> [DirectionBrightness]` | `fun darkestDirection(around, radiusKm = 30.0)` | 16 rays; radius clamped to 1-200 km (30 when not finite); 16 x ceil(radius / 5) lookups |
| constants `width` 3600, `height` 1800, `cellDegrees` 0.1, `levelCount` 16, `maximumLevel` 15, `glowRadiusKm` 100, `glowCoreKm` 5.5, `kilometersPerDegree` 111.195, `defaultRayKm` 30, `maximumFileBytes`, `resourceFileName`, `levelLuminance` | same names | |

**Cell of a place.** `row = clamp(floor((90 - latitude) * 10), 0, 1799)`, `column = floor((longitude + 180) * 10) mod 3600`
(so 180 E is column 0 and latitudes beyond the pole stay on the pole row).

**Sky glow.** `skyGlowIndex = sum over cells within 100 km of levelLuminance[level] * cos(cell latitude) * (max(d, 5.5 km) / 5.5 km)^-2.5`,
`d` the distance from the site to the cell's centre (east-west with the cosine of the mean latitude). `levelLuminance` is
`[0, 0.002997, 0.004306, 0.006188, 0.008891, 0.01278, 0.01836, 0.02638, 0.03791, 0.05447, 0.07827, 0.1125, 0.1616, 0.2322, 0.3337, 0.4795]`,
the geometric middle of each level's range. A megacity gives about 1; a dark site 0. About 1,300 cells at mid latitudes.

**Directions.** Ray `k` runs along azimuth `22.5 k` degrees (great circle, Earth radius 6371.0088 km); the mean level is taken
at `radius * (i + 0.5) / n` for `i < n`, `n = max(1, ceil(radius / 5))`, each sample the nearest cell.

## BortleEstimate and DirectionBrightness

| Member | Meaning |
|---|---|
| `bortleClass` | 1 (excellent dark sky) to 9 (inner-city sky) |
| `level` | the level of the site's own cell, 0-15 |
| `skyGlowIndex` | the glow index the class was taken from |
| `label` | "Excellent dark sky", "Typical dark sky", "Rural sky", "Rural/suburban transition", "Suburban sky", "Bright suburban sky", "Suburban/urban transition", "City sky", "Inner-city sky" |
| `summary` | `"Bortle ~4 (rural/suburban transition)"` |
| `BortleEstimate.caveat` | "Estimated from 2016 satellite night-light imagery; not a sky measurement" |
| `BortleEstimate.glowThresholds` | `[0.00045, 0.0012, 0.008, 0.02, 0.06, 0.15, 0.3, 0.5]`: where classes 2 to 9 begin; strictly increasing |
| `BortleEstimate.bortleClass(forGlowIndex:)`, `label(forClass:)` | 1 + the thresholds reached; the label of a class (limited to 1-9) |
| `DirectionBrightness.direction`, `meanLevel`, `azimuth`, `glow` | the compass point, the mean level 0-15, its centre azimuth, `meanLevel / 15` |
| `[DirectionBrightness].darkest` | the lowest mean level, the first on a tie; `nil` for an empty list |
| `[DirectionBrightness].darkestHint` | `darkest`, only when it is at least one level darker than the brightest direction |
| `[DirectionBrightness].horizonGlow(azimuth:)` | `glow` interpolated linearly between the two nearest directions, wrapping at north; 0 for an empty list or a non-finite azimuth |

A glow index that is negative or not a number counts as 0; a level is limited to 0-15.

**Calibration.** The thresholds were chosen from 25 reference places (table in the documentation of `LightPollutionMap` and in
`Tools/README.md`): 21 fall in their published Bortle class; Joshua Tree (estimate 4, published 2-3), Green Bank (2, published 3),
Roque de los Muchachos (3, published 1) and Plano (9, published 7-8) do not. The class is good to about one class in a city and two
in the dark, where a satellite cannot tell Bortle 1 from 3. Always show the caveat.

## SkyQualityScore

`SkyQualityScore.imaging(night:bortle:cloudFraction:) -> ImagingScore` (Kotlin: `imaging(night, bortle, cloudFraction)`), and
`imaging(astronomicalDarkHours:moonFreeDarkHours:moonIlluminatedFraction:bortle:cloudFraction:)` for the same from its inputs.
The score is 0 to 10, the sum of the points of its parts:

| Part (`ImagingScoreComponentKind`) | Weight | Quality, 0 to 1 |
|---|---|---|
| `darknessHours` | 2 | astronomical dark hours / 6, at most 1 (0 with no astronomical darkness) |
| `moon` | 3 | `1 - (dark - moonFree) / dark * illuminatedFraction` (0 with no astronomical darkness) |
| `skyBrightness` | 2 | `(9 - bortleClass) / 8` |
| `clouds` (only when `cloudFraction` is known) | 3 | `1 - cloudFraction` (limited to 0-1; NaN counts as unknown) |

Without clouds the weights are scaled to 10 (20/7, 30/7, 20/7). `ImagingScoreComponent` has `kind`, `quality`, `weight` (the
scaled weight) and `points = weight * quality`; `ImagingScore` has `total`, `components`, `includesClouds`, `rating`
(`ImagingRating`: poor below 3, fair below 5.5, good below 8, excellent) and `component(_:)`. Negative or non-finite hours and
fractions count as 0.

## Tests

Mirrored in `SkyQualityLightPollutionMapTests` and `SkyQualityScoreTests` (Swift and Kotlin, same names and values): the decoder
round trip on a synthetic grid and on runs of every length, malformed files, cell indexing at the edges and across the antimeridian,
ocean cells are 0, big cities are 12 or more (14 or more for the largest), the 25 reference places (levels, glow indices, classes),
the Boulder rays against the generator's own Python model, monotonic thresholds, the glow of one lit cell against the formula, the
darkest direction and the ring on a synthetic gradient, and the score parts (sums, exact values, limits, monotonicity, a real new-moon
night against a full-moon night).

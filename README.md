# Lodestar for Android

**Aim your camera at the night sky.** Lodestar turns a phone clamped to your camera into a smart finder: pick a
planet, nebula, galaxy or star, and on-screen arrows tell you which way to pan and tilt the tripod head until it
is in your frame.

This is the Android version of [Lodestar for iPhone](https://github.com/feetball/Lodestar): the same app, with the
same astronomy engine ported line for line from Swift to Kotlin.

## Features

- **Guided aiming** — a live finder (camera view or virtual sky) with the target marker, an arrow pointing the
  shortest way, "Pan right 23.4° / Tilt up 12.1°" read-outs for pan-tilt heads, an on-target state with haptics,
  optional audio ticks, a level gauge, and the outline of *your* camera's frame.
- **Star alignment** and **Gyro Lock** — centre a bright star once to correct the compass and the bracket; keep
  the heading on the gyroscope, safe from the camera body's magnetism.
- **Any mount** — back camera along the lens (portrait, landscape, upside down) or the phone flat on top.
- **Objects** — 9,000+ stars and 690+ galaxies, nebulae and clusters (all 110 Messier objects), the planets, the
  Moon and the Milky Way core; search, group, sort, favourites, custom RA/Dec targets; rise/set, altitude chart,
  framing and longest exposure for each object.
- **Sky chart** with time travel, **Tonight** planning (darkness windows, Moon, Milky Way core, planets, best
  targets, polar-scope clock), **night mode** (pure red, automatic after sunset, screen dimming, red camera view).
- **Events calendar** — meteor showers (with hourly rates and the radiant's altitude from your site), lunar and
  solar eclipses, conjunctions, oppositions and elongations, supermoons, equinoxes and solstices, each with when
  and where to look and a button to aim at it.
- **Photo planners** — a mosaic planner for panoramas such as the Milky Way arch (panels, overlap and shooting
  order, guided panel by panel in Aim), and star-trail and time-lapse planners.
- **Real skyline** — sweep the phone along the horizon to record trees, buildings and mountains; rise and set
  times, Tonight and the charts then use the site's own skyline.
- **Comets and asteroids** — 216 bright asteroids built in and, optionally, the daily comet file: brightness
  estimates, elongation warnings, a Bright Comets list, and the direction of a comet's ion tail with the camera
  tilt that lays it along the frame.
- **GoTo mounts** — connect over Wi-Fi to OnStep/LX200, Celestron NexStar and SynScan, ASCOM Alpaca and the
  Sky-Watcher AZ-GTi / Star Adventurer GTi (with home position and star alignment); Go to, Sync, nudge arrows and
  an always-available Stop, with altitude limits and a built-in simulated mount to try it without hardware.
- **Optional online data** — all off until switched on in Settings: ISS and bright-satellite passes (follow one in
  Aim), Starlink streak warnings for the planned exposure, fresh comet orbits, and tonight's cloud forecast (MET
  Norway, location rounded to about 10 km).
- **Home-screen widgets** — Tonight at a glance (dark hours, the Moon, the Milky Way core, the next event) in
  two resizable sizes, worked out on the device; a tap opens Tonight.
- **Offline and private** — catalogs and ephemerides are on the device; nothing is collected. The optional online
  data downloads from CelesTrak, the Minor Planet Center and MET Norway only when you turn it on, and a GoTo mount
  is reached over the local network only.

## Project layout

```
android/
  lodestar-kit/     Pure Kotlin/JVM astronomy engine (port of the Swift LodestarKit): coordinates, precession,
                    nutation, aberration, refraction, VSOP87B planets, ELP/Meeus Moon, rise/set, twilight, Moon
                    phases, catalog + search, aiming math, alignment solver, WMM2025, exposure tools, planning,
                    events, skyline, mosaics, satellites (SGP4), comets and asteroids, GoTo mount protocols.
                    1,752 unit tests (every LodestarKit test ported, plus Kotlin-only checks); docs/API-*.md.
  app/              Android app (Jetpack Compose, Material 3, CameraX, MapLibre, Glance widgets).
    app/            AppModel (state + service orchestration), activity, root screen
    model/ services/ theme/ shared/   settings, sensors/location/camera, night palette, shared views
    features/       aim, objects, skychart, tonight, settings, onboarding, tools, events, mosaic, horizon,
                    mount, smallbodies, satellites, online, widget
  tools/            Icon, feature-graphic and screenshot scripts
  PlayStore/        Store listing text and graphics
  docs/             GitHub Pages: privacy policy and support
```

## Building

Requirements: JDK 21 and the Android SDK (platform 37). Then:

```sh
./gradlew :lodestar-kit:test          # engine tests (JVM)
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:bundleRelease          # release bundle (unsigned unless signing properties are set)
```

Release signing reads Gradle properties, never files in the repository: `lodestar.signing.storeFile`,
`lodestar.signing.storePassword`, `lodestar.signing.keyAlias` (default `upload`), `lodestar.signing.keyPassword`.

Debug builds have a screenshot mode (`tools/capture_screenshots.sh`) that fixes the site, time, target and
pointing; the *Emulator* workflow runs it on GitHub Actions and uploads the images.

## Sensors and accuracy

Attitude comes from Android's rotation vector (magnetic north, corrected to true north with the bundled NOAA
WMM2025 model) or, with Gyro Lock, from the game rotation vector, whose heading is transferred from the compass
while the phone stands still. Positions are apparent, topocentric and refracted; against JPL ephemerides the Sun
is good to about 0.3″, the planets to a few arcseconds and the Moon to under 10″ — far below the accuracy of any
phone compass, which star alignment corrects.

## Privacy

No accounts, analytics, ads or tracking. Location, camera and sensors are used on the device only. Network use:
map tiles from OpenFreeMap when the site map is open, and the phone's geocoding service to find a saved site's
time zone. Policy: <https://feetball.github.io/Lodestar-Android/privacy.html>.

## Credits

Star, deep-sky, constellation and Milky Way data, the magnetic model and the software libraries are credited in
[ATTRIBUTION.md](ATTRIBUTION.md).

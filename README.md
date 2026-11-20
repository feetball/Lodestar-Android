# Lodestar for Android

**Aim your camera at the night sky.** Lodestar turns a phone clamped to your camera into a smart finder: pick a
planet, nebula, galaxy or star, and on-screen arrows tell you which way to pan and tilt the tripod head until it
is in your frame.

This is the Android version of [Lodestar for iPhone](https://github.com/feetball/Lodestar): the same app, with the
same astronomy engine ported line for line from Swift to Kotlin.

## Features

- **Guided aiming** — a live finder (camera view or virtual sky) with the target marker, an arrow pointing the
  shortest way, "Pan right 23.4° / Tilt up 12.1°" read-outs for pan-tilt heads, an on-target state with haptics,
  optional audio ticks, optional spoken guidance ("Pan right 12, tilt up 3 … Almost there … On target") for when
  the phone is above eye level, a level gauge, and the outline of *your* camera's frame. Keep a **camera profile**
  for each body and lens, start from a smart-telescope preset (Seestar, Dwarf, Vespera) or read a profile from a
  photo's EXIF data, and switch with one tap; a **fisheye** lens gets a curved or circular frame. A time bar previews
  the sky at a later (or earlier) time, up to 48 hours either way, so you can frame the Milky Way core or a planet
  for 2 am at 9 pm and lock the head; the phone's pointing stays live.
- **Star alignment** and **Gyro Lock** — centre a bright star once to correct the compass and the bracket (syncing
  waits until the phone is held still, stars too close to each other are flagged, and an alignment that is hours old
  gets a warning chip); keep the heading on the gyroscope, safe from the camera body's magnetism.
- **Any mount** — back camera along the lens (portrait, landscape, upside down) or the phone flat on top.
- **Objects** — 9,000+ stars and 690+ galaxies, nebulae and clusters (all 110 Messier objects), the planets, the
  Moon and the Milky Way core; search, group, sort (also by the hours an object is visible tonight above your
  recorded skyline) and filter (visible for at least an hour tonight), favourites, custom RA/Dec targets; hour
  angle and time to the meridian transit, rise/set, altitude chart, framing, longest exposure and filter-aware Moon
  advice for each object (a light-pollution or narrowband filter set in Settings relaxes the Moon warning for
  emission nebulae; Tonight's best targets weigh the Moon the same way).
- **Sky chart** with time travel, **Tonight** planning (darkness windows, Moon, Milky Way core, planets, best
  targets ranked by the hours they stay clear of your skyline, polar-scope clock), **night mode** (pure red, automatic after sunset, screen dimming, red camera view).
- **Sky quality** — an offline Bortle estimate from satellite night-light imagery on every site and a Tonight card
  with the darkest direction and an imaging score for the night (dark hours, Moon, sky brightness and, when the
  forecast is on, clouds); the sky chart can draw a thin glow ring on the horizon.
- **Events calendar** — meteor showers (with hourly rates, the radiant's altitude from your site, the best camera
  direction, away from the radiant and the Moon, and how many meteors to expect in your lens's frame), lunar and
  solar eclipses, conjunctions, oppositions and elongations, supermoons, equinoxes and solstices, the stations
  of the planets, and for planet imagers the Great Red Spot's transits and the Galilean moons' transits, shadows
  and eclipses (with a "Moons Tonight" diagram on Jupiter's screen), each with when and where to look and a button
  to aim at it. With the satellite feed on, a section lists when the ISS or Tiangong crosses the Sun or the Moon in
  the next 14 days, from your site or from a centreline within 50 km, with the time to the second, the duration and
  the coordinates to copy.
- **Photo planners** — a mosaic planner for panoramas such as the Milky Way arch (panels, overlap and shooting
  order, guided panel by panel in Aim), and star-trail and time-lapse planners.
  - **Alignment finder** — when does the Milky Way core, the Moon or any object stand over a ridge or in a gap
    between trees? Pick a point of your recorded skyline (or an azimuth and altitude window), a period, how dark
    it must be and what the Moon may do, and get the matching nights, with the sky chart at that time and Aim.
- **Exposure calculator** — the longest exposure before stars trail by the NPF, 500, 400 and 300 rules for your
  camera, lens and target; for a star tracker, the drift and the limit for a polar-alignment error of 0.1° to 5°;
  for an alt-azimuth mount, the field-rotation rate and the limit at the target's place; and a shutter time for the
  Moon by the Looney 11 rule, adjusted for phase and height, with the Moon's size in pixels. The pages of the Sun,
  Moon and planets show their size in pixels too.
- **Real skyline** — sweep the phone along the horizon to record trees, buildings and mountains; rise and set
  times, Tonight and the charts then use the site's own skyline. Optionally, a **terrain horizon** worked out from
  elevation data (AWS Open Data, downloaded for a saved site only when you ask) stands in where you have not
  recorded a skyline and fills the stretches a partial sweep left open.
- **Comets and asteroids** — 216 bright asteroids built in and, optionally, the daily comet file: brightness
  estimates, elongation warnings, a Bright Comets list, and the direction of a comet's ion tail with the camera
  tilt that lays it along the frame.
- **GoTo mounts** — connect over Wi-Fi to OnStep/LX200, Celestron NexStar and SynScan, ASCOM Alpaca and the
  Sky-Watcher AZ-GTi / Star Adventurer GTi (with home position and star alignment); Go to, Sync, nudge arrows and
  an always-available Stop, with altitude limits, a meridian countdown that warns before the mount has to flip,
  and a built-in simulated mount to try it without hardware.
- **Optional online data** — all off until switched on in Settings: ISS and bright-satellite passes (follow one in
  Aim, and the ISS's transits of the Sun and Moon in the Events calendar), Starlink streak warnings for the planned
  exposure, fresh comet orbits, tonight's cloud forecast with dew, wind, fog and temperature (MET Norway, location
  rounded to about 10 km) and terrain horizons (elevation tiles from AWS Open Data, only when you ask a site for one).
- **Home-screen widgets** — Tonight at a glance (dark hours, the Moon, the Milky Way core, the next event) in
  two resizable sizes, worked out on the device; a tap opens Tonight.
- **Quick settings, shortcuts and the red torch** — a Night mode tile and a Red torch tile; long-press shortcuts for
  Aim, Red torch and Night mode, and "Aim at" shortcuts for the first favourites; a countdown notification (a system
  chronometer to the rise, then to the peak) for the satellite pass you follow in Aim; and a full-screen red torch with
  a brightness bar, white light behind a confirmation and a double tap to close.
- **Wear OS** — an Aim page for a paired watch: while the phone's Aim screen leads to a target, the watch shows the
  arrow, the pan and tilt in degrees, the distance and the target, taps your wrist on target, and goes red when the
  phone is in night mode. It needs the phone app, has no network access and stores nothing.
- **Offline and private** — catalogs and ephemerides are on the device; nothing is collected. The optional online
  data downloads from CelesTrak, the Minor Planet Center, MET Norway and AWS Open Data only when you turn it on, and
  a GoTo mount is reached over the local network only.

## Project layout

```
android/
  lodestar-kit/     Pure Kotlin/JVM astronomy engine (port of the Swift LodestarKit): coordinates, precession,
                    nutation, aberration, refraction, VSOP87B planets, Meeus Pluto, ELP/Meeus Moon, rise/set,
                    twilight, Moon phases, catalog + search, aiming math, alignment solver, WMM2025, exposure
                    tools, planning, events, skyline, mosaics, satellites (SGP4), comets and asteroids, GoTo mount
                    protocols. 1,779 unit tests (every LodestarKit test ported, plus Kotlin-only checks); docs/API-*.md.
  wear/             Wear OS app (Compose; one Aim page fed by the phone over the Wear data layer), same applicationId.
  app/              Android app (Jetpack Compose, Material 3, CameraX, MapLibre, Glance widgets).
    app/            AppModel (state + service orchestration), activity, root screen
    model/ services/ theme/ shared/   settings, sensors/location/camera, night palette, shared views
    features/       aim, objects, skychart, tonight, settings, onboarding, tools, events, mosaic, horizon,
                    mount, smallbodies, satellites, online, widget, system
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
is good to about 0.3″, the planets to a few arcseconds (Pluto to 1.4″ in 1950–2049; its series is valid only
for 1885–2099) and the Moon to under 10″ — far below the accuracy of any phone compass, which star
alignment corrects.

## Privacy

No accounts, analytics, ads or tracking. Location, camera and sensors are used on the device only. Network use:
map tiles from OpenFreeMap when the site map is open, and the phone's geocoding service to find a saved site's
time zone. The optional downloads in Settings > Online Data are off until switched on: CelesTrak, the Minor Planet
Center, MET Norway and the AWS Open Data tile server see the IP address; MET Norway also sees the location rounded to
about 10 km, and the tile server which 1° elevation tiles you ask for (where a site is to within about 100 km), only when
you ask a site for its terrain horizon. Policy: <https://feetball.github.io/Lodestar-Android/privacy.html>.

## Credits

Star, deep-sky, constellation and Milky Way data, the night-lights grid (NASA Worldview / GIBS, Black Marble (VIIRS,
Suomi NPP)), the magnetic model, the optional terrain elevation data (AWS Open Data Terrain Tiles: SRTM and GMTED2010
courtesy of the U.S. Geological Survey, and national surveys) and the software libraries are credited in
[ATTRIBUTION.md](ATTRIBUTION.md).

# Lodestar 1.1 handoff (2026-10-18)

How to continue Lodestar 1.1 on another computer. This file is for Claude Code and for Daniel (repo owner
`feetball`). The same file is in both repositories.

## Start here (prompt for Claude on the new PC)

> Clone `feetball/Lodestar-Android` (branch `main`) and `feetball/Lodestar` (branch `features-1.1`), read
> `HANDOFF.md` in either one, and continue the Lodestar 1.1 release from "What is left".

## The two repositories

| | iOS | Android |
|---|---|---|
| Repo | `feetball/Lodestar` | `feetball/Lodestar-Android` (public) |
| 1.1 code | branch **`features-1.1`** (not merged to `main` yet) | branch **`main`** |
| Version | 1.1 on all four targets (app, iOS widgets, watch app, watch widgets) | 1.1, versionCode 3 |
| CI | `.github/workflows/ci.yml` on macOS runners. Green with every feature merged (run 39176132843); the version bump `bf5e624` is not CI-checked yet | `ci.yml`, green at `b539041` |
| Store text | `AppStore/metadata-en-US.json` (1.1 description, What's New, promotional text) | `PlayStore/listing/en-US/` (`full_description.txt`, `release_notes.txt`) |
| Engine | `LodestarKit/` (Swift 6, Foundation only) | `lodestar-kit/` (pure Kotlin/JVM, a line-for-line twin) |

On this machine the Android repo was checked out inside the iOS checkout (`/opt/StarAim/android`, excluded via
`.git/info/exclude`). On a new PC, two separate clones work fine.

The two engines are twins: same types, algorithms, constants and tests, in both languages. Any engine change goes into
**both**, with mirrored tests (same names, inputs, tolerances). Kotlin has 1,752 tests and Swift 1,590, all passing.
API docs for both languages are in `lodestar-kit/docs/API-*.md` (Android repo).

## What 1.1 contains (all implemented, on both platforms)

- Events calendar: meteor showers, lunar and solar eclipses, conjunctions, oppositions, supermoons, seasons.
- Mosaic planner, including the Milky Way arch.
- Star-trail and time-lapse planners.
- Skyline (horizon) recording per site.
- Comets and asteroids everywhere: 216 bundled bright asteroids plus the optional MPC comet file. Objects tab, detail
  with tail direction, a Bright Comets card and list, Tonight targets, sky chart and Aim markers.
- GoTo mount control over Wi-Fi: LX200/OnStep, NexStar/SynScan, ASCOM Alpaca, and Sky-Watcher Wi-Fi (AZ-GTi /
  Star Adventurer GTi, with home position and star alignment).
  - The Mount button on the Aim screen appears only after a first connection.
  - GoTo and Sync to the Sun are refused.
  - There are simulated mounts for App Review.
- Optional Online Data, every feed off by default:
  - CelesTrak ISS and bright-satellite passes (follow in Aim, wait at the peak);
  - Starlink streak warnings;
  - MPC comet orbits;
  - MET Norway cloud forecast, location rounded to 0.1° (chosen over Open-Meteo, whose free tier is non-commercial).
- Widgets: Android Glance (2 sizes); iOS Home Screen and Lock Screen widgets; Apple Watch app and complications.
- Privacy texts, policies, credits, `ATTRIBUTION.md` and READMEs are updated on both platforms.

**Decision:** 1.1 ships **English only**. Translations are deferred to 1.2.

## What is left (in order)

1. **Review branches (unfinished work in progress)**
   - Two agents were building screenshot harnesses that render every new screen on CI and fix visible defects:
     - Android: branch `review-harness`, which adds `.github/workflows/emulator.yml` input `set=review` and
       `tools/capture_review.sh`.
     - iOS: branch `features-1.1-review-ios`, a review workflow for the Simulator.
   - Both were stopped mid-way and pushed as "WIP".
   - Read their diffs, then run them:
     - Android: `gh workflow run emulator.yml --ref review-harness -f set=review`
     - iOS: the review workflow on its branch
   - Look at the screenshots, fix small visible defects, and merge into `main` (Android) / `features-1.1` (iOS).
   - None of the 1.1 UI has been seen on a device or simulator yet, so this step matters. It is also optional: the
     builds are green without it.
2. **Android release 1.1 (3)**
   - Build a signed bundle:
     `./gradlew :app:bundleRelease -Plodestar.signing.storeFile=<keystore> -Plodestar.signing.storePassword=<pw> -Plodestar.signing.keyAlias=upload -Plodestar.signing.keyPassword=<pw> --no-configuration-cache`
   - Verify the signature with `jarsigner -verify`.
   - Upload it to the **closed testing (alpha)** track with `tools/play_api.py`, using the release notes from
     `PlayStore/listing/en-US/release_notes.txt`.
   - Commit the new full description through the same edit.
   - Play state on 2026-10-18:
     - internal: 1.0 (2) completed.
     - alpha: 1.0 (1) completed (was in review) and 1.0 (2) draft.
     - While the app is still a draft app, only *draft* releases are allowed on alpha/production.
   - The account is a personal developer account: production needs a closed test with **12+ testers for 14 days**
     first.
   - **Daniel, in Play Console, before the release:**
     - Data safety → add *Approximate location*: collected, optional, app functionality, not shared, encrypted in
       transit. It is used only by the opt-in cloud forecast.
3. **iOS release 1.1**
   - Merge `features-1.1` into `main`. Xcode Cloud builds `main` only and signs the build.
   - The bundle IDs `net.kuecker.Lodestar.Widgets`, `.watchkitapp` and `.watchkitapp.Widgets` are already
     registered.
   - If the archive fails on signing, check the Xcode Cloud workflow's signing settings.
   - **Daniel, in App Store Connect:**
     - Create version 1.1 and paste the texts from `AppStore/metadata-en-US.json`.
     - App Privacy → change "Data Not Collected" to Location → **Coarse Location**: not linked to you, no tracking,
       App Functionality. This matches `PrivacyInfo.xcprivacy`.
     - Untick Apple Vision Pro availability (ITMS-90984), if not done yet.
     - Submit for review.
   - Store submissions are Daniel's call; Claude asks first.
4. **New store screenshots (optional)**
   - The current ones show 1.0 screens.
   - Android: `tools/capture_screenshots.sh` via the Emulator workflow.
   - iOS: `Tools/capture_screenshots.sh` on a Mac or a macOS runner.

## Known small differences and open items (1.2 candidates)

- Parity items not yet aligned:
  - the Upcoming-events card filter (Android shows the next 4 within 8 weeks and skips Moon conjunctions unless
    occultations);
  - the Tonight prominence bonus (Android only);
  - the skyline in the altitude chart (Android only);
  - the event-cache radius;
  - sky label thresholds.
- A satellite follow session ends on any target selection on iOS, but only on a target change on Android.
- Sky-Watcher: the Android Sync sheet has "also align the phone"; iOS has "also sync the mount" in the Align sheet.
- Unverified without hardware:
  - mount protocols on real mounts (axis signs, default addresses);
  - the Android Wi-Fi binding and the iOS Local Network prompt;
  - widget timing, WatchConnectivity, and the look of every new screen.
- Translations (1.2).

## Credentials (never commit them; copy securely, not through GitHub)

They were on the old machine only. Copy them to the new PC with a USB stick or a password manager, or re-create them.

| What | Old path | If lost |
|---|---|---|
| App Store Connect API key, ID `X4SGLL26Q3` (issuer in App Store Connect › Users and Access › Integrations) | `/root/.appstoreconnect/private_keys/AuthKey_X4SGLL26Q3.p8` | Create a new key there |
| Google Play service account JSON (`lodestar-play@lodestar-play.iam.gserviceaccount.com`) | `/root/.play/lodestar-play.json` | Create a new key in Google Cloud for that service account |
| Android upload keystore, alias `upload`, plus its password | `/root/lodestar-android-upload-key/` | Request an upload-key reset in Play Console (Play App Signing holds the real app key) |

- `tools/play_api.py` reads the service account path from `PLAY_SERVICE_ACCOUNT`.
- An App Store Connect client is a few lines of PyJWT (ES256, `aud: appstoreconnect-v1`).
- The `.gitignore` files already exclude keys and signing material. Keep it that way.
- The Play service account currently has full admin. Trim it to Lodestar-only release permissions when convenient.

## Tooling

- **Android:** JDK 21, Android SDK platform 37. `./gradlew :lodestar-kit:test :app:testDebugUnitTest :app:lintDebug`.
  The Emulator workflow runs smoke tests and screenshots on GitHub.
- **iOS:**
  - On a Mac: Xcode with the iOS 27 SDK. The project is generated by XcodeGen from `project.yml` (keep the committed
    `.pbxproj` in sync).
  - Without a Mac: run `swift test` in `LodestarKit/` (Linux works), and use `gh workflow run ci.yml --ref <branch>`
    for app builds.
- **GitHub API:** poll CI at most once a minute. Several agents watching runs at once hit the rate limit.


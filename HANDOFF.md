# Lodestar 1.1 handoff (updated 2026-10-20)

How to continue Lodestar 1.1 on another computer. This file is for Claude Code and for Daniel (repo owner
`feetball`). The same file is in both repositories.

## Start here (prompt for Claude on the new PC)

> Clone `feetball/Lodestar-Android` (branch `main`) and `feetball/Lodestar` (branch `main`), read `HANDOFF.md` in
> either one, and continue the Lodestar 1.1 release from "What is left".

## The two repositories

| | iOS | Android |
|---|---|---|
| Repo | `feetball/Lodestar` | `feetball/Lodestar-Android` (public) |
| 1.1 code | **`main`** (features-1.1 and the review branch were merged on 2026-10-19) | **`main`** |
| Version | 1.1 on all four targets (app, iOS widgets, watch app, watch widgets) | 1.1, versionCode 3 |
| CI | `.github/workflows/ci.yml` on macOS runners. `main` is red since `5b1dc75` (see "What is left") | `ci.yml`, green |
| Visual review | `review.yml` (`gh workflow run review.yml --ref <branch>`), shot lists in `Tools/review/` | `emulator.yml` with `-f set=review`, `tools/capture_review.sh` |
| Store text | `AppStore/metadata-en-US.json` (1.1 description, What's New, promotional text) | `PlayStore/listing/en-US/` (`full_description.txt`, `release_notes.txt`) |
| Engine | `LodestarKit/` (Swift 6, Foundation only) | `lodestar-kit/` (pure Kotlin/JVM, a line-for-line twin) |

The two engines are twins: same types, algorithms, constants and tests, in both languages. Any engine change goes into
**both**, with mirrored tests (same names, inputs, tolerances). API docs for both languages are in
`lodestar-kit/docs/API-*.md` (Android repo).

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
- iOS only, added on 2026-10-19 by a separate session: Aim look-around without a target, zoom steps 60/40/20/100°,
  camera zoom up to 8×, a help center, the app's own night tab bar, sky chart framing. Android lacks these (1.2 parity).

**Decision:** 1.1 ships **English only**. Translations are deferred to 1.2.

## Visual review (done 2026-10-19/20)

Every 1.1 screen was photographed (Android: phone, 360 dp phone, 7" tablet, day and night; iOS: iPhone 17 Pro, iPad,
iPhone SE at accessibility text sizes, Apple Watch). About 1,000 pictures were reviewed, 235 distinct defects were found,
an independent pass confirmed about 110, and the confirmed ones were fixed in two rounds and re-checked on new pictures.
A pixel scan checks that night mode is pure red (no green or blue).

- **Android:** all fixes are on `main` (merged from `review-harness`, PR #1). The review set now also photographs the
  simulated Sky-Watcher mount (`mt-16` … `mt-30`). Night mode is pure red on every screen; no crashes.
- **iOS:** fixes are in pull requests against `main`, not merged yet (Daniel's call):
  - #13 review harness: the long iPhone capture job is split in two (it ran into its timeout), Aim without a target is
    photographed, the Online Data sheet opens reliably, a stale-state race in `capture.sh` is fixed;
  - #14 Aim / Mount / Satellites: opaque cards and chips over the sky, the Aim controls bar no longer hidden behind the
    app's own tab bar, the level gauge and big readouts clear of the cards, long mount names, large text;
  - #15 large-text layout: Settings rows, Objects rows, charts, event and object details, Record Skyline, widgets, watch;
  - #16 night mode on iOS 26: the existing red overlay is on automatically at night (iOS 17–25 unchanged), sheets hide
    their grabber at night. Measured on CI (`ios-night-chrome-exp2`): non-red pixels in the night pictures went from
    733,220 to 7,380 with the overlay and to 0 with a window-level filter; `UIDesignRequiresCompatibility` alone does not
    help (552,555). With all four PRs together, 221 of 222 iPhone night pictures are pure red (the one left is a
    review-only sheet).
  - All four compile (Debug and Release); a review run of all four merged together (`review-verify-ios`, a temporary
    branch that can be deleted) is clean.

## What is left (in order)

1. **iOS `main` CI is red** since `5b1dc75` ("Refactor location handling in watchOS app and widgets", which also changed
   22 `LodestarKit` files): `SkyWatcherMountTests.testANudgeIntoTheOverheadLimitEndsAndTrackingContinues` fails (the
   nudge ends at 89.2°, the overhead limit is 88.6°). Fix it, and mirror every engine change of that commit in the
   Kotlin twin (`lodestar-kit/`) with the same tests.
2. **iOS PRs #13–#16**: review and merge. Check #16 on a device (frame rate on the Aim tab with the camera, VoiceOver,
   touches through the overlay). The Sky tab has the same problem #14 fixed for Aim: its time-travel bar is partly under
   the app's own tab bar; the general fix belongs in `RootView` / `AppTabBar`.
3. **Android release 1.1 (3)**
   - Build a signed bundle:
     `./gradlew :app:bundleRelease -Plodestar.signing.storeFile=<keystore> -Plodestar.signing.storePassword=<pw> -Plodestar.signing.keyAlias=upload -Plodestar.signing.keyPassword=<pw> --no-configuration-cache`
   - Verify the signature with `jarsigner -verify`.
   - Upload it to the **closed testing (alpha)** track with `tools/play_api.py`, using the release notes from
     `PlayStore/listing/en-US/release_notes.txt`, and commit the new full description through the same edit.
   - Play state on 2026-10-18: internal 1.0 (2) completed; alpha 1.0 (1) completed (was in review) and 1.0 (2) draft.
     While the app is still a draft app, only *draft* releases are allowed on alpha/production.
   - The account is a personal developer account: production needs a closed test with **12+ testers for 14 days** first.
   - **Daniel, in Play Console, before the release:** Data safety → add *Approximate location*: collected, optional,
     app functionality, not shared, encrypted in transit. It is used only by the opt-in cloud forecast.
4. **iOS release 1.1** (`main` is what Xcode Cloud builds and signs)
   - The bundle IDs `net.kuecker.Lodestar.Widgets`, `.watchkitapp` and `.watchkitapp.Widgets` are already registered.
   - **Daniel, in App Store Connect:** create version 1.1 and paste the texts from `AppStore/metadata-en-US.json`;
     App Privacy → Location → **Coarse Location**: not linked to you, no tracking, App Functionality (matches
     `PrivacyInfo.xcprivacy`); untick Apple Vision Pro availability (ITMS-90984) if not done yet; submit for review.
   - Store submissions are Daniel's call; Claude asks first.
5. **New store screenshots (optional)**: the current ones show 1.0 screens. Android: `tools/capture_screenshots.sh` via
   the Emulator workflow (`set=store`). iOS: `Tools/capture_screenshots.sh` on a Mac or a macOS runner.

## Decisions for Daniel (found by the review, not changed)

- **Night palette brightness.** Secondary and tertiary text is very dim by design. Android `Palette.night(intensity)`
  at intensity 1.0: contrast against black about 4.5:1 (primary), 2.7:1 (secondary), 1.7:1 (tertiary), lower at lower
  intensity. Brighten the two dim roles, or keep them for dark adaptation?
- **"Visible from here" for events** is shown for events whose peak is in daylight or whose radiant is below the horizon
  at the peak (both engines). Decide the rule; change both engines and their tests together.
- **Aim chip "Not aligned"** stays while a Sky-Watcher mount is aligned on two stars (it means the phone's alignment);
  consider a clearer word.
- **Sky-Watcher and "Send location and time when connecting"**: the switch is on, while the set-up says nothing is sent
  to that mount.
- **Copy** left as it is: the privacy paragraph in Settings ▸ About, the event hero line ("19° western of the Sun"),
  "Moon and The Pleiades", the Moon card's "Age … at …" line, "Dark sky" meaning two things.
- **Tablets**: sheets and forms stretch to the full width on the 7" tablet (no maximum width).
- **iOS look-around** (new on main) does not name comets, asteroids or satellites and ignores the skyline.

## Known small differences and open items (1.2 candidates)

- Parity items not yet aligned:
  - the Upcoming-events card filter (Android shows the next 4 within 8 weeks and skips Moon conjunctions unless
    occultations);
  - the Tonight prominence bonus (Android only);
  - the skyline in the altitude chart (Android only);
  - the event-cache radius;
  - sky label thresholds;
  - iOS-only since 2026-10-19: Aim look-around, zoom steps, camera zoom, help center (see above).
- A satellite follow session ends on any target selection on iOS, but only on a target change on Android.
- Sky-Watcher: the Android Sync sheet has "also align the phone"; iOS has "also sync the mount" in the Align sheet.
- Android Aim: the Level gauge's pill can cover a constellation name (the label placer does not know the gauge).
- Small layout leftovers of the review: on the 360 dp phone, Mount Control strands "Sync" at the end of "Not aligned:
  centre a star and tap Sync" and breaks "Tracking on/ off"; on iOS the "f/" before the aperture still has a gap on wide
  rows; at the largest text on a 4.7" iPhone the Aim level gauge has no room while a mount card is shown.
- Unverified without hardware:
  - mount protocols on real mounts (axis signs, default addresses);
  - the Android Wi-Fi binding and the iOS Local Network prompt;
  - widget timing, WatchConnectivity, and the iOS 26 night overlay on a real device.
- Translations (1.2).

## Credentials (never commit them; copy securely, not through GitHub)

They were on the old machine only. Copy them to the new PC with a USB stick or a password manager, or re-create them.
The second PC (WSL2, 2026-10-19) has none of them, and no JDK, Android SDK, Swift or Python either: everything was built
and tested on GitHub Actions.

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
  The Emulator workflow runs smoke tests and screenshots on GitHub (`-f set=review` for the full review set, about
  70 minutes, 270 pictures per size).
- **iOS:**
  - On a Mac: Xcode with the iOS 27 SDK. The project is generated by XcodeGen from `project.yml` (keep the committed
    `.pbxproj` in sync). CI uses Xcode 26.6 with iOS 26 simulators.
  - Without a Mac: run `swift test` in `LodestarKit/` (Linux works), and use `gh workflow run ci.yml --ref <branch>`
    for app builds.
- **GitHub API:** poll CI at most once a minute. Several agents watching runs at once hit the rate limit.

# Sky-Watcher Wi-Fi mounts (Swift + Kotlin) — API reference

GoTo, sync, nudge, abort and tracking for the **Sky-Watcher mounts with built-in Wi-Fi** (AZ-GTi and AZ-GTe in alt-azimuth or equatorial
mode, Star Adventurer GTi, EQ mounts with the Sky-Watcher Wi-Fi dongle), through the **motor controller protocol** that their Wi-Fi
speaks (UDP port 11880). It is a new driver for the existing `MountController` (see `API-mount.md`): the apps choose
"Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)" next to LX200, NexStar and Alpaca, and use the same `connect`, `poll`, `goto`, `sync`,
`abort`, `nudge`, `setTracking` and `setSiteAndTime`.

The motor controller has **no pointing model and no sky knowledge**: it only counts encoder steps and runs timers. Everything the hand
control normally does is done here: the mount geometry (equatorial and alt-azimuth), a star alignment on 1 to 3 stars, GoTo with pier-side
and limit handling, tracking (including the periodic two-axis rate updates of an alt-azimuth mount) and the retries that UDP needs.
The engine still contains **no networking**: the apps give the driver a `MountTransport` (a UDP socket "connected" to the mount).

| Piece | Swift (`LodestarKit`, folder `Mount/`) | Kotlin (`net.kuecker.lodestar.kit.mount`) |
|---|---|---|
| Values: axes, modes, pier side, status, errors, version, limits, configuration | `SkyWatcherTypes.swift` | `SkyWatcherTypes.kt` |
| Protocol codec (commands, replies, numbers, speeds) | `SkyWatcherCodec.swift` | `SkyWatcherCodec.kt` |
| Mount geometry and sky conversions | `SkyWatcherModel.swift` | `SkyWatcherModel.kt` |
| Alignment (points, fit, residuals, priors) | `SkyWatcherAlignment.swift` | `SkyWatcherAlignment.kt` |
| Driver (`MountDriver` for `MountController`) | `SkyWatcherDriver.swift` | `SkyWatcherDriver.kt` |
| Discovery (access point address, UDP probe) | `SkyWatcherDiscovery.swift` | `SkyWatcherDiscovery.kt` |
| Simulator (physics, protocol, fault injection, true geometry) | `SkyWatcherSimulator.swift` | `SkyWatcherSimulator.kt` |
| Internal (not API) | `SkyWatcherLink.swift` | `SkyWatcherLink.kt` |

**What changed in existing files** (the rest of the mount area is untouched, all existing tests stay green): `MountTypes.swift` /
`MountTypes.kt` got one new case, `MountProtocol.skywatcher` / `MountProtocol.SKYWATCHER` (raw value `"skywatcher"`). `MountController`,
`MountDriver`, `MountLink`, the transports, the epochs and the other drivers and simulators are unchanged.

Naming: every type and file is prefixed `SkyWatcher` (the Swift package is one module). Conventions as in `API-mount.md`: angles in degrees,
Swift `struct` ↔ Kotlin `data class`, Swift labels ↔ Kotlin parameter names, `throws` ↔ `@Throws(MountError::class)`, `Date` ↔ `Instant`,
errors are the shared `MountError` (nothing traps, every decoded value is validated, every exchange has a timeout, no loop is unbounded).
**Not thread safe**: use the transport, driver and controller from one thread or queue (the apps' background worker).

---------------------------------------------------------------------------------------------------------

## Quick usage

```swift
// Connection screen: the phone is on the mount's Wi-Fi (access point 192.168.4.1) or on the home network the mount joined.
let clock = SystemMountClock()
let transport: MountTransport = UdpMountTransport(host: "192.168.4.1", port: SkyWatcherDiscovery.port)     // app code (connected UDP socket)
let configuration = SkyWatcherConfiguration(location: observer.location)                  // mode: from the mount model (AZ-GTi: alt-azimuth)
let driver = SkyWatcherDriver(transport: transport, clock: clock, configuration: configuration)
let mount = MountController(driver: driver, observer: observer, settings: MountSettings(altitudeLimit: 5), clock: clock)
let capabilities = try mount.connect()          // blocks: handshake, initialization, stops a running mount, first position (about 20 datagrams)
// Alignment: the mount was powered up at its home position. Centre a bright star with the nudge pad, then
try mount.sync(star.j2000)                      // adds an alignment point and refits the model; do it on 1 to 3 stars (more: up to 8)
// Aim screen
try mount.goto(target.j2000)                    // needs the first sync (`MountError.notAligned` before); tracking starts by itself
mount.poll()                                    // background worker, at least once a second (alt-azimuth tracking runs inside poll!)
try mount.nudge(.east, rate: .center, durationSeconds: 0.5);  try mount.abort();  try mount.setTracking(false)
driver.alignmentFit?.residualsDegrees           // how well the model explains the stars; .predictedErrorDegrees: expected error elsewhere
```

```kotlin
val clock = SystemMountClock()
val transport: MountTransport = UdpMountTransport("192.168.4.1", SkyWatcherDiscovery.port)               // app code
val driver = SkyWatcherDriver(transport, clock, SkyWatcherConfiguration(location = observer.location))
val mount = MountController(driver, observer, MountSettings(altitudeLimit = 5.0), clock)
val capabilities = mount.connect()
mount.sync(star.j2000)
mount.goto(target.j2000)
mount.poll()
mount.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 0.5);  mount.abort();  mount.setTracking(false)
driver.alignmentFit?.residualsDegrees
```

---------------------------------------------------------------------------------------------------------

## References (facts only; no code was copied)

| Reference | Used for |
|---|---|
| Sky-Watcher, *Motor Controller Command Set* (`skywatcher_motor_controller_command_set.pdf`, 5 pages, 25 Aug 2021, sha256 `a4c583bdf617b45ea64b3c742e8b8ab7009a953a2d42c97fdaacd67455d7f73a`, https://inter-static.skywatcher.com/downloads/skywatcher_motor_controller_command_set.pdf) | Command format (`:` letter channel data CR; `=` data CR / `!` code CR), the 24/16/8-bit little-endian hex formats ("0x123456 is sent as 5 6 3 4 1 2"), the position offset 0x800000, the command table (E F G S I J K L B O P a b c h i j k m f g D d e s W q), the `G` mode and direction bits, the `f` status bits, the error codes 0 to 5, 7, 8, the step-period formulas `T1 = TMR_Freq · 360 / speed / CPR` and `N ·` that for high speed, section "Wi-Fi Connection": UDP server on port 11880, one datagram per command and per reply, access point address 192.168.4.1 |
| INDI `indi/drivers/telescope/skywatcherAPI.cpp` / `.h`, `skywatcherAPIMount.cpp`, `indi-3rdparty/indi-eqmod/skywatcher.cpp` / `.h` (GPL; sha256 `7957f159…`, `e5d19e97…`, `0b35640c…`, `c1b68ad6…`; read from github.com/indilib on 2026-10-18) | Facts the PDF does not state: the hidden commands `H` (goto increment), `M` (brake point), `T`, `U`; direction digit `0` increases and `1` decreases the count (positive altitude and azimuth count up); minimum step period 6; the low-speed limit of 128 times sidereal; goto brake point 3200 counts (high speed) / 200 (low speed) but never more than the increment; the low-speed goto margin of 640 sidereal seconds; the mount model codes of `:e` (EQ6 0x00 … Star Adventurer GTi 0x0C … AZ-GTi 0xA5); initializing with `F` when the status says "not initialized"; the firmware version layout `AABBCC` (version `AA.BB`, mount code `CC`) |
| Not used | The *SynScan App Protocol* (port 11881 of the SynScan app, see `API-mount.md`) and the hand-control protocol (`NexStarDriver`) |

---------------------------------------------------------------------------------------------------------

## Values (`SkyWatcherTypes`)

```swift
public enum SkyWatcherAxis: String, CaseIterable, Codable, Hashable, Sendable { case axis1, axis2          // channels "1" (RA / azimuth), "2" (Dec / altitude)
    public var channel: Character; public var index: Int }
public enum SkyWatcherMountMode: String, ... { case equatorial, altAzimuth }
public enum SkyWatcherPierSide: String, ... { case east, west }       // ASCOM names: east = normal (looking west, counterweight down), west = through the pole
public enum SkyWatcherTrackingStrategy: String, ... { case sidereal, servo }
public enum SkyWatcherMountClass: String, ... { case equatorialOnly, altAzimuthOnly, either }
public enum SkyWatcherMotionMode: Character, ... { case gotoFast = "0", trackSlow = "1", gotoSlow = "2", trackFast = "3"     // G command, first digit
    var code: Character; var isGoto: Bool; var isFast: Bool; static func fromCode(_:) -> Self? }
public enum SkyWatcherDirection: Character, ... { case increasing = "0", decreasing = "1"; var code: Character; static func fromCode(_:) -> Self? }
public struct SkyWatcherAxisStatus: Hashable, Sendable { isSpeedMode, isDecreasing, isFast, isRunning, isBlocked, isInitialized, isLevelSwitchOn: Bool; var isGoto: Bool }
public enum SkyWatcherErrorCode: CaseIterable { unknownCommand(0), commandLength(1), motorNotStopped(2), invalidCharacter(3), notInitialized(4),
    driverSleeping(5), pecTrainingRunning(7), noValidPECData(8);  var code: Int; var title: String; static func fromCode(_:) -> Self? }
public struct SkyWatcherVersion: Hashable, Sendable { major, minor, mountCode: Int;  var description: String        // "3.22"
    var mountName: String; var mountClass: SkyWatcherMountClass }
public enum SkyWatcherMountCatalog { static func name(_ code: Int) -> String; mountClass(_:) -> SkyWatcherMountClass; defaultMode(_:) -> SkyWatcherMountMode }

public struct SkyWatcherLimits: Hashable, Sendable {                    // clamped on creation; NaN → default
    minimumAltitudeDegrees: Double          // -20…60, default -10: targets lower than this → MountError.mountHorizonLimit (the controller's own altitude limit, default 0, decides in practice)
    maximumAltitudeDegrees: Double          // alt-az: 60…90, default 88: higher targets → mountOverheadLimit, tracking stops above it
    azimuthMinimumDegrees, azimuthMaximumDegrees: Double   // alt-az cable wrap: -330…0 / 0…330, default -270 / 270 (axis angles from the home position)
    meridianOverlapDegrees: Double          // equatorial: 0…30, default 5: how far the RA axis may turn past the meridian (counterweight horizontal)
    pathMarginDegrees: Double               // equatorial: 0…30, default 5: slews may dip this far below minimumAltitudeDegrees on the way
    maximumAxisRateDegreesPerSecond: Double // 0.1…4, default 3: fastest speed asked of an axis while tracking or nudging
    static let standard; init(minimumAltitudeDegrees: = -10, maximumAltitudeDegrees: = 88, azimuthMinimumDegrees: = -270, azimuthMaximumDegrees: = 270,
                              meridianOverlapDegrees: = 5, pathMarginDegrees: = 5, maximumAxisRateDegreesPerSecond: = 3) }

public struct SkyWatcherConfiguration: Hashable, Sendable {             // clamped on creation
    location: GeographicLocation            // required: the model needs latitude and sidereal time; MountController.setSiteAndTime replaces it
    mode: SkyWatcherMountMode?              // nil: from the mount model (AZ-GTi, AZ-GTe, GT, MF, Dob: alt-azimuth; everything else: equatorial)
    invertsAxis1, invertsAxis2: Bool        // the position count decreases while the axis turns in the positive direction (see "Axis senses")
    exchangeTimeoutSeconds: Double          // 0.2…5, default 0.6: how long one attempt waits for a reply
    attempts: Int                           // 1…6, default 3: how often a command is sent before the exchange fails
    limits: SkyWatcherLimits
    trackingStrategy: SkyWatcherTrackingStrategy?   // nil: sidereal (equatorial) / servo (alt-azimuth; sidereal is not possible there)
    trackingUpdateIntervalSeconds: Double   // 0.25…30, default 1: the least time between two rate updates of a servo tracker or a nudge
    allowsGotoWithoutAlignment: Bool        // default false
    usesAbsoluteGotoTarget: Bool            // default false: GOTO by increment (`H`, `M`) as INDI does; true: absolute target (`S`)
    assumedSlewDegreesPerSecond: Double     // 0.1…10, default 2.5: what the planner assumes to predict where a moving target will be on arrival
    refinementToleranceDegrees: Double      // 0.002…0.5, default 0.02: a slew is corrected with short slews while it ends further away
    maximumAlignmentResidualDegrees: Double // 0.1…10, default 1: a sync that would leave a larger residual on a star is refused
    init(location:, mode: = nil, invertsAxis1: = false, invertsAxis2: = false, exchangeTimeoutSeconds: = 0.6, attempts: = 3, limits: = .standard,
         trackingStrategy: = nil, trackingUpdateIntervalSeconds: = 1, allowsGotoWithoutAlignment: = false, usesAbsoluteGotoTarget: = false,
         assumedSlewDegreesPerSecond: = 2.5, refinementToleranceDegrees: = 0.02, maximumAlignmentResidualDegrees: = 1) }
```
Kotlin: the same, with `enum class SkyWatcherAxis(rawValue, channel)` (`AXIS1`, `AXIS2`, `index`), `SkyWatcherMountMode.EQUATORIAL / ALT_AZIMUTH`,
`SkyWatcherPierSide.EAST / WEST`, `SkyWatcherTrackingStrategy.SIDEREAL / SERVO`, `SkyWatcherMountClass.EQUATORIAL_ONLY / ALT_AZIMUTH_ONLY / EITHER`,
`SkyWatcherMotionMode.GOTO_FAST / TRACK_SLOW / GOTO_SLOW / TRACK_FAST` (`code: Char`), `SkyWatcherDirection.INCREASING / DECREASING`,
`SkyWatcherErrorCode.UNKNOWN_COMMAND …`, `object SkyWatcherMountCatalog`, `data class SkyWatcherVersion(major, minor, mountCode)`, and
`SkyWatcherLimits(...)` / `SkyWatcherConfiguration(location, mode = null, …)` created with `operator fun invoke` and the same defaults.
`MountProtocol` gained `SKYWATCHER`; `SkyWatcherDriver.menuTitle` is the text for the connection menu: "Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)".

---------------------------------------------------------------------------------------------------------

## Protocol codec (`SkyWatcherCodec`)

```swift
public enum SkyWatcherCommand: CaseIterable { setPosition(E), initializationDone(F), setMotionMode(G), setGotoTargetIncrement(H), setBrakePointIncrement(M),
    setGotoTarget(S), setStepPeriod(I), setLongGotoStepPeriod(T), setBrakeSteps(U), startMotion(J), stopMotion(K), instantStop(L), setSleep(B), setAuxSwitch(O),
    setAutoguideSpeed(P), inquireCountsPerRevolution(a), inquireTimerFrequency(b), inquireBrakeSteps(c), inquireGotoTarget(h), inquireStepPeriod(i),
    inquirePosition(j), inquireIncrement(k), inquireBrakePoint(m), inquireStatus(f), inquireHighSpeedRatio(g), inquireTrackingPeriod(D), inquireAxisPosition(d),
    inquireVersion(e), inquirePECPeriod(s), extendedSetting(W), extendedInquire(q)
    var letter: Character; var replyDigits: Int; var isInquiry: Bool; static func fromLetter(_:) -> Self? }   // no bootloader / EEPROM / register / debug commands
public enum SkyWatcherReply: Hashable { case success(payload: String), failure(code: Int) }
public struct SkyWatcherSpeed: Hashable { period: Int, isHighSpeed: Bool }

public enum SkyWatcherCodec {
    static let leadingByte = 0x3A, terminator = 0x0D, positionOffset = 0x800000, maximumDigits = 6, minimumPeriod = 6, maximumPeriod = 0xFFFFFF
    static func encode24 / encode16 / encode8(_ value: Int) throws -> String;  decode24 / decode16 / decode8(_ text: String) throws -> Int   // "563412" ⇄ 0x123456
    static func encodePosition(_ counts: Int) throws -> String;  decodePosition(_ text: String) throws -> Int                                   // counts − 0x800000
    static func request(_ command:, axis:, data: String = "") throws -> [UInt8]                // ":" letter channel data CR
    static func inquire(_ command:, axis:) throws -> [UInt8]
    static func setMotionMode(axis:mode:direction:), setGotoTargetIncrement(axis:counts:), setBrakePointIncrement(axis:counts:), setGotoTarget(axis:position:),
                setStepPeriod(axis:period:), start(axis:), stop(axis:instant:), setPosition(axis:counts:), initializationDone(axis:)  // all throws -> [UInt8]
    static func parseReply(_ line: [UInt8]) throws -> SkyWatcherReply      // without CR; bytes before the first "=" / "!" are skipped (echo, stray LF)
    static func parseDatagram(_ bytes: [UInt8]) throws -> SkyWatcherReply  // up to the first CR, which must be there
    static func error(_ code: Int) -> MountError;  static func isPossiblyCausedByDamagedRequest(_ code: Int) -> Bool
    static func decodeStatus(_:) throws -> SkyWatcherAxisStatus;  encodeStatus(_:) -> String;  decodeVersion(_:) throws -> SkyWatcherVersion;  encodeVersion(_:) -> String
    static func decodeHighSpeedRatio(_ payload: String) throws -> Int
    static var lowSpeedLimitDegreesPerSecond: Double                       // 128 × sidereal
    static func speed(degreesPerSecond:countsPerRevolution:timerFrequency:highSpeedRatio:) -> SkyWatcherSpeed?
    static func degreesPerSecond(period:isHighSpeed:countsPerRevolution:timerFrequency:highSpeedRatio:) -> Double }
```
Kotlin: `object SkyWatcherCodec` with the same functions (`request(command, axis, data = "")`, `setMotionMode(axis, mode, direction)`, `stop(axis, instant = false)`,
`speed(degreesPerSecond, countsPerRevolution, timerFrequency, highSpeedRatio)`, …), `enum class SkyWatcherCommand(letter, replyDigits)`, `sealed interface SkyWatcherReply`
(`Success(payload)`, `Failure(code)`), `data class SkyWatcherSpeed(period, isHighSpeed)`.

| Reply | Meaning / error |
|---|---|
| `=` + 0 to 6 hex digits + CR | success (`a b h i j k m c D d e s q`: 6 digits, `f`: 3, `g`: 2, the setters: none) |
| `!0` | unknown command → `MountError.notSupported(.other)` (damaged-request candidate: sent again first) |
| `!1` command length error → `.rejected("command length error")` (damaged-request candidate); `!3` invalid character → `.rejected` (damaged-request candidate) |
| `!2` motor not stopped → `.busy`; `!7` PEC training running → `.busy`; `!5` driver sleeping → `.standby` |
| `!4` not initialized → `.rejected("the motor controller is not initialized")`; `!8` no valid PEC data → `.rejected`; any other code → `.remote(code:detail:)` |

Formats (verified with the document's own examples): `0x123456` ⇄ `"563412"`, `0x1234` ⇄ `"3412"`, `0x12` ⇄ `"12"`; position `0x12` is sent as `0x800012` (`"120080"`), a reply
`"341280"` is count `0x1234`. Status digits: first `B0` speed mode (1) / GOTO (0), `B1` counter-clockwise, `B2` fast; second `B0` running, `B1` blocked; third `B0` initialized,
`B1` level switch. Speeds: counts per second `r · CPR / 360`, `T1 = TMR_Freq / counts per second` (low speed, up to 128× sidereal), `T1 = N · TMR_Freq · 360 / (r · CPR)` in the
high-speed mode (`N` from `g`); periods below 6 are raised to 6, speeds too slow for 24 bits give no period (the axis is stopped instead).

---------------------------------------------------------------------------------------------------------

## Mount geometry and sky (`SkyWatcherModel`)

The home position (the mount's zero, position count 0x800000) is **where the user switches the mount on**: equatorial: polar axis aligned with the pole, counterweight pointing
down, telescope (camera) parallel to the polar axis pointing at the pole; alt-azimuth: base level, telescope level pointing north.

```swift
public struct SkyWatcherPointingErrors: Hashable, Sendable {      // degrees; all 0 = the ideal geometry
    axis1Index, axis2Index         // home position error: the encoder zero is this far off
    baseTiltX, baseTiltY           // tilt of the primary axis about the local x / y axis: equatorial = polar axis error, alt-azimuth = level error
    orthogonality                  // the secondary axis is not perpendicular to the primary axis
    cone                           // the optical axis is not perpendicular to the secondary axis (a camera on a bracket is not collimated with the mount)
    var polarAxisAltitudeError: Double                           // equatorial: positive = polar axis too high (= baseTiltY)
    func polarAxisAzimuthError(latitude: Double) -> Double       // equatorial: positive = polar axis points east of the pole (= -baseTiltX / cos latitude)
    init(axis1Index: = 0, axis2Index: = 0, baseTiltX: = 0, baseTiltY: = 0, orthogonality: = 0, cone: = 0) }
public struct SkyWatcherAxisSolution: Hashable { axis1: Double, axis2: Double, pierSide: SkyWatcherPierSide }
public struct SkyWatcherPointingModel: Hashable, Sendable {       // mode, isSouthernHemisphere, errors
    func direction(axis1Degrees:axis2Degrees:) -> Vector3                                   // where the mount points for these encoder angles (local frame, unit vector)
    func solutions(_ direction: Vector3, preferredAxis1Degrees: Double = 0) -> [SkyWatcherAxisSolution]   // the two ways of pointing there, one per side; exact inverse
    func pierSide(axis2Degrees:) -> SkyWatcherPierSide;  func modelAxis1(_ axis1Degrees: Double) -> Double
    init(mode:isSouthernHemisphere:errors:) }
public enum SkyWatcherSky {                                        // apparent place of date ⇄ local frame
    static var siderealDegreesPerSecond: Double
    static func localDirection(_ place: EquatorialCoordinate, localSiderealTime:latitude:mode:) -> Vector3
    static func apparentPlace(_ direction: Vector3, localSiderealTime:latitude:mode:) -> EquatorialCoordinate
    static func altitude(_ place: EquatorialCoordinate, localSiderealTime:latitude:) -> Double }
```
Kotlin: `data class SkyWatcherPointingErrors(axis1Index = 0.0, …)`, `data class SkyWatcherPointingModel(mode, isSouthernHemisphere, errors)` with `direction(axis1Degrees, axis2Degrees)`,
`solutions(direction, preferredAxis1Degrees = 0.0)`, `pierSide(axis2Degrees)`, `modelAxis1(axis1Degrees)`; `object SkyWatcherSky`.

Conventions (derived and checked against the foundation's hour-angle / horizontal transforms in `SkyWatcherModelTests`):

- **Local frames.** Equatorial mounts: x towards the meridian point on the celestial equator, y east, z towards the north celestial pole (the same in both hemispheres), a place
  at hour angle `H` and declination `δ` is `(cos δ cos H, -cos δ sin H, sin δ)`. Alt-azimuth mounts: ENU (x east, y north, z up). Places are the **apparent places of date**
  (`MountEpoch.apparentOfDate`: precession, nutation, annual aberration; no refraction).
- **Alt-azimuth.** `axis1` = azimuth from north through east, `axis2` = altitude; home = (0°, 0°) = north, level. `solutions` returns the normal side (`east`, |altitude axis| ≤ 90°) and the
  over-the-top side (`west`); the driver only uses the first.
- **Equatorial.** `axis1` turns the whole mount about the polar axis, `axis2` the telescope about the declination axis. The pointing is `H = axis1 + 90°`, `δ = 90° - axis2` for
  `axis2 ≥ 0` (pier side `east`: normal, looking west, counterweight down in the west sky) and `H = axis1 - 90°`, `δ = 90° + axis2` for `axis2 < 0` (pier side `west`: through the pole,
  looking east). The counterweight bar is parallel to the declination axis and points **below the horizontal exactly while |axis1| < 90°**, so the side changes at the meridian
  (`axis1 = ±90°`); `meridianOverlapDegrees` allows a few degrees more. Home: (0°, 0°) = the pole. Example: Vega at hour angle +4.1 h, δ +38.8°: `axis1 = -28°, axis2 = +51°` (east side).
- **Southern hemisphere.** The mount is the mirror image of a northern one (hour angle and declination change sign): the model mirrors the sky internally, tracking runs the other way
  round (`axis1` decreases). The pier-side names keep their meaning for the mount's own angles (`east` = declination axis angle ≥ 0).
- **Errors.** `v = Rtilt · Rz(-(axis1 + index1)) · Ry(orthogonality) · q`, `q = (sin cone, cos cone · cos φ, cos cone · sin φ)`, `φ = axis2 + index2` (+90° for equatorial mounts),
  `Rtilt = Ry(baseTiltY) · Rx(baseTiltX)`. The inverse is closed form (two solutions), no iteration.

---------------------------------------------------------------------------------------------------------

## Alignment (`SkyWatcherAlignment`)

```swift
public struct SkyWatcherAlignmentPoint: Hashable { axis1Degrees, axis2Degrees: Double; direction: Vector3; timestamp: Date; name: String? }
public struct SkyWatcherAlignmentFit: Hashable {
    model: SkyWatcherPointingModel;  residualsDegrees: [Double]           // per point: angle between the star and where the fitted model points
    predictedErrorDegrees: Double                                         // expected error away from the stars (covariance of the fit, RMS over a grid of axis angles)
    var rmsDegrees: Double; maximumResidualDegrees: Double
    var isPlausible: Bool }                                               // false: errors larger than a hand-made set-up can have
public struct SkyWatcherAlignmentPriors: Hashable { measurementSigmaDegrees = 0.1, tiltSigmaDegrees = 1, orthogonalitySigmaDegrees = 0.2, coneSigmaDegrees = 1;  static let standard }
public enum SkyWatcherAlignmentSolver {
    static let maximumPoints = 8, maximumIndexDegrees = 60, maximumTiltDegrees = 6, maximumOrthogonalityDegrees = 1.5, maximumConeDegrees = 1.5
    static func solve(_ points: [SkyWatcherAlignmentPoint], mode:, isSouthernHemisphere:, priors: = .standard) throws -> SkyWatcherAlignmentFit }
```
Kotlin: `data class SkyWatcherAlignmentPoint(axis1Degrees, axis2Degrees, direction, timestamp, name = null)`, `SkyWatcherAlignmentFit(model, residualsDegrees, predictedErrorDegrees)`,
`SkyWatcherAlignmentPriors(...)`, `object SkyWatcherAlignmentSolver` with `solve(points, mode, isSouthernHemisphere, priors = standard)`.

How the fit works (in the spirit of the phone `AlignmentSolver`: damped least squares plus priors, so that a few stars never invent large corrections): the six errors are fitted
together by Levenberg–Marquardt (≤ 80 iterations); each point contributes the vector between the model's pointing and the star's true direction (weighted by
`measurementSigmaDegrees`); the four small errors carry a prior (typical size of a hand-made set-up), the two index offsets none. So **one star** determines the two index offsets exactly
(the others stay 0), **two stars** begin to show the tilt, **three well-spread stars** the orthogonality and cone as well; weakly determined combinations are shrunk towards zero instead of
exploding. A point whose fit leaves a residual above `maximumAlignmentResidualDegrees`, or errors beyond the plausible limits (index 60°, tilt 6°, orthogonality and cone 1.5°), is
refused by the driver (`MountError.rejected`, "check that you centred the right star"). Cost: 0.13 ms (1 star) to 0.4 ms (8 stars).

What to expect (noise-free simulation: errors of 1 to 3° home position, 0.3 to 0.6° tilt, 0.1 to 0.2° orthogonality and cone; `SkyWatcherMountTests`):

| Alignment | GoTo error over the sky (9 targets, noise-free sim) | `predictedErrorDegrees` (example) |
|---|---|---|
| 1 star, only home position error | < 0.05° | about 1° |
| 1 star, with tilt, orthogonality, cone | 0.5° to several degrees, growing with the distance from the star | about 1° |
| 2 stars, alt-azimuth | about half of that of one star | 0.3° |
| 2 stars, equatorial | not much better than 1 star (two stars cannot always separate polar tilt from the declination index) | 0.5° |
| 3 well-spread stars | **< 0.1° (mostly 0.01 to 0.06°)** | 0.1° to 0.2° |

With a realistic centring scatter of 0.05° per star the same sets give about 0.2° to 0.4° (3 stars) and 0.1° to 0.15° (4 stars) worst case over the sky: add stars (up to 8) while
`predictedErrorDegrees` is large. Choose stars far apart (different sides of the meridian, different declinations / azimuths, ≥ 30° apart) and avoid the zenith and the pole.

---------------------------------------------------------------------------------------------------------

## The driver (`SkyWatcherDriver`)

```swift
public final class SkyWatcherDriver: MountDriver {
    public init(transport: MountTransport, clock: MountClock, configuration: SkyWatcherConfiguration)
    public static let menuTitle: String
    public let configuration: SkyWatcherConfiguration
    public private(set) var location: GeographicLocation                  // from the configuration, replaced by setSiteAndTime
    public private(set) var version: SkyWatcherVersion?                   // after connect
    public private(set) var mountMode: SkyWatcherMountMode?;  trackingStrategy: SkyWatcherTrackingStrategy?
    public private(set) var startupAxisAngles: SkyWatcherAxisAngles?      // encoder angles at connect: far from (0, 0) = the mount was not at home / was moved before
    public private(set) var trackingStopReason: MountError?               // why tracking stopped by itself (a limit); cleared when tracking is switched on
    public private(set) var alignmentPoints: [SkyWatcherAlignmentPoint]
    public private(set) var alignmentFit: SkyWatcherAlignmentFit?
    public var pierSide: SkyWatcherPierSide?;  pointingModel: SkyWatcherPointingModel?;  datagramsSent: Int;  retries: Int
    public var kind: MountProtocol { .skywatcher };  public var epoch: MountEpoch { .apparentOfDate }
    // MountDriver: connect, disconnect, readPosition, readSlewing, readTracking, readAligned, slew(to:), sync(to:), abort, setTracking, startNudge, stopNudge, setSiteAndTime
    public func declareHomePosition() throws                              // "this is home": E to 0 on both axes, forgets alignment and tracking (mount brought home without a power cycle)
    public func clearAlignment();  removeAlignmentPoint(_ index: Int) throws;  restoreAlignment(_ points: [SkyWatcherAlignmentPoint]) throws }
public struct SkyWatcherAxisAngles: Hashable { axis1Degrees, axis2Degrees: Double }
```
Kotlin: `SkyWatcherDriver(transport, clock, configuration)` with the same members (`val version`, `val alignmentFit`, `val pierSide`, `fun declareHomePosition()`, `fun removeAlignmentPoint(index)`,
`fun restoreAlignment(points)`, `companion object { const val menuTitle }`), `data class SkyWatcherAxisAngles`.

Behaviour (all tested against the simulator):

- **`connect()`**: `:e1` (version, mount model), `:a1 :a2` (counts per revolution), `:b1` (timer frequency), `:g1 :g2` (high-speed ratio), `:f1 :f2` (status); if an axis is not initialized, `:F1 :F2`;
  a mount that is moving is stopped (`K`, waits up to 4 s); the position is read (`startupAxisAngles`). About 16 datagrams (0.2 to 1 s). `capabilities`: name "Sky-Watcher AZ-GTi 3.22" (model and
  firmware), every feature, `gotoNeedsAlignment = true` (unless `allowsGotoWithoutAlignment`), `isAligned = false`. The mount mode comes from the model code unless configured. A new session
  forgets the previous alignment (use `restoreAlignment` when the mount stayed on).
- **`sync(target)`** = add an alignment point: it reads both encoders (2 datagrams), converts the target to the local frame at that instant and refits (0.1 to 0.4 ms). Syncing the same star
  again (within 0.5° of an existing point, whenever it was synced) **replaces** its point; at most 8 points are kept (the oldest go first). `MountSyncResult.offsetDegrees` is how far the mount's
  idea of its pointing was off (the home position error at the first sync). A sync does not move the mount and does not interrupt tracking (the tracked place becomes the synced star).
- **`goto`**: refused with `.notAligned` before the first sync, `.mountHorizonLimit` / `.mountOverheadLimit` (the target's altitude at arrival), `.outsideMountLimits` (no pier side or azimuth within the
  limits, or a path that dips below the tripod clearance both ways). Planning: the target is converted for the time the slew will end (3 predictions with `assumedSlewDegreesPerSecond`), the side is
  chosen (**equatorial**: the side that keeps the counterweight below the horizontal; within `meridianOverlapDegrees` of the meridian the current side is kept; **alt-azimuth**: the azimuth is
  unwrapped to the representation nearest to the current angle inside the cable-wrap limits), and for equatorial mounts the straight path of both axes at equal speed is checked against
  `minimumAltitudeDegrees − pathMarginDegrees`; if it dips, the slew is split into two legs (declination axis first or right ascension axis first) or refused. The goto uses `G` (fast mode for long
  moves, slow mode below 640 sidereal seconds), `H` (increment, or `S`), `M` (brake point), `J` per axis. When both axes have stopped, the driver measures the remaining error and starts up to 3 short correction
  slews while it exceeds `refinementToleranceDegrees` (this also removes the error that the moving sky causes during a long slew); then tracking starts. A goto switches tracking on. The `MountController`
  reports arrival, typically 0.01° to 0.03° from the target.
- **Tracking.** `sidereal` (equatorial): axis 1 runs at the sidereal rate (`I`, `J`; in the southern hemisphere the other way round), axis 2 stands still; the driver does nothing more than
  a status check per poll, and stops tracking when the counterweight would rise more than 0.25° above `meridianOverlapDegrees` past the horizontal (`trackingStopReason = .outsideMountLimits`).
  `servo` (alt-azimuth, optional for equatorial mounts, where it also removes the drift of a polar alignment error): **inside `readSlewing`, at most once per `trackingUpdateIntervalSeconds`**,
  the driver reads both encoders, asks the model where the anchor (the tracked sky position) will be 1.5 intervals later and sets both axes to the rates that get there (closed loop: it includes
  the distance to where the anchor is now). Rates above 128× sidereal use the high-speed mode; a reversal of direction costs a stop and a restart (about 6 datagrams). A tick is 8 datagrams
  (up to 20 at a reversal). Limits reached while tracking (azimuth beyond the cable-wrap limit, altitude beyond the overhead limit, rate beyond `maximumAxisRateDegreesPerSecond`) stop tracking
  with `trackingStopReason`; `setTracking(true)` then throws the same error while the cause remains.
- **Nudges** are in sky directions: `east` = towards increasing right ascension, `north` = towards increasing declination, at `rate × sidereal` as the **speed of the view across the sky** (great-circle,
  8× for `.center`); the driver moves the tracked anchor and runs the same closed loop (every 0.5 s while the nudge lasts, so keep polling), alt-azimuth mounts get the matching combination of
  azimuth and altitude rates. A nudge ends at `stopNudge`, or by itself 2 s after its duration if nobody stops it; tracking resumes at the place where it ended. Reaching a limit during a
  nudge ends it. `startNudge` needs 4 to 8 datagrams.
- **`abort`** sends `K` to both axes (decelerating), drops the plan; tracking resumes (from where the mount stopped) at the next poll if it was on.
- **`disconnect`** stops servo tracking (the mount would keep turning at the last rate); a sidereal tracking equatorial mount keeps tracking; a running slew is left to finish; the next `connect` stops everything.
- **`setSiteAndTime(location:date:)`** sends nothing (the mount has no clock or site): it replaces the driver's location (the phone's clock is the sky's time). A change of more than 0.25° in latitude
  or longitude, or crossing the equator, forgets the alignment (the points describe the sky of the old place).
- **Link.** One datagram per command, one per reply (UDP port 11880). An exchange discards stale input, sends, and waits `exchangeTimeoutSeconds` (0.6 s) for the reply; a missing, truncated,
  garbled or wrong-length reply, and the errors `!0`, `!1`, `!3` (which a damaged request can cause), make it send the command again, `attempts` (3) times in all; then `MountError.timeout`. The other `!`
  codes are state errors and thrown at once. A start (`J`) is not repeated blindly: if its reply is lost the driver reads the status (and, for a GOTO, the position: a very short GOTO may already be
  over, and starting it again would run the axis at its last speed) before it sends it again. Late replies are discarded by the next attempt's flush. `datagramsSent` and `retries` show the link quality.
- **Errors** are the shared `MountError` (`userMessage`s as in `API-mount.md`): `notAligned`, `mountHorizonLimit`, `mountOverheadLimit`, `outsideMountLimits`, `rejected` (alignment refused, mount refusals),
  `busy` (`!2`, an axis that does not stop), `standby`, `timeout`, `malformedReply`, `transport`, `notConnected`, `invalidInput`.

### Axis senses (verify on the mount!)

The defaults follow INDI: a positive angle is a growing position count: alt-azimuth `axis1` = azimuth (clockwise seen from above), `axis2` = altitude (up); equatorial `axis1` = turning the mount
towards the west (the sky's hour angle direction in the northern hemisphere), `axis2` = declination axis angle (positive: tube swings to the west at the home position). **These signs could not be
verified on hardware here.** If a mount turns the other way, set `invertsAxis1` / `invertsAxis2` (a setting in "Advanced"; changing it invalidates an alignment). Suggested check for the app (after the first
sync, while the mount tracks): tap "East" on the nudge pad: the view must move east on the sky (the star field moves west in the camera); tap "North": the view moves north. East/north swapped or one
of them reversed means one axis (or both) must be inverted; the app can offer "Axis directions reversed" toggles with this explanation.

---------------------------------------------------------------------------------------------------------

## Discovery (`SkyWatcherDiscovery`)

```swift
public enum SkyWatcherDiscovery {
    static let port = 11880;  static let accessPointAddress = "192.168.4.1"
    static var probePayload: [UInt8]                                     // ":e1\r"
    static func isProbe(_ bytes: [UInt8]) -> Bool
    static func parseProbeReply(_ datagram: [UInt8]) throws -> SkyWatcherVersion       // "=0322A5\r" → AZ-GTi 3.22
    static func broadcastAddress(_ address: String, prefixLength: Int) -> String?      // "192.168.1.23", 24 → "192.168.1.255" (prefix 8…30)
    static func probeTargets(_ address: String, prefixLength: Int) -> [String] }       // phone on 192.168.4.x → ["192.168.4.1"]; else [broadcast, "192.168.4.1"]
```
Kotlin: `object SkyWatcherDiscovery` (`const val port`, `accessPointAddress`, `val probePayload: ByteArray`, `broadcastAddress(address, prefixLength)`, `probeTargets(address, prefixLength)`).
The app sends `probePayload` as a UDP datagram to `probeTargets(...)` port 11880, waits ~1.5 s, and runs every reply through `parseProbeReply` (host = the sender's address): a mount in access point mode
answers at 192.168.4.1, a mount that joined the home network answers the subnet broadcast; always offer manual entry of the IP address.

---------------------------------------------------------------------------------------------------------

## Simulator (for tests, UI previews, the demo mode)

```swift
public final class SkyWatcherSimulator: MountSimulatedStreamTransport {           // a MountTransport; share one ManualMountClock with the driver and the controller
    public init(clock: ManualMountClock, location: GeographicLocation, mode: SkyWatcherMountMode = .altAzimuth, errors: SkyWatcherPointingErrors = ...,
                version: SkyWatcherVersion = AZ-GTi 3.22, countsPerRevolution: Int = 9_216_000, timerFrequency: Int = 1_500_000, highSpeedRatio: Int = 32,
                maximumSlewDegreesPerSecond: Double = 3, accelerationDegreesPerSecondSquared: Double = 6, lowSpeedGotoDegreesPerSecond: Double = 0.5,
                axis1Inverted: Bool = false, axis2Inverted: Bool = false, latencySeconds: Double = 0.05, chunkSize: Int = 0, seed: UInt64 = 1)
    public var requestLossProbability, replyLossProbability, garbleProbability: Double      // random datagram loss / damage from a seeded generator
    public private(set) var gotosStarted, speedStarts: Int;  peakSpeedDegreesPerSecond: Double
    public var isInitialized, isMoving: Bool;  axis1Degrees, axis2Degrees: Double
    public func placeAxes(axis1Degrees:axis2Degrees:)                                   // turn the axes at once (a user turning the mount by hand)
    public func truePointing() -> Vector3;  truePlace() -> EquatorialCoordinate         // what the telescope really points at (true geometry with `errors`)
    public var effectiveErrors: SkyWatcherPointingErrors }                              // errors after `E` (set position) moved the encoder zero
public final class SkyWatcherSimulatedRandom { init(seed: UInt64); func next() -> Double }   // xorshift64*, identical in both languages
```
Kotlin: `SkyWatcherSimulator(clock, location, mode = ALT_AZIMUTH, errors = SkyWatcherPointingErrors(), version = …, countsPerRevolution = 9_216_000, …, seed = 1L)`,
`SkyWatcherSimulatedRandom(seed: Long)`. The base class's `inject(commandPrefix, fault, times)` (`MountSimulatedFault`: `silence`, `replace(text)`, `truncate(keep)`, `disconnect`) works with the
label "letter + channel" (`"j1"`, `"J2"`, prefixes like `"f"` match both axes); `commandLog` lists the labels.

What it models: the commands of `SkyWatcherCodec` with the controller's replies and error codes (unknown command `!0`, length `!1`, motor not stopped `!2`, invalid character `!3`, not initialized `!4`,
sleeping `!5`); `F` before motion commands; speed mode at `TMR / period` counts per second (× the ratio in high-speed mode, capped at the maximum speed, no period change while running fast);
GOTO by increment or absolute target with acceleration, cruise (fast: up to 3°/s with 6°/s², slow: a constant 0.5°/s) and braking; `K` (decelerate) and `L` (instant); the axis returns to
speed mode when it stops; `E` (set position) while stopped; encoder counts relative to 0x800000. Every reply costs the latency of simulated time, a missing reply the caller's timeout.

---------------------------------------------------------------------------------------------------------

## Tests (mirrored: same names, inputs, expected values and tolerances in Swift and Kotlin)

101 Sky-Watcher tests per language (existing mount tests unchanged and green: Kotlin whole kit and Swift whole package pass; zero compiler warnings):

| File | Tests | What it checks |
|---|---|---|
| `SkyWatcherCodecTests` | 18 | the document's own examples (`563412`, `0x800012` → `120080`, `341280` → 0x1234), every command's bytes, replies (junk in front, two replies, malformed ones), all error codes 0 to 8 and their mapping, status bits (128 combinations), version replies and the mount code table, the step-period formulas (low and high speed, limits, 24-bit overflow), discovery probe and broadcast addresses, clamping of configuration and limits |
| `SkyWatcherModelTests` | 20 | home positions, hour angle / declination and azimuth / altitude against the foundation's transforms, the counterweight rule and both pier sides, the southern mirror, each error term, polar axis error signs, 600 random round trips of forward and inverse with random errors, sky conversions (400 round trips), alignment fits: 1 star exact, 2 stars, 3 stars, southern hemisphere, wrong star, predicted error shrinking, input validation |
| `SkyWatcherSimulatorTests` | 15 | the simulated controller itself: inquiries, initialization and sleep, malformed commands, set position, speed mode, high-speed mode, braking, goto (fast, slow, absolute, both axes), fault injection, random loss and damage (seeded), the generator's reference values, true geometry, inverted axes |
| `SkyWatcherMountTests` | 48 | everything end to end through `MountController`: connect (capabilities, model → mode, a moving mount, failures), home position, `declareHomePosition`, alignment on 1, 2, 3 stars with synthetic mis-pointing (1 star: pure home error < 0.05°; 3 stars: GoTo < 0.1° over 9 targets in both modes, both hemispheres, inverted axes), replaced and rejected stars, alignment management, site changes, GoTo at finite speed, limits and refusals (altitude, overhead, cable wrap, tripod clearance with two-leg slews), short way round, abort, correction of the moving sky, pier side and meridian (counterweight down along the whole slew, current side kept near the meridian, tracking stops at the limit, flip by goto), tracking (alt-azimuth 20 min < 0.03° drift, equatorial incl. polar error and the servo that removes it, cable wrap and overhead limits, update rate limit), nudges (all four directions and three rates against the sidereal multiples, high-speed mode, limits, deadline, abort), poll cost, lost / garbled / truncated replies, `!`-codes, lost start replies, short GOTO hazard, 8 % request loss + 8 % reply loss + 8 % damage over a whole session, slow and chopped links, reconnecting, determinism |

---------------------------------------------------------------------------------------------------------

## Notes for the UI agents

### Transport the apps implement

A `MountTransport` over one **UDP** socket "connected" to the mount (`192.168.4.1:11880`, or the discovered address): `send` = one datagram with the whole command (it already ends with CR), `receive(maxCount,
timeoutSeconds)` = the next datagram (at most `maxCount` bytes, throws `MountError.timeout` if none came in time), `discardPendingInput` = drain the socket (zero timeout). Replies are at most 8 bytes; one
`SkyWatcherDriver` per connection. iOS: `NWConnection(host:port:using: .udp)` (Local Network permission, see `API-mount.md`: a denied permission shows up as timeouts at connect). Android: `DatagramSocket` bound to the
Wi-Fi network (`ConnectivityManager.requestNetwork` + `Network.bindSocket`): the mount's access point has no internet and Android otherwise sends the packets over mobile data. Both: the phone must be on the
mount's network; joining it (`NEHotspotConfiguration` / `WifiNetworkSpecifier`) is the app's business; the SSID is printed in the mount's manual (typically `SynScan_…`; **unverified**).

### Setup flow (connection screen → alignment → Aim)

1. **Choose the mount type**: "Sky-Watcher Wi-Fi (AZ-GTi, Star Adventurer GTi)" (`SkyWatcherDriver.menuTitle`). Alt-azimuth or equatorial mode: automatic from the mount model after connect; offer a switch for
   mounts of class `either` (an AZ-GTi on a wedge is equatorial) before connecting (the choice is `SkyWatcherConfiguration.mode`).
2. **Put the mount at its home position and only then switch it on** (show a picture): *alt-azimuth*: tripod level, mount base level, telescope/camera level and pointing north (use a compass; ±5° is fine,
   the alignment finds the rest); *equatorial* (Star Adventurer GTi, EQ mounts): polar axis pointing at the pole as well as possible (polar scope / app), counterweight shaft pointing straight down, telescope/camera parallel
   to the polar axis pointing at the pole. The mount remembers this position until it is switched off.
3. **Join the mount's Wi-Fi** (or the home network it joined) and connect: probe `SkyWatcherDiscovery.probeTargets`, or the typed address; `controller.connect()`. Errors: timeout → "Is the phone on the
   mount's Wi-Fi? (iOS: allow Local Network)"; `standby` → "wake the mount up". After connect show `capabilities.name` ("Sky-Watcher AZ-GTi 3.22").
4. **Check the start position**: if `driver.startupAxisAngles` is more than a few degrees from (0°, 0°) the mount was not at home (or was moved since power-up): "The mount is not at its home position. Switch it off and on at
   the home position, or move it there with the arrows and tap *This is home*" (`declareHomePosition()`).
5. **Set site and time**: call `controller.setSiteAndTime(location:)` once (the driver needs the location; use the same location as the controller's observer). The usual text "Sends your location and the phone's time to the mount"
   is wrong for this mount: say "Uses your location and the phone's clock for pointing; nothing is sent to the mount" (`canSetSiteAndTime` is true). Make sure the phone's clock is set automatically.
6. **Align on 1 to 3 stars** ("Align" screen; GoTo is refused until the first one, show "Not aligned yet"): the app suggests bright stars that are high enough and far apart (first one anywhere, then on the other side of
   the sky; the second and third ≥ 30° from the previous ones, on different sides of the meridian). For each: the user centres the star in the camera view with the nudge pad (rates guide / centre / find), taps *Sync*
   (`controller.sync(star.j2000)`). Show after each sync: "Home position error 3.1°" (`MountSyncResult.offsetDegrees`, first sync), the number of stars, `alignmentFit.residualsDegrees` per star, and
   `alignmentFit.predictedErrorDegrees` as the accuracy ("≈ 0.3° away from these stars"; add stars while it is large; up to 8). `MountError.rejected` after a sync means the star was probably not the one named or the mount
   was not at home: "That does not fit the other stars. Check that you centred <name>" (the point is not added). `driver.alignmentPoints` / `removeAlignmentPoint(_:)` let the user drop a bad star, `clearAlignment()` starts over.
   `status.isAligned` turns true with the first sync (the controller never resets it: after `clearAlignment()` use `driver.alignmentPoints.isEmpty`).
7. Afterwards the normal **Aim** flow: pick a target, *Go to* (confirm once per session as usual), the mount slews, then tracks.

Settings (global, "Advanced"): mount arrangement (equatorial / alt-azimuth, for class `either`), *Reverse axis 1 / 2* (see "Axis senses"), *Mount horizon limit* (default -10°, leave it), *Cable wrap* (azimuth limits, default ±270°, alt-azimuth),
*Meridian overlap* (default 5°, equatorial), *Tracking update interval* (default 1 s), "Allow GoTo without alignment" (off). Keep the controller's *Altitude limit* (user setting) as for the other mounts.

### What the Aim screen shows with this mount

The mount card as in `API-mount.md` (name, connection dot, pointing RA/Dec from `status.position`, distance to the target `status.separation`, phase line, Stop, Go to, Sync, Tracking switch, nudge pad), plus:

- **Alignment line**: "Aligned on 3 stars, 0.02° residual, ≈ 0.2° accuracy" from `driver.alignmentFit` (`maximumResidualDegrees`, `predictedErrorDegrees`); "Not aligned: centre a star and tap Sync" when `driver.alignmentPoints` is empty. Sync stays available for adding stars or refining after a
  GoTo ("Synced (was 0.4° off)" from `offsetDegrees`; with an aligned mount an offset above ~0.5° suggests a wrong star or a bumped mount).
- **Pier side** (equatorial mounts; `driver.pierSide`): "Telescope east of the pier / west of the pier" (`east`: looking west, `west`: looking east). A GoTo across the meridian flips automatically; during the flip the arrival can take
  a minute. There is no automatic flip while tracking: **when tracking stops at the meridian** (`driver.trackingStopReason == .outsideMountLimits`, `status.isTracking == false`) show "Tracking stopped at the meridian. Tap Go to to
  flip the mount" and offer *Go to* for the current target.
- **Tracking state** from `status.isTracking`; when tracking stopped by itself show `driver.trackingStopReason?.userMessage` ("outside the mount's movement limits": cable-wrap or overhead limit for alt-azimuth).
- **Zenith**: alt-azimuth mounts cannot track near the zenith (azimuth rate → ∞): targets above 88° are refused (`mountOverheadLimit`: "Too close to the zenith for this mount"), a target that passes the zenith stops tracking there.
- **Polling is part of tracking**: alt-azimuth tracking runs inside `poll()` (the driver sets new rates every second); equatorial sidereal tracking runs by itself but the meridian limit is checked in `poll()`. One background worker polls every
  second (0.5 s while nudging) as in `API-mount.md`; when the app goes to the background call `controller.abort()` and `controller.disconnect()` (disconnect stops an alt-azimuth mount).
- Distances while slewing: an AZ-GTi slews at about 3°/s at most: a 90° slew takes 30 s; the Stop button always works.
- Errors as in `API-mount.md`; `lastError` of a lost datagram clears itself; link quality (`driver.retries / driver.datagramsSent`) can be shown in a diagnostics screen.

### Cost (CPU time is irrelevant; the time is the Wi-Fi round trip of 20 to 100 ms per datagram)

| Call | Datagrams | Notes |
|---|---|---|
| `connect` | ~16 | 0.2 to 1 s |
| `poll`, idle or sidereal tracking | 4 | two statuses and two positions |
| `poll`, alt-azimuth servo tick (once a second) | 8 (≤ 20 at an axis reversal) | the other polls (faster than the interval) cost 4 |
| `goto` | 4 to 12 per leg + 4 per poll while slewing | one correction slew costs about 8 more |
| `sync` | 2 | 0.1 to 0.4 ms for the fit |
| `nudge` / `stopNudge` | 4 to 8 | |
| `abort` | 2 | |

---------------------------------------------------------------------------------------------------------

## Verification and open questions

Verified: the codec against the document's examples; the geometry against the foundation's transforms and hand-derived angles; the driver, model and alignment end to end against the simulator (whose true geometry is built independently with
rotation matrices and whose controller follows the command set); Kotlin and Swift produce the same numbers (same tests, same tolerances; the random generator has reference values).

**Not verifiable here (hardware): please test on a real mount** and report:
1. **Axis signs** (see "Axis senses"): the direction of positive counts on each axis for the equatorial arrangement (Star Adventurer GTi, EQ mounts) and on the AZ-GTi in alt-azimuth mode.
2. The **mount model codes** (`:e` replies, table from INDI): `0x0C` Star Adventurer GTi, `0xA5` AZ-GTi, `0xA2` AZ-GTe, and whether the Wi-Fi mounts accept the hidden commands `H`, `M` (INDI uses them on the AZ-GTi; `usesAbsoluteGotoTarget` switches to the documented `S`).
3. Whether a Star Adventurer GTi in equatorial mode has the **home position** assumed here (counterweight / camera arm down, camera at the pole) and which way its declination axis turns for positive counts.
4. **Real slew speeds and accelerations** (the planner assumes 2.5°/s; wrong guesses only cost extra correction slews), the **minimum step period** and the high-speed mode of the AZ-GTi at 128× to 800×.
5. Real **Wi-Fi behaviour**: reply latency (the 0.6 s attempt timeout is generous), datagram loss, and whether the access point accepts a second client.
6. Refraction is not modelled (0.03° at 30° altitude, 0.1° at 10°): the alignment absorbs the average.
7. The Moon and planets: the controller converts J2000 places only (no parallax), as for the other mounts.

Data licences for `ATTRIBUTION.md`: no data files are shipped. Facts from Sky-Watcher's public *Motor Controller Command Set* (copyright Sky-Watcher; used as a protocol reference, nothing copied) and from the GPL-licensed INDI sources
(command letters, mount codes, constants; read for facts, **no code was copied or translated**). No third-party library is used.

Merge notes: `merge.py skywatcher mount Mount` replaces the `mount` / `Mount` areas (only `MountTypes` differs from the repository's version, by the one enum case), copies the new Swift test files and installs this file as `docs/API-skywatcher.md`.
`API-mount.md` should get a pointer from "What is *not* implemented" (the Sky-Watcher Wi-Fi motor controller protocol is now implemented by `SkyWatcherDriver`), the protocol table, `MountProtocol` and the connection-screen flow (choice 2:
"Sky-Watcher Wi-Fi").

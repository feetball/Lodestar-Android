# GoTo mount control (Swift + Kotlin) — API reference

Instead of showing arrows, Lodestar can drive a computerised mount to the chosen target, sync it after a star alignment, nudge it and
stop it. The engine contains **no networking**: it encodes and decodes the protocol messages and runs the control logic over small
transport interfaces that the apps implement (TCP/UDP socket, HTTP client).

| Piece | Swift (`LodestarKit`, folder `Mount/`) | Kotlin (`net.kuecker.lodestar.kit.mount`) |
|---|---|---|
| Values: protocol, feature, error, capabilities, nudge, phase, status, settings | `MountTypes.swift` | `MountTypes.kt` |
| Clock and transports: `MountClock`, `MountTransport`, `AlpacaTransport` | `MountTransport.swift` | `MountTransport.kt` |
| Coordinate frame (J2000 ⇄ JNow) | `MountEpoch.swift` | `MountEpoch.kt` |
| Controller (state machine) | `MountController.swift` | `MountController.kt` |
| Driver interface | `MountDriver.swift` | `MountDriver.kt` |
| LX200 codec and driver | `LX200Codec.swift`, `LX200Driver.swift` | `LX200Codec.kt`, `LX200Driver.kt` |
| NexStar / SynScan codec and driver | `NexStarCodec.swift`, `NexStarDriver.swift` | `NexStarCodec.kt`, `NexStarDriver.kt` |
| Alpaca codec (requests, replies, discovery, management) and driver | `AlpacaCodec.swift`, `AlpacaDriver.swift` | `AlpacaCodec.kt`, `AlpacaDriver.kt` |
| JSON parser | `MountJSON.swift` | `MountJSON.kt` |
| Simulators | `MountSimulatorModel.swift`, `MountSimulatedStreamTransport.swift`, `LX200Simulator.swift`, `NexStarSimulator.swift`, `AlpacaSimulator.swift` | same names, `.kt` |
| Internal (not API) | `MountLink.swift`, `MountCalendar.swift` | `MountLink.kt`, `MountCalendar.kt` |

Imports: Swift `import LodestarKit`. Kotlin `import net.kuecker.lodestar.kit.mount.*` (plus `…foundation.*` for coordinates).

Depends on Foundation (`SkyFrame`, `EquatorialCoordinate`, `Observer`, `GeographicLocation`, `ProperMotion`, precession, nutation, aberration).
**Nothing existing was edited**; only the `Mount` area and new test files were added. No data files, no network, no global state.

Conventions
- Angles in degrees; `EquatorialCoordinate` right ascension in degrees (the wire protocols use hours: converted inside the codecs); the places you
  give the controller are **J2000**, the controller converts to the frame the mount expects (`MountEpoch`).
- Naming across languages as in the kit rules: Swift `struct` ↔ Kotlin `data class`, payload `enum` ↔ `sealed interface`/`sealed class` (cases
  capitalised, `data object` for a case without payload), `Date` ↔ `Instant`, `[UInt8]` ↔ `ByteArray`, throwing functions are `throws` in
  Swift and `@Throws(MountError::class)` in Kotlin. Kotlin parameter names = Swift internal names (the Swift labels are shown in the Swift block).
- Generic names are prefixed `Mount…` because the Swift package is one module: `MountNudgeDirection`, `MountNudgeRate`, `MountSimulatorModel`,
  `MountSimulatedFault`, `MountSimulatedStreamTransport` (the names are identical in Kotlin).
- Errors are one typed `MountError` (an `enum` in Swift, a `sealed class` in Kotlin) with a ready-to-show `userMessage`; nothing traps, every decoded
  value is validated (lengths, ranges, characters), every exchange has a timeout, no loop is unbounded.
- **Not thread safe.** Use one controller, its driver and its transport from one thread or one serial queue/actor (a background worker that polls
  and executes the user's commands). The classes are not `Sendable`.

---------------------------------------------------------------------------------------------------------

## Quick usage

```swift
// Connection screen: LX200 over TCP (OnStep Wi-Fi: port 9999, SkyFi: 4030). The app implements MountTransport (below).
let clock = SystemMountClock()
let transport: MountTransport = TcpMountTransport(host: "192.168.0.1", port: 9999)          // app code
let driver = LX200Driver(transport: transport, clock: clock)                                 // or NexStarDriver / AlpacaDriver
let mount = MountController(driver: driver, observer: observer, settings: MountSettings(altitudeLimit: 5), clock: clock)
let capabilities = try mount.connect()                      // blocks: handshake + first position (about 0.1 to 2 s)
try mount.setSiteAndTime(location: observer.location)       // UTC, once per session, for mounts that compute their own sky

// Aim screen, user taps "Go to" (always an explicit tap)
try mount.goto(target.j2000, properMotion: target.properMotion)   // refuses below the altitude limit, while slewing, unaligned …
// background worker, about once a second:
let status = mount.poll()                                          // never throws; status.lastError / status.outcome tell the story
// user centred the star and taps "Sync"
let result = try mount.sync(star.j2000)                            // result.offsetDegrees: how far the mount was off
try mount.abort()                                                  // "Stop": always available
try mount.nudge(.east, rate: .center, durationSeconds: 0.5)        // arrow pad; poll() ends the nudge
```

```kotlin
val clock = SystemMountClock()
val transport: MountTransport = TcpMountTransport("192.168.0.1", 9999)                       // app code
val driver = LX200Driver(transport, clock)                                                    // or NexStarDriver / AlpacaDriver
val mount = MountController(driver, observer, MountSettings(altitudeLimit = 5.0), clock)
val capabilities = mount.connect()
mount.setSiteAndTime(observer.location)
mount.goto(target.j2000, target.properMotion)
val status = mount.poll()
val result = mount.sync(star.j2000)
mount.abort()
mount.nudge(MountNudgeDirection.EAST, MountNudgeRate.CENTER, 0.5)
```

Alpaca (REST): the app supplies an HTTP client instead of a byte transport.

```swift
// 1. Discovery (UDP): send AlpacaDiscovery.requestPayload to the broadcast address, port AlpacaDiscovery.port; every reply: 
let alpacaPort = try AlpacaDiscovery.parseResponse(datagram)                    // the server's HTTP port; host = sender address
// 2. Which telescope? GET configureddevices with your HTTP client
let builder = AlpacaRequestBuilder(deviceNumber: 0, clientID: 4711)
let request = builder.configuredDevices()                                       // method, target (path + query) → your HTTP client
let devices = try AlpacaResponseParser.configuredDevices(AlpacaHTTPResponse(statusCode: http.status, body: http.text), for: request)
let telescopes = devices.filter(\.isTelescope)
// 3. Drive it
let driver = AlpacaDriver(transport: myHttpTransport, configuration: AlpacaConfiguration(deviceNumber: telescopes[0].deviceNumber))
```

```kotlin
val port = AlpacaDiscovery.parseResponse(datagram)
val builder = AlpacaRequestBuilder(deviceNumber = 0, clientID = 4711)
val request = builder.configuredDevices()
val devices = AlpacaResponseParser.configuredDevices(AlpacaHTTPResponse(http.code, http.text), request)
val driver = AlpacaDriver(myHttpTransport, AlpacaConfiguration(deviceNumber = devices.first { it.isTelescope }.deviceNumber))
```

---------------------------------------------------------------------------------------------------------

## What is implemented, and from which references

| Protocol | Reference used for the codec and its tests |
|---|---|
| **LX200** (Meade) incl. OnStep | *Meade Telescope Serial Command Protocol*, revision 2007.10 (17 Oct 2007), `AutostarSerialProtocol2007oct.pdf` (sha256 `7d80a7134274bf047e52698c6ef61b072f598ddfc2bc944e46aa3d55e7041fbf`): `:GR# :GD# :Sr :Sd :MS# :CM# :Q# :U# :D# :Mx# :Qx# :Rx# :Gg# :GG# :SC :SL :St :Sg :SG`, the `<NAK>` busy convention. OnStep **4.24** `Command.ino` (github.com/hjd1964/OnStep, branch release-4.24): `:GU#` status flags, `:GW#`, `:MS#` digits 0 to 9, `:CM#` answers `N/A#` / `En#`, `:Te# :Td#`, `:D#` = `\x7F#`, accepted `:Sg`/`:SG` forms. |
| **NexStar / SynScan** hand control | Celestron *NexStar Communication Protocol* (`1154108406_nexstarcommprot.pdf`, sha256 `a97100496545aa1f96aeb1060bf0a8a45a1e12f418743a1708d9d48dad194dfe`): `V m J L M t T e E r R s S P W H`, position encoding, pass-through slews, timeouts (3.5 s). Sky-Watcher *SynScan Serial Communication Protocol* v3.3 (`SSAP-F-160414V1-EN`, sha256 `bbdbb0c785f9b4e61fd1c1ff2e7adbe294ead97c9e98c3d07e55187ced03f33e`): same commands, six-hex-digit `V`, J2000 positions, firmware gates, timeouts (5 s). Axis signs of the pass-through moves: INDI's Celestron driver (north and west positive). |
| **ASCOM Alpaca** Telescope + Management | *ASCOM Alpaca Device API v1* and *Management API v1* OpenAPI definitions (`AlpacaDeviceAPI_v1.yaml` sha256 `d08d4bfe1a01da637a9084d6a26043d669f665ecee438f6923d078539e4b8276`, `AlpacaManagementAPI_v1.yaml` sha256 `bafa24883572ad0b31efb31aa2e39aa81a945e0f4e449baab376f6c85d18aa84`, MIT licence): paths, parameter names, response shapes, `ClientID`/`ClientTransactionID`, error number range. `ASCOM.Common.Alpaca.AlpacaErrors` (0x400 … 0x4FF, driver range 0x500 … 0xFFF). ASCOM *Telescope* interface notes (`MoveAxis` sign is left to the driver, `PulseGuide` directions). Discovery: ASCOM alpyca / AlpycaDevice (UDP 32227, `alpacadiscovery1`, `{"AlpacaPort": n}`, IPv6 group `ff12::a1:9aca`). |

### Coordinate frames — read this before shipping (differs from the brief)

`MountEpoch` has three values: `j2000`, `meanOfDate` ("JNow" as planetarium programs send it: precession only) and `apparentOfDate` (precession,
nutation and annual aberration: ASCOM's "local topocentric"). They differ by at most 0.01° (36″), far below any GoTo mount's accuracy.

| Mount / protocol | Frame (default) | Why |
|---|---|---|
| LX200 (Meade, OnStep, SkyFi, …) | `meanOfDate` | LX200 mounts take current-epoch ("JNow") coordinates. Configurable (`LX200Configuration.epoch`). |
| **Celestron NexStar** hand control | `meanOfDate` | Celestron hand controls work in the current epoch; INDI's driver says "Slewing to JNOW". The protocol PDF does not name the epoch (**unverified on hardware**). |
| **Sky-Watcher SynScan** hand control (V3 ≥ 3.37, V4 ≥ 4.37.03) | **`j2000`** | The SynScan protocol v3.3 states it explicitly: "the coordinates will be on J.2000 epoch basis" and sync takes "RA and DEC in J.2000 epoch". **This is the opposite of the brief's assumption** ("SynScan expects JNow"). Older firmware: set `NexStarConfiguration(epoch: .meanOfDate)`. |
| Alpaca | from `equatorialsystem` | 1 (topocentric) → `apparentOfDate`, 2 (J2000) → `j2000`; 0, 3 (J2050), 4 (B1950) → `MountError.unsupportedCoordinateSystem`. |

`NexStarConfiguration.epoch == nil` (the default) picks the frame from the version reply at connect time (six hex digits → SynScan → J2000, two
binary bytes → Celestron → mean of date); `NexStarDriver.epoch` is final after `connect()`. Proper motion given to `goto`/`sync` is applied to the
date first (`ProperMotion.apply`); refraction is **not** applied (mounts point at the sky, the apps' sensor guidance handles refraction).

### What is *not* implemented

The Sky-Watcher **Star Adventurer GTi / AZ-GTi (and other built-in Wi-Fi mounts)** do not speak the NexStar protocol on their own Wi-Fi: their
network port (UDP 11880 on the mount's access point) carries the low-level **Sky-Watcher motor controller protocol** (steps, timer presets;
`skywatcher_motor_controller_command_set.pdf`), and the **SynScan app** exposes the mount to other programs through the *SynScan App Protocol*
(`synscan_app_protocol_20250930.pdf`: text commands that mirror ASCOM's ITelescopeV3, TCP/UDP port 11881 of the device running the SynScan app,
e.g. `SlewToCoordinatesAsync,12.45,45.89` → `Ok,SlewToCoordinatesAsync`). Neither is part of this feature (see the report's open questions). The
NexStar driver talks to SynScan **hand controls** (serial, or the SynScan Wi-Fi adapter's serial relay) and to Celestron hand controls/WiFi bridges.

---------------------------------------------------------------------------------------------------------

## Values (`MountTypes`)

```swift
public enum MountProtocol: String, CaseIterable, Codable, Hashable, Sendable { case lx200, nexstar, alpaca }
public enum MountFeature: String, CaseIterable, Codable, Hashable, Sendable {
    case slew, sync, abort, tracking, nudge, siteAndTime, coordinateSystem, other
    public var displayName: String }                    // "slewing to coordinates", "syncing to a position", …
public enum MountNudgeDirection: String, CaseIterable, Codable, Hashable, Sendable { case north, south, east, west }   // north = +Dec/up, east = +RA
public enum MountNudgeRate: String, CaseIterable, Codable, Hashable, Sendable { case guide, center, find, slew       // 1×, 8×, 32×, 128× sidereal
    public var siderealMultiple: Double; public var degreesPerSecond: Double; public static let siderealDegreesPerSecond: Double }
public enum MountPhase: String, CaseIterable, Codable, Hashable, Sendable { case disconnected, idle, slewing, nudging }
public enum MountSlewOutcome: Hashable, Sendable { case arrived(separation: Double), aborted, failed(error: MountError) }

public struct MountCapabilities: Hashable, Sendable {
    public let kind: MountProtocol; public let name: String; public let firmware: String?
    public let canSlew, canSync, canAbort, canSetTracking, canNudge, canSetSiteAndTime: Bool
    public let gotoNeedsAlignment: Bool; public let isAligned: Bool? }

public struct MountStatus: Hashable, Sendable {
    public let phase: MountPhase
    public let position: EquatorialCoordinate?      // where the mount points, J2000
    public let target: EquatorialCoordinate?        // J2000 target of the current or last slew
    public let separation: Double?                  // degrees between position and target
    public let isTracking: Bool?; public let isAligned: Bool?; public let isMoving: Bool?
    public let slewElapsedSeconds: Double?          // while slewing
    public let outcome: MountSlewOutcome?           // last finished slew; cleared by the next goto
    public let lastError: MountError?               // last failed exchange; cleared by the next successful poll
    public let updated: Date?
    public static let disconnected: MountStatus }

public struct MountSyncResult: Hashable, Sendable { public let offsetDegrees: Double }

public struct MountSettings: Hashable, Sendable {   // every value is clamped into its range, NaN → default
    public let altitudeLimit: Double                // -10…60, default 0 (geometric altitude, no refraction)
    public let horizonLookaheadSeconds: Double      // 0…1800, default 60: the target must also be above the limit this much later
    public let maximumSlewSeconds: Double           // 10…3600, default 300: longer slews are aborted
    public let slewStartGraceSeconds: Double        // 0…60, default 5: "not slewing" this long after the command is not yet a failure
    public let arrivalToleranceDegrees: Double      // 0.01…5, default 0.25
    public let pollIntervalSeconds: Double          // 0.1…30, default 1 (awaitSettled)
    public let maximumConsecutiveFailures: Int      // 1…10, default 3: this many failed polls end the session
    public let maximumNudgeSeconds: Double          // 0.1…30, default 10
    public let maximumSyncOffsetDegrees: Double     // 0.1…180, default 180 (= no limit): wrong-star guard
    public static let standard: MountSettings
    public init(altitudeLimit: Double = 0, horizonLookaheadSeconds: Double = 60, maximumSlewSeconds: Double = 300,
                slewStartGraceSeconds: Double = 5, arrivalToleranceDegrees: Double = 0.25, pollIntervalSeconds: Double = 1,
                maximumConsecutiveFailures: Int = 3, maximumNudgeSeconds: Double = 10, maximumSyncOffsetDegrees: Double = 180) }
```
```kotlin
enum class MountProtocol(val rawValue: String) { LX200, NEXSTAR, ALPACA; companion object { fun fromRawValue(raw: String): MountProtocol? } }
enum class MountFeature(val rawValue: String, val displayName: String) { SLEW, SYNC, ABORT, TRACKING, NUDGE, SITE_AND_TIME, COORDINATE_SYSTEM, OTHER }
enum class MountNudgeDirection(val rawValue: String) { NORTH, SOUTH, EAST, WEST }
enum class MountNudgeRate(val rawValue: String, val siderealMultiple: Double) { GUIDE, CENTER, FIND, SLEW
    val degreesPerSecond: Double; companion object { const val siderealDegreesPerSecond: Double } }
enum class MountPhase(val rawValue: String) { DISCONNECTED, IDLE, SLEWING, NUDGING }
sealed interface MountSlewOutcome { data class Arrived(val separation: Double); data object Aborted; data class Failed(val error: MountError) }
data class MountCapabilities(val kind: MountProtocol, val name: String, val firmware: String?, val canSlew: Boolean, val canSync: Boolean,
    val canAbort: Boolean, val canSetTracking: Boolean, val canNudge: Boolean, val canSetSiteAndTime: Boolean,
    val gotoNeedsAlignment: Boolean, val isAligned: Boolean?)
data class MountStatus(val phase: MountPhase, val position: EquatorialCoordinate?, val target: EquatorialCoordinate?, val separation: Double?,
    val isTracking: Boolean?, val isAligned: Boolean?, val isMoving: Boolean?, val slewElapsedSeconds: Double?,
    val outcome: MountSlewOutcome?, val lastError: MountError?, val updated: Instant?) { companion object { val disconnected: MountStatus } }
data class MountSyncResult(val offsetDegrees: Double)
data class MountSettings private constructor(…same properties…) {
    companion object { val standard: MountSettings
        operator fun invoke(altitudeLimit: Double = 0.0, horizonLookaheadSeconds: Double = 60.0, maximumSlewSeconds: Double = 300.0,
            slewStartGraceSeconds: Double = 5.0, arrivalToleranceDegrees: Double = 0.25, pollIntervalSeconds: Double = 1.0,
            maximumConsecutiveFailures: Int = 3, maximumNudgeSeconds: Double = 10.0, maximumSyncOffsetDegrees: Double = 180.0): MountSettings } }
```

### `MountError` — typed, user-explainable

```swift
public enum MountError: Error, Hashable, Sendable, LocalizedError {
    case notConnected, busy, slewInProgress
    case belowHorizonLimit(altitude: Double, limit: Double)       // Lodestar's own refusal
    case mountHorizonLimit, mountOverheadLimit, outsideMountLimits // the mount's refusals
    case notAligned, parked, standby, notTracking
    case notSupported(feature: MountFeature)
    case hardwareFault(detail: String), rejected(detail: String)
    case timeout(operation: String), slewTimeout(seconds: Double), slewStopped(remainingDegrees: Double)
    case syncOffsetTooLarge(offsetDegrees: Double, limitDegrees: Double)
    case connectionLost, transport(detail: String), malformedReply(detail: String)
    case remote(code: Int, detail: String), invalidInput(detail: String), unsupportedCoordinateSystem(code: Int)
    public var userMessage: String { get }  // also errorDescription
    public var isCommunicationFailure: Bool { get }   // timeout, transport, malformedReply, connectionLost }
```
Kotlin: `sealed class MountError(message): Exception` with the same cases as nested classes (`MountError.NotConnected` … `data object`s, `data class`es
for payloads: `BelowHorizonLimit(altitude, limit)`, `NotSupported(feature)`, `Timeout(operation)`, `Remote(code, detail)` …), `userMessage`,
`isCommunicationFailure`. Texts (identical in both languages, the apps may localise later):

| Case | `userMessage` |
|---|---|
| `notConnected` | The mount is not connected. |
| `busy` | The mount is busy. Try again in a moment. |
| `slewInProgress` | The mount is already slewing. Stop it before starting another move. |
| `belowHorizonLimit(-3.3, 0)` | The target is at -3.3°, below your altitude limit of 0.0°. Lodestar won't slew there. |
| `mountHorizonLimit` | The target is below the mount's horizon limit. |
| `mountOverheadLimit` / `outsideMountLimits` | The target is above the mount's overhead limit. / … outside the mount's movement limits. |
| `notAligned` | The mount is not aligned. Do a star alignment on the mount first, then try again. |
| `parked` / `standby` / `notTracking` | The mount is parked. Unpark it first. / … in standby. Wake it up first. / Tracking is off. Turn tracking on first. |
| `notSupported(.sync)` | This mount doesn't support syncing to a position. |
| `slewStopped(12.3)` | The mount stopped 12.3° short of the target. |
| `slewTimeout(300)` | The slew did not finish within 300 s, so it was stopped. |
| `syncOffsetTooLarge(45, 30)` | The mount points 45.0° away from that star, more than the allowed 30°. Check that you centred the right star. |
| `timeout("read position")` | The mount didn't answer in time (read position). |
| `connectionLost` | The connection to the mount was lost. |
| `transport(d)` / `malformedReply(d)` / `remote(c, d)` | Could not talk to the mount: d. / The mount sent an unexpected reply (d). / The mount reported error c: d. |
| `hardwareFault`, `rejected`, `invalidInput`, `unsupportedCoordinateSystem` | The mount reported a hardware fault[: d]. / The mount rejected the command[: d]. / That value can't be used: d. / … coordinate system … (code c). |

---------------------------------------------------------------------------------------------------------

## Clock and transports (`MountTransport`)

```swift
public protocol MountClock: AnyObject { func now() -> Date; func sleep(seconds: Double) }
public final class SystemMountClock: MountClock { public init() }
public final class ManualMountClock: MountClock {                  // simulators and tests: time only moves when told to
    public init(start: Date); public func advance(seconds: Double); public var secondsSince1970: Double }

public protocol MountTransport: AnyObject {                        // LX200 and NexStar: a byte stream (TCP) or a "connected" UDP socket
    func send(_ bytes: [UInt8]) throws
    func receive(maxCount: Int, timeoutSeconds: Double) throws -> [UInt8]   // at least 1 byte, at most maxCount; throws .timeout if nothing came
    func discardPendingInput() throws }                            // drop late replies of earlier commands

public protocol AlpacaTransport: AnyObject {                       // Alpaca: one HTTP request/response
    func send(_ request: AlpacaHTTPRequest, timeoutSeconds: Double) throws -> AlpacaHTTPResponse }
```
```kotlin
interface MountClock { fun now(): Instant; fun sleep(seconds: Double) }
class SystemMountClock : MountClock
class ManualMountClock(start: Instant) : MountClock { fun advance(seconds: Double); val secondsSince1970: Double }
interface MountTransport { fun send(bytes: ByteArray); fun receive(maxCount: Int, timeoutSeconds: Double): ByteArray; fun discardPendingInput() }
interface AlpacaTransport { fun send(request: AlpacaHTTPRequest, timeoutSeconds: Double): AlpacaHTTPResponse }
```
Transport contract (what the apps implement; the simulators are reference implementations):
- `send` writes all bytes (one datagram for UDP). `receive` blocks up to `timeoutSeconds` for **some** input and returns what has arrived (never more than
  `maxCount`, never an empty array); no data in time → throw `MountError.timeout(operation:)`. Replies may arrive in pieces, the engine reassembles
  them against a deadline. `discardPendingInput` drains the socket's receive buffer (the engine calls it before every command).
- I/O failures → throw `MountError.transport(detail:)` (or any other error: the engine wraps it into `.transport` with the error's message).
- `AlpacaTransport.send` performs `request.method` on `endpoint.baseURL + request.target` with `request.body` and `request.contentType`; a
  non-200 status is a **normal return** (the parser turns it into `MountError.remote`); throw `.timeout`/`.transport` only for no-answer/connection errors.
- Do not block the main thread: the controller, its driver and the transport are used from a background worker.

---------------------------------------------------------------------------------------------------------

## `MountController` — the state machine

```swift
public final class MountController {
    public init(driver: MountDriver, observer: Observer, settings: MountSettings = .standard, clock: MountClock = SystemMountClock())
    public var observer: Observer                       // may be replaced when the user moves
    public let settings: MountSettings
    public private(set) var capabilities: MountCapabilities?
    public var status: MountStatus { get }              // snapshot, never talks to the mount
    public var isConnected: Bool { get }

    @discardableResult public func connect() throws -> MountCapabilities     // handshake + first position/tracking/slewing; idempotent
    public func disconnect()                                                // stops a running nudge, leaves a running slew alone
    @discardableResult public func poll() -> MountStatus                    // one refresh cycle, never throws
    @discardableResult public func awaitSettled() -> MountStatus            // polls every pollIntervalSeconds until the slew ended (bounded)

    public func goto(_ target: EquatorialCoordinate, properMotion: ProperMotion? = nil) throws
    @discardableResult public func sync(_ target: EquatorialCoordinate, properMotion: ProperMotion? = nil) throws -> MountSyncResult
    public func abort() throws
    public func nudge(_ direction: MountNudgeDirection, rate: MountNudgeRate = .center, durationSeconds: Double = 0.5) throws
    public func stopNudge() throws
    public func setTracking(_ enabled: Bool) throws
    public func setSiteAndTime(location: GeographicLocation, date: Date? = nil) throws          // UTC, no zone offset
}
```
```kotlin
class MountController(driver: MountDriver, var observer: Observer, val settings: MountSettings = MountSettings.standard,
                      clock: MountClock = SystemMountClock()) {
    var capabilities: MountCapabilities? ; val status: MountStatus ; val isConnected: Boolean
    fun connect(): MountCapabilities ; fun disconnect() ; fun poll(): MountStatus ; fun awaitSettled(): MountStatus
    fun goto(target: EquatorialCoordinate, properMotion: ProperMotion? = null)
    fun sync(target: EquatorialCoordinate, properMotion: ProperMotion? = null): MountSyncResult
    fun abort() ; fun nudge(direction: MountNudgeDirection, rate: MountNudgeRate = MountNudgeRate.CENTER, durationSeconds: Double = 0.5)
    fun stopNudge() ; fun setTracking(enabled: Boolean) ; fun setSiteAndTime(location: GeographicLocation, date: Instant? = null)
}
```

```
disconnected --connect--> idle --goto--> slewing --poll: arrived / stopped short / timeout / link lost--> idle (or disconnected)
                           |  \--nudge--> nudging --duration over (next poll) / stopNudge--> idle
                           \--abort (any phase)--> idle                       3 failed polls in a row --> disconnected
```

Rules the controller enforces (all tested against the simulators of all three protocols):
- **Altitude limit**: `goto` computes the target's geometric altitude with `SkyFrame` for the observer at *now* and at *now + horizonLookaheadSeconds*;
  below `altitudeLimit` → `.belowHorizonLimit(altitude:limit:)`, nothing is sent. (Default 0°; the Settings screen should expose it.)
- **No second slew**: `goto` while `.slewing` → `.slewInProgress`, while `.nudging` → `.busy`, while the mount's own "slewing" flag is set (a move started on the hand
  controller) → `.slewInProgress`. Call `abort()` first. `sync` follows the same rules; `nudge` is refused while slewing.
- **Timeouts**: every driver exchange has a deadline (LX200 3 s, NexStar 5.5 s, Alpaca 10 s by default); a slew longer than `maximumSlewSeconds` is aborted
  (`.slewTimeout`; if the abort itself fails the phase stays `.slewing` and the next poll retries); a nudge ends by itself at the first `poll()` after its
  duration (poll at least once a second while nudging; Alpaca guide-rate pulses and `pulseguide` are timed by the mount itself).
- **Arrival**: a slew is *arrived* when the mount reports "not slewing" and its position (in its own frame) is within `arrivalToleranceDegrees` of what was sent;
  "not slewing" after having been slewing but far away → `.slewStopped(remainingDegrees)`; "not slewing" and far away without ever slewing for `slewStartGraceSeconds`
  → the same error. After a communication failure *during* `goto` (the slew may or may not have started) the controller sends an abort before reporting the error.
- **Sessions**: a failed poll sets `status.lastError` and counts; `maximumConsecutiveFailures` in a row end the session (`phase == .disconnected`,
  `lastError == .connectionLost`, and `outcome == .failed(.connectionLost)` if a slew was running). A successful poll clears the error and the count.
- **Alignment**: `status.isAligned` is refreshed by `poll()` until it is true (NexStar/SynScan `J`, OnStep `:GW#`); before an alignment a hand control's
  positions may be meaningless (even out of range) — that is not treated as a failure then. `goto` on an unaligned NexStar/SynScan → `.notAligned`
  (checked live with `J`, so aligning later works without reconnecting).
- `abort()` repeats a failed stop up to 3 times (communication errors only), also stops a nudge, and is allowed in every connected phase.
- Cost of `poll()`: idle 2 to 4 exchanges (position, slewing flag, tracking, alignment until aligned), slewing 2, nudging 3 to 4; one exchange is ~20 to 100 ms
  on Wi-Fi, so a poll is 0.05 to 0.4 s. `connect()` 6 to 12 exchanges. `goto`/`sync` 3 to 5. Nothing here is CPU relevant (`SkyFrame` ≈ 10 µs).

---------------------------------------------------------------------------------------------------------

## `MountEpoch` — J2000 ⇄ the mount's frame

```swift
public enum MountEpoch: String, CaseIterable, Codable, Hashable, Sendable { case j2000, meanOfDate, apparentOfDate
    public func fromJ2000(_ j2000: EquatorialCoordinate, frame: SkyFrame, properMotion: ProperMotion? = nil) -> EquatorialCoordinate
    public func toJ2000(_ mount: EquatorialCoordinate, frame: SkyFrame) -> EquatorialCoordinate }
```
```kotlin
enum class MountEpoch(val rawValue: String) { J2000, MEAN_OF_DATE, APPARENT_OF_DATE
    fun fromJ2000(j2000: EquatorialCoordinate, frame: SkyFrame, properMotion: ProperMotion? = null): EquatorialCoordinate
    fun toJ2000(mount: EquatorialCoordinate, frame: SkyFrame): EquatorialCoordinate }
```
`meanOfDate` = `frame.precessionMatrix` (IAU 1976) only; `apparentOfDate` = `SkyFrame.apparentPlace` (precession, nutation, annual aberration); the inverse
removes aberration to first order (round trip error < 2·10⁻⁶°). Verified against Meeus 21.b and 23.a (θ Persei, 2028-11-13.19 TD).

---------------------------------------------------------------------------------------------------------

## Drivers

```swift
public protocol MountDriver: AnyObject {
    var kind: MountProtocol { get }; var epoch: MountEpoch { get }          // epoch is final after connect()
    func connect() throws -> MountCapabilities;  func disconnect()
    func readPosition() throws -> EquatorialCoordinate                      // in the driver's epoch
    func readSlewing() throws -> Bool;  func readTracking() throws -> Bool?;  func readAligned() throws -> Bool?
    func slew(to target: EquatorialCoordinate) throws;  func sync(to target: EquatorialCoordinate) throws;  func abort() throws
    func setTracking(_ enabled: Bool) throws
    func startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) throws
    func stopNudge(direction: MountNudgeDirection) throws
    func setSiteAndTime(location: GeographicLocation, date: Date) throws }
```
Kotlin: `interface MountDriver` with the same members (`slew(target)`, `sync(target)`, `startNudge(direction, rate, durationSeconds)`, `setSiteAndTime(location, date)`).
Drivers do one blocking exchange per call, validate every reply, never retry (except the protocol's own busy NAK) and keep no thread state. The apps normally only
construct them and hand them to `MountController`.

### LX200

```swift
public final class LX200Driver: MountDriver {
    public init(transport: MountTransport, clock: MountClock, configuration: LX200Configuration = .standard)
    public let configuration: LX200Configuration
    public private(set) var dialect: LX200Dialect          // .meade or .onstep, detected from :GVP# ("On-Step") unless configured
    public private(set) var precision: LX200Precision }    // position format last seen from the mount
public struct LX200Configuration { epoch: MountEpoch = .meanOfDate, exchangeTimeoutSeconds = 3, identifyTimeoutSeconds = 1, tailTimeoutSeconds = 0.4,
    dialect: LX200Dialect? = nil, prefersHighPrecision = true, busyRetries = 3 }       // all clamped; static let standard
```
Kotlin: `LX200Driver(transport, clock, configuration = LX200Configuration.standard)`, `LX200Configuration(epoch = MountEpoch.MEAN_OF_DATE, …)`.

Behaviour: connect = `:GVP#`/`:GVN#` (optional, 1 s timeout), `:GR#` (switches a **low precision** mount to high with `:U#` — a toggle that is only sent after a
low-precision reply was seen), `:GD#`, on OnStep `:GU#` and `:GW#`. Targets are sent in the precision the mount currently uses (`:Sr12:30:00#` / `:Sr12:30.0#`,
`:Sd+45*30:15#` / `:Sd+45*30#`); a Meade mount that was switched to the other format behind the driver's back refuses the target (`.rejected`) until the next
position reading updates `precision`. `:MS#` replies: Meade `0` / `1<text>#` / `2<text>#`, OnStep `0…9` → `.mountHorizonLimit`, `.mountOverheadLimit`, `.standby`, `.parked`,
`.slewInProgress`, `.outsideMountLimits`, `.hardwareFault`, `.busy`, `.rejected`. `:CM#`: `N/A#`, `En#` (error code like `:MS#`), a name (Meade), an empty `#` (OnStep refused silently). Slewing = `:D#`
non-empty (`\x7F#`). Tracking/park/goto flags and `:Te#`/`:Td#` only on OnStep (`capabilities.canSetTracking`). NAK (0x15) → retry after 0.1 s, `busyRetries` times, then `.busy`.
Nudge = `:R?#` then `:M?#`, stop `:Q?#`. Site/time (UTC, offset 0): `:St :Sg` (west-positive longitude: 105.27° W → `105*16`, 10° E → `350*00`) `:SG :SC :SL`.

```swift
public enum LX200Codec {                                  // pure functions, all inputs/outputs plain strings
    public static let getRightAscension = ":GR#", getDeclination = ":GD#", getProductName = ":GVP#", getFirmwareNumber = ":GVN#",
                      getStatus = ":GU#", getAlignmentStatus = ":GW#", getSlewDistance = ":D#", slewToTarget = ":MS#",
                      syncToTarget = ":CM#", halt = ":Q#", togglePrecision = ":U#", trackingOn = ":Te#", trackingOff = ":Td#"
    public static let busyByte = 0x15
    public static func setTargetRightAscension(hours: Double, precision: LX200Precision) -> String     // ":Sr12:30:00#"
    public static func setTargetDeclination(degrees: Double, precision: LX200Precision) -> String      // ":Sd+45*30:15#"
    public static func move(_ direction: MountNudgeDirection) -> String;  public static func halt(_ direction: MountNudgeDirection) -> String
    public static func setRate(_ rate: MountNudgeRate) -> String
    public static func setLatitude(_ latitude: Double) -> String;  public static func setLongitude(_ eastLongitude: Double) -> String
    public static func setUTCOffset(eastOffsetHours: Double, dialect: LX200Dialect) -> String
    public static func setDate(_ dateTime: MountDateTime) -> String;  public static func setTime(_ dateTime: MountDateTime) -> String
    public static func formatRightAscension(hours: Double, precision: LX200Precision) -> String         // wraps at 24 h, rounds, no 60 s
    public static func formatDeclination(degrees: Double, precision: LX200Precision) -> String          // ±90 clamp, "-00*30", never "-00*00"
    public static func decodeRightAscension(_ text: String) throws -> LX200Reading                        // HH:MM:SS[.ssss] | HH:MM.T[T[T]] ; 24:00:00 → 0
    public static func decodeDeclination(_ text: String) throws -> LX200Reading                          // sDD*MM[:SS[.ssss]] with * : ° 0xDF / : '
    public static func slewRefusal(_ code: Character) throws -> MountError?                              // nil for "0"
    public static func isSlewing(distanceBars: String) -> Bool
    public static func decodeStatus(_ text: String) throws -> LX200StatusFlags                           // OnStep :GU#
    public static func decodeAlignmentStatus(_ text: String) throws -> LX200AlignmentStatus              // :GW# "GT2"
    public static func dialect(productName: String) -> LX200Dialect }
public enum LX200Precision: String { case low, high };  public enum LX200Dialect: String { case meade, onstep }
public struct LX200Reading { value: Double, precision: LX200Precision }
public struct LX200StatusFlags { isTracking, isGotoActive, isParked, isParking, parkFailed, isAtHome: Bool }
public struct LX200AlignmentStatus { mountType: Character, isTracking: Bool, alignedStars: Int }
```
Kotlin: `object LX200Codec` with `const val getRightAscension` … and the same functions (`setTargetRightAscension(hours, precision)`, `move(direction)`, `halt(direction)`,
`setUTCOffset(eastOffsetHours, dialect)`, `formatRightAscension(hours, precision)`, `slewRefusal(code: Char)`, `isSlewing(distanceBars)`, `dialect(productName)`), `enum class LX200Precision { LOW, HIGH }`,
`enum class LX200Dialect { MEADE, ONSTEP }`, data classes `LX200Reading`, `LX200StatusFlags`, `LX200AlignmentStatus`.
Replies are passed **without** the terminator, one character per byte (Latin-1), so the degree byte 0xDF that some Meade scopes send arrives as `ß` and is accepted.

### NexStar / SynScan

```swift
public final class NexStarDriver: MountDriver {
    public init(transport: MountTransport, clock: MountClock, configuration: NexStarConfiguration = .standard)
    public let configuration: NexStarConfiguration;  public private(set) var version: NexStarVersion? }
public struct NexStarConfiguration { epoch: MountEpoch? = nil (automatic: SynScan → J2000, Celestron → meanOfDate), exchangeTimeoutSeconds = 5.5,
    usesPrecisePositions: Bool? = nil (nil: Celestron ≥ 1.6, always SynScan), preferredTrackingMode: NexStarTrackingMode = .altAz,
    invertsEastWest = false }                                          // static let standard
public enum NexStarFlavor: String { case celestron, synscan }
public struct NexStarVersion { flavor, major, minor, patch;  func isAtLeast(_ major: Int, _ minor: Int, _ patch: Int = 0) -> Bool;  var description: String }
public enum NexStarAxis: Int { case azimuthRightAscension = 16, altitudeDeclination = 17 }
public enum NexStarTrackingMode: Int { case off = 0, altAz = 1, eqNorth = 2, eqSouth = 3;  static func fromCode(_ code: Int) -> NexStarTrackingMode? }
public enum NexStarCodec {
    static let getVersion, getModel, getAlignmentComplete, getGotoInProgress, cancelGoto, getTrackingMode: [UInt8]; static let terminator = 0x23
    static func setTrackingMode(_ mode: NexStarTrackingMode) -> [UInt8];  static func getPosition(precise: Bool) -> [UInt8]
    static func goto(_ target: EquatorialCoordinate, precise: Bool) -> [UInt8]   // "r34AB0500,12CE0500" / "R34AB,12CE"
    static func sync(_ target: EquatorialCoordinate, precise: Bool) -> [UInt8]   // "s…" / "S…"
    static func encodeAngle(_ degrees: Double, precise: Bool) -> String           // fraction of a revolution, 4 or 8 hex digits, low byte 00
    static func decodeAngle(_ hexText: String) throws -> Double;  static func decodePosition(_ payload: String) throws -> EquatorialCoordinate
    static func passthrough(payloadLength:device:commandID:data1:data2:data3:responseLength:) -> [UInt8]
    static func variableRateSlew(axis: NexStarAxis, positive: Bool, arcsecondsPerSecond: Double) throws -> [UInt8]   // P 3 dev 6/7 hi lo 0 0
    static func fixedRateSlew(axis: NexStarAxis, positive: Bool, rate: Int) throws -> [UInt8]                       // P 2 dev 36/37 rate 0 0 0 (0 stops)
    static func getDeviceVersion(axis: NexStarAxis) -> [UInt8]
    static func setLocation(_ location: GeographicLocation) throws -> [UInt8]                                       // W + 8 bytes
    static func setTime(_ dateTime: MountDateTime, zoneOffsetHours: Int, daylightSaving: Bool) throws -> [UInt8]    // H + 8 bytes, 2000…2099
    static func decodeVersion(_ payload: [UInt8]) throws -> NexStarVersion       // 2 binary bytes (Celestron) | 6 hex digits (SynScan)
    static func modelName(_ model: Int, flavor: NexStarFlavor) -> String }
```
Kotlin: `NexStarDriver(transport, clock, configuration = NexStarConfiguration.standard)`, `NexStarConfiguration(epoch = null, exchangeTimeoutSeconds = 5.5, …)`, `enum class NexStarFlavor { CELESTRON, SYNSCAN }`,
`NexStarAxis.AZIMUTH_RIGHT_ASCENSION / ALTITUDE_DECLINATION`, `NexStarTrackingMode.OFF / ALT_AZ / EQ_NORTH / EQ_SOUTH`, `object NexStarCodec` (`val getVersion: ByteArray get()`, `goto(target, precise)`, `variableRateSlew(axis, positive, arcsecondsPerSecond)`, …).

Behaviour: connect = `V` (3 bytes, or 7 for SynScan; unambiguous because a Celestron reply's third byte is always `#`), `m`, `J`, `t`, one position read. Capabilities by firmware:
sync Celestron ≥ 4.10 / SynScan 3.x ≥ 3.37, 4.x ≥ 4.37.03; `T`, `P` moves Celestron ≥ 1.6 / SynScan 3.x ≥ 3.03, 4.x ≥ 4.01.01; `t`, `W`, `H` Celestron ≥ 2.3; precise `e/r/s` Celestron ≥ 1.6.
`goto` first reads `J` (not aligned → `.notAligned`; the hand control would silently ignore the command). GOTO enables tracking on SynScan. Nudge = tracking off, variable-rate `P` slew,
and on stop `P` fixed rate 0 then tracking back on in the mode that ran (the protocol notes recommend this). **Axis signs**: positive on the right-ascension/azimuth axis is *west*, on the
declination/altitude axis *north* (INDI's Celestron driver); `invertsEastWest` flips east/west if a mount turns the other way. Site/time: UTC with offset 0.

### Alpaca

```swift
public final class AlpacaDriver: MountDriver {
    public init(transport: AlpacaTransport, configuration: AlpacaConfiguration = .standard);  public let configuration: AlpacaConfiguration }
public struct AlpacaConfiguration { deviceNumber = 0, clientID = 1, exchangeTimeoutSeconds = 10, primaryPositiveIsEast = true, secondaryPositiveIsNorth = true }  // static let standard

public struct AlpacaField { name: String, value: String }
public enum AlpacaMethod: String { case get = "GET", put = "PUT" }
public struct AlpacaHTTPRequest { method, path, query: [AlpacaField], form: [AlpacaField], clientTransactionID: Int
    var target: String { get }       // path[?encoded query]
    var body: String { get }         // application/x-www-form-urlencoded
    var contentType: String? { get } }
public struct AlpacaHTTPResponse { statusCode: Int, body: String }
public final class AlpacaRequestBuilder {                          // stateful: ClientID + increasing ClientTransactionID (1…2147483647, wraps to 1)
    public init(deviceNumber: Int = 0, clientID: Int = 1)
    // management: apiVersions(), serverDescription(), configuredDevices()
    // GET: connected(), name(), canSlewAsync(), canSync(), canPulseGuide(), canSetTracking(), canMoveAxis(_:), equatorialSystem(),
    //      rightAscension(), declination(), slewing(), tracking(), atPark(), trackingRates()
    // PUT: setConnected(_:), slewToCoordinatesAsync(rightAscensionHours:declination:) throws, syncToCoordinates(…) throws, abortSlew(),
    //      setTracking(_:), pulseGuide(direction:durationMilliseconds:) throws, moveAxis(_:degreesPerSecond:) throws,
    //      setSiteLatitude(_:) / setSiteLongitude(_:) / setSiteElevation(_:) throws, setUTCDate(_:) throws
}
public struct AlpacaReply { clientTransactionID: Int?, serverTransactionID: Int?, value: MountJSON?
    func booleanValue() throws -> Bool;  doubleValue() throws -> Double;  integerValue() throws -> Int;  textValue() throws -> String;  integerList() throws -> [Int] }
public enum AlpacaResponseParser {
    static func parse(_ response: AlpacaHTTPResponse, for request: AlpacaHTTPRequest) throws -> AlpacaReply
    static func apiVersions(_:for:) throws -> [Int];  serverDescription(_:for:) throws -> AlpacaServerDescription;  configuredDevices(_:for:) throws -> [AlpacaDevice] }
public struct AlpacaDevice { deviceName, deviceType: String, deviceNumber: Int, uniqueID: String;  var isTelescope: Bool }
public struct AlpacaServerDescription { serverName, manufacturer, manufacturerVersion, location: String }
public struct AlpacaEndpoint { host: String, port: Int;  var baseURL: String;  static func validated(host: String, port: Int) -> AlpacaEndpoint? }
public enum AlpacaDiscovery { static let port = 32227, ipv6MulticastGroup = "ff12::a1:9aca";  static var requestPayload: [UInt8]   // "alpacadiscovery1"
    static func isRequest(_ bytes: [UInt8]) -> Bool;  static func responsePayload(alpacaPort: Int) -> [UInt8];  static func parseResponse(_ bytes: [UInt8]) throws -> Int }
public enum AlpacaCodec { static func percentEncode(_:) -> String;  encodeFields(_:) -> String;  formatNumber(_:) throws -> String   // "12.5", ≤ 9 decimals
    static func formatBoolean(_:) -> String   // "True"/"False"
    static func formatUTCDate(_:) -> String?   // "2026-10-18T04:00:00.000Z"
    static func error(number: Int, message: String) -> MountError }
public enum AlpacaGuideDirection: Int { north = 0, south, east, west };  public enum AlpacaAxis: Int { primary = 0, secondary, tertiary }
```
Kotlin: `AlpacaDriver(transport, configuration = AlpacaConfiguration.standard)`; `AlpacaRequestBuilder(deviceNumber = 0, clientID = 1)` with the same method names
(`slewToCoordinatesAsync(rightAscensionHours, declination)`, `moveAxis(axis, degreesPerSecond)`, …); `AlpacaResponseParser.parse(response, request)` / `.configuredDevices(response, request)`;
`AlpacaMethod.GET/PUT`, `AlpacaGuideDirection.NORTH…`, `AlpacaAxis.PRIMARY…`; `AlpacaDiscovery.port`, `ipv6MulticastGroup`, `requestPayload`, `responsePayload(alpacaPort)`, `parseResponse(bytes)`.

Behaviour: connect = `PUT connected=True`, `GET connected`, `equatorialsystem`, capability probes (`canslewasync`, `cansync`, `canpulseguide`, `cansettracking`, `canmoveaxis` 0/1; a 0x400 reply counts as "no"), `name`, one
position read. Slews are asynchronous (`slewtocoordinatesasync`); completion is read from `slewing`; `atpark` is checked first (→ `.parked`). `ErrorNumber` mapping
(`AlpacaCodec.error`): 0x400/0x40C → `.notSupported(.other)`, 0x401/0x402 → `.rejected`, 0x407 → `.notConnected`, 0x408 → `.parked`, 0x409 → `.rejected`; 0x40B (InvalidOperation), 0x4FF and the driver range
0x500…0xFFF are classified by the message (*park*, *align*, *horizon/below/altitude*, *limit*, *tracking*, *slewing/in progress/moving/busy*) and otherwise become `.remote(code:detail:)`. HTTP status ≠ 200 →
`.remote(code: status, detail: body)`; a different `ClientTransactionID` echoed → `.malformedReply`. Nudges: guide rate (or no `moveaxis`) → `pulseguide` (direction codes are standardised, the mount times
the pulse); faster rates → `moveaxis`, whose sign ASCOM leaves to the driver (`primaryPositiveIsEast`/`secondaryPositiveIsNorth`), stopped with rate 0. Site/time: `sitelatitude`, `sitelongitude`, `siteelevation`, `utcdate`.

### JSON (internal tool, public)

`MountJSON` (`null | bool | number | text | array | object`, Kotlin `MountJSON.Null/Bool/Number/Text/Array/Object`): `MountJSON.parse(_ text: String) throws`, `field(_ name:)` (case-insensitive for ASCII);
strict RFC 8259 grammar, ≤ 262 144 UTF-16 units, depth ≤ 32, no lone surrogates, finite numbers only.

---------------------------------------------------------------------------------------------------------

## Simulators (for tests, UI previews and the demo mode of the connection screen)

All are deterministic: time comes from the `ManualMountClock`; every reply costs `latencySeconds` (default 0.05) of simulated time and every timeout costs its full duration, so deadlines behave as on a real link.

```swift
public final class MountSimulatorModel {                           // the moving mount: slews at a finite rate, reports slewing, syncs, nudges
    public init(clock: ManualMountClock, start: EquatorialCoordinate, slewRateDegreesPerSecond: Double = 4, startDelaySeconds: Double = 0.4)
    public var position: EquatorialCoordinate { get };  public var isSlewing: Bool { get };  public var isNudging: Bool { get }
    public var isTracking = true, isAligned = true, isParked = false;  public private(set) var slewsStarted, syncsPerformed, stopsReceived: Int
    public func beginSlew(to:), stop(), sync(to:), setNudgeRates(rightAscensionDegreesPerSecond:declinationDegreesPerSecond:), displace(rightAscensionDegrees:declinationDegrees:) }
public enum MountSimulatedFault { case silence, replace(text: String), truncate(keep: Int), disconnect }
open class MountSimulatedStreamTransport: MountTransport {         // base of the two stream simulators
    public func inject(_ commandPrefix: String, _ fault: MountSimulatedFault, times: Int = 1);  public var commandLog: [String] { get };  public func clearLog() }
public final class LX200Simulator: MountSimulatedStreamTransport {
    public init(clock:, mount: MountSimulatorModel, dialect: LX200Dialect = .onstep, highPrecision: Bool = true, productName: String? = nil, latencySeconds: Double = 0.05, chunkSize: Int = 0)
    public var highPrecision: Bool;  public var busyReplies: Int;  public var refusalCode: Character?;  public var alignedStars: Int;  public var siteCommands: [String] { get } }
public final class NexStarSimulator: MountSimulatedStreamTransport {
    public init(clock:, mount:, flavor: NexStarFlavor = .synscan, major: Int = 4, minor: Int = 38, patch: Int = 6, model: Int = 128, trackingMode: Int = 1, latencySeconds:, chunkSize:)
    public var trackingMode: Int;  public private(set) var receivedLocation, receivedTime: [Int] }
public enum AlpacaFault { case status(statusCode: Int, body: String), body(text: String), error(number: Int, message: String), wrongTransactionID, timeout }
public final class AlpacaSimulator: AlpacaTransport {
    public init(clock:, mount:, equatorialSystem: Int = 1, canSync: Bool = true, canPulseGuide: Bool = true, canMoveAxis: Bool = true, deviceName: String = "Simulated Alpaca Mount", deviceNumber: Int = 0, latencySeconds: Double = 0.05)
    public func inject(_ method: String, _ fault: AlpacaFault, times: Int = 1);  public var methodLog: [String] { get };  public var siteValues: [String] { get };  public var isConnected: Bool { get } }
```
Kotlin: `MountSimulatorModel(clock, start, slewRateDegreesPerSecond = 4.0, startDelaySeconds = 0.4)`, `LX200Simulator(clock, mount, dialect = LX200Dialect.ONSTEP, highPrecision = true, …)`, `NexStarSimulator(clock, mount, flavor, major, minor, patch, model, trackingMode, …)`,
`AlpacaSimulator(clock, mount, equatorialSystem, canSync, canPulseGuide, canMoveAxis, deviceName, deviceNumber, latencySeconds)`, `MountSimulatedFault.Silence/Replace/Truncate/Disconnect`, `AlpacaFault.Status/Body/Error/WrongTransactionID/Timeout`.
A demo mount without hardware: the controller, the driver and the simulator must share one `ManualMountClock`
(`MountController(driver: LX200Driver(transport: simulator, clock: clock), observer: …, clock: clock)`); for a live demo advance that clock by the elapsed real time
(`clock.advance(seconds:)`) before every `poll()` or command. `awaitSettled()` on a manual clock returns instantly (it advances the clock itself).

---------------------------------------------------------------------------------------------------------

## Notes for the UI agents

### Transports each platform has to implement

| Protocol | Transport | iOS (Network.framework / URLSession) | Android (java.net / OkHttp) |
|---|---|---|---|
| LX200 over Wi-Fi bridge (SkyFi 4030, OnStep SmartWebServer 9999, many others) | `MountTransport` over a **TCP** socket | `NWConnection(host:port:using: .tcp)`; `receive(minimumIncompleteLength: 1, maximumLength: maxCount)` bridged to the blocking call with a semaphore + timeout | `Socket` with `connect(timeout)`, `soTimeout` per `receive`; `InputStream.read(buf, 0, maxCount)` returns what has arrived |
| NexStar/SynScan via a Wi-Fi serial adapter (the SynScan Wi-Fi adapter relays UDP, commonly port 11880; verify with the adapter's manual) or Celestron WiFi bridges | `MountTransport` over **UDP** (a connected socket: one datagram per `send`, replies as datagrams) or TCP | `NWConnection(…, using: .udp)`; `discardPendingInput` = drain with a zero timeout | `DatagramSocket.connect(addr)`; `receive` with `soTimeout`; drain with `soTimeout = 1` |
| Alpaca | `AlpacaTransport` over **HTTP** (plain `http://`, LAN address) + **UDP broadcast** for discovery | `URLSession` (ephemeral, `timeoutIntervalForRequest`), `NWConnection` UDP for discovery | `HttpURLConnection`/OkHttp; `DatagramSocket` with `broadcast = true` for discovery |

Transport details that bite:
- `receive` must return as soon as *any* bytes are there (do not wait for `maxCount`); `maxCount` is an upper bound, never exceed it. One `MountTransport` instance serves one exchange at a time.
- Map "no data before the deadline" to `MountError.timeout("…")`, a closed/reset connection to `MountError.transport("…")`. Re-create the transport (and driver/controller) after a `transport` error or a `connectionLost`.
- UDP: use one socket "connected" to the mount; the engine flushes with `discardPendingInput()` before each command. Datagram boundaries are irrelevant (the engine reassembles).
- HTTP: Alpaca uses **cleartext http** to a LAN address. iOS: App Transport Security needs `NSAppTransportSecurity → NSAllowsLocalNetworking = YES`. Android: network security config / `usesCleartextTraffic` for the LAN address (or a `<domain-config cleartextTrafficPermitted>` for the discovered host).
- Send the body of a `PUT` as `request.body` with `Content-Type: application/x-www-form-urlencoded`; decode the response body as UTF-8; pass status and body to the engine as they are (also 400/500).

**iOS permissions** (add to the app's Info.plist; none of this can be compiled or tested here — verify on a device):
- `NSLocalNetworkUsageDescription` — mandatory since iOS 14 for any connection to a LAN address (the mount, the SynScan adapter, an Alpaca server). Suggested text: "Lodestar connects to your telescope mount over the local network." The system prompt appears the first time a local connection is attempted; if the user denies it, connections fail (surface this as "Allow Local Network access in Settings").
- UDP **broadcast** (Alpaca discovery) and multicast need Apple's *Multicast Networking* entitlement (`com.apple.developer.networking.multicast`, granted on request) — without it send the discovery datagram to the subnet's directed broadcast address, or let the user type the IP address (always offer manual entry).
- `NSBonjourServices` is only needed if the app browses Bonjour (`_http._tcp`, e.g. to list an OnStep SmartWebServer or Alpaca servers that advertise themselves); each browsed type must be listed. The engine does not use Bonjour.
- Joining the mount's own access point (Star Adventurer GTi, SynScan adapter, OnStep AP mode, "192.168.4.1"): `NEHotspotConfiguration` (capability *Hotspot Configuration*) can join by SSID; otherwise tell the user to join in Settings. iOS may prefer cellular when the access point has no internet — a connection to the mount's IP still works.

**Android permissions/behaviour**: `INTERNET`, `ACCESS_NETWORK_STATE`; `CHANGE_WIFI_MULTICAST_STATE` only if you receive multicast (broadcast replies are unicast). A Wi-Fi network without internet is dropped in favour of mobile data
for app traffic: bind sockets to the Wi-Fi `Network` (`ConnectivityManager.requestNetwork` + `Network.socketFactory` / `bindSocket`), or the packets to 192.168.x.x may not leave over Wi-Fi. Connecting to the mount's access
point programmatically (API 29+): `WifiNetworkSpecifier` (needs the user's approval dialog); `NEARBY_WIFI_DEVICES` on API 33+ for discovery of access points.

### Connection screen flow
1. **Choose the mount type**: *Wi-Fi telescope (LX200 / OnStep / SkyFi)* → TCP, host, port (defaults 9999 OnStep, 4030 SkyFi); *Celestron / Sky-Watcher SynScan hand control (Wi-Fi adapter)* → UDP/TCP, host, port; *ASCOM Alpaca* → discovery list + manual entry.
   Persist the choice and the address. Show the epoch only in an "Advanced" section (`NexStarConfiguration.epoch`, `LX200Configuration.epoch`, `AlpacaConfiguration.primaryPositiveIsEast`, `NexStarConfiguration.invertsEastWest`).
2. **Alpaca discovery**: broadcast `AlpacaDiscovery.requestPayload` to UDP `AlpacaDiscovery.port` (every local broadcast address; IPv6 `AlpacaDiscovery.ipv6MulticastGroup` optional), collect replies for ~2 s, `AlpacaDiscovery.parseResponse` each (host = sender address), then for each
   server `GET` the `configureddevices` request through the HTTP transport and list the telescopes (`isTelescope`; show `deviceName`, host); the user picks one; `AlpacaConfiguration(deviceNumber:)`.
3. **Connect** on the worker thread with a spinner and a Cancel button (a connect can take up to ~12 exchanges; every one has its own timeout): `controller.connect()`. Errors: show `error.userMessage`; typical hints — timeout: "Is the phone on the mount's Wi-Fi? Is the right port selected?"
   (+ iOS: Local Network permission); `unsupportedCoordinateSystem`: "Set the telescope to JNow/J2000 in its driver".
4. **Show what was found**: `capabilities.name`, `firmware`, protocol; disable the Sync / Nudge / Tracking controls that `capabilities` says are missing (`canSync`, `canNudge`, `canSetTracking`); if `capabilities.isAligned == false` or later `status.isAligned == false`: banner "The mount is not aligned. Align it on the hand controller, then continue." (`poll()` notices when it is done).
5. **Set site and time** (button or automatic once, if `canSetSiteAndTime`): `controller.setSiteAndTime(location:)` — needed by OnStep and the hand controls, which compute their own sky; text "Sends your location and the phone's time (UTC) to the mount."
6. **Settings** (global, from the existing Settings screen): *Altitude limit* (default 0°, range −10…60), *Maximum sync offset* (optional), *Nudge rate*, *Slew timeout* (advanced).

### Aim screen while a mount is connected
- Replace the guidance arrows by a **mount card**: name + connection dot (green connected / red lost), "Pointing: RA/Dec" (`status.position`), **distance to target** `status.separation` (degrees, large number) and a progress bar based on the separation at slew start, a phase line: *Slewing… 42 s* (`status.phase == .slewing`, `slewElapsedSeconds`), *Arrived — 0.02° from target* (`outcome == .arrived`), *Stopped 3.1° short of the target* (`outcome == .failed(.slewStopped)` → `error.userMessage`), *Aborted*.
- Buttons: **Go to** (only when `phase == .idle`; confirm once per session with the altitude-limit text; disabled and explained when the target is below the limit — use the same SkyFrame altitude to pre-check, and always handle the thrown `MountError` by showing `userMessage`), **Stop** (always visible and reachable in night mode while slewing or nudging; calls `abort()`; never disabled except when disconnected), **Sync** ("This star is centred", enabled when idle and the target is set; on success show "Synced (was 1.2° off)" from `offsetDegrees`; `.syncOffsetTooLarge` → ask to check the star), **Tracking** switch (only if `canSetTracking`; shows `status.isTracking`), **Nudge pad** (four arrows + a rate picker guide/centre/find/slew; hold = repeat `nudge(direction, rate, durationSeconds: 0.5)` every 0.4 s, release = `stopNudge()`; map screen directions to north/east/south/west from the sky orientation, the engine's `east` is +RA).
- **Polling**: one background worker that owns the controller: `poll()` every 1 s while connected (0.5 s while nudging), commands queued on the same worker, UI updated from `status`. Never poll from the main thread; `connect()`/`goto()` can block for the exchange timeouts. While the app is in the background: `abort()` any slew/nudge and `disconnect()` (or at least stop nudges).
- **Errors**: show `status.lastError?.userMessage` as a dismissible line (communication hiccups clear themselves on the next good poll); three failed polls turn the card red with "Connection lost — reconnect"; `phase == .disconnected` returns to the connection screen's reconnect action. A failed `goto` never leaves a slew running: if the app is unsure it already asked the mount to stop.
- Keep the phone-sensor guidance available as a secondary view ("Check framing"): after `Arrived` the phone's attitude can show the residual error, and the user can nudge/sync from there.
- Night mode: all of the above uses the red palette; the Stop button stays high-contrast red-on-dark and must be reachable with one thumb.
- Accessibility: announce phase changes ("Slewing", "Arrived", "Stopped") and give the Stop button a label ("Stop mount").

---------------------------------------------------------------------------------------------------------

## Tests (mirrored: same names, inputs, expected values and tolerances in Swift and Kotlin)

185 mount tests per language (Kotlin whole kit 1289 green, Swift whole package 1127 green; zero compiler warnings in the Mount files):

| File | Tests | What it checks |
|---|---|---|
| `LX200CodecTests` | 22 | encode/decode examples from the Meade/OnStep references: RA/Dec in both precisions, 24 h wrap and carries, negative declinations (`-00*30`, no `-00*00`), apostrophe and 0xDF separators, fractional seconds, boundaries, 40+ malformed replies, `:MS#` digits, `:GU#`, `:GW#`, site/time commands (west-positive longitude, UTC offset in both dialects), 500 random round trips |
| `NexStarCodecTests` | 16 | the documentation's own examples (`34AB,12CE` → 26.4441°, `12AB0500` → 26.252°, `R34AB,12CE`, `r34AB0500,12CE0500`, rate 150″/s → 2/88, `W` 118°20′17″ W 33°50′41″ N, `H` 3:26 PM zone −5), negative declinations, RA wrap, version formats, 18 malformed replies, 500 random round trips |
| `AlpacaCodecTests` | 23 | request paths/parameters against the OpenAPI definitions, transaction numbering (wrap), form encoding, number/date formatting, reply parsing, error-number mapping, HTTP errors, wrong transaction, discovery datagram and reply, management lists |
| `MountJSONTests` | 6 | grammar, escapes, surrogates, limits, 40 malformed documents |
| `MountEpochTests` | 8 | Meeus 21.b / 23.a, proper motion, round trips (3 frames × 5 dates × 51 places incl. poles and RA wrap), raw values |
| `MountSupportTests` | 6 | calendar arithmetic against the platform library, clamping of settings/configurations, error texts, clocks |
| `MountControllerTests`, `…CommandTests`, `…FaultTests` | 12 + 18 + 22 | all six simulator rigs end to end: connect, goto completes (J2000 → mount frame → J2000), sync, abort mid-slew, second goto refused, altitude limit and lookahead, nudges in all directions, tracking, site/time, silent mount, partial/garbled/truncated replies, 1-byte chunks, NAK retries, refusals (OnStep digits 1…9, Meade text, unaligned, parked, tracking off, Alpaca error numbers), slew timeout, stopped short, never started, link lost during a slew, dropped connection, abort retries |
| `MountDriverTests` | 31 | precision toggling (once), forced dialect, firmware gates, epoch defaults/overrides, tracking-mode memory, pass-through failure, unaligned/meaningless positions, Alpaca fallbacks, request numbering, sign configuration, transport exceptions |
| `MountSimulatorTests` | 21 | the simulators themselves: physics, raw replies of OnStep/Meade/NexStar/SynScan/Alpaca, fault injection, chunking |

Costs: every codec call is microseconds; one `SkyFrame` per poll (≈ 10 µs); nothing allocates per frame. Memory: a few hundred bytes per controller.

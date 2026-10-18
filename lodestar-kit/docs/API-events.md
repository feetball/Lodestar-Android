# Astronomical events calendar — API (Kotlin and Swift)

Kotlin package `net.kuecker.lodestar.kit.events` (`lodestar-kit`), Swift folder `Sources/LodestarKit/Events` (`LodestarKit`).
Both implementations have the same types, members, constants, algorithms and evaluation order; every test exists in both
(90 per language, `Events*Tests`). Everything is computed offline from the kit's ephemerides (`Ephemeris`: VSOP87 planets, Meeus
Moon; `MoonPhaseCalculator`, `TwilightCalculator`, `SkyFrame`), with no network access and no global state.

Naming map (as in the other API documents): Swift `struct` ↔ Kotlin immutable `data class`; Swift `enum` without payload ↔ `enum class`
with UPPER_SNAKE constants and `rawValue`; Swift `enum` with payloads ↔ `sealed interface` (nested class = capitalized case name);
`Date` ↔ `java.time.Instant`; `TimeZone` ↔ `ZoneId`; `DateInterval` ↔ Foundation's `DateInterval`; Swift argument labels
disappear in Kotlin (parameter names are the Swift internal names). Imports: `import net.kuecker.lodestar.kit.events.*`
(plus `…ephemeris.*` and `…foundation.*` for `SkyFrame`, `Observer`, `SolarSystemBody`).

## Quick start

```kotlin
val now = Instant.now()
val observer = Observer(GeographicLocation(32.78, -96.80, 150.0))
val options = EventOptions(timeZone = ZoneId.systemDefault(), moonConjunctionThreshold = 3.0)
val events = EventCalculator.events(now, now.plus(366, ChronoUnit.DAYS), observer, options)   // sorted, with visibility
for (event in events) println("${event.date}  ${event.title}  window=${event.visibility?.bestWindow}")
```

```swift
let now = Date()
let observer = Observer(location: GeographicLocation(latitude: 32.78, longitude: -96.80, elevation: 150))
let options = EventOptions(moonConjunctionThreshold: 3, timeZone: .current)
let events = EventCalculator.events(from: now, to: now.addingTimeInterval(366 * 86_400), observer: observer, options: options)
for event in events { print(event.date, event.title, event.visibility?.bestWindow as Any) }
```

## Entry points

```kotlin
object EventCalculator {
    const val maximumSpanDays: Double                                  // 3660.0; longer intervals are cut at this length
    fun events(from: Instant, to: Instant, observer: Observer? = null,
               options: EventOptions = EventOptions()): List<AstronomicalEvent>      // sorted by date, ties by kind
    fun visibility(event: AstronomicalEvent, observer: Observer,
                   options: EventOptions = EventOptions()): EventVisibility?         // null for equinoxes, solstices, apsides
}
```
```swift
public enum EventCalculator {
    public static let maximumSpanDays: Double
    public static func events(from: Date, to: Date, observer: Observer? = nil,
                              options: EventOptions = EventOptions()) -> [AstronomicalEvent]
    public static func visibility(of event: AstronomicalEvent, observer: Observer,
                                  options: EventOptions = EventOptions()) -> EventVisibility?
}
```
`from`/`to` are both inclusive. A reversed or non-finite interval gives an empty list. With an observer, every event except the
equinoxes, solstices and apsides carries an `EventVisibility`, and solar eclipses carry their local circumstances; without one
`visibility == nil` and solar eclipses are global only. The finders below can be used alone (each returns events without
visibility, except that `ConjunctionFinder` and `SolarEclipseFinder` take an optional observer for the topocentric parts).

```kotlin
object MeteorShowerFinder       { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object ConjunctionFinder        { fun events(from: Instant, to: Instant, observer: Observer? = null, options: EventOptions = EventOptions()): List<AstronomicalEvent> }
object OppositionFinder         { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object GreatestElongationFinder { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object EarthApsisFinder         { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object SeasonFinder             { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object FullMoonSpecialFinder    { const val supermoonMaximumDistanceKilometers: Double      // 362_500
                                  const val micromoonMinimumDistanceKilometers: Double      // 400_600
                                  fun events(from: Instant, to: Instant, timeZone: ZoneId = ZoneOffset.UTC): List<AstronomicalEvent> }
object LunarEclipseFinder       { fun events(from: Instant, to: Instant): List<AstronomicalEvent> }
object SolarEclipseFinder       { fun events(from: Instant, to: Instant, observer: Observer? = null): List<AstronomicalEvent>
                                  fun localCircumstances(eclipse: SolarEclipse, observer: Observer): LocalSolarCircumstances }
```
```swift
public enum MeteorShowerFinder       { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum ConjunctionFinder        { static func events(from: Date, to: Date, observer: Observer? = nil, options: EventOptions = EventOptions()) -> [AstronomicalEvent] }
public enum OppositionFinder         { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum GreatestElongationFinder { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum EarthApsisFinder         { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum SeasonFinder             { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum FullMoonSpecialFinder    { static let supermoonMaximumDistanceKilometers = 362_500.0
                                       static let micromoonMinimumDistanceKilometers = 400_600.0
                                       static func events(from: Date, to: Date, timeZone: TimeZone = .gmt) -> [AstronomicalEvent] }
public enum LunarEclipseFinder       { static func events(from: Date, to: Date) -> [AstronomicalEvent] }
public enum SolarEclipseFinder       { static func events(from: Date, to: Date, observer: Observer? = nil) -> [AstronomicalEvent]
                                       static func localCircumstances(of eclipse: SolarEclipse, observer: Observer) -> LocalSolarCircumstances }
```

## Options

```kotlin
@ConsistentCopyVisibility data class EventOptions private constructor(
    val kinds: Set<EventKind>,                 // default EventKind.defaultKinds = every kind except EARTH_APSIS
    val planetConjunctionThreshold: Double,    // degrees, 0…30, default 3
    val moonConjunctionThreshold: Double,      // degrees, 0…30, default 5
    val minimumSolarElongation: Double,        // degrees, 0…90, default 10: closer conjunctions are dropped
    val timeZone: ZoneId?,                     // null: UTC without an observer, else the whole-hour zone of the longitude
    val minimumAltitude: Double,               // degrees, 0…90, default 10: a target must reach it for a viewing window
    val visibleOnly: Boolean,                  // drop events with no viewing window (needs an observer)
) { companion object { operator fun invoke(kinds: Set<EventKind> = EventKind.defaultKinds, planetConjunctionThreshold: Double = 3.0,
        moonConjunctionThreshold: Double = 5.0, minimumSolarElongation: Double = 10.0, timeZone: ZoneId? = null,
        minimumAltitude: Double = 10.0, visibleOnly: Boolean = false): EventOptions } }       // non-finite → default; out of range → clamped
@Serializable enum class EventKind(val rawValue: String) {     // METEOR_SHOWER, CONJUNCTION, OPPOSITION, GREATEST_ELONGATION,
    val displayName: String                                    // EARTH_APSIS, SEASON, FULL_MOON_SPECIAL, LUNAR_ECLIPSE, SOLAR_ECLIPSE
    companion object { val defaultKinds: Set<EventKind>; fun fromRawValue(raw: String): EventKind? }
}
```
```swift
public struct EventOptions: Sendable, Hashable {
    public let kinds: Set<EventKind>; public let planetConjunctionThreshold: Double; public let moonConjunctionThreshold: Double
    public let minimumSolarElongation: Double; public let timeZone: TimeZone?; public let minimumAltitude: Double; public let visibleOnly: Bool
    public init(kinds: Set<EventKind> = EventKind.defaultKinds, planetConjunctionThreshold: Double = 3, moonConjunctionThreshold: Double = 5,
                minimumSolarElongation: Double = 10, timeZone: TimeZone? = nil, minimumAltitude: Double = 10, visibleOnly: Bool = false)
}
public enum EventKind: String, CaseIterable, Sendable, Hashable, Codable {    // meteorShower, conjunction, opposition, greatestElongation,
    public static let defaultKinds: Set<EventKind>; public var displayName: String   // earthApsis, season, fullMoonSpecial, lunarEclipse, solarEclipse
}
```
Raw values (JSON): `meteorShower`, `conjunction`, `opposition`, `greatestElongation`, `earthApsis`, `season`, `fullMoonSpecial`,
`lunarEclipse`, `solarEclipse`; all other enums below serialize as their camel-case raw value in both languages (Kotlin `@Serializable`,
Swift `Codable`).

## The event

```kotlin
data class AstronomicalEvent(val date: Instant, val details: EventDetails, val visibility: EventVisibility?) {
    val kind: EventKind
    val title: String                                          // English default, e.g. "Perseids peak", "Venus and Jupiter", "Total lunar eclipse"
    val aimTargets: List<AimTarget>                            // what to point at, main subject first; empty for seasons/apsides
    fun aimDirection(frame: SkyFrame, refraction: Boolean = true): HorizontalCoordinate?   // the target, or the midpoint of a conjunction
}
sealed interface EventDetails { val kind: EventKind
    data class MeteorShower(val peak: MeteorShowerPeak); data class Conjunction(val conjunction: …); data class Opposition(val opposition: …)
    data class GreatestElongation(val elongation: …); data class EarthApsis(val apsis: …); data class Season(val season: …)
    data class FullMoonSpecial(val special: …); data class LunarEclipse(val eclipse: …); data class SolarEclipse(val eclipse: …) }
sealed interface AimTarget {
    data class Body(val body: SolarSystemBody); data class Fixed(val position: EquatorialCoordinate, val properMotion: ProperMotion?)   // J2000
    data class Radiant(val shower: MeteorShower)                                                                                        // drifts with the frame's date
    fun horizontal(frame: SkyFrame, refraction: Boolean = true): HorizontalCoordinate
}
```
```swift
public struct AstronomicalEvent: Sendable, Hashable {
    public let date: Date; public let details: EventDetails; public let visibility: EventVisibility?
    public var kind: EventKind { get }; public var title: String { get }; public var aimTargets: [AimTarget] { get }
    public func aimDirection(in frame: SkyFrame, refraction: Bool = true) -> HorizontalCoordinate?
}
public enum EventDetails: Sendable, Hashable { case meteorShower(MeteorShowerPeak), conjunction(Conjunction), opposition(Opposition),
    greatestElongation(GreatestElongation), earthApsis(EarthApsis), season(Season), fullMoonSpecial(FullMoonSpecial),
    lunarEclipse(LunarEclipse), solarEclipse(SolarEclipse); public var kind: EventKind { get } }
public enum AimTarget: Sendable, Hashable { case body(SolarSystemBody), fixed(EquatorialCoordinate, ProperMotion?), radiant(MeteorShower)
    public func horizontal(in frame: SkyFrame, refraction: Bool = true) -> HorizontalCoordinate }
```
`date` is: the peak of a meteor shower; the minimum separation of a conjunction; the opposition; the greatest elongation; the apsis;
the equinox or solstice; the full Moon; the greatest eclipse of a lunar eclipse; the greatest eclipse **anywhere on Earth** of a solar
eclipse (the observer's own maximum is `SolarEclipse.local.maximum`).

### Visibility

```kotlin
data class EventVisibility(val altitude: Double, val azimuth: Double,     // the subject at the event's instant (geometric degrees)
    val sunAltitude: Double, val isDark: Boolean,                         // isDark: Sun below −18° at that instant
    val bestWindow: DateInterval?, val bestTime: Instant?,                // see the rules below
    val darknessLimit: Double,                                            // Sun altitude the window required
    val contacts: List<ContactVisibility>) { val isObservable: Boolean }  // isObservable = bestWindow != null
data class ContactVisibility(val contact: EclipseContact, val time: Instant, val altitude: Double, val azimuth: Double)
@Serializable enum class EclipseContact(val rawValue: String) { P1, U1, U2, GREATEST, U3, U4, P4, C1, C2, MAXIMUM, C3, C4 }   // "P1" … "greatest", "maximum"
```
```swift
public struct EventVisibility: Sendable, Hashable { altitude, azimuth, sunAltitude: Double; isDark: Bool; bestWindow: DateInterval?; bestTime: Date?
    darknessLimit: Double; contacts: [ContactVisibility]; public var isObservable: Bool { get } }
public struct ContactVisibility: Sendable, Hashable { contact: EclipseContact; time: Date; altitude: Double; azimuth: Double }
public enum EclipseContact: String, CaseIterable, Sendable, Hashable, Codable { case p1 = "P1", u1 = "U1", u2 = "U2", greatest, u3 = "U3", u4 = "U4", p4 = "P4",
    c1 = "C1", c2 = "C2", maximum, c3 = "C3", c4 = "C4" }
```
Rules (the *subject* is what the event is about):

| Kind | Subject | Window requires | `darknessLimit` |
| --- | --- | --- | --- |
| meteor shower | the radiant (J2000 place at the peak) | Sun below −18° and the radiant ≥ `minimumAltitude` | −18° |
| conjunction | the planet or star (Moon with a planet or star), the midpoint of two planets | Sun below −6°, target ≥ `minimumAltitude` | −6° |
| opposition, greatest elongation | the planet (its place at the event time for the whole night) | Sun below −6°, planet ≥ `minimumAltitude` | −6° |
| full-Moon special | the Moon (exact, moving) | Sun below −0.8333° (after sunset), Moon ≥ `minimumAltitude` | −0.8333° |
| lunar eclipse | the Moon | the umbral phase U1…U4 (P1…P4 for a penumbral eclipse) while the Moon's centre is above −0.5667° | 0 (not applied) |
| solar eclipse | the Sun | the part of C1…C4 with the Sun's centre above −0.8333° | 0 (not applied) |
| equinox, solstice, apsis | — | `visibility == nil` | — |

"That night" is the local noon-to-noon interval (in `EventOptions.timeZone`, see its default above) that contains the event; the
window is the stretch around the subject's highest point; the edges come from root finding (to 0.1 s), samples are 15 minutes apart
(5 for lunar eclipses, 10 for solar ones). `bestTime` is the instant of the subject's highest altitude in the window (the greatest eclipse when it is
visible for a lunar eclipse, the maximum for a solar one). Altitudes are geometric (no refraction); use `SkyFrame.horizontal(…, refraction: true)`
for display. In polar summer the astronomical night does not exist and meteor windows are `nil`.

## Meteor showers

```kotlin
data class MeteorShower(val code: String, val iauNumber: Int, val name: String,
    val activityStartMonth: Int, val activityStartDay: Int, val activityEndMonth: Int, val activityEndDay: Int,
    val peakSolarLongitude: Double,                 // equinox J2000.0: fixes the date every year
    val zhr: Int, val radiant: EquatorialCoordinate /* J2000, at the peak */,
    val radiantDriftRightAscension: Double, val radiantDriftDeclination: Double /* degrees per day */,
    val velocity: Double /* km/s */, val populationIndex: Double) {
    fun peak(date: Instant): Instant                         // the maximum nearest to `date` (Swift peak(near:))
    fun radiant(date: Instant): EquatorialCoordinate         // the aimable position, J2000, drifted (Swift radiant(at:))
    fun activityPeriod(peak: Instant): DateInterval          // Swift activityPeriod(aroundPeak:)
    fun isActive(date: Instant): Boolean                     // Swift isActive(at:)
    companion object { val all: List<MeteorShower>; fun withCode(code: String): MeteorShower? }
}
data class MeteorShowerPeak(val shower: MeteorShower, val activityStart: Instant, val activityEnd: Instant,
                            val moonIlluminatedFraction: Double /* at the peak */, val radiant: EquatorialCoordinate)
```
```swift
public struct MeteorShower: Sendable, Hashable { /* same members */
    public func peak(near date: Date) -> Date; public func radiant(at date: Date) -> EquatorialCoordinate
    public func activityPeriod(aroundPeak peak: Date) -> DateInterval; public func isActive(at date: Date) -> Bool
    public static let all: [MeteorShower]; public static func withCode(_ code: String) -> MeteorShower?
}
public struct MeteorShowerPeak: Sendable, Hashable { shower, activityStart, activityEnd, moonIlluminatedFraction, radiant }
```
The list (18 showers; IAU codes): QUA, ACE, LYR, ETA, ARI, SDA, CAP, PER, AUR, SPE, DRA, ORI, STA, NTA, LEO, HYD, GEM, URS. Source: IMO
*2026 Meteor Shower Calendar* Table 5 (activity, λ☉ of the maximum, radiant, V∞, r, ZHR) and Table 6 (radiant positions; the drift is the
least-squares slope of the tabulated α and δ — points in the appendix). Omitted: ZHR below 5, variable ZHR (π-Puppids, June Bootids,
α-Monocerotids, Phoenicids), one-night showers, the Puppid-Velids (reference date only) and the daytime radio showers. The maximum
is when the Sun's apparent ecliptic longitude (J2000.0 equinox) equals `peakSolarLongitude` (Newton steps, 5 solar positions); this
reproduces the maxima in the IMO text to a few minutes (Lyrids 19:45 UT vs 19h 40m, Leonids 23:51 vs 23h 45m, Perseids 02:09 vs 02–04h).
The radiant drift is extrapolated linearly for at most the length of the activity period on either side of the maximum.

## Conjunctions

```kotlin
@Serializable enum class ReferenceStar(val rawValue: String) { REGULUS, SPICA, ANTARES, ALDEBARAN, PLEIADES
    val displayName: String; val position: EquatorialCoordinate /* J2000 */; val properMotion: ProperMotion }
sealed interface ConjunctionTarget { data class Body(val body: SolarSystemBody); data class Star(val star: ReferenceStar)
    val displayName: String; val aimTarget: AimTarget }
data class Conjunction(val first: ConjunctionTarget,            // the Moon when it takes part
                       val second: ConjunctionTarget,           // a planet or a star
                       val separation: Double,                  // least geocentric separation, degrees
                       val solarElongation: Double,             // of the Moon, or of the midpoint of two planets
                       val occultationPossible: Boolean,        // Moon: separation < semidiameter + horizontal parallax + target radius
                       val topocentric: TopocentricApproach?) { val involvesMoon: Boolean }
data class TopocentricApproach(val time: Instant, val separation: Double, val isOccultation: Boolean)
```
```swift
public enum ReferenceStar: String, CaseIterable, Sendable, Hashable, Codable { case regulus, spica, antares, aldebaran, pleiades
    public var displayName: String; public var position: EquatorialCoordinate; public var properMotion: ProperMotion }
public enum ConjunctionTarget: Sendable, Hashable { case body(SolarSystemBody), star(ReferenceStar); displayName; aimTarget }
public struct Conjunction: Sendable, Hashable { first, second, separation, solarElongation, occultationPossible, topocentric; involvesMoon }
public struct TopocentricApproach: Sendable, Hashable { time: Date; separation: Double; isOccultation: Bool }
```
Pairs: the Moon with Mercury…Neptune and with Regulus, Spica, Antares, Aldebaran and the Pleiades; every pair of planets from Mercury
to Neptune. The planets are sampled daily (geometric heliocentric series, Earth shared) and the Moon every six hours; a local minimum of
the separation is kept when the parabola through the squares of three samples comes within 0.5° of the threshold, and refined by
golden section on the exact apparent geocentric positions (1e-5 d ≈ 1 s). The Moon's topocentric note is the least separation
as seen from the observer within ±3 h of the geocentric minimum (parallax shifts the Moon by up to 1°): a Moon–Venus conjunction of
0.27° geocentric is 0.16° from Dallas and 0.73° from Sydney. Default thresholds: 3° (planet pairs), 5° (Moon): about 110 events a year,
most of them Moon events — use `moonConjunctionThreshold = 2` or 3 for a calendar. `solarElongation < minimumSolarElongation` drops pairs
in the Sun's glare.

## Oppositions, elongations, apsides, seasons

```kotlin
data class Opposition(val planet: SolarSystemBody, val distanceAU: Double, val apparentMagnitude: Double?, val angularDiameterArcseconds: Double)
@Serializable enum class ElongationDirection(val rawValue: String) { EAST, WEST; val displayName: String }
data class GreatestElongation(val planet: SolarSystemBody, val direction: ElongationDirection, val angle: Double, val apparentMagnitude: Double?)
@Serializable enum class EarthApsisKind(val rawValue: String) { PERIHELION, APHELION; val displayName: String }
data class EarthApsis(val kind: EarthApsisKind, val distanceAU: Double)
@Serializable enum class SeasonKind(val rawValue: String, val solarLongitude: Double) { MARCH_EQUINOX, JUNE_SOLSTICE, SEPTEMBER_EQUINOX, DECEMBER_SOLSTICE; val displayName: String }
data class Season(val kind: SeasonKind)
```
```swift
public struct Opposition: Sendable, Hashable { planet: SolarSystemBody; distanceAU: Double; apparentMagnitude: Double?; angularDiameterArcseconds: Double }
public enum ElongationDirection: String, CaseIterable, Sendable, Hashable, Codable { case east, west; displayName }
public struct GreatestElongation: Sendable, Hashable { planet; direction: ElongationDirection; angle: Double; apparentMagnitude: Double? }
public enum EarthApsisKind: String, CaseIterable, Sendable, Hashable, Codable { case perihelion, aphelion; displayName }
public struct EarthApsis: Sendable, Hashable { kind: EarthApsisKind; distanceAU: Double }
public enum SeasonKind: String, CaseIterable, Sendable, Hashable, Codable { case marchEquinox, juneSolstice, septemberEquinox, decemberSolstice; solarLongitude; displayName }
public struct Season: Sendable, Hashable { kind: SeasonKind }
```
* Opposition: Mars, Jupiter, Saturn, Uranus, Neptune; the apparent ecliptic longitude (of date) of the planet minus the Sun's passes 180°
  (5-day scan, Brent). `SeasonKind` is named after the month (hemisphere-neutral); the UI says "vernal"/"autumnal".
* Greatest elongation: Mercury and Venus; the angular distance from the Sun is flat at its maximum, so the instant is the zero of its central
  difference over ±12 h (2-day scan, golden section, Brent): good to a few minutes. `direction` EAST = evening sky.
* Seasons: the Sun's apparent longitude of date reaches 0/90/180/270° (Newton, 4–6 solar states): within 10 s of the USNO tables.
* Apsides (optional, `EventKind.EARTH_APSIS` is not in the default kinds): extremes of the Earth–Sun distance of the VSOP87 series (Earth–Moon
  barycentre); the perihelion is good to ~90 s, the aphelion (a flat minimum of the rate) to ~15 min.

## Full-Moon specials

```kotlin
@Serializable enum class FullMoonCategory(val rawValue: String) { SUPERMOON, MICROMOON, BLUE_MOON; val displayName: String }
data class FullMoonSpecial(val categories: List<FullMoonCategory> /* in enum order */, val distanceKilometers: Double, val angularDiameterArcseconds: Double)
```
```swift
public enum FullMoonCategory: String, CaseIterable, Sendable, Hashable, Codable { case supermoon, micromoon, blueMoon; displayName }
public struct FullMoonSpecial: Sendable, Hashable { categories: [FullMoonCategory]; distanceKilometers: Double; angularDiameterArcseconds: Double }
```
**Definitions used** (the full Moon is lunar elongation 180°, from `MoonPhaseCalculator`):
* *Supermoon*: geocentric centre distance at the full Moon ≤ **362 500 km**. This is the operational form of Nolle's "within 90 % of its closest
  perigee": it reproduces the usual lists (2026: 3 Jan 362 312 km, 24 Nov 360 766 km, 24 Dec 356 738 km; the 2018-01-31 "super blue blood moon").
* *Micromoon*: distance ≥ **400 600 km**, as far from the extreme apogee (406 720 km) as the supermoon cut is from the extreme perigee
  (356 371 km; Meeus ch. 50) — 2026: 1 May, 31 May, 29 Jun.
* *Blue moon*: the second of two full Moons in one calendar month of `timeZone` (monthly definition): 31 May 2026; it depends on the zone
  (the 2020-10-31 14:49 UT full Moon is 1 Nov in Sydney, so Sydney had no Halloween blue moon).
One event per special full Moon; a micromoon that is also blue has both categories.

## Lunar eclipses

```kotlin
@Serializable enum class LunarEclipseKind(val rawValue: String) { PENUMBRAL, PARTIAL, TOTAL; val displayName: String }
data class EclipseContactTime(val contact: EclipseContact, val time: Instant)
data class LunarEclipse(val kind: LunarEclipseKind, val gamma: Double, val penumbralMagnitude: Double, val umbralMagnitude: Double,
    val p1: Instant, val u1: Instant?, val u2: Instant?, val greatest: Instant, val u3: Instant?, val u4: Instant?, val p4: Instant) {
    val contacts: List<EclipseContactTime>; val penumbralDuration: Double; val partialDuration: Double; val totalDuration: Double   // seconds
}
```
```swift
public enum LunarEclipseKind: String, CaseIterable, Sendable, Hashable, Codable { case penumbral, partial, total; displayName }
public struct EclipseContactTime: Sendable, Hashable { contact: EclipseContact; time: Date }
public struct LunarEclipse: Sendable, Hashable { kind, gamma, penumbralMagnitude, umbralMagnitude; p1, u1?, u2?, greatest, u3?, u4?, p4: Date
    public var contacts: [EclipseContactTime] { get }; penumbralDuration, partialDuration, totalDuration (seconds) }
```
Method (equivalent to Meeus ch. 54, but from the engine's geocentric apparent positions): for each full Moon with |β| < 1.7°, the greatest
eclipse is the minimum of the Moon's centre distance ρ from the anti-solar point; the shadow radii at the Moon are
R_u = π☾ + π☉ − s☉ and R_p = π☾ + π☉ + s☉ with the Earth's radius enlarged by **1 %** (Danjon's rule as in the NASA/Espenak canon; the umbra
grows by 1.4 %). Contacts: ρ = R_p + s☾ (P1, P4), R_u + s☾ (U1, U4), R_u − s☾ (U2, U3); magnitudes (R + s☾ − ρ) / 2 s☾; gamma = ρ / π☾ with the Moon's
equatorial parallax, positive north of the axis. `visibility.contacts` gives the Moon's altitude and azimuth at every contact that exists.

## Solar eclipses

```kotlin
@Serializable enum class SolarEclipseKind(val rawValue: String) { PARTIAL, ANNULAR, TOTAL, HYBRID; val displayName: String }
@Serializable enum class LocalSolarEclipseKind(val rawValue: String) { NONE, PARTIAL, ANNULAR, TOTAL }
data class SolarEclipse(val kind: SolarEclipseKind, val greatest: Instant, val gamma: Double, val greatestMagnitude: Double,
                        val isCentral: Boolean, val local: LocalSolarCircumstances?)
data class LocalSolarCircumstances(val kind: LocalSolarEclipseKind, val c1: Instant?, val c2: Instant?, val maximum: Instant?, val c3: Instant?, val c4: Instant?,
    val magnitude: Double, val obscuration: Double, val sunAltitude: Double?, val sunAzimuth: Double?, val centralDuration: Double /* s */, val visibleInterval: DateInterval?)
```
```swift
public enum SolarEclipseKind: String, CaseIterable, Sendable, Hashable, Codable { case partial, annular, total, hybrid; displayName }
public enum LocalSolarEclipseKind: String, CaseIterable, Sendable, Hashable, Codable { case none, partial, annular, total }
public struct SolarEclipse: Sendable, Hashable { kind; greatest: Date; gamma; greatestMagnitude: Double; isCentral: Bool; local: LocalSolarCircumstances? }
public struct LocalSolarCircumstances: Sendable, Hashable { kind; c1, c2, maximum, c3, c4: Date?; magnitude, obscuration: Double; sunAltitude, sunAzimuth: Double?; centralDuration: Double; visibleInterval: DateInterval? }
```
* **Global** (any observer or none): for each new Moon with |β| < 1.75° the greatest eclipse is where the axis through the Sun's and the Moon's
  centres passes nearest the Earth's centre (golden section). The shadow cone gives γ (Earth equatorial radii, positive north) and the radii l₁, l₂ in the
  fundamental plane; kind per Meeus ch. 54: central when |γ| < 0.9972, then total if the umbra reaches the plane (l₂ > 0), hybrid if it falls short by less than
  tan f₂·√(1−γ²) Earth radii, annular otherwise; partial otherwise; non-central total/annular when 0.9972 ≤ |γ| < 0.9972 + |u|.
  **`isCentral`** is the global flag (the axis touches the Earth, so a total/annular/hybrid eclipse occurs somewhere; false for partial and
  non-central eclipses). `greatestMagnitude` follows the NASA catalog: ratio of the Moon's to the Sun's diameter for a central eclipse, the fraction of the Sun's
  diameter covered otherwise (the Earth is a sphere here: partial magnitudes are good to ~0.005).
* **Local** (observer given): the separation of the topocentric apparent Sun and Moon centres is sampled every 20 min within ±7.2 h of the greatest eclipse,
  the maximum found by golden section, C1/C4 where the separation equals the sum of the apparent radii, C2/C3 where it equals their difference (Brent).
  C1…C4 are geometric (as if the horizon did not exist). `kind`/`maximum`/`magnitude`/`obscuration`/Sun position describe what can be **seen**:
  `NONE` when the discs never touch or the Sun's centre stays below −0.8333°; when the Sun rises or sets during the eclipse `maximum` is the best visible
  moment (sunrise or sunset if the true maximum is below the horizon) and `visibleInterval` is the part with the Sun up (compare it with `c1`/`c4`).
  `magnitude` is the fraction of the Sun's diameter covered at `maximum` ((r☉ + r☾ − d)/2r☉, as the U.S. Naval Observatory gives it: 1.015 for the centre of
  Dallas's totality; ≥ 1 total, < 1 annular); `obscuration` is the covered fraction of the Sun's *area* (1 for total, (r☾/r☉)² at annular maximum).
  A hybrid eclipse is `TOTAL` at some sites and `ANNULAR` at others.
* `EventVisibility` for a solar eclipse: altitude/azimuth at `maximum`, `bestWindow = visibleInterval`, contacts C1, C2, MAXIMUM, C3, C4 with the Sun's altitude and azimuth.

## Accuracy and verification (sources in the test comments)

| Check | Reference | Result |
| --- | --- | --- |
| 4 lunar eclipses 2025-03-14 T, 2025-09-07 T, 2026-03-03 T, 2026-08-28 P: P1 U1 U2 greatest U3 U4 P4, magnitudes, gamma | NASA/Espenak contact plots (UT, ΔT 75 s, Danjon) | contacts ≤ 17 s (brief: ±2 min); magnitudes ≤ 0.0004 |
| 30 lunar eclipses 2028–2040 (all kinds) | NASA/Espenak catalog: kind, γ, magnitudes, durations | kinds all correct; γ ≤ 0.001; magnitudes ≤ 0.0025; greatest eclipse ≤ 15 s; durations ≤ 0.4 min |
| 12 solar eclipses 2023–2028 and 38 in 2029–2045 (T, A, H, P, non-central) | NASA/Espenak catalog: kind, γ, magnitude, greatest eclipse | kinds all correct; γ ≤ 0.0007; times ≤ 21 s; central magnitude ≤ 0.0016 (non-central ≤ 0.0042), partial ≤ 0.0055 (Earth as a sphere) |
| Totality at Dallas 2024-04-08, Burgos 2026-08-12 | USNO Solar Eclipse Computer API | C1–C4, maximum within 15 s (Dallas) / 5 s (Burgos); durations 239.8/232.5 s, 106.7/106.3 s; magnitude, Sun alt/az to 0.1° |
| Totality at Luxor 2027-08-02 | JPL Horizons topocentric Sun/Moon (the method reproduces USNO to 6 s) | within 5 s; 384.7 s vs 384.8 s |
| Partial/annular/none: New York, London, Madrid, Canary Is., Antarctica (sunset), Albuquerque (annular), Tokyo and Buenos Aires (not visible) | USNO API | times ≤ 15 s; magnitude ≤ 0.002; obscuration ≤ 0.002 |
| Mars oppositions 2025-01-16, 2027-02-19; Jupiter, Saturn, Uranus, Neptune | JPL Horizons (Δλ = 180°) | ≤ 100 s |
| Venus greatest elongations 2025-01-10 E, 2025-06-01 W, 2026-08-15 E; Mercury | JPL Horizons (S-O-T maximum) | ≤ 2 min; angles ≤ 0.001° |
| Venus–Jupiter 2025-08-12, 2026-06-09; Moon–Venus/Jupiter/Mars/Mercury; Venus–Saturn; Moon with Regulus, Spica, Antares, Pleiades | JPL Horizons (+ Hipparcos places for the stars) | time ≤ 1.5 min (planet pairs ≤ 10 min: they move slowly), separation ≤ 0.0003° |
| Equinoxes/solstices 2026 | USNO seasons API | ≤ 8 s (the tables are to the minute) |
| Perihelion/aphelion 2026 | USNO | 87 s / 15 min |
| Meteor maxima 2026 (Lyrids, Perseids, ε-Perseids, Draconids, Leonids, Geminids) | IMO calendar text | ≤ 25 min (the IMO text rounds some to the hour) |
| Supermoon 2026-01-03, other specials | JPL Horizons distances | ≤ 2 km |

Time scale: events are computed in TT and reported in UT with the kit's ΔT model (≈ 69 s in 2026, extrapolated afterwards). Accuracy
degrades slowly with distance from the present: for years beyond ~2035 add the ΔT uncertainty (several seconds) to the eclipse times.

## Cost (desktop JVM 21 and Swift 6.4 release, x86-64, one core; warm)

| Call | Cost |
| --- | --- |
| `MeteorShowerFinder` one year | 3 ms |
| `ConjunctionFinder` one year (default thresholds, ~105 events) | 55–75 ms (+ 30 ms with an observer: topocentric notes) |
| `OppositionFinder`, `GreatestElongationFinder` one year | 6 ms each |
| `SeasonFinder`, `EarthApsisFinder` | < 1 ms |
| `FullMoonSpecialFinder` / `LunarEclipseFinder` / `SolarEclipseFinder` one year | 5–8 ms each (+ 2 ms per observer for local circumstances) |
| `EventCalculator.events` one year, no observer | ~100 ms |
| `EventCalculator.events` one year, with observer (~150 events with visibility) | 165–215 ms |
| `EventCalculator.visibility(of:…)` for one event | 0.3–1 ms |
The first call of a process costs about 0.9 s on the JVM (class loading and JIT of the planetary series); the Swift debug build used by `swift test` needs ~1.6 s
for a year. Ten years cost ten times a year. Run it off the main thread, keep the result, and recompute at most once a day or when the place changes.
Nothing is cached between calls (only within one `events(…)` call: the new/full Moons and the nights).

## Notes for the UI

* **A calendar for the next 12 months.** Call `EventCalculator.events(now, now + 366 d, observer, EventOptions(timeZone = deviceZone, moonConjunctionThreshold = 3))` once
  (background thread), group the result by the local calendar month of `event.date`, and show `event.title`, the local date/time (`date` is an instant),
  and per kind a one-line subtitle from `details`: meteor shower — ZHR, `moonIlluminatedFraction` ("moon 4 %"), radiant altitude (`visibility.altitude`); conjunction —
  `separation`; opposition — magnitude and `distanceAU`; elongation — angle and EAST/WEST; special full Moon — categories and distance; lunar eclipse — kind,
  `umbralMagnitude`, contacts; solar eclipse — `local.kind`, `local.magnitude`/`obscuration`, `local.maximum`. Grey out or hide events with
  `visibility?.isObservable == false` (or pass `visibleOnly = true`); show `bestWindow` ("22:10–04:50") and `bestTime`; the `isDark` flag says whether the
  event instant itself is in astronomical darkness. Equinoxes, solstices and apsides have no visibility: show them without altitude. Use `EventKind` for filter chips;
  `EARTH_APSIS` is off by default. Localize from `details` (`title` is an English default); the enums carry `displayName` and `rawValue` for keys.
* **Aiming at an event.** `event.aimTargets` lists what to point at, main subject first, and `event.aimDirection(frame)` (a `SkyFrame` for the moment of use) returns the horizontal
  direction to frame — the single target, or the midpoint of the two bodies of a conjunction. Per target: `AimTarget.Body(body)` → `frame.horizontal(body)`
  (the Sun, the Moon or a planet; geocentric apparent with parallax); `AimTarget.Fixed(position, properMotion)` → `frame.horizontal(position, properMotion)` (Regulus,
  Spica, Antares, Aldebaran, the Pleiades: J2000); `AimTarget.Radiant(shower)` → the radiant for the frame's date (`MeteorShower.radiant(date)` is the
  J2000 `EquatorialCoordinate` to give to the kit's pointing/guidance code for a fixed star-like target, drifted by the date). For a conjunction the UI can also aim at each
  body separately via `conjunction.first.aimTarget` / `second.aimTarget`. Re-resolve every frame (the frame's date drives the Moon and the radiant drift). For a meteor shower
  the usual advice is to frame 40–60° away from the radiant rather than at it; the radiant is for the overlay and the pointing aid.
* **Eclipses.** Lunar: draw the contact timeline from `LunarEclipse.contacts` with `visibility.contacts` (altitude per contact; negative = below the horizon). Solar:
  show `local.kind` first ("Total eclipse here: 3m 52s at 12:42 CDT", "Partial, 91 % obscured, maximum 18:13", "No eclipse visible here"); `c1…c4` are geometric, so grey the
  contacts whose `visibility.contacts[…].altitude` is below 0; `SolarEclipse.isCentral` and `kind` describe the eclipse globally ("a total eclipse crosses Spain").
* **When to refresh.** On launch (cached copy is fine for a day), when the observer moves by more than ~100 km (the visibility and the local eclipse circumstances change;
  the events themselves do not, except the Moon's topocentric note), or when the user changes the time zone or the thresholds. `EventCalculator.visibility(of:…)` computes one event's visibility
  on demand (0.3–1 ms) if the list was computed without an observer.
* **Persistence.** Events are value types holding `Date`s; `EventKind`, `ReferenceStar` and the other enums are `Codable`, the payload structs are not (store the request, not the result).

## Data sources and licences (for ATTRIBUTION.md)

* IMO *Meteor Shower Calendar 2026* (© International Meteor Organization 2025): shower parameters (facts: names, activity dates, λ☉, radiant, V∞, r, ZHR, Table 6 positions)
  hand-entered with the citation in `MeteorShower`; the IMO site was down when this was written, so the source is the Wayback Machine copy (sha256 in the source comment).
  No licence text accompanies the calendar: keep the credit line "Meteor shower data: International Meteor Organization" and ask the IMO before redistributing the table itself.
* NASA/GSFC Eclipse Web Site (F. Espenak, J. Meeus; public domain with credit) and the U.S. Naval Observatory data services (US Government, public domain) and JPL Horizons (public):
  used only as test references (values quoted in the tests), nothing is shipped.
* Reference star places: Hipparcos (ESA 1997, new reduction) through the kit's own `stars.json`; the Pleiades centre from SIMBAD (M 45).

## Appendix: Table 6 points behind the radiant drift (IMO 2026, α° δ°; drift = least-squares slope per day)

QUA Dec 30 226 +50, Jan 0 228 +50, Jan 5 231 +49, Jan 10 234 +48 · ACE Jan 30 199 −56, Feb 5 206 −58, Feb 10 213 −59, Feb 15 219 −61, Feb 20 224 −62 ·
LYR Apr 15 263 +34, 20 269 +34, 25 274 +34, 30 279 +34 · ETA Apr 20 323 −7, 25 328 −5, 30 332 −3, May 5 337 −1, 10 341 +1, 15 345 +3, 20 349 +5, 25 353 +7 ·
ARI Jun 5 42 +24, Jun 10 47 +24 · SDA Jul 10 325 −19, 15 329 −19, 20 333 −18, 25 337 −17, 30 340 −16, Aug 5 345 −14, 10 349 −13, 15 352 −12, 20 356 −11 ·
CAP Jul 5 285 −16, 10 289 −15, 15 294 −14, 20 299 −12, 25 303 −11, 30 307 −10, Aug 5 313 −8, 10 318 −6 ·
PER Jul 15 6 +50, 20 11 +52, 25 22 +53, 30 29 +54, Aug 5 37 +56, 10 45 +57, 15 51 +58, 20 57 +58, 25 63 +58 · AUR Aug 25 85 +40, 30 90 +39, Sep 5 96 +39, 10 102 +39 ·
SPE Sep 5 43 +40, 10 48 +40, 15 53 +40, 20 59 +41 · ORI Oct 5 85 +14, 10 88 +15, 15 91 +15, 20 94 +16, 25 98 +16, 30 101 +16, Nov 5 105 +17 ·
STA Sep 20 18 +5, 25 21 +6, 30 25 +7, Oct 5 28 +8, 10 32 +9, 15 36 +11, 20 40 +12, 25 43 +13, 30 47 +14, Nov 5 52 +15, 10 56 +15, 15 60 +16, 20 64 +16 ·
NTA Oct 20 38 +18, 25 43 +19, 30 47 +20, Nov 5 52 +21, 10 56 +22, 15 61 +23, 20 65 +24, 25 70 +24, 30 74 +24 · LEO Nov 10 147 +24, 15 150 +23, 20 153 +21, 25 156 +20, 30 159 +19 ·
HYD Dec 5 122 +3, 10 126 +2, 15 130 +1, 20 134 0 · GEM Dec 5 103 +33, 10 108 +33, 15 113 +33, 20 118 +32 · URS Dec 20 217 +76, Dec 25 217 +74 · DRA Oct 10 262 +54 (one point: no drift).
Resulting drifts (°/day, α / δ): QUA 0.68/−0.19, ACE 1.21/−0.29, LYR 1.06/0, ETA 0.85/0.40, ARI 1.00/0, SDA 0.75/0.21, CAP 0.91/0.27, PER 1.42/0.20, AUR 1.05/−0.05,
SPE 1.06/0.06, DRA 0/0, ORI 0.65/0.08, STA 0.76/0.19, NTA 0.87/0.16, LEO 0.60/−0.26, HYD 0.80/−0.20, GEM 1.00/−0.06, URS 0/−0.40.

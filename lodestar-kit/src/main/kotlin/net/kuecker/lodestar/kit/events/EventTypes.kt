package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import java.time.ZoneId

private typealias ConjunctionPayload = Conjunction
private typealias OppositionPayload = Opposition
private typealias GreatestElongationPayload = GreatestElongation
private typealias EarthApsisPayload = EarthApsis
private typealias SeasonPayload = Season
private typealias FullMoonSpecialPayload = FullMoonSpecial
private typealias LunarEclipsePayload = LunarEclipse
private typealias SolarEclipsePayload = SolarEclipse

/**
 * The kinds of astronomical events the calculator finds.
 *
 * Serialized as its [rawValue] string, like the Swift `Codable` raw-value enum.
 *
 * @property rawValue The camel-case name used as the serialized form, e.g. `"lunarEclipse"`.
 */
@Serializable
enum class EventKind(val rawValue: String) {
    /** The peak of a meteor shower from the IMO working list. */
    @SerialName("meteorShower")
    METEOR_SHOWER("meteorShower"),

    /** A close approach of the Moon or a planet to another planet, a bright ecliptic star or the Pleiades. */
    @SerialName("conjunction")
    CONJUNCTION("conjunction"),

    /** A superior planet (Mars to Neptune) opposite the Sun. */
    @SerialName("opposition")
    OPPOSITION("opposition"),

    /** The greatest elongation of Mercury or Venus from the Sun. */
    @SerialName("greatestElongation")
    GREATEST_ELONGATION("greatestElongation"),

    /** The Earth's perihelion or aphelion. */
    @SerialName("earthApsis")
    EARTH_APSIS("earthApsis"),

    /** An equinox or a solstice. */
    @SerialName("season")
    SEASON("season"),

    /** A full Moon that is a supermoon, a micromoon or a blue moon. */
    @SerialName("fullMoonSpecial")
    FULL_MOON_SPECIAL("fullMoonSpecial"),

    /** A penumbral, partial or total lunar eclipse. */
    @SerialName("lunarEclipse")
    LUNAR_ECLIPSE("lunarEclipse"),

    /** A partial, annular, total or hybrid solar eclipse. */
    @SerialName("solarEclipse")
    SOLAR_ECLIPSE("solarEclipse"),
    ;

    /** The English name, e.g. "Lunar eclipse". */
    val displayName: String
        get() = when (this) {
            METEOR_SHOWER -> "Meteor shower"
            CONJUNCTION -> "Conjunction"
            OPPOSITION -> "Opposition"
            GREATEST_ELONGATION -> "Greatest elongation"
            EARTH_APSIS -> "Perihelion or aphelion"
            SEASON -> "Equinox or solstice"
            FULL_MOON_SPECIAL -> "Special full Moon"
            LUNAR_ECLIPSE -> "Lunar eclipse"
            SOLAR_ECLIPSE -> "Solar eclipse"
        }

    companion object {
        /** Every kind except [EARTH_APSIS], which is optional. */
        val defaultKinds: Set<EventKind> = entries.filter { it != EARTH_APSIS }.toSet()

        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): EventKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What to search for and how strictly. Built with [invoke], which replaces non-finite values with the
 * defaults and clamps the rest into range; like Swift's initializer, the constructor is not public.
 *
 * @property kinds The kinds of events to include; [EventKind.defaultKinds] (everything except the Earth's apsides).
 * @property planetConjunctionThreshold Largest minimum separation in degrees of a planet–planet pair that counts
 * as a conjunction, 0…30, default 3.
 * @property moonConjunctionThreshold Largest minimum geocentric separation in degrees of the Moon from a planet,
 * a bright star or the Pleiades, 0…30, default 5.
 * @property minimumSolarElongation Conjunctions closer than this to the Sun (degrees, 0…90, default 10) are dropped
 * because they cannot be seen.
 * @property timeZone The zone that defines calendar months (blue moons) and the local noon-to-noon nights used for
 * viewing windows; `null` means UTC without an observer and the nearest whole-hour zone of the observer's longitude
 * with one.
 * @property minimumAltitude The altitude in degrees (0…90, default 10) a target must reach for a viewing window.
 * @property visibleOnly Drops events that the observer cannot see (no viewing window); has no effect without an
 * observer or for events without a visibility (equinoxes, solstices, apsides).
 */
@ConsistentCopyVisibility
data class EventOptions private constructor(
    val kinds: Set<EventKind>,
    val planetConjunctionThreshold: Double,
    val moonConjunctionThreshold: Double,
    val minimumSolarElongation: Double,
    val timeZone: ZoneId?,
    val minimumAltitude: Double,
    val visibleOnly: Boolean,
) {
    companion object {
        /** Creates the options (Swift `init(kinds:planetConjunctionThreshold:…)`), normalizing out-of-range values. */
        operator fun invoke(
            kinds: Set<EventKind> = EventKind.defaultKinds,
            planetConjunctionThreshold: Double = 3.0,
            moonConjunctionThreshold: Double = 5.0,
            minimumSolarElongation: Double = 10.0,
            timeZone: ZoneId? = null,
            minimumAltitude: Double = 10.0,
            visibleOnly: Boolean = false,
        ): EventOptions = EventOptions(
            kinds = kinds,
            planetConjunctionThreshold = normalized(planetConjunctionThreshold, 3.0, 0.0, 30.0),
            moonConjunctionThreshold = normalized(moonConjunctionThreshold, 5.0, 0.0, 30.0),
            minimumSolarElongation = normalized(minimumSolarElongation, 10.0, 0.0, 90.0),
            timeZone = timeZone,
            minimumAltitude = normalized(minimumAltitude, 10.0, 0.0, 90.0),
            visibleOnly = visibleOnly,
        )

        private fun normalized(value: Double, fallback: Double, lower: Double, upper: Double): Double =
            if (value.isFinite()) AstroMath.clamp(value, lower, upper) else fallback
    }
}

/**
 * The points of an eclipse at which an observer's altitude is reported.
 *
 * @property rawValue The name used as the serialized form, e.g. `"P1"`.
 */
@Serializable
enum class EclipseContact(val rawValue: String) {
    /** Lunar: the Moon touches the penumbra. */
    @SerialName("P1")
    P1("P1"),

    /** Lunar: the Moon touches the umbra. */
    @SerialName("U1")
    U1("U1"),

    /** Lunar: the Moon is completely inside the umbra. */
    @SerialName("U2")
    U2("U2"),

    /** Lunar: the greatest eclipse. */
    @SerialName("greatest")
    GREATEST("greatest"),

    /** Lunar: the Moon starts to leave the umbra completely. */
    @SerialName("U3")
    U3("U3"),

    /** Lunar: the Moon leaves the umbra. */
    @SerialName("U4")
    U4("U4"),

    /** Lunar: the Moon leaves the penumbra. */
    @SerialName("P4")
    P4("P4"),

    /** Solar: the Moon first touches the Sun. */
    @SerialName("C1")
    C1("C1"),

    /** Solar: the central phase (totality or annularity) begins. */
    @SerialName("C2")
    C2("C2"),

    /** Solar: the maximum eclipse. */
    @SerialName("maximum")
    MAXIMUM("maximum"),

    /** Solar: the central phase ends. */
    @SerialName("C3")
    C3("C3"),

    /** Solar: the Moon last touches the Sun. */
    @SerialName("C4")
    C4("C4"),
    ;

    companion object {
        /** The contact whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): EclipseContact? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Where the eclipsed body stands for an observer at one contact.
 *
 * @property contact Which contact.
 * @property time The instant of the contact.
 * @property altitude Geometric topocentric altitude of the Moon (lunar) or the Sun (solar) in degrees; negative
 * below the horizon.
 * @property azimuth Azimuth in degrees from north through east, [0, 360).
 */
data class ContactVisibility(
    val contact: EclipseContact,
    val time: Instant,
    val altitude: Double,
    val azimuth: Double,
)

/**
 * How well an event can be seen from one place.
 *
 * The *subject* is the radiant of a meteor shower, the Sun or the Moon for an eclipse, the Moon for a special full
 * Moon, the planet for an opposition or an elongation, and for a conjunction the planet or star (for the Moon with a
 * planet or a star) or the midpoint of the two planets. Altitudes are geometric (no refraction).
 *
 * @property altitude Altitude of the subject at the event's instant, degrees (the Moon or Sun at greatest eclipse or
 * maximum for eclipses).
 * @property azimuth Azimuth of the subject at that instant, degrees from north through east.
 * @property sunAltitude The Sun's geometric altitude at that instant, degrees.
 * @property isDark `true` when the Sun is below −18° (astronomical darkness) at that instant.
 * @property bestWindow The viewing window of the night that contains the event (the local noon-to-noon interval,
 * see [EventOptions.timeZone]): the stretch around the subject's highest point in which it is above
 * [EventOptions.minimumAltitude] and the Sun is lower than [darknessLimit]. For eclipses it is the part of the
 * eclipse during which the Moon or Sun is above the horizon. `null` when there is none.
 * @property bestTime The instant of the subject's greatest altitude inside [bestWindow] (the maximum for a solar
 * eclipse, the greatest eclipse when visible for a lunar one).
 * @property darknessLimit The Sun altitude the window required: −18° for meteor showers, −6° for conjunctions,
 * oppositions and elongations, −0.8333° (sunset) for special full Moons, and 0° for eclipses (not applied).
 * @property contacts Altitude and azimuth at each contact of an eclipse; empty for other events.
 */
data class EventVisibility(
    val altitude: Double,
    val azimuth: Double,
    val sunAltitude: Double,
    val isDark: Boolean,
    val bestWindow: DateInterval?,
    val bestTime: Instant?,
    val darknessLimit: Double,
    val contacts: List<ContactVisibility>,
) {
    /** `true` when there is a viewing window. */
    val isObservable: Boolean get() = bestWindow != null
}

/**
 * Something a camera can be pointed at to photograph an event.
 *
 * Resolve it to the sky with [horizontal].
 */
sealed interface AimTarget {
    /** The Sun, the Moon or a planet. */
    data class Body(val body: SolarSystemBody) : AimTarget

    /** A fixed catalog place, J2000, with the proper motion of a star when it has one. */
    data class Fixed(val position: EquatorialCoordinate, val properMotion: ProperMotion?) : AimTarget

    /** The radiant of a meteor shower, drifting with the date of the frame. */
    data class Radiant(val shower: MeteorShower) : AimTarget

    /** Topocentric horizontal coordinates of the target in [frame], optionally including refraction. */
    fun horizontal(frame: SkyFrame, refraction: Boolean = true): HorizontalCoordinate =
        when (this) {
            is Body -> frame.horizontal(body, refraction)
            is Fixed -> frame.horizontal(position, properMotion, refraction)
            is Radiant -> frame.horizontal(shower.radiant(frame.date), null, refraction)
        }
}

/**
 * What kind of event it is and its numbers. Each case wraps one payload type; [kind] names it.
 */
sealed interface EventDetails {
    /** The kind of event these details describe. */
    val kind: EventKind

    /** A meteor shower peak (Swift `.meteorShower(_)`). */
    data class MeteorShower(val peak: MeteorShowerPeak) : EventDetails {
        override val kind: EventKind get() = EventKind.METEOR_SHOWER
    }

    /** A conjunction (Swift `.conjunction(_)`). */
    data class Conjunction(val conjunction: ConjunctionPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.CONJUNCTION
    }

    /** An opposition (Swift `.opposition(_)`). */
    data class Opposition(val opposition: OppositionPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.OPPOSITION
    }

    /** A greatest elongation (Swift `.greatestElongation(_)`). */
    data class GreatestElongation(val elongation: GreatestElongationPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.GREATEST_ELONGATION
    }

    /** The Earth's perihelion or aphelion (Swift `.earthApsis(_)`). */
    data class EarthApsis(val apsis: EarthApsisPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.EARTH_APSIS
    }

    /** An equinox or a solstice (Swift `.season(_)`). */
    data class Season(val season: SeasonPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.SEASON
    }

    /** A supermoon, micromoon or blue moon (Swift `.fullMoonSpecial(_)`). */
    data class FullMoonSpecial(val special: FullMoonSpecialPayload) : EventDetails {
        override val kind: EventKind get() = EventKind.FULL_MOON_SPECIAL
    }

    /** A lunar eclipse (Swift `.lunarEclipse(_)`). */
    data class LunarEclipse(val eclipse: LunarEclipsePayload) : EventDetails {
        override val kind: EventKind get() = EventKind.LUNAR_ECLIPSE
    }

    /** A solar eclipse (Swift `.solarEclipse(_)`). */
    data class SolarEclipse(val eclipse: SolarEclipsePayload) : EventDetails {
        override val kind: EventKind get() = EventKind.SOLAR_ECLIPSE
    }
}

/**
 * One astronomical event.
 *
 * @property date The event's instant: the peak of a meteor shower, the minimum separation of a conjunction, the
 * opposition, the greatest elongation, the apsis, the equinox or solstice, the full Moon, the greatest eclipse of a
 * lunar eclipse and the greatest eclipse (anywhere on Earth) of a solar eclipse.
 * @property details The kind and the numbers of the event.
 * @property visibility How well the observer can see it; `null` without an observer and for equinoxes, solstices and
 * apsides, which are not seen in the sky.
 */
data class AstronomicalEvent(
    val date: Instant,
    val details: EventDetails,
    val visibility: EventVisibility?,
) {
    /** The kind of event. */
    val kind: EventKind get() = details.kind

    /**
     * A short English title for lists, e.g. "Perseids peak", "Venus and Jupiter", "Total lunar eclipse" or
     * "June solstice". The UI localizes from [details] instead.
     */
    val title: String
        get() = when (val details = details) {
            is EventDetails.MeteorShower -> "${details.peak.shower.name} peak"
            is EventDetails.Conjunction ->
                "${details.conjunction.first.displayName} and ${details.conjunction.second.displayName}"
            is EventDetails.Opposition -> "${details.opposition.planet.displayName} at opposition"
            is EventDetails.GreatestElongation ->
                "${details.elongation.planet.displayName} at greatest ${details.elongation.direction.displayName.lowercase()} elongation"
            is EventDetails.EarthApsis -> "Earth at ${details.apsis.kind.displayName.lowercase()}"
            is EventDetails.Season -> details.season.kind.displayName
            is EventDetails.FullMoonSpecial -> details.special.categories.joinToString(" and ") { it.displayName }
            is EventDetails.LunarEclipse -> "${details.eclipse.kind.displayName} lunar eclipse"
            is EventDetails.SolarEclipse -> "${details.eclipse.kind.displayName} solar eclipse"
        }

    /**
     * What to point a camera at, main subject first: the radiant of a meteor shower, the two bodies of a conjunction
     * (the Moon first), the planet of an opposition or an elongation, the Moon for a lunar eclipse or a special
     * full Moon and the Sun for a solar eclipse. Empty for equinoxes, solstices and apsides.
     */
    val aimTargets: List<AimTarget>
        get() = when (val details = details) {
            is EventDetails.MeteorShower -> listOf(AimTarget.Radiant(details.peak.shower))
            is EventDetails.Conjunction ->
                listOf(details.conjunction.first.aimTarget, details.conjunction.second.aimTarget)
            is EventDetails.Opposition -> listOf(AimTarget.Body(details.opposition.planet))
            is EventDetails.GreatestElongation -> listOf(AimTarget.Body(details.elongation.planet))
            is EventDetails.EarthApsis -> emptyList()
            is EventDetails.Season -> emptyList()
            is EventDetails.FullMoonSpecial -> listOf(AimTarget.Body(SolarSystemBody.MOON))
            is EventDetails.LunarEclipse -> listOf(AimTarget.Body(SolarSystemBody.MOON))
            is EventDetails.SolarEclipse -> listOf(AimTarget.Body(SolarSystemBody.SUN))
        }

    /**
     * Where to aim in [frame] to frame the event: the position of the single target or the midpoint of the two
     * bodies of a conjunction; `null` when the event has no target.
     */
    fun aimDirection(frame: SkyFrame, refraction: Boolean = true): HorizontalCoordinate? {
        val targets = aimTargets
        if (targets.isEmpty()) return null
        if (targets.size == 1) return targets[0].horizontal(frame, refraction)
        var sum = Vector3.zero
        for (target in targets) {
            sum += target.horizontal(frame, refraction).unitVector
        }
        return HorizontalCoordinate(sum)
    }
}

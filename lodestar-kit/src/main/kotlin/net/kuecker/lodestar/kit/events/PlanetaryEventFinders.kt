package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor

/**
 * An opposition: a planet at 180° of ecliptic longitude from the Sun, the moment the planet is closest to the
 * Earth's night side of the sky and rises at sunset.
 *
 * @property planet Mars, Jupiter, Saturn, Uranus or Neptune.
 * @property distanceAU The planet's geocentric distance at the opposition in AU.
 * @property apparentMagnitude The planet's apparent magnitude at the opposition.
 * @property angularDiameterArcseconds The planet's apparent diameter in arcseconds.
 */
data class Opposition(
    val planet: SolarSystemBody,
    val distanceAU: Double,
    val apparentMagnitude: Double?,
    val angularDiameterArcseconds: Double,
)

/** Finds the oppositions of Mars, Jupiter, Saturn, Uranus and Neptune. Cost for one year: about 10 ms. */
object OppositionFinder {
    private val planets: List<SolarSystemBody> = listOf(
        SolarSystemBody.MARS, SolarSystemBody.JUPITER, SolarSystemBody.SATURN, SolarSystemBody.URANUS,
        SolarSystemBody.NEPTUNE,
    )
    private const val step: Double = 5.0
    private const val tolerance: Double = 1e-6

    /**
     * The oppositions from [from] to [to] (both inclusive), as events without visibility, in time order. The instant
     * is when the apparent ecliptic longitude (of date) of the planet exceeds the Sun's by 180°.
     */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        val startUT = fromUT - step
        val count = EventSearch.intervalCount(toUT + step - startUT, step)
        val sunLongitudes = DoubleArray(count + 1) { EventSearch.sunLongitudeOfDate(JulianDate(startUT + it * step)) }
        val found = ArrayList<AstronomicalEvent>()
        for (planet in planets) {
            fun offset(ut: Double): Double {
                val jd = JulianDate(ut)
                val planetLongitude = Ephemeris.state(planet, jd).eclipticLongitudeOfDate
                return AstroMath.normalizeSignedDegrees(planetLongitude - EventSearch.sunLongitudeOfDate(jd) - 180)
            }
            var previous = AstroMath.normalizeSignedDegrees(
                Ephemeris.state(planet, JulianDate(startUT)).eclipticLongitudeOfDate - sunLongitudes[0] - 180,
            )
            for (index in 1..count) {
                val ut = startUT + index * step
                val current = AstroMath.normalizeSignedDegrees(
                    Ephemeris.state(planet, JulianDate(ut)).eclipticLongitudeOfDate - sunLongitudes[index] - 180,
                )
                if (previous > 0 && current <= 0 && previous - current < 90) {
                    val root = EventSearch.root({ offset(it) }, ut - step, ut, tolerance)
                    if (root != null && root >= fromUT && root <= toUT) {
                        val jd = JulianDate(root)
                        val state = Ephemeris.state(planet, jd)
                        val opposition = Opposition(
                            planet, state.distanceAU, state.apparentMagnitude, state.angularDiameterArcseconds,
                        )
                        found.add(AstronomicalEvent(jd.date, EventDetails.Opposition(opposition), null))
                    }
                }
                previous = current
            }
        }
        return found.sortedBy { it.date }
    }
}

/**
 * Which side of the Sun a planet stands at its greatest elongation.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class ElongationDirection(val rawValue: String) {
    /** East of the Sun: an evening object. */
    @SerialName("east")
    EAST("east"),

    /** West of the Sun: a morning object. */
    @SerialName("west")
    WEST("west"),
    ;

    /** The English adjective, "Eastern" or "Western". */
    val displayName: String get() = if (this == EAST) "Eastern" else "Western"

    companion object {
        /** The direction whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): ElongationDirection? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The greatest elongation of Mercury or Venus: the largest angular distance from the Sun of an apparition.
 *
 * @property planet Mercury or Venus.
 * @property direction East (evening sky) or west (morning sky) of the Sun.
 * @property angle The elongation in degrees.
 * @property apparentMagnitude The planet's apparent magnitude then.
 */
data class GreatestElongation(
    val planet: SolarSystemBody,
    val direction: ElongationDirection,
    val angle: Double,
    val apparentMagnitude: Double?,
)

/**
 * Finds the greatest elongations of Mercury and Venus.
 *
 * The maximum of the angular distance from the Sun is flat, so the instant is the zero of its derivative (a central
 * difference over one day), which is steadier than comparing nearly equal distances. Cost for one year: about 5 ms.
 */
object GreatestElongationFinder {
    private val planets: List<SolarSystemBody> = listOf(SolarSystemBody.MERCURY, SolarSystemBody.VENUS)
    private const val step: Double = 2.0
    private const val coarseTolerance: Double = 1e-3
    private const val tolerance: Double = 1e-6
    private const val halfDifference: Double = 0.5

    /** The greatest elongations from [from] to [to] (both inclusive), as events without visibility, in time order. */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        val startUT = fromUT - 2 * step
        val count = EventSearch.intervalCount(toUT + 2 * step - startUT, step)
        val sunVectors = Array(count + 1) { Ephemeris.geocentricJ2000(SolarSystemBody.SUN, JulianDate(startUT + it * step)) }
        val found = ArrayList<AstronomicalEvent>()
        for (planet in planets) {
            fun elongation(ut: Double): Double {
                val jd = JulianDate(ut)
                return Ephemeris.geocentricJ2000(planet, jd).angle(Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd))
            }
            val samples = DoubleArray(count + 1) {
                Ephemeris.geocentricJ2000(planet, JulianDate(startUT + it * step)).angle(sunVectors[it])
            }
            for (index in EventSearch.localMaxima(samples)) {
                val coarse = EventSearch.maximum(
                    { elongation(it) }, startUT + (index - 1) * step, startUT + (index + 1) * step, coarseTolerance,
                ).first
                val refined = EventSearch.root(
                    { elongation(it + halfDifference) - elongation(it - halfDifference) },
                    coarse - 2 * halfDifference, coarse + 2 * halfDifference, tolerance,
                ) ?: coarse
                if (refined < fromUT || refined > toUT) continue
                val jd = JulianDate(refined)
                val state = Ephemeris.state(planet, jd)
                val sunLongitude = EventSearch.sunLongitudeOfDate(jd)
                val east = AstroMath.normalizeSignedDegrees(state.eclipticLongitudeOfDate - sunLongitude) > 0
                val result = GreatestElongation(
                    planet,
                    if (east) ElongationDirection.EAST else ElongationDirection.WEST,
                    state.elongation ?: elongation(refined),
                    state.apparentMagnitude,
                )
                found.add(AstronomicalEvent(jd.date, EventDetails.GreatestElongation(result), null))
            }
        }
        return found.sortedBy { it.date }
    }
}

/**
 * Whether the Earth is at its closest to or farthest from the Sun.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class EarthApsisKind(val rawValue: String) {
    /** Closest to the Sun, in early January. */
    @SerialName("perihelion")
    PERIHELION("perihelion"),

    /** Farthest from the Sun, in early July. */
    @SerialName("aphelion")
    APHELION("aphelion"),
    ;

    /** The English name, "Perihelion" or "Aphelion". */
    val displayName: String get() = if (this == PERIHELION) "Perihelion" else "Aphelion"

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): EarthApsisKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The Earth's perihelion or aphelion.
 *
 * @property kind Perihelion or aphelion.
 * @property distanceAU The Earth–Sun distance in AU at the apsis.
 */
data class EarthApsis(
    val kind: EarthApsisKind,
    val distanceAU: Double,
)

/**
 * Finds the Earth's perihelion and aphelion as the extremes of the Earth–Sun distance (the Earth–Moon barycentre of the
 * VSOP87 series, as in the published tables; it differs from the Earth's centre by at most 0.00003 AU).
 */
object EarthApsisFinder {
    private const val halfWindow: Double = 7.0
    private const val halfDifference: Double = 0.5
    private const val tolerance: Double = 1e-6

    /** The apsides from [from] to [to] (both inclusive), as events without visibility, in time order. */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        fun distance(ut: Double): Double = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, JulianDate(ut)).length
        val firstYear = floor(JulianDate.decimalYear(fromUT)).toInt() - 1
        val lastYear = floor(JulianDate.decimalYear(toUT)).toInt() + 1
        val found = ArrayList<AstronomicalEvent>()
        for (year in firstYear..lastYear) {
            for (kind in EarthApsisKind.entries) {
                val centre = if (kind == EarthApsisKind.PERIHELION) {
                    JulianDate.julianDay(year, 1, 3)
                } else {
                    JulianDate.julianDay(year, 7, 4)
                }
                val root = EventSearch.root(
                    { distance(it + halfDifference) - distance(it - halfDifference) },
                    centre - halfWindow, centre + halfWindow, tolerance,
                ) ?: continue
                if (root < fromUT || root > toUT) continue
                found.add(
                    AstronomicalEvent(
                        EventSearch.instant(root), EventDetails.EarthApsis(EarthApsis(kind, distance(root))), null,
                    ),
                )
            }
        }
        return found.sortedBy { it.date }
    }
}

/**
 * Which equinox or solstice.
 *
 * Named after the month, so it is the same in both hemispheres; the UI says "autumnal" or "vernal".
 *
 * @property rawValue The name used as the serialized form.
 * @property solarLongitude The apparent ecliptic longitude of the Sun in degrees at the event.
 */
@Serializable
enum class SeasonKind(val rawValue: String, val solarLongitude: Double) {
    /** The Sun crosses the celestial equator northwards (longitude 0°). */
    @SerialName("marchEquinox")
    MARCH_EQUINOX("marchEquinox", 0.0),

    /** The Sun is at its northernmost (longitude 90°). */
    @SerialName("juneSolstice")
    JUNE_SOLSTICE("juneSolstice", 90.0),

    /** The Sun crosses the celestial equator southwards (longitude 180°). */
    @SerialName("septemberEquinox")
    SEPTEMBER_EQUINOX("septemberEquinox", 180.0),

    /** The Sun is at its southernmost (longitude 270°). */
    @SerialName("decemberSolstice")
    DECEMBER_SOLSTICE("decemberSolstice", 270.0),
    ;

    /** The English name, e.g. "June solstice". */
    val displayName: String
        get() = when (this) {
            MARCH_EQUINOX -> "March equinox"
            JUNE_SOLSTICE -> "June solstice"
            SEPTEMBER_EQUINOX -> "September equinox"
            DECEMBER_SOLSTICE -> "December solstice"
        }

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SeasonKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * An equinox or a solstice.
 *
 * @property kind Which of the four.
 */
data class Season(
    val kind: SeasonKind,
)

/**
 * Finds the equinoxes and solstices: the instants at which the Sun's apparent ecliptic longitude of date reaches 0°,
 * 90°, 180° and 270°, as in the tables of the U.S. Naval Observatory. Cost: about 100 µs each.
 */
object SeasonFinder {
    /** The equinoxes and solstices from [from] to [to] (both inclusive), as events without visibility, in time order. */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        val startLongitude = EventSearch.sunLongitudeOfDate(JulianDate(fromUT))
        var target = ceil(startLongitude / 90) * 90
        var guess = fromUT + (target - startLongitude) / EventSearch.meanSolarRate
        val found = ArrayList<AstronomicalEvent>()
        while (true) {
            val longitude = target % 360
            val ut = EventSearch.solveSolarLongitude(longitude, guess) { EventSearch.sunLongitudeOfDate(it) }
            if (!(ut <= toUT)) break
            if (ut >= fromUT) {
                val kind = SeasonKind.entries.first { it.solarLongitude == longitude }
                found.add(AstronomicalEvent(EventSearch.instant(ut), EventDetails.Season(Season(kind)), null))
            }
            target += 90
            guess = ut + 91.3
        }
        return found
    }
}

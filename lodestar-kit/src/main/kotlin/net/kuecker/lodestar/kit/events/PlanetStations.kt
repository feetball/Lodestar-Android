package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant

/**
 * Which way a planet's apparent motion among the stars turns at a station.
 *
 * @property rawValue The camel-case name used as the serialized form.
 */
@Serializable
enum class PlanetStationKind(val rawValue: String) {
    /** The planet stops moving east among the stars and starts to move west. */
    @SerialName("retrogradeBegins")
    RETROGRADE_BEGINS("retrogradeBegins"),

    /** The planet stops moving west and resumes its eastward, direct motion. */
    @SerialName("directMotionResumes")
    DIRECT_MOTION_RESUMES("directMotionResumes"),
    ;

    /** The English phrase, "retrograde motion begins" or "direct motion resumes". */
    val displayName: String
        get() = if (this == RETROGRADE_BEGINS) "retrograde motion begins" else "direct motion resumes"

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): PlanetStationKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A planet standing still among the stars: its apparent geocentric ecliptic longitude stops increasing or decreasing.
 *
 * @property planet Mercury, Venus, Mars, Jupiter, Saturn, Uranus or Neptune.
 * @property kind Whether retrograde motion begins or direct motion resumes.
 * @property longitude The planet's apparent ecliptic longitude of date at the station, degrees, [0, 360).
 */
data class PlanetStation(
    val planet: SolarSystemBody,
    val kind: PlanetStationKind,
    val longitude: Double,
)

/**
 * Finds the stations of Mercury to Neptune: the instants at which the rate of the apparent geocentric ecliptic
 * longitude of date changes sign.
 *
 * The rate is the central difference of the longitude over half a day, sampled every two days and refined by Brent's
 * method. A longitude curve is flat at a station, so the instant is sensitive to the ephemeris, yet the 11 stations of
 * Mercury, Venus, Mars, Jupiter, Saturn and Neptune compared with JPL's DE440s (see the tests) agree to 2 minutes.
 * Cost for one year of all seven planets: about 30 ms.
 */
object PlanetStations {
    private val planets: List<SolarSystemBody> = listOf(
        SolarSystemBody.MERCURY, SolarSystemBody.VENUS, SolarSystemBody.MARS, SolarSystemBody.JUPITER,
        SolarSystemBody.SATURN, SolarSystemBody.URANUS, SolarSystemBody.NEPTUNE,
    )
    private const val step = 2.0
    private const val halfDifference = 0.25
    private const val tolerance = 1e-6

    /**
     * The stations of [planet] from [from] to [to] (both inclusive), as events without visibility, in time order. Gives
     * nothing for the Sun, the Moon and bodies that are not among Mercury to Neptune, for a reversed interval and
     * outside the years 1 to 4001; intervals longer than ten years are cut short.
     */
    fun find(planet: SolarSystemBody, from: Instant, to: Instant): List<AstronomicalEvent> {
        if (planet !in planets || !EventSearch.isUsable(from, to)) return emptyList()
        val fromUT = EventSearch.ut(from)
        val toUT = EventSearch.ut(EventSearch.limitedEnd(from, to))
        fun longitude(ut: Double): Double = Ephemeris.state(planet, JulianDate(ut)).eclipticLongitudeOfDate
        fun rate(ut: Double): Double =
            AstroMath.normalizeSignedDegrees(longitude(ut + halfDifference) - longitude(ut - halfDifference))
        val startUT = fromUT - step
        val count = EventSearch.intervalCount(toUT + step - startUT, step)
        val found = ArrayList<AstronomicalEvent>()
        var previous = rate(startUT)
        for (index in 1..count) {
            val ut = startUT + index * step
            val current = rate(ut)
            val begins = previous > 0 && current <= 0
            val resumes = previous < 0 && current >= 0
            if (begins || resumes) {
                val root = EventSearch.root({ rate(it) }, ut - step, ut, tolerance)
                if (root != null && root >= fromUT && root <= toUT) {
                    val jd = JulianDate(root)
                    val station = PlanetStation(
                        planet,
                        if (begins) PlanetStationKind.RETROGRADE_BEGINS else PlanetStationKind.DIRECT_MOTION_RESUMES,
                        longitude(root),
                    )
                    found.add(AstronomicalEvent(jd.date, EventDetails.PlanetStation(station), null))
                }
            }
            previous = current
        }
        return found
    }

    /** The stations of all seven planets from [from] to [to], in time order (the events calendar's search). */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> =
        planets.flatMap { find(it, from, to) }.sortedBy { it.date }
}

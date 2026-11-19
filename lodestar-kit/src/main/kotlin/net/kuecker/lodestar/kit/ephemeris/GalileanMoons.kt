package net.kuecker.lodestar.kit.ephemeris

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One of Jupiter's four Galilean moons.
 *
 * @property rawValue The lowercase English name used as the serialized form, e.g. `"io"`.
 */
@Serializable
enum class GalileanMoon(val rawValue: String) {
    /** Io (I), 1.77 days around Jupiter, 5.9 Jupiter radii out. */
    @SerialName("io")
    IO("io"),

    /** Europa (II), 3.55 days, 9.4 Jupiter radii. */
    @SerialName("europa")
    EUROPA("europa"),

    /** Ganymede (III), 7.15 days, 15.0 Jupiter radii. */
    @SerialName("ganymede")
    GANYMEDE("ganymede"),

    /** Callisto (IV), 16.69 days, 26.4 Jupiter radii. */
    @SerialName("callisto")
    CALLISTO("callisto"),
    ;

    /** The English name, e.g. "Io". */
    val displayName: String
        get() = when (this) {
            IO -> "Io"
            EUROPA -> "Europa"
            GANYMEDE -> "Ganymede"
            CALLISTO -> "Callisto"
        }

    /** The one-letter initial used in diagrams: "I", "E", "G" or "C". */
    val initial: String
        get() = when (this) {
            IO -> "I"
            EUROPA -> "E"
            GANYMEDE -> "G"
            CALLISTO -> "C"
        }

    companion object {
        /** The moon whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): GalileanMoon? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What a Galilean moon does relative to Jupiter's disc.
 *
 * @property rawValue The camel-case name used as the serialized form.
 */
@Serializable
enum class GalileanPhenomenon(val rawValue: String) {
    /** The moon crosses in front of Jupiter's disc. */
    @SerialName("transit")
    TRANSIT("transit"),

    /** The moon's shadow falls on the visible disc of Jupiter. */
    @SerialName("shadowTransit")
    SHADOW_TRANSIT("shadowTransit"),

    /** The moon passes behind Jupiter's disc. */
    @SerialName("occultation")
    OCCULTATION("occultation"),

    /** The moon is inside Jupiter's shadow. */
    @SerialName("eclipse")
    ECLIPSE("eclipse"),
    ;

    companion object {
        /** The phenomenon whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): GalileanPhenomenon? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Which end of a phenomenon an event marks.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class GalileanEdge(val rawValue: String) {
    /** The phenomenon begins. */
    @SerialName("start")
    START("start"),

    /** The phenomenon ends. */
    @SerialName("end")
    END("end"),
    ;

    companion object {
        /** The edge whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): GalileanEdge? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Where a moon's shadow falls on the visible disc, in the axes of [GalileanMoonPosition].
 *
 * @property x Offset from the disc centre along Jupiter's equator in Jupiter equatorial radii, positive west.
 * @property y Offset along Jupiter's rotation axis, positive north.
 */
data class GalileanShadow(val x: Double, val y: Double)

/**
 * A Galilean moon as seen from the Earth.
 *
 * The axes follow Jupiter's rotation, as in a drawing of the planet with its equator horizontal: [x] runs along the
 * equator, positive to the west (the side of the sky where the Sun sets; for an inverting telescope it is on the left
 * of a northern observer's view), [y] along the rotation axis, positive to the north, [z] towards the Earth. Jupiter's
 * disc is taken round with a radius of 1 (the 6 % flattening is ignored).
 *
 * @property moon Which moon.
 * @property x Offset from Jupiter's centre in Jupiter equatorial radii, positive west.
 * @property y Offset along the rotation axis in Jupiter radii, positive north.
 * @property z Offset towards the Earth in Jupiter radii: positive when the moon is nearer to us than Jupiter.
 * @property shadow Where the centre of the moon's shadow falls on the part of the disc that faces the Earth, or `null`
 * when the shadow misses it or lands on the far side.
 * @property isEclipsed `true` when the moon is inside Jupiter's shadow (a cylinder one radius wide).
 */
data class GalileanMoonPosition(
    val moon: GalileanMoon,
    val x: Double,
    val y: Double,
    val z: Double,
    val shadow: GalileanShadow?,
    val isEclipsed: Boolean,
) {
    /** `true` when the moon is in front of Jupiter's disc. */
    val isInTransit: Boolean get() = z > 0 && x * x + y * y < 1

    /** `true` when the moon is hidden behind Jupiter's disc. */
    val isBehindDisc: Boolean get() = z < 0 && x * x + y * y < 1
}

/**
 * The four moons at one instant.
 *
 * @property earthDeclination Planetocentric declination of the Earth in degrees (the book's low-accuracy value).
 * @property sunDeclination Planetocentric declination of the Sun in degrees.
 * @property moons Io, Europa, Ganymede and Callisto, in that order.
 */
data class GalileanMoonsState(
    val earthDeclination: Double,
    val sunDeclination: Double,
    val moons: List<GalileanMoonPosition>,
)

/**
 * A start or an end of a moon phenomenon.
 *
 * @property moon Which moon.
 * @property phenomenon What it does.
 * @property edge Whether the phenomenon begins or ends.
 * @property date The instant, with the accuracy stated at [GalileanMoons.accuracyMinutes].
 */
data class GalileanMoonEvent(
    val moon: GalileanMoon,
    val phenomenon: GalileanPhenomenon,
    val edge: GalileanEdge,
    val date: Instant,
) {
    /** A short English sentence, e.g. "Io's shadow enters the disc" or "Europa goes behind Jupiter". */
    val title: String
        get() {
            val name = moon.displayName
            val starts = edge == GalileanEdge.START
            return when (phenomenon) {
                GalileanPhenomenon.TRANSIT -> if (starts) "$name enters the disc" else "$name leaves the disc"
                GalileanPhenomenon.SHADOW_TRANSIT ->
                    if (starts) "$name's shadow enters the disc" else "$name's shadow leaves the disc"
                GalileanPhenomenon.OCCULTATION ->
                    if (starts) "$name goes behind Jupiter" else "$name reappears from behind Jupiter"
                GalileanPhenomenon.ECLIPSE ->
                    if (starts) "$name enters Jupiter's shadow" else "$name leaves Jupiter's shadow"
            }
        }
}

/**
 * The Galilean moons of Jupiter by the low-accuracy method of Jean Meeus, *Astronomical Algorithms*, 2nd ed.,
 * chapter 44: each moon moves on a circle in Jupiter's equatorial plane with periodic corrections, seen from the Earth
 * through the planet's declination. The same circles seen from the Sun give the shadows.
 *
 * The positions are good to about 0.1 Jupiter radius, which is the [accuracyMinutes] of the event times: against JPL's
 * satellite ephemeris (Horizons, jup365) the 139 phenomena of three six-day windows between 2026 November and 2027
 * February came within 1.8 minutes rms and 6 minutes at the worst (Callisto), see the tests.
 */
object GalileanMoons {
    /** About how far the event times can be off, in minutes: the moons' offsets are good to about 0.1 Jupiter radius. */
    const val accuracyMinutes: Double = 5.0

    /** The longest interval, in days, that [events] searches; longer intervals are cut at this length. */
    const val maximumSpanDays: Double = 30.0

    /** The sampling step of [events] in seconds. */
    const val sampleSeconds: Double = 60.0

    /** The positions of the four moons at [julianDate] (its TT drives the theory). */
    fun positions(julianDate: JulianDate): GalileanMoonsState = positions(tt = julianDate.tt)

    private fun positions(tt: Double): GalileanMoonsState {
        val d = tt - JulianDate.j2000
        val v = AstroMath.radians(172.74 + 0.00111588 * d)
        val m = AstroMath.radians(357.529 + 0.9856003 * d)
        val sinV = sin(v)
        val n = AstroMath.radians(20.02 + 0.0830853 * d + 0.329 * sinV)
        val j = AstroMath.radians(66.115 + 0.9025179 * d - 0.329 * sinV)
        val a = AstroMath.radians(1.915) * sin(m) + AstroMath.radians(0.02) * sin(2 * m)
        val b = AstroMath.radians(5.555) * sin(n) + AstroMath.radians(0.168) * sin(2 * n)
        val k = j + a - b
        val earthSun = 1.00014 - 0.01671 * cos(m) - 0.00014 * cos(2 * m)
        val sunJupiter = 5.20872 - 0.25208 * cos(n) - 0.00611 * cos(2 * n)
        val delta = sqrt(sunJupiter * sunJupiter + earthSun * earthSun - 2 * sunJupiter * earthSun * cos(k))
        val psi = asin(AstroMath.clamp(earthSun / delta * sin(k), -1.0, 1.0))
        val lambda = AstroMath.radians(34.35 + 0.083091 * d + 0.329 * sinV) + b
        val sunDeclination = AstroMath.radians(3.12) * sin(lambda + AstroMath.radians(42.8))
        val earthDeclination = sunDeclination -
            AstroMath.radians(2.22) * sin(psi) * cos(lambda + AstroMath.radians(22.0)) -
            AstroMath.radians(1.3) * (sunJupiter - delta) / delta * sin(lambda - AstroMath.radians(100.5))
        val dd = d - delta / 173
        val u1 = AstroMath.radians(163.8069 + 203.4058646 * dd) + psi - b
        val u2 = AstroMath.radians(358.414 + 101.2916335 * dd) + psi - b
        val u3 = AstroMath.radians(5.7176 + 50.234518 * dd) + psi - b
        val u4 = AstroMath.radians(224.8092 + 21.48798 * dd) + psi - b
        val g = AstroMath.radians(331.18 + 50.310482 * dd)
        val h = AstroMath.radians(87.45 + 21.569231 * dd)
        val c1 = AstroMath.radians(0.473) * sin(2 * (u1 - u2))
        val c2 = AstroMath.radians(1.065) * sin(2 * (u2 - u3))
        val c3 = AstroMath.radians(0.165) * sin(g)
        val c4 = AstroMath.radians(0.843) * sin(h)
        val r1 = 5.9057 - 0.0244 * cos(2 * (u1 - u2))
        val r2 = 9.3966 - 0.0882 * cos(2 * (u2 - u3))
        val r3 = 14.9883 - 0.0216 * cos(g)
        val r4 = 26.3627 - 0.1939 * cos(h)
        val geometry = Geometry(earthDeclination, sunDeclination, psi)
        return GalileanMoonsState(
            earthDeclination = AstroMath.degrees(earthDeclination),
            sunDeclination = AstroMath.degrees(sunDeclination),
            moons = listOf(
                geometry.position(GalileanMoon.IO, u1 + c1, r1),
                geometry.position(GalileanMoon.EUROPA, u2 + c2, r2),
                geometry.position(GalileanMoon.GANYMEDE, u3 + c3, r3),
                geometry.position(GalileanMoon.CALLISTO, u4 + c4, r4),
            ),
        )
    }

    /**
     * The geometry shared by the four moons: the declinations of the Earth and the Sun and the angle at Jupiter between
     * them. A moon at orbital longitude `u` (from the Earth side) and distance `r` stands at
     * `r (sin u, 0, cos u)` in axes (west, north, towards the Earth's side of the equatorial plane).
     */
    private class Geometry(val earthDeclination: Double, val sunDeclination: Double, val psi: Double) {
        private val sinEarth = sin(earthDeclination)
        private val cosEarth = cos(earthDeclination)
        private val cosSun = cos(sunDeclination)
        private val sunWest = cosSun * sin(psi)
        private val sunNorth = sin(sunDeclination)
        private val sunToward = cosSun * cos(psi)

        fun position(moon: GalileanMoon, u: Double, r: Double): GalileanMoonPosition {
            val west = r * sin(u)
            val toward = r * cos(u)
            val x = west
            val y = -toward * sinEarth
            val z = toward * cosEarth
            // Along the sunlight: positive when the moon is on the Sun's side of Jupiter.
            val alongSun = west * sunWest + toward * sunToward
            val offAxis = r * r - alongSun * alongSun
            var shadow: GalileanShadow? = null
            var isEclipsed = false
            if (offAxis < 1) {
                if (alongSun > 0) {
                    // The shadow runs away from the Sun and meets the sphere after `reach`.
                    val reach = alongSun - sqrt(1 - offAxis)
                    val hitWest = west - reach * sunWest
                    val hitNorth = -reach * sunNorth
                    val hitToward = toward - reach * sunToward
                    val hitFront = hitNorth * sinEarth + hitToward * cosEarth
                    if (hitFront > 0) shadow = GalileanShadow(hitWest, hitNorth * cosEarth - hitToward * sinEarth)
                } else {
                    isEclipsed = true
                }
            }
            return GalileanMoonPosition(moon, x, y, z, shadow, isEclipsed)
        }
    }

    /**
     * The starts and ends of the transits, shadow transits, occultations and eclipses of the four moons from [from] to
     * [to], in time order. Intervals longer than [maximumSpanDays] are cut short; an interval that is reversed or outside
     * the years 1 to 4001 gives nothing. A phenomenon already under way at [from] has no start event.
     *
     * The moons are sampled every [sampleSeconds] and each change is refined by bisection to under a second. The times
     * are only as good as the positions: about [accuracyMinutes] minutes. Cost: about 1 ms for a day.
     */
    fun events(from: Instant, to: Instant): List<GalileanMoonEvent> {
        if (!(from >= distantPast && to <= distantFuture && to >= from)) return emptyList()
        val startSeconds = from.timeIntervalSince1970
        val endSeconds = minOf(to.timeIntervalSince1970, startSeconds + maximumSpanDays * JulianDate.secondsPerDay)
        val deltaDays = JulianDate(from).deltaT / JulianDate.secondsPerDay
        fun flags(seconds: Double): Int {
            val state = positions(tt = seconds / JulianDate.secondsPerDay + JulianDate.unixEpoch + deltaDays)
            var mask = 0
            for ((index, moon) in state.moons.withIndex()) {
                if (moon.isInTransit) mask = mask or (1 shl (index * 4))
                if (moon.shadow != null) mask = mask or (1 shl (index * 4 + 1))
                if (moon.isBehindDisc) mask = mask or (1 shl (index * 4 + 2))
                if (moon.isEclipsed) mask = mask or (1 shl (index * 4 + 3))
            }
            return mask
        }
        val count = maxOf(1, ceil((endSeconds - startSeconds) / sampleSeconds).toInt())
        val found = ArrayList<GalileanMoonEvent>()
        var previousSeconds = startSeconds
        var previous = flags(startSeconds)
        for (index in 1..count) {
            val seconds = minOf(startSeconds + index * sampleSeconds, endSeconds)
            val current = flags(seconds)
            val changed = previous xor current
            if (changed != 0) {
                for (bit in 0 until 16) {
                    if ((changed and (1 shl bit)) == 0) continue
                    var low = previousSeconds
                    var high = seconds
                    val before = previous and (1 shl bit)
                    while (high - low > 0.5) {
                        val middle = (low + high) / 2
                        if ((flags(middle) and (1 shl bit)) == before) low = middle else high = middle
                    }
                    found.add(
                        GalileanMoonEvent(
                            moon = GalileanMoon.entries[bit / 4],
                            phenomenon = GalileanPhenomenon.entries[bit % 4],
                            edge = if (before == 0) GalileanEdge.START else GalileanEdge.END,
                            date = instantFromTimeIntervalSince1970((low + high) / 2),
                        ),
                    )
                }
            }
            previousSeconds = seconds
            previous = current
        }
        return found.sortedBy { it.date }
    }
}

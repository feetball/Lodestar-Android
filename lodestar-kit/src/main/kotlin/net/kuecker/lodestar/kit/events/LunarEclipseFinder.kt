package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The kind of a lunar eclipse.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class LunarEclipseKind(val rawValue: String) {
    /** The Moon only passes through the penumbra. */
    @SerialName("penumbral")
    PENUMBRAL("penumbral"),

    /** Part of the Moon enters the umbra. */
    @SerialName("partial")
    PARTIAL("partial"),

    /** The whole Moon enters the umbra. */
    @SerialName("total")
    TOTAL("total"),
    ;

    /** The English name, e.g. "Total". */
    val displayName: String
        get() = when (this) {
            PENUMBRAL -> "Penumbral"
            PARTIAL -> "Partial"
            TOTAL -> "Total"
        }

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): LunarEclipseKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The instant of one contact of an eclipse.
 *
 * @property contact Which contact.
 * @property time The instant.
 */
data class EclipseContactTime(
    val contact: EclipseContact,
    val time: Instant,
)

/**
 * A lunar eclipse as seen from the Earth's centre; the event's date is [greatest].
 *
 * The shadow follows Danjon's rule as used in the NASA/Espenak canon: the Earth's radius is enlarged by 1 % to account
 * for the atmosphere (the umbra grows by 1.4 %). The magnitudes are those of the canon: the fraction of the Moon's
 * diameter inside the umbra (negative before the Moon reaches it) and the penumbra.
 *
 * @property kind Penumbral, partial or total.
 * @property gamma Least distance of the Moon's centre from the shadow axis, in units of the Moon's equatorial
 * horizontal parallax, positive when the Moon passes north of the axis.
 * @property penumbralMagnitude The penumbral magnitude at the greatest eclipse.
 * @property umbralMagnitude The umbral magnitude at the greatest eclipse; negative for a penumbral eclipse.
 * @property p1 The Moon enters the penumbra.
 * @property u1 The Moon enters the umbra; `null` for a penumbral eclipse.
 * @property u2 The Moon is completely in the umbra; `null` unless total.
 * @property greatest The Moon's centre is nearest the axis of the shadow.
 * @property u3 The Moon starts to leave the umbra completely; `null` unless total.
 * @property u4 The Moon leaves the umbra; `null` for a penumbral eclipse.
 * @property p4 The Moon leaves the penumbra.
 */
data class LunarEclipse(
    val kind: LunarEclipseKind,
    val gamma: Double,
    val penumbralMagnitude: Double,
    val umbralMagnitude: Double,
    val p1: Instant,
    val u1: Instant?,
    val u2: Instant?,
    val greatest: Instant,
    val u3: Instant?,
    val u4: Instant?,
    val p4: Instant,
) {
    /** The contacts that exist, in time order. */
    val contacts: List<EclipseContactTime>
        get() {
            val all = ArrayList<EclipseContactTime>()
            all.add(EclipseContactTime(EclipseContact.P1, p1))
            u1?.let { all.add(EclipseContactTime(EclipseContact.U1, it)) }
            u2?.let { all.add(EclipseContactTime(EclipseContact.U2, it)) }
            all.add(EclipseContactTime(EclipseContact.GREATEST, greatest))
            u3?.let { all.add(EclipseContactTime(EclipseContact.U3, it)) }
            u4?.let { all.add(EclipseContactTime(EclipseContact.U4, it)) }
            all.add(EclipseContactTime(EclipseContact.P4, p4))
            return all
        }

    /** Seconds between the first and last penumbral contact. */
    val penumbralDuration: Double get() = p4.timeIntervalSince(p1)

    /** Seconds between the first and last umbral contact; 0 for a penumbral eclipse. */
    val partialDuration: Double
        get() = if (u1 != null && u4 != null) u4.timeIntervalSince(u1) else 0.0

    /** Seconds of totality; 0 unless total. */
    val totalDuration: Double
        get() = if (u2 != null && u3 != null) u3.timeIntervalSince(u2) else 0.0
}

/**
 * The Earth's shadow and the Moon at one instant, as seen from the Earth's centre (angles in degrees).
 */
internal class LunarShadow(val ut: Double) {
    /** Angular distance of the Moon's centre from the shadow axis (the point opposite the Sun). */
    val separation: Double

    /** Angular radius of the umbra at the Moon's distance, with the atmospheric enlargement. */
    val umbra: Double

    /** Angular radius of the penumbra at the Moon's distance, with the atmospheric enlargement. */
    val penumbra: Double

    /** The Moon's angular semidiameter. */
    val moonRadius: Double

    /** The Moon's equatorial horizontal parallax (without enlargement). */
    val parallax: Double

    /** `true` when the Moon is north of the axis (ecliptic north). */
    val isNorth: Boolean

    init {
        val jd = JulianDate(ut)
        val sun = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd) * SkyFrame.kilometersPerAU
        val moon = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, jd) * SkyFrame.kilometersPerAU
        val sunDistance = sun.length
        val moonDistance = moon.length
        val axis = -sun / sunDistance
        separation = moon.angle(axis)
        val earthRadius = SkyFrame.earthEquatorialRadiusKilometers * shadowEnlargement
        val moonParallax = AstroMath.asinDeg(earthRadius / moonDistance)
        val sunParallax = AstroMath.asinDeg(earthRadius / sunDistance)
        val sunRadius = AstroMath.asinDeg(SolarSystemBody.SUN.meanRadiusKilometers / sunDistance)
        umbra = moonParallax + sunParallax - sunRadius
        penumbra = moonParallax + sunParallax + sunRadius
        moonRadius = AstroMath.asinDeg(SolarSystemBody.MOON.meanRadiusKilometers / moonDistance)
        parallax = AstroMath.asinDeg(SkyFrame.earthEquatorialRadiusKilometers / moonDistance)
        val offset = moon - axis * moon.dot(axis)
        isNorth = offset.dot(eclipticNorth) >= 0
    }

    companion object {
        /** Danjon's rule: the Earth's radius is enlarged by 1 % for the atmosphere. */
        const val shadowEnlargement: Double = 1.01

        /** The north pole of the ecliptic of J2000.0 in the J2000 equatorial frame. */
        val eclipticNorth: Vector3 = Vector3(
            0.0,
            -sin(AstroMath.radians(Obliquity.j2000)),
            cos(AstroMath.radians(Obliquity.j2000)),
        )
    }
}

/**
 * Finds lunar eclipses: penumbral, partial and total, with the contacts P1, U1, U2, greatest, U3, U4 and P4.
 *
 * For each full Moon whose ecliptic latitude is below 1.7° the greatest eclipse is the minimum of the Moon's
 * angular distance from the anti-solar point (golden-section search), and each contact is the root (Brent) of that
 * distance minus the sum or difference of the Moon's semidiameter and the shadow's radius. The positions are the
 * geocentric apparent places of [Ephemeris]. Cost: about 2 ms per eclipse, plus the phases.
 */
object LunarEclipseFinder {
    private const val latitudeLimit: Double = 1.7
    private const val searchHalfWindow: Double = 0.15
    private const val contactHalfWindow: Double = 0.25
    private const val greatestTolerance: Double = 1e-6
    private const val contactTolerance: Double = 1e-6

    /** The lunar eclipses with the greatest eclipse from [from] to [to] (both inclusive), as events without visibility. */
    fun events(from: Instant, to: Instant): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val end = EventSearch.limitedEnd(from, to)
        return events(from, end, PhaseTable(from, end))
    }

    internal fun events(from: Instant, to: Instant, phases: PhaseTable): List<AstronomicalEvent> {
        val found = ArrayList<AstronomicalEvent>()
        for (full in phases.fullMoons) {
            val eclipse = eclipse(full) ?: continue
            if (eclipse.greatest < from || eclipse.greatest > to) continue
            found.add(AstronomicalEvent(eclipse.greatest, EventDetails.LunarEclipse(eclipse), null))
        }
        return found
    }

    /** The lunar eclipse of the full Moon at [full], or `null` when the Moon misses the penumbra. */
    internal fun eclipse(full: Instant): LunarEclipse? {
        val ut0 = EventSearch.ut(full)
        val moon = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, JulianDate(ut0))
        if (abs(AstroMath.asinDeg(moon.dot(LunarShadow.eclipticNorth) / moon.length)) > latitudeLimit) return null
        val greatestUT = EventSearch.minimum(
            { LunarShadow(it).separation }, ut0 - searchHalfWindow, ut0 + searchHalfWindow, greatestTolerance,
        ).first
        val greatest = LunarShadow(greatestUT)
        if (greatest.separation >= greatest.penumbra + greatest.moonRadius) return null

        val penumbralMagnitude = (greatest.penumbra + greatest.moonRadius - greatest.separation) / (2 * greatest.moonRadius)
        val umbralMagnitude = (greatest.umbra + greatest.moonRadius - greatest.separation) / (2 * greatest.moonRadius)
        val hasUmbra = umbralMagnitude > 0
        val isTotal = greatest.separation < greatest.umbra - greatest.moonRadius

        fun contact(before: Boolean, margin: (LunarShadow) -> Double): Instant? {
            val lower = if (before) greatestUT - contactHalfWindow else greatestUT
            val upper = if (before) greatestUT else greatestUT + contactHalfWindow
            val root = EventSearch.root({ LunarShadow(it).let { shadow -> shadow.separation - margin(shadow) } }, lower, upper, contactTolerance)
            return root?.let { EventSearch.instant(it) }
        }

        val penumbralBegin = contact(true) { it.penumbra + it.moonRadius } ?: return null
        val penumbralEnd = contact(false) { it.penumbra + it.moonRadius } ?: return null
        val partialBegin = if (hasUmbra) contact(true) { it.umbra + it.moonRadius } else null
        val partialEnd = if (hasUmbra) contact(false) { it.umbra + it.moonRadius } else null
        val totalBegin = if (isTotal) contact(true) { it.umbra - it.moonRadius } else null
        val totalEnd = if (isTotal) contact(false) { it.umbra - it.moonRadius } else null
        val gamma = (if (greatest.isNorth) 1.0 else -1.0) * greatest.separation / greatest.parallax
        return LunarEclipse(
            kind = if (isTotal) LunarEclipseKind.TOTAL else if (hasUmbra) LunarEclipseKind.PARTIAL else LunarEclipseKind.PENUMBRAL,
            gamma = gamma,
            penumbralMagnitude = penumbralMagnitude,
            umbralMagnitude = umbralMagnitude,
            p1 = penumbralBegin,
            u1 = partialBegin,
            u2 = totalBegin,
            greatest = EventSearch.instant(greatestUT),
            u3 = totalEnd,
            u4 = partialEnd,
            p4 = penumbralEnd,
        )
    }
}

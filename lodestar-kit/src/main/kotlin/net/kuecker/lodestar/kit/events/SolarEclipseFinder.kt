package net.kuecker.lodestar.kit.events

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The kind of a solar eclipse over the whole Earth.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class SolarEclipseKind(val rawValue: String) {
    /** The Moon's shadow axis misses the Earth, or the umbra and antumbra never touch it. */
    @SerialName("partial")
    PARTIAL("partial"),

    /** The Moon appears smaller than the Sun everywhere along the path. */
    @SerialName("annular")
    ANNULAR("annular"),

    /** The Moon appears larger than the Sun everywhere along the path. */
    @SerialName("total")
    TOTAL("total"),

    /** Annular at the ends of the path and total in the middle. */
    @SerialName("hybrid")
    HYBRID("hybrid"),
    ;

    /** The English name, e.g. "Annular". */
    val displayName: String
        get() = when (this) {
            PARTIAL -> "Partial"
            ANNULAR -> "Annular"
            TOTAL -> "Total"
            HYBRID -> "Hybrid"
        }

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): SolarEclipseKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * What an observer sees of a solar eclipse.
 *
 * @property rawValue The name used as the serialized form.
 */
@Serializable
enum class LocalSolarEclipseKind(val rawValue: String) {
    /** No eclipse is seen: the Moon does not touch the Sun, or the Sun is below the horizon throughout. */
    @SerialName("none")
    NONE("none"),

    /** Only a partial eclipse is seen. */
    @SerialName("partial")
    PARTIAL("partial"),

    /** The Moon is centred on the Sun but smaller: a ring remains. */
    @SerialName("annular")
    ANNULAR("annular"),

    /** The Moon covers the Sun completely. */
    @SerialName("total")
    TOTAL("total"),
    ;

    companion object {
        /** The kind whose [rawValue] is [raw], or `null` (Swift `init?(rawValue:)`). */
        fun fromRawValue(raw: String): LocalSolarEclipseKind? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * A solar eclipse at one place, from the topocentric positions of the Sun and the Moon.
 *
 * The contact times (C1 to C4) and the geometry are those of the discs of the Sun and the Moon as seen from the
 * observer, without regard to the horizon; [kind], [maximum], [magnitude], [obscuration] and the Sun's position refer
 * to what can be seen: when the Sun rises or sets during the eclipse, [maximum] is the best visible moment (the
 * greatest eclipse if the Sun is up then, otherwise the sunrise or the sunset).
 *
 * @property kind What is seen; [LocalSolarEclipseKind.NONE] when the Moon never touches the Sun here or the Sun is
 * below the horizon (centre lower than −0.8333°) for all of the eclipse.
 * @property c1 First contact: the Moon begins to cover the Sun; `null` when the Moon never touches the Sun.
 * @property c2 Second contact: totality or annularity begins; `null` unless the Moon is centred on the Sun here.
 * @property maximum The moment of the greatest visible eclipse; `null` when the Moon never touches the Sun.
 * @property c3 Third contact: totality or annularity ends; `null` unless the Moon is centred on the Sun here.
 * @property c4 Fourth contact: the Moon leaves the Sun; `null` when the Moon never touches the Sun.
 * @property magnitude The fraction of the Sun's diameter covered at [maximum] (1 or more for a total eclipse, less
 * than 1 for an annular one), as in the tables of the U.S. Naval Observatory; 0 for none.
 * @property obscuration The fraction of the Sun's disc area covered at [maximum], [0, 1].
 * @property sunAltitude The Sun's geometric altitude at [maximum] in degrees; `null` for none without a contact.
 * @property sunAzimuth The Sun's azimuth at [maximum] in degrees from north through east; `null` for none without a contact.
 * @property centralDuration Seconds between C2 and C3; 0 without a central phase.
 * @property visibleInterval The part of the eclipse with the Sun above the horizon; `null` when there is none.
 */
data class LocalSolarCircumstances(
    val kind: LocalSolarEclipseKind,
    val c1: Instant?,
    val c2: Instant?,
    val maximum: Instant?,
    val c3: Instant?,
    val c4: Instant?,
    val magnitude: Double,
    val obscuration: Double,
    val sunAltitude: Double?,
    val sunAzimuth: Double?,
    val centralDuration: Double,
    val visibleInterval: DateInterval?,
)

/**
 * A solar eclipse; the event's date is [greatest].
 *
 * @property kind The kind of eclipse over the whole Earth.
 * @property greatest The instant of the greatest eclipse: the shadow axis is nearest the Earth's centre.
 * @property gamma The least distance of the shadow axis from the Earth's centre in Earth equatorial radii, positive
 * when it passes north of the centre.
 * @property greatestMagnitude The magnitude at the greatest eclipse as in the NASA/Espenak catalog: the ratio of the
 * Moon's to the Sun's apparent diameter for a central eclipse and the fraction of the Sun's diameter covered otherwise.
 * @property isCentral `true` when the axis of the shadow touches the Earth, so a total, annular or hybrid eclipse
 * occurs somewhere (the global flag).
 * @property local The eclipse at the observer's place; `null` without an observer.
 */
data class SolarEclipse(
    val kind: SolarEclipseKind,
    val greatest: Instant,
    val gamma: Double,
    val greatestMagnitude: Double,
    val isCentral: Boolean,
    val local: LocalSolarCircumstances?,
)

/**
 * The Moon's shadow cone at one instant, with the axis through the centres of the Sun and the Moon (geocentric apparent
 * positions, kilometres).
 */
internal class SolarShadow(val ut: Double) {
    /** Distance of the Earth's centre from the shadow axis. */
    val axisDistance: Double

    /** Radius of the penumbra in the fundamental plane (through the Earth's centre, perpendicular to the axis). */
    val penumbraRadius: Double

    /** Radius of the umbra in that plane; negative when the cone ends before it (the antumbra of an annular eclipse). */
    val umbraRadius: Double

    /** Tangent of the half-angle of the umbra cone. */
    val umbraTangent: Double

    /** Tangent of the half-angle of the penumbra cone. */
    val penumbraTangent: Double

    /** Distance from the Moon's centre to the fundamental plane along the axis. */
    val planeDistance: Double

    /** Distance between the centres of the Sun and the Moon. */
    val sunMoonDistance: Double

    /** `true` when the axis passes north of the Earth's centre (north of the equator of J2000). */
    val isNorth: Boolean

    init {
        val jd = JulianDate(ut)
        val sun = Ephemeris.geocentricJ2000(SolarSystemBody.SUN, jd) * SkyFrame.kilometersPerAU
        val moon = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, jd) * SkyFrame.kilometersPerAU
        val between = moon - sun
        val distance = between.length
        val direction = between / distance
        val sunRadius = SolarSystemBody.SUN.meanRadiusKilometers
        val moonRadius = SolarSystemBody.MOON.meanRadiusKilometers
        val penumbraSine = (sunRadius + moonRadius) / distance
        val umbraSine = (sunRadius - moonRadius) / distance
        val penumbraCosine = sqrt(1 - penumbraSine * penumbraSine)
        val umbraCosine = sqrt(1 - umbraSine * umbraSine)
        penumbraTangent = penumbraSine / penumbraCosine
        umbraTangent = umbraSine / umbraCosine
        val toPlane = -moon.dot(direction)
        planeDistance = toPlane
        sunMoonDistance = distance
        penumbraRadius = moonRadius / penumbraCosine + toPlane * penumbraTangent
        umbraRadius = moonRadius / umbraCosine - toPlane * umbraTangent
        axisDistance = moon.cross(direction).length
        isNorth = (moon + direction * toPlane).z >= 0
    }
}

/** What the observer sees of the Sun and the Moon at one instant (angles in degrees). */
private class SolarView(ut: Double, observer: Observer) {
    val separation: Double
    val sunRadius: Double
    val moonRadius: Double
    val sunAltitude: Double
    val sunAzimuth: Double

    init {
        val frame = SkyFrame(JulianDate(ut), observer)
        val sun = frame.precessionNutationMatrix * Ephemeris.geocentricJ2000(SolarSystemBody.SUN, frame.julianDate) -
            frame.observerPositionOfDate
        val moon = frame.precessionNutationMatrix * Ephemeris.geocentricJ2000(SolarSystemBody.MOON, frame.julianDate) -
            frame.observerPositionOfDate
        separation = sun.angle(moon)
        sunRadius = AstroMath.asinDeg(SolarSystemBody.SUN.meanRadiusKilometers / (sun.length * SkyFrame.kilometersPerAU))
        moonRadius = AstroMath.asinDeg(SolarSystemBody.MOON.meanRadiusKilometers / (moon.length * SkyFrame.kilometersPerAU))
        val horizontal = HorizontalCoordinate(frame.equatorialOfDateToHorizontal * sun)
        sunAltitude = horizontal.altitude
        sunAzimuth = horizontal.azimuth
    }

    /** The fraction of the Sun's diameter covered. */
    val magnitude: Double get() = DiscOverlap.magnitude(sunRadius, moonRadius, separation)

    /** The fraction of the Sun's disc area covered. */
    val obscuration: Double get() = DiscOverlap.obscuration(sunRadius, moonRadius, separation)
}

/** The geometry of the Moon's disc in front of the Sun's. */
internal object DiscOverlap {
    /**
     * The fraction of the Sun's diameter covered by the Moon's disc: (r☉ + r☾ − d) / 2 r☉, at least 0 (above 1 when the
     * Moon covers the whole Sun and more).
     */
    fun magnitude(sunRadius: Double, moonRadius: Double, separation: Double): Double =
        AstroMath.clamp((sunRadius + moonRadius - separation) / (2 * sunRadius), 0.0, Double.MAX_VALUE)

    /**
     * The fraction of the area of the Sun's disc (radius [sunRadius]) covered by the Moon's disc (radius [moonRadius])
     * whose centre is [separation] away (all in the same unit): the area of the lens of two circles over the Sun's area.
     */
    fun obscuration(sunRadius: Double, moonRadius: Double, separation: Double): Double {
        if (separation >= sunRadius + moonRadius) return 0.0
        if (separation <= abs(sunRadius - moonRadius)) {
            return if (moonRadius >= sunRadius) 1.0 else (moonRadius / sunRadius) * (moonRadius / sunRadius)
        }
        val d = separation
        val first = acos(AstroMath.clamp((d * d + sunRadius * sunRadius - moonRadius * moonRadius) / (2 * d * sunRadius), -1.0, 1.0))
        val second = acos(AstroMath.clamp((d * d + moonRadius * moonRadius - sunRadius * sunRadius) / (2 * d * moonRadius), -1.0, 1.0))
        val kite = sqrt(
            maxOf(
                0.0,
                (-d + sunRadius + moonRadius) * (d + sunRadius - moonRadius) *
                    (d - sunRadius + moonRadius) * (d + sunRadius + moonRadius),
            ),
        )
        val area = sunRadius * sunRadius * first + moonRadius * moonRadius * second - 0.5 * kite
        return AstroMath.clamp(area / (PI * sunRadius * sunRadius), 0.0, 1.0)
    }
}

/**
 * Finds solar eclipses and computes their local circumstances.
 *
 * **Global.** For each new Moon whose ecliptic latitude is below 1.75° the greatest eclipse is the instant at which the
 * axis through the centres of the Sun and the Moon passes nearest the Earth's centre (golden-section search). The
 * shadow cone gives γ and the radii of the penumbra and umbra in the fundamental plane, and the kind follows J. Meeus,
 * *Astronomical Algorithms*, ch. 54: central if |γ| < 0.9972, then total when the umbra reaches the plane, hybrid when
 * it falls short by less than the depth of the Earth's surface at γ and annular when it falls further short; partial
 * otherwise.
 *
 * **Local.** The separation of the topocentric apparent positions of the Sun's and the Moon's centres is sampled every
 * 20 minutes within ±7.2 hours of the greatest eclipse; the maximum is found by golden-section search, the contacts by
 * Brent's method on the separation minus the sum (C1, C4) or the difference (C2, C3) of the apparent radii.
 *
 * Cost: about 3 ms per eclipse for the global circumstances and about 2 ms per observer.
 */
object SolarEclipseFinder {
    /** The ecliptic latitude of the Moon in degrees above which a new Moon is not examined. */
    private const val latitudeLimit: Double = 1.75
    private const val searchHalfWindow: Double = 0.2
    private const val greatestTolerance: Double = 1e-6
    private const val centralLimit: Double = 0.9972
    private const val localHalfWindow: Double = 0.3
    private const val localStep: Double = 20.0 / 1440
    private const val visibleStep: Double = 10.0 / 1440
    private const val contactTolerance: Double = 1e-6

    /**
     * The solar eclipses with the greatest eclipse from [from] to [to] (both inclusive), as events without visibility,
     * in time order. With an [observer] each carries its [SolarEclipse.local] circumstances.
     */
    fun events(from: Instant, to: Instant, observer: Observer? = null): List<AstronomicalEvent> {
        if (!EventSearch.isUsable(from, to)) return emptyList()
        val end = EventSearch.limitedEnd(from, to)
        return events(from, end, observer, PhaseTable(from, end))
    }

    internal fun events(from: Instant, to: Instant, observer: Observer?, phases: PhaseTable): List<AstronomicalEvent> {
        val found = ArrayList<AstronomicalEvent>()
        for (newMoon in phases.newMoons) {
            val eclipse = eclipse(newMoon) ?: continue
            if (eclipse.greatest < from || eclipse.greatest > to) continue
            val complete = if (observer != null) eclipse.copy(local = localCircumstances(eclipse, observer)) else eclipse
            found.add(AstronomicalEvent(complete.greatest, EventDetails.SolarEclipse(complete), null))
        }
        return found
    }

    /** The eclipse of the new Moon at [newMoon] without local circumstances, or `null` when the Moon's shadow misses the Earth. */
    internal fun eclipse(newMoon: Instant): SolarEclipse? {
        val ut0 = EventSearch.ut(newMoon)
        val moon = Ephemeris.geocentricJ2000(SolarSystemBody.MOON, JulianDate(ut0))
        if (abs(AstroMath.asinDeg(moon.dot(LunarShadow.eclipticNorth) / moon.length)) > latitudeLimit) return null
        val greatestUT = EventSearch.minimum(
            { SolarShadow(it).axisDistance }, ut0 - searchHalfWindow, ut0 + searchHalfWindow, greatestTolerance,
        ).first
        val shadow = SolarShadow(greatestUT)
        val earthRadius = SkyFrame.earthEquatorialRadiusKilometers
        val absoluteGamma = shadow.axisDistance / earthRadius
        if (absoluteGamma >= 1 + shadow.penumbraRadius / earthRadius) return null
        val gamma = if (shadow.isNorth) absoluteGamma else -absoluteGamma
        val u = -shadow.umbraRadius / earthRadius
        val isCentralPath = absoluteGamma < centralLimit
        val kind: SolarEclipseKind
        if (isCentralPath) {
            val depth = shadow.umbraTangent * sqrt(1 - absoluteGamma * absoluteGamma)
            kind = if (u < 0) SolarEclipseKind.TOTAL else if (u > depth) SolarEclipseKind.ANNULAR else SolarEclipseKind.HYBRID
        } else if (absoluteGamma < centralLimit + abs(u)) {
            kind = if (u < 0) SolarEclipseKind.TOTAL else SolarEclipseKind.ANNULAR
        } else {
            kind = SolarEclipseKind.PARTIAL
        }
        val magnitude: Double
        if (isCentralPath) {
            val surface = shadow.planeDistance - sqrt(earthRadius * earthRadius - shadow.axisDistance * shadow.axisDistance)
            val sunRadius = SolarSystemBody.SUN.meanRadiusKilometers
            val moonRadius = SolarSystemBody.MOON.meanRadiusKilometers
            magnitude = moonRadius * (shadow.sunMoonDistance + surface) / (sunRadius * surface)
        } else {
            val offset = maxOf(shadow.axisDistance - earthRadius, 0.0)
            magnitude = (shadow.penumbraRadius - offset) / (shadow.penumbraRadius - shadow.umbraRadius)
        }
        return SolarEclipse(
            kind = kind,
            greatest = EventSearch.instant(greatestUT),
            gamma = gamma,
            greatestMagnitude = magnitude,
            isCentral = isCentralPath && kind != SolarEclipseKind.PARTIAL,
            local = null,
        )
    }

    /** The circumstances of [eclipse] at [observer]'s place; costs about 2 ms. */
    fun localCircumstances(eclipse: SolarEclipse, observer: Observer): LocalSolarCircumstances {
        val centre = EventSearch.ut(eclipse.greatest)
        if (!centre.isFinite()) {
            return LocalSolarCircumstances(
                LocalSolarEclipseKind.NONE, null, null, null, null, null, 0.0, 0.0, null, null, 0.0, null,
            )
        }
        val start = centre - localHalfWindow
        val end = centre + localHalfWindow
        val count = EventSearch.intervalCount(end - start, localStep)
        val times = DoubleArray(count + 1) { start + (end - start) * it / count }
        val views = Array(count + 1) { SolarView(times[it], observer) }
        var lowest = 0
        for (index in 1..count) {
            if (views[index].separation < views[lowest].separation) lowest = index
        }
        val peak = EventSearch.minimum(
            { SolarView(it, observer).separation },
            times[maxOf(lowest - 1, 0)], times[minOf(lowest + 1, count)], greatestTolerance,
        ).first
        val closest = SolarView(peak, observer)
        if (closest.separation >= closest.sunRadius + closest.moonRadius) {
            return LocalSolarCircumstances(
                LocalSolarEclipseKind.NONE, null, null, null, null, null, 0.0, 0.0, null, null, 0.0, null,
            )
        }

        fun contactMargin(ut: Double): Double {
            val view = SolarView(ut, observer)
            return view.separation - (view.sunRadius + view.moonRadius)
        }

        fun centralMargin(ut: Double): Double {
            val view = SolarView(ut, observer)
            return view.separation - abs(view.moonRadius - view.sunRadius)
        }

        val firstContact = EventSearch.root({ contactMargin(it) }, start, peak, contactTolerance) ?: start
        val lastContact = EventSearch.root({ contactMargin(it) }, peak, end, contactTolerance) ?: end
        var secondContact: Double? = null
        var thirdContact: Double? = null
        if (closest.separation < abs(closest.moonRadius - closest.sunRadius)) {
            secondContact = EventSearch.root({ centralMargin(it) }, firstContact, peak, contactTolerance)
            thirdContact = EventSearch.root({ centralMargin(it) }, peak, lastContact, contactTolerance)
        }

        val horizon = RiseSetCalculator.sunHorizonAltitude
        val window = AltitudeWindow.best(firstContact, lastContact, visibleStep, horizon) { SolarView(it, observer).sunAltitude }
        val maximum = if (window != null) AstroMath.clamp(peak, window.start, window.end) else peak
        val view = SolarView(maximum, observer)
        val centralVisible = window != null && secondContact != null && thirdContact != null &&
            secondContact <= window.end && thirdContact >= window.start
        val kind = if (window == null) {
            LocalSolarEclipseKind.NONE
        } else if (centralVisible) {
            if (closest.moonRadius > closest.sunRadius) LocalSolarEclipseKind.TOTAL else LocalSolarEclipseKind.ANNULAR
        } else {
            LocalSolarEclipseKind.PARTIAL
        }
        return LocalSolarCircumstances(
            kind = kind,
            c1 = EventSearch.instant(firstContact),
            c2 = secondContact?.let { EventSearch.instant(it) },
            maximum = EventSearch.instant(maximum),
            c3 = thirdContact?.let { EventSearch.instant(it) },
            c4 = EventSearch.instant(lastContact),
            magnitude = view.magnitude,
            obscuration = view.obscuration,
            sunAltitude = view.sunAltitude,
            sunAzimuth = view.sunAzimuth,
            centralDuration = if (secondContact != null && thirdContact != null) (thirdContact - secondContact) * JulianDate.secondsPerDay else 0.0,
            visibleInterval = window?.let { DateInterval(EventSearch.instant(it.start), EventSearch.instant(it.end)) },
        )
    }
}

package net.kuecker.lodestar.kit.satellites

import net.kuecker.lodestar.kit.ephemeris.Ephemeris
import net.kuecker.lodestar.kit.ephemeris.SolarSystemBody
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where to stand to see a satellite cross the middle of the Sun's or the Moon's disc.
 *
 * @property location The place on the centreline (same elevation as the observer), where the satellite's centre
 * crosses the body's centre at [SatelliteTransit.date].
 * @property distanceMeters Ground distance from the observer to [location].
 * @property bearing Direction from the observer to [location], degrees from true north through east, [0, 360).
 * @property separation What is left of the centre-to-centre separation at [location] at [SatelliteTransit.date],
 * in degrees (below 0.01 by construction).
 */
data class SatelliteTransitCentreline(
    val location: GeographicLocation,
    val distanceMeters: Double,
    val bearing: Double,
    val separation: Double,
)

/**
 * One transit, or near transit, of a satellite in front of the Sun or the Moon, found by
 * [SatelliteTransitFinder]. All angles are in degrees and geometric (no refraction); the body's direction is
 * topocentric.
 *
 * @property catalogNumber The satellite's NORAD number.
 * @property name The satellite's name.
 * @property body [SolarSystemBody.SUN] or [SolarSystemBody.MOON].
 * @property date The moment of the smallest separation, seen from the observer's site (to 0.01 s). The same moment
 * holds on the [centreline].
 * @property minimumSeparation The smallest centre-to-centre separation between the satellite and the body as seen
 * from the site.
 * @property isFromSite `true` when the satellite's disc overlaps the body's disc as seen from the site
 * ([minimumSeparation] < [bodyAngularRadius] + half of [satelliteAngularSize]).
 * @property bodyAngularRadius The body's angular radius for the observer (the Moon's depends on its distance and
 * the observer's parallax).
 * @property satelliteAngularSize The satellite's angular extent at [date] from its span (see
 * [SatelliteTransitFinder.satelliteSpanMeters]) and its range.
 * @property satelliteRangeKilometers The distance to the satellite at [date].
 * @property angularSpeed The speed of the satellite across the body's disc, relative to the body, in degrees per second.
 * @property bodyAltitude The body's geometric altitude at [date].
 * @property bodyAzimuth The body's azimuth at [date], from north through east, [0, 360).
 * @property sunAltitude The Sun's geometric altitude at [date].
 * @property isDaylight `true` when the Sun's centre is above -0.833° (the Sun is up).
 * @property isNight `true` when the Sun is below [SatellitePassFinder.defaultDarknessSunAltitude] (-6°).
 * @property siteDurationSeconds How long the satellite's centre is inside the body's disc as seen from the site:
 * the chord at [minimumSeparation] divided by [angularSpeed]; 0 when the centre never enters the disc.
 * @property centrelineDurationSeconds The longest crossing: the disc's diameter divided by the angular speed at the
 * [centreline] (at the site when there is none). The satellite's own size (about 3 % of the Sun's disc) is not added.
 * @property centreline The place of the central transit, or `null` when it is farther than the search's ground distance.
 */
data class SatelliteTransit(
    val catalogNumber: Int,
    val name: String,
    val body: SolarSystemBody,
    val date: Instant,
    val minimumSeparation: Double,
    val isFromSite: Boolean,
    val bodyAngularRadius: Double,
    val satelliteAngularSize: Double,
    val satelliteRangeKilometers: Double,
    val angularSpeed: Double,
    val bodyAltitude: Double,
    val bodyAzimuth: Double,
    val sunAltitude: Double,
    val isDaylight: Boolean,
    val isNight: Boolean,
    val siteDurationSeconds: Double,
    val centrelineDurationSeconds: Double,
    val centreline: SatelliteTransitCentreline?,
)

/**
 * Finds the transits of a satellite (the ISS, the Chinese space station) across the Sun or the Moon.
 *
 * **Method.** [SatellitePassFinder] gives the passes above 3° (daylight passes included: it does not filter by
 * darkness); the body must be above 3° as well. Over each pass the angular separation between the satellite and
 * the centre of the body (both topocentric: the satellite from SGP4, the body from the engine's ephemeris
 * interpolated from hourly nodes, with parallax) is sampled every 2 s, every local minimum is refined by a
 * golden-section search to 0.01 s, and kept when the satellite's disc overlaps the body's (a transit from the
 * site) or when the centreline is near enough. The centreline is the place on the ground from where the satellite
 * crosses the body's centre at the same moment: the separation vector `(Δaz·cos alt, Δalt)` is evaluated for
 * observers displaced 1 km north and 1 km east (and the opposite way), the 2×2 linear system for the ground
 * displacement that zeroes it is solved, and the step is repeated twice more from the new place. The displaced
 * observers keep the elevation of the original one; terrain is not known to the engine.
 *
 * **Cost.** Bounded by passes × samples: about 100 samples per pass and a few hundred evaluations (2 µs each) per
 * kept candidate. A 14-day search for the ISS takes about 20 ms on a desktop and well under a second on a phone.
 *
 * **Accuracy.** The element set decides: an along-track error of a few km grows 1–3 km a day for the ISS, which
 * is 0.3 s a day of timing and a few km of centreline position. Use element sets that are a day or two old and
 * check again on the day.
 */
object SatelliteTransitFinder {
    /** The default ground distance in metres within which the centreline is reported, 50 km. */
    const val defaultMaxGroundDistanceMeters: Double = 50_000.0

    /** The largest ground distance accepted, 200 km. */
    const val maximumGroundDistanceMeters: Double = 200_000.0

    /** The altitude in degrees the body and the satellite must exceed, 3°. */
    const val minimumBodyAltitude: Double = 3.0

    /** The longest window searched, in days. */
    const val maximumWindowDays: Double = SatellitePassFinder.maximumWindowDays

    /** The Sun's altitude in degrees above which it counts as daylight, -0.833° (upper limb at the refracted horizon). */
    const val daylightSunAltitude: Double = -0.833

    /**
     * The span of a satellite in metres, from a small table keyed by NORAD number (default 50 m): the ISS and its
     * modules 109 m (the solar-array truss, NASA); the Chinese space station and its modules 55 m (approximate
     * overall size of the T-shaped station).
     */
    fun satelliteSpanMeters(catalogNumber: Int): Double = when (catalogNumber) {
        25544, 36086, 49044 -> 109.0
        48274, 53239, 54216 -> 55.0
        else -> 50.0
    }

    /**
     * The transits of [satellite] across [body] seen from [observer] or from a place within
     * [maxGroundDistanceMeters] of it, between [from] and [to], in chronological order.
     *
     * @param body [SolarSystemBody.SUN] or [SolarSystemBody.MOON]; any other body gives an empty list.
     * @param maxGroundDistanceMeters How far to look for the centreline, clamped to 0 ... [maximumGroundDistanceMeters].
     * With 0 only transits from the site itself are returned.
     * @return the transits; empty when the window is empty or not finite, or when SGP4 cannot propagate the satellite.
     * The window is limited to [maximumWindowDays].
     */
    fun transits(
        satellite: Satellite,
        body: SolarSystemBody,
        observer: Observer,
        from: Instant,
        to: Instant,
        maxGroundDistanceMeters: Double = defaultMaxGroundDistanceMeters,
    ): List<SatelliteTransit> {
        if (body != SolarSystemBody.SUN && body != SolarSystemBody.MOON) return emptyList()
        val start = from.timeIntervalSinceReferenceDate
        val requestedEnd = to.timeIntervalSinceReferenceDate
        if (!start.isFinite() || !requestedEnd.isFinite() || !(requestedEnd > start)) return emptyList()
        val end = min(requestedEnd, start + maximumWindowDays * JulianDate.secondsPerDay)
        val ground = if (maxGroundDistanceMeters.isFinite()) {
            AstroMath.clamp(maxGroundDistanceMeters, 0.0, maximumGroundDistanceMeters)
        } else {
            defaultMaxGroundDistanceMeters
        }
        val finder = SatellitePassFinder(
            observer,
            DateInterval(from, instantFromTimeIntervalSinceReferenceDate(end)),
            minimumAltitude = minimumBodyAltitude,
        )
        val passes = finder.passes(satellite)
        if (passes.isEmpty()) return emptyList()
        val search = SatelliteTransitSearch(satellite, body, observer, SatelliteTransitBodyTrack.create(body, start, end), ground)
        val transits = ArrayList<SatelliteTransit>()
        for (pass in passes) search.search(pass, transits)
        transits.sortBy { it.date }
        return transits
    }
}

/**
 * The body's TEME position, interpolated like [SatelliteSunTrack] (hourly nodes, cubic Lagrange): the Moon moves
 * 0.55° an hour and the interpolation error is about 0.1 m. Times are seconds since the Foundation reference date.
 */
internal class SatelliteTransitBodyTrack private constructor(private val origin: Double, private val nodes: Array<Vector3>) {
    /** The body's TEME position at [time] in kilometres (the time should lie in the interval given to [create]). */
    fun position(time: Double): Vector3 {
        val raw = floor((time - origin) / spacing).coerceIn(-1.0e6, 1.0e6).toInt()
        val base = raw.coerceIn(1, nodes.size - 3)
        val u = (time - origin) / spacing - base.toDouble()
        val w0 = -u * (u - 1) * (u - 2) / 6
        val w1 = (u + 1) * (u - 1) * (u - 2) / 2
        val w2 = -(u + 1) * u * (u - 2) / 2
        val w3 = (u + 1) * u * (u - 1) / 6
        val n0 = nodes[base - 1]
        val n1 = nodes[base]
        val n2 = nodes[base + 1]
        val n3 = nodes[base + 2]
        return Vector3(
            n0.x * w0 + n1.x * w1 + n2.x * w2 + n3.x * w3,
            n0.y * w0 + n1.y * w1 + n2.y * w2 + n3.y * w3,
            n0.z * w0 + n1.z * w1 + n2.z * w2 + n3.z * w3,
        )
    }

    companion object {
        /** Seconds between nodes. */
        const val spacing: Double = 3_600.0

        /** The most nodes a track holds. */
        const val maximumNodeCount: Int = 5_000

        /** The track of [body] for `[start, end]` (seconds since the reference date). */
        fun create(body: SolarSystemBody, start: Double, end: Double): SatelliteTransitBodyTrack {
            val from = if (start.isFinite()) start else 0.0
            val to = if (end.isFinite()) max(end, from) else from
            val first = floor(from / spacing) * spacing - spacing
            val last = ceil(to / spacing) * spacing + 2 * spacing
            val count = min(max(5, ((last - first) / spacing).toInt() + 1), maximumNodeCount)
            val nodes = Array(count) { index -> exact(body, first + index * spacing) }
            return SatelliteTransitBodyTrack(first, nodes)
        }

        /** The geocentric apparent position of [body] (Sun or Moon) in TEME at [time], in kilometres, from the ephemeris. */
        fun exact(body: SolarSystemBody, time: Double): Vector3 {
            if (body == SolarSystemBody.SUN) return SatelliteSunTrack.exact(time)
            val jd = JulianDate(instantFromTimeIntervalSinceReferenceDate(time))
            val bodyJ2000 = Ephemeris.geocentricJ2000(body, jd)
            val nutation = Nutation(jd)
            val meanObliquity = Obliquity.mean(jd)
            val trueObliquity = meanObliquity + nutation.obliquity
            val equationOfTheEquinoxes = nutation.longitude * AstroMath.cosDeg(trueObliquity)
            val toTrueOfDate = nutation.matrix(meanObliquity) * Precession.matrixFromJ2000(jd)
            val toTeme = Matrix3.frameRotationZ(AstroMath.radians(equationOfTheEquinoxes)) * toTrueOfDate
            return (toTeme * bodyJ2000) * SkyFrame.kilometersPerAU
        }
    }
}

/** One satellite's transit search for one body and observer. Local state only; one instance per call. */
internal class SatelliteTransitSearch(
    private val satellite: Satellite,
    private val body: SolarSystemBody,
    private val observer: Observer,
    private val track: SatelliteTransitBodyTrack,
    private val maxGround: Double,
) {
    private val propagator = satellite.propagator
    private val site = SatelliteObserverGeometry(observer)
    private val state = DoubleArray(6)
    private val look = DoubleArray(8)
    private val bodyRadiusKilometers = body.meanRadiusKilometers
    private val maximumSpeed = propagator.maximumSpeedKilometersPerSecond
    private val spanMeters = SatelliteTransitFinder.satelliteSpanMeters(satellite.catalogNumber)
    private var offsetX = 0.0
    private var offsetY = 0.0

    /** What [measure] finds at one place and moment. */
    private class Measure(
        val separation: Double,
        val bodyRadius: Double,
        val range: Double,
        val speed: Double,
        val bodyAltitude: Double,
        val bodyAzimuth: Double,
    )

    /** The places on the ground [centreline] found. */
    private class Centreline(val place: SatelliteTransitCentreline, val measure: Measure)

    /**
     * Fills [out] with the satellite's ENU unit vector (0-2) and range in kilometres (3), and the body's topocentric
     * ENU unit vector (4-6) and distance in kilometres (7), for [geometry] at [time]. `false` where SGP4 fails.
     */
    private fun evaluate(geometry: SatelliteObserverGeometry, time: Double, out: DoubleArray): Boolean {
        val minutes = (time - propagator.epochReferenceSeconds) / 60.0
        if (propagator.propagateRaw(minutes, state) != 0) return false
        val gmst = Sgp4Time.greenwichSiderealTime(SatelliteFrames.julianDateUt(time))
        val topocentric = geometry.look(state[0], state[1], state[2], state[3], state[4], state[5], gmst)
        val range = topocentric.range
        if (!(range > 0.0)) return false
        out[0] = topocentric.east / range
        out[1] = topocentric.north / range
        out[2] = topocentric.up / range
        out[3] = range
        val position = track.position(time)
        val c = cos(gmst)
        val s = sin(gmst)
        val ex = c * position.x + s * position.y
        val ey = -s * position.x + c * position.y
        val dx = ex - geometry.x
        val dy = ey - geometry.y
        val dz = position.z - geometry.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz)
        if (!(distance > 0.0)) return false
        out[4] = (geometry.eastX * dx + geometry.eastY * dy) / distance
        out[5] = (geometry.northX * dx + geometry.northY * dy + geometry.northZ * dz) / distance
        out[6] = (geometry.upX * dx + geometry.upY * dy + geometry.upZ * dz) / distance
        out[7] = distance
        return true
    }

    /** The angle between the satellite and the body's centre in `look`, degrees. */
    private fun lookSeparation(): Double =
        SatelliteVectorMath.angleBetween(look[0], look[1], look[2], look[4], look[5], look[6])

    /** The separation at [time] seen from the site; large where SGP4 fails. */
    private fun separationAt(time: Double): Double =
        if (evaluate(site, time, look)) lookSeparation() else failedSeparation

    /**
     * Sets [offsetX], [offsetY] to the satellite's offset from the body's centre in the body's tangent plane, in
     * degrees (x towards increasing azimuth, y towards increasing altitude), for what `look` holds.
     */
    private fun computeOffset() {
        val bx = look[4]
        val by = look[5]
        val bz = look[6]
        val horizontal = sqrt(bx * bx + by * by)
        val sinAzimuth = if (horizontal > 1.0e-9) bx / horizontal else 0.0
        val cosAzimuth = if (horizontal > 1.0e-9) by / horizontal else 1.0
        val dot = look[0] * bx + look[1] * by + look[2] * bz
        val across = look[0] * cosAzimuth - look[1] * sinAzimuth
        val up = -look[0] * bz * sinAzimuth - look[1] * bz * cosAzimuth + look[2] * horizontal
        offsetX = AstroMath.degrees(atan2(across, dot))
        offsetY = AstroMath.degrees(atan2(up, dot))
    }

    /** Sets the offset for [geometry] at [time]; `false` where SGP4 fails. */
    private fun offsetAt(geometry: SatelliteObserverGeometry, time: Double): Boolean {
        if (!evaluate(geometry, time, look)) return false
        computeOffset()
        return true
    }

    /** The geometry of the transit for [geometry] at [time], or `null` where SGP4 fails. */
    private fun measure(geometry: SatelliteObserverGeometry, time: Double): Measure? {
        if (!evaluate(geometry, time, look)) return null
        val separation = lookSeparation()
        val bodyRadius = AstroMath.degrees(asin(min(bodyRadiusKilometers / look[7], 1.0)))
        val range = look[3]
        val bodyAltitude = AstroMath.asinDeg(look[6])
        val bodyAzimuth = if (look[4] == 0.0 && look[5] == 0.0) 0.0 else AstroMath.normalizeDegrees(AstroMath.atan2Deg(look[4], look[5]))
        if (!offsetAt(geometry, time - speedHalfStep)) return null
        val x1 = offsetX
        val y1 = offsetY
        if (!offsetAt(geometry, time + speedHalfStep)) return null
        val dx = offsetX - x1
        val dy = offsetY - y1
        val speed = sqrt(dx * dx + dy * dy) / (2.0 * speedHalfStep)
        return Measure(separation, bodyRadius, range, speed, bodyAltitude, bodyAzimuth)
    }

    /** The extra separation, in degrees, that a displacement of up to the search's ground distance can cancel at [range] km. */
    private fun reach(range: Double): Double {
        val meters = range * 1000.0
        return AstroMath.degrees(atan2(maxGround, max(meters - maxGround, 0.2 * meters))) + reachMargin
    }

    /** Appends the transits of [pass] to [into]. */
    fun search(pass: SatellitePass, into: MutableList<SatelliteTransit>) {
        val begin = pass.rise.date.timeIntervalSinceReferenceDate
        val finish = pass.set.date.timeIntervalSinceReferenceDate
        val duration = finish - begin
        if (!(duration > 0.0)) return
        // The body moves less than 0.1 degree in the 20 minutes a low-orbit pass lasts: a pass with the body far
        // below the limit at both ends and in the middle has nothing to find.
        if (duration <= shortPass) {
            var highest = -90.0
            for (fraction in doubleArrayOf(0.0, 0.5, 1.0)) {
                if (evaluate(site, begin + fraction * duration, look)) highest = max(highest, AstroMath.asinDeg(look[6]))
            }
            if (highest < SatelliteTransitFinder.minimumBodyAltitude - altitudeMargin) return
        }
        val count = max(1, ceil(duration / coarseStep).toInt())
        val step = duration / count
        val separations = DoubleArray(count + 1) { separationAt(begin + it * step) }
        for (index in 0..count) {
            val value = separations[index]
            if (value >= failedSeparation) continue
            val before = if (index > 0) separations[index - 1] else Double.POSITIVE_INFINITY
            val after = if (index < count) separations[index + 1] else Double.POSITIVE_INFINITY
            if (!(value < before && value <= after)) continue
            val time = begin + index * step
            if (!evaluate(site, time, look)) continue
            val speedBound = AstroMath.degrees(maximumSpeed / look[3])
            if (value > coarseBodyRadius + reach(look[3]) + 0.5 * speedBound * step) continue
            val low = if (index > 0) time - step else time
            val high = if (index < count) time + step else time
            val refined = if (high > low) {
                SatelliteSearch.maximize({ -separationAt(it) }, low, high, timeTolerance)
            } else {
                time
            }
            transit(refined)?.let { into.add(it) }
        }
    }

    /** The transit whose closest approach seen from the site is at [time], or `null` if there is none worth reporting. */
    private fun transit(time: Double): SatelliteTransit? {
        val here = measure(site, time) ?: return null
        if (here.bodyAltitude < SatelliteTransitFinder.minimumBodyAltitude) return null
        val satelliteSize = AstroMath.degrees(spanMeters / (here.range * 1000.0))
        val overlap = here.bodyRadius + 0.5 * satelliteSize
        val isFromSite = here.separation < overlap
        if (!isFromSite && here.separation > overlap + reach(here.range)) return null
        val central = centreline(time)
        if (!isFromSite && central == null) return null
        val sun = SatelliteSunTrack.exact(time)
        val sunAltitude = AstroMath.asinDeg(site.directionOf(sun.x, sun.y, sun.z, Sgp4Time.greenwichSiderealTime(SatelliteFrames.julianDateUt(time))).z)
        val siteChord = if (isFromSite) {
            val half = here.bodyRadius * here.bodyRadius - here.separation * here.separation
            2.0 * sqrt(max(half, 0.0))
        } else {
            0.0
        }
        val longest = central?.measure ?: here
        return SatelliteTransit(
            catalogNumber = satellite.catalogNumber,
            name = satellite.name,
            body = body,
            date = instantFromTimeIntervalSinceReferenceDate(time),
            minimumSeparation = here.separation,
            isFromSite = isFromSite,
            bodyAngularRadius = here.bodyRadius,
            satelliteAngularSize = satelliteSize,
            satelliteRangeKilometers = here.range,
            angularSpeed = here.speed,
            bodyAltitude = here.bodyAltitude,
            bodyAzimuth = here.bodyAzimuth,
            sunAltitude = sunAltitude,
            isDaylight = sunAltitude > SatelliteTransitFinder.daylightSunAltitude,
            isNight = sunAltitude < SatellitePassFinder.defaultDarknessSunAltitude,
            siteDurationSeconds = if (here.speed > 0.0) siteChord / here.speed else 0.0,
            centrelineDurationSeconds = if (longest.speed > 0.0) 2.0 * longest.bodyRadius / longest.speed else 0.0,
            centreline = central?.place,
        )
    }

    /**
     * The place on the ground, within the search's ground distance, from where the satellite crosses the body's
     * centre at [time]; `null` when there is none, or the iteration does not converge.
     */
    private fun centreline(time: Double): Centreline? {
        var latitude = observer.location.latitude
        var longitude = observer.location.longitude
        val elevation = observer.location.elevation
        var geometry = site
        for (iteration in 0 until centrelineIterations) {
            if (!offsetAt(geometry, time)) return null
            val fx = offsetX
            val fy = offsetY
            if (sqrt(fx * fx + fy * fy) < centrelineConvergence) break
            val eastForward = offsetAt(displaced(latitude, longitude, elevation, 0.0, centrelineBaseline), time)
            val eastForwardX = offsetX
            val eastForwardY = offsetY
            val eastBackward = offsetAt(displaced(latitude, longitude, elevation, 0.0, -centrelineBaseline), time)
            val eastBackwardX = offsetX
            val eastBackwardY = offsetY
            val northForward = offsetAt(displaced(latitude, longitude, elevation, centrelineBaseline, 0.0), time)
            val northForwardX = offsetX
            val northForwardY = offsetY
            val northBackward = offsetAt(displaced(latitude, longitude, elevation, -centrelineBaseline, 0.0), time)
            val northBackwardX = offsetX
            val northBackwardY = offsetY
            if (!eastForward || !eastBackward || !northForward || !northBackward) return null
            // The columns of the Jacobian: how the offset changes per metre east and per metre north.
            val a = (eastForwardX - eastBackwardX) / (2.0 * centrelineBaseline)
            val c = (eastForwardY - eastBackwardY) / (2.0 * centrelineBaseline)
            val b = (northForwardX - northBackwardX) / (2.0 * centrelineBaseline)
            val d = (northForwardY - northBackwardY) / (2.0 * centrelineBaseline)
            val determinant = a * d - b * c
            if (!(abs(determinant) > 1.0e-18)) return null
            val stepEast = -(d * fx - b * fy) / determinant
            val stepNorth = -(-c * fx + a * fy) / determinant
            if (!stepEast.isFinite() || !stepNorth.isFinite() || sqrt(stepEast * stepEast + stepNorth * stepNorth) > maximumStep) return null
            val moved = moved(latitude, longitude, elevation, stepNorth, stepEast)
            latitude = moved.latitude
            longitude = moved.longitude
            geometry = SatelliteObserverGeometry(Observer(moved, observer.atmosphere))
        }
        val measured = measure(geometry, time) ?: return null
        if (!(measured.separation <= centrelineTolerance)) return null
        val here = SatelliteFrames.earthFixedPosition(observer.location)
        val there = SatelliteFrames.earthFixedPosition(GeographicLocation(latitude, longitude, elevation))
        val dx = there.x - here.x
        val dy = there.y - here.y
        val dz = there.z - here.z
        val east = site.eastX * dx + site.eastY * dy
        val north = site.northX * dx + site.northY * dy + site.northZ * dz
        val distance = sqrt(east * east + north * north)
        if (distance > maxGround) return null
        val bearing = if (distance > 0.0) AstroMath.normalizeDegrees(AstroMath.atan2Deg(east, north)) else 0.0
        val place = SatelliteTransitCentreline(
            location = GeographicLocation(latitude, longitude, elevation),
            distanceMeters = distance,
            bearing = bearing,
            separation = measured.separation,
        )
        return Centreline(place, measured)
    }

    /** The observer geometry of a place [north] and [east] metres from the one at [latitude], [longitude]. */
    private fun displaced(latitude: Double, longitude: Double, elevation: Double, north: Double, east: Double): SatelliteObserverGeometry =
        SatelliteObserverGeometry(Observer(moved(latitude, longitude, elevation, north, east), observer.atmosphere))

    /** The place [north] and [east] metres from the one at [latitude], [longitude] (a step on the ellipsoid's local grid). */
    private fun moved(latitude: Double, longitude: Double, elevation: Double, north: Double, east: Double): GeographicLocation {
        val eccentricitySquared = SatelliteFrames.earthFlattening * (2.0 - SatelliteFrames.earthFlattening)
        val sine = AstroMath.sinDeg(latitude)
        val factor = 1.0 - eccentricitySquared * sine * sine
        val equatorial = SatelliteFrames.earthEquatorialRadiusKilometers * 1000.0
        val primeVertical = equatorial / sqrt(factor)
        val meridional = equatorial * (1.0 - eccentricitySquared) / (factor * sqrt(factor))
        val cosine = max(AstroMath.cosDeg(latitude), 1.0e-6)
        return GeographicLocation(
            latitude + AstroMath.degrees(north / meridional),
            longitude + AstroMath.degrees(east / (primeVertical * cosine)),
            elevation,
        )
    }

    private companion object {
        const val failedSeparation: Double = 1_000.0
        const val coarseStep: Double = 2.0
        const val timeTolerance: Double = 0.01
        const val speedHalfStep: Double = 0.5
        const val shortPass: Double = 1_800.0
        const val altitudeMargin: Double = 0.5
        const val coarseBodyRadius: Double = 0.3
        const val reachMargin: Double = 0.02
        const val centrelineBaseline: Double = 1_000.0
        const val centrelineIterations: Int = 3
        const val centrelineConvergence: Double = 1.0e-6
        const val centrelineTolerance: Double = 0.01
        const val maximumStep: Double = 1_000_000.0
    }
}

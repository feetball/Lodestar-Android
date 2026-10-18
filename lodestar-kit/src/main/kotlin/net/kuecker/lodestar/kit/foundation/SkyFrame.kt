package net.kuecker.lodestar.kit.foundation

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.time.Instant
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Proper motion of a star in milliarcseconds per year, J2000 catalog convention.
 *
 * Construction (`ProperMotion(raMilliarcsecondsPerYear, decMilliarcsecondsPerYear)`) replaces
 * non-finite components with 0; deserialization stores the decoded values as they are.
 *
 * @property raMilliarcsecondsPerYear μα* = μα · cos δ (as in Hipparcos), mas/yr.
 * @property decMilliarcsecondsPerYear μδ, mas/yr.
 */
@Serializable
@ConsistentCopyVisibility
data class ProperMotion private constructor(
    val raMilliarcsecondsPerYear: Double,
    val decMilliarcsecondsPerYear: Double,
) {
    /**
     * Applies the motion to a J2000.0 catalog place over [years] Julian years, working on the
     * unit vector so there is no singularity at the poles.
     */
    fun apply(position: EquatorialCoordinate, years: Double): EquatorialCoordinate =
        EquatorialCoordinate(applyToUnitVector(position, years))

    /** The unit vector of [position] moved by this proper motion over [years] Julian years. */
    internal fun applyToUnitVector(position: EquatorialCoordinate, years: Double): Vector3 {
        val masToRadians = 1 / (AstroMath.arcsecondsPerRadian * 1000)
        val sa = AstroMath.sinDeg(position.rightAscension)
        val ca = AstroMath.cosDeg(position.rightAscension)
        val sd = AstroMath.sinDeg(position.declination)
        val cd = AstroMath.cosDeg(position.declination)
        val towardsEast = Vector3(-sa, ca, 0.0)
        val towardsNorth = Vector3(-sd * ca, -sd * sa, cd)
        val displacement = towardsEast * (raMilliarcsecondsPerYear * masToRadians * years) +
            towardsNorth * (decMilliarcsecondsPerYear * masToRadians * years)
        return (position.unitVector + displacement).normalized
    }

    companion object {
        /** Creates a proper motion, replacing non-finite components with 0. */
        operator fun invoke(raMilliarcsecondsPerYear: Double, decMilliarcsecondsPerYear: Double): ProperMotion =
            ProperMotion(
                if (raMilliarcsecondsPerYear.isFinite()) raMilliarcsecondsPerYear else 0.0,
                if (decMilliarcsecondsPerYear.isFinite()) decMilliarcsecondsPerYear else 0.0,
            )
    }
}

/**
 * Where and under which conditions the sky is observed.
 *
 * @property location The observer's place on the Earth.
 * @property atmosphere The ambient conditions that scale refraction.
 */
@Serializable
data class Observer(
    val location: GeographicLocation,
    @Required val atmosphere: Atmosphere = Atmosphere.standard,
)

/**
 * Everything needed to turn catalog or ephemeris positions into horizontal coordinates for one
 * instant and observer. Building a frame is cheap (a few microseconds); reuse one frame for all
 * objects drawn at that instant.
 *
 * Pipeline for fixed objects (stars, deep-sky objects): J2000 catalog place → proper motion →
 * annual aberration → precession → nutation → (apparent place of date) → local sidereal time and
 * latitude → horizontal → optional refraction. Light deflection, annual parallax, diurnal
 * aberration and polar motion are neglected (all ≤ 1″).
 *
 * Immutable; instances have identity semantics (no `equals`), as the Swift struct is not `Equatable`.
 *
 * @property observer The observer this frame was built for.
 */
class SkyFrame(jd: JulianDate, val observer: Observer) {
    /** The instant as a point in time. */
    val date: Instant = jd.date

    /** The instant as a Julian Date. */
    val julianDate: JulianDate = jd

    /** Nutation in longitude and obliquity at this instant. */
    val nutation: Nutation

    /** Mean obliquity of date (degrees). */
    val meanObliquity: Double

    /** True obliquity of date (degrees). */
    val trueObliquity: Double

    /** Greenwich apparent sidereal time (degrees). */
    val greenwichApparentSiderealTime: Double

    /** Local apparent sidereal time (degrees). */
    val localApparentSiderealTime: Double

    /** J2000 → mean equator & equinox of date. */
    val precessionMatrix: Matrix3

    /** J2000 → true equator & equinox of date. */
    val precessionNutationMatrix: Matrix3

    /** True equatorial of date → local ENU horizon frame. */
    val equatorialOfDateToHorizontal: Matrix3

    /** J2000 → local ENU horizon frame (precession, nutation and Earth rotation combined). */
    val j2000ToHorizontal: Matrix3

    /** Earth's orbital velocity β = v/c in the J2000 frame (for annual aberration). */
    val earthVelocity: Vector3

    /** Observer's geocentric position in AU, true equator & equinox of date. */
    val observerPositionOfDate: Vector3

    init {
        val nutation = Nutation(jd)
        this.nutation = nutation
        val meanObliquity = Obliquity.mean(jd)
        this.meanObliquity = meanObliquity
        val trueObliquity = meanObliquity + nutation.obliquity
        this.trueObliquity = trueObliquity
        val gast = SiderealTime.greenwichApparent(jd, nutation, trueObliquity)
        this.greenwichApparentSiderealTime = gast
        val last = AstroMath.normalizeDegrees(gast + observer.location.longitude)
        this.localApparentSiderealTime = last

        val precession = Precession.matrixFromJ2000(jd)
        this.precessionMatrix = precession
        val precessionNutation = nutation.matrix(meanObliquity) * precession
        this.precessionNutationMatrix = precessionNutation
        val toHorizontal = CoordinateTransforms.localEquatorialToHorizontal(observer.location.latitude) *
            Matrix3.frameRotationZ(AstroMath.radians(last))
        this.equatorialOfDateToHorizontal = toHorizontal
        this.j2000ToHorizontal = toHorizontal * precessionNutation
        this.earthVelocity = Aberration.earthVelocity(jd)
        this.observerPositionOfDate = geocentricObserverPosition(observer.location, last)
    }

    /** Creates the frame for a point in time (Swift `init(date:observer:)`). */
    constructor(date: Instant, observer: Observer) : this(JulianDate(date), observer)

    // MARK: Fixed objects

    /** Apparent place (true equator & equinox of date, geocentric) of a J2000 catalog position. */
    fun apparentPlace(j2000: EquatorialCoordinate, properMotion: ProperMotion? = null): EquatorialCoordinate =
        EquatorialCoordinate(precessionNutationMatrix * aberratedDirection(j2000, properMotion))

    /** Horizontal coordinates of a J2000 catalog position, optionally including refraction. */
    fun horizontal(
        j2000: EquatorialCoordinate,
        properMotion: ProperMotion? = null,
        refraction: Boolean = true,
    ): HorizontalCoordinate {
        val geometric = HorizontalCoordinate(j2000ToHorizontal * aberratedDirection(j2000, properMotion))
        return if (refraction) refracted(geometric, observer.atmosphere) else geometric
    }

    /**
     * Fast path for rendering many objects: ENU unit vector of a J2000 unit vector, with
     * aberration but without proper motion or refraction.
     */
    fun horizontalVector(j2000UnitVector: Vector3): Vector3 =
        j2000ToHorizontal * Aberration.apply(j2000UnitVector, earthVelocity)

    // MARK: Solar-system bodies

    /**
     * Topocentric apparent place (true equator & equinox of date) of a body whose geocentric
     * J2000 position (AU) already includes light-time and aberration.
     */
    fun topocentricEquatorial(geocentricJ2000: Vector3): EquatorialCoordinate =
        EquatorialCoordinate(precessionNutationMatrix * geocentricJ2000 - observerPositionOfDate)

    /**
     * Topocentric horizontal coordinates of a body whose geocentric J2000 position (AU) already
     * includes light-time and aberration.
     */
    fun horizontal(geocentricJ2000: Vector3, refraction: Boolean = true): HorizontalCoordinate {
        val topocentric = precessionNutationMatrix * geocentricJ2000 - observerPositionOfDate
        val geometric = HorizontalCoordinate(equatorialOfDateToHorizontal * topocentric)
        return if (refraction) refracted(geometric, observer.atmosphere) else geometric
    }

    // MARK: Celestial poles

    /** Horizontal coordinates of the true celestial pole of date. */
    fun horizontalOfCelestialPole(north: Boolean, refraction: Boolean = true): HorizontalCoordinate {
        val pole = Vector3(0.0, 0.0, if (north) 1.0 else -1.0)
        val geometric = HorizontalCoordinate(equatorialOfDateToHorizontal * pole)
        return if (refraction) refracted(geometric, observer.atmosphere) else geometric
    }

    // MARK: Inverse and helpers

    /**
     * The J2000 position seen in direction [horizontal] (e.g. "what is the camera pointing at").
     * Removes refraction when [refracted] is true and first-order aberration; ignores parallax.
     */
    fun j2000(horizontal: HorizontalCoordinate, refracted: Boolean): EquatorialCoordinate {
        val altitude = if (refracted) {
            Refraction.trueAltitude(horizontal.altitude, observer.atmosphere)
        } else {
            horizontal.altitude
        }
        val geometric = HorizontalCoordinate(horizontal.azimuth, altitude)
        val apparentJ2000 = j2000ToHorizontal.transposed * geometric.unitVector
        return EquatorialCoordinate(Aberration.remove(apparentJ2000, earthVelocity))
    }

    /** Local hour angle in degrees, (-180, 180], of an apparent right ascension of date. */
    fun hourAngle(apparentRightAscension: Double): Double =
        AstroMath.normalizeSignedDegrees(localApparentSiderealTime - apparentRightAscension)

    /** Julian years of TT elapsed since J2000.0 (the catalog epoch). */
    val yearsSinceJ2000: Double get() = (julianDate.tt - JulianDate.j2000) / 365.25

    private fun aberratedDirection(j2000: EquatorialCoordinate, properMotion: ProperMotion?): Vector3 {
        val mean = properMotion?.applyToUnitVector(j2000, yearsSinceJ2000) ?: j2000.unitVector
        return Aberration.apply(mean, earthVelocity)
    }

    companion object {
        /** Earth equatorial radius used for the observer's geocentric position (km). */
        const val earthEquatorialRadiusKilometers: Double = 6378.14

        /** Kilometres per astronomical unit. */
        const val kilometersPerAU: Double = 149_597_870.7

        /** [horizontal] with its altitude raised by atmospheric refraction. */
        fun refracted(horizontal: HorizontalCoordinate, atmosphere: Atmosphere): HorizontalCoordinate =
            HorizontalCoordinate(
                horizontal.azimuth,
                Refraction.apparentAltitude(horizontal.altitude, atmosphere),
            )

        /** Observer's geocentric position (Meeus ch. 11) in AU in the true equatorial frame of date. */
        internal fun geocentricObserverPosition(location: GeographicLocation, localSiderealTime: Double): Vector3 {
            val flattening = 1 / 298.257
            val axisRatio = 1 - flattening
            val latitude = AstroMath.radians(location.latitude)
            val u = atan(axisRatio * tan(latitude))
            val heightRatio = location.elevation / (earthEquatorialRadiusKilometers * 1000)
            val rhoSin = axisRatio * sin(u) + heightRatio * sin(latitude)
            val rhoCos = cos(u) + heightRatio * cos(latitude)
            val scale = earthEquatorialRadiusKilometers / kilometersPerAU
            val theta = AstroMath.radians(localSiderealTime)
            return Vector3(rhoCos * cos(theta), rhoCos * sin(theta), rhoSin) * scale
        }
    }
}

package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.ephemeris.*
import net.kuecker.lodestar.kit.foundation.*
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The frames shared by every target that is followed for one observer (Swift `HorizonSkyFrames`): one
 * [SkyFrame] per six hours, from which the Earth's rotation carries a target across the sky.
 *
 * It is what [RiseSetSampler] keeps privately, held apart so that a batch of targets builds each frame once.
 * Not thread safe.
 */
internal class HorizonSkyFrames(val observer: Observer) {
    /** Everything that is fixed while the Earth turns, for one six-hour stretch. */
    class Chunk(
        val referenceTime: Double,
        /** Local apparent sidereal time at [referenceTime], in degrees. */
        val referenceSiderealAngle: Double,
        /** Rotation from the Earth-fixed equatorial frame (x towards the meridian) to ENU. */
        val localToHorizontal: Matrix3,
        /** J2000 mean equatorial to true equatorial of date. */
        val precessionNutation: Matrix3,
        /** The observer's geocentric position in the Earth-fixed equatorial frame (AU). */
        val observerEarthFixed: Vector3,
        /** The frame of the stretch's middle, for the apparent places of fixed targets. */
        val frame: SkyFrame,
    )

    private val chunks = HashMap<Int, Chunk>()
    private var lastIndex = 0
    private var lastChunk: Chunk? = null

    /** The chunk that contains [time] (seconds since the Foundation reference date). */
    fun chunk(time: Double): Chunk {
        val index = floor(time / chunkLength).toInt()
        val last = lastChunk
        if (last != null && lastIndex == index) return last
        val cached = chunks[index]
        if (cached != null) {
            lastChunk = cached
            lastIndex = index
            return cached
        }
        val referenceTime = (index.toDouble() + 0.5) * chunkLength
        val frame = SkyFrame(instantFromTimeIntervalSinceReferenceDate(referenceTime), observer)
        val siderealAngle = frame.localApparentSiderealTime
        val toEarthFixed = Matrix3.frameRotationZ(AstroMath.radians(siderealAngle))
        val chunk = Chunk(
            referenceTime = referenceTime,
            referenceSiderealAngle = siderealAngle,
            localToHorizontal = frame.equatorialOfDateToHorizontal * toEarthFixed.transposed,
            precessionNutation = frame.precessionNutationMatrix,
            observerEarthFixed = toEarthFixed * frame.observerPositionOfDate,
            frame = frame,
        )
        chunks[index] = chunk
        lastChunk = chunk
        lastIndex = index
        return chunk
    }

    companion object {
        const val chunkLength: Double = 6 * 3600.0

        /** Times further than this (about 300 000 years) from the reference date are not evaluated. */
        const val maximumTime: Double = 1e13

        /** The rate at which local sidereal time advances, in degrees per second of UT. */
        const val siderealDegreesPerSecond: Double = 360.985_647_366_29 / JulianDate.secondsPerDay
    }
}

/**
 * Where one target is in the sky at a time (Swift `HorizonSkyTrack`): [evaluate] computes the geometric
 * altitude, the azimuth and, for the Moon, the limb allowance, and leaves them in the properties.
 *
 * The altitude and azimuth are not a number for a time that is not finite. Not thread safe.
 */
internal abstract class HorizonSkyTrack {
    /** Geometric topocentric altitude of the target's centre at the last [evaluate], in degrees. */
    var altitude: Double = Double.NaN
        protected set

    /** Azimuth in degrees, [0, 360), at the last [evaluate]; 0 when it was not asked for. */
    var azimuth: Double = Double.NaN
        protected set

    /** Degrees added to the centre altitude to reach the limb that defines a rise or set (the Moon); else 0. */
    var limbAllowance: Double = 0.0
        protected set

    /**
     * Computes the position at [time] (seconds since the Foundation reference date); [wantsAzimuth] asks for the
     * azimuth too.
     */
    abstract fun evaluate(time: Double, wantsAzimuth: Boolean)

    /** Leaves the altitude and, if wanted, the azimuth of the ENU direction (east, north, up) in the properties. */
    protected fun store(east: Double, north: Double, up: Double, wantsAzimuth: Boolean) {
        altitude = AstroMath.atan2Deg(up, sqrt(east * east + north * north))
        azimuth = if (wantsAzimuth) AstroMath.normalizeDegrees(AstroMath.atan2Deg(east, north)) else 0.0
    }

    /** Marks the properties as not computed. */
    protected fun invalidate() {
        altitude = Double.NaN
        azimuth = Double.NaN
        limbAllowance = 0.0
    }
}

/** A fixed object (a star or deep-sky object) given by its J2000 place and optional proper motion. */
internal class HorizonFixedTrack(
    private val frames: HorizonSkyFrames,
    private val position: EquatorialCoordinate,
    private val properMotion: ProperMotion?,
) : HorizonSkyTrack() {
    private var directionChunk: HorizonSkyFrames.Chunk? = null
    private var direction: Vector3 = Vector3.zero

    override fun evaluate(time: Double, wantsAzimuth: Boolean) {
        limbAllowance = 0.0
        if (!(time.isFinite() && abs(time) < HorizonSkyFrames.maximumTime)) {
            invalidate()
            return
        }
        val chunk = frames.chunk(time)
        if (directionChunk !== chunk) {
            direction = chunk.frame.apparentPlace(position, properMotion).unitVector
            directionChunk = chunk
        }
        val siderealAngle = chunk.referenceSiderealAngle +
            HorizonSkyFrames.siderealDegreesPerSecond * (time - chunk.referenceTime)
        val angle = AstroMath.radians(siderealAngle)
        val c = cos(angle)
        val s = sin(angle)
        val rotatedX = c * direction.x + s * direction.y
        val rotatedY = -s * direction.x + c * direction.y
        val rotatedZ = direction.z
        val l0 = chunk.localToHorizontal.r0
        val l1 = chunk.localToHorizontal.r1
        val l2 = chunk.localToHorizontal.r2
        store(
            l0.x * rotatedX + l0.y * rotatedY + l0.z * rotatedZ,
            l1.x * rotatedX + l1.y * rotatedY + l1.z * rotatedZ,
            l2.x * rotatedX + l2.y * rotatedY + l2.z * rotatedZ,
            wantsAzimuth,
        )
    }
}

/**
 * The Sun, the Moon or a planet, from its geocentric apparent position interpolated between ephemeris nodes
 * (hourly for the Moon, three-hourly for the others, cubic Lagrange interpolation as in [RiseSetSampler]).
 */
internal class HorizonBodyTrack(
    private val frames: HorizonSkyFrames,
    private val body: SolarSystemBody,
) : HorizonSkyTrack() {
    private val nodeSpacing: Double = if (body != SolarSystemBody.MOON) 3 * 3600.0 else 3600.0
    private val nodes = HashMap<Long, Vector3>()
    private var windowBase = 0L
    private var window: Array<Vector3>? = null

    override fun evaluate(time: Double, wantsAzimuth: Boolean) {
        limbAllowance = 0.0
        if (!(time.isFinite() && abs(time) < HorizonSkyFrames.maximumTime)) {
            invalidate()
            return
        }
        val chunk = frames.chunk(time)
        val siderealAngle = chunk.referenceSiderealAngle +
            HorizonSkyFrames.siderealDegreesPerSecond * (time - chunk.referenceTime)
        val angle = AstroMath.radians(siderealAngle)
        val c = cos(angle)
        val s = sin(angle)

        val nodes = window(time)
        val u = time / nodeSpacing - windowBase.toDouble()
        val w0 = -u * (u - 1) * (u - 2) / 6
        val w1 = (u + 1) * (u - 1) * (u - 2) / 2
        val w2 = -(u + 1) * u * (u - 2) / 2
        val w3 = (u + 1) * u * (u - 1) / 6
        val n0 = nodes[0]
        val n1 = nodes[1]
        val n2 = nodes[2]
        val n3 = nodes[3]
        val geocentricX = n0.x * w0 + n1.x * w1 + n2.x * w2 + n3.x * w3
        val geocentricY = n0.y * w0 + n1.y * w1 + n2.y * w2 + n3.y * w3
        val geocentricZ = n0.z * w0 + n1.z * w1 + n2.z * w2 + n3.z * w3

        val p0 = chunk.precessionNutation.r0
        val p1 = chunk.precessionNutation.r1
        val p2 = chunk.precessionNutation.r2
        val equatorialX = p0.x * geocentricX + p0.y * geocentricY + p0.z * geocentricZ
        val equatorialY = p1.x * geocentricX + p1.y * geocentricY + p1.z * geocentricZ
        val equatorialZ = p2.x * geocentricX + p2.y * geocentricY + p2.z * geocentricZ

        val observerFixed = chunk.observerEarthFixed
        val relativeX = c * equatorialX + s * equatorialY - observerFixed.x
        val relativeY = -s * equatorialX + c * equatorialY - observerFixed.y
        val relativeZ = equatorialZ - observerFixed.z

        val l0 = chunk.localToHorizontal.r0
        val l1 = chunk.localToHorizontal.r1
        val l2 = chunk.localToHorizontal.r2
        val x = l0.x * relativeX + l0.y * relativeY + l0.z * relativeZ
        val y = l1.x * relativeX + l1.y * relativeY + l1.z * relativeZ
        val z = l2.x * relativeX + l2.y * relativeY + l2.z * relativeZ
        store(x, y, z, wantsAzimuth)
        if (body == SolarSystemBody.MOON) {
            val distanceKilometers = sqrt(x * x + y * y + z * z) * SkyFrame.kilometersPerAU
            limbAllowance = AstroMath.asinDeg(body.meanRadiusKilometers / distanceKilometers)
        }
    }

    /** The four nodes `base - 1 ... base + 2` around [time]; the index `base` is left in [windowBase]. */
    private fun window(time: Double): Array<Vector3> {
        val base = floor(time / nodeSpacing).toLong()
        val cached = window
        if (cached != null && windowBase == base) return cached
        val fresh = arrayOf(node(base - 1), node(base), node(base + 1), node(base + 2))
        window = fresh
        windowBase = base
        return fresh
    }

    private fun node(index: Long): Vector3 {
        val cached = nodes[index]
        if (cached != null) return cached
        val date = instantFromTimeIntervalSinceReferenceDate(index.toDouble() * nodeSpacing)
        val position = Ephemeris.geocentricJ2000(body, JulianDate(date))
        nodes[index] = position
        return position
    }
}

/** A target given by a function of time that returns its geometric (or refracted) horizontal coordinates. */
internal class HorizonFunctionTrack(
    private val position: (Instant) -> HorizontalCoordinate,
) : HorizonSkyTrack() {
    override fun evaluate(time: Double, wantsAzimuth: Boolean) {
        limbAllowance = 0.0
        if (!time.isFinite()) {
            invalidate()
            return
        }
        val horizontal = position(instantFromTimeIntervalSinceReferenceDate(time))
        altitude = horizontal.altitude
        azimuth = horizontal.azimuth
    }
}

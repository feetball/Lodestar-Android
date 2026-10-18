package net.kuecker.lodestar.kit.ephemeris

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometric altitude of one target for one observer, cheap to evaluate at many nearby instants.
 *
 * A rise/set search samples the altitude hundreds of times within a day. Rebuilding a full
 * [SkyFrame] and recomputing the ephemeris at every sample would dominate its cost, so this
 * sampler reuses the slowly changing parts:
 * - A [SkyFrame] is built once per six hours; its precession–nutation matrix and (for stars) the
 *   apparent place are held fixed in between, which changes them by less than 0.02″. The Earth's
 *   rotation, which is what moves the target across the sky, is applied exactly at every sample.
 * - Solar-system bodies are evaluated on a grid of nodes (hourly for the Moon, three-hourly for the
 *   Sun and planets) and interpolated with cubic Lagrange polynomials, whose error stays below
 *   0.001″ at those spacings.
 *
 * The sampler caches as it goes, so it is a class and is not thread safe.
 *
 * In Swift this is `RiseSetCalculator.Sampler`; Kotlin cannot add nested types from another file, so it is a
 * top-level internal class of the same package. The per-sample arithmetic is written out on plain `Double`s
 * (the same operations in the same order as the Swift matrix and vector products), so a sample allocates
 * nothing.
 */
internal class RiseSetSampler(private val target: Target, private val observer: Observer) :
    RiseSetProfile.Evaluator {
    /** What the sampler follows. */
    sealed interface Target {
        /** A solar-system body. */
        data class Body(val body: SolarSystemBody) : Target

        /** A fixed object given by its J2000 catalog place and optional proper motion. */
        data class Fixed(val position: EquatorialCoordinate, val properMotion: ProperMotion?) : Target
    }

    /** Everything that is fixed while the Earth turns, for one six-hour stretch. */
    private class Chunk(
        val referenceTime: Double,
        /** Local apparent sidereal time at `referenceTime`, in degrees. */
        val referenceSiderealAngle: Double,
        /** Rotation from the Earth-fixed equatorial frame (x towards the meridian) to ENU. */
        val localToHorizontal: Matrix3,
        /** J2000 mean equatorial to true equatorial of date. */
        val precessionNutation: Matrix3,
        /** The observer's geocentric position in the Earth-fixed equatorial frame (AU). */
        val observerEarthFixed: Vector3,
        /** For a fixed target, its apparent direction in the true equatorial frame of date. */
        val fixedDirection: Vector3,
    )

    private val nodeSpacing: Double =
        if (target is Target.Body && target.body != SolarSystemBody.MOON) 3 * 3600.0 else 3600.0
    private val chunks = HashMap<Int, Chunk>()
    private val nodes = HashMap<Long, Vector3>()

    // The chunk of the last sample and the four interpolation nodes around it: consecutive samples almost
    // always fall into the same ones, so the maps are only consulted when the time moves on.
    private var lastChunkIndex = 0
    private var lastChunk: Chunk? = null
    private var windowBase = 0L
    private var window: Array<Vector3>? = null

    /** The limb allowance of the sample computed last (zero unless the target is the Moon). */
    private var limbAllowance = 0.0

    /**
     * The altitude of the target at [time] (seconds since the Foundation reference date). The result
     * is not a number for a time that is not finite or lies more than 300 000 years from the reference date.
     */
    override fun sample(time: Double): RiseSetSample {
        val centre = centreAltitudeAt(time)
        return RiseSetSample(centre, limbAllowance)
    }

    override fun centreAltitude(time: Double): Double = centreAltitudeAt(time)

    override fun limbAltitude(time: Double): Double {
        val centre = centreAltitudeAt(time)
        return centre + limbAllowance
    }

    /** Computes the centre altitude at [time] and leaves the matching limb allowance in [limbAllowance]. */
    private fun centreAltitudeAt(time: Double): Double {
        limbAllowance = 0.0
        if (!(time.isFinite() && abs(time) < maximumTime)) return Double.NaN
        val chunk = chunk(time)
        val siderealAngle = chunk.referenceSiderealAngle +
            siderealDegreesPerSecond * (time - chunk.referenceTime)
        val angle = AstroMath.radians(siderealAngle)
        return when (target) {
            is Target.Fixed -> fixedAltitude(chunk, cos(angle), sin(angle))
            is Target.Body -> bodyAltitude(target.body, time, chunk, cos(angle), sin(angle))
        }
    }

    // The Earth's rotation is `Matrix3.frameRotationZ(angle)`, whose rows are (c, s, 0), (-s, c, 0) and
    // (0, 0, 1) with c = cos(angle) and s = sin(angle); the products below are the Swift matrix–vector
    // products written out on plain Doubles.

    /** The altitude of the fixed target: the chunk's apparent direction, turned with the Earth. */
    private fun fixedAltitude(chunk: Chunk, c: Double, s: Double): Double {
        val direction = chunk.fixedDirection
        val rotatedX = c * direction.x + s * direction.y
        val rotatedY = -s * direction.x + c * direction.y
        val rotatedZ = direction.z
        val l0 = chunk.localToHorizontal.r0
        val l1 = chunk.localToHorizontal.r1
        val l2 = chunk.localToHorizontal.r2
        return altitude(
            l0.x * rotatedX + l0.y * rotatedY + l0.z * rotatedZ,
            l1.x * rotatedX + l1.y * rotatedY + l1.z * rotatedZ,
            l2.x * rotatedX + l2.y * rotatedY + l2.z * rotatedZ,
        )
    }

    /** The topocentric altitude of [body] from its interpolated geocentric place; sets [limbAllowance] for the Moon. */
    private fun bodyAltitude(body: SolarSystemBody, time: Double, chunk: Chunk, c: Double, s: Double): Double {
        val nodes = window(time, body)
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
        val centre = altitude(x, y, z)
        if (body != SolarSystemBody.MOON) return centre
        // The Moon's horizon event is its upper limb, so its apparent semi-diameter matters.
        val distanceKilometers = sqrt(x * x + y * y + z * z) * SkyFrame.kilometersPerAU
        limbAllowance = AstroMath.asinDeg(body.meanRadiusKilometers / distanceKilometers)
        return centre
    }

    // MARK: Frames

    private fun chunk(time: Double): Chunk {
        val index = floor(time / chunkLength).toInt()
        val last = lastChunk
        if (last != null && lastChunkIndex == index) return last
        val cached = chunks[index]
        if (cached != null) {
            lastChunk = cached
            lastChunkIndex = index
            return cached
        }

        val referenceTime = (index.toDouble() + 0.5) * chunkLength
        val frame = SkyFrame(instantFromTimeIntervalSinceReferenceDate(referenceTime), observer)
        val siderealAngle = frame.localApparentSiderealTime
        val toEarthFixed = Matrix3.frameRotationZ(AstroMath.radians(siderealAngle))
        var fixedDirection = Vector3.zero
        if (target is Target.Fixed) {
            fixedDirection = frame.apparentPlace(target.position, target.properMotion).unitVector
        }
        val chunk = Chunk(
            referenceTime = referenceTime,
            referenceSiderealAngle = siderealAngle,
            localToHorizontal = frame.equatorialOfDateToHorizontal * toEarthFixed.transposed,
            precessionNutation = frame.precessionNutationMatrix,
            observerEarthFixed = toEarthFixed * frame.observerPositionOfDate,
            fixedDirection = fixedDirection,
        )
        chunks[index] = chunk
        lastChunk = chunk
        lastChunkIndex = index
        return chunk
    }

    // MARK: Interpolated ephemeris

    /**
     * The four nodes `base - 1 ... base + 2` around [time] of [body]'s geocentric apparent position
     * (J2000 equatorial, AU), and the index `base` in [windowBase]; the position at [time] is their
     * cubic Lagrange interpolation.
     */
    private fun window(time: Double, body: SolarSystemBody): Array<Vector3> {
        val base = floor(time / nodeSpacing).toLong()
        val cached = window
        if (cached != null && windowBase == base) return cached
        val nodes = arrayOf(node(base - 1, body), node(base, body), node(base + 1, body), node(base + 2, body))
        window = nodes
        windowBase = base
        return nodes
    }

    private fun node(index: Long, body: SolarSystemBody): Vector3 {
        val cached = nodes[index]
        if (cached != null) return cached
        val date = instantFromTimeIntervalSinceReferenceDate(index.toDouble() * nodeSpacing)
        val position = Ephemeris.geocentricJ2000(body, JulianDate(date))
        nodes[index] = position
        return position
    }

    private companion object {
        const val chunkLength: Double = 6 * 3600.0

        /** Times further than this (about 300 000 years) from the reference date are not evaluated. */
        const val maximumTime: Double = 1e13

        /** The rate at which local sidereal time advances, in degrees per second of UT. */
        const val siderealDegreesPerSecond: Double = 360.985_647_366_29 / JulianDate.secondsPerDay

        fun altitude(x: Double, y: Double, z: Double): Double =
            AstroMath.atan2Deg(z, sqrt(x * x + y * y))
    }
}

package net.kuecker.lodestar.kit.horizon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.JsonElement
import net.kuecker.lodestar.kit.foundation.*
import net.kuecker.lodestar.kit.pointing.*
import kotlin.math.abs
import kotlin.math.floor

/**
 * A stretch of azimuth, measured clockwise (from north through east) from [start].
 *
 * Construction (`AzimuthRange(start, length)`) normalizes [start] to [0, 360) and clamps [length] to
 * [0, 360]; a non-finite value becomes 0. A range may run through north (`start = 350`, `length = 20`).
 *
 * @property start The first azimuth of the range in degrees, [0, 360).
 * @property length The extent of the range in degrees, [0, 360]; 360 is the whole circle.
 */
@ConsistentCopyVisibility
data class AzimuthRange private constructor(val start: Double, val length: Double) {
    /** The last azimuth of the range in degrees, [0, 360). */
    val end: Double get() = AstroMath.normalizeDegrees(start + length)

    /** Whether [azimuth] (any angle, in degrees) lies in the range, its ends included. */
    fun contains(azimuth: Double): Boolean {
        if (!azimuth.isFinite()) return false
        return AstroMath.normalizeDegrees(azimuth - start) <= length
    }

    companion object {
        /** The whole circle. */
        val full: AzimuthRange = AzimuthRange(0.0, 360.0)

        /** Creates a range, normalizing [start] to [0, 360) and clamping [length] to [0, 360]. */
        operator fun invoke(start: Double, length: Double): AzimuthRange =
            AzimuthRange(
                if (start.isFinite()) AstroMath.normalizeDegrees(start) else 0.0,
                if (length.isFinite()) AstroMath.clamp(length, 0.0, 360.0) else 0.0,
            )

        /**
         * The range that runs clockwise from [start] to [end] (Swift `init(from:to:)`). Equal azimuths give
         * an empty range, not the whole circle; use [full] for that.
         */
        fun between(start: Double, end: Double): AzimuthRange =
            if (start.isFinite() && end.isFinite()) {
                invoke(start, AstroMath.normalizeDegrees(end - start))
            } else {
                invoke(0.0, 0.0)
            }
    }
}

/**
 * One point of the skyline drawn as a line: an azimuth and the altitude of the skyline there, both in
 * degrees. The azimuth is **not** wrapped, so a line that runs through north keeps increasing (350, 360, 370).
 *
 * @property azimuth Azimuth in degrees from north through east, as requested from [HorizonProfile.polyline].
 * @property altitude Altitude of the skyline in degrees.
 */
data class SkylinePoint(val azimuth: Double, val altitude: Double)

/**
 * The skyline of an observing site: the altitude at which the sky starts, for every azimuth.
 *
 * Trees, ridges and buildings hide the sky near the horizon. The profile stores the altitude of that
 * skyline at every whole degree of azimuth (360 values, azimuth 0° = north, increasing through east) and
 * interpolates linearly in between, wrapping from 359° to 0°. Altitudes are **apparent** (where the
 * obstacle is seen from the observer), between −10° and +60°, and rounded to 0.1°, so a profile that is
 * stored and loaded again is equal to itself. The flat default (0° everywhere) is [flat].
 *
 * A profile is a small immutable value: the JSON form is about 1.2 KB for a typical skyline, 750 bytes for a flat
 * one and never more than 1.9 KB, so one can be stored with every observing site. Its keys are `version` (1)
 * and `altitudeTenths`, an array of 360 integers, the altitudes in tenths of a degree, e.g.
 * `{"version":1,"altitudeTenths":[0,0,25,31,…]}`. Decoding checks the version, the length, that every number is an
 * integer, and the −100…600 range.
 *
 * The constructor is private; use [create], [constant] or [flat]. All edits return a new profile.
 *
 * @property altitudes The skyline altitude at azimuth 0°, 1°, … 359° in degrees; 360 multiples of 0.1.
 */
@Serializable(with = HorizonProfileSerializer::class)
@ConsistentCopyVisibility
data class HorizonProfile private constructor(val altitudes: List<Double>) {
    /** `true` when the skyline is at 0° all round, i.e. the profile changes nothing. */
    val isFlat: Boolean get() = altitudes.all { it == 0.0 }

    /** The highest skyline altitude in degrees. */
    val maximumAltitude: Double get() = altitudes.max()

    /** The lowest skyline altitude in degrees. */
    val minimumAltitude: Double get() = altitudes.min()

    /**
     * The skyline altitude in degrees at [azimuth] (any angle in degrees, wrapped into [0, 360)): the
     * linear interpolation between the two nearest whole degrees, which wraps from 359° to 0°. A
     * non-finite azimuth gives 0.
     */
    fun altitude(azimuth: Double): Double {
        if (!azimuth.isFinite()) return 0.0
        val wrapped = AstroMath.normalizeDegrees(azimuth)
        val lower = floor(wrapped).toInt()
        val fraction = wrapped - lower.toDouble()
        val first = altitudes[lower]
        if (fraction == 0.0) return first
        val second = altitudes[if (lower + 1 == binCount) 0 else lower + 1]
        return first + (second - first) * fraction
    }

    /**
     * The skyline as a line for drawing, from azimuth [from] to azimuth [to] (degrees; the azimuths of the
     * points are not wrapped, so `from = 340, to = 380` runs through north in increasing order).
     *
     * The line has a point at [from], at [to] and at every multiple of [step] in between. Because the skyline
     * is linear between whole degrees, the default [step] of 1° reproduces it exactly; a larger step gives a
     * coarser line. [step] is limited to 0.1…45; a span longer than 360° is cut to 360°; the whole circle
     * (`from = 0, to = 360`) has 361 points, the last one the same as the first. Returns an empty list when
     * [from] or [to] is not finite or beyond ±10⁹°, or [to] is smaller than [from].
     */
    fun polyline(from: Double = 0.0, to: Double = 360.0, step: Double = 1.0): List<SkylinePoint> {
        if (!(from.isFinite() && to.isFinite()) || abs(from) > 1e9 || abs(to) > 1e9 || to < from) return emptyList()
        val end = swiftMin(to, from + 360.0)
        val spacing = if (step.isFinite()) AstroMath.clamp(step, 0.1, 45.0) else 1.0
        val points = ArrayList<SkylinePoint>()
        points.add(SkylinePoint(from, altitude(from)))
        var index = floor(from / spacing).toLong() + 1
        while (index.toDouble() * spacing < end) {
            val azimuth = index.toDouble() * spacing
            points.add(SkylinePoint(azimuth, altitude(azimuth)))
            index += 1
        }
        if (end > from) points.add(SkylinePoint(end, altitude(end)))
        return points
    }

    // MARK: Editing

    /**
     * The profile with [delta] degrees added to the skyline in [range] (negative lowers it). The result is
     * limited to [lowestAltitude]…[highestAltitude]. A [delta] that is not finite changes nothing.
     */
    fun raised(delta: Double, range: AzimuthRange): HorizonProfile {
        if (!delta.isFinite()) return this
        val values = altitudes.toDoubleArray()
        for (index in values.indices) {
            if (range.contains(index.toDouble())) values[index] += delta
        }
        return fromClamped(values)
    }

    /**
     * The profile with the skyline in [range] set to [altitude] degrees (limited to
     * [lowestAltitude]…[highestAltitude]). A non-finite [altitude] changes nothing.
     */
    fun setting(altitude: Double, range: AzimuthRange): HorizonProfile {
        if (!altitude.isFinite()) return this
        val values = altitudes.toDoubleArray()
        for (index in values.indices) {
            if (range.contains(index.toDouble())) values[index] = altitude
        }
        return fromClamped(values)
    }

    /** The profile with the skyline in [range] taken from [other] and the rest kept. */
    fun replacing(range: AzimuthRange, other: HorizonProfile): HorizonProfile {
        val values = altitudes.toDoubleArray()
        for (index in values.indices) {
            if (range.contains(index.toDouble())) values[index] = other.altitudes[index]
        }
        return fromClamped(values)
    }

    /** The profile that is at every azimuth the higher of this skyline and [other]: both sets of obstacles. */
    fun maximum(other: HorizonProfile): HorizonProfile {
        val values = DoubleArray(binCount) { swiftMax(altitudes[it], other.altitudes[it]) }
        return fromClamped(values)
    }

    override fun toString(): String =
        "HorizonProfile(flat=$isFlat, minimum=$minimumAltitude, maximum=$maximumAltitude)"

    companion object {
        /** The number of stored altitudes: one per whole degree of azimuth. */
        const val binCount: Int = 360

        /** The lowest altitude, in degrees, a skyline may have. */
        const val lowestAltitude: Double = -10.0

        /** The highest altitude, in degrees, a skyline may have. */
        const val highestAltitude: Double = 60.0

        /** The version number of the JSON form. */
        const val formatVersion: Int = 1

        private const val lowestTenths = -100
        private const val highestTenths = 600

        /** The flat skyline: 0° at every azimuth. */
        val flat: HorizonProfile = HorizonProfile(List(binCount) { 0.0 })

        /**
         * The profile with the given skyline [altitudes] (degrees at azimuth 0°, 1°, … 359°), rounded to 0.1°;
         * `null` unless there are exactly 360 finite values between −10° and +60°.
         */
        fun create(altitudes: List<Double>): HorizonProfile? {
            if (altitudes.size != binCount) return null
            val tenths = IntArray(binCount)
            for (index in 0 until binCount) {
                val value = altitudes[index]
                if (!value.isFinite()) return null
                val scaled = (value * 10).roundedHalfAwayFromZero()
                if (!(scaled >= lowestTenths.toDouble() && scaled <= highestTenths.toDouble())) return null
                tenths[index] = scaled.toInt()
            }
            return fromTenths(tenths)
        }

        /** A skyline at the same [altitude] all round (limited to −10…+60°; not finite gives 0°). */
        fun constant(altitude: Double): HorizonProfile =
            fromClamped(DoubleArray(binCount) { if (altitude.isFinite()) altitude else 0.0 })

        /** The profile with altitudes of `tenths / 10` degrees; [tenths] must have 360 values in range. */
        internal fun fromTenths(tenths: IntArray): HorizonProfile? {
            if (tenths.size != binCount) return null
            if (tenths.any { it < lowestTenths || it > highestTenths }) return null
            return HorizonProfile(List(binCount) { tenths[it].toDouble() / 10.0 })
        }

        /** Rounds [values] (360 finite numbers) to 0.1° and limits them to the allowed range. */
        internal fun fromClamped(values: DoubleArray): HorizonProfile {
            val tenths = IntArray(binCount) { index ->
                val value = values[index]
                val scaled = if (value.isFinite()) (value * 10).roundedHalfAwayFromZero() else 0.0
                AstroMath.clamp(scaled, lowestTenths.toDouble(), highestTenths.toDouble()).toInt()
            }
            return HorizonProfile(List(binCount) { tenths[it].toDouble() / 10.0 })
        }

        /** The altitudes in tenths of a degree (the JSON numbers). */
        internal fun tenths(profile: HorizonProfile): List<Int> =
            profile.altitudes.map { (it * 10).roundedHalfAwayFromZero().toInt() }
    }
}

/**
 * Serializes a [HorizonProfile] as a JSON object with the keys `version` (1) and `altitudeTenths` (360
 * integers). Decoding reads the object itself, so unknown keys are ignored (like Swift's `JSONDecoder`); a
 * missing key, a value of the wrong type, another version, a number that is not an integer, a value outside
 * −100…600 or an array that is not 360 long throws a [SerializationException].
 */
internal object HorizonProfileSerializer : KSerializer<HorizonProfile> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("net.kuecker.lodestar.kit.horizon.HorizonProfile") {
            element<Int>("version")
            element("altitudeTenths", ListSerializer(Int.serializer()).descriptor)
        }

    override fun serialize(encoder: Encoder, value: HorizonProfile) {
        encoder.encodeStructure(descriptor) {
            encodeIntElement(descriptor, 0, HorizonProfile.formatVersion)
            encodeSerializableElement(descriptor, 1, ListSerializer(Int.serializer()), HorizonProfile.tenths(value))
        }
    }

    override fun deserialize(decoder: Decoder): HorizonProfile = decode(decoder.decodeJsonTree("horizon profile"))

    /** Decodes and validates a horizon profile from its JSON representation. */
    fun decode(element: JsonElement): HorizonProfile {
        val fields = element.toJsonObject("a horizon profile")
        val version = fields.requiredField("version").toStrictDouble("version")
        if (version != HorizonProfile.formatVersion.toDouble()) {
            throw SerializationException("Unsupported horizon profile version.")
        }
        val numbers = fields.requiredField("altitudeTenths").toJsonArray("altitudeTenths")
        if (numbers.size != HorizonProfile.binCount) {
            throw SerializationException("A horizon profile needs ${HorizonProfile.binCount} altitudes.")
        }
        val tenths = IntArray(HorizonProfile.binCount)
        for (index in tenths.indices) {
            val value = numbers[index].toStrictDouble("altitudeTenths[$index]")
            if (value != floor(value) || value < -100.0 || value > 600.0) {
                throw SerializationException("Altitude $index is not a whole number of tenths from -100 to 600.")
            }
            tenths[index] = value.toInt()
        }
        return HorizonProfile.fromTenths(tenths)
            ?: throw SerializationException("The altitudes do not describe a horizon profile.")
    }
}

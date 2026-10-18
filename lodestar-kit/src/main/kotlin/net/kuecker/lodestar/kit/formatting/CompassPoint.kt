package net.kuecker.lodestar.kit.formatting

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero

/**
 * One of the 16 points of the compass rose.
 *
 * Raw values count clockwise from north in steps of 22.5°, so [entries] is already in compass
 * order. The JSON form is the raw integer, as in the Swift original.
 */
@Serializable(with = CompassPointSerializer::class)
enum class CompassPoint(
    /** The position in compass order: 0 for north, 1 for north-northeast, and so on up to 15. */
    val rawValue: Int,
) {
    /** North, 0°. */
    NORTH(0),

    /** North-northeast, 22.5°. */
    NORTH_NORTHEAST(1),

    /** Northeast, 45°. */
    NORTHEAST(2),

    /** East-northeast, 67.5°. */
    EAST_NORTHEAST(3),

    /** East, 90°. */
    EAST(4),

    /** East-southeast, 112.5°. */
    EAST_SOUTHEAST(5),

    /** Southeast, 135°. */
    SOUTHEAST(6),

    /** South-southeast, 157.5°. */
    SOUTH_SOUTHEAST(7),

    /** South, 180°. */
    SOUTH(8),

    /** South-southwest, 202.5°. */
    SOUTH_SOUTHWEST(9),

    /** Southwest, 225°. */
    SOUTHWEST(10),

    /** West-southwest, 247.5°. */
    WEST_SOUTHWEST(11),

    /** West, 270°. */
    WEST(12),

    /** West-northwest, 292.5°. */
    WEST_NORTHWEST(13),

    /** Northwest, 315°. */
    NORTHWEST(14),

    /** North-northwest, 337.5°. */
    NORTH_NORTHWEST(15);

    /** The azimuth of the middle of this sector, in degrees. */
    val centerAzimuth: Double
        get() = rawValue.toDouble() * sectorWidth

    /** The conventional abbreviation, for example "NNE". */
    val abbreviation: String
        get() = when (this) {
            NORTH -> "N"
            NORTH_NORTHEAST -> "NNE"
            NORTHEAST -> "NE"
            EAST_NORTHEAST -> "ENE"
            EAST -> "E"
            EAST_SOUTHEAST -> "ESE"
            SOUTHEAST -> "SE"
            SOUTH_SOUTHEAST -> "SSE"
            SOUTH -> "S"
            SOUTH_SOUTHWEST -> "SSW"
            SOUTHWEST -> "SW"
            WEST_SOUTHWEST -> "WSW"
            WEST -> "W"
            WEST_NORTHWEST -> "WNW"
            NORTHWEST -> "NW"
            NORTH_NORTHWEST -> "NNW"
        }

    /** The full English name, for example "North-northeast". */
    val fullName: String
        get() = when (this) {
            NORTH -> "North"
            NORTH_NORTHEAST -> "North-northeast"
            NORTHEAST -> "Northeast"
            EAST_NORTHEAST -> "East-northeast"
            EAST -> "East"
            EAST_SOUTHEAST -> "East-southeast"
            SOUTHEAST -> "Southeast"
            SOUTH_SOUTHEAST -> "South-southeast"
            SOUTH -> "South"
            SOUTH_SOUTHWEST -> "South-southwest"
            SOUTHWEST -> "Southwest"
            WEST_SOUTHWEST -> "West-southwest"
            WEST -> "West"
            WEST_NORTHWEST -> "West-northwest"
            NORTHWEST -> "Northwest"
            NORTH_NORTHWEST -> "North-northwest"
        }

    companion object {
        /** The angular width of one compass sector, in degrees. */
        const val sectorWidth: Double = 22.5

        /** The compass point with the given raw value (0...15), or null if there is none. */
        fun fromRawValue(raw: Int): CompassPoint? = entries.firstOrNull { it.rawValue == raw }

        /**
         * Creates the compass point whose sector contains an azimuth (Swift `CompassPoint(azimuth:)`).
         *
         * Azimuth is measured in degrees clockwise from north and is wrapped into [0°, 360°), so
         * -90° is west and 450° is east. Each sector spans ±11.25° around its centre and includes its
         * lower boundary: 11.25° is north-northeast and 348.75° is north.
         *
         * A non-finite azimuth yields [NORTH]; callers that must distinguish missing data should
         * check `isFinite` first.
         */
        operator fun invoke(azimuth: Double): CompassPoint {
            if (!azimuth.isFinite()) return NORTH
            var wrapped = azimuth % 360.0
            if (wrapped < 0.0) wrapped += 360.0
            // Sector k is centred on k × 22.5°, so the sector is azimuth ÷ 22.5 rounded to the nearest
            // integer. Halves round up, which gives every sector its lower boundary. Dividing first
            // (rather than adding 11.25° and flooring) keeps the decision exact for azimuths a single
            // floating-point step either side of a boundary.
            val sector = (wrapped / sectorWidth).roundedHalfAwayFromZero().toInt() % entries.size
            return entries[sector]
        }
    }
}

/** Serializes a [CompassPoint] as its raw integer, like the Swift `Int`-backed `Codable` enum. */
internal object CompassPointSerializer : KSerializer<CompassPoint> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("net.kuecker.lodestar.kit.formatting.CompassPoint", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: CompassPoint) {
        encoder.encodeInt(value.rawValue)
    }

    override fun deserialize(decoder: Decoder): CompassPoint {
        val raw = decoder.decodeInt()
        return CompassPoint.fromRawValue(raw)
            ?: throw SerializationException("Cannot initialize CompassPoint from invalid Int value $raw")
    }
}

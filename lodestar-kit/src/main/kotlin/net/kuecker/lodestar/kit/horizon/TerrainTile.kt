package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.floor

/**
 * Names one 1° × 1° elevation tile by the whole-degree latitude and longitude of its south-west corner, the way the
 * Skadi tile set does: `N47E008` covers 47–48° N, 8–9° E.
 *
 * The constructor is private; use [create].
 *
 * @property latitude The latitude of the south-west corner, −90…89.
 * @property longitude The longitude of the south-west corner, −180…179.
 */
@ConsistentCopyVisibility
data class TerrainTileKey private constructor(val latitude: Int, val longitude: Int) : Comparable<TerrainTileKey> {
    /** The Skadi name: `N47E008`, `S34W071`, `N00E000`. */
    val name: String
        get() {
            val ns = if (latitude < 0) "S" else "N"
            val ew = if (longitude < 0) "W" else "E"
            val lat = kotlin.math.abs(latitude).toString().padStart(2, '0')
            val lon = kotlin.math.abs(longitude).toString().padStart(3, '0')
            return "$ns$lat$ew$lon"
        }

    /** The path of the tile's file in the tile set, below its base address: `N47/N47E008.hgt.gz`. */
    val path: String get() = "${name.substring(0, 3)}/$name.hgt.gz"

    /** Orders by latitude, then by longitude. */
    override fun compareTo(other: TerrainTileKey): Int =
        if (latitude != other.latitude) latitude.compareTo(other.latitude) else longitude.compareTo(other.longitude)

    override fun toString(): String = name

    companion object {
        /** The key of the corner [latitude], [longitude] (whole degrees), or `null` when it is outside −90…89, −180…179. */
        fun create(latitude: Int, longitude: Int): TerrainTileKey? {
            if (latitude < -90 || latitude > 89 || longitude < -180 || longitude > 179) return null
            return TerrainTileKey(latitude, longitude)
        }

        /**
         * The key of the tile that contains the point, or `null` at the north pole or the antimeridian's east side
         * (latitude 90°, longitude 180°), outside −90…90, −180…180, or for a value that is not finite.
         */
        fun containing(latitude: Double, longitude: Double): TerrainTileKey? {
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
            return create(floor(latitude).toInt(), floor(longitude).toInt())
        }
    }
}

/** Why bytes could not be read as a terrain tile. */
sealed class TerrainTileError(message: String) : Exception(message) {
    /** The corner is outside latitude −90…89 or longitude −180…179. */
    data class InvalidOrigin(val latitude: Int, val longitude: Int) :
        TerrainTileError("The tile corner $latitude, $longitude is not a valid tile.")

    /** The data is not 3601 × 3601 or 1201 × 1201 samples of two bytes. */
    data class UnsupportedSize(val byteCount: Int) :
        TerrainTileError("$byteCount bytes are not a 3601 × 3601 or 1201 × 1201 elevation tile.")
}

/**
 * One 1° × 1° tile of a digital elevation model in the SRTM `.hgt` layout, which the Skadi tile set of the AWS Open
 * Data Terrain Tiles uses: 3601 × 3601 (1 arc-second) or 1201 × 1201 (3 arc-second) big-endian signed 16-bit
 * elevations in metres, row by row from the northern edge to the southern edge and west to east in a row. The edge
 * samples lie on the tile's border, so neighbouring tiles repeat each other's edge.
 *
 * A sample of −32768 is a void (no data), and so is anything outside −500…9000 m, which no place on land reaches.
 * The samples are kept in one `ShortArray` (26 MB for 3601 × 3601). The tile is immutable; it has no value
 * equality, compare [key] and the elevations.
 *
 * The constructor is private; use [parse].
 */
class TerrainTile private constructor(
    /** The tile's name in the tile set. */
    val key: TerrainTileKey,
    /** The number of samples along one side: 3601 or 1201. */
    val samplesPerSide: Int,
    /** How many samples are voids. */
    val voidCount: Int,
    private val samples: ShortArray,
) {
    /** The latitude of the south-west corner in whole degrees, −90…89. */
    val latitude: Int get() = key.latitude

    /** The longitude of the south-west corner in whole degrees, −180…179. */
    val longitude: Int get() = key.longitude

    /**
     * The elevation in metres at [latitude], [longitude] (degrees), interpolated bilinearly between the four
     * surrounding samples, or `null` when the point is outside the tile (its border included), not finite, or any
     * sample that contributes to it is a void. A sample whose weight is zero does not count, so a point exactly on a
     * sample ignores its neighbours.
     */
    fun elevation(latitude: Double, longitude: Double): Double? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val south = key.latitude.toDouble()
        val west = key.longitude.toDouble()
        if (latitude < south || latitude > south + 1 || longitude < west || longitude > west + 1) return null
        val intervals = (samplesPerSide - 1).toDouble()
        val x = (longitude - west) * intervals
        val y = (south + 1 - latitude) * intervals
        val column = minOf(floor(x).toInt(), samplesPerSide - 2)
        val row = minOf(floor(y).toInt(), samplesPerSide - 2)
        val fx = x - column.toDouble()
        val fy = y - row.toDouble()
        var total = 0.0
        for (corner in 0..3) {
            val dx = corner and 1
            val dy = corner shr 1
            val weight = (if (dx == 0) 1 - fx else fx) * (if (dy == 0) 1 - fy else fy)
            if (weight <= 0) continue
            val sample = samples[(row + dy) * samplesPerSide + column + dx].toInt()
            if (sample == voidSample) return null
            total += weight * sample.toDouble()
        }
        return total
    }

    companion object {
        /** The samples along one side of a 1 arc-second tile. */
        const val highResolutionSamples: Int = 3601

        /** The samples along one side of a 3 arc-second tile. */
        const val lowResolutionSamples: Int = 1201

        /** The value of a sample without data. */
        const val voidSample: Int = -32768

        /** The lowest elevation in metres that counts as data (the Dead Sea shore is near −430 m). */
        const val lowestElevation: Int = -500

        /** The highest elevation in metres that counts as data (Mount Everest is 8849 m). */
        const val highestElevation: Int = 9000

        /**
         * Reads a tile whose south-west corner is [latitude], [longitude] (whole degrees) from the inflated file
         * [bytes]. Samples outside [lowestElevation]…[highestElevation] become voids.
         *
         * Cost: one pass over the bytes (13 million samples for a 1 arc-second tile) and one allocation, the 26 MB
         * sample array (1201 × 1201: 2.9 MB). Call it off the main thread.
         *
         * @throws TerrainTileError.InvalidOrigin when the corner is not a valid tile.
         * @throws TerrainTileError.UnsupportedSize when [bytes] is not exactly one of the two sizes.
         */
        @Throws(TerrainTileError::class)
        fun parse(latitude: Int, longitude: Int, bytes: ByteArray): TerrainTile {
            val key = TerrainTileKey.create(latitude, longitude) ?: throw TerrainTileError.InvalidOrigin(latitude, longitude)
            val side = when (bytes.size) {
                highResolutionSamples * highResolutionSamples * 2 -> highResolutionSamples
                lowResolutionSamples * lowResolutionSamples * 2 -> lowResolutionSamples
                else -> throw TerrainTileError.UnsupportedSize(bytes.size)
            }
            val samples = ShortArray(side * side)
            var voids = 0
            for (index in samples.indices) {
                val value = (bytes[2 * index].toInt() shl 8) or (bytes[2 * index + 1].toInt() and 0xFF)
                if (value < lowestElevation || value > highestElevation) {
                    samples[index] = voidSample.toShort()
                    voids += 1
                } else {
                    samples[index] = value.toShort()
                }
            }
            return TerrainTile(key, side, voids, samples)
        }
    }
}

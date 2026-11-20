package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Synthetic elevation tiles for the terrain tests: no file and no network is needed. */
internal object TerrainTestSupport {
    /** Metres per degree of arc on the sphere of [TerrainHorizon.earthRadiusMeters]. */
    const val metersPerDegree: Double = 6_371_008.8 * Math.PI / 180

    /** The bytes of a tile with [side] × [side] samples, [sample] giving the elevation of a row (from the north) and a column. */
    fun bytes(side: Int, sample: (row: Int, column: Int) -> Int): ByteArray {
        val out = ByteArray(side * side * 2)
        for (row in 0 until side) {
            for (column in 0 until side) {
                val value = sample(row, column)
                val index = 2 * (row * side + column)
                out[index] = (value shr 8).toByte()
                out[index + 1] = value.toByte()
            }
        }
        return out
    }

    /** A 1201 × 1201 tile whose samples are [elevation] at their latitude and longitude, rounded to whole metres. */
    fun tile(latitude: Int, longitude: Int, elevation: (Double, Double) -> Double): TerrainTile {
        val side = TerrainTile.lowResolutionSamples
        val intervals = (side - 1).toDouble()
        val data = bytes(side) { row, column ->
            elevation(latitude.toDouble() + 1 - row.toDouble() / intervals, longitude.toDouble() + column.toDouble() / intervals)
                .roundedHalfAwayFromZero().toInt()
        }
        return TerrainTile.parse(latitude, longitude, data)
    }

    /** A tile at the same [meters] everywhere. */
    fun flatTile(latitude: Int, longitude: Int, meters: Int): TerrainTile = tile(latitude, longitude) { _, _ -> meters.toDouble() }

    /** The point [distance] metres from ([latitude], [longitude]) along [azimuth] on the sphere, as (latitude, longitude). */
    fun destination(latitude: Double, longitude: Double, azimuth: Double, distance: Double): Pair<Double, Double> {
        val delta = distance / TerrainHorizon.earthRadiusMeters
        val phi = AstroMath.radians(latitude)
        val theta = AstroMath.radians(azimuth)
        val phi2 = asin(sin(phi) * cos(delta) + cos(phi) * sin(delta) * cos(theta))
        val lambda = AstroMath.radians(longitude) +
            atan2(sin(theta) * sin(delta) * cos(phi), cos(delta) - sin(phi) * sin(phi2))
        return AstroMath.degrees(phi2) to AstroMath.degrees(lambda)
    }

    /** A round hill: [height] metres at the apex, falling to 0 m at [radius] metres as `1 − (r / radius)²`. */
    fun dome(apexLatitude: Double, apexLongitude: Double, height: Double = 300.0, radius: Double = 3000.0): (Double, Double) -> Double =
        { latitude, longitude ->
            val north = (latitude - apexLatitude) * metersPerDegree
            val east = (longitude - apexLongitude) * metersPerDegree * AstroMath.cosDeg(apexLatitude)
            val fraction = hypot(north, east) / radius
            if (fraction >= 1) 0.0 else height * (1 - fraction * fraction)
        }

    /**
     * The highest apparent altitude along the ray from ([latitude], [longitude]) at [azimuth], computed straight from the
     * landscape [elevation] (not from a tile: no sampling, no rounding): the same steps and the same formula as the engine,
     * for an eye 2 m above [ground].
     */
    fun expectedSkyline(
        latitude: Double,
        longitude: Double,
        azimuth: Double,
        ground: Double,
        elevation: (Double, Double) -> Double,
    ): Double = TerrainHorizon.sampleDistances(TerrainHorizon.defaultMaximumDistanceMeters).maxOf { distance ->
        val (rayLatitude, rayLongitude) = destination(latitude, longitude, azimuth, distance)
        TerrainHorizon.apparentAltitude(elevation(rayLatitude, rayLongitude) - (ground + 2), distance)
    }

    /** The dip of the horizon over flat ground for an eye [eye] metres high, in degrees (negative): `−√(2h / R′)`. */
    fun flatDip(eye: Double): Double {
        val effective = TerrainHorizon.earthRadiusMeters / (1 - TerrainHorizon.refractionCoefficient)
        return -AstroMath.degrees(kotlin.math.sqrt(2 * eye / effective))
    }
}

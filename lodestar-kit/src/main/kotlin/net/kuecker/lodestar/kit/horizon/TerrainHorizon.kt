package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.foundation.*
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * What [TerrainHorizon.profile] found.
 *
 * @property profile The skyline: the highest apparent altitude of the terrain along each of the 360 whole-degree
 * azimuths, limited to −10…+60° and rounded to 0.1° like every [HorizonProfile].
 * @property altitudes The same 360 altitudes before the limits and the rounding, in degrees. Over flat ground they
 * are the dip of the horizon, a few hundredths of a degree for an eye 2 m high, which the 0.1° of [profile] cannot show.
 * @property groundElevationMeters The elevation of the ground at the site, from the tiles, or `null` when no tile
 * covers the site or its sample is a void. It is the reference of every altitude.
 * @property coveredRangeMeters For each azimuth, how far from the site the tiles reach without a break (0 when the
 * first step is already outside the tiles); [TerrainHorizon.maximumRangeMeters] at most.
 * @property missingTiles The tiles within the range that were not supplied, nearest first; their terrain is not in
 * the skyline.
 * @property voidSampleShare The share of the samples that fell on voids (water the survey could not measure, gaps) and
 * were counted as sea level, 0…1.
 */
data class TerrainHorizonResult(
    val profile: HorizonProfile,
    val altitudes: List<Double>,
    val groundElevationMeters: Double?,
    val coveredRangeMeters: List<Double>,
    val missingTiles: List<TerrainTileKey>,
    val voidSampleShare: Double,
) {
    /** The shortest of [coveredRangeMeters]: how far the data reaches in the worst direction. */
    val minimumCoveredRangeMeters: Double get() = coveredRangeMeters.min()

    /** `true` when no tile within the range is missing. */
    val isComplete: Boolean get() = missingTiles.isEmpty()
}

/**
 * The skyline a site would have without trees and buildings: the highest terrain along every azimuth, computed from
 * digital elevation tiles ([TerrainTile]). Pure computation; the tiles are downloaded and inflated by the apps.
 *
 * **Method.** For each of the 360 whole-degree azimuths a ray is marched over the sphere of radius
 * [earthRadiusMeters] (the mean radius of the WGS 84 ellipsoid, 6 371 008.8 m) from the site: every 30 m up to 2 km,
 * every 100 m up to 10 km and every 250 m beyond, to the range. The elevation under each step comes from the tile that
 * contains it (bilinear), a void counts as 0 m, and a step outside every tile is skipped. The apparent altitude of a
 * terrain point at distance `d` and height `h`, seen from the eye at `h0` (ground at the site plus the eye height),
 * is `atan((h − h0) / d) − d / (2 R′)` with `R′ = R / (1 − k)`: the first term is the elevation angle, the second the
 * drop of the surface below the tangent plane (`d² / 2R`) reduced by standard terrestrial refraction with the
 * coefficient `k` = [refractionCoefficient] (0.13). That is Gauss's value from the Hannover triangulation of 1826 and
 * the mean refraction coefficient geodetic instruments apply (the Leica TS13 manual gives the height correction
 * `(1 − k) d² / 2R` with `k` = 0.13); the refracted ray is treated as straight over an Earth of radius `R′` (see
 * "Atmospheric refraction", Wikipedia, `R_eff = R / (1 − k)`). Real refraction varies with the temperature gradient,
 * by day and by night (Hirt et al., J. Geophys. Res. 115, D21102, 2010); the part of the correction that refraction
 * accounts for is `k d / 2R`, 0.03° at 50 km, so a different `k` moves a ridge at the edge of the range by about that.
 * The skyline at an azimuth is the maximum over the steps. Over flat ground this gives the dip of the horizon,
 * `−√(2h / R′)` radians for an eye at height `h`: −0.042° for 2 m (the geometric dip `√(2h / R)` is −0.045°).
 *
 * **Accuracy.** The elevation data is a surface model: tree tops and buildings count as ground where the survey saw
 * them. A tile has 30 m (1 arc-second) or 90 m (3 arc-second) samples, so a ridge narrower than that is missed or
 * blunted; one sample is 0.17° wide as seen from 10 km (30 m) and the profile's bins are 1° wide.
 *
 * **Cost.** About 110 000 bilinear samples for the default range (360 azimuths × about 300 steps), plus
 * [TerrainTile.parse] for every tile. Call both off the main thread.
 */
object TerrainHorizon {
    /** The radius of the sphere the rays run over, in metres: the mean radius of the WGS 84 ellipsoid. */
    const val earthRadiusMeters: Double = 6_371_008.8

    /** The coefficient of standard terrestrial refraction, `k`: the ray's curvature is this share of the Earth's. */
    const val refractionCoefficient: Double = 0.13

    /** The height of the eye above the ground, in metres, when the caller gives none. */
    const val defaultEyeHeightMeters: Double = 2.0

    /** The range of the rays, in metres, when the caller gives none. */
    const val defaultMaximumDistanceMeters: Double = 50_000.0

    /** The highest eye height accepted, in metres; more is limited to this. */
    const val maximumEyeHeightMeters: Double = 100.0

    /** The shortest range accepted, in metres. */
    const val minimumRangeMeters: Double = 1_000.0

    /** The longest range accepted, in metres. */
    const val maximumRangeMeters: Double = 100_000.0

    /** The step of the rays, in metres, up to [nearLimitMeters]. */
    const val nearStepMeters: Double = 30.0

    /** The step of the rays, in metres, from [nearLimitMeters] to [midLimitMeters]. */
    const val midStepMeters: Double = 100.0

    /** The step of the rays, in metres, beyond [midLimitMeters]. */
    const val farStepMeters: Double = 250.0

    /** The distance up to which the rays step by [nearStepMeters], in metres. */
    const val nearLimitMeters: Double = 2_000.0

    /** The distance up to which the rays step by [midStepMeters], in metres. */
    const val midLimitMeters: Double = 10_000.0

    /** [requiredTiles] lists at most this many tiles (a site near a pole is within 50 km of dozens). */
    const val maximumTileCount: Int = 16

    private const val metersPerDegree: Double = earthRadiusMeters * AstroMath.radiansPerDegree

    /**
     * The apparent altitude in degrees of a terrain point [heightDifferenceMeters] above the eye, at the horizontal
     * distance [distanceMeters]: `atan(Δh / d) − d / (2 R′)` with `R′ = R / (1 − k)`, see the class description.
     * Negative below the eye's horizontal plane. [distanceMeters] must be positive.
     */
    fun apparentAltitude(heightDifferenceMeters: Double, distanceMeters: Double): Double {
        val effectiveRadius = earthRadiusMeters / (1 - refractionCoefficient)
        return AstroMath.degrees(atan(heightDifferenceMeters / distanceMeters)) -
            AstroMath.degrees(distanceMeters / (2 * effectiveRadius))
    }

    /**
     * The distances from the site, in metres, at which the rays are sampled for a range of [maximumDistanceMeters]:
     * 30, 60 … 1980, then 2010, 2110 … 9910, then 10 010, 10 260 …, the last one not beyond the range.
     */
    fun sampleDistances(maximumDistanceMeters: Double): List<Double> {
        val range = clampedRange(maximumDistanceMeters)
        val distances = ArrayList<Double>()
        var distance = nearStepMeters
        while (distance <= range) {
            distances.add(distance)
            distance += when {
                distance < nearLimitMeters -> nearStepMeters
                distance < midLimitMeters -> midStepMeters
                else -> farStepMeters
            }
        }
        return distances
    }

    /**
     * The tiles a profile for [location] with the range [maxDistanceMeters] needs: every 1° × 1° tile that comes
     * within that distance of the site, nearest first (the tile with the site first), at most [maximumTileCount].
     * A 50 km circle is 0.9° wide at the equator and 1.3° wide at 47° of latitude, so it reaches up to four tiles
     * near the equator and up to six at mid latitudes. Ties in distance are ordered by latitude, then longitude. The
     * distance to a tile is measured to the nearest point of its border, with a local flat-earth metric (error
     * well under a kilometre at 50 km).
     */
    fun requiredTiles(
        location: GeographicLocation,
        maxDistanceMeters: Double = defaultMaximumDistanceMeters,
    ): List<TerrainTileKey> {
        val range = clampedRange(maxDistanceMeters)
        val latitude = location.latitude
        val longitude = location.longitude
        val latitudeSpan = range / metersPerDegree
        val longitudeSpan = minOf(latitudeSpan / maxOf(AstroMath.cosDeg(latitude), 0.01), 180.0)
        val southmost = maxOf(floor(latitude - latitudeSpan).toInt(), -90)
        val northmost = minOf(floor(latitude + latitudeSpan).toInt(), 89)
        val westmost = floor(longitude - longitudeSpan).toInt()
        val eastmost = floor(longitude + longitudeSpan).toInt()
        val found = ArrayList<Pair<Double, TerrainTileKey>>()
        val seen = HashSet<TerrainTileKey>()
        for (row in southmost..northmost) {
            for (column in westmost..eastmost) {
                val wrapped = Math.floorMod(column + 180, 360) - 180
                val key = TerrainTileKey.create(row, wrapped) ?: continue
                val northSouth = maxOf(row.toDouble() - latitude, 0.0, latitude - (row + 1).toDouble())
                val eastWest = maxOf(column.toDouble() - longitude, 0.0, longitude - (column + 1).toDouble())
                val nearestLatitude = AstroMath.clamp(latitude, row.toDouble(), (row + 1).toDouble())
                val meters = hypot(northSouth, eastWest * AstroMath.cosDeg(nearestLatitude)) * metersPerDegree
                if (meters <= range && seen.add(key)) found.add(meters to key)
            }
        }
        found.sortWith(compareBy<Pair<Double, TerrainTileKey>> { it.first }.thenBy { it.second })
        return found.take(maximumTileCount).map { it.second }
    }

    /**
     * The skyline of the terrain around [location] from [tiles].
     *
     * @param location The site. Its elevation is used only where no tile covers the site.
     * @param tiles The elevation tiles; the ones the range needs are listed by [requiredTiles], and a tile that is
     * given twice counts once. Tiles that are not needed do no harm.
     * @param eyeHeightMeters The height of the eye above the ground, in metres: 0…100, 2 when it is not finite.
     * @param maxDistanceMeters How far the rays go, in metres: 1000…100 000, 50 000 when it is not finite.
     */
    fun profile(
        location: GeographicLocation,
        tiles: List<TerrainTile>,
        eyeHeightMeters: Double = defaultEyeHeightMeters,
        maxDistanceMeters: Double = defaultMaximumDistanceMeters,
    ): TerrainHorizonResult {
        val eye = if (eyeHeightMeters.isFinite()) {
            AstroMath.clamp(eyeHeightMeters, 0.0, maximumEyeHeightMeters)
        } else {
            defaultEyeHeightMeters
        }
        val range = clampedRange(maxDistanceMeters)
        val index = HashMap<Int, TerrainTile>()
        for (tile in tiles) index.putIfAbsent(tileCode(tile.latitude, tile.longitude), tile)
        val missing = requiredTiles(location, range).filter { !index.containsKey(tileCode(it.latitude, it.longitude)) }
        val ground = tileAt(index, location.latitude, location.longitude)?.elevation(location.latitude, location.longitude)
        val eyeLevel = (ground ?: location.elevation) + eye

        val distances = sampleDistances(range)
        val sinSteps = DoubleArray(distances.size) { sin(distances[it] / earthRadiusMeters) }
        val cosSteps = DoubleArray(distances.size) { cos(distances[it] / earthRadiusMeters) }
        val sinLatitude0 = AstroMath.sinDeg(location.latitude)
        val cosLatitude0 = AstroMath.cosDeg(location.latitude)
        val altitudes = DoubleArray(HorizonProfile.binCount)
        val covered = DoubleArray(HorizonProfile.binCount)
        var voids = 0
        var used = 0
        for (bin in 0 until HorizonProfile.binCount) {
            val azimuth = AstroMath.radians(bin.toDouble())
            val sinAzimuth = sin(azimuth)
            val cosAzimuth = cos(azimuth)
            var best = Double.NEGATIVE_INFINITY
            var reach = 0.0
            var isContiguous = true
            for (step in distances.indices) {
                val sinLatitude = sinLatitude0 * cosSteps[step] + cosLatitude0 * sinSteps[step] * cosAzimuth
                val latitude = AstroMath.degrees(asin(AstroMath.clamp(sinLatitude, -1.0, 1.0)))
                var longitude = location.longitude + AstroMath.degrees(
                    atan2(sinAzimuth * sinSteps[step] * cosLatitude0, cosSteps[step] - sinLatitude0 * sinLatitude),
                )
                if (longitude >= 180) longitude -= 360 else if (longitude < -180) longitude += 360
                val tile = tileAt(index, latitude, longitude)
                if (tile == null) {
                    isContiguous = false
                    continue
                }
                if (isContiguous) reach = distances[step]
                val elevation = tile.elevation(latitude, longitude)
                if (elevation == null) voids += 1
                used += 1
                val altitude = apparentAltitude((elevation ?: 0.0) - eyeLevel, distances[step])
                if (altitude > best) best = altitude
            }
            altitudes[bin] = if (best == Double.NEGATIVE_INFINITY) 0.0 else best
            covered[bin] = reach
        }
        return TerrainHorizonResult(
            profile = HorizonProfile.fromClamped(altitudes.copyOf()),
            altitudes = altitudes.toList(),
            groundElevationMeters = ground,
            coveredRangeMeters = covered.toList(),
            missingTiles = missing,
            voidSampleShare = if (used == 0) 0.0 else voids.toDouble() / used.toDouble(),
        )
    }

    private fun clampedRange(meters: Double): Double =
        if (meters.isFinite()) AstroMath.clamp(meters, minimumRangeMeters, maximumRangeMeters) else defaultMaximumDistanceMeters

    private fun tileCode(latitude: Int, longitude: Int): Int = (latitude + 90) * 360 + (longitude + 180)

    private fun tileAt(index: Map<Int, TerrainTile>, latitude: Double, longitude: Double): TerrainTile? {
        val key = TerrainTileKey.containing(latitude, longitude) ?: return null
        return index[tileCode(key.latitude, key.longitude)]
    }
}

/**
 * The skyline to use at a site that has a recorded skyline, a terrain skyline, or both: the recorded one where the
 * sweep reached, the terrain one in the [gaps] the sweep left (see [unsweptRanges]). With only one of them that one;
 * with neither `null`. Without [gaps] the recorded skyline wins everywhere (its coverage is not known or complete).
 */
fun HorizonProfile.Companion.merged(
    recorded: HorizonProfile?,
    terrain: HorizonProfile?,
    gaps: List<AzimuthRange> = emptyList(),
): HorizonProfile? {
    if (recorded == null) return terrain
    if (terrain == null || gaps.isEmpty()) return recorded
    val values = recorded.altitudes.toDoubleArray()
    for (index in values.indices) {
        if (gaps.any { it.contains(index.toDouble()) }) values[index] = terrain.altitudes[index]
    }
    return fromClamped(values)
}

/**
 * The stretches a sweep left open, from the [swept] flags it reached (360 values, one per whole degree, like
 * `HorizonRecorder.recordedMask`): the runs of unswept degrees longer than [maximumInterpolatedGap] (the recorder
 * fills shorter ones by interpolation, so they are not gaps), in azimuth order, each starting at the lower edge of its
 * first degree like `HorizonRecorder.gaps`. Nothing swept gives the whole circle; a [swept] list that is not 360 long
 * gives no gap.
 */
fun HorizonProfile.Companion.unsweptRanges(
    swept: List<Boolean>,
    maximumInterpolatedGap: Int = HorizonRecorder.Configuration.standard.maximumInterpolatedGap,
): List<AzimuthRange> {
    val count = HorizonProfile.binCount
    if (swept.size != count) return emptyList()
    val limit = AstroMath.clamp(maximumInterpolatedGap, 0, 30)
    val first = swept.indexOfFirst { it }
    if (first < 0) return listOf(AzimuthRange.full)
    val ranges = ArrayList<AzimuthRange>()
    var start = -1
    for (offset in 1..count) {
        val bin = (first + offset) % count
        if (!swept[bin]) {
            if (start < 0) start = bin
        } else if (start >= 0) {
            val length = (bin - start + count) % count
            if (length > limit) ranges.add(AzimuthRange(start.toDouble() - 0.5, length.toDouble()))
            start = -1
        }
    }
    return ranges.sortedBy { it.start }
}

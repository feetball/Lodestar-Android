package net.kuecker.lodestar.kit.skyquality

import net.kuecker.lodestar.kit.catalog.Catalog
import net.kuecker.lodestar.kit.formatting.CompassPoint
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.io.IOException
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Why the light pollution map could not be loaded.
 *
 * @property description A sentence that says what is wrong with the data file.
 */
sealed class SkyQualityError(val description: String) : Exception(description) {
    /** The same sentence as [description] (Swift `LocalizedError.errorDescription`). */
    val errorDescription: String? get() = description

    /** The bundled `lightpollution.bin` is missing, unreadable, larger than [LightPollutionMap.maximumFileBytes] or malformed. */
    data class LightPollutionMapUnavailable(val reason: String) :
        SkyQualityError("The light pollution map is unavailable: $reason")
}

/**
 * A coarse map of the artificial light at night, and from it an offline estimate of the sky quality of any site.
 *
 * ```kotlin
 * val map = LightPollutionMap.load()                       // once; keep the value
 * val here = GeographicLocation(41.66, -77.82)
 * val estimate = map.bortleEstimate(here)                  // class 1-9, label, caveat
 * val around = map.darkestDirection(here)                  // 16 compass directions, 30 km
 * around.darkestHint?.direction                            // where the sky is darker, if it is
 * ```
 *
 * **Data.** `lightpollution.bin`, a 3600 x 1800 grid of 0.1° cells (about 11 km) with 16 levels, built by
 * `Tools/build_light_pollution.py` from NASA GIBS, layer `VIIRS_Black_Marble` (Suomi NPP VIIRS day/night band composite of
 * 2016, tiles pinned in `Tools/lightpollution.lock.json`). The land's faint base texture is removed, the light is averaged
 * per cell and quantized on a log scale: level 0 is dark (below 0.0025 of the display's full scale: ocean, deserts,
 * forests, ice), level 15 the centre of a big city. The file is 137 kB; [load] expands it to 6.5 MB (one byte per cell).
 * Credit: NASA Worldview / GIBS, Black Marble (VIIRS, Suomi NPP).
 *
 * **Levels.** [level] is the nearest cell and nothing else; the data is too coarse for interpolation. A satellite sees the
 * light a place sends up, not the glow it receives: a dark cell 15 km from a town is level 0 and still has a bright sky.
 *
 * **Sky glow.** [skyGlowIndex] adds up the light of every cell within [glowRadiusKm] (100 km) of the site, each cell's
 * luminance ([levelLuminance]) times its area (the cosine of its latitude) times `(d / 5.5 km)^-2.5`, where `d` is the
 * distance to the cell's centre and never less than 5.5 km (Walker's law for the glow of a town, the exponent -2.5 of
 * Garstang-type models). The index is in units of "one cell of luminance 1 at the site"; a megacity gives 1 or more.
 * [bortleEstimate] turns the index into a class with [BortleEstimate.glowThresholds].
 *
 * **Calibration.** The thresholds were chosen from these places (the generator prints the table with
 * `--report`; published classes are approximate, sources differ by one class, and a satellite sees emitted light):
 *
 * | Place | Published | Cell level | Glow index | Estimate |
 * |---|---|---|---|---|
 * | Cherry Springs State Park, PA | 2 | 0 | 0.0005 | 2 |
 * | Death Valley, Badwater | 1-2 | 0 | 0.00009 | 1 |
 * | Big Bend, Chisos Basin | 1-2 | 0 | 0.0 | 1 |
 * | Joshua Tree, Hidden Valley | 2-3 | 0 | 0.017 | 4 (off by one) |
 * | Zselic Starry Sky Park, HU | 3 | 0 | 0.0041 | 3 |
 * | Galloway Forest Park | 2-3 | 0 | 0.0024 | 3 |
 * | Mauna Kea summit | 1 | 0 | 0.0003 | 1 |
 * | Natural Bridges, UT | 1 | 0 | 0.00001 | 1 |
 * | Paranal, Chile | 1 | 0 | 0.00004 | 1 |
 * | Roque de los Muchachos, La Palma | 1 | 0 | 0.0017 | 3 (off by two: towns 20 km below) |
 * | Kielder Observatory, UK | 2-3 | 0 | 0.0037 | 3 |
 * | McDonald Observatory, TX | 1-2 | 0 | 0.0001 | 1 |
 * | Green Bank, WV | 3 | 0 | 0.0006 | 2 (off by one) |
 * | Flagstaff, AZ (Lowell Observatory) | 5-6 | 7 | 0.050 | 5 |
 * | Boulder, CO | 7 | 12 | 0.23 | 7 |
 * | Palo Alto, CA | 7-8 | 13 | 0.40 | 8 |
 * | Plano, TX | 7-8 | 15 | 1.29 | 9 (the whole Dallas area is lit) |
 * | Munich, Marienplatz | 8-9 | 15 | 0.61 | 9 |
 * | Cape Town, centre | 8-9 | 14 | 0.47 | 8 |
 * | London, Trafalgar Square | 9 | 15 | 1.12 | 9 |
 * | New York, Times Square | 9 | 15 | 1.31 | 9 |
 * | Las Vegas, the Strip | 9 | 15 | 1.02 | 9 |
 * | Tokyo, Shinjuku | 9 | 15 | 1.26 | 9 |
 * | Pacific Ocean (0°, 150° W), Sahara (23° N, 10° E) | 1 | 0 | 0.0 | 1 |
 *
 * 21 of the 25 places fall in their published class. The estimate is good to about a class in a city and two in the dark,
 * where the satellite cannot tell a Bortle 1 from a Bortle 3 sky.
 *
 * **Cost.** [level] is a lookup. [skyGlowIndex] and [bortleEstimate] visit about 1,300 cells at mid latitudes (a few
 * thousand at 70°, all of the 19 rows at the poles): tens of microseconds. [darkestDirection] makes 16 x 6 lookups.
 * Instances are immutable and may be shared between threads.
 */
class LightPollutionMap private constructor(internal val levels: ByteArray) {
    /** The level, 0 (dark) to 15, of the cell that contains [at] (the nearest cell). */
    fun level(at: GeographicLocation): Int = levels[row(at.latitude) * width + column(at.longitude)].toInt()

    /**
     * The sky-glow index of a site: the light of the cells within [glowRadiusKm] weighted by area and distance (see the
     * class documentation). 0 in the dark, about 1 or more in the centre of a megacity. Tens of microseconds.
     */
    fun skyGlowIndex(at: GeographicLocation): Double {
        val latitude = at.latitude
        val longitude = at.longitude
        val centerRow = row(latitude)
        val centerColumn = column(longitude)
        val rowSpan = (glowRadiusKm / (kilometersPerDegree * cellDegrees)).toInt() + 1
        var total = 0.0
        for (row in max(0, centerRow - rowSpan)..min(height - 1, centerRow + rowSpan)) {
            val rowLatitude = 90.0 - (row.toDouble() + 0.5) * cellDegrees
            val area = cos(AstroMath.radians(rowLatitude))
            val eastWest = kilometersPerDegree * cos(AstroMath.radians((latitude + rowLatitude) / 2.0))
            val northSouth = (rowLatitude - latitude) * kilometersPerDegree
            val columnSpan = min(width / 2 - 1, (glowRadiusKm / (max(eastWest, 1e-9) * cellDegrees)).toInt() + 1)
            for (step in -columnSpan..columnSpan) {
                val column = Math.floorMod(centerColumn + step, width)
                val level = levels[row * width + column].toInt()
                if (level == 0) continue
                val delta = ((column.toDouble() + 0.5) * cellDegrees - 180.0 - longitude + 540.0) % 360.0 - 180.0
                val distance = hypot(delta * eastWest, northSouth)
                if (distance > glowRadiusKm) continue
                val ratio = max(distance, glowCoreKm) / glowCoreKm
                total += luminance[level] * area / (ratio * ratio * sqrt(ratio))
            }
        }
        return total
    }

    /**
     * The estimated Bortle class of the sky at [at]: [skyGlowIndex] turned into a class 1-9 with
     * [BortleEstimate.glowThresholds], with the cell's [level]. Tens of microseconds.
     */
    fun bortleEstimate(at: GeographicLocation): BortleEstimate = BortleEstimate(skyGlowIndex(at), level(at))

    /**
     * The 16 compass directions around [around], north first, each with the mean level of the cells along a ray of
     * [radiusKm] (sampled every 5 km or less, at the middle of equal steps). The darkest direction is the hint for where
     * to look for a darker sky: see [darkest] and [darkestHint]; [horizonGlow] turns the list into a ring.
     *
     * @param radiusKm The length of the rays, clamped to 1-200 km; 30 when it is not a finite number.
     */
    fun darkestDirection(around: GeographicLocation, radiusKm: Double = defaultRayKm): List<DirectionBrightness> {
        val radius = if (radiusKm.isFinite()) AstroMath.clamp(radiusKm, 1.0, 200.0) else defaultRayKm
        val samples = max(1, ceil(radius / 5.0).toInt())
        return CompassPoint.entries.map { direction ->
            var sum = 0
            for (index in 0 until samples) {
                val distance = radius * (index.toDouble() + 0.5) / samples.toDouble()
                sum += level(destination(around, direction.centerAzimuth, distance))
            }
            DirectionBrightness(direction, sum.toDouble() / samples.toDouble())
        }
    }

    companion object {
        /** The name of the bundled file in the library's resources. */
        const val resourceFileName: String = "lightpollution.bin"

        /** The largest file that is accepted, 1 MiB (the shipped one is 137 kB). */
        const val maximumFileBytes: Int = 1024 * 1024

        /** Cells per row of the grid: 3600 cells of 0.1° from 180° W eastward. */
        const val width: Int = 3600

        /** Rows of the grid: 1800 cells of 0.1° from 90° N southward. */
        const val height: Int = 1800

        /** The side of a cell in degrees. */
        const val cellDegrees: Double = 0.1

        /** The number of levels, 0 (dark) to 15. */
        const val levelCount: Int = 16

        /** The brightest level, 15. */
        const val maximumLevel: Int = 15

        /** The radius in kilometres within which cells add to the sky glow. */
        const val glowRadiusKm: Double = 100.0

        /** The distance in kilometres below which a cell counts as if it were this far: about half a cell. */
        const val glowCoreKm: Double = 5.5

        /** The length of a degree of latitude in kilometres, as the generator and the glow model use it. */
        const val kilometersPerDegree: Double = 111.195

        /** The default length of the rays of [darkestDirection], 30 km. */
        const val defaultRayKm: Double = 30.0

        private const val earthRadiusKm = 6371.0088
        private const val headerBytes = 16

        private val luminance = doubleArrayOf(
            0.0, 0.002997, 0.004306, 0.006188, 0.008891, 0.01278, 0.01836, 0.02638,
            0.03791, 0.05447, 0.07827, 0.1125, 0.1616, 0.2322, 0.3337, 0.4795,
        )

        /**
         * The linear luminance (0 to about 0.5 of the display's full scale) that stands for each level in the sky-glow
         * model: the geometric middle of the level's range; 0 for level 0. Index 0-15.
         */
        val levelLuminance: List<Double> = luminance.toList()

        /**
         * Reads the bundled file and expands it to 6.5 MB; about 10 ms. Call it once, off the main thread, and keep the
         * value.
         *
         * @throws SkyQualityError.LightPollutionMapUnavailable when the file is missing, unreadable, too large or malformed.
         */
        fun load(): LightPollutionMap = parse(bundledData())

        /** The raw contents of the bundled file (a resource in the folder of [Catalog]'s data files). */
        internal fun bundledData(): ByteArray {
            val stream = LightPollutionMap::class.java.getResourceAsStream(Catalog.RESOURCE_DIRECTORY + resourceFileName)
                ?: throw SkyQualityError.LightPollutionMapUnavailable("the resource $resourceFileName is missing")
            try {
                return stream.use { it.readBytes() }
            } catch (error: IOException) {
                throw SkyQualityError.LightPollutionMapUnavailable(error.message ?: error.toString())
            }
        }

        /**
         * Reads the contents of such a file; [load] is this with the bundled bytes. The format is described in
         * `Tools/build_light_pollution.py`: a 16-byte header, then runs per row.
         */
        internal fun parse(data: ByteArray): LightPollutionMap {
            fun unavailable(reason: String) = SkyQualityError.LightPollutionMapUnavailable(reason)
            if (data.size > maximumFileBytes) throw unavailable("the file is larger than $maximumFileBytes bytes")
            if (data.size < headerBytes) throw unavailable("the file is shorter than its header")
            if (data[0] != 'L'.code.toByte() || data[1] != 'P'.code.toByte() || data[2] != 'M'.code.toByte() ||
                data[3] != '1'.code.toByte()
            ) {
                throw unavailable("the file is not a light pollution map")
            }
            fun byte(index: Int): Int = data[index].toInt() and 0xFF
            val gridWidth = byte(4) or (byte(5) shl 8)
            val gridHeight = byte(6) or (byte(7) shl 8)
            val runLength = byte(10).toLong() or (byte(11).toLong() shl 8) or (byte(12).toLong() shl 16) or
                (byte(13).toLong() shl 24)
            if (gridWidth != width || gridHeight != height || byte(8) != levelCount || byte(9) != 0 ||
                byte(14) != 0 || byte(15) != 0
            ) {
                throw unavailable("the grid is not 3600 x 1800 cells of 16 levels")
            }
            if (runLength != (data.size - headerBytes).toLong()) throw unavailable("the run data has the wrong length")
            val cells = ByteArray(width * height)
            var position = headerBytes
            for (row in 0 until height) {
                var column = 0
                while (column < width) {
                    if (position >= data.size) throw unavailable("the run data ends inside a row")
                    val token = byte(position)
                    position += 1
                    var run = (token ushr 4) + 1
                    if (token ushr 4 == 15) {
                        var extra = 0
                        var shift = 0
                        while (true) {
                            if (position >= data.size || shift > 14) throw unavailable("a run length is malformed")
                            val next = byte(position)
                            position += 1
                            extra = extra or ((next and 0x7F) shl shift)
                            shift += 7
                            if (next and 0x80 == 0) break
                        }
                        run = 16 + extra
                    }
                    if (run > width - column) throw unavailable("a run crosses the end of its row")
                    val level = (token and 15).toByte()
                    val start = row * width + column
                    cells.fill(level, start, start + run)
                    column += run
                }
            }
            if (position != data.size) throw unavailable("the file has bytes after the last row")
            return LightPollutionMap(cells)
        }

        /** The row of the cell that contains [latitude]: 0 at the north pole, clamped to the grid. */
        internal fun row(latitude: Double): Int =
            min(max(floor((90.0 - latitude) * 10.0).toInt(), 0), height - 1)

        /** The column of the cell that contains [longitude]: 0 starts at 180° W; wraps around. */
        internal fun column(longitude: Double): Int = Math.floorMod(floor((longitude + 180.0) * 10.0).toInt(), width)

        /** The place [distanceKm] from [origin] on the initial great-circle bearing [bearing] (degrees from north). */
        private fun destination(origin: GeographicLocation, bearing: Double, distanceKm: Double): GeographicLocation {
            val angle = distanceKm / earthRadiusKm
            val theta = AstroMath.radians(bearing)
            val phi = AstroMath.radians(origin.latitude)
            val phi2 = asin(sin(phi) * cos(angle) + cos(phi) * sin(angle) * cos(theta))
            val lambda2 = AstroMath.radians(origin.longitude) +
                atan2(sin(theta) * sin(angle) * cos(phi), cos(angle) - sin(phi) * sin(phi2))
            return GeographicLocation(AstroMath.degrees(phi2), AstroMath.degrees(lambda2))
        }
    }
}

/**
 * A sky-quality estimate for one site, from [LightPollutionMap.bortleEstimate].
 *
 * The Bortle scale (Bortle, *Sky & Telescope*, February 2001) rates a sky from class 1, the darkest, to class 9, an
 * inner-city sky. The class here is an estimate from 2016 satellite night-light imagery: [caveat] must be shown with it.
 *
 * @property bortleClass The estimated class, 1 (excellent dark sky) to 9 (inner-city sky).
 * @property level The light level of the site's own cell, 0 to 15.
 * @property skyGlowIndex The sky-glow index the class was taken from (see [LightPollutionMap]).
 */
@ConsistentCopyVisibility
data class BortleEstimate private constructor(
    val bortleClass: Int,
    val level: Int,
    val skyGlowIndex: Double,
) {
    /** The name of the class in Bortle's scale, for example "Rural sky". */
    val label: String get() = Companion.label(bortleClass)

    /** A short phrase for site lists: "Bortle ~4 (rural/suburban transition)". */
    val summary: String get() = "Bortle ~$bortleClass (${label.lowercase()})"

    companion object {
        /** What to say with every estimate: it is not a measurement. */
        const val caveat: String = "Estimated from 2016 satellite night-light imagery; not a sky measurement"

        /**
         * The sky-glow index at which classes 2, 3, ... 9 begin; class 1 is below the first. Strictly increasing, chosen
         * from the reference places in the documentation of [LightPollutionMap].
         */
        val glowThresholds: List<Double> = listOf(0.00045, 0.0012, 0.008, 0.02, 0.06, 0.15, 0.3, 0.5)

        /**
         * The estimate for a glow index and a cell level: [skyGlowIndex] that is negative or not a number counts as 0
         * and [level] is limited to 0-15.
         */
        operator fun invoke(skyGlowIndex: Double, level: Int): BortleEstimate {
            val glow = if (skyGlowIndex.isFinite() && skyGlowIndex > 0.0) skyGlowIndex else 0.0
            return BortleEstimate(bortleClass(glow), AstroMath.clamp(level, 0, LightPollutionMap.maximumLevel), glow)
        }

        /** The Bortle class, 1-9, of a sky-glow index: 1 plus the number of [glowThresholds] it reaches. */
        fun bortleClass(forGlowIndex: Double): Int = 1 + glowThresholds.count { it <= forGlowIndex }

        /** The name of a class in Bortle's scale; the class is limited to 1-9. */
        fun label(forClass: Int): String = when (AstroMath.clamp(forClass, 1, 9)) {
            1 -> "Excellent dark sky"
            2 -> "Typical dark sky"
            3 -> "Rural sky"
            4 -> "Rural/suburban transition"
            5 -> "Suburban sky"
            6 -> "Bright suburban sky"
            7 -> "Suburban/urban transition"
            8 -> "City sky"
            else -> "Inner-city sky"
        }
    }
}

/**
 * The light along one compass direction from a site, from [LightPollutionMap.darkestDirection].
 *
 * @property direction The compass point; the ray runs along its centre azimuth.
 * @property meanLevel The mean level, 0 to 15, of the cells sampled along the ray.
 */
@ConsistentCopyVisibility
data class DirectionBrightness internal constructor(
    val direction: CompassPoint,
    val meanLevel: Double,
) {
    /** The azimuth of the ray in degrees, from north through east. */
    val azimuth: Double get() = direction.centerAzimuth

    /** [meanLevel] as a fraction of the brightest level, 0 to 1: the strength of the glow on the horizon. */
    val glow: Double get() = meanLevel / LightPollutionMap.maximumLevel.toDouble()
}

/** The darkest direction of a list from [LightPollutionMap.darkestDirection]: the lowest mean level, the first on a tie. */
val List<DirectionBrightness>.darkest: DirectionBrightness?
    get() {
        var best: DirectionBrightness? = null
        for (candidate in this) {
            val current = best
            if (current == null || candidate.meanLevel < current.meanLevel) best = candidate
        }
        return best
    }

/**
 * The [darkest] direction, but only when it is worth saying: at least one level darker than the brightest direction.
 * `null` over open sea or in the middle of a uniformly lit area, where every direction is alike.
 */
val List<DirectionBrightness>.darkestHint: DirectionBrightness?
    get() {
        val dark = darkest ?: return null
        val brightest = this.maxOf { it.meanLevel }
        return if (brightest - dark.meanLevel >= 1.0) dark else null
    }

/**
 * The glow on the horizon toward [azimuth], 0 to 1, interpolated linearly between the two nearest directions of a list
 * as returned by [LightPollutionMap.darkestDirection] (evenly spaced from north, wrapping at north). 0 for an empty
 * list or an azimuth that is not a finite number. For the ring of a sky chart.
 */
fun List<DirectionBrightness>.horizonGlow(azimuth: Double): Double {
    if (isEmpty() || !azimuth.isFinite()) return 0.0
    var wrapped = azimuth % 360.0
    if (wrapped < 0.0) wrapped += 360.0
    val position = wrapped / (360.0 / size.toDouble())
    val lower = floor(position)
    val first = lower.toInt() % size
    val second = (first + 1) % size
    val fraction = position - lower
    return this[first].glow * (1.0 - fraction) + this[second].glow * fraction
}

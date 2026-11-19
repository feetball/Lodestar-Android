package net.kuecker.lodestar.kit.skyquality

import net.kuecker.lodestar.kit.formatting.CompassPoint
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import kotlin.math.cos
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkyQualityLightPollutionMapTests {
    private val support = SkyQualityTestSupport

    /** A place of the reference table: the generator's `--report` of the shipped file (glow to five decimals). */
    private class Reference(
        val name: String,
        val latitude: Double,
        val longitude: Double,
        val level: Int,
        val glow: Double,
        val bortleClass: Int,
    )

    private val references = listOf(
        Reference("Cherry Springs State Park", 41.6627, -77.8230, 0, 0.00050, 2),
        Reference("Death Valley, Badwater", 36.2296, -116.7673, 0, 0.00009, 1),
        Reference("Big Bend, Chisos Basin", 29.2706, -103.3010, 0, 0.00000, 1),
        Reference("Joshua Tree, Hidden Valley", 34.0125, -116.1672, 0, 0.01672, 4),
        Reference("Zselic Starry Sky Park", 46.2125, 17.8689, 0, 0.00411, 3),
        Reference("Galloway Forest Park", 55.0450, -4.4900, 0, 0.00239, 3),
        Reference("Mauna Kea summit", 19.8207, -155.4681, 0, 0.00033, 1),
        Reference("Natural Bridges", 37.6020, -110.0140, 0, 0.00001, 1),
        Reference("Paranal", -24.6275, -70.4044, 0, 0.00004, 1),
        Reference("Roque de los Muchachos", 28.7606, -17.8816, 0, 0.00171, 3),
        Reference("Kielder Observatory", 55.2336, -2.6025, 0, 0.00368, 3),
        Reference("McDonald Observatory", 30.6714, -104.0225, 0, 0.00010, 1),
        Reference("Green Bank", 38.4331, -79.8397, 0, 0.00062, 2),
        Reference("Flagstaff, Lowell Observatory", 35.2028, -111.6646, 7, 0.04954, 5),
        Reference("Boulder", 40.0150, -105.2705, 12, 0.22996, 7),
        Reference("Palo Alto", 37.4419, -122.1430, 13, 0.40191, 8),
        Reference("Plano", 33.0198, -96.6989, 15, 1.29076, 9),
        Reference("Munich, Marienplatz", 48.1374, 11.5755, 15, 0.61057, 9),
        Reference("Cape Town", -33.9249, 18.4241, 14, 0.47350, 8),
        Reference("London, Trafalgar Square", 51.5080, -0.1281, 15, 1.11594, 9),
        Reference("New York, Times Square", 40.7580, -73.9855, 15, 1.30694, 9),
        Reference("Las Vegas, the Strip", 36.1147, -115.1728, 15, 1.01962, 9),
        Reference("Tokyo, Shinjuku", 35.6896, 139.7006, 15, 1.26326, 9),
        Reference("Pacific Ocean", 0.0, -150.0, 0, 0.0, 1),
        Reference("Sahara", 23.0, 10.0, 0, 0.0, 1),
    )

    /** The class (or range of classes) published for each place: park and observatory pages, NPS and IDA. */
    private val published = mapOf(
        "Cherry Springs State Park" to 2..2, "Death Valley, Badwater" to 1..2, "Big Bend, Chisos Basin" to 1..2,
        "Joshua Tree, Hidden Valley" to 2..3, "Zselic Starry Sky Park" to 3..3, "Galloway Forest Park" to 2..3,
        "Mauna Kea summit" to 1..1, "Natural Bridges" to 1..1, "Paranal" to 1..1, "Roque de los Muchachos" to 1..1,
        "Kielder Observatory" to 2..3, "McDonald Observatory" to 1..2, "Green Bank" to 3..3,
        "Flagstaff, Lowell Observatory" to 5..6, "Boulder" to 7..7, "Palo Alto" to 7..8, "Plano" to 7..8,
        "Munich, Marienplatz" to 8..9, "Cape Town" to 8..9, "London, Trafalgar Square" to 9..9,
        "New York, Times Square" to 9..9, "Las Vegas, the Strip" to 9..9, "Tokyo, Shinjuku" to 9..9,
        "Pacific Ocean" to 1..1, "Sahara" to 1..1,
    )

    private val shipped: LightPollutionMap by lazy { LightPollutionMap.load() }

    // MARK: Decoder

    @Test
    fun testDecoderRoundTripsASyntheticGrid() {
        val cells = support.cells { row, column ->
            when {
                row < 600 -> (column / 5 + row / 100) % 16
                row < 900 -> 3
                row < 1200 -> (column + row) % 2 * 9
                else -> if (column in 100 until 3500) 15 else 0
            }
        }
        val blob = support.encode(cells)
        assertTrue(blob.size < cells.size / 4)
        val map = LightPollutionMap.parse(blob)
        assertContentEquals(cells, map.levels)
        for (row in listOf(0, 1, 599, 600, 899, 900, 1000, 1199, 1200, 1799)) {
            for (column in listOf(0, 1, 99, 100, 3499, 3500, 3599, 1234)) {
                assertEquals(cells[row * 3600 + column].toInt(), map.level(support.centre(row, column)), "$row, $column")
            }
        }
    }

    @Test
    fun testDecoderReadsRunsOfEveryLength() {
        for (run in listOf(1, 2, 15, 16, 17, 143, 144, 3583, 3584, 3599, 3600)) {
            val cells = support.cells { _, column -> if (column < run) 7 else 2 }
            assertContentEquals(cells, support.map(cells).levels, "run $run")
        }
    }

    @Test
    fun testDecoderRejectsMalformedFiles() {
        val good = support.encode(support.darkCells())
        assertEquals(16 + 1800 * 3, good.size)
        assertContentEquals(good, support.file(listOf(0xF0, 0x80, 0x1C)))
        val bad = mapOf(
            "magic" to support.header(5400, magic = "LPM2") + good.copyOfRange(16, good.size),
            "width" to support.header(5400, gridWidth = 3599) + good.copyOfRange(16, good.size),
            "height" to support.header(5400, gridHeight = 1799) + good.copyOfRange(16, good.size),
            "levels" to support.header(5400, levels = 15) + good.copyOfRange(16, good.size),
            "reserved" to support.header(5400, reserved = 1) + good.copyOfRange(16, good.size),
            "length" to support.header(5399) + good.copyOfRange(16, good.size),
            "short header" to good.copyOfRange(0, 10),
            "truncated" to good.copyOfRange(0, good.size - 1),
            "trailing" to good + byteArrayOf(0),
            "run past the row" to support.file(listOf(0xF0, 0x81, 0x1C)),
            "row too short" to support.file(listOf(0x0F)),
            "varint too long" to support.file(listOf(0xF0, 0x80, 0x80, 0x80, 0x01)),
            "open varint" to support.file(listOf(0xF0, 0x80)),
            "too large" to ByteArray(LightPollutionMap.maximumFileBytes + 1),
        )
        for ((name, data) in bad) {
            val error = assertFailsWith<SkyQualityError.LightPollutionMapUnavailable>(name) { LightPollutionMap.parse(data) }
            assertTrue(error.description.startsWith("The light pollution map is unavailable: "), name)
        }
    }

    @Test
    fun testErrorSentenceNamesTheReason() {
        val error = SkyQualityError.LightPollutionMapUnavailable("the file is not a light pollution map")
        assertEquals("The light pollution map is unavailable: the file is not a light pollution map", error.description)
        assertEquals(error.description, error.message)
        assertEquals(error.description, error.errorDescription)
    }

    // MARK: Cells

    @Test
    fun testCellsAreIndexedFromTheNorthWestCorner() {
        val cells = support.darkCells()
        for ((row, column, level) in listOf(
            Triple(0, 0, 1), Triple(0, 3599, 2), Triple(1799, 0, 3), Triple(1799, 3599, 4),
            Triple(899, 1800, 5), Triple(900, 1799, 6),
        )) {
            cells[row * 3600 + column] = level.toByte()
        }
        val map = support.map(cells)
        assertEquals(1, map.level(GeographicLocation(90.0, -180.0)))
        assertEquals(1, map.level(GeographicLocation(89.95, -179.95)))
        assertEquals(2, map.level(GeographicLocation(90.0, 179.99)))
        assertEquals(3, map.level(GeographicLocation(-90.0, -180.0)))
        assertEquals(4, map.level(GeographicLocation(-90.0, 179.95)))
        assertEquals(5, map.level(GeographicLocation(0.05, 0.05)))
        assertEquals(6, map.level(GeographicLocation(-0.05, -0.05)))
        // 180° E is the first column again (longitudes wrap), and a latitude beyond the pole is the pole.
        assertEquals(1, map.level(GeographicLocation(89.95, 180.0)))
        assertEquals(1, map.level(GeographicLocation(95.0, -180.0)))
        assertEquals(0, map.level(GeographicLocation(45.0, 45.0)))
    }

    // MARK: The shipped file

    @Test
    fun testShippedMapLoadsAndOceansAreDark() {
        assertTrue(LightPollutionMap.bundledData().size in 100_000..200_000)
        for ((latitude, longitude) in listOf(0.0 to -150.0, -40.0 to -130.0, 30.0 to -40.0, -85.0 to 0.0, 72.0 to -40.0)) {
            assertEquals(0, shipped.level(GeographicLocation(latitude, longitude)), "$latitude, $longitude")
        }
        assertEquals(0, shipped.level(GeographicLocation(23.0, 10.0)))
    }

    @Test
    fun testKnownCitiesAreBright() {
        for (reference in references.filter { it.name in listOf("New York, Times Square", "London, Trafalgar Square", "Tokyo, Shinjuku", "Las Vegas, the Strip", "Munich, Marienplatz") }) {
            assertTrue(shipped.level(GeographicLocation(reference.latitude, reference.longitude)) >= 14, reference.name)
        }
        assertTrue(shipped.level(GeographicLocation(40.0150, -105.2705)) >= 12)
        assertTrue(shipped.level(GeographicLocation(48.8566, 2.3522)) >= 14)
        assertTrue(shipped.level(GeographicLocation(34.0522, -118.2437)) >= 14)
    }

    @Test
    fun testReferencePlacesKeepTheirLevelsGlowAndClass() {
        for (reference in references) {
            val place = GeographicLocation(reference.latitude, reference.longitude)
            assertEquals(reference.level, shipped.level(place), reference.name)
            assertEquals(reference.glow, shipped.skyGlowIndex(place), 1e-5, reference.name)
            val estimate = shipped.bortleEstimate(place)
            assertEquals(reference.bortleClass, estimate.bortleClass, reference.name)
            assertEquals(reference.level, estimate.level, reference.name)
            assertEquals(shipped.skyGlowIndex(place), estimate.skyGlowIndex, reference.name)
        }
        // 21 of the 25 places fall in the class published for them (the table in the documentation of the map).
        assertEquals(21, references.count { it.bortleClass in published.getValue(it.name) })
    }

    @Test
    fun testBrightCitiesAreClassNineAndDarkSitesAreLow() {
        assertEquals(9, shipped.bortleEstimate(GeographicLocation(40.7580, -73.9855)).bortleClass)
        assertTrue(shipped.bortleEstimate(GeographicLocation(36.2296, -116.7673)).bortleClass <= 2)
        assertTrue(shipped.skyGlowIndex(GeographicLocation(40.7580, -73.9855)) > shipped.skyGlowIndex(GeographicLocation(40.0150, -105.2705)))
    }

    // MARK: Bortle thresholds

    @Test
    fun testGlowThresholdsAreMonotonicAndMapToClasses() {
        val thresholds = BortleEstimate.glowThresholds
        assertEquals(8, thresholds.size)
        assertTrue(thresholds.first() > 0)
        for (index in 1 until thresholds.size) assertTrue(thresholds[index] > thresholds[index - 1], "threshold $index")
        assertEquals(1, BortleEstimate.bortleClass(0.0))
        assertEquals(1, BortleEstimate.bortleClass(-3.0))
        assertEquals(1, BortleEstimate.bortleClass(Double.NaN))
        assertEquals(9, BortleEstimate.bortleClass(1e9))
        assertEquals(9, BortleEstimate.bortleClass(Double.POSITIVE_INFINITY))
        for ((index, threshold) in thresholds.withIndex()) {
            assertEquals(index + 2, BortleEstimate.bortleClass(threshold), "at threshold $index")
            assertEquals(index + 1, BortleEstimate.bortleClass(threshold * 0.999), "below threshold $index")
        }
        var previous = 1
        var glow = 0.0
        while (glow < 2.0) {
            val bortleClass = BortleEstimate.bortleClass(glow)
            assertTrue(bortleClass >= previous, "glow $glow")
            previous = bortleClass
            glow += 0.0005
        }
        assertEquals(9, previous)
    }

    @Test
    fun testEstimateHasALabelASummaryAndTheCaveat() {
        val labels = (1..9).map { BortleEstimate.label(it) }
        assertEquals(9, labels.toSet().size)
        assertEquals("Excellent dark sky", labels.first())
        assertEquals("Inner-city sky", labels.last())
        assertEquals(labels.first(), BortleEstimate.label(-4))
        assertEquals(labels.last(), BortleEstimate.label(40))
        val rural = BortleEstimate(0.003, 0)
        assertEquals(3, rural.bortleClass)
        assertEquals("Rural sky", rural.label)
        assertEquals("Bortle ~3 (rural sky)", rural.summary)
        val transition = BortleEstimate(0.01, 2)
        assertEquals(4, transition.bortleClass)
        assertEquals("Bortle ~4 (rural/suburban transition)", transition.summary)
        assertEquals("Estimated from 2016 satellite night-light imagery; not a sky measurement", BortleEstimate.caveat)
        assertEquals(15, BortleEstimate(0.0, 99).level)
        assertEquals(0, BortleEstimate(0.0, -5).level)
        val broken = BortleEstimate(Double.NaN, 3)
        assertEquals(1, broken.bortleClass)
        assertEquals(0.0, broken.skyGlowIndex)
        assertEquals(BortleEstimate(0.003, 0), rural)
    }

    // MARK: Sky glow

    @Test
    fun testSkyGlowOfASingleLitCellFollowsDistanceToTheMinusTwoAndAHalf() {
        val cells = support.darkCells()
        cells[900 * 3600 + 1800] = 11
        val map = support.map(cells)
        val centre = support.centre(900, 1800)
        val luminance = LightPollutionMap.levelLuminance[11]
        assertEquals(0.1125, luminance)
        // In the cell itself, and anywhere within the core distance, the cell counts at its own luminance.
        assertEquals(luminance * cos(Math.toRadians(centre.latitude)), map.skyGlowIndex(centre), 1e-12)
        var previous = Double.MAX_VALUE
        for (degrees in listOf(0.05, 0.1, 0.2, 0.4, 0.8)) {
            val site = GeographicLocation(centre.latitude, centre.longitude + degrees)
            val distance = degrees * LightPollutionMap.kilometersPerDegree * cos(Math.toRadians(centre.latitude))
            val ratio = maxOf(distance, LightPollutionMap.glowCoreKm) / LightPollutionMap.glowCoreKm
            val expected = luminance * cos(Math.toRadians(centre.latitude)) / ratio.pow(2.5)
            val glow = map.skyGlowIndex(site)
            assertEquals(expected, glow, expected * 1e-9, "$degrees°")
            assertTrue(glow < previous, "$degrees°")
            previous = glow
        }
        // 1.2° is 133 km: out of reach, in every direction.
        assertEquals(0.0, map.skyGlowIndex(GeographicLocation(centre.latitude, centre.longitude + 1.2)))
        assertEquals(0.0, map.skyGlowIndex(GeographicLocation(centre.latitude + 1.2, centre.longitude)))
        assertEquals(0.0, map.skyGlowIndex(GeographicLocation(centre.latitude - 1.2, centre.longitude - 1.2)))
        assertEquals(0.0, support.map(support.darkCells()).skyGlowIndex(centre))
    }

    @Test
    fun testSkyGlowReachesAcrossTheAntimeridian() {
        val east = support.darkCells()
        east[900 * 3600 + 3599] = 12
        val west = support.darkCells()
        west[900 * 3600 + 1799] = 12
        val across = support.map(east).skyGlowIndex(GeographicLocation(-0.05, -179.95))
        val inland = support.map(west).skyGlowIndex(GeographicLocation(-0.05, 0.05))
        assertTrue(across > 0)
        assertEquals(inland, across, inland * 1e-9)
    }

    @Test
    fun testSkyGlowIsFiniteAtThePoles() {
        val bright = support.map(support.cells { _, _ -> 15 })
        for (latitude in listOf(90.0, 89.95, 85.0, -85.0, -89.95, -90.0)) {
            val glow = bright.skyGlowIndex(GeographicLocation(latitude, 30.0))
            assertTrue(glow.isFinite() && glow > 0, "$latitude")
        }
        val equator = bright.skyGlowIndex(GeographicLocation(0.0, 30.0))
        assertTrue(equator > 1.0)
    }

    // MARK: Directions

    @Test
    fun testDarkestDirectionOfASyntheticGradient() {
        // Level 10 at 0° E, one level more per 0.1° eastward: the east is brighter, the west darker.
        val map = support.map(support.cells { _, column -> (column - 1790).coerceIn(0, 15) })
        val site = GeographicLocation(0.0, 0.0)
        val directions = map.darkestDirection(site)
        assertEquals(16, directions.size)
        assertEquals(CompassPoint.entries.toList(), directions.map { it.direction })
        assertEquals(listOf(0.0, 22.5, 45.0), directions.take(3).map { it.azimuth })
        fun mean(point: CompassPoint) = directions[point.rawValue].meanLevel
        assertEquals(10.0, mean(CompassPoint.NORTH), 1e-12)
        assertEquals(10.0, mean(CompassPoint.SOUTH), 1e-12)
        assertEquals(11.0, mean(CompassPoint.EAST), 1e-12)
        assertEquals(8.0, mean(CompassPoint.WEST), 1e-12)
        assertTrue(mean(CompassPoint.WEST) < mean(CompassPoint.WEST_NORTHWEST))
        assertTrue(mean(CompassPoint.EAST) > mean(CompassPoint.NORTH))
        val darkest = assertNotNull(directions.darkest)
        assertEquals(CompassPoint.WEST, darkest.direction)
        assertEquals(CompassPoint.WEST, assertNotNull(directions.darkestHint).direction)
        assertEquals(8.0 / 15.0, darkest.glow, 1e-12)
        // The ring: exact at the sector centres, linear between them, periodic.
        assertEquals(darkest.glow, directions.horizonGlow(270.0), 1e-12)
        assertEquals(directions.horizonGlow(270.0), directions.horizonGlow(-90.0), 1e-12)
        assertEquals(directions.horizonGlow(270.0), directions.horizonGlow(630.0), 1e-12)
        assertEquals((directions[12].glow + directions[13].glow) / 2, directions.horizonGlow(281.25), 1e-12)
        assertEquals((directions[15].glow + directions[0].glow) / 2, directions.horizonGlow(348.75), 1e-12)
        assertEquals(directions[0].glow, directions.horizonGlow(360.0), 1e-12)
        assertTrue(directions.horizonGlow(90.0) > directions.horizonGlow(0.0))
        assertTrue(directions.horizonGlow(0.0) > directions.horizonGlow(270.0))
        assertEquals(0.0, directions.horizonGlow(Double.NaN))
        assertEquals(0.0, emptyList<DirectionBrightness>().horizonGlow(10.0))
    }

    @Test
    fun testDarkestDirectionHasNoHintWhereEveryDirectionIsAlike() {
        val flat = support.map(support.cells { _, _ -> 5 })
        val directions = flat.darkestDirection(GeographicLocation(10.0, 20.0))
        assertTrue(directions.all { it.meanLevel == 5.0 })
        assertEquals(CompassPoint.NORTH, assertNotNull(directions.darkest).direction)
        assertNull(directions.darkestHint)
        assertNull(emptyList<DirectionBrightness>().darkest)
        assertNull(emptyList<DirectionBrightness>().darkestHint)
    }

    @Test
    fun testDarkestDirectionLimitsTheRadius() {
        val map = support.map(support.cells { _, column -> (column - 1790).coerceIn(0, 15) })
        val site = GeographicLocation(0.0, 0.0)
        assertEquals(map.darkestDirection(site, 30.0), map.darkestDirection(site, Double.NaN))
        assertEquals(map.darkestDirection(site), map.darkestDirection(site, 30.0))
        assertEquals(map.darkestDirection(site, 1.0), map.darkestDirection(site, -50.0))
        assertEquals(map.darkestDirection(site, 200.0), map.darkestDirection(site, 1e9))
        assertEquals(16, map.darkestDirection(site, Double.POSITIVE_INFINITY).size)
    }

    @Test
    fun testDarkestDirectionAtRealSites() {
        val newYork = shipped.darkestDirection(GeographicLocation(40.7580, -73.9855))
        assertEquals(16, newYork.size)
        assertTrue(newYork.all { it.meanLevel in 0.0..15.0 })
        assertTrue(newYork.all { it.meanLevel >= 12.0 })
        val ocean = shipped.darkestDirection(GeographicLocation(0.0, -150.0))
        assertTrue(ocean.all { it.meanLevel == 0.0 })
        assertNull(ocean.darkestHint)
        assertFalse(ocean.any { it.glow > 0 })
    }

    @Test
    fun testDarkestDirectionAtBoulderMatchesTheGeneratorsModel() {
        // From Tools/build_light_pollution.py's model of the same rays (Python, not this code): the foothills to the
        // south-west and west are dark, Denver to the east and south-east is bright.
        val expected = listOf(
            4.0, 7.833333333333333, 10.666666666666666, 9.833333333333334, 9.666666666666666, 12.833333333333334,
            11.666666666666666, 10.666666666666666, 7.5, 1.3333333333333333, 1.3333333333333333, 2.0, 2.0, 2.0, 2.0, 2.0,
        )
        val directions = shipped.darkestDirection(GeographicLocation(40.0150, -105.2705))
        assertEquals(expected.size, directions.size)
        for (index in expected.indices) assertEquals(expected[index], directions[index].meanLevel, 1e-9, "direction $index")
        assertEquals(CompassPoint.SOUTH_SOUTHWEST, assertNotNull(directions.darkestHint).direction)
    }
}

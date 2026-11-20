package net.kuecker.lodestar.kit.horizon

import net.kuecker.lodestar.kit.horizon.TerrainTestSupport as Support
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainTileTests {
    private val side = TerrainTile.lowResolutionSamples

    private fun parse(latitude: Int = 47, longitude: Int = 8, sample: (Int, Int) -> Int): TerrainTile =
        TerrainTile.parse(latitude, longitude, Support.bytes(side, sample))

    /** The latitude of row [row] of a tile whose corner is 47° N. */
    private fun latitude(row: Double): Double = 48 - row / 1200

    /** The longitude of column [column] of a tile whose corner is 8° E. */
    private fun longitude(column: Double): Double = 8 + column / 1200

    // MARK: Names

    @Test
    fun testKeysAreNamedLikeTheTileSet() {
        assertEquals("N47E008", assertNotNull(TerrainTileKey.create(47, 8)).name)
        assertEquals("S34W071", assertNotNull(TerrainTileKey.create(-34, -71)).name)
        assertEquals("N00E000", assertNotNull(TerrainTileKey.create(0, 0)).name)
        assertEquals("S01W001", assertNotNull(TerrainTileKey.create(-1, -1)).name)
        assertEquals("N89E179", assertNotNull(TerrainTileKey.create(89, 179)).name)
        assertEquals("S90W180", assertNotNull(TerrainTileKey.create(-90, -180)).name)
        assertEquals("N47/N47E008.hgt.gz", assertNotNull(TerrainTileKey.create(47, 8)).path)
        assertEquals("S34/S34W071.hgt.gz", assertNotNull(TerrainTileKey.create(-34, -71)).path)
    }

    @Test
    fun testKeysOutsideTheGlobeAreNil() {
        assertNull(TerrainTileKey.create(90, 0))
        assertNull(TerrainTileKey.create(-91, 0))
        assertNull(TerrainTileKey.create(0, 180))
        assertNull(TerrainTileKey.create(0, -181))
    }

    @Test
    fun testKeyContainingAPoint() {
        assertEquals("N47E008", TerrainTileKey.containing(47.5, 8.5)?.name)
        assertEquals("N47E008", TerrainTileKey.containing(47.0, 8.0)?.name)
        assertEquals("S01W001", TerrainTileKey.containing(-0.5, -0.5)?.name)
        assertEquals("S01W001", TerrainTileKey.containing(-0.0001, -0.0001)?.name)
        assertNull(TerrainTileKey.containing(90.0, 0.0))
        assertNull(TerrainTileKey.containing(Double.NaN, 0.0))
        assertNull(TerrainTileKey.containing(0.0, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testKeysOrderByLatitudeThenLongitude() {
        val keys = listOf(Pair(47, 9), Pair(46, 9), Pair(47, 7)).map { assertNotNull(TerrainTileKey.create(it.first, it.second)) }
        assertEquals(listOf("N46E009", "N47E007", "N47E009"), keys.sorted().map { it.name })
    }

    // MARK: Parsing

    @Test
    fun testBigEndianSignedSamplesAreRead() {
        val tile = parse { row, column ->
            when {
                row == 0 && column == 0 -> 400
                row == 0 && column == 1200 -> -2
                row == 1200 && column == 0 -> 256
                row == 1200 && column == 1200 -> 8849
                else -> 1
            }
        }
        assertEquals(47, tile.latitude)
        assertEquals(8, tile.longitude)
        assertEquals(1201, tile.samplesPerSide)
        assertEquals("N47E008", tile.key.name)
        assertEquals(400.0, tile.elevation(48.0, 8.0))
        assertEquals(-2.0, tile.elevation(48.0, 9.0))
        assertEquals(256.0, tile.elevation(47.0, 8.0))
        assertEquals(8849.0, tile.elevation(47.0, 9.0))
        assertEquals(0, tile.voidCount)
    }

    @Test
    fun testTheHighByteComesFirst() {
        // 0x01 0x90 is 400, 0xFF 0x38 is -200 (the high byte comes first).
        val data = Support.bytes(side) { _, _ -> 0 }
        data[0] = 0x01
        data[1] = 0x90.toByte()
        data[2] = 0xFF.toByte()
        data[3] = 0x38
        val tile = TerrainTile.parse(47, 8, data)
        assertEquals(400.0, tile.elevation(48.0, 8.0))
        assertEquals(-200.0, assertNotNull(tile.elevation(48.0, longitude(1.0))), 1e-6)
    }

    @Test
    fun testTheOneArcSecondGridIsSupported() {
        val big = TerrainTile.highResolutionSamples
        val data = Support.bytes(big) { row, column -> (row + column) % 1000 }
        assertEquals(big * big * 2, data.size)
        val tile = TerrainTile.parse(-34, -71, data)
        assertEquals(3601, tile.samplesPerSide)
        assertEquals("S34W071", tile.key.name)
        // Row 0 is the northern edge (33° S), column 3600 the eastern one (70° W).
        assertEquals(0.0, tile.elevation(-33.0, -71.0))
        assertEquals(600.0, tile.elevation(-33.0, -70.0))
        assertEquals(600.0, tile.elevation(-34.0, -71.0))
        assertEquals(((3600 + 3600) % 1000).toDouble(), tile.elevation(-34.0, -70.0))
        // Halfway along the first row: columns 1799 and 1800 hold 799 and 800.
        assertEquals(799.5, assertNotNull(tile.elevation(-33.0, -71.0 + 1799.5 / 3600)), 1e-9)
    }

    @Test
    fun testWrongSizesAndCornersAreRefused() {
        assertEquals(
            TerrainTileError.UnsupportedSize(1000),
            assertFailsWith<TerrainTileError.UnsupportedSize> { TerrainTile.parse(47, 8, ByteArray(1000)) },
        )
        assertFailsWith<TerrainTileError.UnsupportedSize> { TerrainTile.parse(47, 8, ByteArray(0)) }
        assertFailsWith<TerrainTileError.UnsupportedSize> { TerrainTile.parse(47, 8, ByteArray(side * side * 2 + 1)) }
        assertFailsWith<TerrainTileError.UnsupportedSize> { TerrainTile.parse(47, 8, ByteArray(side * side * 2 - 1)) }
        val data = Support.bytes(side) { _, _ -> 0 }
        assertEquals(TerrainTileError.InvalidOrigin(90, 0), assertFailsWith<TerrainTileError.InvalidOrigin> { TerrainTile.parse(90, 0, data) })
        assertFailsWith<TerrainTileError.InvalidOrigin> { TerrainTile.parse(0, 180, data) }
        assertFailsWith<TerrainTileError.InvalidOrigin> { TerrainTile.parse(-91, 0, data) }
    }

    // MARK: Elevation

    @Test
    fun testBilinearInterpolationReproducesALinearSlope() {
        val tile = parse { row, column -> row + 2 * column }
        // A node: row 20, column 10.
        assertEquals(40.0, assertNotNull(tile.elevation(latitude(20.0), longitude(10.0))), 1e-6)
        // The middle of a cell: 20.25 rows and 10.5 columns from the north-west corner.
        assertEquals(20.25 + 2 * 10.5, assertNotNull(tile.elevation(latitude(20.25), longitude(10.5))), 1e-6)
        assertEquals(0.75 + 2 * 1199.5, assertNotNull(tile.elevation(latitude(0.75), longitude(1199.5))), 1e-6)
        // The south-east corner.
        assertEquals(1200.0 + 2400.0, assertNotNull(tile.elevation(47.0, 9.0)), 1e-9)
    }

    @Test
    fun testTheBorderBelongsToTheTile() {
        val tile = parse { _, _ -> 100 }
        for ((latitude, longitude) in listOf(47.0 to 8.0, 48.0 to 8.0, 47.0 to 9.0, 48.0 to 9.0, 47.5 to 8.0, 48.0 to 8.5)) {
            assertEquals(100.0, tile.elevation(latitude, longitude), "$latitude, $longitude")
        }
    }

    @Test
    fun testPointsOutsideTheTileAreNil() {
        val tile = parse { _, _ -> 100 }
        assertNull(tile.elevation(46.9999, 8.5))
        assertNull(tile.elevation(48.0001, 8.5))
        assertNull(tile.elevation(47.5, 7.9999))
        assertNull(tile.elevation(47.5, 9.0001))
        assertNull(tile.elevation(Double.NaN, 8.5))
        assertNull(tile.elevation(47.5, Double.POSITIVE_INFINITY))
    }

    @Test
    fun testVoidsGiveNilExceptForPointsThatDoNotUseThem() {
        val tile = parse { row, column -> if (row == 601 && column == 601) -32768 else 50 }
        assertEquals(1, tile.voidCount)
        // On the void and anywhere its weight is positive.
        assertNull(tile.elevation(latitude(601.0), longitude(601.0)))
        assertNull(tile.elevation(latitude(600.5), longitude(600.5)))
        assertNull(tile.elevation(latitude(601.25), longitude(600.9)))
        // On the node next to it the void has weight 0: row 600, column 600 are exact binary fractions of the tile.
        assertEquals(50.0, tile.elevation(47.5, 8.5))
        assertEquals(50.0, tile.elevation(47.75, 8.25))
        assertEquals(50.0, tile.elevation(latitude(300.0), longitude(900.0)))
    }

    @Test
    fun testElevationsOutsideWhatLandReachesAreVoids() {
        val tile = parse { row, column ->
            when {
                row == 10 && column == 10 -> 9001
                row == 10 && column == 20 -> -501
                row == 10 && column == 30 -> 20_000
                row == 10 && column == 40 -> 9000
                row == 10 && column == 50 -> -500
                else -> 3
            }
        }
        assertEquals(3, tile.voidCount)
        assertNull(tile.elevation(latitude(10.0), longitude(10.0)))
        assertNull(tile.elevation(latitude(10.0), longitude(20.0)))
        assertNull(tile.elevation(latitude(10.0), longitude(30.0)))
        assertEquals(9000.0, assertNotNull(tile.elevation(latitude(10.0), longitude(40.0))), 1e-6)
        assertEquals(-500.0, assertNotNull(tile.elevation(latitude(10.0), longitude(50.0))), 1e-6)
    }

    @Test
    fun testNeighbouringTilesAgreeOnTheirSharedEdge() {
        val ridge = { latitude: Double, longitude: Double -> 200.0 + 100.0 * TerrainWave.value(latitude, longitude) }
        val south = Support.tile(47, 8, ridge)
        val north = Support.tile(48, 8, ridge)
        for (step in 0..12) {
            val longitude = 8.0 + step / 12.0
            assertEquals(south.elevation(48.0, longitude), north.elevation(48.0, longitude))
        }
        assertTrue(assertNotNull(south.elevation(48.0, 8.3)) > 100)
    }
}

/** A smooth deterministic landscape for the seam test. */
private object TerrainWave {
    fun value(latitude: Double, longitude: Double): Double =
        kotlin.math.sin(latitude * 200.0) * kotlin.math.cos(longitude * 150.0)
}

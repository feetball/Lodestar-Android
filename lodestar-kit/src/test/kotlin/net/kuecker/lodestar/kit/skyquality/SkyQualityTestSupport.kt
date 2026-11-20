package net.kuecker.lodestar.kit.skyquality

import net.kuecker.lodestar.kit.foundation.GeographicLocation

/** Synthetic light pollution maps and file images shared by the SkyQuality tests. */
internal object SkyQualityTestSupport {
    const val width = LightPollutionMap.width
    const val height = LightPollutionMap.height

    /** The cells of an all-dark map. */
    fun darkCells(): ByteArray = ByteArray(width * height)

    /** The cells of a map whose level at (row, column) is `level(row, column)`. */
    fun cells(level: (Int, Int) -> Int): ByteArray {
        val cells = ByteArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) cells[row * width + column] = level(row, column).toByte()
        }
        return cells
    }

    /** The file image of a grid: the 16-byte header and the rows as runs (the format of `build_light_pollution.py`). */
    fun encode(cells: ByteArray): ByteArray {
        val data = ArrayList<Byte>()
        for (row in 0 until height) {
            var column = 0
            while (column < width) {
                val value = cells[row * width + column]
                var end = column + 1
                while (end < width && cells[row * width + end] == value) end += 1
                val run = end - column
                if (run <= 15) {
                    data.add((((run - 1) shl 4) or value.toInt()).toByte())
                } else {
                    data.add((0xF0 or value.toInt()).toByte())
                    var extra = run - 16
                    while (true) {
                        val low = extra and 0x7F
                        extra = extra shr 7
                        data.add((low or (if (extra != 0) 0x80 else 0)).toByte())
                        if (extra == 0) break
                    }
                }
                column = end
            }
        }
        return header(data.size) + data.toByteArray()
    }

    /** A header for `length` bytes of run data; the other fields may be overridden to build bad files. */
    fun header(
        length: Int,
        gridWidth: Int = width,
        gridHeight: Int = height,
        levels: Int = 16,
        magic: String = "LPM1",
        reserved: Int = 0,
    ): ByteArray {
        val bytes = ByteArray(16)
        for (index in 0 until 4) bytes[index] = magic[index].code.toByte()
        bytes[4] = gridWidth.toByte()
        bytes[5] = (gridWidth shr 8).toByte()
        bytes[6] = gridHeight.toByte()
        bytes[7] = (gridHeight shr 8).toByte()
        bytes[8] = levels.toByte()
        bytes[9] = reserved.toByte()
        for (index in 0 until 4) bytes[10 + index] = (length shr (8 * index)).toByte()
        return bytes
    }

    /** The map of a grid, through the encoder and the decoder. */
    fun map(cells: ByteArray): LightPollutionMap = LightPollutionMap.parse(encode(cells))

    /** The row and column of the cell whose centre is the returned place. */
    fun centre(row: Int, column: Int): GeographicLocation =
        GeographicLocation(90.0 - (row + 0.5) * 0.1, -180.0 + (column + 0.5) * 0.1)

    /** The bytes of an all-dark file whose first row is replaced by `firstRow` (every other row is one run of 3600). */
    fun file(firstRow: List<Int>): ByteArray {
        val darkRow = listOf(0xF0, 0x80, 0x1C)
        val data = (firstRow + (1 until height).flatMap { darkRow }).map { it.toByte() }.toByteArray()
        return header(data.size) + data
    }
}

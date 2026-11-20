package net.kuecker.lodestar.kit.horizon

/**
 * Reads the header of a gzip file (RFC 1952) so that an app can hand the raw DEFLATE data to its own inflater: iOS
 * `Compression` (`COMPRESSION_ZLIB` is raw DEFLATE) and `java.util.zip.Inflater(true)` on Android. The engine does not
 * inflate anything itself.
 *
 * Only the first member of a file is looked at; files with more than one member (concatenated gzip files) are not
 * what the terrain tiles are. The optional header checksum (`FHCRC`) is skipped, not verified: the inflater checks the
 * data itself, and the trailer's own CRC-32 can be checked by the caller.
 */
object GzipHeader {
    /** The length of the trailer: the CRC-32 and the uncompressed length, four bytes each. */
    const val trailerLength: Int = 8

    /** The most bytes a file name or comment may have before the header is rejected. */
    const val maximumTextLength: Int = 4096

    private const val fixedLength = 10
    private const val headerChecksumFlag = 0x02
    private const val extraFlag = 0x04
    private const val nameFlag = 0x08
    private const val commentFlag = 0x10
    private const val reservedFlags = 0xE0
    private const val deflateMethod = 8

    /**
     * The range of the raw DEFLATE data in [data]: from the end of the header (magic, method, flags, and the optional
     * extra field, file name, comment and header checksum that the flags announce) to the start of the 8-byte
     * trailer, as `start until end`. Offsets count from the first byte of [data].
     *
     * Returns `null` when [data] is not a gzip file the engine understands: wrong magic number or compression method,
     * reserved flags set, a header (or a name or comment longer than [maximumTextLength]) that is cut short, or no
     * room for at least one byte of data before the trailer.
     */
    fun payloadRange(data: ByteArray): IntRange? {
        val start = headerLength(data) ?: return null
        val end = data.size - trailerLength
        if (start >= end) return null
        return start until end
    }

    /**
     * The length of the uncompressed data from the trailer's `ISIZE` field (little endian, modulo 2³²), or `null`
     * when [payloadRange] is `null` or the number does not fit an `Int`. The apps use it to size the output of the
     * inflater and to refuse a file that would inflate to something else than a tile.
     */
    fun uncompressedLength(data: ByteArray): Int? {
        if (payloadRange(data) == null) return null
        val last = data.size
        var value = 0L
        for (offset in 1..4) value = (value shl 8) or (data[last - offset].toLong() and 0xFF)
        return if (value <= Int.MAX_VALUE) value.toInt() else null
    }

    /** The number of header bytes, or `null` when the header is not valid or is cut short. */
    private fun headerLength(data: ByteArray): Int? {
        if (data.size < fixedLength + trailerLength) return null
        if (byte(data, 0) != 0x1F || byte(data, 1) != 0x8B || byte(data, 2) != deflateMethod) return null
        val flags = byte(data, 3)
        if (flags and reservedFlags != 0) return null
        var position = fixedLength
        if (flags and extraFlag != 0) {
            if (position + 2 > data.size) return null
            position += 2 + (byte(data, position) or (byte(data, position + 1) shl 8))
            if (position > data.size) return null
        }
        if (flags and nameFlag != 0) position = (afterTerminator(data, position) ?: return null)
        if (flags and commentFlag != 0) position = (afterTerminator(data, position) ?: return null)
        if (flags and headerChecksumFlag != 0) position += 2
        if (position > data.size) return null
        return position
    }

    /** The position after the zero byte that ends the text starting at [start], or `null` without one in range. */
    private fun afterTerminator(data: ByteArray, start: Int): Int? {
        val limit = minOf(data.size, start + maximumTextLength + 1)
        var position = start
        while (position < limit) {
            if (data[position].toInt() == 0) return position + 1
            position += 1
        }
        return null
    }

    private fun byte(data: ByteArray, index: Int): Int = data[index].toInt() and 0xFF
}

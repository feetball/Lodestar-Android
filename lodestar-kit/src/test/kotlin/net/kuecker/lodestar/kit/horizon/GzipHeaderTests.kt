package net.kuecker.lodestar.kit.horizon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Hand-built gzip headers (RFC 1952) for [GzipHeader]. */
class GzipHeaderTests {
    private val text = "name.hgt".encodeToByteArray() + 0

    /** A gzip file of one member: a header with [flags] and [middle] (the optional fields), [payload], a trailer. */
    private fun file(
        flags: Int = 0,
        middle: ByteArray = ByteArray(0),
        payload: ByteArray = byteArrayOf(0x03, 0x00),
        isize: Long = 0x01_8B_BA_42,
        method: Int = 8,
        magic: List<Int> = listOf(0x1F, 0x8B),
    ): ByteArray {
        val header = ByteArray(10)
        header[0] = magic[0].toByte()
        header[1] = magic[1].toByte()
        header[2] = method.toByte()
        header[3] = flags.toByte()
        header[8] = 2
        header[9] = 0xFF.toByte()
        val trailer = ByteArray(8)
        for (offset in 0..3) trailer[4 + offset] = (isize shr (8 * offset)).toByte()
        return header + middle + payload + trailer
    }

    // MARK: Header cases

    @Test
    fun testMinimalHeaderHasTheDataAfterTenBytes() {
        val data = file()
        assertEquals(10 until 12, GzipHeader.payloadRange(data))
    }

    @Test
    fun testFileNameIsSkipped() {
        val data = file(flags = 0x08, middle = text)
        assertEquals(19 until 21, GzipHeader.payloadRange(data))
    }

    @Test
    fun testCommentIsSkipped() {
        val data = file(flags = 0x10, middle = "made by hand".encodeToByteArray() + 0)
        assertEquals(23 until 25, GzipHeader.payloadRange(data))
    }

    @Test
    fun testExtraFieldIsSkipped() {
        val data = file(flags = 0x04, middle = byteArrayOf(3, 0, 1, 2, 3))
        assertEquals(15 until 17, GzipHeader.payloadRange(data))
    }

    @Test
    fun testHeaderChecksumIsSkipped() {
        val data = file(flags = 0x02, middle = byteArrayOf(0x12, 0x34))
        assertEquals(12 until 14, GzipHeader.payloadRange(data))
    }

    @Test
    fun testAllOptionalFieldsInTheirOrder() {
        // Extra field (length 258 = 2 + 256 bytes, so the length's high byte counts), name, comment, checksum.
        val extra = byteArrayOf(0x02, 0x01) + ByteArray(258)
        val middle = extra + text + "c".encodeToByteArray() + 0 + byteArrayOf(0x55, 0x66)
        val data = file(flags = 0x1E, middle = middle)
        val start = 10 + 2 + 258 + 9 + 2 + 2
        assertEquals(start until start + 2, GzipHeader.payloadRange(data))
    }

    @Test
    fun testHeaderOfARealTileFile() {
        // The first 22 bytes of elevation-tiles-prod/skadi/N47/N47E008.hgt.gz: the file name flag, "N47E008.hgt".
        val header = byteArrayOf(
            0x1F, 0x8B.toByte(), 0x08, 0x08, 0x02, 0x3A, 0x1B, 0x57, 0x02, 0xFF.toByte(),
        ) + "N47E008.hgt".encodeToByteArray() + 0
        val data = header + ByteArray(100) + byteArrayOf(
            0xD4.toByte(), 0x2B, 0xA5.toByte(), 0x74, 0x42, 0xBA.toByte(), 0x8B.toByte(), 0x01,
        )
        assertEquals(22 until 122, GzipHeader.payloadRange(data))
        assertEquals(25_934_402, GzipHeader.uncompressedLength(data))
    }

    @Test
    fun testPayloadRangeCountsFromTheStartOfTheData() {
        val data = file(flags = 0x08, middle = text)
        val shifted = (byteArrayOf(9, 9, 9) + data).copyOfRange(3, data.size + 3)
        assertEquals(GzipHeader.payloadRange(data), GzipHeader.payloadRange(shifted))
    }

    // MARK: Rejections

    @Test
    fun testWrongMagicOrMethodIsRejected() {
        assertNull(GzipHeader.payloadRange(file(magic = listOf(0x1F, 0x8C))))
        assertNull(GzipHeader.payloadRange(file(magic = listOf(0x1E, 0x8B))))
        assertNull(GzipHeader.payloadRange(file(method = 7)))
        assertNull(GzipHeader.payloadRange(file(method = 0)))
    }

    @Test
    fun testReservedFlagsAreRejected() {
        for (flag in listOf(0x20, 0x40, 0x80)) assertNull(GzipHeader.payloadRange(file(flags = flag)))
    }

    @Test
    fun testCutShortHeadersAreRejected() {
        assertNull(GzipHeader.payloadRange(ByteArray(0)))
        assertNull(GzipHeader.payloadRange(byteArrayOf(0x1F, 0x8B.toByte(), 8)))
        // A name without its zero byte.
        assertNull(GzipHeader.payloadRange(file(flags = 0x08, middle = "no end".encodeToByteArray(), payload = ByteArray(0))))
        // An extra field longer than the file.
        assertNull(GzipHeader.payloadRange(file(flags = 0x04, middle = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 1, 2))))
        // The extra field's length bytes overlap the trailer, and no data is left.
        assertNull(GzipHeader.payloadRange(file(flags = 0x04, payload = ByteArray(0))))
    }

    @Test
    fun testNameLongerThanTheLimitIsRejected() {
        val long = ByteArray(GzipHeader.maximumTextLength + 1) { 'a'.code.toByte() } + 0
        assertNull(GzipHeader.payloadRange(file(flags = 0x08, middle = long)))
        val allowed = ByteArray(GzipHeader.maximumTextLength) { 'a'.code.toByte() } + 0
        assertEquals(
            (10 + allowed.size) until (12 + allowed.size),
            GzipHeader.payloadRange(file(flags = 0x08, middle = allowed)),
        )
    }

    @Test
    fun testNoRoomForDataIsRejected() {
        assertNull(GzipHeader.payloadRange(file(payload = ByteArray(0))))
        assertNull(GzipHeader.payloadRange(ByteArray(17)))
        assertEquals(10 until 11, GzipHeader.payloadRange(file(payload = byteArrayOf(3))))
    }

    // MARK: Uncompressed length

    @Test
    fun testUncompressedLengthIsTheLittleEndianTrailerField() {
        assertEquals(25_934_402, GzipHeader.uncompressedLength(file()))
        assertEquals(2_884_802, GzipHeader.uncompressedLength(file(isize = 2_884_802)))
        assertEquals(0, GzipHeader.uncompressedLength(file(isize = 0)))
        assertEquals(Int.MAX_VALUE, GzipHeader.uncompressedLength(file(isize = Int.MAX_VALUE.toLong())))
    }

    @Test
    fun testUncompressedLengthIsNilWhenItDoesNotFitOrTheFileIsNotGzip() {
        assertNull(GzipHeader.uncompressedLength(file(isize = 0x8000_0000L)))
        assertNull(GzipHeader.uncompressedLength(file(isize = 0xFFFF_FFFFL)))
        assertNull(GzipHeader.uncompressedLength(file(method = 7)))
        assertNull(GzipHeader.uncompressedLength(ByteArray(0)))
    }
}

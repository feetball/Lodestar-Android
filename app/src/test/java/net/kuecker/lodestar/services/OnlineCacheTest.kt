package net.kuecker.lodestar.services

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnlineCacheTest {
    private val meta = OnlineCacheMeta(savedAtMillis = 1_000, etag = "\"e\"", lastModified = "Mon", expiresMillis = 5, dataEpochMillis = 9, key = "32.8,-96.8")

    @Test
    fun theBytesAndTheMetadataComeBack() {
        val cache = OnlineCache(temporaryDirectory())
        cache.write("celestrak-stations", byteArrayOf(1, 2, 3), meta)
        val entry = assertNotNull(cache.read("celestrak-stations"))
        assertContentEquals(byteArrayOf(1, 2, 3), entry.bytes)
        assertEquals(meta, entry.meta)
        assertEquals(meta, cache.readMeta("celestrak-stations"))
    }

    @Test
    fun nothingCachedReadsAsNull() {
        val cache = OnlineCache(temporaryDirectory())
        assertNull(cache.read("mpc-comets"))
        assertNull(cache.readMeta("mpc-comets"))
    }

    @Test
    fun writingReplacesTheOldEntryAndLeavesNoTemporaryFile() {
        val directory = temporaryDirectory()
        val cache = OnlineCache(directory)
        cache.write("mpc-comets", byteArrayOf(1), meta)
        cache.write("mpc-comets", byteArrayOf(9, 9), meta.copy(savedAtMillis = 2_000))
        val entry = assertNotNull(cache.read("mpc-comets"))
        assertContentEquals(byteArrayOf(9, 9), entry.bytes)
        assertEquals(2_000, entry.meta.savedAtMillis)
        assertEquals(listOf("mpc-comets.cache"), directory.list()!!.toList())
    }

    @Test
    fun aWriteThatWasInterruptedLeavesTheOldDataAndIsCleanedUp() {
        val directory = temporaryDirectory()
        val cache = OnlineCache(directory)
        cache.write("mpc-comets", byteArrayOf(1, 2), meta)
        // A crash after the temporary file was started but before the rename.
        File(directory, "mpc-comets123.tmp").writeBytes(byteArrayOf(7, 7, 7))
        assertContentEquals(byteArrayOf(1, 2), assertNotNull(cache.read("mpc-comets")).bytes)
        OnlineCache(directory)
        assertFalse(File(directory, "mpc-comets123.tmp").exists())
        assertContentEquals(byteArrayOf(1, 2), assertNotNull(cache.read("mpc-comets")).bytes)
    }

    @Test
    fun aDamagedFileReadsAsNothing() {
        val directory = temporaryDirectory()
        val cache = OnlineCache(directory)
        cache.write("mpc-comets", ByteArray(100) { it.toByte() }, meta)
        val file = File(directory, "mpc-comets.cache")
        // Truncated: the length in the header promises more than the file holds.
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 10))
        assertNull(cache.read("mpc-comets"))
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.read("mpc-comets"))
        assertNull(cache.readMeta("mpc-comets"))
    }

    @Test
    fun updatingTheMetadataKeepsTheBytes() {
        val cache = OnlineCache(temporaryDirectory())
        cache.write("met-forecast", byteArrayOf(4, 5), meta)
        assertTrue(cache.updateMeta("met-forecast", meta.copy(savedAtMillis = 77)))
        val entry = assertNotNull(cache.read("met-forecast"))
        assertContentEquals(byteArrayOf(4, 5), entry.bytes)
        assertEquals(77, entry.meta.savedAtMillis)
        assertFalse(cache.updateMeta("celestrak-visual", meta))
    }

    @Test
    fun deletingRemovesTheEntry() {
        val cache = OnlineCache(temporaryDirectory())
        cache.write("met-forecast", byteArrayOf(4), meta)
        cache.delete("met-forecast")
        assertNull(cache.read("met-forecast"))
        cache.delete("met-forecast")
    }

    @Test
    fun aLargeEntryRoundTrips() {
        val cache = OnlineCache(temporaryDirectory())
        val big = ByteArray(5_000_000) { (it % 251).toByte() }
        cache.write("celestrak-starlink", big, meta)
        assertContentEquals(big, assertNotNull(cache.read("celestrak-starlink")).bytes)
    }

    @Test
    fun namesCannotEscapeTheDirectory() {
        val cache = OnlineCache(temporaryDirectory())
        val failure = runCatching { cache.read("../secret") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}

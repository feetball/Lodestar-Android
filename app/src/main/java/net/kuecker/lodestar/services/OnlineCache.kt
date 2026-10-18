package net.kuecker.lodestar.services

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * What is known about a cached download besides its bytes.
 *
 * @property savedAtMillis When the data was last confirmed current: downloaded, or answered with "not modified".
 * @property etag The server's `ETag`, for the next conditional request.
 * @property lastModified The server's `Last-Modified` header text, for `If-Modified-Since`.
 * @property expiresMillis When the server says the data expires (MET Norway), if it does.
 * @property dataEpochMillis The newest orbit epoch in the data (satellites), if it has one.
 * @property key Tells the copies of one resource apart: the rounded coordinates of a forecast.
 */
@Serializable
data class OnlineCacheMeta(
    val savedAtMillis: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val expiresMillis: Long? = null,
    val dataEpochMillis: Long? = null,
    val key: String? = null,
)

/** A cached download: its bytes and [meta]. */
class OnlineCacheEntry(val bytes: ByteArray, val meta: OnlineCacheMeta)

/**
 * The downloads kept on the phone, one file per resource in [directory] (the app's `noBackupFilesDir/online/`, which
 * Android never backs up). A file holds a small header with the metadata, then the length and the raw bytes, and is replaced
 * atomically: written next to it as a temporary file, flushed to disk, then renamed over the old one. A reader sees
 * the old data or the new, never half of either.
 */
class OnlineCache(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        directory.mkdirs()
        // Leftovers of a write that was interrupted.
        directory.listFiles { file -> file.name.endsWith(TEMPORARY_SUFFIX) }?.forEach { it.delete() }
    }

    /** The entry named [name], or `null` when there is none or the file is damaged. */
    fun read(name: String): OnlineCacheEntry? {
        val file = fileOf(name)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val meta = readMeta(input) ?: return null
                val size = input.readInt()
                if (size < 0 || size > file.length()) return null
                val bytes = ByteArray(size)
                input.readFully(bytes)
                OnlineCacheEntry(bytes, meta)
            }
        } catch (_: IOException) {
            null
        }
    }

    /** The metadata of [name] without reading its bytes, or `null`. */
    fun readMeta(name: String): OnlineCacheMeta? {
        val file = fileOf(name)
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { readMeta(it) }
        } catch (_: IOException) {
            null
        }
    }

    /** Stores [bytes] and [meta] as [name], replacing what was there. Throws [IOException] when the disk refuses. */
    fun write(name: String, bytes: ByteArray, meta: OnlineCacheMeta) {
        directory.mkdirs()
        val target = fileOf(name)
        val temporary = File.createTempFile(name, TEMPORARY_SUFFIX, directory)
        try {
            val header = json.encodeToString(OnlineCacheMeta.serializer(), meta).toByteArray(Charsets.UTF_8)
            java.io.FileOutputStream(temporary).use { stream ->
                DataOutputStream(stream.buffered()).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(header.size)
                    output.write(header)
                    output.writeInt(bytes.size)
                    output.write(bytes)
                    output.flush()
                    stream.fd.sync()
                }
            }
            move(temporary, target)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    /** Replaces the metadata of [name] and keeps its bytes. Returns `false` when there is nothing cached. */
    fun updateMeta(name: String, meta: OnlineCacheMeta): Boolean {
        val entry = read(name) ?: return false
        write(name, entry.bytes, meta)
        return true
    }

    /** Removes [name]. */
    fun delete(name: String) {
        fileOf(name).delete()
    }

    private fun fileOf(name: String): File {
        require(name.matches(NAME)) { "Not a cache name: $name" }
        return File(directory, "$name$SUFFIX")
    }

    private fun readMeta(input: DataInputStream): OnlineCacheMeta? {
        if (input.readInt() != MAGIC) return null
        val length = input.readInt()
        if (length !in 2..MAXIMUM_HEADER) return null
        val header = ByteArray(length)
        input.readFully(header)
        return try {
            json.decodeFromString(OnlineCacheMeta.serializer(), String(header, Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun move(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SUFFIX = ".cache"
        const val TEMPORARY_SUFFIX = ".tmp"
        const val MAGIC = 0x4C4F4431 // "LOD1"
        const val MAXIMUM_HEADER = 4096
        val NAME = Regex("[a-z0-9][a-z0-9-]{0,40}")
    }
}

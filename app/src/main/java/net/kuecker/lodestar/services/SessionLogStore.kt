package net.kuecker.lodestar.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import net.kuecker.lodestar.kit.foundation.instantFromTimeIntervalSince1970
import net.kuecker.lodestar.kit.foundation.timeIntervalSince1970
import net.kuecker.lodestar.kit.interchange.SessionLogCodec
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import java.io.File
import java.io.IOException
import kotlin.math.abs

/**
 * The session log: what was shot (or tried) on which night. It lives in one JSON file in the app's private storage and
 * holds at most [MAXIMUM_ENTRIES] entries. The file has the same format as the iOS app's.
 *
 * Changes are written after a short pause, off the main thread; [saveNow] writes at once (the app calls it when it
 * moves to the background).
 */
class SessionLogStore(private val file: File?, private val scope: CoroutineScope) {
    /** Every entry, newest first. */
    var entries by mutableStateOf(file?.let { SessionLogFile.read(it, MAXIMUM_ENTRIES) } ?: emptyList())
        private set

    /** The identifiers of the objects that have an entry marked as imaged. */
    var imagedTargetIDs by mutableStateOf(imagedIDs(entries))
        private set

    private var saveJob: Job? = null

    /** Whether another entry fits. */
    val hasRoom: Boolean
        get() = entries.size < MAXIMUM_ENTRIES

    /** The entries of one object, newest first. */
    fun entriesForTarget(targetID: String): List<SessionLogEntry> = entries.filter { it.targetID == targetID }

    /** Adds an entry; `false` when the log is full. */
    fun add(entry: SessionLogEntry): Boolean {
        if (!hasRoom) return false
        replace((entries + entry).sortedByDescending { it.date })
        return true
    }

    /** Replaces the entry that has the same identifier. */
    fun update(entry: SessionLogEntry) {
        if (entries.none { it.id == entry.id }) return
        replace(entries.map { if (it.id == entry.id) entry else it }.sortedByDescending { it.date })
    }

    /** Removes the entries with these identifiers. */
    fun delete(ids: Set<String>) {
        if (ids.isEmpty()) return
        replace(entries.filter { it.id !in ids })
    }

    /** Writes the log now. */
    fun saveNow() {
        saveJob?.cancel()
        saveJob = null
        val target = file ?: return
        val snapshot = entries
        scope.launch(Dispatchers.IO) { SessionLogFile.write(snapshot, target) }
    }

    private fun replace(next: List<SessionLogEntry>) {
        entries = next
        imagedTargetIDs = imagedIDs(next)
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DELAY_MS)
            saveNow()
        }
    }

    private fun imagedIDs(list: List<SessionLogEntry>): Set<String> = list.filter { it.isImaged }.mapTo(HashSet()) { it.targetID }

    companion object {
        /** The most entries kept. */
        const val MAXIMUM_ENTRIES = SessionLogCodec.maximumEntries

        private const val SAVE_DELAY_MS = 500L
    }
}

/**
 * The file format of the session log: `{"version": 1, "entries": [...]}`. Every value is optional on reading and is
 * cleaned by [SessionLogEntry], and an entry that cannot be read does not spoil the others.
 */
internal object SessionLogFile {
    private const val LARGEST_FILE_BYTES = 64_000_000L

    /** The entries of the file, newest first, at most [limit], without repeated identifiers; none for a file that cannot be read. */
    fun read(file: File, limit: Int): List<SessionLogEntry> {
        if (!file.isFile || file.length() <= 0 || file.length() > LARGEST_FILE_BYTES) return emptyList()
        val text = try {
            file.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            return emptyList()
        }
        return decode(text, limit)
    }

    fun decode(text: String, limit: Int): List<SessionLogEntry> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return emptyList()
        val array = root["entries"] as? JsonArray ?: return emptyList()
        val seen = HashSet<String>()
        val result = ArrayList<SessionLogEntry>()
        for (element in array) {
            val entry = entryFrom(element) ?: continue
            if (!seen.add(entry.id)) continue
            result.add(entry)
            if (result.size >= limit) break
        }
        return result.sortedByDescending { it.date }
    }

    fun encode(entries: List<SessionLogEntry>): String = buildJsonObject {
        put("version", 1)
        put("entries", JsonArray(entries.map(::jsonOf)))
    }.toString()

    /** Writes the entries to a temporary file and moves it over [file]; a failure leaves the old file in place. */
    fun write(entries: List<SessionLogEntry>, file: File) {
        synchronized(this) {
            val temporary = File(file.parentFile, file.name + ".tmp")
            try {
                temporary.writeText(encode(entries), Charsets.UTF_8)
                if (!temporary.renameTo(file)) {
                    file.delete()
                    temporary.renameTo(file)
                }
            } catch (_: IOException) {
                temporary.delete()
            }
        }
    }

    private fun jsonOf(entry: SessionLogEntry): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("time", entry.date.timeIntervalSince1970)
        put("targetID", entry.targetID)
        put("targetName", entry.targetName)
        put("rightAscension", entry.rightAscension)
        put("declination", entry.declination)
        put("siteName", entry.siteName)
        put("siteLatitude", entry.siteLatitude)
        put("siteLongitude", entry.siteLongitude)
        put("siteUTCOffsetMinutes", entry.siteUTCOffsetMinutes)
        put("camera", entry.camera)
        put("focalLength", entry.focalLength)
        put("lensAperture", entry.lensAperture)
        put("sensorPixelsX", entry.sensorPixelsX)
        put("sensorPixelsY", entry.sensorPixelsY)
        put("exposureSeconds", entry.exposureSeconds)
        put("iso", entry.iso)
        put("frameCount", entry.frameCount)
        put("filter", entry.filter)
        put("notes", entry.notes)
        put("isImaged", entry.isImaged)
    }

    private fun entryFrom(element: JsonElement): SessionLogEntry? {
        val o = element as? JsonObject ?: return null
        val time = o.double("time") ?: return null
        val targetID = o.string("targetID") ?: return null
        if (!time.isFinite() || abs(time) >= 4e11 || targetID.isEmpty()) return null
        return SessionLogEntry(
            id = o.string("id") ?: "",
            date = instantFromTimeIntervalSince1970(time),
            targetID = targetID,
            targetName = o.string("targetName") ?: "",
            rightAscension = o.double("rightAscension"),
            declination = o.double("declination"),
            siteName = o.string("siteName") ?: "",
            siteLatitude = o.double("siteLatitude"),
            siteLongitude = o.double("siteLongitude"),
            siteUTCOffsetMinutes = o.int("siteUTCOffsetMinutes"),
            camera = o.string("camera") ?: "",
            focalLength = o.double("focalLength"),
            lensAperture = o.double("lensAperture"),
            sensorPixelsX = o.int("sensorPixelsX"),
            sensorPixelsY = o.int("sensorPixelsY"),
            exposureSeconds = o.double("exposureSeconds") ?: 0.0,
            iso = o.int("iso") ?: 0,
            frameCount = o.int("frameCount") ?: 0,
            filter = o.string("filter") ?: "",
            notes = o.string("notes") ?: "",
            isImaged = (o["isImaged"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
}

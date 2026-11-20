package net.kuecker.lodestar.features.sessionlog

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.interchange.InterchangeError
import net.kuecker.lodestar.kit.interchange.ObservingListCodec
import net.kuecker.lodestar.kit.interchange.ObservingListImport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The kinds of file Lodestar saves. */
internal enum class InterchangeFileKind(val mimeType: String) {
    CSV("text/csv"),
    XML("text/xml"),
    TEXT("text/plain"),
}

/** A text file to hand to the system's "save as" screen. */
internal class InterchangeFile(val text: String, val kind: InterchangeFileKind, val filename: String)

/**
 * Returns a function that opens the system's "save as" screen for a file and writes its text where the person chose. The
 * save screen needs no permission.
 */
@Composable
internal fun rememberInterchangeExporter(): (InterchangeFile) -> Unit {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<String>(1) }
    val write: (Uri?) -> Unit = { uri ->
        val text = pending[0]
        pending[0] = null
        if (uri != null && text != null) {
            try {
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (_: IOException) {
                Toast.makeText(context, "The file could not be saved.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(InterchangeFileKind.CSV.mimeType), write)
    val xml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(InterchangeFileKind.XML.mimeType), write)
    val plain = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(InterchangeFileKind.TEXT.mimeType), write)
    return remember(csv, xml, plain) {
        { file ->
            pending[0] = file.text
            when (file.kind) {
                InterchangeFileKind.CSV -> csv.launch(file.filename)
                InterchangeFileKind.XML -> xml.launch(file.filename)
                InterchangeFileKind.TEXT -> plain.launch(file.filename)
            }
        }
    }
}

/**
 * Returns a function that opens the system's file picker and calls `onChosen` with the chosen file's [Uri]. Any file can be
 * chosen, because SkySafari lists have no common type. The picker needs no permission.
 */
@Composable
internal fun rememberListPicker(onChosen: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onChosen(uri)
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

/** Reading list files. */
internal object ObservingListFiles {
    /** The largest file that is opened, in bytes. */
    private const val LARGEST_FILE_BYTES = 16_000_000

    /**
     * Reads and decodes the list at [uri] off the main thread.
     *
     * @throws InterchangeError for a file that is not a usable list.
     * @throws IOException when the file cannot be read.
     */
    suspend fun read(context: Context, uri: Uri): ObservingListImport = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")
        val bytes = stream.use { readLimited(it, LARGEST_FILE_BYTES + 1) }
        if (bytes.size > LARGEST_FILE_BYTES) throw InterchangeError.TooLarge(ObservingListCodec.maximumTextScalars)
        ObservingListCodec.decode(text(bytes))
    }

    /** At most [limit] bytes of [stream]. */
    private fun readLimited(stream: InputStream, limit: Int): ByteArray {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(16_384)
        while (result.size() < limit) {
            val count = stream.read(buffer, 0, minOf(buffer.size, limit - result.size()))
            if (count < 0) break
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    /** The text of a file: UTF-16 with a byte order mark (spreadsheets write it), else UTF-8, else Windows Latin 1. */
    fun text(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            if ((first == 0xFF && second == 0xFE) || (first == 0xFE && second == 0xFF)) return String(bytes, Charsets.UTF_16)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    /** What a read error says to the person. */
    fun message(error: Throwable): String = if (error is InterchangeError) error.message ?: "The file is not a usable list." else "The file could not be opened."
}

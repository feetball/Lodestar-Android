package net.kuecker.lodestar.features.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.kuecker.lodestar.kit.phototools.CameraPhotoMetadata
import net.kuecker.lodestar.kit.phototools.CameraPhotoSuggestion

/** What was read from a photo: the EXIF numbers and the camera profile they suggest. */
internal data class CameraPhotoResult(val metadata: CameraPhotoMetadata, val suggestion: CameraPhotoSuggestion)

/** The photo picker and its outcome: [pick] opens the picker; [message] says why the last picture was no use. */
@Stable
internal class CameraPhotoImport(val pick: () -> Unit, val message: String?)

/**
 * Lets the user pick a picture with the system photo picker (no permission needed) and reads the camera and lens from
 * its EXIF data. [onResult] gets what was read, on the main thread. The picture is read once and not kept.
 */
@Composable
internal fun rememberCameraPhotoImport(onResult: (CameraPhotoResult) -> Unit): CameraPhotoImport {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onResult)
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) { CameraPhotoReader.read(context, uri) }
                if (result == null) {
                    message = "That picture does not say which lens it was taken with. Choose a photo straight from " +
                        "the camera, not a screenshot or an edited copy."
                } else {
                    message = null
                    latest(result)
                }
            }
        }
    }
    return CameraPhotoImport(
        pick = {
            message = null
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        message = message,
    )
}

/** Reads the EXIF tags that describe the camera and lens. */
internal object CameraPhotoReader {
    /**
     * The camera and lens a picture was taken with, or null when the picture cannot be read or has no usable focal
     * length. Blocking: call it off the main thread.
     */
    fun read(context: Context, uri: Uri): CameraPhotoResult? {
        val metadata = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream -> metadata(ExifInterface(stream)) }
        }.getOrNull() ?: return null
        val suggestion = metadata.suggestion() ?: return null
        return CameraPhotoResult(metadata, suggestion)
    }

    private fun metadata(exif: ExifInterface): CameraPhotoMetadata {
        fun number(tag: String): Double? = exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        fun whole(tag: String): Int? = exif.getAttributeInt(tag, 0).takeIf { it > 0 }
        return CameraPhotoMetadata(
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL),
            focalLengthMillimeters = number(ExifInterface.TAG_FOCAL_LENGTH),
            focalLength35mmEquivalent = number(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM),
            aperture = number(ExifInterface.TAG_F_NUMBER),
            pixelWidth = whole(ExifInterface.TAG_IMAGE_WIDTH) ?: whole(ExifInterface.TAG_PIXEL_X_DIMENSION),
            pixelHeight = whole(ExifInterface.TAG_IMAGE_LENGTH) ?: whole(ExifInterface.TAG_PIXEL_Y_DIMENSION),
        )
    }
}

/** The lines that tell what a photo said, as label and value. */
internal object CameraPhotoText {
    fun lines(result: CameraPhotoResult): List<Pair<String, String>> {
        val metadata = result.metadata
        val suggestion = result.suggestion
        val lines = mutableListOf<Pair<String, String>>()
        suggestion.name?.let { lines.add("Camera" to it) }
        val focal = "${SettingsFormat.decimal(suggestion.focalLengthMillimeters, 1)} mm"
        val equivalent = metadata.focalLength35mmEquivalent?.takeIf { it > 0 }
        lines.add("Focal length" to if (equivalent != null) "$focal (${SettingsFormat.decimal(equivalent, 0)} mm equivalent)" else focal)
        suggestion.aperture?.let { lines.add("Aperture" to "f/${SettingsFormat.decimal(it, 1)}") }
        suggestion.megapixels?.let { lines.add("Resolution" to "${SettingsFormat.decimal(it, 1)} MP") }
        val sensor = suggestion.sensor
        val crop = suggestion.cropFactor
        lines.add(
            "Sensor" to if (sensor != null && crop != null) {
                "${sensor.name} (crop factor ${SettingsFormat.decimal(crop, 2)})"
            } else {
                "Not in the photo: check it below"
            },
        )
        suggestion.pixelPitchMicrometers?.let { lines.add("Pixel pitch" to "${SettingsFormat.decimal(it, 2)} µm (estimated)") }
        return lines
    }
}

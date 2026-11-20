package net.kuecker.lodestar.kit.phototools

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/**
 * What the EXIF header of a photo says about the camera and lens that took it, for pre-filling a camera profile.
 *
 * Every field is optional, because cameras and editors leave tags out, and none is trusted: [suggestion] checks
 * each value against the supported ranges and ignores what does not fit. No photo is read here; the apps read the
 * tags (`Model`, `FocalLength`, `FocalLenIn35mmFilm`, `FNumber` and the pixel dimensions) and hand the numbers over.
 */
data class CameraPhotoMetadata(
    /** The camera model (EXIF `Model`), for example "NIKON D850". */
    val cameraModel: String?,
    /** The real focal length in millimetres (EXIF `FocalLength`). */
    val focalLengthMillimeters: Double?,
    /** The focal length that frames the same view on full frame, in millimetres (EXIF `FocalLenIn35mmFilm`). */
    val focalLength35mmEquivalent: Double?,
    /** The f-number the picture was taken at (EXIF `FNumber`). */
    val aperture: Double?,
    /** The width of the picture in pixels, whichever way the camera was turned. */
    val pixelWidth: Int?,
    /** The height of the picture in pixels. */
    val pixelHeight: Int?,
) {
    /**
     * The camera profile the photo suggests, or null if it has no usable focal length.
     *
     * The crop factor is the 35 mm equivalent over the real focal length. The sensor is the preset whose crop
     * factor is nearest (within [presetTolerance]); a crop factor that matches no preset gives a custom sensor of
     * that diagonal with the picture's aspect ratio (3:2 without pixel dimensions). The pixel pitch is the
     * sensor's longer side over the picture's longer side in pixels, so it assumes the picture is not cropped
     * or reduced.
     */
    fun suggestion(): CameraPhotoSuggestion? {
        val focalLength = focalLengthMillimeters?.takeIf { LensSetup.focalLengthRange.contains(it) } ?: return null
        val aperture = aperture?.takeIf { LensSetup.apertureRange.contains(it) }
        val width = pixelWidth?.takeIf { it in 1..maximumPixels }
        val height = pixelHeight?.takeIf { it in 1..maximumPixels }
        val cropFactor = focalLength35mmEquivalent?.let { equivalent ->
            if (equivalent.isFinite() && equivalent > 0.0) equivalent / focalLength else null
        }?.takeIf { cropFactorRange.contains(it) }
        val sensor = cropFactor?.let { sensorFor(it, width, height) }
        val pitch = if (sensor != null && width != null && height != null) {
            val micrometers = 1000.0 * max(sensor.widthMillimeters, sensor.heightMillimeters) / max(width, height).toDouble()
            micrometers.takeIf { SensorFormat.pixelPitchRange.contains(it) }
        } else {
            null
        }
        return CameraPhotoSuggestion(
            name = cleanedName(cameraModel),
            sensor = sensor,
            cropFactor = cropFactor,
            focalLengthMillimeters = focalLength,
            aperture = aperture,
            pixelPitchMicrometers = pitch,
            megapixels = if (width != null && height != null) width.toDouble() * height.toDouble() / 1_000_000.0 else null,
        )
    }

    companion object {
        /** The longest name a suggestion keeps, in characters. */
        const val maximumNameLength = 40

        /** The crop factors that are believed: 0.5 (large medium format) to 10 (the smallest phone sensors). */
        val cropFactorRange: ClosedFloatingPointRange<Double> = 0.5..10.0

        /** How far a crop factor may be from a preset's, as a ratio, for the preset to be chosen: 10 %. */
        const val presetTolerance = 0.10

        /** The largest picture dimension that is believed, in pixels. */
        const val maximumPixels = 100_000

        private fun sensorFor(cropFactor: Double, width: Int?, height: Int?): SensorFormat? {
            val nearest = SensorFormat.presets.minByOrNull { abs(ln(cropFactor / it.cropFactor)) }
            if (nearest != null && abs(cropFactor / nearest.cropFactor - 1.0) <= presetTolerance) return nearest
            val diagonal = SensorFormat.fullFrame.diagonalMillimeters / cropFactor
            val longSide = if (width != null && height != null) max(width, height).toDouble() else 3.0
            val shortSide = if (width != null && height != null) minOf(width, height).toDouble() else 2.0
            val sensorWidth = diagonal * longSide / hypot(longSide, shortSide)
            val sensorHeight = diagonal * shortSide / hypot(longSide, shortSide)
            return SensorFormat.validated(sensorWidth, sensorHeight)
        }

        /** The model trimmed of white space and control characters and cut to [maximumNameLength], or null if nothing is left. */
        private fun cleanedName(model: String?): String? {
            val cleaned = model?.filter { !it.isISOControl() }?.trim().orEmpty()
            if (cleaned.isEmpty()) return null
            val codePoints = cleaned.codePoints().limit(maximumNameLength.toLong()).toArray()
            return String(codePoints, 0, codePoints.size).trim().ifEmpty { null }
        }
    }
}

/**
 * The camera profile that a photo's metadata suggests; see [CameraPhotoMetadata.suggestion]. The numbers are inside the
 * ranges of [LensSetup] and [SensorFormat], so they can be used as they are.
 */
data class CameraPhotoSuggestion(
    /** The camera model, or null if the photo has none. */
    val name: String?,
    /** The sensor, or null if the photo says nothing about the crop factor. */
    val sensor: SensorFormat?,
    /** The crop factor the sensor was found from, or null if it could not be worked out. */
    val cropFactor: Double?,
    /** The real focal length in millimetres. */
    val focalLengthMillimeters: Double,
    /** The aperture the photo was taken at, or null if unknown. */
    val aperture: Double?,
    /** The estimated pixel pitch in micrometres, or null if it cannot be estimated. */
    val pixelPitchMicrometers: Double?,
    /** The picture's resolution in millions of pixels, or null if its size is unknown. */
    val megapixels: Double?,
)

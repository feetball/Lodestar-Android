package net.kuecker.lodestar.kit.interchange

import net.kuecker.lodestar.kit.foundation.AstroMath
import java.time.Instant
import java.util.UUID

/**
 * One line of the session log: what was shot (or tried) on a night.
 *
 * Values are cleaned when the entry is created, so that an entry read from a file or typed in always is usable and
 * bounded: text is trimmed and cut (control characters removed), numbers that are not finite or out of range become null
 * or are clamped, and coordinates only count as a pair. The constructor is private; create entries with the companion
 * `invoke`, which cleans every value.
 *
 * Dates are instants; the night an entry belongs to follows from its site's offset from UTC (see
 * [SessionLogCodec.night]).
 *
 * @property id A unique identifier (a UUID string).
 * @property date When the shooting started.
 * @property targetID The identifier of the catalog object (`CelestialObject.id`), which stays valid between launches.
 * @property targetName The target's name as it was when the entry was made.
 * @property rightAscension The target's J2000 right ascension in degrees, [0, 360), for fixed targets.
 * @property declination The target's J2000 declination in degrees, [-90, 90], for fixed targets.
 * @property siteName The observing site's name.
 * @property siteLatitude The site's latitude in degrees, if known (set together with [siteLongitude]).
 * @property siteLongitude The site's longitude in degrees east, if known.
 * @property siteUTCOffsetMinutes The site's standard time offset from UTC in minutes, not counting daylight saving time.
 * @property camera A summary of the camera ("Full Frame 36 × 24 mm").
 * @property focalLength The focal length in millimetres, if known.
 * @property lensAperture The lens aperture as an f-number, if known.
 * @property sensorPixelsX The sensor's width in pixels, if known (set together with [sensorPixelsY]).
 * @property sensorPixelsY The sensor's height in pixels, if known.
 * @property exposureSeconds The exposure of one frame in seconds, 0 when not recorded.
 * @property iso The ISO value, 0 when not recorded.
 * @property frameCount The number of frames, 0 when not recorded.
 * @property filter The filter, empty for none.
 * @property notes Free text, at most [maximumNotesLength] characters.
 * @property isImaged Whether the target was imaged (as opposed to a plan or an attempt).
 */
@ConsistentCopyVisibility
data class SessionLogEntry private constructor(
    val id: String,
    val date: Instant,
    val targetID: String,
    val targetName: String,
    val rightAscension: Double?,
    val declination: Double?,
    val siteName: String,
    val siteLatitude: Double?,
    val siteLongitude: Double?,
    val siteUTCOffsetMinutes: Int?,
    val camera: String,
    val focalLength: Double?,
    val lensAperture: Double?,
    val sensorPixelsX: Int?,
    val sensorPixelsY: Int?,
    val exposureSeconds: Double,
    val iso: Int,
    val frameCount: Int,
    val filter: String,
    val notes: String,
    val isImaged: Boolean,
) {
    /** The exposure time of all frames together in seconds. */
    val totalExposureSeconds: Double
        get() = exposureSeconds * frameCount.toDouble()

    /** Whether the entry has the target's coordinates. */
    val hasCoordinates: Boolean
        get() = rightAscension != null && declination != null

    /** The night the entry belongs to, see [SessionLogCodec.night]; the site's offset counts, UTC when it is not known. */
    val night: Long
        get() = SessionLogCodec.night(date, siteUTCOffsetMinutes ?: 0)

    companion object {
        /** The most characters (Unicode scalars) of the notes. */
        const val maximumNotesLength = 2_000

        /** The most characters of a target or site name. */
        const val maximumNameLength = 120

        /** The most characters of the camera and filter texts. */
        const val maximumLabelLength = 80

        /** The longest exposure of one frame, in seconds. */
        const val maximumExposureSeconds = 86_400.0

        /** The highest ISO value. */
        const val maximumISO = 409_600

        /** The most frames of an entry. */
        const val maximumFrameCount = 100_000

        /** The name of an entry whose target name is empty. */
        const val defaultTargetName = "Unnamed target"

        /**
         * Creates an entry, cleaning every value as described on the type.
         *
         * An [id] that is empty after cleaning is replaced by a new UUID.
         */
        operator fun invoke(
            id: String = UUID.randomUUID().toString().uppercase(),
            date: Instant,
            targetID: String,
            targetName: String,
            rightAscension: Double? = null,
            declination: Double? = null,
            siteName: String = "",
            siteLatitude: Double? = null,
            siteLongitude: Double? = null,
            siteUTCOffsetMinutes: Int? = null,
            camera: String = "",
            focalLength: Double? = null,
            lensAperture: Double? = null,
            sensorPixelsX: Int? = null,
            sensorPixelsY: Int? = null,
            exposureSeconds: Double = 0.0,
            iso: Int = 0,
            frameCount: Int = 0,
            filter: String = "",
            notes: String = "",
            isImaged: Boolean = false,
        ): SessionLogEntry {
            val cleanedID = InterchangeText.singleLine(id, 64)
            val cleanedName = InterchangeText.singleLine(targetName, maximumNameLength)
            val hasPosition = rightAscension != null && declination != null && rightAscension.isFinite() && declination.isFinite()
            val hasSitePlace = siteLatitude != null && siteLongitude != null && siteLatitude.isFinite() &&
                siteLongitude.isFinite() && kotlin.math.abs(siteLatitude) <= 90 && kotlin.math.abs(siteLongitude) <= 180
            val hasSensor = sensorPixelsX != null && sensorPixelsY != null &&
                sensorPixelsX in 1..100_000 && sensorPixelsY in 1..100_000
            return SessionLogEntry(
                id = cleanedID.ifEmpty { UUID.randomUUID().toString().uppercase() },
                date = date,
                targetID = InterchangeText.singleLine(targetID, maximumNameLength),
                targetName = cleanedName.ifEmpty { defaultTargetName },
                rightAscension = if (hasPosition) AstroMath.normalizeDegrees(rightAscension) else null,
                declination = if (hasPosition) AstroMath.clamp(declination, -90.0, 90.0) else null,
                siteName = InterchangeText.singleLine(siteName, maximumNameLength),
                siteLatitude = if (hasSitePlace) siteLatitude else null,
                siteLongitude = if (hasSitePlace) siteLongitude else null,
                siteUTCOffsetMinutes = siteUTCOffsetMinutes?.takeIf { it in -720..840 },
                camera = InterchangeText.singleLine(camera, maximumLabelLength),
                focalLength = focalLength?.takeIf { it.isFinite() && it >= 1.0 && it <= 100_000.0 },
                lensAperture = lensAperture?.takeIf { it.isFinite() && it >= 0.3 && it <= 100.0 },
                sensorPixelsX = if (hasSensor) sensorPixelsX else null,
                sensorPixelsY = if (hasSensor) sensorPixelsY else null,
                exposureSeconds = if (exposureSeconds.isFinite()) AstroMath.clamp(exposureSeconds, 0.0, maximumExposureSeconds) else 0.0,
                iso = AstroMath.clamp(iso, 0, maximumISO),
                frameCount = AstroMath.clamp(frameCount, 0, maximumFrameCount),
                filter = InterchangeText.singleLine(filter, maximumLabelLength),
                notes = InterchangeText.multiLine(notes, maximumNotesLength),
                isImaged = isImaged,
            )
        }
    }
}

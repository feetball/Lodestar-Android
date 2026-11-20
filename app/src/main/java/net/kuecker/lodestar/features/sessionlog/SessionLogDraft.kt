package net.kuecker.lodestar.features.sessionlog

import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.formatting.formatExposure
import net.kuecker.lodestar.kit.interchange.SessionLogCodec
import net.kuecker.lodestar.kit.interchange.SessionLogEntry
import net.kuecker.lodestar.kit.phototools.ExposureCalculator
import net.kuecker.lodestar.kit.phototools.ExposureRule
import net.kuecker.lodestar.kit.phototools.LensSetup
import net.kuecker.lodestar.model.ObserverContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The values of the log sheet while it is open, and how they become an entry. The numbers are kept as the text that was
 * typed, so that a half-typed "1." does not jump around.
 *
 * @property existingID The identifier of the entry being edited; null for a new entry.
 * @property dateText The start of the session as "2026-11-18 20:15" in the time zone of the phone.
 */
internal data class SessionLogDraft(
    val existingID: String?,
    val date: Instant,
    val dateText: String,
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
    val exposureText: String,
    val isoText: String,
    val framesText: String,
    val filter: String,
    val notes: String,
    val isImaged: Boolean,
) {
    /** The start of the session, or null for date text that is not a date and time like "2026-11-18 20:15". */
    val parsedDate: Instant?
        get() {
            if (dateText == formatDate(date)) return date
            return try {
                LocalDateTime.parse(dateText.trim(), dateFormat).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /** The exposure in seconds: 0 for an empty field, null for text that is not a number of seconds from 0 to a day. */
    val exposureSeconds: Double?
        get() {
            val text = exposureText.trim().replace(',', '.')
            if (text.isEmpty()) return 0.0
            val value = text.toDoubleOrNull() ?: return null
            return if (value.isFinite() && value >= 0 && value <= SessionLogEntry.maximumExposureSeconds) value else null
        }

    /** The ISO value: 0 for an empty field, null for text that is not a whole number up to the highest ISO. */
    val iso: Int?
        get() = wholeNumber(isoText, SessionLogEntry.maximumISO)

    /** The number of frames: 0 for an empty field, null for text that is not a whole number up to the most frames. */
    val frameCount: Int?
        get() = wholeNumber(framesText, SessionLogEntry.maximumFrameCount)

    /** Whether the date and every number field hold a usable value. */
    val isValid: Boolean
        get() = parsedDate != null && exposureSeconds != null && iso != null && frameCount != null

    /** The entry the draft stands for; fields that are not valid count as not recorded. */
    fun entry(): SessionLogEntry = SessionLogEntry(
        id = existingID ?: java.util.UUID.randomUUID().toString().uppercase(),
        date = parsedDate ?: date,
        targetID = targetID,
        targetName = targetName,
        rightAscension = rightAscension,
        declination = declination,
        siteName = siteName,
        siteLatitude = siteLatitude,
        siteLongitude = siteLongitude,
        siteUTCOffsetMinutes = siteUTCOffsetMinutes,
        camera = camera,
        focalLength = focalLength,
        lensAperture = lensAperture,
        sensorPixelsX = sensorPixelsX,
        sensorPixelsY = sensorPixelsY,
        exposureSeconds = exposureSeconds ?: 0.0,
        iso = iso ?: 0,
        frameCount = frameCount ?: 0,
        filter = filter,
        notes = notes,
        isImaged = isImaged,
    )

    private fun wholeNumber(text: String, maximum: Int): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        val value = trimmed.toIntOrNull() ?: return null
        return if (value in 0..maximum) value else null
    }

    companion object {
        private val dateFormat: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

        /** [value] with at most three decimals, without trailing zeros and with a "." decimal mark: "120" or "2.5". */
        private fun plainNumber(value: Double): String =
            BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

        /** [date] as "2026-11-18 20:15" in the time zone of the phone. */
        fun formatDate(date: Instant): String = dateFormat.format(date.atZone(ZoneId.systemDefault()))

        /**
         * A new entry for [obj]: now, the observing site, the camera and lens of Settings, and the longest exposure before
         * stars trail (the NPF rule) as the exposure to start from.
         */
        fun new(obj: CelestialObject, context: ObserverContext?, lens: LensSetup, now: Instant): SessionLogDraft {
            val position = obj.j2000
            val npf = ExposureCalculator.maxExposure(ExposureRule.NPF, lens, position?.declination).seconds
            val offsetMinutes = context?.timeZone?.rules?.getStandardOffset(now)?.totalSeconds?.div(60)
            val pitch = lens.pixelPitchMicrometers
            val pixelsX = pitch?.takeIf { it > 0 }?.let { (lens.sensor.widthMillimeters * 1000 / it).roundToInt() }
            val pixelsY = pitch?.takeIf { it > 0 }?.let { (lens.sensor.heightMillimeters * 1000 / it).roundToInt() }
            return SessionLogDraft(
                existingID = null,
                date = now,
                dateText = formatDate(now),
                targetID = obj.id,
                targetName = obj.name,
                rightAscension = position?.rightAscension,
                declination = position?.declination,
                siteName = context?.name ?: "",
                siteLatitude = context?.observer?.location?.latitude,
                siteLongitude = context?.observer?.location?.longitude,
                siteUTCOffsetMinutes = offsetMinutes,
                camera = lens.sensor.name,
                focalLength = lens.focalLengthMillimeters,
                lensAperture = lens.aperture,
                sensorPixelsX = pixelsX,
                sensorPixelsY = pixelsY,
                exposureText = if (npf.isFinite() && npf >= 1) npf.roundToInt().toString() else "",
                isoText = "",
                framesText = "",
                filter = "",
                notes = "",
                isImaged = false,
            )
        }

        /** The draft of an existing entry. */
        fun editing(entry: SessionLogEntry): SessionLogDraft = SessionLogDraft(
            existingID = entry.id,
            date = entry.date,
            dateText = formatDate(entry.date),
            targetID = entry.targetID,
            targetName = entry.targetName,
            rightAscension = entry.rightAscension,
            declination = entry.declination,
            siteName = entry.siteName,
            siteLatitude = entry.siteLatitude,
            siteLongitude = entry.siteLongitude,
            siteUTCOffsetMinutes = entry.siteUTCOffsetMinutes,
            camera = entry.camera,
            focalLength = entry.focalLength,
            lensAperture = entry.lensAperture,
            sensorPixelsX = entry.sensorPixelsX,
            sensorPixelsY = entry.sensorPixelsY,
            exposureText = if (entry.exposureSeconds > 0) plainNumber(entry.exposureSeconds) else "",
            isoText = if (entry.iso > 0) entry.iso.toString() else "",
            framesText = if (entry.frameCount > 0) entry.frameCount.toString() else "",
            filter = entry.filter,
            notes = entry.notes,
            isImaged = entry.isImaged,
        )
    }
}

/** How entries read in lists. */
internal object SessionLogFormat {
    /** "30 × 2 min · ISO 800 · L-eXtreme · Imaged". */
    fun summary(entry: SessionLogEntry): String {
        val parts = ArrayList<String>()
        if (entry.frameCount > 0 && entry.exposureSeconds > 0) {
            parts.add("${entry.frameCount} × ${AngleFormatter.formatExposure(entry.exposureSeconds)}")
        } else if (entry.frameCount > 0) {
            parts.add("${entry.frameCount} frames")
        } else if (entry.exposureSeconds > 0) {
            parts.add(AngleFormatter.formatExposure(entry.exposureSeconds))
        }
        if (entry.iso > 0) parts.add("ISO ${entry.iso}")
        if (entry.filter.isNotEmpty()) parts.add(entry.filter)
        parts.add(if (entry.isImaged) "Imaged" else "Not imaged")
        return parts.joinToString(" · ")
    }

    /** The date and time of an entry in the time zone of the phone, "2026-11-18 20:15". */
    fun dateText(entry: SessionLogEntry): String = SessionLogDraft.formatDate(entry.date)

    /** The time of an entry, "20:15". */
    fun timeText(entry: SessionLogEntry): String = SessionLogDraft.formatDate(entry.date).substring(11)

    /** "Night of 2026-11-18": the title of a night's section. */
    fun nightTitle(night: Long): String = "Night of " + SessionLogCodec.nightText(night)
}

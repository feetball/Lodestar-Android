package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.abs

/** Which of the two position formats the mount uses: `HH:MM.T` / `sDD*MM` or `HH:MM:SS` / `sDD*MM:SS`. */
enum class LX200Precision(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Tenths of a minute of right ascension, whole arcminutes of declination. */
    LOW("low"),

    /** Whole seconds of right ascension, whole arcseconds of declination. */
    HIGH("high");

    companion object {
        /** The precision with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): LX200Precision? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Which family of LX200 firmware is talking; it decides the extensions the driver may use. */
enum class LX200Dialect(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Meade LX200, Autostar, and the many compatible controllers that implement only the Meade command set. */
    MEADE("meade"),

    /** OnStep, which adds `:GU#` status, `:Te#`/`:Td#` tracking and single-digit `:MS#` replies. */
    ONSTEP("onstep");

    companion object {
        /** The dialect with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): LX200Dialect? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** A decoded right ascension (hours) or declination (degrees) and the format it arrived in. */
data class LX200Reading(
    /** Hours for a right ascension, degrees for a declination. */
    val value: Double,
    /** The format of the reply. */
    val precision: LX200Precision,
)

/** The flags of OnStep's `:GU#` status string that the controller uses. */
data class LX200StatusFlags(
    /** `n` is absent: sidereal tracking is on (or a goto started from tracking is running). */
    val isTracking: Boolean,
    /** `N` is absent: a goto is in progress. */
    val isGotoActive: Boolean,
    /** `P`: the mount is parked. */
    val isParked: Boolean,
    /** `I`: parking is in progress. */
    val isParking: Boolean,
    /** `F`: parking failed. */
    val parkFailed: Boolean,
    /** `H`: the mount is at its home position. */
    val isAtHome: Boolean,
)

/** The result of `:GW#` (LX200GPS and OnStep): mount type, tracking and the number of alignment stars. */
data class LX200AlignmentStatus(
    /** `A` alt-azimuth, `P` polar/fork, `L` land, `G` German equatorial. */
    val mountType: Char,
    /** The `T` / `N` flag. */
    val isTracking: Boolean,
    /** 0 when the mount needs an alignment, up to 9 for 9 or more stars. */
    val alignedStars: Int,
)

/**
 * Encoding and decoding of the Meade LX200 command set (Meade Telescope Serial Command Protocol, revision 2007.10,
 * and OnStep's extensions as implemented in the OnStep 4.24 `Command.ino`, https://github.com/hjd1964/OnStep).
 *
 * Commands are ASCII strings `:XX…#`. Replies are `#`-terminated except single-character answers (`0`/`1` flags,
 * the `:MS#` digit). The functions here take a reply without its terminator (as text with one character per byte,
 * so the degree sign 0xDF some Meade scopes send arrives as `ß`) and throw [MountError.MalformedReply] for anything
 * that is not exactly a valid value.
 */
object LX200Codec {
    /** The byte that ends a command and most replies. */
    const val terminator: Char = '#'

    /** Sent by LX200GPS and Autostar II instead of a reply when the controller is busy; retry the command. */
    const val busyByte: Int = 0x15

    // MARK: Fixed commands

    /** Get the telescope's right ascension. */
    const val getRightAscension: String = ":GR#"

    /** Get the telescope's declination. */
    const val getDeclination: String = ":GD#"

    /** Get the product name. */
    const val getProductName: String = ":GVP#"

    /** Get the firmware number. */
    const val getFirmwareNumber: String = ":GVN#"

    /** OnStep: get the status string. */
    const val getStatus: String = ":GU#"

    /** LX200GPS and OnStep: get mount type, tracking and alignment. */
    const val getAlignmentStatus: String = ":GW#"

    /** Get the distance bars: a non-empty answer means the mount is slewing. */
    const val getSlewDistance: String = ":D#"

    /** Slew to the target set with `:Sr` and `:Sd`. */
    const val slewToTarget: String = ":MS#"

    /** Sync the mount to the target set with `:Sr` and `:Sd`. */
    const val syncToTarget: String = ":CM#"

    /** Halt all slewing. */
    const val halt: String = ":Q#"

    /** Toggle between the low and high precision position formats. It is a toggle: never send it blind. */
    const val togglePrecision: String = ":U#"

    /** OnStep: enable tracking. */
    const val trackingOn: String = ":Te#"

    /** OnStep: disable tracking. */
    const val trackingOff: String = ":Td#"

    // MARK: Parameterised commands

    /** `:Sr…#`: set the target right ascension from [hours]. */
    fun setTargetRightAscension(hours: Double, precision: LX200Precision): String =
        ":Sr${formatRightAscension(hours, precision)}#"

    /** `:Sd…#`: set the target declination from [degrees]. */
    fun setTargetDeclination(degrees: Double, precision: LX200Precision): String =
        ":Sd${formatDeclination(degrees, precision)}#"

    /** `:M?#`: start moving in [direction] at the current rate. */
    fun move(direction: MountNudgeDirection): String = ":M${directionLetter(direction)}#"

    /** `:Q?#`: stop moving in [direction]. */
    fun halt(direction: MountNudgeDirection): String = ":Q${directionLetter(direction)}#"

    /** `:R?#`: select the slew rate (`G` guide, `C` centre, `M` find, `S` max). */
    fun setRate(rate: MountNudgeRate): String = ":R${rateLetter(rate)}#"

    /** `:St…#`: set the site latitude (north positive), rounded to the arcminute. */
    fun setLatitude(latitude: Double): String {
        val safe = finiteOrZero(latitude)
        val minutes = (abs(safe.coerceIn(-90.0, 90.0)) * 60).roundedHalfAwayFromZero().toInt()
        val sign = if (safe < 0 && minutes > 0) "-" else "+"
        return ":St$sign${MountCalendar.twoDigits(minutes / 60)}*${MountCalendar.twoDigits(minutes % 60)}#"
    }

    /**
     * `:Sg…#`: set the site longitude from [eastLongitude] (east positive). The protocol counts longitude westwards
     * from 0 to 360°, so 10° E is sent as 350.
     */
    fun setLongitude(eastLongitude: Double): String {
        var west = -finiteOrZero(eastLongitude) % 360.0
        if (west < 0) west += 360.0
        var minutes = (west * 60).roundedHalfAwayFromZero().toInt()
        if (minutes >= 360 * 60) minutes -= 360 * 60
        return ":Sg${MountCalendar.threeDigits(minutes / 60)}*${MountCalendar.twoDigits(minutes % 60)}#"
    }

    /**
     * `:SG…#`: set the hours that are added to local time to get UTC (west of Greenwich positive, so −[eastOffsetHours]).
     * Meade writes `sHH.H`; OnStep writes `sHH` or `sHH:MM` with MM of 30 or 45.
     */
    fun setUTCOffset(eastOffsetHours: Double, dialect: LX200Dialect): String {
        val added = -finiteOrZero(eastOffsetHours).coerceIn(-14.0, 14.0)
        return when (dialect) {
            LX200Dialect.MEADE -> {
                val tenths = (abs(added) * 10).roundedHalfAwayFromZero().toInt()
                val sign = if (added < 0 && tenths > 0) "-" else "+"
                ":SG$sign${MountCalendar.twoDigits(tenths / 10)}.${tenths % 10}#"
            }
            LX200Dialect.ONSTEP -> {
                val quarters = (abs(added) * 4).roundedHalfAwayFromZero().toInt()
                val minutes = quarters * 15
                val sign = if (added < 0 && minutes > 0) "-" else "+"
                val hours = MountCalendar.twoDigits(minutes / 60)
                val rest = minutes % 60
                if (rest == 0) ":SG$sign$hours#" else ":SG$sign$hours:${MountCalendar.twoDigits(rest)}#"
            }
        }
    }

    /** `:SC…#`: set the date to `MM/DD/YY` (the year modulo 100). */
    fun setDate(dateTime: MountDateTime): String =
        ":SC${MountCalendar.twoDigits(dateTime.month)}/${MountCalendar.twoDigits(dateTime.day)}/" +
            "${MountCalendar.twoDigits(dateTime.year % 100)}#"

    /** `:SL…#`: set the local time to `HH:MM:SS`. */
    fun setTime(dateTime: MountDateTime): String =
        ":SL${MountCalendar.twoDigits(dateTime.hour)}:${MountCalendar.twoDigits(dateTime.minute)}:" +
            "${MountCalendar.twoDigits(dateTime.second)}#"

    // MARK: Formatting of positions

    /** `HH:MM:SS` or `HH:MM.T` for [hours], wrapped into [0, 24) and rounded to the format's resolution. */
    fun formatRightAscension(hours: Double, precision: LX200Precision): String {
        var wrapped = finiteOrZero(hours) % 24.0
        if (wrapped < 0) wrapped += 24.0
        return when (precision) {
            LX200Precision.HIGH -> {
                var seconds = (wrapped * 3600).roundedHalfAwayFromZero().toInt()
                if (seconds >= 86_400) seconds -= 86_400
                "${MountCalendar.twoDigits(seconds / 3600)}:${MountCalendar.twoDigits(seconds % 3600 / 60)}:" +
                    MountCalendar.twoDigits(seconds % 60)
            }
            LX200Precision.LOW -> {
                var tenths = (wrapped * 600).roundedHalfAwayFromZero().toInt()
                if (tenths >= 14_400) tenths -= 14_400
                "${MountCalendar.twoDigits(tenths / 600)}:${MountCalendar.twoDigits(tenths % 600 / 10)}.${tenths % 10}"
            }
        }
    }

    /** `sDD*MM:SS` or `sDD*MM` for [degrees], clamped to ±90 and rounded to the format's resolution. */
    fun formatDeclination(degrees: Double, precision: LX200Precision): String {
        val clamped = finiteOrZero(degrees).coerceIn(-90.0, 90.0)
        return when (precision) {
            LX200Precision.HIGH -> {
                val seconds = (abs(clamped) * 3600).roundedHalfAwayFromZero().toInt()
                val sign = if (clamped < 0 && seconds > 0) "-" else "+"
                "$sign${MountCalendar.twoDigits(seconds / 3600)}*${MountCalendar.twoDigits(seconds % 3600 / 60)}:" +
                    MountCalendar.twoDigits(seconds % 60)
            }
            LX200Precision.LOW -> {
                val minutes = (abs(clamped) * 60).roundedHalfAwayFromZero().toInt()
                val sign = if (clamped < 0 && minutes > 0) "-" else "+"
                "$sign${MountCalendar.twoDigits(minutes / 60)}*${MountCalendar.twoDigits(minutes % 60)}"
            }
        }
    }

    // MARK: Decoding of replies

    /**
     * Decodes `HH:MM:SS` (also with fractional seconds, as OnStep's high-precision reply) or `HH:MM.T` into hours.
     * Hours are 0 to 23 (24:00:00 reads as 0), minutes and seconds 0 to 59.
     */
    @Throws(MountError::class)
    fun decodeRightAscension(text: String): LX200Reading {
        val hours = twoDigits(text, 0) ?: throw malformed("right ascension", text)
        if (text.length < 7 || text[2] != ':') throw malformed("right ascension", text)
        val minutes = twoDigits(text, 3) ?: throw malformed("right ascension", text)
        if (hours > 24 || minutes > 59) throw malformed("right ascension", text)
        val value: Double
        val precision: LX200Precision
        when (text[5]) {
            ':' -> {
                val seconds = twoDigits(text, 6) ?: throw malformed("right ascension", text)
                if (seconds > 59) throw malformed("right ascension", text)
                val fraction = if (text.length == 8) 0.0 else decimalFraction(text, 8, 4) ?: throw malformed("right ascension", text)
                value = hours + minutes / 60.0 + (seconds + fraction) / 3600.0
                precision = LX200Precision.HIGH
            }
            '.' -> {
                val fraction = decimalFraction(text, 5, 3) ?: throw malformed("right ascension", text)
                value = hours + (minutes + fraction) / 60.0
                precision = LX200Precision.LOW
            }
            else -> throw malformed("right ascension", text)
        }
        if (value > 24.0) throw malformed("right ascension", text)
        return LX200Reading(if (value >= 24.0) 0.0 else value, precision)
    }

    /**
     * Decodes `sDD*MM:SS` or `sDD*MM` (degree separator `*`, `:`, `°` or the byte 0xDF; seconds separator `:` or `'`,
     * fractional seconds allowed) into degrees. The sign is optional; the magnitude is at most 90°.
     */
    @Throws(MountError::class)
    fun decodeDeclination(text: String): LX200Reading {
        var index = 0
        var negative = false
        if (text.isNotEmpty() && (text[0] == '+' || text[0] == '-')) {
            negative = text[0] == '-'
            index = 1
        }
        val degrees = twoDigits(text, index) ?: throw malformed("declination", text)
        if (text.length < index + 5) throw malformed("declination", text)
        val separator = text[index + 2]
        if (separator != '*' && separator != ':' && separator != '°' && separator != 'ß') {
            throw malformed("declination", text)
        }
        val minutes = twoDigits(text, index + 3) ?: throw malformed("declination", text)
        if (degrees > 90 || minutes > 59) throw malformed("declination", text)
        var magnitude = degrees + minutes / 60.0
        var precision = LX200Precision.LOW
        if (text.length > index + 5) {
            val secondsSeparator = text[index + 5]
            if (secondsSeparator != ':' && secondsSeparator != '\'') throw malformed("declination", text)
            val seconds = twoDigits(text, index + 6) ?: throw malformed("declination", text)
            if (seconds > 59) throw malformed("declination", text)
            val fraction = if (text.length == index + 8) 0.0 else decimalFraction(text, index + 8, 4) ?: throw malformed("declination", text)
            magnitude += (seconds + fraction) / 3600.0
            precision = LX200Precision.HIGH
        }
        if (magnitude > 90.0) throw malformed("declination", text)
        return LX200Reading(if (negative) -magnitude else magnitude, precision)
    }

    /**
     * Maps the digit that answers `:MS#` (and follows `E` in OnStep's `:CM#` failure `En#`) to the reason the mount
     * refused: `0` is success (null). Meade defines 1 (below horizon) and 2 (above the higher limit); OnStep 1 to 9.
     */
    @Throws(MountError::class)
    fun slewRefusal(code: Char): MountError? = when (code) {
        '0' -> null
        '1' -> MountError.MountHorizonLimit
        '2' -> MountError.MountOverheadLimit
        '3' -> MountError.Standby
        '4' -> MountError.Parked
        '5' -> MountError.SlewInProgress
        '6' -> MountError.OutsideMountLimits
        '7' -> MountError.HardwareFault("")
        '8' -> MountError.Busy
        '9' -> MountError.Rejected("the mount refused the move for an unspecified reason")
        else -> throw malformed("slew reply", code.toString())
    }

    /** Decodes the answer of `:D#` (text between the bars and the terminator): non-empty means the mount is slewing. */
    fun isSlewing(distanceBars: String): Boolean = distanceBars.isNotEmpty()

    /** Decodes OnStep's `:GU#` status string. */
    @Throws(MountError::class)
    fun decodeStatus(text: String): LX200StatusFlags {
        if (text.isEmpty() || text.length > 40) throw malformed("status", text)
        var parkMarkers = 0
        for (ch in text) {
            if (ch.code !in 0x21..0x7E) throw malformed("status", text)
            if (ch == 'p' || ch == 'I' || ch == 'P' || ch == 'F') parkMarkers++
        }
        if (parkMarkers != 1) throw malformed("status", text)
        return LX200StatusFlags(
            isTracking = !text.contains('n'),
            isGotoActive = !text.contains('N'),
            isParked = text.contains('P'),
            isParking = text.contains('I'),
            parkFailed = text.contains('F'),
            isAtHome = text.contains('H'),
        )
    }

    /** Decodes `:GW#`: mount type `A`/`P`/`L`/`G`, tracking `T`/`N`, alignment digit. */
    @Throws(MountError::class)
    fun decodeAlignmentStatus(text: String): LX200AlignmentStatus {
        if (text.length != 3) throw malformed("alignment status", text)
        val mount = text[0]
        val tracking = text[1]
        val stars = text[2]
        if (mount != 'A' && mount != 'P' && mount != 'L' && mount != 'G') throw malformed("alignment status", text)
        if (tracking != 'T' && tracking != 'N') throw malformed("alignment status", text)
        if (stars !in '0'..'9') throw malformed("alignment status", text)
        return LX200AlignmentStatus(mount, tracking == 'T', stars - '0')
    }

    /** The dialect a product name (`:GVP#`) belongs to: OnStep calls itself "On-Step". */
    fun dialect(productName: String): LX200Dialect {
        val lowered = MountText.asciiLowercased(productName)
        return if (lowered.contains("on-step") || lowered.contains("onstep")) LX200Dialect.ONSTEP else LX200Dialect.MEADE
    }

    // MARK: Helpers

    /** Non-finite numbers are sent as 0 so that no formatting can fail; callers validate their input first. */
    private fun finiteOrZero(value: Double): Double = if (value.isFinite()) value else 0.0

    private fun directionLetter(direction: MountNudgeDirection): Char = when (direction) {
        MountNudgeDirection.NORTH -> 'n'
        MountNudgeDirection.SOUTH -> 's'
        MountNudgeDirection.EAST -> 'e'
        MountNudgeDirection.WEST -> 'w'
    }

    private fun rateLetter(rate: MountNudgeRate): Char = when (rate) {
        MountNudgeRate.GUIDE -> 'G'
        MountNudgeRate.CENTER -> 'C'
        MountNudgeRate.FIND -> 'M'
        MountNudgeRate.SLEW -> 'S'
    }

    private fun malformed(what: String, text: String): MountError =
        MountError.MalformedReply("$what \"${MountText.sanitized(text, 24)}\"")

    private fun twoDigits(text: String, at: Int): Int? {
        if (at < 0 || text.length < at + 2) return null
        val tens = text[at]
        val ones = text[at + 1]
        if (tens !in '0'..'9' || ones !in '0'..'9') return null
        return (tens - '0') * 10 + (ones - '0')
    }

    /** The value of `.d…` (a dot and 1 to [maximumDigits] digits that end the text) starting at [at], or null. */
    private fun decimalFraction(text: String, at: Int, maximumDigits: Int): Double? {
        if (text.length < at + 2 || text[at] != '.') return null
        val digits = text.length - at - 1
        if (digits > maximumDigits) return null
        var value = 0.0
        var scale = 1.0
        for (offset in 1..digits) {
            val ch = text[at + offset]
            if (ch !in '0'..'9') return null
            scale /= 10.0
            value += (ch - '0') * scale
        }
        return value
    }
}

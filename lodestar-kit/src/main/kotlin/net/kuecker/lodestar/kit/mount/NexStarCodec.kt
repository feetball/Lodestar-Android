package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import kotlin.math.abs

/** Which hand controller family answered the version command. */
enum class NexStarFlavor(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Celestron NexStar hand control: the version is two binary bytes. */
    CELESTRON("celestron"),

    /** Sky-Watcher SynScan hand control: the version is six hexadecimal digits in ASCII. */
    SYNSCAN("synscan");

    companion object {
        /** The flavor with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): NexStarFlavor? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** The hand controller's firmware version. */
data class NexStarVersion(
    /** The family that reported the version. */
    val flavor: NexStarFlavor,
    /** Major version. */
    val major: Int,
    /** Minor version. */
    val minor: Int,
    /** Patch level; 0 for Celestron controllers, which report two numbers. */
    val patch: Int,
) {
    /** True if this version is [major].[minor].[patch] or later. */
    fun isAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean = when {
        this.major != major -> this.major > major
        this.minor != minor -> this.minor > minor
        else -> this.patch >= patch
    }

    /** `4.37.07` for SynScan, `5.28` for Celestron. */
    val description: String
        get() = when (flavor) {
            NexStarFlavor.CELESTRON -> "$major.${MountCalendar.twoDigits(minor)}"
            NexStarFlavor.SYNSCAN -> "$major.${MountCalendar.twoDigits(minor)}.${MountCalendar.twoDigits(patch)}"
        }
}

/** The two motor axes the hand control exposes to pass-through commands. */
enum class NexStarAxis(
    /** The device number used in pass-through commands. */
    val device: Int,
) {
    /** Azimuth on an alt-azimuth mount, right ascension on an equatorial mount (device 16). */
    AZIMUTH_RIGHT_ASCENSION(16),

    /** Altitude on an alt-azimuth mount, declination on an equatorial mount (device 17). */
    ALTITUDE_DECLINATION(17),
}

/** The tracking modes of the `T` and `t` commands. */
enum class NexStarTrackingMode(
    /** The byte sent and received. */
    val code: Int,
) {
    /** Tracking off. */
    OFF(0),

    /** Alt-azimuth tracking. */
    ALT_AZ(1),

    /** Equatorial tracking (Celestron: equatorial north; SynScan: equatorial). */
    EQ_NORTH(2),

    /** Celestron: equatorial south; SynScan: sidereal with PEC. */
    EQ_SOUTH(3);

    companion object {
        /** The mode with the given code, or null if there is none. */
        fun fromCode(code: Int): NexStarTrackingMode? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Encoding and decoding of the Celestron NexStar hand-control serial protocol ("NexStar Communication Protocol",
 * Celestron, https://www.celestron.com/ — mirrored at https://www.as.up.krakow.pl/rzeczy/1154108406_nexstarcommprot.pdf)
 * and its Sky-Watcher SynScan twin ("SynScan Serial Communication Protocol", version 3.3, SSAP-F-160414V1-EN).
 *
 * Commands are single ASCII letters followed by fixed-length arguments; replies end in `#`. Positions are
 * hexadecimal fractions of a revolution: 16 bits (`E`, `R`, `S`: about 20″ per unit) or 32 bits whose lowest 8 bits are
 * unused (`e`, `r`, `s`: about 0.08″ per unit). Pass-through (`P`) commands talk to the motor controllers.
 */
object NexStarCodec {
    /** The byte that ends every reply. */
    const val terminator: Int = 0x23

    // MARK: Simple commands

    /** `V`: get the version. */
    val getVersion: ByteArray get() = byteArrayOf('V'.code.toByte())

    /** `m`: get the model. */
    val getModel: ByteArray get() = byteArrayOf('m'.code.toByte())

    /** `J`: is the alignment complete? */
    val getAlignmentComplete: ByteArray get() = byteArrayOf('J'.code.toByte())

    /** `L`: is a GOTO in progress? */
    val getGotoInProgress: ByteArray get() = byteArrayOf('L'.code.toByte())

    /** `M`: cancel the GOTO. */
    val cancelGoto: ByteArray get() = byteArrayOf('M'.code.toByte())

    /** `t`: get the tracking mode. */
    val getTrackingMode: ByteArray get() = byteArrayOf('t'.code.toByte())

    /** `T` and the mode byte: set the tracking mode. */
    fun setTrackingMode(mode: NexStarTrackingMode): ByteArray =
        byteArrayOf('T'.code.toByte(), mode.code.toByte())

    /** `e` or `E`: get the position as right ascension and declination. */
    fun getPosition(precise: Boolean): ByteArray = byteArrayOf((if (precise) 'e' else 'E').code.toByte())

    /** `r…` or `R…`: GOTO the J2000 or JNow place [target] (whichever frame the mount expects). */
    fun goto(target: EquatorialCoordinate, precise: Boolean): ByteArray =
        MountText.ascii(positionCommand(if (precise) 'r' else 'R', target, precise))

    /** `s…` or `S…`: sync to [target]. */
    fun sync(target: EquatorialCoordinate, precise: Boolean): ByteArray =
        MountText.ascii(positionCommand(if (precise) 's' else 'S', target, precise))

    private fun positionCommand(letter: Char, target: EquatorialCoordinate, precise: Boolean): String =
        "$letter${encodeAngle(target.rightAscension, precise)},${encodeAngle(target.declination, precise)}"

    // MARK: Angles

    /**
     * The hexadecimal fraction of a revolution for [degrees] (wrapped into one turn; negative angles such as a
     * southern declination become 360° minus their size): 4 digits, or 8 digits with the lowest byte 00.
     */
    fun encodeAngle(degrees: Double, precise: Boolean): String {
        val fraction = AstroMath.normalizeDegrees(if (degrees.isFinite()) degrees else 0.0) / 360.0
        return if (precise) {
            var units = (fraction * 16_777_216.0).roundedHalfAwayFromZero().toLong()
            if (units >= 16_777_216L) units -= 16_777_216L
            hex(units * 256, 8)
        } else {
            var units = (fraction * 65_536.0).roundedHalfAwayFromZero().toLong()
            if (units >= 65_536L) units -= 65_536L
            hex(units, 4)
        }
    }

    /** The angle in [0°, 360°) of a 4 or 8 digit hexadecimal fraction of a revolution (the lowest byte of 8 digits is unused). */
    @Throws(MountError::class)
    fun decodeAngle(hexText: String): Double {
        if (hexText.length != 4 && hexText.length != 8) throw malformed("angle", hexText)
        var value = 0L
        for (ch in hexText) {
            val digit = hexDigit(ch)
            if (digit < 0) throw malformed("angle", hexText)
            value = value * 16 + digit
        }
        return if (hexText.length == 4) {
            value / 65_536.0 * 360.0
        } else {
            (value / 256) / 16_777_216.0 * 360.0
        }
    }

    /** Decodes the payload `RRRR,DDDD` or `RRRRRRRR,DDDDDDDD` of `E` / `e` (without the `#`) into a place. */
    @Throws(MountError::class)
    fun decodePosition(payload: String): EquatorialCoordinate {
        val parts = payload.split(',')
        if (parts.size != 2 || parts[0].length != parts[1].length) throw malformed("position", payload)
        val rightAscension = decodeAngle(parts[0])
        var declination = decodeAngle(parts[1])
        if (declination > 180.0) declination -= 360.0
        if (abs(declination) > 90.0 + declinationSlackDegrees) throw malformed("position", payload)
        return EquatorialCoordinate(rightAscension, declination.coerceIn(-90.0, 90.0))
    }

    // MARK: Pass-through

    /**
     * `P` and seven bytes: a pass-through command to [device] with command [commandID], three data bytes and the
     * number of reply bytes expected ([responseLength]).
     */
    fun passthrough(
        payloadLength: Int,
        device: Int,
        commandID: Int,
        data1: Int,
        data2: Int,
        data3: Int,
        responseLength: Int,
    ): ByteArray = byteArrayOf(
        'P'.code.toByte(), payloadLength.toByte(), device.toByte(), commandID.toByte(),
        data1.toByte(), data2.toByte(), data3.toByte(), responseLength.toByte(),
    )

    /**
     * Variable-rate slew of [axis] at [arcsecondsPerSecond] (0 to 16 383.75, resolution ¼″/s) in the positive or negative
     * direction: `P`, 3, device, 6 or 7, rate×4 high byte, low byte, 0, 0.
     */
    @Throws(MountError::class)
    fun variableRateSlew(axis: NexStarAxis, positive: Boolean, arcsecondsPerSecond: Double): ByteArray {
        if (!(arcsecondsPerSecond >= 0) || arcsecondsPerSecond > 16_383.75) {
            throw MountError.InvalidInput("the rate is outside 0 to 16383.75 arcseconds per second")
        }
        val quarters = (arcsecondsPerSecond * 4).roundedHalfAwayFromZero().toInt()
        return passthrough(3, axis.device, if (positive) 6 else 7, quarters / 256, quarters % 256, 0, 0)
    }

    /** Fixed-rate slew of [axis] at hand-control rate [rate] (1 to 9; 0 stops): `P`, 2, device, 36 or 37, rate, 0, 0, 0. */
    @Throws(MountError::class)
    fun fixedRateSlew(axis: NexStarAxis, positive: Boolean, rate: Int): ByteArray {
        if (rate !in 0..9) throw MountError.InvalidInput("the fixed rate must be 0 to 9")
        return passthrough(2, axis.device, if (positive) 36 else 37, rate, 0, 0, 0)
    }

    /** `P`, 1, device, 254, 0, 0, 0, 2: get the firmware version of a motor controller. */
    fun getDeviceVersion(axis: NexStarAxis): ByteArray = passthrough(1, axis.device, 254, 0, 0, 0, 2)

    // MARK: Time and location

    /**
     * `W` and eight bytes: set the site. Latitude and longitude are rounded to the arcsecond; the flags are 0 for north
     * and east, 1 for south and west.
     */
    @Throws(MountError::class)
    fun setLocation(location: GeographicLocation): ByteArray {
        if (!location.latitude.isFinite() || !location.longitude.isFinite()) throw MountError.InvalidInput("the location is not a number")
        val latitude = abs(location.latitude.coerceIn(-90.0, 90.0))
        val longitude = abs(location.longitude.coerceIn(-180.0, 180.0))
        val latSeconds = (latitude * 3600).roundedHalfAwayFromZero().toInt()
        val lonSeconds = (longitude * 3600).roundedHalfAwayFromZero().toInt()
        return byteArrayOf(
            'W'.code.toByte(),
            (latSeconds / 3600).toByte(), (latSeconds % 3600 / 60).toByte(), (latSeconds % 60).toByte(),
            (if (location.latitude < 0) 1 else 0).toByte(),
            (lonSeconds / 3600).toByte(), (lonSeconds % 3600 / 60).toByte(), (lonSeconds % 60).toByte(),
            (if (location.longitude < 0) 1 else 0).toByte(),
        )
    }

    /**
     * `H` and eight bytes: set the clock to hour, minute, second, month, day, year−2000, the zone offset
     * (256 − hours for zones west of Greenwich) and the daylight-saving flag. [dateTime] must be in 2000…2099.
     */
    @Throws(MountError::class)
    fun setTime(dateTime: MountDateTime, zoneOffsetHours: Int, daylightSaving: Boolean): ByteArray {
        if (dateTime.year !in 2000..2099) throw MountError.InvalidInput("the hand control accepts the years 2000 to 2099")
        if (zoneOffsetHours !in -12..14) throw MountError.InvalidInput("the zone offset must be −12 to +14 hours")
        return byteArrayOf(
            'H'.code.toByte(),
            dateTime.hour.toByte(), dateTime.minute.toByte(), dateTime.second.toByte(),
            dateTime.month.toByte(), dateTime.day.toByte(), (dateTime.year - 2000).toByte(),
            (if (zoneOffsetHours < 0) 256 + zoneOffsetHours else zoneOffsetHours).toByte(),
            (if (daylightSaving) 1 else 0).toByte(),
        )
    }

    // MARK: Replies

    /**
     * Decodes a version reply without its `#`: two bytes (Celestron, major and minor) or six hexadecimal digits
     * (SynScan: `042507` is 4.37.07).
     */
    @Throws(MountError::class)
    fun decodeVersion(payload: ByteArray): NexStarVersion {
        if (payload.size == 2) {
            return NexStarVersion(NexStarFlavor.CELESTRON, payload[0].toInt() and 0xFF, payload[1].toInt() and 0xFF, 0)
        }
        if (payload.size == 6) {
            val digits = IntArray(6)
            for (index in 0 until 6) {
                digits[index] = hexDigit((payload[index].toInt() and 0xFF).toChar())
                if (digits[index] < 0) throw MountError.MalformedReply("version")
            }
            return NexStarVersion(
                NexStarFlavor.SYNSCAN,
                digits[0] * 16 + digits[1], digits[2] * 16 + digits[3], digits[4] * 16 + digits[5],
            )
        }
        throw MountError.MalformedReply("version")
    }

    /** The display name of a model number from the `m` command for the controller family. */
    fun modelName(model: Int, flavor: NexStarFlavor): String = when (flavor) {
        NexStarFlavor.CELESTRON -> when (model) {
            1 -> "Celestron NexStar GPS"
            3 -> "Celestron NexStar i-Series"
            4 -> "Celestron NexStar i-Series SE"
            5 -> "Celestron CGE"
            6 -> "Celestron Advanced GT"
            7 -> "Celestron SLT"
            9 -> "Celestron CPC"
            10 -> "Celestron GT"
            11 -> "Celestron NexStar 4/5 SE"
            12 -> "Celestron NexStar 6/8 SE"
            else -> "Celestron NexStar"
        }
        NexStarFlavor.SYNSCAN -> when (model) {
            0 -> "Sky-Watcher EQ6 GOTO"
            1 -> "Sky-Watcher HEQ5 GOTO"
            2 -> "Sky-Watcher EQ5 GOTO"
            3 -> "Sky-Watcher EQ3 GOTO"
            4 -> "Sky-Watcher EQ8 GOTO"
            5 -> "Sky-Watcher AZ-EQ6 GOTO"
            6 -> "Sky-Watcher AZ-EQ5 GOTO"
            in 128..143 -> "Sky-Watcher AZ GOTO"
            in 144..159 -> "Sky-Watcher Dobsonian GOTO"
            160 -> "Sky-Watcher AllView GOTO"
            else -> "Sky-Watcher SynScan"
        }
    }

    // MARK: Helpers

    /** Declination replies may overshoot ±90° by this much (rounding of the last unit) before they count as garbled. */
    const val declinationSlackDegrees: Double = 0.01

    private fun malformed(what: String, text: String): MountError =
        MountError.MalformedReply("$what \"${MountText.sanitized(text, 24)}\"")

    private fun hex(value: Long, digits: Int): String = value.toString(16).uppercase().padStart(digits, '0')

    private fun hexDigit(ch: Char): Int = when (ch) {
        in '0'..'9' -> ch - '0'
        in 'A'..'F' -> ch - 'A' + 10
        in 'a'..'f' -> ch - 'a' + 10
        else -> -1
    }
}

package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero

/**
 * The commands of the Sky-Watcher motor controller protocol that the engine uses (letter and the length of the data in
 * a normal reply). Reference: Sky-Watcher, "Motor Controller Command Set" (`skywatcher_motor_controller_command_set.pdf`,
 * sha256 `a4c583bdf617b45ea64b3c742e8b8ab7009a953a2d42c97fdaacd67455d7f73a`, 25 Aug 2021); the commands `H`, `M`, `T`
 * and `U` are listed there greyed out, their use follows INDI's `skywatcherAPI.cpp` (GPL, read for facts only). The
 * bootloader, EEPROM, register and debug commands are deliberately not offered.
 */
enum class SkyWatcherCommand(
    /** The command letter. */
    val letter: Char,
    /** The number of hex digits in the data of a normal reply; -1 if it is not fixed. */
    val replyDigits: Int,
) {
    /** `E`: set the position count (motor must be stopped). */
    SET_POSITION('E', 0),

    /** `F`: initialization done; motion commands are refused until it was sent after power-up. */
    INITIALIZATION_DONE('F', 0),

    /** `G`: set the motion mode and direction for the next start (motor must be stopped). */
    SET_MOTION_MODE('G', 0),

    /** `H`: set the GOTO target as an increment from the current position. */
    SET_GOTO_TARGET_INCREMENT('H', 0),

    /** `M`: set the brake point (where the deceleration of a GOTO starts) as an increment. */
    SET_BRAKE_POINT_INCREMENT('M', 0),

    /** `S`: set the GOTO target as an absolute position. */
    SET_GOTO_TARGET('S', 0),

    /** `I`: set the step period (the timer preset value, which sets the speed). */
    SET_STEP_PERIOD('I', 0),

    /** `T`: set the long GOTO step period. */
    SET_LONG_GOTO_STEP_PERIOD('T', 0),

    /** `U`: set the brake steps (deceleration ramp length in speed mode). */
    SET_BRAKE_STEPS('U', 0),

    /** `J`: start the motion. */
    START_MOTION('J', 0),

    /** `K`: stop with deceleration (the axis returns to speed mode). */
    STOP_MOTION('K', 0),

    /** `L`: stop instantly. */
    INSTANT_STOP('L', 0),

    /** `B`: wake up or put the driver to sleep. */
    SET_SLEEP('B', 0),

    /** `O`: auxiliary switch (camera snap port) on or off. */
    SET_AUX_SWITCH('O', 0),

    /** `P`: set the autoguide speed. */
    SET_AUTOGUIDE_SPEED('P', 0),

    /** `a`: counts per revolution of the axis. */
    INQUIRE_COUNTS_PER_REVOLUTION('a', 6),

    /** `b`: frequency of the step timer (channel 1 answers for both). */
    INQUIRE_TIMER_FREQUENCY('b', 6),

    /** `c`: brake steps. */
    INQUIRE_BRAKE_STEPS('c', 6),

    /** `h`: the GOTO target position. */
    INQUIRE_GOTO_TARGET('h', 6),

    /** `i`: the step period. */
    INQUIRE_STEP_PERIOD('i', 6),

    /** `j`: the position count (offset by 0x800000). */
    INQUIRE_POSITION('j', 6),

    /** `k`: the GOTO increment. */
    INQUIRE_INCREMENT('k', 6),

    /** `m`: the brake point. */
    INQUIRE_BRAKE_POINT('m', 6),

    /** `f`: the axis status (three hex digits). */
    INQUIRE_STATUS('f', 3),

    /** `g`: the high-speed ratio (two hex digits). */
    INQUIRE_HIGH_SPEED_RATIO('g', 2),

    /** `D`: the step period of 1x sidereal tracking. */
    INQUIRE_TRACKING_PERIOD('D', 6),

    /** `d`: the position of the telescope axis (auxiliary encoder). */
    INQUIRE_AXIS_POSITION('d', 6),

    /** `e`: the motor board version. */
    INQUIRE_VERSION('e', 6),

    /** `s`: the PEC period (counts per worm revolution). */
    INQUIRE_PEC_PERIOD('s', 6),

    /** `W`: extended setting. */
    EXTENDED_SETTING('W', -1),

    /** `q`: extended inquire (features). */
    EXTENDED_INQUIRE('q', 6);

    /** True for the commands that ask for a value. */
    val isInquiry: Boolean get() = replyDigits > 0

    companion object {
        /** The command with the given letter, or null if there is none. */
        fun fromLetter(letter: Char): SkyWatcherCommand? = entries.firstOrNull { it.letter == letter }
    }
}

/** A decoded reply of the motor controller. */
sealed interface SkyWatcherReply {
    /** `=` followed by [payload] (0 to 6 uppercase hex digits). */
    data class Success(val payload: String) : SkyWatcherReply

    /** `!` followed by an error [code]. */
    data class Failure(val code: Int) : SkyWatcherReply
}

/** The step period and speed class that give a wanted speed (see [SkyWatcherCodec.speed]). */
data class SkyWatcherSpeed(
    /** The timer preset value for the `I` command. */
    val period: Int,
    /** True if the axis must be started in the high-speed mode (`3`). */
    val isHighSpeed: Boolean,
)

/**
 * Encoding and decoding of the Sky-Watcher motor controller protocol (the same command set on the serial line and on the
 * Wi-Fi mounts' UDP port 11880): commands `:` letter channel data CR, replies `=` data CR or `!` code CR, numbers as
 * little-endian hex bytes (0x123456 is sent as "563412"), positions offset by 0x800000. Pure functions; every decoder
 * validates characters and lengths and throws [MountError.MalformedReply].
 */
object SkyWatcherCodec {
    /** The first byte of a command, `:`. */
    const val leadingByte: Int = 0x3A

    /** The last byte of a command and of a reply, carriage return. */
    const val terminator: Int = 0x0D

    /** Position counts are sent as `count + 0x800000`. */
    const val positionOffset: Int = 0x800000

    /** The most data digits a command or reply carries. */
    const val maximumDigits: Int = 6

    /** The smallest step period the engine asks for (INDI's `minperiods`, 6 timer ticks). */
    const val minimumPeriod: Int = 6

    /** The largest step period (24 bits). */
    const val maximumPeriod: Int = 0xFFFFFF

    /** The speed above which an axis must run in the high-speed mode: 128 times the sidereal rate (INDI's `LOW_SPEED_MARGIN`). */
    const val lowSpeedLimitSiderealMultiple: Double = 128.0

    // MARK: Numbers

    private const val hexDigits = "0123456789ABCDEF"

    private fun hexValue(character: Char): Int = when (character) {
        in '0'..'9' -> character - '0'
        in 'A'..'F' -> character - 'A' + 10
        else -> -1
    }

    private fun encodeBytes(value: Int, byteCount: Int): String {
        val builder = StringBuilder()
        for (index in 0 until byteCount) {
            val byte = (value shr (8 * index)) and 0xFF
            builder.append(hexDigits[byte shr 4])
            builder.append(hexDigits[byte and 15])
        }
        return builder.toString()
    }

    private fun decodeBytes(text: String, byteCount: Int): Int {
        if (text.length != byteCount * 2) {
            throw MountError.MalformedReply("expected ${byteCount * 2} hex digits, got ${text.length}")
        }
        var value = 0
        for (index in 0 until byteCount) {
            val high = hexValue(text[2 * index])
            val low = hexValue(text[2 * index + 1])
            if (high < 0 || low < 0) throw MountError.MalformedReply("not a hex number")
            value = value or (((high shl 4) or low) shl (8 * index))
        }
        return value
    }

    /** Six hex digits, low byte first: 0x123456 becomes "563412". The value must be 0 to 0xFFFFFF. */
    @Throws(MountError::class)
    fun encode24(value: Int): String {
        if (value < 0 || value > 0xFFFFFF) throw MountError.InvalidInput("$value does not fit in 24 bits")
        return encodeBytes(value, 3)
    }

    /** Four hex digits, low byte first: 0x1234 becomes "3412". */
    @Throws(MountError::class)
    fun encode16(value: Int): String {
        if (value < 0 || value > 0xFFFF) throw MountError.InvalidInput("$value does not fit in 16 bits")
        return encodeBytes(value, 2)
    }

    /** Two hex digits: 0x12 becomes "12". */
    @Throws(MountError::class)
    fun encode8(value: Int): String {
        if (value < 0 || value > 0xFF) throw MountError.InvalidInput("$value does not fit in 8 bits")
        return encodeBytes(value, 1)
    }

    /** The inverse of [encode24]: exactly six uppercase hex digits. */
    @Throws(MountError::class)
    fun decode24(text: String): Int = decodeBytes(text, 3)

    /** The inverse of [encode16]. */
    @Throws(MountError::class)
    fun decode16(text: String): Int = decodeBytes(text, 2)

    /** The inverse of [encode8]. */
    @Throws(MountError::class)
    fun decode8(text: String): Int = decodeBytes(text, 1)

    /** A position count relative to the power-up position (-0x800000 to 0x7FFFFF) as the six digits the controller expects. */
    @Throws(MountError::class)
    fun encodePosition(counts: Int): String {
        if (counts < -positionOffset || counts >= positionOffset) throw MountError.InvalidInput("position $counts is out of range")
        return encode24(counts + positionOffset)
    }

    /** The position count relative to the power-up position in a six-digit reply. */
    @Throws(MountError::class)
    fun decodePosition(text: String): Int = decode24(text) - positionOffset

    // MARK: Requests

    /**
     * One command as a datagram or serial line: `:`, the command letter, the channel digit, [data] (at most six
     * uppercase hex digits) and a carriage return.
     */
    @Throws(MountError::class)
    fun request(command: SkyWatcherCommand, axis: SkyWatcherAxis, data: String = ""): ByteArray {
        if (data.length > maximumDigits) throw MountError.InvalidInput("a command carries at most $maximumDigits data digits")
        for (character in data) {
            if (hexValue(character) < 0) throw MountError.InvalidInput("command data must be uppercase hex digits")
        }
        val text = ":" + command.letter + axis.channel + data
        val bytes = ByteArray(text.length + 1)
        for (index in text.indices) bytes[index] = text[index].code.toByte()
        bytes[text.length] = terminator.toByte()
        return bytes
    }

    /** An inquiry (`a b c h i j k m f g D d e s q` letters) for [axis]. */
    @Throws(MountError::class)
    fun inquire(command: SkyWatcherCommand, axis: SkyWatcherAxis): ByteArray {
        if (!command.isInquiry) throw MountError.InvalidInput("${command.letter} is not an inquiry")
        val data = if (command == SkyWatcherCommand.EXTENDED_INQUIRE) encode24(1) else ""
        return request(command, axis, data)
    }

    /** `G`: [mode] and [direction] for the next start. */
    @Throws(MountError::class)
    fun setMotionMode(axis: SkyWatcherAxis, mode: SkyWatcherMotionMode, direction: SkyWatcherDirection): ByteArray =
        request(SkyWatcherCommand.SET_MOTION_MODE, axis, "${mode.code}${direction.code}")

    /** `H`: the GOTO distance in counts (0 to 0xFFFFFF; the direction is the `G` direction). */
    @Throws(MountError::class)
    fun setGotoTargetIncrement(axis: SkyWatcherAxis, counts: Int): ByteArray =
        request(SkyWatcherCommand.SET_GOTO_TARGET_INCREMENT, axis, encode24(counts))

    /** `M`: the distance before the target where the GOTO starts to brake, in counts. */
    @Throws(MountError::class)
    fun setBrakePointIncrement(axis: SkyWatcherAxis, counts: Int): ByteArray =
        request(SkyWatcherCommand.SET_BRAKE_POINT_INCREMENT, axis, encode24(counts))

    /** `S`: the absolute GOTO target as a position count relative to the power-up position. */
    @Throws(MountError::class)
    fun setGotoTarget(axis: SkyWatcherAxis, position: Int): ByteArray =
        request(SkyWatcherCommand.SET_GOTO_TARGET, axis, encodePosition(position))

    /** `I`: the step period (timer preset value). */
    @Throws(MountError::class)
    fun setStepPeriod(axis: SkyWatcherAxis, period: Int): ByteArray =
        request(SkyWatcherCommand.SET_STEP_PERIOD, axis, encode24(period))

    /** `J`: start the motion. */
    @Throws(MountError::class)
    fun start(axis: SkyWatcherAxis): ByteArray = request(SkyWatcherCommand.START_MOTION, axis)

    /** `K` (decelerate) or `L` (instant): stop the motion. */
    @Throws(MountError::class)
    fun stop(axis: SkyWatcherAxis, instant: Boolean = false): ByteArray =
        request(if (instant) SkyWatcherCommand.INSTANT_STOP else SkyWatcherCommand.STOP_MOTION, axis)

    /** `E`: declare the position count (relative to the power-up position) of a stopped axis. */
    @Throws(MountError::class)
    fun setPosition(axis: SkyWatcherAxis, counts: Int): ByteArray =
        request(SkyWatcherCommand.SET_POSITION, axis, encodePosition(counts))

    /** `F`: initialization done. */
    @Throws(MountError::class)
    fun initializationDone(axis: SkyWatcherAxis): ByteArray = request(SkyWatcherCommand.INITIALIZATION_DONE, axis)

    // MARK: Replies

    /**
     * Decodes one reply without its carriage return. Bytes before the first `=` or `!` are skipped (an echoed command, a
     * stray line feed); after it come at most six uppercase hex digits (`=`) or one or two hex digits (`!`).
     *
     * @throws MountError.MalformedReply for anything else.
     */
    @Throws(MountError::class)
    fun parseReply(line: ByteArray): SkyWatcherReply {
        var start = -1
        for (index in line.indices) {
            val byte = line[index].toInt() and 0xFF
            if (byte == '='.code || byte == '!'.code) {
                start = index
                break
            }
        }
        if (start < 0) throw MountError.MalformedReply("no reply marker")
        val marker = (line[start].toInt() and 0xFF).toChar()
        val rest = StringBuilder()
        for (index in start + 1 until line.size) {
            val character = (line[index].toInt() and 0xFF).toChar()
            if (hexValue(character) < 0) throw MountError.MalformedReply("unexpected character in the reply")
            rest.append(character)
        }
        if (marker == '=') {
            if (rest.length > maximumDigits) throw MountError.MalformedReply("reply too long")
            return SkyWatcherReply.Success(rest.toString())
        }
        if (rest.isEmpty() || rest.length > 2) throw MountError.MalformedReply("malformed error code")
        var code = 0
        for (character in rest) code = code * 16 + hexValue(character)
        return SkyWatcherReply.Failure(code)
    }

    /** Decodes a whole datagram: the reply up to the first carriage return, which must be present. */
    @Throws(MountError::class)
    fun parseDatagram(bytes: ByteArray): SkyWatcherReply {
        var end = -1
        for (index in bytes.indices) {
            if ((bytes[index].toInt() and 0xFF) == terminator) {
                end = index
                break
            }
        }
        if (end < 0) throw MountError.MalformedReply("the reply is incomplete")
        return parseReply(bytes.copyOfRange(0, end))
    }

    /** The error to throw for the reply `!code`. */
    fun error(code: Int): MountError = when (code) {
        SkyWatcherErrorCode.UNKNOWN_COMMAND.code -> MountError.NotSupported(MountFeature.OTHER)
        SkyWatcherErrorCode.COMMAND_LENGTH.code -> MountError.Rejected("command length error")
        SkyWatcherErrorCode.MOTOR_NOT_STOPPED.code -> MountError.Busy
        SkyWatcherErrorCode.INVALID_CHARACTER.code -> MountError.Rejected("invalid character")
        SkyWatcherErrorCode.NOT_INITIALIZED.code -> MountError.Rejected("the motor controller is not initialized")
        SkyWatcherErrorCode.DRIVER_SLEEPING.code -> MountError.Standby
        SkyWatcherErrorCode.PEC_TRAINING_RUNNING.code -> MountError.Busy
        SkyWatcherErrorCode.NO_VALID_PEC_DATA.code -> MountError.Rejected("no valid PEC data")
        else -> MountError.Remote(code, "undocumented motor controller error")
    }

    /**
     * True for the errors that a damaged request can cause (unknown command, length, invalid character): the driver
     * sends the command again before it believes them. The other codes describe the controller's state.
     */
    fun isPossiblyCausedByDamagedRequest(code: Int): Boolean =
        code == SkyWatcherErrorCode.UNKNOWN_COMMAND.code ||
            code == SkyWatcherErrorCode.COMMAND_LENGTH.code ||
            code == SkyWatcherErrorCode.INVALID_CHARACTER.code

    /** The axis status in a three-digit `f` reply. */
    @Throws(MountError::class)
    fun decodeStatus(payload: String): SkyWatcherAxisStatus {
        if (payload.length != 3) throw MountError.MalformedReply("status needs 3 digits")
        val first = hexValue(payload[0])
        val second = hexValue(payload[1])
        val third = hexValue(payload[2])
        if (first < 0 || second < 0 || third < 0) throw MountError.MalformedReply("status is not hex")
        return SkyWatcherAxisStatus(
            isSpeedMode = (first and 1) != 0,
            isDecreasing = (first and 2) != 0,
            isFast = (first and 4) != 0,
            isRunning = (second and 1) != 0,
            isBlocked = (second and 2) != 0,
            isInitialized = (third and 1) != 0,
            isLevelSwitchOn = (third and 2) != 0,
        )
    }

    /** Encodes a status as the three digits of an `f` reply (for simulators and tests). */
    fun encodeStatus(status: SkyWatcherAxisStatus): String {
        val first = (if (status.isSpeedMode) 1 else 0) or (if (status.isDecreasing) 2 else 0) or (if (status.isFast) 4 else 0)
        val second = (if (status.isRunning) 1 else 0) or (if (status.isBlocked) 2 else 0)
        val third = (if (status.isInitialized) 1 else 0) or (if (status.isLevelSwitchOn) 2 else 0)
        return "${hexDigits[first]}${hexDigits[second]}${hexDigits[third]}"
    }

    /** The motor board version in a six-digit `e` reply: the digits `AABBCC` are firmware `AA.BB` and mount code `CC`. */
    @Throws(MountError::class)
    fun decodeVersion(payload: String): SkyWatcherVersion {
        if (payload.length != 6) throw MountError.MalformedReply("version needs 6 digits")
        val values = IntArray(6)
        for (index in 0 until 6) {
            values[index] = hexValue(payload[index])
            if (values[index] < 0) throw MountError.MalformedReply("version is not hex")
        }
        return SkyWatcherVersion(
            major = values[0] * 16 + values[1],
            minor = values[2] * 16 + values[3],
            mountCode = values[4] * 16 + values[5],
        )
    }

    /** Encodes a version as the six digits of an `e` reply (for simulators and tests). */
    fun encodeVersion(version: SkyWatcherVersion): String =
        SkyWatcherMountCatalog.hex2(version.major) + SkyWatcherMountCatalog.hex2(version.minor) +
            SkyWatcherMountCatalog.hex2(version.mountCode)

    /** The high-speed ratio in a two-digit `g` reply (1 to 255). */
    @Throws(MountError::class)
    fun decodeHighSpeedRatio(payload: String): Int {
        val value = decode8(payload)
        if (value == 0) throw MountError.MalformedReply("the high-speed ratio is zero")
        return value
    }

    // MARK: Speeds

    /** The fastest speed of the low-speed mode in degrees per second (128 times sidereal). */
    val lowSpeedLimitDegreesPerSecond: Double get() = lowSpeedLimitSiderealMultiple * MountNudgeRate.siderealDegreesPerSecond

    /**
     * The step period for turning an axis at [degreesPerSecond] (the sign is ignored), following the command set's formula
     * `T1 = TMR_Freq · 360 / speed / CPR`, multiplied by the high-speed ratio above 128 times sidereal. Periods below
     * [minimumPeriod] are raised to it (the axis then runs at the controller's maximum). Returns null if the speed is not a
     * positive number or so slow that the period would not fit in 24 bits.
     */
    fun speed(degreesPerSecond: Double, countsPerRevolution: Int, timerFrequency: Int, highSpeedRatio: Int): SkyWatcherSpeed? {
        val rate = kotlin.math.abs(degreesPerSecond)
        if (!rate.isFinite() || !(rate > 0) || countsPerRevolution <= 0 || timerFrequency <= 0 || highSpeedRatio <= 0) return null
        val isHigh = rate > lowSpeedLimitDegreesPerSecond
        val factor = if (isHigh) highSpeedRatio.toDouble() else 1.0
        val period = factor * timerFrequency.toDouble() * 360.0 / (rate * countsPerRevolution.toDouble())
        if (!(period < maximumPeriod.toDouble() + 0.5)) return null
        val rounded = period.roundedHalfAwayFromZero().toInt()
        return SkyWatcherSpeed(maxOf(rounded, minimumPeriod), isHigh)
    }

    /** The speed in degrees per second that the step period [period] gives (the inverse of [speed]). */
    fun degreesPerSecond(
        period: Int,
        isHighSpeed: Boolean,
        countsPerRevolution: Int,
        timerFrequency: Int,
        highSpeedRatio: Int,
    ): Double {
        if (period <= 0 || countsPerRevolution <= 0 || timerFrequency <= 0) return 0.0
        val factor = if (isHighSpeed) highSpeedRatio.toDouble() else 1.0
        return factor * timerFrequency.toDouble() * 360.0 / (period.toDouble() * countsPerRevolution.toDouble())
    }
}

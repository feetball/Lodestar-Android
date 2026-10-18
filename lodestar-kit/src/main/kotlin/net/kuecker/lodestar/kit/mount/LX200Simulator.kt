package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate

/**
 * An in-memory LX200 mount behind a [MountTransport], for tests and UI previews. It parses `:…#` commands like a Meade
 * or OnStep controller, answers with the documented reply formats (including the single-digit `:MS#` answers of OnStep and
 * the `1<text>#` answers of Meade), and drives a [MountSimulatorModel]. Defects are injected with [inject] or the public
 * properties: busy NAKs ([busyReplies]), refused slews ([refusalCode]), a low-precision mount ([highPrecision]).
 */
class LX200Simulator(
    clock: ManualMountClock,
    /** The moving mount behind the protocol. */
    val mount: MountSimulatorModel,
    /** The firmware family: it decides which commands exist and how errors are reported. */
    val dialect: LX200Dialect = LX200Dialect.ONSTEP,
    /** The position format currently selected on the mount (`:U#` toggles it). */
    var highPrecision: Boolean = true,
    /** The answer to `:GVP#`. */
    val productName: String = if (dialect == LX200Dialect.ONSTEP) "On-Step" else "LX200 Classic",
    latencySeconds: Double = 0.05,
    chunkSize: Int = 0,
) : MountSimulatedStreamTransport(clock, latencySeconds, chunkSize) {
    /** Answer this many commands with the busy byte (NAK) before behaving normally. */
    var busyReplies: Int = 0

    /** If set (`'1'` … `'9'`), `:MS#` and `:CM#` refuse with this code (see [LX200Codec.slewRefusal]). */
    var refusalCode: Char? = null

    /** The number of alignment stars `:GW#` reports. */
    var alignedStars: Int = 2

    /** The site and clock commands received (`St+40*00`, `Sg104*00`, `SG+00`, `SC10/18/26`, `SL04:00:00`), in order. */
    val siteCommands: List<String> get() = siteLog

    private val siteLog = ArrayList<String>()
    private var targetRightAscension: Double? = null
    private var targetDeclination: Double? = null
    private var selectedRate: MountNudgeRate = MountNudgeRate.GUIDE
    private var rightAscensionRate = 0.0
    private var declinationRate = 0.0

    private val precision: LX200Precision get() = if (highPrecision) LX200Precision.HIGH else LX200Precision.LOW

    override fun consume(pending: ByteArray): Int {
        val colon = pending.indexOf(':'.code.toByte())
        if (colon < 0) return pending.size
        var hash = -1
        for (index in colon until pending.size) {
            if (pending[index] == '#'.code.toByte()) {
                hash = index
                break
            }
        }
        if (hash < 0) return 0
        handle(MountText.latin1(pending.copyOfRange(colon + 1, hash)))
        return hash + 1
    }

    private fun text(label: String, answer: String) = reply(label, MountText.latin1Bytes(answer))

    private fun handle(frame: String) {
        if (busyReplies > 0) {
            busyReplies -= 1
            reply(frame, byteArrayOf(LX200Codec.busyByte.toByte()))
            return
        }
        when {
            frame == "GR" -> text(frame, LX200Codec.formatRightAscension(mount.position.rightAscension / 15.0, precision) + "#")
            frame == "GD" -> text(frame, LX200Codec.formatDeclination(mount.position.declination, precision) + "#")
            frame == "GVP" -> text(frame, "$productName#")
            frame == "GVN" -> text(frame, "4.24#")
            frame == "GU" && dialect == LX200Dialect.ONSTEP -> text(frame, statusText() + "#")
            frame == "GW" && dialect == LX200Dialect.ONSTEP ->
                text(frame, "G${if (mount.isTracking) 'T' else 'N'}${alignedStars.coerceIn(0, 9)}#")
            frame == "D" -> text(frame, if (mount.isSlewing) "\u007f#" else "#")
            frame.startsWith("Sr") -> setTarget(frame, isRightAscension = true)
            frame.startsWith("Sd") -> setTarget(frame, isRightAscension = false)
            frame == "MS" -> slewToTarget(frame)
            frame == "CM" -> syncToTarget(frame)
            frame == "Q" -> {
                rightAscensionRate = 0.0
                declinationRate = 0.0
                mount.stop()
                noReply(frame)
            }
            frame.length == 2 && frame[0] == 'Q' -> move(frame, start = false)
            frame.length == 2 && frame[0] == 'M' -> move(frame, start = true)
            frame.length == 2 && frame[0] == 'R' -> selectRate(frame)
            frame == "U" -> {
                highPrecision = !highPrecision
                noReply(frame)
            }
            frame == "Te" && dialect == LX200Dialect.ONSTEP -> setTracking(frame, true)
            frame == "Td" && dialect == LX200Dialect.ONSTEP -> setTracking(frame, false)
            frame.startsWith("SC") -> {
                siteLog.add(frame)
                text(frame, if (dialect == LX200Dialect.MEADE) "1Updating Planetary Data#${" ".repeat(30)}#" else "1")
            }
            frame.startsWith("St") || frame.startsWith("Sg") || frame.startsWith("SG") || frame.startsWith("SL") -> {
                siteLog.add(frame)
                text(frame, "1")
            }
            else -> noReply(frame)
        }
    }

    private fun statusText(): String {
        val builder = StringBuilder()
        if (!mount.isTracking) builder.append('n')
        if (!mount.isSlewing) builder.append('N')
        builder.append(if (mount.isParked) 'P' else 'p')
        builder.append("Eo000")
        return builder.toString()
    }

    private fun setTarget(frame: String, isRightAscension: Boolean) {
        val argument = frame.substring(2)
        try {
            if (isRightAscension) {
                val reading = LX200Codec.decodeRightAscension(argument)
                if (dialect == LX200Dialect.MEADE && reading.precision != precision) throw MountError.Rejected("precision")
                targetRightAscension = reading.value * 15.0
            } else {
                val reading = LX200Codec.decodeDeclination(argument)
                if (dialect == LX200Dialect.MEADE && reading.precision != precision) throw MountError.Rejected("precision")
                targetDeclination = reading.value
            }
            text(frame, "1")
        } catch (error: MountError) {
            text(frame, "0")
        }
    }

    private fun slewToTarget(frame: String) {
        val ra = targetRightAscension
        val dec = targetDeclination
        val refusal: Char? = refusalCode ?: when {
            mount.isParked -> '4'
            mount.isSlewing -> '5'
            ra == null || dec == null -> '9'
            else -> null
        }
        if (refusal != null || ra == null || dec == null) {
            answerRefusal(frame, refusal ?: '9')
            return
        }
        mount.beginSlew(EquatorialCoordinate(ra, dec))
        text(frame, "0")
    }

    private fun answerRefusal(frame: String, digit: Char) {
        if (dialect == LX200Dialect.ONSTEP) {
            text(frame, digit.toString())
        } else {
            text(frame, "$digit${if (digit == '1') "Object below horizon" else "Object below higher"}#")
        }
    }

    private fun syncToTarget(frame: String) {
        val code = refusalCode
        val ra = targetRightAscension
        val dec = targetDeclination
        if (dialect == LX200Dialect.ONSTEP) {
            when {
                mount.isParked || mount.isSlewing -> text(frame, "#")
                code != null -> text(frame, "E$code#")
                ra == null || dec == null -> text(frame, "#")
                else -> {
                    mount.sync(EquatorialCoordinate(ra, dec))
                    text(frame, "N/A#")
                }
            }
        } else if (ra != null && dec != null) {
            mount.sync(EquatorialCoordinate(ra, dec))
            text(frame, "Coordinates matched        #")
        } else {
            text(frame, "#")
        }
    }

    private fun setTracking(frame: String, enabled: Boolean) {
        if (mount.isParked || mount.isSlewing) {
            text(frame, "0")
        } else {
            mount.isTracking = enabled
            text(frame, "1")
        }
    }

    private fun selectRate(frame: String) {
        selectedRate = when (frame[1]) {
            'G' -> MountNudgeRate.GUIDE
            'C' -> MountNudgeRate.CENTER
            'M' -> MountNudgeRate.FIND
            'S' -> MountNudgeRate.SLEW
            else -> selectedRate
        }
        noReply(frame)
    }

    private fun move(frame: String, start: Boolean) {
        val speed = if (start) selectedRate.degreesPerSecond else 0.0
        when (frame[1]) {
            'n' -> declinationRate = speed
            's' -> declinationRate = if (start) -speed else 0.0
            'e' -> rightAscensionRate = speed
            'w' -> rightAscensionRate = if (start) -speed else 0.0
            else -> Unit
        }
        mount.setNudgeRates(rightAscensionRate, declinationRate)
        noReply(frame)
    }
}

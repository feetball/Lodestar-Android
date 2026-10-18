package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.time.Instant

/**
 * Settings of an [LX200Driver].
 *
 * Values are clamped to their ranges when the configuration is created.
 */
@ConsistentCopyVisibility
data class LX200Configuration private constructor(
    /** The frame the mount expects; LX200 mounts expect "JNow", so the default is [MountEpoch.MEAN_OF_DATE]. */
    val epoch: MountEpoch,
    /** How long one command may take to answer (0.5 to 30 s). */
    val exchangeTimeoutSeconds: Double,
    /** How long the optional identification commands may take (0.2 to 10 s). */
    val identifyTimeoutSeconds: Double,
    /** How long to wait for the optional text after a `0`/`1` answer (0.05 to 2 s). */
    val tailTimeoutSeconds: Double,
    /** The dialect to assume; null detects it from the product name. */
    val dialect: LX200Dialect?,
    /** Switch the mount to the high precision position format at connect time when it is in the low one. */
    val prefersHighPrecision: Boolean,
    /** How often a command is repeated when the controller answers "busy" (0 to 10). */
    val busyRetries: Int,
) {
    companion object {
        /** The defaults: JNow (mean of date), 3 s timeout, automatic dialect, high precision. */
        val standard: LX200Configuration = invoke()

        /** Creates a configuration; out-of-range values are clamped. */
        operator fun invoke(
            epoch: MountEpoch = MountEpoch.MEAN_OF_DATE,
            exchangeTimeoutSeconds: Double = 3.0,
            identifyTimeoutSeconds: Double = 1.0,
            tailTimeoutSeconds: Double = 0.4,
            dialect: LX200Dialect? = null,
            prefersHighPrecision: Boolean = true,
            busyRetries: Int = 3,
        ): LX200Configuration = LX200Configuration(
            epoch = epoch,
            exchangeTimeoutSeconds = sane(exchangeTimeoutSeconds, 3.0, 0.5, 30.0),
            identifyTimeoutSeconds = sane(identifyTimeoutSeconds, 1.0, 0.2, 10.0),
            tailTimeoutSeconds = sane(tailTimeoutSeconds, 0.4, 0.05, 2.0),
            dialect = dialect,
            prefersHighPrecision = prefersHighPrecision,
            busyRetries = busyRetries.coerceIn(0, 10),
        )

        private fun sane(value: Double, default: Double, lower: Double, upper: Double): Double =
            if (value.isNaN()) default else value.coerceIn(lower, upper)
    }
}

/**
 * Drives an LX200-compatible mount (Meade LX200/Autostar, OnStep, SkyFi and other Wi-Fi bridges) over a byte-stream
 * [MountTransport]. See [LX200Codec] for the command reference.
 *
 * Position format: the driver reads `:GR#`/`:GD#` in whatever precision the mount uses and sends targets in the
 * same precision; at connect time it switches a low-precision mount to high precision with `:U#` (a toggle, sent
 * only after a low-precision reply was seen).
 *
 * Coordinates are in [LX200Configuration.epoch]; the Meade command set sends JNow, which the controller derives from
 * J2000 with the engine's precession ([MountEpoch.MEAN_OF_DATE]).
 */
class LX200Driver(
    transport: MountTransport,
    private val clock: MountClock,
    /** The settings in use. */
    val configuration: LX200Configuration = LX200Configuration.standard,
) : MountDriver {
    private val link = MountLink(transport, clock)

    /** The dialect in use: configured, or detected at [connect]. */
    var dialect: LX200Dialect = configuration.dialect ?: LX200Dialect.MEADE
        private set

    /** The position format last seen from the mount (used for the next `:Sr`/`:Sd`). */
    var precision: LX200Precision = LX200Precision.HIGH
        private set

    override val kind: MountProtocol get() = MountProtocol.LX200

    override val epoch: MountEpoch get() = configuration.epoch

    @Throws(MountError::class)
    override fun connect(): MountCapabilities {
        var name = ""
        var firmware: String? = null
        try {
            name = MountText.sanitized(query(LX200Codec.getProductName, "identify", configuration.identifyTimeoutSeconds), 40)
            if (name.isNotEmpty()) {
                val number = MountText.sanitized(
                    query(LX200Codec.getFirmwareNumber, "identify", configuration.identifyTimeoutSeconds), 20,
                )
                if (number.isNotEmpty()) firmware = number
            }
        } catch (error: MountError.Timeout) {
            // Not every controller knows the identification commands; the position read below decides.
        } catch (error: MountError.MalformedReply) {
            name = ""
            firmware = null
        }
        dialect = configuration.dialect ?: LX200Codec.dialect(name)

        var reading = LX200Codec.decodeRightAscension(query(LX200Codec.getRightAscension, "read right ascension"))
        if (reading.precision == LX200Precision.LOW && configuration.prefersHighPrecision) {
            sendOnly(LX200Codec.togglePrecision)
            reading = LX200Codec.decodeRightAscension(query(LX200Codec.getRightAscension, "read right ascension"))
        }
        precision = reading.precision
        LX200Codec.decodeDeclination(query(LX200Codec.getDeclination, "read declination"))

        var aligned: Boolean? = null
        if (dialect == LX200Dialect.ONSTEP) {
            LX200Codec.decodeStatus(query(LX200Codec.getStatus, "read status"))
            try {
                aligned = LX200Codec.decodeAlignmentStatus(query(LX200Codec.getAlignmentStatus, "read alignment")).alignedStars > 0
            } catch (error: MountError.Timeout) {
                aligned = null
            } catch (error: MountError.MalformedReply) {
                aligned = null
            }
        }
        return MountCapabilities(
            kind = MountProtocol.LX200,
            name = if (name.isEmpty()) "LX200-compatible mount" else name,
            firmware = firmware,
            canSlew = true,
            canSync = true,
            canAbort = true,
            canSetTracking = dialect == LX200Dialect.ONSTEP,
            canNudge = true,
            canSetSiteAndTime = true,
            gotoNeedsAlignment = false,
            isAligned = aligned,
        )
    }

    override fun disconnect() {
        // The protocol has no session; nothing to close.
    }

    @Throws(MountError::class)
    override fun readPosition(): EquatorialCoordinate {
        val ra = LX200Codec.decodeRightAscension(query(LX200Codec.getRightAscension, "read right ascension"))
        val dec = LX200Codec.decodeDeclination(query(LX200Codec.getDeclination, "read declination"))
        precision = ra.precision
        return EquatorialCoordinate.fromRightAscensionHours(ra.value, dec.value)
    }

    @Throws(MountError::class)
    override fun readSlewing(): Boolean = LX200Codec.isSlewing(query(LX200Codec.getSlewDistance, "read slew state"))

    @Throws(MountError::class)
    override fun readTracking(): Boolean? {
        if (dialect != LX200Dialect.ONSTEP) return null
        return readStatus().isTracking
    }

    @Throws(MountError::class)
    override fun readAligned(): Boolean? {
        if (dialect != LX200Dialect.ONSTEP) return null
        return LX200Codec.decodeAlignmentStatus(query(LX200Codec.getAlignmentStatus, "read alignment")).alignedStars > 0
    }

    @Throws(MountError::class)
    override fun slew(target: EquatorialCoordinate) {
        setTarget(target)
        exchange(LX200Codec.slewToTarget, "start slew") { first ->
            val code = first.toChar()
            val refusal = LX200Codec.slewRefusal(code)
            if (refusal != null) {
                if (code == '1' || code == '2') drainTail(1)
                throw refusal
            }
        }
    }

    @Throws(MountError::class)
    override fun sync(target: EquatorialCoordinate) {
        if (dialect == LX200Dialect.ONSTEP) {
            val status = readStatus()
            if (status.isParked) throw MountError.Parked
            if (status.isGotoActive) throw MountError.SlewInProgress
        }
        setTarget(target)
        val reply = query(LX200Codec.syncToTarget, "sync")
        if (reply.length == 2 && reply[0] == 'E' && reply[1] in '1'..'9') {
            throw LX200Codec.slewRefusal(reply[1]) ?: MountError.Rejected("sync")
        }
        if (reply.isEmpty()) throw MountError.Rejected("the mount ignored the sync")
    }

    @Throws(MountError::class)
    override fun abort() {
        sendOnly(LX200Codec.halt)
    }

    @Throws(MountError::class)
    override fun setTracking(enabled: Boolean) {
        if (dialect != LX200Dialect.ONSTEP) throw MountError.NotSupported(MountFeature.TRACKING)
        if (flag(if (enabled) LX200Codec.trackingOn else LX200Codec.trackingOff, "set tracking")) return
        val status = readStatus()
        if (status.isParked) throw MountError.Parked
        if (status.isGotoActive) throw MountError.SlewInProgress
        throw MountError.Rejected("tracking could not be changed")
    }

    @Throws(MountError::class)
    override fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) {
        sendOnly(LX200Codec.setRate(rate))
        sendOnly(LX200Codec.move(direction))
    }

    @Throws(MountError::class)
    override fun stopNudge(direction: MountNudgeDirection) {
        sendOnly(LX200Codec.halt(direction))
    }

    @Throws(MountError::class)
    override fun setSiteAndTime(location: GeographicLocation, date: Instant) {
        val fields = MountCalendar.fields(date) ?: throw MountError.InvalidInput("the date is outside 1970 to 9999")
        requireAccepted(flag(LX200Codec.setLatitude(location.latitude), "set latitude"), "latitude")
        requireAccepted(flag(LX200Codec.setLongitude(location.longitude), "set longitude"), "longitude")
        requireAccepted(flag(LX200Codec.setUTCOffset(0.0, dialect), "set UTC offset"), "UTC offset")
        val dateAccepted = exchange(LX200Codec.setDate(fields), "set date") { first ->
            val accepted = flagValue(first)
            if (accepted && dialect == LX200Dialect.MEADE) drainTail(2)
            accepted
        }
        requireAccepted(dateAccepted, "date")
        requireAccepted(flag(LX200Codec.setTime(fields), "set time"), "time")
    }

    // MARK: Exchanges

    private fun requireAccepted(accepted: Boolean, what: String) {
        if (!accepted) throw MountError.Rejected("the $what was not accepted")
    }

    private fun readStatus(): LX200StatusFlags = LX200Codec.decodeStatus(query(LX200Codec.getStatus, "read status"))

    private fun setTarget(target: EquatorialCoordinate) {
        if (!target.rightAscension.isFinite() || !target.declination.isFinite()) {
            throw MountError.InvalidInput("the target coordinates are not numbers")
        }
        requireAccepted(
            flag(LX200Codec.setTargetRightAscension(target.rightAscension / 15.0, precision), "set target right ascension"),
            "target right ascension",
        )
        requireAccepted(
            flag(LX200Codec.setTargetDeclination(target.declination, precision), "set target declination"),
            "target declination",
        )
    }

    /** Sends a command that has no reply. */
    private fun sendOnly(command: String) {
        link.beginExchange()
        link.send(MountText.ascii(command))
    }

    /** Sends [command], waits for the first reply byte and hands it to [reply]; repeats the command on a busy NAK. */
    private fun <T> exchange(
        command: String,
        operation: String,
        timeoutSeconds: Double = configuration.exchangeTimeoutSeconds,
        reply: (Int) -> T,
    ): T {
        for (attempt in 0..configuration.busyRetries) {
            link.beginExchange()
            link.send(MountText.ascii(command))
            val first = link.readExactly(1, timeoutSeconds, operation)[0].toInt() and 0xFF
            if (first == LX200Codec.busyByte) {
                clock.sleep(busyDelaySeconds)
                continue
            }
            return reply(first)
        }
        throw MountError.Busy
    }

    /** A `#`-terminated text reply (a lone `#` is the empty text). */
    private fun query(
        command: String,
        operation: String,
        timeoutSeconds: Double = configuration.exchangeTimeoutSeconds,
    ): String = exchange(command, operation, timeoutSeconds) { first ->
        if (first == LX200Codec.terminator.code) {
            ""
        } else {
            val rest = link.readUntil(LX200Codec.terminator.code.toByte(), maximumReplyLength, timeoutSeconds, operation)
            MountText.latin1(byteArrayOf(first.toByte()) + rest)
        }
    }

    /** A `0`/`1` flag reply. */
    private fun flag(command: String, operation: String): Boolean =
        exchange(command, operation) { first -> flagValue(first) }

    private fun flagValue(first: Int): Boolean = when (first) {
        '1'.code -> true
        '0'.code -> false
        else -> throw MountError.MalformedReply("flag reply 0x${first.toString(16)}")
    }

    /** Reads and drops up to [segments] `#`-terminated texts that follow a short answer; silence is fine. */
    private fun drainTail(segments: Int) {
        for (segment in 0 until segments) {
            try {
                link.readUntil(
                    LX200Codec.terminator.code.toByte(), maximumReplyLength, configuration.tailTimeoutSeconds, "reply text",
                )
            } catch (error: MountError.Timeout) {
                return
            }
        }
    }

    private companion object {
        const val maximumReplyLength: Int = 63
        const val busyDelaySeconds: Double = 0.1
    }
}

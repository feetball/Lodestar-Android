package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.time.Instant

/**
 * Settings of a [NexStarDriver].
 *
 * Values are clamped to their ranges when the configuration is created.
 */
@ConsistentCopyVisibility
data class NexStarConfiguration private constructor(
    /**
     * The frame of the coordinates on the wire. Null (the default) picks it from the controller family at connect time:
     * Sky-Watcher SynScan firmware 3.37 / 4.37.03 or later documents J2000 for GOTO, sync and position replies
     * ("SynScan Serial Communication Protocol" 3.3), Celestron hand controls take the current epoch ("JNow", mean of
     * date). Set it to override, for example [MountEpoch.MEAN_OF_DATE] for a SynScan hand control with old firmware.
     */
    val epoch: MountEpoch?,
    /** How long one command may take (1 to 30 s). Celestron documents up to 3.5 s, SynScan up to 5 s, in the worst case. */
    val exchangeTimeoutSeconds: Double,
    /** Use the 32-bit position commands; null decides from the firmware version (Celestron 1.6 and later, always for SynScan). */
    val usesPrecisePositions: Boolean?,
    /** The mode `T` sets when tracking is switched on and the mount has not reported a running mode (alt-az for the AZ-GTi family, equatorial for EQ mounts). */
    val preferredTrackingMode: NexStarTrackingMode,
    /** Swap the sense of east and west nudges (the sign of the right ascension / azimuth axis is not documented per mount). */
    val invertsEastWest: Boolean,
) {
    companion object {
        /** Automatic epoch, 5.5 s timeout, alt-azimuth tracking when switched on, INDI's axis signs. */
        val standard: NexStarConfiguration = invoke()

        /** Creates a configuration; out-of-range values are clamped. */
        operator fun invoke(
            epoch: MountEpoch? = null,
            exchangeTimeoutSeconds: Double = 5.5,
            usesPrecisePositions: Boolean? = null,
            preferredTrackingMode: NexStarTrackingMode = NexStarTrackingMode.ALT_AZ,
            invertsEastWest: Boolean = false,
        ): NexStarConfiguration = NexStarConfiguration(
            epoch = epoch,
            exchangeTimeoutSeconds = if (exchangeTimeoutSeconds.isNaN()) 5.5 else exchangeTimeoutSeconds.coerceIn(1.0, 30.0),
            usesPrecisePositions = usesPrecisePositions,
            preferredTrackingMode = if (preferredTrackingMode == NexStarTrackingMode.OFF) {
                NexStarTrackingMode.ALT_AZ
            } else {
                preferredTrackingMode
            },
            invertsEastWest = invertsEastWest,
        )
    }
}

/**
 * Drives a Celestron NexStar or Sky-Watcher SynScan hand control over a byte-stream [MountTransport] (serial bridge,
 * or the SynScan Wi-Fi adapter's UDP/TCP relay). See [NexStarCodec] for the command reference.
 *
 * GOTO and sync only work once the hand control is aligned ("J" answers 1); the driver refuses to slew otherwise.
 * Manual nudges use the variable-rate pass-through slews; as the protocol notes recommend, tracking is switched off for
 * the duration of a nudge and restored afterwards.
 */
class NexStarDriver(
    transport: MountTransport,
    clock: MountClock,
    /** The settings in use. */
    val configuration: NexStarConfiguration = NexStarConfiguration.standard,
) : MountDriver {
    private val link = MountLink(transport, clock)
    private var resolvedEpoch: MountEpoch = configuration.epoch ?: MountEpoch.MEAN_OF_DATE
    private var precise: Boolean = configuration.usesPrecisePositions ?: true
    private var rememberedTrackingMode: NexStarTrackingMode = configuration.preferredTrackingMode
    private var trackingToRestore: NexStarTrackingMode? = null
    private val activeNudges = LinkedHashSet<MountNudgeDirection>()
    private var canReadTracking = true
    private var canSetTime = true

    /** The firmware version found by [connect]; null before. */
    var version: NexStarVersion? = null
        private set

    override val kind: MountProtocol get() = MountProtocol.NEXSTAR

    override val epoch: MountEpoch get() = resolvedEpoch

    @Throws(MountError::class)
    override fun connect(): MountCapabilities {
        val found = readVersion()
        version = found
        resolvedEpoch = configuration.epoch ?: if (found.flavor == NexStarFlavor.SYNSCAN) MountEpoch.J2000 else MountEpoch.MEAN_OF_DATE
        val isSynScan = found.flavor == NexStarFlavor.SYNSCAN
        precise = configuration.usesPrecisePositions ?: (isSynScan || found.isAtLeast(1, 6))
        canReadTracking = isSynScan || found.isAtLeast(2, 3)
        canSetTime = isSynScan || found.isAtLeast(2, 3)

        var name = if (isSynScan) "Sky-Watcher SynScan" else "Celestron NexStar"
        try {
            val model = transact(NexStarCodec.getModel, 1, "read model")
            name = NexStarCodec.modelName(model[0].toInt() and 0xFF, found.flavor)
        } catch (error: MountError.Timeout) {
            // The model command is optional on old firmware.
        } catch (error: MountError.MalformedReply) {
            // Keep the generic name.
        }
        var aligned: Boolean? = null
        try {
            aligned = readAlignmentFlag()
        } catch (error: MountError.Timeout) {
            aligned = null
        }
        if (canReadTracking) {
            val mode = readTrackingMode()
            if (mode != NexStarTrackingMode.OFF) rememberedTrackingMode = mode
        }
        try {
            readPosition()
        } catch (error: MountError.MalformedReply) {
            // Before an alignment the position values are not meaningful and may not even be valid coordinates.
            if (aligned != false) throw error
        }

        val canSync = if (isSynScan) {
            if (found.major == 3) found.isAtLeast(3, 37) else found.isAtLeast(4, 37, 3)
        } else {
            found.isAtLeast(4, 10)
        }
        val canPassThrough = if (isSynScan) {
            if (found.major == 3) found.isAtLeast(3, 3) else found.isAtLeast(4, 1, 1)
        } else {
            found.isAtLeast(1, 6)
        }
        return MountCapabilities(
            kind = MountProtocol.NEXSTAR,
            name = "$name ${found.description}",
            firmware = found.description,
            canSlew = true,
            canSync = canSync,
            canAbort = true,
            canSetTracking = isSynScan || found.isAtLeast(1, 6),
            canNudge = canPassThrough,
            canSetSiteAndTime = canSetTime,
            gotoNeedsAlignment = true,
            isAligned = aligned,
        )
    }

    override fun disconnect() {
        activeNudges.clear()
    }

    @Throws(MountError::class)
    override fun readPosition(): EquatorialCoordinate {
        val payload = transact(NexStarCodec.getPosition(precise), if (precise) 17 else 9, "read position")
        return NexStarCodec.decodePosition(MountText.latin1(payload))
    }

    @Throws(MountError::class)
    override fun readSlewing(): Boolean {
        val reply = transact(NexStarCodec.getGotoInProgress, 1, "read slew state")
        return when (reply[0].toInt()) {
            '1'.code -> true
            '0'.code -> false
            else -> throw MountError.MalformedReply("slew state")
        }
    }

    @Throws(MountError::class)
    override fun readTracking(): Boolean? {
        if (!canReadTracking) return null
        return readTrackingMode() != NexStarTrackingMode.OFF
    }

    @Throws(MountError::class)
    override fun readAligned(): Boolean = readAlignmentFlag()

    @Throws(MountError::class)
    override fun slew(target: EquatorialCoordinate) {
        requireFinite(target)
        if (!readAlignmentFlag()) throw MountError.NotAligned
        expectAcknowledge(NexStarCodec.goto(target, precise), "start slew")
    }

    @Throws(MountError::class)
    override fun sync(target: EquatorialCoordinate) {
        requireFinite(target)
        expectAcknowledge(NexStarCodec.sync(target, precise), "sync")
    }

    @Throws(MountError::class)
    override fun abort() {
        expectAcknowledge(NexStarCodec.cancelGoto, "stop slew")
    }

    @Throws(MountError::class)
    override fun setTracking(enabled: Boolean) {
        val mode = if (enabled) rememberedTrackingMode else NexStarTrackingMode.OFF
        expectAcknowledge(NexStarCodec.setTrackingMode(mode), "set tracking")
        if (enabled) trackingToRestore = null
    }

    @Throws(MountError::class)
    override fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double) {
        if (activeNudges.isEmpty() && canReadTracking) {
            val mode = readTrackingMode()
            if (mode != NexStarTrackingMode.OFF) {
                trackingToRestore = mode
                expectAcknowledge(NexStarCodec.setTrackingMode(NexStarTrackingMode.OFF), "pause tracking")
            }
        }
        val command = NexStarCodec.variableRateSlew(axis(direction), isPositive(direction), rate.degreesPerSecond * 3600.0)
        expectPassthroughAcknowledge(command, "start move")
        activeNudges.add(direction)
    }

    @Throws(MountError::class)
    override fun stopNudge(direction: MountNudgeDirection) {
        expectPassthroughAcknowledge(NexStarCodec.fixedRateSlew(axis(direction), isPositive(direction), 0), "stop move")
        activeNudges.remove(direction)
        if (activeNudges.isEmpty()) {
            val restore = trackingToRestore
            if (restore != null) {
                expectAcknowledge(NexStarCodec.setTrackingMode(restore), "resume tracking")
                trackingToRestore = null
            }
        }
    }

    @Throws(MountError::class)
    override fun setSiteAndTime(location: GeographicLocation, date: Instant) {
        if (!canSetTime) throw MountError.NotSupported(MountFeature.SITE_AND_TIME)
        val fields = MountCalendar.fields(date) ?: throw MountError.InvalidInput("the date is outside 1970 to 9999")
        expectAcknowledge(NexStarCodec.setLocation(location), "set location")
        expectAcknowledge(NexStarCodec.setTime(fields, 0, false), "set time")
    }

    // MARK: Exchanges

    private fun requireFinite(target: EquatorialCoordinate) {
        if (!target.rightAscension.isFinite() || !target.declination.isFinite()) {
            throw MountError.InvalidInput("the target coordinates are not numbers")
        }
    }

    private fun axis(direction: MountNudgeDirection): NexStarAxis = when (direction) {
        MountNudgeDirection.NORTH, MountNudgeDirection.SOUTH -> NexStarAxis.ALTITUDE_DECLINATION
        MountNudgeDirection.EAST, MountNudgeDirection.WEST -> NexStarAxis.AZIMUTH_RIGHT_ASCENSION
    }

    /** North and west are the positive directions of the declination and right ascension axes (as in INDI's Celestron driver). */
    private fun isPositive(direction: MountNudgeDirection): Boolean = when (direction) {
        MountNudgeDirection.NORTH -> true
        MountNudgeDirection.SOUTH -> false
        MountNudgeDirection.WEST -> !configuration.invertsEastWest
        MountNudgeDirection.EAST -> configuration.invertsEastWest
    }

    /** Sends [command] and reads [payloadLength] bytes plus the `#`; returns the payload. */
    private fun transact(command: ByteArray, payloadLength: Int, operation: String): ByteArray {
        link.beginExchange()
        link.send(command)
        val reply = link.readExactly(payloadLength + 1, configuration.exchangeTimeoutSeconds, operation)
        if (reply[payloadLength].toInt() != NexStarCodec.terminator) {
            throw MountError.MalformedReply("$operation: reply not terminated")
        }
        return reply.copyOfRange(0, payloadLength)
    }

    /** A command whose whole reply is `#`. */
    private fun expectAcknowledge(command: ByteArray, operation: String) {
        link.beginExchange()
        link.send(command)
        val reply = link.readExactly(1, configuration.exchangeTimeoutSeconds, operation)
        if (reply[0].toInt() != NexStarCodec.terminator) throw MountError.MalformedReply("$operation: no acknowledgement")
    }

    /**
     * A pass-through command whose answer is `#`; a different first byte means the motor controller did not take part
     * (the hand control then sends one extra byte before the `#`).
     */
    private fun expectPassthroughAcknowledge(command: ByteArray, operation: String) {
        link.beginExchange()
        link.send(command)
        val reply = link.readExactly(1, configuration.exchangeTimeoutSeconds, operation)
        if (reply[0].toInt() != NexStarCodec.terminator) {
            throw MountError.Rejected("the motor controller did not answer ($operation)")
        }
    }

    private fun readVersion(): NexStarVersion {
        link.beginExchange()
        link.send(NexStarCodec.getVersion)
        val timeout = configuration.exchangeTimeoutSeconds
        val head = link.readExactly(3, timeout, "read version")
        if (head[2].toInt() == NexStarCodec.terminator) return NexStarCodec.decodeVersion(head.copyOfRange(0, 2))
        val tail = link.readExactly(4, timeout, "read version")
        val whole = head + tail
        if (whole[6].toInt() != NexStarCodec.terminator) throw MountError.MalformedReply("version: reply not terminated")
        return NexStarCodec.decodeVersion(whole.copyOfRange(0, 6))
    }

    private fun readAlignmentFlag(): Boolean {
        val reply = transact(NexStarCodec.getAlignmentComplete, 1, "read alignment")
        return when (reply[0].toInt()) {
            1 -> true
            0 -> false
            else -> throw MountError.MalformedReply("alignment")
        }
    }

    private fun readTrackingMode(): NexStarTrackingMode {
        val reply = transact(NexStarCodec.getTrackingMode, 1, "read tracking")
        return NexStarTrackingMode.fromCode(reply[0].toInt() and 0xFF) ?: throw MountError.MalformedReply("tracking mode")
    }
}

package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import java.time.Instant

/**
 * One protocol session with a mount, as the [MountController] uses it. Coordinates are in the driver's own
 * [epoch]; the controller converts from and to J2000. Drivers perform one blocking exchange per call, validate every
 * reply and throw [MountError]; they never retry on their own (the controller decides) except for the protocol's own
 * "busy, send again" convention.
 *
 * Not thread safe: use one driver, its transport and its controller from one thread (or one queue) at a time.
 */
interface MountDriver {
    /** The protocol this driver speaks. */
    val kind: MountProtocol

    /** The frame of the coordinates passed to and returned by the other methods. Final after [connect]. */
    val epoch: MountEpoch

    /** Performs the handshake (version, capabilities, first position) and returns what the mount can do. */
    @Throws(MountError::class)
    fun connect(): MountCapabilities

    /** Ends the session; no exchange failures are reported. */
    fun disconnect()

    /** The place the mount points at. */
    @Throws(MountError::class)
    fun readPosition(): EquatorialCoordinate

    /** Whether the mount is executing a slew. */
    @Throws(MountError::class)
    fun readSlewing(): Boolean

    /** Whether the mount is tracking; null if the protocol cannot tell. */
    @Throws(MountError::class)
    fun readTracking(): Boolean?

    /** Whether the mount is aligned (a star alignment is complete); null if the protocol cannot tell. */
    @Throws(MountError::class)
    fun readAligned(): Boolean?

    /** Starts a slew to [target]; returns once the mount has accepted it. */
    @Throws(MountError::class)
    fun slew(target: EquatorialCoordinate)

    /** Tells the mount that it points at [target] now. */
    @Throws(MountError::class)
    fun sync(target: EquatorialCoordinate)

    /** Stops a slew (and any nudge). */
    @Throws(MountError::class)
    fun abort()

    /** Switches tracking on or off. */
    @Throws(MountError::class)
    fun setTracking(enabled: Boolean)

    /**
     * Starts moving in [direction] at [rate]. The mount keeps moving until [stopNudge], except where the protocol
     * has timed pulses, which end by themselves after [durationSeconds].
     */
    @Throws(MountError::class)
    fun startNudge(direction: MountNudgeDirection, rate: MountNudgeRate, durationSeconds: Double)

    /** Stops the nudge in [direction]. */
    @Throws(MountError::class)
    fun stopNudge(direction: MountNudgeDirection)

    /** Sets the mount's site and clock; the clock is set to UTC with no zone offset. */
    @Throws(MountError::class)
    fun setSiteAndTime(location: GeographicLocation, date: Instant)
}

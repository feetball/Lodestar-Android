package net.kuecker.lodestar.kit.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.foundation.formatFixed
import java.time.Instant

/** The control protocols the engine speaks. */
enum class MountProtocol(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Meade LX200 serial command protocol (also OnStep and many Wi-Fi bridges), usually over a TCP socket. */
    LX200("lx200"),

    /** Celestron NexStar / Sky-Watcher SynScan hand-controller protocol, over a TCP or UDP socket. */
    NEXSTAR("nexstar"),

    /** ASCOM Alpaca Telescope interface, REST over HTTP. */
    ALPACA("alpaca");

    companion object {
        /** The protocol with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountProtocol? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** Something a mount may not be able to do, named in [MountError.NotSupported]. */
enum class MountFeature(
    /** The stable string used for storage. */
    val rawValue: String,
    /** Words that finish the sentence "This mount doesn't support …". */
    val displayName: String,
) {
    /** Slewing to coordinates. */
    SLEW("slew", "slewing to coordinates"),

    /** Telling the mount where it is pointing. */
    SYNC("sync", "syncing to a position"),

    /** Stopping a slew. */
    ABORT("abort", "stopping a slew"),

    /** Switching tracking on and off. */
    TRACKING("tracking", "switching tracking on and off"),

    /** Short manual moves. */
    NUDGE("nudge", "manual moves"),

    /** Setting the mount's site and clock. */
    SITE_AND_TIME("siteAndTime", "setting the site and time"),

    /** The mount's coordinate system. */
    COORDINATE_SYSTEM("coordinateSystem", "this coordinate system"),

    /** An operation the protocol layer could not attribute to a feature. */
    OTHER("other", "this operation");

    companion object {
        /** The feature with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountFeature? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * Why a mount operation failed. Every case has a message a photographer can read ([message], same text as
 * [userMessage]); the engine throws these and never traps.
 */
sealed class MountError(message: String) : Exception(message) {
    /** The text to show the user. */
    val userMessage: String get() = message ?: ""

    /** True for failures of the link rather than refusals by the mount: timeouts, I/O errors, garbled replies. */
    val isCommunicationFailure: Boolean
        get() = this is Timeout || this is Transport || this is MalformedReply || this is ConnectionLost

    /** There is no open session with the mount. */
    data object NotConnected : MountError("The mount is not connected.")

    /** The mount cannot accept the command right now. */
    data object Busy : MountError("The mount is busy. Try again in a moment.")

    /** A slew is already running; abort it before starting another. */
    data object SlewInProgress : MountError("The mount is already slewing. Stop it before starting another move.")

    /** Lodestar's own safety check: the target is below the configured altitude limit. */
    data class BelowHorizonLimit(val altitude: Double, val limit: Double) : MountError(
        "The target is at ${formatFixed(altitude, 1)}°, below your altitude limit of ${formatFixed(limit, 1)}°. " +
            "Lodestar won't slew there.",
    )

    /** The mount refused the target because it is below the mount's own horizon limit. */
    data object MountHorizonLimit : MountError("The target is below the mount's horizon limit.")

    /** The mount refused the target because it is above the mount's overhead limit. */
    data object MountOverheadLimit : MountError("The target is above the mount's overhead limit.")

    /** The mount refused the target because it is outside its movement limits (meridian, declination, axis). */
    data object OutsideMountLimits : MountError("The target is outside the mount's movement limits.")

    /** The mount needs a star alignment before it can slew. */
    data object NotAligned : MountError("The mount is not aligned. Do a star alignment on the mount first, then try again.")

    /** The mount is parked. */
    data object Parked : MountError("The mount is parked. Unpark it first.")

    /** The mount's controller is in standby. */
    data object Standby : MountError("The mount is in standby. Wake it up first.")

    /** Tracking is off and the operation needs it on. */
    data object NotTracking : MountError("Tracking is off. Turn tracking on first.")

    /** The mount or its protocol does not offer [feature]. */
    data class NotSupported(val feature: MountFeature) :
        MountError("This mount doesn't support ${feature.displayName}.")

    /** The mount reported a hardware fault. */
    data class HardwareFault(val detail: String) : MountError(
        if (detail.isEmpty()) "The mount reported a hardware fault." else "The mount reported a hardware fault: $detail.",
    )

    /** The mount rejected a value or command. */
    data class Rejected(val detail: String) : MountError(
        if (detail.isEmpty()) "The mount rejected the command." else "The mount rejected the command: $detail.",
    )

    /** The mount did not answer within the timeout. */
    data class Timeout(val operation: String) : MountError("The mount didn't answer in time ($operation).")

    /** A slew did not finish within [seconds] and was stopped. */
    data class SlewTimeout(val seconds: Double) :
        MountError("The slew did not finish within ${formatFixed(seconds, 0)} s, so it was stopped.")

    /** The mount stopped [remainingDegrees] short of the target without being told to. */
    data class SlewStopped(val remainingDegrees: Double) :
        MountError("The mount stopped ${formatFixed(remainingDegrees, 1)}° short of the target.")

    /** A sync would move the mount's idea of its position by [offsetDegrees], more than the allowed [limitDegrees]. */
    data class SyncOffsetTooLarge(val offsetDegrees: Double, val limitDegrees: Double) : MountError(
        "The mount points ${formatFixed(offsetDegrees, 1)}° away from that star, more than the allowed " +
            "${formatFixed(limitDegrees, 0)}°. Check that you centred the right star.",
    )

    /** Repeated failures ended the session. */
    data object ConnectionLost : MountError("The connection to the mount was lost.")

    /** The transport reported an I/O failure. */
    data class Transport(val detail: String) : MountError("Could not talk to the mount: $detail.")

    /** The mount's reply failed validation (wrong length, bad characters, out of range, wrong transaction). */
    data class MalformedReply(val detail: String) : MountError("The mount sent an unexpected reply ($detail).")

    /** The mount reported error [code] with [detail] that has no more specific case. */
    data class Remote(val code: Int, val detail: String) : MountError(
        if (detail.isEmpty()) "The mount reported error $code." else "The mount reported error $code: $detail.",
    )

    /** The target or another input is not a usable value. */
    data class InvalidInput(val detail: String) : MountError("That value can't be used: $detail.")

    /** The mount reports a coordinate system the engine cannot convert to (code from the protocol). */
    data class UnsupportedCoordinateSystem(val code: Int) :
        MountError("The mount uses a coordinate system Lodestar can't convert to (code $code).")
}

/** What the connected mount can do, found out by the connection handshake. */
data class MountCapabilities(
    /** The protocol in use. */
    val kind: MountProtocol,
    /** A display name: product name and firmware as the mount reports it; length-capped. */
    val name: String,
    /** The firmware version text, if the mount reports one. */
    val firmware: String?,
    /** Slewing to coordinates is available. */
    val canSlew: Boolean,
    /** Sync is available. */
    val canSync: Boolean,
    /** A slew can be stopped. */
    val canAbort: Boolean,
    /** Tracking can be switched on and off. */
    val canSetTracking: Boolean,
    /** Manual nudges are available. */
    val canNudge: Boolean,
    /** The site and clock can be set. */
    val canSetSiteAndTime: Boolean,
    /** A slew is refused (or silently ignored) by the mount until it is star-aligned. */
    val gotoNeedsAlignment: Boolean,
    /** Whether the mount reports being aligned; null if it cannot tell. */
    val isAligned: Boolean?,
)

/** A direction on the sky in which a nudge moves the mount (north = +declination or up, east = +right ascension). */
enum class MountNudgeDirection(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** Towards increasing declination (up on an alt-azimuth mount). */
    NORTH("north"),

    /** Towards decreasing declination (down). */
    SOUTH("south"),

    /** Towards increasing right ascension (right when facing south in the northern hemisphere). */
    EAST("east"),

    /** Towards decreasing right ascension. */
    WEST("west");

    companion object {
        /** The direction with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountNudgeDirection? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** How fast a nudge moves, as a multiple of the sidereal rate. */
enum class MountNudgeRate(
    /** The stable string used for storage. */
    val rawValue: String,
    /** The rate as a multiple of the sidereal rate (15.041 arcseconds per second). */
    val siderealMultiple: Double,
) {
    /** The mount's guide rate: for the finest corrections. */
    GUIDE("guide", 1.0),

    /** The centring rate: fine framing adjustments. */
    CENTER("center", 8.0),

    /** The find rate: moving across a field. */
    FIND("find", 32.0),

    /** A fast rate for larger moves. */
    SLEW("slew", 128.0);

    /** The rate in degrees per second. */
    val degreesPerSecond: Double get() = siderealMultiple * siderealDegreesPerSecond

    companion object {
        /** The sidereal rate in degrees per second (360.98564736629 degrees per mean solar day). */
        const val siderealDegreesPerSecond: Double = 360.98564736629 / 86_400.0

        /** The rate with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountNudgeRate? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** What the controller is doing. */
enum class MountPhase(
    /** The stable string used for storage. */
    val rawValue: String,
) {
    /** No session. */
    DISCONNECTED("disconnected"),

    /** Connected and not moving under the controller's command. */
    IDLE("idle"),

    /** A slew is in progress. */
    SLEWING("slewing"),

    /** A manual nudge is in progress. */
    NUDGING("nudging");

    companion object {
        /** The phase with the given raw value, or null if there is none. */
        fun fromRawValue(raw: String): MountPhase? = entries.firstOrNull { it.rawValue == raw }
    }
}

/** How the last slew ended. */
sealed interface MountSlewOutcome {
    /** The mount stopped within the tolerance of the target, [separation] degrees away. */
    data class Arrived(val separation: Double) : MountSlewOutcome

    /** The user aborted the slew. */
    data object Aborted : MountSlewOutcome

    /** The slew failed; [error] says why. */
    data class Failed(val error: MountError) : MountSlewOutcome
}

/** A snapshot of the controller for the UI. */
data class MountStatus(
    /** What the controller is doing. */
    val phase: MountPhase,
    /** Where the mount points, as a J2000 place; null until the first reading. */
    val position: EquatorialCoordinate?,
    /** The J2000 target of the current or last slew. */
    val target: EquatorialCoordinate?,
    /** Degrees between [position] and [target]; null when either is unknown. */
    val separation: Double?,
    /** Whether the mount is tracking; null if the protocol cannot tell. */
    val isTracking: Boolean?,
    /**
     * Whether the mount reports being aligned; null if it cannot tell. Refreshed by [MountController.poll] until it is
     * true, so a screen can ask the user to align the mount and notice when it is done.
     */
    val isAligned: Boolean?,
    /** The mount's own "slewing" flag at the last reading; null before the first reading. */
    val isMoving: Boolean?,
    /** Seconds since the current slew started; null when not slewing. */
    val slewElapsedSeconds: Double?,
    /** How the last finished slew ended; cleared when a new slew starts. */
    val outcome: MountSlewOutcome?,
    /** The last communication error, cleared by the next successful poll. */
    val lastError: MountError?,
    /** When the status was last refreshed. */
    val updated: Instant?,
) {
    companion object {
        /** The status of a controller that has no session. */
        val disconnected: MountStatus = MountStatus(
            MountPhase.DISCONNECTED, null, null, null, null, null, null, null, null, null, null,
        )
    }
}

/** The result of a successful sync. */
data class MountSyncResult(
    /** Degrees between where the mount believed it pointed and the synced position. */
    val offsetDegrees: Double,
)

/**
 * Limits and timings of the controller. Out-of-range or non-finite values are replaced by the nearest allowed value
 * (the default for NaN), so the controller never runs with an unbounded wait.
 */
@ConsistentCopyVisibility
data class MountSettings private constructor(
    /** Targets lower than this altitude (degrees, geometric, no refraction) are refused. */
    val altitudeLimit: Double,
    /** The target must also stay above the limit this many seconds from now (the slew takes time). */
    val horizonLookaheadSeconds: Double,
    /** A slew that has not finished after this many seconds is stopped. */
    val maximumSlewSeconds: Double,
    /** After a slew command the mount may report "not slewing" for this long before the slew counts as not started. */
    val slewStartGraceSeconds: Double,
    /** The mount counts as arrived when it stops within this many degrees of the target. */
    val arrivalToleranceDegrees: Double,
    /** The time between polls used by [MountController.awaitSettled]. */
    val pollIntervalSeconds: Double,
    /** This many failed polls in a row end the session. */
    val maximumConsecutiveFailures: Int,
    /** The longest nudge. */
    val maximumNudgeSeconds: Double,
    /** A sync that would move the mount by more than this many degrees is refused (180 allows everything). */
    val maximumSyncOffsetDegrees: Double,
) {
    companion object {
        /** The default settings. */
        val standard: MountSettings = invoke()

        /** Creates settings; values are clamped to their allowed ranges (see the documentation of each property). */
        operator fun invoke(
            altitudeLimit: Double = 0.0,
            horizonLookaheadSeconds: Double = 60.0,
            maximumSlewSeconds: Double = 300.0,
            slewStartGraceSeconds: Double = 5.0,
            arrivalToleranceDegrees: Double = 0.25,
            pollIntervalSeconds: Double = 1.0,
            maximumConsecutiveFailures: Int = 3,
            maximumNudgeSeconds: Double = 10.0,
            maximumSyncOffsetDegrees: Double = 180.0,
        ): MountSettings = MountSettings(
            altitudeLimit = sane(altitudeLimit, 0.0, -10.0, 60.0),
            horizonLookaheadSeconds = sane(horizonLookaheadSeconds, 60.0, 0.0, 1800.0),
            maximumSlewSeconds = sane(maximumSlewSeconds, 300.0, 10.0, 3600.0),
            slewStartGraceSeconds = sane(slewStartGraceSeconds, 5.0, 0.0, 60.0),
            arrivalToleranceDegrees = sane(arrivalToleranceDegrees, 0.25, 0.01, 5.0),
            pollIntervalSeconds = sane(pollIntervalSeconds, 1.0, 0.1, 30.0),
            maximumConsecutiveFailures = maximumConsecutiveFailures.coerceIn(1, 10),
            maximumNudgeSeconds = sane(maximumNudgeSeconds, 10.0, 0.1, 30.0),
            maximumSyncOffsetDegrees = sane(maximumSyncOffsetDegrees, 180.0, 0.1, 180.0),
        )

        private fun sane(value: Double, default: Double, lower: Double, upper: Double): Double =
            if (value.isNaN()) default else value.coerceIn(lower, upper)
    }
}

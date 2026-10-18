package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountSlewSummary
import net.kuecker.lodestar.services.MountSnapshot
import net.kuecker.lodestar.shared.AppFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/** The words of the mount screens. Pure functions of the state, so they can be tested without a screen. */
internal object MountText {
    /** What the mount is doing, for the line under its name. */
    fun phaseLine(snapshot: MountSnapshot): String {
        when (snapshot.state) {
            MountConnectionState.CONNECTING -> return "Connecting…"
            MountConnectionState.DISCONNECTED -> return if (snapshot.lostConnection) "Connection lost" else "Not connected"
            MountConnectionState.CONNECTED -> Unit
        }
        val status = snapshot.status
        return when (status.phase) {
            MountPhase.SLEWING -> "Slewing… ${elapsed(status.slewElapsedSeconds ?: 0.0)}"
            MountPhase.NUDGING -> "Moving…"
            else -> when (val outcome = status.outcome) {
                is MountSlewOutcome.Arrived -> "Arrived — ${AppFormat.separation(outcome.separation)} from target"
                MountSlewOutcome.Aborted -> "Stopped"
                is MountSlewOutcome.Failed -> outcome.error.userMessage
                null -> if (status.isTracking == false) "Ready · tracking is off" else "Ready"
            }
        }
    }

    /** A short phrase that changes only when the situation does, for screen readers to announce. */
    fun announcement(snapshot: MountSnapshot): String {
        if (snapshot.state == MountConnectionState.CONNECTING) return "Connecting to the mount"
        if (snapshot.state == MountConnectionState.DISCONNECTED) return if (snapshot.lostConnection) "Mount connection lost" else ""
        val status = snapshot.status
        return when (status.phase) {
            MountPhase.SLEWING -> "Mount slewing"
            MountPhase.NUDGING -> "Mount moving"
            else -> when (val outcome = status.outcome) {
                is MountSlewOutcome.Arrived -> "Mount arrived at the target"
                MountSlewOutcome.Aborted -> "Mount stopped"
                is MountSlewOutcome.Failed -> "Mount stopped. ${outcome.error.userMessage}"
                null -> "Mount connected"
            }
        }
    }

    /** "12h 34m 56s  +45° 10′ 20″" or the decimal form, as the user chose. */
    fun position(coordinate: EquatorialCoordinate, style: CoordinateStyle): String =
        "${AppFormat.rightAscension(coordinate, style)}  ${AppFormat.declination(coordinate, style)}"

    /** What the connected mount can do, as short phrases. */
    fun abilities(capabilities: MountCapabilities): List<String> = buildList {
        if (capabilities.canSlew) add("Go to")
        if (capabilities.canSync) add("Sync")
        if (capabilities.canAbort) add("Stop")
        if (capabilities.canNudge) add("Nudges")
        if (capabilities.canSetTracking) add("Tracking on/off")
        if (capabilities.canSetSiteAndTime) add("Location and time")
    }

    /** The slew in words: how far, and which way in azimuth and altitude. */
    fun slew(summary: MountSlewSummary): String {
        val across = "${AppFormat.separation(summary.distanceDegrees)} across the sky"
        val turn = when {
            abs(summary.azimuthChange) < SMALL_TURN -> null
            summary.azimuthChange > 0 -> "turns ${degrees(summary.azimuthChange)} clockwise (seen from above)"
            else -> "turns ${degrees(-summary.azimuthChange)} counter-clockwise (seen from above)"
        }
        val tilt = when {
            abs(summary.altitudeChange) < SMALL_TURN -> null
            summary.altitudeChange > 0 -> "tilts ${degrees(summary.altitudeChange)} up"
            else -> "tilts ${degrees(-summary.altitudeChange)} down"
        }
        val parts = listOfNotNull(turn, tilt)
        return if (parts.isEmpty()) "Moves $across." else "Moves $across: ${parts.joinToString(" and ")}."
    }

    fun direction(direction: MountNudgeDirection): String = when (direction) {
        MountNudgeDirection.NORTH -> "north"
        MountNudgeDirection.SOUTH -> "south"
        MountNudgeDirection.EAST -> "east"
        MountNudgeDirection.WEST -> "west"
    }

    fun rateName(rate: MountNudgeRate): String = when (rate) {
        MountNudgeRate.GUIDE -> "Guide"
        MountNudgeRate.CENTER -> "Center"
        MountNudgeRate.FIND -> "Find"
        MountNudgeRate.SLEW -> "Slew"
    }

    /** "Center: 8× sidereal, about 2′ per second". */
    fun rateDescription(rate: MountNudgeRate): String =
        "${rateName(rate)}: ${rate.siderealMultiple.roundToInt()}× sidereal, about ${AppFormat.separation(rate.degreesPerSecond)} per second"

    private fun elapsed(seconds: Double): String {
        val whole = seconds.coerceAtLeast(0.0).toInt()
        return if (whole < SECONDS_PER_MINUTE) "$whole s" else "${whole / SECONDS_PER_MINUTE} min ${whole % SECONDS_PER_MINUTE} s"
    }

    private fun degrees(value: Double): String = AppFormat.separation(value)

    private const val SMALL_TURN = 0.5
    private const val SECONDS_PER_MINUTE = 60
}

package net.kuecker.lodestar.features.mount

import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.EquatorialCoordinate
import net.kuecker.lodestar.kit.mount.MountCapabilities
import net.kuecker.lodestar.kit.mount.MountError
import net.kuecker.lodestar.kit.mount.MountNudgeDirection
import net.kuecker.lodestar.kit.mount.MountNudgeRate
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.model.CoordinateStyle
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountHints
import net.kuecker.lodestar.services.MountSkyWatcherInfo
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

    // Sky-Watcher Wi-Fi mounts

    /** "Aligned on 3 stars, 0.02° residual, ≈ 0.2° accuracy", or what to do first. */
    fun alignmentLine(info: MountSkyWatcherInfo): String {
        val count = info.stars.size
        if (count == 0) return "Not aligned: centre a star and tap Sync"
        val accuracy = info.predictedErrorDegrees?.let { ", ≈ ${accuracyText(it)} accuracy" }.orEmpty()
        val residual = if (count > 1) info.maximumResidualDegrees?.let { ", ${AngleFormatter.formatDegrees(it, 2)} residual" }.orEmpty() else ""
        return "Aligned on $count star${if (count == 1) "" else "s"}$residual$accuracy"
    }

    /** An angle with the digits that mean something: 0.05°, 0.2°, 1.0°, 12°. */
    fun accuracyText(degrees: Double): String = when {
        degrees < SMALL_ACCURACY -> AngleFormatter.formatDegrees(degrees, 2)
        degrees < LARGE_ACCURACY -> AngleFormatter.formatDegrees(degrees, 1)
        else -> AngleFormatter.formatDegrees(degrees, 0)
    }

    /** "Telescope east of the pier" (looking west) or "west of the pier" (looking east). */
    fun pierSideLine(side: SkyWatcherPierSide): String =
        if (side == SkyWatcherPierSide.EAST) "Telescope east of the pier" else "Telescope west of the pier"

    /** "Tracking", "Tracking is off", or null when the mount does not say. */
    fun trackingLine(snapshot: MountSnapshot): String? = when (snapshot.status.isTracking) {
        true -> "Tracking"
        false -> "Tracking is off"
        null -> null
    }

    /** Why tracking stopped by itself, with what to do; null while it has not. */
    fun trackingStopLine(snapshot: MountSnapshot): String? {
        val info = snapshot.skyWatcher ?: return null
        val reason = info.trackingStopReason ?: return null
        if (snapshot.status.isTracking == true) return null
        return when {
            reason is MountError.OutsideMountLimits && info.mode == SkyWatcherMountMode.EQUATORIAL ->
                "Tracking stopped at the meridian. Tap Go to to flip the mount"
            reason is MountError.OutsideMountLimits ->
                "Tracking stopped at a limit of the mount (cable wrap or near the zenith). Tap Go to to start again"
            reason is MountError.MountOverheadLimit ->
                "Tracking stopped near the zenith, where this mount cannot follow"
            else -> "Tracking stopped: ${reason.userMessage}"
        }
    }

    /** What stands in the way of Go to because the mount is not aligned; null when it is. */
    fun notAlignedBanner(snapshot: MountSnapshot): String? {
        val capabilities = snapshot.capabilities ?: return null
        if (!capabilities.gotoNeedsAlignment || snapshot.isAligned != false) return null
        return if (snapshot.skyWatcher != null) MountHints.SKYWATCHER_NOT_ALIGNED else HAND_CONTROLLER_NOT_ALIGNED
    }

    /** The start-position check after connecting. */
    fun startPositionLine(info: MountSkyWatcherInfo): String =
        if (info.startsAwayFromHome) {
            "The mount is not at its home position. Switch it off and on at the home position, or move it there " +
                "with the arrows and tap This is home."
        } else {
            "The mount started at its home position."
        }

    fun axisAngles(info: MountSkyWatcherInfo): String =
        "${AngleFormatter.formatSignedDegrees(info.startAxis1Degrees, 1)} / ${AngleFormatter.formatSignedDegrees(info.startAxis2Degrees, 1)}"

    private fun elapsed(seconds: Double): String {
        val whole = seconds.coerceAtLeast(0.0).toInt()
        return if (whole < SECONDS_PER_MINUTE) "$whole s" else "${whole / SECONDS_PER_MINUTE} min ${whole % SECONDS_PER_MINUTE} s"
    }

    private fun degrees(value: Double): String = AppFormat.separation(value)

    /** Said when a hand-controller mount (LX200, NexStar, Alpaca) reports that it is not aligned. */
    const val HAND_CONTROLLER_NOT_ALIGNED = "The mount is not aligned. Align it on the hand controller, then continue."

    private const val SMALL_ACCURACY = 0.095
    private const val LARGE_ACCURACY = 9.5
    private const val SMALL_TURN = 0.5
    private const val SECONDS_PER_MINUTE = 60
}

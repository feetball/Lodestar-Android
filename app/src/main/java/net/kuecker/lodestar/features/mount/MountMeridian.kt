package net.kuecker.lodestar.features.mount

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import net.kuecker.lodestar.features.objects.ObjectDetailMeridian
import net.kuecker.lodestar.kit.foundation.Observer
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.SkyWatcherMountMode
import net.kuecker.lodestar.kit.mount.SkyWatcherPierSide
import net.kuecker.lodestar.kit.planning.MeridianClock
import net.kuecker.lodestar.kit.planning.MeridianFlipWindow
import net.kuecker.lodestar.services.MountSnapshot
import net.kuecker.lodestar.shared.ChipTone
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.StatusChip
import java.time.Instant
import kotlin.math.floor

/**
 * The meridian countdown of the mount card, for a German equatorial mount that has to flip when it has tracked past
 * the meridian: "Meridian in 25 min", a warning under 15 minutes, "Past the meridian: flip" after it. Display only:
 * nothing is sent to the mount.
 */
internal object MountMeridian {
    /** The countdown turns into a warning below this many seconds. */
    const val WARNING_SECONDS = 15 * 60.0

    /** Where the mount is on its way to the meridian limit. */
    enum class Phase {
        /** More than [WARNING_SECONDS] to go. */
        AHEAD,

        /** Less than [WARNING_SECONDS] to go. */
        SOON,

        /** The target has reached the limit: the mount has to flip. */
        PAST,
    }

    /** What the chip says and how urgent it is. */
    data class Chip(val text: String, val phase: Phase)

    /**
     * The chip for a mount that is tracking the sky, or `null` when there is nothing to say: no position or tracking
     * yet, a slew or nudge in progress, a Sky-Watcher mount set up as alt-azimuth, or one already on its normal pier
     * side (looking west, where it can track on past the meridian: it has flipped).
     *
     * The hour angle is the one of the place the mount points at; the limit is the driver's meridian limit when it has
     * one (a Sky-Watcher mount: its overlap past the meridian), else 0°.
     */
    fun chip(snapshot: MountSnapshot, observer: Observer, now: Instant): Chip? {
        val status = snapshot.status
        val position = status.position ?: return null
        if (!snapshot.isConnected || status.isTracking != true || status.phase != MountPhase.IDLE) return null
        val info = snapshot.skyWatcher
        if (info?.mode == SkyWatcherMountMode.ALT_AZIMUTH || info?.pierSide == SkyWatcherPierSide.EAST) return null
        val apparent = SkyFrame(now, observer).apparentPlace(position)
        val hourAngle = MeridianClock.hourAngle(apparent, now, observer)
        val window = MeridianClock.flipWindow(hourAngle, info?.meridianLimitDegrees ?: 0.0) ?: return null
        return chip(window)
    }

    /** The chip for a countdown. */
    fun chip(window: MeridianFlipWindow): Chip {
        val seconds = window.secondsUntilLimit
        if (window.isPastLimit) return Chip("Past the meridian: flip", Phase.PAST)
        val minutes = floor(seconds / SECONDS_PER_MINUTE).toLong()
        val text = if (minutes == 0L) "Meridian in under 1 min" else "Meridian in ${ObjectDetailMeridian.durationText(minutes)}"
        return Chip(text, if (seconds < WARNING_SECONDS) Phase.SOON else Phase.AHEAD)
    }

    private const val SECONDS_PER_MINUTE = 60.0
}

/** The chip of [MountMeridian] for the connected mount; nothing while it has nothing to say. */
@Composable
internal fun MountMeridianChip(snapshot: MountSnapshot, modifier: Modifier = Modifier) {
    val app = LocalAppModel.current
    val observer = app.observerContext?.observer ?: return
    val status = snapshot.status
    val chip = remember(status.updated, status.position, status.isTracking, status.phase, snapshot.skyWatcher, observer) {
        MountMeridian.chip(snapshot, observer, Snapshot.withoutReadObservation { app.clock.now })
    } ?: return
    val isPast = chip.phase == MountMeridian.Phase.PAST
    StatusChip(
        icon = if (isPast) Icons.Filled.Warning else Icons.Outlined.Schedule,
        text = chip.text,
        // A mount that has to flip is said once, when the chip turns into that: the text no longer changes after it.
        modifier = if (isPast) modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite } else modifier,
        tone = when (chip.phase) {
            MountMeridian.Phase.AHEAD -> ChipTone.NEUTRAL
            MountMeridian.Phase.SOON -> ChipTone.WARNING
            MountMeridian.Phase.PAST -> ChipTone.CRITICAL
        },
    )
}

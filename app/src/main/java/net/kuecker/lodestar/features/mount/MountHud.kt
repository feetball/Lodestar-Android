package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.ControlCamera
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material.icons.outlined.OpenWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.kuecker.lodestar.features.aim.AimControlButton
import net.kuecker.lodestar.features.aim.AimFittingText
import net.kuecker.lodestar.features.aim.aimPanel
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.mount.MountSlewOutcome
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountNotice
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountSnapshot
import net.kuecker.lodestar.services.MountTargeting
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import java.time.Instant

/** Milliseconds a notice stays in the mount card. */
private const val NOTICE_SHOWN_MS = 7_000L

/** The Mount button of the Aim controls bar: opens the connection sheet; filled while a mount is connected. */
@Composable
internal fun MountAimButton(minHeight: Dp, modifier: Modifier = Modifier) {
    val service = rememberMountService()
    val sheets = rememberMountAimState()
    val snapshot = service.snapshot
    val value = when {
        snapshot.state == MountConnectionState.CONNECTED -> "Connected"
        snapshot.state == MountConnectionState.CONNECTING -> "Connecting"
        snapshot.lostConnection -> "Connection lost"
        else -> "Not connected"
    }
    AimControlButton(
        onClick = { sheets.open(MountSheetKind.CONTROL) },
        modifier = modifier.semantics {
            contentDescription = "Mount control"
            stateDescription = value
        },
        isOn = snapshot.state == MountConnectionState.CONNECTED,
        minHeight = minHeight,
        onClickLabel = "Opens the connection to a GoTo mount.",
    ) {
        Icon(Icons.Outlined.ControlCamera, contentDescription = null, modifier = Modifier.size(22.dp))
        AimFittingText(
            "Mount",
            MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            LocalContentColor.current,
        )
    }
}

/**
 * The mount card over the sky while a mount is connected (or connecting, or lost): its name and link state, where it
 * points, the distance to the target and the slew's progress, and the buttons Go to, Sync, Nudge and Stop. Stop is
 * always there and always enabled.
 */
@Composable
internal fun MountHudCard(target: CelestialObject?, modifier: Modifier = Modifier) {
    val service = rememberMountService()
    val sheets = rememberMountAimState()
    val snapshot = service.snapshot
    when {
        snapshot.state == MountConnectionState.CONNECTING -> MountStatusCard(modifier) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MountSpinner()
                Text(
                    "Connecting to ${snapshot.connection?.host.orEmpty()}…",
                    color = LocalPalette.current.primaryText,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                LodestarButton("Cancel", onClick = service::cancelConnect, kind = LodestarButtonKind.SECONDARY)
            }
        }
        snapshot.state == MountConnectionState.CONNECTED -> MountConnectedCard(service, snapshot, target, sheets, modifier)
        snapshot.lostConnection -> MountStatusCard(modifier) {
            val palette = LocalPalette.current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = palette.critical, modifier = Modifier.size(20.dp))
                Text(
                    "Connection lost",
                    color = palette.critical,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "If the mount is still moving, stop it with its own controls.",
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LodestarButton("Reconnect", onClick = service::reconnect)
                LodestarButton("Dismiss", onClick = service::dismissProblem, kind = LodestarButtonKind.SECONDARY)
            }
        }
        else -> Unit
    }
}

@Composable
private fun MountStatusCard(modifier: Modifier, content: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().aimPanel(palette), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun MountConnectedCard(
    service: MountService,
    snapshot: MountSnapshot,
    target: CelestialObject?,
    sheets: MountAimState,
    modifier: Modifier,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val status = snapshot.status
    val capabilities = snapshot.capabilities
    val observer = app.observerContext?.observer
    val separation = remember(status.updated, status.position, target?.id, observer) {
        val position = status.position
        if (target == null || observer == null || position == null) {
            null
        } else {
            position.angularSeparation(MountTargeting.target(target, observer, Instant.now()).j2000)
        }
    }
    val isIdle = status.phase == MountPhase.IDLE
    val needsAlignment = capabilities?.gotoNeedsAlignment == true && status.isAligned == false
    val canGoto = target != null && isIdle && capabilities?.canSlew == true && !needsAlignment
    val canSync = target != null && isIdle && capabilities?.canSync == true
    val canNudge = capabilities?.canNudge == true && status.phase != MountPhase.SLEWING

    MountStatusCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MountStatusDot(if (snapshot.linkProblem != null) palette.warning else palette.positive)
            Text(
                (capabilities?.name ?: "Mount") + if (snapshot.isDemo) " (simulated)" else "",
                color = palette.primaryText,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (separation != null) {
                Text(
                    AppFormat.separation(separation) + " to target",
                    color = palette.guidance,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
                    maxLines = 1,
                )
            }
        }
        Text(
            MountText.phaseLine(snapshot),
            color = phaseColor(snapshot),
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).monospacedDigits(),
        )
        val start = snapshot.slewStartSeparation
        val remaining = status.separation
        if (status.phase == MountPhase.SLEWING && start != null && start > 0 && remaining != null) {
            LinearProgressIndicator(
                progress = { (1 - remaining / start).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = palette.guidance,
                trackColor = palette.stroke,
            )
        }
        status.position?.let {
            Text(
                "Points at ${MountText.position(it, app.settings.coordinateStyle)}",
                color = palette.secondaryText,
                style = MaterialTheme.typography.labelMedium.monospacedDigits(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (needsAlignment) {
            MountNote("The mount is not aligned. Align it on the hand controller, then continue.", palette.warning)
        }
        snapshot.linkProblem?.let { MountNote(it, palette.warning) }
        snapshot.notice?.let { MountNoticeLine(it, service) }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            MountHudButton("Go to", Icons.Outlined.NearMe, canGoto, Modifier.weight(1f), "Go to ${target?.name ?: "the target"}") {
                sheets.open(MountSheetKind.GOTO)
            }
            MountHudButton("Sync", Icons.Outlined.MyLocation, canSync, Modifier.weight(1f), "Sync the mount to ${target?.name ?: "the target"}") {
                sheets.open(MountSheetKind.SYNC)
            }
            MountHudButton("Nudge", Icons.Outlined.OpenWith, canNudge, Modifier.weight(1f), "Nudge the mount") {
                sheets.open(MountSheetKind.NUDGE)
            }
            MountStopButton(onStop = service::stop, modifier = Modifier.weight(1.2f))
        }
    }
    MountAnnouncer(snapshot)
}

@Composable
private fun phaseColor(snapshot: MountSnapshot): Color {
    val palette = LocalPalette.current
    val status = snapshot.status
    return when {
        status.phase != MountPhase.IDLE -> palette.guidance
        status.outcome is MountSlewOutcome.Arrived -> palette.positive
        status.outcome is MountSlewOutcome.Failed -> palette.warning
        else -> palette.secondaryText
    }
}

/** A line of text with a warning mark, inside the card. */
@Composable
internal fun MountNote(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** A message about what just happened; goes away by itself after a few seconds. */
@Composable
private fun MountNoticeLine(notice: MountNotice, service: MountService) {
    val palette = LocalPalette.current
    LaunchedEffect(notice.id) {
        delay(NOTICE_SHOWN_MS)
        service.dismissNotice(notice.id)
    }
    Text(
        notice.text,
        color = if (notice.isError) palette.warning else palette.positive,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * A point-sized element that tells screen readers when the mount's situation changes (slewing, arrived, stopped, lost).
 * Its text changes only with the situation, not with the seconds ticking by, so it does not chatter.
 */
@Composable
private fun MountAnnouncer(snapshot: MountSnapshot) {
    val announcement = MountText.announcement(snapshot)
    Box(
        Modifier.size(1.dp).semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = announcement
        },
    )
}

/** A compact button: a symbol over a label, drawn from the palette. */
@Composable
private fun MountHudButton(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    modifier: Modifier,
    description: String,
    onClick: () -> Unit,
) {
    AimControlButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        enabled = enabled,
        minHeight = HUD_BUTTON_HEIGHT,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        AimFittingText(title, MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold), LocalContentColor.current)
    }
}

private val HUD_BUTTON_HEIGHT = 50.dp

/**
 * The Stop button: red on dark, large, never disabled. Pressing it sends the stop ahead of everything else in the
 * queue to the mount.
 */
@Composable
internal fun MountStopButton(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = HUD_BUTTON_HEIGHT)
            .clip(shape)
            .background(palette.critical, shape)
            .border(1.dp, palette.primaryText.copy(alpha = 0.6f), shape)
            .clickable(onClickLabel = "Stops every movement of the mount", role = Role.Button, onClick = onStop)
            .semantics { contentDescription = "Stop mount" },
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Stop, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
        Text("Stop", color = Color.Black, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/** A small filled circle that says whether the link is good. */
@Composable
internal fun MountStatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape).clearAndSetSemantics { })
}

/** A spinner in the palette's accent colour. */
@Composable
internal fun MountSpinner(size: Dp = 22.dp) {
    val palette = LocalPalette.current
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        color = palette.accent,
        trackColor = palette.stroke,
        strokeWidth = 2.5.dp,
    )
}

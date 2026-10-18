package net.kuecker.lodestar.features.mount

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.NearMe
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.aim.aimMessage
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.horizontal
import net.kuecker.lodestar.kit.foundation.SkyFrame
import net.kuecker.lodestar.kit.mount.MountPhase
import net.kuecker.lodestar.kit.pointing.AlignmentError
import net.kuecker.lodestar.kit.pointing.AlignmentPoint
import net.kuecker.lodestar.services.MountConnectionState
import net.kuecker.lodestar.services.MountResult
import net.kuecker.lodestar.services.MountService
import net.kuecker.lodestar.services.MountTarget
import net.kuecker.lodestar.services.MountTargeting
import net.kuecker.lodestar.shared.AppFormat
import net.kuecker.lodestar.shared.CardTitle
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.InfoRow
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.LocalSheetDismiss
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.LodestarButtonKind
import net.kuecker.lodestar.shared.LodestarSheet
import net.kuecker.lodestar.shared.SettingsCaption
import net.kuecker.lodestar.shared.SettingsSection
import net.kuecker.lodestar.shared.SettingsToggleRow
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.card

/**
 * Most of the screen the nudge sheet takes. It is only as tall as its pad needs, so that the sky and the target stay in
 * view while centring; on a small screen it grows to this much before it scrolls.
 */
private const val NUDGE_SHEET_MAX_HEIGHT = 0.75f

/** Share of the screen the connection sheet fills. */
private const val CONTROL_SHEET_HEIGHT = 0.9f

/** Milliseconds between the refreshes of the target's place in the confirmation sheet. */
private const val REFRESH_MS = 2_000L

/**
 * The sheets of the mount over the Aim screen. Put one in the Aim screen next to its other sheets; it shows whichever
 * the mount card or the Mount button opened. [target] is the object the Aim screen guides to.
 */
@Composable
fun MountAimSheets(target: CelestialObject?) {
    val sheets = rememberMountAimState()
    MountScreenshotEffect(sheets, target)
    // A sheet does not wait for the Aim screen to come back: leaving the screen closes it.
    DisposableEffect(sheets) { onDispose { sheets.close() } }
    when (sheets.sheet) {
        MountSheetKind.CONTROL -> LodestarSheet(onDismiss = sheets::close) {
            MountSheetFrame("Mount", CONTROL_SHEET_HEIGHT, LocalSheetDismiss.current) { MountControlContent() }
        }
        MountSheetKind.GOTO -> LodestarSheet(onDismiss = sheets::close) {
            MountActionContent(MountAction.GOTO, target, LocalSheetDismiss.current)
        }
        MountSheetKind.SYNC -> LodestarSheet(onDismiss = sheets::close) {
            MountActionContent(MountAction.SYNC, target, LocalSheetDismiss.current)
        }
        MountSheetKind.NUDGE -> LodestarSheet(onDismiss = sheets::close, scrimColor = Color.Black.copy(alpha = 0.15f)) {
            MountSheetFrame("Nudge mount", NUDGE_SHEET_MAX_HEIGHT, LocalSheetDismiss.current, fitsContent = true) { MountNudgeContent() }
        }
        null -> Unit
    }
}

/**
 * A sheet body: a title row with Done, and a scrolling [content] below it. The sheet fills [heightShare] of the screen,
 * or, with [fitsContent], is as tall as its content up to that share.
 */
@Composable
private fun MountSheetFrame(
    title: String,
    heightShare: Float,
    onDone: () -> Unit,
    fitsContent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    val height = if (fitsContent) {
        Modifier.heightIn(max = LocalConfiguration.current.screenHeightDp.dp * heightShare)
    } else {
        Modifier.fillMaxHeight(heightShare)
    }
    Column(Modifier.fillMaxWidth().then(height)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            LodestarButton("Done", onClick = onDone, kind = LodestarButtonKind.SECONDARY)
        }
        Column(Modifier.weight(1f, fill = !fitsContent).verticalScroll(rememberScrollState())) {
            content()
        }
    }
}

// Nudge

@Composable
private fun MountNudgeContent() {
    val service = rememberMountService()
    val snapshot = service.snapshot
    val capabilities = snapshot.capabilities
    if (snapshot.state != MountConnectionState.CONNECTED || capabilities == null) {
        EmptyStateView(
            title = "Mount not connected",
            icon = Icons.Outlined.NearMe,
            message = "Connect a mount to nudge it.",
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    val rate = service.preferences.settings.nudgeRate
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(MountText.phaseLine(snapshot), color = LocalPalette.current.guidance, style = MaterialTheme.typography.titleSmall)
        MountRatePicker(rate = rate, onSelect = { selected -> service.preferences.update { it.copy(nudgeRate = selected) } })
        MountNudgePad(service, rate, isEnabled = capabilities.canNudge && snapshot.status.phase != MountPhase.SLEWING)
        SettingsCaption(
            "Hold an arrow to keep moving; the mount stops when you let go. N and S move along declination, E and W " +
                "along right ascension (north up, east left, as on a star chart).",
        )
        snapshot.notice?.takeIf { it.isError }?.let { MountMessage(it.text, isError = true) }
    }
}

// Go to and Sync

internal enum class MountAction { GOTO, SYNC }

@Composable
private fun MountActionContent(action: MountAction, target: CelestialObject?, onDone: () -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val service = rememberMountService()
    val scope = rememberCoroutineScope()
    val snapshot = service.snapshot
    val observer = app.observerContext?.observer
    var now by remember { mutableStateOf(Snapshot.withoutReadObservation { app.clock.now }) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(REFRESH_MS)
            now = app.clock.now
        }
    }
    val resolved = remember(target?.id, observer, now) {
        if (target == null || observer == null) null else MountTargeting.target(target, observer, now)
    }
    val title = if (action == MountAction.GOTO) "Go to ${target?.name.orEmpty()}" else "Sync mount to ${target?.name.orEmpty()}"
    var isWorking by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var alignsPhone by remember { mutableStateOf(true) }

    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                title,
                color = palette.primaryText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            LodestarButton("Cancel", onClick = onDone, kind = LodestarButtonKind.SECONDARY)
        }
        // What does not fit scrolls: the confirmation has more than a small phone can show.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (resolved == null || target == null || snapshot.state != MountConnectionState.CONNECTED) {
                Text(
                    if (snapshot.state != MountConnectionState.CONNECTED) "The mount is not connected." else "There is no target or observing location.",
                    color = palette.secondaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
                return@Column
            }
            val limit = service.preferences.settings.altitudeLimit
            val problem = if (action == MountAction.GOTO && observer != null) {
                MountTargeting.altitudeProblem(resolved, observer, now, limit)
            } else {
                null
            }
            val canConfirm = !isWorking && problem == null && !resolved.isSun

            // The sections bring a margin of their own; everything else is given the same one.
            val inset = Modifier.padding(horizontal = 16.dp)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TargetCard(target, resolved, snapshot.status.position?.let { MountText.position(it, app.settings.coordinateStyle) }, inset)
                if (action == MountAction.GOTO) {
                    GotoDetails(resolved, service, limit, inset)
                } else {
                    SyncDetails(resolved, snapshot.status.position?.angularSeparation(resolved.j2000), inset)
                }
                problem?.let { Box(inset) { MountMessage(it.userMessage, isError = true) } }
                if (resolved.isSun) Box(inset) { MountMessage(MountTargeting.SUN_REFUSAL, isError = true) }
                if (action == MountAction.SYNC && !resolved.isSun) {
                    SettingsSection(title = "Phone alignment") {
                        SettingsToggleRow(
                            title = "Also align the phone on this target",
                            checked = alignsPhone,
                            onCheckedChange = { alignsPhone = it },
                            caption = "Uses the same centred frame to correct the phone's compass and bracket, as Align does.",
                        )
                    }
                }
                failure?.let { Box(inset) { MountMessage(it, isError = true) } }
                SettingsCaption(
                    if (action == MountAction.GOTO) {
                        "Keep people, hands and cables clear of the mount before it moves. Stop is on the Aim screen the whole time."
                    } else {
                        "Sync only when the target is in the middle of your camera's frame. Syncing on the wrong star makes later " +
                            "slews miss."
                    },
                    inset,
                )
                LodestarButton(
                    if (action == MountAction.GOTO) "Start slew" else "Sync mount",
                    onClick = {
                        isWorking = true
                        failure = null
                        scope.launch {
                            val result = if (action == MountAction.GOTO) service.goto(resolved) else service.sync(resolved)
                            isWorking = false
                            when (result) {
                                is MountResult.Failure -> failure = result.message
                                is MountResult.Success -> {
                                    if (action == MountAction.SYNC && alignsPhone) alignPhone(app, service, target)
                                    onDone()
                                }
                            }
                        }
                    },
                    modifier = inset.fillMaxWidth(),
                    enabled = canConfirm,
                    icon = if (action == MountAction.GOTO) Icons.Outlined.NearMe else Icons.Outlined.MyLocation,
                )
            }
        }
    }
}

@Composable
private fun TargetCard(target: CelestialObject, resolved: MountTarget, mountPosition: String?, modifier: Modifier) {
    val palette = LocalPalette.current
    val app = LocalAppModel.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("Target")
        Text(
            target.name,
            color = palette.primaryText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        InfoRow("Altitude", AppFormat.altitude(resolved.horizontal.altitude))
        InfoRow("Azimuth", AppFormat.azimuth(resolved.horizontal.azimuth))
        InfoRow("Position", MountText.position(resolved.j2000, app.settings.coordinateStyle))
        mountPosition?.let { InfoRow("Mount points at", it) }
    }
}

@Composable
private fun GotoDetails(resolved: MountTarget, service: MountService, limit: Double, modifier: Modifier) {
    val palette = LocalPalette.current
    val from = service.marker?.horizontal
    val summary = MountTargeting.slew(from, resolved.horizontal)
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("The slew")
        if (from != null) InfoRow("From", "${AppFormat.azimuth(from.azimuth)} · ${AppFormat.altitude(from.altitude)}")
        InfoRow("To", "${AppFormat.azimuth(resolved.horizontal.azimuth)} · ${AppFormat.altitude(resolved.horizontal.altitude)}")
        InfoRow("Altitude limit", AppFormat.altitude(limit))
        Text(
            summary?.let { MountText.slew(it) } ?: "Lodestar does not know yet where the mount points.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SyncDetails(resolved: MountTarget, mountOffset: Double?, modifier: Modifier) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().card(palette), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitle("Sync")
        Text(
            "Tells the mount that it points at ${resolved.name} right now.",
            color = palette.secondaryText,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (mountOffset != null) {
            InfoRow("Mount believes it is", "${AppFormat.separation(mountOffset)} away")
        }
    }
}

/** Adds the phone's alignment point for [target], the way the Align sheet does, and reports how it went. */
private fun alignPhone(app: AppModel, service: MountService, target: CelestialObject) {
    val sample = app.motion.latestSample()
    val observer = app.observerContext?.observer
    if (sample == null || observer == null) {
        service.postNotice("The mount is synced. The phone could not be aligned: its sensors are not delivering data.", true)
        return
    }
    val date = app.clock.now
    val apparent = SkyFrame(date, observer).horizontal(target, refraction = true)
    val point = AlignmentPoint(sample.rawAttitude, apparent, date, target.name)
    try {
        app.alignment = app.alignment.adding(point, app.mountProfile)
        service.postNotice("The mount is synced and the phone is aligned on ${target.name}.", false)
    } catch (error: AlignmentError) {
        service.postNotice("The mount is synced. The phone was not aligned. ${error.aimMessage(target.name)}", true)
    }
}

package net.kuecker.lodestar.features.skychart

import net.kuecker.lodestar.shared.AppFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.North
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.features.smallbodies.SmallBodyAccessibleMarkers
import net.kuecker.lodestar.features.smallbodies.SmallBodyChartMarkers
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.formatting.AngleFormatter
import net.kuecker.lodestar.kit.foundation.roundedHalfAwayFromZero
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.SkylineSummary
import net.kuecker.lodestar.shared.StatusChip
import net.kuecker.lodestar.theme.LocalPalette
import java.text.NumberFormat
import java.time.ZoneId

/** How often the live layers (the camera, and the chart when it turns with the camera) take a new sensor sample. */
private const val LIVE_INTERVAL_NANOS = 33_000_000L

/** The zoom factor from which the zoom chip is shown. */
private const val ZOOM_CHIP_THRESHOLD = 1.05f

/**
 * The chart area of the Sky tab: the planisphere with the live camera layer on top, the pinch, drag and tap gestures,
 * the status chips and the callout of the selected object.
 *
 * While the motion sensors run, a frame loop polls the newest sample about 30 times a second and the camera layer is
 * redrawn from it; with [rotatesToHeading] the whole chart is redrawn with the camera heading, otherwise only the small
 * camera layer is. The loop lives as long as this composable, so it pauses when the tab is not on screen.
 *
 * @param onDetails Opens the detail screen of an object.
 */
@Composable
internal fun SkyChartSurface(
    model: SkyChartModel,
    content: SkyChartContent,
    layers: SkyChartLayerOptions,
    rotatesToHeading: Boolean,
    mosaic: MosaicSession,
    timeZone: ZoneId,
    onDetails: (CelestialObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val live = remember { SkyChartLiveState() }
    val followsHeading by rememberUpdatedState(rotatesToHeading)
    val isMotionRunning = app.motion.status == MotionStatus.Running

    LaunchedEffect(live, isMotionRunning) {
        if (!isMotionRunning) {
            live.clear()
            return@LaunchedEffect
        }
        var last = Long.MIN_VALUE
        while (true) {
            withFrameNanos { now ->
                if (last == Long.MIN_VALUE || now - last >= LIVE_INTERVAL_NANOS) {
                    last = now
                    live.refresh(app)
                }
            }
        }
    }

    val mosaicStatus = mosaic.takeIf { content.scene.mosaic?.plan === it.plan }?.spokenStatus
    val summary = remember(content, timeZone, mosaicStatus) { accessibilitySummary(content.scene, timeZone, mosaicStatus) }
    val visibleBodies = remember(content) { content.scene.bodies.filter { it.isAboveHorizon } }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .pointerInput(model) {
                    detectTapGestures { tap ->
                        val layout = PlanisphereLayout(size.width.toFloat(), size.height.toFloat(), density)
                        val rotation = if (followsHeading) live.rotation else 0.0
                        model.handleTap(tap.x, tap.y, model.viewport(layout, rotation))
                    }
                }
                .pointerInput(model) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val layout = PlanisphereLayout(size.width.toFloat(), size.height.toFloat(), density)
                        if (zoom != 1f) model.zoomBy(zoom, centroid, layout)
                        if (pan != Offset.Zero) model.panBy(pan, layout)
                    }
                }
                .semantics { contentDescription = summary },
        ) {
            PlanisphereCanvas(content, model, live, palette, layers, rotatesToHeading, mosaic)
            if (layers.cameraView) PlanisphereLiveCanvas(model, live, palette, rotatesToHeading)
            StatusChips(model, live, layers.cameraView, rotatesToHeading, mosaic)
            AccessibleBodies(visibleBodies, model::select)
            SmallBodyAccessibleMarkers(content.scene.smallBodies, model::select)
        }

        AnimatedContent(
            targetState = model.selection,
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
            transitionSpec = {
                val enter = slideInVertically(tween(CALLOUT_MILLIS)) { it } + fadeIn(tween(CALLOUT_MILLIS))
                val exit = slideOutVertically(tween(CALLOUT_MILLIS)) { it } + fadeOut(tween(CALLOUT_MILLIS))
                when {
                    initialState == null -> enter togetherWith ExitTransition.None
                    targetState == null -> EnterTransition.None togetherWith exit
                    else -> EnterTransition.None togetherWith ExitTransition.None
                }
            },
            contentKey = { it != null },
            label = "SkyChartCallout",
        ) { selection ->
            if (selection != null) {
                SkyChartCallout(
                    selection = selection,
                    isLive = app.clock.isLive,
                    onAim = { app.aim(selection.obj) },
                    onDetails = { onDetails(selection.obj) },
                    onDismiss = model::clearSelection,
                )
            }
        }
    }
}

private const val CALLOUT_MILLIS = 200

/**
 * The chips along the top edge: whether north or the camera heading is up, the zoom, and where the camera points, and
 * below them the progress of a running mosaic. They are not interactive. Only this composable follows the zoom, so a pinch recomposes nothing else.
 */
@Composable
private fun StatusChips(
    model: SkyChartModel,
    live: SkyChartLiveState,
    showsCamera: Boolean,
    rotatesToHeading: Boolean,
    mosaic: MosaicSession,
) {
    val zoomText by remember(model) {
        derivedStateOf { if (model.zoom > ZOOM_CHIP_THRESHOLD) zoomText(model.zoom) else null }
    }
    val readout = live.readout
    Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            StatusChip(Icons.Outlined.North, if (rotatesToHeading && readout != null) "Heading up" else "North up")
            zoomText?.let { StatusChip(Icons.Outlined.Search, it) }
            Spacer(Modifier.weight(1f))
            if (showsCamera && readout != null) SkyChartCameraReadout(readout)
        }
        // A second line, so a long chip cannot push the others off a narrow screen.
        if (mosaic.isActive) StatusChip(Icons.Outlined.GridView, "Mosaic · ${mosaic.progressText}")
    }
}

/** The zoom factor, such as "2.5×". */
private fun zoomText(zoom: Float): String {
    val format = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    return "${format.format(zoom.toDouble())}×"
}

/**
 * The Sun, Moon and planets above the horizon as synthetic elements that accessibility services can activate to select
 * them, standing in for what the canvas draws.
 */
@Composable
private fun AccessibleBodies(bodies: List<SkyChartBodyMarker>, onSelect: (CelestialObject) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        for (body in bodies) {
            Box(
                Modifier.size(1.dp).semantics(mergeDescendants = true) {
                    contentDescription = description(body)
                    role = Role.Button
                    onClick(label = "Shows the object's card") {
                        onSelect(body.obj)
                        true
                    }
                },
            )
        }
    }
}

private fun accessibilitySummary(scene: SkyChartScene, timeZone: ZoneId, mosaicStatus: String?): String {
    val time = AppFormat.dateTime(scene.date, timeZone)
    val parts = arrayListOf("Sky chart for $time, looking up with north at the top.")
    val visible = scene.bodies.filter { it.isAboveHorizon }
    if (visible.isEmpty()) parts.add("The Sun, Moon and planets are all below the horizon.")
    visible.mapTo(parts, ::description)
    SmallBodyChartMarkers.spokenSummary(scene.smallBodies)?.let(parts::add)
    scene.skyline?.let {
        parts.add("${SkylineSummary.describe(it.profile)} It is drawn as a darker band around the edge of the chart.")
    }
    if (mosaicStatus != null) parts.add("$mosaicStatus The panels are outlined on the chart, the current one highlighted.")
    return parts.joinToString(" ")
}

private fun description(body: SkyChartBodyMarker): String {
    val direction = AngleFormatter.compassPoint(body.horizontal.azimuth).fullName.lowercase()
    val hidden = if (body.isBehindSkyline) ", behind the recorded skyline" else ""
    return "${body.name}, altitude ${body.horizontal.altitude.roundedHalfAwayFromZero().toInt()} degrees, $direction$hidden."
}

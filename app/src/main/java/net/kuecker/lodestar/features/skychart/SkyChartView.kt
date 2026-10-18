package net.kuecker.lodestar.features.skychart

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.kuecker.lodestar.app.AppModel
import net.kuecker.lodestar.features.mosaic.MosaicSession
import net.kuecker.lodestar.features.objects.ObjectDetailScreen
import net.kuecker.lodestar.kit.catalog.CelestialObject
import net.kuecker.lodestar.kit.catalog.Prominence
import net.kuecker.lodestar.services.CatalogService
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.NavStack
import net.kuecker.lodestar.shared.NavStackHost
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.SkySnapshotOptions
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground
import java.time.ZoneId

/**
 * The Sky tab: an all-sky chart of the observer's sky at any moment within a year of now.
 *
 * The chart is a planisphere: the zenith is at the centre, north at the top and east to the left, as when lying down
 * and looking up. It shows the stars, constellations, Milky Way, Sun, Moon, planets and deep-sky objects, the aiming
 * target, and where the camera on the tripod points. Pinch and drag zoom and pan; a tap selects an object and opens its
 * card, from which "Details" pushes the object's detail screen.
 */
@Composable
fun SkyChartScreen() {
    val stack = rememberRetained("sky.nav") { NavStack<SkyRoute>(SkyRoute.Chart) }
    NavStackHost(stack) { route ->
        when (route) {
            SkyRoute.Chart -> SkyChartRoot(onDetails = { stack.push(SkyRoute.Detail(it)) })
            is SkyRoute.Detail -> ObjectDetailScreen(route.obj, onBack = { stack.pop() })
        }
    }
}

private sealed interface SkyRoute {
    data object Chart : SkyRoute

    data class Detail(val obj: CelestialObject) : SkyRoute
}

/** The chart options that belong to this screen alone (not shared with other screens' settings). Kept for the session. */
@Stable
internal class SkyChartPreferences {
    /** Turns the chart so that the camera heading is up. */
    var rotatesToHeading by mutableStateOf(false)
    var showsStarNames by mutableStateOf(true)
    var showsAltitudeGrid by mutableStateOf(true)
    var showsCameraView by mutableStateOf(true)
}

/**
 * The chart screen: the top bar, the chart with its time bar (or the request for a location), the layers menu over
 * both, and the date sheet. The chart model and the layer options are retained, so the chart comes back as it was left.
 */
@Composable
private fun SkyChartRoot(onDetails: (CelestialObject) -> Unit) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val model = rememberRetained("sky.model") { SkyChartModel() }
    val preferences = rememberRetained("sky.preferences") { SkyChartPreferences() }
    val mosaic = rememberRetained(MosaicSession.KEY) { MosaicSession() }
    var showsLayers by remember { mutableStateOf(false) }
    var showsDateSheet by remember { mutableStateOf(false) }
    val observer = app.observerContext

    // The scenes are built for as long as this screen is shown, for the newest inputs.
    LaunchedEffect(model) { model.runBuilds() }
    LaunchedEffect(model, app) {
        snapshotFlow { inputs(app, mosaic) }.collect { current ->
            val catalog = app.catalog.catalog
            if (current != null && catalog != null) model.update(catalog, current)
        }
    }
    BackHandler(enabled = showsLayers) { showsLayers = false }

    Column(Modifier.fillMaxSize().screenBackground(palette)) {
        TopBar(model, preferences, showsLayers, onToggleLayers = { showsLayers = !showsLayers })
        if (observer != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ChartArea(app, model, preferences, mosaic, observer.timeZone, onDetails)
                    }
                    TimeTravelBar(
                        observer.timeZone,
                        onChooseDate = { showsDateSheet = true },
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    )
                }
                LayersMenu(app, preferences, showsLayers, onDismiss = { showsLayers = false })
            }
        } else {
            NeedsLocationView(Modifier.weight(1f))
        }
    }
    if (showsDateSheet) {
        SkyChartDateSheet(observer?.timeZone ?: ZoneId.systemDefault(), onDismiss = { showsDateSheet = false })
    }
}

/**
 * What the scene depends on, or `null` while there is no observer. The chart's instant is rounded, so the 1 Hz clock does
 * not rebuild it every second.
 */
private fun inputs(app: AppModel, mosaic: MosaicSession): SkyChartInputs? {
    val observer = app.observerContext ?: return null
    val settings = app.settings
    val options = SkySnapshotOptions(
        starMagnitudeLimit = settings.starMagnitudeLimit,
        includeConstellations = settings.showConstellationLines || settings.showConstellationNames,
        includeDeepSky = settings.showDeepSkyObjects,
        includeMilkyWay = settings.showMilkyWay,
        deepSkyProminence = Prominence.fromRawValue(settings.deepSkyProminence) ?: Prominence.PROMINENT,
    )
    return SkyChartInputs(
        observer = observer.observer,
        date = SkyChartModel.bucketed(app.clock.displayDate),
        options = options,
        target = app.target,
        horizon = observer.horizon,
        mosaic = mosaic.plan,
        isCatalogLoaded = app.catalog.catalog != null,
    )
}

/** The chart, or why there is none yet: the sky catalog failed to load, or the first scene is still being built. */
@Composable
private fun ChartArea(
    app: AppModel,
    model: SkyChartModel,
    preferences: SkyChartPreferences,
    mosaic: MosaicSession,
    timeZone: ZoneId,
    onDetails: (CelestialObject) -> Unit,
) {
    val palette = LocalPalette.current
    val content = model.content
    val loadState = app.catalog.loadState
    val settings = app.settings
    when {
        content != null -> SkyChartSurface(
            model = model,
            content = content,
            layers = SkyChartLayerOptions(
                constellationLines = settings.showConstellationLines,
                constellationNames = settings.showConstellationNames,
                milkyWay = settings.showMilkyWay,
                deepSkyObjects = settings.showDeepSkyObjects,
                starNames = preferences.showsStarNames,
                altitudeGrid = preferences.showsAltitudeGrid,
                cameraView = preferences.showsCameraView,
            ),
            rotatesToHeading = preferences.rotatesToHeading,
            mosaic = mosaic,
            timeZone = timeZone,
            onDetails = onDetails,
        )
        loadState is CatalogService.LoadState.Failed ->
            EmptyStateView("Sky Catalog Unavailable", Icons.Outlined.WarningAmber, loadState.message)
        else -> Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            CircularProgressIndicator(color = palette.accent)
            Text("Preparing the sky…", color = palette.secondaryText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The menu that shows and hides layers, over a transparent backdrop that dismisses it. */
@Composable
private fun LayersMenu(app: AppModel, preferences: SkyChartPreferences, isShown: Boolean, onDismiss: () -> Unit) {
    // The menu grows out of the layers button at the top right.
    val origin = TransformOrigin(1f, 0f)
    AnimatedVisibility(
        visible = isShown,
        enter = fadeIn(tween(MENU_MILLIS)) + scaleIn(tween(MENU_MILLIS), initialScale = 0.96f, transformOrigin = origin),
        exit = fadeOut(tween(MENU_MILLIS)) + scaleOut(tween(MENU_MILLIS), targetScale = 0.96f, transformOrigin = origin),
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
            SkyChartLayersMenu(
                store = app.settingsStore,
                preferences = preferences,
                onDismiss = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 12.dp),
            )
        }
    }
}

private const val MENU_MILLIS = 150

// Top bar

/**
 * The title and the three actions: follow the camera heading, reset the zoom, and show the layers. The buttons dim by
 * tint rather than by being disabled, which would draw them in grey.
 */
@Composable
private fun TopBar(
    model: SkyChartModel,
    preferences: SkyChartPreferences,
    showsLayers: Boolean,
    onToggleLayers: () -> Unit,
) {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val canFollowHeading = app.motion.status != MotionStatus.Unavailable
    val headingTint = if (canFollowHeading) palette.accent else palette.tertiaryText
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Sky Chart",
            Modifier.weight(1f).padding(start = 12.dp).semantics { heading() },
            color = palette.primaryText,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconToggleButton(
            checked = preferences.rotatesToHeading,
            onCheckedChange = { if (canFollowHeading) preferences.rotatesToHeading = it },
            modifier = Modifier.semantics {
                contentDescription = "Rotate chart to camera heading"
                stateDescription = headingToggleValue(canFollowHeading, preferences.rotatesToHeading)
                role = Role.Switch
            },
            colors = IconButtonDefaults.iconToggleButtonColors(contentColor = headingTint, checkedContentColor = headingTint),
        ) {
            val icon = if (preferences.rotatesToHeading) Icons.Filled.Explore else Icons.Outlined.Explore
            Icon(icon, contentDescription = null)
        }
        ResetZoomButton(model)
        IconButton(
            onClick = onToggleLayers,
            modifier = Modifier.semantics { stateDescription = if (showsLayers) "Shown" else "Hidden" },
            colors = IconButtonDefaults.iconButtonColors(contentColor = palette.accent),
        ) {
            Icon(Icons.Outlined.Layers, contentDescription = "Layers")
        }
    }
}

private fun headingToggleValue(canFollow: Boolean, isOn: Boolean): String = when {
    !canFollow -> "Unavailable without motion sensors"
    isOn -> "On"
    else -> "Off"
}

/**
 * Puts the chart back at zoom 1 and centred. A composable of its own, so that the top bar does not recompose with every
 * step of a pinch or drag, which this button follows.
 */
@Composable
private fun ResetZoomButton(model: SkyChartModel) {
    val palette = LocalPalette.current
    val isZoomed = model.zoom != 1f || model.pan != Offset.Zero
    IconButton(
        onClick = model::resetViewport,
        colors = IconButtonDefaults.iconButtonColors(contentColor = if (isZoomed) palette.accent else palette.tertiaryText),
    ) {
        Icon(Icons.Outlined.ZoomOutMap, contentDescription = "Reset zoom")
    }
}

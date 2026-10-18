package net.kuecker.lodestar.features.settings

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import android.content.Context
import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import net.kuecker.lodestar.kit.foundation.AstroMath
import net.kuecker.lodestar.kit.foundation.GeographicLocation
import net.kuecker.lodestar.shared.LodestarButton
import net.kuecker.lodestar.shared.monospacedDigits
import net.kuecker.lodestar.theme.LocalPalette
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.AttributionDialogManager
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import kotlin.math.ln

/** OpenFreeMap's light style, used by day. */
private const val DAY_STYLE = "https://tiles.openfreemap.org/styles/liberty"

/** OpenFreeMap's dark style, used at night. */
private const val NIGHT_STYLE = "https://tiles.openfreemap.org/styles/dark"

/** Latitude/longitude span of the initial view around a chosen position (about 55 km). */
private const val SITE_SPAN = 0.5

/** Span of the initial view around the fallback centre (a region). */
private const val REGION_SPAN = 8.0

/** Latitude beyond which the initial view is not centred, in degrees. */
private const val POLAR_LIMIT = 80.0

/** The zoom of the whole-world view, used when there is neither a pin nor a fallback centre. */
private const val WORLD_ZOOM = 1.0

/** MapLibre draws the world 512 density-independent pixels wide at zoom 0. */
private const val WORLD_WIDTH_AT_ZOOM_ZERO = 512.0

/** The smallest zoom the map allows: about the whole world across the screen. */
private const val MINIMUM_ZOOM = 1.0

/** The picker's window draws behind the system bars and the display cutout; its bars keep clear of them. */
private val topInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)

private val bottomInsets: WindowInsets
    @Composable get() = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)

/** Dark red laid over the map at night; 12 % of the original colours still show through. */
private val nightScrim = Color(red = 0.55f, green = 0f, blue = 0f, alpha = 0.88f)

private data class MapCredit(val title: String, val url: String)

/** The parties the OpenFreeMap styles ask to be credited, shown behind the map's ⓘ button. */
private val mapCredits = listOf(
    MapCredit("OpenFreeMap", "https://openfreemap.org"),
    MapCredit("© OpenMapTiles", "https://openmaptiles.org"),
    MapCredit("Data from OpenStreetMap contributors", "https://www.openstreetmap.org/copyright"),
    MapCredit("© MapLibre", "https://maplibre.org"),
)

/**
 * A full-screen map on which the user taps to drop a pin for a new observing site. It is the only screen that loads
 * network data besides the time zone lookup: OpenFreeMap tiles, drawn by MapLibre.
 *
 * In night mode the map, which draws its own colours, uses the dark style and is covered by a dark-red scrim; the pin,
 * the readout and the button use the palette and sit above it. The pin is only a convenience: the site editor also
 * takes typed coordinates. It is shown in its own dialog window because a map inside the editor's bottom sheet would
 * fight the sheet over vertical drags.
 *
 * @param initialLocation The pin to start with, if any; the map opens around it.
 * @param fallbackCenter Where to open the map when there is no pin, e.g. the current observer.
 * @param onSelect Called with the pinned position when the user confirms.
 * @param onClose Called when the picker should go away (after a confirmation, or back).
 */
@Composable
internal fun MapSitePicker(
    initialLocation: GeographicLocation?,
    fallbackCenter: GeographicLocation?,
    onSelect: (GeographicLocation) -> Unit,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MapSitePickerContent(initialLocation, fallbackCenter, onSelect, onClose)
    }
}

@Composable
private fun MapSitePickerContent(
    initialLocation: GeographicLocation?,
    fallbackCenter: GeographicLocation?,
    onSelect: (GeographicLocation) -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalPalette.current
    val widthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp().value.toDouble() }
    val camera = remember { initialCamera(initialLocation, fallbackCenter, widthDp) }
    var selection by remember { mutableStateOf(initialLocation) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var loadFailed by remember { mutableStateOf(false) }
    var showsAttribution by remember { mutableStateOf(false) }

    HideSystemBarsAtNight(palette.isNight)
    Column(Modifier.fillMaxSize().background(palette.background)) {
        SettingsTopBar("Choose Site", onBack = onClose, modifier = Modifier.windowInsetsPadding(topInsets))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Map. Tap to place the site. You can also type its coordinates in the editor."
                },
        ) {
            SiteMapView(
                styleUrl = if (palette.isNight) NIGHT_STYLE else DAY_STYLE,
                // The icon must stand out from the style's own colours: light on the dark style, dark on the light one.
                attributionTint = (if (palette.isNight) palette.primaryText else palette.background).toArgb(),
                camera = camera,
                onMapReady = { map = it },
                onTap = { tapped -> GeographicLocation.validated(tapped.latitude, tapped.longitude)?.let { selection = it } },
                onCameraMoved = { cameraTick += 1 },
                onLoadFailed = { loadFailed = it },
                onAttributionClick = { showsAttribution = true },
                modifier = Modifier.fillMaxSize(),
            )
            if (palette.isNight) {
                // No pointer handling: touches reach the map beneath.
                Box(Modifier.fillMaxSize().background(nightScrim))
            }
            SelectionMarker(map = map, selection = selection, cameraTick = { cameraTick }, modifier = Modifier.fillMaxSize())
        }
        ConfirmationBar(
            selection = selection,
            loadFailed = loadFailed,
            onConfirm = {
                selection?.let(onSelect)
                onClose()
            },
        )
    }
    if (showsAttribution) AttributionDialog(onDismiss = { showsAttribution = false })
}

/** The coordinates of the pin and the button that takes them over. */
@Composable
private fun ConfirmationBar(selection: GeographicLocation?, loadFailed: Boolean, onConfirm: () -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().background(palette.surface).windowInsetsPadding(bottomInsets).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (loadFailed) {
            Text(
                "The map could not be loaded, perhaps because there is no network. You can type the coordinates instead.",
                color = palette.warning,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            selection?.let(SettingsFormat::coordinates) ?: "Tap the map to drop a pin.",
            color = palette.primaryText,
            style = MaterialTheme.typography.bodyMedium.monospacedDigits(),
        )
        LodestarButton("Use This Location", onClick = onConfirm, modifier = Modifier.fillMaxWidth(), enabled = selection != null)
    }
}

/**
 * The pin: a ring around a dot in the palette's colours, projected from the selected position every time the camera
 * moves ([cameraTick] changes). It is drawn by Compose above the night scrim so it stays visible at night.
 */
@Composable
private fun SelectionMarker(map: MapLibreMap?, selection: GeographicLocation?, cameraTick: () -> Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier) {
        cameraTick() // reading the tick makes the next camera move redraw the pin
        val point = selection?.let { map?.projection?.toScreenLocation(LatLng(it.latitude, it.longitude)) } ?: return@Canvas
        val center = Offset(point.x, point.y)
        drawCircle(palette.background, radius = 12.dp.toPx(), center = center)
        drawCircle(palette.accent, radius = 9.dp.toPx(), center = center)
        drawCircle(palette.background, radius = 3.dp.toPx(), center = center)
    }
}

/**
 * The MapLibre map for [styleUrl], opened on [camera]. Tapping reports the coordinate; the camera is reported while
 * it moves so overlays can follow. MapLibre's own attribution button stays visible, drawn in [attributionTint];
 * [onAttributionClick] replaces its stock dialog, which is bright at night. On a phone that cannot load MapLibre's
 * native library nothing is shown and [onLoadFailed] says so, so the site can still be typed.
 */
@Composable
private fun SiteMapView(
    styleUrl: String,
    attributionTint: Int,
    camera: CameraPosition,
    onMapReady: (MapLibreMap) -> Unit,
    onTap: (LatLng) -> Unit,
    onCameraMoved: () -> Unit,
    onLoadFailed: (Boolean) -> Unit,
    onAttributionClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { createMapView(context, camera) }
    if (mapView == null) {
        LaunchedEffect(Unit) { onLoadFailed(true) }
        return
    }
    LoadedMap(
        mapView, styleUrl, attributionTint, onMapReady, onTap, onCameraMoved, onLoadFailed, onAttributionClick, modifier,
    )
}

/** Creates the map view, or `null` when MapLibre's native library cannot be loaded on this phone. */
private fun createMapView(context: Context, camera: CameraPosition): MapView? =
    try {
        MapLibre.getInstance(context)
        // A texture view composes with the overlays above it (the night scrim must cover every pixel).
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(camera)
            .textureMode(true)
            .logoEnabled(false)
            .tiltGesturesEnabled(false)
        MapView(context, options)
    } catch (_: LinkageError) {
        null // e.g. an UnsatisfiedLinkError: no build of the library for this phone's processor
    }

@Composable
private fun LoadedMap(
    mapView: MapView,
    styleUrl: String,
    attributionTint: Int,
    onMapReady: (MapLibreMap) -> Unit,
    onTap: (LatLng) -> Unit,
    onCameraMoved: () -> Unit,
    onLoadFailed: (Boolean) -> Unit,
    onAttributionClick: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnMapReady by rememberUpdatedState(onMapReady)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnCameraMoved by rememberUpdatedState(onCameraMoved)
    val currentOnLoadFailed by rememberUpdatedState(onLoadFailed)
    val currentOnAttributionClick by rememberUpdatedState(onAttributionClick)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(mapView, lifecycle) {
        val bridge = MapLifecycleBridge(mapView)
        lifecycle.addObserver(bridge)
        mapView.addOnDidFinishLoadingStyleListener {
            currentOnLoadFailed(false)
            currentOnCameraMoved()
        }
        mapView.addOnDidFailLoadingMapListener { currentOnLoadFailed(true) }
        // The first projection may happen before the map knows its size; this one comes when it has settled.
        mapView.addOnDidBecomeIdleListener { currentOnCameraMoved() }
        mapView.getMapAsync { ready ->
            ready.setMinZoomPreference(MINIMUM_ZOOM)
            ready.addOnMapClickListener { tapped ->
                currentOnTap(tapped.wrap())
                true
            }
            ready.addOnCameraMoveListener { currentOnCameraMoved() }
            ready.addOnCameraIdleListener { currentOnCameraMoved() }
            ready.uiSettings.setAttributionDialogManager(
                object : AttributionDialogManager(context, ready) {
                    override fun onClick(view: View) = currentOnAttributionClick()
                },
            )
            map = ready
            currentOnMapReady(ready)
        }
        onDispose {
            lifecycle.removeObserver(bridge)
            bridge.release()
        }
    }
    // The style loads when the map is ready and again when day turns to night.
    LaunchedEffect(map, styleUrl) { map?.setStyle(styleUrl) }
    // The theme's primary colour, which the SDK would use, is not one this app defines.
    LaunchedEffect(map, attributionTint) { map?.uiSettings?.setAttributionTintColor(attributionTint) }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Forwards the host's lifecycle to a [MapView], which must be told about every step in order. It keeps track of how
 * far the map got, so joining a lifecycle that is already running and leaving it early both call the right methods.
 */
private class MapLifecycleBridge(private val mapView: MapView) : LifecycleEventObserver {
    private enum class Stage { NEW, CREATED, STARTED, RESUMED, DESTROYED }

    private var stage = Stage.NEW

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> create()
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            Lifecycle.Event.ON_DESTROY -> release()
            Lifecycle.Event.ON_ANY -> Unit
        }
    }

    /** Takes the map down through pause, stop and destroy, whatever stage it had reached. */
    fun release() {
        pause()
        stop()
        if (stage != Stage.DESTROYED) {
            mapView.onDestroy()
            stage = Stage.DESTROYED
        }
    }

    private fun create() {
        if (stage == Stage.NEW) {
            mapView.onCreate(null) // nothing to restore: the picker starts afresh every time
            stage = Stage.CREATED
        }
    }

    private fun start() {
        create()
        if (stage == Stage.CREATED) {
            mapView.onStart()
            stage = Stage.STARTED
        }
    }

    private fun resume() {
        start()
        if (stage == Stage.STARTED) {
            mapView.onResume()
            stage = Stage.RESUMED
        }
    }

    private fun pause() {
        if (stage == Stage.RESUMED) {
            mapView.onPause()
            stage = Stage.STARTED
        }
    }

    private fun stop() {
        if (stage == Stage.STARTED) {
            mapView.onStop()
            stage = Stage.CREATED
        }
    }
}

/** The credits behind the map's ⓘ button, as a palette-coloured dialog with links. */
@Composable
private fun AttributionDialog(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surfaceRaised,
        titleContentColor = palette.primaryText,
        textContentColor = palette.secondaryText,
        title = { Text("Map attribution") },
        text = {
            Column {
                for (credit in mapCredits) {
                    Text(
                        credit.title,
                        color = palette.accent,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                try {
                                    uriHandler.openUri(credit.url)
                                } catch (_: IllegalArgumentException) {
                                    // No app on the phone can open web links; the credit text itself is shown.
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = palette.accent)) {
                Text("Close")
            }
        },
    )
}

/**
 * Hides the system bars of the picker's dialog window at night, as the app does for its own window: they are white.
 * The dialog is a window of its own, so the activity's setting does not reach it.
 */
@Composable
private fun HideSystemBarsAtNight(isNight: Boolean) {
    val view = LocalView.current
    LaunchedEffect(view, isNight) {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isNight) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

/**
 * The view to open the map on: a few tens of kilometres around the pin, a region around the fallback centre, or the
 * whole world. The centre stays clear of the poles, where the map projection stops. [widthDp] is the width of the
 * map in density-independent pixels, which decides the zoom for a span of degrees.
 */
private fun initialCamera(initial: GeographicLocation?, fallback: GeographicLocation?, widthDp: Double): CameraPosition {
    val (center, span) = when {
        initial != null -> initial to SITE_SPAN
        fallback != null -> fallback to REGION_SPAN
        else -> return CameraPosition.Builder().target(LatLng(20.0, 0.0)).zoom(WORLD_ZOOM).build()
    }
    val latitude = AstroMath.clamp(center.latitude, -POLAR_LIMIT, POLAR_LIMIT)
    val zoom = (ln(360.0 * widthDp / (WORLD_WIDTH_AT_ZOOM_ZERO * span)) / ln(2.0)).coerceIn(MINIMUM_ZOOM, 20.0)
    return CameraPosition.Builder().target(LatLng(latitude, center.longitude)).zoom(zoom).build()
}

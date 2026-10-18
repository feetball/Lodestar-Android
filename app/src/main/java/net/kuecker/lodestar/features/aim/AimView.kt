package net.kuecker.lodestar.features.aim

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import net.kuecker.lodestar.app.AppTab
import net.kuecker.lodestar.app.ScreenshotScreen
import net.kuecker.lodestar.app.ScreenshotScreenEffect
import net.kuecker.lodestar.features.mosaic.MosaicPlanSheet
import net.kuecker.lodestar.features.mosaic.awaitScreenshotAim
import net.kuecker.lodestar.features.mount.MountAimSheets
import net.kuecker.lodestar.services.MotionStatus
import net.kuecker.lodestar.shared.EmptyStateView
import net.kuecker.lodestar.shared.LocalAppModel
import net.kuecker.lodestar.shared.NeedsLocationView
import net.kuecker.lodestar.shared.rememberRetained
import net.kuecker.lodestar.theme.LocalPalette
import net.kuecker.lodestar.theme.screenBackground

/** The device-local preferences file (the same one the app model keeps its device-only state in). */
private const val LOCAL_PREFERENCES = "lodestar.local"

/**
 * The Aim tab: the live sky over the camera picture with guidance to the chosen target.
 *
 * Layers, back to front: the camera picture (when wanted), the sky overlay, the arrow around the reticle, and the
 * heads-up display. The sky and the picture run edge to edge; the display keeps clear of cutouts and the tab bar.
 *
 * This is the owner of the view model and the sheets, and starts the background work that belongs to the screen:
 * the sensors, the timelines and the audio run only while this tab is showing and the app is in front. The view
 * model outlives the tab (it is kept with the app model), so coming back finds the sky as it was.
 */
@Composable
fun AimScreen() {
    val app = LocalAppModel.current
    val palette = LocalPalette.current
    val context = LocalContext.current
    val model = rememberRetained("aim.model") {
        AimViewModel(app, context.applicationContext.getSharedPreferences(LOCAL_PREFERENCES, Context.MODE_PRIVATE))
    }
    var sheet by remember { mutableStateOf<AimSheet?>(null) }

    val isActive = app.selectedTab == AppTab.AIM && app.isForeground
    val configuration = AimConfiguration(app)
    val skyInputs = model.skyInputs(configuration)

    SideEffect { model.apply(configuration) }
    LaunchedEffect(model.guidanceTarget?.id) { model.syncTarget() }
    // The background work restarts whenever its inputs change and stops while the screen is not in use.
    LaunchedEffect(skyInputs, isActive) {
        if (isActive) model.keepSkyCurrent(skyInputs)
    }
    LaunchedEffect(skyInputs, isActive) {
        if (isActive) model.keepRiseInfoCurrent(skyInputs)
    }
    // A running mosaic leads guidance from panel to panel: the panels' places are refreshed every second, and the
    // guidance follows the panel being shot at once.
    val mosaicPlan = model.mosaic.plan
    val observer = app.observerContext?.observer
    LaunchedEffect(mosaicPlan, observer, isActive) {
        if (isActive) model.keepMosaicCurrent(mosaicPlan, observer)
    }
    LaunchedEffect(mosaicPlan, model.mosaic.step) { model.syncMosaic() }
    LaunchedEffect(model.satellite.source, observer) { model.syncSatellite() }
    LaunchedEffect(isActive) {
        if (isActive) model.runLoop() else model.stopFeedback()
    }
    DisposableEffect(model) {
        onDispose { model.stopFeedback() }
    }

    Box(Modifier.fillMaxSize().screenBackground(palette)) {
        when {
            app.observerContext == null -> NeedsLocationView()
            app.motion.status == MotionStatus.Unavailable -> EmptyStateView(
                title = "Motion Sensors Needed",
                icon = Icons.Outlined.Sensors,
                message = "Aiming reads the gyroscope, accelerometer and compass of your phone, and this device does " +
                    "not have the sensors it needs. Objects, the sky chart and tonight's plan work everywhere.",
            )
            else -> AimStage(model, onSheet = { sheet = it }, isActive = isActive)
        }
    }
    MountAimSheets(target = model.guidanceTarget)
    ScreenshotScreenEffect(ScreenshotScreen.MOSAIC) {
        awaitScreenshotAim(app)
        sheet = AimSheet.MOSAIC
    }
    when (sheet) {
        AimSheet.TARGET_PICKER -> AimTargetPickerSheet(model, onDismiss = { sheet = null })
        AimSheet.ALIGNMENT -> AlignmentSheet(model, onDismiss = { sheet = null })
        AimSheet.MOSAIC -> MosaicPlanSheet(
            session = model.mosaic,
            target = model.guidanceTarget,
            cameraDirection = { model.engine.frame()?.pointing?.boresight },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

/** The layered screen itself. */
@Composable
private fun AimStage(model: AimViewModel, onSheet: (AimSheet) -> Unit, isActive: Boolean) {
    val palette = LocalPalette.current
    Box(Modifier.fillMaxSize().pointerInput(model) { detectZoom(model) }) {
        Box(Modifier.fillMaxSize().background(palette.background)) {
            CameraPreviewView(isWanted = model.configuration.wantsCameraPreview)
            SkyOverlayView(model, isActive, Modifier.fillMaxSize())
            GuidanceArrowView(model, isActive)
        }
        GuidanceHUD(model, onSheet, isActive)
        AimAccessibilitySummary(model, isActive)
    }
}

/**
 * Pinching zooms the virtual sky; the camera picture has a fixed field of view. Watches the touches without taking
 * them, so the controls keep working.
 */
private suspend fun PointerInputScope.detectZoom(model: AimViewModel) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var magnification = 1.0
        var hasPinched = false
        do {
            val event = awaitPointerEvent()
            val zoom = event.calculateZoom()
            if (zoom != 1f) {
                hasPinched = true
                magnification *= zoom
                if (model.cameraGeometry == null) model.updatePinch(magnification)
            }
        } while (event.changes.any { it.pressed })
        if (hasPinched) model.endPinch()
    }
}
